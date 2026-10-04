# C2-3 connected agent preparation continuation

September13, based one92a4eb79. AgentStepRunner applies document scope before
preparation and approval. Original model arguments still own loop/history semantics.
AgentToolDispatcher reads the existing issuance policy once, prepares without
consent or effects, keeps the same OperationDispatchPlan on its executing stack,
and routes that reference through every policy retry after approval. Its existing
live gate privately retains the full frozen preview. A recorded plan skips another
prompt and queries the current receipt without minting consent. Background refusal
and WATCH/AUTO policy remain in the existing gate; a projected workflow wrapper
keeps its separate streaming path and prepares its inner nodes in the next item.
No extra pending store or authority is introduced. Router-absent legacy tests retain
the audited sole direct dispatcher fallback.

The new real-loop regression observes scoped arguments at preparation before any
prompt and exact equality at keyed dispatch after approval. Negative1022 deliberately
bypasses scope before preparation: its single case fails at the missing docIds
assertion, not a timeout. Separate tests cover WATCH LOW approval, private display,
rejection, one preparation across two retry attempts, recorded receipt requery
without consent, background refusal before preparation and workflow wrapper routing.

Initial1023 passes978 represented cases in103 suites with zero failures/errors/skips:
all684 app-agent cases execute, services39 and UI12 execute, API243 reuses unchanged
successful1019 inputs. Its build fails only on two PMD Optional qualifiers made
redundant by the added import. Final1024 removes those qualifiers and passes the
same item gate including PMD and UI integration compilation; all four test tasks
are UP-TO-DATE because compiled test/runtime inputs are unchanged by the cleanup.
This explicitly reuses1023 rather than claiming978 newly executed tests at1024.

```text
gradlew.bat :modules:app-agent:test :modules:app-agent-api:test :modules:app-services:test --tests '*PreparedOperationDispatchTest' --tests '*WorkflowToolRunnerImplTest' --tests '*WorkflowShapeRunnerTest' :modules:ui:test --tests '*AgentApprovalPreviewTest' --tests '*AgentControllerApprovalDispatchTest' :modules:app-agent:pmdMain :modules:app-agent:pmdTest :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1 --max-workers=4 --console=plain
```

[Verification](agent-continuation-verification.json) records final source hashes and
raw artifacts under `F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/`, prefixes
`c2-3-agent-continuation-negative1022`, `c2-3-agent-continuation1023` and
`c2-3-agent-continuation1024`. Keep through stage acceptance plus30 days and export
before worktree release. No hosted v3 or live/model proof is claimed. The actual
SQLite/router/consent boundary remains covered by PreparedOperationDispatchTest,
while this item exercises the previously disconnected agent loop/approval/retry seam.
Workflow continuation, nested snapshot association, frontend lookup and the integrated
C2-3 live/hosted acceptance still remain. No prepared producer is activated yet.
