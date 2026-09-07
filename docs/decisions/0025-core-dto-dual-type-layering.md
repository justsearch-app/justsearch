---
title: "ADR-0025: Core DTO Dual-Type Layering (gRPC vs REST)"
type: decision
status: stable
description: "Maintain separate core DTOs (gRPC-aligned) and app-api records (REST-aligned) as intentional layering, not duplication."
date: 2026-04-06
probes: none - the dual-type layering is a design intent about which types live where; the module-deps gate already fails a boundary violation, and a probe would restate it.
last_reviewed: 2026-09-02
---

# ADR-0025: Core DTO Dual-Type Layering (gRPC vs REST)

## Status
Accepted

## Context

The `core` module contains DTOs (`Query`, `Result`, `Result.Hit`) that are near-duplicates of `app-api` records (`SearchRequest`, `KnowledgeSearchResponse`). Both type families have the same fields, the same nested records (Filters, TimeRange, Clause, Cursor), and the same defensive-copy patterns. Translation code in `DefaultAppFacade` (Head-to-index-half) and `KnowledgeClient` (index-half-to-Head) maps between them field-by-field.

This has been flagged as potential code smell multiple times. The 377 core module review investigated whether the duplication should be collapsed.

The `core` DTOs serve the internal contract used by the gRPC boundary between Head and Worker (ADR-0001, ADR-0002). They are proto-aligned, stable across process restarts, and used by 5 modules with 21 production imports. The `app-api` records serve the external REST contract exposed to the frontend and agents. They are JSON-aligned, support additive evolution via `@RecordBuilder` (ADR-0022), and include computed fields not present in the internal contract.

The forces at play:
- The gRPC contract and REST contract serve different consumers with different stability guarantees.
- `core` types are the innermost hexagonal ports — they should be minimal and stable.
- `app-api` records evolve more frequently as UI and agent needs change.
- `Result.Hit.doc_id` uses snake_case deliberately — the JSON API, Lucene schema (`SchemaFields.DOC_ID`), and frontend all use `doc_id` as the wire format.

## Decision

Maintain both type families as intentional layering:

1. **`core` DTOs** serve the internal gRPC contract. They are proto-aligned, minimal, and change only when the search semantics change. All 9 production files have been frozen since November 2025 (5 months at time of review) — evidence that the internal contract is stable.

2. **`app-api` records** serve the external REST contract. They are JSON-aligned, annotated with `@RecordBuilder` for fluent construction, and include computed/aggregated fields (e.g., query understanding metadata, pipeline execution details) not present in the internal contract.

3. **Translation** happens in two places: `DefaultAppFacade` (translates `app-api` request types to `core` types for the index-half call) and `KnowledgeClient` (translates `core` result types to `app-api` types for the REST response). This translation is the cost of the layering.

4. **Wire-format naming** (`doc_id` snake_case) is deliberate compatibility — the JSON API, Lucene stored-field schema, and frontend all use `doc_id`. This is not a naming inconsistency but a cross-layer contract.

## Consequences

**Positive:**
- Internal and external contracts evolve independently. Adding a field to the REST response (e.g., `queryUnderstanding`) does not require changing the gRPC proto or the `core` types.
- gRPC schema changes don't force REST API changes — the translation layer absorbs the difference.
- Each type family is optimized for its serialization format (proto field numbering vs JSON key naming).
- The `core` module remains minimal and stable (9 files, 21 imports, 5-month freeze) — a clean hexagonal port.

**Negative:**
- Field-by-field translation code in `DefaultAppFacade` and `KnowledgeClient` is boilerplate that must be maintained.
- Risk of drift if fields are added to one type family but not the other. Mitigated by integration tests that exercise the full request-response path through both translations.
- New developers may perceive the duplication as code smell and attempt to "fix" it by collapsing the types — this ADR documents why the layering is intentional.

## Alternatives Considered

### Single shared type
Use one set of types (either `core` DTOs or `app-api` records) for both gRPC and REST. Rejected because it couples internal and external evolution — a REST-only field addition (e.g., adding `queryUnderstanding` to the search response) would require updating the proto definition, regenerating proto code, and updating the Worker even though the Worker doesn't use the field. The tight coupling would make REST contract changes disproportionately expensive.

### Auto-generate one from the other
Use code generation to derive `app-api` records from `core` DTOs (or vice versa). Rejected because the mapping is not 1:1 — the REST contract has computed fields, different nullability semantics, aggregated sub-objects, and `@RecordBuilder` annotations that don't exist in the internal contract. A code generator would need extensive configuration to handle these differences, and the generated code would be harder to debug than the explicit translation.

### Use proto-generated Java types directly in REST layer
Skip the `core` DTOs and use protobuf-generated Java classes (`SearchRequest`, `SearchResponse` from `indexing.proto`) directly in the REST controllers. Rejected because proto-generated types have builder ergonomics unsuitable for REST serialization (`.newBuilder().setField().build()` chains), immutable by default in ways that conflict with Jackson serialization, and carry proto-specific metadata (`UnknownFieldSet`, `ByteString`) that shouldn't leak into the REST API.
