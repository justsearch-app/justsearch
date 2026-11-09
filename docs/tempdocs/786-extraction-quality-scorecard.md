---
title: "extraction-quality scorecard — stand up the 623 §F sibling: measured numbers for the quality dimension the retrieval scorecard explicitly excludes"
type: tempdocs
status: "chartered (2026-07-22). Measurement lane; the corpora already exist in the catalog (OHR-bench family, tempdoc 252) — the work is running the current stack against them and wiring the envelope discipline retrieval already has."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: extraction / measurement
related:
  - 623 (release scorecard)  # §F names the extraction-quality sibling this lane builds
  - 252 (OHR-bench corpora)  # ohr-bench-{clean,got-moderate,mineru-moderate,tika-pdf} — 962 queries each, already cataloged
---

## §A. Problem

**Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence. The Release Scorecard states its own boundary: it measures retrieval ranking quality and
"does NOT measure document extraction / OCR / VDU routing quality" (623 §F). But the
product's promise is search over real, messy files — PDFs, scans, office docs — where
extraction quality upper-bounds everything downstream. The measurement assets exist and are
idle: four OHR-bench variants in the catalog (ground-truth text vs GOT-OCR vs MinerU vs
original-PDFs-through-Tika, 962 extractive queries each, last validated 2026-03), which
isolate the extraction variable by construction (same queries, same content, different
extraction paths). There are currently NO numbers for the shipped extractor at HEAD.

## §B. Scope

1. **Baseline sweep:** hybrid-mode runs across the four OHR-bench variants at HEAD
   defaults. The clean-vs-tika delta IS the extraction-quality cost of the shipped Tika
   path; clean-vs-GOT/MinerU brackets what better extraction would buy.
2. **Per-domain breakdown** (OHR-bench spans 7 domains) — extraction cost is not uniform;
   the table should say WHERE the shipped path loses (tables? scanned? multi-column?).
3. **VDU routing check:** where the VDU-eligibility path exists (VduEligibility tests,
   local Tesseract env), measure whether routing decisions correlate with the docs the
   Tika path loses — routing precision is only meaningful against measured loss.
4. **Scorecard wiring:** an internal extraction scorecard section (register or a sibling
   doc per 623 §F's design), with the same cohort-envelope noise discipline retrieval has;
   NOT the public scorecard (623 publication stays founder-gated).
5. **Catalog hygiene ride-along:** re-validate the four OHR rows (Last Validated 2026-03)
   against the current committed content, same as any dataset touch.

## §C. Acceptance

- A filled table: 4 variants × hybrid nDCG@10 (+ per-domain) at a named git SHA, register-
  recorded with confidence tiers.
- The extraction-cost headline (clean minus tika) stated with CI, plus the bracketed
  headroom (GOT/MinerU deltas).
- A routed conclusion: either "extraction cost is material → charter the improvement lane
  with these numbers as its baseline" or "cost is within X% → extraction is not the
  bottleneck; recorded, no lane" — both are wins; the void is the only loss.

## §D. Notes

- Pure measurement lane: no extractor changes in scope. Any improvement work gets its own
  charter with this lane's table as the baseline (instrument-first, again).
- Windows local env caveat: the Tesseract/tessdata-dependent tests are known-red locally
  (expected-state) — the eval path must not silently depend on that env; verify the
  extraction actually ran per-variant (interrogate the doc counts, not just the scores).

## §E. Baseline sweep executed — the §B.1 table, at HEAD defaults (2026-07-28)

The four-variant hybrid sweep ran on 2026-07-28 at `git_sha adaf7b44`, one arm per variant,
962 queries each. Artifacts (per-arm `summary.json`, `projections/bootstrap_ci.json`,
`hybrid_per_query.json`, `hybrid_run.trec`, worker metrics):
`tmp/786-sweep/ohr-bench-{clean,got-moderate,mineru-moderate,tika-pdf}/`.

### §E.1 The table (§C acceptance item 1)

All four arms: `doc_count` 1000, `query_count` 962, `final_doc_count` 1001,
`ann_proof_status` PASS, `error_count` 0, `comparable: true`, identical observed leg set
`branch_fusion+chunk_merge+cross_encoder+dense+hybrid+query_classification`. Values copied
from each arm's `summary.json` → `per_mode.hybrid.aggregate_metrics`; CIs from
`projections/bootstrap_ci.json` (95%, 1000 resamples, `ci_status: ok`).

| Variant | nDCG@10 | 95% CI | P@1 | R@10 | RR@10 | AP@10 | Δ vs clean |
|---|---|---|---|---|---|---|---|
| clean (ground-truth text) | **0.9512** | 0.9406–0.9620 | 0.9127 | 0.9875 | 0.9400 | 0.9394 | — |
| GOT-OCR moderate | 0.8377 | 0.8171–0.8595 | 0.7817 | 0.8888 | 0.8221 | 0.8211 | −0.1135 (**−11.93%**) |
| **Tika PDF (the shipped path)** | **0.8205** | 0.7984–0.8422 | 0.7661 | 0.8649 | 0.8052 | 0.8057 | **−0.1307 (−13.74%)** |
| MinerU moderate | 0.7249 | 0.7008–0.7500 | 0.6466 | 0.8046 | 0.6984 | 0.6995 | −0.2262 (**−23.78%**) |

### §E.2 The headline, with CI (§C acceptance item 2)

**The shipped Tika path costs −0.1307 nDCG@10 (−13.74%) against clean text** on this
corpus at HEAD defaults. The clean and tika CIs do not overlap (0.9406–0.9620 vs
0.7984–0.8422), so the extraction cost is separable from measurement noise.

The bracketed headroom is narrower than 252's lexical-only measurement implied:

- **GOT (a better extractor) buys back only ~0.017 nDCG** over Tika — and the two CIs
  **overlap** (0.8171–0.8595 vs 0.7984–0.8422), so *GOT-vs-Tika is not separable at
  n=962*. Do not read GOT as a measured improvement over the shipped path.
- **MinerU is decisively worse than Tika** (CIs disjoint: 0.7008–0.7500 vs 0.7984–0.8422),
  reproducing 252's finding that MinerU's empty/trivial-extraction rate dominates its
  penalty.

**Reordering vs 252 (interrogate-results).** 252 measured lexical/BM25-only with
`JUSTSEARCH_AI_DISABLED=true` and reported clean 0.9487 / GOT 0.8090 / Tika 0.7947 /
MinerU 0.6382 (taxes −14.7% / −16.2% / −32.7%). This sweep is a *different measurement*
— full hybrid pipeline, CE on, chunk branch active — so the numbers are not directly
comparable to 252's. What changed is that **every tax shrank** (Tika −16.2% → −13.74%,
MinerU −32.7% → −23.78%) and **Tika moved from behind GOT to statistically tied with
it**. The honest reading: the full pipeline's dense + chunk legs partially compensate for
extraction noise, and they compensate *most* where the noise is worst. This is an
observation about the two configurations, not a causal attribution — no arm isolated the
compensating leg, so "which leg absorbs the extraction noise" remains unmeasured.

### §E.3 Routed conclusion (§C acceptance item 3)

**Extraction cost is material: −13.74% at the shipped defaults, CI-separable from clean.**
This clears §C's ">X%" bar and 252's >5% decision gate by a wide margin, so the routing is
"charter the improvement lane with these numbers as its baseline" — *but* with one
correction to the presumed lever: **the obvious swap-to-a-better-OCR move is not measured
to help.** GOT is the best available alternative extraction in this corpus family and it
is statistically indistinguishable from the shipped Tika path. An improvement lane should
therefore target the residual **clean-minus-Tika 0.1307**, not the **GOT-minus-Tika
0.017** — i.e. the VLM/structure-recovery direction F-009 already names, not a swap to a
conventional OCR engine.

### §E.4 What this sweep did NOT deliver (scope honesty)

- **§B.2 per-domain breakdown is NOT in these artifacts.** `projections/
  stratified_metrics.json` buckets by `decision_kind` / `first_relevant_rank` /
  `query_length`, not by OHR-Bench's 7 domains — so "WHERE the shipped path loses
  (tables? scanned? multi-column?)" is still unanswered. It needs a domain-labelled
  stratification the projection does not currently emit.
- **§B.3 VDU routing check** — not run.
- **§B.5 catalog re-validation** — the four rows' Last Validated stays 2026-03 (this
  sweep measured against them but did not re-validate their content).
- The §B.1 caveat about verifying extraction actually ran per-variant **is satisfied**:
  all four arms report `final_doc_count` 1001 and `error_count` 0, and the four
  `corpus_identity.signature` values are pairwise distinct
  (`641ec0b7ae96` / `ea1dd54da222` / `f306dc80d5e6` / `f90ba56d8e73`), so the arms did
  index four genuinely different corpora rather than silently re-measuring one.
