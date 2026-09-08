# Stage B hosted CI correction — 2026-09-08

The `be47faa40` checkpoint's local evidence remains valid, but its completion wording omitted
B9's distinct requirement for the death-observability step to pass on a hosted PR run.
No CI workflow had run for that head. The two older branch workflow-dispatch runs were installer
attempts, not evidence for these steps. [Draft PR 718](https://github.com/justsearch-app/justsearch/pull/718)
opens PR 1 early to obtain that tier; it does not authorize an intermediate implementation merge.
C1 waits for this hosted acceptance correction.

The first [CI run 34269384198](https://github.com/justsearch-app/justsearch/actions/runs/34269384198)
at `be47faa40` had six failed jobs. The failures are preserved in ignored `tmp/b-ci-*.txt`:

| Job | Cause | Correction |
| --- | --- | --- |
| Windows-native | Dev-runner tests ran before Java setup and found JDK 17. | Move the existing setup before both process-test commands. |
| Public claims | Fresh Knip reported two unused exported declarations; the earlier local report was stale. | Keep both implementations, make their module-local declarations private; regenerate the report before the gate. |
| Build | CI enables Error Prone on test sources; two protobuf-null comparisons and an always-true count test failed compilation. | Use the non-null getter contract and assert the concurrent empty-index searches return zero hits. |
| Platform contracts | The managed-llama fixture launched powershell.exe on Linux. | Use an owned Java sleeping child on either OS. |
| App/UI | Matching process fixtures recorded a launcher path instead of the OS executable; variant parsing mishandled backslashes on Linux. | Capture actual OS identity at production registration and in fixtures, fail closed on missing identity, and normalize variant separators. |
| Integration (advisory) | The boot-recovery fault counter survived but its injection site disappeared with Worker PID validation, so READY was not recovery. | Rehome the existing production-disabled counter before index composition; preserve all recovery assertions. |

The original hosted installed Engine recovery class itself passed **5/5, zero skips**; the
advisory integration job failed on the separate boot-recovery test. This does not establish
Linux whole-Engine recovery: the installed fixture remains Windows-only. Section 16 now carries
supported-OS forced-kill/replay coverage explicitly to E, alongside signed installer/user-store
proof. Tauri AppHandle setup/events remain source-reviewed rather than an executed GUI claim.

Independent review cleared the process identity, CI/tooling and fixture corrections. The design
consolidation review found three old authority/classification statements; they were corrected.
Stage B's dated essays were replaced with an index and their active constraints/trade-offs folded
into sections 7, 16 and 17. Full earlier chronology remains at Git commit `be47faa40`.

## Post-correction local verification

All Java commands below enabled test-source Error Prone with `-PskipErrorProneTests=false`.
Build plus PMD (`build -x test pmdAll`) passed. Focused portability and boot-injector tests
passed. The installed command `:modules:system-tests:integrationTest --tests
'*WorkerBootRecoveryE2ETest' --tests '*EngineSupervisedRecoveryE2ETest' -PskipWebBuild=true`
passed **6/6 with zero skips** in 2m4s. The only later edit to this integration class changes
its assertion diagnostic from the retired PID injector to the new injection site.

Frontend typecheck, **6,462 unit tests**, and **27 UI gates** passed. Fresh Knip generation
followed by the dead-code gate passed without changing its baseline. Documentation generation,
canonical checks and paired-skill synchronization passed. Independent read-only review cleared
the runtime corrections and the rehomed injector; its last finding was the stale diagnostic,
now corrected. The full stress-enabled suite passed as recorded below; the next hosted run is pending.

Raw output stays in ignored `tmp/`. SHA-256 identifies the exact preserved artifacts:

| Artifact | SHA-256 |
| --- | --- |

| `tmp/b-ci-final-build.txt` | `bdd7e235bde2f56ef46f2c81882f684b9d646a5101409353d50d1e27b0c478b1` |
| `tmp/b-ci-targeted.txt` | `b7548c7668bc3d10c5192a13ca7bfdfd0d2ff18c81d18e01a1c2010ffb979c38` |
| `tmp/b-ci-portability-tests.txt` | `f1c228d7df8df723ed4bbf008b63c9d3ac10e24ff3c0583dcce6d03b2291c2c9` |
| `tmp/b-ci-boot-unit.txt` | `348c58ce52e1b413152238ae3e69a1ce8ac8de59f6676b2607e6be7d9aeab5a5` |
| `tmp/b-ci-installed.txt` | `3d707d464d65892805e10436ffc0c961b238e55a9ba6e0efbd470521fa6831e1` |
| `tmp/b-ci-ui-unit.txt` | `485ab73b98ffedfe74486fa0a5f1c6afd3f625beec1adaf3fccd6b20a09208a7` |
| `tmp/b-ci-ui-typecheck.txt` | `596373b383c6e3421b378952b2fbf2d04b8a1bcef36fd19dc1812d88a565e100` |
| `tmp/b-ci-ui-gates.txt` | `ba2ce83b00452103daadba8e4dbe359dc57ff47153636628a92eff6c7609439b` |
| `tmp/b-ci-installed-xml/TEST-io.justsearch.systemtests.api.WorkerBootRecoveryE2ETest.xml` | `83898f9afd2ee09657c01973ccd6894d8ed2545ad2fd0f6c5e0590292e26bd20` |
| `tmp/b-ci-installed-xml/TEST-io.justsearch.systemtests.supervision.EngineSupervisedRecoveryE2ETest.xml` | `5bc945b80393095f2deb791b5ffb75b8c90baaf5cfa62b713e3c0c28ffbe8b4b` |
| `tmp/b-ci-final-full.txt` | `07b827e1d757a0feb43c8dbc3f6fe461dab45a0973192f71d6101f23fc21bfcb` |
| `tmp/b-ci-final-full-xml/summary.json` | `75d53a424d1fb87a2bb2aa8272b02c17817bdf2fb84b0e5c273ab0836dce5eb8` |
| `tmp/b-ci-final-full-xml/inventory.json` | `4ce7c15af61bb0bca55c32707b40984b3b9fe63d5299a78013da4890e9c9f16c` |

The fresh `test -PincludeStress=true -PskipErrorProneTests=false --no-build-cache --rerun-tasks`
run passed in **10m46s: 9,460 tests, zero failures/errors, 25 skips across 34 modules**.
All 194 tasks executed. Its 1,534 XML files were copied to `tmp/b-ci-final-full-xml` before
any further test invocation, with per-module counts and per-file hashes. The increase of four
over the preceding 9,456-test checkpoint is the two process-identity regressions plus the two
boot-injector regressions. No Linux execution is inferred from this Windows local run.
