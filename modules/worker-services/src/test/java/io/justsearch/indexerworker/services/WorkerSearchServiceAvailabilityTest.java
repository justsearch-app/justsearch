/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.ort.QueryDevice;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The same behavioural checks run against the boolean-only before-state and explicit states. */
final class WorkerSearchServiceAvailabilityTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private final Map<String, String> properties = new HashMap<>();
  private RunningRuntime runtime;
  private final AtomicInteger calls = new AtomicInteger();
  private final Throwable loadFailure = new IllegalStateException("tokenizer load failed");

  @AfterEach
  void close() throws Exception {
    if (runtime != null) runtime.close();
    WorkerSearchServiceRagBudgetTest.restoreProperties(properties);
  }

  @Test void readyAcrossAllModesPreservesQueueExpiry() throws Exception { queued("READY"); }
  @Test void startingAcrossAllModesPreservesQueueExpiry() throws Exception { queued("STARTING"); }
  @Test void absentAcrossAllModesPreservesAbsence() throws Exception { queued("ABSENT"); }
  @Test void failedAcrossAllModesKeepsUnavailableOutcome() throws Exception { queued("FAILED"); }

  @Test void timelyFailedAcrossAllModesPreservesUnavailableOutcome() throws Exception {
    check("FAILED", false);
  }

  private void queued(String state) throws Exception { check(state, true); }

  @Test void removedProviderAcrossAllModesKeepsAbsence() throws Exception {
    check("ABSENT", true, true);
  }

  private void check(String state, boolean expired) throws Exception { check(state, expired, false); }

  private void check(String state, boolean expired, boolean removed) throws Exception {
    runtime = WorkerSearchServiceRagBudgetTest.openIndex(
        properties, testLuceneExecutors(), "position", "Hello state retrieval");
    var provider = provider(state);
    var bindings = new EncoderBindings();
    boolean present = state.equals("READY") || state.equals("STARTING");
    if (present) {
      var splade = mock(SpladeEncoder.class, invocation -> {
        if (invocation.getMethod().getName().equals("queryDevice")) return QueryDevice.CPU;
        if (invocation.getMethod().getName().equals("encode")) {
          calls.incrementAndGet();
          return Map.of("Hello", 1f);
        }
        return RETURNS_DEFAULTS.answer(invocation);
      });
      bindings.bindSpladeEncoder(splade);
    }
    var service = new WorkerSearchService(runtime, provider, bindings);
    if (removed) service.setEmbeddingProvider(null);
    // A queued expiry does not await models. In particular FAILED is never still loading.
    var waits = new AtomicInteger();
    service.setModelReadyLatchSupplier(() -> {
      waits.incrementAndGet(); return new CountDownLatch(0);
    });
    // Expired: queued 8500 ms, past the 8000 ms CPU cap, with 21.5 s of the request still live.
    var call = WorkerSearchServiceQueuedUnavailableTest.queuedFor(
        Duration.ofSeconds(30), Duration.ofMillis(expired ? 8500 : 0));
    assertAll("All modes for " + state, () -> {
    var hybrid = service.search(query(pipeline().setSparseEnabled(true).setDenseEnabled(true)), call);
    var hd = hybrid.getSearchTrace().getDegradation();
    assertEquals(present ? "SEMANTIC_BUDGET_EXCEEDED" : "NO_EMBEDDING_SERVICE",
        hd.getHybridFallbackReason(), state + " HYBRID");
    assertEquals(present ? "contended" : "", hd.getHybridFallbackCause());
    assertTrue(hybrid.getTotalHits() > 0);
    }, () -> {
    var auto = service.search(query(pipeline().setSparseEnabled(true).setDenseAuto(true)), call);
    assertEquals(present, auto.getSearchTrace().getDegradation().getHybridFallback());
    assertEquals(present ? "SEMANTIC_BUDGET_EXCEEDED" : "",
        auto.getSearchTrace().getDegradation().getHybridFallbackReason());
    assertEquals(present ? "contended" : "",
        auto.getSearchTrace().getDegradation().getHybridFallbackCause());
    assertTrue(auto.getTotalHits() > 0);
    }, () -> {
    var splade = service.search(query(pipeline().setSparseEnabled(true).setSpladeEnabled(true)), call);
    assertEquals(present ? "SEMANTIC_BUDGET_EXCEEDED" : "NO_EMBEDDING_SERVICE",
        splade.getSearchTrace().getDegradation().getSpladeSkipReason());
    assertEquals(present ? "contended" : "",
        splade.getSearchTrace().getDegradation().getSpladeSkipCause());
    assertTrue(splade.getTotalHits() > 0);
    }, () -> {
    var rag = service.retrieveContext(WorkerSearchServiceRagBudgetTest.question("Hello"), call);
    assertEquals(present ? "SEMANTIC_BUDGET_EXCEEDED"
        : state.equals("ABSENT") ? "NO_EMBEDDING_SERVICE" : "EMBEDDING_UNAVAILABLE",
        rag.getRetrievalModeReason());
    assertEquals(present ? "contended" : "", rag.getRetrievalModeCause());
    assertEquals("BM25", rag.getRetrievalMode());
    assertTrue(rag.getChunksCount() > 0);
    }, () -> {
    // Expired: queued 1600 ms of 2000 ms; 400 ms live, under the 500 ms VECTOR-only margin.
    var vectorCall = WorkerSearchServiceQueuedUnavailableTest.queuedFor(
        Duration.ofMillis(expired ? 2000 : 20000), Duration.ofMillis(expired ? 1600 : 0));
    var error = assertThrows(WorkerServiceException.class,
        () -> service.search(query(pipeline().setDenseEnabled(true)), vectorCall));
    assertEquals(present ? WorkerServiceException.Status.DEADLINE_EXCEEDED
        : WorkerServiceException.Status.INVALID_ARGUMENT, error.status());
    if (present) {
      assertTrue(error.getMessage().contains("SEMANTIC_BUDGET_EXCEEDED"));
      assertTrue(error.getMessage().contains("contended"));
    } else {
      assertTrue(error.getMessage().contains("NO_EMBEDDING_SERVICE"));
    }
    });
    assertEquals(0, calls.get(), "no inference after queue expiry");
    assertEquals(expired ? 0 : 5, waits.get(), "model latch checked only before the semantic deadline");
  }

  private EmbeddingProvider provider(String state) {
    if (state.equals("ABSENT")) return NoOpEmbeddingProvider.INSTANCE;
    return mock(EmbeddingProvider.class, invocation -> switch (invocation.getMethod().getName()) {
      case "availability" -> availability(state);
      case "isAvailable" -> state.equals("READY");
      case "queryDevice" -> QueryDevice.CPU;
      case "dimension" -> 4;
      case "embedQuery" -> { calls.incrementAndGet(); yield new float[] {1, 0, 0, 0}; }
      default -> RETURNS_DEFAULTS.answer(invocation);
    });
  }

  private Object availability(String state) throws ReflectiveOperationException {
    var type = Class.forName(EmbeddingProvider.class.getName() + "$Availability");
    if (!state.equals("FAILED")) return type.getField(state).get(null);
    var enumType = Class.forName(type.getName() + "$State");
    Object failed = java.util.Arrays.stream(enumType.getEnumConstants())
        .filter(e -> e.toString().equals("FAILED")).findFirst().orElseThrow();
    return type.getConstructor(enumType, Throwable.class).newInstance(failed, loadFailure);
  }
}
