/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
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

/** Uses metadata-only configuration and mocked transport; never loads a model or starts Engine. */
final class SearchCpuDefaultTest {
  @TempDir Path tmp;
  private final ConfigStore previous = ConfigStore.globalOrNull();

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  private void publish(String choice, boolean gpu, int pathOrdinal) {
    var builder = new ResolvedConfigBuilder()
        .putDefault("justsearch.models.dir", tmp.resolve("models").toString())
        .putDefault("justsearch.rerank.gpu.enabled", Boolean.toString(gpu))
        .putDefault("justsearch.rerank.min_hits", "1")
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

  private KnowledgeSearchEngine engine(KnowledgeClient client) {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
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
