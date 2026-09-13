# C1-7 commit timer actual-exit proof

2026-09-09, Windows / Temurin 25.0.2, lane-F-A. Tested changes over `6d94ef433`;
the item commit carries these files. C1 remains open.

CommitOps.stopCommitTimer cancels its periodic future without interrupting an active commit,
then closes the concrete executor and clears its references only after actual termination.
An interrupted closer escalates to shutdownNow through ExecutorService.close, continues waiting,
and restores interruption after the callback exits. The logical Lucene executor registration
stays open for later runtime instances. No new state machine or shutdown helper was added.

Root re-read RuntimeSession.closeResources: stopCommitTimer precedes writer/directory close.
The production terminal-writer notification launches EngineRoot's separate exit thread; no
production timer callback closes its own runtime synchronously. Thus direct executor close does
not introduce a self-join in the current owner graph. This is the same actual-exit ownership
choice as the KnowledgeServer producers in C1 section13.

The regression runs the real scheduled commit and blocks its completion callback while ignoring
interruption. It interrupts the closer, proves that executor/future references remain published
and stop still waits, then releases the callback and checks termination, interrupt restoration,
reference clearing and opening a replacement timer through the same logical registration.
Cleanup releases/joins the callback and stopper even if an assertion fails. This is a concrete
scheduler lifetime/reuse test, not a production registry max-instance accounting test.

- Run77: CommitOps tests passed; PMD rejected two existing fully-qualified test references made
  redundant by the new imports. Run78 removed one but missed the cast qualifier; PMD still red.
  Root removed the remaining qualifier without changing test behavior.
- Run79: CommitOps tests and PMD test passed.
- Mutation80: restore the old full stop method, including its interrupted-wait early return.
  Compilation succeeded; the new regression failed because the stopper was no longer alive while
  its interrupted callback remained blocked. Script restored source in finally.
- Run81: restored tests plus PMD main/test passed using valid Gradle cached outputs.

Evidence: logs `tmp/c1-batch4-commit-timer-{77,78,79}.txt`,
`tmp/c1-batch4-commit-timer-mutant-80.txt`, `tmp/c1-batch4-commit-timer-restored-81.txt`;
XML snapshots `tmp/c1-batch4-commit-timer-green-{79,81}` and mutant80 directory. Retain through
lane completion plus30 days. No new full/stress/live/hosted/platform claim follows from this slice.
