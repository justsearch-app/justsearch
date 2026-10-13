/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.QueryDevice;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FP3: real worker/capture/planner/retrieval; only the native encoder is replaced.
 * A nonblank SPLADE-only request must retain keyword results and the existing skip producer.
 * No subprocesses are started; cleanup closes the runtime and the owned executor bundle.
 */
final class WorkerSearchServiceSpladeOnlyFallbackTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path temp;
  private RunningRuntime runtime;
  private String previousConfig;
  private final ObservedLatch loading = new ObservedLatch();
  private final SpladeEncoder encoder = mock(SpladeEncoder.class);

  @BeforeEach
  void openIndex() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    Path config = temp.resolve("config.yaml");
    Files.writeString(config, "app:\n  data_dir: '" + temp.toString().replace('\\', '/')
        + "'\nindex:\n  collections:\n    - name: spladefallback\n      roots: ['ignored']\n"
        + "vector:\n  dimension: 4\n", StandardCharsets.UTF_8);
    System.setProperty("justsearch.config", config.toString());
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "doc-1", SchemaFields.DOC_UID, "doc-1#0",
        SchemaFields.PATH, "doc-1", SchemaFields.CONTENT, "Hello world")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    when(encoder.queryDevice()).thenReturn(QueryDevice.CPU);
    when(encoder.encode(anyString(), any(InferenceRequest.class))).thenReturn(Map.of("hello", 1f));
  }

  @AfterEach
  void closeIndex() throws Exception {
    loading.countDown();
    if (runtime != null) runtime.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
  }

  @Test
  void queuedSpladeOnlyWithBoundEncoderReturnsKeywordsAndContendedReason() throws Exception {
    var service = service(true, false);
    assertFallback(service.search(request(false), expiredSemanticCall()),
        "SEMANTIC_BUDGET_EXCEEDED", "contended");
    verify(encoder, never()).encode(anyString(), any(InferenceRequest.class));
  }

  @Test
  void queuedSpladeOnlyWithStartingEncoderReturnsKeywordsWithoutWaiting() {
    var service = service(false, true);
    assertFallback(service.search(request(false), expiredSemanticCall()),
        "SEMANTIC_BUDGET_EXCEEDED", "contended");
    assertEquals(0, loading.waits);
  }

  @Test
  void queuedDeprecatedSpladeModeReturnsKeywordsAndContendedReason() {
    var service = service(false, true);
    var request = SearchRequest.newBuilder().setQuery("Hello").setLimit(10)
        .setMode(SearchMode.SEARCH_MODE_SPLADE).build();
    assertFallback(service.search(request, expiredSemanticCall()),
        "SEMANTIC_BUDGET_EXCEEDED", "contended");
    assertEquals(0, loading.waits);
  }

  @Test
  void timelySpladeOnlyLoadingReturnsKeywordsAndLoadingReason() {
    assertFallback(service(false, true).search(request(false), foreground(Duration.ofSeconds(2))),
        "SEMANTIC_BUDGET_EXCEEDED", "loading");
    assertTrue(loading.waits > 0, "a timely query waits for loading models in cancellation-aware slices");
  }

  @Test
  void absentSpladeOnlyReturnsKeywordsWithItsExistingNoServiceReason() {
    assertFallback(service(false, false).search(request(false), foreground(Duration.ofSeconds(10))),
        "NO_EMBEDDING_SERVICE", "");
  }

  @Test
  void queuedPermanentlyAbsentSpladeRetainsNoServiceReasonWithoutFalseBudgetCause() {
    assertFallback(service(false, false).search(request(false), expiredSemanticCall()),
        "NO_EMBEDDING_SERVICE", "");
  }

  @Test
  void unavailableSpladeEncoderReturnsKeywordsWithItsExistingGenerationFailure() throws Exception {
    when(encoder.encode(anyString(), any(InferenceRequest.class)))
        .thenThrow(new IllegalStateException("encoder unavailable"));
    assertFallback(service(true, false).search(request(false), foreground(Duration.ofSeconds(10))),
        "EMBEDDING_GENERATION_FAILED", "");
    verify(encoder).encode(anyString(), any(InferenceRequest.class));
  }

  @Test
  void sparsePlusSpladePreservesQueuedFallback() throws Exception {
    assertFallback(service(true, false).search(request(true), expiredSemanticCall()),
        "SEMANTIC_BUDGET_EXCEEDED", "contended");
    verify(encoder, never()).encode(anyString(), any(InferenceRequest.class));
  }

  @Test
  void timelySpladeOnlyStillRunsOnlySplade() throws Exception {
    var response = service(true, false).search(request(false), foreground(Duration.ofSeconds(10)));
    assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
    assertTrue(response.getSearchTrace().getDegradation().getSpladeExecuted());
    assertEquals("", response.getSearchTrace().getDegradation().getSpladeSkipReason());
    assertTrue(response.getSearchTrace().getStagesList().stream().anyMatch(
        s -> s.getId().equals("sparse-retrieval") && s.getStatus().equals("skipped")));
    verify(encoder).encode(anyString(), any(InferenceRequest.class));
  }

  @Test
  void successfulEmptySpladeWeightsDoNotBecomeAKeywordFallback() throws Exception {
    when(encoder.encode(anyString(), any(InferenceRequest.class))).thenReturn(Map.of());
    var response = service(true, false).search(request(false), foreground(Duration.ofSeconds(10)));
    assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
    assertEquals("", response.getSearchTrace().getDegradation().getSpladeSkipReason());
    assertEquals(0, response.getTotalHits());
  }

  @Test
  void blankQueryStillHasTheEmptyQueryDecision() {
    var response = service(false, false).search(request(false).toBuilder().setQuery("").build(),
        foreground(Duration.ofSeconds(10)));
    assertEquals("empty_query", response.getSearchTrace().getDecisionKind());
    assertEquals(0, response.getTotalHits());
  }

  private WorkerSearchService service(boolean bound, boolean starting) {
    var bindings = new EncoderBindings();
    if (bound) bindings.bindSpladeEncoder(encoder);
    var service = new WorkerSearchService(runtime,
        starting ? NoOpEmbeddingProvider.starting() : NoOpEmbeddingProvider.INSTANCE, bindings);
    var compat = new EmbeddingCompatibilityController(
        () -> Map.of(EmbeddingCompatibilityController.COMMIT_META_KEY, "my-model"),
        () -> 1L, () -> 1, () -> Optional.of("my-model"));
    compat.refresh();
    service.setEmbeddingCompatController(compat);
    if (starting) service.setModelReadyLatchSupplier(() -> loading);
    return service;
  }

  private static SearchRequest request(boolean sparse) {
    return SearchRequest.newBuilder().setQuery("Hello").setLimit(10)
        .setPipeline(PipelineConfig.newBuilder().setSpladeEnabled(true).setSparseEnabled(sparse))
        .build();
  }

  private static CallContext expiredSemanticCall() {
    // Record the queue's elapsed time on the same absolute clock as the Engine call.
    // The 8 s CPU cap has passed; the request still has 2 s for keyword retrieval.
    var call = foreground(Duration.ofSeconds(2));
    return new CallContext(call.traceId(), call.requestId(), call.cancel(), call.engineContext(),
        call.provenance(), call.childLifetime(), call.deadlineNanos(),
        System.nanoTime() - Duration.ofSeconds(9).toNanos());
  }

  private static void assertFallback(SearchResponse response, String reason, String cause) {
    var trace = response.getSearchTrace();
    assertEquals(reason, trace.getDegradation().getSpladeSkipReason(), "skip reason was lost");
    assertEquals(cause, trace.getDegradation().getSpladeSkipCause());
    assertFalse(trace.getDegradation().getSpladeExecuted());
    assertFalse(trace.getDegradation().getHybridFallback());
    assertEquals("TEXT", trace.getEffectiveMode());
    assertEquals("multi_leg", trace.getDecisionKind());
    assertTrue(response.getTotalHits() >= 1, "keyword results were lost");
    assertTrue(trace.getStagesList().stream().anyMatch(s -> s.getId().equals("splade-retrieval")
        && s.getStatus().equals("skipped") && s.getReason().equals(reason)));
  }

  private static final class ObservedLatch extends CountDownLatch {
    int waits;
    ObservedLatch() { super(1); }
    @Override public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      waits++;
      return super.await(timeout, unit);
    }
  }
}
