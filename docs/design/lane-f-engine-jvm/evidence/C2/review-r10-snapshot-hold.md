# R10: complete the unused snapshot retirement

Full858 at33e54dfcd passes the Engine suite, then fails UnreferencedCodeTest because
WatchedRootsState.snapshotBindings has only test callers after R9 removed its
production prepared-plan caller. Output `tmp/c2-review-r10-full858.txt` and XML
`tmp/c2-review-r10-full858-xml/` preserve2564 cases/351 suites and the single failure.

The helper and its immutable/atomic-snapshot fixtures move to the held source packet
supplement, pinned to33e54dfcd. Existing concurrent duplicate registration still proves
one member, one label, one queued walk and one watcher; removal races still prove no
resurrected membership or label. Those assertions use existing state accessors rather
than the retired future projection. Both original and supplemental source patches
remain reviewable; the supplement reverse-application check passes. No unused helper
was rooted, suppressed or artificially wired to make the gate green.

Initial861 compilation exposed RootCompletionMembershipTest's additional caller;
its empty-membership check now uses the original map plus the existing collection
accessor. Focused862 executes45 cases/22 suites across both tasks, with no failures,
errors or skips. PMD and UI integration-test compilation pass. Exact command/counts
are in review-r10-snapshot-verification.json. Raw
`tmp/c2-review-r10-snapshot{861,862}.txt` and `tmp/c2-review-r10-snapshot862-xml/`.

R10 remains open. The next full run uses Gradle --continue to collect independent
failures without treating them as success, after two early-stop runs left later
modules untested. Retain raw evidence through lane acceptance plus30 days and export
before releasing the worktree.
