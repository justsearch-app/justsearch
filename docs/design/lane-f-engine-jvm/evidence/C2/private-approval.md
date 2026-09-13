# C2-3 private live approval lookup

September13, based on08ef6b262. GET /api/chat/approval projects an existing live
agent or workflow gate, with Cache-Control: no-store. Its argsSummary is the full
bounded frozen preview when present, otherwise the existing 200-character public
argument summary. Missing callId is400 and a missing gate is404. The read performs
no approval or dispatch and emits no key, nonce, payload or preview into SSE/history.
AgentRunQueries owns the read; PendingToolApproval is a transient projection over
existing gate state, not another pending store. The agent waiter now removes its
gate even when announcement throws or waiting ends without a reply; cancellation
also makes the private read empty before the waiter finishes cleanup.

Negative1018 replaces the full preview with the raw summary: both agent/workflow
endpoint assertions fail for the intended display mismatch. Its third failure
exposes the actual abandoned agent gate after announcement failure. Eight cases
are represented, three failures and no errors/skips. The final cleanup regression
preserves the original announcement exception and proves no readable gate remains.

Initial1019 executes1597 cases in232 suites, zero failures/errors and one inherited
skip, including all1183 UI cases. The build fails only on two PMD redundant Locale
qualifiers. Final1020 passes426 focused cases and PMD/integration compilation;
AgentController bytecode before/after that qualifier-only cleanup is identical.
Final1021 adds the cancellation read regression and passes427 cases in55 suites,
zero failures/errors/skips: agent149, services23 and UI12 execute; API243 reuses
unchanged successful1019 inputs. All affected PMD and UI integration compilation
pass. The full UI1019 run predates the final AgentSession cancellation read guard;
it is historical broad coverage, not a claim of a full suite at final1021.

Exact final1021 command (1020 identical; 1019 omits the two UI --tests filters):

```text
gradlew.bat :modules:app-agent-api:test :modules:app-agent:test --tests '*AgentApprovalPreviewTest' --tests '*AgentLoopServiceTest' --tests '*AgentToolDispatcherRetryTest' --tests '*AgentLoopServiceAuditTest' :modules:app-services:test --tests '*WorkflowShapeRunnerTest' --tests '*WorkflowToolRunnerImplTest' :modules:ui:test --tests '*AgentApprovalPreviewTest' --tests '*AgentControllerApprovalDispatchTest' :modules:app-agent-api:pmdMain :modules:app-agent-api:pmdTest :modules:app-agent:pmdMain :modules:app-agent:pmdTest :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:ui:pmdMain :modules:ui:pmdTest :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1 --max-workers=4 --console=plain
```

Canonical API documentation and regenerated indexes/skills pass llmstxt, skills-sync,
canonical-link, canonical module-dependency and runtime-configuration checks.
[Verification](private-approval-verification.json) records source hashes, counts,
commands and raw artifact inventory. Raw evidence is available under
`F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/` with prefixes
`c2-3-private-approval-negative1018`, `c2-3-private-approval1019`,
`c2-3-private-approval1020`, `c2-3-private-approval1021` and
`c2-3-private-approval-docs1020`. Keep through stage acceptance plus30 days and
export before worktree release. No live/model or hosted v3 proof is claimed.

The endpoint is connected to existing live gate owners; prepared agent/workflow
producers and the frontend lookup consumer remain the next C2-3 items. Workflow
snapshot association and background confirmation refusal are explicitly retained
in the connected-consumer plan. No prepared producer is activated by this item.
