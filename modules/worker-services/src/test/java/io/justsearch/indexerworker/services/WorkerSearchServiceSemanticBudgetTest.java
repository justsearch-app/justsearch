/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.Composition;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionAcquireDeadlineExceededException;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D (item 5a) tests B1a, B1b, B2, B3, B4, B5, P6a and P6b: a search answers within its
 * deadline.
 * When query-side semantic work cannot finish inside the search's semantic sub-budget, the search
 * returns keyword results with the reason instead of a 504 (S1, S2).
 *
 * <p>Real seam: every check enters through {@link WorkerSearchService#search}, the production
 * entry point, with the production index, planner and encoder bindings. Only the native encoder is
 * replaced. The contended cases hold a real {@code NativeSessionHandle}'s device permit with a
 * BACKGROUND lease (the bulk-indexing shape of M1 run A1), and the query leg acquires through the
 * same handle; the native {@code OrtSession} is a mock, because a native run cannot be made to
 * stall on demand.
 *
 * <p>Uses only API that exists before the change, so each repaired check runs on the before-state.
 */
final class WorkerSearchServiceSemanticBudgetTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** The request deadline the head would give a search, shortened so the suite stays fast. */
  static final Duration REQUEST_BUDGET = Duration.ofSeconds(3);

  /**
   * The semantic deadline plus the margin under the default search budget: {@code min(cap, D −
   * lexical reserve − margin) + margin}. The test embedder reports the default CPU query device
   * (cap 8 s), so with {@link #REQUEST_BUDGET} (3 s) that is {@code D − 250 ms} lexical reserve.
   */
  static final Duration SEMANTIC_DEADLINE_PLUS_MARGIN = Duration.ofMillis(3_000 - 250);

  private String previousConfig;
  private RunningRuntime lifecycle;
  private final CountDownLatch releaseOnExit = new CountDownLatch(1);

  @BeforeEach
  void openIndex() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    lifecycle = newLifecycleWithOneDoc("Hello world");
  }

  @AfterEach
  void closeIndex() throws Exception {
    releaseOnExit.countDown();
    if (lifecycle != null) lifecycle.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
  }

  @Test
  // B1a: explicit hybrid with the models latch closed answers in time with the reason
  void explicitHybridWithModelsLoadingAnswersWithKeywordResults() {
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.starting());
    service.setModelReadyLatchSupplier(() -> releaseOnExit);
    SearchRequest request = query(pipeline().setSparseEnabled(true).setDenseEnabled(true));

    CallContext call = foreground(REQUEST_BUDGET);
    SearchResponse response =
        assertTimeoutPreemptively(REQUEST_BUDGET, () -> service.search(request, call));

    assertKeywordFallback(response, "SEMANTIC_BUDGET_EXCEEDED");
  }

  @Test
  // B1b: AUTO dense with the models latch closed reports the budget, not a planner skip
  void autoDenseWithModelsLoadingReportsTheBudgetReason() {
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.starting());
    service.setModelReadyLatchSupplier(() -> releaseOnExit);
    SearchRequest request = query(pipeline().setSparseEnabled(true).setDenseAuto(true));

    CallContext call = foreground(REQUEST_BUDGET);
    SearchResponse response =
        assertTimeoutPreemptively(REQUEST_BUDGET, () -> service.search(request, call));

    assertKeywordFallback(response, "SEMANTIC_BUDGET_EXCEEDED");
  }

  @Test
  // B2: a background lease held on the embedding handle gives keyword results in time
  void contendedEmbeddingHandleGivesKeywordResults() throws Exception {
    try (SessionHandle embeddingHandle = gpuHandle("b2-embedding");
        SessionHandle.Lease bulk = holdBackground(embeddingHandle)) {
      var embeddings = new HandleBackedEmbeddings(embeddingHandle);
      var service = new WorkerSearchService(lifecycle, embeddings);
      SearchRequest request = query(SearchMode.SEARCH_MODE_HYBRID);

      long start = System.nanoTime();
      CallContext call = foreground(REQUEST_BUDGET);
      SearchResponse response = assertDoesNotThrow(() -> service.search(request, call));

      assertTrue(System.nanoTime() - start < REQUEST_BUDGET.toNanos(), "answered after D");
      assertEquals(1, embeddings.calls.get(), "the dense leg tried its encoder");
      assertKeywordFallback(response, "SEMANTIC_BUDGET_EXCEEDED");
    }
  }

  @Test
  // B3: a held SPLADE handle skips only SPLADE, with the reason; dense still runs
  void contendedSpladeHandleSkipsOnlyThatLeg() throws Exception {
    try (SessionHandle spladeHandle = gpuHandle("b3-splade", 1);
        SessionHandle.Lease bulk = holdBackground(spladeHandle);
        SessionHandle denseHandle = gpuHandle("b3-dense")) {
      var bindings = new EncoderBindings();
      bindings.bindSpladeEncoder(handleBackedSplade(spladeHandle));
      var embeddings = new HandleBackedEmbeddings(denseHandle);
      var service = new WorkerSearchService(lifecycle, embeddings, bindings);
      SearchRequest request =
          query(pipeline().setSparseEnabled(true).setDenseEnabled(true).setSpladeEnabled(true));

      long start = System.nanoTime();
      CallContext call = foreground(REQUEST_BUDGET);
      SearchResponse response = assertDoesNotThrow(() -> service.search(request, call));

      assertTrue(System.nanoTime() - start < REQUEST_BUDGET.toNanos(), "answered after D");
      var degradation = response.getSearchTrace().getDegradation();
      assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getSpladeSkipReason());
      assertFalse(degradation.getSpladeExecuted());
      assertFalse(degradation.getHybridFallback(), "the dense leg answered");
      assertTrue(response.getTotalHits() >= 1, "keyword results remain available");
    }
  }

  @Test
  // B4: a dense run that does not return gives keyword results within the semantic deadline plus
  // the margin, and the seat comes back, exactly once, when the run finally returns
  void stalledDenseRunGivesKeywordResultsInTime() throws Exception {
    CountDownLatch runMayReturn = new CountDownLatch(1);
    OrtSession stalled = mock(OrtSession.class);
    when(stalled.run(anyMap()))
        .thenAnswer(
            invocation -> {
              runMayReturn.await(60, TimeUnit.SECONDS);
              return mock(OrtSession.Result.class);
            });
    try (SessionHandle embeddingHandle = gpuHandle("b4-embedding", stalled)) {
      var embeddings = new HandleBackedEmbeddings(embeddingHandle, /* runsNative= */ true);
      var service = new WorkerSearchService(lifecycle, embeddings);
      SearchRequest request = query(SearchMode.SEARCH_MODE_HYBRID);
      // Warm the lexical path untimed, so the bound measures the semantic leg, not a cold JVM.
      service.search(query(SearchMode.SEARCH_MODE_TEXT), foreground(REQUEST_BUDGET));

      CallContext call = foreground(REQUEST_BUDGET);
      SearchResponse response;
      try {
        response =
            assertTimeoutPreemptively(
                SEMANTIC_DEADLINE_PLUS_MARGIN, () -> service.search(request, call));
      } finally {
        runMayReturn.countDown();
      }

      assertEquals(1, embeddings.calls.get(), "the dense leg started its run");
      assertKeywordFallback(response, "SEMANTIC_BUDGET_EXCEEDED");
      assertSeatCountIsFull(embeddingHandle);
    }
  }

  @Test
  // B5: a request deadline too short for any semantic budget still ends in DEADLINE_EXCEEDED
  void deadlineTooShortForASemanticBudgetIsStillADeadline() throws Exception {
    try (SessionHandle embeddingHandle = gpuHandle("b5-embedding");
        SessionHandle.Lease bulk = holdBackground(embeddingHandle)) {
      var service = new WorkerSearchService(lifecycle, new HandleBackedEmbeddings(embeddingHandle));
      SearchRequest request = query(SearchMode.SEARCH_MODE_HYBRID);

      CallContext call = foreground(Duration.ofMillis(300));
      var failure =
          assertThrows(WorkerServiceException.class, () -> service.search(request, call));

      assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
    }
  }

  @Test
  // P6a: a lexical-only search with the models latch closed returns at once
  void lexicalOnlySearchDoesNotWaitForModels() {
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.INSTANCE);
    service.setModelReadyLatchSupplier(() -> releaseOnExit);
    SearchRequest request = query(SearchMode.SEARCH_MODE_TEXT);

    CallContext call = foreground(REQUEST_BUDGET);
    SearchResponse response =
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> service.search(request, call));

    assertTrue(response.getTotalHits() >= 1);
    assertFalse(response.getSearchTrace().getDegradation().getHybridFallback());
  }

  @Test
  // P6b: a lexical-only search with a blocked encoder never acquires it and never falls back
  void lexicalOnlySearchNeverTouchesTheSemanticBudget() throws Exception {
    try (SessionHandle embeddingHandle = gpuHandle("p6b-embedding");
        SessionHandle.Lease bulk = holdBackground(embeddingHandle)) {
      var embeddings = new HandleBackedEmbeddings(embeddingHandle);
      var service = new WorkerSearchService(lifecycle, embeddings);
      SearchRequest request = query(SearchMode.SEARCH_MODE_TEXT);

      CallContext call = foreground(REQUEST_BUDGET);
      SearchResponse response =
          assertTimeoutPreemptively(Duration.ofSeconds(1), () -> service.search(request, call));

      assertEquals(0, embeddings.calls.get(), "lexical-only search acquired the encoder");
      var degradation = response.getSearchTrace().getDegradation();
      assertFalse(degradation.getHybridFallback());
      assertFalse("SEMANTIC_BUDGET_EXCEEDED".equals(degradation.getHybridFallbackReason()));
      assertFalse("SEMANTIC_BUDGET_EXCEEDED".equals(degradation.getSpladeSkipReason()));
      assertTrue(response.getTotalHits() >= 1);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------------

  static void assertKeywordFallback(SearchResponse response, String reason) {
    var degradation = response.getSearchTrace().getDegradation();
    assertTrue(degradation.getHybridFallback(), "the dense leg fell back");
    assertEquals(reason, degradation.getHybridFallbackReason());
    assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
    assertTrue(response.getTotalHits() >= 1, "keyword results remain available");
  }

  static SearchRequest query(SearchMode mode) {
    return SearchRequest.newBuilder().setQuery("Hello").setLimit(10).setMode(mode).build();
  }

  static SearchRequest query(PipelineConfig.Builder pipeline) {
    return SearchRequest.newBuilder()
        .setQuery("Hello")
        .setLimit(10)
        .setMode(SearchMode.SEARCH_MODE_HYBRID)
        .setPipeline(pipeline.build())
        .build();
  }

  static PipelineConfig.Builder pipeline() {
    return PipelineConfig.newBuilder();
  }

  /** A foreground (interactive) call whose request deadline is {@code budget} from now. */
  static CallContext foreground(Duration budget) {
    CallContext none = CallContext.none();
    EngineContext interactive =
        new EngineContext(
            EngineContext.ClientKind.INTERNAL,
            "semantic-budget-test",
            Optional.empty(),
            Optional.empty(),
            "TRUSTED",
            "SYSTEM_INTERNAL",
            EngineContext.Survival.DURABLE,
            EngineContext.Urgency.FOREGROUND);
    return new CallContext(
        null,
        null,
        CallContext.CancelSignal.NEVER,
        interactive,
        none.provenance(),
        none.childLifetime(),
        System.nanoTime() + budget.toNanos());
  }

  /**
   * A real {@code NativeSessionHandle} built through the production assembler, whose GPU session
   * is a mock: acquisition, the device permit and the deadline checks are the production ones.
   */
  static SessionHandle gpuHandle(String name) throws Exception {
    return gpuHandle(name, mock(OrtSession.class));
  }

  static SessionHandle gpuHandle(String name, OrtSession gpuSession) throws Exception {
    return gpuHandle(name, 0, gpuSession);
  }

  /**
   * A handle on the given CUDA device. Handles on one device share its seat, so a test that holds
   * one leg's device while another leg's stays free puts the two legs on different devices.
   */
  static SessionHandle gpuHandle(String name, int cudaDevice) throws Exception {
    return gpuHandle(name, cudaDevice, mock(OrtSession.class));
  }

  static SessionHandle gpuHandle(String name, int cudaDevice, OrtSession gpuSession)
      throws Exception {
    Path model = Path.of("missing", "model.onnx");
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(cudaDevice, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            60_000L);
    SessionHandle handle =
        OrtSessionAssembler.buildManager(
            name, new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)),
            () -> true);
    set(handle, "gpuSession", gpuSession);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    return handle;
  }

  /** Holds the handle's device as a bulk-indexing run would. */
  static SessionHandle.Lease holdBackground(SessionHandle handle) {
    SessionHandle.Lease lease =
        handle.acquire(
            SessionAcquisitionRequest.within(
                SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofMinutes(1)));
    assertFalse(lease.isCpu(), "the fixture must hold the GPU permit");
    return lease;
  }

  /**
   * The handle's one GPU seat is free again, and there is exactly one: a foreground caller gets it,
   * and a second caller cannot while the first holds it.
   */
  static void assertSeatCountIsFull(SessionHandle handle) {
    try (SessionHandle.Lease seat =
        assertDoesNotThrow(
            () ->
                handle.acquire(
                    SessionAcquisitionRequest.within(
                        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(5))),
            "the seat did not come back")) {
      assertFalse(seat.isCpu());
      assertThrows(
          SessionAcquireDeadlineExceededException.class,
          () ->
              handle.acquire(
                  SessionAcquisitionRequest.within(
                      SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofMillis(100))),
          "a second GPU seat exists: the lease was released twice");
    }
  }

  static SpladeEncoder handleBackedSplade(SessionHandle handle) throws Exception {
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(splade.encode(anyString(), any(InferenceRequest.class)))
        .thenAnswer(
            invocation -> {
              InferenceRequest request = invocation.getArgument(1);
              try (SessionHandle.Lease lease =
                  handle.acquire(SessionAcquisitionRequest.from(request))) {
                return Map.of("hello", 1.0f);
              }
            });
    return splade;
  }

  /**
   * A query embedder whose encode acquires its handle exactly as the ONNX embedder does and, when
   * {@code runsNative}, runs the lease's session as the ONNX embedder does.
   */
  static final class HandleBackedEmbeddings implements EmbeddingProvider {
    private final SessionHandle handle;
    private final boolean runsNative;
    final AtomicInteger calls = new AtomicInteger();

    HandleBackedEmbeddings(SessionHandle handle) {
      this(handle, false);
    }

    HandleBackedEmbeddings(SessionHandle handle, boolean runsNative) {
      this.handle = handle;
      this.runsNative = runsNative;
    }

    @Override
    public float[] embedQuery(String text, InferenceRequest request) {
      calls.incrementAndGet();
      try (SessionHandle.Lease lease = handle.acquire(SessionAcquisitionRequest.from(request))) {
        if (runsNative) {
          try (OrtSession.Result ignored = lease.run(Map.of())) {
            // The vector is fixed; only the run's timing matters here.
          }
        }
        return new float[] {1f, 0f, 0f, 0f};
      } catch (ai.onnxruntime.OrtException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public float[] embedQuery(String text) {
      throw new AssertionError("a query embed must carry its request");
    }

    @Override
    public float[] embedDocument(String text) {
      throw new AssertionError("no document embeds in a search");
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      throw new AssertionError("no document embeds in a search");
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
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private RunningRuntime newLifecycleWithOneDoc(String content) throws Exception {
    Path base = Files.createTempDirectory("justsearch-semantic-budget-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: semanticbudget\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
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
                SchemaFields.CONTENT, content)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return runtime;
  }
}
