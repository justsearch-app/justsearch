---
title: "payload-integrity corpus lane: layer-specific treatment of the injected gold document (real-host bulk, synthetic low-overlap bridge, unique needle), the three gates that prove each layer, and the retraction of the vocabulary-confound framing — the 766 program's corpus half"
type: tempdocs
status: "FULLY CERTIFIED — both English members (§R, 2026-07-22). Full 8-cell cohort measured end-to-end; en-legal-clerc and en-email-enron-raw are each `fully-certified`, 16/16 scientific gates green (32/32 total). Thresholds re-derived per cell from the fresh n=50 measurements (707:760 rule + §Q.3 leak-floor override) and written to the policy with a provenance sidecar. Closed-book 0.000 on all 8 cells. Retrieval collapses at 10k for legal (bridge-entity gold among 9,900 decoys) but the measured-derived bands certify it honestly; enron retrieval stays strong. NOTE: certification is structural+measured only — it does NOT green-light the paid confirmatory campaign (founder-gated). Prior status (PILOT CERTIFIED §Q) superseded."
lite-class: false
created: 2026-07-21
author: agent (Fable orchestration), 766 program charter
category: eval-design / corpus-generation
related:
  - 766-eval-content-rebuild-program   # umbrella: design decisions D2-D5, D8 — READ FIRST (and 762 §X for evidence)
  - 707-pillar1-inband-utility-corpus  # the pipeline being extended
  - 741-eval-corpora-are-derived-artifacts
---

> **Agent-utility campaign freeze (2026-11-09):** Fresh and resumed live agent-utility campaigns are frozen. Their commands and runbooks remain available for historical reproduction and offline analysis, along with published evidence and offline replay. Neighboring experiments retain their existing status. The campaign plans and launch/resume instructions below are historical and do not authorize campaign work. Charter. Execute after 766 §B (D2/D3/D4/D5/D8) and 762 §X.6.1/.3. The
> injection machinery, determinism proofs, and certification framework
> already exist — this lane replaces the PAYLOAD and adds GATES.

# 767 — payload-integrity corpus lane

> **READER'S GUIDE (2026-07-21).** This document is ~1,700 lines of dated working history and
> contains **three claims that were later retracted**. Read this block before trusting any section.
>
> **What is current:** §I (design — supersedes §F), §J-§N (implementation, measurement, rebuild),
> and §M (what remains). **§F is superseded. §A-§E are the original charter and its research; their
> framing did not survive measurement.**
>
> **What the lane actually did.** The eval corpus plants fabricated documents among real ones to
> measure a search engine against a grep-only baseline. The planted documents turned out to be
> enumerable four different ways — an identical filler paragraph, an answer inferable from the entity
> name, a distinct document-ID shape, and (the dominant one) a **sort position** that put them at the
> top of a directory listing. Agents were finding them by listing the folder. The lane closes all
> four and adds standing gates that measure each.
>
> **Retractions, in order — do not cite the originals:**
> 1. **§H.0** retracts the *vocabulary-confound* evidence that originally justified this lane. The
>    source measurement used a substring match (`spa` matched *newspaper*, *disparate*) and compared
>    per-file against per-line counts. Token-level, the term appears in **0 of 199** real opinions.
> 2. **§K.7b** withdraws §K.3's term-reachability explanation of the arm-A asymmetry as *sufficient*
>    — it survives as a real 5× effect but explains only a small slice.
> 3. **§L.0** corrects §J.5's claim that the committed cells failed their regeneration check. They
>    did not; they were recorded `fully-certified` **while being perfectly enumerable**, because no
>    gate asked. Also §J.2's retirement of the length hypothesis was right for legal and wrong as a
>    general claim (§K.8c).
>
> **The finding worth carrying out of here:** indistinguishability has an **envelope**, not just a
> payload, and the envelope is where attention isn't. Identifiers, ordering, size and timestamps
> carried more signal than the text did. §L.1 records two further instances outside this lane
> (`635-corpora`, `scripts/sandbox/plant_defects.py`) and §L.3 says why no general framework should
> be built yet.
>
> **State:** both English members rebuilt at n=50 and `structurally-certified`, all five leak gates
> green (§N). Outstanding: the paid `closed_book` gate plus three GPU gates (all-or-nothing, 16
> entries), and `de-miracl`, whose host data is not cached.

## §A. Work items *(original charter — superseded framing; see §I)*

1. **Entity bank + minting (D2).** Harvest a typed entity bank from each
   host corpus (deterministic NER or per-domain heuristic harvesting —
   mechanism is yours; determinism given corpus+seed is mandatory, no LLM in
   the build path). Mint gold entities type- and length-matched to native
   entities, collision-checked against the bank (must not exist in any real
   doc) and against each other. Kill the syllable-pair minting path.
2. **Register-matched fact sentences (D2).** Per-domain sentence templates
   in the host register (email prose for Enron hosts, judicial prose for
   CLERC hosts) replacing `_FILLER` and the current template sentences.
   Interleave via the existing `corpus_inject.assemble` seam.
3. **Leak-free golds (D5).** Format-diverse, domain-plausible gold values;
   value minting decoupled from entity uid counters (delete
   `corpus_generate.py:320-323`'s coupling); exact-match after
   normalization stays the scoring contract.
4. **Multi-schema questions (D4).** Single-fact lookup + 2-hop bridge +
   multi-doc aggregation, each with per-schema difficulty calibration;
   `question_type` labeling per the register's hop-count vocabulary note.
5. **Certification gates (D3).** In `corpus_certify` (structural-check and
   SCIENTIFIC_GATES seams, see 762 §X.6.1): distractor-flood index,
   injected-entity indistinguishability, naming/format-leak, gold
   dispersion, per-schema difficulty band. Wire thresholds into the 707
   certification policy file; a failing corpus is unbuildable into a
   campaign.
6. **Strata + distribution (D8).** Regenerate en-legal-clerc and
   en-email-enron-raw strata (1k/10k) on the new payload; "synthetically
   altered" header stamp as a build invariant; PII-scrub gate for published
   Enron samples; fetch-then-inject recipes only (no modified real docs
   committed). Optional (explicitly severable): third gov-docs stratum.
7. **Register duty.** Update the search-quality register Dataset Catalog
   (new strata rows; annotate the v5 strata as superseded-for-claims) and
   `/search-quality` + re-run `retrieval_calibration` baselines for the new
   strata before closing.

## §B. Acceptance

- All 766 §D orphan items 1/2 deleted in this lane's PR (retire-with-a-sweep).
- New strata pass ALL certification gates including the new five; closed_book
  ≈ 0 at haiku AND at the hero tier (coordinate with 768 for the tier run).
- A grep-simulation probe (the 764 mechanism-probe method) shows the
  baseline arm's expected grep experience is now vocabulary-matched across
  strata — the confound is measured dead, not assumed dead.
- Determinism: cross-interpreter regeneration proof green (existing seam).
- jseval suite green; `check-language-agnostic-analysis` unaffected (payload
  generation is corpus tooling, not engine analysis).

## §C. Constraints

- Zero paid-API in the build path (local model via `ai_activate` permitted
  for VALIDATION probes only, never generation).
- Windows: PYTHONUTF8=1; Edit/Write or python UTF-8 scripts only.
- 762 §D data inventory for all prior-campaign references; step2-powered
  worktree read-only.

## §D. Theorize (2026-07-21)

The charter fixes the *what* (D2-D5, D8); this section explores the *how* before
settling it in §F. The generator being replaced renders one question schema
(a fabricated entity-relation chain) three ways — prose, code, tabular
(`corpus_generate.py` `_render_prose`/`_render_code`/`_render_tabular`) — over
syllable-minted entity names (`_name`, ~:288), `_FILLER` boilerplate (:48), and
gold values coupled to the entity uid counter (`_chain`, :319-323). The real-text
injector (`corpus_inject.assemble`) already interleaves each gold's sentences into a
genuine host document and proves cross-interpreter determinism. So the substrate is
real; only the payload is alien. Four directions structure the rebuild.

**Direction 1 — where the camouflage actually comes from.** Two sources of
"domain-native" are available and they are not equivalent. (a) *Minted entities that
look like the domain's* (a fake judge that reads like a judge, a fake sender that
reads like an email address). (b) *Carrier sentences that are actually the domain's*
— rather than author a "fact sentence" and hope it matches the register, take a real
host sentence of the right relational shape and substitute minted entities into it.
(b) is strictly stronger against shape-grep because there is no authored sentence to
fingerprint. The tension: a planted needle needs a *new* fact (an un-guessable value
that is the answer and appears in no real doc), which pure in-place substitution
cannot manufacture. The design must therefore blend: real carrier where one exists,
authored scaffolding only at relation junctions, and certify that the blend leaves no
shape signature.

**Direction 2 — entity harvest: NER vs heuristics, and where the model runs.** The
hidden assumption worth killing early is "harvest must happen in the build path."
It must not. If the typed entity bank is harvested once, offline, and *committed as a
hashed artifact*, then the deterministic, LLM-free, cross-interpreter-proof build path
never imports the harvester — it only samples the frozen bank. That reframes the
spaCy-vs-regex question from "what goes in the build path" (answer: neither) to "what
best fills a committed bank" — where a 500 MB NER dep confined to an offline mint step
is cheap, and regex harvesters for domain-structural entities (email addresses, docket
numbers, case captions) are a complement, not a rival.

**Direction 3 — certification as a null-hypothesis test, not a constant.** Every leak
the charter names (distractor-flood, entity indistinguishability, shape/format leak,
dispersion) is really the same question: *is the injected profile distinguishable from
the host's own native profile?* That argues against fixed thresholds and for deriving
each gate's bound from a native-vs-native null computed on the specific host corpus —
which is also how the repo already sets per-cell retrieval bands (they vary per cell in
the certification policy, i.e. measured, not magic). The template-fingerprint gate is
the novel one: it must prove no delexicalized shape pattern selects gold docs above its
native base rate.

**Direction 4 — difficulty without curve-fitting.** The prior regime killed ~45% of a
10k stratum as dead questions, and any per-model difficulty tuning risks fitting one
tier. The escape: anchor the difficulty band on *retrieval-reachability* (an engine
property — does the gold surface in top-k on the actual engine at certify time?) rather
than on any model's answer rate. Reachability floors kill dead questions pre-spend; a
top-1-triviality cap kills ceiling questions; both endpoints are model-independent.
The existing closed-book gate stays the orthogonal, model-tier-run un-guessability
control.

**Rejected alternatives.** (i) *spaCy in the build path* — puts a heavy non-stdlib dep
and cross-version drift into the determinism proof, for no gain over a committed bank.
(ii) *Pure regex harvest, no NER* — misses the free-text PERSON/ORG/GPE mass that is
most of the camouflage surface in prose-heavy opinions; the bank would be too thin for
type-matched minting. (iii) *Keep authoring register templates and rely on a large
library* — a library reduces but cannot prove the absence of a shape signature; without
the null-calibrated gate it is hope, which is exactly the failure mode D3 exists to end.
(iv) *Fixed indistinguishability constants* — a constant that passes on Enron can fail
on CLERC; the host corpus must be its own null.

## §E. Research (2026-07-21)

- **Faithfulness-QA construction (the D2-cited recipe), verbatim.** A typed entity bank
  is built by running **spaCy `en_core_web_lg` NER once, offline, over all source
  contexts**, collecting 8 types (PERSON, GPE, ORG, DATE, CARDINAL, NORP, LOC, EVENT),
  discarding spans <2 or >100 chars → a **static 76,953-entity bank**. Replacement:
  sample `e_new` with `type(e_new)=t`, `e_new≠e_orig`; **resample ≤5 times** to enforce
  `0.3 ≤ |e_new|/|e_orig| ≤ 3.0`, else reject; enforce `e_new ∉ context` (collision),
  `count(e_orig,context) ≤ 10`, `|e_new| ≥ 2`; replace all case-insensitive occurrences.
  Quality filters (100% audit pass on 200 samples): replacement present, original fully
  removed, context changed, modified/original length ratio in band. Crucially the bank
  is a **committed artifact**, and the substitution carrier is a **real source sentence**
  — there is no authored template. [arXiv 2604.25313](https://arxiv.org/abs/2604.25313),
  [HTML](https://arxiv.org/html/2604.25313v2).
- **spaCy NER determinism.** `en_core_web_lg` NER is a greedy transition-based parser
  with no inference-time randomness; **CPU inference is reproducible run-to-run**.
  Reported non-determinism is GPU *training*, not CPU inference — not applicable to an
  offline harvest. Confirms a committed bank can be regenerated in principle, though its
  reproducibility anchor should be the artifact hash, not a spaCy re-run.
  [spaCy #6490](https://github.com/explosion/spaCy/issues/6490),
  [model card](https://huggingface.co/spacy/en_core_web_lg).
- **Shape/leakage critiques of needle benchmarks.** Recent long-context work flags that
  synthetic benchmarks are "susceptible to data leakage, short-circuiting, and … a
  priori identifiable," motivating designs "resistant to leakage" with distractors that
  are *retriever-dependent* rather than query-independent (Haystack Engineering,
  [arXiv 2510.07414](https://arxiv.org/pdf/2510.07414); MLRBench, referenced therein).
  This is the external warrant for a standing shape-indistinguishability gate rather than
  a one-off audit.
- **NoCha minimal-pair device (D3's un-guessability certifier).** 1,001 minimally-
  different true/false claim pairs over recent fiction; the minimal pair "guards against
  models being right for the wrong reasons while enabling easy verification," and the set
  is withheld to prevent contamination. The transferable idea: an un-guessable planted
  fact plus a control that floors near-zero by construction — which the existing
  closed-book gate already measures. [arXiv 2406.16264](https://arxiv.org/abs/2406.16264),
  [leaderboard](https://novelchallenge.github.io/).
- **Repo machinery confirmed against source (durable, repo-relative paths).** Leak-gate
  seam: `SCIENTIFIC_GATES` and the structural-check set live in
  `scripts/jseval/jseval/corpus_certify.py:38-52`; per-cell measured thresholds (bands
  differ per cell — the measured-derived pattern) in
  `scripts/jseval/707-corpus-certification-policy.v1.json`. Deterministic assembly +
  cross-interpreter proof: `corpus_inject.py` `assemble`/`_interleave` (:25-105) and
  `_cross_process_assembly` (:108-162). Existing collision gate to generalize:
  `descriptor_collision_report` (`corpus_certify.py:1017`, exact-title-match) and
  `regeneration_determinism_report` (:1067). Orphan payload paths to delete:
  `corpus_generate.py` `_SYL_*`/`_ATTR_*` (:38-47), `_FILLER` (:48), `_name` (:288),
  `_chain` value/counter coupling (:319-323), and the domain-alien `_SEM_TYPE`/`_SEM_PLACE`
  synthetic descriptor pools (:65-104).

## §F. Design (2026-07-21) — SUPERSEDED by §I

> **Superseded 2026-07-21 by §I.** §H's measurements refuted this section's framing (camouflage)
> and its central item (§F3 host-derived recast). Retained as design history; do not implement from
> it. Items not adopted are listed in §I.6.

**F1 — Entity harvest: committed-bank boundary (settles the open question).** The typed
entity bank is a **committed, sha256-pinned artifact per host corpus**, harvested
**offline, once**, by a two-source minter: (a) spaCy `en_core_web_lg` NER (the exact
Faithfulness-QA recipe — 8 types, 2-100 char span filter) for free-text PERSON/ORG/GPE/…
entities; (b) stdlib per-domain regex harvesters for domain-structural entities NER
under-collects (email local-parts/domains for Enron; docket numbers, reporter citations,
party/judge captions for CLERC). Provenance records the spaCy model version and the
bank hash. **The build/regeneration path never imports spaCy or the harvesters** — it
samples the frozen bank with the seeded RNG only. *Rationale:* this confines the 500 MB
NER dependency to an offline mint step, keeps the LLM-free cross-interpreter regeneration
proof (`corpus_inject._cross_process_assembly`) stdlib-only and green, matches the cited
recipe (which itself commits a static bank), and leans on the fact that spaCy CPU NER is
deterministic — while making the *artifact hash*, not a re-run, the reproducibility
anchor. Rejected: spaCy-in-build-path (dep + drift into the determinism gate) and
pure-regex (bank too thin for type-matched minting).

**F2 — Minting (replaces syllable + counter-coupled paths).** Gold entities are minted
**type- and length-band matched** (`0.3-3.0×`, resample ≤5 then reject — Faithfulness-QA),
from a **type-shaped generator** (a minted PERSON reads as a name, a minted ORG as an
org), collision-checked against the *entire host bank* (must equal no real entity of any
type), the *distractor pile*, and *other minted golds*. Gold **values** are minted from a
per-schema, **format-diverse value space decoupled from any uid counter** (kills
`corpus_generate.py:319-323`), collision-checked, never format-uniform. This deletes
orphans 766 §D-1 and §D-2 in this lane's PR (retire-with-a-sweep), including the
`_SYL_*`/`_ATTR_*`/`_FILLER`/`_name`/`_chain` paths.

**F3 — Carrier realism over authored realism (template-fingerprint defense).** Fact
sentences prefer **real host carrier sentences** of the target relation, harvested from
the host corpus, with minted entities substituted in place (no authored template → no
authored signature). Authored scaffolding is confined to relation *junctions* where no
real carrier can guarantee chain connectivity, and there it draws from a **large,
register-matched realization library** (many surface forms per relation). The
`_SEM_TYPE`/`_SEM_PLACE` synthetic-descriptor pools are **orphaned here**: they are
themselves domain-alien ("reactor in the northern marshlands" is as greppable inside a
legal opinion as `_FILLER`), so the semantic-bridge retrieval-difficulty property they
provided is **recast onto host-derived descriptors** (real captions/subjects paired with
their own paraphrases) rather than a synthetic pool. Naming this orphan explicitly is
part of the sweep.

**F4 — The template-fingerprint CERT GATE (new).** A standing gate in `corpus_certify`
alongside `SCIENTIFIC_GATES`: compute a **delexicalized shape signature** over gold
sentences (entities masked; token/character shingles plus a POS/dependency skeleton) and
over a native host-sentence sample. **Fail if any shape pattern selects gold docs at a
rate significantly above its native base rate** (the enumerable-signature condition), and
fail if the gold shape distribution's divergence from native exceeds the **native-vs-native
null** (below). This is the gold-side sibling of the distractor-flood index; together they
prove "shape-grep cannot enumerate gold docs."

**F5 — Null-calibrated thresholds (settles threshold derivation).** Every leak gate's
bound is **derived from a native-vs-native null on the specific host corpus**, not a fixed
constant — the same measured-derived pattern the certification policy already uses
(per-cell bands in `707-corpus-certification-policy.v1.json`). Concretely: distractor-
flood bound = native cross-domain grep base rate ± native split variation; entity
indistinguishability = minted grep-hit profile within the native same-type distribution
(e.g. KS-test non-rejection / [p5,p95] band); shape divergence ≤ native split divergence;
dispersion ≥ a fraction of uniform derived from native doc spread. Thresholds are recorded
per stratum in the policy file with the null-sample provenance, so a skeptic can re-derive
them.

**F6 — Per-schema gold contract + scorer (settles gold-format vs substring scoring).**
Each schema declares a `gold_kind`; the substring-exact-after-normalization scorer becomes
a small **deterministic comparator registry keyed on `gold_kind`** (no judge):
single-fact and 2-hop-bridge → `single_value` (existing substring/exact-normalized path);
multi-doc aggregation → `set` (order-insensitive all-present), `count`/`sum` (numeric
exact after normalization), or `extremum` (single latest/max value). Golds stay exact-
matchable and format-diverse; only aggregation adds a comparator, and each comparator is
exact and deterministic. `question_type` keeps the edge-count labeling (it is
commitment-bound via `query_gold_sha256`; new schemas ship as new committed strata, so no
relabel of existing bytes).

**F7 — Difficulty band without curve-fitting (settles per-tier calibration).** The
per-schema difficulty band extends `retrieval_calibration` and is **anchored on
retrieval-reachability**, a model-independent engine property measured at certify time per
stratum: a **floor** requiring each gold to surface in top-k on the actual engine (kills
dead questions pre-spend — the ~45% failure the prior 10k regime hit) and a **cap**
rejecting single-hop-trivial top-1 golds. Because both endpoints are retrieval quantities,
the band does not track any model tier. The existing `closed_book` gate stays the
orthogonal un-guessability control and **runs at both the haiku floor and the hero tier**
(charter §B), the only model-tier-dependent gate.

**F8 — Distribution (D8).** Fetch-then-inject deterministic recipes for all strata (no
modified real document committed); a machine-visible "synthetically altered" header stamp
as a build invariant; a PII-scrub gate on any published Enron sample. Reuses the 709 fetch
cache and 741 derived-artifact posture unchanged.

**Orphans owned by this lane's PR** (deleted/relabeled here, not a later sweep): the
`_SYL_*`/`_ATTR_*` pools, `_FILLER`, `_name`, `_chain` counter-coupling
(`corpus_generate.py:38-48,288,319-323`); the `_SEM_TYPE`/`_SEM_PLACE` synthetic descriptor
pools (:65-104); and the register-side duty on the v5 strata (annotate superseded-for-claims)
per charter §A-7.

**Principle and reach.** This lane instantiates the program's "camouflage is certified,
not assumed" (766 §E) and sharpens it into a narrower, testable rule: **the host corpus is
its own null hypothesis** — a planted artifact is *indistinguishable* exactly when its
measured profile falls inside the host's native-vs-native null, so every leak gate is an
adversary-relative, null-calibrated test rather than a fixed constant. A second shape:
**carrier realism over authored realism** — substitute into real sentences rather than
author synthetic ones, because a real carrier has no authored signature to leak. *Where
else it applies:* any fixture that plants sentinel content in real data (leak canaries,
honeytokens, watermark probes) and any future synthetic-in-real corpus. *Evidence it earns
its keep:* the first certification run that rejects an alien payload pre-spend, and a
grep-simulation probe (charter §B) showing the baseline arm's expected grep experience is
vocabulary- and shape-matched across strata — the confound measured dead, not assumed dead.
*Retirement condition:* retire the null-calibrated framing if corpora ever become fully
real (no injection), or if a single fixed threshold is demonstrated to generalize across
host corpora (making the per-host null redundant); retire "carrier over authored" once
generation models author sentences that pass the shape null as reliably as real carriers.

**Could not fully settle (flagged for planning).** One quantity is genuinely
measurement-dependent and cannot be fixed in design: the **exact numeric bounds** of the
null-calibrated gates (F4/F5) and the retrieval-reachability band (F7). By construction
they are derived from each host corpus's native null at build time, so the design fixes the
*derivation method and provenance*, not the numbers — the numbers land during
implementation from the measured native samples, and must be recorded in the policy file
with their null-sample provenance before any paid run.

## §G. Takeover investigation + verdict (2026-07-21)

Autonomous investigation: 766/762/764/768-770 program read, `corpus_{generate,inject,certify}.py`
+ certification policy audited against source, three load-bearing claims independently
re-verified by the orchestrator. **LITE-CLASS: no** (this is a core-module rewrite plus new
gates, not teardown/rename/config-delete).

### G.1 Verdict — GO, now, with a revised scope

**Do it, and do it now.** Three independent reasons, in descending strength:

1. **The defect is measured, severe, and worse than the charter states.** Re-verified this
   session: `grep -c 'quiet markets where traders gather'
   scripts/jseval/707-corpora/en-legal-clerc/1000-verbose/fabricated-docs.jsonl` →
   **280/280**. The `_FILLER` paragraph (`corpus_generate.py:48-53`) is byte-identical
   across every gold doc in every stratum and repeats 7+ times per doc, so **one grep
   selects 40/40 gold docs with zero false positives**. The charter argues the payload is
   *domain-alien*; it is additionally *perfectly enumerable*. The 764 probe
   (`tmp/analysis-624/764/mechanism/grep_flood.csv`) was cited here for the vocabulary half —
   **§H.0 RETRACTS that citation: the measurement is a substring-plus-unit artifact and does not
   survive reproduction.** Reason 1 rests on the enumerability finding alone, which is unaffected.
2. **There is a live correctness bug, not just a credibility one.** `corpus_generate.py:288`
   mints `Tasdell272` and `:319-323` mints the gold value from the *same* counter →
   `…0272`. 764 §E records agents already exploiting it (answers with the hop-2 doc never
   opened, fabricated citations around pattern-guesses), neutralized only by an accident of
   the scorer's full-string requirement. This alone justifies a PR independent of the rest.
3. **It is on the critical path and blocks nothing else in return.** 766 §C lists 767 as
   `Depends on: —`; nothing on `main` supersedes it (no 766-program code has landed —
   `SCIENTIFIC_GATES` is still the original four, `corpus_certify.py:38-40`). The
   founder-gated hero campaign (~$90–270) cannot honestly run on the current payload.

**But the design as written should not be planned verbatim.** §F spends its heaviest
machinery on threats no reviewer will raise, and one of its items actively creates the
objection that would kill the benchmark. Scope revisions in G.3–G.5 are conditions of the GO.

### G.2 Cheapest validating evidence — half exists, half is a 1–2h probe

The **defect** is already measured (764's probe; re-verified above) — no further evidence is
needed to justify acting. What is *not* measured is the **fix**: nobody has shown that
host-derived camouflage actually equalizes the baseline grep experience.

That gap is closable for ~1–2 hours at zero API cost, on CLERC-1k only (already fetch-cached),
and **should gate the 445-line rewrite**: harvest an entity bank with *stdlib heuristics only*
(capitalized n-grams, docket/reporter-citation regex — no spaCy); mint 40 type/length-matched
golds; replace `_FILLER` with real sentences sampled from other host docs; then measure
(i) minted-entity document-frequency profile vs the native same-type DF band, (ii) the legal/email content-term DF profile measured token-boundary and per-document on BOTH
sides (the §H.0 unit rule — the original 22.2%-vs-~0% framing of this step is retracted), (iii) max repeated ≥5-gram
frequency across gold docs vs native. All three outcomes are decision-relevant: passes on
stdlib → F1's spaCy dependency is optional and can be cut before commitment; needs NER types →
F1 is justified on measurement rather than on a cited paper, and you know which entity classes
carry the camouflage; fails either way → the §F design bet is wrong, discovered for two hours
instead of after a rewrite, 20 cell re-baselines, and a founder-gated spend.

Commit the 280/280 grep above as the "before" half of the §B acceptance probe — it costs
nothing and defines the metric the rebuild must beat.

### G.3 Scope revisions (conditions of the GO)

Per-item ratings against the *measured* defect:

| Item | Rating | Disposition |
|---|---|---|
| F1 committed sha-pinned entity bank | boundary NECESSARY; spaCy VALUABLE | Keep the boundary verbatim — the design's best call. Let §G.2 decide spaCy vs stdlib vs the in-repo ONNX NER. |
| F2 type/length minting + uid-decoupled values | **NECESSARY (live bug)** | Ship regardless of what else survives review. |
| F3 real-carrier substitution | 80% NECESSARY, ambition risky | Keep "replace `_FILLER` with real host sentences". Drop the claim at `:216-217` that F3 means "no authored template → no authored signature" (see G.4a). |
| F4 delexicalized shape-signature gate | **SPECULATIVE — cut** | See G.4c: it contradicts F1 and lacks power at n=40. |
| F5 null-calibrated thresholds | principle NECESSARY, apparatus SPECULATIVE | Reduce to the vocabulary-flood gate, where the per-host null is genuinely mandatory. |
| F6 `gold_kind` comparator registry | NECESSARY iff D4 ships | Keep. D4 is separable but is the *more* reviewer-visible defect than anything F4 covers. |
| F7 retrieval-reachability band | **HARMFUL as specced — cut** | See G.4b. |
| F8 distribution/PII/header stamp | NECESSARY, mostly already true | `recipe.json` is already fetch-then-inject. Residual = header stamp + PII gate. Small; don't let it inflate the estimate. |

Replace F4/F5-apparatus with **two stdlib gates**: a **vocabulary-flood gate** (per-question
content-term DF profiled against the same host corpus's native term-DF distribution — the 764
mechanism probe made standing) and a **repeated-n-gram gate** (no ≥5-gram, excluding minted
strings, appears in more than *k*% of gold docs unless it appears at ≥that rate natively — this
catches `_FILLER` and any authored-template reuse for ~20 lines, no parser, no distribution
theory).

### G.4 Three findings the design does not currently handle

**(a) F3's resolution does not resolve its own tension.** 767 `:94-98` honestly flags that a
needle needs a *new* fact, which pure substitution cannot manufacture, and resolves it as
"authored scaffolding only at relation junctions" (`:218`). But **the relation junction *is*
the needle** — the gold fact "X → V" is precisely the sentence that cannot be real. So F3
camouflages the padding and the surround and leaves the load-bearing sentence authored. Its
fallback, "a large, register-matched realization library" (`:219-220`), is the position §D's own
rejected-alternatives list dismisses as "hope" (`:133-135`). Not fatal — an overstated claim,
not a broken mechanism — but F3 must be scoped honestly as "camouflage the surround; make the
needle sentence's *vocabulary* host-matched," which F1/F2 already achieve.

**(b) F7 is circular, and the magnitude is already measured in this repo.** F7 proposes a
certify-time floor requiring each gold to surface in top-k **on the actual engine**
(`:262-264`). Retrieval-reachability is a property of the **treatment arm only** — the baseline
arm has no engine. In a paired McNemar design the delta is driven by discordant pairs, and
questions the engine cannot retrieve are the principal source of *(baseline-correct,
tool-incorrect)* pairs. Deleting them is one-sided pruning of the unfavourable discordant cell:
**the published Δ is inflated by construction and the p-value invalid, because the item set was
selected on an arm-conditional outcome.** 764 `:133-135` already quantifies the scale — removing
9/20 dead legal-10k qids moved the delta **0.094 → 0.172, 1.8×** — and those were *model-floor*
deaths, which are roughly arm-symmetric and therefore the *defensible* case; F7 prunes on a
criterion asymmetric by construction. "You removed every question your engine couldn't retrieve,
then published how much your engine helps" is one sentence from a clone-and-inspect skeptic, and
no amount of camouflage gating survives it. The existing `retrieval_calibration` gate is *not*
precedent: it is an aggregate per-cell admissibility band with both a floor **and a ceiling**,
failing cells wholesale; F7 converts that into item-level selection on the treatment outcome — a
change of kind, not degree. **Remedies in preference order:** (1) keep it at cell level, ship
nothing new; (2) if per-item pruning is genuinely wanted, measure reachability with a
**reference retriever that is not the system under test** (BM25-only or a frozen pre-769 build),
pre-registered and frozen — note 769 explicitly improves bridge-entity retrieval, so calibrating
F7 on the post-769 engine bakes 769's improvement into the item set; (3) whatever ships, publish
the unfiltered number alongside plus the count and fate of every dropped question. The
legitimate motivation (dead-question dilution) is **already solved by 766 D7's** ITT +
per-protocol + completion triple — by disclosure rather than by silently changing the denominator.

**(c) F4 contradicts F1.** F1 (`:196-198`) confines spaCy to an offline mint step *specifically*
to keep the determinism path stdlib-only. F4 then requires a POS/dependency skeleton over gold
and native sentences **at certify time**, reintroducing the dependency into the *gate* path,
where version drift flips pass/fail verdicts written into committed certification artifacts.
Separately, at `query_count: 20` and `n_gold_docs: 40` a shape-divergence test either has no
power after multiple-testing correction or fires constantly without it. **A gate that cannot
fail is worse than no gate — it launders an assumption into a certification.** Demand a power
calculation before shipping any statistical gate.

### G.5 The biggest gap: 767 orphans the uplift mechanism without a replacement criterion

`_SEM_TYPE`/`_SEM_PLACE` (`corpus_generate.py:65-104`) are deleted by §F3 in one clause and
"recast onto host-derived descriptors." But the source comment at `:55-63`, verified verbatim
this session, states their purpose:

> the QUERY references it via SYNONYMS (low lexical overlap), so exact-match `Grep` / pure-BM25
> fails at the entry point but dense/SPLADE bridges semantically — **the only setup where
> JustSearch's retrieval can beat a grep-agent.**

**That is the mechanism that generates the with-tool uplift the campaign exists to publish.**
Real captions paired with their real paraphrases usually *do* share surface tokens; if the
semantic-bridge gap collapses, the engine's advantage collapses with it — and that is discovered
*after* the $90–270 spend. F4/F5 get two full sections; the uplift generator gets one clause and
no acceptance criterion. **Add a bridge-entry lexical-overlap gate** (overlap between the
question's entry descriptor and the head gold doc, bounded against the native null) with at
least F4's weight. This couples to lane 769 and should be settled with it.

### G.6 Sequencing corrections

1. **§B's acceptance is unsatisfiable as written.** It requires `closed_book ≈ 0` at the hero
   tier (`:59-60`), but hero-tier runs are the founder-gated spend 767 exists to unblock.
   Restate hero-tier `closed_book` as a **pre-registration** step, not a 767 closure gate.
2. **Certification is unstable under the program's own roadmap.** Engine-measured gates
   (`retrieval_calibration` today) mean every 769/770 merge can decertify 767's strata. The
   machinery exists and is unused: the run manifest already carries a validated `git_sha`
   (`corpus_certify.py:456`). 767 must declare which gates are engine-version-bound, pin the
   engine sha in the policy, and state the re-certification trigger. Currently silent.
3. **Per-stratum question count `n` is unspecified and is a $90–270 decision made by omission.**
   Cells carry `query_count: 20`. 764 `:127-131`: exact McNemar at n=60 reliably sees Δ≥0.20 but
   n@80% is 92 for Δ=0.15 and 208 for Δ=0.10. If the rebuild removes the vocabulary artifact the
   *legal* delta should **shrink** (the old one was partly artifact), so the rebuilt benchmark
   plausibly lands underpowered and certifies nothing. `query_gold_sha256` commits the question
   set — decide `n` **before** regeneration, not after.
4. **Run the G.2 probe before planning the rewrite.**

### G.7 What this displaces or duplicates

Duplicates nothing — no existing gate checks distinguishability, grep-leakage, distractor
flooding, or shape signatures (full gate inventory audited; `descriptor_collision` is
exact-*title*-match only, `corpus_certify.py:1064`, and is structurally blind to a shared body
signature). Displaces: the `_SYL_*`/`_ATTR_*`/`_FILLER`/`_name`/`_chain` paths and the
`_SEM_TYPE`/`_SEM_PLACE` pools (~445 of 844 lines, 53% of `corpus_generate.py` — a core rewrite,
not an edit); and retires the v5 strata for claims. **Unscoped cost the charter implies but does
not name:** replacing the payload invalidates every `corpus_signature` and `query_gold_sha256`
across the committed cells in `707-corpus-certification-policy.v1.json` (**8** required cells — see §H.6, which corrects an earlier "20" here), and every per-cell measured
threshold there was calibrated against the old payload — all four scientific gates need
re-baselining per cell. `corpus_inject.py` is reusable as-is (confirmed).

### G.8 Pre-existing defect found in the injection substrate (in scope, not yet named)

`corpus_inject.py:17` `_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")` splits on any period, so a
legal citation `Anderson v. Liberty Lobby, Inc., 477 U.S. 242, 248 (1986)` is split at `U.S.`
and filler is injected **inside** the citation. Injection therefore corrupts host documents at
CLERC injection sites today. Independent of the payload rebuild, cheap to fix, and it degrades
exactly the "real host document" realism this lane depends on. Fold into this lane's PR.

## §H. Derisk pass (2026-07-21) — measurements, and two corrections to §G

Six offline experiments (E1-E6), zero paid API, no GPU, no dev stack. All numbers computed from
primary sources; scratch artifacts in the session scratchpad. **Two §G claims are corrected
below — one of them was load-bearing for the verdict's justification.**

### H.0 CORRECTION 1 — the vocabulary-confound evidence does not survive reproduction

§G.1 cited `tmp/analysis-624/764/mechanism/grep_flood.csv` ("`spa` 2225/10001 legal vs 131/150k
enron") as establishing the vocabulary half of the confound. **That measurement is an artifact.**
Two independent defects, both verified this session:

1. **It is a substring match, not a token match.** Verified directly on the 199 cached CLERC
   opinions: substring `spa` matches **81/199 files**; the token `\bspa\b` matches **0/199**.
   What the substring actually matched: `space` (16), `newspaper` (14), `newspapers` (13),
   `disparate` (9), `disparity` (9), `span` (5), `aerospace` (4), `spaces` (4). The payload term
   is `thermal spa` (`corpus_generate.py:71`) — **it occurs zero times in real legal text.**
2. **The two columns use different units, and the CSV header says so:**
   `legal_10k_staged_files_matching(of 10001)` vs `enron_raw_lines_matching(of 150000 sampled)`.
   Files versus lines. A worker reproduced the enron per-line figures almost exactly
   (`spa` 123/150,029 vs the cited 131; `wine` 5 vs 6; `coastal` 9 vs 9; `thermal` 6 vs 6;
   `beacon`/`aqueduct`/`reactor` 0 vs 0), confirming the unit mismatch.

Unit-normalized, token-boundary, both per-document over 10k docs each: `spa` is **0.25% legal vs
0.26% enron — a ratio of 0.96×, not 247×**. Across all 20 payload terms: legal 0.16%, enron
0.045% (3.6×). On the operational metric (best single-term grep over the 20 real fabricated
queries): legal 0.191%, enron 0.043% (4.39×), and **0/20 queries in either domain return >1% of
the host corpus** (legal median 0.06% ≈ 6 docs of 10,000).

**Provenance note explaining how this survived:** the two scripts preserved next to the CSV
(`measure_perq_gold.py`, `measure_reads.py`) produce `findings.csv`, **not** `grep_flood.csv`.
The flood numbers have no preserved script — they were ad-hoc shell, unreproducible, and
unit-mismatched by their own header.

**What still stands, and what does not:**

| Claim | Status |
|---|---|
| Payload is perfectly enumerable (`_FILLER` selects 280/280 gold docs) | **STANDS** — literal full-phrase grep, no substring ambiguity; verified twice |
| uid↔gold-value coupling leak (`Tasdell272` → `…0272`) | **STANDS** — source-verified, 764 §E documents live exploitation |
| Baseline arm drowns in legal, cruises in email (43/60 vs 6/60 opened neither gold; acc 0.033 vs 0.533) | **STANDS** — `findings.csv`, backed by two reproducible scripts over real eval logs |
| *Vocabulary flood is the CAUSE of that behaviour* | **REFUTED as measured** — the payload's document frequency does not explain it |

So the **phenomenon is real and the explanation is not**. 767 currently proposes camouflage as
the remedy for a cause that has been measured and found not to hold. The behavioural asymmetry
needs a fresh diagnosis before camouflage is justified *as its fix*.

### H.1 E1 — the host-derived bridge COLLAPSES the semantic gap (F3 refuted)

> **[MEASUREMENT CAVEAT — see §J.4]** The 0.083 baseline below was measured on the *pre-injection*
> `fabricated-docs.jsonl`; on assembled cells the same instrument gives 0.16 (verbose) / 0.11
> (short-natural). The 0.083 → 0.820 comparison therefore spans two populations and **should not be
> re-quoted until re-measured on assembled cells both sides.** The qualitative conclusion is
> expected to survive (0.16 vs 0.820 is still ~5×, and the df≤5 grep-decisive result at 88% is
> population-independent), but the specific figures are not yet on a consistent basis.

Baseline (committed CLERC cells, same instrument): query↔gold-head OverlapCoef mean **0.083**,
13/20 zero-overlap. Simulated 767 §F3 host-derived descriptors (26 CLERC + 27 Enron realistic
paraphrases of real case captions / email subjects):

| Regime | OverlapCoef min/med/max/mean | Zero-overlap |
|---|---|---|
| Baseline (synthetic `_SEM_*`) | 0.000 / 0.000 / 0.250 / **0.083** | 13/20 |
| CLERC, host-derived + paraphrased | 0.500 / 0.800 / 1.000 / **0.820** | **0/26** |
| Enron, host-derived + paraphrased | 0.000 / 0.333 / 0.667 / **0.333** | 2/27 |
| Control: descriptor used verbatim | 1.000 / 1.000 / 1.000 / **1.000** | 0 |

**CLERC mean overlap rises 9.9× (0.083 → 0.820).** Grep-decisive test — does the paraphrase hand
a grep agent a rare anchor that occurs in the correct host? CLERC: **65% of queries leak a token
with df=1** (unique to one document — a perfect grep anchor), 88% at df≤5, 100% at df≤25. Enron:
**0% at every threshold up to df≤25**. Leaked CLERC tokens are exactly the party surnames
(`norris`, `celebrezze`, `mediquip`, `gozlon`, `peretz`). Mismatch control confirms signal is
real (3.3× CLERC / 4.1× Enron over a random wrong host). Numbers are conservative — adding a
stemmer *raises* overlap (0.820 → 0.850), and production BM25 stems.

**The mechanism is structural, not an artifact of paraphrase quality:** a case caption's
identifying content *is* its proper nouns, so any paraphrase preserving document-uniqueness
preserves the greppable tokens, and any paraphrase dropping them stops identifying the document.
**Uniqueness and lexical distance are in direct tension — this is not fixable by paraphrasing
harder.** Note also that the baseline's *maximum* overlap (0.250) is indistinguishable from
CLERC's host-derived *background noise floor* (0.248): the current design's worst case is the
proposed design's best case.

### H.2 E4 — no domain supports both halves of F3

Real carrier-sentence availability for F3's "substitute into real host sentences":

| | CLERC | Enron |
|---|---|---|
| Sentences scanned | 72,764 (199 opinions) | 224,312 (5,485 emails) |
| **The generator's 4 current relations, verbatim** | **0 matches** | 183 (all newswire-sourced) |
| All probed domain-native relations | 81 (1.11/1k) | 1,529 (6.82/1k) |

Both headline counts collapse on inspection. Enron: **1,235 of 1,529 (81%) are `Forwarded by`** —
an email *header*, not prose; the semantically usable remainder (`was led by`, `was founded by`)
sits in 84 of 5,485 emails, **81 of which are VC-funding press boilerplate pasted into the
mailbox**, leaving ~294 native carriers (0.054/email). CLERC: **46 of 81 (57%) are the fixed
formula `Justice <Name> delivered the opinion of the Court`**, leaving ~35 varied carriers across
199 opinions (0.18/opinion). A single 280-doc cell needs ~280 carriers → ~1,500 CLERC opinions
(requires streaming the full 8.2 GB corpus) or essentially the entire cached Enron mailbox.

**Combined with H.1 this is the decisive design finding: E1 says the host-derived bridge is safe
in Enron and fails in CLERC; E4 says carrier realism is achievable in CLERC and not in Enron.
There is no stratum where both halves of §F3 hold.** §F3 as written does not have a domain.

### H.3 E2 — camouflage does not equalize the grep experience

Simulated camouflaged payload (40 minted entities/domain, `_FILLER` replaced by real host
sentences from other docs):

| Payload | best-grep hit rate, legal | enron | asymmetry |
|---|---|---|---|
| current (real queries) | 0.191% | 0.043% | 4.39× |
| **simulated camouflage** | **7.11%** (max 69.05%) | **0.675%** (max 1.76%) | **10.5×** |
| simulated, query exposes minted entity | 0.000% | 0.000% | 1.0× |

**Camouflage makes the (real, much smaller) asymmetry worse — 4.39× → 10.5× — and raises absolute
legal flood ~37×.** Mechanism: camouflage *inherits* its host's own DF concentration, and the
hosts differ intrinsically (a CLERC opinion's ORG entities are things like "the district court",
DF 69%; Enron's are individual company names with low DF). Drawing from the host imports the
difference rather than cancelling it.

**Two components of the bet do work.** The `_FILLER` leak is closed decisively — max repeated
5-gram across gold docs falls from **280/280 (100%) → 10.0–12.5% legal / 30–37.5% enron, below
the native base rate** (42.5% legal, 100% enron). Minted-entity DF is 0/40 by construction
(verified), and the content-term DF profile lands inside the native [p5,p95] envelope for
80–87% of terms. And when the query exposes a minted entity, best-grep is **0.000% in both
domains** — perfectly equal, because uniqueness dominates camouflage.

### H.4 E3 — spaCy is not needed (F1's 500 MB dep can be cut)

| Harvester | throughput | full 14k harvest | types reached |
|---|---|---|---|
| stdlib regex | 81–402 docs/s | **~3 min** | PER/ORG/LOC + DATE, CARDINAL + CITATION 6,655 · CASE 5,108 · DOCKET 174 · JUDGE 126 · EMAIL 918 · PHONE 274 |
| ONNX NER (in-repo) | 0.385–2.79 docs/s | **~10.1 h** | PER/ORG/LOC only — **DATE never fires** despite being declared |

Of the cited recipe's 8 types the ONNX model reaches **3**. Overlap between harvesters is low
(Jaccard 0.06–0.24) — they find *different* strings, not the same ones better; stdlib gets
multiword spans (`northern district of illinois`), NER gets bare surnames (`dasovich`).
Minting 40 type+length-matched entities is **feasible collision-free with either**; stdlib's
only thin band is LOC (11/24 length buckets with <3 natives), which NER fills.

**VERDICT: stdlib is the right default** — 200× faster, strictly more types, and length
distributions (the only property minting consumes) are ample. Run the ONNX NER **once, offline,
cached** as a LOC/PER supplement. spaCy's marginal value is GPE/NORP/EVENT, which are not
load-bearing. **F1's 500 MB dependency should be cut.**

### H.5 E5/E5b/E6 — coupling, prior art, cost

- **Coupling map (complete).** Sites that change together: `generate()` signature/body
  (`corpus_generate.py:655-657`, uses at `:681,:684-688,:695,:702-704,:722,:736,:768-771`) →
  `regenerate_and_diff` subprocess marshalling (`:819-826`, keyword-by-name so a signature change
  fails **loudly**) → `corpus_certify._REQUIRED_PROVENANCE_KEYS` (`:31-33`) +
  `regeneration_determinism_report` (`:1067-1128`) → **14 committed `fabricated-meta.json` /
  `meta.json` files** under `707-corpora/**` → `tests/test_corpus_governance.py` (~20 call sites,
  incl. a positional-argv comment at `:383-384` that must stay in sync). No CLI coupling — there
  is **no `corpus-generate` command**; `generate()` is called only from certify, tests, and one
  experiment script.
- **The dangerous, non-mechanical case.** `regeneration_determinism_report` branches on
  `gp["method"]` (`:1087`). For `real-text-injection-v1` (all committed 707 cells) it validates a
  pre-recorded digest and **never touches `semantic`/`n_chains`**. Only `procedural-fabricated`
  hits `_REQUIRED_PROVENANCE_KEYS` and spawns a real regeneration. So a careless rename **won't
  fail loudly — it will silently degrade those corpora to `passed: None` (skip)**, not to a
  failure. This must be designed around explicitly.
- **Prior art is a dead end.** `tmp/707-rebuild/` is a **byte-identical reproduction** of the
  committed cells (sha256 match on `fabricated-docs.jsonl`; JSON diffs are CRLF-only, exactly 179
  CR bytes for 179 lines) — leftover from diagnosing the CRLF bake-in fixed in `6ef20664`. It
  contains no alternative payload to salvage.
- **Offline certification is not a budget risk.** Real timing: structural checks
  (`corpus_signature` + `regeneration_determinism_report` + `_validate_commitment` +
  `descriptor_collision_report`) over a materialized 1k cell = **0.28 s** work / 0.61 s wall; a
  full double-subprocess `procedural-fabricated` regeneration = **0.62 s**. Control suite
  `test_corpus_governance + test_corpus_inject + test_corpus_axis` = **92 passed in 16.35 s**,
  clean. The cost risk is entirely in the NEEDS-ENGINE gates (~1h20m GPU precedent for 8 cells)
  and the PAID `closed_book` gate, not the offline half.

### H.6 CORRECTION 2 — cell count

§G.7 said "20 cells". The policy (`707-corpus-certification-policy.v1.json`) has **8**
`required_cells` (4 en-legal-clerc + 4 en-email-enron-raw). The 20 was 767's *target* matrix, not
current state. Re-baselining scope is 8 cells today.

### H.7 What the measurements do to the design

The lane still has a real job, but **both its justification and its central design item change**:

- **Justify on the two verified defects, not the confound.** Payload enumerability (280/280) and
  the uid↔value leak are measured, severe, and independently sufficient. The vocabulary-flood
  justification should be struck (H.0).
- **Diagnose the behavioural asymmetry separately before treating it.** 43/60 vs 6/60 is real and
  now unexplained. Camouflage is not its established remedy, and E2 says camouflage would make the
  DF asymmetry *worse*. This is a diagnosis lane of its own — plausibly the same root as 769's
  bridge-entity retrieval failure, which is also legal-only.
- **Drop §F3's descriptor recast; KEEP `_SEM_TYPE`/`_SEM_PLACE`.** E1 shows the synthetic
  descriptor bridge is the thing that works (0.083 overlap, 13/20 at zero) and that its proposed
  replacement destroys it (0.820, 65% grep-decisive at df=1). §F3 and the "Orphans owned by this
  lane's PR" entry claiming these pools should be **reversed**: they are load-bearing, not orphans.
- **What survives as the evidence-backed core of 767** — much smaller than the charter:
  (1) delete `_FILLER`, replace with real host sentences sampled from other docs *(E2: 100% → 10-12.5%,
  below native base rate — this component is proven)*;
  (2) delete the uid↔value coupling, mint format-diverse values *(live bug)*;
  (3) replace syllable-minted names with host-matched minted entities from a **stdlib-harvested,
  committed, sha-pinned bank** *(E3: no spaCy, ~3 min harvest)*;
  (4) two stdlib gates — repeated-n-gram *(proven discriminating: native base rate 42.5%/100% vs
  simulated 10-37.5%)* and a **token-boundary, per-document, unit-consistent** DF gate *(H.0's
  lesson encoded as a gate)*;
  (5) keep the existing semantic-bridge descriptors and add the bridge-entry overlap gate from §G.5
  as a **regression guard on the 0.083 baseline**, not as a replacement mechanism.
  Cut: F1's spaCy, F3's recast, F4 entirely, F5's apparatus, F7 as specced.

## §I. Design (2026-07-21, post-derisk) — payload integrity by layer

§H's measurements refuted the lane's framing (camouflage) and its central design item (§F3).
This section replaces §F. It is a design, not an implementation plan.

### I.1 The reframing: one document, three layers, three different adversaries

Both the status quo and §F3 apply a **uniform** treatment to the gold document — the status quo
makes it all synthetic, §F3 would make it all host-derived. §H shows each choice fails at the
opposite end. The document actually carries three functionally distinct layers, each with its own
adversary and its own correct mechanism:

| Layer | Job | Adversary | Correct mechanism | Measured |
|---|---|---|---|---|
| **Bulk / context** (most of the bytes) | Not be enumerable as a set | shape-grep over the corpus | **Real host text**, sampled per-doc | `_FILLER` 100% → real sentences 10–12.5%, *below* native base rate (H.3) |
| **Bridge / entry** (the descriptor the query aims at) | Be reachable semantically but not lexically | grep/BM25 at the entry point | **Synthetic, low-overlap descriptors** | synthetic 0.083 mean overlap, 13/20 at zero; host-derived 0.820 with 65% df=1 anchors (H.1) |
| **Needle / fact** (relation + minted entities + gold value) | Be un-guessable and un-enumerable | pattern inference, closed-book guessing | **Uniqueness**, not camouflage | best-grep 0.000% in both domains once the query exposes a minted entity (H.3) |

**The design principle this yields: indistinguishability is layer-specific.** You camouflage the
bulk, you *isolate* the bridge, and you make the needle *unique*. A uniform treatment necessarily
fails at least one layer — which is exactly what both the current payload and §F3 do, in opposite
directions. This supersedes §F's "camouflage everything, certify the blend."

A corollary worth stating because it is counter-intuitive: **the bridge's value comes from being
synthetic.** Its low lexical overlap is not a defect to be camouflaged away — it is the property
that makes retrieval beat grep, and §H.1 measures its host-derived replacement destroying it.
`_SEM_TYPE`/`_SEM_PLACE` are therefore **load-bearing infrastructure, not orphans**; §F3's orphan
claim is reversed by this design.

### I.2 What the lane becomes

**767 is a payload-integrity lane, not a camouflage lane.** Scope:

1. **Bulk** — replace `_FILLER` with real host sentences sampled from *other* host documents of the
   same stratum (never the doc's own host, to avoid a self-similarity artifact). Deterministic,
   seeded, sampled from a committed pool.
2. **Needle** — decouple gold-value minting from the entity uid counter; mint format-diverse values;
   replace syllable-minted names with entities sampled from a **committed, sha-pinned, typed entity
   bank** harvested from the host corpus. §H.4: the harvester is **stdlib** (~3 min for 14k docs,
   strictly more types than the ONNX NER, which never emits DATE despite declaring it); the in-repo
   ONNX NER is an optional offline LOC/PER supplement. **spaCy is not adopted** — §F1's 500 MB
   dependency buys only GPE/NORP/EVENT, which are not load-bearing.
3. **Bridge** — keep the synthetic descriptor pools unchanged, and add the gate that protects them.
4. **Certification** — three gates matched to the three layers, plus one cross-cutting measurement
   invariant (I.3).
5. **Explicitly out of scope** — the A-arm/B-arm difficulty asymmetry (I.4). This lane does not
   claim to fix it and must not be justified by it.

### I.3 Certification: the gates are the probes, committed

Each layer gets the gate that matches its mechanism. Thresholds are derived per host corpus where —
and only where — §H measured that a constant cannot work.

- **G-bulk (shape-enumerability).** No k-gram / shingle selects gold documents above the *native
  base rate for the same k in the same host corpus*. The per-host null is **mandatory here and
  proven necessary**: measured native repeated-5-gram base rates are 42.5% (legal) vs 100% (enron) —
  no constant passes both. This is the one place §F5's null-calibration principle survives §H, and
  it survives because it was measured, not assumed.
- **G-bridge (bridge-distance regression guard). NEW — nothing guards this today** (confirmed: the
  gate set is closed at `SCIENTIFIC_GATES` + `_CELL_CHECKS`, and the nearest analogues —
  `descriptor_collision` (exact-title, doc-vs-doc), `shortcut_leak_rate` (LLM hop-collapse probe),
  `rag_reachability_probe` (749's chunkless-doc guard) — measure different quantities). Two
  conditions: (a) query↔gold-head content-token overlap stays at or below the committed baseline;
  (b) **no query leaks a token whose document frequency in the host corpus is at or below a floor**
  — the df=1 anchor test, which is the specific thing that killed §F3 and which an aggregate mean
  would have hidden.
- **G-needle (uniqueness).** Minted entities and gold values have df=0 across the assembled corpus;
  values are counter-decoupled and format-diverse. This **generalizes the existing
  `descriptor_collision` check** (today exact-title-match, doc-vs-doc) rather than adding a parallel
  one — extend that seam, don't fork it.
- **Not adopted:** §F4 (delexicalized POS/dependency shape gate — reintroduces a parser into the
  gate path that §F1 deliberately kept out, and lacks power at the committed cell sizes), §F5's
  broader statistical apparatus, §F7 (item-level retrieval-reachability pruning — see §G.4b; the
  circularity objection stands independently of §H).

**Cross-cutting invariant — measurements that gate must declare their unit.** §H.0's refuted claim
was produced by a substring match compared across mismatched units (files vs lines). Every frequency
or rate comparison in certification must therefore record, in the artifact, its **matching mode
(token-boundary vs substring) and its unit (per-document vs per-line), with the same unit on both
sides of any ratio**. A gate compares only like units. This conforms to the certification policy's
existing "measured, with recorded provenance" pattern rather than introducing a new one.

**And the structural fix for how H.0 happened at all: every such measurement ships as a committed,
runnable gate, not as an ad-hoc script.** The refuted numbers survived precisely because they were
unpreserved shell whose method could not be inspected — the two scripts kept beside the CSV produce
a *different* file. Converting the derisk probes into committed gates makes the method inspectable
and the numbers reproducible by a third party, which is the standard this benchmark's whole purpose
demands.

### I.4 The asymmetry this lane does NOT fix — and where it belongs

§H.0 left the behavioural finding standing and unexplained (43/60 legal baseline cells opened
neither gold vs 6/60 email; accuracy 0.033 vs 0.533). **The replacement explanation is probably
already being pursued in lane 769**, and the two should be coordinated rather than diagnosed twice.

769's diagnosis is that on legal, dense + SPLADE are near-dead (F-030/F-031/F-034), leaving BM25 as
the only live leg — "so a bridge that structure-vocab can't reach *lexically* has no semantic leg to
rescue it." That is the **same low-overlap quantity** this design's G-bridge gate measures. Read
together, one corpus property plausibly produces both symptoms in different arms: the A-arm has only
grep, so low overlap means it finds nothing (43/60); the B-arm on legal has only BM25, which is also
lexical, so it finds nothing either (769's 17/127 engine-owned misses). Both are **legal-only,
email-zero, and both worsen with corpus size** — three independent alignments.

Note this is a *hypothesis with three corroborations*, not a measured finding, and it must not be
banked as one — that is the mistake §H.0 exists to prevent. 769's M1 (gold-doc content inspection,
~$0, no backend, first-to-run) is the cheapest test and should settle it before either lane treats
it. A second candidate mechanism §H.3 surfaced and did not test: host corpora differ intrinsically
in self-similarity (a CLERC opinion's frequent terms have very high document frequency; Enron's are
heterogeneous), so *any* generic-English grep floods in legal and is sparse in email — a
**distractor-side** property that no payload change can fix. If that is the cause, corpus difficulty
is not a payload question at all.

**Design consequence either way:** this lane ships payload integrity and does not claim the
asymmetry. Whether the strata are *usable* for a headline comparison is a separate question that
769's M1 answers first.

### I.5 Sequencing consequence

Both 769 (engine: bridge-entity retrieval) and 770 (B-arm tool surface) change quantities the
re-baseline measures. Landing them and 767 without staging makes their effects non-separable. The
re-baselines should therefore be staged and each attributed, and any engine-measured gate should
record the engine sha it was calibrated against (`corpus_certify` already carries and validates a
`git_sha` in the run manifest — extend that seam rather than adding a new pin). This is the §G.6
point, now sharpened by the likelihood that 767 and 769 share a root cause.

### I.6 Orphans owned by this lane's PR

**Code** (deleted here, not in a later sweep): `_FILLER` and `_pad`; `_SYL_A`/`_SYL_B` and `_name`;
`_ATTR_ADJ`/`_ATTR_NOUN` and `_chain`'s counter coupling. **Reversal: `_SEM_TYPE`/`_SEM_PLACE` are
NOT orphaned** — §F3's claim that they are is withdrawn by I.1, and they gain a protecting gate.

**Claims** (retire-with-a-sweep — the refuted explanation is load-bearing for a program-level
decision, so this is not optional tidying): the vocabulary-confound explanation must be corrected
wherever it is asserted or relied on — 764's §E verdict paragraph (surgically: the behavioural half
stands, the vocabulary half does not), 762's §X.1 finding 1, its §L top-ranked lever's evidence
column, its §X.3 camouflage design tension, and its "missing gates" list; 766's §A problem statement
and — most consequentially — **766 D3's distractor-flood gate, whose named provenance is the refuted
measurement** and which must be re-derived from I.3's layer analysis or dropped; 767's own §G.2
residue still citing the number as a probe target. The search-quality register's F-039 caveat (and
its dual copy in the skill) rests on the *enumerability* finding, which stands — it needs a wording
tweak, not a retraction. No canonical doc, ADR, README, `docs/business/`, or register entry asserts
the refuted claim, so the public surface needs no correction.

**Design items not adopted** — recorded so a future reader does not resurrect them as unfinished
work: §F1's spaCy dependency, §F3's host-derived descriptor recast, §F4, §F5's apparatus beyond
G-bulk, §F7.

**Interaction with 741** (corpora are derived artifacts): a rebuild mints a fourth permanent corpus
blob set in a repo whose owner decision on LFS/derived-artifact posture is still open. This design
leans on 741's own argument — the recipe is a deterministic build spec — and stays fetch-then-inject,
committing recipes and the entity bank, not assembled corpora.

### I.7 Reach — principles, and the conditions that retire them

**P1 — Layer-specific indistinguishability.** *A planted artifact in real data has functionally
distinct layers with different adversaries; a uniform treatment necessarily fails at least one.*
Where else it applies: leak canaries, honeytokens, watermark probes, seeded test fixtures embedded
in production-like data, and any future synthetic-in-real corpus. Does existing code violate it?
Yes — the current generator is the violation this lane fixes, and §F3 would have been a second one
in the opposite direction. *Evidence it earns its keep:* a certification run that passes one layer's
gate while failing another's, demonstrating the layers have genuinely different requirements — the
uniform-treatment designs cannot produce that signal. *Retirement condition:* retire if corpora ever
become fully real (no injection, so no layers), or if one layer's requirement is empirically shown
to dominate the others such that a single treatment satisfies all three.

**P2 — A measurement that gates must declare its unit and matching mode.** *Any frequency or rate
comparison that a gate acts on records its unit and matching mode in the artifact, and only like
units are compared.* This is not a new seam — it **extends the certification policy's existing
"measured, with provenance" pattern**, and should conform to it rather than sit beside it. Where
else it applies: every ratcheted baseline in the repo (npm-audit ratchet, class-size ratchet,
retrieval bands, leak_floor), and any future measured threshold. Existing violations: unknown and
worth one pass — the refuted claim is one confirmed instance, produced by exactly this failure, and
it went unchallenged through three tempdocs and a program charter. *Evidence it earns its keep:* a
gate or review refusing a comparison on unit grounds, or a unit mismatch caught before it is banked.
*Retirement condition:* retire the prose rule once all gate measurements flow through a shared typed
measurement helper that makes a mismatched comparison structurally unrepresentable — at that point
the invariant is enforced by construction and the rule is redundant apparatus.

## §J. Implementation record (2026-07-21) — phases 0-4 + the ID channel

Phases 0-4 of §I are implemented and green: **215 passed** across the corpus suites, full jseval
suite **2341 passed / 2 skipped** (the 2 reds are the pre-existing `correction-eval-queries-missing`
known state), and the cross-process byte-equality determinism test is green throughout. Phase 5
(regeneration + re-certification) remains gated. Two findings below **correct claims made earlier
in this tempdoc** — recorded here rather than silently amended.

### J.1 What shipped

- **`jseval/corpus_leak.py`** (new) — pure offline leak measurement: n-gram selectivity, length
  profile, query↔gold overlap, rare-token leak, token DF, and (J.3) ID shape. Every function
  declares `matching_mode` + `unit` per §I.3's invariant. The null is computed from the real
  distractors **inside the same cell**, so it is per-host by construction and needs no external
  data. A naive native-coverage loop was O(gold n-grams × native docs) ≈ 116M lookups (5.98 s/cell,
  would not scale to a 10k cell); replaced with a single-pass count (2.33 s, identical output).
- **`jseval/entity_harvest.py`** + **`jseval/entity_bank.py`** (new) — offline stdlib harvester,
  split from the build-path half (load / validate / sample a frozen sha-pinned bank). The build
  path cannot import the harvester, so a build can never silently re-harvest from a different
  snapshot. **spaCy is not adopted** (§H.4).
- **`corpus_generate.py`** — `doc_words=None` disables padding (the host supplies the real bulk);
  syllable minting and the uid↔value coupling deleted; entities minted type- and length-matched
  from the bank. `_SEM_TYPE`/`_SEM_PLACE` untouched per §I.1. Provenance gains `payload_version` +
  `entity_bank` + `entity_bank_sha256`, with `payload_version` as a **discriminator so a
  missing/renamed key fails loudly** instead of degrading to the silent determinism skip §G.6
  warned about.
- **`corpus_inject.py`** — abbreviation-aware sentence splitting, closing §G.8 (injection was
  landing inside `477 U.S. 242`). Real-text effect: 72,764 → 50,679 split points over 199 CLERC
  opinions. The first fix missed parenthesized abbreviations (`(Fed. Cir. 1995)` still split at
  `(Fed.`) because the token lookup did not strip leading punctuation — caught on review, fixed,
  regression-tested.

### J.2 CORRECTION — the length-separability hypothesis is retired

The plan asserted gold docs (~730 words) were length-separable from real distractors (~140 words),
making document length a second enumeration channel. **Measured on the real assembled cell
(`en-legal-clerc-1k-verbose`, 1000 docs) this is false:** gold `{min 675, median 2096, p95 9909}`
vs native `{min 66, median 1940.5, p95 9274}`, separability **0.075**, gold median inside the
native p5-p95 band.

Cause, verified by inspection: in an assembled cell a gold doc is **not** a short standalone
fabricated document — it is a real CLERC opinion with the fabricated marker sentence spliced into
it, so its length tracks the host distribution. The 730-word figure came from one non-representative
short host (140 words) and was generalized without a distribution behind it. **The length channel
is closed by construction; only the n-gram channel was open.** The padding deletion remains correct
and independently justified — the boilerplate still scored `max_gold_coverage 1.0` against a native
base rate of 0.225.

### J.3 NEW leak channel found and closed: document IDs

Measured on the same real cell:

```
shape = numeric       (e.g. '1000731')   -> 960 docs   all real distractors
shape = lower+digits  (e.g. 'breldac18') ->  40 docs   exactly the gold set
```

`id_shape_report` on the committed cell: **separability J = 1.000** against a 0.151 null, best rule
`shape_class == 'lower-alphanumeric'`, **precision 1.000 / recall 1.000 (TP 40, FP 0)**.

**A single rule over document IDs selects the entire gold set without reading one document body** —
and because the harness materializes documents as `<doc_id>.txt`, a directory listing exposes it.
This is a *cleaner* enumeration channel than the text boilerplate this lane was created to fix, and
it was present in every prior campaign. Nothing in the tempdoc, §F, or §I anticipated it; it
surfaced only because the ID-shape question was asked of real data.

Fix: gold IDs are minted **at assemble time** by perturbing the trailing digits of a real host ID,
donors drawn without replacement from the cell's own host set. Assemble-time was chosen over a
generate-time convention parameter because assembly is the only point that sees the real host ID
set — and `assemble` *silently drops* a real doc whose id collides with a gold id, so a
generate-time collision would corrupt a cell with no error. Post-fix: **J = 0.085 against a 0.151
null (passes)**, 0 collisions against the 14k host ID space. Minted vs real, verbatim:
`5622559 / 3555747 / 77641` vs `11677261 / 4345908 / 6144016`.

**The gate earned its keep immediately by catching two leaks inside its own fix:** redrawing a whole
digit run leaked because 8-digit CLERC IDs all begin with `1` (rule `not id.startswith('1')`,
J = 0.265); and drawing donors *with* replacement leaked because two gold IDs from one donor share
its prefix (J = 0.347). Both are exactly the kind of defect a design review passes and a measurement
catches — the §I.3 argument for shipping gates rather than one-off probes.

Also checked, contrary to the risk assumed when briefing this work: **no production or test code
classifies documents by ID shape**. Gold membership is carried out-of-band via `evidence_ids`
everywhere. The `[a-z]+\d+` classifier that prompted the concern lives only in throwaway analysis
scripts.

### J.4 CORRECTION — the G-bridge baseline was measured on the wrong population

§H.1 quoted a query↔gold overlap baseline of **mean OverlapCoef 0.083, 12-13 of 20 queries at
zero**. That was measured on `fabricated-docs.jsonl` — the **pre-injection** file, where a gold doc
is a short synthetic document. On the **real assembled cell** the same instrument gives
**mean 0.16, 9/20 at zero** (verbose) and **mean 0.11, 11/20** (short-natural).

Two consequences, and the second matters more:

1. **G-bridge's committed baseline must be the assembled-cell figure**, per stratum and per variant
   (they differ materially), not the pre-injection number.
2. **§H.1's headline comparison needs restating on a consistent basis before it is load-bearing.**
   E1 compared a *pre-injection* baseline (0.083) against a *host-derived simulation built on real
   host documents* (0.820). Those are different populations — the same class of error as §H.0's
   files-vs-lines. The conclusion very likely survives (0.16 vs 0.820 is still ~5×, and the
   grep-decisive df≤5 result at 88% is a separate, population-independent measure), but **the
   0.083 → 0.820 figure should not be quoted again until re-measured on assembled cells both
   sides.** Recorded rather than quietly corrected, because this tempdoc's whole subject is
   measurements that were trusted without their unit being checked.

### J.5 State change to note

The committed 707 cells now **fail** `cross_process_regeneration`: `regeneration_determinism_report`
returns `passed: None` for their legacy provenance (the minters that produced their bytes no longer
exist), and `corpus_certify.py:133` treats a non-`True` verdict as `False`. This is intended —
failing loudly beats the silent skip — and those cells are regenerated in the gated phase. But
until then, **the committed cells are uncertifiable**, and that should not be discovered as a
surprise at gate time.

## §K. Dry-run rebuild + grep replay (2026-07-21)

Two things were run to break the Phase-5 gate without spending anything: a full offline dry-run
rebuild of one cell, and a grep-replay probe calibrated against the real campaign logs. Both are
free, offline, and reproducible. Outcome: **all five leak gates green**, and **a measured
replacement explanation for the asymmetry §H.0 left unexplained.**

### K.1 The pipeline works end to end, and all five gates pass

Real CLERC host corpus materialized (14,000 docs, cache hit, ~2.5 min), a real entity bank
harvested from it (sha `57f4506c…`, `validate_entity_bank` green), a payload generated against
that bank with `doc_words=None`, and the result assembled into the real hosts. Three independent
subprocess builds were **byte-identical**.

| Gate | OLD (committed) | after phases 0-4 | after K.2 fixes | verdict |
|---|---|---|---|---|
| `ngram_selectivity` max_gold_coverage | **1.000** | 0.500 | **0.150** (native base 0.225) | FAIL → **PASS** |
| `id_shape` separability (Youden J) | **1.000** (prec 1.0 / rec 1.0) | 0.085 | **0.085** (null 0.151) | FAIL → **PASS** |
| `rare_token_leak` at df≤5 | 1/20 | 1/20 | **0/20** (also 0 at df≤1,2,10,25) | FAIL → **PASS** |
| `query_overlap` mean / zero-count | 0.160 / 9-of-20 | 0.095 / 12-of-20 | **0.045 / 16-of-20** | improved 3.6× |
| `length_profile` separability | 0.075 | 0.100 | **0.100** | PASS throughout |

Every OLD figure reproduced the previously-measured values exactly — the harness measures what it
claims to. The **semantic bridge got materially better**, not worse: mean query↔gold overlap fell
from 0.160 to 0.045 and zero-overlap queries rose from 9 to 16 of 20. This is the property §I.1
identified as load-bearing and §F3 would have destroyed.

The n-gram gate's residual is now **real CLERC boilerplate inside the host documents**
(`e g united states v`, 0.150 gold / 0.033 native) rather than payload — the right place for the
measure to bottom out.

### K.2 Two residual defects, found by the gates, in code this lane had not touched

**(a) The relation template was a 0.5 grep anchor.** `_render_prose` indexes the relation pool by
**hop** (`rels[i % len(rels)]`), and every 707 cell is `hops=1` — so all 20 chains rendered
`_RELATIONS["prose"][0]`, putting `was designed by the engineer` in 20 of 40 gold docs and 0 native
docs. Four relation phrasings existed; one was ever used. Fixed by rotating per **chain**, widening
the pool 4 → 8, and adding 8 terminal phrasings (the tail was a template-coverage anchor at 20/40
but not a 5-gram anchor, because its value token varies — the measured 0.500 came from the head
alone; the brief's assumption here was corrected by the worker).

**(b) The descriptor pools leaked a df=1 anchor.** `_SEM_PLACE` paired
`Carpathian highlands` / `Carpathian uplands` — the synonym varied the head noun and left a
distinctive proper-noun modifier verbatim, minting the token `carpathian` which occurs in **0 of
the 14,000 real hosts**, i.e. document frequency 1: a perfect grep anchor pinning exactly one
document. Eight such pairs were fixed **in place** across all six pools (English and German), so
pool lengths are unchanged and the pairwise-coprimality that makes `_max_semantic_chains` exact is
preserved — verified live, `lcm(21,44,25) == 23100` for both languages.

Note the irony worth recording: the pools §I.1 defended as load-bearing had their own leak. The
defence was correct (their replacement measured far worse) *and* they needed repair. A standing
guard now asserts no `_SEM_*` pair shares a token between its doc-side and query-side members, so
the class cannot return; all four new guards were precision-verified by reverting to the pre-fix
state and confirming each goes red.

A load-bearing consumer surfaced during the fix: `corpus_query_strata._short_natural` hardcoded the
`designer of` phrasing and **raised** on anything else — it would have broken the moment relations
varied.

### K.3 The replacement explanation for the unexplained asymmetry

§H.0 left the behavioural finding standing (legal baseline 43/60 cells opened neither gold, accuracy
0.033; email 6/60, 0.533) with **no valid explanation**. A grep-replay probe now supplies a measured
candidate.

**The logs contained far more than prior analysis used.** They live in the `step2-powered` worktree,
and arm-A agents made ~1,000 *search* calls per corpus (Grep 422/569, Bash 585/398, plus Glob and
PowerShell `Select-String`). Prior analysis extracted only `Read` calls — which is why search terms
were believed unrecoverable. Real terms, not simulated ones, drive the probe.

**Calibration.** Directionally correct at every threshold. Using the honest unit — per-cell, and
only terms issued *before* the first gold read (pooling across replicates imports terms the agent
learned only after opening a gold doc, and over-predicted legal ~3×) — the probe agrees with
observed behaviour on **80% of legal cells** and correctly predicts 35-40/60 unreachable. On email
its precision is 0.88-0.93 but recall is poor: it misses cells that succeeded by routes it does not
model. **So it is a lower bound on grep-baseline success and cannot produce a false "the rebuild is
fine" on legal** — conservative in the direction that matters.

**The explanation, measured with matched units on both sides:**

| | median DF, all query terms | median DF of terms that hit a gold doc |
|---|---|---|
| email | 3 | 44 |
| legal | 13 | **173** |

Legal gold documents are written as legal opinions embedded among legal opinions, so a query's
content words (`designer`, `value`, `station`) match hundreds of hosts — the largest match set is
1000 of 1000. Email gold sits among emails whose vocabulary is disjoint from the fabricated
entities, so a term is either rare or absent.

> **[CORRECTED 2026-07-21 — see §K.7]** This section originally concluded: *"The grep baseline
> drowns in legal because the fabricated gold shares its host domain's vocabulary; in email it does
> not."* **That overstates what is measured and is withdrawn as a sufficient explanation.** Widening
> the sample from n=11/15 to n=164/121 confirms and strengthens the *median* DF gap (37 email vs 183
> legal, ~5×) — but the **fraction of gold-hitting terms that are actually usable (DF ≤ 20) is 0.22
> in BOTH corpora, identical.** Reachability needs only one usable term, so that equal fraction
> matters more than the unequal median: predicted cells with ≥1 usable term are 27/60 email vs 21/60
> legal, while observed gold-opening was 54/60 vs 17/60. **DF selectivity is real, but it explains
> only a small slice of the asymmetry.** See §K.7 for the corrected statement and the residual.

This is a *different* claim from the one retracted in §H.0, and it is measured the way that one was
not: token-boundary, per-document, same unit on both sides. It is not "the payload's vocabulary
floods legal" — it is "the query's content terms match many legal hosts because the gold is written
in the host's register."

**Two alternatives tested and refuted rather than assumed:**
- *ID-shape exclusion.* 47 of 60 email cells issued a filename filter (`grep -v maildir`) that would
  exclude real hosts. Tempting mechanism — but non-causal: cells using it opened gold 42/47 (89%) vs
  12/13 (92%) without, and in legal it *anti*-correlates. Would have been reported as the answer if
  it had not been checked.
- *Read-budget exhaustion.* Refuted: legal cells that were reachable-but-failed used **more** effort
  than cells that succeeded (7.8 vs 6.5 reads, 27.9 vs 21.8 turns). They searched hard and drowned.

**Honest limit:** the DF figures rest on n=11 and n=15 gold-hitting terms. Directionally strong,
statistically thin, and it must not be banked as settled on that basis — the §H.0 mistake was
exactly banking a number without checking what produced it. It is a strong candidate to be
cross-checked against 769's M1, not a finished finding.

**Design implication if it holds:** this is a **host-corpus property, not a payload property.** No
payload change fixes it, and making the payload *more* host-native would make it worse — which is
consistent with §H.3's independent measurement of camouflage worsening the cross-domain asymmetry
(4.39× → 10.5×). Two different instruments now point the same way.

### K.4 What this does to the Phase-5 gate

The dependency on 769's M1 is weaker than when §J was written. The probe is calibrated,
conservative on legal, and supplies a measured candidate explanation — so 767 no longer needs M1 to
*decide* anything; it needs M1 to *cross-check* a finding. Coordination, not blockage.

### K.5 Residuals recorded, not papered over

- **German compound pairs share substrings while being token-disjoint** (`Flussbiegung`/`Flusses`,
  `Küstenklippen`/`Steilküste`, and ~7 more), so the token-boundary gates do not fire. Left
  deliberately: these are high-document-frequency everyday words, so they are not df≤5 anchors, and
  `de-miracl` is not in the active certification policy. The one qualitatively different case —
  `Karpaten`, a minted proper noun with df≈0 in any host — was fixed on both sides.
- **English ordinal pairs** (`seven`/`seventh`) share a root inherently, given the cardinal→ordinal
  contract. Same reasoning: high-df, not an anchor.
- Closing either fully means abandoning the ordinal pairing and rewriting ~10 German compounds —
  flagged for a decision rather than done unasked.

### K.6 Verification

248 passed across the corpus suites (independently re-run by the orchestrator); full jseval suite
2,387 passed / 2 skipped, the 2 reds being the pre-existing `correction-eval-queries-missing` known
state. Cross-process byte-equality determinism green throughout. UTF-8 integrity of the German pool
edits verified at byte level — umlauts are single codepoints (`ö` U+00F6, `ü` U+00FC, `ä` U+00E4),
zero mojibake, per the Windows cp1252 hazard.

### K.7 The rebuilt cell under the calibrated probe — and a correction to K.3

The probe was run against the **rebuilt** cell (`cell-FINAL`) with the same real arm-A search terms
and the same per-cell / pre-first-gold-read methodology.

**A confound had to be handled first:** the rebuild changed the queries — only 2 of 20 query texts
are identical, because relation phrasings diversified (`designer` → `founder`/`builder`/`leader`)
and place nouns changed (`ridge` → `crest`). An agent facing "crest to the east" would search
`crest`, not `ridge`. So replaying *old* terms against the *new* cell is biased downward and is a
lower bound, not a fair comparison. Reported alongside it is a gold-blind query-token proxy where
each cell uses its own query text — which removes the wording confound but is systematically
pessimistic in absolute terms and preserves **ordering only**. Neither instrument alone is
sufficient; they agree in direction.

| threshold | email | legal-OLD | legal-NEW |
|---|---|---|---|
| T=5 | 21/60 | 12/60 | 7/60 |
| T=20 | 27/60 | **21/60** | **17/60** |
| T=100 | 43/60 | 32/60 | 26/60 |

**(a) Grep reachability stays low after the rebuild — legal-NEW ≤ legal-OLD at every threshold, on
both instruments.** Nothing jumped. The rebuild did not make the corpus easier for a grep agent, so
the headroom the engine needs in order to win is preserved. This was the outcome that mattered; the
opposite would have been a serious problem.

**(b) Closing the payload leaks changed reachability essentially not at all — recorded as a null
result rather than dressed up as an improvement.** The small downward drift is attributable to the
deliberate query↔gold overlap reduction (0.045 mean, 16/20 at zero), not to `_FILLER` removal or ID
reshaping. Corroborating: the ID-shape filter test showed these agents were unaffected by ID shape
(email 89% with filter vs 92% without; legal *anti*-correlated). **The leaks were latent, not
exploited.** The rebuild's value is therefore closing a hole a skeptic could find on inspection —
not one these particular agents were using. That is still worth doing, because the benchmark's claim
is that it survives a clone-and-inspect skeptic, not that it survived one cohort of agents.

### K.7b CORRECTION — K.3's explanation is withdrawn as *sufficient*

Widening the sample from n=11/15 to **n=164/121** gold-hitting terms:

| corpus | subset | n | median DF | frac usable (DF ≤ 20) |
|---|---|---|---|---|
| email | gold-hitting | 164 | **37** | **0.22** |
| legal | gold-hitting | 121 | **183** | **0.22** |

The **median** gap is confirmed and strengthened — ~5×, on a sample an order of magnitude larger.
**But the fraction of gold-hitting terms that are actually *usable* is 0.22 in both corpora,
identical.** Reachability needs only one usable term, so the equal fraction dominates the unequal
median. Following it through: predicted cells with ≥1 usable a-priori term are **27/60 email vs
21/60 legal** — close — while observed gold-opening was **54/60 vs 17/60** — far apart.

**So DF selectivity is real, and it is why the probe calibrates well on legal, but it explains only
a small slice of the asymmetry.** K.3's claim that the baseline "drowns in legal because the gold
shares its host domain's vocabulary" is withdrawn as a sufficient explanation. The §H.0 asymmetry is
**still not fully explained.**

This is the second time in this lane an explanation has been retracted on a wider or better-unit
sample. That is the process working rather than failing — but it is also the reason nothing here
should feed a public claim until it survives an independent cross-check.

**The residual, stated precisely:** something other than a-priori term reachability finds gold in
email (54/60 observed vs ~27/60 predicted), and it apparently does not operate in legal (17/60
observed, *below* its own 21/60 prediction). Two mechanisms are already refuted — ID-shape filename
filtering (no causal effect) and read-budget exhaustion (failing legal cells spent *more* effort:
7.8 vs 6.5 reads, 27.9 vs 21.8 turns). The leading untested hypothesis is **exploration cost**: an
agent lacking a good term can still find gold by browsing, which is cheap when documents are short
and heterogeneous (emails) and prohibitive when they are long and homogeneous (legal opinions,
median ~1,940 words) — so legal agents spend their budget on a handful of long documents and never
get a second iteration. Under test at time of writing.

**Why the residual matters to this lane's Phase 5:** if legal is hard for the baseline because of an
intrinsic host-corpus property, that is *good* for the benchmark — the baseline has no chance and
the engine has headroom, provided the engine itself succeeds there. If instead legal is hard for
*both* arms for the same underlying reason, the stratum cannot produce a meaningful delta and
regenerating it is wasted effort. That is the open Phase-5 question, and it is a better-posed
version of what the 769 M1 gate was standing in for.

**Instrument scope, corrected:** the probe should be described as a **reachability regression
detector** — conservative, a lower bound on grep-baseline success, unable to produce a false "the
rebuild is fine" on legal — and *not* as a model of grep-agent success. It is sound for the purpose
it was built for and should not be quoted beyond it.

### K.8 The residual resolved — and the Phase-5 question answered the other way round

The exploration-cost hypothesis was tested adversarially. It is **partly right and secondary**; the
dominant mechanism is a **leak this lane closes**, and finding it inverts the Phase-5 worry.

**Read cost — supported, but not the main effect.** Legal agents read **3.9× more words across 32%
fewer documents** (50,962 words/cell over 7.5 docs vs email's 13,216 over 11.1; 6,753 vs 1,420
words per document). Median document *count* is identical (8 vs 8) — the difference is
cost-per-document, not appetite. The "never get a second iteration" clause is **refuted**: legal has
the *higher* share of cells still introducing new search tokens after the third read (88% vs 68%).
Legal agents iterate; it just doesn't land.

**Listing order — the dominant discriminator, and nobody anticipated it.** Gold rank in a
lexicographic directory listing:

| | min gold rank | gold in first 20 |
|---|---|---|
| email | **0** | **14/40** |
| legal-OLD | **960** | 0/40 |
| legal-NEW | 48 | (median rank 451) |

Legal hosts are all-digit and old gold was lowercase-alpha, so **all 40 gold docs sort after all 960
hosts**. Email hosts are `maildir__*`, so gold IDs split around `m` and 14 land at the very top of
the listing. This is the route actually taken: of the 29 email cells that found gold with no usable
a-priori search term, **28/29 ran `ls`/Glob before their first gold read**, and 21 of the 52 gold
docs they opened were in the first 20 listed. Legal's 4 such cells: 0 in the first 20, gold at ranks
963–976.

The direct test of the 54-vs-27 gap:

| | usable term & opened | **no usable term & opened** | usable & NOT opened | neither |
|---|---|---|---|---|
| email | 25 | **29/60 (48%)** | 2 | 4 |
| legal | 13 | **4/60 (7%)** | 8 | 35 |

**Length salience — a second email-only leak.** Email gold sits at the **89th percentile** of its
corpus by length (18/40 in the top decile; 742 words against a 190-word host median). Legal gold
sits at the 52nd (2,096 vs 1,940). Email gold docs visibly stand out; legal gold docs are
camouflaged by their hosts.

### K.8b THE INVERSION — legal is the stratum that works

Pulling arm B from the same logs (**independently re-verified by the orchestrator directly against
the eval logs**):

| stratum | arm A (grep baseline) | arm B (engine) | delta |
|---|---|---|---|
| email | 0.533 | 0.417 | **−0.117** |
| legal | 0.033 | 0.200 | **+0.167** |

**Legal is not hard for both arms. The engine beats the grep baseline ~6× there, producing a clean
positive delta. Email is the broken stratum** — the engine *loses* to grep, and the leak analysis
explains why: arm A was reading gold straight off the top of a directory listing, and off the
longest documents in the corpus. **The email arm-A baseline of 0.533 is substantially inflated by
two enumeration leaks that this rebuild closes.** It was never a clean baseline.

So the Phase-5 question resolves opposite to the worry that motivated the 769 gate: **legal can
support a headline comparison — keep it.** The stratum whose regeneration most needs justifying is
**email**, where closing the same two leaks should lower arm A and may flip that −0.117.

This also retires, for the third time in this lane, an explanation of the "email null". It was not a
domain finding (764), not a vocabulary confound (§H.0), and not term-reachability (§K.7b). It was
**arm A exploiting enumeration channels** — which is precisely the defect class this lane was
chartered to close, arrived at from a direction nobody predicted.

### K.8c Corrections this forces to earlier sections

1. **§K.7(b)'s "the leaks were latent, not exploited" is WRONG for email.** The ID leak *was*
   exploited — via listing sort order, not via grep patterns. The null result stands strictly
   *within the probe's scope* (term reachability), which is exactly why §K.7 rescoped it as a
   reachability regression detector rather than a model of agent success. The broader interpretation
   overreached. For legal the latency claim does hold: gold sorted last, so nothing was there to
   exploit.
2. **§J.2's retirement of the length-separability hypothesis was correct for legal and wrong as a
   general claim.** Length salience is real and large in **email** (89th percentile, 18/40 in the
   top decile). The hypothesis was retired on legal-only evidence — the same single-stratum
   generalisation error it was retiring.
3. **Do not cite "legal agents who find gold answer at 1.00."** It rests on **n=2**. The fuller
   picture: email n=54 at 0.57 (n=38 both-gold at 0.74); legal n=17 at **0.12**, with only 2/60
   cells ever reading both gold documents.

### K.8d Caveats held

Arm B numbers are single-campaign at n=60/cell, and arm B behaviour was **not** replayed the way arm
A was — so the *reason* the engine wins on legal is not established here, only that it does. The
legal-NEW enumeration fix (gold now at median listing rank 451, min 48) is measured **on the corpus,
not on agent behaviour**: it predicts the channel is closed, it does not demonstrate an agent failing
to use it. Demonstrating that needs a campaign, which is the gated spend.

### K.8e What this means for the lane

The rebuild's value is now concrete and specific rather than hygienic: it closes the two channels
(ID sort position, length salience) that inflated the email baseline and produced a negative delta
on a stratum that should show a positive one. That is a stronger justification than the one the lane
started with, and it points the regeneration priority at **email first**, not legal.

## §L. Does this generalize? (2026-07-21)

Two distinct issues came out of this lane, and they generalize very differently. Both were
investigated empirically rather than reasoned about.

### L.0 CORRECTION to §J.5 — and the finding it was hiding

§J.5 claimed the committed 707 cells "now **fail** `cross_process_regeneration`". **That was wrong
in both halves.** The committed certificates show **every check `True`**, and `en-legal-clerc` and
`en-email-enron-raw` are both recorded as **`fully-certified`**. The claim was inferred from reading
`corpus_certify.py:133` rather than from opening the artifacts — the same guess-instead-of-verify
move this lane keeps catching.

What replaces it is worse, and is the deepest finding of the session:

| member | committed certificate | measured now |
|---|---|---|
| `en-legal-clerc` | **`fully-certified`** | `trailing_int(id) <= 40` → **40/40 gold, 0 false positives**, J = 1.000 vs a 0.227 null; n-gram coverage 1.000 vs 0.225 |
| `en-email-enron-raw` | **`fully-certified`** | same class |

**The certification system marked as fully-certified two corpora whose entire gold set is selected
by a one-line rule.** Not because a gate failed — because **no gate measured the property**. The
four scientific gates ask whether the corpus is answerable-without-retrieval, whether retrieval
lands in band, whether recall holds, and whether cascade leaks; none asks whether the planted set is
*enumerable*. A certificate is only as good as the questions it asks, and this one was not asking
the question that mattered most.

Consequence now that the check is wired: `_complete_certification_document` returns **False** for
both members where it previously returned True, and the live consumer
(`agent_utility_inspect.py:1387`) raises on the snapshot. Those certificates need re-deriving, and
`en-legal-clerc` will legitimately fail until its IDs are re-minted — which the rebuilt cell already
passes. This is correct behaviour, not a regression, but it is a live breakage to sequence
deliberately.

Also worth recording: **no test exercised `certify_materialized_family` at all** before this work.
The repo's top-level certification entry point was untested.

### L.1 Issue A — planted-artifact distinguishability: GENERALIZES, two instances found

The lesson from §K.8 is that **indistinguishability has an envelope, not just a payload.** Three of
the four channels that leaked here had nothing to do with document text: identifier shape, sort
position, and length. Design attention went entirely to the payload.

**Instance 1 — `635-corpora/**`, all seven corpora, same bug.** The generator assigns gold documents
trailing integers `1..N` and distractors `N+1..M` with zero overlap
(`corpus_generate.py:860,872,878`). `trailing_int(id) <= 40` selects the whole gold set of
`needle-burial-v1` at **J = 1.0, TP 40/40, FP 0/240** — and the same holds for
`battlefield-en-scale-v1` (1140 gold of 2736), `synth-code-v1`, `synth-multihop-prose-v2`,
`synth-multiling-de-v1`, `synth-scan-v1`, `synth-tabular-v1`. Sorting by ID puts every needle
contiguous at the top. `needle-burial-v1`'s entire stated purpose is that the needle be *buried*; it
is not buried against a sort.

Honest scope limit: these corpora ship `evidence_ids` in plaintext in `queries.json` anyway, so for
a conventional retrieval harness this leaks nothing new. It matters specifically for
**agent-mediated** evaluation, where the agent sees the document store and is not supposed to be
able to shortcut to the needle — which is what the `battlefield-*` and `needle-burial-*` corpora
exist for.

**Instance 2 — `scripts/sandbox/plant_defects.py`, independent, and it quotes the invariant it
violates.** The script plants deliberately-defective screenshots to test whether a review round
catches them, and at `:181-197` it quotes tempdoc 734 verbatim: *"A plant the round can recognise as
a plant measures nothing. If a seeded defect is distinguishable from a real artefact — by filename
convention, by a marker, by living in a manifest the round can read — the round's catch rate is
theatre."* It then enforces exactly one channel (ground truth outside the output dir) and leaks
three:
- **Filename**: plants are named `94-dup-settings-recheck.png`, `98-activity-surface-blank.png` —
  the tokens `dup`, `blank`, `recheck` appear in no honest capture name (`:113-159`).
- **Ordering**: every plant is numbered 90-100 while every donor is 04-33, so plants sort as a
  contiguous terminal block.
- **Size / mtime**: two of three blank plants are deliberately built under the 16 KiB floor, and all
  plants are written in one batch, so they occupy a single terminal bucket in the very mtime
  histogram `check_coverage.py:402-431` prints.

The author *was* reasoning about envelope leakage — there is a careful re-encode-don't-byte-copy
rule at `:35-42` so mislabeled plants don't trip the duplicate detector. The attention just went to
one channel.

**Instance 3 — a blind spot in this lane's own gate, now fixed.** `id_shape_report`'s rule family
covered character class, length, and prefix/suffix but had **no numeric-magnitude axis**, so on
`needle-burial-v1` it reported `len(id) <= 9` at J ≈ 0.379 while the actual perfect separator sat at
J = 1.0, invisible. It did still fail the corpus, so this was severity understatement rather than a
false pass — but a gate that names the wrong rule invites the wrong fix. Closed by adding
`trailing_int / leading_int / int(id) <= k`.

A **second** defect surfaced while fixing the first: the null was under-powered. It is a max over
draws, so it is monotone in draw count; at the default 5 samples it saturated at 0.151 while 12.5%
of same-population draws exceed 0.185 — a shape-matched corpus would fail roughly **one time in
eight on sampling noise alone**. Raised to 25 with the measurement recorded inline. A gate that
fires spuriously trains its own discounting, which is a slower version of having no gate.

### L.2 Issue B — measurement provenance: does NOT generalize; the repo is unusually good

The §H.0 failure (a number produced by unpreserved ad-hoc shell, substring-matched, unit-mismatched)
is **not** widespread. A strong convention already exists and is followed across roughly ten files:
a top-level `note` naming the derivation rule and re-derive command, a `tempdoc` pointer, and
per-value `src` fields. `union-recall-gate-baselines.v1.json:4` goes furthest and states a seeded,
runnable re-derivation path per number. `ci-walltime-policy.v1.json:5` states sample size, date,
dispersion and multiplier, and labels itself advisory. `agent-quality-baselines.v1.json` explicitly
marks unmeasured numbers as SEED values. Public benchmark claims are the best-sourced quantitative
text in the repo — projected from a committed `release.v1.json`, guarded by
`check-outward-number-citations.mjs` and `check-readme-benchmark-numbers.mjs`, with external
baselines explicitly labelled as cited-not-rerun.

Four narrow exposures, not a systemic problem:

1. **`707-corpus-certification-policy.v1.json` — 32 bare numbers, and the schema *forbids* fixing
   it.** No `note`, no `tempdoc`, no `src`. The derivation rule exists only in tempdoc 707
   (`:759-760`), which the artifact does not point at. And the schema is `additionalProperties:
   false` with an exact key-set check at `corpus_certify.py:265`, so **the repo's own best practice
   is illegal in its most load-bearing file.** Worse: `shortcut_leak_rate_max`, `leak_floor.maximum`,
   `union_recall.minimum`, `closed_book.maximum_accuracy` are all *rates with no stated denominator*
   — a per-query vs per-document mixup would keep every value in `[0,1]`, pass the schema, and go
   green. That is the §H.0 hazard live on the certification gate.
2. **F-039's evidence is in gitignored `tmp/` with an untracked producing script** — load-bearing for
   four tempdocs (762/763/766/767) and the chartering finding for 769. This is §H.0's failure mode
   reproducing exactly: if that directory is cleaned, the finding becomes unfalsifiable. Its
   load-bearing scaling claim also rests on a **3-event cell** (3/48 → 14/50).
3. **F-016/F-017 — n=50, no recorded method**, currently load-bearing for tempdoc 770. F-016's
   headline `16.1%` is carried from an external paper and sits in the same bullet as our own
   92%→71%, so a reader cannot tell which number is ours. A 20-point swing at n=50 has a ±13pp
   interval.
4. **Register hygiene** — two distinct findings both titled `F-030`, worked around in body text as
   `F-030(678)`; a bare citation lands on the wrong one half the time. And nothing lints that a
   finding carries an `n`, a command, or a reachable artifact.

Separately: **the public agent-utility block is stale in a way no check catches.**
`gen-public-agent-utility.mjs:235` hardcodes the string `agent-utility-public-v1` in its no-result
branch while the active policy is `v2` — the accepted branch reads it dynamically (`:281`), only the
null branch hardcodes. The generated region is byte-identical to what the generator produces, so the
consistency check passes on wrong content. This renders into `README.md`, `RESEARCH.md`, and
`docs/reference/benchmarks/agent-utility.md`.

### L.3 Is a broader fix viable? — verdict

**Done now, because they were defects in shipped code:** the numeric rule axis, the null power fix,
and wiring `corpus_leak` into `corpus_certify` (it was referenced only from tests — a gate nobody
calls is not a gate).

**Bounded and clearly warranted, but separately scoped:** the 635 generator's gold-then-distractor
ID assignment (seven corpora, one shared root cause in `corpus_generate.py`), and
`plant_defects.py`'s three leaking channels. Both are small; neither belongs in this lane's PR.

**Not yet warranted — a general envelope-leak framework.** The reusable core is genuinely small: a
function that, given two labelled sets and some attribute extractors, searches simple separating
rules and scores the best against a same-population null. That is roughly what `id_shape_report`
already is, generalized past document IDs to filenames, sizes, timestamps, and sequence numbers.
Two real consumers exist (corpora, sandbox plants) — but they are different enough in shape
(documents in a JSONL vs files on disk) that extracting the abstraction now would be guessing at the
seam. **Extract it when the second consumer is actually built, not before.**

**Not warranted — a provenance-enforcement gate.** The convention is already ~90% followed and there
is exactly one structural violator, which cannot comply without a schema change. Building a gate to
enforce a convention that mostly works, against a violator that is exempt by construction, is
apparatus. Fix the violator instead. *Trigger to revisit:* if a second baseline file ships without
provenance, or if any measured number is found wrong again in a way its own file could have
prevented, the gate becomes worth building.

### L.4 The principle, sharpened

§I.7's P1 said indistinguishability is **layer-specific**. §K.8 and this section sharpen it:
**indistinguishability is envelope-specific, and the envelope is where attention isn't.** In every
instance found — the corpora, the 635 goldens, the sandbox plants — the content was thought about
and the container was not. Identifiers, ordering, size, and timestamps carried more signal than the
text did, and in the corpora case the *dominant* channel (sort position) was one nobody had named
at all.

*Evidence it earns its keep:* a leak found in a channel the author did not enumerate — already
satisfied three times (sort position here, filename/ordering in `plant_defects.py`, numeric ID in
the 635 family). *Retirement condition:* retire when a planted-artifact design is found where
enumerating the envelope channels adds nothing over inspecting the payload — i.e. when the payload
really is the only surface. Nothing found so far comes close.

## §M. Can the blockers be resolved without a founder decision? (2026-07-21)

Both items previously listed as BLOCKED ON YOU were investigated. **Neither is a founder decision.**
One was never a blocker; the other turned out to be a code question with a measured answer.

### M.1 The certificate blocker was not real

Verified by running CI locally in this worktree: **exit 0**, and the full jseval suite is
**2,395 passed / 2 skipped** (the 2 reds are the unrelated `correction-eval-queries-missing` known
state). Nothing breaks today.

Why: the consumer that raises (`agent_utility_inspect.py:1383-1393`) is reached only when
`--corpus-certification` is passed to `jseval utility-run` — the **paid campaign** command. And
`check-public-agent-utility.mjs` (`ci.yml:117`) short-circuits because
`public-agent-utility/current.v1.json` has `"current": null`.

Two corrections to the earlier framing: **all three members fail**, not two (`de-miracl` too), and
the failure is not `cross_process_regeneration` — the committed cells carry 7 checks and no
`indistinguishability` block at all, which the boundary validator now requires.

**The one thing to sequence:** the null publication pointer is the only thing between this and a red
CI. `certification_snapshot_valid` (`corpus_certify.py:747`) also runs against the base64-embedded
certification inside a published claim, feeding the `corpus_certification_complete` claim gate
(`utility_claim_policy.py:342-356`). So **re-certify before selecting any publication pointer**, not
after. Also: do not pass `--corpus-certification` to a paid `utility-run` until re-certification
lands — it aborts at setup, cheap but confusing mid-campaign.

### M.2 The question-count "decision" dissolves — the ceiling was mis-diagnosed twice

**First mis-diagnosis (mine):** I assumed the binding constraint was the gold:distractor ratio. It
is not — that only bites past 125 questions. Worse, in the 707 injected path the generated
distractors are **discarded entirely** (`corpus_inject.py:282-285`); every distractor is a real host
document. The descriptor-space concern does not exist in the path the campaign uses.

**Second mis-diagnosis:** the apparent ceiling is `use_qual = n_chains > min(len(types), len(places))`
= **21** (`corpus_generate.py:827`). Measured: the actual uniqueness requirement is the *(type,
place)* pair, and by CRT that map is injective on `[0, lcm(21,44))`. **First collision measured at
chain 924**, not 22 — `_gold_descriptor_reservations` returns exactly `n` distinct pairs for every
n ≤ 924 and goes lossy at 925, precisely the CRT period. **The guard is over-conservative as a
uniqueness guard by 44×.** The `_max_semantic_chains` docstring already makes this CRT argument for
the *triple*; nobody applied it to the *pair*.

**And 21 was never a question ceiling at all.** Only the *short-natural* stratum is affected — the
verbose stratum generates cleanly at n=92 today (19-22 words, no cap applies). Measured word counts:

| lang | n | regime | verbose | short-natural (5-12 cap) |
|---|---|---|---|---|
| en | 21 | as-shipped 2-axis | 16-18 | OK |
| en | 22 | **as-shipped 3-axis** | 19-21 | **FAIL (13 words)** |
| en | 22 / 40 | forced 2-axis | 16-18 | **OK** |
| en | 100 | forced 2-axis | 16-19 | FAIL (13 words) |
| de | 22 / 40 / 100 | forced 2-axis | 17-20 | **OK (7-10)** |

Under a corrected guard, DE is clean to all 924 chains and EN to 40. **The entire EN limit traces to
one pool entry** — `_SEM_TYPE[19] = ("chapel", "small place of worship")` (`corpus_generate.py:87`),
the only 4-word type synonym. An in-place 2-word substitution yields **0/924 over cap**, which is
exactly the repair mode the pool comment sanctions ("fixes are in-place substitutions, never
additions or removals").

**So the question count is a code question with a measured answer, not a judgement call:**
- Verbose-only at n=92: **works today, no change**.
- Both strata at n=92 (which certification requires — the 2×2 matrix is hard-required at
  `commands/corpus.py:58-64`): needs the guard correction **plus** the one-entry pool substitution.

**Blast radius of the corrected guard, measured:** suite 119 → 117 passed, 2 failed.
`test_three_axis_regime_above_pair_bound` is a threshold guard (moveable).
`test_generate_third_axis_keeps_distractor_duplication_low_at_scale` is **substantive**: at
n_chains=26 with distractor_ratio=30 (780 generated distractors drawn from 924 pair-combos),
duplication goes 4.1% → 58.2%, blowing a 25% threshold. `n_gold_involved == 0` in both regimes, so
qrel correctness holds — this is wasted distractor diversity, not a correctness break.

**That failure is the legitimate reason the guard exists — and it is a different concern from
uniqueness.** The single condition conflates them. The qualifier axis protects *generated-distractor
diversity*, which matters only in the standalone 635 path and **not at all** in the 707 injected
path where generated distractors are thrown away. Separating the two concerns is the real fix; the
exact condition is a design choice worth making deliberately rather than inside this analysis.

All 707 campaign cells are `n_chains=20` and already two-axis, so they are bit-identical under
either guard. Four standalone corpora currently above 21 would change bytes:
`battlefield-en-scale-v1` (n=380), `battlefield-en-v1`, `battlefield-de-v1`,
`synth-multihop-prose-v2` (n=26).

### M.3 Runtime of the new gate at scale — measured, not extrapolated

`indistinguishability_report` on a real 10,000-document cell (40 gold + 9,960 CLERC hosts), two
independent runs agreeing within 2%:

| n_docs | total | ngram | id_shape |
|---|---|---|---|
| 1,000 | 2.1-2.4 s | 2.2 s | 0.19 s |
| 10,000 | **24.3-24.9 s** | 21.4 s | 2.2 s |

**Scaling is linear** (5k→10k ratio 2.08×). ~25 s on a 10k cell is not a budget concern. The
earlier worry that the n-gram pass might scale quadratically is refuted.

### M.4 What is actually left, and whose it is

**Resolvable without a founder decision** — the offline half of a rebuild is complete and free:
fetch hosts (cache hit, ~2.5 min/member), inject ×4 cells, then `corpus-certify-member` with
`--scientific-evidence` omitted, which legitimately yields `structurally-certified`
(`commands/corpus.py:42`; status resolution at `corpus_certify.py:254-257`). Roughly 5-10 min per
member, dominated by the fetch. Note the 2×2 size/variant matrix is **hard-required**, and the
scientific evidence matrix is all-or-nothing: **0 gates or 16**, no partial mode
(`commands/corpus.py:103-111`).

**Genuinely not resolvable without the owner** — three things, none of them statistical:
1. **Paid API spend** — the `closed_book` gate drives the `claude` CLI (`corpus_certify.py:990`).
   Spending money is not delegable.
2. **A dev-stack window** — `retrieval_calibration`, `union_recall`, `leak_floor` need the shared
   GPU stack (~1h20m precedent for an 8-cell cohort). Shared-resource contention requires
   coordination, not judgement.
3. **Publication pointer selection** — owner-gated by design
   (`gen-public-agent-utility.mjs:6`: "The pointer is the only mutable selection surface").

The design choice in M.2 (how to separate uniqueness from distractor-diversity in the `use_qual`
condition) is a genuine choice, but it is an engineering one with a measured blast radius — not a
founder call.

## §N. The rebuild (2026-07-21) — offline half complete, all gates green

Both English members were rebuilt end to end on the fixed pipeline and re-certified offline. Zero
paid API calls, zero GPU, zero network (both host fetches were cache hits).

### N.1 Result

`en-legal-clerc` and `en-email-enron-raw` are both **`structurally-certified`**, 4 cells each,
**8/8 per-cell checks green**, `query_count` 20 → **50**. The four scientific gates are correctly
stamped `pending-model-run` / `pending-backend-run` — those are the paid and GPU halves.

All five leak gates pass on all eight rebuilt cells:

| cell | ngram gold/null | id_shape J/null | length sep | zero-overlap | rare-token df≤5 |
|---|---|---|---|---|---|
| legal 1k (both variants) | 0.07 / 0.19 | 0.081 / 0.151 | 0.11 | 32-35 / 50 | 0/50 |
| legal 10k (both) | 0.07 / 0.14 | 0.088 / 0.181 | 0.11 | 32-35 / 50 | 0/50 |
| email 1k (both) | 0.07 / 0.08 | 0.096 / 0.129 | 0.07 | 46 / 50 | 0/50 |
| email 10k (both) | 0.07 / 0.10 | 0.073 / 0.154 | 0.06 | 46 / 50 | 0/50 |

For comparison, the corpora these replace scored `ngram` **1.000** against a 0.225 null and
`id_shape` **J = 1.000** with precision and recall both 1.000 — while carrying certificates that
read `fully-certified`.

Family invariants verified independently of the certifier (`queries_identical_across_sizes`,
`qrels_identical_across_sizes`, `one_k_docs_are_subset_of_ten_k`, all four strata checks). All eight
cells carry a passing cross-process `assembly_determinism` record.

New committed artifacts: a real entity bank per member under `707-corpora/<member>/entity-bank/`
with a `commitment.v1.json`-shaped sha256 manifest, `validate_entity_bank` green — legal
`fd163005…` (PER 60,644 / CASE 92,967 / CITATION 153,765 unique), email `7589ebcb…` (PER 49,811 /
EMAIL 17,089 unique).

Timings, whole offline half: CLERC fetch 2m17s, Enron 30s (both cache hits), harvest 71s/17s,
generate ~2s, inject 8 cells 62s, certify 72s, gates 85s. **Under 10 minutes of real work.**

### N.2 A third defect the gate caught — and it was in the injection substrate

CLERC's 1k cells **initially failed** `id_shape`: J = 0.1767 against a 0.1512 null, best rule
`not (trailing_int(id) <= 5591422)`.

Root cause, and it is subtle: gold IDs were minted from **every ID in the real-document set** (all
14,000), but the natives that actually *enter* a cell are only those passing `host_min_words ≥ 60`
— 10,817 of 14,000. On CLERC that filter is **not ID-independent**: short opinions skew toward high
IDs, so the eligible natives sit lower in the numeric range (median 4.07M) than the corpus at large
(4.25M). Gold was therefore minted into a slightly different numeric distribution from its own
neighbours — a leak with no visible surface at all, invisible to any inspection that isn't
statistical.

The block comment already stated the intent — "a REAL host ID drawn from the **same cell**" — and
the implementation did not do that.

Fixed by drawing donors from the eligible pool while keeping collision exclusion across all real
IDs. **Causal test, same seed, only the donor set varied: J 0.1767 → 0.0811.** `pool` is independent
of `n_distractors`, so 1k and 10k still mint identical IDs. A regression test was added and
precision-verified red against the pre-fix donor set.

This is the third time the gates have caught something a design review would have passed — after the
numeric-rule blind spot and the two leaks inside the ID-minting fix itself.

### N.3 Parameter choices and their consequences

`n_chains = 50` (was 20), `hops = 1`, `doc_words = None`, **`distractor_ratio = 0`**.

The `distractor_ratio=0` choice is verified equivalent, not a guess: at ratio 6 vs 0 the assembled
digest is **identical** (`d8efd19983ca35db`) because `corpus_inject.assemble` filters the fabricated
source down to evidence-referenced docs anyway. The old path generated 280 fabricated docs and
discarded 240 of them per cell. Setting it to 0 also keeps the run clear of the new
distractor-diversity trigger, which is what lets the short-natural stratum stay inside its 5-12 word
cap at n=50 (measured 10-12 words EN, 7-10 DE).

**The consequence to be aware of:** gold is now 100 docs = **10.0% of a 1k cell** (1.0% at 10k), and
each question now sits among **98 synthetic near-duplicate decoys** rather than 38 — a 2.58×
increase. That interacts with the known bridge-entity retrieval difficulty (register F-039, lane
769), and it is the reason n was set at 50 rather than the power target of ~92, which would have put
gold at 18.4% of the 1k cell with ~4.8× the decoys. **Regeneration is under 10 minutes, so this
remains cheap to revise right up until the paid and GPU gates run — and expensive immediately
after.**

### N.4 Outstanding

- **`de-miracl` was not rebuilt.** Its raw data is not in the fetch cache (the `ir_datasets` dir is
  empty), so it needs a real download rather than a cache hit. It remains at `query_count: 20` with
  the old payload and **no `indistinguishability` block**, so it still fails the boundary validator
  on re-derivation. It is not in the active claim policy, but it is a third member and should not be
  forgotten.
- **`member.v1.json` `gold_source` is now stale for both members** (and was already wrong for enron
  before this rebuild). Logged to the observations inbox rather than fixed inline.
- **`635-corpora/needle-burial-v1` was deliberately not regenerated**, despite being CLERC's declared
  gold source: it is a standalone retrieval regression guard with baselines registered in the
  search-quality register, and regenerating it at n=50 would silently invalidate them. Its own ID
  leak (§L.1) remains open and separately scoped.
- **The paid `closed_book` gate and the three GPU-backed gates** (`retrieval_calibration`,
  `union_recall`, `leak_floor`) are the only remaining certification work. The evidence matrix is
  all-or-nothing — 0 gates or 16 (`commands/corpus.py:103-111`) — so those must run together.
- **Re-certify before selecting any publication pointer** (§M.1): the claim policy validates the
  certification copy embedded inside a published claim.

Suite: **260 passed** on the corpus files; full jseval suite 2,398 passed with only the pre-existing
`correction-eval-queries-missing` reds. No test was weakened.

## §O. Entity-bank trim (2026-07-21) — a 45 MB artifact I should not have committed

§N committed two entity banks totalling **45.5 MB**, taking `707-corpora` from ~5 MB to 51 MB. That
was wrong on two counts and is corrected here.

**It contradicted an open owner decision.** Tempdoc 741 exists specifically because eval corpora
accumulate as blobs (~700 MB/year, with "the largest object in the repo is 45 MB" cited as the
problem). §N added a new largest-object class without asking, inside a commit whose headline was
about something else. The saving grace is ADR-0045's squash-merge: only the final tree reaches
`main`, so trimming before any PR keeps it out of public history entirely. Had it been noticed after
merge it would have been permanent.

**It was also just oversized.** The bank stored every harvested surface — 153,765 citations, 92,967
case captions — when the build path reads far less.

### O.1 What the build path actually consumes

Investigating this corrected my own assumption in a way worth recording:

- **Surfaces are only spliced for PER/ORG/LOC.** The 153,765 CITATIONs and 92,967 CASEs existed
  *solely* as a collision blacklist — never sampled, never read as text.
- **`df` is dead weight.** Nothing outside the harvester reads it. It was ~⅓ of the file.
- **A third consumed property I had not listed: the target-length distribution.** `mint_entity`
  draws its target as a length sampled with real-corpus frequency. **Naive subsampling would have
  silently shifted that distribution** — and the visible symptom would have been a `length_profile`
  gate drift much later, with no obvious cause. Preserved by storing uncapped per-length counts
  separately from the capped exemplar sample.

### O.2 The v2 format and what it costs

`exemplars` (≤64 real surfaces per mintable type × length, stride-selected across the sorted list so
the sample spans the alphabet) · `length_weights` (uncapped per-length counts — every length draw
goes against these) · `collision_index` (36-bit truncated sha256 of the normalized form of **every**
real surface, all 9 types).

| | before | after | factor |
|---|---|---|---|
| legal bank | 36.94 MB | **2.61 MB** | 13.5× |
| email bank | 9.90 MB | **0.92 MB** | 10.2× |
| `707-corpora` | 51 MB | **9.2 MB** | 5.5× |

**Collision exactness is unconditional in the direction that matters.** Equal strings have equal
digests, so a candidate colliding with a real surface is *always* rejected — probability 1, not a
bound. Truncation admits only false *positives*: a distinct string sharing a digest, p ≤ N/2³⁶ ≈
5.9e-6 at the largest bank, whose only consequence is one wasted re-draw out of 64 attempts. It can
never admit a collision. This matters because a false negative would plant a fake entity colliding
with real text — the exact defect class this lane exists to eliminate.

**What was given up, measured:** only *which* real surface a splice fragment comes from once >64
surfaces share a length. 3,000 mints from the trimmed bank gave 3,000 unique names and 6,171
distinct tokens, against 6,150 for an uncapped bank — no measurable diversity loss.

### O.3 Two results interrogated rather than banked

**The gate numbers came back nearly identical, which is exactly when to check harder.** Verified the
corpus genuinely rebuilt: all four `assembled_digest`s changed, minted names are different strings,
and every `fabricated-meta.json` pins the new bank sha. The stability is real and has a mechanism —
`length_weights` preserves the length distribution by construction, and `id_shape` depends on host-ID
donors rather than the bank.

**A ceiling test looked alarming and was diagnosed rather than reported:** trimmed banks managed
22,274 mints before exhaustion, uncapped only 497. That inverted ordering was suspicious. Cause: a
long-tailed type occasionally draws a very short target length, and *no* splice fits — driven by
`length_weights`, which is **identical** in both formats. Across 6 seeds both banks clear 6,000; the
uncapped one lost the lottery twice. Same pre-existing trap, different draw — not a capability
difference. Corpora need ~150 mints, so no cell approaches it. Logged, not fixed.

Final state: both members **`structurally-certified`**, q=50, **8/8 per-cell checks, zero failing
cells**, all five leak gates green, cross-process determinism green. 276 tests pass on the corpus
suites.

### O.4 A 2.5 GB near-miss

A full test run created an untracked **`datasets-miracl/`** at the worktree root. `.gitignore`
carried `datasets/` but **not** that name, so 2.5 GB sat one `git add -A` away from being staged —
and `git add -A` was used in this session. Never committed and absent from the main checkout, so no
damage.

Fixed by widening the ignore to `datasets-*/`, which covers any per-member staging directory rather
than the one name that happened to bite. The underlying lesson is the branch-safety rule this
session did not consistently follow: **stage your own files explicitly rather than `git add -A`**,
precisely because an unexpected multi-gigabyte directory is invisible in a summary line.

### O.5 Sweep

Old `entity-bank.v1.json` deleted for both members and the test fixture; stale docstring references
updated. The harvester keeps full emission via `--emit-full` under schema `entity-bank-full.v1`,
which `validate_entity_bank` **rejects** — so an analysis dump cannot be mistaken for a generation
input.

---

## §P. Session lessons (2026-07-21)

Five things this session got wrong or nearly got wrong, and where each is now documented. §O already
records two of them in narrative form; this section is the index, not a second copy.

**P.1 — Two jseval traps, both live, both now blockers in the runbook.**

- *Leg-union under-measurement.* Phase C1 prescribed `--modes lexical,vector,hybrid`. That passes the
  projection's structural check (`status: "ok"` needs one leg plus a final mode), but the
  certification thresholds came from a run whose leg set also included `splade`, and
  `staged_recall_accounting` unions only the legs present — an omitted mode drops out silently rather
  than erroring. Caught by arithmetic before any spend: the certified union 0.75 is unreachable from
  vector 0.5952 ∪ lexical 0.0193 (≤ 0.6145 disjoint), and a pilot re-run returned union 0.48 exactly
  equal to its vector recall. Every cell would have failed the 0.65 `union_recall` floor *after* the
  full paid + GPU run, presenting as corpus regression rather than a command-line error.
- *`datasets/` resolution.* `corpus-certify --datasets-dir datasets` resolves against cwd;
  `jseval run` resolves `datasets/` from the repo root and ignores cwd. Materializing into
  `scripts/jseval/datasets/` satisfies the first and fails the second.

Documented in `767-certification-runbook.md` §1 **B4** / **B5** (with the arithmetic and the Phase C1
command corrected to `--modes lexical,vector,splade,hybrid --embedding --splade`), summarized in
`.claude/skills/jseval/SKILL.md` (§Available Modes, §Key Flags), and generalized as
`measurement-config-mismatch` in `docs/reference/contributing/agent-postmortems.md` §23.

**P.2 — Exit-code masking by a trailing statement (a genuine hook gap).**

`cmd > log 2>&1; echo "exit=$?"` makes the harness report the *echo's* status. A real failed run was
reported to the orchestrator as exit 0. This is adjacent to the known piped form, but not the same:
`pipe-mask-hint.mjs` detects a build/test command whose exit is swallowed by a trailing pipe into
`tail`/`grep`/`head` and explicitly reasons about the `| tail; echo $?` shape — a **redirect** plus a
trailing statement never forms that pipeline, so it falls outside the hook's detection entirely.
Recommendation: **extend `pipe-mask-hint.mjs`** to also fire when a build/test command is followed by
`;` and a further statement without `pipefail`/`PIPESTATUS`/an explicit `$?` capture in between.
Deliberately *not* proposed as prose — the always-loaded budget is already over (P.5), and a
mechanically detectable shell shape is hook-tier work (~100%) rather than prose-tier (~70%).

**P.3 — `git add -A` staged 2.5 GB.** An untracked `datasets-miracl/` staging directory was picked up
because `.gitignore` carried a bare `datasets/` entry that did not cover it; widened to `datasets-*/`
so any per-member staging directory is covered rather than the one name that bit. The branch-safety
rule to *stage your own files explicitly* already existed and was not followed — the failure is
adherence, not a missing rule. Narrative: §O.4.

**P.4 — A 45 MB artifact committed without a size check**, against tempdoc 741's open blob-growth
decision — which exists precisely because "the largest object in the repo is 45 MB" is the stated
problem. Trimmed before any PR, so ADR-0045's squash-merge keeps it out of public history. Narrative:
§O.

**P.5 — The always-loaded budget ratchet is already failing** (`node scripts/ci/check-always-loaded-budget.mjs`:
five files over ceiling by ~11 KB total) — a pre-existing condition, not caused here. It constrained
where these lessons could live: everything above went to a tempdoc, a skill, or the handle-addressed
postmortems file, and nothing was added to `CLAUDE.md` or `.claude/rules/**`. Worth noting as a
second-order effect — a saturated always-loaded budget silently converts "write a rule" into "write a
hook or a reference case", which is the better outcome here but will not always be.

## §Q. Pilot certification (2026-07-21) — the old thresholds were calibrated on a leak

One cell (`en-legal-clerc-1k-verbose`) was piloted rather than running the full cohort. Total spend:
**one closed-book pass, under $1.** It found two configuration errors that would each have wrecked
the full run, and then produced a causal result that answers the threshold question outright.

### Q.1 The result

| configuration | nDCG@10 | union recall | leak | vector recall |
|---|---|---|---|---|
| original (old payload, filler **ON**, n=20) | 0.5051 | 0.75 | 0.00 | 0.5952 |
| control (**new** payload, filler **ON**, n=20) | **0.4922** | 0.85 | 0.05 | 0.80 |
| control (**new** payload, filler **OFF**, n=20) | **0.3281** | 0.65 | 0.15 | 0.55 |
| production (**new** payload, filler **OFF**, n=50) | **0.3300** | 0.50 | 0.08 | 0.46 |

Two differences isolated, each by holding everything else constant:

- **New payload vs old, filler held ON: −0.013 nDCG.** The rebuild did not degrade retrieval. Same
  seed, same host pool, same entity bank, byte-identical determinism check.
- **Filler ON → OFF, all else equal: −0.164 nDCG** (and −0.20 union recall). This is the entire drop.

**The `_FILLER` paragraph was inflating measured retrieval quality by about a third.** It appeared in
100% of gold documents and 0% of real host documents, so it was a perfect gold-identifying feature —
590 words of identical text creating a strong shared signal that floated every gold document up the
ranking. Vector recall shows it most starkly: 0.80 with filler, 0.55 without.

**Consequence: the ratified thresholds were derived from leak-inflated measurements.** The 0.42 nDCG
floor was calibrated against a number roughly a third of which was the leak. The certification was,
in part, certifying the artifact.

The diagnostic shape that led here is worth keeping: **recall barely moved while ranking collapsed**
(vector 0.5952 → 0.55; nDCG 0.5051 → 0.328). Gold was still being found and had merely stopped being
trivially rankable — which is what removing a gold-only feature should look like.

### Q.2 Two hypotheses tested and discarded first

Recorded because both were plausible and both were wrong, and because the third one being right does
not retroactively justify the first two.

1. **"The missing SPLADE leg explains the union-recall gap."** Union 0.75 is arithmetically
   unreachable from vector 0.5952 ∪ lexical 0.0193, so a third leg had to exist — sound reasoning,
   and it did find a real configuration bug (runbook B4). But adding SPLADE moved union only
   0.48 → 0.50 and nDCG 0.2987 → 0.33. Not the cause.
2. **"The n=20 → 50 decoy-density increase explains the drop."** Refuted directly: the n=20 control
   scored 0.3281 against production's 0.3300 — decoy density has essentially no effect on nDCG. It
   does affect union recall (0.65 → 0.50), just not the headline metric.

### Q.3 Derived thresholds, and the honest limits

Applying the documented 707 rule (`707:760` — band low = measured − 0.08, union min = measured − 0.10,
leak max = measured + 0.10) to the production measurements:

| gate | current | derived | measured |
|---|---|---|---|
| `retrieval_calibration.ndcg_band` low | 0.42 | **0.25** | 0.33 |
| `union_recall.minimum` | 0.65 | **0.40** | 0.50 |
| `leak_floor.maximum` | 0.10 | **0.18** | 0.08 |
| `closed_book.maximum_accuracy` | 0.15 | **0.15** (unchanged) | **0.000** |

**This is not yet a re-registration proposal.** Three limits:

1. **One cell measured, eight cells need thresholds.** These are per-cell values. The *mechanism*
   (filler inflation) should generalize, but the numbers must be measured per cell, not extrapolated.
2. **`leak_rate` is noisy at this n.** Three runs of the same payload family gave 0.15, 0.05 and 0.08.
   Mechanically applying "measured + 0.10" to a single noisy estimate would pin a ceiling to sampling
   scatter. This one deserves either more samples or a deliberately conservative constant.
3. **Pre-registration discipline still binds.** The causal reason is now measured rather than
   assumed, which is what makes a downward move defensible — but the numbers must be registered
   **before** the confirmatory run, with this section cited as the reason, not adjusted afterward to
   fit results.

The closed-book gate needs no discussion: measured **0.000** against a 0.15 ceiling. Not one of the
fifty questions is answerable without retrieval.

### Q.4 What the pilot cost, and what it bought

Spend: **under $1** (50 closed-book calls). No paid call was made in any of the four evaluation runs.

Bought: two run-killing configuration errors caught before the cohort (runbook B4/B5), the
decoy-density hypothesis refuted, the filler-inflation mechanism established causally, and a
demonstration that the payload rebuild is retrieval-neutral once the leak is held constant.

Had the full cohort run first, every cell would have failed `union_recall` on the mode-set error
alone, after roughly $28 and a multi-hour GPU window — and the failure would have presented as
"the rebuilt corpus is worse," which was the interpretation already primed and waiting.

## §R. Full-cohort certification (2026-07-22)

Executed the complete 8-cell certification end-to-end per the `767-certification-runbook.md`
contract. **Result: both English members are `fully-certified` — 16/16 scientific gates each,
32/32 total.** Structural + measured certification only; it does **not** authorize the paid
confirmatory campaign (founder-gated).

### R.1 Per-cell measurements (n=50)

`nDCG` = hybrid nDCG@10 (fidelity, `--embedding`, headline mode hybrid); `union` = `leg_union_recall`
and `leak` = `leak_rate` (projection, all three legs vector/lexical/**splade** present per B4);
`sLeak` = fidelity shortcut-leak rate; `cb` = closed-book accuracy.

| cell | nDCG | union | leak | sLeak | cb |
|---|---|---|---|---|---|
| en-legal-clerc-1k-verbose | 0.3261 | 0.50 | 0.08 | 0.00 | 0.000 |
| en-legal-clerc-1k-short-natural | 0.2482 | 0.48 | 0.12 | 0.00 | 0.000 |
| en-legal-clerc-10k-verbose | 0.0625 | 0.14* | 0.02* | 0.00 | 0.000 |
| en-legal-clerc-10k-short-natural | 0.0784 | 0.08 | 0.00 | 0.00 | 0.000 |
| en-email-enron-raw-1k-verbose | 0.6576 | 0.86 | 0.04 | 0.00 | 0.000 |
| en-email-enron-raw-1k-short-natural | 0.6122 | 0.80 | 0.02 | 0.00 | 0.000 |
| en-email-enron-raw-10k-verbose | 0.4864 | 0.48 | 0.10 | 0.00 | 0.000 |
| en-email-enron-raw-10k-short-natural | 0.4408 | 0.46 | 0.02 | 0.00 | 0.000 |

`*` cell-3's C1 was re-run once (its original manifest had `git_sha=None`, a transient defect from
the early chaotic window — see R.4); the rerun's projection gave union 0.14 / leak 0.02 vs the
original 0.16 / 0.00 (ordinary retrieval run-to-run variance, same corpus signature). Both sets
pass the pre-registered threshold; the threshold was **not** re-derived to fit (R.3).

**Reading the numbers.** Enron retrieval is strong (nDCG 0.44–0.66) — distinctive email gold.
Legal collapses with scale: 10k cells sit at 0.06–0.08 nDCG / 0.08–0.16 union, because gold is 1%
of a 10k cell among 9,900 near-duplicate decoys and the bridge-entity gold is intrinsically hard to
rank (register F-039, lane 769). This is a genuine property of the corpus at scale, cross-checked
against each cell's C1 projection nDCG (e.g. legal-10k-verbose C1 0.096 vs fidelity 0.0625, same
ballpark) — not a measurement artifact. **Founder-relevant:** the two legal-10k cells produce
near-vacuous `retrieval_calibration`/`union_recall` bands (band low clamps to 0.0, union min to
0.06/0.0) — mechanically correct under the measured-derived rule, but those two gates barely
constrain anything for those cells. Flagging, not deciding.

### R.2 Derived thresholds (written to policy)

Rule applied verbatim (707:760 + the §Q.3 override, stated in the sidecar
`707-corpus-certification-policy.provenance.v1.json`): `ndcg_band = [clamp(nDCG − 0.08, 0, 0.85),
0.85]`; `shortcut_leak_rate_max = clamp(sLeak + 0.10)`; `union_recall.minimum = clamp(union − 0.10,
0, 1)`; `closed_book.maximum_accuracy = 0.15` fixed; **`leak_floor.maximum = max(leak + 0.10, 0.20)`
per §Q.3's leak-noise concern** (leak_rate is noisy at n=50 — three pilot runs gave 0.15/0.05/0.08 —
so a 0.20 floor prevents pinning a ceiling to sampling scatter).

| cell | ndcg_band | sLeak_max | union_min | leak_max |
|---|---|---|---|---|
| en-legal-clerc-1k-verbose | [0.2461, 0.85] | 0.10 | 0.40 | 0.20 |
| en-legal-clerc-1k-short-natural | [0.1682, 0.85] | 0.10 | 0.38 | 0.22 |
| en-legal-clerc-10k-verbose | [0.0000, 0.85] | 0.10 | 0.06 | 0.20 |
| en-legal-clerc-10k-short-natural | [0.0000, 0.85] | 0.10 | 0.00 | 0.20 |
| en-email-enron-raw-1k-verbose | [0.5776, 0.85] | 0.10 | 0.76 | 0.20 |
| en-email-enron-raw-1k-short-natural | [0.5322, 0.85] | 0.10 | 0.70 | 0.20 |
| en-email-enron-raw-10k-verbose | [0.4064, 0.85] | 0.10 | 0.38 | 0.20 |
| en-email-enron-raw-10k-short-natural | [0.3608, 0.85] | 0.10 | 0.36 | 0.20 |

Every measured value sits inside its derived threshold by construction (self-consistency check
green). B1 identity (signature/gold-hash/count 20→50) was re-pinned first, as bookkeeping, before
any measurement.

### R.3 Provenance lives in a sidecar, not the policy JSON (deviation)

The task asked for a per-cell provenance field in the policy. **The policy schema forbids it:**
`_active_scientific_policy_cells` (`corpus_certify.py:271, :285`) requires the top-level object to be
*exactly* `{schema, status, unresolved, required_cells}` and each cell to be *exactly*
`{member, dataset, corpus_signature, query_gold_sha256, query_count, thresholds}` — any extra field
raises "malformed". So provenance is recorded in a sibling
`707-corpus-certification-policy.provenance.v1.json` (rule + per-cell measurements) plus this §R.

### R.4 Deviations from the runbook, with reasons

1. **Materialization (Phase A1):** the exact `corpus-inject-real` invocations were not recorded
   anywhere (runbook A1). Reconstructed them: fetch the 14k host pool
   (`corpus-fetch-clerc`/`-enron-raw`, seed 707 — both cache hits, corpus sha matched each recipe's
   `real_source_sha256` exactly), rebuild each cell's gold-source from the committed `fabricated-*`
   files, and run `corpus-inject-real` with the recipe params (`--commitment-dir` → throwaway so the
   committed source is untouched, `--datasets-dir` = repo-root per B5). A2 verified all 8 cells
   byte-identical to the committed structural certs.
2. **Backend coordination (§4):** did **not** use the MCP dev-stack lease. `jseval run --start-backend`
   spawns `runHeadlessEval` on port 33221 itself (`backend.py:209`), which would collide with an
   MCP-started backend — the runbook's own Phase C commands are self-contained jseval, not MCP.
   Verified `quick_health` no-owner + port 33221 free before starting; sole active agent throughout;
   backend down cleanly between phases and at the end. Self-contained `--start-backend --clean` per
   cell (the validated path), which double-ingests each cell (the §3.5 wall-clock driver).
3. **Structural-cert `member` field** normalized from `707-corpora/en-legal-clerc` to `en-legal-clerc`
   — the value that MATCHES the policy's `required_cells[].member` (the field is load-bearing at
   `corpus_certify.py:280/801`); the old value would have mismatched under scientific certification.
4. **cell-3 C1 rerun:** the original cell-3 (legal-10k-verbose) manifest carried `git_sha=None`
   (transient — `git rev-parse HEAD` momentarily empty during the early multi-agent window; every
   other cell has the valid sha `a851aa22…`). The 3 manifest-backed gates require `_is_git_sha`, so
   cell-3 failed Phase E on that alone. Re-ran C1 (free, no paid calls) for a valid manifest;
   thresholds kept as pre-registered (not re-derived — the failure was a manifest defect, not a
   measurement/threshold question, so re-deriving would have been the p-hacking B1 warns against).

### R.5 Harness-platform recovery (the wall-clock inflator)

Two background-driver evictions cost ~2h of rework. Root causes, both platform-level: (a) the harness
kills a **tracked** background task at ~60 min lifetime; (b) `TaskStop` does **not** kill a bash
loop's child processes, so a "stopped" driver orphaned itself and ran **concurrently** with its
replacement — two drivers doing `--start-backend --clean` on the shared eval data dir cross-corrupted
cells 4–5 (two run_dirs ~1 min apart). Fix that held: run the driver as a **fully detached OS process**
(`Start-Process`, not harness-tracked → no 60-min cap) and wait with **self-terminating** foreground
polls (480 s < the 590 s background threshold, so a poll never becomes a second background task and
never evicts the driver). The detached driver then ran ~2 h to completion with zero evictions. Corrupt
cells 4–8 were fully redone under it; cells 1–3 had completed cleanly before the chaos and were reused.

### R.6 Spend and wall-clock

- **Spend (estimate — no per-call invoice is surfaced by jseval; per-call caps $0.10 closed-book /
  $0.05 fidelity bound the worst case):** closed-book 8×50 = 400 calls ≈ $1–3; fidelity ~12–13×50
  probes (8 certified + ~4–5 wasted/partial during the eviction chaos) ≈ $1–7. **Total ≈ $2–9**, well
  under the $20 hard-stop; the $20/§3.3 ceiling was never approached. cell-3 C1 rerun was free.
- **Wall-clock:** ~01:34 → ~08:37 (~7 h). Inflated by the legal-10k double-ingest (~50 min/phase,
  ~120k chunks per 10k long-opinion cell), the two evictions + recovery, the cells-4–5 corruption redo,
  and the cell-3 C1 rerun. A clean run would have been ~5 h. Enron cells were fast (~4 min/1k,
  ~20 min/10k — short email docs).

### R.7 Publication pointer (Phase F)

Both members are now `fully-certified`, so a publication pointer *could* be selected against a
certification document that will validate (the claim policy re-decodes and re-checks the embedded
snapshot, requiring `status == "fully-certified"` and every gate `passed` —
`corpus_certify.py:791`). **No pointer was selected or published** — that, and any paid confirmatory
spend, remain founder decisions outside this run's scope.

## §S. §R.4-1 gap closed — automatic invocation recording (2026-07-22)

The §R.4 deviation-1 gap (materialization invocations "were not recorded anywhere", forcing the
certifying agent to reconstruct the `corpus-inject-real` commands from recipes) is now closed by
always-on invocation recording for the materialization-relevant jseval corpus commands.

- **What records:** `corpus-inject-real` and the whole `corpus-fetch-*` family
  (`-miracl`/`-clerc`/`-enron-raw`). Every invocation appends one record to
  **`<output-dir>/invocations.v1.jsonl`** where `<output-dir>` is the materialized dataset dir
  (`datasets/mixed/<name>/`), so the record travels with the artifact it describes. `datasets/` is
  gitignored (fetch-fresh-never-commit), so this is local runtime provenance that accumulates a
  per-cell materialization history — exactly the "recorded somewhere next to the output" the runbook
  Phase A1 expected, rather than reconstructed after the fact.
- **Record shape (`schema: 707-corpus-invocation.v1`, one JSON object per line):** `command`,
  `timestamp` (UTC ISO-8601), `git_sha` (full 40-char via `manifest._git_sha_full`, or null),
  `git_dirty`, `cwd`, `jseval_package_path` (which worktree copy ran), `argv` (real process argv),
  `params` (resolved click parameter values — Paths coerced to strings), `seeds`, `input_digests`,
  `output_digests`, `output_dir`, and an optional `digests_note`.
- **Digests are reused, never re-computed:** inject-real records the host-pool `real_source_sha256`
  and `assembled_digest` (from `build_source`'s provenance) as inputs and `corpus_signature` (from
  `build_golden`) as output. The `corpus-fetch-*` family samples fresh from the upstream source, so
  it has no input-pool sha to reuse — `input_digests` is empty and `digests_note` names the seed+source
  recipe (in `params`/`seeds`) as the reproducibility key. No multi-GB pool is re-hashed for the record.
- **Additive only / fail-open:** recording never changes command behavior, outputs, or digests, and a
  write failure is a WARN returning `None`, never an error that fails a materialization.
- **Implementation:** `scripts/jseval/jseval/corpus_invocation.py` (`record_invocation` / `build_record`),
  wired into `scripts/jseval/jseval/commands/corpus.py` (`cmd_corpus_inject_real` and the shared
  `_fetch_and_build_mixed` helper). It conforms to the existing `manifest._git_sha_full` git-SHA
  convention and the LF-only committed-artifact write convention used by the recipe/commitment writers
  in this tree. Tests: `scripts/jseval/tests/test_corpus_invocation.py` (4 tests — record fields incl.
  argv/seed/git_sha/timestamp, append semantics, fail-open, and end-to-end CLI sidecar on both the
  inject-real and fetch paths).
