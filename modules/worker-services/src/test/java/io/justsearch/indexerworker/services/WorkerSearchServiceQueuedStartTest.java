/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.pipeline;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.QueryDevice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * #759: a search that waited for an inference thread
 * before it started keeps the semantic sub-budget its call began with. The cap counts from the
 * start of the call, not from the moment a thread picks it up, and a sub-budget that the wait
 * used up gives the existing Spec D outcome at once, without starting an encoder: keyword-only
 * results with SEMANTIC_BUDGET_EXCEEDED (cause contended) for hybrid, AUTO and SPLADE searches,
 * BM25 context with the same reason for a RAG retrieval, and the typed deadline for VECTOR-only.
 *
 * <p>Real seam: {@link WorkerSearchService#search} and {@link
 * WorkerSearchService#retrieveContext} over a real Lucene index. The call starts when its
 * {@link CallContext} is built, as the Engine builds it before the call waits for a thread; the
 * test then waits, as a queued call would, before entering the service. Only the encoders are
 * replaced, by ones that answer at once and count their calls; they report the GPU, so the
 * search's semantic cap is the 2 000 ms GPU cap of {@link SearchBudgetPolicy#DEFAULT}.
 *
 * <p>Uses only API that exists before the change, so each check runs on the before-state.
 */
final class WorkerSearchServiceQueuedStartTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** A request deadline long enough that the GPU cap, not the deadline, bounds the sub-budget. */
  private static final Duration REQUEST_BUDGET = Duration.ofSeconds(10);

  /** The time the call waits for a thread: longer than the 2 000 ms GPU semantic cap. */
  private static final Duration QUEUED_PAST_THE_CAP = Duration.ofMillis(2_300);

  private final Map<String, String> previousProperties = new HashMap<>();
  private RunningRuntime lifecycle;

  @AfterEach
  void closeIndex() throws Exception {
    if (lifecycle != null) lifecycle.close();
    WorkerSearchServiceRagBudgetTest.restoreProperties(previousProperties);
  }

  @Test
  // FP2, FP3: hybrid whose semantic cap passed while the call waited answers keyword-only
  void hybridSearchThatWaitedPastItsSemanticCapAnswersKeywordOnlyWithoutEncoding()
      throws Exception {
    lifecycle = openSearchIndex();
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    SearchRequest request = query(pipeline().setSparseEnabled(true).setDenseEnabled(true));

    CallContext call = foreground(REQUEST_BUDGET);
    waitAsIfQueued(QUEUED_PAST_THE_CAP);
    SearchResponse response =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.search(request, call));

    assertEquals(0, embeddings.queryCalls.get(), "the dense leg started its encoder");
    assertKeywordOnlyContended(response);
  }

  @Test
  // FP3: AUTO dense follows the same rule for its dense leg
  void autoDenseSearchThatWaitedPastItsSemanticCapAnswersKeywordOnlyWithoutEncoding()
      throws Exception {
    lifecycle = openSearchIndex();
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    SearchRequest request = query(pipeline().setSparseEnabled(true).setDenseAuto(true));

    CallContext call = foreground(REQUEST_BUDGET);
    waitAsIfQueued(QUEUED_PAST_THE_CAP);
    SearchResponse response =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.search(request, call));

    assertEquals(0, embeddings.queryCalls.get(), "the dense leg started its encoder");
    assertKeywordOnlyContended(response);
  }

  @Test
  // FP3: a SPLADE leg whose semantic cap passed while the call waited is skipped, with the cause
  void spladeSearchThatWaitedPastItsSemanticCapSkipsSpladeWithoutEncoding() throws Exception {
    lifecycle = openSearchIndex();
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(splade.queryDevice()).thenReturn(QueryDevice.GPU);
    var spladeEncodes = new AtomicInteger();
    when(splade.encode(anyString(), any(InferenceRequest.class))).thenAnswer(invocation -> {
      spladeEncodes.incrementAndGet();
      return Map.of("hello", 1.0f);
    });
    var bindings = new EncoderBindings();
    bindings.bindSpladeEncoder(splade);
    var service = new WorkerSearchService(lifecycle, new GpuEmbeddings(), bindings);
    SearchRequest request = query(pipeline().setSparseEnabled(true).setSpladeEnabled(true));

    CallContext call = foreground(REQUEST_BUDGET);
    waitAsIfQueued(QUEUED_PAST_THE_CAP);
    SearchResponse response =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.search(request, call));

    assertEquals(0, spladeEncodes.get(), "the SPLADE query encoder started");
    var degradation = response.getSearchTrace().getDegradation();
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getSpladeSkipReason());
    assertEquals("contended", degradation.getSpladeSkipCause());
    assertFalse(degradation.getSpladeExecuted());
    assertTrue(response.getTotalHits() >= 1, "keyword results remain available");
  }

  @Test
  // FP3: VECTOR-only whose semantic budget (D - margin) passed while the call waited is the typed
  // deadline with the reason, never a run on what is left of D
  void vectorOnlySearchThatWaitedPastItsSemanticBudgetIsTheTypedDeadline() throws Exception {
    lifecycle = openSearchIndex();
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    SearchRequest request = query(pipeline().setDenseEnabled(true));

    // D = 1 500 ms; VECTOR-only waits within D - 500 ms, which the 1 100 ms wait passes.
    CallContext call = foreground(Duration.ofMillis(1_500));
    waitAsIfQueued(Duration.ofMillis(1_100));
    var failure =
        assertThrows(WorkerServiceException.class, () -> service.search(request, call));

    assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
    assertTrue(failure.getMessage().contains("SEMANTIC_BUDGET_EXCEEDED"), failure.getMessage());
    assertTrue(failure.getMessage().contains("contended"), failure.getMessage());
    assertEquals(0, embeddings.queryCalls.get(), "the dense leg started its encoder");
  }

  @Test
  // FP2, FP3: a RAG retrieval whose semantic cap passed while the call waited answers with BM25
  void ragRetrievalThatWaitedPastItsSemanticCapAnswersWithBm25WithoutEncoding()
      throws Exception {
    lifecycle =
        WorkerSearchServiceRagBudgetTest.openIndex(
            previousProperties, testLuceneExecutors(), "position",
            "Hello world from the queued retrieval test");
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);

    CallContext call = foreground(REQUEST_BUDGET);
    waitAsIfQueued(QUEUED_PAST_THE_CAP);
    RetrieveContextResponse response =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> service.retrieveContext(WorkerSearchServiceRagBudgetTest.question("Hello"), call));

    assertEquals(0, embeddings.queryCalls.get(), "the query embedding started its encoder");
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("contended", response.getRetrievalModeCause());
    assertEquals("BM25", response.getRetrievalMode());
    assertTrue(response.getChunksCount() >= 1, "BM25 context remains available");
  }

  @Test
  // Preservation: a call that did not wait keeps its whole semantic cap and runs its legs
  void hybridSearchThatDidNotWaitRunsItsDenseLeg() throws Exception {
    lifecycle = openSearchIndex();
    var embeddings = new GpuEmbeddings();
    var service = new WorkerSearchService(lifecycle, embeddings);
    SearchRequest request = query(pipeline().setSparseEnabled(true).setDenseEnabled(true));

    CallContext call = foreground(REQUEST_BUDGET);
    SearchResponse response =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.search(request, call));

    assertEquals(1, embeddings.queryCalls.get(), "the dense leg ran its encoder");
    assertFalse(response.getSearchTrace().getDegradation().getHybridFallback());
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------------

  private static void assertKeywordOnlyContended(SearchResponse response) {
    var degradation = response.getSearchTrace().getDegradation();
    assertTrue(degradation.getHybridFallback(), "the dense leg fell back");
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getHybridFallbackReason());
    assertEquals("contended", degradation.getHybridFallbackCause());
    assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
    assertTrue(response.getTotalHits() >= 1, "keyword results remain available");
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

  /** A query embedder on the GPU that answers at once and counts its query embeds. */
  static final class GpuEmbeddings implements EmbeddingProvider {
    final AtomicInteger queryCalls = new AtomicInteger();

    @Override
    public float[] embedQuery(String text, InferenceRequest request) {
      queryCalls.incrementAndGet();
      request.remainingNanos();
      return new float[] {1f, 0f, 0f, 0f};
    }

    @Override
    public float[] embedDocument(String text, InferenceRequest request) {
      return new float[] {1f, 0f, 0f, 0f};
    }

    @Override
    public float[] embedQuery(String text) {
      throw new AssertionError("a query embed must carry its request");
    }

    @Override
    public float[] embedDocument(String text) {
      throw new AssertionError("a document embed must carry its request");
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      throw new AssertionError("no batch embeds in a search");
    }

    @Override
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return 4;
    }

    @Override
    public Availability availability() {
      return Availability.READY;
    }

    @Override
    public boolean isUsingGpu() {
      return true;
    }

    @Override
    public QueryDevice queryDevice() {
      return QueryDevice.GPU;
    }
  }
}
