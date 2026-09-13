# C1-7 Lucene generation capacity and resource lifetime

2026-09-09, Windows / Temurin25.0.2, restored candidate over749ae81dc (original slice began at d0d09bc72). C1 remains open.

The Engine test constructs three actual RuntimeSession generations through the production registry
and LuceneExecutorRegistrations. An actual fourth runtime is refused, including while the previous
generation's scheduled commit callback ignores cancellation. Only its actual exit releases the
third slot. The test then opens the same fourth index path successfully.

That refusal exposed a constructor leak: the commit timer opens after the writer, directory and
NRT thread. RuntimeSession now closes those already-initialized resources if timer creation refuses,
then rethrows the exact refusal with cleanup failures suppressed. The existing session close owner
is sufficient; a separate rollback registry or partially-constructed runtime state is unnecessary.

The passing generation test also logged a crtrt close interruption. ExecutorService.close restores
interruption after waiting for its callback; Lucene10.4 ControlledRealTimeReopenThread.close wraps an
interrupted join in ThreadInterruptedException. The old broad catch logged it and closed the writer
while that thread could still be alive. RuntimeSession now defers the interrupt flag through its
resource cleanup and retries that specific interrupted join. It keeps the NRT reference and writer
until actual thread exit; unexpected close failures propagate rather than authorizing resource
release. The caller's interrupt is restored after cleanup. No new executor or lifecycle state is
introduced. CommitOps.suspendNrtRefresh remains unchanged because it already propagates failure and
does not report successful retirement.

RuntimeSessionNrtLifetimeTest installs a controlled refresh-thread subclass into a real runtime.
It blocks that thread, interrupts close during the actual Lucene join, proves the writer and thread
remain owned, then releases the thread and checks it saw an open writer before exit. Final close
joins it, closes the writer, clears the reference and restores caller interruption.

Run113 first passed real-generation capacity but exposed the logged NRT issue above. Run114's new
NRT fixture omitted registrations and failed before exercising cleanup; corrected in run115, which
executed the NRT regression successfully; the generation test was UP-TO-DATE. Run128 restored
compatible cached target test outputs and passed affected PMD. Those are cache revalidations,
not new test execution. Mutation118 changes
the production generation cap3 to4 and fails actual fourth-open refusal. Mutation119 removes
constructor rollback and fails with LockObtainFailedException on the fourth path's write.lock.
Its wrapper message is IndexRuntimeIOException, so the mutation script's initial top-message
matcher rejected the result; root independently read the nested lock cause. Mutation120 returns
from interrupted NRT join instead of retrying and fails because close returns while the thread is
held. All sources restored in finally. These are behavioral failures, not specification-shape tests.

Logs: tmp/c1-batch4-focused-{113,114,115}.txt and c1-batch4-lifetime-mutant-{118,119,120}.txt.
Preserved XML: tmp/c1-batch4-green-115 and corresponding mutant directories. Artifacts retained
through lane completion plus30 days. Full/stress/live/hosted proof remains required separately.


Independent refute-first review found no product defect in the final constructor/close order or
lifetime tests. It requested explicit separation of cached revalidation from execution. Root run138
therefore used --rerun-tasks for the NRT, direct/hybrid fanout, worker three-way and actual-generation
suites. All93 tasks executed; five tests passed with zero failures, timestamps09:57:32–09:58:00 UTC.
Command: ./gradlew.bat :modules:adapters-lucene:test --tests '*RuntimeSessionNrtLifetimeTest'
--tests '*FanoutRuntimeLifetimeTest' :modules:worker-services:test
--tests '*SearchExecutorFanoutLifetimeTest' :modules:app-engine:test
--tests '*EngineLuceneGenerationCapacityTest' --rerun-tasks.
Log tmp/c1-batch4-lifetime-executed-138.txt; XML tmp/c1-batch4-lifetime-green-138.
Tested base749ae81dc plus the following owned source files (SHA256):

- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RuntimeSession.java: 936484c055bbb1f9f7693ad6e4a0227de4fa3d6382850eda3232ea1672ee6908
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RuntimeSessionNrtLifetimeTest.java: 93add85d0b982f2839d5a7605f3e4ba73d659a9bfff40ae830d988522e46c167
- modules/app-engine/src/test/java/io/justsearch/app/engine/EngineLuceneGenerationCapacityTest.java: 666010a87e98aa83db126983f781d7baacc8dff0b900c87339dac0013168a50a
