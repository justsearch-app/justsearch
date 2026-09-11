---
title: "Token efficiency is a trend, not a snapshot — and the data that proves it is on a 30-day delete timer"
type: tempdocs
status: "IMPLEMENTED 2026-09-02 on branch worktree-agent-a5cf92a1ad3d218b1 (4 commits, 66/66 suite green, acceptance gate reproduced) — NOT merged, awaiting owner go-ahead. Analysis §1 complete; one headline retracted after a size control (§1.2b); rework half measured and confound-tested (§1.3b). §4 criticisms and §5.3 are arguments, not decisions."
created: 2026-09-02
updated: 2026-09-02
lane: agent-analytics / token efficiency
model: opus (analysis + design) -> sonnet (implementation, deliberately -- see §6.0)
parent: 886-agent-token-efficiency-review
related:
  - 886-agent-token-efficiency-review   # the snapshot this adds a time axis to
  - 841-prompt-cache-efficiency          # cache-write taxonomy
  - 743-*                                # delegate-by-default falsifier, due 2026-09-14
  - 858-*                                # PHI retirement; merge-ledger restatement
---

# 908 — Token efficiency trend + the retention inversion

**Thesis, three parts.**

1. **The composition of spend shifted decisively, the existing stack structurally cannot see
   it, and the data needed to judge whether the shift was worth it does not exist.** Every reader
   under `scripts/agent-analytics/` is a single-window snapshot. Adding a time axis to the *same*
   ledger shows subagent share of spend rising **36% -> 74%** over four weeks, concentrated
   entirely in the `>=120-call` spawn tail (**58% -> 78%** of subagent cost), while the main loop
   got *cheaper*. Internal efficiency (`ctx/out`, `$/M-output`) worsened alongside it. But
   delivery-normalised cost — the number that would say whether the shift paid — swings ~2x
   week-to-week with no trend, and five weeks is all the corpus has (§1.2b, §4.3). The
   `agent-token-efficiency-review` tempdoc measured 2026-08-01..09-02 as one block and reported
   the average of a moving composition as if it were a level.

2. **The shift is traceable to the subagent tail, not the main loop** — and the evidence for
   every claim here is being deleted on a rolling 30-day timer while 972 MB of never-pruned OTLP
   traces that no reader consumes sits next to it. The retention policy is inverted with respect
   to analytical value.

3. **Rework roughly tripled over the same period, and it is not a work-type artifact.**
   Proximate rework (a `fix:` PR touching code another PR changed in the prior 14 days) went
   15.4% -> 40.8% of PRs (z = 4.63). Restricted to backend-majority PRs at identical n = 114 per
   period — where the mandated post-implementation UX audit does not apply — 15% -> 41%
   (z = 4.42). This is the `delegate-by-default` falsifier's rework half, which `858 §8` recorded
   as unmeasured; it is a `git log --numstat` join (§1.3b). Its remedy, however, is not the one
   the falsifier offers (§4.4b).

---

## 1. The measurement (2026-09-02)

Corpus: `listCalls({harnesses:['claude-code']})`, 99,375 calls. Claude Code rotates transcripts
at ~30 days (`cleanupPeriodDays` unset -> default 30; oldest surviving transcript mtime
**2026-08-04**), so W31 is truncated, W36 is 3 days and contains work-in-flight whose merges have
not landed. **Complete, trustworthy weeks are W33-W35.** Every table below states that caveat
because without it the trend lies at both edges.

### 1.1 Leading indicators (denominator-free)

| Week | calls | cost | ctx/out | $/M-out | main p50 ctx | sub p50 ctx | sub cost share |
|---|---|---|---|---|---|---|---|
| W32 | 13,618 | $3,063 | 836 | $766 | 426k | 181k | 36% |
| W33 | 21,885 | $4,149 | 887 | $746 | 396k | 158k | 52% |
| W34 | 26,044 | $4,524 | 997 | $683 | 502k | 180k | 58% |
| W35 | 23,365 | $4,243 | **1,393** | **$991** | 331k | **201k** | **74%** |
| W36* | 14,472 | $2,974 | 1,098 | $872 | 332k | 187k | 62% |

### 1.2 Lagging indicator (delivery denominator)

Using `git log origin/main --first-parent` — under ADR-0045 every PR squashes to exactly one
first-parent commit, so this is a *count of shipped PRs* that needs no session attribution at all.

| Week | cost | PRs landed | **$/landed PR** |
|---|---|---|---|
| W32 | $3,063 | 45 | $68 |
| W33 | $4,149 | 64 | **$65** |
| W34 | $4,524 | 57 | $79 |
| W35 | $4,243 | 49 | **$87** |
| W36* | $2,974 | 23 | $129 (partial, in-flight) |

### 1.2b The size control — and what it kills

**Added after §1.2 was first written, and it retracts part of it.** PR *count* says nothing about
PR *size*. Joining `git log --first-parent --numstat` per week, splitting churn into code
(`modules/**/src/`, `scripts/`, `gates/`, `contracts/`), docs and generated noise:

| Week | PRs | med churn/PR | med files/PR | code churn | **$/1k code lines** | $/PR |
|---|---|---|---|---|---|---|
| W32 | 45 | 882 | 14 | 50,111 | $61.1 | $68 |
| W33 | 64 | 364 | 6 | 54,993 | **$75.4** | $65 |
| W34 | 57 | 1,266 | 13 | 96,220 | **$47.0** | $79 |
| W35 | 49 | 1,031 | 10 | 48,863 | $86.8 | $87 |
| W36* | 23 | 3,001 | 52 | 49,106 | $60.6 | $129 |

**The two denominators disagree, and they disagree hardest on the weeks the trend claim rested
on.** W33 is the *cheapest* week per PR ($65) and the *second most expensive* per code line
($75.4) — its PRs were a third the size of the neighbours' (median 364 lines, 6 files). W34 is
the opposite: expensive per PR ($79), cheapest per code line ($47.0).

So `$/1k code lines` reads 61 → 75 → 47 → 87 → 61: a ~2x week-to-week swing with **no trend at
all**. At ~50 PRs/week over 5 weeks, the noise band is wider than any effect this data could
resolve.

**Retraction.** §1.2's "+34% cost per landed PR across three clean weeks" is two points selected
from five, and it does not survive a size control. I should not have written it as a finding
without running the control first, and the fact that it agreed with the ratio metrics is exactly
the confirmation-shaped trap CLAUDE.md's `interrogate-results` names: the number I expected
appeared, so I stopped digging.

### 1.2c What actually survives

| Claim | Status |
|---|---|
| Subagent share of spend rose 36% → 52% → 58% → 74% (W36 62%) | **Holds** — monotonic over four weeks, structural |
| `>=120-call` spawn cost share rose 58% → 66% → 76% → 78% | **Holds** — monotonic then plateau |
| `ctx/out` and `$/M-output` degraded (836→1,393 / $766→$991, W32→W35) | **Holds W32-W35**, but W36 falls back to 1,098 / $872 — a 4-point rise, not an established slope |
| Main loop improved (p50 502k → 331k, spend $1,995 → $1,108) | **Holds** |
| Median spawn is flat while `$/spawn` rose | **Holds** — the regression is tail-only |
| Cost per unit of delivered work is rising | **RETRACTED** — see §1.2b |

The honest summary is therefore narrower and, for the purpose of this tempdoc, *more* damning:
**the composition of spend shifted decisively into a long-subagent tail, and this corpus cannot
tell you whether that was worth it.** Five weeks at this volume is not enough data, and the
sixth week is already gone (§4.3).

### 1.3 The cause: the spawn tail, not the median, and not the main loop

The main loop **improved** over the same period: p50 context 502k -> 331k, spend $1,995 -> $1,108.
Delegation absorbed that gain and then exceeded it.

| Week | spawns | med calls/spawn | med peak ctx | **$/spawn** | >=120-call spawns | their cost share |
|---|---|---|---|---|---|---|
| W32 | 131 | 68 | 194k | $8.5 | 32 | 58% |
| W33 | 257 | 48 | 159k | $8.5 | 40 | 66% |
| W34 | 253 | 51 | 176k | $10.4 | 62 | 76% |
| W35 | 221 | 53 | 174k | **$14.5** | 47 | **78%** |
| W36* | 157 | 39 | 141k | $10.9 | 23 | 76% |

**The median spawn is flat** (~50 calls, ~175k peak context) while `$/spawn` rose 71%. The entire
regression lives in the tail: the `>=120-call` bucket's share of subagent cost climbed 58% -> 78%.
`sub|claude-opus-5` alone is **$10,499 of $19,814** total spend (72,613 calls, 10.5M output).

The top spawns in the window are 639-1,365-call, 700-920k-peak-context `general-purpose`/opus
workers costing $246-$355 each. That is exactly the shape CLAUDE.md's own
`delegating-to-subagents` rule forbids ("chunk long refactors into bounded delegations"), run on
an orchestrator-grade model at orchestrator-grade context, *inside* the one execution environment
where the hook layer does not fire.

### 1.3b The rework half — measured, and it is the half that moves

`858 §8` flagged the git-churn join as the missing half of the `delegate-by-default` falsifier.
It is a `git log --numstat` join; here it is.

**Proximate rework** = a `fix:`/`revert:` PR that touches at least one code file changed by a
*different* PR in the preceding 14 days. The proximity control matters: an unqualified `fix:`
count also catches corrections to months-old code, which is not what the falsifier means.

| Week | PRs | `fix:` share | **proximate-rework share of all PRs** |
|---|---|---|---|
| W29 | 66 | 14% | 18% |
| W30 | 51 | 8% | 10% |
| W31 | 52 | 25% | 17% |
| W32 | 45 | 44% | **53%** |
| W33 | 64 | 45% | **39%** |
| W34 | 57 | 32% | **33%** |
| W35 | 49 | 43% | **41%** |

Pooled: **W29-W31 = 18/117 (15.4%)** vs **W32-W35 = 73/179 (40.8%)**, two-proportion
**z = 4.63**. Unlike the cost measure (§1.2b), this one clears its own noise band comfortably.

**Not a titling artifact:** `feat:` share held (36/49/37% → 33/28/44/45%) while `fix:` roughly
tripled; `docs`/`chore` shrank. The mix did not swap `feat` for `fix`.

The fix titles are unambiguous about what they are: `fix(871): ... (live findings)`,
`fix(sv3): ... (live-audit findings)`, `fix(859 D live findings)`, `fix(853): audit
remediation`, `fix(sv3): ... original defect was a hidden-tab artifact`. This is *shipped, then
corrected* — the falsifier's sense of rework.

**The obvious confound — tested, and it does not hold.** W32-W35 is heavy presentation-authority
work (sv3, 859-871), where `slice-execution.md`'s `ux-audit-closure` rule *mandates* an
independent live audit after implementation, so a high follow-up-fix rate could be that process
working as designed. Splitting each PR by whether it touches more `modules/ui-web/` files than
backend files:

| Period | class | PRs | proximate rework | rate |
|---|---|---|---|---|
| W29-W31 | backend | **114** | 17 | **15%** |
| W29-W31 | ui | 3 | 1 | 33% |
| W32-W35 | backend | **114** | 47 | **41%** |
| W32-W35 | ui | 65 | 26 | 40% |

**Backend-majority PRs: identical n = 114 in both periods, rework 15% -> 41% (z = 4.42).** The UI
work added 65 PRs at a comparable 40% rate — it increased the *volume* of rework but is not what
moved the backend rate. The live-audit mandate does not apply to backend slices, so the rise there
needs another explanation.

**Residual confounds, not tested.** (a) Backend work in W32-W35 includes the 882-885
decision-review lanes, which are audit-shaped by design; spot-checking the fix titles they read as
live findings on freshly shipped features (`fix(847)`, `fix(853)`, `fix: streaming producer
wedge`, `fix(worker): ...`) rather than a dedicated audit lane's output, but this is a read of
titles, not a classification. (b) Difficulty is uncontrolled. So: a correlation of the right sign
and magnitude, with the leading alternative explanation eliminated and two lesser ones open. §4.4
draws no causal claim from it.

### 1.4 Reproduction

The three tables above came from throwaway readers in `tmp/tokeff2/`
(`weekly.mjs`, `w2.mjs`, `spawnweek.mjs`), all on `lib/ledger/index.mjs` +
`spawn-economics.mjs`'s exported `costOfCall`. **This is the second time in one week that
answering a token-efficiency question required a throwaway reader in `tmp/`** — the
`agent-token-efficiency-review` tempdoc needed `tmp/tokeff/{deep,deep3,deep4}.mjs` for the same
reason. By `structural-defects-no-repeat` that is sufficient; §5 productionises it.

---

## 2. Why the shipped stack could not see this

Not a bug in any reader — a shared shape. `context-residency.mjs`, `spawn-economics.mjs`,
`cache-efficiency.mjs`, `overhead-taxonomy.mjs`, `context-attribution.mjs` all take
`--since/--until` and emit **one aggregate for the whole window**. `baseline-economics.mjs` is
the sole exception (it prints a weekly rollup) and it is the one reader whose numbers I trust
least (§4.2).

The consequence is specific, not general: a snapshot reader answers *"where did the money go?"*
and cannot answer *"is this getting better or worse?"* — which is the only question that tells
you whether a lever worked. Every lever the `agent-token-efficiency-review` tempdoc ranked in its
§4 is unfalsifiable against the current stack, because re-running the same snapshot after a
change produces a number with nothing to compare it to except a hand-copied figure in a tempdoc.

---

## 3. Theorization

### 3.1 Three candidate framings

- **(A) Efficiency as a ratio.** `ctx/out`, `$/M-output`. Denominator-free, immune to corpus
  truncation and to attribution error, computable from the ledger alone. Weakness: output tokens
  are not value — a session that writes 50k tokens of wrong code scores well.
- **(B) Efficiency as cost per unit of delivery.** `$/landed PR`. This is where the field landed
  in 2026 (put cost on the span, then make cost-per-outcome the denominator). Weakness: the
  denominator is lumpy, lags the spend that produced it by days, and says nothing about PR size.
- **(C) Efficiency as adherence.** Count violations of the rules that are known to cost money
  (spawns over N calls, calls over a context cap). Weakness: presumes the rule is right.

**Judgment: report all three, in that order, and do not composite them.** The PHI retirement
(`858 §7`, composite r = 0.064 at N = 116) is the reference case for what happens when you fold
weakly-correlated per-session signals into one score: the composite is the part that fails, and
it fails by hiding the per-signal effects that were real. (A) is the leading indicator, (B) the
lagging one, (C) the actionable one. A reader that prints three columns side by side lets the
maintainer do the inference; a reader that prints one number has already thrown it away.

### 3.2 What "better" would even look like

Worth stating before building the instrument, because the instrument will otherwise define it by
accident. A week is *more* efficient than another if it landed comparable work for less spend.
The honest version of that sentence has three escape hatches — "comparable" (PR size/difficulty),
"work" (some PRs are rework), "spend" (some spend is investigation that pays off later). This
reader is not going to close any of the three. It should therefore be explicit that it measures
**a proxy that is directionally useful and individually deniable**, and that a single week's
movement is noise. The falsifier in §7 is stated in those terms.

### 3.3 Alternatives considered and rejected

- **A composite efficiency score.** Rejected — see §3.1, `858 §7`.
- **Adopting an external platform (Langfuse / Phoenix / AgentOps / Braintrust).** Rejected, and I
  agree with the `agent-token-efficiency-review` tempdoc's §4.6 reasoning rather than re-deriving
  it: they add trace UI and eval harnesses, not a *measurement* this stack lacks. The gap here is
  a 60-line `groupBy(week)`, not a platform.
- **A dashboard.** Rejected — `858 D1` retired dashboards. `--json` plus a table is the whole
  requirement.
- **Reviving the session->merge attribution for trend use.** Rejected in favour of
  `git log --first-parent`; see §4.2.
- **Per-day granularity as the default.** Rejected. At ~7 sessions/day the daily denominator
  (PRs landed) is 0-15 and the ratio is dominated by which day a long spawn happened to run.
  `--by day` should exist for incident forensics; `week` is the default.

---

## 4. Criticism of current decisions

Written as arguments to be judged, not as decisions taken. Each is falsifiable.

### 4.1 The `agent-token-efficiency-review` tempdoc's levers are ranked off a level, not a trend

Its §4 ranks "bound context per call" first at an upper bound of $5.4k/window. That figure is a
*static* cap-excess computation over a 5-week block. The trend says the main loop already
improved (502k -> 331k p50) without the lever, while subagent context rose — so the ranked lever
is aimed at the half of the system that is fixing itself. **Lever 1 and lever 2 should swap**:
model routing and spawn-length bounding target the 74%-of-spend half that is actively
degrading. This does not invalidate the tempdoc — it invalidates reading a snapshot as a plan.

### 4.2 `baseline-economics.mjs`'s cost/merge is the wrong denominator and should stop being the headline

Its own caveat block is the argument: of 479 merge rows, **144 are unattributable** (no
discoverable transcript), 86 duplicates, 80 off-main — 313 eligible, 169 attributed. So the
headline `$121.72/merge` is computed over ~35% of the merge rows, and the missing 65% are
missing *because transcripts rotate*, which is not random with respect to time. That biases the
denominator toward recent weeks, which is precisely the direction that would *hide* a rising
trend.

`git log origin/main --first-parent | count-by-week` needs no attribution, no ledger, no
`record-merge.mjs` backfill, cannot be double-counted, and is exact. The session->merge ledger
remains the right tool for *per-session* attribution ("what did this session cost per thing it
shipped"); it is the wrong tool for *aggregate trend*. The stack has been carrying an expensive,
lossy join to answer a question a one-line git command answers exactly.

### 4.3 The retention policy is inverted, and it is destroying the only data that can answer this

Measured on the main checkout today:

| Stream | Size | Retention | Consumers |
|---|---|---|---|
| `otlp/traces.*` | **972 MB / 49 files** | `None` (never pruned) | **none** — no reader in `scripts/agent-analytics/` reads the traces stream; only a generic `loadOtlpStream` test touches it |
| `otlp/metrics.*` | 149 MB / 8 files | `None` (never pruned) | `loadCostsFromOtlp` (session-level dollars/tokens only) |
| `otlp/logs.*` | 61 MB / 4 files | 2 archives | — |
| `~/.claude/projects/**/*.jsonl` | — | **~30 days, harness-controlled** | **every reader in this directory** |

The `otlp-sink.py` README calls the metrics doubling from the `gen_ai.usage` normalisation a
"known tradeoff" and defers the retention question to the owner. That framing understates it. The
actual state is: **the stream with zero consumers is kept forever, and the stream that every
consumer depends on is deleted after 30 days by a harness setting nobody in this repo set.** Per-call
context distribution, spawn run lengths, compaction triggers, per-tool residency — none of it
exists outside the transcripts, and none of it is preserved.

Concretely: the W31 row in §1.1 is already unrecoverable, and by 2026-10-02 the W33-W35 rows —
the three clean weeks this entire analysis rests on — will be gone. Every week that passes
destroys a week of the only history that can judge whether any of these levers worked. That is
irreversible, and it is happening now, which makes the snapshot writer (§5.2) the higher-priority
half of this tempdoc even though the trend reader (§5.1) is the more visible one.

The cheap fixes, in order: (a) write the weekly aggregate to a small append-only NDJSON before
rotation eats the source; (b) set `RETENTION["traces"]` to a finite number, or stop exporting
traces, since nothing reads them; (c) *consider* raising `cleanupPeriodDays` — but note this only
buys time and costs disk, and (a) is strictly better because aggregates are ~1 KB/week against
gigabytes of raw.

### 4.4 The `delegate-by-default` falsifier appears to be firing, and its judgment is due

CLAUDE.md's `delegating-to-subagents` rule carries its own falsifier, opened 2026-07-14, judge by
~2026-09-14: *"cost-per-shipped-merge should improve without rework rising — flat -> delete this
paragraph; rework up -> raise the floor."*

**The correct verdict on 2026-09-14 is "cannot be judged", and that is itself a finding.** My
first pass had this as "the falsifier is firing" on the strength of $65 -> $87 per PR. The size
control (§1.2b) kills that: normalised by code churn the same weeks read $75 -> $47 -> $87, a 2x
swing with no trend. Neither direction is supported.

What the data *does* establish is that the falsifier's premise moved: the share of spend running
inside subagents went 36% -> 74%, and the `>=120-call` tail — the exact shape the rule tells the
orchestrator to chunk away — now carries ~78% of subagent cost. The intervention the falsifier
was written to evaluate did happen, at scale.

On its two halves: **the cost half cannot be judged** (noise band wider than any plausible effect
at n≈50 PRs/week × 5 weeks, and the window cannot be widened backwards because the transcripts are
already deleted — §4.3). **The rework half can, and it moved**: proximate rework 15.4% -> 40.8%,
z = 4.63 (§1.3b), with a work-type confound stated there that this corpus cannot separate out.

### 4.4b The falsifier's remedies do not fit the failure mode

This is the sharper criticism. The rule offers exactly two outcomes: *"flat -> delete this
paragraph; rework up -> raise the floor."* Rework is up. But **"raise the floor" is not an
available move**: the floor is already near the ceiling — 675 of 1,018 spawns requested `opus`
outright, 95% of spawn cost is opus, and Sonnet is 303 spawns at $578 total. There is no cheaper
tier to stop using and no more expensive tier to escalate to. The remedy presumes the failure mode
is *model choice*, and the ledger says it is *run length*: the median spawn is flat at ~50 calls
while `$/spawn` rose 71%, and the entire increase sits in the `>=120-call` tail (§1.3).

So the falsifier as written cannot produce a correct answer regardless of what the data says. It
needs a third outcome — **bound the spawn, not the model** — and that is the one CLAUDE.md already
states in prose ("chunk long refactors into bounded delegations") and that the ledger shows is not
happening: 47-62 spawns per week exceed 120 calls, every week, for five weeks. By CLAUDE.md's own
`before-appending-to-rules` reasoning, a must-rule that prose has failed to deliver for five
consecutive weeks belongs in a hook (~100% adherence), not in more prose (~70%). §5.3 proposes the
cheapest version.

Renewing the paragraph unchanged on 09-14 would be the deferral move
`structural-defects-no-repeat` names; declaring it falsified on the cost half would be asserting
more than the data carries. The honest resolution is to **rewrite the falsifier's remedy set**,
ship §5.2 so the cost half becomes judgeable at ~15 buckets, and act now on the run-length finding
that does not need the cost half at all.

### 4.5b The largest single lever is the orchestrator's own model, and nobody has named it

The `agent-token-efficiency-review` tempdoc's §4.2 lever is "model routing for implementation
**workers**" — opus vs sonnet inside spawns. It never asks what the **main loop** runs on. The
main loop carries the largest resident context in the system (p50 331-500k, re-presented on every
one of ~14k calls), and 84% of those calls run on `claude-fable-5`, whose cache-read rate is
**$1.00/M — exactly 2x Opus 5's $0.50/M and 5x Sonnet 5's $0.20/M** (`lib/transcript-cost.mjs`;
Fable-5's rates are identical to `OPUS_FAST`).

Counterfactual re-pricing of the same token counts, same window (2026-08-03..now):

| main-loop model | calls | cache-read | actual | if Opus 5 | if Sonnet 5 |
|---|---|---|---|---|---|
| `claude-fable-5` | 9,038 | 4,104M | **$6,112** | $3,056 | $1,222 |
| `claude-opus-5` | 3,924 | 1,527M | $1,168 | $1,168 | $467 |
| `claude-fable-5-1` | 1,529 | 516M | $869 | $434 | $174 |
| **total main** | | | **$8,148** | **$4,660** | — |

**Delta: $3,489 per 5-week window**, on identical work — larger than that tempdoc's top-ranked
lever ($2.5-3.5k realistic for bounding context per call), and it requires no behaviour change at
all, only a model choice.

**This is a tradeoff, not a free win, and the tempdoc should not pretend otherwise.** Fable is
presumably run for orchestration quality; CLAUDE.md's own routing rule says judge the output, not
the price tag, and a worse orchestrator that re-does work costs more than $3.5k. The criticism is
not "switch models" — it is that a 2x multiplier on the single largest resident context in the
system was never *measured or named*, in a tempdoc whose whole subject was model-routing
economics. It cannot be traded off if nobody puts a number on it. §5.1's reader prints per-model
rows for exactly this reason.

### 4.5 The fail-closed pricing warning is being desensitised

`cache-efficiency.mjs` ends every run with a loud `!!` for `<synthetic>` — 69 turns, 0.0M tokens,
0.0% of cache-read. The warning exists to catch a genuinely missing model (it caught
`claude-opus-5` hiding a third of all spend, per the README). Firing it every single run for a
known-benign harness placeholder is how a maintainer learns to skim past the line that matters.
`<synthetic>` should be an explicit known-non-billable entry, reported in a quiet one-liner, with
the `!!` reserved for models that are actually unknown. This is small and it is a real
degradation of a load-bearing guard.

---

### 4.6 The two control shims are aimed at the half of the system that is improving

`context-ceiling-hint` (886 PR 4) is a `PostToolUse` hook that fires at 300k/500k main-loop
context. Main-loop p50 is 331-502k and **falling** (502k -> 331k). Meanwhile 74% of spend is inside
subagents, whose p50 rose 158k -> 201k — and **parent hooks do not fire inside a subagent**, so the
shim structurally cannot reach them. `spawn-cost-hint` does reach the spawn, but only on return,
after the spend. So both shims from the token-efficiency review land on the improving half, and
neither can act on the degrading one. Not a defect in either hook — a consequence of where the
harness lets a hook run — but it means "886 shipped control shims" should not be read as "the
subagent tail is now instrumented for control." §5.3 names the one reachable point.

### 4.7 External practice, and the two places it contradicts this repo

Research pass 2026-09-02 (complementing the `agent-token-efficiency-review` tempdoc's §5, which
covered harness settings and effect sizes but not orchestration economics):

- **Explicit per-agent iteration budgets are standard practice**, not a novel idea — published
  scaffolds cap the parent (~90 iterations) and give each subagent an independent, smaller budget
  (~50). This repo has **no budget at either level**, and its `>=120-call` spawns — 47-62 per week
  — would be 2.4x over a typical subagent cap. §5.3's ~120 self-bound is *generous* against this
  precedent, which is an argument for shipping it rather than debating the number.
- **Subagents as "context firewalls" with capped return payloads** (e.g. 8 KB summaries), and
  hierarchical delegation (leads spawning specialists) to keep the orchestrator's own context flat.
  Lower value here: main tool results are only ~9% of re-presentation cost (§1 / 886 §2.4). Noted,
  not proposed.
- **Reference orchestrator/worker token split ~9.8% / 70.6%.** This repo runs **26.6% / 73.4%**
  (`baseline-economics.mjs`). Worker share is at the norm; orchestrator share is ~2.7x it, and the
  guidance attached to that reference is that a coordinator over-consuming is accumulating context
  it should have delegated. Treat as orientation, not a target — it is one paper's measurement on
  different work, and §4.5b shows the orchestrator's *cost* share (44%) diverges from its token
  share anyway because of the model it runs on.
- **"Cost per outcome, not cost per token" is the settled framing** — which §3.1 already adopts,
  and §1.2b is the cautionary case for doing it with only one denominator.

Sources: [Augment Code](https://www.augmentcode.com/guides/ai-agent-loop-token-cost-context-constraints),
[The Harness Effect (arXiv 2607.06906)](https://arxiv.org/pdf/2607.06906),
[The Orchestrator's Tax](https://martinfowler.com/articles/orchestrator-tax.html),
[Code Agent Orchestra](https://addyosmani.com/blog/code-agent-orchestra/),
[Future AGI](https://futureagi.substack.com/p/agent-cost-optimization-is-an-observability),
[MintMCP](https://www.mintmcp.com/blog/ai-agent-monitoring),
[nerdheadz](https://www.nerdheadz.com/blog/token-efficiency-ai-coding-agents-guide).

## 5. Design

### 5.1 `scripts/agent-analytics/efficiency-trend.mjs` (new reader)

A reader on the neutral ledger, following `context-residency.mjs`'s structure exactly (pure
`build*` functions + a `print*` per section + `--json`, guarded `main()`).

```
node scripts/agent-analytics/efficiency-trend.mjs
node scripts/agent-analytics/efficiency-trend.mjs --by day --since 2026-08-20
node scripts/agent-analytics/efficiency-trend.mjs --json
```

Flags: `--by week|day` (default `week`), `--since <ISO>` (default: trailing 60 days), `--until`,
`--harness claude-code|codex-cli|all` (default `all`), `--no-git` (skip the delivery section).

Sections:

- **(a) Leading indicators**, one row per bucket: calls, cost, `ctx/out`, `$/M-output`, main p50
  context, sub p50 context, subagent cost share.
- **(b) Delivery**, one row per bucket: cost, PRs landed (`git log <ref> --first-parent
  --since --until`), `$/landed PR`, **and** median churn/PR, code churn, `$/1k code lines` (from
  `--numstat`, splitting paths into code / docs / generated noise). `--ref` defaults to
  `origin/main`, falls back to `main` and says so. Absent git -> section prints why it is absent,
  never a fabricated denominator.
  **Both denominators are mandatory, side by side.** §1.2b is the reason: they disagree, and
  printing either one alone produces a confident wrong answer. The section must also print a
  one-line power warning naming the observed week-to-week swing, so a 3-bucket slope is not read
  as signal.
- **(c) Spawn tail**, one row per bucket: spawns, median calls/spawn, median peak context,
  `$/spawn`, count and cost share of spawns at or above `--long-spawn-calls` (default 120).
- **(d) Corpus honesty**, always printed, never `--json`-only: oldest surviving transcript mtime
  (the rotation floor), and every bucket marked `TRUNCATED` (starts before floor + 1 day) or
  `PARTIAL` (extends past `now`). **A bucket carrying either marker must be excluded from any
  trend statement the reader prints**, and the marker must appear in `--json` as a boolean field,
  not only in the human table.

Correctness requirements that are not obvious:

1. **Bucket by call `ts`, never by file mtime.** `listCalls` already does per-call `ts` filtering
   (`windowBy: 'ts'`, the default) — do not pass `windowBy: 'mtime'`.
2. **A spawn's bucket is its FIRST call's `ts`**, not each call's, or a long spawn is smeared
   across two weeks and both `$/spawn` figures are wrong.
3. **Pricing fails closed.** Reuse `spawn-economics.mjs`'s exported `costOfCall`; an unpriced call
   is counted in an `unpricedCalls` column, never silently $0.
4. **ISO-8601 weeks (Mon-start) in UTC**, matching `git log --date=format:'%G-W%V'`, or (a) and (b)
   disagree by up to a day at every boundary.
5. **No trend arithmetic across a `TRUNCATED` or `PARTIAL` bucket.**

### 5.2 `scripts/agent-analytics/trend-snapshot.mjs` (new writer) — the higher-priority half

Appends the current bucket aggregates to `tmp/agent-telemetry/efficiency-trend.ndjson`, one line
per `(bucket, harness)`, idempotent (re-running for a bucket replaces that bucket's line rather
than appending a duplicate). Aggregates only — call counts, token sums, percentiles, costs,
spawn-length histogram. **No prompt text, no file paths, no session content**; this is the same
local-only, never-leaves-the-machine posture as the rest of the directory, and the content rule
should be enforced by the test, not just documented.

`efficiency-trend.mjs` reads the snapshot file for buckets that predate the rotation floor and
merges them with live-computed buckets, marking each row's source (`live` / `snapshot`). That is
what makes the history survive the 30-day delete.

Run it from the existing session-end path or manually; deciding *where* it is invoked is part of
the implementation brief, but it must be cheap enough to run unconditionally.

---

### 5.3 The spawn call-budget self-bound (proposal — NOT in this tempdoc's implementation scope)

The finding in §4.4b needs an enforcement point, and the parent has none: **parent hooks do not
fire inside a subagent**, so the orchestrator physically cannot interrupt a spawn that is running
long. `spawn-cost-hint` (886 PR 4) reports the cost *after* return — useful for judgment, useless
for control, because the money is already spent.

The one place the parent can still reach is **`SubagentStart`**, which
`scripts/agent-analytics/hooks/subagent-guide.mjs` already occupies: it injects a baseline brief
into every subagent regardless of what the orchestrator remembered to write. Adding a call-budget
self-bound to its existing "Subagent-specific risk profile" section converts an orchestrator-side
convention (which the ledger shows is ignored) into a subagent-side instruction delivered on every
spawn:

> If you pass ~120 tool calls, stop and report what is done and what remains rather than pushing
> on. A spawn past that length is where this repo's cost concentrates, and a partial result the
> orchestrator can re-chunk is worth more than a complete one that costs 5x.

Why this shape rather than a hard block: a genuinely long task exists, and a guard that kills it
would be worse than the cost. This is advisory-by-construction — the subagent decides — but it is
*delivered* unconditionally, which prose in CLAUDE.md is not.

**Deliberately out of scope for the implementation delegated in §6.** It touches the hook layer,
which means `governance/agent-hooks.v1.json`, the tier-register, and the `hook-integrity` gate —
a different review surface from a maintainer-only reader, and it should be judged on its own.
Falsifier if it ships: `>=120-call` spawns should fall below ~20% of spawn cost within four
buckets; if the tail share is unchanged, the injection is not being read and the next step is a
real budget, not a longer sentence.

## 6. Implementation plan

### 6.0 Model routing — deliberate, and a data point

**OUTCOME (recorded 2026-09-02, as this section promised).** Shipped, green, acceptance gate
reproduced exactly. **234 calls, peak context 410k, $17** (`spawn-economics.mjs --since
2026-09-02`) against $88-$202 for the same day's 347-561-call opus workers — roughly 3-5x cheaper
per call. Four rounds: one implementation + three fix rounds.

- **What sonnet got right first time:** the whole specified surface. Round 1 reproduced §1.1/§1.2b/
  §1.3 exactly, 66/66 suite green, and volunteered work the brief did not ask for — a per-bucket
  `unpricedCalls` column (my own throwaway script silently absorbed those at $0), and a recursive
  `findContentLeaks` that fails closed at write time and is tested against crafted violations
  rather than only clean input.
- **What independent review caught, and its own tests did not:** four defects, all real, all
  reproduced on live data — snapshot merge ignoring `--since`/`--until` (a 10-day query returned
  five weeks); `source` computed but never printed; snapshot rows flagged `TRUNCATED` from *today's*
  floor, which would have permanently excluded every rescued bucket from the trend arithmetic the
  writer exists to enable; and section (d)'s bucket lists contradicting the per-row flags in `--json`.
- **The honest reading is not "sonnet was too weak."** All four cluster in the snapshot-merge and
  corpus-honesty area — the part §5.2 specified in eight lines against §5.1's thirty. That is a
  **brief-thinness signal at least as much as a model signal**, and it is the cheap lesson here:
  the section I under-specified is exactly the section that came back wrong, four times.
- **What was actually load-bearing was the review, not the tier.** The worker's own unit tests
  passed through all four defects; every one surfaced only by running the tool against real data
  and reading the output. `static-green ≠ live-working`, in miniature, on the very tempdoc that
  argues for measurement over assertion.
- **Cost of the review loop, stated:** round 1 was 105 calls, inside the ~120 bound §5.3 argues
  for. The three fix rounds added 129 more, so the *total* ran to 234 — the per-round budget held,
  the cumulative one did not. A review loop is not free; at $17 against $88-$202 it was still the
  cheaper way to get a correct tool.

Rationale for the original choice: the work is
bounded, follows a file-level precedent (`context-residency.mjs`), and has a *self-verifying*
acceptance criterion — it must reproduce the §1 tables, which are already computed and printed
above. That is the profile CLAUDE.md's routing rule describes as sonnet-appropriate, and §1.3
says opus-by-default on long workers is where the money is going. If the output misses the bar it
gets redone on opus and *that* is recorded here, because "judge the output, not the price tag"
requires actually recording the judgment. Either way this is one honest data point for the
`delegate-by-default` falsifier's rework half (§4.4).

**Chunk order note (after the §1.2b retraction):** §5.2's snapshot writer is the half with a
deadline — every week that passes without it destroys a bucket that can never be recovered, and
§4.4 shows the falsifier judgment now *depends* on accumulating buckets forward. §5.1's reader is
the more visible half but has no clock on it. Build the reader first only because the snapshot
writer reuses its aggregation functions; if the work has to be cut short, **ship the writer, not
the pretty tables**.

### 6.1 Chunk 1 — `efficiency-trend.mjs` + tests

Acceptance criteria (all mechanically checkable by the implementer):

- `node scripts/agent-analytics/efficiency-trend.mjs --since 2026-08-03` reproduces §1.1, §1.2,
  §1.2b and §1.3 **to the printed precision**, for W32-W36. This is the primary gate; a mismatch
  means the implementation is wrong, not the tempdoc.
- `--json` emits the same numbers with `truncated`/`partial` booleans per bucket.
- Unit tests over synthetic ledger fixtures (following `fixtures/claude/` precedent, synthetic
  content only) covering: ISO-week boundary alignment against a known date, spawn-bucket-by-first-call,
  unpriced-call accounting, truncated/partial marking, and `--no-git` / git-absent behaviour.
- `node scripts/agent-analytics/run-all-tests.mjs` green.

### 6.2 Chunk 2 — `trend-snapshot.mjs` + tests

- Idempotent append (re-run replaces the bucket line).
- A test asserting the emitted record contains no string longer than N chars and no `/`-bearing
  path-like value — the aggregates-only rule enforced, per §5.2.
- `efficiency-trend.mjs` merges snapshot rows for pre-floor buckets and labels row source.

### 6.3 Chunk 3 — the two small fixes from §4

- `<synthetic>` as a known-non-billable pricing entry so the `!!` warning stops firing on it
  (§4.5). Must not weaken the warning for genuinely unknown models — a test for both branches.
- `RETENTION["traces"]`: propose a finite value in the PR description with the measured 972 MB /
  zero-consumers evidence; **do not change it without the owner's word** — retention is an owner
  decision and §4.3 is an argument, not authorisation.

### 6.4 Documentation

`scripts/agent-analytics/README.md` gets a section in the established voice: what the reader
measures, the rotation-floor caveat, and the explicit statement that the metrics are proxies
(§3.2). No canonical-doc change — this is maintainer tooling.

---

## 7. Falsifiers and open questions

- **This tempdoc's own falsifier.** If, by 2026-10-15, `$/landed PR` and `$/M-output` have moved
  in opposite directions for three consecutive complete weeks, the two are not measuring the same
  thing and §3.1's "report all three" is wrong — collapse to whichever tracks maintainer judgment.
- **PR-size confound: controlled, and it retracted the finding.** Done in §1.2b. Result: median
  churn/PR swings 364 -> 1,266 -> 1,031 across W33-W35 and `$/1k code lines` shows no trend. Kept
  here as the worked example — the control took one `git log --numstat` join and overturned the
  headline, which is the argument for the reader printing both denominators side by side (§5.1b)
  rather than picking one.
- **Statistical power is the real blocker.** At ~50 PRs/week and a ~2x observed week-to-week
  swing in `$/1k code lines`, detecting even a 30% real effect needs many more weeks than five.
  Nobody should re-run this analysis expecting a verdict until the snapshot file (§5.2) has
  accumulated ~15 buckets. The reader should say so in its own output rather than letting a
  maintainer read a 3-week slope as signal.
- **Rework half: measured, and the leading confound is eliminated (§1.3b).** Proximate rework
  15.4% -> 40.8% overall (z = 4.63); backend-only, at identical n = 114 per period, 15% -> 41%
  (z = 4.42). Remaining open: difficulty is uncontrolled, and the 882-885 decision-review lanes'
  contribution to the backend fix count is read from titles, not classified. Neither is large
  enough to explain a 2.7x move, but both should be closed before 09-14 if the judgment is going
  to lean on this number.
- **The rework measure belongs in the reader, not in this tempdoc.** It is a `git log --numstat`
  join the delivery section (§5.1b) already runs. Adding a `rework%` column there is nearly free
  and turns a one-off analysis into a tracked series — but it is NOT in §6's scope, because the
  proximity window (14 days) and the `fix:`-title dependency are judgment calls that deserve their
  own review rather than riding along in a reader PR.
- **`autoCompactWindow: 600000` does NOT fire — answered, and the answer is "the ceiling you
  think you have does not exist."** Inherited open question from the
  `agent-token-efficiency-review` tempdoc §7. The setting IS present
  (`~/.claude/settings.json:52`). Since it was added (2026-08-26),
  `context-residency.mjs --since 2026-08-26` shows **2 compactions, both `manual`**, at
  pre-compaction contexts of **947k and 959k** — sessions ran to ~950k tokens, 1.6x past the
  600k setting, and auto-compaction never triggered; a human compacted by hand both times. Over
  the full window it is 8 manual / 1 auto, the one auto at 1.0M.
  This matters more than an unused setting normally would: it is the difference between "the main
  loop has a context ceiling" and "it does not", and every context-bounding estimate in this
  tempdoc and in the `agent-token-efficiency-review` tempdoc's §4.1 assumed the lever was at least
  *available*. **Do not cite `autoCompactWindow` as a mitigation anywhere until someone establishes
  what it actually does** — the observed behaviour is consistent with the setting being ignored, or
  with auto-compaction firing only at a hard model-context limit regardless of it.
  `context-ceiling-hint`'s 300k/500k advisories are, on this evidence, the only ceiling in the
  main loop — and §4.6 notes they cannot reach the subagents carrying 74% of spend.
- **Is the W34-W35 spawn-tail growth one campaign or a pattern?** The top spawns are 875-878 and
  the 882-885 decision-review lanes. If it is one campaign, the trend is a level shift, not a
  slope, and the right response is a spawn-length bound rather than a routing change.
