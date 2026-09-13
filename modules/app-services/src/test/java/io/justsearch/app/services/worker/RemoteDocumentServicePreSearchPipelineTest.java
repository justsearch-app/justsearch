/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 731 I1: wire-shape regression + rank-order preservation for {@link
 * RemoteDocumentService}'s open-retrieval pre-search path (empty {@code docIds}).
 *
 * <p>Before this fix, {@code preSearchForDocIds} sent a bare {@link SearchRequest} (query + limit
 * only, no pipeline/mode set), which the Worker resolved via the deprecated-mode fallback to a
 * sparse-only+expansion+LambdaMART pipeline — a different leg set than {@code justsearch_search}'s
 * default hybrid preset (sparse+dense RRF). A doc that hybrid search ranks highly could therefore
 * be structurally absent from the evidence pack's candidate universe.
 *
 * <p>Lane F stage A item A10: the harness was a real Netty {@code SearchService} plus a
 * memory-mapped signal bus, and is now {@link TestKnowledgeClient} — the request still arrives
 * exactly as {@code preSearchForDocIds} assembled it, which is what "wire shape" named. It fails
 * RED if {@code preSearchForDocIds} is reverted to the bare request: {@code hasPipeline()} goes
 * false and every {@link PipelineConfig} accessor below reads back its zero-value default.
 */
@DisplayName("RemoteDocumentService pre-search: pipeline wire shape + rank order (tempdoc 731 I1)")
final class RemoteDocumentServicePreSearchPipelineTest {

  private KnowledgeClient client;
  private String prevDataDir;
  private Path tempDataDir;
  private ConfigStore prevConfigStore;
  private final AtomicReference<SearchRequest> capturedSearchRequest = new AtomicReference<>();
  private final AtomicReference<RetrieveContextRequest> capturedRetrieveContextRequest =
      new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    // RerankerConfig.fromEnv() (called from SearchPipelinePresets.expandPreset, tempdoc 731 I1)
    // reads the global ConfigStore — bootstrap it the same way HeadAssemblyTest / production
    // startup do, since this test doesn't go through HeadlessApp bootstrap.
    prevConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();

    prevDataDir = System.getProperty("justsearch.data.dir");
    tempDataDir = Files.createTempDirectory("justsearch-731-i1-presearch-test-");
    System.setProperty("justsearch.data.dir", tempDataDir.toString());

    client =
        new TestKnowledgeClient(
            new io.justsearch.core.execution.TestEngineExecutors(), new CapturingSearchCalls());
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
      client = null;
    }
    if (prevDataDir == null) {
      System.clearProperty("justsearch.data.dir");
    } else {
      System.setProperty("justsearch.data.dir", prevDataDir);
    }
    TestResolvedConfigHelper.restoreGlobal(prevConfigStore);
  }

  @Test
  @DisplayName("open retrieval pre-search sends an explicit hybrid pipeline, not a bare request")
  void preSearchSendsExplicitHybridPipeline() throws Exception {
    RemoteDocumentService service = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);

    RetrieveContextParams params = RetrieveContextParams.of("what is the policy?", 5, 4096);
    service.retrieveContext(params, io.justsearch.app.services.TestEngineContexts.internal()).toCompletableFuture().get(6, TimeUnit.SECONDS);

    SearchRequest sent = capturedSearchRequest.get();
    assertTrue(sent != null, "Pre-search must have issued a search() RPC");

    assertTrue(sent.hasPipeline(), "Pre-search request must carry an explicit PipelineConfig");
    PipelineConfig pipeline = sent.getPipeline();
    assertTrue(pipeline.getSparseEnabled(), "Hybrid preset: sparse leg must be enabled");
    assertTrue(pipeline.getDenseEnabled(), "Hybrid preset: dense leg must be enabled");
    assertFalse(pipeline.getSpladeEnabled(), "Hybrid preset: SPLADE leg must be disabled");
    assertEquals("rrf", pipeline.getFusionAlgorithm(), "Hybrid preset fuses via RRF");
    assertFalse(
        pipeline.getDenseAuto(),
        "This is the explicit HYBRID preset (mirrors justsearch_search's default mode=\"hybrid\"),"
            + " not the capability-derived AUTO preset");
  }

  @Test
  @DisplayName("open retrieval pre-search preserves search rank order into the discovered doc set")
  void preSearchPreservesRankOrder() throws Exception {
    RemoteDocumentService service = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);

    RetrieveContextParams params = RetrieveContextParams.of("what is the policy?", 5, 4096);
    service.retrieveContext(params, io.justsearch.app.services.TestEngineContexts.internal()).toCompletableFuture().get(6, TimeUnit.SECONDS);

    RetrieveContextRequest forwarded = capturedRetrieveContextRequest.get();
    assertTrue(forwarded != null, "Discovered doc IDs must be forwarded to retrieveContext()");

    // CapturingSearchService returns results in rank order doc-c, doc-a, doc-b — deliberately
    // neither alphabetical nor insertion-hash order, so a HashSet (String.hashCode() bucket
    // order) silently reorders this list while a LinkedHashSet preserves it.
    assertEquals(
        List.of("doc-c", "doc-a", "doc-b"),
        forwarded.getDocIdsList(),
        "Discovered doc IDs must preserve the pre-search's rank order");
  }

  /**
   * Captures the request shape sent by pre-search, and returns a fixed rank-ordered result set so
   * rank-order preservation is independently observable.
   */
  private final class CapturingSearchCalls extends TestKnowledgeClient.SearchCalls {

    @Override
    public SearchResponse search(SearchRequest request) {
      capturedSearchRequest.set(request);
      SearchResponse.Builder resp = SearchResponse.newBuilder();
      for (String path : List.of("doc-c", "doc-a", "doc-b")) {
        resp.addResults(SearchResult.newBuilder().setId(path).putFields("path", path).build());
      }
      return resp.build();
    }

    @Override
    public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
      capturedRetrieveContextRequest.set(request);
      return RetrieveContextResponse.newBuilder().build();
    }
  }
}
