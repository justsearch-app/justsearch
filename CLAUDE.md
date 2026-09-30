<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# JustSearch — Claude Code Instructions

`AGENTS.md` is the compact cross-harness contract. This file is the Claude Code
delivery surface for that contract plus Claude-specific workflow details.

Canonical entry points: `docs/llms.txt` (docs index), `docs/tempdocs/` (active work).

<!-- generated:agent-contract:start — source: AGENTS.md; run: node scripts/docs/agent-instructions-sync.mjs -->
## Shared project contract

Shared policy for Codex and Claude Code; generated into `CLAUDE.md`.
Use native tools; specific guidance lives outside the shared block.

Use `docs/llms.txt`. Verify dated tempdoc claims
against canonical docs and code.

## Hard invariants

1. **Application code never touches Lucene.** Index I/O is the index half's
   via a port (ADR-0049).
2. **Preserve the local API trust boundary.** Bind to loopback, enforce the Host
   allowlist, validate MCP Origin, and require the per-boot mutation token where
   ADR-0046 requires it.
3. **Do not resurrect legacy endpoints.** `/api/search` and `/api/settings` are
   removed contracts.
4. **Verify, do not guess.** Use `/api/debug/state` and `/api/health` for
   lifecycle state and `/infra/capabilities` for `host.*` contract versions.
5. **The frontend is Lit, not React.** The active UI is the `shell-v0`
   web-components stack.
6. **Search analysis is locale-invariant.** Do not add per-language analyzers,
   fields, stopwords, spelling dictionaries, or curated synonym authorities.
   The multilingual model stack supplies multilingual behavior.

## Task execution

Continue authorized work after checkpoints, commits, reviews, merges, and status
answers. Stop only at scope completion, user pause/handoff, or when no useful
work can proceed without an external dependency. Session-closeout does not
create a stopping point. Preserve decisions and authorization across compaction;
a pending approval blocks only dependent actions. Distinguish platform
interruptions from voluntary stops. Explanations are not automatically handoffs.

An explicitly authorized migration can supersede a named shipped architecture
rule within its assigned scope. Record the target, superseded rule, and proof in
the governing design; trust boundaries and permissions still apply.

## Start every substantial task

Read this file and relevant canonical docs. Run
`node scripts/agent-analytics/world-state.mjs` before selecting a tempdoc number,
worktree, shared stack, or concurrent lane. Inspect the owning module and nearby
implementations before creating helpers, registries, schemas, or representations.
Adopt an active tempdoc for non-trivial implementation; every acceptance item is
part of the contract. Orientation: Codex `$justsearch-start`, Claude `/start`.
Translate tool-specific commands to the active harness.

## Implementation discipline

- Fix root causes; never hide failure by deleting validation, weakening tests,
  suppressing warnings, or broadening catches. If a failing test's intent seems
  wrong, explain why and ask before changing it.
- Find the source of truth before introducing another representation. Decide
  whether the new form is a projection or an intentional fork.
- Establish why results occurred, including expected-looking results.
- Keep changes scoped and preserve other sessions' work.
- A proven structural failure is actionable without a recurrence threshold.
  Before adding a state machine, persistent marker, writer, or cross-language
  contract, compare simpler ownership and record the trade-off. A real defect
  establishes the need for a fix, not its first proposed mechanism's scope.
- Retire superseded code, configuration, gates, baselines, ignore entries, and
  docs in the same change. Load relevant skills; review both `.agents/skills`
  and `.claude/skills` when shared behavior changes.

## Worktrees and git safety

The main checkout stays on `main`; use a dedicated worktree and branch per
session. Verify directory, branch, and base before editing. Never switch branches,
reset hard, clean, restore the whole tree, or delete/move/restore others' files in
main. Never force-push. Stage explicit paths, not `git add -A`.

Implementation does not authorize publication or merging. Require explicit
per-action authorization before opening/merging a PR or pushing a release;
preserve authorization already given within its stated scope. Check squash-
merged work by content diff, not ancestry. Details:
`docs/reference/contributing/agent-guide.md`, `.claude/rules/branch-safety.md`.

## Delegation

Delegate bounded work with a stable deliverable, assigned files/worktree,
constraints, acceptance checks, and primary-source `file:line` evidence. Scope
growth or new lifecycle/concurrency/ownership ambiguity returns to the parent
before implementation continues. Consolidate feedback; after two substantive
correction rounds, reassess the brief, design, and owner. Never waive defects.
Exploration, review, and separable implementation are suitable. Shared-state changes, migrations, destructive git, merge/release
work, and unsupervised dev-stack ownership are not.

Codex roles: `explorer`/`worker` (Luna/high), `complex_worker` (Sol/medium),
`reviewer` (Sol/high), `companion` (Luna/xhigh; one persistent read-only
context helper per session), and `archivist` (Luna/high; docs-only closeout
writer). Set `fork_turns` to `"none"` or a positive integer; omitted/`"all"`
inherits the parent model and effort and bypasses role pins. Workers return
escalation evidence to the parent. Children inherit the parent turn's sandbox;
role-file `sandbox_mode` is declared intent only. The thread cap is an anomaly
guard, not a concurrency budget. System or session restrictions on delegation
override repository preferences.

## Shared development stack

Only one dev stack and one Gradle build may run across agents. Before starting,
use `justsearch-dev` MCP `quick_health`; never take over a conflicting lease
without explicit user direction. Declare adequate leases and stop owned stacks
when finished. Use the repository sweep to check process identity and ownership;
never directly kill registered helpers.

Codex MCP config is `.codex/config.toml`; Claude uses `.mcp.json`. If the server
is unavailable, fix client configuration instead of bypassing stack ownership.

## Verification

Reconcile every acceptance item with result, tested revision, required environment,
and accessible evidence before claiming completion. Distinguish implementation,
local proof, hosted proof, and authorized deferral. CI wiring is not a successful
run. Name advisory failures and platform gaps; a deferral needs a decision and
destination. Required red or unperformed checks prevent completion.

- Compile: `./gradlew.bat build -x test`
- Multi-module: `./gradlew.bat test`
- Affected module: `./gradlew.bat :modules:<module>:test`
- Frontend, from `modules/ui-web`: `npm run typecheck`, `npm run test:unit:run`
- Agent/governance: subject-specific Node checks in `CLAUDE.md` and
  `docs/reference/contributing/common-workflows.md`

AI-facing behavior needs compile/unit, live API, and a real model query;
`AI_OFFLINE` is insufficient when model activation is available. Compact profile
proves plumbing; quality verification requires standard. Audit-driven fixes need
runnable regressions. For non-trivial control-flow/governance changes, confirm
the gate fires, independently re-read evidence, and refute wrong-reason passes.

Run focused checks during implementation and integrated checks at coherent
boundaries; start required hosted/platform checks early when authorized. Reuse
results only while revision and assumptions still apply. Preserve suite output
before targeted reruns overwrite it. Keep summaries/commands in Git and bulky
artifacts at an accessible location with retention limits; hashes are not access.

## Prompt, tooling, and documentation ownership

`AGENTS.md` is shared policy; `CLAUDE.md` contains its generated projection.
Each harness's rules/skills own scoped guidance. `governance/agent-hooks.v1.json`
owns hook policy; `.codex/hooks.json` is generated (review/trust with `/hooks`).
Never commit secrets to shared config. MCP schemas live in code/contract tests.
Follow blocking hooks' remedies; `JUSTSEARCH_DISABLE_HOOKS=1` is recovery only.
Instruction loading, conversation inheritance, and hook execution are separate;
verify delivery instead of trusting a subagent's self-report.

Canonical docs must match shipped behavior. Update them with governed changes;
regenerate derived docs and skills. Keep current decisions in owning sections,
with a short dated index linking rationale. Handoffs name current state and next
steps without requiring private transcripts or replay of amendments.

References under `docs/reference/contributing/`: `agent-workflow.md` (execution
and evidence), `agent-prompt-surface-governance.md` (ownership), and
`common-workflows.md` (regeneration).
<!-- generated:agent-contract:end -->

## Claude delegation adapter

Project instructions, history, and hook execution have separate delivery rules;
see `.claude/rules/agent-lessons.md` and the canonical agent-workflow reference.
The parent owns scope decisions, acceptance judgment, and repository-wide
regeneration after child edits. Verify isolated worktree bases before editing.

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

| Process | Module | Entry point |
|---|---|---|
| **Engine** — API + index, one JVM | `modules/ui`, `modules/indexer-worker` | `HeadlessApp.java`; index half bound by `EngineRoot` |
| **Brain** (inference) | `modules/app-inference` | manages `llama-server.exe` |

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
| Pre-merge gate | `./gradlew.bat build -x test` from the candidate worktree |

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
