/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.knowledge.CrossEncoderSkipReason;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C5 H3/H4/P2: a delivered search preserves pre-CE scores/order and the truthful policy reason. */
final class KnowledgeSearchLongQueryPolicyTest {
  @TempDir Path temp;
  private ConfigStore previous;

  @BeforeEach
  void configure() throws Exception {
    previous = ConfigStore.globalOrNull();
    Path model = Files.createDirectories(temp.resolve("reranker"));
    Files.writeString(model.resolve("model.onnx"), "stand-in");
    Files.writeString(model.resolve("tokenizer.json"), "{}");
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", temp.resolve("models").toString(),
        "justsearch.search.query_classification.enabled", "false",
        "justsearch.rerank.enabled", "true",
        "justsearch.rerank.model_path", model.toString(),
        "justsearch.rerank.min_hits", "1",
        "justsearch.rerank.judge_blend_enabled", "true",
        "justsearch.rerank.judge_arbitration_enabled", "true"))));
  }

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  @Test
  void policyReasonIsByDesignAndUnknownAndDeadlineReasonsKeepTheirClasses() {
    var reason = CrossEncoderSkipReason.fromWorkerSkipReason("QUERY_TOO_LONG");
    assertEquals("QUERY_TOO_LONG", reason.wire());
    assertFalse(reason.isDrop());
    assertTrue(CrossEncoderSkipReason.fromWorkerSkipReason("").isDrop());
    assertTrue(CrossEncoderSkipReason.fromWorkerSkipReason("unknown").isDrop());
    assertTrue(CrossEncoderSkipReason.fromWorkerSkipReason("DEADLINE_EXCEEDED").isDrop());
    assertTrue(CrossEncoderSkipReason.fromWorkerSkipReason("INFERENCE_FAILED").isDrop());
  }

  @Test
  void deliveredPolicySkipRetainsLambdaMartOrderRawScoresTailAndFinalLimit() {
    String query = "original CE query ".repeat(300);
    var route = new AtomicReference<String>();
    var ceQuery = new AtomicReference<String>();
    var calls = new AtomicInteger();
    KnowledgeClient client = mock(KnowledgeClient.class, invocation -> {
      if (invocation.getMethod().getName().equals("search")) {
        return SearchResponse.newBuilder().setTotalHits(3).addAllResults(List.of(
            SearchResult.newBuilder().setId("first").setScore(11)
                .addTrace(io.justsearch.ipc.HitStage.newBuilder().setId("fusion").setScore(11)).build(),
            SearchResult.newBuilder().setId("second").setScore(22)
                .addTrace(io.justsearch.ipc.HitStage.newBuilder().setId("fusion").setScore(22)).build(),
            SearchResult.newBuilder().setId("tail").setScore(33)
                .addTrace(io.justsearch.ipc.HitStage.newBuilder().setId("fusion").setScore(33)).build())).build();
      }
      if (invocation.getMethod().getName().startsWith("rerank")) {
        route.set(invocation.getMethod().getName());
        ceQuery.set(invocation.getArgument(0));
        calls.incrementAndGet();
        return RerankResponse.newBuilder().setSkipped(true).setSkipReason("QUERY_TOO_LONG").build();
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    RerankerService ltr = mock(RerankerService.class);
    when(ltr.isLoaded()).thenReturn(true);
    when(ltr.rerank(any(), any(), any(), anyInt())).thenReturn(List.of(1, 2, 0));
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.isReady()).thenReturn(true);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation -> {
      Function<KnowledgeClient, ?> callback = invocation.getArgument(0);
      return callback.apply(client);
    });
    var engine = new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class),
        OnlineAiService.unavailable(), ltr, ConfigStore.global());
    for (int limit : new int[] {2, 10}) {
      var request = new KnowledgeSearchRequest(query, limit, null, null, null, List.of(),
          null, null, null, null, false, true,
          new PipelineConfig(true, true, false, "rrf", true, true, 2, false, false));
      var response = engine.search(request, TestEngineContexts.internal());
      var expected = limit == 2 ? List.of("second", "tail") : List.of("second", "tail", "first");
      assertEquals(expected, response.results().stream().map(hit -> hit.id()).toList());
      assertEquals(limit == 2 ? List.of(22d, 33d) : List.of(22d, 33d, 11d),
          response.results().stream().map(hit -> hit.score()).toList());
      var ce = response.searchTrace().stages().stream()
          .filter(stage -> stage.id() == SearchTrace.StageId.CROSS_ENCODER).findFirst().orElseThrow();
      assertEquals(SearchTrace.StageStatus.SKIPPED, ce.status());
      assertEquals("QUERY_TOO_LONG", ce.reason());
      assertNull(ce.detail());
      assertTrue(response.results().stream().allMatch(hit -> hit.trace().stream()
          .noneMatch(stage -> stage.id() == SearchTrace.StageId.CROSS_ENCODER)));
    }
    assertEquals(2, calls.get(), "one policy decision per search, never per document");
    assertEquals("rerankSearch", route.get());
    assertEquals(query, ceQuery.get(), "the original CE query decides, not retrieval expansion");
  }
}
