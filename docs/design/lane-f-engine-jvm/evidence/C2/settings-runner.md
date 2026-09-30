# C2-6 cut2: settings runner authority and committed receipt

2026-09-13. The runner now accepts a fixed app-api SettingsCommitOwner collaborator. It
reserves with that owner before arming the existing SQL marker and before preparation.
The handler supplies only its actual live runner handle on its executing body thread;
commit authority is a separate private object. The port carries a prebuilt successful
OperationResult with authoritative key/revision and typed precommit refusals. Rich response
data remains in memory; the row persists only bounded receipt metadata. A completed keyed
retry derives the original revision from its stored expected revision, without reapplying.

Settings commitment is synchronous inside the body. On return, the runner completes from
the committed receipt even after an ordinary exception or a pending adapter stage. A typed
precommit refusal preserves its code/details in the immediate result and its bounded code
in the row. Missing/contradictory callbacks and swallowed owner Errors remain unresolved.
A settings kind with a composed owner cannot succeed by skipping applySettings. The runner
terminalizes before releasing the matching fence and publishing its completion future.

Errors/uncertainty request ordered restart inside applySettings, before a handler can swallow
them; a per-attempt flag prevents duplicate restart requests. A failing restart callback is
suppressed onto the primary failure without preventing observation/degradation publication.
An Error before settings starts does not request a settings restart. Boot sends both settings
kinds together to the fixed owner before per-row decisions. Public reconcile cannot override
that owner's Wait. Resumed/background owners can create fresh synchronous settings children.
The architecture gate rejects producer calls to owner/commit-control methods as well as SQL
marker/terminal writes; its unauthorized fixture exercises both forbidden capabilities.

## Verification

Final1281 passes91 cases across19 suites, all test tasks executed, with no failures/errors/
skips. This includes28 settings-runner cases,16 generic-runner cases,6 SQL-marker cases,
one inherited diagnostic guard and40 launcher architecture cases (10 in the operations
store architecture suite). PMD and explicit format checks pass. The
[verification manifest](settings-runner-verification.json) binds exact source, logs and XML
archives; the final restored runner hash matches the independently reviewed snapshot.

Failed1277 stops at one PMD redundant-qualifier violation before tests execute. Root removed
the qualifier. Intermediate1278 passes85 cases/19 suites, and intermediate1279 passes90/19,
all executed with no failures/errors/skips. They predate the final swallowed-Error correction
and do not substitute for final1281. Raw compiler/JVM warnings and failed attempts are retained.

Negative1280 deliberately disables three protections and executes6 cases in2 suites, with
three intended assertion failures: off-thread use no longer throws, the prepared result loses
restartScheduled, and a null committed receipt becomes FAILED instead of RUNNING. No compile
failure caused those results. Root restores the runner byte-for-byte afterwards. Public recovery
refusal, no-callback, missing-apply and swallowed-Error have direct regressions, but were not
independently mutated in this campaign; no broader negative-proof claim is made.

Independent read-only source review is clear on the corrected runner/test snapshot, including
reservation order, receipt selection, restricted recovery and Error handling. Artifact retention
is through lane acceptance plus30days, with export before worktree release.

## Remaining owning work

This is a runner protocol slice over real SQLite and a fake fixed owner. SettingsCommitOwner
has no production file-owner implementation or call site yet; the three-argument composition
remains in HeadlessApp and LauncherEnvironment. Next is SettingsCommitCoordinator's physical
file/config fence and witness reconciliation, then all producer/fallback migration and recovery
Health. OperationOutcomeView and generated HTTP/MCP schema projection remain a consumer cut;
the runner's immediate/retry result alone does not prove that wire contract. Full fault/live,
full/stress, installed-schema5 and hosted checks remain required. C2 and the lane stay open.
