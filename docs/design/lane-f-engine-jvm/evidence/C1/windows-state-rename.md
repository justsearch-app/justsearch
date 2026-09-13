# C1 Windows supervisor state publication

The atomic JSON writer retries Windows EPERM/EBUSY rename refusals on its existing async caller,
at10ms intervals for at most one monotonic second. It keeps the complete temporary file between
attempts and checks the deadline before starting another attempt. Exhaustion or a permanent error
cleans only the temporary file and preserves the existing destination. Other platforms and error
codes keep immediate failure. Existing per-run supervisor publication serialization is unchanged.

The real Windows fixture holds a destination FileStream with FileShare.Read, permitting readers
while withholding delete sharing. Microsoft's [CreateFileW contract](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)
states that delete access also governs rename; the [FileShare enum](https://learn.microsoft.com/en-us/dotnet/api/system.io.fileshare?view=net-10.0)
documents the corresponding sharing flags. The fixture first witnesses a real EPERM from a direct
rename probe, then exercises the production writer with that same handle held. This establishes
the Windows failure condition, rather than merely injecting an error with the same name.

The successful arm releases its handle after150ms. The candidate remains pending until release,
the old destination remains readable, and publication then succeeds. A25ms concurrent reader
sees only valid old/new JSON. The exhausted arm keeps the handle held and observes the original
refusal after about one second, with the previous destination intact and no temporary-file residue.
A separate permanent-EIO fixture asserts original exception identity and exactly one rename call.
Each hidden fixture child is released and awaited; a watchdog cleans up a stalled helper.

## Verification

Windows11, Node24.12, Temurin25.0.2; base1bc41dcf5 plus this item.

- Old-code377 fails the retained-temporary-file assertion under the actual Windows read handle.
  Restored378 passes release and exhaustion behavior. Final379 records live reader PIDs, the
  actual EPERM probe and exhaustion at1006ms, plus the permanent-error assertion.
- Adverse380 removes the deadline and then the error-code filter in separate runs. The former
  hits the bounded test watchdog after five seconds; cleanup releases the handle and waits for
  publication. The latter performs66 rename attempts instead of1 on permanent EIO. Both fail
  for those intended reasons. Production is restored byte-for-byte in finally.
- Restored381 passes the complete dev-runner supervisor suite and34/34 supervisor-conformance
  self-checks. Build382 passes in4s with test Error Prone enabled. No reader poll was slowed.
  Docs validation383 and repository-wide script ESLint384 also pass with zero warnings.
  Final installed recovery/full stress and current hosted C1 verification remain required.

Independent read-only review after restoring the adverse mutations found no remaining defect in
the production retry or fixture cleanup. That review did not execute builds or hosted tests; the
fixed revision still requires its hosted Windows run.

Evidence: `tmp/c1-windows-rename-before-377.txt`, `tmp/c1-windows-rename-restored-378.txt`,
`tmp/c1-windows-rename-final-379.txt`, `tmp/c1-windows-rename-adverse-380-deadline.txt`,
`tmp/c1-windows-rename-adverse-380-error-filter.txt`, `tmp/c1-windows-rename-restored-381.txt`,
`tmp/c1-windows-rename-build-382.txt`, `tmp/c1-windows-rename-docs-383.txt`,
`tmp/c1-windows-rename-lint-384.txt`.

The earlier failed hosted writer attempts remain in [hosted CI evidence](hosted-ci.md), including
the successful advisory job that required its third attempt. Later green checkpoints do not
erase those failures. This item resolves their reproduced publication boundary locally.
