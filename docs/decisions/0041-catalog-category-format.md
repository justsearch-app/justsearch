---
title: "ADR-0041: Catalog Category format — protobuf enums + companion metadata"
type: decision
status: accepted - format superseded in part by tempdoc 564
description: "The catalog Category of the contract substrate used protobuf enums + a companion metadata message. Superseded in part by tempdoc 564 (proto demoted to a derived view)."
date: 2026-06-09
probes: none - format superseded in part by tempdoc 564; the surviving half is owned by ADR-0039 probes.
last_reviewed: 2026-09-02
---


# ADR-0041: Catalog Category format — protobuf enums + companion metadata

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted **in principle** (catalogs are a first-class contract Category; no parallel hand-authored enums) — but the **proto-as-source realization is superseded in part by tempdoc 564** (record-as-IDL; proto demoted). The Category framing stands; its V1 format follows ADR-0040 into supersession.

**Where the surviving mechanism lives (added 2026-09-02):** the record-as-IDL projection register [`governance/contract-surfaces.v1.json`](../../governance/contract-surfaces.v1.json) and its emitter [`scripts/codegen/gen-wire-schema-types.mjs`](../../scripts/codegen/gen-wire-schema-types.mjs) are what actually projects contract types today; the decisions that still hold are [ADR-0038](0038-wire-contract-source-of-truth.md) (wire contract as a first-class artifact) and [ADR-0039](0039-contract-substrate.md) (the substrate, narrowed 2026-09-02 to the one registered `wire` Category — no catalog Category is registered).

## Decision

The catalog-Category instance of the contract substrate
(`0039-contract-substrate.md`) uses **protobuf 3 enums + a
companion metadata message**, both authored in the same `.proto` file
under `contracts/catalog/<id>/`. The closed-set identifier ergonomics
come from the generated enum; per-entry metadata (label, i18n key,
deprecation status, since-version) comes from a generated lookup table
populated at build time.

Per-target emitters mirror wire-Category exactly:

- **Java**: `protoc-gen-java` generates a `*ReasonCode` enum + a
  `*Catalog` message containing `repeated *Metadata`. Consumers use
  the enum directly (historical Worker-era example:
  `HealthEventReasonCode.WORKER_STARTING`); metadata (historical example)
  lookups go through a generated `*Catalog.lookup(code)` static helper.
- **TypeScript**: `protoc-gen-es` generates a TS enum (numeric values
  per protobuf-es convention) + the catalog descriptor. Closed-set
  type ergonomics on the consumer side; `SchemaName` type for runtime
  validation reuse.
- **Zod / runtime validators**: `@bufbuild/protovalidate` validates
  references against the catalog at deserialization (slice 3a-1-8c
  cross-reference enforcement consumes this).

This ADR is a sub-decision of `0039-contract-substrate.md`.
The substrate's commitments stand regardless of catalog format choice;
this ADR picks the format under which catalog-Category V1 ships.

## Rationale

Three options were considered for catalog-Category format:

### Option A — companion message only (data-only)

Each catalog is a `.proto` file declaring a `<Name>Catalog` message
with `repeated <Name>Entry entries`. No proto enum.

- **Pro**: reuses wire-Category substrate machinery verbatim. Zero new
  infrastructure.
- **Con**: weak consumer-side closed-set type ergonomics. Java
  consumers stay on parallel-authored enums (e.g., the existing
  `LifecycleReasonCode`); drift between catalog spec and Java enum
  is a real risk. TS consumers get a data array, not a type.

This was the working V1 recommendation in slice 3a-1-8d's prior §A
analysis. On critical re-reading: the "drift risk" is exactly the
problem catalog-Category is supposed to solve. Option A's pragmatism
defeats the substrate's purpose. **Rejected.**

### Option B — proto enum with custom enum-value options

Each catalog is a `.proto` file declaring a `<Name>` enum, with
per-value metadata attached via custom enum-value options
(`[(catalog.label) = "...", (catalog.i18n_key) = "...", (catalog.deprecated) = false]`).

- **Pro**: closed-set type ergonomics; metadata is co-located with
  the value declaration.
- **Con**: protobuf custom enum-value options are awkward to query at
  runtime (historical Worker-era descriptor example: `MyEnum.WORKER_STARTING.getValueDescriptor().getOptions().getExtension(Catalog.label)`).
  Custom options also require a separate `.proto` file declaring the
  extension under `google.protobuf.EnumValueOptions`. Adds substrate
  complexity for the metadata-access path.

**Rejected** — the metadata-access verbosity creates a per-callsite
cost that compounds over time.

### Option C — hybrid: proto enum + companion metadata message (CHOSEN)

Each catalog is a `.proto` file declaring BOTH:

#### Historical Worker-era proto enum example

```proto
enum HealthEventReasonCode {
  HEALTH_EVENT_REASON_UNSPECIFIED = 0;
  WORKER_STARTING = 1;
  WORKER_CRASHED = 2;
  // ...
}

message HealthEventReasonMetadata {
  HealthEventReasonCode code = 1;
  string label = 2;
  string i18n_key = 3 [json_name = "i18nKey"];
  bool deprecated = 4;
  string deprecation_reason = 5 [json_name = "deprecationReason"];
  string since = 6;
}

message HealthEventReasonCatalog {
  // Lookup table; one entry per non-UNSPECIFIED code.
  repeated HealthEventReasonMetadata entries = 1;
}
```

The catalog spec also includes a static `Catalog` constant:

```proto
// At authoring time, the catalog file declares the entries inline as
// constants. Codegen emits a helper that returns the populated lookup
// table without runtime parsing.
```

For Java consumers (historical Worker-era example):
- Use `HealthEventReasonCode.WORKER_STARTING` directly (closed-set type
  ergonomics).
- Lookup metadata via `HealthEventReasonCatalogStaticData.lookup(code)`
  (static helper; constant-time HashMap lookup; no descriptor walking).

For TS consumers (historical Worker-era example):
- Use `HealthEventReasonCode.WORKER_STARTING` (TS enum from protobuf-es).
- Lookup metadata via the same static helper, regenerated for TS.

The catalog-Category recipe ships:
- The `.proto` file (enum + metadata message).
- A static-data Java helper class (manually authored at first; potential
  future codegen via post-processor for full automation).
- A static-data TS helper module (same).
- Catalog `VERSION` + `CHANGELOG.md` (per-catalog evolution; mirrors
  wire-Category's per-Category evolution).

- **Pro**: best consumer ergonomics + structural correctness. Catalog
  spec drives Java enum (no drift); FE consumers get closed-set types;
  metadata lookups are constant-time after one-time-load.
- **Con**: per-catalog static-data helper is hand-authored at first
  (~30 lines per catalog). Future codegen reduces this to zero.

## Rejects

- **Option A (companion message only).** Reuses substrate machinery
  but leaves Java consumers on parallel-authored enums; drift between
  catalog spec and Java enum is exactly the bug class catalog-Category
  is meant to eliminate. Pragmatism defeats the substrate's purpose.
- **Option B (proto enum + custom enum-value options).** Closed-set
  ergonomics, but per-callsite metadata access via descriptor
  inspection (`getValueDescriptor().getOptions().getExtension(...)`)
  is verbose and compounds over the catalog's lifetime. Custom-option
  `.proto` extension files add substrate complexity without offsetting
  benefit.
- **Authoring parallel Java enums after a catalog ships.** Once a
  catalog ships under this ADR, the generated proto enum is canonical;
  pre-existing parallel enums (e.g., `LifecycleReasonCode`) migrate
  *to* the catalog, not alongside it.
- **Runtime-mutable catalogs.** Catalog values are build-time constants.
  Plugin-contributed catalog values are a substrate-extension concern
  (slice 3a-1-8b plugin SDK), not a catalog-Category V1 concern.

## What this changes in the substrate

Per `../reference/ui/frontend-kernel/kernel/05-contract-substrate.md` axis 7 (catalog membership):
catalog-Category is now a first-class instance of the substrate.

- File layout: `contracts/catalog/<id>/<id>.proto` + `VERSION` +
  `CHANGELOG.md`. Per-catalog directory mirrors per-wire-Category file
  layout (`contracts/wire/<file>.proto` is one collective Category;
  catalog-Category has multiple instances, each its own directory).
- Codegen: same `:wireGenerate` task is extended (or paired with
  `:catalogGenerate`) to compile catalog protos.
- Discovery: a catalog's existence is part of the wire-Category's
  reference-target manifest (slice 3a-1-8c reads this to validate
  cross-references).

## Future Agents Must Not

- **Add catalog values without bumping `VERSION` + `CHANGELOG`.** Per
  axis 6 evolution rules: additive value = minor; rename = major;
  remove = major. Same as wire-Category.
- **Encode per-value metadata via custom enum-value options.** Use the
  companion metadata message instead. The descriptor-inspection path
  is intentionally not the recommended consumption pattern.
- **Author parallel Java enums.** Once a catalog ships under this ADR,
  the generated proto enum is canonical. Existing parallel Java enums
  (e.g., `LifecycleReasonCode`) get migrated TO the catalog, not
  alongside it.
- **Make the catalog spec mutable at runtime.** Catalog values are
  build-time constants. Adding a new value at runtime (plugin extension)
  is a substrate-extension concern (slice 3a-1-8b plugin SDK), not a
  catalog-Category V1 concern.

## Revisit When

- A future catalog has more than ~50 entries (the static-data helper's
  hand-authoring cost compounds; codegen via post-processor becomes
  worth it).
- A new emitter target (e.g., Rust, Go) demands a catalog-Category
  generator. The static-helper approach should generalize cleanly;
  the post-processor codegen is the same per-target work as the
  wire-Category emitters.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/05-contract-substrate.md` — catalog-Category declared as
  second substrate instance + format reference to this ADR.
- `30-agent-workflows/10-add-catalog.md` (NEW) — recipe for adding a
  new catalog under this format.
- `slices/3a-1-8d-catalog-consolidation.md` — Phase 1 closure references
  this ADR; Phase 2-6 per-catalog migrations follow the recipe.

## Source Evidence

- Slice 3a-1-8d §A.5 documented the three options with prior bias
  toward Option A; this ADR re-evaluates and selects Option C per
  the long-term-better-answer principle.
- Wire-Category precedent at `contracts/wire/health.proto` demonstrates
  the proto enum + companion message pattern (`Severity` enum +
  `Source` message with metadata fields).
- protobuf-es generates idiomatic TS enums (verified at
  `modules/ui-web/src/api/generated/metrics_pb.d.ts:149-169` for
  `RenderHint`).
- protoc-gen-java generates Java enums with `getValueDescriptor()`
  metadata API; the descriptor-inspection cost informed Option B's
  rejection.
