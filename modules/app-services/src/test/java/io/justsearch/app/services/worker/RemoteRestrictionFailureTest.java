/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Separate injected Head pre-search and rich-port failures must not enter batch fetch. */
class RemoteRestrictionFailureTest {
  @Test
  void preSearchAndDiscoveredIdCopyPreserveRestrictionsOnTheWorkerRequest() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
    var sentSearch = new java.util.concurrent.atomic.AtomicReference<SearchRequest>();
    var sentRetrieval = new java.util.concurrent.atomic.AtomicReference<RetrieveContextRequest>();
    var calls = new TestKnowledgeClient.SearchCalls() {
      @Override public SearchResponse search(SearchRequest request) {
        sentSearch.set(request);
        return SearchResponse.newBuilder().addResults(SearchResult.newBuilder()
            .setId("/inside/target").putFields("path", "/inside/target")).build();
      }
      @Override public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
        sentRetrieval.set(request);
        return RetrieveContextResponse.getDefaultInstance();
      }
    };
    try (var executors = new TestEngineExecutors();
        var client = new TestKnowledgeClient(executors, calls)) {
      var service = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
      var params = new RetrieveContextParams("needle", Set.of(), 5, 500,
          List.of(), List.of(), List.of(), new RetrieveContextParams.TimeRange(100, 200),
          false, "/inside", List.of(), false, RetrieveContextParams.ContextFormat.LABELED,
          List.of(), List.of(), List.of(), RetrieveContextParams.TimeRange.UNSET,
          false, List.of(), List.of("notes"));
      try {
        Class<?> type = Class.forName("io.justsearch.app.api.knowledge.KnowledgeSearchRequest$ByteRange");
        Object range = type.getDeclaredConstructor(Long.class, Long.class).newInstance(0L, 500L);
        params = (RetrieveContextParams) RetrieveContextParams.class.getMethod("withSizeBytes", type).invoke(params, range);
      } catch (ClassNotFoundException absentOnBaseline) {
        // Exercise the baseline's real omission at the Worker boundary.
      }
      service.retrieveContext(params, io.justsearch.app.services.TestEngineContexts.internal())
          .toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals("/inside", sentSearch.get().getFilters().getPathPrefix());
      assertEquals(100, sentSearch.get().getFilters().getModifiedAt().getFromMs());
      assertEquals(List.of("/inside/target"), sentRetrieval.get().getDocIdsList());
      assertEquals(List.of("notes"), sentRetrieval.get().getCollectionList());
      for (com.google.protobuf.Message request : List.of(sentSearch.get().getFilters(), sentRetrieval.get())) {
        var field = request.getDescriptorForType().findFieldByName("size_bytes");
        assertNotNull(field, "size must reach the actual Worker request");
        assertTrue(request.hasField(field));
        var range = (com.google.protobuf.Message) request.getField(field);
        assertEquals(0L, range.getField(range.getDescriptorForType().findFieldByName("from_bytes")));
        assertEquals(500L, range.getField(range.getDescriptorForType().findFieldByName("to_bytes")));
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void preSearchAndRichPortFailuresDoNotBroadenRestrictedRetrieval() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
    try {
      for (boolean failPreSearch : List.of(true, false)) {
        AtomicInteger richCalls = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        var calls = new TestKnowledgeClient.SearchCalls() {
          @Override public SearchResponse search(SearchRequest request) {
            if (failPreSearch) throw new IllegalStateException("injected pre-search failure");
            return SearchResponse.newBuilder().addResults(SearchResult.newBuilder()
                .setId("/inside/target").putFields("path", "/inside/target")).build();
          }
          @Override public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
            richCalls.incrementAndGet();
            throw new IllegalStateException("injected rich-port failure");
          }
          @Override public FetchDocumentsResponse fetchDocuments(FetchDocumentsRequest request) {
            fetches.incrementAndGet();
            return FetchDocumentsResponse.getDefaultInstance();
          }
        };
        try (var executors = new TestEngineExecutors();
            var client = new TestKnowledgeClient(executors, calls)) {
          var service = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
          // Existing folder/date positional constructor allows this check on the baseline.
          var params = new RetrieveContextParams("needle", Set.of(), 5, 500,
              List.of(), List.of(), List.of(), new RetrieveContextParams.TimeRange(100, 200),
              false, "/inside", List.of(), false, RetrieveContextParams.ContextFormat.LABELED,
              List.of(), List.of(), List.of(), RetrieveContextParams.TimeRange.UNSET,
              false, List.of(), List.of());
          var error = assertThrows(ExecutionException.class, () -> service.retrieveContext(params,
              io.justsearch.app.services.TestEngineContexts.internal())
              .toCompletableFuture().get(5, TimeUnit.SECONDS));
          assertTrue(error.toString().contains("injected") || error.getCause().toString().contains("injected"));
          assertEquals(failPreSearch ? 0 : 1, richCalls.get());
          assertEquals(0, fetches.get(), "restricted request must never fall back to unfiltered fetch");
        }
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }
}
