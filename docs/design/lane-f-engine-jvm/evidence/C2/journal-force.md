# C2-4 journal durability barrier

Follow-up integrated1180 finds the test factory disconnected from production.
The [factory correction and focused1182 proof](integrated1180.md) consolidate that
route without altering force/ack ordering or weakening the dead-code gate.

Implemented 2026-09-13 from652949e43. The existing journal crosses FileChannel.force(true)
before append returns success. Retried retained identities force their actual file
generation too: a complete line parsed after an uncertain force is not sufficient
to acknowledge the source. Failure preserves false acknowledgement and invalidates
the derived index; retry rebuilds it without adding a duplicate line. No new writer,
file format, persistent identity registry or schema version is introduced.

Negative1155 introduces the test seam without invoking its barrier: both intended
tests fail.1156 executes41 passing cases, with PMD and integration compilation.
Independent review identifies missing rotated-generation proof. Add the regression
and mutate retained retry to force the active file; negative1157 fails exactly at
the expected generation-path assertion. Restore exact source bytes. Final1158 passes
47 represented cases:32 observability execute,15 UI reuse successful1156 inputs;
test PMD executes, main PMD/integration reuse1156. The existing journal retry suite
also executes in1158. Zero failures/errors/skips.

Independent read-only reviewer c2_pending_source_review checks write/barrier/identity
ordering (ActionEventJournal:186-210), retained-generation forcing (:218-228), and
rotation mapping (:282-295). After the added test (:47-72), no actionable source or
privacy findings remain. This is a narrow prerequisite review, not batch closure.
[Exact commands, source hashes, counts and artifacts](journal-force-verification.json)
retain normal real file-force execution plus injected failures. They do not simulate
physical power loss or establish consumer append/ack scheduling, installed recovery
or hosted success. Raw lane tmp evidence is retained until acceptance plus30 days
and exported before worktree release.

Next: first correct the reader review's client-effect/server-operation ID collision,
then attach the completion consumer and finish retry/shutdown/SSE proof. C2-4 and the
lane remain open, including coherent full and installed-v5/hosted checks.
