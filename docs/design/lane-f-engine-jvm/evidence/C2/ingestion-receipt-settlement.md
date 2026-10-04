# C2-9b.3b.3 receipt-only settlement — 2026-09-14

Parent-owned cut2 on13dd53189 plus the settlement diff. This composes the real
operation runner with the existing queue receipt; it does not activate a producer.

## Behavior

RecordedIngestionSettlement requires the outer owner to revoke permission first.
It waits for actual enumeration exit and the locked issued-claim drain before reading
receipt evidence. Missing/corrupt binding or decreasing already-confirmed counts
selects INGEST_UNIT_STATE_UNAVAILABLE without replacing the last confirmed checkpoint.
A matching checkpoint permits direct terminal reconciliation without another attempt;
a stale checkpoint uses a winning Resume only below the total durable-operation limit3.
The constant is shared policy, not a claim that every generic runner path enforces it.

The winning body rechecks the plan and exact sealed receipt, including stored-byte digest,
before checkpointing. The existing runner owns COMPLETE, FAILED and CANCELLED writes.
Acknowledgement independently rereads the durable terminal row and requires exact cursor,
counts, state, receipt code and null executionId before acknowledging the queue revision.
A failed terminal write cannot release queue retention. Cancellation uses the runner's
asynchronous cancellation outcome so it does not abort the remaining recovery cohort.

## Verification

1712 initial focused run passed11 cases (seven settlement plus four pure receipt).
Independent review found that combined fixtures did not discriminate several guards.
Root separated enumeration-exit and issued-owner barriers, added decreasing-counter and
same-revision/different-byte receipt cases, and strengthened the terminal-write failure
trigger to reject only COMPLETE. Production validation was preserved.

1715 final focused run passed15 cases/two suites, zero skips/failures/errors, with four
PMD tasks and format. Eleven settlement cases use the real SQLite operations store,
runner and queue; four pure receipt cases remain separate.1714 bypassed the attempt
ceiling and produced the expected FAILED-versus-COMPLETE assertion failure.1717 bypassed
six guards independently: enumeration exit, issued claims, decreasing counts, inner plan
reread, exact receipt-byte reread, and terminal-match acknowledgement. All six selected
regressions failed at their intended assertions. Sources were restored byte-for-byte.
The restored settlement SHA256 is760836AFC119CC9E6CFC4A7F5F98A70FF4E90DF1A1C24B7870EDE99BA6E794A8.
An independent reviewer reread the restored source and preserved1715/1717 XML and cleared
this bounded cut. Integrated1718 executed all three full suites: Engine255/53 suites,
API233/46 and operations store581/87, totaling1,069 cases/186 suites with zero skips,
failures or errors. Six PMD tasks and format pass. The exact command, revision and XML
are preserved in1718-counts.json and1718-xml/. Final1720 documentation regeneration,
canonical links/runtime matrix, all three operation/execution/guard-resolution gates and
store-recoverability checks pass.

The issued-owner fixture preserves valid progress while changing its plan binding.
Removing progress while a claim is live correctly prevents returnUnfinishedClaims from
pretending its durable terminal bookkeeping succeeded. Such a claim remains fenced and
owned; a later process reopen has no process-local owner. This test does not waive that
failure or claim that a corrupt live queue can always drain without process recovery.

Artifacts are tmp/1712-counts.json,1714-counts.json,1715-counts.json,1717-counts.json,
the matching numeric logs and -xml directories,1717-command.txt, and byte originals
1714-settlement-original.bin/1717-settlement-original.bin under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/. Retain through final lane
reconciliation plus30 days, at least2026-10-14.

## Remaining and hosted boundary

The real stable parent/child coordinator must supply revoke-before-drain ordering,
admission, strict accepted-parent binding, private winning fresh origin, restart-only
revalidation, actual producer exit, parent acknowledgement ordering, maintenance and
final-drain/replacement wiring. The fixture's ordinary ingest row proves receipt mechanics;
it is not proof of production child binding or a connected producer. These remain cut3
and d.3b. C2 remains open and merge remains at stage F.

Foundation commit13dd53189's CI34868482004 failed the app-launcher unreferenced-code guard
in the app-ui job; the new package-private projection is not yet connected to a production
owner. The guard must remain effective. The next owner wiring is the intended correction,
not a baseline exemption. Exact hosted logs are tmp/1719-hosted-failed.txt and metadata
1719-hosted-ci.json; the downloaded app-ui XML confirms exactly RecordedIngestionReceipt
and its terminalMatches method, each repeated by three attempts. The other12 CI jobs and
CLA34868477831 passed. Cut2 is pushed23f9221be. CI34870994136 has11 successful jobs and two dead-code failures:
app-ui flags RecordedIngestionSettlement and its acknowledge/reconcile methods; platform-contracts
flags the same unreferenced class. CLA34870989825 passes. Exact downloaded XML/logs are
in tmp/1723-hosted-* under the same retention rule. The current coordinator wiring consumes
these methods; its required whole-program verification is not yet complete.
