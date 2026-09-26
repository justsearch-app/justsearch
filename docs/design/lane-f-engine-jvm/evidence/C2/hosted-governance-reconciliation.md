# C2 hosted and full-suite reconciliation, 2026-09-21

## Current result

Full stress2064 at `0a23b0a4fd8b5781ed936cfc1f70dafcdcbe94b9` completed in
18m11s with 11,119 cases, one failure, zero errors and 30 skips across 1,746 suites.
It represented 34 module test tasks, 19 reused. Command:
`./gradlew.bat -PtestParallelism=1 --continue test -PincludeStress=true`.
The failure is SystemAccessFunnelTest: HeadlessApp.main introduced a direct
System.getenv() call while composing the installed fault hook. This is a real
new guardrail violation, not an allowed baseline expansion. All original output
and XML were captured before focused tests could overwrite them:
`tmp/2064-full-stress.txt`, `tmp/2064-full-stress-counts.json`,
`tmp/2064-full-stress-xml/`. The 30 skips retain the prior qualified platform/model/
external-fixture/deferred-composition limits; they are not additional proof.

The correction passes SystemAccess::rawEnvVar through the barrier's injected lookup
function. It reads only the existing four process-level harness selectors and
preserves exact phase/key/kind/harness selection. No system-access allowlist grows.
The source-level config scan alone had missed the no-argument getenv bypass;
the bytecode rule correctly caught it. Keep both checks in acceptance evidence.

Hosted run35552397005 at the same revision independently fails that rule and the
store-recoverability gate: OperationFaultBarrier is a new unclassified write site.
Its reached/pending/release files now have an explicit EPHEMERAL/RESET authority
row. They persist only for the selected test's forced restart, contain operation
metadata/PID rather than source content, and do not participate in product recovery.
They are under the data directory and therefore are not put in the scratch-file
escape list. The harness validates evidence before killing; Engine presence checks
prevent retriggering the same barrier after its successor starts.

Focused2070 executes seven cases in four suites, zero failures/errors/skips, plus
ui PMD main/test and spotlessCheck (21s). Command:
`./gradlew.bat :modules:ui:test --tests '*OperationFaultBarrierTest' :modules:dead-code-audit:test --tests '*SystemAccessFunnelTest' :modules:ui:pmdMain :modules:ui:pmdTest spotlessCheck -PtestParallelism=1`.
Artifacts: `tmp/2070-fault-governance.txt`, matching counts JSON and XML directory.
Store gate2071 passes 46 registered authorities, six catalog stores; its existing
Node regression file passes2072. These prove the local correction, not a new
all-green integrated or hosted result. Initial installed2073 passes all eight
cases with exact artifact capture, but independent review found that envVar's
blank-to-null normalization would silently disable an invalid blank selector.
The final correction adds rawEnvVar to the existing configuration funnel, leaves
envVar's historical normalization unchanged, and adds blank/whitespace selector
regressions with and without harness mode. Review also corrected the register:
only reached publication is atomic; pending JSON and timestamped release are
direct writes, and release is consumed by presence. Final store2076 passes;
combined focused/configuration/installed2075 passes 288 cases/37 suites with zero
failures/errors/skips in2m59s, including all eight installed scenarios, configuration
tests, the new blank-selector case, system-access enforcement, PMD and formatting.
Exact commands/counts/XML and the eight owned-stop fixture manifest are retained
under `tmp/2075-fault-governance-final*`. Process-level probe2079 launches Java with
an actual empty environment value and proves rawEnvVar preserves it while envVar
still normalizes it; source/output are `tmp/RawEnvironmentProbe.java` and
`tmp/2079-raw-environment-probe.txt`. Health2080 is ABSENT with no foreign run or
inference orphan. Root owns the consolidated correction; no validation or
allowlist was weakened. Full integrated stress must be green at the final batch.

## Hosted integration failures remain actionable

Run35548954444 at `1a94b5c0a275af571e0ebfb10ff922e726a4f116` has overall
conclusion success, but job106180087135 (Integration tests, system-tests tier)
failed. `.github/workflows/ci.yml` deliberately marks that job continue-on-error;
run-level success is not integration acceptance. The Windows artifact reports
seven failed invocations among 14: writer replay timeout three times, hostile-lock
ingest HTTP500 twice, processing pre-kill observation timeout once, and lock-boot
socket closure once. Job log lines2286-2333 retain failure attribution.

Writer corpus paths are outside the seeded watched scope in that revision and
remain so at0a23. Read-only inspection of all three archived writer operations
databases confirms parent id3 FAILED with RECOVERY_SCOPE_REFUSED and persistent
`ingest-refusal:1:RECOVERY_SCOPE_REFUSED`; this is now direct evidence, not only a
source-based suspicion. Compact extracted rows are in
`tmp/2074-hosted-writer-refusals.json`, with each original fixture directory named.
The intended recovery scenario must seed a real watched corpus and place its two
source documents there; weakening recovery scope validation is not a fix.
Hostile-lock acceptance runs while the intruder is already active and
has not established a durable accepted corpus when it fails. Neither observation
is a license to suppress failures or blindly retry. Processing fails before its
deliberate kill despite child-entry evidence in the fixture, so it proves no
successor recovery. Root is investigating actual causes and preserving each
scenario's intended fault before changing fixtures.

Read-only subagent audit artifacts: `tmp/2066-*`, including downloaded
`tmp/2066-integration-test-results/`. Current job logs:
`tmp/2068-public-claims.txt`, `tmp/2068-platform-contracts.txt`; workflow-signal
health is `tmp/2067-workflow-signal-health.txt`. Later snapshot2077 records current
run35552397005 completed FAILURE: integration, public claims and platform-contracts
failed. Its integration artifacts are being reconciled separately; the local
configuration/register fixes above do not close those scenario failures. Retain raw artifacts through lane
acceptance plus 30 days and export them before releasing the worktree.
