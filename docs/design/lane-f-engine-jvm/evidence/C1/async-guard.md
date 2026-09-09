# C1-6 explicit executor rule

2026-09-09 Windows / Temurin25.0.2; combined candidate over d0d09bc72. C1 remains open.
LayeringEnforcementTest Rule9 inspects all production code-unit accesses, including method
references, to CompletableFuture and CompletionStage *Async members. The target parameter list
must contain Executor. It also rejects commonPool and parallel-stream work that bypasses admission.
This checks the executable overload rather than a commonPool source symbol that the old twelve
bare async producers never spelled. The duplicate historical Rule7 comment is retired.

Mutation110 adds bare CompletableFuture.supplyAsync and111 adds CompletionStage.thenApplyAsync;
both fail Rule9. Mutation112's explicit-executor overload passes. Mutation123's runAsync method
reference without Executor fails, and124's BiFunction reference to the executor-taking overload
passes. Installed ArchUnit1.4.1's getCodeUnitAccessesFromSelf includes calls and references; target
getRawParameterTypes supplies the overload distinction. Run128 restored all launcher guards and
PMD successfully. See executor-guards.md for initial API/PMD corrections and raw-factory companion.

Logs: tmp/c1-batch4-executor-guard-mutant-{110,111,112,123,124}.txt and
c1-batch4-restored-128.txt. Preserved XML in matching mutant directories and
c1-batch4-green-128/app-launcher. Retain through lane completion plus30 days.
No full/stress/live/hosted claim follows from this guard slice.

Restored run135 passed launcher guards and PMD; XML retained in tmp/c1-batch4-guards-green-135.
Mutation134 added commonPool and parallelStream and failed with two separately named violations.
Log: tmp/c1-batch4-executor-guard-mutant-134.txt; restored log: c1-batch4-guards-restored-135.txt.
