---
title: "ADR-0037: Universal SSE envelope"
type: decision
status: accepted
description: "Streaming Resources share one multi-frame SSE envelope (initial snapshot frame + subsequent delta frames) rather than per-endpoint ad-hoc stream shapes."
date: 2026-06-09
probes: none - the SSE envelope shape is owned by the wire contract Category and its projection gate.
last_reviewed: 2026-09-02
---


# ADR-0037: Universal SSE envelope

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted. Describes the live SSE Resource transport (capabilities stream, health stream, chat/agent streams).

## Decision

Every framework SSE endpoint adopts a single uniform wire envelope.
The wire shape is fixed at:

- SSE event name is constant `"frame"`. The native SSE `event:` field
  is not used for routing.
- Payload is a single JSON object as the SSE `data:` field, containing
  six fields: `streamId`, `frameKind`, `seq`, `ts`, `payload`,
  `resumeToken`.
- `frameKind` ∈ `{UPDATE, LIFECYCLE}` discriminates data frames from
  connection-management frames. Lifecycle subkind lives nested in
  `payload.kind` (`connected` / `snapshot` / `heartbeat` (live SSE keepalive, not the retired process signal) / `reset` /
  `closing`).
- `streamId` is a kind-prefixed slug (`<kind>:<id>` where `<kind>` ∈
  `{registry, surface, system}`). Stable across reconnects.
- `seq` is a per-stream monotonic long starting at 1.
- `resumeToken` is opaque on the wire (server-encoded; consumers MUST
  NOT parse it).
- Resume contract: clients reconnect with `?since=<resumeToken>`;
  server replays in-window UPDATE frames or emits `reset` + fresh
  `snapshot` if the token falls outside the resume window.
- Capability handshake advertises adoption via
  `serverCapabilities.streamingEnvelope: { version: 1 }`. FE consumers
  feature-detect.

The four shipped SSE Resources (HealthEvent, RuntimeContext,
ServerCapabilities, OperationHistory) all use this shape. Future
SSE Resources (for example HISTORY and TABULAR resources) adopt it
from day one — no per-Resource wire-shape design space remains.
Diagnostic log streams are now `DiagnosticChannel` flows after slice
448 retired LOG_TAIL from `Resource.Category`; they inherit this
envelope only when a specific DiagnosticChannel endpoint declares it.

Language streams (`RagStreamingHandler`, agent token streams) are
explicitly out of scope and keep their cancellation / ordering
contracts independent.

## Rationale (historical pre-substrate behavior)

Pre-substrate, every SSE controller invented its own wire shape. The
four controllers shipping when slice 436 began had four distinct
shapes — each used Javalin's native SSE `event:` field with bespoke
event names (`snapshot` / `delta` / `replace` / `append` /
`heartbeat`) and bespoke payload structures. Reconnect / resume /
heartbeat semantics were per-controller; the FE consumer logic
diverged accordingly.

The cost class this creates: every new SSE Resource (444c, 445,
plus runtime AI install progress, scan progress, etc.) would need
its own wire-shape design + its own FE consumer dispatch logic +
its own resume token convention. The framework's whole-codebase
generic-renderer ambition (Stage 3a) requires a single dispatch path
that routes envelope frames to consumers by `frameKind` + payload
kind, not by SSE event name.

The corrected substrate puts wire-format design behind the kernel.
Slice authors stop deciding wire shape; they decide payload shape.
The frame-routing dispatcher sits at the FE boundary once, used by
every consumer.

### Why a constant SSE event name (not native SSE event field)

Native SSE event names couple the wire to the discriminator.
Consumers using `EventSource.addEventListener('snapshot', ...)` etc.
need per-event listeners, and the addable kinds are baked into the
client at compile time. Adding a new lifecycle subkind requires
updating every FE consumer.

Constant event name `"frame"` + frameKind discriminator routes the
dispatch to a single listener. The discriminator lives in the
payload, where it's free to evolve: lifecycle subkinds can be added
non-breakingly per the LSP soft-fail discipline.

### Why opaque `resumeToken`

Sequential server-side cursors leak server state and constrain the
encoding to a stable format. An opaque token (currently base64-URL-
encoded `(streamId, seq)` tuple internally) lets the encoding evolve
without a protocol break. Consumers treat it as bytes; the server's
`ResumeTokenCodec` decides what they mean.

The opacity is contractual. Consumers parsing the token are using
internal-only structure; future encoding changes (e.g., HMAC
signing, multi-version support) won't break them.

### Why only UPDATE frames in the ring buffer

Lifecycle frames are per-connection ephemera. Sharing them across
clients would mean Client A's `connected` lifecycle frame ends up
in Client B's resume replay — semantically wrong (Client B's
connected event is its own).

UPDATE frames are broadcasts; every subscriber sees the same frame.
Resume replays the missed broadcasts, not the per-connection
lifecycle. The `SseStreamChannel`'s frame discipline (publish vs
nextEnvelope) enforces this structurally.

### Why per-stream coordinator owns sequence + ring + fan-out

Earlier drafts considered per-connection sequence trackers. That
design failed for cross-connection resume: a client reconnecting
to a different writer instance would see seq numbering reset, and
the server would have no way to honor `?since=<token>` from a prior
connection.

The per-stream coordinator (`SseStreamChannel`) is the single
integration point: registries delegate `broadcast()` to
`channel.publish()`, controllers subscribe to receive envelopes
already wrapped + sequenced. Sequence is shared across all
subscribers; resume tokens are valid across reconnects within the
same server lifetime.

## Rejects

- Native SSE `event:` field as the routing discriminator.
- Per-controller wire shapes; legacy event-named SSE (snapshot /
  delta / etc.) is gone.
- Sequential server-side cursors as the resume mechanism.
- Per-connection sequence trackers / ring buffers (couples resume to
  connection lifetime).
- Adding a new SSE Resource without adopting the envelope.
- Reintroducing LOG_TAIL as an SSE Resource category instead of using
  `DiagnosticChannel` for diagnostic log streams.
- Treating language streams (`RagStreamingHandler`, agent token
  streams) as envelope adopters — their cancellation / ordering
  contracts are independent.

## Future Agents Must Not

- Ship a new SSE controller without delegating to
  `SseEnvelopeWriter.attach(...)`.
- Re-introduce per-controller `sendFrame` / `sendLifecycleToClient` /
  `attemptResume` boilerplate; the writer encapsulates the contract
  uniformly. (Reference case: slice 436 §B.B Fix A consolidated the
  ~320 LOC of per-controller duplication that the original retrofit
  shipped.)
- Bypass `SseStreamChannel.publish()` to broadcast UPDATE frames; the
  channel is the single integration point.
- Append lifecycle frames to the ring buffer (use `nextEnvelope`, not
  `publish`).
- Treat `resumeToken` as parseable on the consumer side.
- Change the constant SSE event name `"frame"`. A version bump on
  `serverCapabilities.streamingEnvelope.version` signals a wire-
  incompatible change; field additions within v1 are non-breaking.

## Revisit When

- Frame coalescing (slice 436 Phase 3, currently deferred) is
  needed: per-stream coalesce-function registration adds substrate
  surface. The decision's wire-format claims hold; the coalescing
  policy is additive.
- Backpressure signaling (slice 436 §"Out of scope for V1") is
  needed: the `/api/stream/<streamId>/backpressure` POST endpoint
  would add a new contract surface. The envelope wire shape is
  unaffected.
- Snapshot-vs-subscribe race (slice 436 §B.B Fix F documented
  limitation) is closed: requires subscribe-before-snapshot +
  queue-and-filter pattern. Closure is implementation detail; the
  wire shape is unaffected.
- A future stream class genuinely cannot be modelled with the
  existing envelope (e.g., bidirectional streams, per the spec's
  §"Out of scope" list). Would constitute a v2 envelope or a new
  stream-class decision.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/05-streaming-envelope.md` (the canonical wire-format
  reference; this ADR captures rationale, that doc captures contract).
- `../reference/ui/frontend-kernel/kernel/01-runtime-contracts.md` §"Streaming Contract" (cross-
  reference).
- `../reference/ui/frontend-kernel/kernel/00-primitives.md` (Resource × SSE_STREAM adopts the
  envelope).
- `30-agent-workflows/01b-add-event-stream-resource.md`,
  `01c-add-history-resource.md` (per-Category SSE recipes; each
  carries a "Wire Format" subsection cross-referencing the kernel
  doc).
- `30-agent-workflows/01e-add-log-tail-resource.md` (retired redirect;
  retained as historical evidence that LOG_TAIL moved out of
  Resource).
- `slices/436-streaming-envelope.md` (implementation spec + §B.A/§B.B
  appendixes recording the Fix A–F post-impl pass).
- `60-migration-history/02-slice-outcome-digest.md` (slice 1.8 entry
  recording the decision's first instance).
- `GLOSSARY.md` / `TRACEABILITY.md` / `CONFLICT-LEDGER.md` (cross-
  references).

## Source Evidence

- `slices/436-streaming-envelope.md` — implementation spec; §B.B Fix A
  consolidated ~320 LOC of per-controller boilerplate into
  `SseEnvelopeWriter`. §B.B Fix B closed an `attemptResume` empty-buffer
  false-positive. §B.B Fix D extended Phase 10 closeout coverage. Four
  controllers (HealthEvent, RuntimeContext, ServerCapabilities,
  OperationHistory) retrofitted to the `"frame"` event-name envelope.
- `archive/source-tempdocs/` — pre-substrate, every SSE controller
  invented its own wire shape. Source 426 §"Slice 1.8" framed the
  envelope as a substrate-level fix; this ADR is the ratified form.
- `60-migration-history/02-slice-outcome-digest.md` §"slice 436"
  records the decision's first shipped instance + the four
  controllers retrofitted.
- `modules/app-observability/.../SseEnvelopeWriter.java` (canonical
  per-connection helper); `modules/app-observability/.../SseStreamChannel.java`
  (per-stream coordinator). The implementation surfaces correspond
  one-to-one with the wire contract above.
