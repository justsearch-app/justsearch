# C2-3 persisted workflow background posture

2026-09-13, based on8a3fbc4ac. The independent read-only backend check found
WorkflowShapeRunner still wrote background=false despite receiving the server-owned
posture. Persist that posture in the existing run metadata map. No new field or
permission is introduced.

The real RunEventStore fixture reads meta.json at the session_started append and
after synchronous completion. Both must carry the server boolean, with RUNNING and
DONE respectively. It covers true/false through direct USER engine invocation and
through the actual nested workflow bridge. Direct execution delegates to the agent
shape; nested execution uses an AUTO gate, which is permitted for that caller.

Initial negative1040/1041 represent13 tests with three failures. One detects the
metadata defect; two expose an independent audience refusal: a nested LlmStep from
an AGENT workflow cannot invoke the USER-only core.agent-run shape. The diagnostic
run preserves that exact refusal. This is not proof of nested LLM execution. The
metadata fixture is corrected to a permitted AUTO gate for the nested case, keeping
the direct LLM assertions. Negative1042 then reports exactly two intended failures
(expected true, persisted false) among13 tests. The USER/AGENT integration path and
its terminal metadata on refusal are a separate required C2-3 follow-up under
investigation; audience permissions must not be weakened to make the fixture pass.

Final1043 executes76 workflow/engine/agent-shape tests across13 suites, zero
failures/errors/skips. Services PMD passes; UI integration compilation reuses its
unchanged successful inputs. The full integrated build remains required after the
pending stream-lifetime and nested-delegation corrections.

[Verification](workflow-meta-verification.json) records commands, summaries and
source hashes. Raw logs/counts/XML are retained under
F:/justsearch-public/.claude/worktrees/lane-F-A/tmp with prefixes
c2-3-workflow-meta-negative1040 through1042 and c2-3-workflow-meta1043. Keep through
stage acceptance plus30 days; export before worktree release. C2 remains open.
