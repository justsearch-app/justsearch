# D1 registry producer and schema2 continuation

## Current claim

Base `78f58bd6c` plus the uncommitted D1 migration. This record is local evidence,
not D1 acceptance. Mutable WorkerCapability/InferenceCapability authorities are
retired; physical producers publish through registry handles. Status/health,
wire schemas, frontend, native host and development host consume schema2.
Runtime Contract advances to `0.4.0` because the lifecycle shape is breaking.

## Verification through run2358

Artifacts below are relative to this worktree's `tmp/`; retain through lane
acceptance plus30days and export before worktree deletion. Exact source inventories,
copied JUnit XML and counts accompany Java test runs where noted. Cache reuse is
not fresh execution.

| Run | Result and limit |
| --- | --- |
| 2333 / 2340 | First affected Java run:386 cases,6 failures in UI fixtures. Corrected fixture ownership/default state; UI rerun196 cases,0 failures. |
| 2337 | Four focused frontend files:54 passing cases; earlier2331 typecheck passed. |
| 2338 | Deliberate READY-epoch reset omission fails the specific reset-budget assertion. Restored source byte-for-byte. |
| 2339 | Rust host19 cases pass; native supervisor conformance binary builds. Engine-probe tests passed2331 in library and binary targets. |
| 2342 / 2344 / 2346 | Harness selftest34 passes. First full dev run16/17, budget-exhaustion case failed under concurrent checks; cause unproven and case artifacts were removed by default cleanup. Targeted retained rerun passes; retained full dev and Tauri reruns each17/17 pass. Do not call the original failure explained. |
| 2345 | Full `spotlessCheck pmdAll --continue` passes after resolving all reported findings. |
| 2347 | `build -x test :modules:ui:installDist --continue` passes. Integration tasks actually executed30 cases,0 failures,9 skips; counts/XML preserved. This is not the full unit suite. |
| 2348 | Installed health/status expose schema2;245 live routes captured. Standard activation fails because nullable chat intent was unboxed. Error JSON retained. Stack stopped with ports closed. |
| 2349 | Nullable-intent fix and activation-guard release regressions:59 cases,0 failures,0 skips across activation, raw mode handling and runtime contract tests. XML/counts retained. |
| 2351 | Runtime client regenerated for0.4;7 client tests pass. Updated UI distribution, affected formatting and affected test PMD checks pass. |
| 2352 / 2353 | Boot-recovery19 fresh cases pass. Deliberately removing the new close gates makes the new regression fail with unwanted RECOVERED; restored exact source hash83f7e027fe586345848e14658db0b49e8a7e8aefdd5262de0dff675a478d376a. |
| 2354 / 2355 / 2356 | New production-order test first had wrong argument order (compile failure); corrected, then exposed requestRecoveryNow returning ALREADY_RUNNING after closure. Added closed-first refusal. Both suites34 fresh cases pass,0 failures/skips; updated installDist. Monitor now closes before API. |
| 2357 / 2358 | Installed2356, run0c431f46-8b0d-42f2-95d8-b7620ec14190, API59656, UI5173, build stamp35fc139f1b8f368b. Runtime-client live smoke passes0.4.0, health/readiness200.245 routes/OpenAPI recaptured. Standard activation endpoint refuses operator-locked executable as configured; the intent API records online at accepted revision1 without changing executable authority. Qwen_Qwen3.5-9B-Q4_K_M.gguf becomes online; all four components READY. One tier2 retrieval query returns Captain Mortimer Flux, exact1.0,0 anchor errors. `2358-model-query/tier2-eval.json` retains answer/source anchors. Functional proof, not a benchmark. |
| 2358 UI | Affected chat-chip-yield still times out waiting for collapsed degradation banner despite live backend/model. Fixture/product investigation remains open. |

## Open proof and review

- Live proof2358 covers installed2356, before the process-resource extraction now in WIP. Reverify affected composition after integration.
- Live route/OpenAPI snapshots now reflect0.4 (2358).
- UI capture2334/2358 fails at the chat degradation banner;2358 rules out an absent backend. Resolved by explicit fixture capture2360 and coverage2362; see the proof below.
- Independent review found the shipped launcher composes a live generative
  manager without a registry. Its separate admission/executor ServiceLoaders do
  not expose EngineRoot's shared resource owner. Resolve ownership, do not add a
  shadow registry or suppress the feature to avoid the work. The selected shared
  process-resource SPI is recorded in component-plan; the shared owner is implemented; post-extraction acceptance remains open.
- Review also found recovery callbacks after the bounded monitor close. The
 2352 fix gates late completion and failure settlement after physical start,
  including occurrence/handover/readiness delivery.2356 also orders monitor close
  before API teardown and rejects manual recovery after close; the regression holds start
  across actual shutdown timeout and checks a real late successful client.
- Full affected/integrated verification of the completed slice remains required.

D1 configuration readers/register/dispatch, D2, E and F remain open. No required
check or implementation item has been waived by this checkpoint.


Live2358 reused the prior evaluation data. The MCP server resolves the requested
worktree data path relative to its main checkout, then the worktree runner resolves
that relative path again. Actual data is
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/.claude/worktrees/lane-f-pr1-verify/tmp/lane-f-resume-live1982`.
This is a tooling path discrepancy, not evidence of a clean index or changed data
ownership. No corpus was cleared. See saved `2358-live-components.json`,
`2358-live-inference.json`, `2358-live-health.json` and live/model command logs.


## Process-resource integration and UI proof2360–2363

EngineProcessResources/DefaultEngineProcessResources now provide the one policy,
admission/leases, executor registry and component registry. EngineRoot delegates;
LauncherEnvironment uses the sole SPI and passes its actual registry to HeadAssembly.
Independent admission/executor SPI descriptors and launcher loaders are retired.
Headless ordered/final shutdown closes the bundle after its users. The launcher
registers its intentionally absent index and retains generative observation.

2361 found a test-only Map/List mismatch and a shadowed Java lambda parameter;
both corrected.2362 runs70 fresh cases across3 modules,0 failures/skips. The
launcher regression publishes READY through the real Head generative handle and
observes both the actual process registry and capability adapter, ruling out an
immutable unavailable fallback. Shared policy/accounting and process-vs-index
lifetime tests also pass. Test JVM reports rrd4j Unsafe.invokeCleaner deprecation
(from rrd4j3.10); no warning suppression was added. Source inventory and XML/counts
are preserved under2362-process-resources. Independent review identified teardown refusal/lifetime defects; corrections and regressions are awaiting focused verification.

UI failure2334/2358 is explained: affected selection chooses chat-chip-yield,
which requires a degraded fixture; a healthy live backend correctly has no banner.
2360 repeats with explicit --fixtures successfully. Both its measure.json and
2359 live-home measurement report0 axe violations,0 console errors,no overflow;
PNG reviewed. These are separate fixture-rendering and live-home claims.
The harness now rejects fixture-only recipes before serving/browser launch when
--fixtures is missing (14 Python regressions pass2362). Both UI-check skills now
explain fixture versus live evidence. Step-coverage gate passes2362; UI typecheck
and3 focused files/53 tests pass2359.

Owned live run0c431f46 stopped successfully with portsClosed:true. Static2363 found one unused alias; corrected. Full static/unit/stress2364 completed as recorded below; current corrections and post-extraction live proof remain outstanding. No stage acceptance is inferred from the focused counts.


## Integrated2364 and corrections awaiting2365

`spotlessCheck pmdAll test -PincludeStress=true --continue` completed in11m2s.
Static checks passed. Preserved counts show11463 cases:11380 executed and83 reused,
6 failures,31 skips,1786 suites across34 module tasks. The reused tasks are
core-contracts, extension-substrate, infra-core, prompt-support, ssot-tools and
test-support. Artifacts: `tmp/2364-integrated-stress.txt`, matching `-counts.json`,
`-xml/`, `-sources.json` and `-skips.json`. Required red checks block acceptance.

Failures and root causes:

- UnreferencedCodeTest found four old captured-config wrappers and one new
  test-only launcher accessor. Retired wrappers; tests pass explicit snapshots.
  Launcher test now captures the real SPI owner through its factory seam.
- EngineUpgradeLifecycleTest and three LocalApiComponentRegistryTest cases had
  stale fixtures without required physical component registrations. Fixtures now
  register canonical non-API specs while retaining the actual registry and all
  existing health/bind/version/failure assertions.
- EngineRootTerminalWriterFailureTest's swap joined for5s while real Tika external
  tool probes delayed application-service reconstruction. XML shows close's
  expected timeout at21:48:01.016, replacement indexing at21:48:06.030, and cleanup
  retry at21:48:06.033. The join missed completion by about14ms; successful final
  cleanup excludes a stuck swap lock. Isolate unrelated tool discovery in the
  ownership fixture; preserve its timeout, actual runtime swap and retry assertions.

Review also found shutdown must retain process resources when Head/index drain or
component apply closure refuses. WIP now closes components before executors,
requires both ordered drains, and removes the failed-drain executor fallback.
Fatal-startup cleanup retains the monitor and closes it before API teardown.
Held-lease execution, drain-refusal and normal/fatal recovery regressions await2365.
No timeout relaxation, assertion deletion or production warning suppression is used.


2365 focused corrections pass114 fresh cases,0 failures/skips across6 modules,
including real boot/ingest/search and all runtime-swap ownership cases. Timing
fixture uses the existing virtual newAppServices seam only after real initial
root.start; it asserts replacement service identity as well as original runtime,
lock, retry and deadline behavior. Independent rereview finds no residual defect
in the resource/shutdown slice; future production apply-lease teardown is an
explicit obligation in the apply-register plan.

2366 deliberately restored the old try-with-executors close ordering. The held
apply test fails specifically with EngineExecutorRejectedException on executor
registration after the refused close, proving the regression detects premature
execution teardown. Restored exact source bytes SHA256
36727aa9b152975c0d837cc184c857eb991f2ffa5a67cf65f6051638957decba.
XML/counts, failure log and restoration record are preserved under2366-resource-negative.

2367 full static/unit/stress plus installDist passes against restored sources:
11465 cases,0 failures,31 skips;5253 executed and6212 up-to-date across34 tasks.
The source inventory103 files, exact reuse list, XML and skipped-case records are
preserved under2367-integrated-corrections. Generated sets (7), readiness-code
producer/emission gate (58 codes), canonical links157, docs index116, skill
projections and module graph checks also pass.

Full frontend2367 typecheck passes;6595 unit tests pass and1 fails because the
no-Engine-port fixture receives env8080.2368 reproduces with explicit VITE_API_PORT.
The initial Vite test-mode discovery guard does not resolve2369; investigation
continues, with the original assertion unchanged. This red check remains open.

App-connected MCP preflight still serves main's retired workerDist requirement.
2370 reuses the existing stdio client with this worktree's .codex/config.toml;
all actual Engine preflight checks pass through the same shared ownership layer.
No removed Worker distribution was recreated and no stack ownership check bypassed.


2371 full frontend suite passes490 files/6596 cases after the minimal Vite
`mode !== test` discovery guard; original endpoint assertions remain intact.
2368/2369 were not valid controls for the manifest-discovery fix: explicitly
injecting VITE_API_PORT exercises Vite's separate environment loading. The guard
removes desktop-manifest compile-time pollution in unit mode; it preserves
intentional development/environment endpoint discovery. No blanket empty env
constants were added. The original full2367 failure and full2371 pass retain
evidence for the actual correction.

Installed2370 is build stamp1054171e93831310, run
d8eb5a19-e383-46cc-bce9-8e1223721b7b, API53671, UI5173. Actual data path matches
the reused2358 directory (no clean/reset). Runtime-client live smoke passes0.4.0,
health/readiness200. Online intent converges at revision2, standard
Qwen_Qwen3.5-9B-Q4_K_M.gguf is active, all4 components READY. The real tier2 query
returns Captain Mortimer Flux, exact1.0 and0 errors/anchor errors; this is
functional proof, not benchmark acceptance. JSON/logs under2370 preserve the
response, model identity and anchors.

2370 live home capture fails because the app shell does not mount within15s.
Browser inspection confirms an empty root with no error log; backend freshness
and all component readiness are healthy, and served frontend code embeds the
correct53671 port. Vite stdout/stderr has no build error. This is an open live UI
issue under investigation, not waived by the full unit pass or earlier captures.
