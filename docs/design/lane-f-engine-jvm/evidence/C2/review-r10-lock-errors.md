# R10 final-review correction: native lock error ownership

Both owners previously swallowed release/close errors and removed their JVM fence.
Best-effort metadata also returned successful acquisition after a write/force closed
the native channel. Negative884 executes14 cases and fails all six named loss/error
cases across both owners. The metadata-error-with-valid-lock controls already pass.

Use channel close as the one release operation; a separate release leaves an old
channel whose later close could affect a new POSIX owner. Reserve the canonical path
before opening. Store its existing owner in the JVM map, so a failed contender cannot
remove another owner's reservation. Clear handles/reservation only after successful
channel close. Retry a failed close while the actual channel remains open. If it is
already closed after a reported failure, a later close can be a no-op and cannot prove
native cleanup; keep refusal until process exit. App shared-holder lookup excludes
these unhealthy reservations. Acquisition failures retain their original cause plus
cleanup failure and cannot hide an unclosed channel.

The fault fixtures wrap real FileChannels. A child JVM is refused while the open
failed-close channel retains ownership; after successful retry, acquisition works.
A separately injected failure after actual native close proves a no-op retry cannot
clear the process-lifetime refusal. That fixture removes only its own in-memory
reservation after closing its actual channel; production has no such bypass. Metadata
failure is tolerated with a live lock and refused when the native channel closes.
Test-only Mockito is added to app-util using the existing catalog version and scoped
lockfile regeneration883. Attempt882 stopped at strict dependency locking before any
test ran; it is not a behavioral negative control.

Root's caller reread finds KnowledgeServer also swallowed the index-lock close failure
and counted shutdown complete. Negative886 fails that case. The caller now retains
the owner and propagates failure before its completion latch; retry closes it before
reporting shutdown complete. No additional lifecycle state was introduced there.

Preliminary885 passes78 cases. Final887 executes87 cases/24 suites, zero failures,
errors or skips, including launcher exclusion/teardown, both native owners and server
close completion. Both modules' PMD and UI integration-test compilation pass. Exact
command/counts: review-r10-lock-errors-verification.json. Raw evidence:
`tmp/c2-review-r10-lock-errors-negative882.txt`,
`tmp/c2-review-r10-lock-test-dependencies883.txt`,
`tmp/c2-review-r10-lock-errors-negative884.txt`,
`tmp/c2-review-r10-lock-errors-negative884-xml/`,
`tmp/c2-review-r10-lock-errors885.txt`, `tmp/c2-review-r10-lock-errors885-xml/`,
`tmp/c2-review-r10-lock-owner-negative886.txt`, `tmp/c2-review-r10-lock-owner-negative886-xml/`,
`tmp/c2-review-r10-lock-errors887.txt`, and `tmp/c2-review-r10-lock-errors887-xml/`.
Retain through lane acceptance plus30 days; export before worktree release.
Fresh full and hosted proof follows the consolidated final-review corrections.
