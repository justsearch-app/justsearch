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

`JvmBaseConventionsPlugin` declares three new inputs for every `Test` task: `CI`;
`JUSTSEARCH_EMBED_ONNX_MODEL_PATH`; and `TestModelAssetsFingerprint`, a value source that records
path, size and modification time of every file under each `models/` directory on the
`ModelDirTestResolver` walk, plus the override directory. Before this change Gradle reused a test
result recorded under a different `CI` value or a different set of model files.

Trade-off: one fingerprint for all test tasks, not one per model-dependent family. It walks 78
small stat calls and needs no per-test registry, but a model change reruns every test task, not
only the asset-gated ones. Models change rarely, so the simpler owner was kept.

### J3 controls (A7), 2026-10-05, on this branch

Rows 1–8: `:modules:ort-common:test` in this worktree. Rows 9–10:
`:modules:worker-core:test --tests …BertNerInferenceBoundedTokenizeTest` (gated on the untracked
`models/onnx/ner/model.onnx`), in a temporary detached checkout outside the repository tree. There,
models were made present with a directory junction to the main checkout's `models/`, then removed.
Gradle's `--info` gave the reason for each row.

| # | Condition | Outcome |
| --- | --- | --- |
| 1 | `CI` unset | Executed (no history) |
| 2 | Same again | UP-TO-DATE |
| 3 | `CI=true` | Executed: `testEnvCi` changed |
| 4 | `CI=false` | Executed: `testEnvCi` changed |
| 5 | `CI` unset again | FROM-CACHE (restored cache of row 1) |
| 6 | A model file's modification time changed | Executed: `testModelAssets` changed |
| 7 | A model file added | Executed: `testModelAssets` changed |
| 8 | Files restored | FROM-CACHE |
| 9 | Fresh checkout, no models on the walk | Executed; the NER test skipped (1 test, 1 skipped) |
| 10 | Same checkout, models present | Executed: `testModelAssets` changed; the NER test ran (1 test, 0 skipped) |
| N9–N10 | Rows 9–10 with `main`'s build logic | Row N10 was UP-TO-DATE and kept the skipped result: the defect this change fixes |

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

- This change: full Java suite on the candidate; PR; merge through the queue.
- First pilot task: A1 delivery check, then collection per the pilot protocol.
- Owner: confirm the commit policy in project knowledge.
- agent-system: fix the adoption checker's reviewer-path false positive.
