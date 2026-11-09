---
title: "Public agent-utility evidence release: project an accepted 624 comparison record into a zero-cost deterministic replay, durable evidence bundle, and governed public claim; keep paid stochastic reruns separate"
type: tempdocs
status: "incomplete — level-1 (code-complete) work is implemented, independently verified, and code-quality-hardened on codex/719-agent-utility-publication (see §Post-implementation takeover verification and §Pre-PR fix pass); branch is unpushed pending owner go-ahead (gradle pre-merge build + hosted CI + RESEARCH.md narrative wording are the remaining pre-merge items, §Session handoff). Numeric promotion remains blocked on owner-set scientific thresholds, backend/model corpus gates, and a future accepted run. The public pointer remains current:null; the 2026-07-12 survivor statistic is only a rejected history fixture."
created: 2026-07-12
updated: 2026-07-13
author: agent (Fable, strategy session) — filed at founder request after the token-efficiency competitive-benchmark research
category: eval-infrastructure / go-to-market / benchmark-publication / search-quality
related:
  - 624-agentic-retrieval-eval-rebuild            # the internal measurement machinery this publishes (utility-run / utility-comparison.v1)
  - 707-pillar1-inband-utility-corpus              # the certified in-band corpus the accepted record must reference (built there, not here)
  - 704-measurement-substrate-correct-data-program # the substrate program; this is its outward-facing conversion step (pillars beyond measurement)
  - 667-benchmark-release-external-baselines-and-research-md  # the existing RESEARCH.md public surface this projects into
  - 623-reproducible-benchmark-release             # the canonical-record + reproducible-release discipline this conforms to
  - 625-asserted-measurement-provenance            # the generalized projection-not-fork principle; 719 builds only this concrete instance
---

> **Agent-utility campaign freeze (2026-11-09):** Fresh and resumed live agent-utility campaigns are frozen. Their commands and runbooks remain available for historical reproduction and offline analysis, along with published evidence and offline replay. Neighboring experiments retain their existing status. The campaign plans and launch/resume instructions below are historical and do not authorize campaign work. NOTE: Noncanonical working tempdoc. The opening stub is retained as history but is superseded by the
> takeover, research, and settled-design sections below. Verify every cited number against the named
> records before building on it. Historical proposal language below is retained for auditability;
> the current implementation authority is the fold immediately below.

## Current implementation fold (2026-07-13; internal promotion boundary implemented)

The first implementation was incorrectly described as complete. The internal promotion boundary now
fails closed, but this tempdoc remains incomplete until the explicit external gates below are
satisfied:

- One all-attempt observation seam indexes the expected condition/seed/query matrix, rejects corrupt
  or incomplete logs, and feeds loss accounting, status, scale reporting, composition, sanitization,
  and replay. Excluded cells retain tool/leak/surface evidence.
- Source identity covers corpus bytes, query-and-gold bytes, full canonical MCP `tools/list`, resolved
  model, environment, and Git status including untracked files. Resume recomputes those facts; replay
  cannot supply search identity. It also captures the matching fully certified 707 snapshot and
  rejects missing, partial, stale, or byte-drifted certification.
- `utility-recompose` is a pure offline finalizer for logs or sanitized evidence. The versioned claim
  policy pre-registers the exact member/dataset/size/query-variant/model/query-count/seed matrix,
  rejects missing, extra, and duplicate strata, requires every stratum to be the complete
  seed-by-query Cartesian product in both arms, and treats an empty matrix as unresolved. Seed,
  paired-observation, uncertainty, adoption, and outcome gates apply to every ITT stratum; aggregate
  counts are descriptive. Per-protocol evidence is secondary; validity is separate from benefit,
  harm, null, adoption-only, and inconclusive outcome direction. Any inconclusive required stratum
  blocks promotion. The owner-set matrix and thresholds remain deliberately unresolved.
- The rejected 2026-07-12 pilot is strict allowlisted JSONL evidence. It replays all 48 attempts and
  rejects the false-green survivor projection through the actual loss accounting.
- CLERC and MIRACL-DE have committed 1k/10k verbose and short-natural members plus immutable
  commitment manifests and structural certification. The certification command can now aggregate
  those four member cells with typed closed-book, retrieval-calibration, union-recall, and leak
  measurement-only artifacts whose verdicts are recomputed against a separate, checked-in pre-run
  policy rather than artifact-supplied thresholds. That 707 policy remains draft and unresolved.
  It binds the exact query-and-gold digest as well as corpus bytes. Gate artifacts embed the canonical
  measurement and, for backend gates, run-manifest/projection bytes. The source-time snapshot embeds
  the exact policy, certificate, and gate bytes, so policy evaluation and replay re-hash and
  re-evaluate them;
  those scientific runs remain pending. CLERC is revision-pinned and MIRACL records its
  dataset/library version. EnronQA stays non-claim-eligible because its upstream license is unresolved.
- Publication construction is accepted-only and bundles the exact policy snapshot. Python and CI
  agree on initial-null, accepted, superseded, and withdrawn pointer states. Rejected evidence stays
  in test/history fixtures; the checked-in pointer remains `current:null`.
- A dedicated reference page, outward `RESEARCH.md` checks, projection lifecycle/drift tests, and
  canonical jseval CI replay exist. Accepted README output is a short outcome-specific teaser;
  `RESEARCH.md` is per-stratum; the reference page carries full arm distributions, intervals, loss,
  outcome, certification, evidence, and replay identity. Manual judge/recomposition/probe helpers
  were removed.

Remaining work: run the no-cost backend corpus gates when a stack is available; obtain owner
decisions for the exact campaign matrix, scientific margins, model cohort, email source or
replacement, and smoke/powered budgets; authorize any future paid campaign; and select a result only
if an accepted immutable bundle passes all gates.

### Verification and handoff evidence (repair run)

Branch: `codex/719-agent-utility-publication`. No PR, push, merge, paid call, credentialed call, or
accepted-result selection was performed.

- Final repair verification: `python -m pytest -q scripts/jseval/tests` reached
  **1750 passed, 2 failed** in 446.84 seconds (2026-07-13). Both failures
  are the pre-existing `test_correction_probe` default-manifest tests, because
  `scripts/jseval/jseval/data/correction-eval-queries.v1.json` is absent from both this HEAD and the
  main checkout. This unrelated missing fixture was not fabricated as part of 719.
- The final focused policy, certification, capture, command-surface, and publication suite is
  **150 passed**. This includes the exact seed-by-query product, pre-run threshold authority,
  query/gold drift, canonical source/manifest tampering, shortcut-leak arithmetic, safe/recomputed
  environment identity, all-required-strata promotion, and full MCP schema identity/drift. An
  independent refute-first reviewer dynamically repeated the promotion attacks and found no remaining
  blocker in that scope.
- Recomposition of the committed rejected fixture produces semantic digest
  `2cea990ce444f6cb177f8ec3e0dddae4354286e8d62f512f0ee2a8594c076ee6`, `comparable=false`, paired
  retention `0.5`, A loss `24/16/8`, B loss `24/14/10`, and `claim_verdict.accepted=false`.
- Serialized single- and cross-corpus records validate against the two v2 schemas. Command inventory
  check is green at 84 commands, including the canonical scientific-evidence packager.
  `python -m compileall -q scripts/jseval/jseval` is green.
- `check-public-agent-utility.mjs`, the three projection `--check` results, outward-number citation
  tests, workflow-trigger tests/guard, `git diff --check`, canonical-doc links, module-dependency
  canonical check, runtime-config matrix, `llms.txt --check`, skills sync, and methodology
  markdownlint are green.
- A fresh `git clone --no-local` replayed the rejected evidence with the canonical CLI and ran
  `check-public-agent-utility.mjs` from inside the clean clone: worktree clean, pointer null,
  `accepted=false`, `comparable=false`, semantic digest
  `2cea990ce444f6cb177f8ec3e0dddae4354286e8d62f512f0ee2a8594c076ee6`.

### Explicit deferred checks and owner gates

- The accepted-result renderer/replay branch is exercised with synthetic accepted fixtures in tests;
  no real accepted record can exist while the checked-in scientific policy is unresolved.
- Hosted GitHub CI has not run because this branch was not pushed. The local workflow trigger guard is
  green. The cross-worktree tempdoc-number guard currently reports an unrelated collision between two
  other worktrees' tempdoc 720 drafts; 719 itself has no number collision.
- CLERC/MIRACL structural materialization is complete and recorded in 707. Closed-book, retrieval,
  union-recall, leak, provider replication, and paid adoption/powered campaigns remain unverified.
  Two canonical backend-only attempts were made against the CLERC 1k verbose member; both stopped
  before ingestion because the backend did not become healthy within jseval's 120-second startup
  boundary. No process or data directory survived. The checked-in 707 scientific policy remains
  draft, so no scientific evidence can certify until the owner settles its exact cells and thresholds.

# 719 — Public agent-utility evidence release

## The goal (north star)

An outside developer clones the public repo, runs **one command**, and reproduces the same
agent-utility numbers JustSearch publishes — token-efficiency first, accuracy second. The artifact is
**runnable, not just readable**: raw per-query results, the corpus, the harness, and the exact command
are all public, so a skeptic can re-derive the headline rather than trust it.

This is the **conversion step**: turning the internal `utility-comparison.v1` measurement (owned by
624) into an external, self-serve artifact. It is the trust/attention layer, not more measurement.

## Why this, why now (the evidence)

A competitive-benchmark research pass (2026-07-12) on the agent-tooling projects that won attention by
leading with token-efficiency produced one decisive, counter-intuitive finding:

- **Magnitude is not the moat.** Cq won ~225 HN points with **no token number at all** (pure
  narrative); Code-review-graph claimed **49× fewer tokens** and got **12 points**. Same category,
  and the number-size predicted attention *backwards*. Attention tracked **reproducibility +
  narrative + install-friction**, not the size of the percentage.
- **The "96-99%" blockbusters are a different, easier metric** (fixed MCP-schema-overhead elimination,
  or isolated retrieval-step tokens with no accuracy gate) — NOT JustSearch's harder, accuracy-gated,
  end-to-end doc-QA measurement. The one true methodological cousin (CodeGraph: same agent
  with-vs-without the tool, real task, end-to-end tokens) scored **47% mean / 59% median** and went
  viral (0→47k stars) largely on **published raw per-repo numbers + reproducibility**, not on the
  percentage alone.
- JustSearch's honest current number (~**25.7% fewer tokens end-to-end**, accuracy-gated, CI reported,
  n=12 pilot) is roughly half CodeGraph's, on a *harder-measured* basis — respectable, not
  blockbuster. Its genuine, uncopyable edge is **rigor + honesty**, which only becomes an asset if it
  is *legible to outsiders* — i.e. reproducible.

So the bottleneck to "showing off" is not a bigger number; it is the **public artifact around the
number**. This tempdoc owns that artifact (minus the demo, see Boundary).

## What this owns

1. **A one-command public reproduction.** From a clean clone: fetch/build the committed benchmark
   corpus, run the agent-utility comparison, emit the same headline metrics. Conforms to 623's
   canonical-record + reproducible-release discipline; extends 667's existing public benchmark surface
   (`RESEARCH.md`, `release.v1.json`), does not fork it.
2. **Published raw results, not just a headline.** Per-query token/accuracy deltas, the exact model,
   corpus signature, CLI version, and CI/cohort identity — the CodeGraph lesson (raw per-repo numbers
   published) applied here. The honest-methodology story (the placebo-arm catch, the synthetic-artifact
   correction) made concrete as a reproducibility narrative.
3. **Two honest metrics, clearly distinguished** (the research's legitimate-reframe finding):
   - **End-to-end token efficiency** (the ~25% number): total context to answer a real question,
     accuracy held equal. The rigorous headline.
   - **Retrieval-step-only token cost** (Semble's methodology): tokens to *locate* the correct
     document vs tokens to *answer* — a distinct cut of the same experiment, plausibly a much larger
     honest gap (grep burns tokens on failed exploratory searches). **Published as a separate,
     labeled metric, never as a rebrand of the 25%.** This is the single cheapest path to a
     peers-frame-competitive number and uses data already in hand.
4. **A benchmark that an outsider can trust *because* they can run it** — the reproducibility itself is
   the differentiator vs the unverifiable-claim crowd.

## First concrete deliverable (cheap, do first)

**The retrieval-step re-slice of the existing pilot data.** The n=12 (and any future) pilot's per-cell
tool-call logs already record token counts per tool call. Slice them into "tokens spent locating the
right document(s)" vs "tokens spent reading/answering," for both arms, and report the located-phase
delta as a distinct metric. ~$0 (no new agent run — reads existing logs). Verify feasibility against
the captured per-cell `tool_calls` token fields first; if the phase boundary isn't cleanly derivable
from current logs, that gap is this tempdoc's first small harness ask.

## Boundary (what this does NOT own)

- **NOT the demo.** The visual/airplane-mode demo is explicitly out of scope for now (founder
  direction 2026-07-12) — it will come, but not here.
- **NOT the corpus construction.** The contamination-free, in-band, grep-stressing corpus is 707's
  (pillar-1). This benchmark *consumes and ships* it; if 707's corpus isn't ready, this can bootstrap
  on the existing fabricated corpus (`battlefield-en-scale-v1`) with the honest caveat that grep
  succeeds there (no accuracy headroom) — the token-efficiency + retrieval-step metrics are still
  valid on it (contamination-robust), which is exactly why token-efficiency leads.
- **NOT the powered accuracy run.** The ~$60-90 certified accuracy run is a founder spend gate (624
  §Step-2). This tempdoc makes whatever run *is* authorized reproducible; it does not authorize spend.
- **NOT engine changes** (encoder-domain fit is 708; fusion is 712/713). A benchmark measures; it does
  not fix.
- **NOT a code-search benchmark.** JustSearch's wedge is messy heterogeneous *documents*, not code
  (native agent grep already wins on code, and the NL encoder is domain-mismatched on code — see the
  encoder-domain-mismatch finding, 678/708). A code benchmark would be fighting on the peers' turf
  where JustSearch is structurally weaker. If a code run is ever done, it is a private *diagnostic*
  ("does it even work on code?"), not a headline benchmark — and not this tempdoc's deliverable.

## First questions for the next agent

1. Is the retrieval-step phase boundary derivable from the existing per-cell `tool_calls` token logs,
   or does the harness need a small capture addition? (Do this first — it is the cheapest honest
   bigger-number.)
2. What is the *minimum* corpus that can ship publicly with the repo (license-clean, small enough to
   clone, contamination-free) so the one-command reproduction works out-of-the-box — 707's pillar-1
   corpus, a subset, or the fabricated `battlefield-en-scale-v1` source (regenerable, already
   committed)?
3. Where does the reproduction command live so an outsider finds it in 30 seconds — a `RESEARCH.md`
   section (667), a top-level `BENCHMARK.md`, a `make benchmark`? (Install-friction is an attention
   variable per the research.)
4. What is the honest headline sentence, and does it lead with token-efficiency (contamination-robust,
   ready) rather than accuracy (gated on 707 + the powered run)?

---

# Takeover investigation (2026-07-13)

## Verdict

**Do the underlying idea, but not this draft and not now.** A public, inspectable agent-utility record
would be useful. The current pilot cannot support that publication, the proposed first deliverable is
not available from its logs, and most of the general release/publication machinery already exists in
623 and 667. Keep 719 only as the eventual agent-utility sibling/projection of those authorities.

The immediate decision is therefore **NO-GO on implementation/publication from the current pilot**.
First correct the upstream loss-accounting projection in the 624/675 harness and recompose the existing
logs at zero model cost. That recompose should mark this pilot non-comparable. A publishable run comes
after the governed record and the 707 corpus exist; 719 should consume those outputs, not compensate for
their absence.

## Load-bearing findings

### 1. The retrieval-step re-slice premise is false for the captured pilot

The raw Inspect log records an ordered `tool_calls` list containing tool name and input, then one terminal
per-cell `unique_tokens` value (`cache_creation_input_tokens`). It does **not** record token counts per tool
call, tool-result token sizes, or cumulative usage checkpoints from which a locating/answering split could
be reconstructed. `agent_utility_inspect._record_cell` confirms that shape: tool calls are projected first,
while usage is copied only from the terminal `ResultMessage`.

Therefore the existing data can identify *which* tools ran and in what order, but cannot allocate context
tokens to a retrieval phase. Even with future capture, "located the right document" needs an explicit
event/criterion; it is not a reliable inference from the first MCP call, especially when an answer-oriented
tool can retrieve and synthesize in one call. The draft's "already record token counts per tool call" claim
and its `$0` first deliverable are withdrawn.

Evidence:

- `scripts/jseval/jseval/agent_utility_inspect.py` (`_record_cell`)
- provisional local pilot log:
  `scripts/jseval/624-pilot-2026-07-12/logs/2026-07-12T03-52-42-00-00_agent-utility-task_7EXqthzWBGRHZeRtxMMXcX.json`

### 2. The current 25.7% record fails its own comparability bar

The machine-readable result's arithmetic is reproducible for the surviving 12 pairs: mean unique tokens
fall from 40,303.9 to 29,932.5, a 25.7% reduction, and the stored bootstrap interval excludes zero.
`jseval utility-status` reproduces the same survivor projection.

But the raw log contains 24 attempted A cells and 24 attempted B cells:

| Arm | Attempted | Successful | Timed out / errored | Exclusion rate |
|---|---:|---:|---:|---:|
| A (file tools) | 24 | 16 | 8 | 33.3% |
| B (file tools + JustSearch) | 24 | 14 | 10 | 41.7% |

Only 12/24 query pairs completed in both arms (paired retention 0.50). The excluded-query Jaccard is
0.50 (6 shared exclusions across a 12-query union). Under `utility_governance.paired_comparability`'s
pre-registered defaults, each arm exceeds the 15% maximum exclusion rate and paired retention is below
0.70. The record must be non-comparable regardless of the survivor CI.

The false green stamp has a concrete cause. Tempdoc 675 consolidated conditions into one Inspect task and
put `condition` on each sample. `eval_logs_to_summaries` was updated for that shape, but
`compute_loss_accounting` still reads `log.eval.metadata.condition`. It therefore returns no arms, and the
empty input vacuously becomes `comparable=true` with empty loss metrics. The provisional record consequently
contains:

- `comparability.comparable: true`
- `comparability.metrics.per_arm_exclusion_rate: {}`
- `comparability.per_arm_loss: {}`

This is an upstream 624/675 measurement-governance defect, not 719 publication work. Do not repair it in
this tempdoc.

Evidence:

- `scripts/jseval/jseval/agent_utility_run.py` (sample-level condition projection)
- `scripts/jseval/jseval/utility_governance.py` (task-level condition lookup and thresholds)
- provisional local record:
  `scripts/jseval/624-pilot-2026-07-12/out/utility-comparison.v1.json`
- open observation PR #169 currently repeats the survivor result and also disagrees with the record on
  baseline accuracy/adoption; it is not an independent authority:
  https://github.com/eliasjustus/justsearch/pull/169

### 3. The provisional artifact is not yet a clean public release input

The pilot directory is untracked. Its Inspect log points to machine-local temporary corpus and MCP-config
paths, reports a dirty working tree, and the composed cohort leaves `mcp_tool_surface_hash` and `hardware`
null. The source commit is real and retained, and the 46 MB procedural
`scripts/jseval/635-corpora/battlefield-en-scale-v1/docs.jsonl` corpus is committed, so recovery is possible;
however, the current log is not a self-contained clean-clone artifact without sanitization and a governed
recomposition.

The final corpus is also not present: 707 is still design-only. Its real-text EN/DE legal+email corpus,
licensing/fetch recipes, injected gold, and certification gates are unimplemented. The existing all-synthetic
scale corpus can support a clearly caveated harness smoke, but it cannot silently substitute for the final
public benchmark substrate.

### 4. "Run one command and get the same numbers" conflates two different promises

A deterministic replay can re-derive a report from frozen raw logs. A fresh live agent run cannot promise
the exact same numbers: the hosted model, CLI/SDK behavior, and agent trajectories change, and the run costs
money. 719 must eventually state those as separate reproducibility levels. The defensible promise is:

1. re-derive the published record exactly from frozen, sanitized raw evidence; and
2. rerun the pinned protocol to test whether the result falls inside a stated replication envelope.

The current north-star sentence promises (2) with the determinism of (1), which is not achievable for a
hosted agent benchmark.

## Competitive-research corrections

The external scan supports public evidence as useful, but not the draft's causal story.

- **Cq is not a methodological comparator.** Its ~225-point Show HN launch is a shared-learning/memory
  system for coding agents, not a retrieval/token benchmark. It is evidence that a clear story can attract
  attention without a token number, but not evidence about benchmark reproducibility:
  https://news.ycombinator.com/item?id=47491466
- **code-review-graph does publish reproducibility material.** Its repository contains an eval runner,
  configs, committed per-repo CSV results, and a documented reproduce path. The 12-point HN post therefore
  does not support "low attention because unverifiable": https://news.ycombinator.com/item?id=47314090 and
  https://github.com/tirth8205/code-review-graph
- **Semble's 98% is correctly a different metric**, but it is also unusually reproducible: 1,251 labeled
  retrieval queries, pinned repos, committed result JSON, executable benchmark commands, and a stated
  `tiktoken` methodology. It is a retrieval-budget simulation, not an end-to-end agent outcome:
  https://github.com/MinishLab/semble/tree/main/benchmarks
- **CodeGraph is the closest public shape, but the draft over-compresses it.** Its current README reports
  median-of-four with/without-agent runs across seven code repositories; the per-repo token reductions vary
  from 23% to 64% (about 47% arithmetic mean, 54% median for the displayed percentages), with accuracy
  represented by cited-answer quality rather than JustSearch's exact-answer gate. It publishes the harness
  and aggregate/per-repo tables, but one documented matrix driver still lived in `/tmp`, so even this is not
  a pristine one-command clone reproduction: https://github.com/colbymchenry/codegraph#benchmark-results

As of this investigation, the repositories also differ greatly in age, product surface, distribution, and
audience. Star growth cannot be attributed to reproducibility from this tiny observational sample. Treat
"reproducibility + narrative + install friction drives attention" as a plausible go-to-market hypothesis,
not a research result.

## Existing authority and displacement

Most of the proposed program already exists:

- **623** owns the metric-family-capable canonical benchmark release and explicitly names a public benchmark
  repo/reproducibility path as an extension.
- **667** owns the public `RESEARCH.md` projection and its honesty discipline.
- **624/675** own the agent-utility runner, raw traces, comparison schema, and run governance.
- **707** owns the final public utility corpus.

What does not yet exist is much smaller: an agent-utility sibling record accepted by 623's authority, plus
the raw-evidence packaging/replay and 667 projection specific to that metric family. That residual justifies
719. A separate top-level release architecture, separate benchmark page authority, new corpus, or new
measurement harness would duplicate and weaken the existing seams.

## Cheapest evidence and ordered unblock

1. **Upstream, zero model cost:** correct consolidated-log loss accounting in 624/675 and recompose this
   exact pilot. Expected result: survivor delta retained, record marked non-comparable with A=33.3%,
   B=41.7%, paired retention=0.50.
2. **Then, zero model cost:** from a clean worktree, verify that a sanitized frozen log plus pinned corpus
   identity can regenerate a byte-stable metrics payload (timestamps/path normalization excepted). This is
   the cheapest evidence that 719's residual replay responsibility is real.
3. **Only after those pass:** consume 707's certified corpus and a 624-authorized governed run. Do not spend
   on a new run merely to rescue the current headline.

Until step 1 is complete, the current 25.7% figure is an internal survivor statistic, not an
"accuracy-gated, CI-reported public benchmark" candidate.

---

# Design theorization (2026-07-13; possibilities, not a settled design)

> This section deliberately explores frames and alternatives before design. It does not reverse the
> takeover NO-GO, choose an artifact shape, or authorize implementation/spend.

## Reframe the object: benchmark program, reproduction package, or executable claim?

The draft assumes the thing to build is a **benchmark**. That word may be doing too much work. A benchmark
normally implies a stable task suite on which multiple systems can be compared over time. The immediate
need is narrower: let an outsider inspect and independently derive **one bounded claim** from its evidence.
Possible names reveal different scopes:

- **Benchmark:** durable comparative task suite; strongest long-term ambition, highest maintenance and
  contamination burden.
- **Reproduction package:** frozen corpus + logs + analyzer for one historical result; honest but does not
  imply future model comparability.
- **Evidence bundle / executable claim:** the claim, cohort identity, raw evidence, acceptance verdict, and
  derivation kept together; potentially the smallest truthful unit.
- **Claim ledger:** accepted and rejected evidence bundles, including the records whose own gates failed;
  strongest honesty narrative, but easiest public surface to misunderstand.

The last two fit the actual gap better than a second release program. A useful broader principle is:

> **Reproducibility is a property of a bounded claim and its evidence chain, not a property conferred on a
> project by having a benchmark command.**

That principle is an instance of 625's asserted-measurement provenance and should not become new generalized
machinery here.

## Four different promises currently collapsed into "one command"

| Promise | What the outsider does | Determinism | Cost / prerequisites | What it establishes |
|---|---|---|---|---|
| **Inspect** | Reads the claim, protocol, cohort, exclusions, and per-query rows | Exact | Browser/text only | The claim is legible and bounded |
| **Replay** | Re-runs composition/statistics over frozen raw evidence | Exact except normalized timestamps/paths | Local Python only; no model/backend | The published arithmetic follows from the evidence |
| **Replicate** | Re-runs the same protocol with the same nominal agent/backend cohort | Stochastic | Credentials, paid model, JustSearch stack, models/hardware | The effect survives a new run within an envelope |
| **Challenge** | Runs the protocol on a new corpus/model/tool | Intentionally different | Highest setup and interpretation cost | Transfer/generalization, not reproduction |

The draft promises **replication** with the exactness of **replay**. A plausible future public surface could
make all four visible while allowing each to fail independently. The important theoretical point is that
the zero-friction primary action should probably be replay, not a paid live run. Requiring Claude credentials,
GPU models, corpus ingestion, and dollars before a skeptic can verify the number conflicts with the same
install-friction thesis the draft cites.

## The causal estimand needs to be explicit before the artifact is designed

The realistic product question is condition B: what happens when JustSearch is **offered in addition to**
the file tools an agent already has? That is an intent-to-treat comparison. It includes whether the agent
notices and chooses the tool; this is appropriate for product utility, but only if adoption is high enough
to plausibly connect the measured effect to JustSearch.

The provisional survivor record reports B adoption of only 8.3% (one of 12 paired B cells made an MCP call),
yet reports a 25.7% mean token reduction. Eleven B cells therefore exhibit any token difference without
using JustSearch at all. Possible explanations include ordinary agent trajectory variance, different
tool-schema context, survivor selection, or indirect steering from the offered surface. None establishes
retrieval utility. This is not a new standard invented by 719: 624's pre-registered tree already says
**"null with low adoption: not a utility finding at all — an adoption finding."** The same logic applies to
a favorable token delta with low adoption.

Several measurement framings remain possible later:

- **Addition-arm intent-to-treat:** the honest product headline, conditional on a pre-registered adoption
  floor and complete loss accounting.
- **Substitution-arm mechanism probe:** can JustSearch replace file exploration when forced? Useful diagnosis,
  but not the normal product comparison and never the unqualified headline.
- **Treatment-on-treated/adopter slice:** useful mechanism description, but selection-biased because the agent
  chooses when to adopt; not a causal headline.
- **Encouragement/steering experiment:** measures whether better tool legibility changes adoption and outcomes;
  valuable for product work, but a different causal question from retrieval quality.

719 should eventually publish whichever estimand 624 certifies rather than infer a favorable mechanism from
the final percentage.

## "Accuracy held equal" is stronger than a non-significant difference

The draft repeatedly describes token savings as accuracy-gated or accuracy held equal. McNemar `p > 0.05`
does not establish equality; it only fails to show a difference at the available power. A credible
token-efficiency claim could take several forms:

- require an explicit paired non-inferiority margin for answer accuracy;
- require both arms to clear an absolute accuracy floor plus report the uncertainty on their difference;
- state the observed accuracy values without claiming equivalence; or
- avoid an efficiency headline when the run cannot exclude a practically meaningful accuracy loss.

Which is right is a 624 measurement decision, not a 719 publication decision. The public artifact must not
upgrade "not significant" into "equal."

## Token efficiency has at least three non-equivalent meanings

The current record's `tokens_unique` is specifically Claude's `cache_creation_input_tokens`: input tokens
written when creating a cache entry. It excludes cache reads and uncached input after the breakpoint, and is
not the same as total processed tokens, billed tokens, context-window occupancy, tool-result bytes, or
retrieval-step tokens. External projects use several of those other denominators. Calling this provider field
"unique-content tokens" is therefore an interpretation that the external research pass below does not support.

Possible public metrics later include:

- **cache-creation input tokens** (current canonical field; a provider cache-write counter, not total input);
- **total processed/billed tokens or dollars** (closer to user cost, provider- and pricing-dependent);
- **tool-result/context bytes** (provider-independent approximation, weaker semantic meaning);
- **tokens until sufficient evidence is first present** (mechanism-focused, requires prospective capture and
  an explicit evidence-sufficiency event); and
- **end-to-end turns/time/tool calls** (often more legible to users, noisy and hardware/service-dependent).

These should not be collapsed into one "tokens saved" sentence. In particular, a future retrieval-phase
metric would be a new prospectively-instrumented metric, not a re-slice of this pilot and not automatically
the primary product claim.

## Public reproducibility and contamination resistance pull in opposite directions

Once the corpus, queries, and gold answers are public, future agent models can train on them. A corpus that
was private-synthetic when measured becomes public-post-cutoff evidence after release. Full publication is
excellent for replaying the historical claim but weakens the corpus as a permanent source of fresh utility
claims.

Possible directions, none selected:

1. **Frozen public verification set:** maximal auditability; use it for replay and regression only, and date
   every claim to the model cutoff/cohort.
2. **Public generator, fresh committed instantiation per campaign:** real-text recipes and injection machinery
   remain public, while fabricated identifiers/facts are generated after the tested model's cutoff. A seed or
   commitment must be fixed before outcomes are seen to prevent corpus shopping.
3. **Public development set + private/embargoed confirmatory set:** strongest continuing contamination control,
   weakest immediate outsider reproducibility; release the confirmatory set after the claim and rotate.
4. **Outsider-supplied challenge corpus:** strongest real-world transfer evidence and least founder control,
   but no longer a one-command clone experience and harder to score automatically.

707 owns the substrate decision. 719 only needs to expose the chosen corpus status and avoid implying that
"public" and "contamination-free forever" are compatible.

## Alternative artifact/publication shapes

### A. Thin projection inside the existing repo

Extend the existing 624 record and 667 `RESEARCH.md` surface, with replay routed through jseval. This has the
least authority duplication and keeps code/data together. Risks: the repository and setup are large, and a
marketing/research reader may not discover the evidence quickly.

### B. Self-contained evidence directory in this repo

Co-locate one accepted record, normalized raw rows/logs, corpus references, and replay instructions under a
clearly named public path. Better discoverability and archival clarity; risks becoming a second release
authority unless every rendered fact is projected from 624's canonical record.

### C. Generated standalone release asset or external benchmark repository

Small clone, strong narrative, citation-friendly. It creates publication/version-sync and provenance risks:
the external artifact must be generated from a pinned source record, never manually copied. This is already
623's named E8 extension and should not be independently invented in 719.

### D. Accepted/rejected claim ledger

Publish not only the winning record but the retracted 92%/62% claim, MCP-invalid A-vs-A run, non-comparable
pilot, and eventual accepted record with machine-readable acceptance reasons. This would make the "our audit
catches our own claims" story concrete. It could also overwhelm adopters, reward failure theater, or let a
rejected figure escape its caveat. A ledger may be more valuable to research-methodology audiences than to
the product landing surface.

The shapes are not mutually exclusive, but combining them prematurely would recreate the program sprawl the
takeover rejected.

## The record has a lifecycle, not a timeless truth value

Agent benchmarks decay faster than retrieval benchmarks. Hosted model aliases move, old CLI/SDK versions
become unavailable, prices change, tool schemas change, and a published corpus crosses into model training
data. A historical record can remain **replayable** while becoming impossible to **replicate literally**.

Potential lifecycle states worth considering later:

- candidate / rejected by gate;
- accepted for a bounded claim;
- superseded by a corrected derivation of the same evidence;
- replicated on a newer cohort;
- stale or non-replicable because dependencies/model are unavailable; and
- retired from headline use while retained as history.

This argues against a mutable "current number" copied into prose. It also suggests that a fresh replication
should usually create a new cohort record rather than overwrite the historical claim.

## Raw evidence is not automatically publishable evidence

"Publish the raw logs" hides several checks:

- machine-local paths, usernames, environment details, and accidental secrets need normalization/audit;
- tool results can reproduce third-party licensed text even when the corpus distribution recipe itself is
  licensing-safe;
- provider terms may constrain redistribution of full model transcripts or generated outputs and should be
  checked before release;
- timeouts, retries, blocked calls, partial traces, and exclusions must remain visible after sanitization;
- normalization must not remove the evidence needed to detect leaks or disallowed tools; and
- checksums must bind the public normalized evidence back to the accepted canonical record.

A compact per-query evidence projection may be safer and easier to audit than publishing opaque megabyte-scale
Inspect logs, but it loses forensic depth. Keeping both raw archival evidence and a normalized public projection
is another possible split, not yet a decision.

## Audience tension: the same artifact cannot optimize every first impression

- **Skeptical adopter:** wants a 30-second explanation, exact caveat, and free replay.
- **Researcher/evaluator:** wants task construction, per-query rows, uncertainty, exclusions, and raw traces.
- **Contributor:** wants the live rerun/challenge command and extension points.
- **Founder/product reader:** wants the single defensible claim and why it matters.

The current draft optimizes for the researcher while arguing from install friction and attention. One possible
resolution is progressive disclosure: the claim and acceptance verdict first, exact replay second, full
methodology/raw evidence third, paid replication last. That is a communication possibility, not a UI design.

## Timing alternatives

1. **Wait for a fully accepted 707/624 record before any 719 build.** Lowest throwaway/misleading risk; delays
   learning whether outsiders can actually replay the artifact.
2. **Run only the zero-cost replay-feasibility spike on a known fixture.** Tests the residual 719 premise without
   presenting the invalid pilot as product evidence; risks accidentally settling format before the real record.
3. **Package the current failed pilot explicitly as a rejected-record case study.** Makes governance tangible;
   high risk that the 25.7% escapes its rejection label.
4. **Publish methodology only, no number.** Establishes rigor and invites collaboration; can read as vaporware
   and duplicates RESEARCH.md unless tightly scoped.

The takeover's default remains option 1 plus the upstream zero-cost recompose. The other paths are recorded
because they trade learning speed against claim-integrity risk differently.

## Broader recurring system shape

719 exposes a three-part shape already latent in 623/624/625:

```text
cohort-bound raw evidence -> canonical measurement + acceptance verdict -> public projections
```

The acceptance verdict is load-bearing. Raw evidence plus correct arithmetic is insufficient when exclusions,
adoption, contamination, or non-inferiority gates reject the interpretation. Conversely, a public paragraph
must never become a fourth authority that silently drops the verdict.

This shape may apply to every externally asserted measurement, but 625 already records that generalization.
719 should instantiate it only for agent utility and resist building a universal claim system.

## Questions the later design must settle

1. Is the public unit a benchmark suite, one accepted evidence bundle, or a ledger of claim attempts?
2. Which action is promised in "one command": exact replay, paid replication, or both under distinct verbs?
3. What adoption floor makes an addition-arm token delta attributable enough to call a utility result?
4. What accuracy non-inferiority/equivalence statement is required before saying tokens fell "at equal accuracy"?
5. Is `cache_creation_input_tokens` the public headline metric, and what plain-language name avoids comparison
   with incompatible external token metrics?
6. How does 707 balance a public frozen reproduction set against fresh contamination-resistant campaigns?
7. Are rejected records public evidence of rigor or internal forensic history?
8. What normalized evidence is sufficient for independent audit without redistributing sensitive/licensed data?
9. Which pieces are projections of 624/623/667, and what—if anything—remains canonical in 719 itself?
10. What expires or supersedes a claim when its hosted model/CLI cohort can no longer be recreated?

# External research pass (2026-07-13; evidence before design)

## Research-worthiness decision

**Warranted.** The unsettled parts are actively changing external systems, not merely local packaging:
agent-evaluation variance, hosted-model versioning, provider token semantics, benchmark contamination, and
artifact-review terminology. A primary-source pass could therefore invalidate a claim or reproduction promise
before design begins. It did. The pass intentionally did not research competitor headline percentages again;
the takeover already showed that those comparisons use incompatible tasks and metrics.

No external code, text, dataset, or asset was copied or adapted. The sources below are used only for factual
summaries and design constraints, so no third-party license or attribution payload is being introduced.

## Findings that materially change 719

### 1. The pilot's token claim is mislabeled

Anthropic defines `cache_creation_input_tokens` as tokens written while creating a cache entry,
`cache_read_input_tokens` as tokens retrieved from cache, and `input_tokens` as input after the cache
breakpoint. Total input is the sum of all three. Tool-definition changes invalidate the tool cache. The pilot's
`tokens_unique` field therefore measures **cache-creation input tokens**, not total context, total processed
input, billed tokens, or a provider-neutral notion of unique content. Because condition B adds an MCP tool
surface, the cache-write delta can also include a mechanical tool-definition/cache effect.

This does not prove the observed 25.7% delta is false. It does mean the current plain-language interpretation
is unsupported and cannot become the public headline unchanged. Any later claim needs the exact provider field
name plus the complementary counters, or a separately justified metric owned by 624.

Source: [Anthropic prompt caching documentation](https://platform.claude.com/docs/en/build-with-claude/prompt-caching).

### 2. The recorded model identity is insufficient for literal replication

Anthropic distinguishes pinned model IDs from convenience aliases. Earlier-generation aliases resolve to the
latest dated snapshot for that minor version; the Models API can resolve an alias to an ID. Even for a pinned
ID, Anthropic says serving infrastructure such as routing, safety classifiers, and sampling logic can change
and produce observable differences.

The pilot records agent model `haiku` and leaves `model_version` null. That does not identify the exact model
snapshot that produced the result. A future run can still test a current cohort, but it cannot honestly claim
literal same-model replication from this record. Capturing the resolved model ID is necessary; it is not by
itself sufficient to promise identical outputs.

Sources: [Anthropic model IDs and versioning](https://platform.claude.com/docs/en/about-claude/models/model-ids-and-versions),
[Anthropic Models API](https://platform.claude.com/docs/en/api/beta/models/retrieve).

### 3. Agentic replication needs repeated runs and an explicit envelope

The large-scale study *On Randomness in Agentic Evals* reports meaningful score variation across nominally
identical runs, including at temperature zero, and traces much of it to early trajectory divergence. It
recommends multiple independent runs, variance reporting, and power analysis rather than a single run.
Anthropic separately reports that runtime configuration can move agentic coding-eval scores and argues that
resources and time are part of the evaluated system.

Those results are not directly transferable as JustSearch sample-size constants: their tasks, outcome, and
variance structure differ. They do support the qualitative conclusion that the pilot's one seed, null hardware
field, and incomplete runtime identity cannot support a strong live-replication promise. Exact frozen-evidence
recomposition and stochastic rerunning need different verbs, success conditions, and evidence.

Sources: [On Randomness in Agentic Evals](https://arxiv.org/abs/2602.07150),
[Anthropic on infrastructure noise in agentic coding evals](https://www.anthropic.com/engineering/infrastructure-noise).

### 4. The product verbs need a standards crosswalk

ACM uses **repeatability** for the same team and same setup, **reproducibility** for a different team using the
same setup/artifacts, and **replicability** for a different team using independently developed artifacts. Its
artifact badges separately distinguish artifact availability/functionality from results independently
reproduced or replicated, and its availability badge expects a permanent archival repository rather than a
personal page.

The theorization's Inspect/Replay/Replicate/Challenge ladder remains useful as product language, but it must
not silently borrow ACM meanings. An author-provided one-command recomputation over frozen logs establishes
inspectability and repeatability of the derivation. It does not by itself establish that an independent team
has reproduced the result, and a Git branch alone is not the strongest archival claim.

Source: [ACM artifact review and badging policy](https://www.acm.org/publications/policies/artifact-review-and-badging-current).

### 5. Native Inspect logs are useful evidence, but not automatically a public artifact

Inspect's `.eval` format is compressed, incremental, and substantially smaller than JSON. The log contains the
evaluation plan, results, statistics, samples, scores, and status, and Inspect exposes APIs for reading,
rescoring, editing, and retrying logs while retaining provenance. However, full raw model API traffic is not
logged by default: Inspect records only the first few calls plus errors unless full API logging is enabled.

This supports retaining native `.eval` evidence for forensic depth and generating a smaller public projection,
but does not settle that packaging choice. It also means a future "raw logs contain every API exchange" claim
would require prospective full logging and a publication audit; it cannot be assumed from standard Inspect
logs or recovered after the fact.

Source: [Inspect AI evaluation logs](https://inspect.aisi.org.uk/eval-logs.html).

### 6. Public release proves a historical claim, not permanent freshness

LiveBench was created around the problem that public benchmark questions can enter future training data. Its
response is an objective-scoring benchmark whose questions and results are published while the question set is
continually refreshed. The exact cadence is not the reusable point; the separation between auditable historical
releases and fresh measurement is.

This validates the theorized tension. A frozen 707 corpus can be excellent evidence for the dated cohort and
poor evidence for an evergreen "unseen by models" claim. The refresh/embargo/generator choice remains with 707,
but 719 must expose corpus vintage and contamination status instead of implying that public release stays fresh.

Source: [LiveBench paper](https://arxiv.org/abs/2406.19314).

### 7. Provider ownership does not clear the whole log for publication

Anthropic's current Commercial Terms say the customer retains input rights and owns outputs, subject to
applicable law and compliance with the terms. They also place responsibility on the customer to evaluate output
before using or sharing it and require the customer to possess rights and permissions for inputs. This removes
the broad concern that Anthropic necessarily owns every generated transcript; it does not grant rights in
third-party source text embedded in prompts/tool results, waive privacy obligations, or make secrets and local
paths safe to publish.

Therefore 719 needs a content/license/privacy sanitization decision before distributing raw traces. This is a
project constraint, not legal advice, and the governing account/terms should be rechecked at release time.

Source: [Anthropic Commercial Terms of Service](https://www.anthropic.com/legal/commercial-terms).

## Net effect on the current verdict

The research strengthens the takeover's **NO-GO as currently framed** verdict:

- the 25.7% pilot result is non-comparable, low-adoption evidence **and** its metric is currently overstated;
- `haiku` plus null `model_version` cannot identify the original model snapshot;
- one-command frozen replay cannot be advertised as independent reproduction or stochastic replication;
- a credible live rerun needs multiple seeds and a bounded environment, not a promise of exact output; and
- public raw evidence needs prospective capture plus sanitization, not a bulk copy of local logs.

It also narrows the positive thesis. The strongest residual role for 719 is still a public projection of an
accepted 624 record: make the claim inspectable, make its derivation cheaply repeatable, bind it to durable
evidence, and provide a separately labeled live rerun/challenge path. Whether that becomes a directory, release
asset, or external repository remains design work and is not selected here.

## Questions the research did not settle

1. The accuracy non-inferiority margin and power target; these depend on 624's estimand and observed variance.
2. The adoption floor that permits an addition-arm delta to be described as JustSearch utility.
3. 707's refresh, embargo, or generated-corpus policy.
4. Whether rejected attempts belong in the public claim ledger or only in internal forensic history.
5. The artifact home and archival mechanism; ACM's policy matters only if that level of claim is pursued.
6. The exact public evidence projection that preserves falsifiability without redistributing unsafe content.

# Settled design (2026-07-13)

## Design decision

719 is **not a benchmark program and not a second measurement authority**. It is the publication boundary for
one accepted agent-utility finding:

```text
624 raw run + comparison record + scientific verdict
                         |
                         v
719 sanitized replay evidence + immutable publication manifest
                         |
                         v
generated agent-utility methodology / RESEARCH.md / README projections
```

The existing `utility-comparison.v1` record remains the canonical measurement record. It is already designed
as the condition-paired sibling of 623's retrieval `release.v1.json`; it must not be nested into that retrieval
release or copied into another result schema. The optional `release.v1.json.utility` section is a regression-
floor projection and is not a source for a public agent-utility claim.

719 adds only the missing outward-facing structure: select an accepted record, bind it to sufficient public
evidence, prove its derivation can be repeated without credentials, and generate every current public claim
from that selected record. This realizes 624's deferred public-claim-projector design now that `RESEARCH.md`
exists, without duplicating it.

## Hard prerequisite: 624 must emit interpretation, not just arithmetic

`comparability.comparable=true` is necessary and insufficient for publication. It covers loss accounting; it
does not decide adoption sufficiency, accuracy non-inferiority, corpus certification, tool-surface validity,
statistical adequacy, or which branch of 624's pre-registered interpretation tree applies.

Before 719 can promote anything, the 624 authority must provide a **machine-readable claim verdict** alongside
the comparison record. The exact encoding belongs to 624, but the semantic contract is fixed here:

- outcome-neutral state: accepted or rejected, with machine-readable reasons;
- accepted claim class: benefit, harm, null, adoption-only, or another pre-registered class;
- the exact arm and strata the verdict covers;
- the credibility-bar checks and thresholds that produced it; and
- the wording constraints the public projection must preserve.

An accepted verdict does not mean a favorable result. A credible null or harm result can be accepted; low
adoption can yield an accepted adoption finding but not be projected as retrieval utility. 719 validates and
publishes the upstream verdict; it never upgrades, repairs, or reinterprets it.

The upstream record also needs the exact resolved agent model ID, corrected provider token semantics/counters,
complete tool-surface and loss accounting, clean source identity, and certified corpus identity. The current
`haiku`/null-version pilot and the schema description that calls cache writes "unique-content tokens" do not
meet that input contract.

## Design invariants forced by de-risking

The evidence spike found that these are prerequisites of the settled design, not later implementation details:

1. **Identity is captured when the run happens.** The source Git SHA, real corpus content/recipe hash, resolved
   provider model ID/version, search-config key, MCP tool-surface hash, and relevant runtime/hardware identity
   must be persisted in the governed run evidence. Replay must never substitute the reproducer's checkout SHA,
   infer a corpus identity from a dataset slug, or accept manually supplied identity as authority.
2. **Resolved model identity participates in equivalence.** Model version is part of pairing/cohort identity,
   not display metadata. Cells from two provider snapshots must not pair or aggregate under the same alias.
3. **Every attempt survives into validity accounting.** Successful summaries alone are insufficient. Errors,
   timeouts, retries, exclusions, and their arm/corpus linkage remain available to both composition and the
   scientific verdict; otherwise a failed arm can become falsely comparable.
4. **Offline recomposition is pure and shared.** One composition seam accepts one or more completed Inspect-log
   corpora, performs no model/backend work, preserves source identity, and emits the canonical record. Status,
   live-run finalization, judge recomposition, cross-corpus composition, and 719 replay use that seam rather than
   separate near-equivalent paths.
5. **Scientific interpretation remains upstream.** 719 can validate and project only 624's outcome-neutral
   machine verdict. It must not derive acceptance, adoption sufficiency, or accuracy language from a green
   comparability boolean.
6. **Publication drift is a required-CI failure.** The purpose-built projector/check covers every generated
   target, including root `RESEARCH.md`, and runs in the required public-claims lane rather than relying on an
   optional docs workflow or a narrower outward-number scan.

## The evidence release has three layers

### 1. Canonical fact: the accepted 624 comparison record

The public release points to the exact accepted `utility-comparison.v1` (and any required per-corpus sibling
records) by content hash. Metrics, uncertainty, coverage, exclusions, adoption, and claim classification are
read from these records. The publication layer contains no independently typed copy of those numbers.

### 2. Public replay input: a sanitized, lossless-for-the-claim observation projection

719 generates a normalized per-cell evidence set from the governed Inspect/run artifacts. It includes all
attempted cells, not only survivors, and retains every field needed to recompute both the measurement and its
acceptance checks:

- corpus, query, seed, arm, and cohort linkage;
- success/error/timeout/retry and exclusion state;
- scored outcome and judge provenance;
- exact provider usage counters, cost, turns, and adoption/tool-call assertions; and
- offered-tool-surface, disallowed-tool, leak, and contamination evidence used by the verdict.

The projection removes or normalizes secrets, usernames, machine-local paths, and third-party text not needed
for falsification. Sanitization is itself versioned and reported. Native Inspect logs may be included as an
optional forensic annex only when a content/license/privacy audit passes; v1 does not require bulk publication
of opaque raw logs. The public projection is sufficient only if deleting a field cannot hide a failed gate or
change the recomposed record.

This is deliberately **not** a serialization of `eval_logs_to_summaries`: that projection drops errored cells
before composition and reconstructs loss accounting separately. The public evidence shape is a typed allowlist
over all attempted cells. Redaction-invariance tests must prove that sanitization preserves the canonical
arithmetic, exclusions, failed gates, leak/disallowed-tool evidence, and final claim verdict.

### 3. Publication manifest: identity and transport, never another result authority

Each publication is an immutable, claim/cohort-keyed bundle with a generated manifest that binds:

- canonical record path and hash;
- normalized evidence paths and hashes;
- corpus recipe/signature and certification references;
- resolved model, CLI/harness, prompt, tool surface, source commit, and environment identity;
- sanitization/audit result;
- deterministic replay command and expected semantic digest; and
- lifecycle state and any superseded publication.

The manifest may select the current accepted release, but it does not contain hand-authored metrics or claim
prose. A small current pointer can choose which immutable publication feeds public docs. Selection is explicit;
facts remain in the 624 record.

All bound identity comes from source-time run evidence and the accepted record. The manifest generator fails on
missing identity; it cannot repair provenance from the current checkout, a replay-time CLI argument, a provider
alias, or a dataset path.

## Two commands, two contracts

Both public contracts depend on one upstream 624 composition seam. Before 719 exists, 624 must add a pure
offline recompose operation for one or more completed Inspect-log directories that can write the canonical
record without judging, credentials, a backend, or a second corpus. It replaces the current command split in
which `utility-status` can read one corpus but not write, the Inspect writer requires cross-corpus input, and a
single-corpus write is coupled to live judge work. Existing status/live/judge/cross-corpus commands become
consumers of the shared seam; they remain useful interfaces, not independent composition authorities.

### Deterministic replay: the primary public action

The zero-cost clean-clone command verifies bundle hashes, recomposes the comparison record and claim verdict
from the public observations, and checks semantic equality with the committed canonical record. Declared
volatile fields such as compose timestamps and normalized paths are excluded from the equality digest rather
than silently tolerated. It requires no model credentials and no running JustSearch backend.

This is the promise 719 puts first: **repeat the published derivation exactly**. In ACM terminology it is
artifact repeatability/inspectability until an independent team actually exercises it; the public product verb
may remain "replay" as long as the standards crosswalk is explicit.

### Live rerun: a separate, paid stochastic operation

The existing 624 `utility-run` path remains the protocol rerun. It requires the certified corpus materialized,
the JustSearch stack/models, a pinned agent/model cohort, credentials, and spend. It always writes a new
candidate record. It never overwrites the published record and never promises identical outputs; success means
the new cohort is interpreted against its pre-registered uncertainty/replication envelope.

719 documents and links this path but does not wrap, own, or make it the default reproduction action.

## Publication gate: fail closed

A publication manifest and numeric public claim may be generated only when all of these hold:

1. 624's machine verdict accepts the stated claim class and arm.
2. The record is comparable, adequately powered for that class, and accounts for every attempted cell,
   including errors, retries, timeouts, and exclusions in the correct arm/corpus.
3. Adoption and offered-tool evidence support the interpretation; an adoption-only verdict cannot render as
   a utility win.
4. Accuracy language matches the actual non-inferiority/equivalence or descriptive result; `p > 0.05` never
   renders as "equal accuracy."
5. Token labels match provider semantics and expose the counters needed to understand the denominator.
6. Model, harness, prompt, tool surface, commit, corpus, and environment identities are captured at source time
   and complete enough for the bounded claim; resolved model version participates in pairing/cohort identity,
   corpus identity is a content/recipe hash rather than a slug, and the source tree is clean.
7. Corpus certification and contamination/vintage status are present.
8. Evidence sanitization/license/privacy checks pass and retain all gate-relevant negative evidence.
9. The zero-cost replay passes from a clean clone and produces the expected semantic digest.
10. Every public projection passes its drift check.

Missing or rejected input produces a generated **no accepted result** state, never a partially rendered number.
This permits the publication machinery to exist before a positive result without turning a failed pilot into
marketing material.

## Public surfaces

The release uses the existing documentation hierarchy rather than creating a new top-level benchmark brand:

1. A dedicated `docs/reference/benchmarks/agent-utility.md` explains the paired-agent methodology, claim
   classes, metric semantics, limitations, evidence layout, replay command, and separately labeled live rerun.
   Its current-result block is generated.
2. `RESEARCH.md` keeps its explanatory research narrative but replaces hand-maintained present-state/result
   assertions with a short generated projection from the current publication.
3. README's current "benchmark in development" sentence becomes a generated status/claim teaser that links to
   the reference page. It emits no number when no release is accepted.

One purpose-built projector and one CI drift check should update/check all three surfaces, following the
existing `gen-public-benchmark.mjs` pattern. Do not add a generalized measurement-claims framework, a second
`RESEARCH.md`, a top-level `BENCHMARK.md`, or a standalone repository in v1. A DOI archive or external mirror
is a later distribution choice if the project actually pursues an ACM-style availability claim.

The check must be invoked directly by the required `.github/workflows/ci.yml` public-claims lane and name all
three targets explicitly. Extending only `check-release-baseline-sync.mjs`, the manually dispatched docs-lint
workflow, or the current outward-number scan is insufficient because none presently guarantees root
`RESEARCH.md` projection drift will fail required CI.

## Lifecycle and correction

- **Candidate/rejected:** retained as measurement history; never selected by the public pointer.
- **Accepted/current:** immutable bundle selected by the pointer and projected into docs.
- **Superseded/withdrawn:** retained with the reason and successor/tombstone; public docs stop asserting its
  number immediately.
- **Replayable but no longer live-rerunnable:** historical derivation remains valid while model/service/corpus
  availability is marked stale.
- **New cohort replication:** a new record and publication, never an in-place refresh of the old claim.

If corrected logic re-derives the same evidence, 624's existing revision/supersedes mechanism applies to the
record and 719 creates a new publication bundle. Git history alone is not the public lifecycle model.

## Sequencing

1. **Fix the known false green:** repair 624/675 consolidated-log loss accounting and add a regression test with
   the real single-pool log shape. Recompose the current pilot as an explicit rejected fixture; no model spend
   and no publication.
2. **Make capture replay-ready:** persist source-time Git, corpus, resolved-model, search-config, MCP-surface,
   runtime/hardware, and corrected token-counter identity. Put resolved model version into pairing/cohort
   identity before another claim run.
3. **Unify offline composition:** add the pure one-or-more-Inspect-log recompose seam and prove that replaying
   the rejected fixture from two different checkout SHAs yields the same semantic record.
4. **Finish scientific authority:** define and emit 624's machine-readable claim verdict from the pre-registered
   tree, including adoption classification, accuracy-language constraint, failed gates, and arm/strata.
5. **Spike sanitized replay first:** export all attempted cells from the rejected fixture, sanitize them, and
   prove from a clean clone that both its arithmetic and expected failed gates reappear. Starting with a failed
   fixture prevents the projection from being validated only on favorable survivors.
6. **Produce an eligible candidate:** build/certify 707, then run the founder-authorized 624 campaign through
   the corrected capture/recompose/verdict path. Only an accepted result can become current public input.
7. **Implement the bounded 719 path:** generate the immutable bundle, dedicated methodology page,
   README/`RESEARCH.md` projections, and required-CI drift/replay checks against the accepted record.
8. **Distribute later if earned:** archive/mirror only after the in-repo path has been exercised by an outsider.

This ordering prevents 719 from designing around an invalid number while still giving the replay substrate a
cheap, outcome-independent test.

## Superseded and displaced material

The settled design explicitly retires these earlier ideas; implementation must not leave them as live guidance:

- The original north star, "run one command and get the same live-agent numbers," is replaced by exact frozen
  replay plus a separately labeled stochastic rerun.
- The retrieval-step token re-slice is retired for this pilot and is not a 719 deliverable. Prospective phase
  instrumentation would be a new 624 measurement question.
- The 25.7% survivor statistic, the term "unique-content tokens," and the claim "accuracy held equal" are not
  publication inputs.
- The all-synthetic scale corpus may be a replay/test fixture; it cannot bootstrap the public utility claim in
  place of certified 707 evidence.
- Publishing all native logs by default is retired in favor of sufficient normalized evidence plus an optional
  audited forensic annex.
- Summary-only `eval_logs_to_summaries` output is retired as a public replay substrate; it may remain a status
  view, but the lossless observation projection and shared composer are authoritative for replay.
- Replay-time `_git_sha_full()`, manually supplied search identity, dataset-slug corpus "signatures," and
  alias-only model pairing are retired as identity sources for claim-bearing records.
- The accepted/rejected public claim ledger is deferred; rejected runs remain inspectable history without a
  first-class promotional surface.
- 624's deferred purpose-built public projector is **transferred to and fulfilled by 719**, not independently
  implemented in both tempdocs.
- Hand-maintained current-state/result prose in README and `RESEARCH.md` is orphaned once the projector lands;
  the implementation must replace those regions with generated markers or remove them in the same change.

## Scope judgment

This is larger than a docs edit because the real promise requires a public evidence input, integrity binding,
sanitization, deterministic recomposition, and a fail-closed promotion rule. It is smaller than the original
benchmark proposal because it reuses the 624 runner/record, 707 corpus, 623 canonical-record discipline, 667
public surface, and existing docs projector pattern. It creates no runner, corpus, scoring method, general
claims registry, or external release program.

The loss fix, source-time identity, resolved-model equivalence, shared offline composer, and scientific verdict
are measurement-substrate work owned by 624/675 (or by an explicit ownership transfer recorded there). They are
hard prerequisites discovered by 719, not permission to hide measurement repairs inside the bundle generator.
719 owns the typed public observation projection, sanitizer invariance, manifest, replay verifier, projector,
and required public-CI enforcement once those inputs exist.

Implementation remains **NO-GO until the upstream accepted-record contract exists**. The normalized-evidence
replay spike may proceed earlier against a clearly rejected fixture because it cannot publish a claim and tests
the only genuinely new technical premise.

## Design reach

### Existing principle this conforms to

719 is another instance of the established **canonical authority + governed projections** seam:

- canonical authority: 624's comparison record and scientific verdict;
- evidence projection: the sanitized observations sufficient to falsify/recompose it;
- public projections: methodology, `RESEARCH.md`, and README; and
- gate: publication/replay/drift checks that prevent a rejected or stale record from escaping.

This is conformance to 623/625, not a new generalized claims system.

### Source-provenance invariant exposed by the replay design

**Run identity is an event property, not a replay parameter.** A historical measurement must carry the
identity that existed when it was produced; a later projector may verify or normalize that identity but cannot
replace it with facts about the observer's checkout. The candidate scope is every persisted jseval result that
can be recomposed later, especially hosted-model, corpus-derived, or tool-surface-sensitive evaluations.

**Evidence that this earns its keep:** the same frozen run recomposes to one semantic digest from different
checkouts, while two runs made against different resolved model snapshots or corpus contents cannot silently
pair. **Retirement condition:** remove 719-specific identity enforcement when a shared 625/measurement-record
contract captures and enforces the same source-time fields across jseval; migrate to that authority rather than
keeping parallel checks.

### New recurring distinction worth naming

**A stochastic evaluation has two independent reproducibility contracts: derivation replay and protocol
rerun.** The first can be deterministic, free, and indefinitely preserved from frozen evidence. The second is
stochastic, environment-dependent, potentially expensive, and may become impossible when hosted dependencies
retire. A public artifact should state which contract each command and badge satisfies.

Candidate scope: other hosted-agent evaluations, LLM-judge studies, and any externally asserted result whose
raw run cannot be expected to reproduce byte-identically. Existing violations include this tempdoc's original
one-command promise and the broad `RESEARCH.md` sentence saying everything above is runnable from a clone even
though the deferred agent-utility campaign and 707 corpus are not yet reproducible public artifacts.

**Evidence that the distinction earns its keep:** an outsider can verify a published number without credentials;
a fresh rerun can disagree without being mislabeled a broken replay; CI catches public-claim drift; and a retired
model changes live-rerun status without invalidating the historical derivation.

**Retirement condition:** remove the bespoke 719 publication layer if no agent-utility record is ever accepted,
or migrate/delete it if 623 later grows a native sibling-record publication mechanism that provides the same
evidence binding, replay, lifecycle, and projection guarantees. Do not preserve parallel machinery merely
because this tempdoc named the distinction.

## Plain-language design summary

**Actual design:** wait for 624 to produce an accepted, properly identified result on 707. Then publish an
immutable evidence bundle that lets anyone recompute that result for free. Keep the paid live-agent rerun as a
different command with a stochastic success criterion. Generate the methodology, research page, and README
status from the accepted record so a rejected or corrected number cannot drift into public prose.

**Reach:** this follows the repository's existing single-authority/projection pattern. The broader lessons are
that run identity belongs to the original event, and hosted-agent evaluations need separate promises for
replaying old evidence and rerunning the live protocol. Those rules may help other evals later, but 719 should
build only the agent-utility instance and disappear if shared 623/625 machinery eventually subsumes it.

# Confidence-building pass (2026-07-13; no feature implementation)

## Confidence verdict

**Implementation confidence: 5/10.** The publication architecture is sound and fits existing projector
patterns, but the assumed replay-ready 624 substrate does not exist yet. The remaining uncertainty is not
primarily UI/docs work; it is source provenance and scientific classification at the measurement boundary.

The design remains correct, but "wait for an accepted record, then package it" is incomplete. The accepted
record must first become replay-ready through explicit upstream work. Treating that as incidental 719 glue
would reproduce the authority leak this design is meant to prevent.

## What was checked

### 1. Can current Inspect evidence support a lossless public observation projection? — partially

The raw Inspect JSON is rich enough to make this feasible. The 2026-07-12 pilot log is 1.36 MB for 48 attempted
cells and retains all 18 errored cells, condition, retry/error state, ordered tool calls, blocked calls, offered
MCP surface, completions, and the usage/cost/turn counters for the 30 successful cells. This is enough raw
material to export all attempts rather than only survivors.

But `eval_logs_to_summaries` deliberately drops errored samples before composition. Loss accounting is rebuilt
through a separate `compute_loss_accounting` pass. A public replay input therefore cannot be a dump of the
current compose summaries; it needs a first-class observation projection that preserves both successful and
failed cells. It also needs tested redaction of staged corpus paths, Claude tool-result paths, and tool arguments
without removing evidence used by leak/disallowed-tool checks.

**Result:** feasible, but a new typed projection/sanitizer is real implementation work, not packaging.

### 2. Can a committed single-corpus Inspect log be recomposed deterministically today? — no

The arithmetic itself is deterministic: paired observations are sorted, McNemar is deterministic, and the
bootstrap uses RNG seed 42. The command surface is the blocker:

- `utility-status` reads a single log and recomputes an emerging result, but cannot write a canonical record;
- `utility-compose` writes a record, but accepts only the retired legacy-run JSON shape;
- `utility-compose-cross-corpus` writes from Inspect logs, but correctly requires two distinct corpora; and
- `utility-judge --output-dir` can recompose a single Inspect log only while performing judge work against a
  live model endpoint.

The zero-cost experiment passed the same committed scale log twice to `utility-compose-cross-corpus`; the CLI
refused it with `cross-corpus composition needs 2+ distinct corpora`. That is correct behavior and proves the
missing seam: no pure single-log Inspect recompose command exists.

More importantly, `eval_logs_to_summaries` calls `_git_sha_full()` at replay time instead of reading a source
SHA from the log. Replaying on another checkout therefore changes `git_sha` and `agent_cohort_key`. The search
config key is also supplied manually to status/compose rather than recovered from the evidence. Exact semantic
replay is impossible until source-time identity is persisted and replay consumes it.

**Result:** deterministic statistics are ready; deterministic provenance/recomposition is not.

### 3. Are current records sufficiently identified for publication? — no

The inspected 2026-07-03 and 2026-07-08 committed records do carry source SHA, CLI version, prompt hash, judge,
and sometimes the MCP surface hash. They do not carry a resolved agent model version, corpus content signature,
or hardware. The 2026-07-12 pilot additionally has null MCP surface hash.

The gap is deeper than missing output fields:

- Inspect task metadata records `model: "haiku"`, not a resolved provider model ID.
- `build_agent_manifest` has `agent_model_version`, but the Inspect path does not populate it.
- `agent_cohort_key` does not hash model/version, and `pairing_key` uses the alias-level `agent_model` only.
  Different resolved snapshots could therefore pair or aggregate under the same visible model label.
- Inspect metadata accepts `corpus_signature`, but current logs use the dataset slug as the signature.
- `compose_utility` strips the corpus signature and model version from the top-level canonical record.
- the logs do not store their source Git SHA, so a later recompose cannot recover it honestly.

**Result:** capture and identity semantics must be corrected at run time; 719 cannot reconstruct them later.

### 4. Is there a machine-readable scientific acceptance verdict to project? — no

`utility-comparison.v1` carries metrics, coverage, comparability, and tool assertions. No schema/code surface
contains `claim_verdict`, publication acceptance, outcome class, adoption floor, accuracy non-inferiority
decision, or the full 624 credibility-bar result. `utility_gate` is intentionally a condition-C regression
tripwire for synthetic smoke data, not a publication/estimation gate.

The live 2026-07-12 check demonstrates why this matters: `utility-status` currently prints
`comparable(so far)=True` with empty loss metrics because consolidated logs moved `condition` to sample metadata
while `compute_loss_accounting` still reads task metadata. A green comparability boolean cannot be treated as a
claim verdict even after that bug is fixed.

**Result:** 624 needs a machine verdict and unresolved policy choices (notably adoption and accuracy margins)
must be settled before 719 can generate outcome text safely.

### 5. Does the docs/CI pattern support a fail-closed projector? — yes, with one correction

The existing release projection machinery is a strong fit:

- `gen-public-benchmark.mjs --check` is marker-bounded and fails on missing required targets or drift;
- `check-release-baseline-sync.mjs` composes schema and projection checks; and
- all current release projection/number checks passed locally.

However, `check-release-baseline-sync.mjs` is not invoked by required `ci.yml`, and `docs-lint.yml` is manual
(`workflow_dispatch`) only. The required `public-claims` lane instead runs narrower README/outward-number guards.
`check-outward-number-citations.mjs` currently scans root README plus `docs/business/**`; it reported one outward
file and does not scan root `RESEARCH.md`.

**Result:** the projector design is low-risk, but its drift check must be wired directly into required public
CI and must explicitly include `RESEARCH.md`; merely copying the existing local-check placement is insufficient.

## Verification evidence

- `python -m pytest tests/test_agent_utility_run.py tests/test_utility_comparison.py
  tests/test_utility_revision_cli.py -q` — **87 passed**, 14 expected small/degenerate-sample numerical warnings.
- `python -m jseval utility-status` on the committed scale fixture — all A/B/C cells complete and comparable;
  confirms the read-only single-log path works.
- `python -m jseval utility-status` on the 2026-07-12 consolidated pilot — empty loss accounting and false green
  reproduced exactly.
- `utility-compose-cross-corpus` single-corpus experiment — correctly refused; confirms there is no equivalent
  single-log write path.
- `gen-public-benchmark --check`, release-baseline sync, README-number sync, and outward-number citation checks
  — all passed; the latter exposed its one-file/`RESEARCH.md` coverage boundary.
- `git diff --check` is the final tempdoc verification; no production or test code was changed by this pass.

## Design refinements forced by this pass

These findings are now incorporated into the settled design above; this list remains as the evidence-pass audit
trail:

1. **Fix consolidated loss accounting and add the missing single-pool regression test.** This remains 624/675.
2. **Make source identity capture-complete before another claim run:** source Git SHA, real corpus content hash,
   resolved provider model ID/version, search-config key, MCP tool-surface hash, and relevant hardware/runtime.
   Model version must participate in pairing/cohort identity rather than being display-only.
3. **Add one pure Inspect recompose path** that accepts one or more completed log directories, performs no live
   model/backend work, preserves source identity, and writes the canonical record. `utility-status`, live run,
   judge recompose, cross-corpus compose, and future 719 replay should call the same composition seam.
4. **Define the 624 claim verdict** from the pre-registered tree, including accepted/rejected state, outcome
   class, arm/strata, failed gates, adoption classification, and accuracy-language constraint.
5. **Then spike the public observation projection** on a rejected fixture: export all attempts, sanitize, replay,
   and prove that the false-green pilot recomposes as rejected. Do not start with a favorable fixture.
6. **Only then implement the 719 publication manifest/projector/docs**, with its check in required public CI.

Items 1–4 are measurement-substrate work even though 719 depends on them. They should be implemented under 624
or an explicitly transferred prerequisite, not smuggled into the public bundle generator.

## Risk register

| Risk | Severity | Current evidence | Required reduction |
|---|---|---|---|
| Replay stamps the reproducer's SHA, not the run's SHA | Critical | direct source read | persist/read source identity; regression test across checkout SHA |
| Model alias/version can mix silently | Critical | version absent from Inspect path and pairing key | resolve at run time; include in identity and record |
| Consolidated errors disappear from loss accounting | Critical | reproduced false green on real pilot | sample-level condition fix + real-shape test |
| No machine scientific verdict | Critical | schema/code search; `utility_gate` has different purpose | settle policy and emit outcome-neutral verdict |
| No pure single-log recompose command | High | CLI help + refused experiment | shared offline recompose command/seam |
| Sanitization can hide negative evidence | High | raw tool args/paths are gate-relevant | typed allowlist projection + redaction invariance tests |
| Corpus "signature" is currently a slug | High | real Inspect metadata | hash materialized corpus/recipe and preserve it |
| Public claim check misses `RESEARCH.md` | Medium | local check scanned one outward file | required-CI projector check with explicit targets |
| Projector/docs implementation itself | Low | existing generator/check pattern passes | conform to current marker/check idiom |

## Difficulty and model/effort recommendation

**Overall difficulty: high, because correctness spans identity, statistical interpretation, evidence
sanitization, and public CI.** The final docs projector is straightforward; the upstream replay/claim contract is
not.

- **End-to-end remaining work:** use **Opus, high effort**. Keep one agent responsible for the identity and
  scientific-boundary invariants through tests; splitting those across several low-context implementers would
  increase fork risk.
- **After prerequisites 1–5 are green:** the bounded publication manifest/projector/docs portion is suitable for
  **Sonnet, medium effort**, followed by Opus review of fail-closed behavior and claim wording.
- **Expected shape:** several focused changes rather than one large PR: loss-accounting fix; identity/offline
  recompose; claim verdict; evidence sanitizer/replay spike; then 719 publication surfaces.

Confidence should rise to **7/10** once the real rejected fixture reproduces its loss/verdict and a separate
source-complete fixture produces the same semantic record under two checkout SHAs. It should reach **8/10** only
after sanitized evidence reproduces the rejected fixture's arithmetic and failed gates from a clean clone. The
old pilot cannot prove identity fields it never captured. Do not begin public-surface implementation at 5/10.

---

# Implementation plan (2026-07-13)

## Planning verdict and completion contract

The design is implementable as one autonomous goal, but “end to end” has two honest completion levels:

1. **Autonomous/code-complete:** current-main reconciliation, corrected measurement substrate, source-complete
   future capture, shared offline recomposition, outcome-neutral verdict machinery, sanitized rejected-fixture
   replay, production 707 corpus/certification, immutable no-result publication state, generated docs, and
   required CI. This work requires no paid agent campaign and can be completed without manufacturing a claim.
2. **Accepted numeric publication:** additionally requires a pre-registered campaign policy, owner-authorized
   spend, credentials and uninterrupted runtime, a smoke that clears its stop gates, a powered campaign that
   returns an accepted verdict, and authority to move the public current pointer. A favorable outcome is never
   a completion condition; accepted harm, null, or adoption-only findings are equally valid outputs.

The implementation goal is complete at level 1 when all machinery is green and the public projection honestly
says **no accepted result**. It reaches level 2 only if the external gates are explicitly supplied and the
result passes the machine policy. A rejected campaign is a successful measurement outcome but does not authorize
a numeric public projection.

## Reconcile live authority before editing code

This worktree is currently diverged from and 11 commits behind `origin/main`. Implementation begins by updating
the worktree to current `origin/main` and resolving this tempdoc without dropping its design/plan. Two newer
facts materially change the starting state:

- PR #160 added `scripts/jseval/experiments/inject_707_probe.py` and proved the injection mechanism on real
  Enron text (dense 0.92–0.95, hybrid 0.89–0.92, R@10 1.0 versus lexical 0.18). That script is explicitly a
  throwaway probe; production EN-legal/CLERC and DE members, provenance/determinism, and certification remain.
- PR #167 excludes errored cells from the legacy result projection. It does **not** fix consolidated Inspect
  loss accounting; `compute_loss_accounting` still reads task-level condition while executor v2 stores it per
  sample.

In the same reconciliation change, update the current-authority folds in the 624 and 707 tempdocs: 702/pillar 5
are cleared, the 707 substrate is ratified and feasibility-proven on email, the unresolved campaign config is
FW-008/current Float32 rather than “640,” and “tier sweep for $60–90” is not a coherent authorized matrix.
Historical sections stay intact; current folds point readers to the corrected authority.

## Target artifact and command surface

Use the existing jseval and marker-bounded docs patterns; do not create a generic claims framework.

```text
scripts/jseval/
  utility-claim-policy.v1.json
  utility-claim-policy.v1.schema.json
  utility-comparison-cross-corpus.v1.schema.json
  agent-utility-observation.v1.schema.json
  agent-utility-publication.v1.schema.json
  agent-utility-publication-pointer.v1.schema.json
  agent-utility-records/<record-id>/
    utility-comparison.v1.json            # immutable canonical 624 record (or cross-corpus sibling)
  public-agent-utility/
    current.v1.json                       # explicit {current: null} before first acceptance
    publications/<publication-id>/
      publication.v1.json                 # hashes/identity/lifecycle only; no copied metrics
      observations.v1.jsonl               # typed allowlist containing every attempted cell
```

The publication manifest points by relative path and byte hash to the exact immutable canonical record under
`agent-utility-records`; it does not retype metrics. It also records the observation
hash, policy/verdict digest, semantic record digest, replay command, sanitizer version, lifecycle state, and
superseded publication where applicable. Existing publication directories are immutable and the builder
refuses overwrite. A missing/malformed pointer is an error; `{current: null}` is the deliberate pre-release
state.

Add these narrow interfaces:

- `jseval utility-recompose --log-dir ... [--log-dir ...] --output-dir ...`: canonical pure offline writer;
- `jseval utility-evidence-export --log-dir ... --out ...`: typed all-attempt sanitizer/exporter;
- `jseval utility-publication-build --record ... --evidence ... --publication-id ...`: fail-closed promotion;
- `jseval utility-publication-select --publication-id ...` (or `--clear --reason ...`): explicit validated
  current-pointer transition, separate from bundle construction;
- `jseval utility-replay --publication ...`: zero-cost integrity, recomposition, verdict, and digest verifier;
- `node scripts/docs/gen-public-agent-utility.mjs [--check]`: one validated input, three target renderers; and
- `node scripts/ci/check-public-agent-utility.mjs`: pointer/schema/hash/replay/projector aggregate check.

Library logic belongs in focused Python modules (observation loading, claim policy/verdict, publication/replay),
with thin Click wrappers in `commands/utility.py`. Adding commands requires regenerating
`jseval/commands/inventory.generated.json`; `test_command_surface.py` remains the command-surface lock.

## Increment 1 — one all-attempt observation seam and correct loss accounting

Do not patch the false green with another independent log walk. Introduce one typed Inspect observation loader
that preserves every sample and becomes the input for both valid-cell summaries and loss accounting.

- Read condition from sample metadata, falling back to task metadata only for legacy multi-task logs.
- Normalize executor-v2 IDs (`A|qid` → `qid`) consistently for success, error, and exclusion sets.
- Count both `sample.metadata.error` and native `sample.error`; retain retry count, first error, timeout, partial
  tool evidence, and excluded query/seed linkage.
- Merge multiple log files per arm rather than overwriting an arm, and reject duplicate cell identities.
- Make `eval_logs_to_summaries` a compatibility/status projection over the observation seam and make
  `compute_loss_accounting` delegate to the same data. It is no longer a replay authority.
- Add a real executor-v2-shaped consolidated fixture with successful and errored A/B samples. Assert non-empty
  per-arm attempted/completed/excluded counts, normalized excluded IDs, Jaccard, paired retention, and rejection.

The local 2026-07-12 pilot remains the decisive acceptance case: 48 attempts, 30 successes, 18 errors; expected
loss is A 8/24 and B 10/24, paired retention 0.50, and the comparison must be rejected. Commit only a sanitized
projection later, never the raw log.

## Increment 2 — capture complete source identity before another run

Correct identity at capture time, then make recomposition refuse to invent it:

- Compute and persist source Git SHA/dirty state before `eval_set`, never through replay-time `_git_sha_full()`.
- Reuse `corpus_identity.corpus_signature` over the materialized corpus/recipe inputs; a dataset slug is a label,
  never a signature. Claim-grade runs fail if the computed hash is absent or disagrees with the declared recipe.
- Pin the live search `config_cohort_key` immediately before the campaign using the existing calibration seam,
  and persist it in task metadata. Status/judge/recompose no longer accept a manual search key as authority.
- Hash the actual MCP `tools/list` surface and persist the offered surface; a caller-supplied hash may be checked
  but cannot substitute for capture.
- Capture environment/runtime/hardware with the existing environment/manifest helpers and Inspect package list.
- Capture `AssistantMessage.model` for every response and cross-check `ResultMessage.model_usage` keys. Require
  one resolved provider model ID per cell and a coherent resolved ID per model group; keep the requested alias
  only as display/context.
- Preserve the raw provider usage counters and rename derived labels to their actual provider semantics. Do not
  continue calling cache-creation input tokens “unique-content tokens.”
- Preserve full judge identity for `hybrid-em-llm`; the current helper drops model/version for every kind except
  literal `llm-judge`.

Preserve axis-relative identity correctly: the harness-only `agent_cohort_key` may continue excluding model as a
range axis, but pairing and measured-cell identity must include corpus content signature plus resolved provider
model ID/version. Composition rejects duplicate or mixed snapshots instead of selecting the first summary.
Persist those identities in the canonical record. Bump the record `schema_version` for the changed semantics;
old records remain immutable and explicitly non-claim-grade rather than being silently reinterpreted.

## Increment 3 — shared pure recomposition and semantic equality

Add one pure finalizer over one or more completed Inspect log directories plus optional already-written judge
overlays. It performs no backend, credential, model, or judge calls. It:

1. loads typed observations and source identity;
2. applies leak flags and judge overlay provenance;
3. derives summaries and loss from the same observations;
4. composes single- or cross-corpus arithmetic through the existing `compose_utility` functions;
5. evaluates the scientific claim policy; and
6. writes the canonical record and its semantic digest.

`utility-run`, `utility-status`, `utility-judge --output-dir`, and `utility-compose-cross-corpus` become thin
consumers. `utility-recompose` is the canonical offline writer and automatically selects the single/cross-corpus
shape from distinct corpus identities. Keep `utility-compose` only for historical legacy JSON; route it through
the shared finalizer where possible and mark its verdict permanently non-claim-grade.

Add a real schema for `utility-comparison-cross-corpus.v1`; today only the single-corpus schema exists. Project
the judge overlay's calibration summary and hash into verdict inputs so replay can verify the judge gate without
calling a judge again.

Define semantic equality as canonical JSON excluding only an explicit closed set of volatile transport fields
(for example `composed_at` and normalized bundle-relative paths). File SHA-256 still protects exact bytes. Never
ignore arbitrary differences to make replay pass.

## Increment 4 — versioned claim policy and outcome-neutral machine verdict

Add a checked-in `utility-claim-policy.v1` rather than burying scientific thresholds in prose or renderer code.
The policy identifies the primary A/B estimand and records at least:

- minimum seeds and paired completed observations;
- comparability/loss thresholds;
- required corpus/tool-surface/leak/contamination/judge-calibration gates;
- significance alpha and uncertainty requirements per metric;
- adoption class thresholds and intention-to-treat/per-protocol constraints;
- accuracy non-inferiority/equivalence margin where token/cost benefit language needs it; and
- per-stratum promotion rules.

The current repo has not settled the adoption threshold or accuracy margin. Implement the schema/evaluator now,
but an incomplete/draft policy must deterministically return `rejected: policy_unresolved`; do not guess these
numbers or infer them after seeing a campaign.

Add the paired accuracy-delta interval needed by the policy; McNemar `p` alone cannot establish equivalence or
non-inferiority. Embed `claim_verdict` in canonical single- and cross-corpus records (optional for historical
records, mandatory for publication), containing accepted/rejected, benefit/harm/null/adoption-only/inconclusive,
arm and strata, policy ID/hash, each gate's observed value/threshold/result/reasons, and renderer wording
constraints. Do not repurpose `utility_gate.py`; it remains the condition-C standing regression tripwire.

## Increment 5 — rejected evidence export and replay proof

Build the new public premise before corpus spend or favorable data exists.

- Export a typed allowlist from the local 48-attempt pilot. Allow fields required for arithmetic, loss,
  identity completeness, judge provenance, provider counters, retries/errors, adoption, offered surface,
  disallowed/leak evidence, and verdict gates. Deny raw messages, completions, events, tool inputs/results,
  absolute paths, usernames, credentials, and third-party text not required for falsification.
- Use `additionalProperties:false` at the observation-cell boundary. Tests prove both sides: denied fields cannot
  change exported bytes, while changing or deleting any gate-relevant negative field changes the digest or
  fails replay.
- Recompose the sanitized pilot as rejected with the expected loss reasons and `identity_incomplete` where the
  original log genuinely omitted source fields.

The pilot cannot prove source-identity preservation because its log never captured source SHA/model version.
Do not “repair” it from the current checkout or provisional record. Add a second, zero-cost source-complete
mocked Inspect fixture to prove that the same evidence recomposes to the same semantic digest under two checkout
SHAs. The pilot proves all-attempt loss/sanitization; the source-complete fixture proves identity preservation.
Together they raise confidence without fabricating provenance.

If the untracked raw pilot disappears before export, stop this milestone and report that exact evidence loss;
a newly synthesized 48-cell shape is not an equivalent substitute for the real false green.

## Increment 6 — productionize and certify the 707 corpus

Start from current `origin/main`'s successful Enron probe, but move the reusable mechanism into the governed
corpus machinery. No ad-hoc eval script becomes production infrastructure.

- Add a real-text-injection corpus type/command that reuses 635 generation, 666 transient-fetch recipes,
  `corpus_build`, `corpus_certify`, and `corpus_identity`; commit recipes and fabricated injections, never
  licensed host text.
- Generalize the proven interleave/descriptor placement, deterministic host selection, same-query 10³/10⁴
  members, and two committed verbosity strata.
- Extend generation provenance and regeneration-determinism to `real-text-injection`, binding upstream source
  revision/checksum, recipe, injection assets, host-selection seed, and output signature.
- Build EN-email, EN-legal/CLERC, and DE (licensing-clean legal source if established; otherwise the already
  authorized MIRACL-de fallback). The 6.7 GB CLERC fetch is a time/network cost, not paid-agent spend.
- Calibrate per member with jseval only. Require descriptor collision zero for gold, closed-book/contamination
  sanity, deterministic rebuild, retrieval in band, grep headroom, chunk completeness, union-recall/leak floors,
  size variability at identical queries, and explicit verbosity/member strata.

Once production tests and commands cover the behavior, delete `experiments/inject_707_probe.py` or replace it
with a short tombstone pointing to the production command; do not maintain two injectors. Correct 707's current
fold from “dense dead/no implementation” to the current probe and production state without rewriting history.

## Increment 7 — immutable publication path and explicit no-result state

Implement observation/publication/pointer schema validation and the publication Python commands. The builder:

- validates the canonical record with stricter publication prerequisites beyond the permissive historical schema;
- refuses missing source identity, corpus hash, resolved model, tool surface, policy, verdict, or negative evidence;
- refuses rejected verdicts and refuses to overwrite an existing publication ID;
- hashes the exact record/evidence files and stores no independent metrics or prose; and
- writes a new immutable manifest, while pointer movement remains a separate explicit action.

The selector is the only writer for `current.v1.json`. It accepts only an existing accepted publication whose
hashes/replay still pass, records the prior selection when superseding, and requires a reason when clearing or
withdrawing. Bundle construction never selects itself implicitly.

Commit `public-agent-utility/current.v1.json` with `current: null` initially. Exercise candidate/rejected,
accepted/current, superseded/withdrawn, replayable-but-not-live-rerunnable, and new-cohort lifecycle tests. A
rejected fixture may be committed under test fixtures/evidence history, never selected as current.

## Increment 8 — generated public surfaces and required CI

Add `docs/reference/benchmarks/agent-utility.md` with authored methodology, metric semantics, limitations,
evidence layout, free replay, and separately labeled paid rerun. Only its current-result block is generated.

`gen-public-agent-utility.mjs` reads and validates the pointer/manifest/record once, then renders three distinct
bounded projections with unique markers:

- the reference page's detailed current-result/no-result block;
- `RESEARCH.md`'s research-state block; and
- README's short status/claim teaser.

Replace README's hand-maintained “benchmark in development” paragraph and the current-state/result portion of
`RESEARCH.md`. Rewrite `RESEARCH.md`'s broad “Everything above is runnable from a clone” promise so retrieval
reproduction and agent-utility replay/live-rerun are stated separately. Do not touch the existing retrieval
table or make `release.v1.json.utility` a claim authority.

Wire `check-public-agent-utility.mjs` directly into `.github/workflows/ci.yml`'s required `Public claims` job.
That job must set up Python and install core jseval so `utility-replay` calls the canonical Python composer;
do not duplicate statistics in JavaScript to keep CI Node-only. Add root `RESEARCH.md` explicitly to
`check-outward-number-citations.mjs` and extend its fixtures. Projector tests cover null/current/withdrawn,
missing markers, drift in any target, and the invariant that no numeric claim renders for `current: null`.

Because this adds canonical reference documentation, finish with the docs-maintenance sequence: regenerate
`docs/llms.txt`, run skills sync if affected, verify canonical links, run markdownlint, and run the public
projector in `--check` mode.

## Increment 9 — owner checkpoint, smoke, powered campaign, and promotion

Before any paid call, record explicit owner decisions in the active policy/campaign config:

1. pin the FW-008/Float32 search cohort choice;
2. define exact EN/DE members, verbosity strata, query count, seed count, and what “n≥100 paired” means;
3. default to haiku-only under the jseval cost policy; Sonnet/Opus require separate explicit authorization;
4. set numeric grep-headroom, adoption, and accuracy-language/non-inferiority thresholds;
5. authorize a capped ~$3 smoke and separately cap the powered run; and
6. delegate or retain final current-pointer/claim-template approval.

Run the smoke through `jseval utility-calibrate`/`utility-run`, not an ad-hoc script. It must have complete
source identity, 100% eligible B-cell surface verification, truthful loss accounting, and pre-registered
headroom/adoption success; otherwise STOP without powered spend. The executor must run uninterrupted because
675 hard-kill resume remains unsupported.

Only after a passing smoke and a second authorization run the powered haiku campaign through the corrected
capture/finalizer/verdict path. The candidate must meet the ratified matrix, ≥5 seeds, clarified n≥100 paired
completed, comparable=true with all attempts accounted, complete uncertainty and certification evidence, and
an outcome-neutral verdict. Any per-stratum public claim clears its own power/uncertainty gate.

If accepted, build the immutable publication, verify it from a clean clone, review the generated wording against
the verdict constraints, and move the current pointer. If rejected, retain it as history and leave the public
no-result state. Never rerun merely to seek a more favorable class.

## Verification matrix

Each increment lands with its focused tests; the final branch runs all of the following before handoff:

```powershell
$env:PYTHONPATH="$PWD\scripts\jseval"
python -m jseval.commands.inventory --write
python -m pytest scripts/jseval/tests/test_agent_utility_inspect.py `
  scripts/jseval/tests/test_agent_utility_run.py `
  scripts/jseval/tests/test_utility_comparison.py `
  scripts/jseval/tests/test_utility_judge.py `
  scripts/jseval/tests/test_utility_revision_cli.py `
  scripts/jseval/tests/test_command_surface.py -q
python -m pytest scripts/jseval/tests -q
python -m jseval utility-recompose --log-dir <rejected-fixture> --output-dir <out-1>
python -m jseval utility-recompose --log-dir <rejected-fixture> --output-dir <out-2>
python -m jseval utility-replay --publication <fixture-or-current-publication>
node scripts/ci/check-outward-number-citations.test.mjs
node scripts/docs/gen-public-agent-utility.mjs --check
node scripts/ci/check-public-agent-utility.mjs
node scripts/docs/verify-canonical-doc-links.mjs
node scripts/docs/llmstxt-generate.mjs --check
git diff --check
```

Also run the existing public-claims checks and validate replay from a genuinely clean clone. The two recomposes
must have identical semantic digests and rejection reasons. Final secret/privacy review searches the committed
observation/publication files for absolute paths, usernames, credential patterns, raw completions, and tool args.
No production claim is selected unless the clean-clone replay and required CI both pass.

## Teardown and migration that ride with the implementation

- Delete duplicate finalization logic from status/live/judge/cross-corpus commands after they call the shared
  finalizer; retain the commands as interfaces.
- Retire replay-time `_git_sha_full()`, replay-supplied search identity, dataset-slug “signatures,” alias-only
  pairing, and summary-only replay. Old records remain immutable and visibly pre-contract.
- Remove `_leak_free_judged_recompose.py` and `_run_judge_with_backend.py` once the supported judge/recompose
  commands cover them; migrate any still-useful invocation notes first.
- Refactor `scale_matrix_report.py` to consume the typed observation seam and delete its private independent
  errored-cell reader.
- Productionize then remove/tombstone the 707 throwaway injector; preserve its evidence in the tempdoc.
- Replace the live README/`RESEARCH.md` prose in the same change that installs their generated markers.
- Keep the all-synthetic scale record only as an explicitly non-public fixture; never use it as 707 evidence.
- Do not create a second benchmark repo, top-level `BENCHMARK.md`, claim ledger, JavaScript statistics path, or
  generalized measurement-claims framework.

## Implementation orchestration

Keep one lead responsible for the observation schema, source identity, pairing, composer, claim policy, and
semantic digest through their tests; these are one correctness boundary and should not be split among
low-context implementers. After that contract is stable, two workstreams can proceed in parallel:

- production 707 corpus/provenance/certification; and
- 719 publication manifest, no-result lifecycle, projector, docs, and CI.

Avoid concurrent edits to `commands/utility.py` and the utility schemas. Integrate each workstream through the
shared contract, then perform one refute-first review of fail-closed behavior before any paid checkpoint. No PR
is created until explicitly requested.

---

# Post-implementation takeover verification (2026-07-13, session 9d3c1869)

A second takeover pass, performed after the implementation fold above, independently re-verified the
branch state rather than trusting the fold's self-report. All checks were run fresh in this worktree.

## Independently reproduced facts

- **Branch state:** `codex/719-agent-utility-publication` at `04bb078`, clean tree, **6 commits ahead
  of and 0 behind `origin/main`** (fully reconciled). 112 files, +17,778/−1,043. Never pushed; no PR;
  hosted CI has not run.
- **Pointer:** `scripts/jseval/public-agent-utility/current.v1.json` is committed with
  `current: null` and a machine-readable reason — the handoff's hard requirement holds.
- **Fail-closed policies:** both `utility-claim-policy.v1.json` and
  `707-corpus-certification-policy.v1.json` are `status: draft` with explicit `unresolved` lists
  (required strata/cells, adoption floor, non-inferiority margin, token/cost margins). Promotion
  deterministically rejects with `policy_unresolved`; no threshold was invented.
- **Rejected-fixture replay:** `jseval utility-recompose --evidence tests/fixtures/agent-utility-rejected-2026-07-12/observations.v1.jsonl`
  reproduced semantic digest `2cea990ce444f6cb…c076ee6`, `comparable=false`, loss A 24/16/8 (33.3%),
  B 24/14/10 (41.7%), `claim_verdict.accepted=false`, `outcome=inconclusive`, reasons including
  `policy_unresolved` and `maximum_exclusion_rate` — byte-for-byte the fold's claim.
- **Tests:** focused suites (`test_agent_utility_inspect`, `test_utility_claim_policy`,
  `test_utility_evidence`, `test_utility_publication`, `test_utility_comparison`,
  `test_command_surface`) — **151 passed, 0 failed**. `compileall` green; command inventory 84.
- **Projections/CI:** `gen-public-agent-utility.mjs --check` and `check-public-agent-utility.mjs`
  green ("no accepted result"); both are wired into the required `Public claims` job in `ci.yml`
  with Python + editable jseval install, as the design demanded.
- **Sanitization spot-check:** the committed `observations.v1.jsonl` contains no usernames,
  drive-letter paths, `/tmp`, or `AppData` strings.
- **Pre-existing failure confirmed:** the 2 full-suite failures (`test_correction_probe`) are caused
  by `jseval/data/correction-eval-queries.v1.json`, absent from `origin/main` since v0.1.0 — not
  719's doing. Logged to the observations inbox (session shard 9d3c1869).
- **No supersession:** tempdoc 720 (RAG generation layer) does not touch agent-utility; nothing
  merged to `main` since reconciliation conflicts with this branch. PR #169 (the shard repeating the
  invalid 25.7% survivor figure) is still open and unmerged, so the rejected number has not escaped.

## Findings for PR review (not blockers)

1. **RESEARCH.md lost its retraction narrative.** The generated block replaced not only the
   drift-prone current-state assertions but also the authored honesty story — the retracted
   "92%/62%" figure and the A-vs-A MCP-config discovery. That story was 667's strongest rigor
   evidence for the research/grant audience and now survives only in internal docs. The design said
   RESEARCH.md "keeps its explanatory research narrative." Recommend restoring a short authored,
   dated retrospective paragraph above the generated marker (pure history, no current-state claims,
   so it cannot drift) — an owner wording call at PR review, not a machinery defect.
2. **One branch instead of several focused changes.** The plan expected "several focused changes
   rather than one large PR"; the branch mixes 624-substrate, 707-corpus, and 719-publication work
   in 6 commits / 112 files. Re-splitting now would cost more than it buys (the seams share schemas
   and tests); recommend one squash PR with a body that names the three workstreams explicitly.

## Verdict

**GO — publish the branch now; everything further is owner-gated.** The level-1
(autonomous/code-complete) contract defined by the plan is implemented and, per the checks above,
real. The cheapest missing evidence for it is hosted CI on the pushed branch — the only verification
tier not yet exercised. Ordered next actions:

1. Push the branch, open the PR (single squash), let required CI run — zero risk of publishing a
   number, because every public surface renders the honest no-result state and CI enforces it.
   Merge only on explicit owner go-ahead.
2. After merge, retry the deferred no-cost backend corpus gates (closed-book, retrieval-calibration,
   union-recall, leak) from a main-based worktree with the shared dev stack — the two prior attempts
   died on the 120-second backend-startup boundary and need that diagnosed first.
3. Everything else — required campaign matrix, adoption floor, accuracy non-inferiority margin,
   model cohort, EnronQA replacement or license resolution, smoke/powered budgets, and any pointer
   movement — is a founder decision the harness correctly refuses to invent. Do not proceed past
   the draft policies without those recorded.

This is not a NO-GO-until-evidence verdict: the evidence the earlier takeover demanded (rejected
pilot recomposes as rejected; sanitized replay reproduces arithmetic and failed gates from committed
evidence) now exists and was independently reproduced here.

## Code-quality review of the implementation (2026-07-13; 4 independent scoped reviewers)

Four scoped reviewers (observation/recompose seam; policy/evidence/publication; corpus machinery;
JS projector + tests) audited *how* the branch is written. No correctness defects; all findings are
maintainability/structure. Summary for PR review and a later `/simplify` pass:

**Genuinely good:** clean acyclic module layering (`agent_utility_observations` stdlib-leaf →
`utility_recompose` → `utility_publication`; `utility_claim_policy` stdlib-leaf); `utility_recompose`
verified pure; real deduplication happened (`agent_utility_run.py` net −98 lines, old parallel
log-walks in `scale_matrix_report`/`compute_loss_accounting` routed through the seam); corpus
determinism has a single RNG seed site and thresholds come only from the checked-in policy; **test
quality is the standout** — exact reason-string assertions, parametrized every-threshold-changes-the-
verdict coverage, path-escape tests, and the real false-green pilot replayed byte-for-byte
(`test_utility_evidence.py:194-217`); no statistics were duplicated into JavaScript.

**Systemic defects (ranked):**

1. **JS re-implements Python's verification rules** — `check-public-agent-utility.mjs:26-96`
   duplicates ~70 lines of manifest-shape/hash/path-confinement validation that
   `utility_publication.replay_publication` already enforces (a second authority that can drift —
   the projection-vs-fork risk applied to validation logic). It also has no companion `.test.mjs`,
   and prints `offline replay failed:\nundefined` when Python is missing.
2. **Generator ergonomics fork the cited idiom** — `gen-public-agent-utility.mjs:317-341` throws a
   raw stack trace on the routine `--check`-drift path, reports only the first failing target, and
   writes targets sequentially with no check-before-write (a crash can leave README updated while
   RESEARCH.md is stale), unlike `gen-public-benchmark.mjs`'s typed per-target status collection.
   Its `.test.mjs` also aborts on first assertion instead of the repo's `ok()` collector idiom.
3. **Schema↔Python allowlist duplication ×3 with no sync test** — `_OBSERVATION_KEYS`
   (`utility_evidence.py:13-21`), `supported_requirements` (`utility_claim_policy.py:173-181`), and
   the pointer ref-triple (`utility_publication.py:57-60`) each hand-mirror their schema; only the
   forward direction is tested, so a drifted allowlist in a fail-closed system is a silent-drift
   class.
4. **Monolithic correctness-critical functions** — `evaluate_claim` 437 lines
   (`utility_claim_policy.py:147-583`), `_intention_to_treat_estimand` 198
   (`utility_recompose.py:72-269`), `certify_materialized_family` 191 and
   `_derive_scientific_verdict` 189 (`corpus_certify.py`), `compose_utility_cross_corpus` 189 —
   ~3× the package's established ~60-line ceiling for dense statistical code, concentrated exactly
   where reviewability matters most. `corpus_certify.py` (172→1,050 lines) mixes ≥6 responsibilities
   and repeats one exact-shape-validation idiom ~6×.
5. **Micro-duplication despite the anti-fork design** — `_WITH_TOOL = {"B","C"}` defined 4×
   (one dead at `agent_utility_run.py:292`, one inline literal); campaign-matrix validator written
   2×; MCP-surface singularity check 3×; canonical-JSON+sha256 recipe 2×
   (`policy_digest` vs `semantic_digest`); `_read_jsonl` re-authored in `corpus_inject.py:18-19`
   despite existing verbatim in `corpus_build.py:31-37`; a second spawn-and-diff mechanism beside
   `corpus_generate.regenerate_and_diff`, whose docstring exists to prevent exactly that.
6. **Inconsistent error vocabulary** — `UtilityComposeError` vs bare `ValueError` for identical
   failure classes in the new seam; structured report-cell failures vs raised `ValueError` in the
   same corpus-certify call graph; the CLI-local rejection-reason literal
   `"legacy_executor_non_claim_grade"` living only in `commands/utility.py:238` outside the policy
   module's vocabulary; `cmd_utility_compose` and `cmd_corpus_certify_member` are logic-bearing
   wrappers.
7. **"Typed observation seam" is untyped** — plain `dict[str, Any]` with ~25 convention-threaded
   string keys across 4+ modules, ignoring the existing `jseval/types.py` dataclass idiom.

**Judgment:** the macro-architecture matches the settled design; the micro-implementation is
correct, heavily tested first-draft code at roughly 1.5–2× necessary volume. Recommended pre/at-PR
fixes (small, targeted): reduce the JS check to orchestration with a clear missing-Python message
(#1), align the generator to collect-all/check-before-write (#2), add the three schema↔constant
sync tests (#3), unify `_WITH_TOOL` + delete the dead definition and share the digest helper (#5,
partial). Defer monolith decomposition (#4) and dataclass typing (#7) to a deliberate post-merge
simplify pass — churning a green 151-test correctness boundary for readability alone is not worth
the risk in the same PR.

### Pre-PR fix pass (2026-07-13; findings #1/#2/#3/#5-partial implemented)

Two implementation agents (disjoint file sets) plus lead review landed the targeted subset:

- **#2** `gen-public-agent-utility.mjs`: `project()` replaced by read-only `evaluate()` returning
  typed `{rel, status}` (`missing`/`no-markers`/`in-sync`/`drift`/`updated`) + two-phase writes —
  any target lacking markers blocks the whole write batch; all targets reported, clean
  `FAIL`/`DRIFT` lines, `process.exitCode=1`, no thrown exceptions on expected paths. Rendered
  content between markers byte-unchanged (committed targets stayed in-sync).
- **#1** `check-public-agent-utility.mjs`: JS now owns only pointer semantics (pointer shape,
  null-state transitions, pointer→manifest confinement/hash/id/lifecycle binding); every
  bundle-internal check was deleted only after mapping it to the covering Python authority:
  full-manifest and nested-section exact-key checks → `_require_exact_keys` calls in
  `utility_publication.replay_publication` (~L169-186; `test_replay_rejects_unknown_manifest_fields`);
  record/observations/policy path confinement → `_confined` (~L190-204;
  `test_replay_rejects_paths_escaping_the_immutable_bundle`); per-file sha256 → ~L205-210
  (`test_replay_detects_evidence_tampering`); policy id/status → the manifest↔stored-verdict
  equality + `policy_digest` byte binding (~L223-229) plus `evaluate_claim`'s `policy_resolved`
  gate forcing `status=="active"` for any accepted verdict; record `semantic_digest`/`accepted` →
  superseded by replay's full recomposition via `finalize_evidence` (~L230-241), which is strictly
  stronger than the deleted field comparison. Missing-Python now fails with a named binary +
  remedy via exported `missingPythonMessage()`; `CHECK_PUBLIC_AGENT_UTILITY_ROOT` env override
  (test-only) added.
- Tests: gen test converted to the repo `ok()` collector idiom + multi-target-drift and
  write-atomicity cases (95 assertions); new `check-public-agent-utility.test.mjs` (22 assertions)
  wired into the required Public claims CI job before the check step.
- **#5** `WITH_TOOL_CONDITIONS` now defined once in `agent_utility_observations.py` and imported by
  `utility_comparison`/`utility_recompose`/`agent_utility_inspect` (a 5th occurrence the review
  missed, found during implementation); dead copy in `agent_utility_run.py` deleted. Canonical-JSON
  digest recipe consolidated into `utility_claim_policy.canonical_bytes/canonical_digest`;
  `semantic_digest` delegates. `corpus_inject` imports `corpus_build.read_jsonl` (implementations
  verified behavior-identical before merging).
- **#3** three schema↔constant sync tests added (observation keys + source keys, hoisted
  `SUPPORTED_REQUIREMENTS`, hoisted `POINTER_REF_KEYS`), each loading its schema from disk and
  asserting exact bidirectional equality; all three pairs matched (no drift existed yet).

Verification on the combined tree: full jseval suite **1753 passed / 2 failed** (the two
pre-existing `test_correction_probe` fixture-missing failures; +3 passes are the new sync tests);
rejected-fixture semantic digest **unchanged** (`2cea990c…`); gen `--check`, both `.test.mjs`
suites, `check-public-agent-utility.mjs`, `check-workflow-triggers`, `gen-public-benchmark
--check`, `git diff --check` all green. Residuals intentionally left: `corpus_inject.
_canonical_digest` is a third digest-recipe site (cross-importing claim-policy into corpus
machinery would couple unrelated domains — fold into a neutral shared helper if one ever exists);
`jseval --help` piped under cp1252 hits the known pre-existing σ/UnicodeEncodeError quirk
(use `PYTHONUTF8=1`; verified present on committed help text before this pass).

### Session handoff (2026-07-13, end of session 9d3c1869)

Branch `codex/719-agent-utility-publication` at the pre-PR-fix commit; worktree clean; **not
pushed, no PR** (per standing instruction: publish only on explicit owner go-ahead).

Next actions, in order:
1. `./gradlew.bat build -x test` on this branch (required pre-merge step; not yet run this
   session — the diff touches no Java, so expected to be a formality, but it is unverified).
2. Push, open one squash PR (body should name the three workstreams: 624/675 measurement
   substrate, 707 corpus materialization, 719 publication boundary), let required CI run. Hosted
   CI has never seen this branch; the Public claims job installs Python 3.13 + editable jseval in
   CI for the first time — the one genuinely untested environment.
3. Owner wording decision before or at PR review: restore a short authored, dated retraction
   narrative (92%/62% retraction; A-vs-A MCP-config catch) above `RESEARCH.md`'s generated marker,
   or accept the drier generated-only block. Pure history, cannot drift; ~10-minute change.
4. Merge only on explicit owner instruction; then the deferred backend corpus gates and the owner
   policy decisions (campaign matrix, adoption floor, accuracy margin, model cohort, email source,
   budgets) proceed per the fold at the top of this tempdoc.

Unverified assumptions at handoff (each becomes verified or falsified by step 1-2 above):
- Hosted CI passes (all local equivalents green; CI environment itself unexercised).
- Gradle build unaffected (no Java in the diff; not yet demonstrated).
- The original branch's claimed independent refute-first review (implementation fold above) is an
  honor-system claim from the prior session; this session's independent verification covered the
  behavioral attack surfaces and a 4-reviewer code-quality audit, not a line-by-line re-review of
  all 17.8k lines.
- The fresh `git clone --no-local` replay was performed by the prior session (fold above); this
  session re-verified replay in-worktree but did not repeat the clean-clone run after the
  code-quality fixes (the fixes do not touch replay arithmetic; digest re-verified in-tree).

Deferred/known issues not owned here: `test_correction_probe` 2-red on main (missing
`correction-eval-queries.v1.json`, logged to observations shard, this session); unrelated
tempdoc-720 number collision between two other worktrees (pre-existing note in the deferred-checks
section above); post-merge simplify candidates (monolith decomposition of `evaluate_claim` /
`corpus_certify.py`, dataclass typing of the observation seam, `corpus_inject._canonical_digest`).

---

# Field defect: evidence-export / recompose divergence (2026-07-28, first real publication)

The publication builder's own fail-closed check caught a real defect on 719's first real-world
use — `build_publication` (`scripts/jseval/jseval/utility_publication.py:102-110`) recomposes the
supplied sanitized evidence and requires
`semantic_digest(recomposed) == semantic_digest(record)`; it raised
*"record does not semantically match the supplied evidence"* for the 782 window-2 hero record.

## Root cause

Two readers of the SAME log directory disagreed on `correct`:

- `finalize_logs` resolved the directory's `judge-overlay.json` and passed it to
  `read_inspect_observations`, which lets the overlay's `final` verdict override the raw
  `substring_scorer` value (`scripts/jseval/jseval/agent_utility_observations.py:107-109`).
- `export_log_dir` called `read_inspect_observations(log_dir)` with **no** overlay
  (`scripts/jseval/jseval/utility_evidence.py:387-388`, pre-fix), so every judge-rescored cell was
  exported with its pre-judge verdict.

Nothing in the sanitized schema was missing or renamed — `correct` was always exported; it just
carried the wrong (pre-judge) value. The two paths therefore agreed cell-for-cell on the error
classification, the exhaustion accounting, cost/tokens and the pair set, and diverged only on
accuracy, which is why the symptom looked like a stratification bug.

Diverging cells on the 782 window-2 evidence (5 of 360):
`en-email-enron-raw-1k-verbose` `B|2|q1`; `en-legal-clerc-1k-verbose` `A|0|q5`, `A|1|q5`,
`A|2|q5`, `B|0|q8`. The three baseline-arm legal cells are the 64-vs-61 A-correct gap
(pooled baseline 0.4354 vs 0.415, delta -0.1156 vs -0.1088).

## Fix (seam)

The overlay resolution is now one authority, `resolve_judge_overlay`, in
`agent_utility_observations.py` next to `read_inspect_observations` — the module that owns "how a
log directory becomes observations". `finalize_logs`, `export_log_dir` and
`partial_status_projection` all call it; `utility_recompose._load_overlay` (the private duplicate)
is deleted, and `utility-evidence-export` gains the same optional `--judge-overlay` that
`utility-recompose --log-dir` has.

The strict allowlist is **unchanged**: no new field crosses the public boundary, because the fix
corrects the value of a field that was already exported. The digest check itself is untouched.

## Proof

Fresh export of the three window-2 log dirs, then `finalize_evidence`, reproduces the composed
record exactly: `semantic_digest == c5a75457b264e0cfdecf5ab1ac552d3430a93300f3241766b9c72a49be1560bb`
(identical to `finalize_logs` over the same dirs). Regression coverage lives in
`scripts/jseval/tests/test_evidence_export_judge_overlay_719.py` (real Inspect `EvalLog` fixture via
`eval_set` + an overlay that both rescues and overturns a cell; asserts per-cell agreement and
export-vs-logs digest equality, and that a directory with no overlay is byte-unchanged).
