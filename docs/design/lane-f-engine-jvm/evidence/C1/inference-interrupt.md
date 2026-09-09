# C1-6 model wait ownership — 2026-09-09

OnlineModeOps now waits interruptibly through EngineFutures.await. Executor lookup owns
pre-handoff cleanup; after supplyAsync takes ownership, only its actual-exit callback releases
the retained work reference. The former catch around submission and waiting could close that
reference when waiting failed even while the accepted model task was still running.

The regression starts an operation that deliberately ignores interrupt, interrupts its caller,
and verifies prompt failure, preserved interrupt status, and one remaining work reference until
the operation's exit latch releases. No additional admission policy or fallback is introduced.

On Windows/Temurin 25, run44 above e021b8828 passed all ten OwnedStreamCancellationTest tests.
Replacing EngineFutures.await with join compiled and failed the new test at the prompt-return
TimeoutException assertion (run45); restoring it passed the same inputs from the valid run44
cache (run46). See [shared logs and mutation details](future-lifetime.md#interruptible-waits-c1-5-2026-09-09).
These focused proofs do not replace the remaining integrated, live or hosted C1 verification.
Retain local tmp logs and preserved XML through lane completion plus 30 days.
