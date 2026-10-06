/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionDropoutPolicy;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.services.LanguageUtils;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;

public final class IndexingDocumentOps {
  private static final int CHUNK_THRESHOLD_CHARS = ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS;
  private static final int CONTENT_PREVIEW_MAX_CHARS =
      ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS;
  private static final String DEFAULT_LANGUAGE = resolveDefaultLanguageTag();

  private IndexingDocumentOps() {}

  /** Shared parent-document metadata derived once and reused for parent and chunk writes. */
  public record ParentIndexMetadata(
      String mime,
      String mimeBase,
      String fileKind,
      String language,
      Long parentTokenCount) {

    /**
     * @param collection the parent document's collection tag (nullable) — carried onto the chunk
     *     so the chunk branch can apply collection scopes (tempdoc 811 item 3). It is NOT part of
     *     this record because it is a property of the indexing job, not of the extracted content
     *     {@code deriveParentMetadata} sees.
     */
    public ChunkDocumentWriter.ParentChunkMetadata toChunkMetadata(
        String collection, String parentDocUid) {
      return new ChunkDocumentWriter.ParentChunkMetadata(
          mime, mimeBase, fileKind, language, parentTokenCount, collection, parentDocUid);
    }
  }

  @FunctionalInterface
  public interface StageRecorder {
    void record(String stageId, long durationMs, String reasonCode);
  }

  /** Trusted source metadata captured and validated by the ingestion boundary. */
  public record SourceFileMetadata(
      long sizeBytes,
      long modifiedAtMs,
      String sourcePathHash,
      String sourceSha256,
      String artifactStatus,
      String policyId,
      String parserId,
      boolean truncated,
      int embeddedResourceCount,
      int maxEmbeddedDepth,
      long artifactValidatedAtMs,
      int parserWarningsCount,
      String reasonCode,
      String visualExtractionEvidenceJson) {
    public SourceFileMetadata(long sizeBytes, long modifiedAtMs) {
      this(sizeBytes, modifiedAtMs, null, null, null, null, null, false, 0, 0, 0L, 0, null, null);
    }

    public SourceFileMetadata(long sizeBytes, long modifiedAtMs, String sourceSha256) {
      this(
          sizeBytes,
          modifiedAtMs,
          null,
          sourceSha256,
          null,
          null,
          null,
          false,
          0,
          0,
          0L,
          0,
          null,
          null);
    }
  }

  /**
   * Builds an IndexDocument from a validated extraction artifact.
   *
   * <p>This is the only entry point into document construction. The {@link
   * ValidatedExtractionArtifact} type can only be produced by {@link
   * io.justsearch.indexerworker.extract.ExtractionArtifact#validate(
   * io.justsearch.indexerworker.extract.TikaExtractionPolicy, String)}, so callers cannot reach
   * here with unvalidated parser output.
   *
   * @param precomputedEmbedding if non-null, uses this vector instead of calling embeddingProvider
   */
  public static IndexDocument buildDocument(
      Path filePath,
      ValidatedExtractionArtifact artifact,
      String collection,
      WorkerSignalBus signalBus,
      EmbeddingProvider embeddingProvider,
      boolean allowEmbeddingWrites,
      SpladeEncoder spladeEncoder,
      ParentIndexMetadata parentMetadata,
      StageRecorder stageRecorder,
      Logger log,
      float[] precomputedEmbedding,
      SourceFileMetadata sourceMetadata,
      String docUid) {
    if (docUid == null || docUid.isBlank()) {
      throw new IllegalStateException("A persisted document identity is required before indexing");
    }
    return buildDocumentInternal(
        filePath,
        artifact.result(),
        collection,
        signalBus,
        embeddingProvider,
        allowEmbeddingWrites,
        spladeEncoder,
        parentMetadata,
        stageRecorder,
        log,
        precomputedEmbedding,
        withArtifact(sourceMetadata, artifact),
        docUid);
  }

  private static IndexDocument buildDocumentInternal(
      Path filePath,
      ExtractionResult extraction,
      String collection,
      WorkerSignalBus signalBus,
      EmbeddingProvider embeddingProvider,
      boolean allowEmbeddingWrites,
      SpladeEncoder spladeEncoder,
      ParentIndexMetadata parentMetadata,
      StageRecorder stageRecorder,
      Logger log,
      float[] precomputedEmbedding,
      SourceFileMetadata sourceMetadata,
      String docUid) {
    EmbeddingProvider ep =
        embeddingProvider != null ? embeddingProvider : NoOpEmbeddingProvider.INSTANCE;
    String absolutePath = PathNormalizer.normalizeKey(filePath);
    String fileName = filePath.getFileName().toString();

    ParentIndexMetadata metadata =
        parentMetadata != null
            ? parentMetadata
            : deriveParentMetadata(filePath, extraction, spladeEncoder, log);
    boolean isMarkdown = "markdown".equals(metadata.fileKind());

    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, absolutePath);
    fields.put(SchemaFields.DOC_UID, docUid);
    fields.put(SchemaFields.PATH, absolutePath);
    fields.put(SchemaFields.FILENAME, fileName);
    fields.put(SchemaFields.CONTENT, extraction.content());
    // Tempdoc 931 §C.6 — the content revision, written wherever CONTENT is, from the SAME string
    // that is stored. Feedback captures (doc_uid, content_revision), so a label taken against one
    // revision of a document is recognisable as stale after the file is edited.
    if (extraction.content() != null) {
      fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(extraction.content()));
    }
    fields.put(SchemaFields.CONTENT_PREVIEW, contentPreview(extraction.content(), isMarkdown));
    fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());
    if (collection != null && !collection.isBlank()) {
      fields.put(SchemaFields.COLLECTION, collection);
    }

    if (extraction.title() != null && !extraction.title().isBlank()) {
      fields.put(SchemaFields.TITLE, extraction.title());
    } else if (fileName != null && !fileName.isBlank()) {
      int dot = fileName.lastIndexOf('.');
      String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
      fields.put(SchemaFields.TITLE, stem);
    }
    if (extraction.author() != null && !extraction.author().isBlank()) {
      fields.put(SchemaFields.AUTHOR, extraction.author());
    }

    // Frontmatter metadata: lowercased keyword fields for filtering/faceting
    Map<String, String> fmMeta = extraction.frontmatterMetadata();
    if (!fmMeta.isEmpty()) {
      putIfNonBlankLower(fields, SchemaFields.META_SOURCE, fmMeta.get("source"));
      putIfNonBlankLower(fields, SchemaFields.META_AUTHOR, fmMeta.get("author"));
      putIfNonBlankLower(fields, SchemaFields.META_CATEGORY, fmMeta.get("category"));
      String publishedAt = fmMeta.get("published_at");
      if (publishedAt != null) {
        Long epochMs = parsePublishedAt(publishedAt);
        if (epochMs != null) {
          fields.put(SchemaFields.META_PUBLISHED_AT, epochMs);
        }
      }
    }

    fields.put(SchemaFields.MIME, metadata.mime());
    if (metadata.mimeBase() != null && !metadata.mimeBase().isBlank()) {
      fields.put(SchemaFields.MIME_BASE, metadata.mimeBase());
    }
    fields.put(SchemaFields.FILE_KIND, metadata.fileKind());
    fields.put(SchemaFields.LANGUAGE, metadata.language());
    if (metadata.parentTokenCount() != null) {
      fields.put(SchemaFields.PARENT_TOKEN_COUNT, metadata.parentTokenCount());
    }

    String extractionMethod = extractionMethod(sourceMetadata);
    String visualEvidenceJson = sourceMetadata == null ? null : sourceMetadata.visualExtractionEvidenceJson();
    if (visualEvidenceJson != null && !visualEvidenceJson.isBlank()) {
      fields.put(SchemaFields.VISUAL_EXTRACTION_EVIDENCE, visualEvidenceJson);
    }
    markVduIfNeeded(filePath, extraction, extractionMethod, visualEvidenceJson, fields, log);

    if (sourceMetadata != null) {
      fields.put(SchemaFields.SIZE_BYTES, sourceMetadata.sizeBytes());
      fields.put(SchemaFields.MODIFIED_AT, sourceMetadata.modifiedAtMs());
      if (sourceMetadata.sourceSha256() != null && !sourceMetadata.sourceSha256().isBlank()) {
        fields.put(SchemaFields.SOURCE_SHA256, sourceMetadata.sourceSha256());
      }
    } else {
      try {
        fields.put(SchemaFields.SIZE_BYTES, Files.size(filePath));
        fields.put(SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(filePath).toMillis());
      } catch (IOException e) {
        log.debug("Failed to read file metadata: {}", filePath);
      }
    }

    if (precomputedEmbedding != null && precomputedEmbedding.length > 0) {
      // Use pre-computed embedding from batch processing
      fields.put(SchemaFields.VECTOR, precomputedEmbedding);
      fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      log.trace(
          "Using batch-computed embedding ({} dims) for: {}",
          precomputedEmbedding.length,
          filePath.getFileName());
    } else if (ep.isAvailable() && allowEmbeddingWrites) {
      // Spec E section 1.3 item 3: the bulk hold (external memory pressure, later a game) defers the
      // vector to PENDING like a Main GPU claim. Energy saver never reaches here: held() excludes it.
      if (signalBus.isMainGpuActive() || signalBus.isBulkHeld()) {
        log.debug(
            "Skipping GPU embedding, Main GPU active or bulk GPU work held: {}",
            filePath.getFileName());
        fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      } else {
        try {
          long embedStart = System.currentTimeMillis();
          float[] vector = ep.embedDocument(
              extraction.content(), io.justsearch.core.execution.InferenceRequest.background());
          stageRecorder.record("analyze", System.currentTimeMillis() - embedStart, null);
          if (vector != null && vector.length > 0) {
            fields.put(SchemaFields.VECTOR, vector);
            fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            log.trace(
                "Generated embedding with {} dimensions for: {}",
                vector.length,
                filePath.getFileName());
          } else {
            fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
          }
        } catch (RuntimeException e) {
          log.debug(
              "Failed to generate embedding for {}: {}", filePath.getFileName(), e.getMessage());
          // Tempdoc 819 C: route the inline failure through the SAME 3-strike escalation the
          // backfill uses instead of marking FAILED on the first fault. The backfill selects only
          // PENDING documents (EmbeddingBackfillOps.java:46-50), so a first-fault FAILED document
          // was never retried — one transient fault (a GPU/ORT hiccup) permanently cost that
          // document its vector. This is a freshly written document, so the count starts at 0; a
          // forced reindex likewise deserves a fresh budget. `computeEmbeddingFailureUpdate` sets
          // EMBEDDING_STATUS=FAILED only once the count reaches EMBEDDING_MAX_RETRIES, so PENDING
          // is the correct status for every attempt before that.
          fields.putAll(EmbeddingBackfillOps.computeEmbeddingFailureUpdate(0));
          fields.putIfAbsent(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        }
      }
    } else {
      fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    }

    // NER runs via backfill — mark all new documents as PENDING
    fields.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);

    // SPLADE sparse encoding — always deferred to backfill for throughput (tempdoc 278 item 2a).
    // The backfill path uses batch encoding which is 2.28x faster per-doc (EXP-5).
    fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);

    // Extraction provenance (tempdoc 252 Tier 1+2).
    fields.put(SchemaFields.EXTRACTION_METHOD, extractionMethod);
    double qualityScore = TextQualityAnalyzer.computeQualityScore(extraction.content());
    fields.put(SchemaFields.EXTRACTION_QUALITY_SCORE, (float) qualityScore);

    // Tempdoc 410 §11 — search-time honesty fields. Written from the validated artifact when one
    // is available (production path); skipped on the legacy/test path where sourceMetadata is null
    // or carries only size/mtime. Existing indices indexed before this slice will read null for
    // these fields — tolerated until a re-index.
    if (sourceMetadata != null && sourceMetadata.artifactStatus() != null) {
      fields.put(SchemaFields.EXTRACTION_STATUS, sourceMetadata.artifactStatus());
      fields.put(SchemaFields.CONTENT_TRUNCATED, sourceMetadata.truncated());
      if (sourceMetadata.policyId() != null) {
        fields.put(SchemaFields.EXTRACTION_POLICY_ID, sourceMetadata.policyId());
      }
      if (sourceMetadata.parserId() != null) {
        fields.put(SchemaFields.EXTRACTION_PARSER_ID, sourceMetadata.parserId());
      }
      if (sourceMetadata.embeddedResourceCount() > 0) {
        fields.put(
            SchemaFields.EMBEDDED_RESOURCE_COUNT, (long) sourceMetadata.embeddedResourceCount());
      }
      if (sourceMetadata.parserWarningsCount() > 0) {
        fields.put(
            SchemaFields.PARSER_WARNINGS_COUNT, (long) sourceMetadata.parserWarningsCount());
      }
      if (sourceMetadata.reasonCode() != null) {
        fields.put(SchemaFields.EXTRACTION_REASON_CODE, sourceMetadata.reasonCode());
      }
    }

    markExtractionDropout(extraction.content(), fields);

    return new IndexDocument(fields);
  }

  /**
   * Tempdoc 790 item 3 — the honest-hole marker. A document whose indexed text is an extraction
   * dropout gets one of two terminal-vs-pending states written to the provenance fields the search
   * path already reads:
   *
   * <ul>
   *   <li>a further fallback tier is queued ({@code vdu_status=PENDING}) → reason code
   *       {@code EXTRACTION_DROPOUT_PENDING_FALLBACK}, and {@code extraction_method} keeps naming
   *       the tier that ran;
   *   <li>no tier remains → {@code extraction_method=NONE} +
   *       {@code EXTRACTION_DROPOUT_UNRECOVERED}. The document stays indexed (path, filename,
   *       metadata are real and findable) but is no longer indistinguishable from a document that
   *       genuinely has no text to give.
   * </ul>
   *
   * <p>Order matters: this runs after the artifact-status block, so the dropout code wins over the
   * broader {@code SUCCESS_EMPTY} it refines.
   */
  static void markExtractionDropout(String content, Map<String, Object> fields) {
    if (!ExtractionDropoutPolicy.isDropout(content)) {
      return;
    }
    boolean fallbackPending =
        SchemaFields.VDU_STATUS_PENDING.equals(fields.get(SchemaFields.VDU_STATUS));
    if (fallbackPending) {
      fields.put(
          SchemaFields.EXTRACTION_REASON_CODE, IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK);
      return;
    }
    fields.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_NONE);
    fields.put(
        SchemaFields.EXTRACTION_REASON_CODE, IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED);
  }

  private static SourceFileMetadata withArtifact(
      SourceFileMetadata sourceMetadata, ValidatedExtractionArtifact artifact) {
    SourceFileMetadata base = sourceMetadata != null ? sourceMetadata : new SourceFileMetadata(0L, 0L);
    return new SourceFileMetadata(
        base.sizeBytes(),
        base.modifiedAtMs(),
        artifact.sourcePathHash() != null ? artifact.sourcePathHash() : base.sourcePathHash(),
        base.sourceSha256(),
        artifact.status().name(),
        artifact.policyId(),
        artifact.parserId(),
        artifact.truncated(),
        artifact.embeddedResourceCount(),
        artifact.maxEmbeddedDepth(),
        artifact.validatedAtMs(),
        artifact.warnings() != null ? artifact.warnings().size() : 0,
        deriveReasonCode(artifact),
        artifact.visualExtractionEvidenceJson());
  }

  private static String extractionMethod(SourceFileMetadata sourceMetadata) {
    if (sourceMetadata != null
        && io.justsearch.indexerworker.extract.OcrRoutingConfig.PARSER_ID.equals(
            sourceMetadata.parserId())) {
      return SchemaFields.EXTRACTION_METHOD_OCR_TIKA;
    }
    return SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED;
  }

  /**
   * Slice A.2 — derive the {@code SchemaFields.EXTRACTION_REASON_CODE} value from the artifact's
   * status. SUCCESS_FULL paths leave the field unset (null = "the implicit success case"); any
   * non-full success — SUCCESS_PARTIAL or SUCCESS_EMPTY (tempdoc 671, Long-term design part 2)
   * — surfaces the matching reason constant so search-side callers can distinguish "got
   * everything" from "got the prefix Tika handed us before we cut it off" or "got nothing at
   * all." Failure statuses (FAILED, TIMED_OUT, BUDGET_EXCEEDED) never reach {@code buildDocument}
   * in production — they short-circuit in {@code IndexingLoop} and produce a ledger event
   * without a document — so the explicit cases here are defense-in-depth.
   *
   * <p>Slice G.3 (M3) — the switch is exhaustive (no {@code default} arm). Adding a new
   * {@link io.justsearch.indexerworker.extract.ExtractionStatus} value is a compile error
   * forcing a deliberate decision: does the new status reach a successful document, and if so
   * what reason code should the field carry? This is exactly what caught the SUCCESS_EMPTY
   * addition at compile time and forced the case below.
   */
  private static String deriveReasonCode(ValidatedExtractionArtifact artifact) {
    if (artifact == null || artifact.status() == null) {
      return null;
    }
    return switch (artifact.status()) {
      case SUCCESS_FULL -> null;
      case SUCCESS_PARTIAL -> IngestionReasonCodes.SUCCESS_PARTIAL;
      case SUCCESS_EMPTY -> IngestionReasonCodes.SUCCESS_EMPTY;
      case FAILED, TIMED_OUT, BUDGET_EXCEEDED -> null;
    };
  }

  /**
   * @param collection the parent document's collection tag (nullable), written onto every chunk so
   *     collection scoping works on the chunk branch (tempdoc 811 item 3). Must be the same value
   *     passed to {@link #buildDocument} for the parent, or parent and chunks disagree on scope.
   * @param chunkSpladeEnabled {@code rag.chunk_splade.enabled}, read from the live resolved config
   *     by the caller (tempdoc 931 §E item 8) — decides whether a chunk carries a
   *     {@code splade_status} at all.
   */
  public static int indexChunks(
      Path filePath,
      ExtractionResult extraction,
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      ParentIndexMetadata parentMetadata,
      String collection,
      String parentDocUid,
      boolean chunkSpladeEnabled) {
    String content = extraction.content();
    String parentDocId = PathNormalizer.normalizeKey(filePath);

    if (content == null || content.length() < CHUNK_THRESHOLD_CHARS) {
      ChunkDocumentWriter.regenerateChunks(
          documentFieldOps, indexingCoordinator, parentDocId, "", null, chunkSpladeEnabled);
      return 0;
    }

    ParentIndexMetadata metadata =
        parentMetadata != null
            ? parentMetadata
            : deriveParentMetadata(filePath, extraction, null, null);

    return ChunkDocumentWriter.regenerateChunks(
        documentFieldOps,
        indexingCoordinator,
        parentDocId,
        content,
        metadata.toChunkMetadata(collection, parentDocUid),
        chunkSpladeEnabled);
  }

  public static ParentIndexMetadata deriveParentMetadata(
      Path filePath, ExtractionResult extraction, SpladeEncoder spladeEncoder, Logger log) {
    String mime = extraction.mimeType();
    String mimeBase = normalizeMimeBase(mime);
    String fileKind = classifyFileKind(filePath, mimeBase);
    boolean isMarkdown = "markdown".equals(fileKind);
    String language = resolveLanguage(contentPreview(extraction.content(), isMarkdown));

    Long parentTokenCount = null;
    if (spladeEncoder != null) {
      try {
        parentTokenCount = spladeEncoder.tokenCount(extraction.content());
      } catch (RuntimeException e) {
        if (log != null) {
          log.debug(
              "Failed to compute SPLADE token count for {}: {}",
              filePath.getFileName(),
              e.getMessage());
        }
      }
    }
    if (parentTokenCount == null) {
      // Tempdoc 717: the SPLADE encoder loads asynchronously, so on a fresh build a small corpus can
      // be fully indexed before it is ready — leaving parent_token_count null. The corpus-profile
      // short/long classifier (CorpusProfile) then sees median 0, mis-classifies a long corpus as
      // "short", and the chunk_merge search leg is silently skipped (intermittent quality halving).
      // Fall back to a cheap, always-available character-based estimate so the field is NEVER null
      // and never race-dependent; the exact SPLADE count is still used whenever the encoder is
      // ready. The corpus-profile buckets are coarse (256/512/1024/…), so a ~4-chars/token estimate
      // is sufficient to classify long-vs-short correctly.
      parentTokenCount = estimateTokenCount(extraction.content());
    }

    return new ParentIndexMetadata(mime, mimeBase, fileKind, language, parentTokenCount);
  }

  /**
   * Cheap, always-available token-count estimate (tempdoc 717) used as a fallback for {@code
   * parent_token_count} when the SPLADE encoder isn't ready at index time — so the corpus-profile
   * short/long classifier is never fed a null/zero token count due to a startup race. Returns 0 for
   * null/blank content.
   *
   * <p>Divisor 3 (not 4) is deliberate (tempdoc 717 review, Finding 2): the property that matters is
   * that any document long enough to have been <em>chunked</em> (≥ {@link #CHUNK_THRESHOLD_CHARS} =
   * 2000 chars) estimates <em>above</em> the 512-token short threshold, so a chunked corpus is never
   * mis-classified short. 2000/3 ≈ 666 &gt; 512 with margin; 2000/4 = 500 would fall just under. The
   * estimate is language-uniform (a fixed chars/token ratio, not a per-language lever — invariant
   * #6): CJK packs more tokens per char, so a chars/3 estimate under-counts CJK, but a chunked CJK
   * doc still has ≥ 2000 chars → ≥ 666 → long. Erring toward "long" is the safe direction (running
   * chunk-merge on a short corpus is hybrid-neutral, F-036; skipping it on a long one halves
   * quality). The exact SPLADE count is still used whenever the encoder is ready.
   */
  static long estimateTokenCount(String content) {
    if (content == null || content.isBlank()) {
      return 0L;
    }
    return Math.max(1L, content.length() / 3L);
  }

  public static String contentPreview(String content, boolean isMarkdown) {
    return LanguageUtils.contentPreview(content, CONTENT_PREVIEW_MAX_CHARS, isMarkdown);
  }

  public static String contentPreview(String content) {
    return contentPreview(content, false);
  }

  public static String normalizeMimeBase(String mime) {
    if (mime == null) {
      return null;
    }
    String s = mime.trim().toLowerCase(Locale.ROOT);
    if (s.isBlank()) {
      return null;
    }
    int semi = s.indexOf(';');
    if (semi >= 0) {
      s = s.substring(0, semi).trim();
    }
    return s.isBlank() ? null : s;
  }

  public static String classifyFileKind(Path filePath, String mimeBase) {
    String name =
        filePath == null || filePath.getFileName() == null ? "" : filePath.getFileName().toString();
    String lowerName = name.toLowerCase(Locale.ROOT);
    String ext = "";
    int dot = lowerName.lastIndexOf('.');
    if (dot >= 0 && dot < lowerName.length() - 1) {
      ext = lowerName.substring(dot);
    }

    if (mimeBase != null) {
      if ("application/pdf".equals(mimeBase)) return "pdf";
      if (mimeBase.startsWith("image/")) return "image";
      if ("text/markdown".equals(mimeBase) || "text/x-markdown".equals(mimeBase)) return "markdown";
      if (mimeBase.contains("officedocument")
          || mimeBase.contains("msword")
          || mimeBase.contains("ms-excel")
          || mimeBase.contains("ms-powerpoint")) return "office";
      if (mimeBase.startsWith("text/")
          || mimeBase.contains("json")
          || mimeBase.contains("xml")
          || mimeBase.contains("javascript")) {
        if (isCodeExtension(ext)) return "code";
        if (isMarkdownExtension(ext)) return "markdown";
        return "text";
      }
      if (mimeBase.contains("zip")
          || mimeBase.contains("gzip")
          || mimeBase.contains("x-7z")
          || mimeBase.contains("x-rar")
          || mimeBase.contains("x-tar")) return "archive";
      if ("application/octet-stream".equals(mimeBase)) {
        if (isMarkdownExtension(ext)) return "markdown";
        if (isCodeExtension(ext)) return "code";
        return "binary";
      }
      return "binary";
    }

    if (isMarkdownExtension(ext)) return "markdown";
    if (isCodeExtension(ext)) return "code";
    return "unknown";
  }

  public static boolean isMarkdownExtension(String ext) {
    return ".md".equals(ext) || ".markdown".equals(ext) || ".mdx".equals(ext);
  }

  public static boolean isCodeExtension(String ext) {
    return switch (ext) {
      case ".ts", ".tsx", ".js", ".jsx", ".py", ".java", ".kt", ".go", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp",
          ".cs", ".rb", ".php", ".swift", ".scala", ".sql", ".sh", ".ps1", ".yml", ".yaml", ".json", ".xml", ".toml",
          ".ini", ".gradle", ".properties" -> true;
      default -> false;
    };
  }

  public static String resolveLanguage(Object previewObj) {
    String preview = previewObj instanceof String s ? s : null;
    String detected = detectLanguage(preview);
    if (detected != null && !detected.isBlank()) {
      return detected;
    }
    return DEFAULT_LANGUAGE != null && !DEFAULT_LANGUAGE.isBlank() ? DEFAULT_LANGUAGE : "und";
  }

  public static String resolveDefaultLanguageTag() {
    try {
      Locale locale = Locale.getDefault();
      if (locale != null) {
        String tag = locale.toLanguageTag();
        if (tag != null && !tag.isBlank()) {
          return tag;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }
    return "en-US";
  }

  public static String detectLanguage(String text) {
    if (text == null) {
      return null;
    }
    String sample = text.strip();
    if (sample.isEmpty()) {
      return null;
    }
    int latin = 0;
    int cyrillic = 0;
    int han = 0;
    int kana = 0;
    int hangul = 0;
    int arabic = 0;
    int devanagari = 0;
    int greek = 0;
    int examined = 0;
    int limit = Math.min(sample.length(), 4096);
    for (int i = 0; i < limit; i++) {
      char ch = sample.charAt(i);
      if (!Character.isLetter(ch)) {
        continue;
      }
      examined++;
      Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
      if (block == null) {
        continue;
      }
      if (isLatin(block)) {
        latin++;
      } else if (isCyrillic(block)) {
        cyrillic++;
      } else if (isHan(block)) {
        han++;
      } else if (isKana(block)) {
        kana++;
      } else if (isHangul(block)) {
        hangul++;
      } else if (isArabic(block)) {
        arabic++;
      } else if (isDevanagari(block)) {
        devanagari++;
      } else if (isGreek(block)) {
        greek++;
      }
    }
    if (examined < 8) {
      return null;
    }
    int threshold = Math.max(5, examined / 6);
    if (han >= threshold) {
      return "zh";
    }
    if (kana >= threshold) {
      return "ja";
    }
    if (hangul >= threshold) {
      return "ko";
    }
    if (arabic >= threshold) {
      return "ar";
    }
    if (devanagari >= threshold) {
      return "hi";
    }
    if (cyrillic >= threshold) {
      return "ru";
    }
    if (greek >= threshold) {
      return "el";
    }
    if (latin >= threshold) {
      return null;
    }
    return null;
  }

  public static boolean isLatin(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.BASIC_LATIN
        || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
        || block == Character.UnicodeBlock.LATIN_EXTENDED_A
        || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
        || block == Character.UnicodeBlock.LATIN_EXTENDED_B
        || block == Character.UnicodeBlock.LATIN_EXTENDED_C
        || block == Character.UnicodeBlock.LATIN_EXTENDED_D
        || block == Character.UnicodeBlock.LATIN_EXTENDED_E
        || block == Character.UnicodeBlock.LATIN_EXTENDED_F
        || block == Character.UnicodeBlock.LATIN_EXTENDED_G;
  }

  public static boolean isCyrillic(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.CYRILLIC
        || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_C;
  }

  public static boolean isHan(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
  }

  public static boolean isKana(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.HIRAGANA
        || block == Character.UnicodeBlock.KATAKANA
        || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS;
  }

  public static boolean isHangul(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.HANGUL_SYLLABLES
        || block == Character.UnicodeBlock.HANGUL_JAMO
        || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
        || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
        || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B;
  }

  public static boolean isArabic(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.ARABIC
        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
        || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
        || block == Character.UnicodeBlock.ARABIC_EXTENDED_A;
  }

  public static boolean isDevanagari(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.DEVANAGARI
        || block == Character.UnicodeBlock.DEVANAGARI_EXTENDED;
  }

  public static boolean isGreek(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.GREEK
        || block == Character.UnicodeBlock.GREEK_EXTENDED;
  }

  /**
   * Quality threshold below which a VDU-eligible document is queued for VLM re-extraction.
   * Configurable via {@code JUSTSEARCH_VDU_QUALITY_THRESHOLD} (default 0.3).
   */
  static final double VDU_QUALITY_THRESHOLD = clampThreshold(
      io.justsearch.configuration.EnvRegistry.VDU_QUALITY_THRESHOLD.getDouble(0.3));

  private static double clampThreshold(double value) {
    return Math.max(0.0, Math.min(1.0, value));
  }

  public static void markVduIfNeeded(
      Path filePath,
      ExtractionResult extraction,
      String extractionMethod,
      Map<String, Object> fields,
      Logger log) {
    markVduIfNeeded(filePath, extraction, extractionMethod, null, fields, log);
  }

  public static void markVduIfNeeded(
      Path filePath,
      ExtractionResult extraction,
      String extractionMethod,
      String visualExtractionEvidenceJson,
      Map<String, Object> fields,
      Logger log) {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            filePath, extraction, extractionMethod, visualExtractionEvidenceJson, VDU_QUALITY_THRESHOLD);
    fields.put(SchemaFields.VDU_STATUS, decision.status());
    if (decision.demandKind() != null) {
      fields.put(SchemaFields.VDU_DEMAND_KIND, decision.demandKind());
    }
    if (SchemaFields.VDU_STATUS_PENDING.equals(decision.status())) {
      log.info(
          "Marked for VDU processing (kind={}, reason={}): {}",
          decision.demandKind(),
          decision.reason(),
          filePath.getFileName());
      return;
    }
    log.trace(
        "VDU not needed (reason={}): {}",
        decision.reason(),
        filePath.getFileName());
  }

  public static boolean isGarbageText(String text) {
    return TextQualityAnalyzer.isGarbageText(text);
  }

  private static void putIfNonBlankLower(
      Map<String, Object> fields, String key, String value) {
    if (value != null && !value.isBlank()) {
      fields.put(key, value.toLowerCase(Locale.ROOT));
    }
  }

  private static final java.time.format.DateTimeFormatter PUBLISHED_AT_FMT =
      new java.time.format.DateTimeFormatterBuilder()
          .appendOptional(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
          .appendOptional(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .appendOptional(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
          .toFormatter();

  static Long parsePublishedAt(String dateStr) {
    try {
      var parsed = PUBLISHED_AT_FMT.parseBest(
          dateStr.strip(),
          java.time.LocalDateTime::from,
          java.time.LocalDate::from);
      if (parsed instanceof java.time.LocalDateTime ldt) {
        return ldt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
      } else if (parsed instanceof java.time.LocalDate ld) {
        return ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
      }
    } catch (java.time.format.DateTimeParseException ignored) {
      // Unparseable date — skip silently
    }
    return null;
  }
}
