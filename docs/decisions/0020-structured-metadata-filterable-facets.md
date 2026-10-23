---
title: "ADR-0020: Structured Metadata Fields as Filterable Facets"
type: decision
status: stable
description: "Extend entity faceting to structured metadata with meta_ prefix, typed proto fields, and two-stage chunk retrieval."
date: 2026-03-27
probes: none - the meta_ field convention is already owned by the SSOT catalog sync gate; a second probe over the same catalogs would be a duplicate authority.
last_reviewed: 2026-10-20
---

# ADR-0020: Structured Metadata Fields as Filterable Facets

## Status
Accepted

Current implementation corrections are recorded in the 2026-10-20 amendment below.

## Context

Documents may contain structured metadata (source, author, category, published date) extracted from YAML frontmatter or Tika properties. In the agent retrieval eval (tempdoc 346), 14 out of 20 agent reflections requested metadata filtering as their primary pain point. Agents had to keyword-search for publication names like "The Verge" and manually inspect each result's frontmatter, wasting turns and tokens. A structured filter like `source:"The Verge"` would be trivial.

ADR-0007 established entity faceting over a full knowledge graph. The entity faceting pattern (NER extraction, Lucene `StringField` indexing, proto-typed filter fields, facet count aggregation) was proven and performant. Metadata fields have similar requirements — filterable, facetable, type-safe — but differ in extraction method (frontmatter parsing vs NER) and normalization semantics (case-insensitive keywords vs entity-linked strings).

The forces at play:
- Agent and user search needs structured filtering for document provenance.
- Entity facets (ADR-0007) proved the indexing and query pattern.
- Metadata fields include both keyword types (source, author, category) and numeric types (published date as epoch milliseconds).
- Chunk-level search in the RAG path must respect document-level metadata without storing metadata on every chunk.

## Decision

Extend ADR-0007's entity faceting pattern to structured metadata with the following design:

1. **Field naming**: `meta_` prefix convention for metadata fields (distinct from `entity_` prefix for NER-extracted entities). Fields defined in the SSOT field catalog (`fields.v1.json`).
2. **Normalization**: `toLowerCase(Locale.ROOT)` applied at both index time and query time for keyword metadata fields. This ensures case-insensitive matching without analyzers.
3. **Proto typing**: Typed proto fields in `SearchRequest` (not generic `MetadataFilter { field, value }`). Each metadata field has its own repeated string field (e.g., `repeated string meta_source`, `repeated string meta_author`) or typed range fields (e.g., `int64 published_after`, `int64 published_before`).
4. **Numeric indexing**: `long` fields (like `meta_published_at_ms`) are dual-indexed as `LongPoint` (for BKD range queries) + `NumericDocValuesField` (for facet aggregation), wrapped in `IndexOrDocValuesQuery` for optimizer flexibility.
5. **Two-stage chunk retrieval**: In the RAG context path (`retrieve-context`), metadata and entity filters are never applied to chunks directly. Instead: (a) pre-search parent documents matching the filters to get doc IDs, (b) scope chunk search to those parent doc IDs. This is necessary because metadata fields are stored only on parent documents, not on chunks.

## Consequences

**Positive:**
- Consistent pattern with entity facets — same indexing, querying, and facet aggregation machinery.
- Type-safe proto API enables compile-time contract validation and clear evolution path.
- Efficient numeric range queries via BKD trees (LongPoint) for date filtering.
- Case-insensitive keyword matching without custom analyzers.
- Agent eval showed infrastructure ceiling of 100% accuracy with ~1 API call per filter-eligible query (vs 6-23 turns without filters).

**Negative:**
- Two-stage retrieval in the RAG path adds complexity and an extra search round-trip for filtered context requests.
- Metadata filters are not stored on chunks — this is an architectural constraint that must be maintained. Storing metadata on chunks would bloat the index and complicate update semantics.
- Agent adoption depends on model capability and tool description quality (Opus discovers filters from descriptions; Haiku does not reliably).

## Alternatives Considered

### Generic MetadataFilter proto message
Use a generic `message MetadataFilter { string field = 1; string value = 2; }` instead of typed fields. Rejected because it loses type safety (date ranges become stringly-typed), makes proto contract evolution harder (no field-level deprecation), and pushes validation from compile time to runtime. The small number of metadata fields (4 initially) doesn't justify the abstraction overhead.

### Store metadata on chunks too
Index all metadata fields on both parent documents and their chunks, eliminating the two-stage retrieval. Rejected because it bloats the chunk index (each chunk duplicates all parent metadata), complicates update semantics (metadata change requires re-indexing all chunks), and creates consistency risks (parent and chunk metadata could diverge).

### Reuse entity fields for metadata
Treat metadata values as entities and use the existing `entity_` field infrastructure. Rejected because metadata and entities have different normalization semantics (metadata uses `toLowerCase(Locale.ROOT)` for exact keyword matching; entities use NER-linked normalization), different extraction pipelines (frontmatter parsing vs NER model inference), and different UI presentation needs.

## Amendment 2026-10-20: current typed filter carriers

The original flat-field examples are historical. In
`modules/ipc-common/src/main/proto/indexing.proto`, `SearchRequest` carries a
`SearchFilters` message. `SearchFilters` and `RetrieveContextRequest` declare
`meta_source`, `meta_author`, `meta_category` and a `TimeRangeMs meta_published_at`;
the timestamp range is not a pair of flat `published_after`/`published_before`
fields. Under [ADR-0049](0049-one-engine-jvm-and-the-boundaries-that-survive.md),
these protobuf messages remain the in-process index-port vocabulary, without
a gRPC service or separate Worker process. The typed-filter and parent-before-
chunk retrieval decisions stand.
