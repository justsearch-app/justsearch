---
title: Development Guide
type: reference
status: stable
description: "Development workflows, static analysis, and testing reference for contributors."
---

# Development Guide

## 1. Configuration & Single Source of Truth (SSOT)

### 1.1. The Golden Rule: Inversion of Control

**Low-level modules are "dumb."** They do not scan the filesystem for configuration.
Modules must be **given** their configuration object (POJO) via their constructor.

### 1.2. Path Resolution Order (CRITICAL)

All path resolution follows this priority:
1. **System Property** (e.g., `-Djustsearch.repo.root=/path`) - **Highest Priority**
2. **Environment Variable** (e.g., `JUSTSEARCH_REPO_ROOT=/path`)
3. **Auto-discovery** (traverse up from CWD looking for `SSOT/` directory)

**Never use `System.getenv()` directly.** Use `EnvRegistry.MY_VAR.get()`.

## 2. Testing Guidelines

### 2.1. The Test Pyramid

| Test Type | Directory | Purpose |
| :--- | :--- | :--- |
| **Unit** | `src/test` | Isolated classes, mocked deps. <10ms. |
| **Integration** | `src/integrationTest` | Module wiring, real Logic. |
| **System** | `modules/system-tests` | Spawns real child processes. IPC verification. |

### 2.2. Testing IPC

**Retired pattern (lane F stage A).** `WorkerProcessManager`, `MmfTestHarness` and `GrpcTestClient` were deleted
at lane F stage A (items A11-A14): there is no worker process to spawn, no memory-mapped port to
discover and no historical gRPC client to build. Tests that need a working index compose the Engine in-process
through `EngineRoot` and run in `modules/app-engine/src/test/java/io/justsearch/app/engine/` on every
build; see [Testing Strategy](../../explanation/09-testing-strategy.md). The process boundaries that
survive — `llama-server` and the extraction sandbox child pool — are still exercised from
`modules/system-tests`.

### 2.3. Test Tiers in Practice

Use the right Gradle command for the verification level you need:

| Command | What runs | When to use |
|---------|-----------|-------------|
| `node scripts/lib/tool-invoker.cjs gradle test` | Unit tests only (`src/test/java`) | **Fast inner loop** — after every code change |
| `node scripts/lib/tool-invoker.cjs gradle check` | Unit + integration + PMD + Spotless | **Pre-commit** — before pushing |
| `node scripts/lib/tool-invoker.cjs gradle fullTestSuite` | Unit + integration + system (system tier requires `-PincludeSystemTests=true`) | Full verification (system-tests module) |

**Opt-in flags** (system-tests module only):

- `-PincludeSystemTests=true` — include system/chaos tests
- `-PincludeAiTests=true` — include AI inference tests (requires GPU)
- `-PincludeAgentTests=true` — include agent deterministic battery tests

Source of truth for test tier definitions: `modules/system-tests/build.gradle.kts`.

**Load-sensitive latency gates are quarantined, not weakened.** A test that asserts wall-clock
latency fails on a machine running a concurrent Gradle build for a reason that has nothing to do
with the code — and the right response is never to raise the threshold, because a tight threshold
is exactly what makes it a regression detector. Tag it `@Tag("load-sensitive")`, exclude the tag
from the suite that `check` depends on, and run it from a dedicated task on an idle machine:

| Command | What runs |
|---------|-----------|
| `node scripts/lib/tool-invoker.cjs gradle :modules:app-services:loadSensitiveTest` | the `load-sensitive` latency gates, thresholds unchanged |

There is **no perf-ratchet CI lane** today, so these tasks are manual by construction: wiring one
into hosted CI would reproduce the flake there instead of here. If a perf lane is ever added, wire
every `loadSensitiveTest` task into it rather than back into `check`. Worked case:
`LambdaMartBenchmarkTest` (5 ms p50; measured 8.6-22 ms under sibling-worktree build load on four
occasions in one session, green on every isolated re-run), quarantined 2026-09-07.

**AI eval runtime policy (canonical):**

1. Keep AI tests opt-in for normal dev and CI verification paths (`-PincludeAiTests=true` required).
2. Use full RAG quality runs (`*RagQualityEvalTest`) on a perf-capable environment (dedicated self-hosted runner or equivalent local setup), not every default `check` run.
3. Run `node scripts/verify-prerequisites.mjs` before AI quality runs that depend on native runtime/model availability.

### Recurring jobs

- **Named checks, fresh results:** `js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs --out <evidence dir>/<name>.json --check <check id> ...` records executed, skipped and not-found checks. JVM IDs are test-class source paths, optionally `#<method>`. Vitest file paths require installed `modules/ui-web/node_modules` (launcher checkouts lack it). Rust IDs require `#<full libtest name>`; Rust checks are unqualified. The maintainer's `js-gradle-slot` wrapper covers the whole script because it runs Gradle itself. `--before` needs `git worktree add`, unavailable in sandboxes that refuse it. It needs Git LFS initialised in the repository (`git lfs install --local` once).
- **Searchable after ingest:** with an Engine your session owns, poll `POST /api/knowledge/search` for a unique marker until the expected document ID is returned, with a deadline. `stageAndVerify` in `scripts/dev/lib/stage-reference-corpus.mjs` accepts any result; it is insufficient proof alone.
- **Acceptance digest:** `node scripts/governance/gates/test-intent/cli.mjs digest <changeset> --base <task base>`.
- **Test totals:** `node scripts/ci/report-unit-test-attribution.mjs` reads every test task under a module root. Running one task freshly can mix its results with another task's stale results. Use run-evidence for named checks.
- **Isolated Engine:** `dev-runner` needs a fixed frontend port and always starts the frontend. Sandboxed sessions start an Engine with their own `JUSTSEARCH_HOME`, `JUSTSEARCH_DATA_DIR`, `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` and API port 0; no one-command isolated start is listed here.

## 3. Development Workflow

### 3.1. Git Stash Warning

**Never use `git stash` to inspect staged state.** Use `git diff --cached --stat`
instead. Stash with `--keep-index` followed by `pop` silently drops unstaged
modifications when the working tree has staged renames or a complex mix of staged
and unstaged changes. This has caused silent data loss in production agent sessions.

### 3.2. Long-Running Eval Hygiene

Use wrapper-first orchestration for long-running evaluation and benchmark sessions:

- Canonical wrapper: `python -m jseval`
- Do not treat raw backgrounded PowerShell eval commands as the normal orchestration path

Path and dependency rules:

- Model/native path environment variables used for Java or ONNX launches should be absolute
- After changing Gradle dependencies or telemetry/exporter coordinates, regenerate lockfiles before handoff:
  `node scripts/lib/tool-invoker.cjs gradle --no-configuration-cache resolveAndLockAll --write-locks`

### 3.3. Pre-commit Verification

Before pushing changes, run the relevant verification commands:

```powershell
# Heavy verification
node scripts/lib/tool-invoker.cjs gradle check --no-configuration-cache

# Pre-merge checks (run individually when their subjects change — see
# docs/reference/contributing/pre-merge-checks.md for the canonical list)
node scripts/ci/check-workflow-triggers.mjs
node scripts/ci/check-root-readme.mjs
node scripts/governance/run.mjs --gate wire --mode gate
```

#### PMD Rule Configuration

PMD is configured in `config/pmd/ruleset.xml` across 6 categories:

| Category | Rules |
|----------|-------|
| codestyle (1) | `UnnecessaryFullyQualifiedName` |
| errorprone (10) | `BrokenNullCheck`, `DoNotTerminateVM`, `EmptyCatchBlock`, `EqualsNull`, `ImplicitSwitchFallThrough`, `MisplacedNullCheck`, `OverrideBothEqualsAndHashcode`, `ReturnFromFinallyBlock`, `UnconditionalIfStatement`, `UselessOperationOnImmutable` |
| multithreading (3) | `AvoidThreadGroup`, `DoubleCheckedLocking`, `NonThreadSafeSingleton` |
| security (2) | `HardCodedCryptoKey`, `InsecureCryptoIv` |
| bestpractices (6) | `UnusedLocalVariable`, `UnusedPrivateField`, `UnusedPrivateMethod`, `UnusedAssignment`, `UnusedFormalParameter`, `SystemPrintln` |
| documentation (1) | `CommentContent` — the parked-marker (TODO/FIXME/XXX) ban, successor to the retired `todo-fixme` kernel gate |

**Scope — every Java source set.** `pmdMain` uses `ruleset.xml`; every other source set
(`test`, `integrationTest`, `systemTest`, `determinismTest`, `testFixtures`) uses
`config/pmd/ruleset-tests.xml`, which is the same ruleset minus `SystemPrintln` (a test's console
output is its report) and `NonThreadSafeSingleton` (it fires on `@BeforeAll`/`@AfterAll` fixture
assignment, which JUnit serialises). CLI-entry-point modules (`ssot-tools`, `core-contracts`) point
`pmdMain` at `config/pmd/ruleset-cli-tools.xml`, which drops `SystemPrintln` and `DoNotTerminateVM`.

All three files inline their rules — PMD's `rule ref` resolves against the CLASSPATH, not the
working directory, and an unresolvable ref makes PMD analyse **zero files while Gradle reports
success**. `scripts/ci/check-pmd-ruleset-sync.mjs` therefore enforces that each derived ruleset is
the authority minus its declared, description-justified subtraction, and that no ref is a file path.

Run everything with `node scripts/lib/tool-invoker.cjs gradle pmdAll` (root aggregate over all 93 PMD tasks); CI runs the same
task in the required `Build (no model blobs)` job, measured at 188 s there. Running it instead on
`Unit tests (platform-contracts)` was tried and measured worse (201 s, and that lane over its
advisory budget) — `pmdAll` compiles every source set of every module either way, so the placement
is decided by which job has wall-time headroom, not by which already compiles tests.
`-PskipPmd=true` is the local fast-iteration escape hatch; CI never sets it.

**Exception handling policy:**

An audit of ~100 `catch (Exception ignored) {}` sites found that silent exception swallowing was the codebase default because it was 1 line while proper handling was 5-10 lines. This created invisible failures: config reload errors, health endpoint crashes, index cleanup leaks, and streaming truncation — all with no log, no metric, no signal. The `Faults` utility was built to make the right thing equally convenient, and the PMD rule was tightened to prevent recurrence.

Every `catch` block must either (a) log the exception, (b) rethrow/wrap, or (c) include a comment explaining why silence is correct. Use `Faults.logAndContinue()` or `Faults.logAndFallback()` from `modules/configuration` for fault-isolation patterns — these are 1-line replacements for `catch (Exception ignored) {}`. PMD enforces `EmptyCatchBlock`: naming the variable `ignored` is NOT permitted (the `allowExceptionNameRegex` only accepts `expected`). For genuinely expected exceptions (parse fallbacks), use `expected` as the variable name with a comment.

**Rules intentionally excluded:**
- `CompareObjectsWithEquals` - Intentional identity comparisons exist (Thread, Throwable cause checks)
- `AvoidBranchingStatementAsLastInLoop` - Flags valid break-after-find patterns
- `AvoidCatchingGenericException` - Intentional error handling patterns

#### Intentional Code Patterns

Some code patterns trigger static analysis warnings but are intentional:

**Identity comparisons (`==`):**

```java
Thread.currentThread() == ownerThread  // Thread identity check
cause != throwable                      // Throwable cause loop prevention
indexRuntime != activeRuntime           // Runtime instance check
block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS  // Enum-like singletons
```

**Intentionally silent expected exceptions:**
Only use an empty catch when the exception is genuinely expected and silence is correct. PMD only allows the exception variable name `expected`; `ignored` is not permitted. Prefer logging, rethrowing, wrapping, or `Faults.logAndContinue()` / `Faults.logAndFallback()` for fault-isolation paths.

```java
} catch (NumberFormatException expected) {
  // fallback to default value
}
```

#### Error Prone Configuration

[Error Prone](https://errorprone.info/) is Google's compile-time bug detector, applied as a `javac` plugin via `conventions.errorprone` (`build-logic/src/main/kotlin/conventions/ErrorProneConventionsPlugin.kt`). Generated sources are excluded via `-XepExcludedPaths:.*[\\/]build[\\/]generated[\\/]`.

**Global suppressions:** `config/errorprone/suppressions.txt` - one check name per line. Currently suppressed:

| Check | Reason | Warnings suppressed |
|-------|--------|---------------------|
| `PatternMatchingInstanceof` | Java 21 pattern matching style preference | - |
| `ReferenceEquality` | `Character.UnicodeBlock` constants are singletons; `==` is correct | 112 |
| `StringSplitter` | Simple `String.split()` usage where behavior is well-understood | 10 |

**How to suppress a new check:**
- **Globally:** Add the check name to `config/errorprone/suppressions.txt` with a comment explaining why
- **Per-site:** Add `@SuppressWarnings("CheckName")` to the method or class

#### Additional Static Analysis Tools

**Dependency Analysis (DAGP):**

```bash
node scripts/lib/tool-invoker.cjs gradle buildHealth  # Full dependency analysis
```

Detects unused dependencies, wrong configurations (api vs implementation), and used-but-undeclared transitive dependencies. Report at `build/reports/dependency-analysis/build-health-report.txt`.

**Lock-skew convergence gate:**

```bash
node scripts/ci/report-lock-skew.mjs --root . --fail-on-unexpected-skew --out-json tmp/lock-skew-report.json
```

Fails when duplicate locked coordinates exist outside the optional allowlist. With no allowlist, this is a zero-skew check.

**Lockfile freshness check:**

CI does not regenerate Gradle lockfiles or compare them for freshness. After dependency changes,
run `resolveAndLockAll --write-locks` locally and review the lockfile diff before handoff.

**Never hand-edit lockfiles.** Always regenerate with `resolveAndLockAll --write-locks`. Manual edits miss auxiliary configurations (like PMD aux classpaths) that only appear during full resolution.

Regenerate lockfiles after changing versions in `libs.versions.toml`, adding/removing dependencies, or changing plugin versions:

```bash
node scripts/lib/tool-invoker.cjs gradle --no-configuration-cache resolveAndLockAll --write-locks
```

**Unreferenced Code Detection (ArchUnit):**

```bash
node scripts/lib/tool-invoker.cjs gradle :modules:app-launcher:test --tests "*UnreferencedCodeTest*"
```

Detects unreferenced private methods across the codebase using bytecode analysis. Known false positives (FFM callbacks, test infrastructure) are frozen in `archunit_store/`.

To freeze new false positives:
1. Edit `modules/app-launcher/src/test/resources/archunit.properties`
2. Set `allowStoreCreation=true` and `allowStoreUpdate=true`
3. Run the test
4. Restore both settings to `false`

**Module Staleness Detection:**

```bash
node scripts/architecture/module-deps.mjs --include-staleness
```

Flags modules with zero dependents that haven't been modified in 6+ months. Entry-point modules (app-launcher, shell, benchmarks) naturally have zero dependents and are excluded.

#### `@RecordBuilder` Convention

Records in `app-api` that have **>10 positional parameters** and are **constructed across multiple modules** should be annotated with `@RecordBuilder` (io.soabase.record-builder). This generates a fluent builder so new fields don't break callers. See [ADR-0022](../../decisions/0022-recordbuilder-annotation-processor.md) for the decision rationale and rejected alternatives.

**When to annotate:** When you add a field to a record and the positional constructor change causes compilation errors in multiple modules. Don't annotate preemptively — annotate when coupling pain first appears.

**Enforcement:** An ArchUnit rule in `ArchitectureRulesTest` flags positional construction of annotated records in production code. The rule maintains an explicit type list — add new records to `RECORD_BUILDER_TYPES` when annotating.

**Currently covered by the ArchUnit rule (7 records):** `KnowledgeSearchResponse`, `KnowledgeSearchResponse.Hit`, `MigrationGenerationView`, `EnrichmentProgressView`, `WorkerDebugView`, `WorkerOperationalView`, `KnowledgeStatusView`.

**Adding a field to an API record:**

Follow the canonical [Add a field to an API record](common-workflows.md#add-a-field-to-an-api-record)
recipe. It covers construction sites, controller response maps, schema regeneration, generated
TypeScript/Zod wire types, and backward-compatibility baselines. Do not hand-author a second
frontend wire type or schema.

### 3.4. UI smoke checks

#### 3.4.1 Start the dev stack (backend + UI)

```powershell
# From repo root
$env:JUSTSEARCH_API_PORT = "33221" # pick a deterministic base port (dev-all will try near this)
npm --prefix ./modules/ui-web run dev:all
```

On Linux (Bash), from the same repository root:

```sh
JUSTSEARCH_API_PORT=33221 npm --prefix ./modules/ui-web run dev:all
```

#### 3.4.2 Deterministic readiness checks

Before interacting with the UI, verify the backend is ready:

- `GET http://127.0.0.1:<apiPort>/api/status` returns 200
- `GET http://127.0.0.1:<apiPort>/api/health` returns 200 when the Engine lifecycle is ready or
  degraded. Inspect its schema-2 `components` (`api`, `index`, `encoders`, `generative`) for
  capability readiness.

`dev:all` prints the chosen backend endpoint (it may pick a nearby port if the base port is busy).

#### 3.4.3 Open the UI in a browser

Open the UI with an explicit backend port:

- `http://localhost:5173/?api_port=<apiPort>`

Notes:

- Prefer `localhost` for the UI URL. Some agent browser environments cannot reach `127.0.0.1` even when the host OS can.
- If the UI loads but shows "disconnected", check:
  - the backend readiness endpoints above
  - browser console messages and network requests

#### 3.4.4 What to verify in the UI (minimal "it works")

- Confirm the status bar shows **Connected**.
- Navigate between tabs (Search / Library / AI Brain / Health) and ensure each page renders.
- On **Health**, confirm API and Index indicators are green.

Desktop-only features (native folder picker, file dialogs) are unavailable in a pure browser run. A warning banner is expected.

#### 3.4.5 Debugging tools

- Prefer inspecting API responses directly (`/api/status`, `/api/health`) over inferring state from UI text.
- If the UI is blank or unresponsive:
  - check the browser console for errors
  - check Vite output and backend logs

#### 3.4.6 Clean shutdown

- Stop the dev stack with `Ctrl+C` in the terminal running `dev:all`.
- On Windows, if processes linger, terminate the process tree (e.g., via `taskkill /T /F`) to avoid stale ports.

#### 3.4.7 Visual verification of UI component changes (jseval ui-shot)

When editing files under `modules/ui-web/src/`, use `jseval ui-shot` for targeted screenshot capture to verify visual correctness:

- **Single step**: `jseval ui-shot <step>` captures a screenshot of the named UI state. Read the output PNG path to see the result inline.
- **Step discovery**: `jseval ui-shot --list` shows all available steps (chain and isolated) with their dependency relationships.
- **Affected steps**: `jseval ui-shot --affected <file>` finds which steps exercise a given source file. Works with full Windows paths.
- **Measurement companion (615 §6.2)**: every capture also writes a `<step>.measure.json` (accessibility tree + axe violations + element geometry/overflow + console errors) and prints a one-line fact summary. Judge correctness from the **facts** (cheaper + more reliable than the PNG); read the PNG for gestalt. The harness drives Lit `shell-v0`. `python -m jseval ui-shot` defaults to mock-data demo mode. For a real backend, use `--no-demo` with a running dev stack (AI also needs `ai_activate`), and choose a step without its own response mocks or demo-URL navigation: those steps still use demo data with `--no-demo`.

### 3.5. CI signal model

The public repository has two CI postures:

- The standard hosted `CI` workflow runs automatically on pull requests and
  pushes to `main`, and can also be dispatched manually. ADR-0044 owns this
  public hosted lane, amended by ADR-0053. PRs run affected lanes; main and manual dispatch
  run the full suite, including the slow tiers. Every required name reports on every PR.
  Measured axe is required; on PRs it runs for affected frontend inputs, and it runs
  on pushes to main and manual dispatch.
- Self-hosted, release, and benchmark/specialty workflows stay manually dispatched unless a
  later ADR changes their trigger policy. ADR-0026 remains the historical basis for that posture.
  CodeQL runs weekly on Mondays at 04:17 UTC and can also be dispatched manually (ADR-0052).

The public hosted `CI` workflow is split into stable fact lanes: public claims,
license and notices, no-model build, unit tests, secret scan, and the
`jseval Python suite` (required since 2026-09-05, ADR-0044 amendment). A red
check should name the fact that failed rather than one generic build bucket.

The public unit-test signal uses nine execution shards across UI, services, search-worker
and platform-contracts. `Unit tests (app-ui)`, `Unit tests (search-worker)` and
`Unit tests (platform-contracts)` aggregate their selected shards, accepting intentional skips
and failing on a shard failure or cancellation. The expensive source sets use disjoint
class partitions from `scripts/ci/unit-test-class-shards.v1.json`; local `test` tasks remain complete.
Each execution lane publishes a unit-test attribution report from Gradle/JUnit XML. Use those reports to
identify slow modules, slow suites, skipped counts, and hosted runner image
identity before proposing another workflow split. Do not split the lanes solely
by runtime without an evidence boundary.

`scripts/ci/unit-test-shard-policy.v1.json` is the checked-in contract for those
unit shards. It declares each shard's check name, artifact, runner label, Gradle
task list, local reproduction command, owner, platform classification, and
warn-only budget settings. Run
`node scripts/ci/verify-unit-test-shard-policy.mjs` after changing `ci.yml`,
workflow-signal policy, unit shard membership, or unit-shard budget metadata.

The unit-test shards run with `-PskipWebBuild=true` because the web bundle is
owned by the separate `Build (no model blobs)` fact lane. Keep that boundary
intact: if web assets need verification, use or extend the build lane rather
than making unit-test lanes prove the same fact again.

Each unit shard also publishes an advisory budget report. Budget warnings are
diagnostic evidence only; they must not replace the Gradle test step as the
pass/fail source, and blocking thresholds require a later explicit design after
hosted trend samples exist.

The public-claims lane verifies `scripts/ci/test-evidence-policy.v1.json`.
Whenever a Java test is skipped under `CI=true`, or a non-stress JUnit tag is
introduced, update that policy with the owner, evidence tier, replacement
signal, and cadence. Stress tags remain governed by
`scripts/ci/stress-suite-policy.v1.json`.

Hosted CI checks finished candidates. Run the local subset before each push;
Windows-only behaviour is the exception when it requires the hosted Windows runner.

**Dispatch `ci.yml` manually** when you need a fresh hosted run outside the
normal pull-request or push trigger, for example after updating a branch whose
previous run was cancelled or when rechecking after a transient hosted-runner
failure.

**Dispatch manual specialty workflows** when you changed a subject of their
gate:

- `docs-lint.yml` after edits under `docs/**`, `scripts/docs/**`, or
  `scripts/architecture/module-deps.mjs` if you need remote docs verification.
- `codeql.yml` when you need semantic code scanning before its weekly run
  (Mondays, ADR-0052).
- `build-installer.yml --ref <vX.Y.Z>` for installer/release attach validation.
- `update-preserves-models.yml` after editing NSIS hooks, `tauri.conf.json`
  bundle resources, or sidecar staging — it installs a published release, seeds
  model files, upgrades to the next release and asserts they survive
  (`docs/how-to/verify-update-preserves-models.md`). The
  `check-update-preserves-models` script is only the static half.
- `sign-vendored-mirrors.yml` is a release-cut step documented in
  `docs/how-to/cut-a-release.md`.
- `onramp-smoke.yml` for the model-less Tier 0 first-success proof;
  `ci-walltime-trend.yml` for CI wall-time trend reporting.

After changes to query orchestration, fusion weights, reranking, or anything
that could shift nDCG@10, re-run the arm and compare (no workflow wraps it):
`python -m jseval run --start-backend --dataset <slug> --modes full`, then
`python -m jseval relevance-gate --dataset <slug>` against its pinned floor.
There is no calibrated σ to compare against — tempdoc 930 §18.1 row 7 removed
the cohort-envelope machinery, which never produced a baseline.

The `agent-live-eval-nightly.yml`, `rr219-resilience-governance-nightly.yml`,
`rr219-resilience-soak-weekly.yml`, `track-g-report-win.yml`, and
`claim-a-report-win.yml` workflows were retired in slice 3a-1-8f §B.14
(2026-05-12). Their underlying DAG runners and bench infrastructure were
deleted by commit `a9c484f59` (2026-03-16); jseval covers the substance
(`scripts/jseval/` — `agent-eval`, `retrieval-eval`, `rag-eval`,
`bench-concurrency`, etc.). `phase-3-observability-nightly.yml` was likewise
retired (2026-07-07) — it never ran automatically in its history (ADR-0026);
its manual `jseval gate` capability is unaffected (tempdoc 930 §18.1 row 7
narrowed that gate to a projection-presence check).

**How to trigger and inspect:**

```text
gh workflow run ci.yml                              # re-run public hosted fact lanes
gh workflow run docs-lint.yml                       # manual docs verification
gh workflow run codeql.yml                          # semantic code scanning
gh workflow run build-installer.yml --ref <vX.Y.Z>  # installer/release attach
gh workflow run update-preserves-models.yml         # N->N+1 model survival
gh run list --workflow=<name> --limit=1             # check latest status
gh run view <id>                                    # inspect a specific run
```

Main branch protection requires the stable public check names declared in
`scripts/ci/workflow-signal-policy.v1.json`. Run
`node scripts/ci/check-branch-protection.mjs --repo justsearch-app/justsearch --branch main`
with a maintainer token after changing CI job names or branch-protection settings; the default
pull-request `GITHUB_TOKEN` cannot read branch-protection settings.

### 3.5.1. CI Failure Troubleshooting

**Test failure:**

- Symptom: a `Unit tests (<lane>)` job fails. Read the Gradle log or download its
  `unit-test-attribution-*` artifact for JUnit XML and attribution reports.
- Reproduce: `node scripts/lib/tool-invoker.cjs gradle :modules:<m>:test --tests "*FailingTest"`
- Fix: Read the failing test, fix the code or test expectation, re-run.

**PMD violation:**

- Symptom: `node scripts/lib/tool-invoker.cjs gradle check` fails with "PMD rule violations found".
- Reproduce: Check `build/reports/pmd/main.xml` under the failing module for `file:line:rule` detail.
- Fix: Fix the flagged code pattern. See §3.3 for intentionally excluded rules and accepted patterns.

**Spotless formatting:**

- Symptom: `node scripts/lib/tool-invoker.cjs gradle check` fails with "Spotless violations found".
- Reproduce: `node scripts/lib/tool-invoker.cjs gradle spotlessCheck`
- Fix: Run `node scripts/lib/tool-invoker.cjs gradle spotlessApply`, then re-stage the changed files.

**Docs lint (markdownlint):**

- Symptom: `docs-lint` workflow fails with `file:line MD0xx` violations.
- Reproduce: `npx markdownlint docs/explanation/**/*.md docs/reference/**/*.md docs/how-to/**/*.md docs/decisions/**/*.md`
- Fix: Fix the Markdown issue. Common violations: MD040 (missing code fence language tag — use `text` for plain output), MD032 (blank line around lists).

**Docs lint (link check):**

- Symptom: `docs-lint` workflow fails with "broken link" or "tempdoc cross-reference" error.
- Reproduce: `node scripts/docs/verify-canonical-doc-links.mjs`
- Fix: Fix the broken link, or remove the `docs/tempdocs/` cross-reference from canonical docs (canonical docs must not link to tempdocs).

**Docs lint (runtime config matrix):**

- Symptom: `docs-lint` or `ci` fails with a runtime config matrix mismatch error.
- Reproduce: `node scripts/docs/verify-runtime-config-matrix.mjs`
- Fix: Regenerate and/or update `docs/reference/configuration/runtime-config-ownership-matrix.md` using `node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md`.

**Docs lint (module dependency graph drift):**

- Symptom: `docs-lint` or `ci` fails because canonical module dependency docs are stale.
- Reproduce: `node scripts/architecture/module-deps.mjs --check-canonical`
- Fix: Run `node scripts/architecture/module-deps.mjs --update-canonical`, then commit the updated `docs/reference/architecture/module-deps.md`.

**llms.txt drift:**

- Symptom: `docs-lint` workflow fails with "llms.txt is out of date".
- Reproduce: `node scripts/docs/llmstxt-generate.mjs --check`
- Fix: Run `node scripts/docs/llmstxt-generate.mjs` to regenerate, then commit the updated `docs/llms.txt`.

### 3.6. Module-Scoped Verification

Name the changed modules and the modules that depend on them in one Gradle invocation:

```powershell
# Example: build + test app-engine and its app-launcher dependent together
node scripts/lib/tool-invoker.cjs gradle :modules:app-engine:build :modules:app-launcher:build
```

Choose the modules from the dependency graph:
`node scripts/architecture/module-deps.mjs --include-staleness`.
If nothing depends on the changed module, its own `:modules:<module>:build` is sufficient.
Do not use `:modules:<module>:buildDependents` in this build: it fails with a circular task
dependency between app-engine, app-services and indexer-worker.

### 3.7. History publication (PR/commit granularity)

Public `main` is a curated narrative, not a working log. The policy has two
independent halves, and getting either wrong reintroduces the agent-transcript
history shape this repo deliberately avoids (tempdoc 653; the old private repo
reached 6,563 commits, 3,331 of them touching `docs/tempdocs`).

**Axis 1 — how a branch lands (mechanized, you don't manage it).** ADR-0045 set
the repo to squash-only: every PR collapses to one `main` commit whose title and
complete body come from the *PR* title/body, not the branch commit list. Keep
those two fields permanently commit-safe. Mutable authorship, scope/risk,
verification evidence, and review state belong in exactly one managed PR comment
created from `.github/pr-review-record-template.md`; they never move temporarily
through the PR body.

Use `scripts/ci/pr-review-record.mjs upsert --pr <N> --file <review-file>` to
preview the exact comment create/update. The command prints a fingerprint; pass
that value to the same command with `--execute --confirm <fingerprint>`. It
refuses duplicate markers, foreign ownership, stale head/body metadata, and
changes visible at its final preflight or exact read-back. GitHub comment
updates have no compare-and-swap precondition, so the authenticated comment
owner must be the sole writer from dry-run through read-back; do not manually
edit the managed comment concurrently. Run `pr-review-record.mjs check --pr <N>` and
`preview-squash-message.mjs --pr <N>` after the final push and review edit so
findings can be corrected. The validated enqueue gateway repeats both live checks
immediately before enabling auto-merge. The first command verifies the rich record;
the second rejects review structure, template residue, provider prose, task boxes,
and operational state from the public commit.

Check summaries and artifacts may hold reproducible detail, but the managed
comment is the stable index. Do not use a check summary as the only durable
review record. Landed-message comparison is semantic rather than byte-exact:
GitHub may hard-wrap long lines and append its own attribution. Public bodies
therefore avoid tables or nested formatting whose meaning depends on wrapping.

Four `gh` CLI quirks (tempdoc 695, 930) worth knowing at merge/wait time:

- **A push does not guarantee a CI run.** GitHub's `synchronize` event
  intermittently does not fire, leaving a PR whose newest green check belongs
  to an *older* commit — which reads as green on the PR page, because the
  evidence is real, just about a different tree. `gh pr checks` cannot tell the
  difference. Before trusting checks, confirm one exists for the exact head sha:
  `node scripts/ci/ensure-ci-on-sha.mjs [<branch>] [--remote]` — it looks for a
  run whose `headSha` matches, dispatches `ci.yml` for the ref if none appears,
  and confirms the dispatched run actually carries that sha. Idempotent, so
  re-running it is a no-op; `--no-dispatch` reports without acting. This is the
  precondition for the next bullet: waiting on checks only means something once
  you know checks exist for the right commit.
- **Waiting on a PR's CI completion** fits `node scripts/dev/run-gh.mjs
  checks-wait <N>` (backgrounded) better than a hand-rolled poll loop — the
  latter costs hundreds of loop iterations and GitHub API calls on a
  multi-minute job, and can outlast the Bash tool's own command timeout.
  `checks-wait` pre-polls
  until checks register on the branch, then decodes `gh pr checks`'s
  documented 0=pass/1=fail/8=pending bitwise exit contract instead of
  guessing at it (tempdoc 743 P-K). For a specific landed commit, use
  `node scripts/dev/run-gh.mjs run-wait-sha <sha> --workflow CI --branch main
  --event push`; it owns registration polling, emits only transitions, and
  has a bounded timeout. Watching
  immediately after *any* push (not just the batch case further below) can
  race check registration — `checks-wait`'s pre-poll is exactly that
  mitigation; see the registration-race bullet in Batch-publishing below and
  never chain the wait with the merge in one command.
- **`checks-wait --required-only` can report green with nothing verified**
  (tempdoc 930 publication, 2026-09-05, three occurrences). While a PR is
  `CONFLICTING` GitHub builds no merge commit, so no CI registers and "all
  required checks green" is vacuous; seconds after a push it can read the
  superseded run's cached state; and a check you are in the middle of
  *promoting* to required is not yet in the required set, so it is skipped.
  Before enqueueing, confirm the PR is mergeable, that the checks
  belong to the current `headRefOid`, and that `gh pr checks <N> --required`
  lists every context you expect.
- **`origin/main` moves under a long task.** Background re-fetches advance the
  remote-tracking ref mid-task, so `git diff origin/main` drifts (a 100k-line
  "leak" that is only base drift), and `git reset --soft origin/main` can
  silently give a rebuilt commit a parent that already contains a later PR —
  which reverts that PR on push. Re-fetch immediately before every comparison
  and verify `git log -1 --format=%P` after any soft reset.

Follow [ADR-0053 § Decision](../../decisions/0053-one-pr-ci-run-and-squash-auto-merge.md#decision) for validated squash enqueueing.
After enqueue, run its printed command in the background:
`node scripts/dev/run-gh.mjs merge-wait N --head <validated-head-sha> --timeout-sec 5400`
(include `--repo owner/repo` if enqueue used it). The 40-hex `--head` binds observation to
the enqueued head; omitting it watches the first poll's head. The observer polls every
60 seconds and reads classic branch protection and all pages of rulesets once at start,
using the union of their required contexts. Its 10-minute stall timer runs only while
every required context has reported a passing result. If classic protection cannot be
read because of permissions, or both sources contain no required contexts, it warns once
and disables stall detection; the timeout still applies and names missing contexts when
known. Other read failures or invalid responses exit 2.
Exit 0 means merged. Exit 1 means follow-up
work on the same task; the message says what needs attention and when to enqueue again.
Exit 3 means inspect the PR, then start observing again; enqueue again only if the message
says to. Exit 2 means retry once, then investigate if it fails again.

For CI selection, publication and post-merge verification, follow [ADR-0053 § Decision](../../decisions/0053-one-pr-ci-run-and-squash-auto-merge.md#decision)
and its [Measured axe amendment](../../decisions/0053-one-pr-ci-run-and-squash-auto-merge.md#amendment-2026-10-24-measured-axe-becomes-required).

Validate the local reproduction inventory with `node scripts/ci/run-publish-preflight.mjs --check`
before pushing. Confirm the merge with `node scripts/dev/run-gh.mjs merge-wait N` before cleanup.
The gateway's reads and write remain a short sole-writer window: avoid concurrent edits to the
review record and PR metadata while enqueue runs. Direct merge commands bypass its validation.

**Axis 2 — whether a change deserves its own PR (judgment, your call).** Squash
fixes *intra*-PR noise; it does nothing about *inter*-PR noise — one trivial PR
per edit still produces one standalone public commit each. The convention (industry-standard — Microsoft's
engineering playbook puts docs in the code PR, Kubernetes' guide says batch tiny
fixes rather than open a PR each):

| Change | How it should reach `main` |
|---|---|
| **tempdoc** (`docs/tempdocs/**`) — dated working history | None: tempdocs are frozen since 2026-10-05. Current design goes to a canonical doc or an ADR. |
| **canonical doc** (`docs/{explanation,reference,how-to,decisions}`) — durable current truth | May stand alone as its own PR/commit. |
| **docs + code together** | Already a ride-along — publish normally. |

Worked example (from before the freeze): a post-merge research pass that only appends a "future
directions" section to a tempdoc is working history, not a project-history unit.
Landing it as its own PR (`docs(644): … (#16)`) is exactly the axis-2 miss —
tidy under axis 1, but a standalone public commit for archaeology. It should have
ridden along with the next substantive PR or batched.

### 3.8. A change to a governed decision updates its ADR in the same PR

A change that moves a decision an ADR governs updates that ADR's
`last_reviewed:` and `probes:` in the **same PR** as the change.

Why the same PR: a decision that moved in code or another doc while its ADR still reads the
old way is not a documentation lag, it is two authorities disagreeing — and the
newer one is the one nobody consults. That is precisely the drift the premise-probe
register (`governance/adr-probes.v1.json`, tempdoc 884) exists to catch, and it can
only catch it if the probe is re-pointed at the *new* premise. A probe left restating
the superseded decision is worse than no probe: it reports green about a claim the
ADR no longer makes.

How:

- **Find the governing ADR** — grep `governance/adr-probes.v1.json` for the surface
  you changed, or scan the Decision Log in `docs/decisions/README.md`. The
  `architecture-decisions` row in `governance/consult-register.v1.json` lists the
  procedure for changes under `docs/decisions/`.
- **Amend, don't rewrite** — follow `docs/decisions/README.md` § How to re-examine
  an ADR. Amendments are append-only; the superseded reasoning is the record, so add
  a dated amendment rather than editing Context/Decision in place.
- **Re-point the probe, then re-date** — update or replace the `probes:` entry so it
  restates the new premise, and set `last_reviewed:` to today. Never edit a probe
  merely to make it pass.
- **Verify** — `node scripts/governance/run.mjs --gate adr-coverage --mode gate`.
  Review staleness is also listed by the orientation command
  `node scripts/agent-analytics/world-state.mjs` (§ ADR review) — run it, nothing runs it
  for you — because a CI-only
  warning about a decision nobody is scheduled to re-read is the pile that produced
  this rule.

Predictable evasion: *"the ADR update can be a follow-up PR."* Follow-ups that never
came are tempdoc 742's entire corpus — ~350 files of residue and two inert gates, all
of it deferred by exactly that sentence. The ADR edit is small and you are already in
the change that makes it necessary; there is no cheaper moment, and there will not be
a later one.
