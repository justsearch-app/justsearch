# C2-4 atomic snapshot and reconnect

Investigation,2026-09-13, grounded at4c6389be4. The completion consumer is committed
and locally/live verified. Full/stress1202 passes on that unchanged source.
This record owns the C2-4 attachment/checkpoint mechanism. Implementation and focused proof
are recorded in [snapshot-retirement](snapshot-retirement.md); live and rendered proof now pass [1238/1241](sse-live-rendered.md); integrated proof remains open.
The root is making these decisions autonomously within the existing reconnect item.

Current cuts: [ordered publication](sse-ordering.md),
[frontend keyed convergence](history-keyed-merge.md), and
[frontend checkpoint recovery](checkpoint-recovery.md) are implemented with focused,
negative and compatibility evidence and independent review. Strong snapshot/token attachment,
safe backend lifecycle checkpoints and transport retirement now pass compatibility1231.
Live reconnect/restart1238 and rendered overlap/full UI1241 now pass. Next: coherent
full/stress proof, with installed-v5/hosted still required.

## Verified reach

- SseEnvelopeWriter.attach (ui/api,222-247) snapshots before plain subscribe on a
  fresh connection or resume miss. The durable SQL read cannot move under a channel lock.
- MultiplexedSseWriter (ui/api,92-116) also separates replay from subscription.
  ShellEventsStreamController includes the action ledger (111-123), so correcting
  only the standalone controller would leave the product path racy. Share attachment
  orchestration; retire the non-atomic multiplexed replay branch.
- SseStreamChannel.publish (app-observability/stream,120-137) allocates its sequence
  before the read lock. Concurrent read-lock publishers can also append/fan out out
  of sequence. A cursor must not pass an undelivered earlier update. Preserve the
  existing bounded handoff and no socket writes under the channel write lock.
- The existing external resume-window rule rejects a positive cursor with an empty
  UPDATE ring, even if lifecycle frames issued that sequence. Fresh internal snapshot
  boundaries need explicit source-owned validation, including overflow during SQL
  reads; reusing that external rule blindly can reset/retry unnecessarily or forever.
- ResumeTokenCodec encodes only streamId and sequence; StreamSequenceTracker restarts
  at zero. A same-valued old token can be accepted once a new channel's sequence grows.
  Bind opaque tokens to the channel incarnation. Old/mismatched tokens reset rather
  than pretending to identify this channel's retained window. No wire field is needed.
- SseEnvelopeWriter sends connected/snapshot lifecycle frames with freshly allocated
  global cursors before older replay frames. Both EnvelopeStream and MultiplexedStream
  retain every incoming token. Disconnect during this interval can skip data. Lifecycle
  tokens must describe delivered state, not merely the latest allocated sequence.
- Both frontend stream owners advance tokens after reducer failure. Recover through
  the existing physical reconnect/backoff owner; do not acknowledge an unapplied row.
  Multiplexed failure clears only the failed logical stream's checkpoint before the
  common connection reconnects. Preserve unaffected logical stream tokens.

## Identity reuse

OperationHistoryResourceCatalog is EVENT_STREAM/SSE_STREAM, not HISTORY. Both generic
event and history strategies currently append UPDATEs without deduplication. Declare
operationKey through the existing Resource.primaryKey field, and let those strategies
opt into keyed snapshot/update convergence. Blank key declarations and unkeyed legacy
STORAGE_FAILED observations keep append semantics. The operation reference is not an
invocation identity. No hardcoded resource-id branch or schema-field guessing.

Resource only requires a key for TABULAR; it does not forbid one for other categories.
The existing JSON schema is an unconstrained string, and serialization already carries
it. Clarify Java/TS/canonical comments; no additional Resource field or schema version.
ActionLedgerClient already deduplicates by explicit event id. Its view's GET seed is
a useful fallback: an older stream snapshot followed by complete atomic replay reaches
the current state, so that transient ordering is not a reason to remove the fallback.

## Required proof and retirement

Before claiming this item: prove publication during the durable snapshot, a snapshot
whose token expires, empty-ring positive internal boundaries, bounded retry failure,
same sequence on a new incarnation, disconnect during replay, slow/overflowing handoff,
concurrent publisher ordering and failure cleanup. Exercise standalone and multiplexed
history/ledger routes. Snapshot overlap must not duplicate keyed frontend entries;
distinct invocations of one operation and unkeyed observations remain distinct.

Retire obsolete non-atomic helpers/branches and stale race/codec/primaryKey comments
with the implementation. Preserve existing event-only and run-stream ownership, explicit
reset on unrecoverable gaps, bounded memory and cleanup. Run focused negative regressions,
canonical/generated checks, live SSE reconnect/restart, independent review, and the
integrated/installed/hosted tiers already owed by C2. Source reads and socket delivery
must stay outside the channel write lock; compare simpler ownership before adding state.

## Settled mechanism after independent refutation

The root accepts the independent review's minimal ownership change: SseStreamChannel
serializes sequence allocation, ring append and bounded listener enqueue under one short
publication boundary. Its existing HandoffListener becomes a permanently bounded serial
delivery owner; publisher/attach threads drain it outside channel locks. No new executor,
event bus or durable source. A publisher encountering an already-draining listener only
enqueues; a slow socket cannot make another publisher enter that socket concurrently.
Overflow retires only that listener and clears queued work. Claim drain ownership when
actually draining, not for every listener before the first socket write, so other
publishers can still drain healthy listeners while one writer is blocked.

FrameHistoryRingBuffer owns a monotonic dropped-through sequence fence. New strong
snapshot/token APIs require matching channel incarnation, cursor <= current sequence,
and cursor >= that fence. A channel-owned SnapshotBoundary is captured before SQL;
the channel validates and registers a buffering listener before any candidate snapshot
is sent. Outside locks, emit the prefix/snapshot, replay after the boundary, then drain
queued live frames. Retry capture/query/attach at most3 times on an expired boundary;
after that fail the connection with cleanup. Do not send an invalid candidate snapshot.

Preserve the separately decided numeric run API: subscribeAndReplay(listener,0) means
fresh attachment to whatever retained tail exists, with the run's own snapshot primer.
Positive numeric cursors keep their existing window rules. Requiring zero >= dropped
fence there would make a run with evicted history impossible to attach to. New opaque
tokens and durable-snapshot boundaries must never use this permissive numeric path.

Per-channel random UUID incarnation stays inside opaque tokens. Legacy tokens may decode
for multiplex routing, but missing or mismatched incarnation always means reset for the
strong path. A lifecycle frame's checkpoint is the attachment's safely delivered state;
it must not become the latest allocated global sequence. A snapshot checkpoints its
prequery boundary, while UPDATE/replay advances only after successful socket delivery.
Control-frame sequence allocation and resume acknowledgement are distinct: preserve
the envelope field shape and explicitly document replay's original source sequences.

Single-channel and multiplexed writers share one source-attachment helper. Prefix or
heartbeat registration failures must release every acquired subscription. Event-only
fresh attachment uses the same bounded boundary/register primitive without a snapshot.
Do not keep the old replay-then-subscribe implementation as a second path.

Independent read-only mapping and design refutation are complete at4c6389be4; neither
is executed implementation proof. Root owns implementation and verification next.

## Retirement and connection scope

2026-09-13 root amendment, re-grounded after40d1b75cd. Permanent queue overflow exposes
a real ownership defect: removing a listener leaves an independent SSE heartbeat running.
This affects standalone, multiplexed, RunStreamWriter and raw native/AG-UI run attaches.
The raw path is RunObservation.Handle.observe → AgentSessionRegistry.attachToRun,
whose existing completion latch releases SseHeartbeat.around's finally cleanup.

Use a per-subscription retirement callback, staged under the channel gate and invoked
outside it, with registration-after-retirement delivering the signal immediately.
Existing connection owners close on that signal; raw run attaches count down their
existing latch. This preserves numeric run-zero replay and terminal run ownership.
Heartbeat polling would delay recovery and fail to cover non-heartbeat observers;
an additional executor/event bus or a second run-terminal event is unnecessary.
Registry consumers keep their existing eviction-on-failure policy; the new callback
provides transport ownership notification and does not invent a durable registry retry loop.

Generic standalone/multiplexed connection cleanup must cover close before/during attach,
prefix failure, heartbeat rejection and partial acquisition. Reuse the owned close-future
pattern already proven by RunStreamWriter instead of Javalin's unsafe keepAlive ordering.
Keep run snapshot/primer and numeric cursor behavior in their existing owner. Compare
shared cleanup extraction against copying it before implementing the generic helper;
record the final scope and its proof here. Required regressions include retirement outside
the channel lock, exactly-once notification, raw attach release, and connection cleanup.

Final ownership choice (2026-09-13): extract RunStreamWriter's pre-created future and
one-shot resource cleanup into package-private SseConnection, shared by all three UI
writers. This supersedes duplicate cleanup and generic keepAlive calls; it adds no executor
or registry. Source attachment supplies onRegistered outside the source gate, before
prefix/replay I/O, so the transport acquires its subscription and retirement notification
before a blocked initial write can overflow. A callback installed only after subscribeAndReplay
returns would leave that initial-delivery hole. RunObservation passes its existing detach
latch callback through the same registration point; terminal run callbacks remain separate.
Numeric run/raw primers also move after ownership registration and before captured replay.
Live-at-entry run retirement is registered before writes; already-retired lingering runs replay
before closing. Run registry state/bookkeeping stages terminal callbacks under its monitor and
invokes them outside it; fatal errors are aggregated after sibling cleanup, preserving identity.
Raw detach signals promptly but cannot interrupt an already-blocked synchronous callback.
Final compatibility1231 and negative1226/1230 provide focused execution; independent review
has no remaining substantive finding. The linked proof preserves failed1227/1228/1229 attempts.
