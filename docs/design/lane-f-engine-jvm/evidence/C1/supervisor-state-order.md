# C1 inherited supervisor publication ordering

Hosted34350223349's first migration recovery attempt recorded all three requested exits but
timed out observing the rollback successor. The preserved fixture output reports EPERM renaming
supervisor.v1.json.tmp to supervisor.v1.json. The handoff watcher publishes STOPPING without
awaiting it; the child-exit handler can concurrently publish RESTARTING through the same fixed
temporary filename. Unique temporary names would still allow stale transitions to land last.

One promise chain owned by the existing supervisor run now serializes its state-record writer.
Records are captured when the transition occurs, then published in that order. No second state
authority, per-path registry or new on-disk protocol is introduced. A rejected publication still
reaches its caller and cannot poison later writes; the production filesystem error logging remains.
Design section0 assigns this correction to inherited B8 under C1 verification.

The existing dev-runner supervisor unit runner now holds the first actual writer call while
submitting the next transition. Adverse247 observes both entering the shared writer concurrently
and fails. Restored248 passes, proves stopping→restarting order, the final on-disk restarting
record and no remaining temporary file. The complete existing helper suite passes as well.
Logs: `tmp/c1-supervisor-order-before-247.txt`, `tmp/c1-supervisor-order-restored-248.txt`.
The hosted artifact is retained under `tmp/c1-hosted-artifacts-233/` and the run's uploaded
integration-test-results artifact. Installed239 predates this writer correction; a final installed
migration run and hosted proof remain required. This source repair does not claim them complete.
