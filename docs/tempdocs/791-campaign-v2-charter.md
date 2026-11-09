---
status: "Historical/frozen 2026-11-09 — next agent-utility campaign charter retained for reference; fresh and resumed live campaigns are frozen. Original charter dated 2026-07-28; source evidence: register F-043, 782 §I/§J, tmp/hero-arc-analysis/."
created: 2026-07-28
---

# 791 — Campaign v2 charter: the design axes the hero campaign's evidence dictates

## What v1 (782) established, in campaign-design terms

**Agent campaign freeze (2026-11-09):** Fresh and resumed live agent campaigns are frozen. Campaign commands and runbooks are retained for historical reproduction and offline analysis; published evidence and offline replay remain available. The campaign plans and launch/resume instructions below are historical and do not authorize campaign work. Accepted/adoption-only at sonnet, addition arm, 1k–10k fabricated 2-hop corpora, $0.80/cell:
adoption 1.0; no accuracy benefit; question-level negative NOT significant anywhere; the
mechanism is delivery-shape (hop-1 satisficing + abstention miscalibration), not retrieval;
the baseline arm degrades −5.8 pts under 10× scale while the tool arm is flat; exhaustion
asymmetry penalizes the tool arm under fixed caps (15% of the pooled delta); the tool arm is
25–43% faster in every stratum.

## Design axes for v2 (each with the evidence that put it here)

1. **Substitution arm** (tool-only vs file-tools-only): the unmeasured configuration, and
   the one matching the no-filesystem deployment class (cloud/sandboxed/MCP-only agents)
   where the tool is the only channel. v1's addition arm cannot answer it.
2. **Scale as the primary axis** (1k → 10k → 50k → 100k+, one question set): v1's one
   pro-product mechanism signal (baseline decays, tool flat; pivot deficit vanishing at 10k)
   predicts a crossover — v2 should be designed to FIND or FALSIFY it. Prerequisite: the
   tool arm's harness fragility at 10k (12 vs 4 stream/budget errors) must be fixed and
   smoke-verified before any 50k spend.
3. **Budget design**: fixed per-cell caps bias against the tool arm. Options to decide at
   freeze: compute-normalized arms; a cap high enough that exhaustion is rare in both arms;
   or reporting the accuracy-per-dollar / accuracy-per-minute frontier as a co-primary
   (v1's speed data suggests time-bounded frames where the tool already wins). Phase-1
   telemetry from tempdoc 789 (per-turn receipts) is the enabling instrumentation.
4. **Statistics**: question-level cluster-aware tests as the preregistered PRIMARY (v1's
   cell-level McNemar treats seeds as independent and overstates; the question-level test
   cleared α nowhere). Seeds stay for variance estimation, not for n-inflation. This is a
   policy-v5 item and should be dry-run against a synthetic record before freeze (the
   50-vs-20 lesson: BOTH v1 freeze defects were policy-vs-design incompatibilities reachable
   only at run/compose time).
   **LANDED 2026-07-29 — see "Axis 4 LANDED" below.**
5. **Model-tier axis** (the strategic one): haiku measurably benefited (624 token savings);
   sonnet did not (782). One campaign shape across ≥2 model tiers measures utility as a
   function of agent capability — directly testing the "value shrinks each model generation"
   headwind. Smallest honest version: one stratum × two tiers.
6. **Naturalistic stratum**: at least one natural-question stratum (enron-qa exists,
   real QA pairs) beside the fabricated chains, so behavioral findings (satisficing,
   abstention) are checked off-distribution from the generator that showed an
   offset-placement confound (engine-joins offset analysis: file-tools-arm offset
   correlations = generator artifact). Generator hygiene (independent randomization of gold
   placement) rides here if fabricated strata are regenerated.
7. **Delivery-shape interaction**: if tempdoc 789's Phase-2 probe ships a winning framing
   before v2 freezes, v2 measures WITH it (the product as it will ship); if not, v2 carries
   the framing question as an explicit arm or explicitly excludes it. Do not let the two
   campaigns' scopes silently overlap — 789 owns framing-vs-framing, 791 owns
   tool-vs-baseline.

## Axis 4 LANDED — 2026-07-29 (claim policy v5 + the pre-freeze dry-run)

Dated history, appended post-charter. Axis 4 was the one axis that is free (design-only, no
measured spend), and both of its halves are now shipped and green. **No cell was measured and no
committed record was re-scored.**

### What shipped

**`agent-utility-public-v5`** (`scripts/jseval/utility-claim-policy.v5.json`) — v4 VERBATIM plus
ONE additive requirement, `question_level_primary`, and the two resampling-effort floors it needs
(`minimum_permutation_draws` / `minimum_cluster_bootstrap_draws`, both 20 000). The file is
GENERATED from v4, so "v4 verbatim" is a mechanical property of the artifact rather than a prose
claim, and a test asserts every shared threshold, the required strata, both carried semantics
blocks and the whole changelog tail are byte-identical. v4 is now `status: superseded`.

Under the requirement, `utility_recompose` computes per required stratum
(`jseval/utility_question_level.py`):

- a paired **sign-flip permutation** p over the per-question mean deltas (whole clusters flip;
  `p = (hits + 1) / (draws + 1)`, so it can never report 0), and
- a **question-cluster bootstrap** interval — questions resampled with replacement, each carrying
  its seed replicates — BCa where the bias correction and acceleration are both computable,
  percentile otherwise, with the method NAMED in the record and the percentile interval always
  recorded beside it.

The per-stratum accuracy outcome (harm / non-inferiority / equivalence) reads the CLUSTER
interval. The cell-level exact McNemar keeps its value and its position in the record and is
labelled `descriptive` — demoted, never dropped. Every stratum outcome records which interval it
read (`gates.question_level_primary.observed.accuracy_interval_source`).

Determinism is a gate, not a convention: the RNG seed is `sha256` of record-resident identity
material (stratum id, corpus signature, query-identity digest, expected-cell count, method id),
and the seed, the recipe and the material are all written into the record. A seed that does not
re-derive from its own recorded material FAILS the gate.

**`jseval utility-policy-dryrun --design <cells-file> --policy <policy>`**
(`jseval/utility_policy_dryrun.py`) — the §15.4 pre-freeze check. It reads a
`782-hero-campaign-cells.v1`-shaped design, synthesizes a minimal structurally-valid composed
record with the design's declared shape (strata, seeds, qids, conditions, certification snapshots
loaded from the REAL referenced files, schema census read from the REAL gold files), evaluates
every policy gate against it, and classifies each result:

| category | meaning | blocks |
|---|---|---|
| `structurally-impossible` | a design-derived precondition can never hold, whatever the run produces | yes |
| `undetermined` | a failure the structural analysis cannot explain, or a design fact that could not be read | yes |
| `placeholder` | a gate that PASSED only on a synthesizer stub the design does not pin | no (listed) |

The structural analysis runs **independently** of the synthetic evaluation, and a disagreement
between the two is itself `undetermined` — the dry-run never picks a winner. `placeholder` exists
so "31/31 pass" can never be read as "31 verified": 16 of the hero design's gates pass on stubs
and each one names its stub.

### Both 782 freeze defects, caught for $0 — replayed from the real frozen artifacts

Regression tests (`tests/test_utility_policy_dryrun.py`) run the REAL frozen
`scripts/jseval/782-hero/cells.v1.json` against the REAL superseded policy documents. This is
archaeology, not fiction — the pre-Amendment-1 `known_schemas` is restored from the value v3's own
changelog entry records it was narrowed from.

- **BLOCKER-1** — v3 at freeze: `schema_strata_reported` → `structurally-impossible` on all three
  strata: *"the design's measured 20-query prefix contains question types ['1_hop']; the policy
  requires ['1_hop', '2_hop'] and ['2_hop'] can never appear in a breakdown of this query set."*
  Originally caught pre-launch, but only after the design was frozen.
- **FREEZE DEFECT #2** — v3/v4-pre-subset-gate: `corpus_certification_complete` →
  `structurally-impossible` on all three strata: *"the certification certifies 50 queries and the
  design measures 20; this policy compares the two counts for EXACT equality, so no run under this
  design can pass."* Originally caught at COMPOSE, after ~$278 of measured cells.
- **The catch is real work, not a constant NO:** same design, same certifications, same 50-vs-20
  counts — v3 refuses and v4 passes. And the ACTIVE v5 policy dry-runs **COMPATIBLE (31/31,
  exit 0)** against the frozen hero design, which is axis 4's own pre-freeze check discharged.

### v5 on the real hero record ($0 offline re-evaluation)

Control first (`interrogate-results`): the same log dirs re-composed under the pre-supersede v4
document reproduce the committed `combined-v4` `semantic_digest c5a75457…` **exactly**, so any v5
difference is attributable to the policy and not to harness drift.

**v5 verdict: `accepted` / `adoption-only`, 31/31 gates, `reasons []`** — unchanged, because
`adoption-only` never rested on significance (it is an adoption-rate promotion class). Every
measured number outside `claim_verdict` and the new `question_level` block is byte-identical.
`question_level_primary_reported` is the only gate v5 adds.

| stratum | Δacc | cell-level McNemar p | question-level sign-flip p | cluster CI (BCa) | percentile |
|---|---|---|---|---|---|
| enron-1k | −0.1833 | 0.0633 | **0.1600** | [−0.383, +0.050] | [−0.400, +0.033] |
| enron-10k | −0.1167 | 0.1185 | **0.1523** | [−0.267, −0.017] | [−0.250, 0.000] |
| legal-1k | −0.0167 | 1.0 | **1.0** | [−0.233, +0.133] | [−0.200, +0.150] |

**Honest reading, with the caveats stated rather than smoothed:**

1. The committed cohort is window-2 only, and on it the cell-level p was ALREADY above α on every
   stratum. v5 flips no significance verdict on this record. The headline 0.0446-vs-0.1358
   disagreement is on **raw-EM** scoring of the same window; the record's authoritative scoring
   applies the judge overlay, which moves enron-1k by exactly one cell (1/60). Verified causally,
   not assumed: enron-10k has **zero** judge flips and its record numbers reproduce the
   archaeology's byte-for-byte, while enron-1k (1 flip) and legal-1k (4 flips) differ by exactly
   their flip counts.
2. What survives on either scoring is the *systematic* understatement: the cell-level test roughly
   halves p wherever there is any signal (0.063 vs 0.160; 0.118 vs 0.152).
3. **BCa is not uniformly the wider interval.** Measured across 8 independent RNG streams on all 6
   window×stratum cells, both estimators are Monte-Carlo stable to within one lattice step of the
   discrete cluster statistic (1/60), and BCa SHIFTS the interval rather than narrowing it (widths
   agree). On the 10k strata the BCa upper bound is −0.017 where the percentile bound is 0.000 —
   i.e. BCa excludes zero in the ANTI-product direction. Neither reaches the ±0.1 non-inferiority
   margin, so no per-stratum outcome differs between the two. Both are recorded.

### Consequences for the v2 preregistration

- Axis 4 needs no further design work: the successor to 782 §E freezes against v5 and runs
  `utility-policy-dryrun` as a preflight step BEFORE the freeze commit, not after.
- Seeds are not dropped — `minimum_seeds` (3) and the `SEED_FLOOR` outcome rule are untouched.
  What changes is that seeds stop entering the significance denominator as if independent, which
  is exactly what axis 4 asked for ("Seeds stay for variance estimation, not for n-inflation").
- Power planning for v2 must now be done in QUESTIONS, not cells: the effective n on the hero
  design was 20, not 60. Any v2 stratum sized off the cell-level number is under-powered by
  construction.

## Sequencing constraints (not a plan)

789 Phase 1 (telemetry, $0) strictly before v2 freeze — v2's behavioral metrics come from
it. 790 (extraction fallback) is independent but if it ships, v2's corpora/index cohort
must pin it explicitly (cohort identity, 782's lesson). The frozen-protocol machinery
(cells.v1.json shape, preflight, budget guard, detached-driver operations, gitignored run
dirs, --agent-env pins) carries over from 782 §E/§I as-is; the operational playbook is
proven.

## Founder decision points this charter parks

Total budget and go/no-go; which axes make the cut if budget bounds the design (the
axes above are ranked roughly by information-per-dollar: 2 > 1 > 3/4 (free, design-only)
> 5 > 6); whether any resulting number publishes (623 pipeline, founder-only, unchanged).
