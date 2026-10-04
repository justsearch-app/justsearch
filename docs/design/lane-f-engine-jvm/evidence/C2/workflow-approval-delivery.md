# C2-3 workflow approval delivery correction

September13, based on082119b34. WorkflowShapeRunner registers its existing future
before announcing the pending call and discards it on every exit. A sink failure
propagates unchanged after cleanup. A synchronous approve or reject can therefore
find its gate instead of being lost before registration.

WorkflowToolRunnerImpl now translates pending/approved/rejected controls into the
existing typed AgentEvents, retaining the inner call id and public arguments. Other
workflow events remain progress. The workflow's explicit wait carries INLINE_CONFIRM
or TYPED_CONFIRM from its risk, so the enclosing agent's AUTO dial cannot remove it.
The existing unified reply endpoint already falls through from the outer session's
gate lookup to WorkflowGateRegistry; the new test exercises that nested session case
for both approve and reject. No new event schema, gate registry or persistent payload
is introduced. Frozen previews are not added to events or history.

Negative1012 runs the four new synchronous-reply/nested-event cases against the old
implementation. All four fail for their intended reasons: missing registered gate,
and AgentProgress instead of an answerable ToolCallPendingApproval. Ten cases are
represented including six always-included contract cases. Raw XML is preserved.

Initial1013 represents32 cases with one failed new cleanup test and one PMD test rule
violation. The cleanup test incorrectly expected an error event, while this runner
propagates the original sink exception; the final test asserts that exact exception
and an absent gate. The PMD UnnecessaryFullyQualifiedName finding used an already
imported AtomicReference. No existing test intent or product failure propagation was
weakened. The test XML/log remain available; the intermediate PMD XML was overwritten
by the successful rerun and is not claimed as retained raw evidence.

Final1014 passes32 represented cases in8 suites, zero failures/errors/skips. The
services task executes; UI reuses its unchanged successful1013 result. Affected PMD
and UI integration-test compilation pass. Exact counts, source hashes and accessible
raw artifacts are in [verification](workflow-approval-delivery-verification.json).

```text
gradlew.bat :modules:app-services:test --tests '*WorkflowShapeRunnerTest' --tests '*WorkflowToolRunnerImplTest' :modules:ui:test --tests '*AgentControllerApprovalDispatchTest' :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:ui:pmdMain :modules:ui:pmdTest :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1 --max-workers=4 --console=plain
```

Raw prefixes at `F:/justsearch-public/.claude/worktrees/lane-F-A/tmp/` are
`c2-3-workflow-approval-negative1012`, `c2-3-workflow-approval1013` and
`c2-3-workflow-approval1014`, each with log, counts and XML. Retain through stage
acceptance plus30 days and export before worktree release. This is focused backend
proof; prepared continuation, private preview lookup, nested snapshot reattachment
and the integrated/live/hosted C2-3 boundary remain required.
