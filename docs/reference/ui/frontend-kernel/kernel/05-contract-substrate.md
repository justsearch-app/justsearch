---
title: "Frontend kernel — contract substrate"
type: reference
status: partially-superseded
description: "The contract substrate seven axes. Currency: the proto-format realization is superseded by tempdoc 564 (record-as-IDL)."
date: 2026-06-09
---

# Frontend kernel — contract substrate

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.
>
> **Currency:** the contract-substrate *concept* stands, but its protobuf-format realization
> (ADR-0040 / ADR-0041) was **superseded by tempdoc 564** (record-as-IDL; proto demoted to a derived view).


A contract is a first-class architectural object. Every cross-language
agreement the system publishes (wire protocol, plugin SDK, catalog set,
registry primitives, persistence formats) goes through the same substrate.
The substrate's job is to keep these agreements honest over time — across
producer/consumer drift, multi-language ports, plugin authors, and version
evolution.

The wire contract between backend and frontend is one Category in this
substrate. Plugin SDK, catalogs, and registry primitives are other
Categories. Adding a new contract category is a registration in the
substrate, not a new architecture.

**Active substrate Categories (as of 2026-05-06):**

1. **wire-Category** — language-neutral wire contract for backend↔frontend
   communication. Format: protobuf 3 + protovalidate v1.0
   (`../../../../decisions/0040-wire-contract-format.md`). Spec source:
   `contracts/wire/*.proto`. Single VERSION + CHANGELOG.
2. **catalog-Category** — closed-set identifier catalogs referenced by
   wire fields (severity, reason codes, operation ids, etc.). Format:
   protobuf 3 enum + companion metadata message
   (`../../../../decisions/0041-catalog-category-format.md`). Spec source:
   `contracts/catalog/<id>/<id>.proto`. Per-catalog VERSION +
   CHANGELOG (each catalog evolves independently).

Future Categories (plugin-SDK identity per slice 3a-1-8b; cross-reference
declarations per slice 3a-1-8c; registry-primitive serialization formats)
register the same way: a per-Category sub-decision under ADR-09 picks
the format; the substrate's seven-axis frame applies regardless.

## What A Contract Carries

A contract is not just a shape declaration. Every contract carries seven
distinct axes of cross-language agreement. The substrate makes each axis
a first-class artifact:

1. **Shape** — wire JSON / structured payload. Field names, types, unions,
   enums. What an IDL is good at.
2. **Required-ness presence semantics** — "field must be present" vs
   "field optional" vs "field always present, may be null" vs "field
   absent when null." Today encoded by three overlapping mechanisms
   (`@JsonInclude(NON_NULL)`, `Optional<T>`, Jackson serialization
   defaults); the substrate normalizes them into one declaration.
3. **Runtime invariants** — non-shape constraints that the producer
   guarantees. `id` non-blank; `reason` matches a regex; `relatedMetrics`
   immutable. Today these live exclusively in Java compact-constructor
   checks; the substrate emits them as language-symmetric refinements
   (Java compact-constructor check, Zod `.refine()`, future-language
   validator equivalent).
4. **Cross-references** — a field's value indexes into another contract.
   `recoveryOperationId` references the OperationRegistry; `i18nKey`
   references the i18n catalog; `reason` references a reason-code
   catalog. Shape-valid wire payloads can be reference-broken (a
   `recoveryOperationId` referring to no Operation); the substrate
   declares the cross-reference and enforces integrity at handshake +
   CI.
5. **Behavior separation** — generated record types carry data and
   accessors only. Factory methods, deprecated alias accessors, view
   transformers, computed properties live in per-language consumer
   code that imports the generated types. The substrate's architectural
   rule: contract artifacts declare shape + invariants + cross-
   references; behavior is per-language projection code, not part of
   the contract. Where that consumer code lives (a companion file
   alongside the generated record, the calling module's package, a
   separate utility module) is an implementation choice, not a
   substrate commitment.
6. **Evolution rules** — additive optional field is patch; additive
   required field is minor (consumers must update); rename or remove is
   major. Today procedural in `docs/reference/contributing/common-workflows.md` and
   partial in capability-handshake versioning; the substrate enforces
   the rules by structural-diff CI gate on every contract change. The
   contract's git history is the protocol's history.
7. **Catalog membership** — the set of valid identifier values for
   fields like `id`, `reason`, `severity`, `kind`. The wire's
   `id="index.unavailable"` is shape-valid as a string but only
   meaningful relative to the 27-entry health-event catalog. Catalogs
   are themselves contract artifacts in the substrate, with their own
   evolution rules.

A "single source of truth for shape" addresses axis 1. The substrate
addresses all seven.

**Axis interactions.** The seven axes are first-class but not strictly
orthogonal — some combinations interact at the tooling level. The
wire-Category implementation (slice 3a-1-8 Phase 1, recorded in
`../../../../decisions/0040-wire-contract-format.md`) surfaced one specific
coupling: axis 7 (catalog membership) constraints applied to a
discriminator field conflict with axis 6 (forward-compat) — closed-set
membership rejects unknown future variants, which is precisely what
forward-compat needs to admit. The production rule is to NOT enforce
catalog membership at the wire-Category level on discriminator fields;
the closed set is documented in the corresponding catalog Category as
*reference*, with the consumer's emitter handling unknown values via a
fallback variant. Future contract Categories may surface additional
axis couplings; each is documented in the relevant ADR. The substrate
does not prevent these interactions structurally — it surfaces them
honestly so the production rule for each is explicit.

## Substrate Shape

The contract substrate is a uniform pattern with three top-level
components:

- **Spec source.** Language-neutral text files declaring shape +
  invariants + cross-references + evolution rules + catalog membership
  for each contract Category. Format choice (OpenAPI 3.1, TypeSpec,
  Protobuf with JSON mapping, custom DSL) is a tooling concern; the
  substrate commitment is to the seven-axis content, not to any specific
  format.
- **Projection emitters.** Per-target codegens that read the spec source
  and emit (a) idiomatic types, (b) runtime validators with refinements,
  (c) cross-reference integrity helpers, (d) capability-handshake
  declarations. Java, TypeScript, Zod are V1 targets; future-language
  ports add emitters without changing the spec.
- **Governance machinery.** Build-time enforcement that contract types
  exist only via projection (Java check on Jackson wire annotations,
  ESLint rule on TS interfaces matching wire shape patterns); CI
  structural-diff gate on contract changes; capability-handshake
  reporting of every Category's current contract version.

## Distribution Posture

Contract artifacts are language-neutral and **separately distributable**
from any consumer's build. The spec source lives at repo root
(`contracts/<category>/`), versioned independently of any module. Each
projection emitter is *capable* of publishing an artifact appropriate
to its target (Maven jar for Java; npm package for TypeScript; raw
spec download for "any language"). Consumers pin a contract version;
the build refuses to compile against unpinned contracts.

*(V1 exercise: the language-neutral spec source ships in slice
`3a-1-8` Phase 2; per-target publishing + version-pin enforcement +
release-cadence policy ship in slice `3a-1-8b-contract-distribution.md`.
V1 plugins are statically compiled into the bundle and consume the
generated TS directly from `modules/ui-web/src/api/generated/`; the
"plugin SDK is the contract package" identity becomes operationally
visible when V2+ runtime-loadable plugins materialize and consume the
published npm artifact.)*

This posture is what makes plugin authors and alternative-language hosts
first-class consumers. A contract artifact baked into the JVM build
(e.g., as a Gradle subproject) cannot serve those consumers without
re-bundling. The substrate rules out the Gradle-subproject shape
explicitly.

The plugin SDK and the contract package are the same artifact, viewed
through the plugin-author lens. There is no separate "plugin SDK
package"; the SDK is what falls out of treating the contract as a
language-neutral distributable. The plugin manifest types
(`shell-v0/plugin-api/plugin-types.ts`) themselves are a contract
Category in this substrate; their seven-axis Category spec is
authored in slice `3a-1-8b-contract-distribution.md` alongside the
distribution mechanics that make plugin SDK identity operational.

## Runtime Negotiation

The substrate's runtime-continuous commitment is discharged at the
**Resource layer**, not the transport layer. The capability handshake
(`/infra/capabilities/stream` via `CapabilitiesChangeRegistry`) is a
multi-frame SSE Resource: the initial frame reports the current
contract version for every Category in the substrate, and subsequent
frames carry mid-session evolution as typed change events on the
same channel. Mid-session contract upgrades are first-class capability
events on this Resource — consumers subscribe to the Resource and
react per-Category, just as they do for any other Resource in the
registry.

Per-envelope contract version tagging (the `contractVersion` field
admitted on the universal SSE envelope's payload via slice 436) is
preserved as an **opt-in diagnostic helper** for streams that want
self-describing frames (replay logs, trace export, integration test
fixtures). It is *not* the substrate's primary runtime-continuous
mechanism; consumers that need cross-version detection subscribe to
the capability Resource, not to per-envelope tags.

The choice of layer-of-discharge is governed by the slice-execution
methodology's **Pass-6 layer-of-discharge check**
(`docs/reference/contributing/slice-execution.md` §"Layer-of-discharge
check for substrate commitments"). Resource-layer expression is the
default; transport-layer mechanisms are admissible only when the
Resource-layer expression provably fails. Three production-validated
Resource-layer channel shapes (LSP capability-registration, Envoy
delta-stream, CloudEvents typed-event subscription) form the catalog
to select from.

When the contract Resource is exercised, consumers re-validate
per-event. Mismatches degrade gracefully under the LSP soft-fail
discipline (`../../../../decisions/0034-fe-backend-owned-truth.md`,
`01-runtime-contracts.md`). The "session-start handshake"
is the bulk-snapshot frame; mid-session evolution is the per-event
frame stream. Both ride the same Resource — the Resource is the
substrate's negotiation surface.

## Governance Posture

Contract authoring is constrained by tooling, not procedure. The
substrate's governance *capabilities* (per §"Capability vs Mandate"):

- Wire-shaped types may only be declared in the contract spec. The
  build refuses Java classes with Jackson wire annotations
  (`@JsonProperty` on records consumed by REST/SSE controllers,
  `@JsonNaming`, `@JsonTypeInfo` on wire-format sealed types) outside
  the contract projection. ESLint refuses TS files declaring wire-
  shaped interfaces outside `api/generated/`. *(V1 exercise: Phase 5b
  static checks live; mandate live for the wire Category.)*
- Catalog values may only be added through the catalog-Category
  spec. References to identifiers absent from the catalog fail at CI
  cross-reference integrity check. *(V1 exercise: capability admitted;
  not exercised until `slices/3a-1-8d-catalog-consolidation.md` +
  `slices/3a-1-8c-cross-reference-enforcement.md` ship.)*
- Contract-version bumps that mismatch their evolution-rule
  classification (a rename committed as a patch bump, an additive
  required field committed as patch) fail the structural-diff CI gate.
  *(V1 exercise: capability admitted; V1 ships manual CHANGELOG +
  reviewer discipline. **V1.5 live (2026-05-07)**: kernel-mediated
  mechanical structural-diff via `slices/3a-1-8f-governance-runtime.md`
  — the governance kernel composes per-axis enforcer plugins, emits
  SARIF v2.1.0, and cross-validates declared classification ×
  structural diff × VERSION delta as a single truth-table verdict. See
  §"Governance Kernel" below for the substrate primitive.)*
- Plugin manifests' declared `contractVersion` is machine-checked
  against the host's reported version per the evolution-rule
  semver-compatibility matrix. *(V1 exercise: Phase 6 ships the
  machine check.)*

Procedural rules in `docs/reference/contributing/common-workflows.md` remain valid
during the substrate's bootstrap. Each capability graduates from
"procedural during bootstrap" to "mechanically enforced" on its own
schedule, per the capability-vs-mandate discipline.

## Governance Kernel

The mechanical-enforcement machinery for the substrate's seven-axis ×
N-Categories matrix lives in **one kernel** at
`scripts/contract-governance/`, not as bespoke per-cell scripts. Slice
`slices/3a-1-8f-governance-runtime.md` ships the substrate; wire ×
Axis 6 is the first registered enforcer.

Three structural primitives:

1. **Category registry** (`contracts/registry.v1.json`, `kind:
   contract-category-registry.v1`) — declares each Category's
   `specDir`, `format`, `version`, `versionFile`, `changelog`,
   `changesetsDir`, baseline strategy, and applicable axes. The
   `externalEnforcers[]` array hosts non-Category enforcer
   invocations (e.g., the `buf breaking` preflight over
   `modules/ipc-common/src/main/proto`, migrated from
   `scripts/architecture/run-buf-preflight-win.ps1`; its registry id
   is still `ipc-grpc-buf-breaking`, a name the directory no longer
   earns — the protos declare messages only since lane F item A14).
2. **Per-axis enforcer plugins** with a uniform interface (see
   `scripts/contract-governance/lib/enforcer.mjs`). Each enforcer is
   a function `(target, options) → EnforcerResult`. The protobuf-
   Axis-6 enforcer wraps `buf breaking` with `GIT_LFS_SKIP_SMUDGE=1`
   and shallow-clone detection. Future axes (Axis 4 cross-references
   per slice 3a-1-8c; Axis 5 ArchUnit/ESLint per §A.9 of 3a-1-8f) plug
   in by registering their enforcer module.
3. **SARIF v2.1.0 (minimal subset) report payload.** Each enforcer
   emits one `runs[]` entry tagged with a distinct
   `tool.driver.name`. The runner concatenates all entries into a
   single SARIF document. GitHub Code Scanning ingests this directly
   via `github/codeql-action/upload-sarif@v3`; multi-tool aggregation
   is built into the SARIF spec.

The Axis-6 truth-table cross-validates declared classification (from
per-PR `<category>/.changesets/*.md` files, changesets-style file
shape) × structural diff (from buf breaking) × VERSION delta as a
single verdict. Misclassifications, undeclared breaks, phantom
version bumps, and insufficient bumps all fail the gate with a
distinct SARIF `ruleId`.

Per-PR classification files (changesets-style, per slice 3a-1-8f
§A.14):

```markdown
---
evolution-rule: rename
---
Renamed `Foo.bar` to `Foo.baz` to match the catalog identifier convention.
```

Allowed `evolution-rule` values: `additive-optional`,
`additive-required`, `enum-value-added`, `rename`, `remove`,
`enum-value-removed`, `enum-value-renamed`, `type-change`,
`package-rename` (post 2026-05-07 §A.10 dry-run; type-change /
enum-value-renamed / package-rename added based on empirical buf
output). Highest-bump-wins aggregation across PR-scope `.changesets/`
files determines the truth-table input.

**Escape hatch posture (V1):** none. The truth-table provides
legitimate paths for every classification. The canonical industry
hatch (e.g., `buf skip breaking` PR label) exists in upstream tooling
because those tools lack classification declarations; we have them.
An `emergency-override` classification value may ship as a follow-up
if real workload friction surfaces post-V1.

**Self-test discipline:** the runner ships a positive + negative
fixture pair (`contracts/_fixtures/governance-{positive,negative}/`)
that runs on every CI invocation via `--self-test`. The negative
fixture deliberately misclassifies a removal as `additive-optional`;
the runner asserts the truth-table catches it. Self-test failure means
the gate machinery itself has rotted, not that real contracts have
broken.

**Activation posture (amended 2026-05-12 per slice 3a-1-8f §B.10):**
the kernel fires only when explicitly invoked. Two invocation paths
ship:

1. **CI**, when a workflow lane explicitly invokes the contract
   governance gate for `contracts/` or IPC-proto changes. ADR-0044
   allows the public hosted CI lane to run on pull requests and pushes;
   self-hosted/specialty workflow activation remains manual unless a
   later ADR changes it.
2. **Local invocation**, `node scripts/contract-governance/run.mjs
   --mode gate --self-test`. Per CLAUDE.md "Verification Workflow"
   step 5, this runs alongside the other per-subject pre-merge
   checks; no wrapper script bundles them. Per slice 3a-1-8f §B.12,
   the prior three-layer wrapper chain (`gate.ps1` →
   `local-agent-gate-win.ps1` → DAG runner) was deleted because the
   inner DAG runner had been broken since 2026-03-16, and reviewing
   whether a "single canonical gate" affordance was structurally
   useful concluded it wasn't.

The substrate's mechanical-enforcement claim is **conditional on
invocation**: the gate catches breaks *when run*. Public hosted CI can
now provide automatic activation for lanes that include it, but any
specialty/manual lane still depends on the operator dispatching it. The
empirical worked example demonstrating the old activation gap (a
contract-touching commit reaching `main` without the gate firing) is
slice 3a-1-8f §B.9 (`c815c703b`).
Operators relying on this substrate should treat `scripts/gate.ps1`
as load-bearing for pre-merge verification of contract changes.

**Convergence:** the V1 standalone `scripts/wire-contract/changelog-check.mjs`
(per slice 3a-1-8 Phase 5b — CHANGELOG-was-touched proxy) is retired
by the kernel; its semantics are subsumed by the
classification-trailer parser + truth-table verdict. The IPC preflight
(`scripts/architecture/run-buf-preflight-win.ps1`) migrates as an
`externalEnforcer` registration; npm-pinned buf becomes the canonical
install.

## Relationship To The Three Primitives

Operations, Resources, and Prompts are registry primitives
(`../../../../decisions/0031-fe-three-primitives.md`). The wire envelopes that carry
their entries — `OperationDescriptor`, `ResourceDescriptor`,
`PromptDescriptor`, the events they produce, the responses to their
invocations — are wire contracts in this substrate.

The substrate does not collapse the primitives into a single
"everything is a contract" model. Primitives are domain objects;
contracts are their wire-format projections. The substrate is the
machinery that keeps the projection honest. Operation/Resource/Prompt
remain the user-facing vocabulary; the contract substrate is the
backstage truth that prevents drift between producer + consumer +
plugin author + alternative-language host.

## Anti-Patterns

- **Treating shape as the whole contract.** The "single source of truth
  for shape" framing solves axis 1 and leaves axes 2-7 scattered. A
  contract artifact that doesn't encode invariants emits a Zod schema
  that disagrees with the producer's compact-constructor check the
  first time a malformed payload is constructed in TS. The substrate's
  seven-axis frame is structural; one axis is not enough.
- **Baking the contract into the producer's build.** A `modules/api-
  contract/` Gradle subproject couples contract distribution to JVM
  release cadence and makes plugin authors second-class consumers. The
  contract is a separate distributable.
- **Conflating plugin SDK and contract.** Treating "plugin SDK package"
  as deferred-future work while the contract substrate ships first
  duplicates the bug class (plugin author re-encodes wire types the
  contract already declares). The plugin SDK *is* the contract,
  projected for plugin authors.
- **Per-envelope hand-tuning of validator strictness.** A consumer that
  sets Zod `.loose()` to mask additive-field drift is treating the
  symptom of axis-6 absence. The substrate's emitter declares
  passthrough as the default posture for additive fields and tightens
  per the spec; consumers don't choose strictness.
- **Catalogs scattered across registry classes, code constants, i18n
  bundles, and SSOT files.** A reason-code catalog whose authoritative
  source is "wherever I grepped last" is the disease. Catalogs are
  contract artifacts; the substrate hosts them with the same machinery
  as wire shapes.
- **Static handshake at session start with no per-envelope tagging.**
  A consumer that negotiates contract version once per session cannot
  detect mid-session upgrades. The substrate's runtime-continuous
  posture admits live contract evolution as a first-class capability
  event.
- **Behavior on generated records.** Factory methods, deprecated alias
  accessors, view transformers on a generated type couple the contract
  to producer-side ergonomics and leak into every consumer language's
  generated output. Generated records carry data + accessors only;
  behavior is per-language consumer code.

## Capability vs Mandate

The substrate's commitments are *capabilities* — what every contract
Category is admitted to do — not *mandates*. Each slice that exercises
a capability decides whether to make that exercise universal
(mandatory for all instances) or opt-in (chosen per-instance based on
workload need). When in doubt, default to opt-in; mandates are
additional architectural commitments that require workload evidence.

The capability/mandate distinction is what keeps the substrate
generative without forcing every consumer to absorb every exercised
primitive. Per-envelope contract version tagging is admitted as a
capability; whether every wire interaction tags is a V1 implementation
choice. Mechanical structural-diff CI gating is admitted as a
capability; whether V1 ships it or downscopes to manual CHANGELOG
discipline is an implementation choice. The `*Extensions.java`
companion-file pattern is admitted as one implementation of axis 5's
behavior-separation rule; the substrate does not mandate it.

Future contract-substrate slices apply this discipline at the slice
boundary: substrate-level docs (kernel, ADR) commit to capabilities;
slice docs decide what V1 mandates and what stays opt-in. Mandates
graduate to substrate-level commitments only when workload evidence
makes the exercise universal.

## V1 Substrate Exercise

The substrate primitive admits seven axes of contract content + three
top-level components (spec source, projection emitters, governance
machinery) + multiple Categories (wire / plugin-SDK / catalog /
registry-serialization / future). V1 ship via slice 3a-1-8 inhabits
a subset; the rest is admitted-and-deferred to named follow-ups. The
exercise/deferral matrix:

| Substrate feature | V1 exercise (slice 3a-1-8) | Deferred to |
| --- | --- | --- |
| Axis 1 — Shape | Live for wire Category | — |
| Axis 2 — Required-ness | Live for wire Category | — |
| Axis 3 — Runtime invariants (Java + Zod symmetric) | Live for wire Category (subject to Phase 1 kill-switch) | 3a-1-8c if kill-switch invoked |
| Axis 4 — Cross-references | Admitted; not exercised | `slices/3a-1-8c-cross-reference-enforcement.md` |
| Axis 5 — Behavior separation | Live for wire Category | — |
| Axis 6 — Evolution rules (mechanical) | Live (V1.5, 2026-05-07): kernel-mediated mechanical structural-diff via `slices/3a-1-8f-governance-runtime.md` — protobuf `buf breaking` + per-PR changeset classification + VERSION-delta truth-table; SARIF v2.1.0 report payload; self-test fixture pair on every CI run | — |
| Axis 7 — Catalog membership | Admitted; wire-spec enum members carry `TODO: migrate to catalog-Category` | `slices/3a-1-8d-catalog-consolidation.md` |
| Spec source language-neutral location | Live (`contracts/wire/`) | — |
| Per-target emitters (Java + TS + Zod) | Live for wire Category | Multi-language emitters when consumer surfaces |
| Per-target publishing (Maven + npm + raw) | Admitted; not exercised | `slices/3a-1-8b-contract-distribution.md` |
| Version pinning enforcement | Admitted; not exercised | `slices/3a-1-8b-contract-distribution.md` |
| Plugin SDK = contract package (operational) | V1 plugins consume generated TS directly (same repo); identity claim is forward-looking | `slices/3a-1-8b-contract-distribution.md` for V2+ runtime-loadable |
| Plugin manifest as contract Category (formal seven-axis spec) | Admitted; not authored | `slices/3a-1-8b-contract-distribution.md` Phase 3 |
| Capability handshake reports per-Category contract version | Live for wire Category (single Category in V1) | Multi-Category extension as catalog/plugin Categories ship |
| Per-envelope contract version tagging | Mechanism live (universal SSE envelope payload + emitter helper); preserved as **opt-in diagnostic helper** per slice 3a-1-8e rewrite (2026-05-07). Substrate-tier role for runtime-continuous negotiation moved to Resource-layer mid-session evolution events on `/infra/capabilities/stream`. | Resource-layer mid-session evolution events: implemented when 3a-1-8b lands real producers (follow-up slice 3a-1-8e-runtime, gated). |
| Wire-shaped types restricted to contract spec (build-time) | Live (ArchUnit + ESLint static checks) | — |
| Cross-reference integrity at handshake + CI | Admitted; not exercised | `slices/3a-1-8c-cross-reference-enforcement.md` |
| Catalog-Category instances (reason / operation / severity / health-event ids / i18n) | Admitted; not authored | `slices/3a-1-8d-catalog-consolidation.md` |
| Wire Category | Live | — |
| Plugin-SDK Category (formal) | Admitted | `slices/3a-1-8b-contract-distribution.md` |
| Catalog Categories | Admitted | `slices/3a-1-8d-catalog-consolidation.md` |
| Registry-primitive serialization Categories | Named; not scheduled | Future slice when workload surfaces |

A reader landing on this kernel doc should NOT assume every listed
substrate property is V1-live. The architectural commitment is the
substrate primitive's *capability* in each row; V1 ship inhabits the
"V1 exercise" column. The "Deferred to" column is the schedule.

## Vocabulary Governance

Adding a new contract Category to the substrate uses the same review
authority as adding a new value to a typed-shape vocabulary
(`04-shape-governance.md` §"Vocabulary Governance"). A new Category
requires:

- a use case demonstrating that existing Categories cannot represent
  the agreement honestly
- declaration of all seven axes for the new Category (or explicit
  justification of which axes are degenerate for it)
- the projection emitters for V1 targets (Java, TS, Zod) ship in the
  same commit as the Category itself
- the capability-handshake handler for the new Category's version is
  registered

Removing or merging Categories follows the deprecation path in
`04-shape-governance.md` §"Deprecating a value."

## Governance Outcome

The substrate is *capable* of preventing the wire-contract drift
class structurally. Each prevention property is exercised on its own
schedule per the capability-vs-mandate discipline:

- **Producer-vs-consumer type drift impossible** — both project from
  the same source. *V1: live for the wire Category once slice 3a-1-8
  Phase 4 ships generated Java + TS + Zod from `contracts/wire/`.*
- **Producer-invariant-vs-consumer-validator drift impossible** — both
  emit from the same invariant spec. *V1: live for the wire Category
  once Phase 4's invariant-symmetry emitter ships (or partial under
  the Phase 1 kill-switch).*
- **Cross-reference rot caught at handshake + CI.** *V1.5: capability
  admitted; exercised when `slices/3a-1-8c-cross-reference-
  enforcement.md` lands.*
- **Silent breaking changes caught at CI.** *V1: manual CHANGELOG
  enforcement + reviewer discipline. **V1.5 live (2026-05-07)**:
  kernel-mediated mechanical structural-diff via
  `slices/3a-1-8f-governance-runtime.md` — see §"Governance Kernel"
  for the substrate primitive.*
- **"Works on FE, breaks plugin SDK" impossible.** *V1: trivially live
  for statically-compiled plugins (same repo, same generated TS); V2+
  for runtime-loadable plugins once 3a-1-8b's distribution mechanics
  ship.*
- **Multi-language port not a deferred-future concern.** *V1:
  capability admitted by language-neutral spec source; first
  alternative-language emitter ships when a real consumer surfaces.*

Each property is a substrate capability. V1 ship inhabits the wire
Category with axes 1-3 + 5 + 6-partial exercised; axes 4 + 7 are
admitted-and-deferred per the named follow-up slices. A future agent
reading this doc as "the substrate's V1 ship" should read the §"V1
Substrate Exercise" subsection below for the exercise/deferral matrix.

What it doesn't fix: the IDL's expressive ceiling. Contracts that are
inherently negotiated (Operation parameters whose schema depends on
runtime registry state) need an escape hatch. The escape hatch will be
where the next decade of structural defects breeds. The substrate
admits this and quarantines the escape hatch behind explicit policy:
escape-hatch-shaped contracts declare it in their spec; consumers
reading them know not to expect compile-time guarantees.
