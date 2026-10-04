# R8 final-review correction: transaction outcome and cleanup

The prior helper restored auto-commit even when work threw Error or rollback failed.
It also skipped commitSucceeded after a confirmed commit if auto-commit restoration
failed. Named real-SQLite fixtures update the job before failing ledger preparation;
an independent connection observes partial DONE in negative889. The post-commit
reset fixture observes DONE but no projected delta. All four behavioral cases fail
(10 executed cases). Preliminary888 caught missing rollback/callback calls first;
889 strengthens the assertions to externally visible state and delivery.

The corrected helper tracks confirmed commit/rollback and retains the primary cause
plus rollback, reset, projection and close failures. It never restores auto-commit
while rollback is uncertain. Any unresolved rollback/reset makes the queue owner
unavailable, including captured feed subscriptions, until successful close. Failed
close retains its handle; open cannot discard that owner. This is one in-memory
failure cause under the existing lock, not another durable recovery state. The
existing queue Health signal records degradation. Outcome exception wording no
longer falsely claims a rollback when commit actually succeeded.

Preliminary890 crashes in Xerial NativeDB.set_update_listener: removing listeners
after native connection close is unsafe. The retained native stack establishes the
ordering defect; incomplete XML is not a passing run. Retirement now happens at the
outer queue unlock: deliver frozen confirmed deltas, detach/close the feed while
SQLite is alive, then close the failed connection. The same failure cause refuses
reentrant queue work and snapshots throughout; cleanup failures attach to it. This
avoids a second feed lifecycle or listener-detachment marker.

891 passes77 cases. Root then separates the rollback-failure case from the unhandled
Error case: a RuntimeException after SQL plus rollback SQLException must retain the
original cause. Restoring only the old transaction helper fails negative892 (7 cases,
1 failure: rollback replaces the primary exception). 893 restores the corrected
helper and executes77 cases/5 suites, zero failures/errors/skips. Root then verifies
a subscriber Error during retirement cannot replace the reset failure: negative898
fails this compound-cause witness. Final899 preserves delivery/feed-close failures on
the existing primary cause and executes78 cases/5 suites, zero failures/errors/skips. Both PMD tasks and
UI integration-test compilation pass. The close-failure fixture also proves the handle
survives a refused close, and successful cleanup/reopen retains PENDING. Existing
committed-chunk, reentrant callback, claim bookkeeping and projection-failure tests pass.

Exact command/counts: review-r8-transaction-verification.json. Canonical risk guidance
is updated; docs index generation894, index check895, skill check896 and canonical
links897 pass. No embedded skill section takes this risk document as a source.

Raw: `tmp/c2-review-r8-transaction-negative888.txt`,
`tmp/c2-review-r8-transaction-negative888-xml/`, `tmp/c2-review-r8-transaction-negative889.txt`,
`tmp/c2-review-r8-transaction-negative889-xml/`, `tmp/c2-review-r8-transaction890.txt`,
`tmp/c2-review-r8-transaction890-xml/`, `tmp/c2-review-r8-transaction890-native-crash.log`,
`tmp/c2-review-r8-transaction891.txt`, `tmp/c2-review-r8-transaction891-xml/`,
`tmp/c2-review-r8-rollback-negative892.txt`, `tmp/c2-review-r8-rollback-negative892-xml/`,
`tmp/c2-review-r8-transaction893.txt`, `tmp/c2-review-r8-transaction893-xml/`,
`tmp/c2-review-r8-doc-index894.txt`, `tmp/c2-review-r8-doc-index-check895.txt`,
`tmp/c2-review-r8-skills896.txt`, `tmp/c2-review-r8-links897.txt`,
`tmp/c2-review-r8-cleanup-cause-negative898.txt`, `tmp/c2-review-r8-cleanup-cause-negative898-xml/`,
`tmp/c2-review-r8-transaction899.txt`, and `tmp/c2-review-r8-transaction899-xml/`.
Retain through lane acceptance plus30 days and export before worktree release.
Fresh integrated/hosted proof follows all final-review corrections.
