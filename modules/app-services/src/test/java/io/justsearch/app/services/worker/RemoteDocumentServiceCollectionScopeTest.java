/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 821 §3-C2 — the Head-side half of RAG collection scoping. Two hops are pinned, because
 * both dropped the scope before that change: {@code SearchRpcOps#retrieveContext}'s param→proto
 * mapping, and {@code RemoteDocumentService}'s open-retrieval pre-search, whose discovered doc
 * universe the downstream {@code RetrieveContextRequest} is scoped to — an unscoped pre-search
 * would resolve an agent-history ASK to zero parents, since the DEFAULT scope excludes exactly that
 * collection.
 *
 * <p>Lane F stage A item A10: the harness was a real Netty server plus a memory-mapped signal bus,
 * and it is now {@link TestKnowledgeClient}. The assertions are unchanged and still on the
 * <em>request objects the Head produces</em>, which is what "reaches the Worker" always meant — the
 * socket in between was never the subject. Reverting either mapping still fails here.
 */
@DisplayName("RemoteDocumentService — collection scope reaches the Worker (821 §3-C2)")
final class RemoteDocumentServiceCollectionScopeTest {

  private KnowledgeClient client;
  private String prevDataDir;
  private Path tempDataDir;
  private ConfigStore prevConfigStore;
  private final AtomicReference<SearchRequest> capturedSearchRequest = new AtomicReference<>();
  private final AtomicReference<RetrieveContextRequest> capturedRetrieveContextRequest =
      new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    prevConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();

    prevDataDir = System.getProperty("justsearch.data.dir");
    tempDataDir = Files.createTempDirectory("justsearch-821-collection-scope-test-");
    System.setProperty("justsearch.data.dir", tempDataDir.toString());

    client = new TestKnowledgeClient(new CapturingSearchCalls());
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

  private void retrieve(List<String> collection, Set<String> docIds) throws Exception {
    RemoteDocumentService service = new RemoteDocumentService(() -> client);
    RetrieveContextParams params =
        RetrieveContextParams.of("what did the agent do?", 5, 4096, docIds, List.of(), collection);
    service.retrieveContext(params, io.justsearch.app.services.TestEngineContexts.internal()).toCompletableFuture().get(6, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("an explicit scope is mapped onto RetrieveContextRequest.collection")
  void explicitScopeReachesTheRetrieveContextRequest() throws Exception {
    retrieve(List.of("agent-history"), Set.of("d:/agent/session-1.md"));

    RetrieveContextRequest sent = capturedRetrieveContextRequest.get();
    assertTrue(sent != null, "retrieveContext() must have been called");
    assertEquals(
        List.of("agent-history"),
        sent.getCollectionList(),
        "the collection scope must reach the Worker, not be dropped in the param->proto mapping");
  }

  @Test
  @DisplayName("the open-retrieval pre-search carries the same scope")
  void preSearchCarriesTheSameScope() throws Exception {
    // Empty docIds is the open-retrieval path: RemoteDocumentService pre-searches for the doc
    // universe first, so the pre-search must be scoped the same way the retrieval is.
    retrieve(List.of("agent-history"), Set.of());

    SearchRequest preSearch = capturedSearchRequest.get();
    assertTrue(preSearch != null, "open retrieval must have issued a pre-search");
    assertEquals(
        List.of("agent-history"),
        preSearch.getFilters().getCollectionList(),
        "an unscoped pre-search would find zero agent-history parents (the default scope excludes"
            + " that collection) and silently starve the retrieval");

    // The rebuilt params carrying the discovered doc ids must not drop the scope either — that is
    // the hand-rolled record copy that the 811 D-2 class of bug lives in.
    assertEquals(
        List.of("agent-history"),
        capturedRetrieveContextRequest.get().getCollectionList(),
        "the scope must survive the discovered-docIds rebuild");
  }

  @Test
  @DisplayName("no scope leaves the request fields empty (pre-821 behavior, byte-identical)")
  void absentScopeLeavesTheRequestUntouched() throws Exception {
    retrieve(List.of(), Set.of());

    assertEquals(
        List.of(),
        capturedRetrieveContextRequest.get().getCollectionList(),
        "an absent scope must send an empty repeated field, which the Worker reads as the DEFAULT"
            + " scope");
    assertTrue(
        capturedSearchRequest.get().getFilters().getCollectionList().isEmpty(),
        "and the pre-search must be unscoped exactly as before");
  }

  /** Captures both request shapes the Head produces. */
  private final class CapturingSearchCalls extends TestKnowledgeClient.SearchCalls {

    @Override
    public SearchResponse search(SearchRequest request) {
      capturedSearchRequest.set(request);
      return SearchResponse.newBuilder()
          .addResults(
              SearchResult.newBuilder()
                  .setId("d:/agent/session-1.md")
                  .putFields("path", "d:/agent/session-1.md")
                  .build())
          .build();
    }

    @Override
    public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
      capturedRetrieveContextRequest.set(request);
      return RetrieveContextResponse.newBuilder().build();
    }
  }
}
