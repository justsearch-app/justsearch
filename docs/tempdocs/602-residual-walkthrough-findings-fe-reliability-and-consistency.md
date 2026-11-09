---
title: Residual 593-walkthrough findings — the un-homed FE reliability, consistency, capability & reversibility observations (the sweep after 597/598/599/600/601, RAG trust-calibration deliberately excluded)
status: "COMPLETE — all 602 items dispatched or done (2026-06-18). R11 CUSTODY CLOSED (§R11 CUSTODY CLOSURE): the un-executed 'verify in 550/575 or adopt here' action is resolved — R11 is genuinely homed in merged owners (agent has no watched-root-removal tool per 598-reopen B-2 + UI-only `remove-watched-root`/`apply-excludes`; the agent-mutation undo class is fixed by 543-fwd P1 `undoEffectFor` + 560 WS3 declared inverses; the count-vs-undo drift is 550 §B.2), so 602 does NOT adopt it. ── IMPLEMENTED & MERGED to main (2026-06-17, merge `26d07da15`; §AS-BUILT below). R4 (opt-in toast supersede + OverlayHost placement), R5 (Tasks tray resolves the job pathHash → file/folder name via the existing core.resolve-path-hash path, FE-only), R6 (search-trace degradation-reason wording + a new sibling `check-search-degradation-reason-codes` gate), R7 (prominent in-button copy flash), R8 (rail Library intent label) shipped with unit tests; R10 recorded as a product decision (OCR stays off). Static verification batch + live browser verification are the closing step. ── CONFIDENCE PASS DONE (2026-06-17, §CONFIDENCE below) — read-only probes (no impl) re-scoped two items: R6 SHRINKS (the user-tier degradation reasons are largely enum-backed by SearchReasonCode + the existing reason-codes.v1.json already mirrors them, so word+gate is clean; DROP the sparse-shortcut rename — it's diagnostic-tier + has eval/fixture blast radius) and R5 GROWS (the FE cannot join job→folder; needs a backend wire field — multi-module). R8 weakens (the glyph is already `folder-plus`). Overall remaining-work confidence 7/10; the main residual risk is live E2E verification needing a healthy stack (the shared one is 604-wedged + owned). ── USER-FACING DESIGN DONE (2026-06-17, live-inspected, §UX below) — read-only browser pass on the running UI refined two items: R4 is a PLACEMENT problem (a single nav toast already OCCLUDES the top-right Copy URL/bookmark controls), not only a stacking one; and the live 600 degradation banner gives R6 a concrete plain-language tone target + a 'defer to the banner, don't double-surface' rule. R7's 'no toast' disposition is positively reinforced (toasts occlude header controls). No scope grew. (The shared stack was in a live 604 'Reconnecting…' wedge that blocked the results render, so R6/R7's result-card surfaces are source-grounded, not live-rendered.) ── DESIGN THEORIZED (2026-06-17, §DESIGN below) — the residue does NOT share one design; it decomposes by reason-to-change into two instances of the established legibility-projection pattern (R6 trace-reason wording; R5 → 599 folder-identity), one small extension to the 559 messaging model (R4 per-classId supersede), one STALE finding (R7 — in-button confirmation already ships), one capability-legibility/product-default decision (R10 OCR), and one genuine one-off affordance fix (R8). No unified token-legibility framework is warranted (600 declined the same merge). ── DRAWN DOWN (2026-06-17, 593 second-pass regression sweep) — the catalog's items are now dispatched to owners: Tier-1 R1+R2 (monitoring liveness/SSE wedge) PROMOTED to its own doc 604; R3 (two renderers diverge — count half) → 597 REOPEN; R9 (Abilities render) → 605; R11 (Undo doesn't revert folder-remove) → 550/575 check (see §dispatch). The remaining medium/low residuals (R4–R8, R10) stay here as the thin custody catalog / observations-inbox candidates. The regression sweep CONFIRMED every Still-Present item is unchanged on `main`. See §SECOND-PASS DISPATCH. ── PRIOR: open — catalog only (problem statements; no design, no implementation)"
created: 2026-06-17
relates-to: [593, 595, 598, 599, 600]
author: agent
---

# 602 — Residual 593-walkthrough findings (the un-homed remainder)

> **Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence. **Scope of this doc (for now):** a CATALOG only — each item is a one/two-sentence problem
> statement with a severity and a `593` citation. **No design, no implementation detail.** Several
> clusters could each spin into their own focused doc later; this exists so the findings are not
> lost when 593's coverage map goes stale.

## Why this tempdoc was opened

The **593 UX-walkthrough** ("first-time user indexes `docs/`") produced ~30 findings and an explicit
coverage map. The large, named clusters have since been homed:

| 593 cluster | Owner |
|---|---|
| FE presentation truthfulness (chip facts / provisional state / operability) | 594 · 595 · 596 |
| Search "N results" count truthfulness | 597 |
| Backend rebuild/runtime reachability + durability (Area A, "the #1 blocker") | 598 |
| Degradation-cause legibility + self-monitoring blindness (Area B) | 600 |
| Folder indexing journey (per-folder progress / terminal "done" / add-time validation) | 599 |
| Forward-looking "available in ~Ns" progress/ETA signal | 601 |

A **review of what remained** (this doc's trigger) found a residual set with **no owner**. One coherent
residual cluster — **RAG trust-calibration (Area C)** — is **DELIBERATELY EXCLUDED here** and tracked
separately (per the directing instruction). **This doc catches everything else** so it has a home: it is
intentionally heterogeneous (a reliability cluster, scattered single-authority/legibility residuals, two
concrete bugs, a capability default, and an agent-reversibility item), grouped by kind and tiered by
severity. The point is custody, not a single thesis.

---

## Tier 1 — Monitoring/liveness-surface reliability (highest severity; one family)

The diagnostic surfaces are themselves the least reliable exactly when you need them — the FE's
live-state machine (poll/SSE reconnect) is stickier and more pessimistic than the backend truth.

- **R1 — System-Health request-lifecycle wedge.** After a worker respawn the System-Health surface
  stays "Reconnecting…" for ~2 minutes while the backend is healthy; the network shows ~110 API requests
  stuck `pending` (`/api/status`, `/api/inference/status`, …), an **in-place page reload does NOT
  recover it**, and it **clears only on navigating away and back**. 595 owns the *rendering* of a
  transition; the **request-lifecycle / live-connection re-establishment** is owned by no one. 593's own
  verdict: *"the dashboard you'd open during an incident is the one most likely to mislead you."*
  (593 ADDENDUM 3 §S1; baseline CONNECTION flapping.) **Highest-severity orphan in this doc.**
- **R2 — Connection "Reconnecting…" over-pessimistic even at idle.** Even with the API reporting
  `is_healthy:true`, the CONNECTION panel / status pill intermittently flips to "Reconnecting…" — an
  SSE/poll reconnect artifact where the FE connection display is more pessimistic than reality. Same
  family as R1 (a liveness-display mechanism), distinct from 595's settled-vs-provisional axis.
  (593 ADDENDUM 3 baseline.)

## Tier 2 — FE single-authority / messaging / legibility residuals (medium)

Each has an existing lineage but no active owner.

- **R3 — Two result renderers diverge.** The Chat-surface result card (filename + path + "Why this
  result?") and the dedicated Search-surface card (snippet, highlights, MD/JSON/Paths/Ask-AI chips)
  render the *same* results differently — a render single-authority gap. (593 §9.)
- **R4 — Stacking "Navigated to X" toasts.** Navigation raises persistent stacking toasts that pile up
  and overlap the top-right controls (e.g. Copy URL). Messaging-authority lineage (559 Authority III).
  (593 §1.)
- **R5 — Tasks widget exposes worker job HASHES, not filenames.** The indexing Tasks list shows
  `Indexing · default (3c575b)` short hex hashes rather than file names — internal-ID leakage
  (504 D6 lineage). (593 §4.)
- **R6 — "sparse-shortcut" pipeline trace is illegible / falsely reassuring.** The exposed trace says
  `Dense (vector) skipped (sparse-shortcut)`, which reads like an optimization; only source revealed it
  means `wantDense=false` (the query never requested vectors). The trace gives false reassurance.
  Trace-legibility lineage (549/553). NOTE: 598 fixes the *cause* (dense becomes default); the **trace
  wording** that misleads remains its own item. (593 §8a, §G.)
- **R7 — Result exports give no confirmation.** MD / Paths / JSON copy-to-clipboard succeed but show no
  toast/confirmation, so a user gets no signal the copy worked. Messaging-authority (559 III).
  (593 ADDENDUM 6.)
- **R8 — Top "+" rail icon reads as "new", not "manage indexed folders" (Library).** Icon semantics; the
  folder-indexing entry point is discoverable only by clicking (no hover tooltips on the rail).
  (593 §1. Rail-tooltip discoverability for *enabled* affordances was explicitly left OUT of 596 Move 3.)

## Tier 3 — Concrete bugs (medium; may just need fixing + an observations entry)

- **R9 — Agent "Abilities" view did not render on click.** A concrete, reproducible FE bug observed in
  the Agent tier. (593 ADDENDUM 2.)

## Tier 4 — Capability / extraction default (low; possibly intentional)

- **R10 — OCR off by default → image text is not searchable.** A PNG with clearly-rendered text returned
  0 results (`ocr.enabled:false`); the image is indexed and browsable as a *file* but its text is not
  searchable. For a "personal files" user, screenshots / scanned receipts won't be findable by content.
  Likely an intentional default — record the decision (and whether to surface it) rather than assume.
  (593 ADDENDUM 4.)

## Tier 5 — Agent-action reversibility (medium; LINEAGE EXISTS — verify or adopt)

- **R11 — "Undo all AI actions" does NOT revert the agent's folder-remove; folder-remove orphans docs.**
  A verified safety-net failure: after the agent's high-risk folder-remove, clicking the prominent
  **"Undo all AI actions"** returned **"Nothing to undo"**, and the removed folder's docs stayed
  **orphaned** in the index (still searchable) — the exact action the confirm-gate scaffolding exists for
  is not post-hoc reversible, and the "1 operation" counter is inconsistent with the Undo. 599 punts this
  to the **550/575** agent-action-lifecycle lineage. ACTION: confirm 550/575 genuinely tracks this
  specific verified failure; if not, this is its home. (593 ADDENDUM 6 final scorecard.)

---

## Explicitly OUT of this doc

- **RAG trust-calibration (593 Area C)** — off-topic sources labelled "100% RELEVANCE / HIGH CONFIDENCE";
  multi-turn self-contradiction from per-turn independent re-retrieval with no context carry-forward;
  Structured clean-JSON hiding the ungrounded caveat. **Tracked separately by direction; NOT here.**
- Anything already homed: 594/595/596/597 (presentation + count), 598 (Area A), 599 (indexing journey),
  600 (Area B), 601 (ETA). Residuals that are genuinely *consumers* of those (e.g. "switch to Indexing
  mode first" guidance → 595/596 per 598 R5) stay with their owners.

## Disposition note (for whoever picks this up)

This is a custody catalog, not a design. Natural next cuts, if any are pursued: **R1+R2** as one
"monitoring-surface liveness/recovery" doc (the only Tier-1 work); **R3–R8** as `observations.md` inbox
entries or small single-authority fixes; **R9** as a bug/observation; **R10** as a product-default
decision; **R11** as a check against 550/575. No item here should be assumed designed until it has its own
pass.

---

## SECOND-PASS DISPATCH (2026-06-17, 593 regression sweep)

> The 593 walkthrough was re-run against `main` (HEAD `cc293577b`) after 594–603 merged. The
> sweep confirmed **every Still-Present item below is unchanged** and dispatched the catalog to
> owners. This converts 602 from a pending catalog to a thin custody residue.

| Item | Sweep status | Disposition |
|---|---|---|
| **R1** — System-Health request-lifecycle / SSE wedge | Still-Present #8 (mechanism refined: SSE streams return 503, don't re-establish in place) | **→ 604** (new doc; highest-severity orphan promoted to its own pass) |
| **R2** — connection "Reconnecting…" over-pessimistic at idle | Still-Present #7 | **→ 604** (same SSE/poll-reconnect family); the settled-vs-provisional rendering stays 595 |
| **R3** — two result renderers diverge | Still-Present #5 (Chat-surface still window-as-count, 424 vs 405) | **count half → 597 REOPEN**; the broader render single-authority stays here / 570 |
| **R4** — stacking "Navigated to X" toasts | Still-Present #1 | residual here / observations inbox (559 Authority III lineage) |
| **R5** — Tasks widget exposes worker job hashes | Still-Present #3 | residual here / observations inbox (504 D6 lineage) |
| **R6** — "sparse-shortcut" trace illegible | Still-Present #4 | residual here (598 fixed the *cause*; the wording stays) |
| **R7** — exports give no confirmation toast | Still-Present #10 | residual here / observations inbox (559 III) |
| **R8** — top "+" rail icon reads as "new" | (icon semantics; rail tooltips now exist per ✅#1, but the glyph semantics unchanged) | residual here / observations inbox |
| **R9** — Abilities view render | now renders but shows raw i18n keys (NEW #6) | **→ 605** (agent-window reliability doc) |
| **R10** — OCR off by default | Still-Present #11 | residual here — product-default decision |
| **R11** — "Undo all AI actions" doesn't revert folder-remove | (folder-remove now PURGES per ✅#14; whether Undo reverts it remains the open half) | **→ 550/575 check** (agent-action-lifecycle); verify there or adopt here |

**Net:** 602's two highest-severity items (R1+R2) are now 604; the count/render and agent-render
items route to 597/605; R11 routes to 550/575. What remains in 602 is the **R4–R8/R10 medium-low
residue** — small single-authority fixes or observations-inbox entries, not a design thesis. 602
stays open only as custody for those until each is filed.

---

## DESIGN THEORIZATION (2026-06-17, source-verified against `main`)

> **What this part is.** The earlier catalog deliberately proposed nothing. This part is the
> long-term-design round for the R4–R8/R10 residue: each item was re-verified against `main`
> (file:line), and the design question — *what is the correct long-term structure, and does any
> existing structure already cover it?* — was answered per item. The headline conclusion is a
> **negative** one that the catalog's "Tier 2 single-authority residuals" framing obscured:
> **these residuals do not share one design.** Two are instances of a pattern the codebase has
> already proven twice and should reuse; one is a small extension to an existing model; one is
> already shipped (stale); one is a product decision; one is a genuine one-off. The disciplined
> move is to give each item exactly the structure its own reason-to-change requires — and to
> **refuse to manufacture a unified "user-facing-token legibility framework,"** which would be
> speculative abstraction (600 PART IX explicitly declined the same merge: *"mirrors the search
> side's reason-code contract **without merging the two vocabularies**"*; AHA — unify only what
> shares a reason to change).

### D.0 — The one pattern worth recognizing (not a new mechanism)

The codebase has converged, independently, on **one shape** for "an internal token must not reach a
user-facing surface raw": a **closed producer vocabulary → declared FE wording map → forward+backward
correspondence gate.** It is shipped in two places already:

- **Readiness/degradation causes** — `LifecycleReasonCode` (closed enum) → FE `CAUSE_ROWS` (wording)
  → `check-readiness-reason-codes.mjs` (gate, register `governance/readiness-reason-codes.v1.json`).
  This is 600 PART IX/X, merged. It makes *"user sees a raw `Degraded: <code>`"* unrepresentable —
  the Nielsen "no error codes in the user's face" invariant, enforced.
- **Result counts** — 597 modelled funnel cardinality as a **governed projection** of the canonical
  `SearchTrace`, so the headline and the facets cannot drift (553 projection-vs-fork + the
  `execution-surface` gate). Merged; its Chat-surface residue is 597's REOPEN, not 602's.

This pattern is **the answer to recognize, not a framework to build.** Where a *new* vocabulary
demonstrably leaks raw to the user, the correct design is to apply this same shape to *that* vocabulary
— never to fuse all vocabularies into one super-authority. Exactly one 602 residual (R6) is a fresh
instance of a leaking vocabulary; one (R5) is a missing-*input* rather than a missing-*wording*, with
a home that already exists (599). The rest are not legibility-projection problems at all.

### D.1 — R6: search-trace reason wording — extend the *partial* wording layer that already exists

**Verified shape (not "the trace is illegible" in general).** `searchTraceExplain.ts` already carries a
wording layer: `MODE_WORDING` words the effective mode, and `EXPANSION_SKIP_WORDING` words the
expansion skip reasons (`TIMEOUT`/`AI_UNAVAILABLE`/`FAILED`). But the **user-tier degradation summary**
(`userSummaryParts`, lines 150-158) interpolates the **raw backend reason code** verbatim:
`semantic ranking blocked (${d.vectorBlockedReason})`, `fell back from hybrid (${d.hybridFallbackReason})`,
`SPLADE skipped (${d.spladeSkipReason})`. *That* is the genuine "no raw codes to the user" leak, and it
is **broader than R6's `sparse-shortcut` example** — it surfaces every backend degradation reason
(`LEGACY_INDEX_NO_FINGERPRINT`, etc.) raw at the user altitude.

**R6's specific `sparse-shortcut` is a different, smaller case.** It is a *diagnostic*-tier stage reason
(`SearchTraceProjector.java:150`, rendered in the collapsed "Pipeline details" stage list), which the
577 altitude cut deliberately renders raw. And post-598 (dense is now the default) `sparse_shortcut` is
**rare** — it only fires on an explicit sparse-only request. So `sparse-shortcut`'s defect is *misleading
naming* (it reads like an optimization but means "the query never requested vectors"), addressed by a
self-describing rename at the producer — not by wording every diagnostic stage reason (that would fight
the 577 decision).

**Long-term design (scoped):** bring the **user-tier** trace reason vocabulary under the same closed-vocab
→ wording → correspondence-gate discipline 600 shipped — i.e. give the degradation reasons a declared FE
wording map (the `EXPANSION_SKIP_WORDING` sibling) and a forward+backward gate so a new backend reason
code without FE wording fails the build, mirroring `check-readiness-reason-codes.mjs`. This **extends the
wording layer that already exists**; it does not add a renderer or a record. The diagnostic stage reasons
stay raw by the 577 cut; `sparse-shortcut` gets a self-describing producer name. Boundary held: this is a
*third* closed vocabulary with its own gate, **not** merged into the readiness vocabulary.

### D.2 — R5: Tasks-widget folder identity — route to 599's shipped folder↔job linkage

**Verified shape.** `indexingJobsBridge.ts:70` `labelFor()` renders `Indexing · ${collection}
(${pathHash.slice(0,6)})`, with the comment *"disambiguates… without leaking paths."* Two findings: (a)
the "leaking paths" rationale is **misapplied** — in a single-user local-files product the user owns the
paths; there is no privacy boundary, and the user actively wants to know *which folder* is indexing; (b)
the friendly datum (folder name) is **genuinely not on the Tasks wire** — the job row carries only
`collection` + `pathHash`, so this is a missing-*input*, not a missing-*wording-map* (so it is **not** a
D.0 wording-gate instance).

**Long-term design = reuse, not new structure.** 599 (shipped) already established the **folder ⇄ job
linkage** (`ListFailedJobsByPathPrefix` + the per-folder count-by-prefix RPC + the folder-identity
model) and is the owner of folder-facing legibility. The Tasks widget is the **operator-view complement**
of that same identity: it should project the job's owning folder through 599's existing path↔job mapping,
not invent a parallel one. **Disposition: dispatch R5 to 599** (its folder-identity authority is the home;
the hash-label is the global-operator-view gap 599's per-folder work left). No new design in 602.

### D.3 — R4: recurring transient messages — one small extension to the 559 messaging model

**Verified shape.** 559 Authority III gave the product **one** message model (`emitEphemeralToast` →
`AdvisoryStore.emitEphemeral` → `AdvisoryToastHost`), collapsing the old second toast system. But
`emitEphemeral` (`AdvisoryStore.ts:390`) is **append-only**: every call mints a unique `local:${seq}`
record, and the host stacks them (5 s auto-dismiss, no cap, no coalescing). The `classId` already exists
(`core.navigation`) but is used only for telemetry grouping. So a recurring same-class transient
(navigation; and any future status-flip notice) **piles up and overlaps the top-right controls**.

**Long-term design (scoped extension, not a rewrite):** the messaging model needs **one missing contract
— per-`classId` supersede (singleton)**: a transient emitted with a `singleton`/supersede flag *replaces*
the live toast of the same `classId` rather than stacking beside it. This is the correct general answer
(navigation is not the only recurring transient), it lives entirely inside the existing single model
(extends `emitEphemeral` + the host's visible-list reconcile), and it is the smallest structure that
removes the pile-up by construction. **Disposition: a single-authority extension; keep custody in 602
(or a small 559-lineage follow-up).** Not a framework, not a new channel.

### D.4 — R7: export confirmation — STALE; already shipped (close, don't design)

**Verified shape.** `SearchSurface.ts:922-937` + `1283-1300`: the MD/JSON/Paths copy buttons **already
flash "Copied!" in-button for ~1.5 s** on success (`copyFlash`/`renderCopyBtn`). The 559 design here
*deliberately* chose in-button confirmation over a global toast ("no global toast primitive"). So the 593
"no confirmation" finding **does not match `main`** — and the regression-sweep "Still-Present #10" mark is
**incorrect for this surface** (an `interrogate-results` catch: the sweep's status was not source-checked
against the shipped flash). The only residual is *prominence* — the flash is brief/subtle and an operator
can miss it — which is a presentation-tune, **not** a missing-design. **Disposition: record the
correction; close R7.** If prominence is pursued, it is a tone/duration tweak within the existing
in-button affordance, explicitly **not** a new toast (which would re-fork what 559 collapsed).

### D.5 — R10: OCR off by default — a capability-legibility / product-default decision (not a framework)

**Verified shape.** `OcrProcessor.java` exists; `ocr.enabled:false` is the default, so a screenshot's
rendered text is **silently** unsearchable. Two distinct questions, neither of which is an FE-architecture
design: (1) **the product default** — for a "personal files" user whose corpus includes screenshots and
scanned receipts, is off-by-default right? (cost/perf/quality trade — a `/search-quality` + product call);
and (2) **legibility of the consequence** — an off-by-default capability that changes *what is findable*
should be an **explained absence**, not a silent one. That second half is the "explain the absence"
lineage 596 (unavailable-affordance reason) and 600 (degraded-cause wording) already embody: a zero-result
image-text search should be able to tell the user "image text isn't searched because OCR is off — here's
how to enable it," rather than reading as "the product can't find my file."

**Long-term design = a decision + a small reuse, not new structure.** Make the OCR-off state a *legible
capability state* via the existing 596/600 reason-surface, contingent on the product-default decision.
No new mechanism. **Disposition: record the decision question (observations / product owner); the
legibility half is a 596/600-lineage reuse if the default stays off.**

### D.6 — R8: rail "+" glyph — a genuine one-off affordance fix (no design structure warranted)

**Verified shape.** The rail "+" reads as "new", not "manage indexed folders"; rail tooltips now exist
(593 ✅#1) but the glyph semantics are unchanged. This is a single icon-glyph + accessible-label choice
inside the rail's existing affordance model. There is **no design class here** — manufacturing one would
be structure for a case the problem doesn't include. **Disposition: observations-inbox / small affordance
fix** (glyph + aria-label naming the folder-management intent), within the existing rail.

### D.7 — Net disposition after the design round

| Item | Class | Long-term home | New structure? |
|---|---|---|---|
| **R6** | leaking *user-tier* reason vocabulary | extend `searchTraceExplain` wording + a 600-style correspondence gate (3rd closed vocab, **not** merged); rename `sparse-shortcut` at the producer | a scoped wording-gate (reuses the proven shape) |
| **R5** | missing folder-identity *input* | **→ 599** (shipped folder⇄job linkage / folder-identity authority) | none — reuse |
| **R4** | recurring transient pile-up | one **per-`classId` supersede** contract on the single 559 message model | small extension, no new channel |
| **R7** | **STALE** — already shipped | close (in-button "Copied!" flash exists); prominence is a tune | none |
| **R10** | product default + capability legibility | product decision + 596/600 "explain the absence" reuse | none — decision + reuse |
| **R8** | one-off affordance glyph | observations / small rail-glyph + aria-label fix | none |

**The scope verdict.** Of six residuals, **one** (R6) warrants genuinely new (but scoped) structure — and
even that is a reuse of an already-proven gate shape against a vocabulary that demonstrably leaks. The
rest are: a route to an existing authority (R5→599), a small extension to an existing model (R4), a stale
non-finding (R7), a product decision (R10), and a one-off (R8). 602's value as a doc is now this
*decomposition* — it prevented the catalog's "single-authority residuals" framing from inviting a
speculative unified-legibility build that the real problem does not require.

---

## UX — USER-FACING / FRONTEND DESIGN (2026-06-17, live-inspected)

> **Method & honest limit.** Read-only browser pass on the *running* dev UI (another session owns the
> shared stack — no ingest, no folder/agent mutation, no takeover). Captured: Chat (the worded
> degradation banner), a rail-glyph zoom, the Search surface with a live nav toast, and a
> rapid-navigation sweep. **The stack was in a live 604 "Reconnecting…" connection wedge throughout** —
> which itself **blocked the search-results render** (search sat on "almost there…" indefinitely). So
> the result-card surfaces R6/R7 live on (the explain panel, the copy buttons) **could not be exercised
> live**; their UX design below is source-grounded (`searchTraceExplain.ts` / `SearchSurface.ts`) plus
> the **window-level banner precedent I *could* see**. Two items (R5 active-job, R10 image corpus) need
> data mutation I could not do on a shared stack — source-confirmed, not live-rendered. Per
> `interrogate-results`, every "live" claim below is tagged with whether it was reproduced or inferred.

### U.R4 — the toast doesn't only *stack*, it *occludes* the header controls (live ✓)

**Live, reproduced:** a **single** "Navigated to Search · Go back to Chat" toast sits directly over the
top-right **Copy URL + bookmark** control cluster — it occludes them while visible. The 5 s auto-dismiss
means the controls return, but during the window they are covered. (The multi-toast *pile-up* the catalog
describes was **not** reproduced live: the inter-action latency of a tooled session exceeds the 5 s toast
life, so successive toasts dismissed between navigations — the pile-up needs human-speed clicking. The
*mechanism* is still real: `emitEphemeral` has no `classId` coalescing, §D.3.)

**UX design — two requirements, not one.** (a) **Recurrence:** the per-`classId` supersede contract (D.3)
guarantees at most one live nav transient. (b) **Placement:** the transient overlay must **not occlude
persistent header affordances** — anchor it clear of the top-right control row (or have that row reserve a
lane), so Copy URL/bookmark are never covered. (c) **Question the action itself:** a 5 s auto-dismissing
toast is a fragile host for a navigation *undo* ("Go back") — the browser/`<` already does that. The right
altitude for nav feedback is likely a **passive, worded, coalesced breadcrumb**, not an actionable toast
competing with the header. So R4's correct UX = one-at-a-time, non-occluding, and reconsider whether nav
confirmation needs an action at all.

### U.R6 — the per-query explain line should read like the *window banner*, not embed raw codes (banner live ✓; explain line source-grounded)

**Live, reproduced:** the window-level degradation banner already speaks plain language + a remedy —
*"Reduced search capability. An optional capability is unavailable; results are still fully semantic. ·
Learned re-ranking (LambdaMART) is not configured · [Open Health]"*. That is the 600 wording authority
working at the **window altitude**.

**Source-grounded (could not render live — 604 wedge):** the **per-query** trace-explain USER line
(`searchTraceExplain.ts:150-158`) instead **interpolates the raw code**: `semantic ranking blocked
(${vectorBlockedReason})` / `(LEGACY_INDEX_NO_FINGERPRINT)`. Same product, two altitudes, **inconsistent
tone** — one plain-language, one raw-code.

**UX design.** The user-tier explain line should (1) match the **banner's plain-language tone** (a worded
reason, never the raw code — the D.1 wording map + gate), and (2) where its reason **duplicates the window
banner's cause** (e.g. legacy-index/reindex), **defer to the banner** rather than re-state the cause per
result — avoiding double-surfacing the same degradation at two altitudes. Diagnostic-tier stage reasons
stay raw in the collapsed "Pipeline details" disclosure (577 cut). Net: **no raw reason code at the
primary altitude; one cause stated once, at the right altitude.**

### U.R7 — keep in-button confirmation; do NOT add a toast (the live R4 finding *reinforces* this)

**Source-grounded:** the MD/JSON/Paths buttons flash "Copied!" in-button for ~1.5 s
(`SearchSurface.ts:1283-1300`); 559 deliberately chose in-button over a global toast.
**Live cross-finding:** since a transient toast **occludes the header controls** (U.R4), a copy-confirmation
*toast* would be actively worse UX. **Design:** keep the in-button flash; if 593's operator missed it, the
fix is **prominence within the button** (a clearer icon/colour change, marginally longer hold), **not** a
new channel. The 593 "no confirmation" finding is stale for this surface (the regression sweep's
"Still-Present #10" was not source-checked — an `interrogate-results` catch).

### U.R8 — the "+" glyph reads as "add NEW", not "manage my indexed folders" (live ✓)

**Live, reproduced (rail zoom):** the top rail glyph is a generic **box/page-with-a-plus**. For a
first-time user "+" connotes *create/new*, not *"add a folder to my library / manage what's indexed"*. Rail
tooltips now exist (593 ✅#1) but a tooltip is hover-only — the **always-visible glyph** is the primary
signal and it under-specifies the intent. **Design (one-off, no structure):** depict a **folder/library
with a plus** (not a generic box+), and give the control an accessible name that states the intent ("Add a
folder to index" / "Manage indexed folders"). Within the existing rail affordance model.

### U.R5 — source-confirmed; not live-observable on a shared stack

Could not trigger an indexing job (read-only; no ingest), so the hash label was not live-reproduced;
source is definitive (`indexingJobsBridge.ts:70` → `pathHash.slice(0,6)`). **UX unchanged from D.2:** the
task row must name the **folder**, reusing 599's path↔job linkage — e.g. *"Indexing · Documents/Projects —
412 files"*, never *"default (3c575b)"*. A hash is never the right user-facing token for "which folder is
this".

### U.R10 — source-confirmed; not live-observable without an image corpus

Could not add an image to the corpus (read-only). **UX unchanged from D.5:** a zero-result image-text
search should be an **explained absence** — *"Image text isn't searched (OCR is off). Enable it in
Settings."* — reusing the same plain-language empty-state/banner surface the 600 work established (the tone
I confirmed live on the degradation banner). Contingent on the product-default decision.

### U — Net UX verdict

The live pass **refined two items and reinforced one**, and **expanded no scope**:
- **R4** gained a **placement** requirement (don't occlude the header control cluster) on top of the
  coalescing requirement, and a question mark over whether nav feedback should be actionable at all.
- **R6** gained a concrete **tone target** (the live window banner) and a **"defer to the banner, don't
  double-surface"** rule for causes that overlap the window-level degradation.
- **R7's** "no new toast" disposition is **positively reinforced** by the live toast-occlusion finding.
- **R5 / R8 / R10** are unchanged (R8's glyph confirmed live; R5/R10 source-confirmed).

A cross-cutting live observation (out of 602's scope, already owned by **604**): the connection wedge
made search itself unreachable during inspection — a reminder that R6/R7 legibility only *matters* once
results render, so 604 is upstream of the 602 search-surface items in user-impact terms.

---

## CONFIDENCE — pre-implementation probe pass (2026-06-17, read-only; no feature code)

> **What this is.** A confidence-building round run *before* any implementation, to retire the
> surprises that would change a design's shape or scope mid-build. Six probes (P1–P6), all read-only
> (source reads + briefed Explore subagents + the earlier live pass). Each finding is tagged
> reproduced/source-confirmed. Two probes forced a re-scope; the rest confirmed the design.

### P1 — R6 trace-reason vocabulary (source-confirmed) — **scope SHRINKS, confidence UP**
- The three **user-tier** degradation fields (`vectorBlockedReason` / `hybridFallbackReason` /
  `spladeSkipReason`) are **largely enum-backed** by `SearchReasonCode`
  (`modules/worker-services/.../services/SearchReasonCode.java`, ~24 members: 8 embedding-compat + 5
  routing + 11 chunk-merge) plus a couple of literals (`"not-selected"`). The pure **literals**
  (`"sparse-shortcut"`, `"dense-only"`, `"splade-only"`, `"vector-blocked"`, `"empty-query"`,
  `"single-leg-or-no-retrieval"`, …) are **diagnostic-tier stage reasons** in `SearchTraceProjector.java`,
  **not** the user-tier degradation fields — which confirms the §D.1 boundary (word the user tier;
  leave the diagnostic stage list raw per the 577 cut).
- The existing **`SSOT/catalogs/reason-codes.v1.json`** (13 categories / 57 codes) already carries
  `embedding` + `embedding_compat` categories that **mirror the `SearchReasonCode` members** — so R6
  can likely **register the user-tier reasons in the existing catalog**, not mint a brand-new one.
- **Gate is cloneable:** both the `stage-completeness` enforcer
  (`scripts/governance/gates/stage-completeness/enforcer.mjs`, which already cross-checks FE
  `STAGE_LABELS` ↔ Java `StageId`) and `scripts/ci/check-readiness-reason-codes.mjs` (forward+backward
  correspondence + curated allow-lists) are direct precedents. The one complication: the wording gate
  must read **both** an enum *and* a few literals as its producer domain.

### P2 — `sparse-shortcut` rename blast radius (source-confirmed) — **DROP the rename**
Referencers: the Java producer, **3 pinned approval snapshots** (`SSOT/schemas/search-decisions/
sparse-shortcut*.v1.json`) + `SearchPlannerApprovalCorpusTest`, FE trace tests, **and jseval eval
stratification** (`scripts/jseval/.../projections/stratified_metrics.py` buckets nDCG by
`decision_kind="sparse_shortcut"`). Renaming is a contract + eval change. Since `sparse-shortcut` is
diagnostic-tier and rare post-598, the rename is **low-value, higher-risk** — **removed from R6's
core**. If its diagnostic label is ever judged misleading, the contract-safe option is an FE-only
diagnostic label map (optional, deferred; the 577 cut keeps diagnostic reasons raw by default).

### P3 — R5 job→folder feasibility (source-confirmed via subagent + wire reads) — **scope GROWS**
**The FE cannot resolve job → folder name today.** A job's `pathHash` is a **per-file** hash
(`IndexingJobView` carries only `pathHash` + `collection` + state); a root's `pathHash` is a
**per-root** hash; SHA is not prefix-preserving and **raw paths never cross the wire (ADR-0028)**; the
gRPC chain is **ROOT→JOBS only** (`CountJobsByPathPrefix` / `ListFailedJobsByPathPrefix`), with no
JOB→ROOT direction. So **R5 requires a backend wire change** — add a `rootPathHash` to
`IndexingJobView` (or a resolve endpoint), then the FE resolves it to a display path via 599's existing
lazy `core.resolve-path-hash` operation. **R5 is multi-module (worker → proto → app-api wire → schema
regen → FE), not a trivial 599 FE-join.** The §D.2 disposition ("route to 599") stands as the *owner*,
but the *cost* is corrected upward.

### P4 — R4 supersede safety + placement (source-confirmed + live) — **mechanism clarified**
- **Supersede must be opt-in.** Production `classId`s are disjoint (`core.navigation`,
  `core.verdict.settled`, `operation.completed`) **but most toasts use the shared default
  `core.ephemeral`** — so an automatic same-`classId` collapse would wrongly merge unrelated default
  toasts. The safe design is a **per-emit `singleton`/supersede flag** (navigation opts in), not
  classId-automatic coalescing.
- **Placement is a spatial overlap** (live-reproduced): the toast sits in the OverlayHost top-right
  slot and overlaps the app-bar control row (Copy URL/bookmark). The fix is a **spatial offset within
  that slot** — owned by 559 Authority I / the layout-purity gate. *Residual:* the exact slot geometry
  needs reading at implementation time (the OverlayHost slot is defined in the chrome layer).

### P5 — gate + live-verification map — **known surface; one real residual**
- Touching `searchTraceExplain.ts` / `AdvisoryStore`+`AdvisoryToastHost` / rail / `IndexingJobView`
  trips the usual `modules/ui-web/src/**` gate suite (presentation/ambient purity, style-literal &
  atom ratchets, a11y/controls-a11y for new names, message-single-model + transient-arbitration for
  R4, layout-purity for placement) plus — for R5 — the wire/`contract-projection`/`wire-type-single-
  authority` + `updateSchemas` regen on the proto change. R6's new wording gate is **kernel-clone
  work** (register + enforcer, modeled on stage-completeness).
- **Live E2E verification is the main residual risk.** R6/R7 only render once search returns results,
  and the only reachable stack was **604-wedged and owned by another session**. A clean verification
  needs a **healthy, coordinated stack** (own worktree stack or the shared one healthy) — currently
  unavailable, so end-to-end confidence is capped until then.

### P6 — R7 / R8 / R10 quick confirmations
- **R7 (source-confirmed):** the in-button "Copied!" flash is the **sole** confirmation path; stale
  finding holds; only a prominence tune is open (live-verify deferred to a healthy stack).
- **R8 (source-confirmed) — correction:** the Library rail glyph is **already `folder-plus`**
  (`utils/surfaceIcons.ts:12`), not a generic box+. So R8 is a **minor label / small-size-legibility**
  tune (or near-non-issue), not a wrong-glyph fix — weaker than §U.R8 assumed.
- **R10 (source-confirmed):** `INDEX_OCR_ENABLED("index.ocr.enabled")` is nullable→off; the legibility
  surface can reuse the existing `files/ocr_limits_exceeded` reason code + an empty-state. Stays a
  **product decision**, not an implementation-confidence item.

### Confidence ratings (0–10; remaining work)

| Item | Confidence | Why not higher |
|---|---|---|
| **R6** trace-reason wording + gate | **7.5** | Clean & contract-safe after dropping the rename; gate is a kernel-clone (new infra) + live-verify pending a healthy stack |
| **R5** name the folder in Tasks | **6** | Path is clear but multi-module (proto + wire + schema regen + FE lazy resolve); larger than the catalog implied |
| **R4** opt-in supersede + placement | **7** | Mechanism is safe (opt-in flag); placement needs exact OverlayHost slot geometry at impl |
| **R7** confirmation prominence | **8** | Stale/trivial; only live prominence-verify pending |
| **R8** rail affordance label | **7** | Low value, low risk; glyph already `folder-plus` — arguably near-non-issue |
| **R10** OCR default + legibility | **6** | Not an impl-confidence item — needs a product decision first; legibility half is a clean reuse |
| **Overall remaining 602 work** | **7/10** | Designs sound and scope-corrected; residual risks: R5 multi-module, R6 gate-as-new-infra, and **live E2E verification needs a healthy stack** (604-wedged/owned today) |

---

## INTERFERENCE — cross-tempdoc check (2026-06-17, ±20 / recent / worktrees)

> Scope: tempdocs 582–622 modified within ~5h, plus any live worktree named in that range. Goal:
> would any concurrent/adjacent work collide *long-term* with the remaining 602 implementation
> (R4–R8/R10)?

**No active code contention right now.** `git worktree list` shows **only `main`**; the three
in-range `.claude/worktrees/` dirs (`587-…`, `591-deps`, `598-r4`) are **stale leftovers** (no `.git`;
newest `598-r4` is ~7h old). The recent activity (593/595/597/598/600/603/604/605 all touched 20:05–
20:49) is **uncommitted *tempdoc-only* edits** in main (the 593-cluster dispatch) — **no code files
modified**, so nothing is being coded against my files at this moment.

**Long-term file/authority overlaps (when the remaining work lands), ranked:**

1. **600 ↔ R6 — reason-code wording (LOW-MED, coordinatable).** R6 extends
   `SSOT/catalogs/reason-codes.v1.json` (the *search* categories — `embedding`/`embedding_compat`
   already exist there) and **clones** `check-readiness-reason-codes.mjs` for the search-trace
   user-tier reasons. 600 owns the *readiness* vocabulary, but its catalog closure (PART IX) is
   **merged-stable** and its REOPEN scope is `readinessNotice.ts` banner copy + the CelEvaluator/RRD
   backend — **not** the catalog and **not** `searchTraceExplain.ts`. So: build R6's gate as a
   **sibling** (do not edit the readiness gate), add a **separate search category** (600 itself
   mandated "without merging the two vocabularies"). Shared file is the merged catalog only → low
   textual-conflict risk.
2. **597 ↔ R6 / R3 (LOW-MED, convergence).** 597's reopen (R-1) is the **Chat-surface Search tab**
   consuming `matchCount` (UnifiedChatView) — a different file from R6's `searchTraceExplain.ts`. But
   597 R-1 **explicitly wants to ride 602 R3** (render single-authority), and R3 was dispatched to 597.
   So R3 + R6 + 597 R-1 all concern the **search-result-card legibility** and should be **sequenced
   together** if a render-single-authority pass happens. Conceptual convergence, low file conflict.
3. **599 ↔ R5 (FAVORABLE dependency).** R5's needed `rootPathHash` wire field + FE lazy resolve
   **reuses** 599's merged folder-identity model + the `core.resolve-path-hash` operation. No active
   599 worktree. Latent-only risk: 599's DEFERRED D1 (batch count)/B3 (completion toast) follow-ups
   could later co-touch `IndexingJobView`/`indexingJobsBridge` — none in flight now.
4. **598 ↔ R6 (FAVORABLE, merged).** 598 made `sparse-shortcut` rare (dense default) — the reason R6
   can drop the rename. Merged; `598-r4` worktree stale. No conflict.
5. **604 ↔ R6/R7 verification (FAVORABLE/indirect).** 604's SSE/connection wedge is exactly what
   **blocked** the live verification (§CONFIDENCE P5). A 604 fix *unblocks* my R6/R7 E2E verify — 604
   is upstream of my verification, not a code conflict.
6. **605 ↔ R4 (LOW).** 605 (agent window) uses `emitEphemeralToast`; R4 adds an **opt-in** supersede
   flag to the store — additive, and 605's work is run-state/i18n, not store internals.
7. **R4 / R7 / R8 / R10 — isolated.** No in-range neighbor is editing `AdvisoryStore`/`AdvisoryToastHost`/
   `OverlayHost` (R4), `SearchSurface` copy flash (R7), `surfaceIcons`/rail (R8), or OCR config (R10).

**Net:** nothing blocks the remaining 602 work. The only genuine coordination points are **(a)** R6's
reason gate/catalog as a *sibling* to 600's (separate vocab, merged-stable file) and **(b)** R6 + R3 +
597 R-1 converging on the search-result-card — sequence them as one render/legibility pass rather than
three bespoke patches. Recommended order unchanged: land 600's banner reopen + 597's chat-count first,
then R6 on the settled card/catalog; R5 after confirming no 599 wire follow-up is in flight; R4/R7/R8/
R10 anytime (isolated).

---

## AS-BUILT (2026-06-17, worktree `worktree-602-fe-residuals`, UNMERGED)

Implemented the R4–R8 code + R10 decision, matching the §DESIGN/§UX/§CONFIDENCE conclusions (the
confidence pass's scope corrections held: R5 stayed FE-only, R6 dropped the `sparse-shortcut` rename).

- **R7 — copy confirmation prominence.** `SearchSurface.ts` `.copy-btn.flashing` is now a success-family
  state (success colour + faint `--accent-success-16` fill + bold), token-only; no toast added (the live
  R4 finding confirmed toasts occlude header controls). No behavioural change — the in-button flash that
  already existed is just legible now.
- **R8 — rail Library intent label.** New `railAccessibleName(surfaceId, label)` + `RAIL_INTENT_HINT`
  map in `utils/surfaceIcons.ts` (sibling of `SURFACE_ICONS`); `Shell.ts` `renderButton` uses it so the
  icon-only Library control's title/aria-label reads "Manage indexed folders". Glyph unchanged
  (`folder-plus`).
- **R4 — opt-in transient supersede + placement.** `EphemeralToastSpec.supersede?` (`ephemeralToast.ts`);
  `AdvisoryStore.emitEphemeral` drops prior same-`classId` local records when set; `AdvisoryToastHost`
  prunes a departed local toast (animated, idempotent via an `exiting` guard) so the replacement doesn't
  stack; the navigation toast opts in (`Shell.ts`); the OverlayHost `top-right` slot now docks below the
  2.5rem topbar so no top-right overlay occludes Copy URL/bookmark. Unit tests added to
  `AdvisoryStore.test.ts` (supersede drops same-class; leaves other classes; no-op append) and
  `AdvisoryToastHost.test.ts` (prunes a superseded toast).
- **R6 — search-trace degradation-reason wording + sibling gate.** `DEGRADATION_REASON_WORDING` (14
  embedding/routing codes) in `searchTraceExplain.ts` words the three user-tier degradation lines
  (raw-code fallback retained); `searchTraceExplain.test.ts` extended (worded path + exhaustiveness pin).
  New gate `scripts/ci/check-search-degradation-reason-codes.mjs` + register
  `governance/search-degradation-reason-codes.v1.json` enforce `SearchReasonCode`↔wording correspondence
  (forward+backward; the 11 chunk-merge codes are `noWordingExempt`); the gate is a *sibling* of 600's
  readiness gate (vocabularies not merged) and is added to the CLAUDE.md pre-merge list. All three
  degradation fields draw from the single `SearchReasonCode` enum (`VectorEncoding.Failed` /
  `SpladeEncoding.Failed` both carry it). Gate runs green (25 enum codes, 14 worded, 11 exempt). The
  diagnostic-tier stage reasons (incl. `sparse-shortcut`) stay raw per the 577 cut.
- **R5 — Tasks tray file/folder names.** `indexingJobsBridge.ts` resolves a job's existing file
  `pathHash` via the existing `core.resolve-path-hash` resolver (`resolvePathLazy`), memoized, and
  relabels in place ("Indexing · parentFolder/fileName"); the short-hash label is the graceful fallback
  when the hash can't be resolved. FE-only — no wire/proto change. Bridge tests extended (resolved label,
  fallback, bare-filename).
- **R10 — OCR default.** Recorded as a product decision (keep off) in this doc + `docs/observations.md`;
  no code. The legibility half is deferred (needs a not-yet-existing "would have matched image text"
  signal).

**Verification status — COMPLETE (2026-06-17).** Static batch all green: FE typecheck clean, **3188 unit
tests pass** (incl. the new R4/R5/R6 tests), `gradlew build -x test` BUILD SUCCESSFUL, and 12 gates pass
(the new `check-search-degradation-reason-codes` + stage-completeness, transient-arbitration, ambient/
style/contrast/accent, presentation-purity, controls-a11y, layout-purity, message-single-model,
prose-tier-register). **Live browser verification** done against the worktree FE served on a Vite proxy
to the real backend (the dev stack was taken over with user approval; it serves `main`'s FE, so the
worktree FE was run on its own port proxied to the live backend): R8 = rail aria-label "Manage indexed
folders" (glyph unchanged); R7 = prominent green "✓ Copied!" flash; R4 = exactly one nav toast, docked
below the topbar (Copy URL/bookmark unobscured), prior toasts cross-fade out (supersede); R6 = worded
degradation lines with NO raw code (real shipped `userSummaryParts` over a blocked/fallback/splade-skip
trace), graceful raw fallback for an unmapped code; R5 = 374/399 in-flight job tasks rendered real
"folder/file" names (e.g. "business/claude opus 4.6 export.txt", "decisions/0001-three-process-
architecture.md"), short-hash only as a transient fallback for in-flight resolves. The doc record + R10
decision live here in `main`; the code **merged to `main` in `26d07da15`** (the "unmerged, per directive"
note above is stale — it predates the merge; the frontmatter `status` is authoritative).

---

## R11 CUSTODY CLOSURE (2026-06-18, source-verified against `main` HEAD `84f858a96`)

> **What this is.** The §SECOND-PASS DISPATCH routed R11 ("'Undo all AI actions' doesn't revert the
> agent's folder-remove") to a **"550/575 check"** with an explicit, never-executed ACTION:
> *"confirm 550/575 genuinely tracks this specific verified failure; if not, this is its home."* That
> was the one open custody thread 602 still owned. This section executes that verification (read-only,
> file:line-cited) and closes it. **Verdict: R11 is genuinely homed across three already-merged owners;
> 602 does NOT adopt it.**

**The 593 finding, restated precisely.** The agent removed a *watched root* (a library folder-config
entry — "watch-remove", **files intact on disk**, docs left orphaned), the rail showed **"1 operation"**
and offered **Undo all AI actions**, and clicking it produced **"Nothing to undo"** (593 §ADD6, lines
946–959). Two distinct defects: (a) the agent's destructive folder-remove was not post-hoc reversible;
(b) the "1 operation" counter was inconsistent with an empty undo set.

**Finding 1 — the agent cannot remove a watched folder on `main` (defect (a) is no longer reproducible).**
The watched-root removal op `core.remove-watched-root` and the bulk doc-purge `core.apply-excludes` are
both **`ExecutorTag.UI` only** — not on the agent tool surface
(`CoreOperationCatalog.java:674`, `:728`; UI-only since slice 421 on **2026-05-06**, i.e. *before* the
593 walkthrough). The only watched-root op the agent holds is the **additive** `addWatchedRoot`
(`:650`, `Set.of(UI, AGENT)`); the agent's full AGENT-tagged op set is
`{bulkReindex, pingBackend, cancelIndexingJob, retryIndexingJob, resolvePathHash, reindex,
addWatchedRoot, switchInferenceMode, triggerOfflineProcessing}` — **none removes a watched root**. This
is a *deliberate* policy, not an accident: the 598-reopen B-2 rationale (`:411–417`) drops
`ExecutorTag.AGENT` from destructive ops precisely so the executor "keeps it out of the agent surface …
not the audience". 593's own regression sweep corroborates: it could not retest the Undo because it
**"couldn't complete an agent op"** (593:1229). So the agent has no path to the exact action R11
describes. *(The agent's disk file-mutation tool `FileOperationsTool` is a different surface and is
itself undoable — see Finding 2 — and it leaves the watched-root config untouched, so it is not R11's
folder-remove.)*

**Finding 2 — agent backend mutations that DO run with undo support are now reversible (the R11 class
was fixed by 543-fwd P1 + 560 WS3).** R11's core inconsistency — "Undo all AI actions" ignoring agent
mutations that carry no symmetric FE inverse — is the **exact gap** the 543-fwd P1 `undoEffectFor`
bridge was built to close: *"these entries carry `inverse === null` (no FE inverse), so the
originator-scoped undos previously SKIPPED them — meaning 'Undo all AI actions' ignored the exact agent
mutations worth undoing. This bridges that gap"* (`substrates/effects/index.ts:674–699`), reversing them
via a backend compensating `undo-operation` Effect → `POST /api/undo/{id}` when the op ran with undo
support and captured an `executionId`. And operations that declare an inverse (e.g. add↔remove
watched-root, 560 WS3 — `CoreOperationCatalog.java:644–645`, `:668–669`) materialize a non-null
`invoke-operation` inverse (`effects.test.ts:100–116`), so they don't even need the compensation path.
The orphaned-docs half of R11 is independently fixed: **folder-remove now purges** (593 ✅#14;
`RemoveWatchedRootHandler` deletes the root's jobs/docs).

**Finding 3 — the "count vs offered-undo" inconsistency (defect (b)) is tracked by 550 §B.2.** The
"1 operation" counter disagreeing with an empty undo set is an instance of the *count-disagrees-with-the-
list* drift 550 §B.2 names explicitly (`docs/tempdocs/550-agent-action-lifecycle.md:412`), whose root
cause (the missing canonical action record) 550's Thesis I owns. 550's `ActionEvent` already carries
`originator user|agent|system` (§B.4, `:426`), the substrate that makes the agent-activity count and the
undo-offer derive from one record rather than drift.

**Disposition.** R11's three sub-parts are each homed in a *merged* owner: defect (a) → 598-reopen B-2
(agent has no destructive folder op) + the 593 ✅#14 purge fix; the undo-mechanism class → 543-fwd P1 +
560 WS3; defect (b) the count drift → 550 §B.2 / Thesis I. **None is 602's home.** The 593 observation
reflects a pre-fix, heavily-churned environment (593:922 caveat) and a scenario the current agent surface
no longer permits. **R11 custody action: CLOSED — confirmed homed, not adopted.** *(Caveat per
`audit-without-test`: Findings 1–3 are source-verified static claims; a live agent-action E2E retest is
gated on a healthy stack — currently 604-wedged — but the agent-surface absence (Finding 1) makes the
specific failure unreproducible by construction, so the static verdict is high-confidence.)*

**Net 602 status after this pass:** R4–R8 implemented + merged (`26d07da15`) + verified; R10 recorded as
a product decision (`docs/observations.md`); R11 custody closed (homed in 598/543-fwd/560/550). The one
remaining-work item is **R3** (render single-authority) — confidence-probed below, not yet implemented.

---

## R3 CONFIDENCE PROBE (2026-06-18, read-only + census; pre-implementation de-risking)

> **What this is.** R3 ("two result renderers diverge", 593 §9) is the only retained 602 item the
> DESIGN THEORIZATION round (D.0–D.7) skipped — dispatched "count half → 597; broader render
> single-authority **stays here / 570**", never designed. This is a pre-implementation confidence pass
> (no feature code): a renderer census + source/ownership/a11y/gate/sequencing probes to retire the
> surprises before any R3 work. Verified against `main` HEAD `84f858a96`.

### Findings per uncertainty

- **U3 — renderer census (definitive).** The search-result domain has **three** renderers, not two:
  (1) `SearchSurface.renderRow` (`views/SearchSurface.ts:1075`) and (2)
  `UnifiedChatView.renderRetrieveRow` (`views/UnifiedChatView.ts:3072`) — **both already consume the
  shared `projectResultView`** (`views/searchResultViewModel.ts:82`) and the governed shared
  `renderWhyDisclosure` + `renderFacetChips` (the run-renderers SRR authority,
  `governance/run-renderers.v1.json`); and (3) `SearchResultsRenderer`
  (`renderers/controls/SearchResultsRenderer.ts:67`, the `x-ui-renderer='search-results'` hint engine)
  which reads **raw `hit` fields, does NOT use `projectResultView`**, and is **ungoverned**. The
  grounding/citation surfaces (`renderSourceChips`, `CitationsPanel.renderSourceCard`, `SourcesPane`,
  `searchEvidence`) are a **deliberately separate domain** (565 `EvidenceItem`), **not** R3.
- **U4 — the divergence is real and current, not stale (`interrogate-results`).** Between the two
  governed rows, the genuine inconsistencies are exactly two, in **unconditional template code** on
  `main`: **path formatting** — `formatDisplayPath(hit.path)` (Search, `:1110` + the helper at `:37`)
  vs **raw `view.path`** (Chat, `:3083`); and **snippet highlighting** — `highlightTerms(view.snippet,
  query)` (Search, `:1113` + helper at `:54`) vs **none** (Chat, `:3085`). Because the divergence is
  static template (not runtime-conditional), the source read is the definitive verification tier here —
  unlike R7, whose "Still-Present" staleness came from the *code having changed*. (Live visual repro
  deferred: the only healthy stack is owned by another session — `callerIsOwner:false`, lease fresh —
  and driving it for marginal confirmation isn't worth the interference risk.)
- **U5 — a11y / gate blast radius is LOW.** The census **corrected the plan's assumption**: **both rows
  use `role="listitem"`** (not a listbox/`option` vs `listitem` mismatch). The interaction models differ
  *by design* (Search = selectable `div.row` + `aria-current` + `⋯` actions menu + contextmenu; Chat =
  `div.retrieve-row` wrapping one open-`button`, the div-not-button being a deliberate nested-interactive
  choice, `:3069`) and should **not** be force-merged (AHA). The minimal fix touches **inner content
  only** (path + snippet), so no `controls-a11y` change. `formatDisplayPath` + `highlightTerms` are
  **pure, self-contained functions** (no surface state) → trivially hoistable to the shared
  `searchResultViewModel.ts`. The only new style is a `<mark class="hl">` in the chat snippet, reusing
  the existing `--accent-highlight` token (as `SearchSurface` does at `:445`) → clears theme-token
  closure; small `style-literal`/contrast surface.
- **U1 / U2 — scope & ownership (resolved).** R3 is a **narrow consistency fix over the already-shared
  view-model** — NOT a shared-row-*component* extraction (containers differ by design) and NOT the 570
  grand redesign. **570** ("The Search Window", `status: open`) owns the grand "result-as-projection"
  vision — explicitly feasibility-disregarded, "major rewrites in scope", and would even **merge the
  result row with the 565 `EvidenceItem` projection** (570:318). R3 is the small down-payment 570 would
  later subsume; **keep R3 in 602**, do not pull 570's scope in (that is the speculative-abstraction trap
  the D.0 round already warned against).
- **U6 — sequencing.** 597's REOPEN **R-1** (the Chat-surface Search tab still shows window-as-count,
  Still-Present #5) **explicitly wants to land with R3** as one render/count pass over the chat retrieve
  tier (597:882-894). R6 is merged (independent). Recommend **R3 + 597 R-1 as one small pass**; the chat
  retrieve *row* (R3) and the chat *count headline* (R-1) are adjacent but distinct code.
- **U7 — live verification.** Healthy stack exists but is **owned** (lease fresh); an own-worktree stack
  or a coordinated handoff is the impl-time path. For this probe, static + census suffice.

### Minimal-fix shape (characterized, NOT implemented)

1. Hoist `formatDisplayPath` + `highlightTerms` from `SearchSurface.ts` into the shared
   `searchResultViewModel.ts` (or a sibling `resultRowPresenter.ts`); `SearchSurface` imports them
   (no behavior change — regression-safe).
2. `UnifiedChatView.renderRetrieveRow`: format path via `formatDisplayPath`, highlight snippet via
   `highlightTerms(view.snippet, query)` (query from `searchSnapshot.query`); add the `.hl` mark style
   (reuse `--accent-highlight`).
3. (Optional teeth) extend the run-renderers SRR authority to list the two presenter symbols so a
   future fork must reuse them.
4. Decide the **third fork** (`SearchResultsRenderer`): likely **note/defer** — it is the declared-surface
   hint engine with its own raw `ResultHit` shape; folding it onto `projectResultView` is a larger,
   separate step (or 570's).
5. Tests: a `UnifiedChatView` retrieve-row test asserting formatted path + `<mark>` highlight; SearchSurface
   unchanged. 597 R-1 count half rides along.

### Confidence rating — **8/10** for implementing R3

High because: the view-model is already single-authority, the two divergent helpers are pure/hoistable,
both rows share `role="listitem"` (inner-content-only change → minimal a11y/gate risk), the defect is
source-confirmed-current, and scope/ownership are clear (small 602 fix, not 570's rewrite). Residual
risk (−2): (a) live E2E verification needs a coordinated/own stack (the shared one is owned today);
(b) the 597 R-1 count-half coupling adds a little un-traced surface (the chat count headline path);
(c) the third-fork (`SearchResultsRenderer`) + governance-teeth decisions are minor open scope choices;
(d) the new `.hl` chat-snippet style must clear theme-token/contrast gates (low but nonzero).

---

## R3 AS-BUILT (2026-06-18, worktree `worktree-602-r3-render`)

Implemented R3 (result-row render single-authority) as the scoped consistency fix the confidence
probe + Plan-agent review bounded. **597 R-1 (the count half) landed independently while this was in
flight** (`merge 847ac64ba`, `matchCountLabel.ts` shared presenter), so the count is done; this pass is
the row's **path-format + snippet-highlight** half — the two divergences that remained.

- **New shared presenter** `components/searchResults/resultRowPresentation.ts` — the ONE authority for
  the row's `formatDisplayPath` (middle-ellipsis path), `highlightTerms` (query-term `<mark>`), and the
  `highlightStyles` `mark.hl` css fragment (the contrast-floored `--accent-highlight` pair). Sibling of
  597 R-1's `matchCountLabel.ts`; both helpers were moved verbatim out of `SearchSurface` (no behavior
  change there).
- **`SearchSurface.renderRow`** now imports the three symbols (deleted its local copies + the inline
  `.snippet mark.hl` rule). **`UnifiedChatView.renderRetrieveRow`** now renders `formatDisplayPath(view.path)`
  (raw path stays in the `title=` hover attr) and `highlightTerms(view.snippet, q)` (query threaded from
  `renderRetrieveTier`), and composes `highlightStyles`. The interaction-model + affordance differences
  (selectable row + actions menu vs single open-button) are left unchanged by design (AHA).
- **Gate teeth:** `governance/run-renderers.v1.json` `searchResultRendering` gains
  `resultRowPresentation.ts` as an `authoritySite` and `highlightTerms` as an `authoritySymbol`, so a
  third file importing the highlight render must register as a consumer (anti-fork). `check-run-renderers`
  green.
- **Design refinement vs the plan:** kept `formatDisplayPath` as a shared *function* (mirroring the
  just-merged `matchCountLabel.ts`) rather than adding a `displayPath` field to `projectResultView` —
  lower blast radius, consistent with the sibling precedent. The view-model is unchanged.

**Verification — COMPLETE.** FE typecheck clean; **full FE unit suite 3218/3218** (incl. a new
`resultRowPresentation.test.ts` — 8 cases pinning path middle-ellipsis + the `<mark>` highlight incl. the
empty-query no-mark edge — and a new `UnifiedChatView` retrieve-row test asserting the formatted path +
`mark.hl` + raw path in `title`); gates green: `check-run-renderers`, presentation-purity,
theme-token-closure, controls-a11y, layout-purity, color-tokens, style-literal-ratchet, atom-fork-ratchet.
**Real-UI browser validation (required, done):** the worktree FE served on a separate Vite port proxied
to the live backend; query `walkthrough residual` run on BOTH surfaces. The dedicated Search surface and
the Chat retrieve tier now render the SAME result identically — middle-ellipsis path
(`f:\justsearch\docs\tempdo…\602-residual-walkthrough-findings-fe-reliability-and-consistency.md`) and
highlighted `Residual`/`walkthrough` terms — where before the retrieve tier showed the raw path with no
highlighting.

**OUT of scope (logged, not fixed):** `renderers/controls/SearchResultsRenderer.ts` — the
`x-ui-renderer='search-results'` declared-surface renderer is a THIRD result renderer with its own raw
`ResultHit` shape and no query in scope; folding it onto the shared projection/presenter is a separate,
larger step (or 570's grand "result-as-projection"). Logged to `docs/observations.md`. **R3 CLOSED;
602 fully complete.**
