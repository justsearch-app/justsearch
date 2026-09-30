/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.core.util.DocumentTypeDetector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-document {@link ContextInjector} for {@code BatchSummarizeShape}.
 *
 * <p>Per tempdoc 491 §C2.2: reads {@code docIds: List<String>} from the request body,
 * fetches all docs via {@link DocumentService#fetchBatch}, and concatenates them as one user
 * message with the legacy delimiter format {@code "--- File: <filename> ---\n<content>\n\n"}.
 *
 * <p>Emits a single {@code progress} event {@code {phase:"files", totalFiles:N,
 * message:"Summarizing N files..."}} via {@link InjectorResult#events} before the LLM call.
 *
 * <p>Hard-caps total content at 200K characters (same as {@code DocAccess}'s
 * {@code MAX_CONTENT_CHARS}). Token-budget truncation is handled separately by the engine
 * via the LLM service's context-window limits.
 *
 * <p>Missing or empty {@code docIds} → {@link InjectorResult#terminalError} with code
 * {@code NO_FILES}; engine aborts before LLM call.
 */
public final class BatchDocAccess implements ContextInjector {

  private static final Logger LOG = LoggerFactory.getLogger(BatchDocAccess.class);

  /** Stable id used by {@code ConversationShape.contextInjectorIds}. */
  public static final String ID = "core.batch-doc-access";

  /** Default fetch timeout for the batch operation. */
  static final Duration DEFAULT_FETCH_TIMEOUT = Duration.ofSeconds(30);

  /** Soft character cap on injected content — mirrors the Worker's gRPC transport cap. */
  static final int MAX_CONTENT_CHARS = 200_000;

  private final DocumentService documents;
  private final Duration fetchTimeout;

  public BatchDocAccess(DocumentService documents) {
    this(documents, DEFAULT_FETCH_TIMEOUT);
  }

  public BatchDocAccess(DocumentService documents, Duration fetchTimeout) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.fetchTimeout = Objects.requireNonNull(fetchTimeout, "fetchTimeout");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    var engineContext = ctx.engineContext();
    Map<String, Object> body = ctx.requestBody();
    List<String> docIds = extractDocIds(body);

    if (docIds.isEmpty()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "No files selected");
      err.put("errorCode", "NO_FILES");
      err.put("i18nKey", "errors.NO_FILES");
      return InjectorResult.terminalError(new SseEvent("error", err));
    }

    // Make docIds + count visible to downstream consumers (e.g., BatchSummaryDoneEnricher).
    ctx.attributes().put("batch.docIds", docIds);
    ctx.attributes().put("batch.fileCount", docIds.size());

    // Resolve all docs; failed fetches are tolerated (their slot becomes empty, with a
    // {{File: <name>}} delimiter so the model can see what was attempted).
    Map<String, DocumentRecord> records = fetchAll(docIds, engineContext);

    String concatenated = formatDocuments(records, docIds);
    if (concatenated.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "No content in selected files");
      err.put("errorCode", "NO_CONTENT");
      err.put("i18nKey", "errors.NO_CONTENT");
      err.put("docIds", docIds);
      return InjectorResult.terminalError(new SseEvent("error", err));
    }

    String truncated =
        concatenated.length() > MAX_CONTENT_CHARS
            ? concatenated.substring(0, MAX_CONTENT_CHARS)
            : concatenated;

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", "Summarize the following documents:\n\n" + truncated);

    Map<String, Object> progress = new LinkedHashMap<>();
    progress.put("phase", "files");
    progress.put("totalFiles", docIds.size());
    progress.put("message", "Summarizing " + docIds.size() + " files...");

    return InjectorResult.of(List.of(message), List.of(new SseEvent("progress", progress)));
  }

  @SuppressWarnings("unchecked")
  private static List<String> extractDocIds(Map<String, Object> body) {
    Object raw = body == null ? null : body.get("docIds");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object o : list) {
      if (o != null) {
        String s = o.toString();
        if (!s.isBlank()) {
          out.add(s);
        }
      }
    }
    return List.copyOf(out);
  }

  private Map<String, DocumentRecord> fetchAll(List<String> docIds, EngineContext engineContext) {
    try {
      return documents
          .fetchBatch(docIds, engineContext)
          .toCompletableFuture()
          .get(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      LOG.warn("BatchDocAccess: fetchBatch failed for {} docIds", docIds.size(), e);
      return Map.of();
    }
  }

  /**
   * Concatenate the fetched documents with the legacy {@code "--- File: <filename> ---"}
   * delimiter. Documents that fetched empty content are skipped (no delimiter emitted) —
   * matches the legacy {@code RagStreamingHandler.formatDocuments} behavior.
   */
  private static String formatDocuments(Map<String, DocumentRecord> docs, List<String> docIds) {
    StringBuilder sb = new StringBuilder();
    for (String docId : docIds) {
      DocumentRecord record = docs.get(docId);
      if (record == null || record.content() == null || record.content().isBlank()) {
        continue;
      }
      String filename = DocumentTypeDetector.extractFilename(docId);
      sb.append("--- File: ").append(filename).append(" ---\n");
      sb.append(record.content()).append("\n\n");
    }
    return sb.toString();
  }
}
