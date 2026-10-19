---
title: "ADR-0038: Wire contract as a first-class artifact"
type: decision
status: accepted - mechanism superseded by tempdoc 564
description: "The FE↔backend wire contract is a first-class, versioned artifact with one authority (since tempdoc 564, the Java record); hand-written per-language mirrors are forbidden and consumer-side types are meant to be generated from it. Migration is incomplete: named mirrors remain as declared exceptions (amended 2026-09-02), and the probe cannot see unmarked ones."
date: 2026-06-09
probes:
  - adr-0038-no-unregistered-fe-mirror
last_reviewed: 2026-09-02
---


# ADR-0038: Wire contract as a first-class artifact

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted **in principle** (one contract authority; no hand-written mirrors) — but the **source-of-truth mechanism is superseded by tempdoc 564**: the canonical source is the Java record (record-as-IDL → JSON-Schema → {TS, Zod}), not a separate spec, and proto is demoted to a derived/gated view. Read this ADR for the framing; read tempdoc 564 for the shipped mechanism.

> **Extended by `0039-contract-substrate.md` (2026-05-05).**
> ADR-09 generalizes this ADR's "wire contract is a first-class artifact"
> commitment into a uniform contract-substrate primitive: every contract
> the system publishes (wire, plugin SDK, catalogs, registry-primitive
> serialization) is a first-class artifact in a shared substrate, with
> the wire contract as one Category. ADR-08 stays in place as historical
> context; its narrower prohibitions (hand-written per-language mirrors,
> implicit Java-as-SoT with codegen on top, treating typescript-generator
> output as destination architecture) remain valid under ADR-09. New
> work consults ADR-09 + `../reference/ui/frontend-kernel/kernel/05-contract-substrate.md` for the
> destination architecture; refers to ADR-08 for the historical framing
> of the wire-contract concern.

## Decision

The wire contract between the JustSearch backend and frontend (and plugin SDK
consumers, and any future multi-language ports) is a first-class artifact in the
repository. Java records, TypeScript interfaces, Zod runtime schemas, and
OpenAPI documentation are all mechanically generated from this artifact.
Hand-written per-language mirrors of the wire contract are forbidden.

The specific contract format (OpenAPI 3.1 / TypeSpec / Smithy / protobuf with
JSON mapping / Java-internal annotation DSL / other) is **deferred to spike work
in `slices/3a-1-8-wire-contract-architecture.md` Phase 1**, and may be recorded
as a follow-up ADR. This ADR commits to the architectural shift; the
implementing slice picks the format after hands-on evaluation against actual
JustSearch wire types (HealthEvent discriminated unions, CapabilitiesView
versioned slots, KnowledgeSearchResponse deep nesting, `@JsonUnwrapped` cases).

This ADR is analogous in shape to `05-plugin-boundary.md`, which committed to
"plugins do not own backend truth" without locking the plugin loader's
implementation format.

## Rationale

The current state has at least three authoritative representations of the same
wire contract: Java records with Jackson annotations (`modules/app-api`),
hand-written TypeScript interfaces (across `modules/ui-web/src/api/domains/*`,
`shell-v0/handshake/capabilities-types.ts`, and store typings), and hand-written
Zod runtime schemas (`modules/ui-web/src/api/schemas.ts`). Each drifts
independently.

Verified failure modes (slice 3a.1.3 read-only investigation, 2026-05-05):

- Backend wire format uses snake_case top-level (`schema_version`,
  `observed_at`, `reason_code`); FE Zod schemas validate against camelCase
  (`schemaVersion`, `observedAt`); `.loose()` parsing silently drops the
  mismatch. **The hand-mirror is already broken in production code.**
- An experiment running `json-schema-to-typescript@15.0.4` over
  `knowledge-status.schema.json` produced 28 fields, every one optional. The
  schema-driven generation path inherits the schema baseline's missing
  `required` arrays + `title` fields, so type safety would be no better than
  the current `.loose()` masking.
- `StatusRecordSchemaTest:60` documents that the schema generator does not
  understand Jackson `@JsonUnwrapped`, so any schema-based pipeline inherits
  a known divergence between schema and runtime shape.

A theoretically correct wire-contract architecture has eight properties (per
session 2026-05-05 architectural critique): contract is a named/versioned
first-class artifact; contract has zero coupling to any consumer's
implementation language; every consumer-side representation is mechanically
generated; contract encodes everything semantically meaningful (field names,
required, discriminator, deprecation, version-since); drift detection at every
boundary; contract evolution is governed (semver); plugin authors are
first-class consumers; contract is human-readable without language-specific
literacy.

The current "Java records as implicit SoT" architecture fails properties 1, 2,
4, 5, 7, and 8. This ADR commits to fixing all of them.

## Rejects

- **Hand-written per-language mirrors of wire types.** TypeScript interfaces +
  Zod schemas matching backend records by hand. Already shipped and already
  drifting silently.
- **Implicit Java-as-SoT with codegen on top.** A code generator that derives
  TS from Java records (whether via JSON Schema intermediate or via reflection)
  is acceptable as a *tactical stopgap* (see `slices/3a-1-3-gen-ts-types.md`)
  but is **not** the destination architecture: it leaks Java + Jackson
  implementation details into the contract surface, and it doesn't make
  plugin authors / future-language ports first-class consumers.
- **Duplicating contract semantics across Jackson runtime + JSON Schema +
  Zod.** Any state where two of these three encode the same naming / required /
  discriminator information independently. The contract carries the semantics
  once; consumers regenerate.

## Future Agents Must Not

- Author new hand-written TypeScript interfaces for wire payloads. New wire
  types go in the contract artifact; the FE consumes generated TS.
- Author new hand-written Zod schemas for wire payloads. Zod is regenerated
  alongside TS once the contract format is implemented.
- Add new Java records to `modules/app-api` after slice 3a.1.8 Phase 3 lands
  without going through the contract layer.
- Ship a plugin SDK that requires plugin authors to re-encode wire types.
  The SDK is generated from the contract.
- Treat the typescript-generator output from slice 3a.1.3 as the destination
  architecture. It is explicitly a stopgap; slice 3a.1.8 supersedes it.

## Revisit When

- The implementing slice's Phase 1 format spike completes; a follow-up ADR
  may record the format choice (`09-wire-contract-format.md` or similar).
- A multi-language port (Rust / Go / Swift FE; or a backend reimplementation)
  is proposed. The contract layer's value compounds with each new consumer.
- The plugin ecosystem's authoring patterns surface specific friction with
  whatever format Phase 1 picks; a format change would be an ADR amendment.
- A wire-format breaking change is needed; the contract's semver evolution
  rules govern the rollout.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/01-runtime-contracts.md` (versioning axes — wire contract
  becomes a first-class axis; capability handshake reports
  `contractVersion`).
- `20-systems/07-extensions-renderers.md` (plugin SDK is a contract
  consumer; plugin manifest's `contractVersion` field validates against
  the contract's semver).
- `slices/3a-1-3-gen-ts-types.md` (rewritten as tactical stopgap pointing
  at this ADR + slice 3a.1.8).
- `slices/3a-1-8-wire-contract-architecture.md` (new — implements this
  ADR via 5 phases).
- `60-migration-history/07-workload-validation-pass-2.md` §"NEW Gap 9" (the
  origin-gap that surfaced this concern; this ADR + slice 3a.1.8 close it).
- `docs/reference/contributing/common-workflows.md` §"Add a field to an API record"
  (workflow updates as 3a.1.3 and 3a.1.8 land).

## Source Evidence

- The retired **426 frontend-rewrite slice-decomposition** tempdoc, §"Stage 1"
  (pre-deletion artifacting + framework prerequisites — the implicit assumption
  that wire contract management is a Stage 1 concern). Deleted in the 421-folder
  retirement (tempdoc 572); see git history.
- Session 2026-05-05 read-only investigation (slice 3a.1.3 research phase):
  verified the broken Zod / `.loose()` masking; confirmed schema baselines
  omit `required` + `title`; ran the json-schema-to-typescript experiment
  showing every-field-optional output; surfaced
  `cz.habarta.typescript-generator` as a strictly-better tactical option
  than json-schema-to-typescript; surfaced `StatusRecordSchemaTest:60`'s
  documented `@JsonUnwrapped` divergence.
- Session 2026-05-05 deep architectural critique: produced the eight
  properties (P1–P8) of a correct wire-contract architecture; theorized the
  contract-as-first-class-artifact design with multi-target codegen.

### Subsequent moderation (added at slice 3a.1.3 closure, 2026-05-05)

The "Verified failure modes" claim above — *"The hand-mirror is already
broken in production code"* — was overstated relative to what's actually
shipping. The per-field audit during slice 3a.1.3 Phase 2 closure
(`slices/3a-1-3-gen-ts-types.md` §B.B) found:

- FE Zod's `ReadinessEnvelopeSchema` validates `schemaVersion` /
  `observedAt` (camel), which **matches** the wire's
  `ReadinessEnvelopeView` (camel — that record has no `@JsonNaming`).
  No drift here.
- The snake_case records (`Lifecycle.reason_code`, `WorkerDebugView`,
  `HealthNodeView`, `DebugMigrationEnumeratorView`, `SignalBusView`)
  are debug-state surfaces. The FE Zod doesn't validate these — they
  pass through `.loose()` without schema enforcement, but the FE
  doesn't read them either.
- The remaining mixed-case fields are camelCase records the FE both
  validates and reads, with field names that match the wire.

The structural correctness concern this ADR addresses (multiple
authoritative representations of the same wire contract) remains real
and load-bearing. The specific runtime bug the original Source
Evidence cited — silent `.loose()` masking of camel-vs-snake drift in
production code paths — is not currently shipping as an FE-consumed
defect.

ADR-09 (`0039-contract-substrate.md`) cites this moderation
in its own Source Evidence as the recognition that ADR-08 framed the
right problem at the wrong scope (one axis of a seven-axis problem).
The architectural commitment of this ADR — wire contract as
first-class artifact, hand-written per-language mirrors forbidden —
stands on the structural argument; the original "already broken in
production" framing should be read as historical context for the
ADR's authoring moment, not as a current-state claim.

## Alternatives Considered

For Phase 1 of the implementing slice, four candidate formats are on the table:

1. **OpenAPI 3.1 + JSON Schema 2020-12.** Ecosystem density (`openapi-typescript`,
   `openapi-zod-client`, `openapi-generator` for Java); evolves the existing
   `victools/jsonschema-generator` work rather than replacing it; OpenAPI is
   owed regardless for plugin-author API documentation. Patterns: Atlassian,
   Shopify.
2. **TypeSpec (Microsoft, Cadl successor).** First-class discriminated unions,
   cleaner syntax than OpenAPI for service-shaped contracts, designed for API
   specs. Younger ecosystem (Microsoft-controlled), fewer multi-language
   emitters today.
3. **Protobuf with JSON mapping.** Mature multi-language emitter ecosystem
   (15+ years; Java, TS, Go, Rust, Python all first-class). Patterns: Square,
   Stripe. Concern: protobuf's JSON mapping is opinionated (camelCase fields,
   specific timestamp/duration encoding) and may collide with current
   snake_case wire format.
4. **Java records as canonical DSL with custom annotations.** Java records
   remain canonical but with project-internal annotations
   (`@WireName` / `@WireRequired` / `@WireDiscriminator` /
   `@WireDeprecatedSince`) driving codegen. Lower infrastructure than IDL;
   weaker on language-agnostic / human-readable / plugin-author-friendly
   axes.

The implementing slice's Phase 1 spike picks one (or surfaces a fifth) based on
hands-on evaluation against actual JustSearch records. Per user direction
2026-05-05, this ADR does not pre-commit.

## Amendment 2026-09-02: the Surface mirror is generated; the remaining five are declared exceptions

Re-examined under decision-review lane B (tempdoc 884). The decision — *hand-written per-language
mirrors are forbidden; every consumer-side type is mechanically generated* — is unchanged and was
**not** being honoured. This amendment records what was fixed, and names, with an owner and a
blocker each, every place where it still is not.

### What the premise probe now checks

`adr-0038-no-unregistered-fe-mirror` scans all 522 non-generated, non-test `.ts` files under
`modules/ui-web/src` for a file that **self-declares** a hand-written mirror in its own header, and
requires each one to be either registered in `governance/contract-surfaces.v1.json` or a declared,
reasoned exception. **Its guarantee is narrower than this ADR's premise, deliberately:** a mirror
that carries no marker and is not in the exception list is invisible to it. Two such were found by
human reading, not by the scan (`api/domains/indexing.ts`, `api/schemas.ts`), and are listed
explicitly for that reason. The probe catches *new* self-declared mirrors and the deletion of the
generation that replaced an old one; it does not prove the absence of mirrors.

### `api/types/surface.ts` — generated, exception dropped

The mirror named in the 2026-09-01 review is retired end to end:

1. `modules/app-agent-api/.../registry/Surface.java` now also implements `PreciseWire`. That marker
   (see its own javadoc) is what makes the emitted schema *precise* — all eleven components
   `required`, non-`Optional` references non-null — instead of the permissive all-optional default.
   It is accurate rather than aspirational: the record's compact constructor `requireNonNull`s or
   defaults every component. Verified that no other committed schema shifted: after regeneration,
   `git status SSOT/schemas/` showed only the new file.
2. `SubstrateSchemaGenTest#surfaceSchema` emits `SSOT/schemas/surface.v1.json` (capture-or-verify;
   `./gradlew.bat :modules:app-api:updateSchemas` rewrites the baseline).
3. `governance/contract-surfaces.v1.json` registers it as `SurfaceWire`, and
   `scripts/codegen/gen-wire-schema-types.mjs` projects it to
   `modules/ui-web/src/api/generated/schema-types/surface.ts`.
4. `modules/ui-web/src/api/types/surface.ts` is now a **composing barrel** over that projection —
   the `ResourceWire` / `OperationWire` pattern already used by `api/types/registry.ts`.

**Why a barrel rather than deletion.** The obvious reading of this ADR — delete the mirror, point
its importers at the generated module — does not survive contact with the file. `surface.ts`
exports a runtime **value** a type projection cannot provide: `PLACEMENTS`, the closed `Placement`
list used for exhaustive switches (and, until tempdoc 930 chunk H retired the static
`check-a11y-closure.mjs` gate that once parsed this exact file by regex, for its landmark-role
coverage check too). (`AUDIENCES` is declared in the same shape at `surface.ts:49` but has **no
consumer** — `grep -rn AUDIENCES modules/ui-web/src` returns only that declaration and the header
comment naming it. It is not a reason to keep the file; it is a retirement candidate for the UI
lane.) The file also exports `SurfaceFactory` — the 478 §4.A dispatch token minted client-side,
with no Java counterpart at all — and `SurfaceCatalog`, the response envelope, whose Java
counterpart is not a *type*: `RegistryController.handleSurfaces` (`:281`) ends in `writeEnvelope`
(`:397-412`), which composes the envelope as an inline `LinkedHashMap`. There is no record for the
emitter to project, which is why the file's own header says the shape "mirrors
`RegistryController.handleSurfaces`" (`surface.ts:237-238`) rather than a Java class. Generating it
would first require the envelope to become a declared type on the Java side.
And the FE `Surface` carries two fields the backend never sends: `factory`, stamped by
the catalog on receipt, and `splitPairing`, a plugin contribution merged client-side with **zero**
Java source. Deleting the file would have relocated those into consumers — turning one honest
composition point into many. The barrel keeps the *field set* mechanically derived from Java while
naming the client-side additions as exactly that.

**The narrowing this ADR should absorb:** "every consumer-side type is mechanically generated" is
right about the wire and wrong about the boundary. A consumer-side type is legitimately the wire
projection **plus** client-side state, and the enforceable rule is that the wire half must be
generated and the client-side half must be *named as client-side*. That is what the barrel does.

### The five that are not fixed, each with an owner and a blocker

All five stay declared exceptions in `governance/adr-probes.v1.json`, each carrying its reason
there. **Owner: the UI lane** — `modules/ui-web` component work is outside decision-review lane B's
scope, and every one of these needs consumer-side changes rather than codegen alone.

| File | Why it is still a mirror |
|---|---|
| `api/types/conversation-shape.ts` | Structurally the same case `surface.ts` just resolved. Its FE consumers carry client-side state the wire does not, so it needs the same barrel treatment — component work, not codegen. |
| `api/types/selection.ts` | **Blocked, not merely unscheduled.** Sealed-sum discriminated unions are not expressible by the emitter (it supports `type`/`properties`/`required`/`items`/`enum`/`const`/`anyOf` and `$ref` into `#/$defs`; it has no discriminator concept). The emitter must be extended first. |
| `shell-v0/handshake/capabilities-types.ts` | Same recipe as `surface.ts` (`CapabilitiesService.CapabilitiesView` is a plain record), but the handshake payload is consumed during boot, before the generated-module graph loads. The ordering question was not investigated. |
| `api/domains/indexing.ts` | The drift runs **backwards**: `ExcludesService.ExcludesResult` mirrors *from* the FE (its javadoc says so). Generating it would invert the authority — a product decision about who owns the excludes contract, not a codegen chore. |
| `api/schemas.ts` | Tempdoc 683 already reduced it to one deliberately permissive `.loose()` agent-session schema. What is left is an intentional fail-open parse boundary; retiring it is a decision about that boundary. |

`shell-v0/utils/aiInstallPoll.ts` is a sixth exception and a **marker false positive** — its header
says it *used to be* a mirror (retired by tempdoc 840 Phase 4). It is listed so the scan stays green
without weakening the regex.

### The other side of the same register

The `contract-projection` gate was failing on `contract-projection/undeclared-consumer`: four FE
files import a generated wire module without being declared consumers. This is the same register
drift, from the other direction, so it is fixed here. Three are genuine imports of
`schema-types/ai-install-status` and are now registered. The fourth,
`shell-v0/controllers/AgentSessionController.ts`, does **not** import anything — its only occurrence
is a doc comment naming the generated file's path, and the gate's check is a raw substring test over
whole file text rather than an import parse. It is registered with that overstatement recorded
verbatim in the register's own `note`, because the alternative was rewording a comment to dodge a
matcher. Making that check parse imports is routed as a tracked item.

### Reassess When

- **A new self-declared mirror appears unregistered** — the probe fails; decide here whether it is
  generable, blocked, or legitimately client-side, and never silence it by deleting the marker.
- **The emitter gains discriminated-union support** — `selection.ts`'s blocker disappears and its
  exception must be re-argued or dropped.
- **An exception's file stops existing** — the probe fails on a stale exception by construction, so
  a deleted mirror forces this list to be re-read rather than rotting.
