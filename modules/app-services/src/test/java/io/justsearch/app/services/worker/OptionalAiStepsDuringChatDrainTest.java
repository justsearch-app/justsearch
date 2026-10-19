/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every optional AI step a search or an answer fires treats a refusal from the generative gate
 * while chat drains, starts or stops exactly as it treats AI being off (b6d536 item 5). The gate is
 * the production {@link GenerationTracker} with admission closed, as a drain leaves it; the refusal
 * reaches a step either thrown at the call or as the failure of the returned future.
 */
final class OptionalAiStepsDuringChatDrainTest {

  private static final String QUERY = "how do solar panels turn sunlight into power";

  private ConfigStore previousConfig;
  private GenerationTracker gate;
  private ChatActuator.DrainHold drain;
  private List<SearchRequest> workerRequests;

  @BeforeEach
  void setUp() {
    previousConfig = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
    gate = new GenerationTracker(work -> () -> {}, Runnable::run);
    drain = gate.closeNow();
    workerRequests = new ArrayList<>();
  }

  @AfterEach
  void tearDown() {
    drain.close();
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  // ==================== search: query expansion ====================

  @Test
  void aSearchAnswersWithoutExpansionWhenTheDrainingGateRefusesAtTheCall() {
    OnlineAiService ai = available(refusingAtTheCall());

    KnowledgeSearchResponse response =
        assertDoesNotThrow(() -> engine(ai).search(request(), TestEngineContexts.internal()),
            "the search answers while chat drains");

    assertExpansionSkipped(response, "AI_UNAVAILABLE");
    assertEquals(1, workerRequests.size(), "only the base search ran");
  }

  @Test
  void aSearchRecordsTheAiOffReasonWhenTheRefusalArrivesThroughTheFuture() {
    OnlineAiService ai = available(refusingThroughTheFuture());

    KnowledgeSearchResponse response = engine(ai).search(request(), TestEngineContexts.internal());

    assertExpansionSkipped(response, "AI_UNAVAILABLE");
    assertEquals(1, workerRequests.size(), "only the base search ran");
  }

  @Test
  void aSearchWithAiOnStillExpands() {
    drain.close();
    OnlineAiService ai = available(answering("photovoltaic"));

    KnowledgeSearchResponse response = engine(ai).search(request(), TestEngineContexts.internal());

    SearchTrace.TraceStage expansion = expansionStage(response);
    assertEquals(SearchTrace.StageStatus.EXECUTED, expansion.status());
    assertEquals(2, workerRequests.size(), "the base search, then the expanded re-search");
    assertEquals(QUERY + " photovoltaic^0.3", workerRequests.get(1).getQuery());
  }

  @Test
  void aSearchWithAiOffSkipsExpansionAsToday() {
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(false);

    KnowledgeSearchResponse response = engine(ai).search(request(), TestEngineContexts.internal());

    assertExpansionSkipped(response, "AI_UNAVAILABLE");
    assertEquals(1, workerRequests.size());
    verify(ai, never()).chatCompletion(anyList(), anyInt(), any(SamplingParams.class),
        any(EngineContext.class));
  }

  // ==================== other optional steps ====================

  @Test
  void queryUnderstandingSkipsLikeAiOffWhenTheDrainingGateRefusesAtTheCall() throws Exception {
    var service = new QueryUnderstandingService(available(refusingAtTheCall()), () -> true);

    CompletableFuture<QueryUnderstandingService.QuResult> extraction =
        assertDoesNotThrow(() -> service.extract(QUERY, null, TestEngineContexts.internal()));

    assertNull(extraction.get(5, TimeUnit.SECONDS), "no boost filters, as when AI is off");
  }

  @Test
  void filterNormalizationFallsBackLikeAiOffWhenTheDrainingGateRefusesAtTheCall()
      throws Exception {
    var filters = new KnowledgeSearchRequest.Filters(null, null, null, null, null, null, null,
        null, null, null, List.of("Reuters Wire"), null, null, null, null, null);
    // "Reuters Wire" has no deterministic match, so normalization needs the LLM.
    String facets = "meta_source: associated press (120), the verge (85)";
    OnlineAiService off = mock(OnlineAiService.class);
    when(off.isAvailable()).thenReturn(false);
    var aiOff = new FilterNormalizationService(off, () -> true)
        .normalize(filters, facets, TestEngineContexts.internal()).get(5, TimeUnit.SECONDS);
    var service = new FilterNormalizationService(available(refusingAtTheCall()), () -> true);

    var draining = assertDoesNotThrow(
        () -> service.normalize(filters, facets, TestEngineContexts.internal()))
        .get(5, TimeUnit.SECONDS);

    assertNotNull(draining);
    assertEquals(aiOff.normalizedFilters(), draining.normalizedFilters());
    assertEquals(aiOff.source(), draining.source(), "the no-LLM result, as when AI is off");
  }

  @Test
  void contextSufficiencyAnswersUnknownLikeAiOffWhenTheDrainingGateRefusesAtTheCall()
      throws Exception {
    var service = new ContextSufficiencyService(available(refusingAtTheCall()));

    var classification = assertDoesNotThrow(
        () -> service.classify(QUERY, "context", TestEngineContexts.internal()));

    assertNull(classification.get(5, TimeUnit.SECONDS), "unknown, as when AI is off");
  }

  // ==================== helpers ====================

  private interface Completion {
    CompletableFuture<String> complete();
  }

  /** The gate refuses synchronously, as {@code OnlineModeOps.requireOnline} does while draining. */
  private Completion refusingAtTheCall() {
    return () -> {
      gate.requireOpen();
      return CompletableFuture.completedFuture("unreachable");
    };
  }

  /** The gate refuses inside the asynchronous body, as a queued generation task does. */
  private Completion refusingThroughTheFuture() {
    return () -> {
      try {
        gate.admit(GenerationTracker.Kind.GENERATE, null).close();
        return CompletableFuture.completedFuture("unreachable");
      } catch (RuntimeException refused) {
        return CompletableFuture.failedFuture(refused);
      }
    };
  }

  private Completion answering(String text) {
    return () -> {
      gate.admit(GenerationTracker.Kind.GENERATE, null).close();
      return CompletableFuture.completedFuture(text);
    };
  }

  /** An AI that reports available (the mode stays ONLINE during a drain) and completes through. */
  private static OnlineAiService available(Completion completion) {
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(anyList(), anyInt(), any(), any(EngineContext.class)))
        .thenAnswer(ignored -> completion.complete());
    return ai;
  }

  @SuppressWarnings("unchecked")
  private KnowledgeSearchEngine engine(OnlineAiService ai) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenAnswer(invocation -> {
      workerRequests.add(invocation.getArgument(0));
      return SearchResponse.getDefaultInstance();
    });
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    KnowledgeServerBootstrap.ClientLease lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    return new KnowledgeSearchEngine(bootstrap, mock(SearchPerSourceExecutor.class), ai, null,
        ConfigStore.global());
  }

  private static KnowledgeSearchRequest request() {
    return new KnowledgeSearchRequest(QUERY, 10, "text", null, null, List.of(), null, null, null,
        null, false, false, PipelineConfig.TEXT);
  }

  private static SearchTrace.TraceStage expansionStage(KnowledgeSearchResponse response) {
    return response.searchTrace().stages().stream()
        .filter(stage -> stage.id() == SearchTrace.StageId.EXPANSION)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no expansion stage: " + response.searchTrace()));
  }

  private static void assertExpansionSkipped(KnowledgeSearchResponse response, String reason) {
    SearchTrace.TraceStage expansion = expansionStage(response);
    assertEquals(SearchTrace.StageStatus.SKIPPED, expansion.status());
    assertEquals(reason, expansion.reason(), "the same skip reason as when AI is off");
  }
}
