/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.CrossEncoderSkipReason;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.reranker.RerankerConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses metadata-only configuration and mocked transport; never loads a model or starts Engine. */
final class SearchCpuDefaultTest {
  @TempDir Path tmp;
  private final ConfigStore previous = ConfigStore.globalOrNull();

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  private void publish(String choice, boolean gpu, int pathOrdinal) {
    publish(choice, gpu, pathOrdinal, 0);
  }

  private void publish(String choice, boolean gpu, int pathOrdinal, long documentCeiling) {
    var builder = new ResolvedConfigBuilder()
        .putDefault("justsearch.models.dir", tmp.resolve("models").toString())
        .putDefault("justsearch.rerank.gpu.enabled", Boolean.toString(gpu))
        .putDefault("justsearch.rerank.max_avg_doc_length_chars", Long.toString(documentCeiling))
        .putDefault("justsearch.search.query_classification.enabled", "false")
        .put("justsearch.rerank.model_path", pathOrdinal, "fixture", "path", tmp.toString());
    if (choice != null) builder.putSettings("justsearch.rerank.enabled", choice);
    ConfigStore.setGlobal(new ConfigStore(builder.build()));
    assertEquals(!"false".equals(choice), RerankerConfig.fromEnv().isReady());
  }

  @Test
  void defaultAgentAndDocumentSearchUsesCpuOffWithoutChangingTheGlobalModel() {
    publish(null, false, 300);
    assertFalse(SearchPipelinePresets.defaultHybridProtoConfig().getCrossEncoderEnabled(),
        "CPU/null automatic agent and document preset must default off");
    assertTrue(RerankerConfig.fromEnv().enabled());
    assertTrue(RerankerConfig.fromEnv().isReady());
  }

  @Test
  void automaticModesMatchExplicitOffRetrievalWindowAndPages() {
    for (String mode : new String[] {null, "text", "hybrid", "vector", "splade"}) {
      publish(null, false, 300);
      KnowledgeClient client = mock(KnowledgeClient.class);
      List<SearchRequest> sent = new ArrayList<>();
      when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
        SearchRequest request = invocation.getArgument(0);
        sent.add(request);
        var response = SearchResponse.newBuilder().setTotalHits(30)
            .setNextCursor("after:" + request.getLimit());
        for (int i = 0; i < request.getLimit(); i++) {
          response.addResults(SearchResult.newBuilder().setId("doc-" + i).setScore(30 - i));
        }
        return response.build();
      });
      when(client.rerankSearch(anyString(), anyList(), anyLong(), any())).thenReturn(
          RerankResponse.newBuilder().setSkipped(true).setSkipReason("MODEL_NOT_LOADED").build());
      var engine = engine(client);
      var automatic = engine.search(request(mode, null, null, null), TestEngineContexts.internal());
      assertEquals(1, sent.get(0).getLimit(), "automatic CPU/null must not keep CE overfetch: " + mode);
      assertFalse(sent.get(0).getPipeline().getCrossEncoderEnabled());
      var ce = automatic.searchTrace().stages().stream()
          .filter(stage -> stage.id() == SearchTrace.StageId.CROSS_ENCODER).findFirst().orElseThrow();
      assertEquals(SearchTrace.StageStatus.SKIPPED, ce.status());
      assertEquals("PIPELINE_NOT_ELIGIBLE", ce.reason());
      verify(client, never()).rerankSearch(anyString(), anyList(), anyLong(), any());
      var automaticPage = engine.search(request(mode, "path_asc", "after:1", null),
          TestEngineContexts.internal());
      publish("false", false, 300);
      var off = engine.search(request(mode, null, null, null), TestEngineContexts.internal());
      assertEquals(automatic.results(), off.results());
      assertEquals(automatic.nextCursor(), off.nextCursor());
      assertEquals(sent.get(0), sent.get(2), "worker requests must match the ordinary Off control");
      var offPage = engine.search(request(mode, "path_asc", "after:1", null),
          TestEngineContexts.internal());
      assertEquals(automaticPage.results(), offPage.results());
      assertEquals(automaticPage.nextCursor(), offPage.nextCursor());
      assertEquals(sent.get(1), sent.get(3));
      assertEquals(1, sent.get(3).getLimit());
      assertEquals("after:1", sent.get(3).getCursor());
    }
  }

  @Test
  void explicitChoicesGpuAndOperatorPathsRetainExistingPipelineAndOverfetchAuthority() {
    for (var choice : new String[] {null, "true", "false"}) {
      for (boolean gpu : new boolean[] {false, true}) {
        for (int ordinal : new int[] {300, 400, 500}) {
          if (choice == null && !gpu && ordinal < 400) continue;
          publish(choice, gpu, ordinal);
          KnowledgeClient client = mock(KnowledgeClient.class);
          List<SearchRequest> sent = new ArrayList<>();
          when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return SearchResponse.getDefaultInstance();
          });
          var engine = engine(client);
          engine.search(request(null, null, null, null), TestEngineContexts.internal());
          boolean enabled = !"false".equals(choice);
          boolean automatic = enabled && !(choice == null && !gpu && ordinal < 400);
          assertEquals(automatic, sent.get(0).getPipeline().getCrossEncoderEnabled());
          assertEquals(automatic ? 20 : 1, sent.get(0).getLimit());
          var explicit = new PipelineConfig(true, false, false, "none", false, true, 0, false, false);
          engine.search(request(null, null, null, explicit), TestEngineContexts.internal());
          assertTrue(sent.get(1).getPipeline().getCrossEncoderEnabled());
          assertEquals(enabled ? 20 : 1, sent.get(1).getLimit());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void automaticPolicyPrecedesDefaultMinimumHitDiagnosticAtLimitOne(boolean detail) {
    assertPolicyDiagnostic("false", false, 1, 0, 0, detail, "DISABLED");
    assertPolicyDiagnostic(null, false, 1, 0, 0, detail, "PIPELINE_NOT_ELIGIBLE");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void automaticPolicyPrecedesConfiguredDocumentCeilingWithoutChangingExplicitOrGpuGates(boolean detail) {
    assertPolicyDiagnostic("false", false, 6, 100, 200, detail, "DISABLED");
    assertPolicyDiagnostic(null, true, 6, 100, 200, detail, "DOCS_TOO_LONG");
    assertPolicyDiagnostic("true", false, 6, 100, 200, detail, "DOCS_TOO_LONG");
    assertPolicyDiagnostic(null, false, 6, 100, 200, detail, "PIPELINE_NOT_ELIGIBLE");
  }

  private void assertPolicyDiagnostic(String choice, boolean gpu, int limit, long ceiling,
      long averageLength, boolean detail, String expectedReason) {
    publish(choice, gpu, 300, ceiling);
    assertEquals(5, RerankerConfig.fromEnv().minHitsThreshold(), "use the production default");
    KnowledgeClient client = mock(KnowledgeClient.class);
    List<SearchRequest> sent = new ArrayList<>();
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      if (request.getQuery().equals("*:*")) return SearchResponse.getDefaultInstance();
      sent.add(request);
      var response = SearchResponse.newBuilder().setTotalHits(30);
      for (int i = 0; i < request.getLimit(); i++) {
        response.addResults(SearchResult.newBuilder().setId("document-" + i).setScore(30 - i));
      }
      return response.build();
    });
    when(client.getStatus(any())).thenReturn(io.justsearch.ipc.StatusResponse.newBuilder()
        .setTelemetry(io.justsearch.ipc.TelemetryStatus.newBuilder()
            .setContentLengthAvgChars(averageLength)).build());
    var engine = engine(client);
    var capability = mock(io.justsearch.app.api.lifecycle.Capability.class);
    when(capability.available()).thenReturn(true);
    when(capability.health()).thenReturn(io.justsearch.app.api.lifecycle.CapabilityHealth.READY);
    engine.setWorkerCapability(capability);
    assertEquals(averageLength,
        engine.status(TestEngineContexts.internal()).extras().get("contentLengthAvgChars"));
    var request = request(null, null, null, null);
    var response = engine.search(new KnowledgeSearchRequest(request.query(), limit,
        request.mode(), request.sort(), request.cursor(), request.projection(), request.filters(),
        request.boostFilters(), request.facets(), request.querySyntax(), request.includeExcerpts(),
        detail, request.pipeline()), TestEngineContexts.internal());
    boolean off = (choice == null && !gpu) || "false".equals(choice);
    assertEquals(1, sent.size());
    assertEquals(off ? limit : 20, sent.getFirst().getLimit());
    assertEquals(!off, sent.getFirst().getPipeline().getCrossEncoderEnabled());
    assertEquals(detail, sent.getFirst().getIncludeDetail());
    assertEquals(30, response.totalHits(), "the fixture contains multiple matching documents");
    assertEquals(limit, response.results().size(), "nonempty ordinary multi-document retrieval");
    assertTrue(response.results().stream().allMatch(hit -> hit.trace() == null), "no CE scores");
    verify(client, never()).rerankSearch(anyString(), anyList(), anyLong(), any());
    var ce = response.searchTrace().stages().stream()
        .filter(stage -> stage.id() == SearchTrace.StageId.CROSS_ENCODER).findFirst().orElseThrow();
    assertEquals(SearchTrace.StageStatus.SKIPPED, ce.status());
    assertFalse(CrossEncoderSkipReason.valueOf(expectedReason).isDrop());
    assertTrue(response.searchTrace().stages().stream()
        .noneMatch(stage -> stage.status() == SearchTrace.StageStatus.FAILED));
    assertEquals(expectedReason, ce.reason(), "policy skip must own automatic-Off diagnostics");
  }

  private KnowledgeSearchEngine engine(KnowledgeClient client) {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(bootstrap.captureClient()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    return new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class),
        OnlineAiService.unavailable(), null, ConfigStore.global());
  }

  private static KnowledgeSearchRequest request(
      String mode, String sort, String cursor, PipelineConfig pipeline) {
    return new KnowledgeSearchRequest("the scientific evidence explains a biological mechanism", 1,
        mode, sort, cursor, List.of(),
        new KnowledgeSearchRequest.Filters(null, null, null, null, "/documents/", null,
            null, null, null, null, null, null, null, null, null, List.of("fixture")),
        null, null, null, false, false, pipeline);
  }
}
