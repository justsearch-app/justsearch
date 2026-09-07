<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# JustSearch — Claude Code Instructions

`AGENTS.md` is the compact cross-harness contract. This file is the Claude Code
delivery surface for that contract plus Claude-specific workflow details.

Canonical entry points: `docs/llms.txt` (docs index), `docs/tempdocs/` (active work).

## Hard Invariants (Do Not Violate)

<!-- generated:agent-invariants:start — source: AGENTS.md; run: node scripts/docs/agent-instructions-sync.mjs -->
1. **Head never touches Lucene.** All index I/O belongs to the Worker and is reached through gRPC. <!-- rule:head-never-touches-lucene -->
2. **Preserve the local API trust boundary.** Bind to loopback, enforce the Host allowlist, validate MCP Origin, and require the per-boot mutation token where ADR-0046 requires it. <!-- rule:loopback-only-network -->
3. **Do not resurrect legacy endpoints.** `/api/search` and `/api/settings` are removed contracts. <!-- rule:no-legacy-endpoints -->
4. **Verify, do not guess.** Use `/api/debug/state` and `/api/health` for lifecycle state and `/infra/capabilities` for `host.*` contract versions. <!-- rule:verify-dont-guess -->
5. **The frontend is Lit, not React.** The active UI is the `shell-v0` web-components stack. <!-- rule:frontend-stack-is-lit -->
6. **Search analysis is locale-invariant.** Do not add per-language analyzers, fields, stopwords, spelling dictionaries, or curated synonym authorities. The multilingual model stack supplies multilingual behavior. <!-- rule:language-agnostic-analysis -->
<!-- generated:agent-invariants:end -->

## Agent Discipline

### Explore Before Implementing <!-- rule:explore-before-implementing -->

Before new code, check for existing infrastructure:

1. **The module you're working in** for helpers and patterns.
2. **Related modules** — how other controllers handle errors; how neighboring services are structured.
3. **`docs/llms.txt`** and canonical docs for documented patterns.

Failure mode to avoid: creating a new utility function when an identical one exists two packages over. This is the single most common agent mistake in this codebase.

**Before authoring a new *representation* of existing data** (a record/type/projection/span/schema that describes something already modelled — e.g. "what the search pipeline did"), check the relevant register and decide **projection vs fork**: a projection derives from the one canonical source; a fork is a second authority that will drift. For search-execution, the register is `governance/execution-surfaces.v1.json` (the `execution-surface` gate fails the build on an unregistered referencer of the canonical `SearchTrace`). This is the discovery step that prevents the representation-drift class (tempdoc 553); prose-tier (~70%) — the gates are the guarantee. Honest limit: a register only covers *declared* concepts, so judgment still applies for genuinely new ones (and per AHA, only unify what shares a reason to change — don't over-DRY scaffolding).

### Fix Root Causes, Not Symptoms <!-- rule:fix-root-causes-not-symptoms -->

**Never resolve a build or test failure by making the failure invisible instead of impossible** — deleting or commenting the failing code, weakening or disabling the test, suppressing the warning, broadening the catch, removing the validation "in the way". (The suppression subset is ratcheted by `check-suppression-ratchet.mjs`; the rest is review-caught.)

**If a test fails after your changes**, the test is probably right and your code is wrong. Investigate its intent; if you genuinely believe it's wrong, explain why and ask the user before modifying it.

### Verify Your Work <!-- rule:verify-your-work -->

After implementing a change, confirm it actually works before moving on. Run compilation and relevant module tests — do not rely on "it should work."

- **At minimum**: `./gradlew.bat build -x test` (compilation) + `./gradlew.bat :modules:<module>:test` for affected modules.
- **After multi-module changes**: `./gradlew.bat test` for the full unit test suite.
- **After frontend changes** (`modules/ui-web`): `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
- **For visual verification**: `jseval ui-shot <step>` (load `/ui-check` skill for full reference).

Do not declare a task complete if the build is broken or tests are failing.

**Use every verification tier available to you, including the LLM.** <!-- rule:use-every-verification-tier --> When verifying AI-facing features (chat surfaces, RAG, conversation shapes), do not stop at `AI_OFFLINE` and declare "verified up to the LLM boundary" — `ai_activate` loads the runtime in seconds; load the model, send a real query, confirm the full response renders. Compile + unit tests verify code; live-stack API tests verify plumbing; only end-to-end with a running model verifies feature correctness. Before declaring a verification tier unavailable, check whether a tool provides it. The compact chat profile (dev default) satisfies this for plumbing/feature-shape checks; quality-sensitive verification (RAG quality, prompt-format, VLM extraction, eval work) needs `ai_activate {chatProfile:"standard"}`. Handle: `ai-offline-isnt-a-wall` (see `docs/reference/contributing/agent-postmortems.md`).

**Audit-driven fixes need a runnable test, not just a passing audit.** <!-- rule:audit-driven-fixes-need-test --> When a subagent audit concludes "X is the only blocker for Y" (or any similar narrow lifecycle claim — "field F is/isn't rebuilt", "state machine accepts/rejects T", "method M is the sole consumer"), the fix is not complete until a regression test exercising Y is green. Static audits are hypotheses; the test is truth. Handle: `audit-without-test` (see `docs/reference/contributing/agent-postmortems.md` §1).

**Critical-analysis pass is required for non-trivial changes.** <!-- rule:critical-analysis-pass --> After implementing a change that modifies control flow, adds/removes behavioral code, or was implemented based on a subagent audit, perform a critical-analysis pass before declaring complete. Re-examine each change for: (a) wrong-gate / wrong-flag mistakes — does the gate actually fire in the target scenario? `grep` the set-site, don't just trust the symbol exists; (b) audit conclusions that weren't independently verified — did you re-read the code the subagent's claim depends on? (c) test precision — does the assertion distinguish "passes for the right reason" from "passes for a wrong reason"? Handles: `wrong-gate` (§2), `audit-without-test` (§1) in `docs/reference/contributing/agent-postmortems.md`.

### Interrogate Results <!-- rule:interrogate-results -->

When an experiment, benchmark, test, or diagnostic produces a result, investigate what caused it before acting on it or reporting it as a finding. The result is data — the cause is what matters.

- **Improvements**: A benchmark shows 2x speedup. Was it your change, or a warm cache, different baseline, or uncontrolled variable? Establish the causal link.
- **Regressions**: A metric dropped 15%. Is the measurement sound? Did conditions change between runs? Don't report a regression without understanding what caused it.
- **Expected results**: A test fails in the way you predicted, or a metric matches your hypothesis. This is the most dangerous case — confirmation feels validating, so there's no instinct to dig deeper. Verify the result happened *for the reason you think*, not a different one.

Failure mode to avoid: treating correlation as causation. The experiment produced the number you expected, so you move on — without establishing that your change was the reason, or that the result means what you think it means.

### Structural Defects Don't Need Repeat Incidents <!-- rule:structural-defects-no-repeat -->

YAGNI applies to speculative abstractions, not to known structural defects. One documented silent bug proves the bug-class — critique a structural tempdoc's substance (wrong diagnosis, wrong mechanism, wrong scope), not its urgency. **Do not re-introduce "wait-for-more-evidence" triggers under different names** ("low historical rate", "wait for Y first", "cheaper intermediate step", "bridging measure") — every deferral framing is the same move: converting a correctness argument into a cost-benefit argument. Don't convert one into the other unless asked, and if the user disregards a tempdoc's own trigger list, do not invent new ones.

### Tempdocs Are Dated History, Not Current Truth <!-- rule:tempdocs-are-dated-history -->

`docs/tempdocs/` is append-only design history, not canonical truth — a tempdoc reflects its writing date, and newer tempdocs and shipped code supersede older ones. Newer tempdocs have higher numbers; always check the highest-numbered tempdoc first to gauge how stale an older one really is. Before trusting a tempdoc's claim as current, check its frontmatter (`status`/`created`/`updated`) and verify against `main` + canonical docs. (`verify-don't-guess`, applied to docs.)

### Tempdoc Is Your Contract <!-- rule:tempdoc-is-your-contract -->

Every item marked for implementation is work the user already judged necessary — you do not get to decide remaining items are "not worth it", "too difficult", or "diminishing returns". Implement every item unless the user explicitly says skip; if an item looks infeasible, explain why and ask rather than silently skipping or summarizing-and-suggesting-closure. A tempdoc is complete when all its items are implemented, not when the impactful ones feel done.

### Stay Focused on Your Assigned Work <!-- rule:stay-focused-on-assigned-work -->

When asked "what should we do next?", consult the active tempdoc for remaining items first. Propose those before suggesting new work.

- **Do not propose switching to a different tempdoc** unless the current one is fully complete.
- **If nothing is left on the current tempdoc**, say so explicitly and let the user decide.
- **Parallel agents share `main`** — untouched-code reformatting causes merge conflicts with other worktrees, so keep diffs scoped to your task.

### Route Out-of-Scope Findings, Don't Log Them <!-- rule:log-pre-existing-issues -->

There is no inbox (tempdoc 872 retired the observations store: 565 notes, none read). A finding outside your task goes where it is acted on, at discovery — a note is not knowledge, it is a deferred fix:

- **Wrong doc/comment, verified one-line fix** → fix it in place; it rides along in your PR.
- **Red or flaky verification command on `main`** → fix it, or quarantine the flaky test in its own runner with a tracked item; main being red is a defect.
- **Platform/process lesson** → a hook if it is a must/never, else `.claude/rules/agent-lessons.md`.
- **Product defect you won't fix now** → the owning tempdoc's open-items section (or its domain register).
- **Do not investigate further** — route and return to your task. Issues caused by your change are yours to fix.

Predictable evasion: "I'll log it in my tempdoc for later triage" — a triage nobody is scheduled to run is the pile again.

### Retire With a Sweep <!-- rule:retire-with-a-sweep -->

A PR abandoning or replacing a feature, tier, or approach must sweep the retiree's fingerprints — grep its names/paths across code, config, gates, baselines, ignore-lists, docs — and delete or label every hit in the same PR. Residue outliving its reason becomes false authority (tempdoc 742: ~350 files, two inert gates). Predictable evasion: "a follow-up PR will clean it up" — 742's corpus is follow-ups that never came.

### Before Appending to CLAUDE.md or `.claude/rules/` <!-- rule:before-appending-to-rules -->

This file is loaded every session; the always-loaded-budget ratchet caps its bytes because bloat makes rules *less* followed (Anthropic: *"Bloated CLAUDE.md files cause Claude to ignore your actual instructions!"*). Before adding a rule, gate it: (1) **Broad applicability** — would a fresh agent on a *different* task need it? Otherwise route it: platform constraint → `agent-lessons.md`; named reference case → `agent-postmortems.md`; domain workflow → a skill; out-of-scope finding → route it (see above). (2) **Already-said** — grep these files first; edit the existing line, don't duplicate. (3) **Enforcement** — a load-bearing must/never belongs in a hook or gate (~100% adherence), not more prose (~70%). Add what passes to the smallest scope that holds it, and name a new must-rule's predictable evasion inline — pre-empting the specific excuse raises adherence more than restating the rule.

### Delegating to Subagents (Agent Tool) <!-- rule:delegating-to-subagents -->

Parent hooks do **not** fire inside a subagent, and task-specific context is inherited by no type — so the brief in the Agent prompt is mandatory and self-contained (plan, acceptance criteria, constraints), and must require primary-source `file:line` evidence for load-bearing claims: subagent findings are a starting point, not a result (`audit-without-test`). Which types inherit `CLAUDE.md` + `.claude/rules/`: `agent-lessons.md` (`subagents-no-inheritance`) — do not restate it here.

No-hooks consequences: never delegate destructive git; no repeat-guard/build-counter/Read-limits — don't delegate long iterative refactor loops; no regen pointers — after a subagent edits `SSOT/catalogs/`, canonical docs, or `build.gradle.kts`, run the relevant regen step yourself; `isolation: "worktree"` base-ref caveats ([claude-code#50850](https://github.com/anthropics/claude-code/issues/50850)) — verify the base (`verify-worktree-base`).

**Model routing (delegation economics).** Binds the ORCHESTRATOR — whatever model runs the main loop.

- **Fits a subagent:** open-ended research, parallel exploration, second-opinion review, batch read-only audits, bounded verifiable implementation chunks. **Risky:** shared state, migrations, `.gitignore`/CI edits, anything that could leave the worktree inconsistent.
- **Delegate when the work fits the list above.** Orchestration — decomposition, briefs, design, judging returned evidence — is the main loop's job. Chunk long refactors into bounded delegations.
- **Delegate mechanical work once it is enumerable** — when diagnosis ends and the rest is a known list, bundle it into a worker brief with self-verifying acceptance criteria. Exception: a chunk clearly below the spawn cost (brief + re-orientation + round-trip exceeds the task) is done directly — estimate first.
- **Set an explicit `model` on every subagent** — unset inherits the parent, silently billing orchestrator-tier. Sonnet is the floor for findings you'll rely on; `opus` where sonnet quality is in doubt; haiku only where wrong output is self-evident. If output misses the bar, redo it with a stronger model — judge the output, not the price tag.
- **Never delegate:** brief-writing, evidence judgment, main-checkout writes, merge/publish, irreversible actions, trivial edits (single-command scale — an edit+test+doc bundle is already delegable).
- **Dev-stack:** lease acquisition/takeover/teardown and contention decisions stay main-loop; stack-driving MAY be delegated inside a window you have leased and actively supervise, with the contention rules inlined in the brief. **Fire-and-forget stack delegation is never allowed** — that is the predictable evasion, not a variant.
- **Precedence:** a harness or system instruction restricting the Agent tool overrides this default for that session. Follow it and say so — never resolve the conflict silently.

Provenance for the above: owner decisions 2026-07-07 / 2026-07-14, pilot P-C 2026-07-17; tempdoc 743. The "default is delegate" economic claim was judged FLAT on its own falsifier and removed 2026-09-07 (tempdoc 948).

## Architecture

| Process | Module | Entry Point |
|---------|--------|-------------|
| **Head** (UI Host) | `modules/ui` | `HeadlessApp.java` |
| **Body** (Worker) | `modules/indexer-worker` | `IndexerWorker.java` |
| **Brain** (Inference) | `modules/app-inference` | Manages `llama-server.exe` |

Full architecture: `docs/explanation/01-system-overview.md`. Key API endpoints: `docs/reference/api-contract-map.md`.

## Quick Commands

| Goal | Command |
|------|---------|
| Compile | `./gradlew.bat build -x test` |
| Whitespace (run first) | `./gradlew.bat spotlessApply` |
| Unit tests | `./gradlew.bat test` |
| Single module | `./gradlew.bat :modules:<module>:test` |
| Frontend typecheck + tests | `cd modules/ui-web && npm run typecheck && npm run test:unit:run` |
| Pipeline profiling (full lifecycle) | `cd scripts/jseval && python -m jseval run --start-backend --clean --pipeline --json` |
| Hot-reload after edit | `reload` (requires `hotReload: true` on dev-stack start) |
| Pre-merge gate | `./gradlew.bat build -x test` from main before merge |

`spotlessCheck` and `pmdAll` (PMD over **every** Java source set, main and test) run in `check`/`build`. `spotlessApply` fixes whitespace, **not** Java formatting.

Public hosted `CI` runs on PRs, pushes to `main`, and manual dispatch ([ADR-0044](docs/decisions/0044-public-hosted-ci-fact-lanes.md)); self-hosted/specialty workflows remain manual. Local-first verification stays primary. For CI triage load `/ci-triage`; for profiling/live stack load `/jseval` and `/dev-stack`.

Pre-merge script checks — run the check whose **subject** you edited. Commands: `node scripts/ci/<name>.mjs` or `node scripts/governance/run.mjs --gate <id> --mode gate`.

| Edited subject | Check(s) |
|---|---|
| `.github/workflows/*.yml` · root README | `check-workflow-triggers` · `check-root-readme` |
| root `CLAUDE.md` Pre-merge table | `check-premerge-table` |
| repo history publication settings (ADR-0045) | `check-repo-history-policy` |
| PR title/body plus managed review record | `preview-squash-message` · `pr-review-record check` |
| `contracts/**` | `--gate wire` |
| `docs/decisions/**` | `--gate adr-coverage` |
| new `<dataDir>/runtime/` file | `check-runtime-manifest-closure` |
| generated files (regen set) | `regen-all --check` |
| any `package-lock.json` | `check-lockfile-completeness` · `regen-all --check --only notices` · `node scripts/dev/generate-dev-mcp-runtime.mjs --check` |
| NSIS hooks · tauri bundle resources · sidecar staging | `check-update-preserves-models` |
| `SSOT/catalogs/**` · analyzers schema · `adapters-lucene/**` | `check-language-agnostic-analysis` |
| `config/pmd/**` | `check-pmd-ruleset-sync` |
| `docs/tempdocs/**` | `check-tempdoc-numbers` · `check-tempdoc-size` |
| `docs/{explanation,reference,how-to,decisions}/**` | `docs-validate` |
| indexing-job lifecycle surfaces | `--gate operation-surface` |
| `CoreSurfaceCatalog.java` / surface `altitude` | `--gate surface-altitude` |
| `governance/logic-seams.v1.json` or a registered seam | `check-logic-seams --mode gate` |
| guard-string register (`execution-surfaces`/`operation-surfaces`) | `--gate register-guard-resolution` |
| `LifecycleReasonCode.java` / `readinessNotice.ts` | `check-readiness-reason-codes` |
| `justsearch-dev-mcp/**` | `check-dev-mcp-doc-sync` |
| `StoreCatalog.java` · store construction sites · `governance/store-{recoverability,corruption-policies}.v1.json` | `check-store-recoverability` |
| **`modules/ui-web/src/**`** (ui-web gate set) | `node scripts/ci/run-ui-web-gates.mjs` — authority: the `ui-web-gates` recipe in `governance/consult-register.v1.json` |
| ui-shot harness · new RAIL surface | `check-ui-step-coverage` |
| `scripts/agent-analytics/**` | `node scripts/agent-analytics/run-all-tests.mjs` |
| `scripts/**` · `packaging/**` js · `*.ps1` | `npm run lint:scripts` · `check-ps1-warning-comments` |

## Common Pitfalls

| Pitfall | Solution |
|---------|----------|
| Windows memory pressure | Use `-PskipWebBuild=true` for backend-only runs |
| Flaky IPC tests | Use state polling (`awaitPort`), not `Thread.sleep()` |
| Local installer build fails on a dev machine | `package-installer-win.ps1` self-diagnoses and refuses to start when Windows Smart App Control is enforcing — SAC blocks unsigned cargo build-scripts, failing the Rust compile with `os error 4551`; `JUSTSEARCH_SKIP_SAC_CHECK=1` only skips the warning, not the actual block. Use CI instead: `gh workflow run build-installer.yml --ref main` (no tag needed, no Release created against a non-tag ref), then `gh run download`. See `docs/how-to/cut-a-release.md` step 1. |

## Skills (load via `/skill-name`)

Available skills are surfaced in your session via system-reminders (names + descriptions); load the matching skill **before** domain work (sources: `.claude/skills/<name>/SKILL.md`). The one rule that injected list does **not** carry: the two **registers** — `/search-quality` and `/inference-runtime` — must be loaded before the work *and updated before you close your tempdoc*. (tempdoc 620 Move 1: the per-skill descriptions were evicted as a fork of the harness-injected list.)

## Parallel Agents

Up to 3-4 agent sessions run concurrently, each in its own **git worktree** under
`.claude/worktrees/<name>` (branch `worktree-<name>`); the main checkout `F:\JustSearch` stays on `main`.

Setup: `EnterWorktree { name: "..." }` in-session, or `claude --worktree <name>` for a new session. Subagent isolation: `isolation: "worktree"` on the Agent tool.

Dev stack: shared (one at a time). Coordinate via user. Merge target: `main`.

Full rules — destructive-command list, worktree lifecycle, merge workflow: `.claude/rules/branch-safety.md`.

## Pointers

- **Full agent guide**: `docs/reference/contributing/agent-guide.md`
- **Docs index**: `docs/llms.txt`
- **Active work**: `docs/tempdocs/`
- **Canonical docs** (must not drift): `docs/explanation/`, `docs/reference/`, `docs/how-to/`, `docs/decisions/`
- **Reference cases by handle**: `docs/reference/contributing/agent-postmortems.md`
- **Contribution recipes**: `docs/reference/contributing/common-workflows.md` (relocated from always-loaded; path-triggerable recipes also push via `governance/consult-register.v1.json`)
