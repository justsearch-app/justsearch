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
  "resumeToken": "c3VyZmFjZTpoZWFsdGgtZXZlbnRzOjQy"
}
```

Field semantics:

- `streamId` — kind-prefixed slug identifying the stream. Stable
  across reconnects.
- `frameKind` — top-level discriminator (see below).
- `seq` — monotonic per-stream sequence number. Starts at 1 on the
  first frame after stream registration. Gaps may occur across
  server restarts (consumers detect via the `reset` lifecycle).
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
    followed by a fresh `snapshot`.
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

1. **Replays in-window UPDATE frames**: when the token's `streamId`
   matches and its `seq` lies within the channel's ring-buffer
   window (`oldest <= sinceSeq <= currentSeq`), the controller
   forwards each retained UPDATE frame with `seq > sinceSeq`. The
   consumer is brought up to date; no fresh snapshot is emitted.
2. **Emits `reset` + fresh `snapshot`**: when the token is malformed,
   from a different stream, from a future / different server
   lifetime (`sinceSeq > currentSeq`), or predates the ring buffer
   (`sinceSeq < oldest`, including the empty-buffer-with-positive-
   sinceSeq case).

Per slice 436 §B.B Fix B, the empty-buffer guard is essential: a
client whose token references seq from a previous server lifetime
must not receive a false-positive "you're up to date" response when
the new server's buffer is empty.

Case 1 is **atomic** for single-channel attachment: the window check, the
replay snapshot and the listener registration share the channel's short
publication boundary (`SseStreamChannel.subscribeAndReplay`), so a frame broadcast mid-attach
reaches the client either through the replay or through the live
fan-out — never both, never neither. The replay itself is written
**outside** the lock. The listener retains one bounded serial delivery queue
after replay, so a
slow-but-alive reattacher cannot stall publishers behind its socket.

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
`(streamId, seq)` tuple internally; consumers MUST NOT parse it).
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
- A listener set (`Set<Consumer<SseEnvelope>>`).
- A `ReentrantReadWriteLock` guarding the publish-vs-subscribe
  boundary only (the ring keeps its own monitor). `publish` takes the
  read lock once per frame; `subscribeAndReplay` takes the write lock
  once per connection.

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
  evidence first in seq order, then the narrative tail.
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

1. Emit `connected` lifecycle.
2. Read `?since=<token>` from `client.ctx()` (null-safe).
3. `attemptResumeAndSubscribe(token)` — replays AND subscribes
   atomically; on miss (nothing replayed, no listener registered)
   emit `reset`.
4. If not replayed, build snapshot via the supplied supplier and
   emit `snapshot` lifecycle.
5. Subscribe to channel for live UPDATE forwarding — only when step 3
   did not already do so.
6. Schedule heartbeat at the supplied cadence.
7. Register `onClose` to unsubscribe + cancel heartbeat.
8. Call `client.keepAlive()`.

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

## Known limitation: snapshot-vs-subscribe race (no-cursor path only)

On a **fresh connect with no `?since=`** — 17 of the 18 production
routes' normal case — the snapshot is built from a caller-supplied
supplier and only then is `channel.subscribe()` called, so a
broadcast in between can be missed. The window is small
(single-thread function call) but real.

This window stays open deliberately (tempdoc 834 §1.3.1): closing it
means invoking `snapshotExtras.get()` under the channel monitor —
lock inversion across 18 controllers, each free to take its own locks
inside that supplier. A catalog self-corrects at its next snapshot,
so the cost of the race is bounded there.

The **resume path is no longer affected** — see "Resume semantics"
case 1. A stream that cannot tolerate a dropped frame (a run stream,
whose lost `chunk` yields a permanently corrupted answer) therefore
makes "absent cursor ⇒ replay from 0" a protocol requirement and
never takes the no-cursor path.

## Cross-references

- `../../../../decisions/0037-universal-sse-envelope.md` — decision rationale.
- `slices/436-streaming-envelope.md` — implementation spec + §B
  appendixes.
- `../../../../decisions/0036-fe-resource-category.md` — typed Resource Category
  substrate; SSE_STREAM Resources adopt the envelope by default.
- `30-agent-workflows/01b-add-event-stream-resource.md` and
  `01c` / `01e` — per-Category recipes; each has a "Wire Format"
  subsection cross-referencing this doc.
