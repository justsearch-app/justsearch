# C1 hosted CI corrections

2026-09-09. Stage C1 is not complete. Retain raw artifacts through lane completion plus30 days.

Run34339800148, PR718 head db368e353: Public claims failed config-surface and npm-audit;
model-free Build and app-ui failed strict test compilation. Read-only triage and root reread
are preserved in tmp/c1-hosted-triage/triage-report.md and the three named job logs there.
SSE and executor comparison corrections are pushed as09c329b46 and9629f240b; strict local
check181 is running with -PskipErrorProneTests=false. Local defaults skip this test-source
checker, which explains why the integrated build did not expose those three errors.

## Runtime-client advisory correction

GitHub's primary advisory GHSA-2883-xcg3-v3hh, published2026-09-08T21:24:51Z,
identifies js-yaml>=4.0.0,<4.3.2 as vulnerable to CPU denial of service through empty merge
sources. Runtime-client's generator Orval8.27.0 pins4.3.1; npm metadata for latest8.30.0
still pins4.3.1. A scoped orval -> js-yaml4.3.2 override upgrades only that transitive
package. No accepted-advisory baseline change. Source:
https://github.com/advisories/GHSA-2883-xcg3-v3hh

Local install171, deterministic generated-output check172, all7client tests173 and package
contents check174 PASS. The fresh report175 has zero runtime-client advisories; all targets
are available and zero high/critical identities remain. Root/UI lower-severity advisories
remain reported (6/5), outside the gate's existing high/critical threshold. Restored kernel180
passes npm-audit and config-surface; raw logs `tmp/c1-runtime-client-{install-171,regen-172,test-173,pack-174}.txt`
and `tmp/c1-advisory-patched-175.txt`. Before/after identity reports are
under tmp/c1-hosted-triage. No regenerated client source changed. Hosted rerun remains required.


## Admission configuration surface reconciliation

The C1 aggregate-cap override added in e71b512a6 is the one extra EnvRegistry declaration:
measured248pairs versus the stage-A pin247. It is a startup-only reduction, bounded by the
packaged policy in EngineResourcePolicy; it adds no YAML or ConfigKey entry. This correction
adds its own declared-growth changeset and advances only that pin to248. Other stage-A
surface retirement remains. Generated matrix179 and kernel180 pass; raw SARIF is copied to
tmp/c1-hosted-kernel-restored-180.sarif. No dead-setting or sysaccess allowance grows.

Strict compiler/test181 passes (85tasks,6executed79up-to-date) with test Error Prone enabled.
SSE4tests and registry tests execute after09c329b46/9629f240b; this closes their WIP local
proof gap. Hosted rerun remains required. Hosted Docs lint34341109990 passes at56b3d826f;
canonical sources are unchanged by these subsequent test/dependency/governance corrections.


## Hosted1c7fcfff9 follow-up (2026-09-09)

CI34342537664 passes model-free build, app-ui, search-worker, platform-contracts, Windows-native,
license, Rust shell, secrets and jseval; Public claims fails at the ADR-coverage probe, and the
system integration tier fails IndexingLedgerCoherenceTest's new terminal-event assertion on
all retries. Logs tmp/c1-hosted-1c7-failures-192.txt and status snapshot191 (taken before the last
integration result). These are still required failures, not final C1 hosted success.

Local ADR reproduction193 fails because its foreground balance probe still names
ForegroundLoadGateTest.eachForegroundOperationIncrementsAndDecrements, which C1 replaced.
Re-examination of ForegroundLoadGate and current tests confirms the retained normal/exception/
cancellation/Error balance, explicit urgency independent of survival, and one held durable work
increment. ADR0048 now has a dated amendment, reviewed date and decision-log update; the balance
probe names the preserving test and two separate probes pin urgency and durable lifetime. The
real Engine producer witness remains. Restored194 PASS,53 findings,zero failures; before/after
SARIF: tmp/c1-adr-coverage-193.sarif and tmp/c1-adr-coverage-restored-194.sarif. This is a probe
registration check; execution evidence comes from the Engine suites, not this source census.

The integration ledger failure is under read-only root-directed triage. Integrated190, with
all stress tests enabled and strict test-source compilation, has also reported a format-matrix
initialization timeout; that is being investigated before any acceptance claim.

## Hosted6bf931408 follow-up — 2026-09-09

[CI34356123502](https://github.com/justsearch-app/justsearch/actions/runs/34356123502) is FAIL.
Build, all three unit lanes, Rust, jseval, license and secrets pass. Public claims fails on two
Markdown heading errors (B17 had a second H1; the pasted independent review had no H1). Both are
corrected, and local docs validation264/268 passes.

Windows-native supervisor conformance still fails requested-restart-is-not-counted with EPERM
replacing the state file. The integration job is marked successful because writer recovery
passes on its third attempt; its first two attempts fail. This is the configured advisory
failOnPassedAfterRetry=false behavior, not evidence that every attempt was green. The writer
fixtures include an EPERM state publication failure and an Engine close that exceeded its
deadline and was classified hang. The latter incarnation log stops immediately after
"Commit timer stopped"; no thread dump exists to prove its exact blocked frame. The current
bounded runtime-close correction addresses the following NRT wait boundary, but hosted proof
on that corrected source is still required. The previous same-process publisher
serialization remains correct but is insufficient when Windows readers briefly hold the target.
Bounded production rename retry and its regression remain required; the polling harness is not
weakened to hide it. Final C1 hosted green has not been established.

Indexing ledger coherence and migration now pass. Final guarded boot/ingest hostile-lock
recovery has actual fatal-exit witnesses, detailed in [hostile-lock-phase.md](hostile-lock-phase.md).
Raw status, logs and extracted fixture evidence: `tmp/c1-hosted-status-269.json`,
`tmp/c1-hosted-failures-263.txt`, `tmp/c1-workflow-signal-health-263.txt`,
`tmp/c1-hosted-system-green-269.txt`, `tmp/c1-hosted-proof-summary-269.json`,
`tmp/c1-hosted-artifacts-269.json`, `tmp/c1-hosted-artifacts-269/`,
`tmp/c1-docs-validate-restored-264.txt`, `tmp/c1-docs-validate-runtime-268.txt`.
The integration-test-results artifact10106694706 expires2026-12-08T13:17:16Z. The log filename
system-green reflects the job conclusion only; the summary above preserves its failed attempts.

## Hosted44039df47 — 2026-09-09

[CI34363379524](https://github.com/justsearch-app/justsearch/actions/runs/34363379524) passes at
44039df47e88320a79d314cda4b23d49b845604b, including Windows-native, public claims, all three unit
lanes, and both advisory jobs. This revision includes the runtime/reload-close correction and the
interrupted-publisher regression; it precedes the OCR correction now being implemented.

The system job102506184543 completes in7m4s. Downloaded integration XML represents88 cases:
zero failures/errors,42 skips and no failure/error/flakyFailure/rerunFailure entries. The indexing
ledger and writer/migration recovery cases execute without skips. All five installed recovery
fixture outputs are available in the downloaded artifact. Thus this pass is inspected system
evidence, not merely a successful job label or wiring claim.

The previously reproduced Windows concurrent-reader rename defect remains required production
work despite this green run. Earlier failed attempts remain recorded above. Final C1 still needs
proof after the remaining ordered corrections; this checkpoint does not close the stage.

Raw evidence: `tmp/c1-hosted-status-292.json`, `tmp/c1-workflow-signal-health-293.json`,
`tmp/c1-hosted-system-293.txt`, `tmp/c1-hosted-artifacts-293.json`,
`tmp/c1-hosted-artifacts-293/`, `tmp/c1-hosted-proof-summary-293.json`.
The integration-test-results artifact10109174357 expires2026-12-08T14:24:20Z. Local extracted
copies remain accessible through lane-F acceptance; the SHA-256 inventory covers their contents.
