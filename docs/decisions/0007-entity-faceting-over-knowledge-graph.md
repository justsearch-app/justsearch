---
title: "ADR-0007: Entity Faceting Over Full Knowledge Graph"
type: decision
status: stable
description: "Selective adoption of NER entity extraction for Lucene keyword facets with SQLite sidecar disambiguation, deferring full knowledge graph."
date: 2026-01-22
updated: 2026-02-11
probes:
  - adr-0007-entity-boost-retired-or-off
last_reviewed: 2026-10-20
---

# ADR-0007: Entity Faceting Over Full Knowledge Graph

## Status
Accepted. Current implementation paths are listed in the 2026-10-20 amendment.

## Context

A comprehensive knowledge graph architecture was proposed for JustSearch, including GLiNER for Named Entity Recognition, REBEL for relation extraction, SQLite + JGraphT for graph storage, fastcoref for coreference resolution, and cross-document entity linking. The full stack would require ~5-7 GB VRAM, slow indexing by 5-10x (from ~200-400ms to ~2-4s per document), and introduce a new storage layer alongside Lucene.

JustSearch targets personal file search. Personal document collections have sparse entity overlap (few entities appear across multiple documents), diverse content (photos, receipts, notes, PDFs), and implicit relationships rarely worth extracting programmatically. The embedded graph database ecosystem is also immature — Kuzu was abandoned in October 2025, Neo4j Community has GPLv3 concerns, and Apache AGE has deployment complications unsuitable for desktop.

The system needed to decide between full knowledge graph implementation and a lighter alternative that captures the highest-value feature (entity-filtered search) without the complexity.

## Decision

Use **selective adoption**: NER entity extraction stored as **Lucene keyword fields** (`entity_persons_raw`, `entity_organizations_raw`, `entity_locations_raw`) with a **SQLite sidecar** for cross-document entity disambiguation — no separate graph database.

**Two-track NER model strategy**: v1 shipped with `dslim/distilbert-NER` (ONNX, EN-only), now superseded by `Davlan/distilbert-base-multilingual-cased-ner-hrl` (10 langs, tempdoc 343). v2 evaluates GLiNER Medium v2.1 (166M params, ONNX, zero-shot custom entity types, unproven on JVM). The sidecar architecture is model-agnostic — only the NER inference module changes between v1 and v2.

This delivers entity-filtered search (filter results by person, organization, location) and entity faceting (browse entity counts in filter panel). Raw NER spans are stored in Lucene `SortedSetDocValues`; canonical entity mappings live in SQLite sidecar tables (`entity_clusters` + `entity_overrides`), merged at query time. Processing overhead is ~80-220ms per 500-token chunk (est., bert-base-NER on CPU). The approach fits existing ONNX infrastructure (already used for reranker) and existing UI facet patterns (`file_kind`, `language` facets).

Entity IDs are indexed from the start, providing an incremental path: if users later demand relationship queries, relationship fields can be added without re-architecting.

## Consequences

**Positive:**
- ~80% of user value (entity-filtered search) at ~20% of complexity
- Lightweight storage: raw spans in Lucene `SortedSetDocValues`, cluster mappings in SQLite sidecar (same `jobs.db`)
- NER model uses ONNX Runtime, reusing existing infrastructure from the reranker
- 1.3-2x indexing slowdown est. (vs 5-10x for full graph)
- ~0.5-1 GB VRAM (vs 5-7 GB for full graph), CPU fallback available
- v1 (bert-base-NER) ships with zero new dependencies; v2 (GLiNER) adds zero-shot custom entity types
- Sidecar disambiguation: cross-document entity merging without Lucene rewrites
- Incremental: entity IDs indexed now can support relationships later

**Negative:**
- No relationship queries ("Who works with John Smith?")
- No cross-document relationship extraction
- No graph visualization or path discovery
- Entity disambiguation quality measured on CoNLL-2003 test: PER F1=0.927, ORG F1=0.813, LOC F1=0.898 (see F-011 in `docs/reference/search-quality-register.md`)
- Entity faceting is implemented and active. NER runs during ingestion backfill; entity facets appear after the 10% coverage threshold is met.

**Key files:**
- Schema: `SSOT/catalogs/fields.v1.json` — `entity_persons_raw`, `entity_organizations_raw`, `entity_locations_raw` fields (implemented)
- NER inference: `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/ner/BertNerInference.java`
- NER backfill: `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/loop/NerBackfillOps.java`
- Entity disambiguation: `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/ner/DisambiguationService.java`
- SQLite cluster store: `entity_clusters` + `entity_overrides` tables in `jobs.db`
- Entity facets: `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FacetingEngine.java`
- Full analysis: retained in historical design notes; this ADR is the current canonical decision.

**Generalization note:** The entity faceting pattern (keyword field + `SortedDocValuesField`) has been extended to structured document metadata fields (`meta_source`, `meta_author`, `meta_category`) in tempdoc 362, establishing it as a generalized indexing pattern for faceted filtering.

## Alternatives Considered

### Alternative A: Full Knowledge Graph
GLiNER + REBEL + fastcoref + SQLite/JGraphT graph storage. Enables relationship queries, entity profiles, path discovery, and graph visualization. **Rejected** — 5-10x indexing slowdown, ~5-7 GB VRAM (exceeds typical 8 GB consumer GPUs when combined with LLM), embedded graph DB ecosystem risk, maintenance burden (disambiguation, orphan cleanup, schema evolution), and limited value for sparse personal document collections. Doubles system complexity for features most users won't use.

### Alternative B: No entity extraction
Keep search purely keyword/vector-based with no entity awareness. **Rejected** — entity faceting adds meaningful filtering value ("show all docs mentioning Dr. Smith") at low complexity cost. The infrastructure (ONNX, Lucene keyword fields) already exists.

### Reconsideration criteria
Revisit full knowledge graph if: >20% of users request relationship queries, a stable Apache 2.0 embedded graph DB emerges, JustSearch targets enterprise/team use cases, or hardware improvements make 2-4s/doc acceptable.

Added 2026-09-02: the retirement of the `entity_*_text` boost fields is owned by **decision-review lane D**, not by this ADR's criteria above. Lane D decides the field deletion; this ADR keeps the facet decision. See the amendment below.

## Amendment 2026-09-02: facets live, `entity_*_text` boost fields retired by lane D

Re-examined under decision-review lane B (tempdoc 884), outcome **still true, now probed**.

The decision this ADR makes — entity extraction as **Lucene keyword facets**, no graph database —
shipped and is live. Nothing below changes it.

What has changed since 2026-01 is the *other* half of the entity surface: the ICU-analyzed
**boost** fields `entity_persons_text` / `entity_organizations_text` / `entity_locations_text`,
which fed a search-side score boost rather than a facet filter. Decision-review lane D split their
retirement into two safe steps. PR-C0 removed the functional query/configuration path; PR-C2 removes
the physical fields and their writers in the fingerprint-moving bundle:

- `TextQueryOps` now constructs only the `content` + `title` + `author` disjuncts; it has no entity
  query builder or boost resolver.
- `justsearch.search.entity_boost` / `JUSTSEARCH_SEARCH_ENTITY_BOOST` are no longer configuration
  inputs. The public `entityBoost` status property remains as an always-zero compatibility
  tombstone; protobuf field 9 is not deleted or reused.
- PR-C2 removed the three `entity_*_text` fields and their writers. The physical catalog change
  moves `index_fingerprint` and is rebuilt through the blue/green migration path.
- Evidence-span NER membership now consumes the retained `entity_*_raw` values directly; entity
  filtering, faceting, disambiguation, and delivery carriage remain unchanged.

The `entity_*_raw` keyword fields named in the Decision section are unaffected: they are the
facet fields, and faceting is the shipped decision.

### The probe spans the migration

`adr-0007-entity-boost-retired-or-off` is an `any-of` probe with two alternatives — the functional
configuration/query machinery is absent, **or** the `entity_*_text` fields are gone from
`SSOT/catalogs/fields.v1.json`. C0 satisfied the first; C2 satisfies both. It fails only if query
machinery returns while the fields exist. The compatibility tombstone is intentionally not scanned
because its continued presence protects the public wire contract.

`any-of` was added to the probe engine for this case
(`scripts/governance/gates/adr-coverage/probes.mjs`). It is for a premise that is true for two
different reasons across a planned migration — never a way to give a failing probe a second chance.

## Amendment 2026-10-20: current entity implementation paths

The earlier Key files list predates the module split. NER inference is now
`modules/worker-core/src/main/java/io/justsearch/indexerworker/ner/BertNerInference.java`;
disambiguation is `modules/worker-core/src/main/java/io/justsearch/indexerworker/disambiguation/DisambiguationService.java`;
backfill is `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/NerBackfillOps.java`.
The field-9 compatibility tombstone is `entity_boost` in
`modules/ipc-common/src/main/proto/indexing.proto`; under
[ADR-0049](0049-one-engine-jvm-and-the-boundaries-that-survive.md) this is an
in-process DTO vocabulary, not evidence of a surviving Worker RPC transport.
