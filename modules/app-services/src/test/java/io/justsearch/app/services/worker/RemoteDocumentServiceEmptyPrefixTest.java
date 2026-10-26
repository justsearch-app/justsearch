/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** S12 through the actual caller fallback; no server, global properties or durable roots. */
final class RemoteDocumentServiceEmptyPrefixTest {
  private static final int MAX_CHARS = 200_000;

  @ParameterizedTest(name = "fallbackCountsOnlyNonemptyContributions({arguments})[{index}]")
  @ValueSource(booleans = {false, true})
  void fallbackCountsOnlyNonemptyContributions(boolean emptyPrefix) throws Exception {
    String firstId = "/docs/doc-1.txt";
    String secondId = "/docs/doc-2.txt";
    String firstLabel = "doc-1.txt";
    String secondLabel = "doc-2.txt";
    String firstHeader = ContextBudgeter.sectionHeader(1, firstLabel);
    String secondHeader = ContextBudgeter.sectionHeader(2, secondLabel);
    String separator = ContextBudgeter.SECTION_SEPARATOR;
    int firstLength = MAX_CHARS - firstHeader.length() - separator.length()
        - secondHeader.length() - 1;
    String first = "A".repeat(firstLength);
    var calls = new FailingRetrieveCalls(Map.of(
        firstId, first, secondId, emptyPrefix ? "😀" : "BC"));
    var orderedIds = new LinkedHashSet<>(List.of(firstId, secondId));

    try (var executors = new TestEngineExecutors();
        var client = new FallbackClient(executors, calls)) {
      var service = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
      var result = service.retrieveContextWithMeta("question", orderedIds, 5,
              io.justsearch.app.services.TestEngineContexts.internal())
          .toCompletableFuture().get(6, TimeUnit.SECONDS);

      assertEquals(1, calls.retrieveCalls, "the real retrieve path must fail before fallback");
      assertEquals(List.copyOf(orderedIds), calls.fetchedIds, "preserve the ordered fetch fixture");
      assertEquals(emptyPrefix ? 1 : 2, result.docsUsed());
      assertEquals(emptyPrefix ? 1 : 2, result.sections().size());
      assertEquals(firstHeader + first
          + (emptyPrefix ? "" : separator + secondHeader + "B"), result.context());
      assertEquals(emptyPrefix ? firstHeader.length() + firstLength : MAX_CHARS,
          result.context().length());
      assertEquals(new DocumentService.ContextSection(firstLabel, first, false, 0, 0),
          result.sections().get(0));
      if (emptyPrefix) {
        assertFalse(result.context().contains(secondHeader));
        assertFalse(result.context().contains(separator));
        assertTrue(result.sections().stream().noneMatch(s -> s.sourceLabel().equals(secondLabel)));
      } else {
        assertEquals(new DocumentService.ContextSection(secondLabel, "B", true, 1, 1),
            result.sections().get(1));
      }
      assertFalse(result.usedChunks());
      assertEquals(0, result.chunksUsed());
      assertEquals(0, result.chunksFound());
      assertTrue(result.citations().isEmpty(), "existing full-document fallback remains uncited");
      assertEquals("FULLTEXT_FALLBACK", result.retrievalMode());
      assertEquals("GRPC_FAILED", result.retrievalModeReason());
      assertEquals("", result.retrievalModeCause());
      assertFalse(result.contextTruncated(), "preserve the caller's existing fallback convention");
      assertEquals(0, result.quality().chunksIncluded());
    }
  }

  private static final class FailingRetrieveCalls extends TestKnowledgeClient.SearchCalls {
    private final Map<String, String> documents;
    private int retrieveCalls;
    private List<String> fetchedIds = List.of();

    private FailingRetrieveCalls(Map<String, String> documents) {
      this.documents = documents;
    }

    @Override
    public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
      retrieveCalls++;
      throw new KnowledgeClientException(KnowledgeClientException.Status.UNAVAILABLE,
          "forced failure for empty-prefix fallback test");
    }

    @Override
    public FetchDocumentsResponse fetchDocuments(FetchDocumentsRequest request) {
      fetchedIds = List.copyOf(request.getDocIdsList());
      var response = FetchDocumentsResponse.newBuilder();
      for (String id : fetchedIds) {
        response.addDocuments(DocumentContent.newBuilder().setDocId(id)
            .setContent(documents.get(id)).setFound(true));
      }
      return response.build();
    }
  }

  /** TestKnowledgeClient's transport pattern with the existing explicit in-memory roots seam. */
  private static final class FallbackClient extends KnowledgeClient {
    private final SearchServiceCalls calls;

    private FallbackClient(TestEngineExecutors executors, SearchServiceCalls calls) {
      super(executors, 5000, DEFAULT_BATCH_SIZE, IpcTelemetry.noop(), WatchedRootsState.inMemory());
      this.calls = calls;
    }

    @Override
    protected <T> T executeSearchRpc(String operation, RpcDeadlineCategory category,
        Function<SearchServiceCalls, T> rpc, EngineContext context) {
      return rpc.apply(calls);
    }

    @Override
    protected void executeRootWalk(ExecutorService executor, Consumer<EngineContext> body,
        EngineContext context) {
      throw new UnsupportedOperationException("root walk");
    }

    @Override
    protected <T> T executeIngestRpc(String operation, RpcDeadlineCategory category,
        Function<IngestServiceCalls, T> rpc, EngineContext context) {
      throw new UnsupportedOperationException("ingest");
    }

    @Override
    protected <T> T executeHealthRpc(String operation, long deadlineMs,
        Function<HealthServiceCalls, T> rpc, EngineContext context) {
      throw new UnsupportedOperationException("health");
    }

    @Override
    protected ScanRootProgress executeScanRoot(ScanRootRequest request, CancelToken cancel,
        Consumer<ScanRootProgress> progress, EngineContext context) {
      throw new UnsupportedOperationException("scan");
    }

    @Override
    public IndexingJobsStream subscribeIndexingJobs(Consumer<IndexingJobsFrame> frames,
        Consumer<Throwable> errors, Runnable completed, EngineContext context) {
      throw new UnsupportedOperationException("jobs");
    }

    @Override
    public IndexingJobsStream subscribeIndexingJobOutcomes(Consumer<IndexingJobsFrame> frames,
        Consumer<Throwable> errors, Runnable completed, EngineContext context) {
      throw new UnsupportedOperationException("job outcomes");
    }

    @Override
    public int recoverVduProcessingForRetainedOwner(io.justsearch.app.api.EngineWorkHandle owner) {
      throw new UnsupportedOperationException("recover");
    }

    @Override
    protected void closeTransport() {}
  }
}
