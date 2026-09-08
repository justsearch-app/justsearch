---
title: Slice Execution Methodology
type: reference
status: stable
description: "Bidirectional spec/critical-analysis pass discipline for multi-phase tempdoc slices, validated empirically across slice 430's Phases 6-10 (5 consecutive zero-finding outcomes)."
---

# Slice Execution Methodology

A multi-phase tempdoc slice (e.g., 1.1.a / 1.1.d / 1.2 / 1.3 in the 426
slice plan) is large enough that a single agent typically can't hold all
of it in working context simultaneously. The cost of skipping discipline
is silent shipping bugs and rework cycles that compound across phases.

This document distills the patterns that drove **slice 430's Phases 6-10
to five consecutive zero-finding post-implementation outcomes**. The
patterns are not specific to HealthEvent — they generalize to any
multi-phase slice that ships substrate code with downstream consumers
(future Resource instances, future Operations, future surfaces).

Use this when:

- Picking up a tempdoc-driven slice with a multi-phase implementation plan.
- Reviewing a slice's phase boundary before declaring a phase complete.
- Auditing a slice's closure claim before merge.

Don't use this for:

- Simple bug fixes — the discipline is overkill.
- Single-file feature adds with one obvious test.
- Refactors that don't change behavior.

## The bidirectional pass pattern

Every phase of a multi-phase slice should be bracketed by two passes:

1. **Pre-implementation spec-tightening pass** — applied *before* writing
   any code in the next phase, against the just-learned patterns from the
   previous phase. Catches what the spec missed.
2. **Post-implementation critical-analysis pass** — applied *after* the
   phase's code is in, before declaring the phase complete. Catches what
   the test suite missed.

Empirical evidence (slice 430): when the pre-implementation pass is
rigorous enough, the post-implementation pass becomes a verification
rather than a remediation step. Phases 6-10 each landed with 0
actionable findings post-implementation.

## Pre-impl: primitive-selection pass

Before the spec-tightening pass (which verifies code claims against
source), a slice that introduces a new primitive *instance* must
first verify it's selecting the right *primitive*. This pass runs
**before the per-Category / per-primitive recipe is opened** —
because once the recipe opens, its constraints assume the primitive
choice is correct.

This pass exists because the substrate has caught three independent
primitive-selection conflations:

1. **Source-vs-shape** (slice 444 → 444a, the original lesson):
   "Log Resource" picked a producer (head Logback appender) for a
   workload need that needed a different shape (HISTORY for
   structured ingestion ledger). The fix was the typed
   `Resource.Category` axis. **Structurally prevented today** — the
   typed-Category vocabulary forces the shape question before the
   recipe opens.
2. **Truth-class** (slice 446 §A): LOG_TAIL Category models
   incidental infrastructure traces with the same primitive that
   models declared domain truth. Different consumer model, schema
   discipline, privacy class, self-observability constraint. Filed
   as `conflict-ledger.md` C-012; slice 446 deferred. **Not
   structurally prevented yet** — caught by spec-author / reviewer
   judgment, not by the type system.
3. **Vocabulary-creep** (audit
   `60-migration-history/10-truth-class-audit.md` F-2 / F-3): the
   chosen primitive's vocabulary technically covers the workload but
   stretches it semantically. `core.server-capabilities` fits STATE
   (a Resource with a value) but stretches "current value of one
   thing" by bundling four truth-classes. `core.ping-backend` fits
   Operation (an executable capability) but stretches it by being a
   no-side-effect read. **Not structurally prevented** — caught by
   per-instance review.

### Two questions the pass asks

For each new primitive instance the slice introduces:

**Question 1 — Truth-class**: who reads this and for what purpose?

- Domain consumers (end users, agents, plugins) reading declared
  domain truth → `Resource` (then pick a Category).
- Operator consumers debugging the system itself → candidate for
  `DiagnosticChannel` sibling primitive (per C-012); until that
  decision lands, document the asymmetry in the slice's §A.
- Executable capability invoked by humans / agents / plugins →
  `Operation`.
- Language-mediated workflow → `Prompt`.

If the answer crosses category lines (e.g., "operators AND end
users" — different audience, different privacy contract), the
primitive is being asked to swallow two truth-classes. Open a
`conflict-ledger.md` entry; pause primitive selection.

**Question 2 — Vocabulary-creep**: does the chosen primitive's
vocabulary already cover this workload's needs without the recipe
accumulating per-instance anti-pattern guards?

- Walk the chosen primitive's spec (e.g., for Resource: schema +
  Category + SubscriptionMode + history + recovery + freshness).
- For each workload need, identify the substrate slot it lands in.
- If a workload need has no substrate slot AND the recipe proposes
  to compensate ("apply this filter," "guard against recursion,"
  "document this privacy contract"), the substrate is leaking. The
  per-instance compensation will not scale across future instances
  of the same primitive.
- If multiple workload needs land in the same slot but mean
  different things (e.g., STATE Resource carrying both "current
  value" and "registry of other resources"), the slot is
  vocabulary-stretching.

A "yes" to either symptom = vocabulary-creep candidate. Document in
the slice's §A; consider whether the substrate needs a new shape
axis OR a separate primitive before proceeding.

### Reference cases

- **C-012 / slice 446 §A** (truth-class): LOG_TAIL Resource Category
  asymmetry table walking five axes (consumer / schema /
  self-observation / privacy / anti-pattern guards). Outcome: slice
  deferred pending substrate decision; corrected design sketch
  proposes `DiagnosticChannel` sibling primitive.
- **C-013 / audit F-2** (vocabulary-creep, mild):
  `core.server-capabilities` STATE Resource bundles four
  truth-classes (discovery metadata, server config, registry
  contents, change notification). LOW severity — pragmatic
  bootstrap-surface bundling. Filed as a vocabulary-creep
  observation, not a blocker. Trigger to revisit:
  plugin contributions to the envelope, or sub-field-granular reset
  needs.
- **C-010 (extended) / audit F-3** (vocabulary-creep, mild):
  `core.ping-backend` and `core.preview-excludes` are read
  Operations — fit the Operation primitive technically, stretch
  "executable capability" semantically. LOW severity — policy axes
  flatten correctly today. Trigger to revisit: 3a.3+ accumulating
  read-shaped Operations from search/browse/agent surfaces.

### When this pass is required

Run it on:

- **Any slice introducing a new primitive instance.** First-of-Category
  Resources (HISTORY, TABULAR, LOG_TAIL when those land), first-of-kind
  Operations (read-shaped, long-running with progress), first
  declared Prompt instances.
- **Any slice introducing a new primitive entirely** (proposing a
  fourth primitive class — currently C-012's DiagnosticChannel and
  C-014's Plugin-as-primitive both touch this).
- **Any spec author rewriting an existing primitive instance's
  Category / kind** (e.g., 444b's HISTORY → EVENT_STREAM
  reclassification was a vocabulary-creep correction).

Skip it for:

- Subsequent instances of an already-validated primitive Category
  (the second TIMESERIES Resource doesn't re-open the truth-class
  question; the substrate already validated TIMESERIES against
  GPU + queue-depth metrics).
- Bug fixes within an existing instance.
- Refactors that don't change primitive selection.

### Output

A `§A` (or numbered `§A.1` / `§A.2`) section in the slice tempdoc
recording:

1. The truth-class answer (who reads this, for what purpose).
2. The vocabulary-creep walk (which substrate slots the workload
   needs land in, where compensation would be required).
3. If either question raises a flag: severity assessment + ledger
   entry + recommended path (proceed with documented stretch / pause
   for substrate decision / re-target onto different primitive).

Reference shape: slice 446 §A.1 (asymmetry table) + §A.4 (rewrite-
vs-defer rationale) + §A.5 (resolution checklist for the
implementing agent).

### Relationship to the spec-tightening pass

The primitive-selection pass runs *before* the spec-tightening pass.
Spec-tightening assumes the primitive choice is correct and
verifies that spec claims about field paths / signatures / types
match the source. Primitive-selection asks the prior question: is
this the right primitive at all? If primitive-selection raises a
flag and the slice proceeds anyway, spec-tightening cannot rescue
the choice.

Both passes feed the same `§A`/`§B.<letter>` appendix discipline —
the primitive-selection pass produces `§A` content (substrate-level
rationale); spec-tightening produces `§B` content (code-level
verification).

## Pre-implementation spec-tightening pass

Before starting phase N, re-read the spec for phase N with two questions:

1. **What did phase N-1's implementation teach us about the substrate
   that the spec for phase N didn't yet know?**
2. **Where does the spec for phase N make a claim about the codebase
   that hasn't been verified verbatim against the source?**

Then walk the spec line-by-line. For each claim:

- **Field paths** — does the path in the spec match the field as it
  exists in the source today? (Slice 430 §B.R.1 caught a wrong state
  value because the spec used "DEGRADED" / "STARTING" while the source
  used "NOT_CONFIGURED" / "NOT_READY". Reading
  `StatusLifecycleHandler.computeComponent()` verbatim was the fix.)
- **Method signatures** — does the spec assume a public/package-private
  visibility that the source enforces? (§B.V.1 caught a
  package-private boundary blocker requiring a public
  `AgentSessionTerminationObserver` interface.)
- **Type semantics** — does the spec assume a Boolean where the source
  uses tri-state, or vice versa? (§B.S #1 caught a tri-state lookup
  conflating "unknown reason" with "healthy" — would have silently
  cleared a banner while the system was still unhealthy.)
- **Discriminator visibility** — does Jackson's
  `AsPropertyTypeDeserializer` consume the discriminator before the
  subtype's deserializer runs? Use `@JsonTypeInfo(visible = true)` if
  the discriminator must survive (slice 430 §B.P.2).
- **Speculative interfaces** — is the spec adding a bridge / SPI /
  observer with zero current callers? Drop it (§B.O →§B.T.1: the
  `WorkerHealthEvents` bridge was speculative; dropping it removed a
  whole phase's worth of work). YAGNI.

Output: a `§B.<letter>` entry in the slice tempdoc recording the
corrections, each with a primary-source citation. The citations matter
— if a reviewer asks "how do you know?", "I read it" is not enough;
"`StatusLifecycleHandler.java:827-846`" is.

The pre-impl pass is the densest investment of any phase. Slice 430
spent half-a-day to a day per pass and saved an estimated week of
post-impl rework by the end of the slice.

## Post-implementation critical-analysis pass

After phase N's code is in and tests are green, ask: **what would catch
what the test suite missed?** Then walk the diff with that lens.

Required checks:

- **Wrong-gate / wrong-flag mistakes.** Does the gate actually fire in
  the target scenario? `grep` the set-site, don't trust the symbol
  exists. (CLAUDE.md tempdoc 403 Tier B: `automationEnabled` vs
  `justsearch.eval.mode` — wrong gate, would have had zero effect.)
- **Audit conclusions that weren't independently verified.** Did you
  re-read the code the subagent's claim depends on? Subagent audits
  are hypotheses; the test is truth. (See §[Audits-need-tests](#audits-need-tests-not-just-passing-audits)
  below.)
- **Test precision** — does the assertion distinguish "passes for the
  right reason" from "passes for a wrong reason"? (§B.S #2: a test
  used the same `T0` for both the original emit and the re-emit, so it
  passed whether the implementation preserved `lastTransitionTime` or
  not. Advancing the test clock between emits exposed it.)
- **Tri-state lookups.** Wherever the code uses a Map to classify a
  state, distinguish "found and healthy" from "found and unhealthy"
  from "not found." Conflating "not found" with "healthy" silently
  clears banners on regressions.
- **Stale-flag short-circuits.** Wherever a fallback view is used,
  does the code treat unknown ≠ healthy? (Slice 430
  `WorkerSnapshotTap` §B.T.5: a fallback view reports
  `queueDbHealthy=true`; without the stale-flag short-circuit, a
  fallback would falsely *clear* a real `queue-db.unhealthy`
  condition.)
- **Asymmetric lifecycle.** Does every `start()` have a matching
  `stop()`? Did the new component register a shutdown hook? (Slice 430
  rev 3.4: `LocalApiServer.stop()` was previously asymmetric — only
  the new stream controller was stopped; the existing one leaked.)
- **WARN/log dedup.** New error-class WARN logs that fire on every
  tick will swamp the log. Dedup by `Set<MappingKey>` once per
  startup.
- **Wire-format vs implementation honesty.** When a substrate
  declaration claims a property (e.g., `HistoryPolicy.Mode.DURABLE` =
  "Persistent store") but the implementation is in-memory ring buffer,
  consumers reading the substrate see a contradiction. Either fix the
  declaration to match the implementation or implement what the
  declaration claims. Reference: slice 444b post-impl §B.B —
  reclassified `core.operation-history` from HISTORY × DURABLE to
  EVENT_STREAM × RING_BUFFER after the post-impl pass surfaced that
  DURABLE Mode required actual persistence.
- **Infrastructure-without-consumer.** A new listener API / helper
  class with no production callers is a Class B defect — the same
  pattern Trajectory B's sweep was hunting. After shipping a substrate
  helper (e.g., `SseEnvelopeWriter`, `ConfigStore.addListener`), grep
  for callers in `modules/*/src/main/java/` (excluding the file that
  defines the API). Zero production callers means either:
  (a) refactor production callers to use it (preferred), or
  (b) delete the helper (it's speculative). Reference cases: slice
  440 §B.B (ConfigStore.addListener was unused before the
  RuntimeContextConfigBridge wired it); slice 436 post-impl Fix A
  (SseEnvelopeWriter shipped with full lifecycle support but the 4
  retrofitted controllers each duplicated the boilerplate inline,
  leaving ~320 LOC of duplication while the writer sat unused).
- **Recipe-vs-substrate drift.** When a recipe doc (e.g.,
  `30-agent-workflows/01c-add-history-resource.md`) endorses a
  producer-attachment / validator-constraint that contradicts the
  substrate's javadoc or compact-constructor invariants, the recipe
  is wrong, not the substrate. Audit recipes after substrate changes;
  watch for internal contradictions between sections of a single
  recipe. Reference cases: slice 444b §B.B (01c endorsed "in-memory
  + DURABLE" contradicting `HistoryPolicy.java`'s javadoc);
  Trajectory B sweep (01e lines 104-105 contradicted lines 36-44
  about ONE_SHOT permission).

Output: a `§B.<letter>` entry recording each finding's severity and
fix commit. Severity scale (slice 430 convention):

- **Severity 1** — silent bug that would mislead users in production.
- **Severity 2** — test-precision gap that hides a Severity 1 bug.
- **Severity 3** — hygiene fix (WARN dedup, asymmetric lifecycle, dead
  code). Fix anyway.

If the critical-analysis pass produces 0 findings, that is itself
information — it means the pre-implementation pass moved the bugs
upstream. Record it.

## Layer-of-discharge check for substrate commitments (Pass-6)

Specific to slices that ship substrate machinery for cross-cutting
commitments (runtime-continuous negotiation, hot-reload signaling,
catalog evolution, plugin lifecycle, capability handshake extension).
Sibling of the primitive-selection family's Pass-4 (concern-bundling,
C-016) and Pass-5 (extension-suffices, C-017): same pre-impl moment,
same kind of "did I pick the right shape before writing code"
question.

**The check.** For substrate commitments that admit mid-session
evolution, ask: *at what architectural layer is the commitment
discharged?* Resource layer (typed channel on an existing Resource)
is the default. Transport layer (per-frame fields, per-stream
listeners, per-envelope tags) is admissible only when the
Resource-layer expression provably fails — not as an aesthetic
preference.

Three production-validated channel shapes exist at the Resource
layer; select from this catalog or justify a transport-layer
mechanism:

- **Capability-registration** with stable per-capability `id` +
  `type` discriminator (LSP `client/registerCapability` /
  `unregisterCapability`). Best when individual artifacts are
  added/removed independently and consumers want per-artifact
  reaction.
- **Delta-stream** with per-resource add / remove / modify (Envoy
  delta xDS). Best when bulk membership of a set evolves and
  per-entry registration would be noisy.
- **Typed-event subscription** with `type` as primary discriminator
  (CloudEvents). Best when the channel carries heterogeneous event
  families and consumers filter by interest.

A single substrate commitment may compose shapes (e.g., capability-
registration for plugin contributions + delta-stream for first-party
catalog membership). The catalog is descriptive, not prescriptive —
it names the design space so the choice is explicit, not implicit.

**When to run.** Before phase 1 of any slice that proposes a new
event-shape, listener API, or per-frame metadata field on substrate
infrastructure. The check belongs in `§A` pre-impl alongside Pass-4
/ Pass-5; if Pass-6 fails, the §A pass produces a redesign
recommendation and the slice's later phases consume the corrected
design.

**Reference case.** Slice 3a-1-8e (rewrite, 2026-05-07). The
original framing discharged "runtime-continuous negotiation" at the
transport layer (per-envelope `contractVersion` tag). Slice 3a-1-8
Phase 5a shipped the mechanism with the mandatory consumer deferred
to a follow-up — the textbook substrate-without-consumer pattern at
the transport layer. Pass-6 would have caught it pre-author: the
capabilities handshake stream
(`/infra/capabilities/stream` via `CapabilitiesChangeRegistry`) is
already a multi-frame Resource, so the Resource-layer expression of
runtime-continuous negotiation already exists, and the transport-
layer mechanism is therefore not admissible without justification.
The rewrite relocates the commitment to the Resource layer using a
hybrid of capability-registration (plugin contributions) and
delta-stream (catalog membership).

**Anti-patterns** (don't justify transport-layer with these):

- "Per-frame tagging is more granular." (Granularity is rarely the
  question; a Resource-layer change channel that fires per-mutation
  is already per-event-granular.)
- "It's defense-in-depth." (Defense-in-depth at two layers means
  two channels to teach, two places to debug, two paths for
  inconsistency. The substrate's capability-vs-mandate discipline
  frowns on speculative mandates without workload evidence.)
- "It's in case a frame in flight crosses a contract bump." (This
  scenario is rare; verify it occurs in the actual workload before
  shipping mechanism for it.)

**Output.** A `§A` entry recording the layer choice with citation:
"chose Resource-layer capability-registration shape (LSP precedent);
considered transport-layer per-frame tag (rejected because Resource-
layer expression exists at `<file:line>`)." Subsequent phases of
the slice consume the recorded decision; revisiting it is a §B
finding, not a free-form pivot.

## Phantom-ID source-verbatim verification

Specific to Resource-instance slices and any slice that declares a
catalog of producible IDs.

For each declared emit ID:

```bash
git grep -nE '"<id>"' modules/<producing-module>/src/main/java
```

If no producer exists, the ID is one of:

1. **Phantom** — no code path emits it; the spec is wrong, not the
   code. Mark in the catalog as `phantom: true` with a forward-compat
   note, or remove the ID entirely.
2. **Forward-compat** — a future producer will exist; mark explicitly,
   not implicitly.
3. **FE-only** — produced by frontend derivation, not the backend.
   Mark explicitly.

The defect class: a slice's headline ("23 backend + 4 FE-only")
implies a fact the code doesn't yet support. Slice 430 §B.AF caught
3 phantom IDs (`ai.not-configured`, `embedding.not-configured`,
`schema.rebuilding`; the last was retired outright on 2026-09-07,
tempdoc 948) by reading the producing code paths verbatim:
neither backend nor FE produced them, and the FE's defensive checks
were dead code. The headline shifted from "23 backend + 4 FE-only" to
"24 fire-able + 3 phantom (forward-compat)" — a different claim about
the system.

Run this probe **before phase 1 of the slice**, not after the slice
is "complete." If the catalog is wrong upfront, every phase that
references it propagates the error.

## Declaration ≠ runtime — anchor on consumers, not declarers

The phantom-ID check generalizes. For *any* load-bearing claim of "X
exists" or "X works" or "X is wired," produce **both** the declaration
site **and** the runtime consumer site. If the runtime consumer can't
be found via grep within ~5 minutes, the claim is **unverified** —
confidence drops, not rises.

This matters because declarations are cheap and checking them is the
default thing grep does. A typical investigation:

1. Grep for the declaration → finds it.
2. Conclude "X exists, therefore X works."
3. Confidence rises; substrate marked verified.
4. Implementation discovers X *doesn't* work because the declaration's
   runtime consumer either doesn't exist, lives behind an unverified
   module boundary, or is gated by a separate list that has drifted.

The defect is reading the *intent* of a declaration as the
*implementation*. The fix is requiring both endpoints of the wiring
to be source-cited before the claim counts as verified.

**Concrete probe shapes:**

- "Catalog X declares metric Y as archived" — find the **archiver**
  that consumes X's archive declarations at runtime. If no archiver
  reads X, "archived" is a comment, not a fact.
- "Module M registers handler H" — find the **dispatcher** that
  routes events to H. Declaration without dispatch is dead code.
- "Resource R is in the catalog" — find the **renderer** for R's
  category, OR the **consumer** that iterates the catalog. Catalog
  presence without consumer is forward-compat scaffolding.
- "Boot code B fires on app start" — find the **import chain** from
  the entry point to B. If B is imported transitively through a
  conditional path (like a route-bypassed app shell entry), boot may not
  fire on every entry.
- "Metric M has supplier S" — find both the **supplier registration**
  AND the **export pipeline** that polls S. A supplier without a
  reader in the local process is silent.

**Recurring failure mode** (slice 3a.1.4b §B.J, three layers in one
slice):

1. Investigation grepped for `archivedTo(STANDARD)` declarations on
   `worker.documents.indexed.rate_per_sec`. Found it. Concluded "the
   data substrate exists." Raised confidence to ~85%.
2. After visual smoke surfaced the metric was 503ing,
   §B.K-resolution proposed adding the metric to
   `LEGACY_CURATED_METRICS` (Flavor A) — anchoring on **that** list
   as the load-bearing source. A drift-guard test correctly rejected
   the addition.
3. Flavor B (register `WorkerOpsMetricCatalog` with HeadlessApp)
   failed at compile time — the catalog lives in `worker-services`,
   which the head's module cannot import. The boundary is the gate
   that decides which declarations the head sees, and the
   investigation hadn't checked the boundary.

Each layer was a correct sub-system analysis. Each missed the
*runtime path* gating which declaration the consumer could see. The
discipline that closes this defect class:

> For every load-bearing claim of "X exists" or "X works," produce
> both the declaration site AND the runtime consumer site within a
> ~5-minute timebox. If the consumer can't be found, the claim is
> unverified.

This is the operational analog of the phantom-ID check (which
verifies producers for declared emit IDs). The phantom-ID check
catches one specific case; the consumer-anchoring discipline
generalizes to every "X is wired" claim a slice makes.

**Anti-patterns** (don't do these and call the claim verified):

- "The declaration uses an annotation that the runtime *should*
  consume." (Should is not does.)
- "Sister declarations work, so this one will too." (Boundary
  conditions differ. Verify each.)
- "The catalog comment says X is archived." (Comments aren't wiring.)
- "A subagent reported the substrate exists." (Subagents grep
  declarations cheaply; consumer-grep needs a different prompt
  shape.)

**Pre-impl integration**: when the pre-impl spec-tightening pass
(§above) walks each spec claim, declaration-anchored claims should
**double**: declaration site + consumer site. The §B.<letter> entry
records both citations.

**Post-impl integration**: when the post-impl critical-analysis pass
walks the diff, every "X is wired" assertion the diff makes (e.g.,
"this catalog is registered with the head") needs a runtime probe.
Compile + unit tests verify the path the diff exercises; consumer
verification verifies the path the diff *claims* to exercise.

The substrate-level fix for this defect class is the catalog/registry
pattern: when declaration ≡ wiring (the runtime derives the wiring
from the declaration), the gap closes by construction. See
tempdoc 427 (telemetry substrate) for the metric-catalog
case and tempdoc 425 (bootstrap substrate) /
tempdoc 426 (transport substrate) for sibling cases. Until
those substrates ship, the consumer-anchoring discipline is the
process compensation.

## Verify-by-eyes after audit-reports-clean

A corollary of "audits need tests": when an audit (subagent sweep,
critical-analysis pass) reports "no findings" / "all clean" /
"verified," the implementing agent must independently verify before
declaring the audit closed. The audit is a hypothesis; trusting its
"clean" result without source-verbatim verification is the same
audit-without-test failure mode in reverse — the audit reports
absence-of-defect, and that absence claim is itself unverified.

**Reference case** (slice 436 §B.B Fix E): the Trajectory B
`@JsonValue` sweep agent reported "no remaining defects" across all
schema baselines. The implementing agent trusted the report at ship
time without re-reading each baseline. A subsequent plan-mode audit
read all 6 schema files (`operation.v1.json`, `prompt.v1.json`,
`resource.v1.json`, `runtime-context.v1.json`, `health-event.v1.json`,
`operation-history-entry.v1.json`) and confirmed each declares
`OperationId`/`I18nKey` as `string` — the sweep's claim was correct.
But the verification gap (trust + ship vs trust-then-verify) is
itself a defect class: shipping on an unverified clean audit is
shipping on a hypothesis.

**Discipline**: when a sweep / audit reports no defects, the closing
step is to re-read each artifact the sweep claimed to have audited.
Only then mark the sweep closed. The cost is minutes; the value is
ruling out the verification-discipline lapse.

## Audits need tests, not just passing audits

CLAUDE.md says: "Audit-driven fixes need a runnable test, not just a
passing audit." This is a corollary of the bidirectional pass pattern.

A subagent audit ("X is the only blocker for Y") is a hypothesis. The
test is truth. Until a regression test exercising Y is green, the
audit is unverified.

**Reference case** (CLAUDE.md tempdoc 403 Tier C): a `LuceneLifecycleManager`
audit said `analyzerRegistry` was the only restart blocker; the partial
fix shipped, then a regression test revealed two more blockers (state
machine, `indexingCoordinator`). The audit was wrong; the test would
have caught it in minutes.

When you delegate research to a subagent and act on its conclusion:

1. Re-read the code the subagent's conclusion depends on.
2. Write a test that exercises the conclusion.
3. Only declare the conclusion verified when the test is green.

## Methodology arc (six steps)

The arc that emerged from slice 430's life span is:

1. **Pre-impl spec-tightening** (§B.T-style) — corrections derived
   from the previous phase's lessons.
2. **Implementation** — phase N code, ideally landing in 1-3 commits
   per natural sub-boundary.
3. **Outcome record** (§B.U-style) — what shipped, deviations from
   spec, deferred items, finding count.
4. **Post-completion analysis** (§B.AD-style) — purely conceptual
   re-evaluation: did the implementation realize the slice's stated
   goals?
5. **Meta-evaluation** (§B.AE-style) — am I confident enough to
   actually fix the §B.AD findings? Per-finding analysis-rigor +
   fix-feasibility table. Surfaces analysis whose own assumptions
   weren't verified.
6. **Source-verbatim verification** (§B.AF-style) — read the code the
   §B.AD findings depend on. Reclassify findings against the source.
7. **Cleanup** (§B.AG-style) — implement the verified
   recommendations; document the deferred ones.

Steps 4-7 are post-completion. They are valuable because the
implementation itself is the most rigorous spec pass for the paths it
exercises — but only for the paths it exercises. Steps 4-7 surface
what implementation didn't pressure-test.

This arc is **slice-execution methodology**, not framework
architecture. It lives here (rather than in any one feature's design
notes) because it generalizes across slices.

## Verification gate items

Slice tempdocs typically declare a "verification gate" — items the
slice claims to pass before merge. Two patterns matter:

- **Auto-verifiable items** (build green, unit tests green, schema
  generation idempotent) should be checked by the implementing agent.
- **Dev-stack-driven items** (live SSE, condition transitions on
  real triggers, FE consumer renders) require a running dev stack
  and interactive triggers. The autonomous-implementation pattern
  (§B.U.3 / §B.W.2 / §B.Y.3 / §B.AA.2 in slice 430) holds: trigger
  items belong to a user-driven smoke procedure, not the autonomous
  implementing agent.

Document the manual smoke procedure (per-item commands, expected
outputs) so it's a one-pass smoke for the user. Do not silently mark
the gate "passed" because unit tests are green.

### Live-stack walkthrough archival convention (added 2026-05-08)

Live-stack walkthroughs (Chrome MCP browser sessions, ad-hoc curl
sequences, dev-tools console probes) sometimes surface findings the
unit + Pass 8 tiers couldn't see. §X.12's HealthLitView-vs-HealthSurface
mismatch is the canonical example: discovered in a Chrome MCP rail-
click session, lost to the dev-tools console transcript by default.

**Convention**: when a live-stack walkthrough surfaces a finding that
informs a slice's closure narrative or a regression-guard requirement,
**convert the walkthrough into a Playwright spec** in
`modules/ui-web/e2e/`. The framework's existing test artifacts —
screenshots, traces, JSON results in `test-results/` — become the
durable archive.

This is option-of-Pass-5 (extension-suffices): use the existing
Playwright infrastructure rather than introduce a new transcript-
archival substrate. The walkthrough itself stays exploratory; its
product is not the transcript but the spec it generates.

**When the walkthrough is not yet spec-shaped** (e.g., still
investigating, finding hasn't crystallized): pin a one-paragraph
summary in the slice's §B / §X-style appendix with the date,
specific commands run, and observation. The summary's purpose is
to anchor the eventual spec, not to replace it. Slices that close
without a corresponding spec for surfaced findings are treating
"static green + Pass 8" as sufficient for substrate that has FE /
agent / plugin consumers — the §X.12 failure mode the three-tier
verification pattern exists to prevent.

Reference: `447-followup-tier3-tooling.md` (the §C Playwright specs
are the durable archive shape for the §X.12 walkthrough findings;
each spec carries a one-line citation back to its originating
finding).

## When the spec is wrong

The bidirectional pass pattern produces spec corrections continuously.
When the spec is wrong:

- **Within-phase**: amend the spec entry in the tempdoc (e.g.,
  rev 3.5 §B.R.1 corrected the mapping-table state values during
  Phase 4-5 implementation).
- **Cross-phase**: the next phase's pre-impl pass picks up the
  correction and propagates it forward (rev 3.7 §B.T applied Phase
  4-5 lessons to Phase 6's spec).

If the spec author is the original tempdoc author and you're a
follow-up agent, prefer corrections that preserve the original
analysis trail. The §B.<letter> appendix pattern is designed for this
— each correction adds a new section rather than rewriting the
original.

## Subagent verification: forbid circular evidence

When dispatching a subagent for source-verbatim verification of a
spec claim, the prompt MUST explicitly forbid citing the spec under
review as evidence. Subagents anchor to existing spec content easily
— if the spec has a "see X for confirmation" pointer and X is
itself authored by the same spec author, the subagent may cite X
as confirmation without recognizing the circular evidence.

Per slice 444a §B.A.1 reference case: the subagent verifying recovery
field placement cited slice 438's reframe (which the same spec author
wrote) as "explicit confirmation" of the spec author's placement
claim. Self-meta-evaluation by the spec author then surfaced the
circular reasoning. The implementing-agent verification pattern
caught the wrong defect; spec-author self-meta-evaluation caught the
right one.

The hardening rule:

- Subagent prompts for spec verification MUST list "circular
  citation of the spec's own reframes / cross-references / cascade
  notes" as inadmissible evidence.
- Subagent verdicts that cite the spec as confirmation should be
  treated as un-verified.
- Spec-author self-meta-evaluation is a useful complement, not a
  replacement, to subagent verification — pair them; don't pick one.

Reference cases for the methodology:

- Slice 430 §B.AF — subagent caught 3 phantom IDs the spec author's
  outcome record missed. Implementing-agent verification worked.
- Slice 444 review — implementing-agent caught the source-vs-shape
  conflation. Implementing-agent verification worked.
- Slice 444a §B.A.1 — subagent's verdict on recovery field placement
  was wrong (circular evidence); spec-author self-meta-evaluation
  caught it. Implementing-agent verification did NOT catch the
  defect; self-eval did.

The methodology is robust when subagent verification is paired with
spec-author meta-evaluation, with subagent prompts hardened against
circular-evidence anchoring.

## Single-agent self-validation defect class

Per slice 430 §B.AE: when one agent both implements and validates a
slice, the validation may rest on the same blind spots as the
implementation. The CONFLICT-LEDGER closure protocol formalizes the
mitigation: "the agent or reviewer resolving the conflict should not
be the same agent who originally flagged it."

Apply the same discipline to slice closure. When the implementing
agent declares a slice complete, a follow-up agent's
source-verbatim verification pass (§B.AF-style) is a higher-fidelity
check than the implementing agent's own §B.AC-style outcome record.

Slice 430 §B.AF caught 3 phantom IDs that §B.AC missed. The follow-up
pass is not redundant.

## Concrete checklist

Use this when picking up a multi-phase slice:

**Before opening any per-Category recipe (primitive-selection pass):**

- [ ] If the slice introduces a new primitive instance: answer the
      truth-class question (who reads this, for what purpose) and
      walk the vocabulary-creep check (does the chosen primitive
      cover the workload without per-instance compensation?).
      Reference: §"Pre-impl: primitive-selection pass" above.
- [ ] If either question flags a stretch: document in the slice's
      `§A`, file a `conflict-ledger.md` entry, and decide
      proceed-with-documented-stretch vs pause vs re-target *before*
      opening the per-Category recipe.
- [ ] Skip this pass only for subsequent instances of an
      already-validated primitive Category (e.g., the third
      TIMESERIES Resource doesn't re-open the substrate question).

**Before phase 1:**

- [ ] Run the pre-flight emit-path probe: every declared emit ID has
      a producer, or is marked phantom/forward-compat/FE-only.
- [ ] Verify any spec claims about field paths, method signatures,
      visibility, type semantics against source verbatim.
- [ ] **Consumer-anchoring** (per "Declaration ≠ runtime" §above): for
      every load-bearing claim of "X is wired" or "X exists," cite
      both the declaration site AND the runtime consumer site
      (~5-minute timebox per claim). If the consumer can't be found,
      drop confidence — don't proceed assuming the claim holds.

**Before phase N (N > 1):**

- [ ] Pre-implementation spec-tightening pass with phase N-1's lessons.
- [ ] Record corrections in `§B.<letter>` with primary-source citations.
- [ ] Re-run the consumer-anchoring probe for any new declaration-
      based claims phase N relies on (catalogs, registries, listener
      APIs, boot side effects, etc.).

**After phase N is implemented:**

- [ ] Critical-analysis pass with the failure-mode lens.
- [ ] Record findings + fix commits in `§B.<letter>`.
- [ ] If 0 actionable findings, record that as evidence the pre-impl
      pass worked.
- [ ] Wire-format vs implementation honesty check (e.g., are
      `HistoryPolicy.Mode` declarations matched by actual storage
      semantics? `@JsonValue` types matched by their schema baselines?).
- [ ] Infrastructure-without-consumer sweep: any new listener API or
      helper class with zero production callers? `grep -r "new
      SomeClass\b" modules/*/src/main/java/`. If unused, decide
      between consolidation (preferred) and deletion.
- [ ] Recipe-vs-substrate drift check: any recipe doc claims that
      contradict the substrate's javadoc / compact-constructor /
      validator rules?

**Before declaring slice complete:**

- [ ] Post-completion analysis (§B.AD-style).
- [ ] Meta-evaluation (§B.AE-style).
- [ ] Source-verbatim verification (§B.AF-style) — ideally by a
      different agent than the implementer.
- [ ] If a sweep / audit reported "no findings," verify-by-eyes:
      re-read each artifact the sweep claimed to have audited.
      Trust + ship is shipping on a hypothesis.
- [ ] Cleanup (§B.AG-style).

**Before merge:**

- [ ] Build green, unit tests green, spotless clean.
- [ ] Manual smoke procedure documented (don't run it autonomously
      unless the dev-stack ownership model permits).
- [ ] Substrate artifacts (validators, base classes) extracted with
      downstream slice consumers in mind.

## Why this works

Spec passes compound *before* implementation; critical-analysis passes
compound *after*; *implementation itself* is the most rigorous spec
pass — but only for the paths it exercises.

The pattern is bidirectional: post-implementation analysis catches
what the test suite missed; pre-implementation analysis catches what
the spec missed. Five consecutive zero-finding post-implementation
outcomes (slice 430 Phases 6-10) prove that when the spec-tightening
pass is rigorous enough, the implementation lands clean and the
critical-analysis pass becomes verification rather than remediation.

Ship at: "looks complete after a spec-tightening pass against the
prior phase's lessons, at least one implementation pass, *and* at
least one critical-analysis pass per phase."

---

## Substrate-shape governance passes (Pass 4 / 5 / 7 / 8)

Ratified 2026-05-08 per slice 447 §X resolution. The pre-impl
primitive-selection pass (above) checks "is this a primitive?" The
substrate-shape governance passes check the inverse and adjacent
failure modes that produced CONFLICT-LEDGER entries C-015 / C-016 /
C-017 / C-018 + the meta-pattern of single-agent self-validation
that surfaced 4 load-bearing defects across 3 design rounds of
slice 447.

These passes are **load-bearing methodology**. They run on every
substrate-shipping or substrate-rejecting slice, not as
recommendations. A slice that proposes new substrate without
running them is incomplete.

### Pass 4 — Concern-bundling check (closes C-016)

Before declaring N existing things as instances of a new primitive
(or unifying them under one new substrate), verify the N things
share **all four axes**:

1. **Semantic role** — what role does each play in the system?
2. **Consumer model** — who reads each? UI / agent / executor /
   plugin / debug-surface — same consumer surface?
3. **Metadata schema** — do they all carry the same shape of
   metadata, or are they structurally heterogeneous?
4. **Lifecycle** — Operation-author time / runtime-observed /
   documentation-author time / catalog-build time — same
   lifecycle?

If **any axis splits**, the N things are different concerns
sharing a syntactic pattern. Don't bundle them under one new
primitive. The original 7-Kind Relationship proposal in slice 447
failed this check (the 7 Kinds split on consumer model and
lifecycle); the C-016 entry records the lesson.

**Output**: a §"Pass 4 verification" subsection in the slice's
spec-tightening pass, with one row per candidate instance and a
column per axis. If any cell shows divergence, decompose; don't
bundle.

### Pass 5 — Extension-suffices check (closes C-017)

Before declaring a new primitive, catalog, or contributor
interface, verify both:

1. **No existing primitive's field-extension absorbs the concern**.
   Walk every primitive (Operation, Resource, Prompt,
   DiagnosticChannel, Surface manifest) + their sub-records
   (OperationPolicy, Privacy, Interface, Provenance, Presentation).
   For each, ask: "could a new field on this absorb the concern?"
2. **No existing pattern provides a precedent** to follow without
   inventing new substrate. Check `Interface.uiHints`,
   `Resource.schema`, `Resource.history`, `Provenance.tier`,
   `Privacy.pathPolicy`, etc.

If both checks fail (no field-extension fits AND no existing
pattern parallels), then propose new substrate. If either check
passes, extend the existing primitive instead. The original
4-cluster Relationship decomposition failed this check (existing
`Interface.uiHints` provides the pattern; existing `OperationPolicy`
absorbs availability); the C-017 entry records the lesson.

**Output**: a §"Pass 5 verification" subsection in the slice's
spec-tightening pass with explicit rejections of each existing-
extension candidate (or, if extension fits, the slice becomes a
substrate-extension slice rather than a new-substrate slice).

### Pass 7 — Is-this-an-edge check (NEW; closes the C-015 framing error)

Before treating a cross-reference field (a field whose value is an
id-typed reference to another primitive) as an "edge anti-pattern"
needing new substrate, run the distinguishing test:

> **Does this cross-reference describe a *property of the source
> primitive* (metadata, leave it alone), or a *relationship that
> exists independently of either primitive* (candidate for separate
> substrate)?**

The distinguishing question: **if you delete one endpoint, does
the cross-reference become orphaned data that needs a new home,
or does it disappear with its source?** If the latter, it's
metadata. The 5 instances in the 447 family all dissolve with
their source — `Resource.recovery` disappears with the Resource;
`AssertedCondition.recoveryOperationId` disappears with the
observation. **Metadata. Leave it alone.**

The C-015 framing treated metadata as edges and produced 3 rounds
of substrate-design failure. Pass 7 prevents the recurrence.

**Output**: any slice critiquing existing cross-reference fields
must run this check first. If the check classifies the field as
metadata, the slice cannot reach the conclusion "we need new
substrate" — it can only reach refinements (value type widening,
field-name renaming, derived inverse views).

### C-018 — Substrate-without-consumer rule (with §X.11 refinement)

The C-018 conflict (recorded historically in the retired `421` draft's
conflict ledger; see [`conflict-ledger.md`](conflict-ledger.md))
captures the rule: **substrate should not ship without a named
consumer.** A new primitive type, catalog record, contributor
interface, wire-protocol shape, or Manifest tier requires at least
one named reader before merge. The reference case is the slice 481
substrate that shipped without consumers (`Audience` field with no
FE filter; `ConsumerHook` field with no enforcement) after Pass 8
was overridden via mandate-citation.

#### What C-018 governs

C-018 fires when **a new substrate slot is added that no
production code reads**. Examples that *do* fire:

- A new record component declared in a wire-emitted type whose
  production emitter doesn't serialize it (caught by the
  Pass 8 wire-emitter check).
- A new public function with zero production callsites (caught by
  the Pass 8 static-callsite check).
- A new contributor interface with no production implementations
  registered.
- A new field on an existing primitive whose value is never read.

#### What C-018 does NOT govern (§X.11 refinement, ratified 2026-05-08)

C-018 governs **new substrate slots that nobody reads, NOT
type-system refactors where every existing callsite is already a
consumer.** Conflating "no NEW consumer" with "no consumer at all"
caused slice 447 §4.A (rename) + §4.E (partition) to be deferred
under a C-018 invocation when both had full existing consumer
surface from day one (every `OperationRef` callsite is a consumer
of the rename; every `OperationPolicy` caller is a consumer of the
partition). The §X.11 closure note records this misapplication.

The distinguishing test (codified as Pass 7 in this doc): **if you
delete one endpoint, does the cross-reference become orphaned data
that needs a new home, or does it disappear with its source?** The
same shape applies to C-018: if the substrate is a *rename* /
*regrouping* / *type-widening* of existing slots whose readers
already exist, every reader is a consumer from day one — C-018
does not fire. If the substrate is a *new slot* awaiting a future
reader, C-018 fires.

#### Substrate-prepared-without-placement (related but distinct)

A third category exists, sitting between the two above:
**substrate that is wired but has no production placement sites
yet.** Reference case: the `core-contracts` module's BootContract
chain (`BootContractRegistry`, `BootContractValidator`) is wired
into `IndexerWorker.validateAll()` at boot, but no production
class implements `BootContractValidator` and no production
`META-INF/services/` entry exists. The chain runs and returns zero
violations every boot. Same shape for `ContractSampler` —
production-ready primitive with zero placement sites.

This is **not C-018.** The substrate has consumers (the chain's
internal collaborators); placement is deferred by design per
tempdoc 400 LR6-a + tempdoc 402. The Python projection's docstring
documents the deferred-placement contract directly:
*"Until the deferred contract tiers land, no contract.violation
events are emitted in production and the projection returns an
empty aggregate. That is the intended shape: when the runtime
tiers ship, the projection starts returning non-empty data with
zero downstream changes."*

The category is worth tracking — if six months pass without
placement, the substrate becomes a re-evaluation candidate. But
the trigger is **time without placement**, not **zero consumers**.
The `core-contracts-c018-audit` tempdoc records the full
methodology that distinguishes substrate-prepared-without-placement
from C-018.

#### Audit methodology — six failure modes that produce false positives

The `core-contracts-c018-audit` tempdoc surfaced six methodology
failures that produce false C-018 positives. Any C-018 audit must
guard against:

1. **Typed-import grep undercounts ServiceLoader-discovered
   consumers.** Check `META-INF/services/<fqn>` files alongside
   type-reference greps.
2. **Annotation types' "consumers" are use-sites, not import
   sites.** `@BuildContract` may be applied at sites that don't
   import the runtime type directly.
3. **Internal-module callers count.** A type with zero outside
   callers but six in-module callers is module-internal
   infrastructure, not orphaned substrate.
4. **Cross-language consumers are invisible to Java grep.** Python
   projections, NDJSON exporters, generated docs. Read the
   module's `build.gradle.kts` for the full consumer list.
5. **Method-invocation grep ≠ type-reference grep.** A type may
   be referenced by JavaDoc / field type / parameter type while
   the actual `.method()` call surface differs.
6. **Substrate-prepared-without-placement is a distinct shape.**
   Wired consumers with zero populators is not C-018.

A C-018 finding without all six checks performed is a candidate
false positive.

### Pass 8 — Mandatory second-agent verification on substrate-shipping commits (NEW)

Single-agent self-validation has been the load-bearing failure
mode across 3 rounds of slice 447 design (and at smaller scales
across V1.5 reviewer-pass findings). Each round felt rigorous
internally but a different agent's adversarial-framing review
surfaced new defect classes that the original agent missed.

**Codify**: substrate-shipping commits — those that introduce new
primitive types, new catalog records, new contributor interfaces,
new wire-protocol shapes, or new Manifest tiers — require a
different agent's adversarial verification before merge. The
reviewer:

- Does NOT inherit the original agent's framing assumptions
- Walks the source code to verify every claim in the slice's spec
- Treats the spec as **claims to disprove**, not claims to confirm
- Outputs findings as load-bearing (must resolve) or minor
  (track-but-ship), with citations to source

If the reviewer finds load-bearing defects, the original agent
addresses them. The reviewer can spot-check the resolutions; a
second full review is not required unless the resolution itself
introduces new substrate.

**Scope**: this pass is mandatory for substrate-shipping commits.
For implementation slices that consume existing substrate (without
introducing new substrate shapes), Pass 8 is recommended but not
load-bearing.

**Honor-system (was briefly gate-enforced, tempdoc 550 thesis V)**: this
mandate was for a time enforced by the `independent-review` discipline gate,
which has since been **retired** (tempdoc 530 §Remediation; the
audit-dependent gates were judged not worth their cost). The Pass-8 review +
live verification remain the way you produce a truthful record — now as
honor-system discipline rather than a red build. The same retirement removed
the presentation-work sibling `ux-audit-closure` gate.

**Output**: a §"Pass 8 verification" subsection in the slice's
critical-analysis pass, with the reviewer's findings + resolutions
+ explicit "no further reviewer pass required" or "follow-up
review committed in slice X."

**Standard Pass 8 brief checks** (the prompt template the
dispatching agent gives the reviewer). Each check has been
validated against a real escape and is now part of the default
template:

- **Type-system integrity**: sealed-interface bounds consistent
  across `permits` + impl declarations; no callsite silently
  loses type information; renames are textually complete.
- **Construction-site coverage**: every catalog construction site,
  test fixture, and cross-module reference uses the new positional
  argument list / record shape. (Caught by §X.11.5 Phase 3 review.)
- **Schema regen integrity**: the SSOT schema baseline is
  byte-stable or extends gracefully; FE wire-types track the
  baseline.
- **Static callsite check** (added §X.12.8, validated §X.12.10):
  for every newly-added public function, does it have at least one
  production callsite — not just tests? Defines a contract without
  consumers is a real failure mode (signature-without-consumer).
  Reference case: slice 447 §X.11.5 Phase 6 shipped
  `mergePluginRecoveryOverlays` with zero production callsites
  until the V1.5.1 polish landed in `cc237f188`. Two parallel
  functions (`mergePluginSurfaceContributions`,
  `mergePluginResourceContributions`) had been in the same state
  for several earlier slices and the same check would have caught
  them at their original ship point.
- **Wire-emitter check** (added §X.12.10): for every newly-added
  record component on a wire-emitted type, does the production
  emitter actually serialize it? Schema-correctness alone gives
  false security. Reference case: the §X.11.5 Phase 3 partition's
  `availability` + `lineage` sub-records were declared in the
  schema baseline but `UIOperationEmitter.toUIEntry` manually
  serialized only id/type/presentation/intf/policy/provenance/
  executors and silently dropped the new sub-records; Item 2.1's
  `lineage.affects` population would have been wire-invisible
  without the emitter fix in `9c89d5b7f`.
- **Audit-without-test check**: any conclusion of the form "X is
  the only blocker for Y" requires a regression test exercising Y,
  not just static reasoning. (Reference case: tempdoc 403 Tier C.)
- **Wrong-gate / wrong-flag check**: the gate the new code is
  guarded by — does it fire in the target scenario? Grep the
  set-site, don't trust the symbol exists. (Reference case:
  tempdoc 403 Tier B.)
- **Operation-label check** (added tempdoc 509): if a surface
  renders an operation button, does it use `<jf-op-button>` (not
  a raw `<button>` with a hardcoded label)? Hardcoded labels are
  the D2 defect class — "same capability, different name" across
  surfaces. (Reference case: F-3 in tempdoc 504.)
- **Wire-emitter casing canonicalization** (added tempdoc
  511-followup-2 Track EE): every Java emitter that builds a JSON
  envelope for the FE MUST emit enum values as `enum.name()` raw
  (uppercase Java convention) and use FE-canonical discriminator
  field names (`kind`, not `type`) on sealed-type unions. The
  lower-cased emission pattern (e.g.,
  `op.policy().risk().name().toLowerCase()`) used in `UIOperationEmitter`
  through slice 509 was a band-aid that the FE catalog client
  patched with a normalizer; 511-followup Track E removed both.
  Reference case: tempdoc 511 §Critique 8 + Track E + 511-followup-2
  Track AA (which discovered the schema baseline and
  `ConfirmStrategy.java` Jackson annotation also had to be flipped).
  Future emitters that re-introduce `.toLowerCase()` or
  `property = "type"` for an FE-consumed wire MUST fail their
  per-emitter test (assertions on canonical-uppercase + `kind`
  discriminator).

- **Catalog-migration live-walkthrough** (added tempdoc 504 §D7,
  2026-05-19): any slice that migrates a registry or catalog (e.g.,
  `PluginRegistry` migration, `CorePlugin` manifest construction,
  Resource registry rewiring) MUST ship with a live walkthrough of
  every migrated entry as the verification artifact — at minimum
  "the surface mounts without an error placeholder." Round-trip
  lifecycle proofs (uninstall/reinstall) verify the mechanism but
  not the data. Reference case: tempdoc 504 F-40 — 507 Phase 3
  migration ported `core.unified-chat-surface` to the catalog with
  `mountTag: 'jf-chat-shape-mount'` + `consumes: {}`; the
  combination is internally incoherent (the mount wrapper requires
  a `shape-id` derived from `consumes.conversationShapes[0]`), so
  the surface rendered an error placeholder for ~24h before live
  verification caught it. The class is D7
  (catalog-entry-semantically-incoherent) — distinct from D1
  (no-consumer) and D3 (consumer-ignores-field) because a consumer
  *does* render the entry; it just renders an error. A 30-second
  rail walkthrough at PR-time catches it.

### Verdict semantics — Pass 8 verdicts are gates by default (added 2026-05-08)

**A Pass 8 verdict is a merge gate, not a discussion item.** When
the dispatched reviewer returns one of:

- `requires-pass-3` — structural gaps remain; not ready for
  ratification or substrate-shipping merge.
- `load-bearing-defect` — at least one finding must resolve
  before merge.
- `block` — the work as drafted should not ship in this shape.

…the implementing agent **does not have authority to override the
verdict on the user's behalf via mandate-citation**. Phrases like
"the user said don't defer for substantial/inconvenient work" or
"the user mandated long-term-better designs" are valid framings
for *what to ship* but do not authorize shipping past a verdict
that says *not yet ready*. Those mandate clauses describe scope +
ambition; the Pass 8 verdict describes verification readiness;
the two operate on different axes.

**Override is possible only with explicit user authorization
recorded in the commit.** Authorization shape:

- The commit message names the verdict (`Pass 8 verdict:
  requires-pass-3`) and quotes the user's explicit override
  ("user authorized shipping despite verdict on <date>; rationale:
  <one-sentence summary>").
- A row is added to `conflict-ledger.md` recording the override:
  the verdict, the user's authorization, and the named follow-up
  slice that closes the deferred verification work.
- The deferred verification work has a committed slice ID (not
  "future Pass-3 design slice") and a Pass 8 dispatch to a
  different agent on its closure.

Without those three artifacts in the same commit, a substrate-
shipping commit landing against a non-`pass` verdict reproduces
the §X.12 failure mode the discipline exists to prevent.

**Reference case: slice 481** (`447-followup` substrate
unification, commit `d3ca31e3c`, 2026-05-08). The implementing
agent dispatched Pass 8, received `requires-pass-3`, cited the
user's "long-term-better designs" mandate, and shipped 3217 LOC of
substrate (`Audience` axis, `ConsumerHook` substrate slot,
`PrimitiveCatalog<T>`) to main. The verdict was treated as a
discussion item the agent could weigh against other mandates; the
result was substrate-without-consumer (`Audience` field with no
FE filter, `ConsumerHook` field with no enforcement) shipped to
main with deferred-but-uncommitted enforcement — exactly the C-018
pattern the slice claimed to dissolve. The follow-up work to
close the gap (slice TBD) is the right path forward, but the
landing-against-verdict shape is what this verdict-semantics
section exists to prevent next time.

**Why this isn't redundant with the user's "ship without
verification" warning in CLAUDE.md.** That warning is an
*ambition* clause: "don't defer because the work is hard." The
verdict-gating rule is a *verification* clause: "don't merge
substrate ahead of its verification." When ambition + verification
clauses appear to conflict, ambition does not override verification
without explicit, recorded user authorization on the specific
commit. The agent's job is to surface the conflict to the user
and request the override; the agent does not unilaterally resolve
the conflict.

### Composition with existing passes

Pass 4 / 5 / 7 / 8 compose with the existing primitive-selection
pass and Pass 6 (layer-of-discharge):

| Slice shape | Required passes |
|---|---|
| New primitive proposed | 4 + 5 + 7 + 8 + primitive-selection + 6 |
| Existing-primitive field extension | 5 + 6 + 7 + 8 |
| Cross-reference field critique | 7 + 8 |
| Substrate-without-consumer flagged | 6 + 8 |
| Implementation slice (no new substrate) | spec-tightening + critical-analysis (existing); Pass 8 recommended |

A slice author looking at "which passes apply to my slice?" finds
the answer mechanically. Substrate work is high-cost-of-error;
the passes are the structural checks that reduce that cost.

### Reference cases

- **Slice 447 §X (2026-05-08)**: the canonical case. Three rounds
  of substrate-design failure (C-015 → C-016 → C-017) + adversarial
  verification (§W) + re-framing (§X.1). The re-framing rejected
  the substrate-design premise per Pass 7; §X.3 ratifications
  applied Pass 4 (semantic-role split) + Pass 5 (existing-extension
  absorbs each concern); Pass 8 (this resolution itself was
  second-agent-validated by the user as reviewer).
- **V1.5 reviewer-pass (2026-05-08)**: 4 load-bearing defects in
  shipped substrate (F3 stolen-brand, F4 atomicity wording, F5
  pause/resume intent, F6 RED test) found by Pass 8 review.
  Single-agent self-validation had passed each commit.

### Why this works long-term

The four substrate-leak instances cited in C-015 (444a
source-vs-shape, 3a.1.4 renderer-vs-shape, 446 truth-class, 442
edge-as-field) each took 2-3 design rounds to resolve. With
Pass 4-8 ratified, future agents proposing new substrate will run
the checks before spending design rounds. The pattern of "each
round surfaces a new defect class" terminates because the failure
modes each round was producing now have explicit pre-impl checks
named for them.

The cost is doc-overhead per slice — running the passes is real
work. The benefit is preventing multi-week design cycles that end
in deferral. Slice 447's 3 rounds + §W + §X resolution took weeks;
running Pass 4-7 in the first round would have surfaced the
metadata-not-edge framing immediately.
