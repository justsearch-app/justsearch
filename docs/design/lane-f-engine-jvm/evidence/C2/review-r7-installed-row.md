# R7: installed proof must read the operations substrate

September13, adce332f2 plus this harness-only item. The existing installed Engine
scenario now invokes core.ping-backend through the real HTTP dispatcher and reads
its one COMPLETE operations.db row. It compares the same id, key, kind, canonical
identity, receipt, attempts and timestamps after actual Engine death before the
supervisor restarts, then again after the successor becomes healthy. The existing
PROCESSING replay and path-idempotent retry assertions remain. The shared registered
driver holds a600-second lease for its330-second fixture budget and uses its existing
identity-verified crash and cleanup path.

Installed816 on Windows11/Temurin25.0.2/Node24.12.0 passes the unskipped JUnit case
in25.343s (Gradle28s), with UI integration-test compilation and system-tests PMD
passing. The same completed row is observed at all three points; structured row
values, XML hash, run id and accessible artifact paths are in the committed
[verification record](review-r7-verification.json). Registered stop reports
portsClosed:true. Subsequent quick_health reports ABSENT, no foreign runs or
inference orphan.

Negative815 adds the required row-marker assertions to the old jobs-only harness:
its processing replay succeeds, but the installed test fails for absent operation
proof. Negative817 temporarily restores the former AuditPolicy.NONE dispatcher
bypass. Ping still returns success; the installed scenario fails specifically with
`did not persist exactly one completed ping: []`. Its owned cleanup succeeds. The
production file is restored byte-for-byte immediately afterward. Run818 rebuilds
the correct distribution and reuses the exact matching816 test result from cache;
it is not another installed execution. No altered dispatcher code is committed.

```text
gradlew.bat :modules:system-tests:integrationTest --tests *OperationResumeE2ETest
  :modules:ui:compileIntegrationTestJava :modules:system-tests:pmdIntegrationTest
  -PskipWebBuild=true -PtestParallelism=1 --max-workers=4 --console=plain
```

Raw logs are tmp/c2-review-r7-negative-815.txt, tmp/c2-review-r7-816.txt,
tmp/c2-review-r7-negative-817.txt and tmp/c2-review-r7-818.txt, with matching counts
and XML siblings. Fixture816 is
tmp/lane-f-takeover/writer-junit-719b2b6b-b286-40f9-9885-6223e16134c9;
negative815 ends ba580f44-ec8c-474d-bca1-3b02129a2d66 and negative817 ends
8992765f-33aa-4e45-9e78-1dd285979a37 in the same parent. Retain through lane
acceptance plus30 days and export before worktree release.

This closes R7's missing installed-substrate witness, subject to the final batch
review. It does not close C2-11: six keyed write/reconfigure fault points still need
C2-3/C2-8/C2-10 and the batch4 gate. Batch1's dated results remain historical, with
its current status reopened for this witness and R9's later schema reproof.
