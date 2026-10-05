---
title: Agent-system adoption and the verification-efficiency pilot
status: in-progress
---

# 965 — Agent-system adoption and the verification-efficiency pilot

## Scope and authorization

2026-10-05, owner: "you takeover and do all of that" — release and install agent-system 0.8.1,
merge the verification-efficiency design into agent-system, record the baseline decision, install
agent-system into JustSearch, then run the pilot's challenge (P0) and first phase (P1a).

Design: agent-system `design/research/verification-efficiency/DESIGN.md` (draft 3; three
independent reviews, the last "ready for the JustSearch pilot"). Plan: `IMPLEMENTATION-PLAN.md`
beside it. JustSearch pilot intents are declared outside the 0.8.1 baseline's ten intents
(agent-system fix-programme PLAN, Stage 3).

Worktrees: `.claude/worktrees/agent-system-adoption` (adoption, PR #740, merged as `5ad44a229`);
`.claude/worktrees/test-pilot-p1a` (registration entry and P1a).

## 1. Adoption: old-layer inventory

Every rule, hook and required check of the old layer, with its disposition. "Knowledge" means
agent-system's private project knowledge; "public" means a file in this repository.

| Old item | Disposition |
| --- | --- |
| Hard invariants 1–6 (Lucene port, local API trust boundary, no legacy endpoints, verify via `/api/debug/state` and `/api/health`, Lit frontend, locale-invariant analysis) | Knowledge, verbatim |
| Worktree and git safety (main stays on `main`, worktree per session, no destructive git in main, explicit staging, verify squash merges by content) | Knowledge |
| Force-push refusal | Kept: native `permissions.deny` in `.claude/settings.json` |
| Publication: merge only through `node scripts/dev/run-gh.mjs enqueue <PR>`; no merge without the user's authorization | Knowledge; ADR-0045 amended; the merge-guard hook is retired |
| Tempdoc-only changes ride along, never a standalone PR | Knowledge |
| Shared dev stack and one Gradle build at a time (`quick_health`, leases, no takeover without the user) | Knowledge; `.mcp.json` and `.codex/config.toml` keep `justsearch-dev` |
| Verification commands and AI-facing live verification | Knowledge (test ladder, pilot J1) |
| Root pre-merge table | Public: `docs/reference/contributing/pre-merge-checks.md`; the adr-coverage gate reads it |
| Common pitfalls (memory, SAC installer, pipe exit codes, UTF-8 bulk edits, heredoc corruption, class-scanning timeouts under load) | Knowledge |
| Domain skills (search-quality and inference-runtime registers, dev-stack, jseval, installer, ci-triage, ui-check, module-arch, governance, ssot-catalog, docs-maintenance) | Public: moved unchanged to `docs/agent-knowledge/` |
| Workflow skills (start, plan, design, review-changes, session-closeout, takeover, theorize, payback, goal, derisk, present-status, publish, research, blast-radius, capability-realization, collision-check, review-tempdoc-fit, session-retro, time-calibration) | Retired: agent-system's stage guides and role contracts replace them |
| Hooks (guards, hints, compaction, worktree registration and release, agent-spawn reaper, analytics dispatch) | Retired with `governance/agent-hooks.v1.json`; worktree registration is the manual `worktree-lifecycle.cjs` command |
| Gates on the old layer: hook-integrity, Codex agent parity, always-loaded budget, pre-merge table, skills sync, instruction projection, prompt-surface inventory, skill delivery | Retired; CI steps, regen entries and local-repro entries removed |
| Codex agent roles (`.codex/agents`, `[agents]`) and `.codex/hooks.json` | Retired; agent-system's tier mapping replaces them |
| Agent-specific contributor docs | Marked superseded; `agent-guide.md` stays current for its development reference |

## 2. Adoption: verification

| Check | Result |
| --- | --- |
| `node scripts/ci/regen-all.mjs --check --except notices` | OK, 5 sets |
| `node scripts/ci/regen-all.test.mjs` | Pass (floor 6, two hook generators retired) |
| `node scripts/agent-analytics/run-all-tests.mjs` | 41/41 |
| `node scripts/governance/run-all-tests.mjs` | 31/31 |
| `node scripts/governance/run.mjs --self-test --mode gate` | Exit 0 |
| Hermetic kernel gates (10) | Pass |
| `--gate adr-coverage` | Pass |
| `node scripts/cutover/check-snapshot-includes.test.mjs` | 29/29 |
| `docs-validate`, `verify-canonical-doc-links`, `llmstxt-generate --check` | OK |
| `npm run lint:scripts` | Pass |
| `./gradlew.bat build -x test` | Pass; hosted CI and the merge queue passed (#740) |

## 3. Registration (2026-10-05)

- Core 0.8.1 installed at the private root; JustSearch registered as `justsearch` with
  `--replace-entry --mcp-config .mcp.json`. `AGENTS.md` is the projected entry (sha256
  `b98dd133…`, as recorded by setup); the replaced file is kept in the private project.
  `agentsys check`: pass.
- Project knowledge written: commit policy, invariants, worktrees and git, scarce resources, test
  ladder, required set with triggers, merge-queue dispositions, cache facts, pilot guidance, flake
  list, domain pointers, decisions, pitfalls.
- The commit policy was set under the takeover instruction and still needs the owner's confirmation.
- `governance/consult-register.v1.json`: one row no longer claims `AGENTS.md` points at
  `world-state.mjs`.

Adoption suite (`tools/adoption.py --controls`, agent-system `fix-programme`): every check passes
except these.

| Check | Result | Disposition |
| --- | --- | --- |
| Private directories named to builders | Violation, accepted | Known exposure (the orchestrator needs them; reconcile detects use) |
| Private directories named to reviewers | Violation | Checker false positive. The verifier listed only its checkout and probe folder; it named `F:\agent-private\projects\justsearch\` only when it explained why it declined to read the probe's canary, and that path came from the prompt. The checker excludes the canary path, and elided prefixes of it, but not its parent directory. Fix the checker in agent-system |
| Hooks follow the adoption decision | Unavailable | The project already has `.claude/settings.json`, so no hook canary was planted |
| Cross-vendor challenger | Unavailable | No Codex challenger is adopted |

## 4. Pilot P0 and P1a

Acceptance A1–A8 and items J1–J7 are frozen in the agent-system plan.

| Item | Status |
| --- | --- |
| P0 challenge | Done: agent-system `PILOT-P0-CHALLENGE.md` found the required set incomplete; the knowledge now has the full trigger table and a disposition for each hosted job |
| J1 test ladder, J2 pilot guidance, J4 flake list | Done: in project knowledge |
| J3 build-input hygiene | Done in this change; controls below (A7) |
| J5 jqwik pin to 1.9.3 | Done in this change: catalog and three module pins, lockfiles regenerated, 1.9.3 hashes added, 1.10.1 hashes retired |
| J6 old layer retired | Done (#740) |
| J7 measurement setup | Census done (below); comparable-intent selection: no earlier JustSearch task records exist, so pilot intents are compared with the census only |
| A1 delivery to a real builder and verifier launch | Pending: the first pilot task |
| A8 mapping | Below |

### J3 change

`JvmBaseConventionsPlugin` declares new inputs for every `Test` task:

- The environment variables that switch tests or point them at models: `CI`,
  `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`, `JUSTSEARCH_RERANK_MODEL_PATH`,
  `JUSTSEARCH_CITATION_SCORER_MODEL_PATH`, `JUSTSEARCH_ENABLE_REAL_EMBEDDING`. Each is "" when unset.
- `TestModelAssetsFingerprint`, a value source over every `models/` directory on the
  `ModelDirTestResolver` walk from the task's working directory, plus the override directory.
  Each directory adds a presence marker. Each file adds its path, its size and the content of
  its first and last 64 KB; small files are hashed whole. There are no modification times, so
  checkouts with the same files share cache entries. ONNX Runtime session caches
  (`*.optimized`, `*.opt-meta`) are excluded.

Before this change, Gradle reused a test result recorded under a different `CI` value or a
different set of model files.

Trade-offs:
- One fingerprint covers all test tasks rather than an opt-in per module. The tests of 11 modules
  read models, so an opt-in list would miss the next one. The cost is a model change rerunning
  every test task, and about 110 sampled files read per task.
- A same-size edit confined to the middle of a large file goes unseen. Model files are replaced
  whole.
- An independent review (2026-10-05) found that the first version used modification times, which
  would have stopped CI and new worktrees from reusing cache. It also found generated session
  caches, empty `models/` directories and the working directory being ignored. All four are fixed
  in this version.

### J3 controls (A7), 2026-10-05, final candidate

- Rows 1–12: `:modules:ort-common:test` in this worktree.
- Rows 13–15: `:modules:worker-core:test --tests …BertNerInferenceBoundedTokenizeTest`, which is
  gated on the untracked `models/onnx/ner/model.onnx`. They ran in two temporary detached
  checkouts outside the repository tree. Row 15 made models present with a directory junction to
  the main checkout's `models/`, removed afterwards.
- Gradle's `--info` gave the reason for each row.

| # | Condition | Outcome |
| --- | --- | --- |
| 1 | `CI` unset | Executed |
| 2 | Same again | UP-TO-DATE |
| 3 | `CI=true` | Executed: `testEnv_CI` changed |
| 4 | `CI=false` | Executed: `testEnv_CI` changed |
| 5 | `CI` unset again | FROM-CACHE (row 1) |
| 5b | `JUSTSEARCH_RERANK_MODEL_PATH` set | Executed: that input changed |
| 6 | A model file's modification time changed, content unchanged | FROM-CACHE (row 5's key): timestamps are not in the key |
| 7 | One byte appended to that file | Executed: `testModelAssets` changed |
| 8 | File restored | FROM-CACHE |
| 9 | `model.onnx.optimized` session cache written | UP-TO-DATE |
| 10 | A model file added | Executed: `testModelAssets` changed |
| 11 | Empty `models/` created in the module directory | Executed: `testModelAssets` changed |
| 12 | Removed again | FROM-CACHE |
| 13 | Fresh checkout A, no models on the walk | Executed; the NER test skipped (1 test, 1 skipped) |
| 14 | Fresh checkout B at another path, no models | FROM-CACHE from checkout A: keys match across checkouts |
| 15 | Checkout B, models present | Executed: `testModelAssets` changed; the NER test ran (1 test, 0 skipped) |

Negative control on the first version's base: with `main`'s build logic, rows 13 and 15 in one
checkout left row 15 UP-TO-DATE, still holding the skipped result. That replay is the defect this
change fixes.

A second review round found one new defect: a model file that cannot be read failed the build.
Such a file now adds a random entry, so the tests rerun. The walk is also bounded at 16 levels
against junction loops; the deepest real nesting is 3.

| # | Condition | Outcome |
| --- | --- | --- |
| 16 | `prefix_config.json` held with an exclusive lock | Executed; build passes |
| 17 | Lock released | FROM-CACHE: the normal key is back |
| N16 | Row 16 with the previous fingerprint | Build failed: `Error while evaluating property 'testModelAssets'` (`FileNotFoundException`, file in use) |
| 18 | `ort-common` and `worker-core` tests twice, with configuration-cache problems set to fail | Entry stored, then reused; both UP-TO-DATE |

### Required set on the candidate

- `./gradlew.bat build -x test -PskipWebBuild=true`: pass on the final revision. There is no
  web-build change.
- `./gradlew.bat test -PskipWebBuild=true` on `aac66173f`: BUILD SUCCESSFUL in 14m 21s. 33 test
  tasks executed. One was UP-TO-DATE because it had already run on the same revision during the
  controls. 13,198 tests, 43 skipped, 0 failures. The suite had also passed on the first version.
- The unreadable-file fix came after the suite. Its normal-path value is unchanged: both
  model-reading modules' test tasks were UP-TO-DATE against the suite's results (row 18), so the
  suite result stands.
- Lockfiles: `resolveAndLockAll --write-locks --write-verification-metadata sha256`. Only the jqwik
  lines changed, and only the jqwik 1.9.3 hashes were added.
- Hosted CI: deferred to the PR and merge queue.

### J7 guardrail census, 2026-09-07 to 2026-10-05

Script: agent-system `design/research/verification-efficiency/scripts/census.mjs`; output
`outputs/pilot-census-2026-10-05.json`.

| Measure | Value |
| --- | --- |
| Merge-queue CI runs | 25: 24 success, 1 failure |
| Merge-queue ejections caused by tests | 1 (2026-10-04: Windows-native supervisor conformance, and platform-contracts unit tests) |
| Main push CI runs | 22: 20 success, 2 cancelled, 0 failures |
| Rework links, user-caught misses | Not recorded before the pilot; counted from task records from now on |

### A8 mapping: old verification obligations

| Old obligation (retired `CLAUDE.md` and rules) | Now |
| --- | --- |
| Compile with `./gradlew.bat build -x test` before PR ready | Kept: every candidate |
| `./gradlew.bat test` multi-module; affected-module test | Kept: required set (Java row); module test is the ladder's module rung |
| Frontend typecheck and unit tests; ui-web gate set | Kept: ui-web row, plus lint and web build |
| Pre-merge table: run the check for each edited subject | Kept: `pre-merge-checks.md` row |
| `regen-all --check` for generated files | Kept: generated-outputs row |
| AI-facing behaviour needs compile/unit, live API and a real model query; standard profile for quality | Kept: AI row |
| Full kernel and full suite before done (`subset-isnt-the-suite`) | Kept: required set once per candidate on the final candidate |
| Confirm the gate fires; refute wrong-reason passes; reconcile acceptance items | Kept: verification discipline |
| Class-scanning tests time out under load without failing | Kept: pilot guidance, unexpected-red protocol |
| Gradle `--rerun` replays cache results | Replaced: Gradle 9.6.1 `--rerun` disables reuse for the named task; the knowledge says `--rerun --no-build-cache` and to read the result XML |
| Independent reviewer and measured UX audit at slice closure | Replaced: agent-system verifier and challenger roles |
| Agent and governance checks named per subject in `CLAUDE.md` | Kept: governance row and `pre-merge-checks.md` |

## Remaining work

- This change: PR and merge through the queue (needs the owner's go-ahead).
- First pilot task: A1 delivery check, then collection per the pilot protocol.
- Owner: confirm the commit policy in project knowledge.
- agent-system: fix the adoption checker's reviewer-path false positive.
