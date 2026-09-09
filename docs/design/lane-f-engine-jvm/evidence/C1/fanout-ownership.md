# C1 fanout ownership correction — 2026-09-09

The earlier per-call executor close kept the port worker blocked after a later submission refusal.
Simply replacing it with shutdownNow would return promptly but release admitted work and scoped
foreground pacing before an interrupt-ignoring child exited. RuntimeSession could also close
before such a child acquired its Lucene searcher. Executor-instance accounting is a separate owner.

## Implementation and decisions

- **C1-5:** EngineTaskGroup owns one explicit EngineTaskLifetime callback, with one unsealed-parent
  count and actual-exit counts for tasks submitted through EngineFutures. Close cancels accepted
  futures, shuts the executor down and seals the group without waiting. Release happens once after
  every actual exit, including pre-start cancellation. Lifetime composition rolls back a failed
  second retain and releases both owners in reverse order. Ordinary optional failures may fall
  back; executor refusal, cancellation and interruption may not.
- **C1-10:** ForegroundLoadGate.callOwned supplies the child factory at the existing single wrap.
  Interactive work keeps one pacing increment through parent and child actual exit. No-child
  calls retain their previous per-call behavior, and a captured factory cannot resurrect a
  finished call. Durable work retains its existing work-completion/background-transition owner.
- **C1-7:** the port explicitly propagates the factory through CallContext. Hybrid, chunk and
  three-way search groups compose it with the RuntimeSession generation lifetime. Close rejects
  new groups, leaves the old snapshot available to accepted children, waits for their actual exit,
  closes physical resources, then restores any close-thread interrupt. This wait covers explicit
  fanout read owners only; it does not reinterpret the existing writer-drain timeout contract.
  RAG retrieval/union forwards the same factory, and optional failures cannot replay canceled work.

The neutral lifetime is an ephemeral ownership adapter, not another admission registry or context
authority. A cross-thread release callback is necessary; a thread-owned read lock cannot be
released safely by another task. Waiting only at EngineRoot would miss live runtime upgrades and
administrative swaps. A separate port failure callback would retain a blocked port worker and
couple lower index code to exposed completion. These alternatives were rejected in C1's decision.

## Local proof and limits

Windows/Temurin 25, integration source above `58b2eed9d`; results belong to the combined dirty
candidate, not to any individual item checkpoint. All paths below are in this worktree's `tmp/`
and are retained through lane completion plus 30 days.

| Run | Command/scope | Result |
| --- | --- | --- |
| 47 | Core group; Engine port context, fanout admission/pacing and existing pacing | Passed initial foundation; superseded by 55 |
| 48 | compileJava, compileTestJava, compileIntegrationTestJava | Passed all source sets |
| 49 | Focused core/index/Engine | New test used a nonexistent two-argument text-query overload; compile failure, no runtime claim |
| 50 | Same focused suites | Index 55 tests, one failure only in the final post-close assertion: a nulled ops getter produced NPE; changed oracle to actual SearcherBridge acquisition |
| 51 | Focused suites plus full worker-services | Core 14, index 55, Engine 13 passed; worker-services 1,277 passed with two existing skips |
| 52 | Remove admission/pacing factory from the port | Both fanout tests failed: competing admission was incorrectly accepted |
| 53 | Remove child pacing-reference increment | Both fanout tests failed: in-flight count expected 1, actual 0 |
| 54 | Remove runtime lifetime from actual hybrid fanout | Failed: physical close completed while the accepted child was still held before searcher acquisition |
| 55 | Restored candidate; core futures/groups/lifetimes, index lifecycle/hybrid/chunk/drain, Engine port/pacing | Core 23, index 55, Engine 14 passed; includes cancellation helper and captured-factory guard added after 51 |
| 56 | Production registry plus canceled virtual fanout group | Passed; the live instance refuses replacement until actual exit, then replacement opens |

Logs are `c1-batch4-fanout-{foundation-47,compile-48,tests-49,tests-50,tests-51,mutant-52,mutant-53,mutant-54,restored-55,registry-56}.txt`.
Preserved XML is under `c1-batch4-fanout-green-51`, `c1-batch4-fanout-green-55`, and the three
matching mutant directories. Mutations were restored in finally blocks and their assertion
failures independently read. The worker suite in 51 predates the final cancellation fallback
guards; final integrated verification must cover those changes too.

Build57 found one unnecessary qualifier in the new port context; corrected without suppression.
Build58 passed `build -x test` in 47 seconds, including the configured integration suites and
code-quality checks. After that build, independent review found the real WorkerSearchService
search catch still converted executor refusal/interruption into INTERNAL. It now applies the
same explicit non-fallback checks as RAG retrieval. Run59 passed all four SearchCancellationSeamTest
tests, including a real service/orchestrator boundary oracle with injected wrapped failures at
the capture seam. That boundary test deliberately isolates service translation; the real hybrid
submission/refusal and runtime lifetime are proved separately above. Removing the new catch
checks in run60 failed with WorkerServiceException instead of the exact EngineExecutorRejectedException.
Source was restored in finally. Logs: `c1-batch4-fanout-boundary-59.txt` and
`c1-batch4-fanout-boundary-mutant-60.txt`; matching preserved XML directories end in
`boundary-green-59` and `boundary-mutant-60`. Restored run61 passed the four boundary/seam
tests (valid run59 cache) and both worker-services PMD source sets; log
`c1-batch4-fanout-boundary-restored-61.txt`, XML `c1-batch4-fanout-boundary-green-61`.

None of these local results closes C1: remaining producers, state bounds, guards, full stress,
final live admission/pacing, and hosted/platform obligations remain.
