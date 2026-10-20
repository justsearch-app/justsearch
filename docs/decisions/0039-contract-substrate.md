---
title: "ADR-0039: Contract substrate — every published contract is a first-class artifact"
type: decision
status: accepted - format superseded by tempdoc 564
description: "One authority per contract Category, projected per target; only wire is registered. Tempdoc 564 supersedes the proto-as-source realization for frontend wire types: Java records drive JSON Schema and TS/Zod projection."
date: 2026-06-09
probes:
  - adr-0039-one-contract-category
last_reviewed: 2026-09-02
---


# ADR-0039: Contract substrate — every published contract is a first-class artifact

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted **in principle** (single authority per contract; projection-not-fork) — but the **proto-format realization is superseded by tempdoc 564**, which chose record-as-IDL for the wire Category and demoted proto to a derived view. The substrate framing stands; its V1 format does not.

## Decision

Every cross-language agreement the system publishes — wire protocol,
plugin SDK, catalog set, registry primitive serialization, persistence
formats — is a contract object in a uniform substrate. Each contract
declares seven axes (shape, required-ness, runtime invariants, cross-
references, behavior separation, evolution rules, catalog membership)
in a language-neutral spec. Per-target projection emitters (Java,
TypeScript, Zod, future-language) generate idiomatic types + runtime
validators with refinements + cross-reference helpers + capability-
handshake declarations from the same spec.

Contract artifacts are **language-neutral and separately distributable**
from any consumer's build. Spec source lives at repo root
(`contracts/<category>/`), versioned independently of any module. Each
projection emitter publishes an artifact appropriate to its target
(Maven jar for Java; npm package for TypeScript; raw spec download for
"any language"). A contract's git history is its protocol's history.

The wire contract between backend and frontend (and plugin SDK
consumers, and any future multi-language port) is one Category in this
substrate. Plugin SDK, catalogs, and the registry-primitive
serialization formats are other Categories.

The plugin SDK and the contract package are the same artifact, viewed
through the plugin-author lens. There is no separate "plugin SDK
package."

This ADR extends `0038-wire-contract-source-of-truth.md`.
ADR-08 committed to "the wire contract is a first-class artifact"; this
ADR commits to "every contract this system publishes is a first-class
artifact, with the wire contract as one Category in a uniform
substrate." ADR-08 stays in place as historical context; its narrower
prohibitions (hand-written per-language mirrors, implicit Java-as-SoT
with codegen on top) remain valid under this ADR.

The implementing slice is `slices/3a-1-8-wire-contract-architecture.md`,
re-scoped from "build the wire contract" to "ship the wire-Category
instance of the contract substrate, with substrate-extension work named
in follow-up slice stubs (3a-1-8b/c/d)."

## Rationale

ADR-08's "single source of truth" framing addresses one axis (shape) of
what is actually a seven-axis problem. The five hard requirements ADR-08
named (discriminated unions, mixed snake/camel, optional/nullable
distinction, forward-compat passthrough, semver evolution) are real but
incomplete. A contract carries:

1. shape (what an IDL handles)
2. required-ness presence semantics
3. runtime invariants (compact-constructor regex checks, non-blank
   guards, immutable-copy normalization)
4. cross-references (`recoveryOperationId` → OperationRegistry;
   `i18nKey` → i18n catalog)
5. behavior separation (factories, deprecated aliases, view
   transformers — per-language, NOT contract)
6. evolution rules (additive-optional = patch; additive-required =
   minor; rename/remove = major)
7. catalog membership (the closed set of valid `id`/`reason`/
   `severity` values)

ADR-08 handled axis 1 and partial axis 2. Axes 3-7 stayed scattered:
invariants live in Java compact constructors only (no TS/Zod symmetry);
cross-references are validated by ad-hoc tests if at all; behavior is
co-located with generated record candidates (Phase 3 of the original
ADR-08 implementation surface had to disperse it); evolution rules are
procedural; catalog membership is split across `HealthEventChange-
Registry` callsites, `AppFacadeBootstrap`, i18n bundles, and SSOT
files.

The substrate framing makes all seven axes first-class artifacts and
treats "the wire contract" as one Category in a uniform pattern. This
prevents the bug class long-term: producer-vs-consumer type drift is
impossible because both project from the same source; producer-
invariant-vs-consumer-validator drift is impossible because both emit
from the same invariant spec; cross-reference rot is caught at
handshake + CI; "this works on the FE but breaks the plugin SDK" is
impossible because the plugin SDK is the contract.

### Why a substrate, not a single artifact

The natural alternative is "one big wire contract spec that includes
plugin SDK and catalogs as extensions." This collapses the substrate's
Category axis the same way an early Resource design collapsed
information shape into transport (per `0036-fe-resource-category.md`). Different contract Categories have different evolution
rules (a wire-format breaking change is high-blast-radius; a catalog
addition is patch-grade), different consumer expectations (a plugin
manifest validates at install; a wire envelope validates per-frame),
and different distribution shapes (catalog spec ships at human-
readable cadence; wire spec ships at protocol-version cadence). Per
shape governance (`../reference/ui/frontend-kernel/kernel/04-shape-governance.md`), Categories are
typed values inside an existing primitive. Per the same governance,
the contract primitive is one substrate with typed Category values, not
separate primitives per Category.

The substrate borrows 444a's *shape-move* (typed Categories within a
primitive, with per-Category recipes governing the design space) but
not its primitive-class. 444a typed an axis on an existing domain
primitive (`Resource`); ADR-09 introduces a new *infrastructure*
primitive (the contract substrate itself) with the Category axis
built in from day one. Per the kernel doc §"Relationship To The
Three Primitives": the substrate is not a fourth domain primitive
parallel to Operation/Resource/Prompt — it is the projection
machinery that sits beneath the three primitives and keeps their
wire-format projections honest. Categories in the substrate (wire,
plugin-SDK, catalog, registry-serialization) are types of
*infrastructure agreements*, not types of user-facing entities. The
per-Category recipe pattern is reused; the primitive class is
different.

Adding a Category to the contract substrate (e.g., a future eval-
corpus protocol) goes through shape governance the same way adding
a sixth Resource Category (`TIMESERIES`) did.

### Why a separate distributable

A contract baked into the producer's build (e.g., a Gradle subproject
`modules/api-contract/`) couples contract distribution to JVM release
cadence and makes plugin authors and alternative-language hosts
second-class consumers — they have to re-bundle the contract or
re-encode the types. The substrate's distribution posture rules out
the Gradle-subproject shape. Contract sources live at repo root,
versioned independently. Each projection emitter publishes an artifact
appropriate to its target.

This dissolves ADR-08's Phase 6 ("plugin SDK package, deferred-future
work"). The plugin SDK is the contract, projected for plugin authors.
There is no separate SDK package; treating the contract as a
language-neutral distributable is what makes the SDK first-class.

### Why governance must be mechanical

ADR-08's "Future Agents Must Not" list and the procedural rules in
`docs/reference/contributing/common-workflows.md` are correct but fragile. Procedure
depends on agent compliance; the bug class re-emerges as soon as one
agent skips a step. The substrate's *trajectory* is mechanical
governance: build refuses non-contract-derived wire types; ESLint
refuses wire-shaped TS interfaces outside `api/generated/`; CI
structural-diff gate refuses contract changes whose evolution-rule
classification doesn't match their version bump. The architectural
rule is invariant — the only place where contract is declared is the
contract.

Per the substrate's capability-vs-mandate distinction (kernel doc
§"Capability vs Mandate"), each governance capability graduates to
mechanical enforcement on its own schedule. V1 ship under slice
3a-1-8 inhabits the static checks (ArchUnit Java + ESLint TS) and
the handshake-side machine check; manual CHANGELOG enforcement +
reviewer discipline covered evolution-rule classification under V1.
**V1.5 live (2026-05-07; activation-caveat amended 2026-05-12)**:
kernel-mediated mechanical structural-diff via the governance kernel
(`scripts/contract-governance/`); first instance: wire/Axis-6, with
report payload SARIF v2.1.0 ingestible by GitHub Code Scanning. See
`slices/3a-1-8f-governance-runtime.md` for the substrate
implementation. **Activation caveat:** the kernel fires only when
explicitly invoked. ADR-0044 allows the public hosted CI lane to run
on pull requests and pushes, but a contract gate still only protects
that lane when the lane actually invokes it; self-hosted/specialty
workflows remain manual unless separately amended. The substrate's
mechanical-enforcement claim is true *given* invocation. Empirical
worked example demonstrating the old activation gap: slice 3a-1-8f
§B.9 (`c815c703b` reached `main` with an undeclared structural break
because CI was not dispatched). Cross-reference integrity is admitted as capability;
mechanical enforcement ships with `slices/3a-1-8c-cross-reference-
enforcement.md` (registers as a kernel enforcer plugin).
Catalog-membership integrity ships with
`slices/3a-1-8d-catalog-consolidation.md`.

The substrate's commitment is "every governance rule is mechanically
enforced on its own schedule"; V1 is a partial-mechanical state with
named follow-ups for the rest. Procedural enforcement during
bootstrap is structurally bounded — it has a sunset slice — not a
permanent fallback.

### Why runtime negotiation lives at the Resource layer

Capability handshake at session start is a fixed point in time;
between session start and session end, contract version cannot change
without a session restart unless the substrate provides a
runtime-continuous channel. The substrate's runtime-continuous
posture admits mid-session contract upgrades as first-class capability
events.

**The mechanism is the Resource layer, not the transport layer.** The
capability handshake (`/infra/capabilities/stream` via
`CapabilitiesChangeRegistry`) is already a multi-frame SSE Resource:
the initial frame is the bulk handshake snapshot; subsequent frames
carry mid-session evolution as typed change events on the same
channel. Consumers subscribe to the Resource and react per-Category,
just as they do for any other Resource in the registry. This is the
canonical Resource-layer expression of the substrate's runtime-
continuous commitment.

Per-envelope contract version tagging (the `contractVersion` field
on the universal SSE envelope's payload via slice 436) is preserved
as an **opt-in diagnostic helper** for streams that want self-
describing frames. It is *not* the substrate's primary runtime-
continuous mechanism. Earlier drafts of this ADR named per-envelope
tagging as the mechanism — slice 3a-1-8e's rewrite (2026-05-07)
relocated the role to the Resource layer after primary-source
investigation showed `/infra/capabilities/stream` already supports
multi-frame emission via `CapabilitiesChangeRegistry`. The
per-envelope helper stays because it has legitimate diagnostic uses
(replay logs, trace export, integration test fixtures); its
substrate-tier role downgrades.

Per the substrate's capability-vs-mandate distinction
(`../reference/ui/frontend-kernel/kernel/05-contract-substrate.md` §"Capability vs Mandate"), V1
ships the mechanism with opt-in per-endpoint adoption
(`slices/3a-1-8-wire-contract-architecture.md` Phase 5a); the
Resource-layer mid-session evolution events are scheduled as a
follow-up slice gated on real producers (slice 3a-1-8b runtime
plugins; slice 3a-1-8d Phase 2+ catalog evolution). Mandatory
adoption graduates to a substrate-level commitment only when
workload evidence makes the exercise universal.

## Rejects

- **"Wire contract as one IDL artifact" as the destination
  architecture.** That is ADR-08's framing; it solves axis 1 and leaves
  axes 2-7 scattered. The substrate framing supersedes it.
- **A `modules/api-contract/` Gradle subproject** as the contract's
  home. Couples contract distribution to JVM build; rules out plugin
  authors and alternative-language hosts as first-class consumers.
- **A separate "plugin SDK package" as deferred-future work.** The
  plugin SDK is the contract, projected for plugin authors. Treating
  them as separate artifacts duplicates the bug class.
- **Hand-written per-language validators with `.loose()` masking** to
  absorb additive-field drift. The substrate's emitter declares
  passthrough posture for additive fields by construction; consumers
  don't hand-tune strictness.
- **Catalogs as scattered ownership** (some in SSOT, some in registry
  classes, some in code constants, some in i18n bundles). Catalogs are
  contract artifacts in the substrate.
- **Behavior on generated records** (factory methods, deprecated alias
  accessors, view transformers). Couples the contract to producer-side
  ergonomics and leaks into every consumer language. Behavior is per-
  language companion code; generated records carry data + accessors
  only.
- **Procedural-only governance.** Workflow checklists in CLAUDE.md /
  common-workflows.md don't survive the agent who skips a step. The
  substrate enforces governance by tooling.
- **Static-handshake-at-session-start as the only negotiation
  surface.** Session-bounded version negotiation cannot express mid-
  session contract evolution. The substrate is runtime-continuous.

## Future Agents Must

- **Discharge cross-cutting substrate commitments at the Resource
  layer before the transport layer.** When a substrate commitment
  admits mid-session evolution (runtime-continuous negotiation,
  hot-reload signaling, catalog evolution, plugin lifecycle,
  capability handshake extension), express it as a typed channel on
  an existing or new Resource. Three production-validated channel
  shapes exist at the Resource layer: capability-registration with
  stable per-capability `id` + `type` discriminator (LSP
  `client/registerCapability`); delta-stream with per-resource
  add/remove/modify (Envoy delta xDS); typed-event subscription
  with `type` as primary discriminator (CloudEvents). Select from
  this catalog or document why none fits the workload. New
  bottom-up mechanisms (transport-layer fields, per-stream
  listeners, per-envelope tags) are admissible only when the
  Resource-layer expression provably fails — not as an aesthetic
  preference. The methodology check is **slice-execution.md
  §"Layer-of-discharge check for substrate commitments (Pass-6)"**;
  it belongs in the slice's §A pre-impl pass alongside Pass-4 /
  Pass-5. Reference case: slice 3a-1-8e (rewrite, 2026-05-07) —
  the original framing discharged runtime-continuous negotiation
  at the transport layer (per-envelope `contractVersion` tag);
  Pass-6 would have caught this because
  `/infra/capabilities/stream` already supports multi-frame
  emission. The rewrite relocated the commitment to the Resource
  layer.

## Future Agents Must Not

- Author a wire-format type outside the contract spec. The build will
  refuse Java classes with Jackson wire annotations and TS files
  declaring wire-shaped interfaces outside `api/generated/` once the
  governance enforcement of `slices/3a-1-8-wire-contract-architecture.md`
  Phase 5 lands; until then, the substrate's intent is normative even
  while the enforcement is procedural.
- Add behavior to generated records. Companion projection files (Java
  `*Extensions.java`; TS sibling files) are the only home for factory
  methods, deprecated alias accessors, view transformers, computed
  properties.
- Add a catalog identifier (reason code, operation id, severity value,
  i18n key) without going through the catalog-Category spec. Cross-
  reference integrity check fails at CI.
- Bump a contract version with a classification that doesn't match
  the structural diff. Renames committed as patches, additive required
  fields committed as patches, etc., fail the CI gate.
- Treat the substrate's plugin-SDK-as-contract identity as optional.
  A plugin SDK package distinct from the contract artifact is the
  bug class this ADR exists to prevent.
- Author a Gradle subproject as the contract's home. The contract is
  language-neutral; emitter modules are per-target, not per-author-
  language.
- Design out the *capability* of per-envelope contract version tagging
  from a new wire envelope. The mechanism must be admitted (the
  envelope's payload schema permits the field; the emitter generates
  the tag-and-validate helper). Per-endpoint adoption is opt-in by
  default, gated by whether cross-version reads are possible for that
  consumer; mandatory adoption follows workload evidence per the
  substrate's capability-vs-mandate discipline.
- Re-encode wire types in plugin code. Plugins consume the contract's
  TS + Zod projection.
- Constrain a discriminator field to a closed catalog set at the
  contract spec's *enforcement* layer (whichever the format provides:
  protovalidate `in:`, JSON Schema `enum`, etc.). Discriminators are
  free-string at the enforcement layer; the closed set is documented
  in the corresponding catalog Category for reference. Per the
  axis-interaction note in `../reference/ui/frontend-kernel/kernel/05-contract-substrate.md`
  §"What A Contract Carries", catalog membership and forward-compat
  conflict on discriminator fields; production rule favors
  forward-compat. (Reference: `0040-wire-contract-format.md`
  + slice 3a-1-8 §B.3 surfaced this from live verification.)

## Revisit When

- A contract Category needs declaration that the seven-axis frame
  cannot honestly carry. (Speculative; would constitute a substrate
  proposal under `04-shape-governance.md` §"Vocabulary Governance.")
- The format spike in `slices/3a-1-8-wire-contract-architecture.md`
  Phase 1 surfaces a sixth axis (or proves one of the seven is
  degenerate for the wire-Category instance). A follow-up ADR records
  the refinement.
- A multi-language port (Rust / Go / Swift FE; or a backend
  reimplementation) materializes and stresses the substrate's
  distribution posture. The "language-neutral by construction"
  property is testable when there's a real second consumer language.
- The plugin ecosystem's V2+ runtime-loadable plugin model surfaces
  a need the V1 statically-compiled plugin pattern doesn't (e.g.,
  per-plugin contract version pinning, signed contract artifacts).
- The runtime-continuous negotiation posture proves more disruptive
  in practice than the static-handshake model the substrate replaces
  (e.g., consumers rebinding mid-session is more expensive than a
  session restart). Recorded as `slices/3a-1-8e-...` if a slice
  materializes.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/05-contract-substrate.md` (the substrate's normative
  declaration)
- `../reference/ui/frontend-kernel/kernel/01-runtime-contracts.md` (versioning axes — contract
  substrate is a first-class axis; capability handshake reports
  per-Category contract version; per-envelope tagging is admitted as a
  capability on the universal SSE envelope, exercised opt-in per
  consumer)
- `../reference/ui/frontend-kernel/kernel/04-shape-governance.md` (vocabulary-governance authority
  for adding contract Categories)
- `20-systems/07-extensions-renderers.md` (plugin SDK is the contract,
  not a separate package; plugin manifest's `contractVersion`
  machine-checked against host's reported version)
- `0038-wire-contract-source-of-truth.md` (extended by this
  ADR; stays in place as historical context with forward-link header)
- `0031-fe-three-primitives.md` (clarification: primitives are
  domain objects, contracts are their wire-format projections; the
  substrate is the projection machinery, not a fourth primitive)
- `0035-fe-plugin-boundary.md` (plugin SDK identity with the
  contract package; V2+ runtime-loadable plugins consume the same
  artifact V1 statically-compiled plugins consume)
- `slices/3a-1-8-wire-contract-architecture.md` (re-scoped to "wire
  Category instance of the contract substrate")
- `slices/3a-1-8b-contract-distribution.md` (follow-up — separate
  distributable, plugin SDK identity)
- `slices/3a-1-8c-cross-reference-enforcement.md` (follow-up — cross-
  reference integrity at handshake + CI)
- `slices/3a-1-8d-catalog-consolidation.md` (follow-up — scattered
  catalog ownership consolidated)
- `slices/3a-1-3-gen-ts-types.md` (closed slice; tactical stopgap;
  superseded by 3a-1-8 Phase 4 under the substrate framing — slice
  ID unchanged, supersession pointer remains valid)
- `docs/reference/contributing/common-workflows.md` §"Add a field to an API record"
  (procedural workflow remains valid until 3a-1-8 Phase 5 mechanizes
  enforcement; updates land with that phase)
- `TRACEABILITY.md` (Concept Coverage row added: "Contract Substrate")
- `CONFLICT-LEDGER.md` (`R-007` reframe entry: ADR-08 extended by
  ADR-09)

## Source Evidence

- `slices/3a-1-3-gen-ts-types.md` §B.A + §B.B (the closed-slice
  closure analysis that surfaced the seven-axis structure: §B.A's
  "wire shape doesn't encode required-ness" is axis 2; §B.B's
  "broken Zod claim was overstated, structural concern remains" is
  the recognition that ADR-08 framed the right problem at the wrong
  scope).
- `slices/3a-1-3-gen-ts-types.md` §B.C through §B.E (typescript-
  generator behavior surfaces — `@JsonTypeInfo(visible=true)`,
  `Date` vs ISO-8601 string, plugin classloader pivot — that
  demonstrate single-tool emitter assumptions don't extend across
  consumer languages without per-tool workaround code; the substrate
  isolates these as per-emitter concerns).
- Session 2026-05-05 deep architectural critique (in this rewrite
  cycle): produced the seven-axis frame; theorized contract substrate
  as architectural primitive; identified plugin-SDK / contract
  identity; identified runtime-continuous negotiation; identified
  governance-by-tooling.
- `slices/444-resource-category-substrate.md` §B.1-§B.4 (the
  precedent: typed-Category-within-primitive substrate move that this
  ADR generalizes from Resource to Contract).
- `60-migration-history/06-resource-category-rewrite.md` (the
  methodology lesson: implementing-agent verification surfaced what
  spec-author self-validation missed; same pattern applies to ADR-08
  → ADR-09).
- The retired **421-extensibility** tempdoc (the original framing of
  plugins as first-class consumers; this ADR realizes that framing
  structurally via plugin-SDK / contract identity).
- The retired **421-data-plane** tempdoc, §"Anti-patterns" (the original
  "one primitive per backend subsystem" prohibition that this ADR honors
  via the typed-Category-within-substrate pattern). Both deleted in the
  421-folder retirement (tempdoc 572); see git history.

## Alternatives Considered

The implementing slice's Phase 1 spike picks a contract format
(OpenAPI 3.1 + JSON Schema 2020-12 / TypeSpec / Protobuf with JSON
mapping / Java records as DSL / fifth-option contender) for the wire
Category. Per ADR-08 and per user direction 2026-05-05, this ADR does
not pre-commit on format. Format choice is a tooling concern; the
substrate commitment is to the seven-axis content and the distribution
posture, not to any specific format. A follow-up ADR (e.g.,
`0040-wire-contract-format.md` or numbered separately)
records the format choice if it warrants ADR-grade documentation.

The substrate-as-architecture commitment is explicitly NOT contingent
on the format choice. Even if Phase 1's format spike fails to satisfy
all seven axes within bounds and downscales (per the slice's kill-
switch), the substrate framing still holds — what changes is which
axes ship in V1 vs follow-up slices, not whether the substrate is the
right architectural primitive.

Three substrate alternatives considered and rejected:

1. **No substrate; ADR-08 stands as written.** Solves axis 1; leaves
   axes 2-7 scattered. Re-introduces the bug class within the next 1-2
   substrate-extension slices.
2. **Substrate as part of the registry-primitive primitive itself**
   (Operation/Resource/Prompt acquire a "wire-projection" sub-axis).
   Conflates domain objects with their projections. The substrate is
   distinct machinery; primitives produce wire-shaped data, the
   substrate keeps that data's projection honest.
3. **Substrate as a per-Category set of independent specs with no
   uniform machinery.** Each contract Category gets its own ad-hoc
   evolution / distribution / governance pattern. The bug class
   re-emerges per-Category; the substrate's value is the uniform
   pattern that prevents it.

## Amendment 2026-09-02: narrowed to the one Category that exists

Re-examined under decision-review lane B (tempdoc 884), outcome **narrowed**.

The Decision section generalizes to "every cross-language agreement the system publishes — wire
protocol, plugin SDK, catalog set, registry primitive serialization, persistence formats". The
substrate register tells a smaller story: `contracts/registry.v1.json` `/categories` has **exactly
one entry**, `wire` (`specDir: contracts/wire`, `format: protobuf`, `versionFile:
contracts/wire/VERSION`, four declared axes). There is no `plugin-sdk` Category, no `catalog`
Category, no registry-serialization or persistence Category.

**The substrate framing is retained** — one authority per contract, projection-not-fork,
language-neutral spec, per-target emitters, mechanical governance. That framing is what
ADR-0038 needed generalized and it is what the `wire` Category actually instantiates.

**The claim is narrowed to `wire`.** As of this amendment, ADR-0039 decides the substrate shape
for the one registered Category and nothing else. The plugin-SDK, catalog and
registry-serialization Categories named in the Decision section were **never registered**; they
are aspiration, not decided architecture. (ADR-0041 designed the catalog Category's *format* and
is itself partly superseded by tempdoc 564; neither has a registry entry.) Do not cite this ADR
as authority for how a plugin-SDK or catalog contract must be shaped — that decision has not
been made.

### Reopening trigger

**A second entry appearing in `contracts/registry.v1.json` `/categories` reopens this ADR.**
Probe `adr-0039-one-contract-category` pins the count at 1, so the addition fails the
`adr-coverage` gate: the Category cannot be accepted until this ADR has been re-examined and
the generalization either re-established with evidence or re-narrowed. That is the intended
order — decide first, register second.
