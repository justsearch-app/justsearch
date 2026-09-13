/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Shared parent/chunk mutation rules for direct VDU results and durable switch-buffer replay. */
public final class VduResultWriter {
  private VduResultWriter() {}

  /** Validates before direct mutation or buffer acceptance; replay applies the same rules. */
  public static void validate(UpdateVduResultRequest request) {
    if (request.getDocId().isBlank()) throw new IllegalArgumentException("doc_id is required");
    var outcome = effectiveOutcome(request);
    if (outcome == VduUpdateOutcome.UNRECOGNIZED) {
      throw new IllegalArgumentException("Unsupported VDU outcome: " + request.getOutcomeValue());
    }
    if (outcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT
        && (!request.hasExtractedContent() || request.getExtractedContent().isBlank())) {
      throw new IllegalArgumentException("SUCCESS_TEXT requires non-blank extracted_content");
    }
  }

  /**
   * Applies one result; the caller must cover success with its existing commit boundary.
   * A false result means the parent was absent. Exceptions preserve failure for the caller.
   */
  public static boolean apply(
      RunningRuntime runtime, UpdateVduResultRequest request, boolean chunkSpladeEnabled)
      throws IOException {
    validate(request);
    String docId = request.getDocId();
    var outcome = effectiveOutcome(request);
    String content = request.getExtractedContent();
    boolean replaceContent = outcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT
        || (outcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED && !content.isBlank());
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.VDU_PROCESSED, "true");
    switch (outcome) {
      case VDU_UPDATE_OUTCOME_SUCCESS_TEXT ->
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_COMPLETED);
      case VDU_UPDATE_OUTCOME_SUCCESS_EMPTY -> {
        updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_COMPLETED_EMPTY);
        markDropoutUnrecovered(runtime, docId, updates);
      }
      case VDU_UPDATE_OUTCOME_FAILED -> {
        updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
        markDropoutUnrecovered(runtime, docId, updates);
      }
      case VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT -> {
        updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_REJECTED);
        markDropoutUnrecovered(runtime, docId, updates);
      }
      default -> {
        if (!request.getVduStatus().isBlank()) updates.put(SchemaFields.VDU_STATUS, request.getVduStatus());
      }
    }
    if (replaceContent) {
      String preview = LanguageUtils.contentPreview(content, ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS);
      updates.put(SchemaFields.CONTENT, content);
      updates.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
      updates.put(SchemaFields.CONTENT_PREVIEW, preview);
      updates.put(SchemaFields.LANGUAGE, LanguageUtils.resolveLanguage(preview));
      updates.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_VDU);
      updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    }
    if (!request.getVduEnrichment().isBlank()) {
      updates.put(SchemaFields.VDU_ENRICHMENT, request.getVduEnrichment());
    }
    if (request.getPageCount() > 0) {
      updates.put(SchemaFields.VDU_PAGE_COUNT, String.valueOf(request.getPageCount()));
    }
    // Partial chunk writes may reach an unrelated commit after failure. Preserve the previous
    // parent state until all replacement chunks exist, so PROCESSING remains recoverable.
    if (replaceContent) {
      ChunkDocumentWriter.regenerateChunksFromExistingParent(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), docId, content, chunkSpladeEnabled);
    }
    return runtime.indexingCoordinator().updateDocument(docId, updates);
  }

  // Permanent compatibility: the legacy vdu_status string remains a supported input.
  private static VduUpdateOutcome effectiveOutcome(UpdateVduResultRequest request) {
    if (request.getOutcome() != VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED) {
      return request.getOutcome();
    }
    return switch (request.getVduStatus().toUpperCase(Locale.ROOT)) {
      case "COMPLETED" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT;
      case "COMPLETED_EMPTY" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
      case "FAILED" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED;
      default -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED;
    };
  }

  private static void markDropoutUnrecovered(
      RunningRuntime runtime, String docId, Map<String, Object> updates) throws IOException {
    String reason = runtime.documentFieldOps().getDocumentFieldOrThrow(docId, SchemaFields.EXTRACTION_REASON_CODE);
    if (!IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK.equals(reason)) return;
    updates.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_NONE);
    updates.put(SchemaFields.EXTRACTION_REASON_CODE, IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED);
  }
}
