# Atomic snapshot attachment and subscription retirement

Implemented 2026-09-13 at40d1b75cd plus the source hashes in
[verification](snapshot-retirement-verification.json). This is a C2-4 mechanism cut;
live reconnect, coherent integrated and installed/hosted proof remain open.

## Behavior

Standalone and multiplexed writers capture the channel boundary before snapshot SQL,
validate/register buffered delivery before sending a candidate, then replay source-ordered
updates and drain live frames. At most three expired candidates are queried; none is sent.
Incarnation-bound tokens reject old server lifetimes even after replacement sequence growth.
The discarded-update fence covers count/byte eviction and evidence replacement. Control-frame
sequences remain distinct from delivery checkpoints; snapshots checkpoint pre-query state and
heartbeats cannot acknowledge undelivered global sequence allocations.

Subscription retirement notifies transport owners outside source locks. A shared SseConnection
owns the pre-created request future, subscriptions and heartbeat across standalone, multiplexed
and run writers. Ownership exists before prefix/replay delivery can block; late acquisitions
are released immediately after close. Overflow of one multiplexed source closes all siblings.
Numeric run-zero tail semantics and snapshot-primer ordering remain intact. Live-at-entry
terminal retirement closes during replay; already-retired lingering runs can replay first.

Raw RunObservation passes detachment to AgentSessionRegistry's existing completion latch.
Notification cannot interrupt a synchronous blocked write; after that callback returns, the
raw handler can unwind instead of waiting for run termination. Native heartbeat finally cleanup
then runs. Registry retirement now stages callbacks with state/bookkeeping and invokes them
outside the registry monitor. Fatal cleanup errors reach callers after sibling cleanup.

Retired: non-atomic attemptResume, snapshot-before-registration branches, legacy token encoder,
generic keepAlive, duplicate connection cleanup, and stale canonical race/codec/lock claims.

## Verification

Final compatibility1231: **756 represented cases, 665 executed**, 96 suites,
0 failures, 0 errors, 0 skips. Full app-observability tests,
AgentLoopService and raw-detach tests, UI SSE/run/health tests, affected PMD/format gates and UI
integration-test compilation are included. Cached results are identified in the manifest;
this is not the full repository suite. Source restoration is byte-identical and hashed.
After1231 only two writer comments were corrected; behavior is unchanged. Canonical indexes,
skill embeddings and links pass1233; the manifest includes its retained log.

Negative1226 uses previous production plus regressions: four intended failures expose both
standalone/multiplexed snapshot loss and connected acknowledging undelivered replay.
Negative1230 delays ownership until after delivery and holds the registry lock during callbacks:
five intended failures detect blocked generic replay (two modes), run prefix overflow, raw primer
overflow and registry access blocked by retirement. Eight represented cases include three harness
containers. Mutants were restored before the final run.

1227 remains FAILED (test compilation),1228 FAILED (two misplaced migrated cursor fixtures),
1229 FAILED (one PMD qualifier violation despite all156 represented tests passing). Original logs,
failure messages, commands, per-task execution/cache status and XML archives are accessible from
the manifest. Artifacts stay through lane acceptance plus30 days and are exported before release.

Independent refute-first review is clear after the root fixed initial ownership, numeric primer,
terminal-during-replay, fatal callback isolation and registry-monitor findings. Local tests provide
execution proof; reviewer inspection alone does not. No C2-4, stage C2 or lane closure is claimed.
