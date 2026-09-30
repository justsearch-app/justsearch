/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hybrid filter value normalization (366). Maps approximate agent-provided filter values to exact
 * indexed values using a two-tier approach:
 *
 * <ol>
 *   <li><b>Deterministic matching</b> (always, 0ms): exact, prefix, and contains matching against
 *       the facet vocabulary. Handles variant expansion (FOX News → all fox news-* entries),
 *       exact match (fortune → fortune), and absence detection (Bloomberg → no match).
 *   <li><b>LLM fallback</b> (only when deterministic fails): for semantic gaps that no string
 *       algorithm can bridge (e.g., CBS Sports → cbssports.com). Uses the local LLM with a
 *       plain-text prompt.
 * </ol>
 *
 * <p>Fires only when explicit filters are present — mutually exclusive with QU (which fires only
 * when explicit filters are absent).
 */
public final class FilterNormalizationService {
  private static final Logger log = LoggerFactory.getLogger(FilterNormalizationService.class);

  /** Max tokens for normalization output (typically <50 tokens). */
  private static final int NORM_MAX_TOKENS = 128;

  /** Hard deadline for the LLM normalization call. Matches QU deadline (366). */
  private static final long NORM_DEADLINE_MS = 8000L;

  /**
   * No response_format — plain text for speed (avoids grammar-constrained decoding). Thinking is
   * off because {@link SamplingParams#DETERMINISTIC} carries it for every mechanical call (835 §10f).
   */
  private static final SamplingParams NORM_SAMPLING = SamplingParams.DETERMINISTIC;

  /** Pattern to parse "input -> output" lines from LLM response. */
  private static final Pattern ARROW_LINE = Pattern.compile("^(.+?)\\s*->\\s*(.+)$");

  /** Prompt template loaded from classpath. */
  private static final String PROMPT_TEMPLATE;

  static {
    String template;
    try (InputStream is =
        FilterNormalizationService.class.getResourceAsStream("/qu/filter-norm.v1.txt")) {
      if (is == null) throw new IOException("filter-norm.v1.txt not found on classpath");
      template =
          new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
              .lines()
              .collect(Collectors.joining("\n"));
    } catch (IOException e) {
      log.warn("Failed to load filter normalization prompt: {}", e.getMessage());
      template = null;
    }
    PROMPT_TEMPLATE = template;
  }

  private final OnlineAiService aiService;

  public FilterNormalizationService(OnlineAiService aiService) {
    this.aiService = Objects.requireNonNull(aiService, "aiService");
    if (PROMPT_TEMPLATE != null) {
      log.info("FilterNormalizationService initialized (prompt loaded)");
    } else {
      log.warn("FilterNormalizationService initialized but DISABLED (missing prompt)");
    }
  }

  /**
   * Returns true if the full normalization pipeline (deterministic + LLM) is available.
   * Used by callers that want the best possible normalization.
   */
  public boolean isAvailable() {
    return io.justsearch.configuration.EnvRegistry.FILTER_NORM_ENABLED.getBoolean(false)
        && PROMPT_TEMPLATE != null
        && aiService.isAvailable();
  }

  /**
   * Returns true if deterministic normalization (exact/prefix/contains matching) is available.
   * Always true — deterministic matching is zero-cost, zero-risk, and requires no LLM.
   * The normalize() method handles LLM unavailability gracefully by falling back to
   * deterministic results + lowercased originals for unresolved values.
   */
  public boolean isDeterministicAvailable() {
    return true;
  }

  /**
   * Normalizes metadata filter values against the facet vocabulary using a hybrid approach:
   * deterministic string matching first, LLM fallback only for unresolved values.
   *
   * @param filters the agent-provided filters (values may be approximate)
   * @param facetSnapshot the cached facet snapshot text from the adapter
   * @return a future containing the normalization result, never null
   */
  public CompletableFuture<NormResult> normalize(
      KnowledgeSearchRequest.Filters filters, String facetSnapshot,
      io.justsearch.core.context.EngineContext engineContext) {
    if (filters == null) {
      return CompletableFuture.completedFuture(null);
    }

    // Lowercase all metadata values (always)
    List<String> sources = lowercase(filters.metaSource());
    List<String> authors = lowercase(filters.metaAuthor());
    List<String> categories = lowercase(filters.metaCategory());

    // Parse vocabulary from facet snapshot
    Set<String> knownSources = parseFacetValues(facetSnapshot, "meta_source");
    Set<String> knownAuthors = parseFacetValues(facetSnapshot, "meta_author");
    Set<String> knownCategories = parseFacetValues(facetSnapshot, "meta_category");

    log.debug("Filter norm vocabulary: sources={}, authors={}, categories={}",
        knownSources.size(), knownAuthors.size(), knownCategories.size());

    // Deterministic matching: exact → prefix → contains (0ms)
    var resolvedSources = deterministicMatch(sources, knownSources);
    var resolvedAuthors = deterministicMatch(authors, knownAuthors);
    var resolvedCategories = deterministicMatch(categories, knownCategories);

    // Check if all values were resolved deterministically
    boolean allResolved = resolvedSources.unresolved().isEmpty()
        && resolvedAuthors.unresolved().isEmpty()
        && resolvedCategories.unresolved().isEmpty();

    List<String> finalSources = resolvedSources.resolved();
    List<String> finalAuthors = resolvedAuthors.resolved();
    List<String> finalCategories = resolvedCategories.resolved();

    String source = resolvedSources.anyMatched() || resolvedAuthors.anyMatched()
        || resolvedCategories.anyMatched() ? "deterministic" : "exact_match";

    if (allResolved) {
      KnowledgeSearchRequest.Filters normalized = rebuildFilters(filters, finalSources, finalAuthors, finalCategories);
      return CompletableFuture.completedFuture(
          new NormResult(normalized, buildOriginalMap(filters),
              buildNormalizedMap(finalSources, finalAuthors, finalCategories), 0L, source));
    }

    // LLM fallback: only for values that deterministic matching couldn't resolve
    if (!isAvailable()) {
      // No LLM available — use deterministic results + lowercased originals for unresolved
      finalSources = mergeResolved(resolvedSources);
      finalAuthors = mergeResolved(resolvedAuthors);
      finalCategories = mergeResolved(resolvedCategories);
      KnowledgeSearchRequest.Filters normalized = rebuildFilters(filters, finalSources, finalAuthors, finalCategories);
      return CompletableFuture.completedFuture(
          new NormResult(normalized, buildOriginalMap(filters),
              buildNormalizedMap(finalSources, finalAuthors, finalCategories), 0L, "deterministic"));
    }

    // Collect unresolved values for LLM
    var needsLlm = new LinkedHashMap<String, List<String>>();
    if (!resolvedSources.unresolved().isEmpty()) needsLlm.put("meta_source", resolvedSources.unresolved());
    if (!resolvedAuthors.unresolved().isEmpty()) needsLlm.put("meta_author", resolvedAuthors.unresolved());
    if (!resolvedCategories.unresolved().isEmpty()) needsLlm.put("meta_category", resolvedCategories.unresolved());

    Map<String, Set<String>> vocabularies = Map.of(
        "meta_source", knownSources,
        "meta_author", knownAuthors,
        "meta_category", knownCategories);

    String prompt = buildPrompt(needsLlm, facetSnapshot);
    List<Map<String, Object>> messages =
        List.of(
            Map.of("role", "system", "content", prompt),
            Map.of("role", "user", "content", buildUserMessage(needsLlm)));

    // Capture resolved values for use in async callback
    final var capturedSources = resolvedSources;
    final var capturedAuthors = resolvedAuthors;
    final var capturedCategories = resolvedCategories;

    long startNs = System.nanoTime();
    return aiService
        .chatCompletion(messages, NORM_MAX_TOKENS, NORM_SAMPLING, engineContext)
        .orTimeout(NORM_DEADLINE_MS, TimeUnit.MILLISECONDS)
        .thenApply(
            response -> {
              long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
              log.debug("Filter normalization LLM completed in {}ms", elapsedMs);
              return mergeLlmResponse(response, filters, capturedSources, capturedAuthors,
                  capturedCategories, vocabularies, elapsedMs);
            })
        .exceptionally(
            ex -> {
              io.justsearch.core.execution.EngineFutures.rethrowExecutorRefusal(ex);
              long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
              log.debug("Filter normalization LLM failed/timeout after {}ms: {}", elapsedMs, ex.getMessage());
              // Fallback: use deterministic results + lowercased originals for unresolved
              var fallbackSources = mergeResolved(capturedSources);
              var fallbackAuthors = mergeResolved(capturedAuthors);
              var fallbackCategories = mergeResolved(capturedCategories);
              KnowledgeSearchRequest.Filters normalized = rebuildFilters(filters, fallbackSources, fallbackAuthors, fallbackCategories);
              return new NormResult(normalized, buildOriginalMap(filters),
                  buildNormalizedMap(fallbackSources, fallbackAuthors, fallbackCategories), elapsedMs, "timeout");
            });
  }

  // ==================== Deterministic Matching ====================

  /** Result of deterministic matching for a single field's values. */
  record MatchResult(List<String> resolved, List<String> unresolved, boolean anyMatched) {}

  /**
   * Matches filter values against the vocabulary using exact → prefix → contains cascade.
   * Returns resolved values (mapped to vocabulary entries) and unresolved values (need LLM).
   */
  static MatchResult deterministicMatch(List<String> values, Set<String> vocabulary) {
    if (values.isEmpty()) return new MatchResult(List.of(), List.of(), false);
    if (vocabulary.isEmpty()) return new MatchResult(List.of(), values, false);

    List<String> resolved = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    boolean anyMatched = false;

    for (String v : values) {
      // 1. Exact match
      if (vocabulary.contains(v)) {
        resolved.add(v);
        continue;
      }

      // 2. Prefix match: find all vocabulary entries that START WITH this value
      List<String> prefixMatches = vocabulary.stream()
          .filter(known -> known.startsWith(v))
          .toList();
      if (!prefixMatches.isEmpty()) {
        resolved.addAll(prefixMatches);
        anyMatched = true;
        log.debug("Filter norm prefix match: {} -> {}", v, prefixMatches);
        continue;
      }

      // 3. Contains match (min 3 chars): find vocabulary entries that CONTAIN this value
      if (v.length() >= 3) {
        List<String> containsMatches = vocabulary.stream()
            .filter(known -> known.contains(v))
            .toList();
        if (!containsMatches.isEmpty() && containsMatches.size() <= 5) {
          resolved.addAll(containsMatches);
          anyMatched = true;
          log.debug("Filter norm contains match: {} -> {}", v, containsMatches);
          continue;
        }
      }

      // No deterministic match — needs LLM
      unresolved.add(v);
    }

    return new MatchResult(List.copyOf(resolved), List.copyOf(unresolved), anyMatched);
  }

  /** Merges resolved values with unresolved (kept as-is for fallback). */
  private static List<String> mergeResolved(MatchResult result) {
    var merged = new ArrayList<>(result.resolved());
    merged.addAll(result.unresolved());
    return merged;
  }

  // ==================== Prompt Building ====================

  private String buildPrompt(Map<String, List<String>> needsNorm, String facetSnapshot) {
    if (PROMPT_TEMPLATE == null) return "";

    // Extract just the relevant vocabulary lines from the snapshot
    var vocabLines = new StringBuilder();
    for (String field : needsNorm.keySet()) {
      String line = extractFieldLine(facetSnapshot, field);
      if (line != null) {
        vocabLines.append("Known ").append(field).append(" values: ").append(line).append("\n");
      }
    }

    return PROMPT_TEMPLATE
        .replace("{{VOCABULARY}}", vocabLines.toString().strip());
  }

  private String buildUserMessage(Map<String, List<String>> needsNorm) {
    var sb = new StringBuilder();
    for (var entry : needsNorm.entrySet()) {
      sb.append(entry.getKey()).append(": ");
      sb.append(String.join(", ", entry.getValue()));
      sb.append("\n");
    }
    return sb.toString().strip();
  }

  // ==================== LLM Response Merging ====================

  /** Merges deterministic results with LLM output for unresolved values. */
  private NormResult mergeLlmResponse(
      String response,
      KnowledgeSearchRequest.Filters originalFilters,
      MatchResult sourceMatch,
      MatchResult authorMatch,
      MatchResult categoryMatch,
      Map<String, Set<String>> vocabularies,
      long elapsedMs) {

    // Parse LLM response for unresolved values
    Map<String, List<String>> llmMappings = parseLlmResponse(response);

    // Merge: deterministic resolved + LLM resolved for unresolved values
    List<String> finalSources = mergeDeterministicAndLlm(
        sourceMatch, llmMappings, vocabularies.getOrDefault("meta_source", Set.of()));
    List<String> finalAuthors = mergeDeterministicAndLlm(
        authorMatch, llmMappings, vocabularies.getOrDefault("meta_author", Set.of()));
    List<String> finalCategories = mergeDeterministicAndLlm(
        categoryMatch, llmMappings, vocabularies.getOrDefault("meta_category", Set.of()));

    KnowledgeSearchRequest.Filters normalized = rebuildFilters(originalFilters, finalSources, finalAuthors, finalCategories);
    return new NormResult(normalized, buildOriginalMap(originalFilters),
        buildNormalizedMap(finalSources, finalAuthors, finalCategories), elapsedMs, "hybrid");
  }

  /** Parses the LLM's "input -> output" response into a mapping. */
  private Map<String, List<String>> parseLlmResponse(String response) {
    Map<String, List<String>> mappings = new LinkedHashMap<>();
    if (response == null || response.isBlank()) return mappings;

    log.debug("Filter norm LLM response: [{}]", response.replace("\n", "\\n"));

    for (String line : response.strip().split("\n")) {
      Matcher m = ARROW_LINE.matcher(line.strip());
      if (!m.matches()) continue;
      String input = m.group(1).strip().toLowerCase(Locale.ROOT);
      String output = m.group(2).strip();

      if ("NO_MATCH".equalsIgnoreCase(output) || "no_match".equals(output)) {
        mappings.put(input, List.of()); // Explicit no-match
        continue;
      }

      List<String> matches = Arrays.stream(output.split(","))
          .map(s -> s.strip().toLowerCase(Locale.ROOT))
          .filter(s -> !s.isBlank())
          .toList();
      mappings.put(input, matches);
    }
    return mappings;
  }

  /** Combines deterministic resolved values with LLM results for unresolved values. */
  private List<String> mergeDeterministicAndLlm(
      MatchResult deterministicResult, Map<String, List<String>> llmMappings, Set<String> vocabulary) {
    List<String> result = new ArrayList<>(deterministicResult.resolved());

    for (String unresolved : deterministicResult.unresolved()) {
      List<String> llmMatch = llmMappings.get(unresolved);
      if (llmMatch != null && !llmMatch.isEmpty()) {
        // Validate LLM output against vocabulary
        for (String m : llmMatch) {
          if (vocabulary.isEmpty() || vocabulary.contains(m)) {
            result.add(m);
          } else {
            log.debug("Rejecting hallucinated LLM normalization: {} -> {} (not in vocabulary)", unresolved, m);
          }
        }
      } else if (llmMatch != null && llmMatch.isEmpty()) {
        // LLM explicitly said NO_MATCH — drop this value
        log.debug("Filter norm: LLM returned NO_MATCH for {}", unresolved);
      } else {
        // LLM didn't return anything for this value — keep lowercased original
        result.add(unresolved);
      }
    }
    return result;
  }

  // ==================== Facet Snapshot Parsing ====================

  /**
   * Extracts the value list for a field from the facet snapshot text.
   * Format: "meta_source: the verge (1523), techcrunch (892), ..."
   */
  static Set<String> parseFacetValues(String snapshot, String fieldName) {
    if (snapshot == null || snapshot.isBlank()) return Set.of();
    String line = extractFieldLine(snapshot, fieldName);
    if (line == null) return Set.of();

    Set<String> values = new LinkedHashSet<>();
    // Split by comma, strip count suffix like " (123)"
    for (String entry : line.split(",")) {
      String trimmed = entry.strip().replaceAll("\\s*\\(\\d+\\)\\s*$", "").strip();
      if (!trimmed.isBlank()) {
        values.add(trimmed.toLowerCase(Locale.ROOT));
      }
    }
    return values;
  }

  /** Extracts the line for a specific field from the snapshot text. */
  private static String extractFieldLine(String snapshot, String fieldName) {
    for (String line : snapshot.split("\n")) {
      String trimmed = line.strip();
      String prefix = fieldName + ":";
      if (trimmed.startsWith(prefix)) {
        return trimmed.substring(prefix.length()).strip();
      }
    }
    return null;
  }

  // ==================== Utilities ====================

  private static List<String> lowercase(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.stream()
        .filter(v -> v != null && !v.isBlank())
        .map(v -> v.toLowerCase(Locale.ROOT))
        .toList();
  }

  private static KnowledgeSearchRequest.Filters rebuildFilters(
      KnowledgeSearchRequest.Filters original,
      List<String> sources,
      List<String> authors,
      List<String> categories) {
    return KnowledgeSearchRequestFiltersBuilder.builder()
        .mime(original.mime())
        .language(original.language())
        .fileKind(original.fileKind())
        .mimeBase(original.mimeBase())
        .pathPrefix(original.pathPrefix())
        .includeChunks(original.includeChunks())
        .modifiedAt(original.modifiedAt())
        .entityPersons(original.entityPersons())
        .entityOrganizations(original.entityOrganizations())
        .entityLocations(original.entityLocations())
        .metaSource(sources)
        .metaAuthor(authors)
        .metaCategory(categories)
        .metaPublishedAt(original.metaPublishedAt())
        .docIds(original.docIds())
        .build();
  }

  private static Map<String, List<String>> buildOriginalMap(KnowledgeSearchRequest.Filters filters) {
    var m = new LinkedHashMap<String, List<String>>();
    if (!filters.metaSource().isEmpty()) m.put("meta_source", filters.metaSource());
    if (!filters.metaAuthor().isEmpty()) m.put("meta_author", filters.metaAuthor());
    if (!filters.metaCategory().isEmpty()) m.put("meta_category", filters.metaCategory());
    return m;
  }

  private static Map<String, List<String>> buildNormalizedMap(
      List<String> sources, List<String> authors, List<String> categories) {
    var m = new LinkedHashMap<String, List<String>>();
    if (!sources.isEmpty()) m.put("meta_source", sources);
    if (!authors.isEmpty()) m.put("meta_author", authors);
    if (!categories.isEmpty()) m.put("meta_category", categories);
    return m;
  }

  /** Result of filter value normalization. */
  public record NormResult(
      KnowledgeSearchRequest.Filters normalizedFilters,
      Map<String, List<String>> original,
      Map<String, List<String>> normalized,
      long latencyMs,
      String source) {} // "llm", "case_only", "exact_match", "timeout"
}
