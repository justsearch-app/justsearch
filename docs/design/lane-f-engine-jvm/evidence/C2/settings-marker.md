# C2-6 cut2: durable preparation marker

2026-09-13. The existing accepted_settings_revision column now has a runner-only
armSettingsRevision transition and an internal expectedSettingsRevision projection. No schema
migration or second expected-revision authority is introduced. The
[verification manifest](settings-marker-verification.json) pins the tested source and artifacts.

One conditional SQL UPDATE requires RUNNING, kind settings-apply or reconfigure, and an absent
marker. Revision zero is valid; negative or Long.MAX_VALUE revisions cannot advance and refuse.
Arming never emits terminal callbacks. Repeated arming cannot change the recorded revision;
completed/failed/cancelled rows remain immutable even when their marker is still null.
The marker is the expected revision, not the committed revision (which will be expected+1).

Marker1268 passes45 represented cases in17 suites with no failures/errors/skips, all test tasks
executed. The marker tests prove both settings kinds, eligible zero, restart retention,
unarmed terminal refusal, wrong-kind refusal, concurrent one-winner arming, SQL failure leaving
the marker null, and no completion notification. The launcher ownership gate includes arming;
its unauthorized-writer fixture proves a foreign producer is rejected. PMD and explicit
spotlessCheck tasks pass. Raw JVM/compiler warnings remain visible. Run duration2m17s includes
recompilation of dependent modules after the internal record projection changed.

Independent read-only review added direct unarmed-terminal and eligible revision-zero coverage
and found no remaining code/test blocker.

This sub-batch does not connect the runner to a settings owner or implement committed receipts,
file/row reconciliation, public outcome projection, all-producer migration, or the full fault
matrix. Those are the next C2-6 cuts. Full/stress, installed and hosted proof remain required.
Artifacts are retained through lane acceptance plus30days and exported before worktree release.
