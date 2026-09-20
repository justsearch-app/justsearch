---
title: "Frontend kernel — primitives"
type: reference
status: stable
description: "The three FE registry primitives (Operation/Resource/Prompt) and how surfaces project from them."
date: 2026-06-09
---

# Frontend kernel — primitives

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.


The kernel has four backend-declared primitives:

1. `Operation`
2. `Resource`
3. `Prompt`
4. `DiagnosticChannel`

These are not product concepts. They are framework shapes used to project
backend-owned capability into any frontend surface.

For the convergence rationale (what was tried, what failed, why these three),
see `../../../../decisions/0031-fe-three-primitives.md`. For full source evidence, see
`archive/source-tempdocs/421-data-plane.md`, `429-slice-1-2-admin-action-registry.md`,
and `435-operation-substrate-doc-alignment.md`.

## Operation

An `Operation` is an executable capability.

Examples:

- restart worker
- reindex source
- install model pack
- activate runtime variant
- export diagnostics
- cancel a long-running job

An operation declaration includes:

- stable id
- title and description i18n keys
- argument schema
- target scope
- availability expression
- risk tier
- confirmation strategy
- execution endpoint or executor binding
- progress/resource linkage
- cancellation support
- idempotency and retry semantics

Operations should replace scattered admin actions, bespoke panel buttons, and
agent-only tool declarations when those actions represent the same backend
capability.

### Capability vs policy axes (slice 484 §3.2 #1 closure, 2026-05-08)

Operation entries (and Resource / Prompt entries that ship the same axes per
slice 481 §7 step 2) carry two axes that read similarly but are *orthogonal*:

- **`executors: Set<ExecutorTag>`** — *technical capability*. Which technical
  executors *can* invoke this Operation: `UI` (a button click), `AGENT` (an
  LLM tool-binding), `CLI` (a script). Used for routing the invocation at
  emit time (`OperationEmitter.filterForTarget(catalog)` filters by
  `executors.contains(targetExecutor())`).
- **`audience: Audience`** — *consumer intent*. Who the Operation is *for*:
  `USER` (an end-user feature), `OPERATOR` (an admin action), `AGENT` (an
  agent-internal tool not surfaced to humans), `DEVELOPER` (a debug surface).
  Used for visibility filtering in chrome (`visibleAudienceSet`), for
  agent allow-listing per slice 484 §3.1 C1, and for trust-tier floor
  composition per slice 449 §0 D2.

The two axes can combine in any pairing. Examples:

- `audience=USER, executors={UI, AGENT}` — a user-facing Operation
  (search-index, reindex) the LLM can invoke on the user's behalf.
- `audience=OPERATOR, executors={UI}` — an admin action only the operator
  can invoke from the chrome (restart-worker, clear-failed-jobs,
  export-diagnostics).
- `audience=OPERATOR, executors={UI, AGENT}` — an admin action *also*
  exposed to the LLM. Pre-slice-481 this combination shipped on
  `core.bulk-reindex` and `core.rebuild-index`. Per slice 484 §3.1 C1, the
  agent emitter's allow-list now blocks the AGENT-side leak: USER + AGENT
  audiences pass; OPERATOR + DEVELOPER are denied.
- `audience=AGENT, executors={AGENT}` — an agent-internal tool with no
  human-visible surface (none today; reserved for future agent-prompt
  scaffolding).

The role separation matters because the temptation to merge the axes is
real: both look like "who can invoke." The capability axis tells you
*what's wired up*; the audience axis tells you *what's allowed*. Merging
them flattens the slice 484 §3.1 C1 fix and the slice 449 §0 D2 audience
composition rule into one indistinguishable field.

## Resource

A `Resource` is observable runtime truth.

Examples:

- health events
- operation registry
- settings schema
- model installation status
- runtime readiness
- job progress
- plugin state

A resource declaration includes:

- stable id
- **category** (typed information shape — see below)
- schema
- snapshot endpoint
- optional stream endpoint
- subscription mode (transport — see below)
- optional history policy (required when category implies retained history)
- optional recovery operation id (cross-link to a recovery `Operation`)
- freshness/staleness semantics
- ownership
- visibility and cache policy
- degraded-state behavior

Resources are the kernel's answer to frontend split-brain state. Product
surfaces may compose resources, but they must not rederive backend-owned truth.

### Resource Category (information shape)

Every Resource declares one of five Categories. The Category is the
**information-shape** discriminator — what kind of data the Resource
represents — orthogonal to the **transport** discriminator
(`SubscriptionMode`).

- **STATE** — current value of one thing (settings, runtime mode,
  capability flags). No retained history; updates replace prior value.
- **EVENT_STREAM** — live typed events with bounded recent-window
  retention (HealthEvent, agent session events).
  Subscribers see a snapshot of recent + a stream of new.
- **HISTORY** — durable past events with queryable reads (ingestion
  ledger, operation history, search history). Append-only;
  retention is policy-driven; reads are typically paginated.
- **TABULAR** — current state of a collection of items (job queue,
  library sources, active runtimes). Snapshot + per-item delta updates.
- **TIMESERIES** — sliding window of regular numeric samples
  (job-queue depth trend, indexing throughput, GPU utilization).
  Snapshot-of-window with regular cadence; subscribers get the
  current N samples per frame, not a stream of discrete events.
  Distinct from EVENT_STREAM in update cadence (regular vs irregular)
  and wire economy (one frame carries N samples vs N frames carrying
  one sample each).

Adding a Category value follows shape-governance per
`04-shape-governance.md` §"Vocabulary Governance". The
durable decision rationale is in `../../../../decisions/0036-fe-resource-category.md`.

The constraint matrix between Category and `SubscriptionMode` is
documented in `20-systems/01-resources.md` §"Resource Policy
Vocabulary".

`primaryKey` is required for TABULAR resources and is an optional
identity declaration for EVENT_STREAM and HISTORY. Those event strategies merge
snapshot/update overlap by the explicitly declared key, keeping the latest value
for each identity within their bounded window. Missing or blank row keys append
independently; a blank declaration preserves append semantics for the entire stream.
Operation history declares `operationKey`, which identifies an invocation rather
than the operation definition shared by many invocations.

SSE_STREAM resources use the universal
SSE envelope (single JSON object with `streamId` / `frameKind` /
`seq` / `ts` / `payload` / `resumeToken`; constant SSE event name
`"frame"`). Canonical wire-format reference:
`05-streaming-envelope.md`. Decision rationale:
`../../../../decisions/0037-universal-sse-envelope.md`. The four shipped
SSE_STREAM Resources (HealthEvent, RuntimeContext,
ServerCapabilities, OperationHistory) all use this shape.

## DiagnosticChannel

A `DiagnosticChannel` is operator-observable diagnostic flow that is
not backend domain truth.

Examples:

- head process log stream
- worker process log stream
- plugin loader diagnostics
- boot trace
- debug-only infrastructure traces

Diagnostic channels are separate from Resources because they have a
different consumer model and privacy posture. A Resource describes
declared backend truth for users, agents, plugins, and product
surfaces. A DiagnosticChannel describes operator-level traces whose
schema may be intentionally looser and whose consumers require
diagnostic permission.

A diagnostic channel declaration includes:

- stable id
- presentation i18n keys
- channel kind or subcategory
- stream endpoint
- retention/capacity policy
- consumer permission floor
- producer ownership
- privacy and redaction notes
- diagnostic attribution

Slice 448 retired `LOG_TAIL` from `Resource.Category` and introduced
DiagnosticChannel as the fourth primitive. Log-tail work belongs here,
not in the Resource Category router.

## Prompt

A `Prompt` is a declared language interaction shape.

Examples:

- summarize selected documents
- ask across a document set
- explain a health event
- generate a search query refinement
- agent tool invocation plan

A prompt declaration includes:

- stable id
- input schema
- output schema or stream shape
- safety/risk class
- model/runtime requirements
- citation/provenance expectations
- cancellation behavior
- i18n labels and help text

Prompts are separate from operations because they produce language-mediated
outputs and often need provenance, context windows, and model readiness
constraints.

## Product Concepts Are Not Automatically Primitives

Documents, sources, indexes, results, citations, sessions, and previews are
product/domain concepts. They may be exposed through resources or acted on by
operations, but they should not become new kernel primitives without passing the
primitive checklist.

## Primitive Checklist

Introduce a new primitive only when all are true:

- multiple unrelated surfaces need the same shape
- backend ownership is clear
- generic rendering or generic consumption is realistic
- lifecycle, versioning, and validation rules can be specified
- the shape cannot be represented as an entry kind of an existing primitive

Otherwise, use an operation/resource/prompt entry kind or a product-specific
contract.


## Composition modifier (slice 481 reframe of the slice 449 "Manifest tier")

**Renamed framing per slice 481 §C.1.F (post-Pass-8, 2026-05-08)**: the
slice 449 "Manifest tier" describes a real distinction (entries that
*compose* other entries vs. atomic primitives), but framing it as a
*tier* obscures that the distinction is a typed *modifier* orthogonal
to the primary Category axis. Surface and Plugin do not sit alongside
the four primitives in a parallel tier; they sit *as* primitive Category
instances with `composition.kind = COMPOSED` (per slice 481 §3.7 sealed
`Composition` discriminator: `ATOMIC` / `COMPOSED` / `INVARIANT_REF` /
`METADATA_REF`). The Java seal currently excludes Surface; the projected
unification (slice 481 §3.8) is the post-Step-1 + post-Plugin-Java-
reification destination.

The Composition modifier ships entries that compose primitives into
UI-mountable or installable units. It does not introduce a new wire-shape
category.

A **composed Surface** entry declares a self-mounting Lit element keyed
by an audience axis (USER / AGENT / OPERATOR / DEVELOPER) and a
placement (RAIL / STAGE / HUD / STATUS / DRAWER / MODAL / DEEPLINK /
HEADLESS_AGENT_TOOL / COMMAND). Each entry's `consumes` field
references the primitives the surface depends on
(`resources` / `operations` / `prompts` / `diagnosticChannels`); the
catalog validator at handshake time enforces that all referenced ids
resolve.

**`Placement.MODAL` is realized** (tempdoc 855): a MODAL-placement target
opens as a chrome-level overlay window over an unchanged stage, instead of
mounting into it. `NavigationHandler` (`router/navigationHandler.ts`)
carries one placement-conditional branch, reached by every entry point
(rail, command palette, URL boot) because they all funnel through the same
handler: it skips `setActiveSurface` (the catalog-wide Stage fallback is
not placement-filtered and would otherwise mount the surface standalone)
and skips `activateProjection` (it unconditionally tears down the
*current* surface's URL projection before its own schema check, which
would kill the underlying stage surface's live URL sync for a
schema-less MODAL target). It still runs `applyState` and dedupes the
history push when the target address is already current, and reports to
the opening callback whether a push actually happened — close is
pushed-aware (`history.back()` only when this navigation added the entry;
otherwise a forward navigation restores the stage surface, covering the
boot-under-modal case). User-initiated close (ESC/X/backdrop) restores
focus to the invoker per WAI-ARIA (`ModalityController`'s own restore); a
navigation-initiated `dismiss()` (a realized stage navigation while the
window is open) adds no restore of its own, because the navigation owns
focus's destination — the platform dialog may still return focus to the
invoker, which is acceptable for the dismissal cases. First and currently
only realization: `core.settings-surface` (mounted as
`<jf-settings-window>` in `chrome/SettingsWindow.ts`, in `OverlayHost`'s
`center` slot — the same dock as the command palette — using
`ModalController` over a native `<dialog>`, the `ConfirmDialog` idiom).

**Audience composition rule** (slice 449 §0 D2; refined-but-retained-as-V1
per slice 481 §D defect 1): the effective audience of a composed entry
is the *maximum* of:
  1. the declared audience,
  2. the audience floor from the entry's provenance trust tier,
  3. the audience floor implied by any DiagnosticChannel the entry
     consumes (channels with `consumerPermission != CORE` raise the
     floor to OPERATOR per slice 448).

Pass 8 (slice 481 §D defect 1) flagged the MAX rule as too coarse — a
USER-audience Surface that consumes one OPERATOR-floored DiagnosticChannel
becomes OPERATOR-only, when the structurally-honest answer is usually to
*redact* the operator-only sub-fields rather than *elevate* the whole
surface. V1 retains MAX; per-field redact is a Pass-3 follow-up that
needs its own design cycle.

Trust tier floors:
  - CORE / TRUSTED_PLUGIN: USER (no demotion).
  - UNTRUSTED_PLUGIN: OPERATOR (admin-only by default).

**Why Composition is a modifier, not a Category**: composed entries are
not wire shapes — they're structured *references* to existing entries.
Adding a fifth primitive Category (per Pass-4 / Pass-5 verification,
slice 449 §2/§3) would re-introduce concern-bundling (C-016) and
primitive-proliferation (C-017) the kernel was designed to avoid. Slice
481 §C.1.F proved the four-Category framing was over-strong by walking
a hypothetical Workflow primitive through it (Workflow has three
simultaneous Category memberships; the resolution is that Composition
is orthogonal, not a fourth Category).

The Composition modifier hosts (in V1.5+) plugin-contributed surfaces
(`PluginCapabilities.surfaces`), merged into the live `SurfaceCatalog`
at plugin install time with the audience floor applied per
`audienceFloorForTier()` (V1: TRUSTED_PLUGIN → USER floor; V1.5
sandbox: UNTRUSTED_PLUGIN → OPERATOR floor).

## See also

- [Frontend kernel — declarative presentation](06-declarative-presentation.md) — how the core
  surfaces *render* from a typed declaration (the `DeclaredSurface` engine, `x-ui-renderer` dispatch,
  the frontend `Effect` vocabulary, behaviour-as-statechart), and the truth/presentation cut that
  keeps the primitives above team-owned.
