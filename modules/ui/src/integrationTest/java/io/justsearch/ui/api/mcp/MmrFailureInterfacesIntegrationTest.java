/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.core.execution.EngineTaskLifetime;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ui.api.TestRequestContexts;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

/** S2/S3/S4/S5/S8/S11: deterministic policy, deliberately NOT model compatibility evidence. */
@Timeout(300)
final class MmrFailureInterfacesIntegrationTest extends MmrEngineFixture {
  @Test
  void partialAndInvalidPassageVectorsKeepThePositionPoolThroughRestMcpAndConversation()
      throws Exception {
    start(null);
    var position = retrieve();
    assertPassages(position);
    assertEquals("BM25", position.retrievalMode());
    // The provider seam and cleared controller isolate selection policy. They cannot certify
    // native model readiness, persisted compatibility, or either vector retrieval branch.
    worker.setEmbeddingCompatController(null);
    assertAll(List.of(Failure.NULL, Failure.THROWN, Failure.NAN, Failure.ZERO,
        Failure.WRONG_DIMENSION).stream().map(failure -> (Executable) () -> {
      var provider = new ScriptedProvider(failure);
      worker.setEmbeddingProvider(provider);
      var result = retrieve();
      System.out.println("MMR_FAILURE kind=" + failure + " mode=" + result.retrievalMode()
          + " selected=" + identities(result) + " passageCalls=" + provider.passages.get());
      assertAll(failure.name(),
          () -> assertPassages(result),
          () -> assertEquals(identities(position), identities(result), "full positional fallback"),
          () -> assertEquals(position.context(), result.context(), "same eligible assembly"),
          () -> assertEquals(1, provider.queries.get(), "one MMR query acquisition"),
          () -> assertTrue(provider.passages.get() > 0, "failure follows successful vectors"),
          () -> assertOutward(position),
          () -> assertConversation(documents));
    }));
  }

  @Test
  void invalidQueryGoesStraightToPositionWithoutPassageInference() throws Exception {
    start(null);
    var position = retrieve();
    worker.setEmbeddingCompatController(null);
    var provider = new ScriptedProvider(Failure.QUERY_NAN);
    worker.setEmbeddingProvider(provider);
    var result = retrieve();
    assertPassages(result);
    assertEquals(identities(position), identities(result));
    assertEquals(1, provider.queries.get());
    assertEquals(0, provider.passages.get(), "invalid query must avoid rank-only passage inference");
    // REST/MCP start with a hybrid pre-search, then BM25 retrieval with rank-only MMR.
    // D7 makes a restricted pre-search failure terminal, so inject NaN only into MMR.
    var outwardProvider = new ScriptedProvider(Failure.QUERY_NAN) {
      @Override public float[] embedQuery(String text) {
        float[] invalid = super.embedQuery(text);
        return queries.get() % 2 == 1 ? new float[] {1, 0} : invalid;
      }
    };
    worker.setEmbeddingProvider(outwardProvider);
    assertOutward(result);
    assertEquals(4, outwardProvider.queries.get(), "each interface runs pre-search then MMR");
    assertEquals(0, outwardProvider.passages.get(), "REST and MCP also avoid passage inference");
  }

  @Test
  void cancelledSupplierWithNormallyReturningProviderPreservesWorkerAndOutwardBoundaries()
      throws Exception {
    start(null);
    worker.setEmbeddingCompatController(null);
    var cancelled = new AtomicBoolean();
    var provider = new ScriptedProvider(Failure.NONE) {
      @Override
      public float[] embedDocument(String text, InferenceRequest request) {
        // Deliberately cooperative-supplier cancellation, NOT a provider-thrown failure.
        // Returning normally makes the existing worker postcheck the authority under test.
        float[] value = embedDocument(text);
        cancelled.set(true);
        return value;
      }
    };
    worker.setEmbeddingProvider(provider);
    var request = RetrieveContextRequest.newBuilder().setQuestion(QUESTION)
        .addDocIds(article.toString()).setPathPrefix(article.getParent().toString()).setTopK(K)
        .setMaxContextTokens(6000).setAutoEntityExtract(false).build();
    java.util.function.Supplier<CallContext> call = () -> new CallContext(null,
        "mmr-cancelled-supplier", cancelled::get, TestRequestContexts.browser(),
        new JobQueue.EnqueueProvenance("user", "BUTTON"), EngineTaskLifetime.NONE);
    var failure = assertThrows(CancellationException.class,
        () -> worker.retrieveContext(request, call.get()));
    assertTrue(provider.passages.get() > 0, "cancelled after a normally returned passage");
    System.out.println("MMR_CANCEL_WORKER type=" + failure.getClass().getName()
        + " message=" + failure.getMessage() + " passageCalls=" + provider.passages.get());

    // Signal injection at the existing worker port, while all other client calls delegate to
    // the real Engine. Cancellation must survive the optional whole-document fallback.
    KnowledgeClient adapter = mock(KnowledgeClient.class, delegatesTo(client));
    doAnswer(ignored -> {
      cancelled.set(false);
      return worker.retrieveContext(request, call.get());
    }).when(adapter).retrieveContext(any(RetrieveContextParams.class), any());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var remote = new RemoteDocumentService(executor, executor, () -> adapter);
      var outward = remote.retrieveContext(params(), TestRequestContexts.browser())
          .handle((result, error) -> error).toCompletableFuture().join();
      assertNotNull(outward, "cancellation must not become a successful fallback");
      while (outward instanceof java.util.concurrent.CompletionException
          || outward instanceof java.util.concurrent.ExecutionException) outward = outward.getCause();
      assertInstanceOf(CancellationException.class, outward);
    }
  }

  enum Failure { NONE, NULL, THROWN, NAN, ZERO, WRONG_DIMENSION, QUERY_NAN }

  /** Two successes, then only unusable vectors: original worker underfills the NULL/THROWN case. */
  static class ScriptedProvider implements EmbeddingProvider {
    final Failure failure;
    final AtomicInteger queries = new AtomicInteger();
    final AtomicInteger passages = new AtomicInteger();

    ScriptedProvider(Failure failure) { this.failure = failure; }

    public float[] embedQuery(String text) {
      queries.incrementAndGet();
      passages.set(0);
      return failure == Failure.QUERY_NAN ? new float[] {Float.NaN, 1} : new float[] {1, 0};
    }

    public float[] embedQuery(String text, InferenceRequest request) {
      return embedQuery(text);
    }

    public float[] embedDocument(String text) {
      int attempt = passages.getAndIncrement();
      if (attempt < 2 || failure == Failure.NONE) return new float[] {1, 0};
      return switch (failure) {
        case NULL -> null;
        case THROWN -> throw new IllegalStateException("controlled passage embedding failure");
        case NAN -> new float[] {Float.NaN, 1};
        case ZERO -> new float[] {0, 0};
        case WRONG_DIMENSION -> new float[] {1, 0, 0};
        default -> new float[] {1, 0};
      };
    }

    public float[] embedDocument(String text, InferenceRequest request) {
      return embedDocument(text);
    }

    public List<float[]> embedDocumentBatch(List<String> texts) {
      throw new AssertionError("query-only provider must never enter indexing");
    }
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String text, int[][] spans) {
      throw new AssertionError("query-only provider must never enter indexing");
    }
    public int dimension() { return 2; }
    public Availability availability() { return Availability.READY; }
    public boolean isUsingGpu() { return false; }
  }
}
