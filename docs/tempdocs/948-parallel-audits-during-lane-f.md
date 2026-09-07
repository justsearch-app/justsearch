---
title: "948 — Parallel audits during lane F: delegation-economics falsifier, 921 thirty-merge audit"
type: tempdocs
status: "DONE (2026-09-07) — falsifier judged FLAT and applied; 921 audit run (retirement not met, one owner decision routed); pre-merge table row added"
created: 2026-09-07
updated: 2026-09-07
lane: agent tooling / publication process (parallel to lane F, outside its blast radius)
related:
  - 743-orchestrator-economics-and-model-routing   # the falsifier this tempdoc executes
  - 921-separate-pr-review-record-from-public-squash-record   # the 30-merge audit this tempdoc runs
  - 918-wave2-kernel-residue-repin-enforcement-and-ci-gate-wiring   # §G residue: the pre-merge table row
  - 938-release-consolidation-before-architecture-change   # the hygiene list the survey started from
---

# 948 — Parallel audits during lane F

## Why this tempdoc exists

Lane F (the Engine JVM merge, `docs/design/lane-f-engine-jvm/`) is running on its own
branch. Its design section 17.1 assumes all other development is halted, and section 16
pairs the branch against `main` after PR 0, so anything landing on `main` in parallel must
stay out of lane F's owned files, must not move anything stage E measures, and must merge
cleanly under the lane's weekly `git merge` from `main`.

A survey of tempdocs 917 to 941 on 2026-09-07 produced a candidate list of parallel work.
Verified against `main` at `11f855a83`, about half was stale or already done (README privacy
scoping, threat-model OTLP policy, the `ts-any` gate replaced by ESLint, `adr-coverage`
changesets, overlay dedup, the first graded Codex round, MAINTAINING.md placeholders). Two
items were genuinely open and time-critical, both analytics-only and outside lane F:

1. The delegation-economics falsifier in `CLAUDE.md`, due 2026-09-14.
2. Tempdoc 921's thirty-merge post-land audit, gated on 30 merges after PR #632 and now
   sitting at 62.

This tempdoc records both, plus one governance row the same survey found missing.

## 1. Delegation-economics falsifier (CLAUDE.md, tempdoc 743)

### The rule

`CLAUDE.md` carried, under "Model routing (delegation economics)": *"Falsifier (window opened
2026-07-14, judge by ~2026-09-14, instrument `scripts/agent-analytics/baseline-economics.mjs`):
cost-per-shipped-merge should improve without rework rising — flat → delete this paragraph;
rework up → raise the floor."* Tempdoc 743 defines the metric set (cost per shipped merge,
orchestrator/worker split, rework rate) and makes rework a hard constraint: *"rework/escape
rate must not worsen"* (743 line 150).

### Method

Commands, all read-only:

```
node scripts/agent-analytics/baseline-economics.mjs --since 2026-06-18 --until 2026-07-14 --md   # BEFORE
node scripts/agent-analytics/baseline-economics.mjs --since 2026-07-14 --until 2026-09-07 --md   # AFTER (captured ~15:50Z)
node scripts/agent-analytics/baseline-economics.mjs --md                                         # DEFAULT
gh pr list --state merged --limit 1000 --json number,title,mergedAt                              # 643 PRs
```

Executed by an opus subagent; the two load-bearing numbers (the empty BEFORE window and the
743 recorded baseline) were re-run and re-read by the orchestrator.

### Blocking data finding

The BEFORE window returns **0 sessions**. DEFAULT equals AFTER (83 sessions both), so no
transcript survives with a start before 2026-07-14. Claude Code rotated them away and there is
no fallback cost source (`tmp/agent-telemetry/otlp/` has no ledger). The falsifier therefore
cannot be executed as a before/after measurement with the named instrument. The only BEFORE
numbers are the ones tempdoc 743 recorded on 2026-07-16.

### Cost per shipped merge

| Pairing | BEFORE (743, recorded) | +3.6% restatement (856) | AFTER (measured) | Delta |
|---|---|---|---|---|
| Whole-window attributed | $106.25 (743:378) | $110.07 | $113.93 | +3.5% |
| Complete weeks only | $63.07 = $11,290/179 (743:357) | $65.34 | $105.96 = $19,497/184 (W33 to W36) | +62% |
| gh-denominator variant | $74.28 = $11,290/152 | — | $72.75 = $19,497/268 | −2% |
| Orchestrator / worker tokens | 84.0% / 16.0% | — | 21.8% / 78.2% | −62 pp orchestrator |

Weekly cost/merge in the AFTER window spans $73 to $275 (3.8x), wider than every delta above
except the complete-weeks one. No pairing shows improvement beyond restatement noise. The
AFTER window is open-ended and drifts with every merge (a re-run the same evening read
$114.56, +4.1%); the direction does not change.

### Rework

Merged-PR title classes bucketed on `mergedAt` around 2026-07-14. Regex
`^fix[(:]|revert|round-N fixes|repair|re-pin|^hotfix`.

| Window | n | rework (broad) | `fix(` share | `fix/(feat+fix)` |
|---|---|---|---|---|
| BEFORE 06-18 to 07-14 | 155 | 21.3% | 20.0% | 44.3% |
| AFTER 07-14 to 09-07 | 488 | 28.5% | 28.1% | 45.7% |
| AFTER-a 07-14 to 08-09 | 213 | 22.5% | 22.1% | 36.4% |
| AFTER-b 08-09 to 09-07 | 275 | 33.1% | 32.7% | 52.6% |

The raw `fix(` rise is largely composition (BEFORE was docs-heavy at 33.5% docs PRs, AFTER is
feat-heavy at 13.9%). Controlled, rework is flat in aggregate (44.3% to 45.7%) with a rising
trend inside the AFTER window that coincides with the 0.3.0 release-hardening rounds
(tempdoc 941). Throughput rose 46% (6.0 to 8.7 PRs/day).

### Confounders

- The BEFORE cost is a recorded value under an older parser and pre-856 ledger filter, not a
  reproducible measurement.
- Three structural breaks sit inside the comparison, quoted from the instrument's own caveats:
  2026-07-14 friction hooks, 2026-07-15 model-routing change (the policy itself, one day after
  the window opened), 2026-09-05 hook retirement.
- Attribution collapsed from 94% to 57% of merge rows; the ledger covers 62% of real merges.
- The scope filter is inert (0 of 31 ids match), so the session population is unclassified.

### Verdict and action

**FLAT.** The policy was adopted hard (the orchestrator share of tokens fell from 84% to 22%),
but the payoff the falsifier demanded is absent: +3.5% on the closest like-for-like pairing,
inside the ±3.6% restatement noise; +62% and −2% on the other two; weekly noise larger than
all three. Rework is flat in aggregate and rising late in the window, enough to bar an
"improved without rework rising" pass and not enough, on its own, to trigger "raise the floor".

Per the rule's own text, applied 2026-09-07 in `CLAUDE.md`:

- The "Contested, and due for judgment" bullet is deleted.
- "Default is delegate ... prefer even inefficient delegation ... when unsure, delegate anyway"
  is replaced by "Delegate when the work fits the list above"; the "when unsure, still
  delegate" tail on the mechanical-work bullet is dropped.
- Kept unchanged: the fit/risky list, explicit `model` on every subagent with the sonnet floor
  (hook-enforced by `subagent-model-guard`), never-delegate list, dev-stack rules, precedence.
- The provenance line records the judgment and points here. `AGENTS.md` already read
  "Delegate only bounded work" and needed no change.
- Reading of "delete this paragraph": applied to the economic claim, not to the whole
  block, because the fit/risky list, the explicit-model rule and the never-delegate list are
  scope and safety rules that the falsifier never measured and a hook enforces one of them.
  A reviewer could read the late-window rework rise (52.6%) as "rework up, raise the floor";
  declined here on composition grounds and because the 0.3.0 hardening rounds sit inside it.

Honest limit the owner should weigh: the evidence chain the falsifier named is broken at the
BEFORE edge. The rule was applied because the alternative, "inconclusive so keep it", is the
wait-for-more-evidence deferral `CLAUDE.md` forbids, and because no reading showed
improvement. What would make a future re-open decidable rather than inferable: transcript
archival (the store rotates and nothing snapshots it), merge-ledger coverage above 62%, and a
working scope filter.

## 2. Tempdoc 921 thirty-merge post-land audit

### Definition

921 lines 1559 to 1562: *"After activation lands, audit the next 30 squash merges for
public-body hygiene, managed-comment freshness, duplicate markers, and semantic landed
projection."* Thresholds are 921 §8.4 (lines 616 to 627). 921 §20 (lines 1456 to 1460)
supersedes byte equality with semantic equality for the landed projection, and §11 (lines 705
to 713) gives the retirement condition.

### Population and method

62 PRs numbered above #632, merged 2026-09-04 to 2026-09-07, all by the owner. Per-PR
validation ran the repo's own validator read-only with full coverage:

```
gh pr list --state merged --limit 200 --json number,title,body,mergedAt,mergeCommit,comments --search "merged:>=2026-09-04"
node scripts/ci/pr-review-record.mjs check --pr <N> --json --repo justsearch-app/justsearch   # 62/62
node scripts/ci/preview-squash-message.mjs --pr 707 --json   # titleSource PR_TITLE, bodySource PR_BODY
git log -1 --format='%s|%b|%P' <mergeCommit>                 # landed projection
```

Run by an opus subagent; the orchestrator re-verified the escape claim below on #683, #687
and #688 directly.

### Results

| Check | Pass | Fail | Failing PRs |
|---|---|---|---|
| Public-body hygiene (no `buildPublicSquashRecord` error) | 33 | 29 | breakdown below |
| Managed-comment freshness | 42 of 42 evaluable | 0 | none |
| Duplicate markers | 62 | 0 | none (max one managed comment) |
| Managed record present | 42 | 20 | 633 to 636, 639, 640, 643, 645 to 647, 657 to 660, 664, 683 to 687 |
| Semantic landed projection | 62 | 0 | none |

Hygiene errors: `public-provider-banner` 29 (all the linked footer form, the detector gap
tempdoc 933 §0 names; every one crossed into the permanent commit on `main`),
`public-review-residue` 18, `subject-too-long` 12 (worst 150 chars), `public-body-too-large` 9
(max 3218). Freshness is only evaluable where a record exists, since the validator
short-circuits on cardinality other than one.

### §8.4 threshold verdicts

| Threshold | Measured | Verdict |
|---|---|---|
| 100% single-parent publications | 62 of 62 | PASS |
| 0 review-round, stack or provider-banner blocks in projected bodies | 29 provider banners | FAIL |
| 95% of subjects at or under 72 chars | 81% | FAIL |
| body median at or under 1200 and 100% under 2000 | median 1127; 9 over 2000 | PARTIAL FAIL |
| 95% post-merge projection matches | semantic 100%, byte 0% | PASS under §20 |
| Session-Id coverage by self-declared actor class | 42 declared `agent`, all with a valid id; 20 undeclared, 6 with an id; 77% overall | REPORTED |
| at most one legitimate false block per 30 PRs | not measurable from merged PRs | NOT MEASURED |

### What the window actually shows

Clean rate by sub-window (validator exit 0):

| Window | Clean |
|---|---|
| #633 to #668, the literal "next 30" | 2 of 30 |
| #633 to #672, before #675 | 3 of 33 |
| #675 to #707, after #675 | 24 of 29 |
| #688 to #707 | 16 of 16 |

PR #675 (tempdoc 933, merged 2026-09-05 10:33Z) is the inflection: it shipped the linked-footer
detector and the fail-closed `run-gh.mjs enqueue` gateway. The literal §8.4 verdict measures
the pre-enforcement regime. The honest headline: 933 fixed it, with a five-PR tail.

**Retirement condition (§11): not met.** Commit-safe bodies reached 53% in the first
30-PR window and 88% in the second, both under the 95% bar, and no false blocks were
observed against 35 real defects landed. The separation contract stays.

**Grammar revision (§8.4 lines 626 to 627): not triggered.** Zero false blocks in evidence.

### Findings for follow-up

1. **PRs #683 to #687 escaped a landed gate.** All five carry the provider banner into `main`
   after #675 made the detector catch it (verified on #683 `87a6e041b` and #687 `b96cd9998`;
   #688 `9772c4d2c` is clean). Enforcement lives only at the agent Bash boundary
   (`publication-merge-guard.mjs` redirecting `gh pr merge` to the enqueue gateway); a web-UI
   merge, a non-hooked harness, or `JUSTSEARCH_DISABLE_HOOKS=1` bypasses it. CI runs only
   the validators' unit tests, never the live per-PR check. 921 §21.2 deliberately deferred a
   required check pending "a proven trusted-base event path". This audit is the live evidence
   921 asked for before reconsidering that deferral. Routed to the owner as a decision, not
   fixed here: a required check touches `.github/workflows` and branch protection.
2. **29 permanent commits carry the banner and 14 lack a Session-Id.** 921 line 651 forbids
   rewriting history. Recorded defect, not a repair task.
3. **20 merged PRs have no managed review record.** Caught going forward by the gateway's
   cardinality check.
4. **Subject length is the weakest surviving axis.** All 12 over-length subjects predate #688.

## 3. Pre-merge table: `store-corruption-policies` trigger (918 §G residue)

`scripts/ci/check-store-recoverability.mjs` reads `governance/store-corruption-policies.v1.json`
(line 20) and holds every row's `corruptionPolicy` to its vocabulary, but the `CLAUDE.md`
pre-merge table named only `StoreCatalog.java` and store construction sites. The row now also
names both `governance/store-{recoverability,corruption-policies}.v1.json`.
`check-premerge-table` and `check-always-loaded-budget` pass.
