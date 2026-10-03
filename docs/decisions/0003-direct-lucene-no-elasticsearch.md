---
title: "ADR-0003: Use Lucene Directly Without Search Platform"
type: decision
status: stable
description: "Interact with Lucene API directly instead of Elasticsearch or Solr."
date: 2026-02-03
probes:
  - adr-0003-lucene-direct
  - adr-0003-no-search-platform
last_reviewed: 2026-09-02
---

# ADR-0003: Use Lucene Directly Without Search Platform

## Status

Accepted

## Context

JustSearch needs a full-text search engine that supports keyword search (BM25), vector search (HNSW embeddings), and hybrid retrieval. The standard options are:

1. Wrap a search platform (Elasticsearch, OpenSearch, Solr) that manages Lucene internally.
2. Use Lucene's Java API directly (`IndexWriter`, `IndexSearcher`, `MMapDirectory`).

JustSearch is a **desktop application** targeting consumer hardware. It runs entirely on the user's machine with no server infrastructure. The search index lives on local disk alongside the application.

Key constraints:

- **Footprint:** Desktop users won't accept a 500MB+ JVM process for a search platform running in the background.
- **Startup time:** Elasticsearch takes 10-30 seconds to start. Desktop apps need sub-second search availability.
- **File locking control:** On Windows, MMap files are OS-locked. We need precise control over which process opens index files and when they're unmapped (see [ADR-0001](0001-three-process-architecture.md)).
- **Schema control:** We enforce a strict SSOT field catalog (`FieldCatalogDef`) with validated field mappings. Search platforms add their own schema management layer that would conflict.

## Decision

Use Apache Lucene 10 directly via its Java API. The Engine's index half is the sole owner of Lucene index files. All search, indexing, and schema management are implemented in `modules/adapters-lucene`.

Key implementation choices:

- `MMapDirectory` for memory-mapped file access (fast reads, OS-managed caching).
- `FieldMapper` for strict schema enforcement against the SSOT catalog.
- Custom codec (`JustSearchCodec`, base codec `Lucene104Codec`) with `Lucene99HnswVectorsFormat` for vector search (768-dim, Float32).
- Generation-scoped index layout for blue/green schema migration support.
- Custom commit strategy (time-based + size-based + shutdown triggers).

## Consequences

**Positive:**

- Minimal footprint: no separate search server process, no cluster coordination overhead.
- Fast startup: the Worker opens the index directory directly — search is available as soon as the JVM starts.
- Full control over file locking: we control when `IndexWriter.close()` and `Directory.close()` are called, ensuring Lucene releases MMap handles before directory operations on Windows.
- Schema is code-controlled: `FieldCatalogDef` is the single source of truth, validated at startup by `LuceneIndexRuntime.validateIndexableFields()`.
- Direct access to Lucene internals enables custom commit strategies, backpressure, and corruption recovery that would be hidden behind a search platform's abstraction.

**Negative:**

- No built-in distributed search, replication, or cluster management. Acceptable for a single-user desktop app.
- Must implement our own schema migration (blue/green generation system), commit strategy, corruption recovery, and backpressure — features that Elasticsearch provides out of the box.
- Lucene API is low-level and changes between major versions. Upgrades require reviewing API changes across the entire `adapters-lucene` module.
- No admin UI or REST API for index inspection (we built custom debug endpoints in the Local API instead).

## Alternatives Considered

### Embedded Elasticsearch (historical process terminology)

Run Elasticsearch as an embedded library within the historical index process.

**Rejected because:** Elasticsearch explicitly removed embedded mode in 5.x. Even when it existed, the resource footprint (heap, threads, cluster state management) was designed for server workloads, not desktop apps. The minimum heap recommendation is 1GB — unacceptable for a desktop search tool.

### Embedded Solr

Run Solr in embedded mode (`EmbeddedSolrServer`).

**Rejected because:** Solr's embedded mode is technically supported but poorly maintained. It still carries the full Solr stack (Jetty, ZooKeeper client, collection management) which adds hundreds of MB to the distribution. Schema management would conflict with our SSOT `FieldCatalogDef` approach.

### SQLite FTS5

Use SQLite's full-text search extension for keyword search, with a separate vector store.

**Rejected because:** FTS5 lacks HNSW vector search, requiring a second storage system for embeddings. BM25 ranking in FTS5 is less sophisticated than Lucene's (no field-level boosting, no configurable similarity). The split storage would double the schema management and migration complexity.

See also: [Storage Engine](../explanation/04-storage-engine.md) for full Lucene implementation details.

## Reassess When

- An embeddable search library offers native hybrid retrieval (BM25 + vector) under 200 MB footprint, eliminating the need to implement search primitives directly against Lucene's low-level API.
- JustSearch moves to a client-server model where a hosted search backend is acceptable, removing the desktop footprint constraint.

*Added by tempdoc 269 trigger audit (2026-03).*
