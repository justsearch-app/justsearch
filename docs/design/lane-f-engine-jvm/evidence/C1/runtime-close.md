# C1 bounded runtime close — 2026-09-09

Independent-review must-fix1 owns this correction under C1-7/C1-11. The lifetime invariant is
actual exit: close timeout retains every live generation's snapshot, writer and NRT reference.
RuntimeSession closes admission under a short monitor and uses a separate close lock with a
five-second monotonic wait budget. Timer cancellation starts before generation-owner waiting;
timer fields remain published until its executor terminates. Write-barrier timeout throws and
retains the runtime; swap-attempt telemetry remains balanced.

The initially proposed NRT interrupt/join approach failed the existing zero-delay refresh test:
Lucene does not enter its interruptible condition wait when the configured delay is zero. The
governing section0 amendment records this executable refutation and the selected registered
close task. `head.lucene.nrt-close` uses a virtual executor with three maximum instances per
component. Each exact NRT thread retains one close attempt across timeouts. Its task invokes
Lucene's public close; its executor must actually terminate and NRT must actually exit before
writer release. A completed failed task can be retried without losing its still-live NRT owner.

NRT resume and retirement share the short admission monitor. Constructor activation opens the
timer before starting NRT, preserving rollback when the fourth runtime's timer is refused.
KnowledgeServer's pre-Lucene shutdown phase executes once; an incomplete runtime close leaves
the enclosing stores, registrations, index lock and completion latch retained. EngineRoot keeps
the server and propagates failure; start cannot replace that unresolved owner. A later close
retries the same server. This claim concerns retaining the enclosing lock on runtime failure;
it does not change IndexRootLock's pre-existing handling of OS-level release errors.

## Executed evidence

- Run258 reproduced late-search blocking behind NRT disposal and unbounded generation-owner
  waiting on the original source. Log `tmp/c1-runtime-close-before-258.txt`; original reports
  `tmp/c1-runtime-close-results-258/manifest.json`.
- Run259 caught the interrupt-only design error in the existing real Lucene zero-delay test.
  Log `tmp/c1-runtime-close-restored-259.txt`; reports `tmp/c1-runtime-close-results-259/manifest.json`.
- Run260 passed the focused Lucene runtime, commit, drain, and NRT tests after registered close
  ownership replaced interruption-only stop. Log `tmp/c1-runtime-close-restored-260.txt`.
- Run261 passed real EngineRoot/KnowledgeServer held-generation close, lock retention, retry,
  fresh start, prior terminal-writer tests and production generation capacity. Log
  `tmp/c1-runtime-owner-retry-261.txt`. The final revision additionally asserts that the held
  generation is the cause of failure, preventing an unrelated close error from satisfying it.
- Full262 stopped on the old three-registration assertion and an unused initializer introduced
  by the drain change. Log `tmp/c1-runtime-integrated-262.txt`; preserved reports
  `tmp/c1-runtime-integrated-results-262/manifest.json`. Neither is waived.
- Run265 passed focused Lucene regressions and both PMD source sets after the review corrections.
  Log `tmp/c1-runtime-review-restored-265.txt`.
- Full266 stopped at Spotless because a local indentation helper translated two source files to
  CRLF. The files were restored to LF; no test failure was claimed from this formatting result.
  Log `tmp/c1-runtime-integrated-restored-266.txt`.

The final NRT timeout regression now calls public runtime.close, asserts bounded return and a
retained writer, and verifies retry reuses the close task. A production-registry test proves three
NRT close instances, fourth-instance refusal and reuse after actual executor exit. These latest
assertions pass full267: build plus the stress-enabled suite and isolated system integration
complete in10m13s, with9737 represented unit cases (25 skipped) and118 integration cases
(52 skipped), zero failures. Source overlay `tmp/c1-runtime-overlay-267.patch`, log
`tmp/c1-runtime-integrated-restored-267.txt`, reports
`tmp/c1-runtime-integrated-results-267/manifest.json` preserve that exact pre-reload-lock scope.

Final review found one remaining enclosing-owner race: swapRuntime could finish opening and
publish after shutdown had passed runtime disposal. The section0 decision replaces its intrinsic
serialization with a ReentrantLock shared by swap and close. Close rejects subsequent swaps and
has five seconds to acquire that lock before any teardown; an accepted stalled opener can finish
only while the pending close retains ownership. Retry sees and closes the published generation.
No fresh-runtime registry or asynchronous cleanup owner is required.

Run270 passes real EngineRoot reload-vs-close, held generation, index-root lock, retry and fresh
start regressions plus generation capacity, KnowledgeServer completion tests and affected PMD.
Log `tmp/c1-runtime-swap-restored-270.txt`. The race test holds the opener through the first close
timeout, verifies the lock/latch remain owned and later swaps refuse promptly, then releases the
opener and verifies retry closes that exact fresh generation and releases the lock.
The independent reviewer re-read the final lock correction and run270 log/XML and found no
remaining defect. It verified lock order, both closeStarted checks, bounded failure before
teardown, retention through completion, and all four real EngineRoot lifecycle tests plus the
two production-capacity tests. Reviewed source git-blob identities:
KnowledgeServer403dd88ea57809dc758ee6c4506e4e6ff37d14f5 and
EngineRootTerminalWriterFailureTest7c3dd9b598cb6c93bf25cb3332ba72f06a194166.
The final source overlay is `tmp/c1-runtime-overlay-271.patch`.
Final full271 completes with one existing fanout test timing failure: it observed child exit
before the parent call's admission release. All runtime-close/reload regressions and installed
recovery cases pass; the full result remains FAIL. Its9738 represented unit cases have one failure
and25 skips;118 integration cases have zero failures and52 skips. Raw log
`tmp/c1-runtime-final-integrated-271.txt`, reports
`tmp/c1-runtime-final-integrated-results-271/manifest.json`. The strengthened parent/child lifetime
regression is tracked separately in [fanout-parent-exit.md](fanout-parent-exit.md).
Build273 passes the complete build with tests excluded. Final live/current hosted and a full
green run remain required for C1. Local raw evidence stays through lane F acceptance.
