/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.pipeline;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.services.WorkerSearchServiceQueuedStartTest.GpuEmbeddings;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** FP3/U4: queue expiry precedes loading; loading begun after timely seating stays loading. */
final class WorkerSearchServiceQueuedLoadingTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final Duration REQUEST_BUDGET = Duration.ofSeconds(10);
  private static final Duration QUEUED_PAST_THE_CAP = Duration.ofMillis(2_300);
  private final Map<String, String> previousProperties = new HashMap<>();
  private RunningRuntime lifecycle;

  @AfterEach
  void closeIndex() throws Exception {
    if (lifecycle != null) lifecycle.close();
    WorkerSearchServiceRagBudgetTest.restoreProperties(previousProperties);
  }

  @Test
  // U4, FP3: the queue consumed the cap before models could be awaited.
  void queuedHybridWithModelsLoadingReportsContentionWithoutAwaitingModels() throws Exception {
    lifecycle = openSearchIndex();
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    var loading = new ObservedLatch(1);
    service.setModelReadyLatchSupplier(() -> loading);
    var request = query(pipeline().setSparseEnabled(true).setDenseEnabled(true));
    CallContext call = foreground(REQUEST_BUDGET);
    waitAsIfQueued(QUEUED_PAST_THE_CAP);

    SearchResponse response = service.search(request, call);

    assertKeywordOnlyContended(response);
    assertEquals(0, embeddings.queryCalls.get());
    assertEquals(0, loading.waitCalls.get(), "the queue already spent the semantic budget");
  }

  @Test
  // U4, FP3: retrieval uses the same cause precedence, including prior loading observations.
  void queuedRagWithModelsLoadingReportsContentionWithoutAwaitingModels() throws Exception {
    lifecycle = WorkerSearchServiceRagBudgetTest.openIndex(
        previousProperties, testLuceneExecutors(), "position", "Hello queued retrieval");
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    var loading = new ObservedLatch(1);
    service.setModelReadyLatchSupplier(() -> loading);
    CallContext call = foreground(REQUEST_BUDGET);
    waitAsIfQueued(QUEUED_PAST_THE_CAP);

    var response = service.retrieveContext(
        WorkerSearchServiceRagBudgetTest.question("Hello").toBuilder()
            .setSemanticLoadingObserved(true).build(), call);

    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("contended", response.getRetrievalModeCause());
    assertEquals("BM25", response.getRetrievalMode());
    assertTrue(response.getChunksCount() >= 1);
    assertEquals(0, embeddings.queryCalls.get());
    assertEquals(0, loading.waitCalls.get());
  }

  @Test
  // Preservation: a request seated in time waits for loading and keeps that cause.
  void seatedHybridWithModelsLoadingKeepsLoadingCause() throws Exception {
    lifecycle = openSearchIndex();
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    var loading = new ObservedLatch(1);
    service.setModelReadyLatchSupplier(() -> loading);

    var response = service.search(
        query(pipeline().setSparseEnabled(true).setDenseEnabled(true)),
        foreground(REQUEST_BUDGET));

    var degradation = response.getSearchTrace().getDegradation();
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getHybridFallbackReason());
    assertEquals("loading", degradation.getHybridFallbackCause());
    assertTrue(response.getTotalHits() >= 1);
    assertEquals(0, embeddings.queryCalls.get());
    assertTrue(loading.waitCalls.get() > 0, "a timely query waits for loading models in cancellation-aware slices");
  }

  private static final class ObservedLatch extends CountDownLatch {
    final AtomicInteger waitCalls = new AtomicInteger();

    ObservedLatch(int count) {
      super(count);
    }

    @Override
    public boolean await(long time, TimeUnit unit) throws InterruptedException {
      waitCalls.incrementAndGet();
      return super.await(time, unit);
    }
  }

  @Test
  void queuedHybridWithReadyModelsStillReportsContention() throws Exception {
    lifecycle = openSearchIndex();
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    var ready = new ObservedLatch(0);
    service.setModelReadyLatchSupplier(() -> ready);
    CallContext call = foreground(REQUEST_BUDGET);
    waitAsIfQueued(QUEUED_PAST_THE_CAP);

    var response = service.search(
        query(pipeline().setSparseEnabled(true).setDenseEnabled(true)), call);

    assertKeywordOnlyContended(response);
    assertEquals(0, embeddings.queryCalls.get());
    assertEquals(0, ready.waitCalls.get());
  }

  private static void assertKeywordOnlyContended(SearchResponse response) {
    var degradation = response.getSearchTrace().getDegradation();
    assertTrue(degradation.getHybridFallback());
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getHybridFallbackReason());
    assertEquals("contended", degradation.getHybridFallbackCause());
    assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
    assertTrue(response.getTotalHits() >= 1);
  }

  private static void waitAsIfQueued(Duration wait) throws InterruptedException {
    long until = System.nanoTime() + wait.toNanos();
    long remaining;
    while ((remaining = until - System.nanoTime()) > 0) {
      TimeUnit.NANOSECONDS.sleep(remaining);
    }
  }

  private RunningRuntime openSearchIndex() throws Exception {
    Path base = Files.createTempDirectory("justsearch-queued-start-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: queuedstart\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    previousProperties.putIfAbsent("justsearch.config", System.getProperty("justsearch.config"));
    System.setProperty("justsearch.config", cfg.toString());

    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .ephemeral()
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.PATH, "doc-1",
                SchemaFields.CONTENT, "Hello world")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return runtime;
  }

}
