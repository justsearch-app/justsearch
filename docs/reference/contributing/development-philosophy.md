---
title: Development Philosophy
type: reference
status: stable
description: "Issue tracking tiers, observation workflow, and documentation update rules."
---

# Development Philosophy

This project follows **organic development**: issues and rough edges are captured as they're noticed, not as separate investigation tasks.

The observations conditions store (`docs/observations.md` / `docs/observations.d/`) was **retired** (tempdoc 872) in favor of **route, don't log**: an out-of-scope finding is routed to its destination at the moment it's discovered, not appended to a pile for later triage. During any task, if you notice a behavioral issue — something that affects users, causes bugs, or creates friction — route it immediately: (a) a wrong doc/comment with a verified one-line fix → fix it in place, ride-along in the same PR; (b) a red/flaky verification command on main → fix it, or quarantine the flaky test in its own runner with a tracked item (tempdoc 930 retired the expected-state pin mechanism: a pin made red-on-main cheaper to live with than to fix); (c) a platform/process lesson → a hook (must/never) or `.claude/rules/agent-lessons.md`; (d) a product defect you won't fix now → the owning tempdoc's open-items section, or a domain register for its domain. Don't stop to investigate unless explicitly asked — route and continue.

## Two tiers of issue tracking

| Tier | Location | Friction | Lifetime | When to use |
|------|----------|----------|----------|-------------|
| Routed finding | Owning tempdoc's open-items section, a fix in place, or `agent-lessons.md`, per the routing rule above | Low (route at discovery, no separate triage pass) | Until the destination item is resolved | Notice something mid-task |
| Domain registers | `docs/reference/search-quality-register.md`, `docs/reference/inference-runtime-register.md` | High (ID, evidence, verification date) | Until the finding is superseded by shipped work | Empirical findings and standing trade-offs in a register's domain |

The standing per-domain issue registers under `docs/reference/issues/` were **retired** (tempdoc 821 §7 D5, 2026-08-12) — most had not been stamped since 2026-02/04 and real tracking had already migrated to the (then-live, now also retired) observations store, the two domain registers, and tempdocs. The deleted files' content is recoverable from git history, as is the observations store's (last full snapshot: commit 7b85a5a6).

Architectural trade-offs and conscious design tensions belong in an ADR (`docs/decisions/`) when the "why not X?" answer is worth preserving, or as a register entry with its revisit trigger. A trade-off that is neither belongs as a parked item in its owning tempdoc.

**Tempdocs** (`docs/tempdocs/`) are for active implementation work — investigation logs, planning docs, and session-scoped notes. They are not part of the issue tracking system.

## Issue lifecycle rules

- A fixed finding is **deleted** from its register, or closed out in its owning tempdoc, not marked closed in a separate list — the commit that fixed it is the permanent record.
- An item evaluated and intentionally closed (won't-fix, deferred, accepted) needs a durable home for its rationale: an ADR, a register entry with a revisit trigger, or a parked item in its owning tempdoc. Do not leave closed items sitting in an active list.
- Registers and tempdoc open-items sections hold only items someone could act on. If it is not something to fix or revisit, it belongs in an ADR or nowhere.

## Softness portfolio

Seams where enforcement is **deliberately left soft** — each with its reason and the condition
under which the choice is revisited. This section exists so deliberate fail-open choices stay
visible decisions instead of silent drift; it is also the routing destination for
posture-adjudication findings (tempdoc 680's routing lanes). Add a row when a softening choice is
made deliberately; remove it when the revisit condition fires and the seam is hardened or the
softness re-affirmed.

| Seam | Deliberate posture | Reason | Revisit when |
|------|--------------------|--------|--------------|
| PIT mutation ratchet (`test-efficacy` gate) | `workflow_dispatch`-only, not per-PR CI | Self-hosted-runner availability (ADR-0026) | A hosted lane can carry the PIT run within budget |
| Governance kernel gates in public CI | Not in the required-check set | Runner-availability constraint (ADR-0026 / ADR-0044); local-first verification remains primary | A hosted kernel lane proves stable — then add to required checks |
| FE runtime schema validation (`parseWireContract` / `validateWithFallback`) | Degrade-and-log in production rather than crash | Crashing the user's UI on wire drift punishes the user, not the developer | A dev/CI-throws split lands, or a real-use drift incident shows logging alone is insufficient |

## Upgrade-safe defaults

New config keys that change search or ranking behavior must register their programmatic default via `putDefault(key, value)` in the relevant `contributeYaml*()` method of `ResolvedConfigBuilder`. This ensures the effective value appears in:
- Startup logs (`logResolutions()` at INFO level)
- `/api/debug/effective-config` as `source: "default", ordinal: 100`

Without this, operators see "unset" when the runtime is silently using the default. The `putDefault` ordinal (100) is the lowest priority, so any explicit YAML, env var, or system property setting wins.

## Doc update rules

- When you change behavior/contract: update `docs/explanation/` and/or `docs/reference/`.
- When you record an architectural decision with alternatives: create an ADR in `docs/decisions/`.
- After adding/changing canonical docs: run `node scripts/docs/llmstxt-generate.mjs` to regenerate the index.
- When the Gradle module graph changes (`settings.gradle.kts` or `modules/**/build.gradle.kts`): run `node scripts/architecture/module-deps.mjs --update-canonical` and verify with `--check-canonical`.
- When you write notes/ideas: use `docs/tempdocs/` (noncanonical); route out-of-scope findings through [agent-workflow](agent-workflow.md#route-findings-at-discovery) (fix in place / rules / owning tempdoc) — there is no inbox helper.
- Full guide (frontmatter, CI checks, doc types): `docs/reference/contributing/writing-docs-for-ai.md`
