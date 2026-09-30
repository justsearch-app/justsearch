/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transparent query understanding layer (363). Uses the local LLM to extract structured filters
 * from natural language queries and maps them to soft-boost filters.
 *
 * <p>The LLM call uses {@code response_format} JSON schema constraints and {@code
 * enableThinking(false)} for direct extraction without reasoning overhead.
 */
public final class QueryUnderstandingService {
  private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

  /** Max tokens for QU extraction (output is ~50-100 tokens JSON). */
  private static final int QU_MAX_TOKENS = 256;

  /** Hard deadline for the QU LLM call before falling back to no-boost search. */
  private static final long QU_DEADLINE_MS = 8000L; // 363 original: 2000L; raised for LLM queueing (366)

  /** Sampling params: low temperature, no thinking, response_format for JSON schema. */
  private static final SamplingParams QU_SAMPLING;

  /** The system prompt template loaded from SSOT resource. */
  private static final String PROMPT_TEMPLATE;

  /** The JSON schema for response_format, loaded from SSOT resource. */
  private static final Map<String, Object> RESPONSE_FORMAT_SCHEMA;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    // Load prompt template
    String template;
    try (InputStream is =
        QueryUnderstandingService.class.getResourceAsStream("/qu/qu.v1.txt")) {
      if (is == null) throw new IOException("QU prompt template not found on classpath");
      template =
          new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
              .lines()
              .collect(Collectors.joining("\n"));
    } catch (IOException e) {
      log.warn("Failed to load QU prompt template, QU will be disabled: {}", e.getMessage());
      template = null;
    }
    PROMPT_TEMPLATE = template;

    // Load JSON schema
    Map<String, Object> schema;
    try (InputStream is =
        QueryUnderstandingService.class.getResourceAsStream("/qu/qu-intent.v1.schema.json")) {
      if (is == null) throw new IOException("QU schema not found on classpath");
      String schemaJson =
          new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
              .lines()
              .collect(Collectors.joining("\n"));
      schema = MAPPER.readValue(schemaJson, new TypeReference<>() {});
    } catch (IOException e) {
      log.warn("Failed to load QU schema, QU will be disabled: {}", e.getMessage());
      schema = null;
    }
    RESPONSE_FORMAT_SCHEMA = schema;

    // Build sampling params: deterministic (which now carries enable_thinking:false — tempdoc 835
    // §10f) plus the response_format schema.
    if (RESPONSE_FORMAT_SCHEMA != null) {
      QU_SAMPLING =
          SamplingParams.DETERMINISTIC.withResponseFormat(
              Map.of("type", "json_object", "schema", RESPONSE_FORMAT_SCHEMA));
    } else {
      QU_SAMPLING = null;
    }
  }

  private final OnlineAiService aiService;

  public QueryUnderstandingService(OnlineAiService aiService) {
    this.aiService = Objects.requireNonNull(aiService, "aiService");
    if (PROMPT_TEMPLATE != null && QU_SAMPLING != null) {
      log.info("QueryUnderstandingService initialized (prompt loaded, schema loaded)");
    } else {
      log.warn("QueryUnderstandingService initialized but DISABLED (missing resources)");
    }
  }

  /**
   * Returns true if QU is explicitly enabled, has all resources loaded, and the AI service is
   * available. Disabled by default (366 — experimental). Enable via JUSTSEARCH_QU_ENABLED=true.
   */
  public boolean isAvailable() {
    return io.justsearch.configuration.EnvRegistry.QU_ENABLED.getBoolean(false)
        && PROMPT_TEMPLATE != null
        && QU_SAMPLING != null
        && aiService.isAvailable();
  }

  /**
   * Extracts soft-boost filters from the query using the local LLM.
   *
   * @param query the user's raw search query
   * @param indexSnapshot optional index snapshot text for grounding (facet values, date range); null
   *     or empty to skip grounding
   * @return a future containing the boost filters, or null if extraction failed or was skipped
   */
  public CompletableFuture<QuResult> extract(String query, String indexSnapshot,
      io.justsearch.core.context.EngineContext engineContext) {
    if (!isAvailable()) {
      return CompletableFuture.completedFuture(null);
    }

    String systemPrompt = buildSystemPrompt(indexSnapshot);
    List<Map<String, Object>> messages =
        List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", "Query: \"" + query + "\""));

    long startNs = System.nanoTime();
    return aiService
        .chatCompletion(messages, QU_MAX_TOKENS, QU_SAMPLING, engineContext)
        .orTimeout(QU_DEADLINE_MS, TimeUnit.MILLISECONDS)
        .thenApply(
            json -> {
              long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
              log.debug("QU extraction completed in {}ms", elapsedMs);
              return parseResponse(json, elapsedMs);
            })
        .exceptionally(
            ex -> {
              io.justsearch.core.execution.EngineFutures.rethrowExecutorRefusal(ex);
              long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
              log.debug("QU extraction failed after {}ms: {}", elapsedMs, ex.getMessage());
              return null;
            });
  }

  /** Result of query understanding extraction. */
  public record QuResult(
      String refinedQuery, KnowledgeSearchRequest.Filters boostFilters, long latencyMs) {}

  private static String buildSystemPrompt(String indexSnapshot) {
    String prompt = PROMPT_TEMPLATE;
    if (indexSnapshot != null && !indexSnapshot.isBlank()) {
      prompt = prompt.replace("{{INDEX_SNAPSHOT}}", indexSnapshot);
    } else {
      prompt = prompt.replace("{{INDEX_SNAPSHOT}}", "");
    }
    prompt = prompt.replace("{{TODAY}}", LocalDate.now(java.time.ZoneId.systemDefault()).toString());
    return prompt;
  }

  @SuppressWarnings("unchecked")
  private static QuResult parseResponse(String json, long latencyMs) {
    if (json == null || json.isBlank()) {
      log.debug("QU returned empty response");
      return null;
    }

    try {
      Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});

      String refinedQuery = (String) parsed.get("query");
      if (refinedQuery == null || refinedQuery.isBlank()) {
        log.debug("QU returned no query field");
        return null;
      }

      // Extract filter fields from flat JSON
      List<String> metaSource = toStringList(parsed.get("meta_source"));
      List<String> metaAuthor = toStringList(parsed.get("meta_author"));
      List<String> metaCategory = toStringList(parsed.get("meta_category"));
      List<String> entityPersons = toStringList(parsed.get("entity_persons"));
      List<String> entityOrgs = toStringList(parsed.get("entity_organizations"));
      List<String> entityLocations = toStringList(parsed.get("entity_locations"));

      // Date range parsing
      String pubAfter = (String) parsed.get("meta_published_after");
      String pubBefore = (String) parsed.get("meta_published_before");
      KnowledgeSearchRequest.TimeRangeMs metaPub = parseDateRange(pubAfter, pubBefore);

      // Check if any filters were actually extracted
      boolean hasFilters =
          !metaSource.isEmpty()
              || !metaAuthor.isEmpty()
              || !metaCategory.isEmpty()
              || !entityPersons.isEmpty()
              || !entityOrgs.isEmpty()
              || !entityLocations.isEmpty()
              || metaPub != null;

      if (!hasFilters) {
        log.debug("QU extracted no filters (passthrough), latency={}ms", latencyMs);
        return new QuResult(refinedQuery, null, latencyMs);
      }

      KnowledgeSearchRequest.Filters boostFilters =
          KnowledgeSearchRequestFiltersBuilder.builder()
              .entityPersons(entityPersons)
              .entityOrganizations(entityOrgs)
              .entityLocations(entityLocations)
              .metaSource(metaSource)
              .metaAuthor(metaAuthor)
              .metaCategory(metaCategory)
              .metaPublishedAt(metaPub)
              .build();

      log.debug(
          "QU extracted boost filters: source={}, author={}, category={}, persons={}, orgs={},"
              + " locations={}, dates={}, latency={}ms",
          metaSource,
          metaAuthor,
          metaCategory,
          entityPersons,
          entityOrgs,
          entityLocations,
          metaPub != null,
          latencyMs);

      return new QuResult(refinedQuery, boostFilters, latencyMs);
    } catch (Exception e) {
      log.debug("QU JSON parse error: {}", e.getMessage());
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> toStringList(Object obj) {
    if (obj instanceof List<?> list) {
      return list.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .filter(s -> !s.isBlank())
          .toList();
    }
    return List.of();
  }

  /** Parses ISO date strings into epoch millis for the TimeRangeMs record. */
  private static KnowledgeSearchRequest.TimeRangeMs parseDateRange(
      String afterStr, String beforeStr) {
    Long fromMs = parseIsoDateToEpochMs(afterStr);
    Long toMs = parseIsoDateToEpochMs(beforeStr);
    if (fromMs == null && toMs == null) return null;
    return new KnowledgeSearchRequest.TimeRangeMs(fromMs, toMs);
  }

  private static Long parseIsoDateToEpochMs(String isoDate) {
    if (isoDate == null || isoDate.isBlank()) return null;
    try {
      LocalDate date = LocalDate.parse(isoDate);
      return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    } catch (Exception e) {
      log.debug("QU date parse error for '{}': {}", isoDate, e.getMessage());
      return null;
    }
  }
}
