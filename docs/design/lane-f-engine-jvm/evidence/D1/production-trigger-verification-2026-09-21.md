# D1 production trigger and ownership proof

Source: `3f22be975` plus the saved source inventories, Windows, 2026-09-21.
This connects the registry trigger; mutable capability retirement and D1 acceptance
remain open.

OrchestrationPhase subscribes the production readiness trigger to the component
registry. HeadlessApp explicitly requests a sample after both client handovers;
source inspection corrected the earlier assumption that this request already
existed. HeadAssembly includes the acquired trigger in constructor-failure cleanup.
Seven read-only consumers now accept the existing Capability interface.

The real HeadlessApp/EngineRoot composition test registers no components or
subscriptions itself. It proves no RPC before handover, exactly one after the real
handover, and exactly one more after LocalApiServer publishes ABSENT. Physical
KnowledgeClient observation is mocked: this proves wiring, not index health or
schema-2 readiness. A separate late-constructor-failure test observes the actual
subscription release and executor registration close before fixture teardown.

## Verification

- 2314: read-only interface adaptation compiles; affected main PMD/format pass.
- 2315: 114 cases / 16 suites, both test tasks fresh, zero failures/errors/skips;
  affected static checks pass. Predates the cleanup and exact-count corrections.
- 2316: 44 cases / 10 suites, one test-fixture failure: TestEngineExecutors does
  not support accounting snapshots. Fresh UI exact-count proof passed. The cleanup
  test was corrected to observe the actual registered owner's close call.
- 2317: 11 cases / eight suites, zero failures/errors/skips. Seven app-services
  cases fresh; four UI cases reused unchanged from 2316. Affected static pass.
- 2318 negative: omitting registry wiring fails only the API-stop sampling witness.
- 2319 negative: omitting the post-bind request and constructor cleanup produces
  the two intended failures: no first observation and unreleased subscription.
  Both negative runs restore all production sources byte-exactly in finally.
- 2320: restored positive run, 92 cases / 13 suites, both test tasks fresh,
  zero failures/errors/skips; affected format/main and test PMD pass in 56 seconds.

Independent correction review found no remaining concrete defect. Negative
controls establish that the tests depend on the intended production owners.

Restored command:

```powershell
.\gradlew.bat :modules:app-services:test --tests '*HeadAssemblyTest' --tests '*ReadinessReconciliationTrigger*Test' :modules:ui:test --tests '*HeadlessAppComponentRegistryCoverageTest' --tests '*StatusLifecycle*Test' :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:ui:spotlessCheck :modules:ui:pmdMain :modules:ui:pmdTest -PtestParallelism=1 --continue
```

Raw logs, source inventories, counts and copied XML are under this worktree's
`tmp/2314-readonly-consumers*`, `tmp/2315-production-trigger*`,
`tmp/2316-trigger-reviewed*`, `tmp/2317-trigger-cleanup*`,
`tmp/2318-registry-wire-negative*`, `tmp/2319-bind-cleanup-negative*` and
`tmp/2320-production-trigger-restored*`. Mutation driver:
`tmp/2318-2319-trigger-negatives.py`. Retain through lane acceptance plus 30 days;
export before removing this worktree. Existing advisory compiler warnings remain
in the logs; none were suppressed.
