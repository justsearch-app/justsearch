/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing;

import java.util.Set;

/** Shared schema field identifiers used across indexing components. */
public final class SchemaFields {
  private SchemaFields() {}

  // Document identity fields
  public static final String DOC_ID = "doc_id";
  public static final String DOC_UID = "doc_uid";
  public static final String PATH = "path";
  public static final String FILENAME = "filename";
  public static final String COLLECTION = "collection";
  /**
   * Tempdoc 585 §D Phase 4 (D4b) — the reserved collection tag for indexed agent-run transcripts.
   * Search excludes this collection by default (so transcripts don't pollute normal document search)
   * and includes it only when a request explicitly scopes to it (the "Agent history" scope).
   */
  public static final String AGENT_HISTORY_COLLECTION = "agent-history";

  // Content fields
  public static final String CONTENT = "content";
  /**
   * SHA-256 (lowercase hex) of this parent document's stored {@link #CONTENT} — its CONTENT
   * REVISION (tempdoc 931 §C.6).
   *
   * <p>Identity ({@code doc_uid}) answers "which document is this"; this answers "which version of
   * it". Feedback captures the pair, so a label collected against one revision can be recognised as
   * stale after the file is edited instead of silently training on text that no longer exists.
   * Stored-only: it is projected to the Head, never queried or sorted on. The digest is the same
   * one {@link io.justsearch.indexing.chunking.ChunkParentRevision} defines for
   * {@link #CHUNK_PARENT_CONTENT_SHA256}, so parent and chunk revisions are directly comparable.
   */
  public static final String CONTENT_SHA256 = "content_sha256";

  /** Small stored preview used for result list snippets (bounded length). */
  public static final String CONTENT_PREVIEW = "content_preview";
  public static final String TITLE = "title";

  // Author / sender fields (populated from Tika metadata for email and Office docs)
  public static final String AUTHOR = "author";

  // Metadata fields
  public static final String SIZE_BYTES = "size_bytes";
  /** SHA-256 of the exact source bytes from which the stored extraction was produced. */
  public static final String SOURCE_SHA256 = "source_sha256";
  /** Source-owned no-file projection witness; absent from file documents. */
  public static final String PROJECTION_SOURCE_ID = "projection_source_id";
  public static final String PROJECTION_SOURCE_REVISION = "projection_source_revision";
  public static final String PROJECTION_DIGEST = "projection_digest";
  public static final String MODIFIED_AT = "modified_at";
  public static final String INDEXED_AT = "indexed_at";
  public static final String CREATED_EPOCH_MS = "created_epoch_ms";
  public static final String LANGUAGE = "language";
  public static final String MIME = "mime";
  /** Base mime without parameters (e.g. "text/plain" from "text/plain; charset=UTF-8"). */
  public static final String MIME_BASE = "mime_base";
  /** UX-oriented file type bucket for filtering (e.g. pdf|markdown|image|code|text|office|archive|binary|unknown). */
  public static final String FILE_KIND = "file_kind";
  /** SPLADE-token count of the parent document content, used for ranking-time length modulation. */
  public static final String PARENT_TOKEN_COUNT = "parent_token_count";
  public static final String VECTOR = "vector";
  public static final String HARD_DELETE = "_hard_delete";
  public static final String SOFT_DELETE = "_soft_delete";
  public static final String SOFT_DELETE_TS = "_ts";
  public static final String SOFT_DELETE_ORDINAL = "_ordinal";

  // AI/Embedding fields
  /** Embedding generation status: "PENDING", "COMPLETED", or "FAILED" */
  public static final String EMBEDDING_STATUS = "embedding_status";

  /** Embedding status values */
  public static final String EMBEDDING_STATUS_PENDING = "PENDING";
  public static final String EMBEDDING_STATUS_COMPLETED = "COMPLETED";
  public static final String EMBEDDING_STATUS_FAILED = "FAILED";

  /** Embedding retry count for poison pill protection */
  public static final String EMBEDDING_RETRY_COUNT = "embedding_retry_count";
  public static final int EMBEDDING_MAX_RETRIES = 3;

  // VDU (Vision Document Understanding) fields
  /**
   * VDU processing status: "PENDING", "PROCESSING", "COMPLETED", "COMPLETED_EMPTY", "REJECTED",
   * "FAILED", or "NOT_NEEDED"
   */
  public static final String VDU_STATUS = "vdu_status";

  /** VDU status values */
  public static final String VDU_STATUS_PENDING = "PENDING";
  public static final String VDU_STATUS_PROCESSING = "PROCESSING";
  public static final String VDU_STATUS_COMPLETED = "COMPLETED";
  public static final String VDU_STATUS_COMPLETED_EMPTY = "COMPLETED_EMPTY";

  /**
   * Tempdoc 677: the abstention gate judged the extraction untrustworthy, either because VDU ran
   * and returned non-empty text that failed a post-call confidence check (suspected confabulation
   * on an unreadable input), or because the input-legibility gate skipped the model call entirely
   * (no page carried any textual signal at all). Baseline content was RETAINED either way —
   * honest absence instead of confident fabrication. Terminal like COMPLETED_EMPTY (no re-queue;
   * poison-pill discipline).
   */
  public static final String VDU_STATUS_REJECTED = "REJECTED";

  public static final String VDU_STATUS_FAILED = "FAILED";
  public static final String VDU_STATUS_NOT_NEEDED = "NOT_NEEDED";

  /** VDU retry count for poison pill protection */
  public static final String VDU_RETRY_COUNT = "vdu_retry_count";
  public static final int VDU_MAX_RETRIES = 3;

  /** Flag indicating document was processed by VDU (value: "true") */
  public static final String VDU_PROCESSED = "vdu_processed";

  /** VDU-extracted enrichment data (JSON with summary, entities, doc_type) */
  public static final String VDU_ENRICHMENT = "vdu_enrichment";

  /** Number of pages processed by VDU */
  public static final String VDU_PAGE_COUNT = "vdu_page_count";

  /** Why a document is queued for VDU. Absent when VDU is not needed. */
  public static final String VDU_DEMAND_KIND = "vdu_demand_kind";
  public static final String VDU_DEMAND_KIND_BASELINE_TEXT = "baseline_text";
  public static final String VDU_DEMAND_KIND_VISUAL_ENRICHMENT = "visual_enrichment";

  // RAG Chunking fields (Phase 1.5)
  /** Indicates this is a chunk document, not a full document. */
  public static final String IS_CHUNK = "is_chunk";
  /** Parent document ID for chunk documents. */
  public static final String PARENT_DOC_ID = "parent_doc_id";
  /** Chunk index within the parent document (0-based). */
  public static final String CHUNK_INDEX = "chunk_index";
  /** Total number of chunks in the parent document. */
  public static final String CHUNK_TOTAL = "chunk_total";
  /** Chunk content (searchable text). */
  public static final String CHUNK_CONTENT = "chunk_content";
  /** Start character offset of this chunk within the parent document's extracted content (0-based). */
  public static final String CHUNK_START_CHAR = "chunk_start_char";
  /** End character offset (exclusive) of this chunk within the parent document's extracted content (0-based). */
  public static final String CHUNK_END_CHAR = "chunk_end_char";
  /**
   * SHA-256 (lowercase hex) of the parent {@link #CONTENT} revision this chunk was cut from
   * (tempdoc 931 §C.1). {@code chunk_content} is not stored, so a read-modify-write on a chunk
   * re-slices it out of whatever parent content the current searcher shows; parent write and chunk
   * regeneration are separate calls, so a same-length rewrite in between would silently produce
   * wrong chunk text. This is the revision identity that makes the mismatch detectable.
   */
  public static final String CHUNK_PARENT_CONTENT_SHA256 = "chunk_parent_content_sha256";

  // Chunk navigation fields (F8 Tier 2: Citation UX - In-Document Navigation)
  /** Start line number of this chunk within the parent document (1-based). */
  public static final String CHUNK_START_LINE = "chunk_start_line";
  /** End line number of this chunk within the parent document (1-based). */
  public static final String CHUNK_END_LINE = "chunk_end_line";
  /** Nearest preceding markdown heading text (empty if N/A or not markdown). */
  public static final String CHUNK_HEADING_TEXT = "chunk_heading_text";
  /** Heading level 1-6 for markdown, 0 otherwise. */
  public static final String CHUNK_HEADING_LEVEL = "chunk_heading_level";

  // Chunk embedding fields (Phase 6)
  /** Vector embedding for chunk content (768-dimensional). */
  public static final String CHUNK_VECTOR = "chunk_vector";
  /** Chunk embedding generation status: "PENDING", "COMPLETED", or "FAILED" */
  public static final String CHUNK_EMBEDDING_STATUS = "chunk_embedding_status";
  /** Chunk embedding retry count for poison pill protection */
  public static final String CHUNK_EMBEDDING_RETRY_COUNT = "chunk_embedding_retry_count";

  // NER Entity fields
  /** Raw person entity names extracted by NER (multi-valued keyword for filter/facet). */
  public static final String ENTITY_PERSONS_RAW = "entity_persons_raw";
  /** Raw organization entity names extracted by NER (multi-valued keyword for filter/facet). */
  public static final String ENTITY_ORGANIZATIONS_RAW = "entity_organizations_raw";
  /** Raw location entity names extracted by NER (multi-valued keyword for filter/facet). */
  public static final String ENTITY_LOCATIONS_RAW = "entity_locations_raw";
  /** NER processing status for this document. */
  public static final String NER_STATUS = "ner_status";
  public static final String NER_STATUS_PENDING = "PENDING";
  public static final String NER_STATUS_COMPLETED = "COMPLETED";

  /**
   * NER ran successfully and extracted zero entities. Terminal like {@link #NER_STATUS_COMPLETED}
   * (no re-queue), but distinguishable, so a document carrying no {@code ENTITY_*} fields is not
   * indistinguishable from an enriched one. Mirrors the {@link #VDU_STATUS_COMPLETED_EMPTY}
   * precedent for the same "stage ran fine, produced nothing" case.
   */
  public static final String NER_STATUS_COMPLETED_EMPTY = "COMPLETED_EMPTY";

  public static final String NER_STATUS_FAILED = "FAILED";
  /** NER retry count for poison pill protection. */
  public static final String NER_RETRY_COUNT = "ner_retry_count";
  public static final int NER_MAX_RETRIES = 3;

  // SPLADE sparse retrieval fields
  /** SPLADE sparse vector field (FeatureField per token). */
  public static final String SPLADE = "splade";
  /** SPLADE processing status for this document. */
  public static final String SPLADE_STATUS = "splade_status";
  public static final String SPLADE_STATUS_PENDING = "PENDING";
  public static final String SPLADE_STATUS_COMPLETED = "COMPLETED";

  /**
   * SPLADE encoded successfully but produced no materialisable weight (an empty map, or one whose
   * every weight is &lt;= 0 — {@code FieldMapper.addFields} only emits a {@code FeatureField} for a
   * strictly-positive weight). Terminal like {@link #SPLADE_STATUS_COMPLETED} (no re-queue), but
   * distinguishable, so a document carrying zero {@code splade} postings is not indistinguishable
   * from an encoded one. Mirrors the {@link #VDU_STATUS_COMPLETED_EMPTY} / {@link
   * #NER_STATUS_COMPLETED_EMPTY} precedents for the same "stage ran fine, produced nothing" case.
   *
   * <p>Why not {@code COMPLETED}: that token asserts an artifact exists, and the write-time
   * status/artifact contract (tempdoc 798) rejects the claim. Why not {@code FAILED}: nothing
   * failed, and FAILED is a poison-pill signal. Being neither COMPLETED nor null also means the RMW
   * {@code reset-status} lane preserves it instead of resetting to PENDING and zeroing the retry
   * counter — the livelock cycle a data-less COMPLETED reopens.
   */
  public static final String SPLADE_STATUS_COMPLETED_EMPTY = "COMPLETED_EMPTY";

  public static final String SPLADE_STATUS_FAILED = "FAILED";
  /** SPLADE retry count for poison pill protection. */
  public static final String SPLADE_RETRY_COUNT = "splade_retry_count";
  public static final int SPLADE_MAX_RETRIES = 3;

  // Frontmatter metadata fields (filterable/facetable keyword + date fields)
  /** Source publication from frontmatter (keyword, lowercased at index time). */
  public static final String META_SOURCE = "meta_source";
  /** Author from frontmatter (keyword, lowercased at index time). */
  public static final String META_AUTHOR = "meta_author";
  /** Category from frontmatter (keyword, lowercased at index time). */
  public static final String META_CATEGORY = "meta_category";
  /** Publication date from frontmatter (epoch millis). */
  public static final String META_PUBLISHED_AT = "meta_published_at";

  // Extraction provenance fields
  /** Extraction method used to produce the content (e.g. TIKA_STRUCTURED, VDU). */
  public static final String EXTRACTION_METHOD = "extraction_method";
  public static final String EXTRACTION_METHOD_TIKA_FLAT = "TIKA_FLAT";
  public static final String EXTRACTION_METHOD_TIKA_STRUCTURED = "TIKA_STRUCTURED";
  public static final String EXTRACTION_METHOD_OCR_TIKA = "OCR_TIKA";
  public static final String EXTRACTION_METHOD_VDU = "VDU";

  /**
   * No extraction tier produced usable text for this document (tempdoc 790). The document is still
   * indexed — with its metadata, its path, and whatever the last tier returned — but the content is
   * an acknowledged hole rather than a silently empty "successful" extraction. Paired with {@code
   * IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED} in {@link #EXTRACTION_REASON_CODE}.
   */
  public static final String EXTRACTION_METHOD_NONE = "NONE";
  /** Numeric quality score (0.0-1.0) of the extracted text. */
  public static final String EXTRACTION_QUALITY_SCORE = "extraction_quality_score";
  /** Compact JSON explaining the OCR/VDU routing decision for this document. */
  public static final String VISUAL_EXTRACTION_EVIDENCE = "visual_extraction_evidence";

  // Tempdoc 410 §11 — search-time honesty fields. Tolerated as null on existing indices;
  // documents indexed after this slice carry the full provenance shape.
  /**
   * Trust/provenance status of the parser output that produced this document. Mirrors
   * {@link io.justsearch.indexerworker.extract.ExtractionStatus} values:
   * {@code SUCCESS_FULL} | {@code SUCCESS_PARTIAL} | {@code FAILED} | {@code TIMED_OUT} |
   * {@code BUDGET_EXCEEDED}. Null for documents indexed before tempdoc 410 V1.
   */
  public static final String EXTRACTION_STATUS = "extraction_status";

  /**
   * True when the parser output exceeded the policy's max-extracted-chars cap and was truncated
   * before being indexed. Distinguishes "the file is short" from "we cut the tail off."
   */
  public static final String CONTENT_TRUNCATED = "content_truncated";

  /**
   * Stable Worker reason code for the outcome that produced this document. Matches the
   * constants in {@code io.justsearch.indexerworker.ingest.IngestionReasonCodes}; primarily
   * non-null for SUCCESS_PARTIAL / failed-but-indexed paths.
   */
  public static final String EXTRACTION_REASON_CODE = "extraction_reason_code";

  /**
   * Identifier of the {@code TikaExtractionPolicy} active when this document was extracted.
   * Lets eval and diagnostics stratify by policy version.
   */
  public static final String EXTRACTION_POLICY_ID = "extraction_policy_id";

  /**
   * Identifier of the parser implementation that produced this document
   * (e.g. {@code tika-policy-structured}, {@code sandbox-child}).
   */
  public static final String EXTRACTION_PARSER_ID = "extraction_parser_id";

  /**
   * Number of embedded resources observed during recursive parsing (0 for non-archive,
   * non-recursive parses). Helps identify documents whose content originates from many small
   * embedded files.
   */
  public static final String EMBEDDED_RESOURCE_COUNT = "embedded_resource_count";

  /**
   * Number of non-fatal parser warnings produced while extracting this document. Distinguishes
   * "extracted cleanly" from "extracted with caveats Tika reported." Zero is the common case;
   * non-zero is the actionable signal for eval and diagnostics.
   */
  public static final String PARSER_WARNINGS_COUNT = "parser_warnings_count";

  // ========== Schema Validation ==========

  /**
   * Fields that IndexingLoop writes to documents.
   * Used for startup validation to catch missing catalog entries.
   *
   * <p>This set should contain all fields that are written during indexing.
   * If a field is written but not in this set, it won't be validated.
   * If a field is in this set but not in the SSOT catalog, startup will fail.
   */
  public static final Set<String> INDEXABLE_FIELDS = Set.of(
      // Document identity
      DOC_ID,
      DOC_UID,
      PATH,
      FILENAME,

      // Content
      CONTENT,
      CONTENT_SHA256,
      CONTENT_PREVIEW,
      TITLE,

      // Metadata
      MIME,
      MIME_BASE,
      FILE_KIND,
      LANGUAGE,
      PARENT_TOKEN_COUNT,
      SIZE_BYTES,
      SOURCE_SHA256,
      PROJECTION_SOURCE_ID,
      PROJECTION_SOURCE_REVISION,
      PROJECTION_DIGEST,
      MODIFIED_AT,
      INDEXED_AT,

      // Embedding
      EMBEDDING_STATUS,
      VECTOR,

      // VDU
      VDU_STATUS,
      VDU_DEMAND_KIND,

      // Chunking
      IS_CHUNK,
      PARENT_DOC_ID,
      CHUNK_INDEX,
      CHUNK_TOTAL,
      CHUNK_CONTENT,
      CHUNK_START_CHAR,
      CHUNK_END_CHAR,
      CHUNK_PARENT_CONTENT_SHA256,

      // Chunk navigation (F8 Tier 2)
      CHUNK_START_LINE,
      CHUNK_END_LINE,
      CHUNK_HEADING_TEXT,
      CHUNK_HEADING_LEVEL,

      // Chunk embedding (Phase 6)
      CHUNK_VECTOR,
      CHUNK_EMBEDDING_STATUS,
      CHUNK_EMBEDDING_RETRY_COUNT,

      // NER entity extraction
      ENTITY_PERSONS_RAW,
      ENTITY_ORGANIZATIONS_RAW,
      ENTITY_LOCATIONS_RAW,
      NER_STATUS,
      NER_RETRY_COUNT,

      // SPLADE sparse retrieval
      SPLADE,
      SPLADE_STATUS,
      SPLADE_RETRY_COUNT,

      // Extraction provenance
      EXTRACTION_METHOD,
      EXTRACTION_QUALITY_SCORE,
      VISUAL_EXTRACTION_EVIDENCE,
      EXTRACTION_STATUS,
      CONTENT_TRUNCATED,
      EXTRACTION_REASON_CODE,
      EXTRACTION_POLICY_ID,
      EXTRACTION_PARSER_ID,
      EMBEDDED_RESOURCE_COUNT,
      PARSER_WARNINGS_COUNT,

      // Frontmatter metadata
      META_SOURCE,
      META_AUTHOR,
      META_CATEGORY,
      META_PUBLISHED_AT
  );
}
