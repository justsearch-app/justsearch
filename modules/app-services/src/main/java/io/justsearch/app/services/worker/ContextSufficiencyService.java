/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Post-search context sufficiency classification (363). Uses the local LLM to determine whether
 * retrieved context contains enough information to answer the user's question.
 *
 * <p>Designed for the {@code retrieve-context} path (agent-facing RAG). The binary result is
 * combined with quality signals (coverage, best_score) by the caller to produce a reliable
 * answerability signal.
 */
public final class ContextSufficiencyService {
  private static final Logger log = LoggerFactory.getLogger(ContextSufficiencyService.class);

  /** Max tokens for sufficiency output (just {"sufficient": true/false}). */
  private static final int MAX_TOKENS = 32;

  /** Hard deadline before falling back to no sufficiency signal. */
  private static final long DEADLINE_MS = 5000L;

  private static final SamplingParams SAMPLING;
  private static final String PROMPT_TEMPLATE;
  private static final Map<String, Object> RESPONSE_FORMAT_SCHEMA;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    String template;
    try (InputStream is =
        ContextSufficiencyService.class.getResourceAsStream("/qu/sufficiency.v1.txt")) {
      if (is == null) throw new IOException("Sufficiency prompt not found on classpath");
      template =
          new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
              .lines()
              .collect(Collectors.joining("\n"));
    } catch (IOException e) {
      log.warn("Failed to load sufficiency prompt: {}", e.getMessage());
      template = null;
    }
    PROMPT_TEMPLATE = template;

    Map<String, Object> schema;
    try (InputStream is =
        ContextSufficiencyService.class.getResourceAsStream("/qu/sufficiency.v1.schema.json")) {
      if (is == null) throw new IOException("Sufficiency schema not found on classpath");
      String json =
          new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
              .lines()
              .collect(Collectors.joining("\n"));
      schema = MAPPER.readValue(json, new TypeReference<>() {});
    } catch (IOException e) {
      log.warn("Failed to load sufficiency schema: {}", e.getMessage());
      schema = null;
    }
    RESPONSE_FORMAT_SCHEMA = schema;

    if (RESPONSE_FORMAT_SCHEMA != null) {
      // DETERMINISTIC carries enable_thinking:false for every mechanical call (tempdoc 835 §10f).
      SAMPLING =
          SamplingParams.DETERMINISTIC.withResponseFormat(
              Map.of("type", "json_object", "schema", RESPONSE_FORMAT_SCHEMA));
    } else {
      SAMPLING = null;
    }
  }

  private final OnlineAiService aiService;

  public ContextSufficiencyService(OnlineAiService aiService) {
    this.aiService = Objects.requireNonNull(aiService, "aiService");
    if (PROMPT_TEMPLATE != null && SAMPLING != null) {
      log.info("ContextSufficiencyService initialized");
    } else {
      log.warn("ContextSufficiencyService initialized but DISABLED (missing resources)");
    }
  }

  /** Returns true if the service has all resources loaded and the AI service is available. */
  public boolean isAvailable() {
    return PROMPT_TEMPLATE != null && SAMPLING != null && aiService.isAvailable();
  }

  /**
   * Classifies whether the retrieved context is sufficient to answer the query.
   *
   * @param query the user's question
   * @param contextText the assembled context string from retrieve-context
   * @return future containing true (sufficient), false (insufficient), or null on failure
   */
  public CompletableFuture<SufficiencyResult> classify(String query, String contextText,
      io.justsearch.core.context.EngineContext engineContext) {
    if (!isAvailable()) {
      return CompletableFuture.completedFuture(null);
    }

    String prompt =
        PROMPT_TEMPLATE + "\n\nQUESTION: " + query + "\n\nSEARCH RESULTS:\n" + contextText;
    List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));

    long startNs = System.nanoTime();
    return aiService
        .chatCompletion(messages, MAX_TOKENS, SAMPLING, engineContext)
        .orTimeout(DEADLINE_MS, TimeUnit.MILLISECONDS)
        .thenApply(
            json -> {
              long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
              return parseResponse(json, elapsedMs);
            })
        .exceptionally(
            ex -> {
              io.justsearch.core.execution.EngineFutures.rethrowExecutorRefusal(ex);
              long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
              log.debug("Sufficiency check failed after {}ms: {}", elapsedMs, ex.getMessage());
              return null;
            });
  }

  /** Result of sufficiency classification. */
  public record SufficiencyResult(boolean sufficient, long latencyMs) {}

  private static SufficiencyResult parseResponse(String json, long latencyMs) {
    if (json == null || json.isBlank()) {
      log.debug("Sufficiency check returned empty response");
      return null;
    }
    try {
      Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});
      Object val = parsed.get("sufficient");
      if (val instanceof Boolean b) {
        log.debug("Sufficiency check: sufficient={}, latency={}ms", b, latencyMs);
        return new SufficiencyResult(b, latencyMs);
      }
      log.debug("Sufficiency check: unexpected value type for 'sufficient': {}", val);
      return null;
    } catch (Exception e) {
      log.debug("Sufficiency check parse error: {}", e.getMessage());
      return null;
    }
  }
}
