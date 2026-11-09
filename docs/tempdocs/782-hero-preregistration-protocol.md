---
title: "hero campaign pre-registration protocol — the binding measurement contract the 766 program closes with"
type: tempdocs
status: "FROZEN 2026-07-28 (§E.0, §H) + AMENDMENT 1 (2026-07-28, zero measured cells) — §E.1–§E.6 + §E.8 are frozen; any later edit to them voids claim eligibility. Both triggers verified: 760 installer artifact (run 29914075529) + 781 cohort fully-certified (#311). Active policy agent-utility-public-v3 (#310). §E.7 disposed 9 FILLED / 2 CALIBRATION-TIME / row 12 AMENDED. Amendment 1 narrowed known_schemas to [1_hop] (corpus-description correction; thresholds and strata untouched), clearing BLOCKER-1 — preflight now 36 PASS / 0 FAIL / 6 PENDING. Remaining pre-launch: the sonnet closed-book measurement (§H FINDING-2), the derivability + leak checks, and founder launch authorization. Campaign driver prepared dry at scripts/jseval/782-hero/."
created: 2026-07-22
updated: 2026-07-28
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: eval-campaign / pre-registration
related:
  - 766-eval-content-rebuild-program   # "program closes with the hero campaign's pre-registration"
  - 781-corpus-rebuild-v2-camouflaged-metadata  # corpus prerequisite
  - 760-installer-distribution-readiness       # trigger prerequisite (founder lane)
  - 771-post-rebuild-retrieval-residue         # item 3 (at-capture instrumentation) lands here
  - 765-agent-economics-lane                   # pre-flip read-amplification baseline for §B.4
  - 624 (relaunch pre-registration)            # the precedent protocol shape
---

## §A. Purpose

**Agent-utility campaign freeze (2026-11-09):** Fresh and resumed live agent-utility campaigns are frozen. Their commands and runbooks remain available for historical reproduction and offline analysis, along with published evidence and offline replay. Neighboring experiments retain their existing status. The campaign plans and launch/resume instructions below are historical and do not authorize campaign work. One document that makes the hero run auditable before it is expensive: every rule that could
be bent after seeing results is written and frozen before any paid call. Precedent: the 624
relaunch pre-registration (adoption-only target, budget guard, honest-abort semantics) — this
protocol inherits that shape and adds what 624's post-mortem and the 767/774 leak history
taught.

## §B. Contents to draft (each becomes a frozen section)

1. **Design:** strata (enron-1k/10k, legal-1k; legal-10k EXCLUDED per decision 2 — the
   representation floor is 783's problem, not a claim), n=60/stratum, judge=sonnet,
   cheapest-first ordering, USD cap ~$300 with the 624-style guard + pre-registered abort.
2. **Validity rules decided blind:** the leak checks the probe history mandates (a
   measurement probe without pre-registered validity rules once reported its own leak as a
   win — agent-lessons 2026-07-22); identity gates; corpus-signature pins to the 781 v2
   commitments; single-cohort requirement; verified_tool_surface rate ≥ 0.9 (624 policy).
3. **Judge validity:** a pre-run judge-agreement spot-check (sonnet vs held-out human/founder
   adjudication on a small sample, 764's method) with a pre-registered agreement floor;
   below floor → the run does not start.
4. **At-capture instrumentation (771 item 3 lands here):** per-query rank-of-gold, span/
   evidence carriage, tool-call counts, and the READ-AMPLIFICATION after-measure — 765
   baselined agents re-Reading full files pre-flip; the hero captures the post-flip
   distribution for free. Also exhaustion/duration ledgers per 624.
5. **Claim grammar:** exactly what sentence(s) the run can promote, with confidence
   intervals (bootstrap over queries, reported alongside every headline number), and what
   verdicts are possible (accepted / adoption-only / rejected — 624's ladder). No public
   number leaves 623's founder-gated pipeline (decision 3).
6. **Spend + incident ledger format**, resume-safety rules, and the two-failed-relaunches
   stop rule (inherited from the 624 supervision spec).

## §C. Acceptance

- Protocol frozen (this doc's freeze commit named) BEFORE the first paid call; any later
  edit to frozen sections voids the run's claim eligibility.
- Both triggers verified at freeze time: installer artifact exists (760); 781 cohort
  fully certified incl. gold-selectivity.
- The run itself is a separate execution record (new tempdoc or 766 closing section) citing
  this protocol by commit.

## §D. Notes

- Founder-gated actions stay founder-gated: launching the paid run, publishing any number
  (623), and any cap increase.
- The protocol should be short enough to be read before every supervision shift; verbosity
  here is a validity risk, not thoroughness.

---

# §E. THE PROTOCOL (draft 2026-07-27 — **NOT FROZEN**)

> **Freeze mechanics.** This §E is the frozen artifact. It freezes when a founder-authorized
> commit (a) resolves every `PENDING-AT-FREEZE:` marker in §E.7, (b) records the freeze commit
> SHA in §E.0, and (c) verifies both triggers (760 installer artifact exists; 781 cohort
> `fully-certified`). Until then §E is a draft and **no paid call may be made**. After freeze,
> any edit to §E.1–§E.6 voids the run's claim eligibility; amendments made *before the first
> measured cell* follow 624's dated-amendment convention and must be appended, never inlined.

## §E.0 Freeze record — **FROZEN 2026-07-28**

**§E.1–§E.6 are FROZEN as of the commit that introduces this line** (the freeze commit; its short
SHA is recorded in §H once the commit exists — 624's convention, since a commit cannot contain its
own hash). **Any later edit to §E.1–§E.6 voids the run's claim eligibility.** The frozen sections
are: §E.1 Design, §E.2 Validity rules, §E.3 Scorer/judge validity, §E.4 At-capture
instrumentation, §E.5 Claim grammar, §E.6 Ledgers/resume/stop rules — plus §E.8, which is the
frozen qid selection this freeze commit materializes.

| field | value |
|---|---|
| freeze commit | the commit introducing this line (short SHA recorded in §H) |
| freeze timestamp (UTC) | 2026-07-28 |
| trigger 1 — 760 installer artifact | **SATISFIED** — `build-installer.yml` dispatch run `29914075529` (2026-07-22) is GREEN end-to-end; artifact `windows-installer` (`.github/workflows/build-installer.yml:268-278`), 259.9 MB, downloaded and PE-census-verified (177 PEs: 99 rehearsal-signed + timestamped, 78 vendor-signed untouched, zero NotSigned) — 760 §CI signing rehearsal campaign. **Honest scope:** this is a *rehearsal-signed CI artifact*, not a GA-released real-cert-signed installer; the GA cut and the cert/vendor decision remain owner-gated and are **not** 782 launch preconditions (§C requires only that the artifact exists). Actions retention is 7d, so the blob itself has expired — the durable evidence is 760's recorded census, and a fresh artifact is one `gh workflow run build-installer.yml --ref main` dispatch away with no release cut. |
| trigger 2 — 781 cohort `fully-certified` | **SATISFIED** — both members `status: "fully-certified"`, `structural_passed: true`, `fully_certified: true` in `scripts/jseval/781-corpora/<member>/structural-certification.v1.json`, committed by `6e3db1a6` (#311). Blobs: `en-email-enron-raw` `21c05563`, `en-legal-clerc` `73b08679`. All three hero cells carry `checks` 9/9 true and `scientific_gates` `closed_book`/`retrieval_calibration`/`union_recall`/`leak_floor` all `passed: true`. **Pointer correction (this freeze):** P2's original pointer to `member.v1.json` is wrong — that file carries no `status` key and its `remaining_gates` is pre-#311 residue still listing the four scientific gates. The machine authority is the certification file (`corpus_certify.py:815-817` reads `status == "fully-certified"` there); P2's check is hereby pinned to `structural-certification.v1.json`. |
| active claim policy at freeze | **`agent-utility-public-v3`** — `scripts/jseval/utility-claim-policy.v3.json` (blob `1d6d4b41`), `status: "active"`, `unresolved: []`, ratified by squash `76185d82` (#310, 2026-07-28). It is the only `utility-claim-policy.*.json` with `status: "active"`; `utility-claim-policy.v1.json` is `superseded`. `required_strata` = §E.1 verbatim, cheapest-first, each `query_count: 20`, `seed_ids: [0,1,2]`, `requested_model: "sonnet"`. |
| launch authorization | founder-gated; recorded in §E.6 ledger, never here. **NOT GRANTED.** |
| launch eligibility | **BLOCKER-1 CLEARED by Amendment 1 (below).** Remaining pre-launch work is procedural, not a decision: the §E.2 P3 sonnet closed-book measurement (§H FINDING-2 → campaign-plan Step 0b), the §E.3 derivability audit, the §E.2 P4 leak checks, and founder launch authorization. |

### Amendment 1 — 2026-07-28 (pre-launch, **zero measured cells**)

624's dated-amendment convention: a frozen section may take **appended, dated** amendments until
the first measured cell; after that, any change voids claim eligibility (§E.2 R5). Zero cells are
measured, so this amendment is in-window. It is appended here, never inlined into §E.1–§E.6.

| field | value |
|---|---|
| amendment | **1** |
| date | 2026-07-28 |
| decided by | orchestrator, under the founder's 2026-07-28 blanket delegation |
| measured cells at amendment | **0** |
| change | `scripts/jseval/utility-claim-policy.v3.json` → `required_schema_strata.known_schemas`: `["1_hop","2_hop"]` → `["1_hop"]` |
| unchanged | every `thresholds` value; `required_strata`; every `requirements` key (incl. `schema_strata_reported: true`); **`require_all_present` stays `true`** — now satisfiable and still load-bearing, since the `1_hop` breakdown must still be reported and an absent one still refuses the record |
| amendment commit | `4c0fd7bf` |
| resolves | §E.7 row 12 / §H BLOCKER-1 |

**Rationale, recorded verbatim in the policy's `policy_changelog`:** *"Amendment 1 (pre-launch,
zero cells measured): known_schemas [1_hop,2_hop]→[1_hop] — corpus-description correction per 782
§E.7 row 12 / BLOCKER-1; thresholds untouched; decided under founder delegation of 2026-07-28"*.

This is a **corpus-description correction, not a threshold or bar change.** The certified 781 v2
gold sets — and all 707 English cells — are 100 % `1_hop` by construction; the v3-DRAFT's `2_hop`
entry described a multi-schema cohort that was never built. Requiring the presence of a schema
absent from the corpus is a spec error, and the refusal gate surfaced it **pre-spend**, exactly as
intended (§G.1's stated residual limit). No `§E.1–§E.6` text changed; the amendment moves a policy
value that §E.2 R3 deliberately reads *from the policy* rather than restating.

## §E.1 Design (frozen section 1)

**Authority: founder decision 2 (766 §G). Nothing in this section may be re-optimized.**

- **Strata (3, all `verbose`):**

  | # | stratum | dataset | corpus_member | size |
  |---|---|---|---|---|
  | 1 | enron-1k | `mixed/en-email-enron-raw-1k-verbose` | `en-email-enron-raw` | 1000 |
  | 2 | enron-10k | `mixed/en-email-enron-raw-10k-verbose` | `en-email-enron-raw` | 10000 |
  | 3 | legal-1k | `mixed/en-legal-clerc-1k-verbose` | `en-legal-clerc` | 1000 |

- **legal-10k is EXCLUDED** — not in `required_strata`, not run, not reported, not a control.
  Reason (771 §E M5): true retrieval floor 0.82–0.90 there, so a delta measures corpus
  difficulty, not the tool. Adding it back is a founder decision, not a supervision-shift one.
- **Model:** sonnet-class for both arms (`requested_model: "sonnet"`); every cell must resolve
  to one `resolved_provider_model`. haiku is **not** run in this campaign.
- **Arms:** A = file tools only; B = A + JustSearch MCP. Primary estimand `addition_b`, ITT
  (resource-exhaustion-as-failure) primary, per-protocol secondary.
- **n = 60 paired observations per stratum** = 20 committed qids × 3 seeds `{0,1,2}` × 2 arms
  = **120 cells/stratum, 360 cells total**. The v2 cells carry 50 queries each; the 20 are
  selected by a deterministic committed rule, never by hand. ✅ **RESOLVED at freeze — see §E.8.**
  The rule is `--max-queries 20` over the committed, certified `queries.json` in its committed
  order, i.e. **qids `q0001`–`q0020`**. This is not a style preference: it is the only selection
  that survives the certification digest check (`agent_utility_inspect.py:1794-1824` hashes the
  WHOLE queries file's bytes and truncates only the sample rows, so any *rewritten* subset file —
  including a PRNG sample — fails `query_gold_sha256` with "query-and-gold digest disagrees").
  624 set the same precedent (`retained_query_indices: [0..19]`,
  `624-run-2026-07-21-relaunch/calibration-email-enron-raw-1k-verbose.json`).
- **Ordering: cheapest-first** — enron-1k → enron-10k → legal-1k. (624 confirmatory amendment 1:
  the max-extrapolation guard over-projects when the most expensive stratum calibrates first;
  the fix is order, not cap.)
- **Budget:** hard cap **$300 USD**, running guard `python scripts/jseval/step2-budget-guard.py
  --cap 300` (max-extrapolation over `cost_estimate_usd` of known calibrations) before each
  stratum launch. **Binding limit is USD, never wall-clock** (765 §E: 69/86 wall-clock kills lost
  their cost receipt; USD kills retain them).
  **CALIBRATION-TIME (procedure frozen here, number derived at run time — 624's precedent, which
  also set `--max-budget` from fresh calibrations rather than pre-registering a number):** per-cell
  `max_budget` USD and `concurrency` come from the *fresh sonnet* `utility-calibrate` for each
  stratum, run on the campaign day against the live backend. **The frozen part is the derivation
  rule, not the value:**
  - `concurrency` — taken from the calibration file's `concurrency` key, which `utility-run` reads
    and overrides the CLI flag with (`commands/utility.py:358`). Calibration is invoked at the
    campaign's target concurrency (`--concurrency 6`, 624's value; the calibration pilot runs *at*
    it so the estimate is not a single-threaded extrapolation).
  - `max_budget` — `ceil2(1.6 × p95_per_cell_cost_usd_at_sonnet)` from the same calibration, floored
    at `$0.50` (624's haiku value) and **capped at `$2.00`**; `ceil2` = round up to cents. The 1.6
    headroom and the floor/cap are frozen now so the number cannot be re-chosen after seeing a
    stratum's first cells. If a stratum's derived value would exceed `$2.00`, that is a **stop**,
    not a raise: record it and escalate (a cap raise is founder-gated, §E.6).
  - The derived pair is written into the run dir's `spend-ledger.md` at the phase boundary *before*
    that stratum's launch, and into `campaign-plan.md`'s per-cell record — never adjusted after.
- **No partial-value ladder.** `required_strata_exact` means a 2-stratum record cannot promote,
  so a projected over-cap → **ABORT the whole campaign and report** (624 confirmatory §Budget).

## §E.2 Validity rules decided blind (frozen section 2)

Every rule below is evaluable mechanically, before any result is interpreted. **P-rules are
pre-launch (fail → do not launch); R-rules are per-run/at-compose (fail → the affected stratum
is void, see §E.6).**

**P0 — policy identity.** ✅ **RESOLVED 2026-07-28 by the §G ratification.** The active policy is
`scripts/jseval/utility-claim-policy.v3.json` (`policy_id: agent-utility-public-v3`,
`status: "active"`), and it declares exactly the §E.1 three strata with `requested_model: "sonnet"`,
`query_count: 20`, `seed_ids: [0,1,2]` — see §G for the verbatim block. The pre-launch check is:
that file is the one `utility-claim-policy.*.json` with `status: "active"`, its `unresolved` is `[]`,
and its `required_strata` match §E.1. `utility-claim-policy.v1.json` is now
`status: "superseded"` (it is history, not the operative policy) and the v3 DRAFT file — whose
matrix contradicted decision 2 — is deleted.

**P1 — corpus_signature pins (v2 cohort, 781 §E.2).** Each stratum's computed
`corpus_signature` must equal, byte for byte:

| stratum | `corpus_signature` | `query_gold_sha256` (verbose) |
|---|---|---|
| enron-1k | `3391fc9781c6dafc4881322337558810b739b1a1d55a4eac5e1f035bec18cff7` | `1138c58c809859a5fb8e4dcd6fe54845d67834e474d7d49be7a2144db3584352` |
| enron-10k | `6a80af3b5bdbb59d578eedfd00a4c35815ffcf07450afce2de73d4dd818399cb` | `1138c58c809859a5fb8e4dcd6fe54845d67834e474d7d49be7a2144db3584352` |
| legal-1k | `6df707031abcd296773a0bf8c6a7750bb0b8704ce4ab4035105cf88b8df01fae` | `2797469a5f80aa8881553323880cd910dd83b621dabd935e7d577337f0f3f565` |

Read from `scripts/jseval/781-corpora/<member>/structural-certification.v1.json`; asserted
against the composed record's `strata[*].corpus.corpus_signature`. A mismatch is fatal — never
"the corpus moved, re-pin the protocol". ✅ **RE-CONFIRMED at freeze 2026-07-28 by primary re-read
(not assumption)** of the certifications committed by `6e3db1a6` (#311) — blobs `21c05563`
(`en-email-enron-raw`) and `73b08679` (`en-legal-clerc`). All six values above match byte for byte;
781 §E.4's scientific pass did not move them, as predicted. Also re-read at the same time and
recorded here so P2 is evaluable from one place: every hero cell's `query_count` is **50** (the
committed gold set; the campaign measures the first 20 — §E.8), and every hero cell's
`field_selectivity` gate is `passed: true` with `max_field_separability: 0` ≤ `native_base_rate: 0`
on `n_fields_compared: 2`, worst field `text` (method
`field-presence-null-calibrated-separability`) — the title-presence leak class (774 §J.7) is closed
at zero separability on all three strata.

**P2 — gold-selectivity certification.** Every stratum's member must be `fully-certified`
(`781-corpora/<member>/member.v1.json`), and the `field_selectivity` gate must be PASS on
**every field × every cell** with `max_field_separability <= native_base_rate`. A
`structurally-certified` member is NOT sufficient — that is 781's current state, and the whole
point of the trigger. This is the direct guard against the title-presence leak class (774 §J.7).

**P3 — closed-book at hero tier.** Closed-book accuracy measured **at sonnet** on each stratum
must be `0.000` (781 §C). Any non-zero value means the answers are derivable without the corpus
→ do not launch. ✅ **The two-number conflict is RESOLVED (§G, 2026-07-28):** the ratified
`maximum_closed_book_accuracy` is `0.1`, and it is a post-hoc failure **CEILING** enforced by the
`closed_book_at_hero_tier` gate — it does **not** relax this P-rule. The pre-launch bar stays
`0.000` at sonnet. Read it as: below 0.000 to launch; above 0.1 voids the benefit headline for
that stratum even if it somehow launched.

**P4 — probe-self-leak checks (the 2026-07-22 lesson: a probe without pre-registered validity
rules reported its own leak as a win).** All four run pre-launch over committed artifacts, output
committed to the run dir:
  1. **Arm symmetry** — `prompt_template_hash` and the instruction text are byte-identical
     across arms A and B; the arms differ *only* by MCP tool availability. Any arm-specific
     prompt content voids the design.
  2. **No gold text in the prompt** — for every selected qid, the question text contains neither
     the gold answer string nor any gold doc id, and no gold-only *field* (title, author, entity)
     is injected into either arm's context. This is the exact shape of the discarded
     title-prepend arm.
  3. **Answer-key path isolation** — the agents' `--add-dir` resolves to the exploded
     `corpus-dir` only; `qrels`, `fabricated-queries.json`, `fabricated-meta.json` and
     `commitment.v1.json` are outside every readable root. Verified by path check pre-launch and
     by `leak_suspect_tool_calls == []` on every cell at compose.
  4. **Shipped-config parity** — `captured_search_config` equals the shipped product default
     cohort (post-775 flip). No eval-only boost, field, or limit. We measure the product users
     get (766 §G decision 1).

**P5 — scorer/judge floor.** §E.3 must be green. Below floor → the run does not start.

**R1 — identity gates (per run + at compose).** One `search_config_cohort_key`, one
`agent_cohort_key`, one `cli_version` for the whole campaign, `git_dirty: false`, clean committed
tree, corpus-dir derivation check enforced in root mode (624 §Identity hardening), exposure
identity carried through compose.

**R2 — `verified_tool_surface`** (policy `agent-utility-public-v2` semantics, carried into v3):
rate ≥ **0.9**, exactly one observed hash equal to the declared cohort hash, **any single
different hash is fatal regardless of rate**, a missing hash after retry+fallback is a capture
miss that lowers the rate only.

**R3 — data-quality thresholds** (from the active policy `thresholds`, not restated as new
numbers): `minimum_seeds` 3, `minimum_paired_observations` 54, `maximum_exclusion_rate` 0.15,
`minimum_paired_retention` 0.7, `minimum_excluded_jaccard` 0.5, `significance_alpha` 0.05,
`minimum_adoption_rate` 0.9. Plus `no_leak_suspect_cells`, `contamination_class:
private-synthetic`.

**R4 — single cohort, one compose.** All three strata compose in ONE
`utility-recompose` invocation. No retroactive stratum substitution; a voided stratum is rerun
under the SAME cohort window or the campaign records `rejected` (624 relaunch §Analysis plan).

**R5 — nothing changes after the first measured cell.** Strata, n, qid list, arms, model,
thresholds, α, estimand, cap. Any post-hoc change → the run is not claim-eligible, full stop.

## §E.3 Scorer / judge validity + pre-registered agreement floor (frozen section 3)

**Scorer identity first.** 764 §E established that the production scorer is `substring_scorer`
(gold string ⊆ answer), **not** an LLM judge — and that its full-string requirement was the only
thing neutralizing the v1 naming leak. So the audit's shape depends on what is active at the
freeze commit. ✅ **READ AT THE FREEZE COMMIT, verbatim:** the wired scorer is
**`substring_scorer`** — `scorer=substring_scorer()` at
`scripts/jseval/jseval/agent_utility_inspect.py:1512`, defined at `:1454-1460` (delegates to
`_score_answer(target.text, completion)`; gold string ⊆ answer, with the abstention/error branch
returning `"I"`). The task's declared `judge_kind` default is `"substring-em"`
(`agent_utility_inspect.py:1483`). **No LLM judge is active in the primary scoring path.**

**Operational reading of the two-layer scorer (this is the frozen §F.3 semantics).** The primary
scorer for the hero campaign is `substring_scorer` (EM). The hybrid **EM→local-LLM judge overlay**
is a *post-hoc, zero-paid-API* re-score applied to the already-completed logs —
`jseval utility-judge` → `scripts/jseval/jseval/utility_judge.py:141 judge_logs(...)`, which keeps
EM as a high-precision auto-PASS and only sends EM-misses to the judge, stamping
`kind="hybrid-em-llm"` when it judged anything and `"substring-em"` when it did not (`:197`). The
overlay **never changes which cells were run or what they cost** — it is attached at compose via
`utility-recompose --judge-overlay`. **Judge-family control stays intact:** the cross-family grader
panel (`utility_judge.py:691 judge_cross_family`, with `cohens_kappa` `:290`, `bootstrap_kappa_ci`
`:313`, `is_degenerate_pe` `:273`, `rater_agreement_report` `:356`) is the calibration instrument,
and the local model does the bulk pass (`utility-judge-local-swap-smoketest` proves the swap), so
no paid judge call is required for the overlay. Because the *primary* scorer at freeze is
`substring_scorer`, the branch that runs pre-launch is the **derivability audit** below, not the
agreement spot-check (§E.7 row 10). Then:

- **If `substring_scorer` is active** — run the *derivability* audit instead of an agreement
  audit: for every selected qid, assert the gold answer string is **not** derivable from any
  token in the question or from any entity name in the corpus metadata (the
  `corpus_generate.py:320-323` naming-leak class, 764 §E). Floor: **0 derivable qids**. Any hit →
  the qid is replaced from the committed pool *before* launch, and the replacement is recorded.
- **If an LLM judge is active** — run the pre-registered agreement spot-check below.

**Agreement spot-check (method: 764 §A.2 — zero-cost first, paid only for tiebreaks).**

- **Sample:** 40 completed cells, stratified: ≥10 per stratum, balanced across arms A/B, and
  balanced across scored-correct / scored-incorrect. Drawn with a seed fixed here
  (`random.Random(2026)`), from a **pre-hero pilot or banked cells** — never from the hero cells.
- **Reference:** founder adjudication (held-out human), rubric held fixed — the rubric is
  measured, never redesigned in this audit (764 §B).
- **Floors, both required:** exact agreement **≥ 0.95** (≥38/40) **AND** at most **1**
  disagreement in the direction that favours arm B. An arm-asymmetric error pattern is the
  failure mode that manufactures a win, so it carries the tighter floor.
- **Below either floor → the run does not start.** No remediation-then-relaunch inside the
  campaign; a failed audit sends the scorer back to design and this section is re-frozen.
- Cost: the local model (`ai_activate`, ~11s load) does the bulk pass at zero API cost; paid
  sonnet is used only to break local-vs-original disagreements (764 §D).

## §E.4 At-capture instrumentation (frozen section 4) — 771 item 3 lands here

Everything below is captured **during** the hero cells; none of it costs an extra paid call.
Raw tool content is never committed — digests only (`_tool_result_digest_entry`,
`agent_utility_inspect.py:934`).

**Per search call** (`state.metadata["tool_result_digests"][*]`, 768 D6):
`ordered_doc_ids`, `scores`, `gold_rank` (honest `None` when gold is absent — **never a
fabricated 0**), `delivered_tier`, `delivered_fields`, `component_bytes`.

**Per cell:** `tool_calls`, `tool_call_sequence`, `tool_calls_blocked`,
`disallowed_tool_calls`, `leak_suspect_tool_calls`, `mcp_tools_offered`,
`observed_mcp_tool_surface_hash`, `usage`, `cost_usd`, `unique_tokens`, `turns`,
`usage_truncated`.

**Derived at compose (committed as JSON in the run dir):**

1. **Rank-of-gold distribution** per stratum × query: median/p90 `gold_rank`, and the
   share of search calls with `gold_rank is None`.
2. **Evidence/span carriage**: share of B cells whose delivered payload carried the
   answer-bearing span (post-775 flip); `delivered_tier` census.
3. **Read-amplification after-measure (771 item 3).** From `tool_call_sequence`, per B cell:
   (a) count of filesystem `Read` calls occurring after the first `justsearch_search` call;
   (b) the share of those Reads whose target is **not** in any preceding `ordered_doc_ids`;
   (c) `mcp_call_share`. **Pre-flip reference (765 §E / 770 §A.2, different cohort, model and
   corpora): `mcp_call_share` ≈ 0.5, and 50.3% of post-search Reads (N=862) targeted documents
   search never returned.** This after-measure is **pre-registered as DESCRIPTIVE ONLY** — it is
   not a comparison, not a claim, and no delta against the pre-flip reference may be promoted.
   Naming this before launch is what stops "we cut re-reads by X%" from appearing afterwards.
4. **Exhaustion ledger** per stratum × arm: `n_attempted / n_completed / n_exhausted /
   n_excluded / n_pending`, `per_arm_loss`, plus the 765 §E taxonomy split —
   **USD-exhausted (receipts retained)** vs **wall-clock-cancelled (cost null by design)**.
   Null costs are **segmented, never imputed** (757 §D.2).
5. **Duration ledger** per stratum × arm: median + p95 wall-clock with an explicit censoring
   flag; efficiency intervals **fail closed** wherever with-tool truncation exists — that is a
   pre-registered accepted outcome, not a defect (624 relaunch §Registered target).

## §E.5 Claim grammar (frozen section 5)

**Verdict ladder (624's, unchanged).** The composed record's `claim_verdict` is the verdict; no
human re-reads it into something better.

| verdict | when | what may be said |
|---|---|---|
| `accepted` / outcome `benefit` | all gates pass **and** efficiency intervals are available and direction-checked | the accuracy/efficiency sentence in the templates below |
| `accepted` / outcome `adoption-only` | all gates pass, efficiency intervals unavailable (truncation) | the adoption sentence only — **no numeric benefit language** |
| `rejected` / `inconclusive` | any gate fails | nothing. The measured numbers stay in this tempdoc as history |

**Confidence-interval convention (applies to every headline number).** Report a **95% cluster
bootstrap CI over queries**: resample **qids with replacement, carrying each qid's 3 seed
replicates as a cluster** (seeds within a qid are not independent), 10,000 resamples, BCa,
`random.Random(2026)`. Report it *beside* the exact-McNemar p (α = 0.05) — both, always. If the
two disagree, both are reported and the disagreement is stated; it is never resolved post hoc.

**Power honesty line, mandatory beside every null (764 §E).** At n = 60 pairs, exact McNemar has
power 0.89 for Δ = 0.20 (discordant rate 0.25) and is underpowered below Δ ≈ 0.15 (n@80%: 92 for
Δ = 0.15, 208 for Δ = 0.10). A null is therefore reported as **"no effect detected at n = 60"**,
never as "no effect".

**Triple reporting (v3 `triple_reporting_semantics`).** The ITT number is the headline; the
per-protocol accuracy **and** the completion rate are published beside it; the per-cell budget is
stated explicitly.

**Forbidden headlines** (any occurrence makes the statement non-promotable):
- raw absolute accuracy of a weak arm presented alone;
- any ratio off a near-zero base — an infinite/undefined multiplier must be reframed as
  completion-rescue or absolute lift;
- adoption rate presented as benefit;
- a null stratum as a headline;
- **anything about legal-10k** (excluded per §E.1);
- **any read-amplification delta** (§E.4 item 3 is descriptive only);
- any number without its CI and its estimand named.

**Publication.** No number leaves the 623 founder-gated pipeline (766 §G decision 3): immutable
publication manifest + owner-selected pointer. Drafting a public sentence is not publishing it;
publishing is founder-only, per action.

## §E.6 Spend + incident ledger, resume-safety, stop rules (frozen section 6)

**Run directory:** `scripts/jseval/782-run-<YYYY-MM-DD>-hero/` — per-stratum
`utility-comparison.v1.json`, the single combined cross-corpus record, all calibrations, the
chain script, the §E.2 pre-launch check outputs, the §E.4 derived JSONs, and both ledgers below.

**Spend ledger** (`spend-ledger.md`, appended at every phase boundary — before each calibration,
before each stratum launch, after each stratum completes):

| ts (UTC) | phase | stratum | planned $ | actual $ | cumulative $ | guard projection $ | headroom vs $300 |
|---|---|---|---|---|---|---|---|

Actuals reconcile against the per-run records' ledgers at close; an unreconciled gap is reported,
not smoothed (765 §E found a ~+8% SDK price-table skew — report the skew, don't pick a number).

**Incident ledger** (`incident-ledger.md` — 624's confirmatory night logged six):

| id | ts (UTC) | what fired | cohort-affecting? | remedy | $ consumed | stratum voided? |
|---|---|---|---|---|---|---|

Every guard that fires gets a row, including guards that fired correctly. A fired guard is
evidence, not an obstacle — never hand-patch an identity to get past one.

**Resume-safety.**
- Resume uses Inspect `eval_set` durable per-cell resume keyed on `sample_id`, into the **same**
  `log_dir` with **byte-identical task args**. A changed task arg creates a new run and **splits
  the cohort** — treat it as a failed relaunch, not a resume.
- After any resume, re-assert R1 (single `search_config_cohort_key` / `agent_cohort_key` /
  `cli_version`) before continuing.
- Calibrations are never adopted across git SHAs or CLI versions (624 incident #5: a banked
  calibration from another SHA split the cohort and the recompose refused).
- The harness window runs with `DISABLE_AUTOUPDATER=1`; `cli_version` is recorded at start and
  re-checked at every phase boundary. A mid-campaign CLI change voids every cell after it (624
  incident #6).

**Stop rules (all mandatory; each is a full stop, not a judgment call).**
1. **Two-failed-relaunches** — a stratum may be relaunched at most **twice** inside the cohort
   window. On the second failed relaunch of **any** stratum the campaign STOPS and records
   `rejected`. No third attempt, no stratum substitution, no cohort-window extension.
2. **Guard over-cap** — projected total > $300 → ABORT the campaign (§E.1: no partial ladder).
3. **Pre-launch failure** — any P0–P5 check red → do not launch.
4. **Fatal identity** — a different observed tool-surface hash (R2), or a mid-campaign cohort
   split that a resume cannot repair → stop and record.
5. **Post-first-cell change request** — any request to change a frozen parameter mid-campaign →
   stop, record, escalate to the founder. Not an amendment; the amendment window closed.

**Founder-gated, never agent-initiated:** launch authorization, any cap increase, any
publication, any change to §E.1's design parameters, policy ratification.

**The execution record is a separate document** (§C): a new tempdoc or a 766 closing section that
cites this protocol **by its §E.0 freeze commit SHA**. Results are never written into §E.1–§E.6 —
that would be editing a frozen section.

## §E.7 PENDING-AT-FREEZE index (the mechanical freeze checklist) — **CLOSED OUT 2026-07-28**

| # | item | section | disposition at freeze |
|---|---|---|---|
| 1 | freeze commit SHA + timestamp | §E.0 | **FILLED** — timestamp 2026-07-28; SHA recorded in §H per 624's "the commit introducing this line" convention |
| 2 | 760 installer artifact identity | §E.0 | **FILLED** — `build-installer.yml` run `29914075529`, artifact `windows-installer`, PE-census-verified (760 §CI signing rehearsal campaign) |
| 3 | 781 members `fully-certified` (not `structurally-certified`) | §E.0 / P2 | **FILLED** — both members `status: "fully-certified"` / `fully_certified: true` in `structural-certification.v1.json` (`6e3db1a6`, #311); P2's pointer corrected from `member.v1.json` (which has no `status` and stale `remaining_gates`) to the certification file |
| 4 | ~~ratified `policy_id` + `required_strata` re-pinned to §E.1~~ ✅ **CLOSED 2026-07-28 (§G)** — `agent-utility-public-v3`, `scripts/jseval/utility-claim-policy.v3.json`, strata = §E.1 verbatim | P0 | **FILLED** — merge commit `76185d82` (#310) recorded in §E.0 |
| 5 | re-confirm the three `corpus_signature` pins are unmoved by 781 §E.4 | P1 | **FILLED** — all six values (3 signatures + 3 gold digests) re-read and byte-identical to §E.2's pinned table; see P1 |
| 6 | ~~ratified `maximum_closed_book_accuracy`~~ ✅ **CLOSED 2026-07-28 (§G)** — `0.1` as a post-hoc failure CEILING; P3's pre-launch bar stays `0.000` at sonnet | P3 | **FILLED** |
| 7 | the 20-qid list per stratum + sha256 + the deterministic selection rule | §E.1 | **FILLED** — §E.8 (rule + the three lists + both digests + schema census) |
| 8 | per-cell `max_budget` USD from fresh **sonnet** calibrations; concurrency | §E.1 | **CALIBRATION-TIME** — the derivation rule, floor and cap are frozen in §E.1; the number comes from the campaign-day sonnet calibration. 624's precedent set `--max-budget` the same way. |
| 9 | active scorer identity at the freeze commit (`substring_scorer` vs LLM judge), recorded `file:line` | §E.3 | **FILLED** — `substring_scorer`, `agent_utility_inspect.py:1512` (def `:1454-1460`), `judge_kind` default `"substring-em"` `:1483` |
| 10 | which §E.3 branch runs (derivability audit vs agreement spot-check) — follows item 9 | §E.3 | **FILLED** — the **derivability audit** branch (row 9 resolved to `substring_scorer`) |
| 11 | run-directory date suffix | §E.6 | **CALIBRATION-TIME** (launch-day) — `scripts/jseval/782-run-<YYYY-MM-DD>-hero/`; the *pattern* is frozen, only the launch date is unknown until the founder authorizes |
| 12 | **NEW at freeze — `known_schemas` coverage vs the hero query sets** | P-rule (new) / §E.5 | ✅ **AMENDED 2026-07-28** — §E.0 Amendment 1 (commit `4c0fd7bf`) narrows `known_schemas` to `["1_hop"]`; `require_all_present` stays `true`. Was a founder decision; taken under the 2026-07-28 blanket delegation. See §H BLOCKER-1. |

**11 of 11 original rows disposed: 9 FILLED, 2 CALIBRATION-TIME** (rows 8 and 11 — each is a value
624's precedent also set at run time; the freeze pins the *procedure and guard*, not the number).
Row 12 is new: the freeze's own mechanical check found a pre-launch blocker that §G.1 predicted
would be findable from the committed qid list. Nothing in this table is a judgment call at
supervision time.

## §E.8 The frozen 20-qid selection (frozen section 7 — §E.7 row 7)

**Selection rule (mechanical, no PRNG, no hand-picking).** For each stratum: take the committed,
certified `queries.json` in its committed order and keep the **first 20 rows**, i.e. `--max-queries
20` on `jseval utility-run`. qids are the rows' own `query_family_id` values, `q0001`–`q0020`;
Inspect sample ids are `{condition}|q{index}` with `index` 0-based, so `A|q0`–`A|q19` and
`B|q0`–`B|q19` per seed.

**Why this rule and not a seeded sample.** `run_utility_eval` computes
`query_identity.sha256` over the **entire queries file's bytes** and only afterwards truncates
`rows` to `max_queries` (`scripts/jseval/jseval/agent_utility_inspect.py:1796-1812`), then refuses
the run when that digest differs from the certification's `query_gold_sha256` (`:1818-1824`,
`"corpus_certification rejected: query-and-gold digest disagrees with queries"`). A seeded sample
would have to be written to a new file, whose bytes differ, so it would fail the certified-identity
check — the certification would have to be re-issued to bless a subset. Truncation is therefore the
only certification-preserving selection, and it is fully deterministic. 624 used the same shape
(`retained_query_indices: [0..19]`, 0 dropped).

**The lists.** Identical qid labels across all three strata by construction (positional ids), which
is why the label digest alone is not an identity — the per-stratum **content** digest below is the
binding pin.

| stratum | dataset | qids | `qid_list_sha256` | `selected_query_sha256` |
|---|---|---|---|---|
| enron-1k | `mixed/en-email-enron-raw-1k-verbose` | `q0001`…`q0020` | `7b7856e8d8d0f3ce4ca8499798ce93ac1e5aec58633fc03fe514d7f3853c5b5b` | `27aba99517718d0fe1cecab16232d563a6ed8ee11d7ba0bed547c5183a75d065` |
| enron-10k | `mixed/en-email-enron-raw-10k-verbose` | `q0001`…`q0020` | `7b7856e8d8d0f3ce4ca8499798ce93ac1e5aec58633fc03fe514d7f3853c5b5b` | `27aba99517718d0fe1cecab16232d563a6ed8ee11d7ba0bed547c5183a75d065` |
| legal-1k | `mixed/en-legal-clerc-1k-verbose` | `q0001`…`q0020` | `7b7856e8d8d0f3ce4ca8499798ce93ac1e5aec58633fc03fe514d7f3853c5b5b` | `316f80bb6e0144aad11c8ebddfe660ee1881afde4b6b862d889f0a44fea96cb2` |

- `qid_list_sha256` = `sha256(utf8("q0001\n…\nq0020\n"))`.
- `selected_query_sha256` = `sha256(utf8(json.dumps([{qid, query, answer, question_type}, …],
  separators=(",",":"))))` over the 20 selected rows in order, read from
  `scripts/jseval/781-corpora/<member>/<size>-verbose/fabricated-queries.json`.
- The two enron strata share a digest **correctly**: they share one gold query set (§E.2 P1 pins the
  same `query_gold_sha256` `1138c58c…` for both). legal-1k differs, as its gold digest
  (`2797469a…`) does.
- Cross-checked at freeze: the first 20 rows of the **materialized** `queries.json` match these on
  `query`/`answer`/`question_type`/`query_family_id` at both visible materializations
  (`tmp/781-v2-datasets/mixed/…` and the `datasets/mixed/…` junction) — MATCH on all three strata.
- Reproduce: `python scripts/jseval/782-hero/preflight.py` (prints both digests per stratum).

**Schema-coverage picture at freeze (§G.1's residual limit, evaluated).**

| stratum | selected 20 | full committed 50 | policy `known_schemas` at freeze | projection at freeze | after Amendment 1 (`["1_hop"]`) |
|---|---|---|---|---|---|
| enron-1k | `1_hop`: 20 | `1_hop`: 50 | `["1_hop","2_hop"]` | **WILL FAIL** — `2_hop` missing | **PASSES** |
| enron-10k | `1_hop`: 20 | `1_hop`: 50 | `["1_hop","2_hop"]` | **WILL FAIL** — `2_hop` missing | **PASSES** |
| legal-1k | `1_hop`: 20 | `1_hop`: 50 | `["1_hop","2_hop"]` | **WILL FAIL** — `2_hop` missing | **PASSES** |

Every stratum is single-schema. This is **not** an artifact of the 20-of-50 truncation — the full
committed 50-query gold set of every one of the eight 781 v2 cells is 100 % `1_hop`, and so is
every 707 English cell. `2_hop` exists only in older corpora (`635-corpora/synth-multihop-prose-v2`,
`624-corpora/battlefield-en-v1`, `707-corpora/de-miracl/gold-short-natural`), none of which is a
hero stratum. Recorded here at freeze so compose-time cannot surprise; escalated as §H BLOCKER-1.

## §G Claim policy v3 — RATIFIED 2026-07-28 (closes §E.7 items 4 and 6)

**Authority.** Founder decision 2 (766 §G); the ratification act itself was delegated to the
orchestrator on 2026-07-28 with a fixed composition rule — **v3-DRAFT's structural machinery +
v1's (`agent-utility-public-v2`'s) numeric thresholds VERBATIM + founder-decision-2 strata**.
No threshold was tuned and no stratum was chosen by an agent.

**File.** `scripts/jseval/utility-claim-policy.v3.json` — `policy_id: agent-utility-public-v3`,
`status: active`. `required_strata` are exactly §E.1, in §E.1's cheapest-first order:

| # | `stratum_id` |
|---|---|
| 1 | `en-email-enron-raw\|mixed/en-email-enron-raw-1k-verbose\|1000\|verbose\|sonnet` |
| 2 | `en-email-enron-raw\|mixed/en-email-enron-raw-10k-verbose\|10000\|verbose\|sonnet` |
| 3 | `en-legal-clerc\|mixed/en-legal-clerc-1k-verbose\|1000\|verbose\|sonnet` |

each `query_count: 20`, `seed_ids: [0,1,2]`. **legal-10k is absent** — item 4's contradiction
(the draft declared legal-10k×sonnet + legal-1k×sonnet + legal-10k×haiku) is resolved in favour
of decision 2. Item 6 is resolved by keeping the draft's `maximum_closed_book_accuracy: 0.1` as a
post-hoc failure **CEILING**; it does not relax P3, whose pre-launch bar stays 0.000 at sonnet.

**Selector change.** `utility_claim_policy.policy_path()` now returns the v3 file. The v2
document (`utility-claim-policy.v1.json`) is `status: "superseded"` +
`superseded_by: "agent-utility-public-v3"`, retained as history and as the byte-source the
no-tuning test compares against. `utility-claim-policy.v3-DRAFT.json` is DELETED, and its
`v3_draft_policy_path`/`load_v3_draft_policy` accessors are gone; the only live references that
moved were `scripts/docs/gen-public-agent-utility.mjs` (+ its test), which also stopped hardcoding
"four-stratum … 1k and 10k" and now renders the stratum count from the policy.

**Three additive requirements are now wired, not decorative.** `completion_triple_reported`,
`closed_book_at_hero_tier` and `schema_strata_reported` were declared by the draft but would have
failed the `supported_policy_requirements` gate closed, making every record unpromotable. Each is
now a real conditional gate (fires only when a policy declares it, so records under v1/v2 project
byte-identically):

- `completion_triple_reported` — every ITT stratum must carry `n_per_protocol_pairs` and a per-arm
  `completion_rate` (`estimands.completion.strata[*].by_arm[*]`).
- `closed_book_at_hero_tier` — every stratum's 707 certification snapshot must carry a measured
  `scientific_gates.closed_book.observed.closed_book_accuracy` at or below the ceiling, with a
  named measurement model.
- `schema_strata_reported` — every composed measured cell must publish a `schema_stratified.by_stratum`
  covering both known schemas. Cells are located by their own `primary_arm` marker, not by joining
  on a dataset key (the measured key is the canonical slug `beir/fixture` while the ITT stratum's
  `corpus` is `fixture` — a join there would silently miss).

**Defect found and fixed by that third gate (768 D4 round-trip).** `sanitize_observation` wrote
`question_type` and `_OBSERVATION_KEYS` accepted it, but `read_evidence` never restored it — so
every *offline replay* recomposed with no schema tag and silently dropped `schema_stratified`,
contradicting the write-side comment's own promise. Repaired at
`scripts/jseval/jseval/utility_evidence.py` (`read_evidence`). This mattered directly: the
publication/replay path is how a hero record gets published.

**Test evidence.** Full jseval suite: **2520 passed, 2 skipped** (`python -m pytest tests/`).
`node scripts/docs/gen-public-agent-utility.test.mjs` → OK (98 assertions); `--check` in sync after
regenerating README/RESEARCH/`docs/reference/benchmarks/agent-utility.md`. Deliberate pin changes:

| pin | change | why it is the new truth |
|---|---|---|
| `test_checked_in_policy_is_active_confirmatory_four_stratum` | renamed `…_is_ratified_v3_three_stratum_sonnet_hero`; asserts v3 id + the §E.1 ordered stratum list | the four-stratum haiku matrix is superseded by decision 2 |
| `test_checked_in_active_policy_evaluates_surface_via_rate_branch` | `agent-utility-public-v2` → `…-v3` | same rate semantics, new carrier (§E.2 R2) |
| `test_supported_requirements_match_schema_properties_exactly` | `required` now compared against a new explicit `MANDATORY_REQUIREMENTS` constant, still an exact set equality | supported ≠ mandatory once requirements became additive; keeps the superseded v2 document schema-valid without back-dating keys into it |
| `test_historical_fixture_semantic_digest_repinned_after_624_itt_change` | `ed81f79b…` → `c3f98ebd…` | 4th occurrence of the documented policy-identity re-pin class; the sole-mover proof is now the runnable `test_v3_repin_is_policy_identity_only_prior_pin_reproduces` (see §G.1), not prose |

New test: `test_v3_ratification_tuned_no_threshold_and_dropped_legal_10k` — literally compares v3's
thresholds against the superseded v2 file on every shared key, asserts the only divergence is the
additive `maximum_closed_book_accuracy`, and asserts 3 strata / all sonnet / no legal-10k. That
makes "no threshold was tuned during ratification" a mechanical property rather than a claim.

## §G.1 Independent-review response (2026-07-28)

An independent refute-first review of the ratification commit returned SHIP-WITH-FIXES. The
ratification content was confirmed sound (thresholds re-diffed verbatim vs v1; strata verbatim vs
§E.1; conditional gates confirmed key-present-and-truthy; old-policy records reproduced
byte-identically). Six items landed as a follow-up commit.

**1. A false verification recipe I wrote — corrected.** §G originally claimed the prior digest pin
`ed81f79b…` reproduces "when the SUPERSEDED v2 document is passed as the policy". It does not: the
committed superseded document yields `d32dffba…`, because *this commit's own* `status` flip to
`superseded` plus the added `superseded_by` change the policy hash the verdict carries. The
conclusion (policy identity is the sole mover) was true, but the recipe would have sent the next
auditor chasing a phantom regression. The claim is now a runnable test rather than prose —
`test_v3_repin_is_policy_identity_only_prior_pin_reproduces` reconstructs the *pre-ratification*
v2 shape from the committed file (pop `superseded_by`, restore `status: "active"`) and asserts
`ed81f79b…`. Lesson worth keeping: a prose verification recipe that nobody re-runs is exactly where
a phantom hides — pin it or drop it.

**2. Negative tests for the three new gates.** Only the pass branch was covered, so a gate inverted
to always-True would have shipped green (a passing gate and an absent gate are indistinguishable
from `accepted is True`). Added four refusal tests, each asserting the SPECIFIC gate name surfaces:
`completion_triple_reported` (strip `estimands.completion`), `schema_strata_reported` (strip the
breakdown, asserting the missing-schema payload), and two for `closed_book_at_hero_tier` (evidence
removed; accuracy above the ceiling). **Honest isolation note:** the closed-book measurement rides
the 707 snapshot, which is cross-validated against its own embedded certificate, so any mutation of
it *also* trips `corpus_certification_complete`. That co-failure is asserted rather than glossed,
and the gate's independent work is proved by a before/after on the same gate plus its observed
payload — the first draft of these tests claimed an isolation they did not have, which would have
been a second false recipe.

**3. Sweep residue in the operator-facing sections.** §E.2 P0 still told the operator to verify
`utility-claim-policy.v1.json` had `status: "active"` (now false) — repointed at the v3 file with a
status-based check. §E.2 P3 now records the ceiling-vs-0.000 resolution inline. §E.7 rows 4 and 6
are struck through and marked CLOSED, with the running count (9 of 11 open) stated. Stale comment at
`gen-public-agent-utility.test.mjs` corrected.

**4. Canonical doc.** `docs/reference/benchmarks/agent-utility.md` now states the three
promotion-blocking reporting requirements in its verdict contract. Docs regen pair run
(`llmstxt-generate` + `skills-sync`); neither produced a diff.

**5. Generator robustness.** `gen-public-agent-utility.mjs` no longer hardcodes a version-numbered
policy filename — the same defect class its own test comment says was fixed for the policy ID. It
now selects the single `utility-claim-policy.*.json` with `status: "active"` and FAILS generation
when zero or two are active, or when `unresolved` is non-empty, rather than rendering the sentence
"is active and fully resolved" over a policy that is neither. Covered by a new fail-closed scenario
(generator test: 98 → 110 assertions).

**6. Pre-campaign landmine — CLOSED (this was the one that could have cost money).** The §G
follow-up below was fixed rather than deferred, because a collapsed schema could have made a PAID
hero record unpromotable for a reporting artefact. Two composer defects, in
`scripts/jseval/jseval/utility_comparison.py`:

- `_stratified_breakdown` dropped a label whose paired observations collapsed. It now emits an
  explicit null entry (`available: false`, `n_paired_observations: 0`, collapse reason) — opt-in via
  `emit_null_strata`, passed only by the schema axis, so the corpus axis (§T.4) composes
  byte-identically to before.
- The deeper cause, found only by writing the test: the label vocabulary was derived from the
  *surviving pairs*, so a fully collapsed label had no label left to report and vanished anyway. The
  null-emitting path now takes its vocabulary from the whole `qid → label` map. **This is why the
  first fix looked correct and was not** — the drop happened one level above where it appeared to.
- `_default_schema_stratify` returned `None` for a single-schema cell, conflating "one schema
  measured" with "schema coverage never computed". It now returns `None` only when NO query carries
  a `question_type` — the genuine pre-768 byte-identity contract, still asserted.

The pre-existing contract test asserting a single-schema cell adds no key was flipped deliberately
(`test_single_schema_cell_still_reports_its_one_schema_but_untagged_adds_no_key`): v3's
`require_all_present` needs those two cases distinguishable. New tests:
`test_collapsed_schema_reports_an_honest_null_never_a_dropped_key` (the exact collapsed case) and
`test_null_strata_emission_is_opt_in_so_the_corpus_axis_is_unchanged`.

**Residual limit, stated rather than hidden.** A schema absent from a cell's *query set* entirely
still fails `schema_strata_reported`, because the composer has no access to the policy's
`known_schemas` vocabulary and cannot invent a null for a schema it never saw. That failure is
correct under `require_all_present`, and it is detectable pre-launch from the committed 20-qid list
(§E.7 row 7) rather than at compose time on a paid record — which is the property that mattered.

## §H Freeze execution record (2026-07-28)

**Freeze commit:** `docs(782): FREEZE the hero pre-registration protocol` — short SHA
**`28c2cced`** on `worktree-agent-a4fa71b45c297d4ac` — the commit that froze §E.1–§E.6 + §E.8 and
introduced this §H. Its SHA is recorded by the immediately following one-line commit rather than by
amending it into itself: an `--amend` that injects a SHA changes that very SHA, so the injected
value names an unreachable object. (624's "reference the commit introducing this line" convention,
resolved the non-regressive way. The freeze is `28c2cced`; the recording commit is not part of the
frozen content and does not touch §E.1–§E.6.) Every §E.7 row is disposed (§E.7); §E.1–§E.6
plus §E.8 are frozen; §E.0 records the two triggers and the active policy. The campaign driver
prepared by the same commit lives at `scripts/jseval/782-hero/` (`campaign-plan.md`,
`preflight.py`, `cells.v1.json`) — **dry artifacts only: no paid call was made, no backend was
started, no dataset or certification was touched.**

Two findings the freeze's own mechanical checks produced. Both are recorded rather than worked
around; neither is an agent's to decide.

### BLOCKER-1 — `schema_strata_reported` cannot pass on any hero stratum — **CLEARED 2026-07-28 by Amendment 1**

> **Resolution (appended, the finding below is left intact as the record of what was found).**
> Resolution 1 was taken: `known_schemas` → `["1_hop"]`, commit `4c0fd7bf`, under the founder's
> 2026-07-28 blanket delegation. `require_all_present` stays `true`. See §E.0 Amendment 1 for the
> verbatim rationale. `preflight.py` now reports **36 PASS / 0 FAIL / 6 PENDING**. Digest
> consequence: the 5th occurrence of the documented policy-identity re-pin class
> (`c3f98ebd…` → `88e98a93…`), with the sole-mover property proved by the runnable
> `test_amendment_1_repin_is_policy_identity_only_prior_pin_reproduces` rather than by prose —
> §G.1 item 1's lesson applied.

**What.** v3 declares `required_schema_strata.known_schemas: ["1_hop","2_hop"]` with
`require_all_present: true` (`scripts/jseval/utility-claim-policy.v3.json`). The gate
(`scripts/jseval/jseval/utility_claim_policy.py:517-542`) requires every known schema to appear in
each measured cell's `schema_stratified.by_stratum`. **All three hero strata's gold query sets are
100 % `1_hop`** — verified over the whole committed 50-query set of all eight 781 v2 cells, not just
the selected 20 (§E.8). So `2_hop` is absent from the breakdown on every cell, the gate fails, and
`claim_verdict` cannot be `accepted` — **the full $300 would buy an unpromotable record.**

**Why it wasn't caught earlier.** §G.1 item 6 fixed the *composer* so a schema whose pairs collapse
is reported as an honest null instead of vanishing, and stated the residual limit exactly: a schema
absent from the query set entirely still fails, "detectable pre-launch from the committed 20-qid
list (§E.7 row 7)". Row 7 is this freeze. The prediction held and the detector fired — before spend,
which is the property that mattered.

**Resolutions, both founder-gated (§E.6: policy ratification and §E.1 design changes are
founder-only).** Recorded without a recommendation *(resolution 1 was subsequently taken — see the
resolution note above)*:

1. **Amend `known_schemas` to `["1_hop"]`** in the v3 policy. Cheapest; honest (the hero corpora
   genuinely measure one schema); costs the 2-hop reporting axis 768 D4 introduced, and re-opens the
   policy-identity digest re-pin class (5th occurrence — `test_historical_fixture_semantic_digest_*`).
   Note this is a *policy* change after §E.0's freeze, not an edit to a frozen §E section.
2. **Add `2_hop` queries to the three hero corpora.** Preserves the reporting axis; requires
   regenerating the gold sets → new `query_gold_sha256` and (if `corpus.jsonl` changes) new
   `corpus_signature` → **full 781 re-certification** → §E.2 P1's pinned table must be re-issued,
   which means re-freezing §E.2. Expensive and slow.

Until one lands, §E.0's `launch eligibility` stays **BLOCKED**. `preflight.py` fails closed on this
item by design. *(Resolution 1 landed 2026-07-28; the item now passes.)*

### FINDING-2 — the committed closed-book measurement is at haiku; P3's bar is sonnet

`scientific_gates.closed_book.observed` in both 781 certifications reads
`{"closed_book_accuracy": 0, "n_queries": 50, "model": "haiku", "date": "2026-07-27",
"method": "closed-book-slot-guess"}`. Two different bars read it:

- The **policy gate** `closed_book_at_hero_tier`
  (`utility_claim_policy.py:478-515`) requires only a numeric accuracy ≤ `0.1` and a **non-empty
  model name** — it does **not** check the measurement tier. The committed haiku measurement
  therefore **passes the gate as it stands**.
- **§E.2 P3**, this protocol's own pre-launch rule, requires `0.000` measured **at sonnet**. That
  measurement does not exist. P3 is *not* satisfied by committed artifacts.

P3 is frozen and is the stricter bar, so a **fresh sonnet closed-book measurement on all three
strata is a mandatory pre-launch step** — it is Step 0b in `campaign-plan.md`. It is a real (small)
paid step: 3 strata × 50 queries, no tools, no corpus. Its cost is charged to the $300 cap and
recorded in the spend ledger like any other phase. It does not change the certification files; it is
attached as the campaign's own P3 evidence in the run dir. **This is not a licence to relax P3** —
if the sonnet measurement is anything other than `0.000`, §E.6 stop rule 3 applies.

### Pointer corrections made at freeze (operator-facing, pre-freeze so not frozen-section edits)

- **P2's certification pointer** moved from `member.v1.json` to `structural-certification.v1.json`.
  `member.v1.json` carries no `status` key at all and its `remaining_gates` still lists all four
  scientific gates — pre-#311 residue. The machine authority is
  `corpus_certify.py:815-817`, which reads `status == "fully-certified"` from the certification
  file. An operator following the original pointer would have concluded the trigger was unmet.
  (Logged to the observations inbox as a repo-side residue to sweep separately.)
- **§E.1's selection rule** is now the mechanically-forced truncation rule rather than an abstract
  "deterministic committed rule", with the certification-digest reason stated inline so a future
  agent cannot "improve" it into a seeded sample and silently break certified identity.

## §I Campaign log — run 2026-07-28 (appended post-freeze; dated history, amendments per §E.0 convention)

Authoritative evidence: `scripts/jseval/782-run-2026-07-28-hero/` (records, calibrations, judge
overlays, closed-book, leak-checks, §E.4 derived JSONs, both ledgers, window-1 records). Register
entry: F-043. This section is the narrative index, not a second authority.

**Window 1 (morning):** s1 void run (mixed-model guard poisoned 120/120 cells — the CLI's
background-haiku calls; the frozen plan had dropped 624's `--agent-env` mitigation) → stop rule 5
invoked → **Amendment 2** (uniform `--agent-env ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-sonnet-5` +
`CLAUDE_CODE_SUBAGENT_MODEL=claude-sonnet-5`; max_budget re-derived $0.80 by the frozen clamp rule
on the measured censored p95). s1/s2/s3 then completed comparable:true, judge overlays real
(out-of-band llama on 8081 through the Head /v1 proxy after diagnosing the eval-mode
`llm=True` silent no-op; polarity pre-checked both ways). **Step-5 compose FAILED CLOSED:**
`agent_cohort_key` split — the run dir lived inside the tracked tree, so `source_git_state`
drifted between strata (untracked 24→35→46) and `git_dirty` was true throughout (§E.2 R1
violated; the step-g between-strata re-assertion was not executed — owned in the incident
ledger). Unrepairable in-place (identity is baked; never hand-patch). Window-1 spend ~$130.

**Window 2 (afternoon):** identical frozen parameters; run dir relocated to gitignored `tmp/`
(ignored paths never enter the untracked fingerprint), calibrations reused (same git_sha
bea1ac37 + CLI 2.1.220, 624 precedent), R1 porcelain-0 asserted mechanically before EVERY
stratum launch. Two externally-induced incidents, both fail-closed with $0-to-small waste and
both root-caused (serve backend killed as a harness-tracked task mid-s1 → all long-running
processes now run detached; s2 pre-cell stray-root abort → the leak guard correctly refused a
dataDir still carrying s1's watched root; purged via `DELETE /api/indexing/roots`,
deletedJobs=1001). s1/s2/s3 completed comparable:true, identity clean (untracked 0, dirty
false), ONE cohort. Judge overlays ×3 clean (flips 1/0/4, agreement 0.887–0.984,
call_failures 0). **THE COMPOSE SUCCEEDED** — `combined/utility-comparison-cross-corpus.v1.json`,
semantic_digest `e2bb70c3…`. Window-2 spend ~$148; campaign total ~$278 of the $300 cap.

**Verdict as recorded:** `rejected / inconclusive / addition_b`; all three strata
`adoption-only`; 29/30 gates pass. **FREEZE DEFECT #2 (escalated, not patched):** the sole
failing gate `corpus_certification_complete` demands `cert.query_count == cell.query_count`
(`utility_claim_policy.py:414-423`) — the certifications certify the 50-query committed set,
the frozen design runs the 20-qid subset; 50 ≠ 20 always. Same class as BLOCKER-1, reachable
only at compose. Code-certain counterfactual (`utility_claim_policy.py:852-867`):
**accepted / adoption-only**. Founder decision requested: policy v4 with a subset-aware
certification gate (keyed on the frozen `selected_query_sha256` in `cells.v1.json`) + an
offline re-compose ($0, no re-measurement, no new cells). Until then the record stands as
rejected — the verdict is not re-read into something better.

**Measured (95% CI beside exact-McNemar p; power line: no effect detected at n=60/stratum):**
enron-1k Δacc −0.1964 [−0.375, −0.018] p=0.063; enron-10k −0.1304 p=0.146; legal-1k +0.0222
p=1.0. Window-1 signs identical (−0.154 / −0.093 / +0.022). Adoption rate 1.0; tool surface
verified 180/180 single hash; zero leak-suspect cells. Honest headline: the sonnet agent
reliably adopts the tool; the addition arm shows no accuracy benefit and is point-negative on
enron at this budget. Note the designed-in conservative asymmetry: B-arm cells exhaust the
$0.80 budget more often than A (10k: 12/60 vs 4/60) under `resource-exhaustion-as-failure`.

**Publication:** nothing here publishes; 623 pipeline remains founder-only per §E.5.

---

## §J Policy v4 re-compose (2026-07-28, founder-authorized)

**Founder authorization (verbatim, 2026-07-28):**

> firstly i would be up for upgrading the rule, but i think we shouldnt rerun today

Scope granted: the rule upgrade **and** a $0 OFFLINE re-compose of the existing window-2
evidence. Scope withheld: any re-measurement. **No new paid cell was run; total campaign spend
is unchanged at ~$278.** Publication (623) remains founder-only per §E.5 and is not touched here.

### J.1 The defect

`corpus_certification_complete` compared `cert.query_count == cell.query_count` **exactly**
(`scripts/jseval/jseval/utility_claim_policy.py:418` under v3). The 781 structural
certifications certify the **50-query committed gold set**; the frozen §E.1 design measures the
pre-registered **20-qid leading prefix** (`--max-queries 20`, §E.8). 50 ≠ 20 on every stratum,
so **no run under the frozen design could ever pass this gate** — while `query_gold_sha256`,
the identity that actually chains the certified queries, matched on all three strata all along.
A count comparison was standing in for a subset-identity check. Same defect class as BLOCKER-1
(caught at freeze); this one was reachable only at compose, on paid evidence.

### J.2 The gate design (policy v4, `agent-utility-public-v4`)

v4 is **v3 verbatim** — every threshold, every required stratum, every requirement, every
semantics block — plus ONE additive requirement, `certified_query_subset`. No threshold was
tuned; no stratum changed; no bar was relaxed. `utility-claim-policy.v3.json` flips to
`status: "superseded"` with `superseded_by: "agent-utility-public-v4"`, mirroring exactly how
v3 superseded v2 (`utility-claim-policy.v1.json`).

Like every v3 additive gate, the branch is **conditional**: it fires only when the selected
policy declares the requirement, so a record evaluated under v1/v2/v3 projects a byte-identical
verdict.

| Element | `file:line` |
|---|---|
| Requirement registered (`certified_query_subset`) | `scripts/jseval/jseval/utility_claim_policy.py:50` |
| Active-policy pointer → v4 | `scripts/jseval/jseval/utility_claim_policy.py:83-85` |
| Directly-superseded (v3) accessors | `scripts/jseval/jseval/utility_claim_policy.py:108-122` |
| `_derived_query_subset_identity` (record-side digest) | `scripts/jseval/jseval/utility_claim_policy.py:189-228` |
| `_certified_query_subset_ok` (the three-legged check) | `scripts/jseval/jseval/utility_claim_policy.py:231-260` |
| Conditional wiring inside the gate | `scripts/jseval/jseval/utility_claim_policy.py:514-557` |
| The gate emission itself (shape unchanged) | `scripts/jseval/jseval/utility_claim_policy.py:552-557` |
| Policy document (semantics + per-stratum `qid_list_sha256`) | `scripts/jseval/utility-claim-policy.v4.json` |
| Schema (`qid_list_sha256`, requirement, semantics block) | `scripts/jseval/utility-claim-policy.v1.schema.json:34-35,80,85` |
| v3 supersede stamp | `scripts/jseval/utility-claim-policy.v3.json:5-6` |
| Preflight accepts the frozen policy **or its declared successor** | `scripts/jseval/782-hero/preflight.py:78-117` |

**The rule.** With `certified_query_subset` declared, `corpus_certification_complete` passes iff
all of:

- **(a)** `cert.query_gold_sha256 == cell.query_identity.sha256` — unchanged. The FULL committed
  gold file still chains: the harness hashes the whole queries file's bytes and truncates only
  the sample rows, so a rewritten subset file fails here.
- **(b)** `cell.query_count <= cert.query_count`. A run claiming MORE queries than the
  certification covers is not a subset and is refused regardless of any declared digest.
- **(c)** the cell's **derived** subset identity equals this policy's **pre-registered**
  `required_strata[*].qid_list_sha256` for that `stratum_id`.

All other equality checks (dataset / member / size / query_variant / corpus_signature / snapshot
cross-validation) are untouched. **A full-count run (`cert.query_count == cell.query_count`)
short-circuits to the v1/v2/v3 branch verbatim**, so no historical verdict moves and a full run
needs no subset identity.

**What the derived identity is, and why this one.** The composed record carries **no** subset
hash — verified, not assumed: `selected_query_sha256` / `qid_list_sha256` appear nowhere in
`combined/utility-comparison-cross-corpus.v1.json`, and `utility_recompose` /
`agent_utility_inspect` emit neither. What the record *does* carry is
`campaign_identity.expected_cells` — already load-bearing for `itt_strata_derived` and
`source_identity_complete`, so keying on it is a **projection of the record's one selection
authority, not a second one**. Each entry is `"<condition>|<seed>|<qid>"`, and the harness mints
`qid = f"q{i}"` with `i` a **0-based ordinal** into the committed file truncated in committed
order (`agent_utility_inspect.py:1497-1505`: `Sample(id=f"{c}|q{i}")` over `rows[:max_queries]`).
The gate requires those ordinals to be the leading prefix `{0 … n−1}` with `n == query_count`
— the only certification-preserving selection, since any other subset would need its own gold
file and would break (a) — then digests the canonical 1-based labels `q0001…q{n:04d}` with the
**same recipe** that froze `qid_list_sha256` pre-launch (`782-hero/preflight.py:44-58`:
`sha256(utf8("\n".join(labels) + "\n"))`).

That recipe binds the gate to the **pre-registered** value rather than to a number derived today
from the record under judgement: the three strata's derived digests all reproduce
`7b7856e8d8d0f3ce4ca8499798ce93ac1e5aec58633fc03fe514d7f3853c5b5b`, copied verbatim from the
frozen `scripts/jseval/782-hero/cells.v1.json` §E.8 block. A test reads both files and compares
them, so a drift in either fails
(`test_checked_in_v4_policy_pins_the_frozen_782_subset_digests`).

The sibling `selected_query_sha256` is deliberately **not** gated here: no query text survives
into the composed record, so a post-hoc verdict cannot verify it. It remains the PRE-LAUNCH
content pin, asserted by `782-hero/preflight.py:172-186` against the committed gold cell before
any cell is paid for. Pinning a digest the gate cannot check would be decoration.

**Fail-closed, with no permissive default.** A record whose `expected_cells` are absent,
malformed, non-`q<digits>`, not a leading prefix, or of a length other than `query_count` has
**no derivable subset identity and FAILS** — as does a stratum with no pre-registered
`qid_list_sha256`. Four refusal branches are exercised by name
(`scripts/jseval/tests/test_utility_claim_policy.py:504`, `:517`, `:529`, `:548`), beside the
acceptance case (`:481`), the inertness proof (`:562`) and the frozen-digest cross-read
(`:592`).

**Frozen artifacts untouched.** `scripts/jseval/782-hero/cells.v1.json` and `campaign-plan.md`
still name `agent-utility-public-v3` and are **left unchanged** — they are the freeze record, and
§E.2 R5 makes any post-first-cell edit to them void claim eligibility. The chain is expressed
where it belongs instead: in `superseded_by`, which `preflight.py` now walks.

### J.3 Re-compose and assertion results

Same frozen Step-5 invocation as the campaign (identical log dirs + judge overlays,
`--contamination-class private-synthetic --confidence-tier C`), with v4 active, into
`scripts/jseval/782-run-2026-07-28-hero/combined-v4/`.

Control run first (**interrogate-results**): re-composing the same inputs with v3 still active
reproduced the committed record's `semantic_digest e2bb70c3…` and differed from the committed
file in `composed_at` alone — so the re-compose path is deterministic and any v4 difference is
attributable to the policy, not to drift in the harness.

**Re-composed verdict** (`combined-v4/utility-comparison-cross-corpus.v1.json`,
`semantic_digest c5a75457b264e0cfdecf5ab1ac552d3430a93300f3241766b9c72a49be1560bb`):

```json
{
  "status": "accepted",
  "accepted": true,
  "outcome": "adoption-only",
  "arm": "addition_b",
  "strata": "all_required",
  "policy_id": "agent-utility-public-v4",
  "policy_hash": "d0c0d1ab9e621b545635bbadf70d9a1f53494ffa312e0aa8829112c2f8e48d39",
  "policy_status": "active",
  "unresolved": [],
  "reasons": []
}
```

Assertions, all machine-checked against the committed v3-scored record:

- **`corpus_certification_complete` passed.** It is the **only** gate whose value changed:
  `30/30` gates pass, `reasons: []`, and the gate-name set is identical between the two records
  (0 regressions, exactly 1 newly-passing gate).
- **The counterfactual held exactly.** `accepted / adoption-only`, per-stratum outcomes
  `adoption-only` on all three strata — precisely what the incident ledger predicted from
  `utility_claim_policy.py:852-867` without re-running.
- **Measured numbers are byte-identical.** A recursive diff of the two records reports **10
  differing nodes, ALL under `/claim_verdict`, `/semantic_digest`, `/composed_at` — zero
  elsewhere**. `measured`, `estimands`, `cohort`, `comparability`, `tool_call_assertions`,
  `coverage`, `corpora`, `conditions`, `outcome_rule`, `external_baselines`, `seed_count`,
  `confidence_tier` and `statistical_alpha` each compare equal as canonical JSON. Every headline
  delta in §I stands unchanged: enron-1k −0.1964, enron-10k −0.1304, legal-1k +0.0222, adoption
  1.0, surface verified 180/180.

**The v3-scored record at `scripts/jseval/782-run-2026-07-28-hero/combined/` remains committed
unchanged as dated history** — it is what the campaign produced under the policy in force at the
time, and it is not re-scored, edited, or replaced. `combined-v4/` sits beside it as the
founder-authorized offline re-compose, and the honest headline is unchanged by either: the sonnet
agent adopts the JustSearch MCP tool at rate 1.0 when offered, with **no measurable accuracy
benefit** and point-negative deltas on enron at n=60/stratum. `adoption-only` is a promotion
class, not a benefit claim — v4's `triple_reporting_semantics` still forbids
adoption-rate-as-benefit.

### J.4 Verification

- `test_utility_claim_policy.py` + `test_utility_evidence.py`: 124 passed.
- Full jseval suite: **2535 passed, 2 skipped, 1 failed** — the sole failure is the known
  `test_percentile_within_bounds` flake under full-suite load; it passes in isolation (7 passed).
- `gen-public-agent-utility.test.mjs`: OK (110 assertions); generator re-run and `--check` in sync
  (README / RESEARCH / benchmarks doc now name `agent-utility-public-v4`).
- `782-hero/preflight.py`: **36 PASS / 0 FAIL / 6 PENDING** — identical to the freeze result.
- Historical-projection byte-identity: `test_v4_repin_is_policy_identity_only_prior_pin_reproduces`
  reconstructs the v3 document in its pre-supersede shape and reproduces the v3-era pin
  `88e98a93…` exactly; the pre-amendment pin `c3f98ebd…` and the v2 pin `ed81f79b…` still
  reproduce from their own reconstructions. The active-policy fixture digest re-pins to
  `e3c3c9fd…` (6th occurrence of the same policy-identity re-pin class).

---

## §J.5 Addendum 2026-07-29 — claim policy v5 exists; this record stands as dated history

Appended post-hoc under the §E.0 convention. **Nothing in §I or §J is re-scored, edited or
replaced, and no cell was measured.** Written here only so a reader arriving at §J does not take
`agent-utility-public-v4` to be the active policy.

**What changed upstream.** Tempdoc 791 (campaign-v2 charter) axis 4 landed on 2026-07-29:
`agent-utility-public-v5` is now the ACTIVE claim policy (v4 verbatim + the additive
`question_level_primary` requirement), and v4 is `status: superseded`. v5 makes the QUESTION the
unit of analysis for the accuracy outcome — a paired sign-flip permutation over per-question mean
deltas and a question-cluster bootstrap interval — because the CELL-level exact McNemar reported
throughout §I counts a question's 3 seed replicates as 3 independent observations and so
understates `p`. The cell-level numbers stay reported, labelled descriptive.

**This record is not restated under v5.** `combined/` (v3-scored) and `combined-v4/` (the
founder-authorized offline re-compose) remain committed exactly as they are. A $0 offline
re-evaluation under v5 was run to report what would change, and is recorded in tempdoc 791, not
here; its result is `accepted / adoption-only`, 31/31 gates, `reasons []` — **unchanged**, because
`adoption-only` is an adoption-rate promotion class that never rested on significance. A control
re-compose under the pre-supersede v4 document reproduced this record's `semantic_digest
c5a75457…` exactly, which is what makes that attribution sound.

**The §I p-values remain what they were, and remain cell-level.** Read them as descriptive. On the
question-level primary, no stratum in this cohort clears α = 0.05 under either test; the
disagreement that motivated v5 (raw-EM window-2 enron-1k: cell-level 0.0446 vs question-level
0.1358) is a scoring-tier apart from this record's judge-overlaid numbers, and tempdoc 791
documents the reconciliation cell-for-cell.

**Both defects in §H/§J.1 are now $0 to find in advance.** `jseval utility-policy-dryrun` replays a
frozen design against a policy before the freeze commit; run against the real
`782-hero/cells.v1.json` it reports BLOCKER-1 and FREEZE DEFECT #2 as `structurally-impossible`,
per stratum, with the exact diagnosis each originally took a launch and a compose to surface.
Those two replays are committed regression tests (`tests/test_utility_policy_dryrun.py`).
