# C2-3 connected workflow preparation and snapshot continuation

September13, based onc787c6daa. Workflow tool steps now prepare through the shared
gated executor before any human wait and route the same plan afterward. Recorded
rows skip another prompt. The runner requires the existing shared IntentGateEvaluator
from composition, using ASSIST with the operation's declared confirmation floor;
LOW with Typed remains typed. Current DENY and background human waits refuse before
preparation. The agent's server-owned background flag reaches the workflow bridge
and runner. A cancelled workflow terminal becomes a failed enclosing tool result.

The existing WorkflowGateRegistry privately holds frozen display and retains two
session aliases for its public pending-detail projection (workflow and enclosing
run). AgentLoopService snapshots concatenate that projection with their own pending
gates through the existing step-runner/WorkflowToolRunner owner. No second gate
store, hierarchy registry or authority is added. Nested live events carry the
existing authoritative gate behavior, never the preview/key/nonce/prepared payload.

Initial1025 passes417 represented cases with PMD/integration compilation. The final
regressions additionally prove nested background refusal returns failure and late
reattachment finds a workflow gate even when no pending event exists in the replay
ring. Negative1026 removes the declared floor and nested snapshot projection: both
selected regressions fail at expected-one/actual-zero pending gates (eight represented
cases including six always-included contract cases, two failures, no errors/skips).

Final1027 passes2155 cases in281 suites, zero failures/errors and one inherited UI
skip: full agent685, services44 and full UI1183 execute; API243 reuses unchanged
successful1025 inputs. All affected PMD and UI integration compilation pass.
The shared actual SQLite/router/consent fixture is included in services44.

```text
gradlew.bat :modules:app-services:test --tests '*WorkflowPreparedContinuationTest' --tests '*WorkflowShapeRunnerTest' --tests '*WorkflowToolRunnerImplTest' --tests '*PreparedOperationDispatchTest' :modules:app-agent:test :modules:app-agent-api:test :modules:ui:test :modules:ui:compileIntegrationTestJava :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:app-agent:pmdMain :modules:app-agent:pmdTest :modules:app-agent-api:pmdMain :modules:app-agent-api:pmdTest :modules:ui:pmdMain :modules:ui:pmdTest --continue -PtestParallelism=1 --max-workers=4 --console=plain
```

[Verification](workflow-continuation-verification.json) pins source hashes and raw
artifacts under `F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/`, prefixes
`c2-3-workflow-continuation1025`, `c2-3-workflow-continuation-negative1026`, and
`c2-3-workflow-continuation1027`. Keep through stage acceptance plus30 days and export
before worktree release. No live/model or hosted v3 proof is claimed.

Next bounded item: an LlmStep delegates through ConversationEngine and can start
another agent; its server-owned background posture must reach that shape too.
Section0 and C2-3-plan record the concrete existing engine-body projection mechanism.
Then connect the frontend private lookup and run integrated C2-3 acceptance. The direct
tool/gate background tests here do not claim coverage of that nested LLM path.
No prepared producer is activated; later C2 producers/recovery remain required.
