# C1-7 fixture ownership and background scheduler — 2026-09-09

Source: corrective working tree above b5d1fd961, Windows / Temurin JDK 25. All paths below are
accessible beneath this worktree. Retain raw outputs/XML through lane completion plus 30 days.
These item checkpoints do not close C1 or replace its outstanding stress/live/platform proofs.

## Changes and ownership

The existing LuceneExecutorTestBase is now an exported adapters-lucene test fixture. Its one
TestEngineExecutors and Lucene registration bundle remain per test; inherited cleanup runs after
the subclass runtime cleanup. This avoids dozens of copies of a registry factory or production fallback.
52 external fixture classes now inject the bundle into 79 runtime builders. Static/per-class
system fixtures own their bundles through their existing AfterAll lifetime; standalone seed
helpers use try-with-resources around both registry/bundle and runtime. New fixture dependencies
were regenerated with resolveAndLockAll --write-locks (run4); only the adapters-lucene lockfile's
configuration coverage changed, not dependency versions.

Four service tests used IndexingLoop subclasses whose supposedly no-op constructors still opened
an unused extraction owner. They now use mocks with the same IDLE/current-commit-time answers and
no-op start/close, keeping every service assertion. A latch-only search test explicitly supplies
a mocked runtime registration bundle. Actual-runtime tests continue using real asynchronous
fixtures. The unreferenced Lucene registry accessor and its unused field were deleted.

BackgroundRunService now takes the process registry and owns head.background-agent-run: one BG
scheduled instance, one worker, the canonical BG queue bound. Constructor failure closes its
registration; shutdown cancels pending work and retires that registration exactly once.
ResourceApiModule passes its registry through InteractionThreadController and invokes its
shutdown in the existing best-effort traversal. No owner closes the process registry.

## Proof and failures preserved

- Full stress-enabled run36 started at b5d1fd961 before this slice was edited. It stopped on two
  launcher checks (unused accessor and old confinement signature). Engine 177, adapters-lucene
  717, app-inference 319, app-agent 672 and other already-executed modules passed. In particular,
  actual read-while-write and index-lock tests passed. This was NOT a full-suite pass.
  `tmp/c1-batch4-full-tests-36.txt`, fresh XML `tmp/c1-batch4-full-results-36/`.
  Thread dumps 1/2 under `tmp/c1-full36-thread-dump*.txt` confirmed progress, not a hung build.
- All production, unit-test and integration-test compilation passed run37:
  `tmp/c1-batch4-fixture-compile-37.txt`.
- Run38: worker-services 1277 tests, four setup failures and two skips; indexer-worker 384 tests,
  58 setup failures and 12 skips. All failures were the missing fixture owners described above.
  Launcher 34, app-agent 2 and UI 19 selected tests passed with no skips. UI covers actual
  registry bounds/refusal, pending cancellation, controller assembly and module teardown.
  `tmp/c1-batch4-fixture-tests-38.txt`, XML `tmp/c1-batch4-fixture-results-38/`.
- After fixture corrections, run39 passed all 64 affected indexer-worker tests and all four latch
  tests, zero failures/errors/skips. `tmp/c1-batch4-fixture-tests-39.txt`, XML
  `tmp/c1-batch4-fixture-green-39/`. The other run38 tests were not rerun unnecessarily.
- Removing only ResourceApiModule's interaction-controller shutdown left a real registration
  live and failed the intended reconstruction assertion. `tmp/c1-batch4-background-close-mutant-41.txt`,
  XML `tmp/c1-batch4-background-close-mutant-41/`. Source was restored in a finally block.
- Restored run42 passed 34 launcher checks and six UI background/shutdown checks, zero failures,
  errors or skips. `tmp/c1-batch4-restored-tests-42.txt`, XML `tmp/c1-batch4-restored-green-42/`.

Remaining production census: KnowledgeServer's stuck-job timer and deferred-model bare async;
SearchPerSourceExecutor's static virtual pool; PdfOcrEngine's per-call fixed pool. The source count
before adding the background scheduler was 64 logical declarations (helper expansion included),
so the planned floor 63 remains a lower bound, not closure proof. The raw-constructor/arity gate,
fanout interruption, blocked commit close, scan buffers, final integrated stress/model proof and
subsequent design stages remain required.

Build43 (`build -x test`) passed in 52 seconds after these corrections, including configured
integration and code-quality tasks. `tmp/c1-batch4-build-43.txt`. This is the integrated working
tree above b5d1fd961; isolated corrective commits are not claimed to have independently run it.
