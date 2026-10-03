# C2-3 projected workflow delegation and terminal metadata

2026-09-13, based on85fad8249. The metadata fixture exposed a real composition
mismatch. CoreWorkflowCatalog declares USER workflows containing USER LlmSteps;
WorkflowOperationProjection intentionally makes their operations AGENT-facing.
WorkflowToolRunnerImpl passed that projection audience into the source workflow,
so the engine correctly refused its USER-only nested shape. The fix uses the
catalog-resolved workflow audience. It does not change the engine's audience check
or the workflow tool node intent gate. The projection remains AGENT-facing.

The source mapping is WorkflowOperationProjection.java:36-56, CoreWorkflowCatalog,
ConversationEngine.validateAudience and the actual bridge-to-runner call. Passing a
caller-chosen audience or broadly allowing AGENT into USER shapes is rejected: the
registered composition declaration already owns the intended audience. Only the
bridge's projection mistake is corrected.

A separate node exception bypassed the event-driven terminal metadata write. A
finally block now checks the existing runMeta state and changes a still-RUNNING
record to ERROR with an updated timestamp. The original exception propagates to the
existing transport owner; a capture/rethrow retains it while cleanup attaches
any storage failure as suppressed. No failure is converted into success. Existing done/error metadata is preserved. This reuses the run's current
state map rather than adding an independent lifecycle or recovery marker.

Negative1054 represents17 tests with three intended failures: true/false nested
LLM delegation is refused, and a thrown node leaves RUNNING instead of ERROR.
The companion direct AGENT-to-USER denial passes. Final1055 executes80 focused
workflow/engine/agent-shape cases across13 suites, zero failures/errors/skips;
services PMD and integration compilation pass. A trailing-space check after the
try/finally indentation was corrected without semantic edits. Final1056 rebuilds
that source and PMD, reusing the bytecode-equivalent successful80-case test task
from1055; it does not represent another test execution.

The real RunEventStore fixture checks RUNNING then DONE plus both background
values through an actual projected LlmStep and ConversationEngine/agent runner.
A throwing agent node retains the same exception instance and leaves ERROR metadata.
Direct AGENT invocation of the USER-only shape is still denied before agent execution.
No model is run by these fixtures; live/model proof remains required.

Canonical API documentation and allfive checks pass1056 after regeneration.
[Verification](workflow-delegation-verification.json) records commands, source hashes,
counts and reuse. Raw logs/counts/XML use the1054 negative and1055/1056 prefixes under
F:/justsearch-public/.claude/worktrees/lane-F-A/tmp. Keep through stage acceptance
plus30 days and export before worktree release. The full integrated build and C2-3
live/hosted acceptance remain required. C2 and subsequent stages remain open.

## Cleanup-write correction after independent review

The read-only follow-up confirms the declared-audience correction, then finds that
a failing metadata write in finally can replace the active node exception. The
real-store negative1057 creates a directory at meta.json during a node failure;
its one failing assertion among18 cases observes CorruptDurableStoreException in
place of the original node exception. The final fixture makes that directory
nonempty to retain the same replacement refusal across platforms.

Retain the thrown RuntimeException/Error in a stack-local reference and immediately
rethrow it. The existing finally still attempts terminal metadata; a cleanup failure
is added as suppressed to the primary failure, or propagated if there is no primary.
No failure is swallowed and no extra persistent lifecycle state is added. The test
asserts the original exception instance, one suppressed CorruptDurableStoreException
and the real blocked metadata path. Final1058 executes81 cases across13 suites,
zero failures/errors/skips, with PMD/integration compilation green. This supersedes
the80-case1055/1056 verification, which remains retained history.

The independent follow-up used the existing worker under the native task cap; root
owns the correction and the actual filesystem regression. All source/artifact
limits and remaining integrated/live/hosted obligations above still apply.
