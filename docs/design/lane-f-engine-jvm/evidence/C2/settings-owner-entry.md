# Settings reset owner entry correction

2026-09-13, based on35ec28055. Full test attempt1340 found a real architecture
violation: normal reserveReset called the runner-only public reserve entry on itself.
The fixed coordinator shares the unchanged reservation checks through private
reserveOwned under each public entry mutex and creates the final-purpose fence once.
It does not allowlist the coordinator. The guard now covers reserveReset and applyReset;
separate unauthorized fixtures must each appear in its violation report.

Independent Sol review of the actual two-file diff is clear. Root verified:

| Run | Result and scope |
|---|---|
|1338|Full build -x test passes; integration outputs contain30 cases/10 suites,10 inherited skips.|
|1340|Full test attempt fails:2635 cases/363 suites across12 observed tasks, one actual owner-entry violation; later modules did not run.|
|1341|81 focused cases/21 suites execute green; PMD rejects one unnecessary fully qualified name in the new fixture.|
|1342|Qualifier/indentation cleanup passes PMD/format;81 unchanged compiled test cases reused.|
|1343|Removing only applyReset from the guard yields36 cases, exactly one intended failure at the unauthorized-apply assertion; prior violation assertions pass.|
|1344|Byte-exact restoration of both final source hashes;81 focused cases pass with PMD/format.|

The source hashes and logs/counts/XML archive locations are in
[verification manifest](settings-owner-entry-verification.json). Raw artifacts remain
under the lane worktree tmp directory through lane acceptance plus30 days, with export
before worktree release. These are local proofs. The corrected full suite and successor
candidate hosted checks are still required. C2-6 production composition, producer/wire,
Health and installed successor-bootstrap acceptance remain open.
