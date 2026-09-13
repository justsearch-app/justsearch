---
title: "Frontend kernel — streaming envelope"
type: reference
status: stable
description: "The universal multi-frame SSE envelope contract (initial snapshot + delta frames)."
date: 2026-06-09
---

# Frontend kernel — streaming envelope

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.


Every framework SSE endpoint adopts the universal envelope. New SSE
Resources adopt it from day one; the four shipped Resources
(`core.health-events`, `core.runtime-context`, `core.server-capabilities`,
`core.operation-history`) all use it.

This doc is the wire-format reference. The decision rationale lives in
`../../../../decisions/0037-universal-sse-envelope.md`. The implementation history
lives in `slices/436-streaming-envelope.md`.

## Wire format

Every frame is a single JSON object as the SSE `data:` payload. The
SSE event name is constant `"frame"`. Consumers route by
`envelope.frameKind` and (for lifecycle frames) the nested
`payload.kind`.

```json
{
  "streamId": "surface:health-events",
  "frameKind": "UPDATE",
  "seq": 42,
  "ts": "2026-05-05T12:34:56.789Z",
  "payload": { /* frame-specific shape */ },
  "resumeToken": "<opaque-source-checkpoint>"
}
```

Field semantics:

- `streamId` — kind-prefixed slug identifying the stream. Stable
  across reconnects.
- `frameKind` — top-level discriminator (see below).
- `seq` — source-allocated sequence, starting at 1. Replayed updates retain their original
  sequence and can follow a newer control-frame sequence; this is not a delivery checkpoint.
- `ts` — server-side wall-clock timestamp of frame emission, ISO-8601
  UTC.
- `payload` — frame-specific data. UPDATE frames carry the
  controller-defined wire shape. LIFECYCLE frames carry at minimum
  `{kind: <subkind>}` plus per-subkind extras.
- `resumeToken` — opaque server-encoded cursor. Consumers send it as
  `?since=<resumeToken>` on reconnect.

## Frame kinds

`frameKind` is the top-level discriminator:

- `UPDATE` — data frame. Broadcasts to all subscribed clients.
  Carries the controller-defined payload shape (e.g.,
  `HealthEventChangeRegistry.HealthDelta`). Retained in the per-
  stream ring buffer for resume.
- `LIFECYCLE` — connection-management frame. Per-connection (not
  shared); not retained in the ring buffer. The lifecycle subkind
  is encoded in `payload.kind`:
  - `connected` — emitted once per connection on subscribe.
  - `snapshot` — carries the controller-supplied initial-state
    payload via `{kind: "snapshot", ...extras}`. Emitted on subscribe
    when no resume token was supplied (or after a `reset`).
  - `heartbeat` — emitted on the heartbeat scheduler tick.
  - `reset` — signals the consumer should discard cached state.
    Emitted when a resume attempt falls outside the window. Always
    followed by a fresh `snapshot` on stateful streams; event-only streams resume live delivery.
  - `closing` — emitted once during graceful shutdown.

## StreamId

Stream identifiers are kind-prefixed slugs of the form
`<kind>:<id>` where:

- `<kind>` ∈ `{registry, surface, system, run}`:
  - `registry` — catalog-shaped streams (e.g.,
    `registry:capabilities`).
  - `surface` — UI-rendered surfaces (e.g., `surface:health-events`,
    `surface:operation-history`).
  - `system` — system-level streams (e.g., `system:runtime-context`).
  - `run` — per-run observation streams (e.g., `run:run-4f3c9a10`).
    Unlike the other three, which are process-lifetime singletons one
    per catalog, a `run` stream is per-instance and N-at-a-time. The
    kind is accepted by the substrate as of tempdoc 834 S3a; the run
    channels that use it land in S3b.
- `<id>` matches `[a-z][a-z0-9-]*` — letter-initial for every kind,
  which is why run ids are minted `run-<uuid>` rather than a bare
  UUID (a UUID may start with a digit).

Validation lives in `StreamId.PATTERN`
(`modules/app-api/.../stream/StreamId.java`), mirrored on the wire by
`stream.proto`'s `stream_id` pattern constraint.

## Resume semantics

A consumer may include `?since=<resumeToken>` on reconnect. The
controller decodes the token and either:

1. **Replays covered UPDATE frames** when the token matches the stream and its channel
   incarnation, its sequence is no greater than the current source sequence, and it covers
   the channel's highest discarded-update sequence. Forward retained updates after that
   checkpoint, without a new snapshot.
2. **Emits reset and a fresh snapshot** when decoding, incarnation or coverage validation fails.
   Legacy tokens lack an incarnation and reset. A positive cursor with an empty UPDATE ring
   is valid for its own incarnation when no update was discarded. Event-only streams reset
   without a snapshot.

Both standalone and multiplexed attachment validate the cursor, capture replay and register
buffered delivery under one short source lock. Source queries, transport ownership callbacks
and socket writes run outside that lock. A listener keeps its bounded serial queue after replay;
overflow retires it and closes its physical connection, releasing sibling subscriptions and
heartbeat. Ownership is installed before initial replay can block.

Lifecycle resume tokens describe successfully delivered state. Connected retains a valid
requested checkpoint; reset clears it. Snapshot acknowledges the source boundary captured
before its query. Heartbeat and closing retain the last delivered update/snapshot checkpoint,
even if other clients have since allocated newer control sequences. Consumers must treat tokens
as opaque acknowledgements rather than deriving one from a frame's sequence.

Frontend reducers acknowledge a frame's resume token only after applying the frame.
If a reducer throws, EnvelopeStream preserves the prior payload, clears its checkpoint,
marks the connection disconnected and detaches the source before notifying listeners.
Its existing reconnect/backoff owner opens a fresh connection without that token;
late frames from the detached source cannot advance it. If a listener reopens the stream
during notification, a later failure
still detaches that replacement source while reusing the single pending reconnect timer.
The observed sequence remains available for diagnostics and is not a delivery acknowledgement. On a multiplexed
connection, only the failed logical stream loses its token; unaffected streams retain
their checkpoints in the reconnect bundle.

`resumeToken` is opaque on the wire (base64-URL-encoded
`(streamId, seq, incarnation)` tuple internally; consumers MUST NOT parse it).
The opacity is contractual so the encoding can change without a
protocol break.

## Heartbeat policy

Default cadence is 30 seconds (per the original spec). The four
shipped controllers override to 15 seconds — tighter heartbeats are
fine; loosening would shrink the FE's connection-dead-detection
budget.

Heartbeat frames are LIFECYCLE-kind broadcasts originating from
each controller's per-connection scheduler. They consume seqs from
the channel's shared tracker but are NOT retained in the ring
buffer.

## Per-stream coordination

Each `StreamId` has exactly one `SseStreamChannel` instance per
process (one per change-registry). The channel owns:

- A `StreamSequenceTracker` (atomic monotonic counter, starts at 1).
- A `FrameHistoryRingBuffer` (default capacity 9000 frames).
- A listener set with one bounded serial delivery queue per listener.
- A short publication lock covering sequence allocation, ring append,
  enqueue and replay registration. Queries and callbacks run outside it.

### Retention bounds

`FrameRetentionPolicy` carries the buffer's bounds. Every catalog
stream runs on `FrameRetentionPolicy.DEFAULT` — 9000 frames, **no**
byte bound, **no** evidence slot — under which no frame is ever
sized and behaviour is identical to the pre-834 buffer. Two further
axes exist for streams that need them (tempdoc 834 §2, consumed by
the run channels in S3b):

- **`maxBytes`** — a byte bound on the narrative ring, evicting
  oldest-first. A lone frame larger than the whole budget is still
  retained; the buffer degrades to holding that frame, never to
  holding nothing.
- **Evidence slot** — frames a policy's `EvidenceClassifier` keys are
  held in a latest-wins map under their own `maxEvidenceBytes`
  budget instead of the narrative ring, so one large replace-only
  frame cannot evict thousands of narrative frames. Replay returns
  evidence first in seq order, then the narrative tail for numeric run replay.
  Strong envelope replay sorts both tiers by source sequence before advancing checkpoints.
  `oldestSeqOrZero()` answers from the narrative ring whenever it
  holds anything: a stale evidence frame surviving in the slot does
  not make the narrative gap back to its seq replayable.

Byte accounting is an **estimate** — fixed per-frame overhead plus
payload string content (`FrameRetentionSizer`), not wire size.
Tempdoc 834's probe P2 (retained bytes per answer) has not been run,
so the overhead constant is provisional.

Frame discipline:

- `channel.publish(frameKind, payload)` — broadcasts to all
  subscribers. Assigns next seq, wraps in envelope, appends to ring
  if `frameKind == UPDATE`, fans out to listeners.
- `channel.nextEnvelope(frameKind, payload)` — per-client envelope
  construction. Consumes a seq but does NOT append to ring or
  broadcast. Used for lifecycle frames sent only to one connection.

Resume reads `framesSince(sinceSeq)` from the ring; `oldestRetainedSeq()`
reports the buffer's first frame (or 0 when empty).

## Per-connection writer

`SseEnvelopeWriter` is the canonical per-connection helper. The four
shipped controllers each delegate via the static `attach()`
orchestrator:

```java
public void handle(SseClient sseClient) {
  SseEnvelopeWriter.attach(
      sseClient,
      changes.channel(),
      () -> Map.of(/* controller-specific snapshot extras */),
      clock,
      heartbeatScheduler,
      HEARTBEAT_SECONDS);
}
```

The `attach` orchestrator sequences:

1. Install connection cleanup and a pre-created request-completion future.
2. Read `?since=<token>` and attempt a strong atomic resume. A valid attachment acquires
   transport ownership before emitting connected and replaying updates.
3. On a fresh attachment or resume miss, capture a source boundary, query the snapshot outside
   source locks, then validate/register before sending connected, optional reset, and snapshot.
   Retry an expired boundary at most three times; never send an invalid snapshot candidate.
4. Drain buffered live updates after replay and schedule the shared connection heartbeat.
5. On close, delivery retirement or setup failure, release all owned subscriptions, cancel
   heartbeat and complete the request future. Acquisitions racing close are released immediately.

A controller's `handle()` method shrinks to ~5 lines of
delegation; the writer encapsulates the contract uniformly.

## Capability advertisement

The handshake at `/infra/capabilities` advertises:

```json
{
  "serverCapabilities": {
    "streamingEnvelope": { "version": 1 }
  }
}
```

Absence implies a backend that pre-dates the envelope (bespoke per-
endpoint shape). FE consumers feature-detect to fall back gracefully.
A version bump signals a wire-incompatible envelope change; field
additions within v1 are non-breaking per the LSP soft-fail discipline.

## Snapshot and numeric run attachment

Fresh stateful attachment uses the same source-boundary registration path as multiplexed
attachment; an update published during the snapshot read is replayed afterward. The snapshot
may already include that update, so keyed state consumers converge by their declared identity.
Event-only fresh attachment starts at its captured boundary and does not invent a state snapshot.

Run streams retain their separate numeric `sinceSeq` contract: zero attaches to the retained
tail even after eviction, and positive cursors use the run's numeric window checks. The existing
run snapshot primer precedes replay. Strong opaque envelope cursors never use this permissive
numeric-zero fallback.

## Cross-references

- `../../../../decisions/0037-universal-sse-envelope.md` — decision rationale.
- `slices/436-streaming-envelope.md` — implementation spec + §B
  appendixes.
- `../../../../decisions/0036-fe-resource-category.md` — typed Resource Category
  substrate; SSE_STREAM Resources adopt the envelope by default.
- `30-agent-workflows/01b-add-event-stream-resource.md` and
  `01c` / `01e` — per-Category recipes; each has a "Wire Format"
  subsection cross-referencing this doc.
