# R10: preserve native lock ownership on refusal

September13, source6b109c27c plus this correction. R2 is reopened by hosted
CI34727822388: LauncherEnvironmentCloseTest fails all three Linux attempts because
the second launcher succeeds. The launcher already acquires before operations open.
AppInstanceLock instead records acquisition time as process start, compares it with
actual process start and can unlink a live locked inode as supposedly stale.
IndexRootLock contains the same mechanism. Windows prevented that unlink; its local
pass did not establish Linux exclusion.

Both lock owners now retain the file and rely exclusively on the OS lock. Their
existing metadata remains diagnostic, with actual process start only when available.
No metadata is read to override refusal. The removed parser-only tests belonged to
the retired recovery mechanism; unlocked-stale acquisition and close/reacquisition
coverage remain. Each owner serializes same-JVM acquisition and release, refuses
before a competing channel opens, and resolves parent/directory aliases. This also
prevents POSIX channel-close semantics from releasing the first lock. The app holder
query uses the same monitor. The index lock stays beside the index directory so
legacy directory moves still work. No cross-module lock abstraction was introduced.

The primary contract is [Java25 FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html):
VM termination releases locks; on some platforms closing another channel can release
all JVM locks on that file. The explorer confirmed both production acquisition paths
and the required sibling placement; this is not the final independent batch review.

Named new tests in both owners verify exact process-start metadata, same-JVM refusal
followed by a separate JVM's refusal, normal close/reacquisition, unlocked stale
metadata, and Runtime.halt(71) while locked followed by another JVM's acquisition.
The unchanged launcher test still requires one constructed runner and its RUNNING row.

- `tmp/c2-review-r10-lock-negative844.txt` and matching -xml/: 3 cases, one intended
  metadata failure on prior AppInstanceLock. Windows exclusion cases pass historically.
- `tmp/c2-review-r10-index-lock-negative846.txt` and matching -xml/: 9 cases, one
  intended metadata failure on prior IndexRootLock (includes mandatory contract tests).
- `tmp/c2-review-r10-locks848.txt` and matching -xml/: 63 cases/20 suites, all three
  test tasks execute, zero failures/errors/skips; selected PMD and UI integration-test
  compilation pass. Exact command/counts in review-r10-lock-verification.json.
- `tmp/c2-review-r10-full840.txt` and matching -xml/ retain the first full-run attempt:
  stopped on local CRLF formatting, not a test failure. Owned Java files are normalized
  to the repository LF rule; this is byte normalization, not source behavior changes.
- `tmp/c2-review-r10-release841.txt`: all12 release-asset tests pass. The Public claims
  workflow now runs that existing test file. Workflow trigger check842, preflight
  inventory check, and regenerated canonical links851 pass.

Full batch verification and fresh Linux hosted proof remain required. Raw evidence
is retained in this worktree through lane acceptance plus30 days and exported before
worktree release. This correction is a pushed R10 checkpoint, not batch closure.
