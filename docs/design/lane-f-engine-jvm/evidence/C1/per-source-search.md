# C1-7 per-source search ownership

2026-09-09, Windows / Temurin 25.0.2, lane-F-A. Changes tested over `71c4e17a3`;
the item commit carries the implementation. C1 remains open.

HeadAssembly owns one SearchPerSourceExecutor shared by eager/late HTTP and agent adapters.
It holds stable foreground/background virtual registrations; each call opens an instance with
EngineTaskGroup and retains the attached work until every accepted child actually exits. The
current admitted context and OpenTelemetry context both reach the virtual child. Cancellation,
interruption, timeout, capacity refusal and fatal errors bypass optional replay/backfill; ordinary
failure retains the existing merge/fallback behavior. Partial Head construction rolls back its
direct search/document owners, including failures after those owners were opened. The standalone
API builder requires an explicit search owner when no complete Head supplies it.

Root review corrected a lost Context.taskWrapping wrapper, wrapped fatal backfill, incomplete
constructor rollback, one omitted integration fixture, and a ConfigStore test fixture that had
implicitly depended on test order. An independent reviewer identified the rollback and omitted
fixture; root owns their fixes. SchemaMismatchStatusContractTest compiles with the explicit owner;
its live integration execution is still part of the remaining integrated proof.

Verification history:

- Run86: focused app-services tests and all four app-services/UI PMD tasks passed; one of 28 UI
  tests failed before its assertion because its fixture had not initialized ConfigStore. Run87
  fixed that setup and all 28 UI tests passed. Production configuration behavior was unchanged.
- Run88: the two real EnginePerSourceOwnershipTest cases passed with the production executor
  registry and aggregate admission limit one. Work cancellation and caller interruption return
  promptly, interrupt the child, refuse unrelated work while that child ignores interruption,
  and admit replacement work only after actual child exit. No fake admission count proves this.
- Mutation89: removing the retained work lifetime compiled and failed both aggregate-one tests
  because active work dropped to zero before the child exited. Source restored in finally.
- Run91: Head constructor rollback, HeadAssembly, per-source and adapter tests passed, together
  with the real aggregate tests and app-services PMD main/test. UI integration compilation passed.
- Run92: the added trace-context regression was blocked by compilation errors in a concurrently
  authored, separate architecture test. Those errors do not constitute producer test evidence.

- Run93: producer tests passed, including the trace regression, but the new architecture guard
  incorrectly imported Gradle test fixtures as production and PMD flagged the trace scope's unused
  resource variable. Root excluded test-fixture artifact locations from that new production-only
  guard and made scope cleanup explicit. Run94: all 48 app-services tests and PMD test passed;
  aggregate Engine tests (2), OCR tests (28) and UI tests (23) retain their passing run93 evidence.

- Mutations95/97: removing the telemetry wrapper fails the virtual-child context assertion;
  omitting constructor rollback fails the acquired-registration close assertion. Both compiled
  and failed at their intended oracle; sources restored in finally.
- Run98: restored per-source/adapter/construction and real aggregate-one tests plus app-services
  PMD main/test passed. Raw log `tmp/c1-batch4-producers-restored-98.txt`, XML snapshot
  `tmp/c1-batch4-producer-green-98`. Some unchanged tasks reused valid Gradle cached results.

Raw evidence is under `tmp/c1-batch4-per-source-final-86.txt`, `c1-batch4-producers-87.txt`,
`c1-batch4-producers-88.txt`, `c1-batch4-producer-mutant-89.txt`, and
`c1-batch4-producers-review-{91,92,93,94}.txt`. XML snapshots: `c1-batch4-per-source-results-86`,
`c1-batch4-producer-results-87`, `c1-batch4-producer-green-88`,
`c1-batch4-producer-mutant-89`, `c1-batch4-producer-results-92` (the latter retains each module's
last completed test run; timestamps distinguish the blocked run), and the analogous
`c1-batch4-producer-green-94` snapshot (timestamps distinguish modules last executed in run93). Retain through lane completion
plus 30 days. Earlier worker-run focused output is not used as final proof because that worker
ran Gradle outside the assigned no-build scope; root repeated verification exclusively.

Full/stress, installed standard-model, hosted/platform and final stage-wide evidence remain
required. This producer slice is not a claim of C1 completion.
