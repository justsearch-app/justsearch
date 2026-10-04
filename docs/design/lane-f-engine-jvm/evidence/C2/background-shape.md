# C2-3 background posture through nested shape delegation

September13, based onb8b458567. ConversationEngine's explicit server-side run
overload carries the enclosing background posture. The engine projects that fact
into its existing shape dispatch body and overwrites caller backgroundRun input,
just as it already owns recordsToThread. ToolIteratingShapeRunner invokes the
background-aware AgentService overload, and WorkflowShapeRunner preserves the fact
through LlmStep delegation. Urgency/survival are not repurposed as interactivity;
no additional context record, store, shape hierarchy or authority is introduced.

The connected engine/workflow/tool-iterating test supplies the opposite caller
value and verifies both server postures at the eventual agent call. Ordinary public
ingress ignores a caller claim to be background. Negative1028 deliberately changes
only the LlmStep's forwarded flag to false: the background case fails expected[true]
versus actual[false], while the other cases pass (nine represented, one failure,
no errors/skips including six always-included contract cases).

Final1029 passes747 represented cases in61 suites, zero failures/errors/skips:
services62 execute; full agent685 reuses unchanged successful1027 inputs. Services
PMD and UI integration compilation pass. The preceding full UI1027 run predates
this item; no full UI proof at1029 is claimed.

```text
gradlew.bat :modules:app-services:test --tests '*WorkflowBackgroundDelegationTest' --tests '*WorkflowPreparedContinuationTest' --tests '*WorkflowShapeRunnerTest' --tests '*WorkflowToolRunnerImplTest' --tests '*ConversationEngineTest' --tests '*ToolIteratingShapeRunnerTest' :modules:app-agent:test :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1 --max-workers=4 --console=plain
```

[Verification](background-shape-verification.json) records source hashes and raw
artifacts under `F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/`, prefixes
`c2-3-background-shape-negative1028` and `c2-3-background-shape1029`. Keep through
stage acceptance plus30 days and export before worktree release. No live/model or
hosted v3 proof is claimed. Frontend private lookup and the integrated C2-3 gate
remain next; later C2 producers/recovery are still required.
