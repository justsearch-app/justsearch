/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Owner-level dispatch counts, not real model quality, parser, or HTTP evidence. */
final class KnowledgeSearchEngineClassificationDispatchTest {
  @TempDir Path dir;
  private ConfigStore previousConfig;
  private ConfigStore store;

  @BeforeEach
  void configure() throws Exception {
    previousConfig = ConfigStore.globalOrNull();
    Path model = Files.createDirectories(dir.resolve("reranker"));
    Files.writeString(model.resolve("model.onnx"), "test readiness stand-in");
    Files.writeString(model.resolve("tokenizer.json"), "{}");
    store = new ConfigStore(config(true, true));
    ConfigStore.setGlobal(store);
  }

  private io.justsearch.configuration.resolved.ResolvedConfig config(boolean classify, boolean qu) {
    return TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", dir.resolve("models").toString(),
        "justsearch.rerank.model_path", dir.resolve("reranker").toString(),
        "justsearch.rerank.enabled", "true",
        "justsearch.rerank.min_hits", "1",
        "justsearch.search.query_classification.enabled", Boolean.toString(classify),
        "justsearch.qu.enabled", Boolean.toString(qu),
        "justsearch.filter_norm.enabled", "true"));
  }

  @AfterEach
  void restore() { TestResolvedConfigHelper.restoreGlobal(previousConfig); }

  @ParameterizedTest(name = "disabledHasNoDetectedType({0})")
  @ValueSource(strings = {"simple", "lucene", "explicit-lucene"})
  void disabledHasNoDetectedType(String route) {
    store.swap(config(false, true));
    var f = new Fixture();
    boolean lucene = route.contains("lucene");
    var response = f.search(request("src/main/java", null, lucene ? "lucene" : null,
        route.startsWith("explicit") ? pipeline(false, true) : null));
    var stage = stage(response, "query-understanding");
    assertEquals(SearchTrace.StageStatus.DISABLED, stage.status());
    assertEquals("DISABLED", stage.reason(), "disabled precedes explicit pipeline and syntax");
    assertNull(stage.detail(), "classification never ran; do not invent INFORMATIONAL");
    assertEquals(1, f.reranks.size(), "effective INFORMATIONAL still permits CE");
    assertEquals(lucene ? 0 : 1, f.calls(256), "syntax owns QU independently of disabled classification");
  }

  @ParameterizedTest(name = "bypassRetainsRawTypeAndReportsReason({0})")
  @ValueSource(strings = {"explicit-simple", "explicit-lucene", "preset-lucene"})
  void bypassRetainsRawTypeAndReportsReason(String route) {
    var f = new Fixture();
    boolean explicit = route.startsWith("explicit");
    var response = f.search(request("src/main/java", "text", route.endsWith("lucene") ? "lucene" : null,
        explicit ? pipeline(false, true) : null));
    var stage = stage(response, "query-understanding");
    assertEquals(SearchTrace.StageStatus.EXECUTED, stage.status());
    assertEquals("NAVIGATIONAL", stage.detail());
    assertEquals(explicit ? "EXPLICIT_PIPELINE" : "LUCENE_SYNTAX", stage.reason());
    assertEquals(1, f.reranks.size(), "effective type permits actual CE dispatch");
    assertEquals(route.endsWith("simple") ? 1 : 0, f.calls(256));
  }

  @ParameterizedTest(name = "lucenePreviouslyEligibleQuNeverDispatches({0})")
  @ValueSource(strings = {"title:report", "title:\"quarterly report\"", "*:*", "climate policy",
      "report.pdf?", "meta_source:x OR climate"})
  void lucenePreviouslyEligibleQuNeverDispatches(String query) {
    var f = new Fixture();
    // Explicit pipeline makes every raw type otherwise eligible. No expansion, AI and QU on.
    var response = f.search(request(query, null, "lucene", pipeline(false, true)));
    assertEquals(0, f.calls(256), "raw Lucene query language must not enter LLM QU");
    assertEquals(1, f.requests.size());
    assertEquals(query, f.requests.getFirst().getQuery());
    assertEquals(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE, f.requests.getFirst().getQuerySyntax());
    assertTrue(response.queryUnderstanding() == null || response.queryUnderstanding().appliedBoosts().isEmpty(),
        "historical classifier row is not LLM proof");
  }

  @ParameterizedTest(name = "pairedSimpleTextStillDispatchesQu({0})")
  @ValueSource(strings = {"title:report", "title:\"quarterly report\"", "*:*", "climate policy",
      "report.pdf?", "meta_source:x OR climate"})
  void pairedSimpleTextStillDispatchesQu(String query) {
    var f = new Fixture();
    f.search(request(query, null, "simple", pipeline(false, false)));
    assertEquals(1, f.calls(256), "the same text remains QU-eligible with explicit SIMPLE pipeline");
    assertEquals(query, f.requests.getFirst().getQuery());
    assertEquals(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE, f.requests.getFirst().getQuerySyntax());
  }

  @ParameterizedTest(name = "luceneQuotedShapesRemainCeEligibleWithoutQu({0})")
  @MethodSource("luceneQuotedCases")
  void luceneQuotedShapesRemainCeEligibleWithoutQu(String query, String rawType) {
    var f = new Fixture();
    var response = f.search(request(query, "hybrid", "lucene", null));
    assertEquals(0, f.calls(256));
    assertEquals(List.of(query), f.reranks);
    assertEquals(rawType, stage(response, "query-understanding").detail());
    assertEquals(2, response.totalHits());
  }

  static Stream<Arguments> luceneQuotedCases() {
    return Stream.of(
        Arguments.of("\"src/main/java\"", "NAVIGATIONAL"),
        Arguments.of("\"report.pdf\"", "EXACT_MATCH"));
  }

  @Test
  void libraryMatchAllFacetLoaderLosesOnlyOptionalQu() {
    var f = new Fixture();
    var request = new KnowledgeSearchRequest("*:*", 1, null, null, null, List.of(), null, null,
        new KnowledgeSearchRequest.Facets(true, 200, List.of(
            new KnowledgeSearchRequest.FieldSpec("meta_source", 30))),
        "lucene", false, false, pipeline(false, false));
    var response = f.search(request);
    assertEquals(0, f.calls(256), "Library loader is deliberately excluded from QU");
    assertEquals("EXPLICIT_PIPELINE", stage(response, "query-understanding").reason());
    assertEquals("*:*", f.requests.getFirst().getQuery());
    assertTrue(f.requests.getFirst().getFacets().getInclude());
    assertEquals(0, f.reranks.size(), "the caller's CE flag still owns dispatch");
  }

  @ParameterizedTest(name = "luceneNavigationalShapesReachCeWithRawText({0})")
  @ValueSource(strings = {"meta_source:x", "src/main/java", "meta_source\\:x"})
  void luceneNavigationalShapesReachCeWithRawText(String query) {
    var f = new Fixture();
    var response = f.search(request(query, null, "lucene", null));
    assertEquals(List.of(query), f.reranks);
    assertEquals(query, f.requests.getFirst().getQuery());
    assertEquals("LUCENE_SYNTAX", stage(response, "query-understanding").reason());
    assertEquals("NAVIGATIONAL", stage(response, "query-understanding").detail());
    assertEquals("LUCENE_SYNTAX", stage(response, "expansion").reason());
    assertEquals(0, f.completions.size());
    assertEquals(2, response.totalHits());
    assertEquals(List.of("/second.txt", "/first.txt"), ids(response));
  }

  @Test
  void disabledAndInformationalAreDifferentEvenIfEffectivePolicyIsTheSame() {
    store.swap(config(false, false));
    var disabled = new Fixture().search(request("climate policy", "hybrid", null, null));
    store.swap(config(true, false));
    var classified = new Fixture().search(request("climate policy", "hybrid", null, null));
    assertEquals(SearchTrace.StageStatus.DISABLED, stage(disabled, "query-understanding").status());
    assertNull(stage(disabled, "query-understanding").detail());
    assertEquals(SearchTrace.StageStatus.EXECUTED, stage(classified, "query-understanding").status());
    assertEquals("INFORMATIONAL", stage(classified, "query-understanding").detail());
    assertNull(stage(classified, "query-understanding").reason());
  }

  @Test
  void disabledDiagnosticUsesCapturedConfigEvenAfterPublicationChanges() {
    store.swap(config(false, false));
    var f = new Fixture();
    when(f.client.search(any(SearchRequest.class), any())).thenAnswer(ignored -> {
      store.swap(config(true, true));
      return baseHits();
    });
    var response = f.search(request("src/main/java", "hybrid", null, null));
    assertEquals(SearchTrace.StageStatus.DISABLED, stage(response, "query-understanding").status());
    assertEquals("DISABLED", stage(response, "query-understanding").reason());
    assertNull(stage(response, "query-understanding").detail());
    assertEquals(1, f.reranks.size());
    assertEquals(0, f.calls(256));
  }

  @ParameterizedTest(name = "simplePresetsKeepRawPolicyAndActualDispatch({0},{1})")
  @MethodSource("simpleCases")
  void simplePresetsKeepRawPolicyAndActualDispatch(
      String query, String mode, String type, int qu, int expansion, int ce) {
    var f = new Fixture();
    var response = f.search(request(query, mode, null, null));
    assertEquals(type, stage(response, "query-understanding").detail());
    assertNull(stage(response, "query-understanding").reason(), "explicit mode is still a preset");
    assertEquals(qu, f.calls(256));
    assertEquals(expansion, f.calls(64));
    assertEquals(ce, f.reranks.size());
    assertEquals(query, f.requests.getFirst().getQuery(), "base retrieval keeps exact raw text");
    if (ce > 0) assertEquals(List.of(query), f.reranks);
    assertEquals(mode == null, f.requests.getFirst().getPipeline().getDenseAuto());
  }

  static Stream<Arguments> simpleCases() {
    return Stream.of(
        Arguments.of("report.pdf", null, "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("src/main/java", "text", "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("src/main/java", "hybrid", "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("\"src/main/java\"", "text", "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("\"report.pdf\"", null, "EXACT_MATCH", 0, 0, 1),
        Arguments.of("\"report.pdf", "text", "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("\"foo\" OR \"bar\"", "hybrid", "EXACT_MATCH", 0, 0, 1),
        Arguments.of("report.pdf quarterly", null, "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("why\u00a0src/main/java", "text", "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("C:\\My Files\\notes", "hybrid", "NAVIGATIONAL", 0, 0, 0),
        Arguments.of("climate policy", "hybrid", "INFORMATIONAL", 1, 0, 1),
        Arguments.of("optimization", "text", "EXPLORATORY", 1, 1, 1),
        Arguments.of("report.pdf?", "text", "EXPLORATORY", 1, 1, 1),
        Arguments.of("\u00a0", "text", "EXPLORATORY", 1, 1, 1),
        Arguments.of("", "text", "INFORMATIONAL", 0, 0, 1),
        Arguments.of(" ", "text", "INFORMATIONAL", 0, 0, 1));
  }

  @ParameterizedTest(name = "simpleQuKeepsItsIndependentAuthorities({0})")
  @ValueSource(strings = {"cursor", "filters", "boosts", "qu-disabled", "ai-unavailable"})
  void simpleQuKeepsItsIndependentAuthorities(String gate) {
    if (gate.equals("qu-disabled")) store.swap(config(true, false));
    var f = new Fixture();
    if (gate.equals("ai-unavailable")) when(f.ai.isAvailable()).thenReturn(false);
    var filter = KnowledgeSearchRequestFiltersBuilder.builder().metaSource(List.of("x")).build();
    var response = f.search(new KnowledgeSearchRequest("climate policy", 10, "hybrid", null,
        gate.equals("cursor") ? "cursor-token" : null, List.of("title"),
        gate.equals("filters") ? filter : null, gate.equals("boosts") ? filter : null,
        null, "simple", false, false, null));
    assertEquals(0, f.calls(256));
    assertEquals("INFORMATIONAL", stage(response, "query-understanding").detail());
    assertEquals(1, f.reranks.size(), "CE is independently eligible, including cursor pages");
    assertEquals(gate.equals("cursor") ? "cursor-token" : "", f.requests.getFirst().getCursor());
    if (gate.equals("filters")) assertEquals(List.of("x"), f.requests.getFirst().getFilters().getMetaSourceList());
    if (gate.equals("boosts")) assertEquals(List.of("x"), f.requests.getFirst().getBoostFilters().getMetaSourceList());
  }

  @ParameterizedTest(name = "luceneFiltersAndFieldSortRemainOwnedByCaller({0})")
  @ValueSource(strings = {"simple", "lucene"})
  void luceneFiltersAndFieldSortRemainOwnedByCaller(String syntax) {
    var f = new Fixture();
    var filter = KnowledgeSearchRequestFiltersBuilder.builder().metaSource(List.of("X")).build();
    var boost = KnowledgeSearchRequestFiltersBuilder.builder().metaAuthor(List.of("Author")).build();
    var response = f.search(new KnowledgeSearchRequest("meta_source:x", 1, null, "modified_desc",
        "field-page", List.of("title"), filter, boost, null, syntax, false, false, null));
    SearchRequest sent = f.requests.getFirst();
    assertEquals("meta_source:x", sent.getQuery());
    assertEquals("field-page", sent.getCursor());
    assertEquals(1, sent.getLimit(), "field-sort pages cannot overfetch");
    assertEquals(List.of("author"), sent.getBoostFilters().getMetaAuthorList().stream().map(String::toLowerCase).toList());
    assertEquals(List.of("x"), sent.getFilters().getMetaSourceList(), "existing normalization still runs");
    assertNotNull(response.filterNormalization());
    assertEquals(filter, response.appliedFilters().filters());
    assertEquals(boost, response.appliedFilters().boostFilters());
    assertEquals(0, f.calls(256));
    assertEquals(0, f.reranks.size());
    assertEquals("PIPELINE_NOT_ELIGIBLE", stage(response, "cross-encoder").reason());
  }

  @Test
  void actualQuMayDispatchWithoutSurfacedExtraction() {
    var f = new Fixture();
    var response = f.search(request("climate policy", "hybrid", null, null));
    assertEquals(1, f.calls(256), "fixture returns invalid JSON, but the LLM dispatch occurred");
    assertNull(response.queryUnderstanding(), "dispatch need not produce surfaced extraction");
    assertEquals("INFORMATIONAL", stage(response, "query-understanding").detail());
  }

  @Test
  void capturedEnabledClassificationDoesNotFollowLaterGlobalDisable() {
    var f = new Fixture();
    when(f.client.search(any(SearchRequest.class), any())).thenAnswer(ignored -> {
      store.swap(config(false, false));
      return baseHits();
    });
    var response = f.search(request("src/main/java", "hybrid", null, null));
    assertEquals("NAVIGATIONAL", stage(response, "query-understanding").detail());
    assertNull(stage(response, "query-understanding").reason());
    assertEquals(0, f.reranks.size());
  }

  @Test
  void x5AdvancedSearchOverridesSemanticPipelineAndKeepsExplicitFilters() {
    var f = new Fixture();
    var filter = KnowledgeSearchRequestFiltersBuilder.builder().metaSource(List.of("X")).build();
    var semantic = new PipelineConfig(true, true, true, "rrf", true, true, 20, true, true);
    var response = f.search(new KnowledgeSearchRequest("title:budget", 10, "hybrid", null,
        null, List.of("title"), filter, null, null, "search", false, false, semantic));
    assertEquals(1, f.requests.size());
    var sent = f.requests.getFirst();
    assertEquals("SEARCH_QUERY_SYNTAX_SEARCH", sent.getQuerySyntax().name());
    assertTrue(sent.getPipeline().getSparseEnabled());
    assertFalse(sent.getPipeline().getDenseEnabled());
    assertFalse(sent.getPipeline().getDenseAuto());
    assertFalse(sent.getPipeline().getSpladeEnabled());
    assertFalse(sent.getPipeline().getExpansionEnabled());
    assertFalse(sent.getPipeline().getCrossEncoderEnabled());
    assertEquals(List.of("X"), sent.getFilters().getMetaSourceList());
    assertNull(response.filterNormalization());
    assertNull(response.queryUnderstanding());
    assertTrue(f.completions.isEmpty());
    assertTrue(f.reranks.isEmpty());
  }

  @Test
  void x6SearchRejectsInvalidSyntaxBeforeWorkerSearch() {
    for (String query : List.of("\"", "\"\"", "title:", "author:", "-foo",
        "\"a b\" OR c", "\"budget review\" AND forecast", "\"budget review\" NOT forecast")) {
      var f = new Fixture();
      var failure = assertThrows(IllegalArgumentException.class,
          () -> f.search(request(query, null, "search", null)), query);
      assertFalse(failure.getMessage().isBlank());
      assertTrue(f.requests.isEmpty());
    }
  }

  @Test
  void x7PlainSearchKeepsSimpleDispatchAndExpansionEligibility() {
    for (String query : List.of("climate policy", "foo OR bar", "foo AND bar", "foo NOT bar", "bud")) {
      var plain = new Fixture();
      var search = new Fixture();
      plain.search(request(query, null, "simple", null));
      search.search(request(query, null, "search", null));
      assertEquals(plain.requests, search.requests, query);
      assertEquals(plain.completions, search.completions, query);
      assertEquals(plain.reranks, search.reranks, query);
    }
  }

  private final class Fixture {
    final KnowledgeClient client = mock(KnowledgeClient.class);
    final OnlineAiService ai = mock(OnlineAiService.class);
    final List<SearchRequest> requests = new ArrayList<>();
    final List<String> reranks = new ArrayList<>();
    final List<Integer> completions = new ArrayList<>();
    final KnowledgeSearchEngine engine;

    @SuppressWarnings("unchecked")
    Fixture() {
      when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
        requests.add(invocation.getArgument(0));
        return baseHits();
      });
      when(client.rerank(anyString(), anyList(), anyLong(), any())).thenAnswer(invocation -> {
        reranks.add(invocation.getArgument(0));
        return RerankResponse.newBuilder().addSortedIndices(1).addSortedIndices(0)
            .addScores(1f).addScores(2f).build();
      });
      when(ai.isAvailable()).thenReturn(true);
      when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(invocation -> {
        completions.add(invocation.getArgument(1));
        // Invalid optional output keeps base request/ranking observation separate from QU/expansion.
        return CompletableFuture.completedFuture("not-valid-optional-output");
      });
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
      when(bootstrap.publicationLock()).thenReturn(store.publicationLock());
      when(bootstrap.acquireClientLease()).thenReturn(lease);
      when(lease.withClient(any())).thenAnswer(invocation ->
          ((Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
      engine = new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class), ai, null, store);
    }

    long calls(int maxTokens) { return completions.stream().filter(n -> n == maxTokens).count(); }
    KnowledgeSearchResponse search(KnowledgeSearchRequest request) {
      return engine.search(request, TestEngineContexts.internal());
    }
  }

  private static PipelineConfig pipeline(boolean expansion, boolean ce) {
    return new PipelineConfig(true, false, false, "none", false, ce, 0, expansion, false);
  }

  private static KnowledgeSearchRequest request(String query, String mode, String syntax, PipelineConfig pipeline) {
    return new KnowledgeSearchRequest(query, 10, mode, null, null, List.of(), null, null,
        null, syntax, false, false, pipeline);
  }

  private static SearchResponse baseHits() {
    return SearchResponse.newBuilder().setTotalHits(2)
        .addResults(SearchResult.newBuilder().setId("/first.txt").setScore(2))
        .addResults(SearchResult.newBuilder().setId("/second.txt").setScore(1)).build();
  }

  private static SearchTrace.TraceStage stage(KnowledgeSearchResponse response, String id) {
    return response.searchTrace().stages().stream().filter(s -> s.id().wireId().equals(id))
        .findFirst().orElseThrow();
  }

  private static List<String> ids(KnowledgeSearchResponse response) {
    return response.results().stream().map(KnowledgeSearchResponse.Hit::id).toList();
  }
}
