/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.services.conversation.shapes.HierarchicalSummarizeShape;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.core.util.ContextBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ShapeRunner} for the hierarchical-summarize shape.
 *
 * <p>Per tempdoc 491 §C2.3: a SHAPE_DRIVEN runner that orchestrates the data-driven
 * multi-LLM-call pipeline that does not fit substrate-driven execution (each LLM call has
 * fresh content driven by data, not by prior LLM output).
 *
 * <p>Phases (mirrors the legacy {@code /api/summarize/hierarchical/stream} event vocabulary):
 *
 * <ol>
 *   <li>{@code progress phase:"loading"} — fetch document text via {@link DocumentService},
 *       fall back to direct filesystem read if the docId looks like a path.
 *   <li>{@code progress phase:"splitting"} — split into sections by token budget.
 *   <li>{@code progress phase:"sections"} — emit section count.
 *   <li>{@code progress phase:"summarizing"} — for each section, call {@code streamChat} with
 *       the section-summary system prompt; one progress event per section.
 *   <li>{@code progress phase:"synthesis"} — stream the final synthesis via {@code streamChat}
 *       with the synthesis system prompt; emit {@code chunk} events for each delta.
 *   <li>{@code done} — payload {@code {docId, hierarchical:true, sections, finishReason}}.
 * </ol>
 *
 * <p>System prompts are lifted from the legacy {@code MapReducePipeline} constants (the more
 * carefully-designed stable-intermediate-format prompts), allowing C5 to delete the legacy
 * {@code streamSummary} interface methods entirely.
 */
public final class HierarchicalShapeRunner implements ShapeRunner {

  private static final Logger LOG = LoggerFactory.getLogger(HierarchicalShapeRunner.class);

  private static final int SECTION_TIMEOUT_SECONDS = 45;
  private static final int SYNTHESIS_TIMEOUT_SECONDS = 180;

  /**
   * The completion reserved for one section summary, and for the final synthesis.
   *
   * <p>Tempdoc 883 decision 3 — these two are OUTPUT limits, not window fractions: they are the
   * {@code max_tokens} handed to {@code streamChat}, the same category as
   * {@code ConversationEngine.DEFAULT_MAX_TOKENS}. They stay constants. What changed is that
   * {@link #SYNTHESIS_MAX_TOKENS} is now the reserve this runner's {@link ContextBudget} is BUILT
   * from, so the threshold it derives already accounts for the room the answer will take.
   */
  private static final int SECTION_MAX_TOKENS = 512;

  private static final int SYNTHESIS_MAX_TOKENS = 1024;

  private static final String SECTION_SUMMARY_SYSTEM_PROMPT =
      """
      You are a careful summarization engine.
      Rules:
      - ONLY use information explicitly present in the provided text.
      - Do NOT use outside knowledge.
      - If something is not stated, write "unknown" or omit it.
      - Be concise; aim for a few short paragraphs or bullets.
      """;

  private static final String SYNTHESIS_SYSTEM_PROMPT =
      """
      You are given section summaries of a larger document.
      Synthesize them into a single coherent summary that captures key information across
      all sections. Do not add information not present in the section summaries.
      """;

  private static final Duration DOC_FETCH_TIMEOUT = Duration.ofSeconds(20);

  private final Supplier<OnlineAiService> onlineAiSupplier;
  private final Supplier<DocumentService> documentsSupplier;

  public HierarchicalShapeRunner(
      Supplier<OnlineAiService> onlineAiSupplier, Supplier<DocumentService> documentsSupplier) {
    this.onlineAiSupplier = onlineAiSupplier;
    this.documentsSupplier = documentsSupplier;
  }

  @Override
  public ConversationShapeRef shapeId() {
    return HierarchicalSummarizeShape.ID;
  }

  @Override
  public void run(Map<String, Object> body, Audience audience, Consumer<SseEvent> sink, EngineContext engineContext) {
    String docId = asString(body.get("docId"));
    if (docId == null || docId.isBlank()) {
      emitError(sink, "No document ID provided", "NO_DOC_ID");
      return;
    }

    OnlineAiService onlineAi = onlineAiSupplier.get();
    if (onlineAi == null || !onlineAi.isAvailable()) {
      String errMsg =
          onlineAi != null && onlineAi.isStartingUp()
              ? "AI is starting up, please wait"
              : "AI summarization unavailable";
      String errCode = onlineAi != null && onlineAi.isStartingUp() ? "AI_STARTING" : "AI_OFFLINE";
      emitError(sink, errMsg, errCode);
      return;
    }

    emitProgress(sink, "loading", "Loading document...");

    String content = loadDocument(docId, asString(body.get("content")), engineContext);
    if (content == null || content.isBlank()) {
      emitError(sink, "Document has no content", "NO_CONTENT");
      return;
    }

    // Tempdoc 883 decision 3 — the shape of this run is derived from the window the server
    // actually has, not from a 5000-token literal that predates the launch ladder. The reserve is
    // the synthesis call's own max_tokens, because that is the call the single-pass branch makes.
    ContextBudget budget = RAGContext.budgetFor(onlineAiSupplier, SYNTHESIS_MAX_TOKENS);

    int totalTokens = estimateTokens(content);
    LOG.info(
        "Hierarchical summary: docId={} tokens={} threshold={} window={} ({})",
        docId,
        totalTokens,
        budget.hierarchicalThreshold(),
        budget.windowTokens(),
        budget.source());

    if (totalTokens < budget.hierarchicalThreshold()) {
      emitProgress(sink, "standard", "Document is small, using single-pass summarization");
      streamSingleSynthesis(onlineAi, content, docId, false, 0, sink);
      return;
    }

    emitProgress(sink, "splitting", "Splitting into sections...");
    List<String> sections = splitIntoSections(content, budget.sectionTarget());
    int sectionCount = sections.size();

    Map<String, Object> sectionsPayload = new LinkedHashMap<>();
    sectionsPayload.put("phase", "sections");
    sectionsPayload.put("message", "Document split into " + sectionCount + " sections");
    sectionsPayload.put("totalStages", sectionCount);
    sectionsPayload.put("totalTokens", totalTokens);
    sink.accept(new SseEvent("progress", sectionsPayload));

    List<String> sectionSummaries = new ArrayList<>();
    int failedSections = 0;
    for (int i = 0; i < sectionCount; i++) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("phase", "summarizing");
      p.put("message", "Summarizing section " + (i + 1) + " of " + sectionCount);
      p.put("stage", i + 1);
      p.put("totalStages", sectionCount);
      sink.accept(new SseEvent("progress", p));

      try {
        String sectionSummary =
            blockingStreamChat(
                onlineAi,
                List.of(
                    msg("system", SECTION_SUMMARY_SYSTEM_PROMPT),
                    msg(
                        "user",
                        "SECTION " + (i + 1) + " of " + sectionCount + "\n\n" + sections.get(i))),
                SECTION_MAX_TOKENS,
                SECTION_TIMEOUT_SECONDS);
        sectionSummaries.add(sectionSummary);
      } catch (TimeoutException e) {
        LOG.warn("Section {} summarization timed out", i + 1);
        String excerpt = sections.get(i);
        sectionSummaries.add(
            "[Section "
                + (i + 1)
                + " excerpt] "
                + (excerpt.length() > 200 ? excerpt.substring(0, 200) + "..." : excerpt));
        failedSections++;
      } catch (Exception e) {
        LOG.warn("Section {} summarization failed", i + 1, e);
        sectionSummaries.add("[Section " + (i + 1) + " could not be summarized]");
        failedSections++;
      }
    }

    emitProgress(
        sink,
        "synthesis",
        "Synthesizing final summary from " + sectionCount + " sections...");

    String combined = formatSectionSummaries(sectionSummaries);
    streamSynthesis(onlineAi, combined, docId, sectionCount, failedSections, sink);
  }

  // ---- Phase helpers ----

  private void streamSingleSynthesis(
      OnlineAiService ai,
      String content,
      String docId,
      boolean hierarchical,
      int sectionCount,
      Consumer<SseEvent> sink) {
    List<Map<String, Object>> messages =
        List.of(
            msg("system", SYNTHESIS_SYSTEM_PROMPT),
            msg("user", "Summarize the following text:\n\n" + content));
    streamFinalToSink(ai, messages, docId, hierarchical, sectionCount, 0, sink);
  }

  private void streamSynthesis(
      OnlineAiService ai,
      String combinedSummaries,
      String docId,
      int sectionCount,
      int failedSections,
      Consumer<SseEvent> sink) {
    List<Map<String, Object>> messages =
        List.of(
            msg("system", SYNTHESIS_SYSTEM_PROMPT),
            msg(
                "user",
                "Write a coherent summary from the section summaries below.\n"
                    + "Prefer concise paragraphs and bullets. Do not invent missing parts.\n\n"
                    + combinedSummaries));
    streamFinalToSink(ai, messages, docId, true, sectionCount, failedSections, sink);
  }

  private void streamFinalToSink(
      OnlineAiService ai,
      List<Map<String, Object>> messages,
      String docId,
      boolean hierarchical,
      int sectionCount,
      int failedSections,
      Consumer<SseEvent> sink) {
    AtomicBoolean terminal = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> finishReasonRef = new AtomicReference<>();

    ai.streamChat(
        messages,
        SYNTHESIS_MAX_TOKENS,
        chunk -> {
          if (chunk != null && !chunk.isEmpty()) {
            sink.accept(new SseEvent("chunk", Map.of("text", chunk)));
          }
        },
        finishReason -> {
          finishReasonRef.set(finishReason);
          if (terminal.compareAndSet(false, true)) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("docId", docId);
            done.put("hierarchical", hierarchical);
            if (hierarchical) {
              done.put("sections", sectionCount);
              done.put("failedSections", failedSections);
            }
            if (finishReason != null) {
              done.put("finishReason", finishReason);
            }
            sink.accept(new SseEvent("done", done));
          }
          latch.countDown();
        },
        err -> {
          if (terminal.compareAndSet(false, true)) {
            LOG.error("Hierarchical synthesis failed", err);
            emitError(sink, err.getMessage() == null ? "Synthesis failed" : err.getMessage(),
                "SYNTHESIS_FAILED");
          }
          latch.countDown();
        });

    try {
      if (!latch.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          && terminal.compareAndSet(false, true)) {
        emitError(sink, "Synthesis timeout", "TIMEOUT");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (terminal.compareAndSet(false, true)) {
        emitError(sink, "Interrupted", "INTERRUPTED");
      }
    }
  }

  // ---- Document loading ----

  private String loadDocument(String docId, String providedContent, EngineContext engineContext) {
    DocumentService docs = documentsSupplier.get();
    if (docs != null) {
      try {
        DocumentRecord record =
            docs.fetch(docId, engineContext)
                .toCompletableFuture()
                .get(DOC_FETCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (record != null && record.content() != null && !record.content().isBlank()) {
          return record.content();
        }
      } catch (Exception e) {
        LOG.debug("Document fetch failed for {}; trying direct filesystem", docId, e);
      }
    }
    // Direct filesystem fallback (for docIds that are paths).
    String fromFile = readFileDirectly(docId);
    if (fromFile != null && !fromFile.isBlank()) {
      return fromFile;
    }
    return providedContent == null ? "" : providedContent;
  }

  private static String readFileDirectly(String pathStr) {
    if (pathStr == null || pathStr.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(pathStr);
      if (Files.exists(path) && Files.isRegularFile(path)) {
        return Files.readString(path);
      }
    } catch (Exception e) {
      LOG.debug("readFileDirectly: cannot read {}", pathStr, e);
    }
    return null;
  }

  // ---- LLM blocking helper ----

  private static String blockingStreamChat(
      OnlineAiService ai,
      List<Map<String, Object>> messages,
      int maxTokens,
      int timeoutSeconds)
      throws Exception {
    StringBuilder out = new StringBuilder();
    AtomicReference<Throwable> err = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    ai.streamChat(
        messages,
        maxTokens,
        chunk -> {
          if (chunk != null) {
            out.append(chunk);
          }
        },
        complete -> latch.countDown(),
        e -> {
          err.set(e);
          latch.countDown();
        },
        SamplingParams.DETERMINISTIC,
        false);

    if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
      throw new TimeoutException("section summarize timeout");
    }
    Throwable t = err.get();
    if (t != null) {
      if (t instanceof Exception ex) throw ex;
      throw new RuntimeException(t);
    }
    return out.toString();
  }

  // ---- Token + section helpers (lifted self-contained from TokenEstimationUtils) ----

  private static int estimateTokens(String text) {
    if (text == null || text.isEmpty()) return 0;
    // Heuristic ~4 chars/token (matches TokenEstimationUtils.estimateTokens).
    return Math.max(1, text.length() / 4);
  }

  private static List<String> splitIntoSections(String text, int targetTokens) {
    if (text == null || text.isEmpty()) return List.of();
    int targetChars = Math.max(256, targetTokens * 4);
    List<String> out = new ArrayList<>();
    int idx = 0;
    int len = text.length();
    while (idx < len) {
      int end = Math.min(idx + targetChars, len);
      if (end < len) {
        int boundary = findBoundary(text, end, Math.max(idx, end - 500));
        if (boundary > idx) {
          end = boundary;
        }
      }
      out.add(text.substring(idx, end).trim());
      idx = end;
    }
    return out;
  }

  private static int findBoundary(String text, int preferredEnd, int minStart) {
    // Look backward for paragraph break.
    int pbreak = text.lastIndexOf("\n\n", preferredEnd);
    if (pbreak >= minStart) return pbreak + 2;
    int nbreak = text.lastIndexOf('\n', preferredEnd);
    if (nbreak >= minStart) return nbreak + 1;
    int period = text.lastIndexOf(". ", preferredEnd);
    if (period >= minStart) return period + 2;
    return preferredEnd;
  }

  private static String formatSectionSummaries(List<String> summaries) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < summaries.size(); i++) {
      sb.append("## Section ").append(i + 1).append("\n");
      sb.append(summaries.get(i).trim()).append("\n\n");
    }
    return sb.toString().trim();
  }

  // ---- Misc ----

  private static Map<String, Object> msg(String role, String content) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", role);
    m.put("content", content);
    return m;
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static void emitProgress(Consumer<SseEvent> sink, String phase, String message) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("phase", phase);
    p.put("message", message);
    sink.accept(new SseEvent("progress", p));
  }

  private static void emitError(Consumer<SseEvent> sink, String message, String errorCode) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("error", message);
    p.put("errorCode", errorCode);
    p.put("i18nKey", "errors." + errorCode);
    sink.accept(new SseEvent("error", p));
  }

}
