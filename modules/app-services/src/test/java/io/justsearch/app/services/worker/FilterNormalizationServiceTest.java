package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FilterNormalizationService")
class FilterNormalizationServiceTest {
  private static final io.justsearch.core.context.EngineContext TEST_CONTEXT =
      io.justsearch.app.services.intent.EngineProvenance.internal("filter-test",
          io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
          io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);

  @Mock OnlineAiService aiService;

  private static final String FACET_SNAPSHOT =
      """
      Known index contents:
      meta_source: the verge (45), techcrunch (97), cnbc | world business news leader (7), fortune (25), the independent - life and style (27), the independent - sports (5), the independent - travel (4), fox news - health (10), fox news - lifestyle (14), fox news - entertainment (5), cbssports.com (16)
      meta_author: john doe (10), jane smith (5)
      meta_category: sports (180), technology (150), entertainment (90)""";

  private FilterNormalizationService service;

  @BeforeEach
  void setUp() {
    service = new FilterNormalizationService(aiService);
  }

  private static KnowledgeSearchRequest.Filters filtersWithSource(String... sources) {
    return KnowledgeSearchRequestFiltersBuilder.builder()
        .metaSource(List.of(sources))
        .build();
  }

  @Nested
  @DisplayName("parseFacetValues")
  class ParseFacetValues {

    @Test
    void extractsSourceValues() {
      Set<String> sources = FilterNormalizationService.parseFacetValues(FACET_SNAPSHOT, "meta_source");
      assertTrue(sources.contains("the verge"));
      assertTrue(sources.contains("techcrunch"));
      assertTrue(sources.contains("cnbc | world business news leader"));
      assertTrue(sources.contains("fortune"));
      assertTrue(sources.contains("the independent - life and style"));
      assertTrue(sources.contains("cbssports.com"));
    }

    @Test
    void extractsCategoryValues() {
      Set<String> categories = FilterNormalizationService.parseFacetValues(FACET_SNAPSHOT, "meta_category");
      assertTrue(categories.contains("sports"));
      assertTrue(categories.contains("technology"));
      assertTrue(categories.contains("entertainment"));
    }

    @Test
    void returnsEmptyForMissingField() {
      Set<String> empty = FilterNormalizationService.parseFacetValues(FACET_SNAPSHOT, "nonexistent");
      assertTrue(empty.isEmpty());
    }

    @Test
    void handlesNullSnapshot() {
      Set<String> empty = FilterNormalizationService.parseFacetValues(null, "meta_source");
      assertTrue(empty.isEmpty());
    }

    @Test
    void handlesBlankSnapshot() {
      Set<String> empty = FilterNormalizationService.parseFacetValues("", "meta_source");
      assertTrue(empty.isEmpty());
    }
  }

  @Nested
  @DisplayName("deterministicMatch")
  class DeterministicMatching {

    private final Set<String> SOURCES = Set.of(
        "the verge", "techcrunch", "cnbc | world business news leader", "fortune",
        "the independent - life and style", "the independent - sports",
        "the independent - travel", "fox news - health", "fox news - lifestyle",
        "fox news - entertainment", "cbssports.com");

    @Test
    void exactMatch() {
      var result = FilterNormalizationService.deterministicMatch(List.of("fortune"), SOURCES);
      assertEquals(List.of("fortune"), result.resolved());
      assertTrue(result.unresolved().isEmpty());
      assertFalse(result.anyMatched());  // exact match, not prefix/contains
    }

    @Test
    void prefixExpandsToAllVariants() {
      var result = FilterNormalizationService.deterministicMatch(List.of("fox news"), SOURCES);
      assertTrue(result.resolved().containsAll(List.of(
          "fox news - health", "fox news - lifestyle", "fox news - entertainment")));
      assertEquals(3, result.resolved().size());
      assertTrue(result.unresolved().isEmpty());
      assertTrue(result.anyMatched());
    }

    @Test
    void prefixExpandsIndependent() {
      var result = FilterNormalizationService.deterministicMatch(List.of("the independent"), SOURCES);
      assertTrue(result.resolved().containsAll(List.of(
          "the independent - life and style", "the independent - sports",
          "the independent - travel")));
      assertEquals(3, result.resolved().size());
      assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void containsMatchWorks() {
      // "cnbc" is contained in "cnbc | world business news leader"
      var result = FilterNormalizationService.deterministicMatch(List.of("cnbc"), SOURCES);
      assertEquals(List.of("cnbc | world business news leader"), result.resolved());
      assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void noMatchGoesToUnresolved() {
      var result = FilterNormalizationService.deterministicMatch(List.of("bloomberg"), SOURCES);
      assertTrue(result.resolved().isEmpty());
      assertEquals(List.of("bloomberg"), result.unresolved());
    }

    @Test
    void semanticGapGoesToUnresolved() {
      // "cbs sports" has no prefix/contains match for "cbssports.com"
      var result = FilterNormalizationService.deterministicMatch(List.of("cbs sports"), SOURCES);
      assertTrue(result.resolved().isEmpty());
      assertEquals(List.of("cbs sports"), result.unresolved());
    }

    @Test
    void mixedResolution() {
      var result = FilterNormalizationService.deterministicMatch(
          List.of("fortune", "bloomberg", "fox news"), SOURCES);
      // fortune = exact, fox news = prefix (3 variants), bloomberg = unresolved
      assertTrue(result.resolved().contains("fortune"));
      assertTrue(result.resolved().contains("fox news - health"));
      assertEquals(List.of("bloomberg"), result.unresolved());
    }

    @Test
    void emptyVocabularyAllUnresolved() {
      var result = FilterNormalizationService.deterministicMatch(List.of("fortune"), Set.of());
      assertTrue(result.resolved().isEmpty());
      assertEquals(List.of("fortune"), result.unresolved());
    }

    @Test
    void shortContainsSkipped() {
      // "zz" is <3 chars with no prefix match — contains matching should be skipped
      var result = FilterNormalizationService.deterministicMatch(List.of("zz"), SOURCES);
      assertTrue(result.resolved().isEmpty());
      assertEquals(List.of("zz"), result.unresolved());
    }
  }

  @Nested
  @DisplayName("normalize — short-circuit")
  class ShortCircuit {

    @Test
    void exactMatchSkipsLlm() throws Exception {
      // "techcrunch" exactly matches a known value
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);

      var result = service.normalize(filtersWithSource("techcrunch"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("exact_match", result.source());
      assertEquals(0L, result.latencyMs());
      assertEquals(List.of("techcrunch"), result.normalizedFilters().metaSource());
      verify(aiService, never()).chatCompletion(any(), anyInt(), any(), any());

      System.clearProperty("justsearch.filter_norm.enabled");
    }

    @Test
    void caseOnlyMatchSkipsLlm() throws Exception {
      // "TechCrunch" lowercases to "techcrunch" which matches
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);

      var result = service.normalize(filtersWithSource("TechCrunch"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("exact_match", result.source());
      assertEquals(List.of("techcrunch"), result.normalizedFilters().metaSource());
      verify(aiService, never()).chatCompletion(any(), anyInt(), any(), any());

      System.clearProperty("justsearch.filter_norm.enabled");
    }

    @Test
    void nullFiltersReturnsNull() throws Exception {
      var result = service.normalize(null, FACET_SNAPSHOT, TEST_CONTEXT).get();
      assertNull(result);
    }
  }

  @Nested
  @DisplayName("normalize — LLM call (semantic gaps only)")
  class LlmCall {

    @Test
    void semanticGapNormalizedByLlm() throws Exception {
      // "cbs sports" has no prefix/contains match for "cbssports.com" — needs LLM
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);
      when(aiService.chatCompletion(any(), anyInt(), any(), any()))
          .thenReturn(CompletableFuture.completedFuture(
              "cbs sports -> cbssports.com"));

      var result = service.normalize(filtersWithSource("CBS Sports"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("hybrid", result.source());
      assertEquals(List.of("cbssports.com"), result.normalizedFilters().metaSource());

      System.clearProperty("justsearch.filter_norm.enabled");
    }

    @Test
    void noMatchDropsValue() throws Exception {
      // "bloomberg" has no deterministic match — LLM returns NO_MATCH
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);
      when(aiService.chatCompletion(any(), anyInt(), any(), any()))
          .thenReturn(CompletableFuture.completedFuture(
              "bloomberg -> NO_MATCH"));

      var result = service.normalize(filtersWithSource("Bloomberg"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("hybrid", result.source());
      // NO_MATCH means the value is dropped entirely
      assertTrue(result.normalizedFilters().metaSource().isEmpty());

      System.clearProperty("justsearch.filter_norm.enabled");
    }

    @Test
    void hallucinatedValueRejected() throws Exception {
      // LLM returns a value not in vocabulary — should be rejected
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);
      when(aiService.chatCompletion(any(), anyInt(), any(), any()))
          .thenReturn(CompletableFuture.completedFuture(
              "cbs sports -> cbs sports network"));  // not in vocabulary

      var result = service.normalize(filtersWithSource("CBS Sports"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      // Hallucinated value rejected — unresolved value kept as-is
      assertTrue(result.normalizedFilters().metaSource().isEmpty()
          || result.normalizedFilters().metaSource().contains("cbs sports"));

      System.clearProperty("justsearch.filter_norm.enabled");
    }

    @Test
    void deterministicAndLlmMerged() throws Exception {
      // "fortune" resolves deterministically, "cbs sports" needs LLM
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);
      when(aiService.chatCompletion(any(), anyInt(), any(), any()))
          .thenReturn(CompletableFuture.completedFuture(
              "cbs sports -> cbssports.com"));

      var result = service.normalize(filtersWithSource("Fortune", "CBS Sports"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("hybrid", result.source());
      var sources = result.normalizedFilters().metaSource();
      assertTrue(sources.contains("fortune"));
      assertTrue(sources.contains("cbssports.com"));

      System.clearProperty("justsearch.filter_norm.enabled");
    }
  }

  @Nested
  @DisplayName("normalize — fallback")
  class Fallback {

    @Test
    void timeoutFallsToDetPlusOriginal() throws Exception {
      // "cbs sports" has no deterministic match, LLM times out → keep lowercased original
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);
      when(aiService.chatCompletion(any(), anyInt(), any(), any()))
          .thenReturn(CompletableFuture.supplyAsync(() -> {
            try {
              Thread.sleep(10_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return "cbs sports -> cbssports.com";
          }));

      var result = service.normalize(filtersWithSource("CBS Sports"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("timeout", result.source());
      assertEquals(List.of("cbs sports"), result.normalizedFilters().metaSource());

      System.clearProperty("justsearch.filter_norm.enabled");
    }

    @Test
    void llmErrorFallsToDetPlusOriginal() throws Exception {
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);
      when(aiService.chatCompletion(any(), anyInt(), any(), any()))
          .thenReturn(CompletableFuture.failedFuture(new RuntimeException("LLM down")));

      var result = service.normalize(filtersWithSource("CBS Sports"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("timeout", result.source());
      assertEquals(List.of("cbs sports"), result.normalizedFilters().metaSource());

      System.clearProperty("justsearch.filter_norm.enabled");
    }

    @Test
    void deterministicResolvesWithoutLlm() throws Exception {
      // "fox news" prefix-matches deterministically → no LLM call needed
      System.setProperty("justsearch.filter_norm.enabled", "true");
      when(aiService.isAvailable()).thenReturn(true);

      var result = service.normalize(filtersWithSource("FOX News"), FACET_SNAPSHOT, TEST_CONTEXT).get();

      assertNotNull(result);
      assertEquals("deterministic", result.source());
      var sources = result.normalizedFilters().metaSource();
      assertEquals(3, sources.size());
      assertTrue(sources.contains("fox news - health"));
      assertTrue(sources.contains("fox news - lifestyle"));
      assertTrue(sources.contains("fox news - entertainment"));
      verify(aiService, never()).chatCompletion(any(), anyInt(), any(), any());

      System.clearProperty("justsearch.filter_norm.enabled");
    }
  }
}
