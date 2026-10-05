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

Worktree: `.claude/worktrees/agent-system-adoption`, branch `worktree-agent-system-adoption`.

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
| `./gradlew.bat build -x test` | Pending |

## 3. Pilot P0 and P1a

Acceptance A1–A8 and items J1–J7 are frozen in the agent-system plan. Status:

| Item | Status |
| --- | --- |
| P0 challenge | Pending: after registration |
| J1 test ladder, J2 pilot guidance, J4 flake list, J7 measurement setup | Pending: written into project knowledge at registration |
| J3 build-input hygiene | Pending: follow-up change with controls |
| J5 jqwik pin to 1.9.3 | Pending: follow-up change |
| J6 old layer retired | Done in this change |

## Remaining work

- Gradle pre-merge build; PR; merge through the queue.
- Install agent-system 0.8.1 core; register JustSearch with `--replace-entry --mcp-config .mcp.json`; commit the projected `AGENTS.md`; evaluation and adoption suite.
- P0, then J1–J5 and J7 as above.
