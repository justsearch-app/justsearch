# R8 correction-review follow-up: retain the queue close owner

The final review's first correction check at2168d1245 resolves the running-transition,
canonical alias, metadata-loss and native-lock-close findings. It identifies one
remaining queue ownership finding with two paths: normal close retains its failed
connection without setting the reuse guard, so open overwrites it; KnowledgeServer
swallows queue-close failure before releasing index exclusion and publishing completed
shutdown. EngineRoot trusts that completion and can discard the server owner.

Negative903 executes8 cases and fails the two named paths. The direct test uses a
real SQLite connection whose close fails once, verifies reopen is refused, then
requires successful cleanup/reopen with the existing PENDING row. The server test
requires the original close error, incomplete awaitClosed, retained queue and index
lock, no premature lock close, and ordered successful retry. Neither changes the
existing best-effort semantics of unrelated cleanup owners.

Use the existing connection-wide failure cause for both transaction retirement and
normal close; rename it from transactionFailure to connectionFailure to match its
scope. All close failures retain the original handle, block open/use, and report
Health degradation. Successful close alone clears the cause and handle. The server
propagates queue-close failure before executor/index-lock release and before its
completion latch. EngineRoot already propagates that IOException while retaining the
server; no extra state, new recovery owner or second close protocol is required.

Final904 executes100 cases/8 suites with zero failures/errors/skips. Indexer-worker
PMD main/test and Spotless pass; UI integration-test compilation is UP-TO-DATE with
unchanged inputs, not claimed as fresh compilation. Exact command/counts are in
review-r8-close-owner-verification.json. Raw: `tmp/c2-review-r8-close-owner-negative903.txt`,
`tmp/c2-review-r8-close-owner-negative903-xml/`, `tmp/c2-review-r8-close-owner904.txt`,
and `tmp/c2-review-r8-close-owner904-xml/`. Retain through acceptance plus30 days;
export before worktree release. Final correction review and fresh full/hosted proof
remain required. Full900's no-test-failure result cannot excuse its formatting failure.
