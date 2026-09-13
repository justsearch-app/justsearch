/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.services.LanguageUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkIds;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical writer for chunk documents (RAG).
 *
 * <p>This centralizes chunk indexing so all write paths (index loop, VDU live update,
 * VDU replay) produce consistent chunks (offsets, sizing, metadata).
 */
public final class ChunkDocumentWriter {

  public static final int CHUNK_THRESHOLD_CHARS = ChunkSplitter.CHUNK_THRESHOLD_CHARS;
  public static final int CHUNK_TARGET_TOKENS = ChunkSplitter.DEFAULT_CHUNK_TOKENS;
  public static final int CHUNK_OVERLAP_TOKENS = ChunkSplitter.DEFAULT_OVERLAP_TOKENS;
  public static final int CONTENT_PREVIEW_MAX_CHARS = ChunkSplitter.CONTENT_PREVIEW_MAX_CHARS;

  private ChunkDocumentWriter() {}

  /**
   * Parent-document metadata a chunk inherits at write time.
   *
   * <p>{@code collection} (tempdoc 811 item 3) is what lets the default agent-history exclusion
   * bind on the chunk branch at all: {@code QueryFilterBuilder.buildChunkFilterQuery} filters
   * chunks on the same {@code collection} field the whole-doc legs use, and a chunk that does not
   * carry its parent's tag is invisible to that clause.
   */
  public record ParentChunkMetadata(
      String mime,
      String mimeBase,
      String fileKind,
      String language,
      Long parentTokenCount,
      String collection,
      String parentDocUid) {}

  /**
   * Regenerates chunk docs for a parent doc by loading metadata from the existing parent document.
   *
   * <p>This is used by VDU update/replay paths where we don't have a {@code Path} or
   * {@code ExtractionResult} but want chunks to inherit metadata such as mime/file_kind.
   *
   * @param chunkSpladeEnabled see {@link #regenerateChunks}; read from the LIVE resolved config at
   *     the call site, never a constructor-time copy
   */
  public static int regenerateChunksFromExistingParent(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator,
      String parentDocId, String content, boolean chunkSpladeEnabled) {
    if (documentFieldOps == null) {
      return 0;
    }
    String mime = documentFieldOps.getDocumentField(parentDocId, SchemaFields.MIME);
    String mimeBase = documentFieldOps.getDocumentField(parentDocId, SchemaFields.MIME_BASE);
    String fileKind = documentFieldOps.getDocumentField(parentDocId, SchemaFields.FILE_KIND);
    String parentTokenCountRaw =
        documentFieldOps.getDocumentField(parentDocId, SchemaFields.PARENT_TOKEN_COUNT);
    // Tempdoc 811 item 3 — inherit the parent's collection tag so the chunk branch can scope on it.
    String collection = documentFieldOps.getDocumentField(parentDocId, SchemaFields.COLLECTION);
    String parentDocUid = documentFieldOps.getDocumentField(parentDocId, SchemaFields.DOC_UID);

    boolean isMarkdown = "markdown".equalsIgnoreCase(fileKind);
    String preview = LanguageUtils.contentPreview(content, CONTENT_PREVIEW_MAX_CHARS, isMarkdown);
    String language = LanguageUtils.resolveLanguage(preview);
    Long parentTokenCount =
        parentTokenCountRaw == null || parentTokenCountRaw.isBlank()
            ? null
            : Long.parseLong(parentTokenCountRaw);

    return regenerateChunks(
        documentFieldOps,
        indexingCoordinator,
        parentDocId,
        content,
        new ParentChunkMetadata(
            mime, mimeBase, fileKind, language, parentTokenCount, collection, parentDocUid),
        chunkSpladeEnabled);
  }

  /**
   * Regenerates chunk docs for a parent doc with explicit metadata.
   *
   * <p>Existing chunks are deleted before replacement. A deletion failure propagates so the caller
   * cannot report successful regeneration while stale chunks remain searchable.
   *
   * @param chunkSpladeEnabled {@code rag.chunk_splade.enabled} (tempdoc 712 / 931 §E item 8). When
   *     false the chunk carries NO {@code splade_status} at all rather than PENDING: every backfill
   *     lane refuses chunk SPLADE while the flag is off, so a PENDING stamp would be a permanent
   *     claim of outstanding work for a stage that will never run — and it sits in every
   *     field-carrying denominator ({@code IndexCountOps#countWithField}) forever. Absence is the
   *     post-798 "this stage does not apply to this document" encoding.
   */
  public static int regenerateChunks(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator,
      String parentDocId, String content, ParentChunkMetadata meta, boolean chunkSpladeEnabled) {
    if (documentFieldOps == null || indexingCoordinator == null
        || parentDocId == null || parentDocId.isBlank()) {
      return 0;
    }

    if (content == null || content.length() < CHUNK_THRESHOLD_CHARS) {
      indexingCoordinator.deleteChunksForParentDocId(parentDocId);
      return 0;
    }

    // F2: Use MIME-based mode selection for CSV/JSON chunking awareness
    ChunkSplitter.Mode mode = ChunkSplitter.Mode.fromMimeOrFileKind(
        meta != null ? meta.mimeBase : null,
        meta != null ? meta.fileKind : null);
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, CHUNK_TARGET_TOKENS, CHUNK_OVERLAP_TOKENS, mode);
    if (chunks.size() <= 1) {
      indexingCoordinator.deleteChunksForParentDocId(parentDocId);
      return 0;
    }
    if (meta == null || meta.parentDocUid == null || meta.parentDocUid.isBlank()) {
      throw new IllegalStateException(
          "A persisted parent document identity is required before chunk indexing");
    }

    // Validate every prerequisite for replacement before deleting the currently searchable
    // chunks. A transient parent-identity read failure must fail closed without data loss.
    indexingCoordinator.deleteChunksForParentDocId(parentDocId);

    // ChunkSplitter offsets are now relative to the original content (including leading whitespace).
    // Tempdoc 931 §C.1: every chunk carries the identity of the parent revision it was cut from, so
    // an RMW that re-slices chunk_content out of a LATER parent revision is detectable instead of
    // silently producing wrong text. Hashed once per parent, not once per chunk.
    String parentContentRevision = ChunkParentRevision.sha256Hex(content);
    int indexed = 0;
    for (ChunkSplitter.Chunk chunk : chunks) {
      String chunkContent = chunk.content();
      if (chunkContent == null || chunkContent.isBlank()) {
        continue;
      }

      Map<String, Object> fields = new HashMap<>();
      String chunkId = ChunkIds.newChunkDocId();
      fields.put(SchemaFields.DOC_ID, chunkId);
      fields.put(SchemaFields.DOC_UID, meta.parentDocUid + "#" + chunk.index());
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, parentDocId);
      fields.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunk.index()));
      fields.put(SchemaFields.CHUNK_TOTAL, String.valueOf(chunks.size()));
      fields.put(SchemaFields.CHUNK_CONTENT, chunkContent);
      int absoluteStartChar = chunk.startChar();
      int absoluteEndChar = chunk.endChar();
      fields.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(absoluteStartChar));
      fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(absoluteEndChar));
      fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, parentContentRevision);
      fields.put(SchemaFields.PATH, parentDocId);

      // F8 Tier 2: Line numbers (1-based)
      // endChar is exclusive [startChar, endChar), so use endChar-1 for the inclusive end line
      int startLine = ChunkOffsetMath.calculateLineNumber(content, absoluteStartChar);
      int endLine = ChunkOffsetMath.calculateLineNumber(content, Math.max(0, absoluteEndChar - 1));
      fields.put(SchemaFields.CHUNK_START_LINE, String.valueOf(startLine));
      fields.put(SchemaFields.CHUNK_END_LINE, String.valueOf(endLine));

      // Heading context: extract from Markdown and structured-extracted content.
      // The findPrecedingHeading() regex matches "## heading" markers which appear in both
      // native Markdown and StructuredDocument.toAnnotatedText() output (tempdoc 252 Tier 1).
      boolean hasHeadingMarkers =
          (meta != null
              && ("markdown".equalsIgnoreCase(meta.fileKind)
                  || "pdf".equalsIgnoreCase(meta.fileKind)
                  || "office".equalsIgnoreCase(meta.fileKind)));
      if (hasHeadingMarkers) {
        ChunkOffsetMath.HeadingInfo heading =
            ChunkOffsetMath.findPrecedingHeading(content, absoluteStartChar);
        fields.put(SchemaFields.CHUNK_HEADING_TEXT, heading.text());
        fields.put(SchemaFields.CHUNK_HEADING_LEVEL, String.valueOf(heading.level()));
      } else {
        fields.put(SchemaFields.CHUNK_HEADING_TEXT, "");
        fields.put(SchemaFields.CHUNK_HEADING_LEVEL, "0");
      }

      if (meta != null) {
        if (meta.mime != null && !meta.mime.isBlank()) {
          fields.put(SchemaFields.MIME, meta.mime);
        }
        if (meta.mimeBase != null && !meta.mimeBase.isBlank()) {
          fields.put(SchemaFields.MIME_BASE, meta.mimeBase);
        }
        if (meta.fileKind != null && !meta.fileKind.isBlank()) {
          fields.put(SchemaFields.FILE_KIND, meta.fileKind);
        }
        if (meta.language != null && !meta.language.isBlank()) {
          fields.put(SchemaFields.LANGUAGE, meta.language);
        }
        if (meta.parentTokenCount != null) {
          fields.put(SchemaFields.PARENT_TOKEN_COUNT, meta.parentTokenCount);
        }
        // Tempdoc 811 item 3 — the chunk carries its PARENT's collection tag, so a collection
        // scope (and the default agent-history exclusion) applies to chunks as well as documents.
        if (meta.collection != null && !meta.collection.isBlank()) {
          fields.put(SchemaFields.COLLECTION, meta.collection);
        }
      }

      fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());

      // Initialize chunk embedding status for Phase 6 backfill
      fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");

      // Initialize SPLADE status for Phase 3 backfill — only when the stage applies at all.
      if (chunkSpladeEnabled) {
        fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
      }

      indexingCoordinator.indexSingle(new IndexDocument(fields));
      indexed++;
    }

    return indexed;
  }

  // ========== F8 Tier 2: Line Number & Heading Extraction ==========

}
