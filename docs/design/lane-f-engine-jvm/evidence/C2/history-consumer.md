# C2-4 completion consumer

2026-09-13, base5df62b4f3 plus the source-hashed consumer overlay. One
OperationHistoryProjector attaches as the last acquisition in both Head constructors.
The committed-row hook publishes history and live ledger events without producer-side
journal I/O. Memory/note agent rows enter the ledger; generic agent-loop rows are
explicitly acknowledged as another source's responsibility. Dispatcher keyed emission
is retired; unkeyed STORAGE_FAILED observations remain visible.

The registered one-second timer forces append before source acknowledgement and
stops its durable pass on failure. A second bounded startup pass traverses pending
metadata through a completion-time/id cursor, restricted in SQL to the finite accepted
row-id cohort captured after subscription. Neither cursor nor ceiling is durable progress.
The existing pending bit owns retention and replay. Each arm reads at most256 rows.
Close quiesces callbacks and the scheduler before releasing registration or Head
dependencies; constructor activation gating and final attachment prevent orphaned owners.

Independent review found and corrected two failures before acceptance: an early Head
attachment could strand a retryable owner after later bootstrap failure; unbounded
startup enumeration could chase new completions forever. A timestamp upper bound was
also rejected because clocks regress. Final read-only review found no further runtime
defect; its remaining documentation contradiction was corrected to the source-id ceiling.

Focused1191 executes98 tests in28 suites, zero failures/errors/skips. It covers actual
non-dispatched memory/note completion, pre-start refusal, hidden/excluded rows,520-row
startup, sustained arrivals with advancing/regressed clocks, forced append/failed ack
retry, idle sink repair, constructor failure with a captured callback, callback drain,
retryable shutdown and both bootstrap/dispatcher paths. Final1194 is green after exact
mutant restoration and reuses those98 successful inputs (25 cache-restored,73 up-to-date);
PMD, formatting and integration compilation pass. Launcher installDist also passes.

Negative1192 removes the SQL cohort ceiling: both sustained-arrival cases fail because
startup keeps paging. Negative1193 ignores rejected journal acceptance: the disabled-sink
case finds zero pending rows instead of256. Both source files were restored byte-for-byte.
The sole-ack ArchUnit rule passes with a deliberately unauthorized caller that it rejects.
The operation-surface gate failed1185 for the unregistered new projector and passes1190
after its guarded projection registration. No rule, suppression or baseline is weakened.

Live1198 passes15 recorded checks on the managed Engine. A file blocks the journal
directory; committed failure remains visible through history, ledger, HTTP and MCP,
and the stopped source retains history_pending=1. After repair and a controlled restart,
the same history/event/outcome return, keyed retry remains idempotent, and the stopped
source has history_pending=0. Both owned runs stop with ports closed.

Live1195 remains a failed freshness witness: root refreshed app-launcher:installDist,
but the dev runner's recorded classpath loads modules/ui/build/install/ui/lib. It ran
the old distribution and lacked restart projection. UI installDist1197 corrected the
launch artifact; installed observability/services jars match their built jar hashes.
No production workaround or assertion relaxation was needed. Use UI installDist for
subsequent managed Engine proofs. Earlier1184/1186 test compilation and1187/1188 PMD
failures remain recorded;1189 passed before the final source-id correction.

Mockito uses the existing repository catalog as testImplementation only; the full
resolveAndLockAll1185 command generated its four transitive lock entries; full resolution
1200 leaves those lock contents unchanged. Documentation
regeneration, canonical links and module-dependency checks pass. Existing JVM warnings
remain in raw logs. Latest hosted snapshot: CI34758939074 pending and CLA34758938221
queued at5df62b4f3; runner allocation is not hosted proof.

[Commands, counts, source hashes, portable XML and live evidence](history-consumer-verification.json).
Retain raw artifacts through lane acceptance plus30 days and export before worktree
release. This proves controlled restart, not physical power loss. No new full-suite,
installed-v5 packaging, atomic SSE or frontend keyed-merge acceptance is claimed.
C2-4 remains open; continue atomic snapshot/reconnect and keyed frontend merge, then
the coherent full/stress/installed/hosted boundaries.955 owns its typed Memory adapter
inside this sole projector's ownership; no placeholder product variant is introduced.
