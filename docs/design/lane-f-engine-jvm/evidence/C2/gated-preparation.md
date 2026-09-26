# C2-3 shared orchestrator preparation route

September13, based on8968055aa. GatedOperationExecutor exposes prepare and
routePrepared through the existing BackendIntentRouter. The caller retains the
existing OperationDispatchPlan and immutable public arguments across its gate and
retries; no second prepared-call record, store or authority is added. Context and
correlation checks run before capsule issuance. A ready nonce uses mintPrepared;
an absent authority refuses rather than using the legacy sentinel. A recorded plan
queries the current receipt through the router without minting another capsule.

The connected test uses actual SQLite, OperationExecutorImpl, BackendIntentRouterImpl
and ConsentCapsuleService for both AGENT_LOOP and WORKFLOW. It verifies no effect or
acceptance before approval, the original frozen target after external state changes,
the correct transport/session attribution, receipt reuse and a subsequently engaged
hard-stop. Negative1015 changes the prepared mint to an ordinary public-input mint;
both transport cases fail at the intended ConfirmationRequiredException. Eight cases
are represented including six always-included contract cases.

Initial1016 fails API test compilation because the API module has no Mockito dependency.
Its services137 and agent145 tests execute successfully, but it is not a successful
item gate. The final API tests use handwritten port doubles matching the existing
module style; no dependency is added. Final1017 passes525 represented cases in54
suites with zero failures/errors/skips. API243 executes; services137 and agent145
reuse unchanged successful1016 inputs. Affected PMD and UI integration-test compilation
pass. The API tests also verify preparation performs no routing/consent, recorded
plans obtain a fresh receipt and prepared consent cannot use the sentinel fallback.

```text
gradlew.bat :modules:app-agent-api:test :modules:app-services:test --tests '*PreparedOperationDispatchTest' --tests '*OperationExecutorImplTest' --tests '*BackendIntentRouterImplTest' --tests '*WorkflowShapeRunnerTest' --tests '*WorkflowToolRunnerImplTest' :modules:app-agent:test --tests '*AgentLoopServiceAuditTest' --tests '*AgentToolDispatcherRetryTest' --tests '*AgentLoopServiceTest' :modules:app-agent-api:pmdMain :modules:app-agent-api:pmdTest :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:app-agent:pmdMain :modules:app-agent:pmdTest :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1 --max-workers=4 --console=plain
```

[Verification](gated-preparation-verification.json) pins source hashes, counts and raw
artifacts. Raw prefixes under `F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/`
are `c2-3-gated-preparation-negative1015`, `c2-3-gated-preparation1016` and
`c2-3-gated-preparation1017`; keep them through stage acceptance plus30 days and export
before worktree release. No live/model/hosted proof is claimed. The agent/workflow
loops still need to call this shared path with scope applied before preparation,
private preview lookup and nested snapshot reattachment; C2-3 remains open.
