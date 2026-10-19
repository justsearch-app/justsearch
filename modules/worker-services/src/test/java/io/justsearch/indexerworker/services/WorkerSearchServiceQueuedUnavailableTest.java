/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.pipeline;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.QueryDevice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** FP3/V2/V3: transient provider readiness must not erase a budget spent in the queue.
 * Real worker/Lucene seam and CPU cap, matching the independent verifier's queued-start fixture.
 * Only encoder implementations are replaced; no GPU session or native inference is used.
 */
final class WorkerSearchServiceQueuedUnavailableTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final Duration REQUEST_BUDGET = Duration.ofSeconds(20);
  private static final Duration QUEUED = Duration.ofMillis(8500);
  private static final Duration VECTOR_BUDGET = Duration.ofMillis(2000);
  private static final Duration VECTOR_QUEUED = Duration.ofMillis(1600);
  private final Map<String, String> previousProperties = new HashMap<>();
  private RunningRuntime lifecycle;
  private final ObservedLatch loading = new ObservedLatch(1);
  private final AtomicInteger encodes = new AtomicInteger();

  @AfterEach
  void closeIndex() throws Exception {
    if (lifecycle != null) lifecycle.close();
    WorkerSearchServiceRagBudgetTest.restoreProperties(previousProperties);
  }

  @Test
  void queuedHybridWithUnavailableProviderKeepsBudgetOutcome() throws Exception {
    checkQueuedSearch(false);
  }

  @Test
  void queuedAutoWithUnavailableProviderKeepsBudgetOutcome() throws Exception {
    checkQueuedSearch(true);
  }

  private void checkQueuedSearch(boolean auto) throws Exception {
    lifecycle = openSearchIndex();
    var provider = transientProvider();
    var service = service(provider);
    var request = query(auto ? pipeline().setSparseEnabled(true).setDenseAuto(true)
        : pipeline().setSparseEnabled(true).setDenseEnabled(true));
    CallContext call = queuedCall();
    assertFalse(provider.isAvailable());
    assertKeywordBudget(service.search(request, call), "contended");
    assertUnused();
    loading.countDown();
    assertTrue(provider.isAvailable(), "provider is absent only while loading");
  }

  @Test
  void queuedRagWithUnavailableProviderKeepsBudgetOutcome() throws Exception {
    lifecycle = WorkerSearchServiceRagBudgetTest.openIndex(
        previousProperties, testLuceneExecutors(), "position", "Hello queued retrieval");
    var service = service(transientProvider());
    var response = service.retrieveContext(
        WorkerSearchServiceRagBudgetTest.question("Hello"), queuedCall());
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("contended", response.getRetrievalModeCause());
    assertEquals("BM25", response.getRetrievalMode());
    assertTrue(response.getChunksCount() >= 1);
    assertUnused();
  }

  @Test
  void queuedSpladeWithUnavailableEmbeddingProviderKeepsBudgetOutcome() throws Exception {
    lifecycle = openSearchIndex();
    var splade = mock(SpladeEncoder.class);
    when(splade.queryDevice()).thenReturn(QueryDevice.CPU);
    var bindings = new EncoderBindings();
    bindings.bindSpladeEncoder(splade);
    var service = new WorkerSearchService(lifecycle, transientProvider(), bindings);
    service.setModelReadyLatchSupplier(loading::forWait);
    var response = service.search(
        query(pipeline().setSparseEnabled(true).setSpladeEnabled(true)), queuedCall());
    var degradation = response.getSearchTrace().getDegradation();
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getSpladeSkipReason());
    assertEquals("contended", degradation.getSpladeSkipCause());
    assertFalse(degradation.getSpladeExecuted());
    assertTrue(response.getTotalHits() >= 1);
    verify(splade, never()).encode(anyString(), any(io.justsearch.core.execution.InferenceRequest.class));
    assertUnused();
  }

  @Test
  void timelyHybridWithUnavailableProviderKeepsLoadingCause() throws Exception {
    checkTimelySearch(false);
  }

  @Test
  void timelyAutoWithUnavailableProviderKeepsLoadingCause() throws Exception {
    checkTimelySearch(true);
  }

  private void checkTimelySearch(boolean auto) throws Exception {
    lifecycle = openSearchIndex();
    var provider = transientProvider();
    var service = service(provider);
    assertKeywordBudget(service.search(query(auto
        ? pipeline().setSparseEnabled(true).setDenseAuto(true)
        : pipeline().setSparseEnabled(true).setDenseEnabled(true)),
        foreground(REQUEST_BUDGET)), "loading");
    assertEquals(1, loading.logicalWaits.get(), "one logical models-ready wait, regardless of cancellation slices");
    assertEquals(0, encodes.get());
    loading.countDown();
    assertTrue(provider.isAvailable());
  }

  @Test
  void queuedReadyProviderKeepsContention() throws Exception {
    lifecycle = openSearchIndex();
    loading.countDown();
    var service = service(transientProvider());
    var call = queuedCall();
    assertKeywordBudget(service.search(
        query(pipeline().setSparseEnabled(true).setDenseEnabled(true)), call), "contended");
    assertKeywordBudget(service.search(
        query(pipeline().setSparseEnabled(true).setDenseAuto(true)), call), "contended");
    assertUnused();
  }

  @Test
  void queuedUnavailableVectorKeepsTypedDeadline() throws Exception {
    lifecycle = openSearchIndex();
    var service = service(transientProvider());
    var call = queuedVectorCall();
    var failure = assertThrows(WorkerServiceException.class,
        () -> service.search(query(pipeline().setDenseEnabled(true)), call));
    assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
    assertTrue(failure.getMessage().contains("SEMANTIC_BUDGET_EXCEEDED"));
    assertTrue(failure.getMessage().contains("contended"));
    assertUnused();
  }

  @Test
  void permanentlyAbsentProviderKeepsExistingOutcomes() throws Exception {
    lifecycle = WorkerSearchServiceRagBudgetTest.openIndex(
        previousProperties, testLuceneExecutors(), "position", "Hello absent provider");
    var service = service(NoOpEmbeddingProvider.INSTANCE);
    var call = queuedCall();
    var hybrid = service.search(query(pipeline().setSparseEnabled(true).setDenseEnabled(true)), call);
    assertEquals("NO_EMBEDDING_SERVICE", hybrid.getSearchTrace().getDegradation().getHybridFallbackReason());
    assertEquals("", hybrid.getSearchTrace().getDegradation().getHybridFallbackCause());
    var auto = service.search(query(pipeline().setSparseEnabled(true).setDenseAuto(true)), call);
    assertFalse(auto.getSearchTrace().getDegradation().getHybridFallback());
    assertEquals("", auto.getSearchTrace().getDegradation().getHybridFallbackReason());
    var rag = service.retrieveContext(WorkerSearchServiceRagBudgetTest.question("Hello"), call);
    assertEquals("NO_EMBEDDING_SERVICE", rag.getRetrievalModeReason());
    assertEquals("", rag.getRetrievalModeCause());
    assertUnused();
  }

  @Test
  void unexpiredUnavailableProviderKeepsExistingRouting() throws Exception {
    lifecycle = openSearchIndex();
    var provider = transientProvider();
    // Models-ready is open but this provider remains unavailable: today's availability routing.
    var service = new WorkerSearchService(lifecycle, provider);
    service.setModelReadyLatchSupplier(() -> new CountDownLatch(0));
    var hybrid = service.search(query(pipeline().setSparseEnabled(true).setDenseEnabled(true)),
        foreground(REQUEST_BUDGET));
    assertEquals("NO_EMBEDDING_SERVICE", hybrid.getSearchTrace().getDegradation().getHybridFallbackReason());
    var auto = service.search(query(pipeline().setSparseEnabled(true).setDenseAuto(true)),
        foreground(REQUEST_BUDGET));
    assertFalse(auto.getSearchTrace().getDegradation().getHybridFallback());
    assertUnused();
  }

  @Test
  void queuedColdPlaceholderHybridKeepsBudgetOutcome() throws Exception {
    checkColdSearch(false);
  }

  @Test
  void queuedColdPlaceholderAutoKeepsBudgetOutcome() throws Exception {
    checkColdSearch(true);
  }

  private void checkColdSearch(boolean auto) throws Exception {
    lifecycle = openSearchIndex();
    var service = coldService(true);
    assertKeywordBudget(service.search(query(auto
        ? pipeline().setSparseEnabled(true).setDenseAuto(true)
        : pipeline().setSparseEnabled(true).setDenseEnabled(true)), queuedCall()), "contended");
    assertUnused();
  }

  @Test
  void queuedColdPlaceholderSpladeKeepsBudgetOutcome() throws Exception {
    lifecycle = openSearchIndex();
    var service = coldService(true);
    var response = service.search(
        query(pipeline().setSparseEnabled(true).setSpladeEnabled(true)), queuedCall());
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getSearchTrace().getDegradation().getSpladeSkipReason());
    assertEquals("contended", response.getSearchTrace().getDegradation().getSpladeSkipCause());
    assertFalse(response.getSearchTrace().getDegradation().getSpladeExecuted());
    assertTrue(response.getTotalHits() >= 1);
    assertUnused();
  }

  @Test
  void queuedColdPlaceholderRagKeepsBudgetOutcome() throws Exception {
    lifecycle = WorkerSearchServiceRagBudgetTest.openIndex(
        previousProperties, testLuceneExecutors(), "position", "Hello cold retrieval");
    var service = coldService(true);
    var response = service.retrieveContext(WorkerSearchServiceRagBudgetTest.question("Hello"), queuedCall());
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("contended", response.getRetrievalModeCause());
    assertEquals("BM25", response.getRetrievalMode());
    assertTrue(response.getChunksCount() >= 1);
    assertUnused();
  }

  @Test
  void queuedColdPlaceholderVectorKeepsTypedDeadline() throws Exception {
    lifecycle = openSearchIndex();
    var service = coldService(true);
    var call = queuedVectorCall();
    var failure = assertThrows(WorkerServiceException.class,
        () -> service.search(query(pipeline().setDenseEnabled(true)), call));
    assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
    assertTrue(failure.getMessage().contains("SEMANTIC_BUDGET_EXCEEDED"));
    assertTrue(failure.getMessage().contains("contended"));
    assertUnused();
  }

  @Test
  void queuedPermanentlyMissingModelKeepsCompatibilityOutcome() throws Exception {
    lifecycle = WorkerSearchServiceRagBudgetTest.openIndex(
        previousProperties, testLuceneExecutors(), "position", "Hello missing model");
    var service = coldService(false);
    var call = queuedCall();
    var hybrid = service.search(query(pipeline().setSparseEnabled(true).setDenseEnabled(true)), call);
    assertEquals("NO_EMBEDDING_MODEL", hybrid.getSearchTrace().getDegradation().getHybridFallbackReason());
    assertEquals("", hybrid.getSearchTrace().getDegradation().getHybridFallbackCause());
    var auto = service.search(query(pipeline().setSparseEnabled(true).setDenseAuto(true)), call);
    assertFalse(auto.getSearchTrace().getDegradation().getHybridFallback());
    var splade = service.search(query(pipeline().setSparseEnabled(true).setSpladeEnabled(true)), call);
    assertEquals("NO_EMBEDDING_SERVICE", splade.getSearchTrace().getDegradation().getSpladeSkipReason());
    assertEquals("", splade.getSearchTrace().getDegradation().getSpladeSkipCause());
    var rag = service.retrieveContext(WorkerSearchServiceRagBudgetTest.question("Hello"), call);
    assertEquals("NO_EMBEDDING_MODEL", rag.getRetrievalModeReason());
    assertEquals("", rag.getRetrievalModeCause());
    assertUnused();
  }

  private WorkerSearchService coldService(boolean installed) {
    var service = service(installed ? NoOpEmbeddingProvider.starting() : NoOpEmbeddingProvider.INSTANCE);
    // Production resolves installed model identity synchronously, before publishing encoders.
    var controller = new EmbeddingCompatibilityController(
        () -> Map.of(EmbeddingCompatibilityController.COMMIT_META_KEY, "installed-model"),
        () -> 1L, () -> 1, () -> installed ? Optional.of("installed-model") : Optional.empty());
    controller.refresh();
    assertEquals(installed, controller.allowQueryEmbeddings());
    service.setEmbeddingCompatController(controller);
    return service;
  }

  private WorkerSearchService service(EmbeddingProvider provider) {
    var service = new WorkerSearchService(lifecycle, provider);
    service.setModelReadyLatchSupplier(loading::forWait);
    return service;
  }

  private EmbeddingProvider transientProvider() {
    var provider = mock(EmbeddingProvider.class);
    when(provider.dimension()).thenReturn(4);
    when(provider.queryDevice()).thenReturn(QueryDevice.CPU);
    when(provider.isAvailable()).thenAnswer(i -> loading.getCount() == 0);
    when(provider.availability()).thenAnswer(i -> loading.getCount() == 0
        ? EmbeddingProvider.Availability.READY : EmbeddingProvider.Availability.STARTING);
    when(provider.embedQuery(anyString(), any(io.justsearch.core.execution.InferenceRequest.class)))
        .thenAnswer(i -> { encodes.incrementAndGet(); return new float[] {1f, 0f, 0f, 0f}; });
    return provider;
  }

  private static CallContext queuedCall() {
    // Queued past the real 8000 ms CPU cap; the whole 20 s deadline still has 11.5 s at service
    // entry, beyond the lexical reserve and margin, so the semantic cap expires, not the request.
    assertTrue(QUEUED.toMillis() > SearchBudgetPolicy.DEFAULT.semanticCapCpuMs());
    assertTrue(REQUEST_BUDGET.minus(QUEUED).toMillis()
        > SearchBudgetPolicy.DEFAULT.lexicalReserveMs() + SearchBudgetPolicy.DEFAULT.marginMs());
    return queuedFor(REQUEST_BUDGET, QUEUED);
  }

  private static CallContext queuedVectorCall() {
    // Queued 1600 ms of a 2000 ms deadline: 400 ms remain at service entry, so the request is
    // still live but its VECTOR-only budget (deadline minus the 500 ms margin) has expired.
    assertTrue(VECTOR_BUDGET.minus(VECTOR_QUEUED).toMillis() > 0);
    assertTrue(VECTOR_BUDGET.minus(VECTOR_QUEUED).toMillis()
        < SearchBudgetPolicy.DEFAULT.marginMs());
    return queuedFor(VECTOR_BUDGET, VECTOR_QUEUED);
  }

  /**
   * A foreground call with request budget {@code budget} whose caller started it {@code queued}
   * before service entry: what the same call would be after waiting {@code queued} for a worker
   * thread, with its start and deadline back-dated on the same clock instead of sleeping.
   */
  static CallContext queuedFor(Duration budget, Duration queued) {
    var call = foreground(budget);
    long start = System.nanoTime() - queued.toNanos();
    return new CallContext(call.traceId(), call.requestId(), call.cancel(), call.engineContext(),
        call.provenance(), call.childLifetime(), start + budget.toNanos(), start);
  }

  private void assertUnused() {
    assertEquals(0, encodes.get(), "no inference after queued expiry");
    assertEquals(0, loading.waitCalls.get(), "no model wait after queued expiry");
  }

  private static void assertKeywordBudget(SearchResponse response, String cause) {
    var degradation = response.getSearchTrace().getDegradation();
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", degradation.getHybridFallbackReason());
    assertEquals(cause, degradation.getHybridFallbackCause());
    assertTrue(degradation.getHybridFallback());
    assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
    assertTrue(response.getTotalHits() >= 1);
  }

  private static final class ObservedLatch extends CountDownLatch {
    final AtomicInteger logicalWaits = new AtomicInteger();

    CountDownLatch forWait() {
      logicalWaits.incrementAndGet();
      return this;
    }

    final AtomicInteger waitCalls = new AtomicInteger();
    ObservedLatch(int count) { super(count); }
    @Override
    public boolean await(long time, TimeUnit unit) throws InterruptedException {
      waitCalls.incrementAndGet();
      return super.await(time, unit);
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
