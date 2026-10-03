# Stage A — full suite and gate record

Recorded **2026-09-08**, after the stage-A checkpoint fixes (blockers 1-6).
Branch `worktree-lane-F-A`. Base = `2845d9e83`.

This is the A20 evidence the checkpoint asked for: the command lines, the XML-derived counts per
module, and the gate table with pass/fail and the cause of any fail. It is a record of one dated
run, not a standing claim — re-run it rather than citing it if the branch moves.

## Suite

```bash
./gradlew.bat build -x test -PskipWebBuild=true --console=plain     # BUILD SUCCESSFUL
./gradlew.bat cleanTest      -PskipWebBuild=true --console=plain     # BUILD SUCCESSFUL
./gradlew.bat test --no-build-cache -PskipWebBuild=true --console=plain   # BUILD SUCCESSFUL in 7m 17s
```

`cleanTest` and `test` are two separate invocations, and `test` carries `--no-build-cache`, because
Gradle replays cached test results here: a suite total that arrives in seconds did not run. The
counts below are read from the `TEST-*.xml` files under each module's `build/test-results/**test**/`
— the `test` task's own output only. Sibling verification tasks (`updateSchemas`, integration
runners) write their own `test-results/<task>/` directories which `cleanTest` does NOT clean; an
earlier tally that walked all of them mixed a stale `updateSchemas` run into the total and reported
a phantom failure.

All 1507 result files were written inside a 7m15s window (01:11:33Z → 01:18:48Z), matching the
7m17s wall time — evidence the suite executed rather than replayed.

| module | classes | tests | skipped | failures | errors |
|---|---:|---:|---:|---:|---:|
| `app-services` | 396 | 2516 | 3 | 0 | 0 |
| `worker-services` | 245 | 1246 | 2 | 0 | 0 |
| `ui` | 152 | 1020 | 1 | 0 | 0 |
| `adapters-lucene` | 97 | 698 | 0 | 0 | 0 |
| `app-agent` | 49 | 661 | 0 | 0 | 0 |
| `app-observability` | 60 | 379 | 0 | 0 | 0 |
| `indexer-worker` | 63 | 363 | 12 | 0 | 0 |
| `worker-core` | 83 | 335 | 6 | 0 | 0 |
| `app-inference` | 27 | 298 | 0 | 0 | 0 |
| `configuration` | 32 | 267 | 0 | 0 | 0 |
| `app-agent-api` | 38 | 228 | 0 | 0 | 0 |
| `app-api` | 40 | 195 | 0 | 0 | 0 |
| `ort-common` | 34 | 164 | 0 | 0 | 0 |
| `benchmarks` | 13 | 104 | 0 | 0 | 0 |
| `system-tests` | 29 | 96 | 0 | 0 | 0 |
| `telemetry` | 25 | 88 | 1 | 0 | 0 |
| `app-engine` | 20 | 85 | 0 | 0 | 0 |
| `indexing` | 19 | 84 | 0 | 0 | 0 |
| `core` | 12 | 77 | 0 | 0 | 0 |
| `gpu-bridge` | 5 | 77 | 0 | 0 | 0 |
| `reranker` | 7 | 68 | 0 | 0 | 0 |
| `ai-backend` | 13 | 62 | 0 | 0 | 0 |
| `app-launcher` | 19 | 60 | 0 | 0 | 0 |
| `core-contracts` | 6 | 38 | 0 | 0 | 0 |
| `api-contract-projection-java` | 3 | 21 | 0 | 0 | 0 |
| `ssot-tools` | 4 | 17 | 0 | 0 | 0 |
| `app-util` | 2 | 16 | 0 | 0 | 0 |
| `test-support` | 6 | 13 | 0 | 0 | 0 |
| `extension-substrate` | 1 | 7 | 0 | 0 | 0 |
| `prompt-support` | 1 | 5 | 0 | 0 | 0 |
| `ipc-common` | 2 | 4 | 0 | 0 | 0 |
| `infra-core` | 1 | 3 | 0 | 0 | 0 |
| `app-config` | 1 | 2 | 0 | 0 | 0 |
| `dead-code-audit` | 2 | 2 | 0 | 0 | 0 |
| **total (34 modules)** | **1508** | **9301** | **25** | **0** | **0** |

**Reconciliation, both rounds.** 9293 before the checkpoint → 9299 after the six fix commits →
**9301** after the re-review round. Every one of the eight is named: `MigrationRestartRequiredTest`
(+3, blocker 1), `InferenceHandlersWorkerRestartTest` (+1, blocker 4),
`KnowledgeClientExceptionStatusParityTest` (+2, blocker 4), `KnowledgeServerCloseCompletionTest`
(+1, re-review item 2), and `EngineMigrationLifecycleTest.cutoverDoesNotChangeWhatThisProcessServesUntilItRestarts`
(+1, re-review item 1). A total that moved by an unexplained amount would mean something was
deleted or skipped silently — and this round DID delete assertions (the `EnergyReducedSink` sink
was removed, so three assertions on a list nothing writes went with it), which is why the count is
checked rather than eyeballed: those were assertion deletions inside surviving tests, not test
deletions, so the class count moved by +1 and the test count by +2.

**What this number does NOT cover**, because a green suite is routinely read as covering it: the
`stress`, `evidence` and `experiment` tags are excluded from every module's default `test` task
(`build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt:100-109`), and
`load-sensitive` is excluded from `app-services`' (`modules/app-services/build.gradle.kts:153,168`).
Two of A12's own conversions carry `@Tag("stress")` — `EngineExtractionSandboxChaosTest:112` and
`EngineFileLockContentionTest:73` — so their properties are not exercised here. See §10.

## The system and integration tiers (A12's own suites)

```bash
./gradlew.bat :modules:system-tests:systemTest :modules:system-tests:integrationTest \
  -PincludeSystemTests=true -PskipWebBuild=true --console=plain --no-build-cache
```

Both tasks exist and both RAN (`systemTest` is gated behind `-PincludeSystemTests=true`, hence the
flag; the task names are `systemTest` and `integrationTest`, not `systemTests`).

| task | classes | tests | skipped | failures | errors |
|---|---:|---:|---:|---:|---:|
| `systemTest` | 1 | 9 | 0 | 0 | 0 |
| `integrationTest` | 19 | 83 | 42 | **1** | 0 |

**The one failure is a real stage-A casualty that A12 did not catch, and it is worth more than a
green would have been.** `io.justsearch.systemtests.api.WorkerBootRecoveryE2ETest` fails with:

> *READY must be the recovery arm's doing, not a boot that quietly succeeded anyway — the injector
> fires on PID validation, so a missing occurrence means the injected failures never happened and
> this run proves nothing.*

Read at the source: the test's entire mechanism is the countdown fault injector
(`justsearch.worker.boot.faultInjectAttempts`), which threw on the first N **PID validations** of
the spawned Worker. Item A11 deleted the spawner, so there is no PID to validate; the boot now
succeeds on the first attempt and the recovery arm is never exercised. `bootFaultInjectAttempts` is
still resolved and guarded in `KnowledgeServerConfig` (`:44-55`, `:120`) but has **zero consumers
outside that record** — the injection point went with the process.

Two things follow. First, **the test is behaving correctly**: it refuses to pass on a boot that
succeeded for the wrong reason, which is precisely the `unreachable-seed-green` protection A12 spent
its effort on — this one had it built in. Second, A12 swept the `systemTest` source set and this
test lives in `integrationTest`, which is why it was missed. It is recorded as a named red in §10
rather than deleted or quarantined: the property (a boot that exhausts its retry budget converges
without a restart) is still real, the boot-recovery arm survived (§10 row 1), and only the
*injection point* is gone. Restoring it means giving the in-process index-half start a fault seam,
which is stage-B work alongside the supervisor that owns `WORKER_RESTART_EXHAUSTED`.

Neither task runs in the default `build`/`test` lane, so this red does not make `main` red — but it
is a red on the branch and is named as one rather than left for the next person to rediscover.

## Gates

```bash
node scripts/governance/run.mjs --mode gate          # the whole kernel
node scripts/governance/run.mjs --gate <id> --mode gate
node scripts/ci/<name>.mjs
node scripts/ci/run-ui-web-gates.mjs
```

**Kernel: `30 gates evaluated, 0 fail, 79 findings`.**

Note that "0 fail" is not "30 pass": the kernel reports **29 pass and 1 skipped**. `test-efficacy`
reports `skipped`, and a skipped gate asserts nothing — it is counted in the 30 evaluated and
excluded from the failures, so reading the headline as a clean sweep over-claims by one gate.

| gate / check | result | cause if not a plain pass |
|---|---|---|
| governance kernel (30 gates) | **29 pass, 1 skipped** | the skip is `test-efficacy`; no gate failed |
| `test-efficacy` | **skipped** | reported as `skipped` by the kernel, not as a pass — it asserts nothing in this run |
| `wire` | pass | needs `npm install` in `scripts/wire-contract` first; without it the failure is a missing dependency, not a contract violation |
| `adr-coverage` | pass | now includes `adr-0049-no-grpc-imports-in-java` (blocker 5) |
| `execution-surface` · `operation-surface` · `surface-altitude` | pass | — |
| `register-guard-resolution` · `engine-port` · `config-surface` | pass | — |
| `dead-code` | pass | **failed first as `kernel/input-missing`** — `tmp/knip-report.json` absent. Produced with `npm --prefix modules/ui-web run knip:report`, then passes. Not a code finding; the stage's deletions leave no dead code. |
| `npm-audit` | pass | **failed first as `kernel/input-missing`** — `tmp/github-advisory-report.json` absent. Produced with `node scripts/ci/report-github-advisories.mjs`, then passes with 0 findings. |
| `check-readiness-reason-codes` | pass | strengthened at blocker 2; reports 1 code awaiting a producer (`worker.restart_exhausted`, owner `lane-F/B`) |
| `check-dev-mcp-doc-sync` · `check-runtime-manifest-closure` · `check-store-recoverability` | pass | — |
| `check-language-agnostic-analysis` · `check-lockfile-completeness` · `check-pmd-ruleset-sync` | pass | — |
| `check-premerge-table` · `check-tempdoc-numbers` · `check-workflow-triggers` | pass | — |
| `check-always-loaded-budget` | pass | **9 bytes of headroom** (22667 / 22676). A pre-merge-table row cannot be added without evicting something. |
| `check-repo-history-policy` · `check-ui-step-coverage` | pass | — |
| `docs-validate` | pass | — |
| `regen-all --check --except notices` | pass | 7 generated file sets match |
| `skills-sync --check` | pass | 5 generated skills, 9 sources |
| `lint:scripts` | pass | eslint `--max-warnings=0` |
| `test:dev-runner` | pass | now also runs per-PR in `ci.yml`'s `public-claims` job (blocker 4) |
| `run-ui-web-gates` | pass | 27/27 |

**Two gates required a locally-generated input.** Recording that rather than the bare "0 fail",
because on a machine without those files the same command reports two failures that look like code
findings and are not. Both inputs are gitignored build products; CI produces them in its own lane.

## What this record cannot tell you

Static. Nothing here executes the Engine. The live half of stage A's evidence is
`a13-live-check.txt`; the packaging half remains the accepted, dated gap in §10 row 5
(`build-installer.yml`'s `release-signing` environment refuses non-`main` refs, so the workflow
fails before its first step on any branch). And per blocker 1, one property is not observable at
all through the API surface: a migration cutover reports a completed cutover in every status field
while the previous generation is still being served. No gate or test in this table would catch that
returning; D1 owns it.
