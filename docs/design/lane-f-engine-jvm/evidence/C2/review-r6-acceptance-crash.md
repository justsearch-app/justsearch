# R6: kill after acceptance, before the first effect

September13, 2d62e3c4b plus this test-only item. The named C2-2 test now exists:
SqliteOperationStoreTest.haltAfterAcceptanceBeforeFirstEffectLeavesAcceptedRowAndEmptyEffectStore.
The child uses the real attempt runner to accept a durable operation, then halts its
JVM without close/hooks before start or the SQLite effect body. The parent reopens
operations.db and asserts the same key is ACCEPTED, attempts zero, no start/receipt,
and zero effects in a separate effects.db table. Retrying that key returns the same
row id and creates nothing.

Two additional child modes deliberately halt on the wrong sides of that boundary:
before acceptance and after the first committed effect. Both must fail the same
acceptance witness; the latter additionally proves exactly one effect was committed.
This prevents a setup-only crash or an after-effect crash from satisfying the test.
The original six quarantine/schema child crash points remain covered.

Final814 executes20 cases in2 suites with zero failures/errors/skips. PMD and UI
integration-test compilation pass. [Exact command and counts](review-r6-verification.json).
Negative812 fails because the requested crash point did not yet exist. Run813's
cases pass but PMD rejects VM termination in a private helper; the fixture's crash
logic now lives directly in its main entry, like its existing six points, without
rule suppression. The two wrong-side witnesses execute in the passing final suite.

Raw evidence: tmp/c2-review-r6-negative-812.txt, tmp/c2-review-r6-813.txt and
tmp/c2-review-r6-814.txt, with matching -xml/ and -counts.json siblings. Keep through
lane acceptance plus30 days and export before releasing the worktree. This is the
named child-JVM unit proof, not the installed C2-11 six-scenario recovery proof.
Full batch verification and final independent review remain pending.
