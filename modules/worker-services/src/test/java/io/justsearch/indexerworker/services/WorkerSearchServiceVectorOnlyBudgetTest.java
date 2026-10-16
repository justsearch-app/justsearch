/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.holdBackground;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.HandleBackedEmbeddings;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D (item 5a) tests V1a and V1b: a VECTOR-only search has no keyword fallback, so it waits for
 * its query embedding within {@code D - marginMs}. A miss is a typed DEADLINE_EXCEEDED whose detail
 * names {@code SEMANTIC_BUDGET_EXCEEDED} and the cause (S2, §5.3); never keyword results and never
 * a silent empty result. A wait that ends inside that budget still returns the vector results.
 *
 * <p>Real seam: {@link WorkerSearchService#search}, the production entry point, with the production
 * index and planner. The query embedder acquires a real {@code NativeSessionHandle} exactly as the
 * ONNX embedder does; a BACKGROUND lease on it is the bulk-indexing contention.
 *
 * <p>Uses only API that exists before the change, so each check runs on the before-state.
 */
final class WorkerSearchServiceVectorOnlyBudgetTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** The request deadline for V1a: the held device does not come back inside it. */
  private static final Duration REQUEST_BUDGET = Duration.ofSeconds(3);

  /** V1b: the device is held this long, which is well inside {@link #WAITING_BUDGET} minus margin. */
  private static final Duration HELD_FOR = Duration.ofSeconds(3);

  /** V1b's request deadline: long enough that {@link #HELD_FOR} ends inside {@code D - margin}. */
  private static final Duration WAITING_BUDGET = Duration.ofSeconds(8);

  private String previousConfig;
  private RunningRuntime lifecycle;

  @BeforeEach
  void openIndex() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    lifecycle = newLifecycleWithOneVectorDoc();
  }

  @AfterEach
  void closeIndex() throws Exception {
    if (lifecycle != null) lifecycle.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
  }

  @Test
  // V1a: VECTOR-only, blocked past D - margin, ends in a typed DEADLINE_EXCEEDED with the reason
  void vectorOnlyBlockedPastItsBudgetIsADeadlineWithTheReason() throws Exception {
    try (SessionHandle embeddingHandle = gpuHandle("v1a-embedding");
        SessionHandle.Lease _ = holdBackground(embeddingHandle)) {
      var embeddings = new HandleBackedEmbeddings(embeddingHandle);
      var service = new WorkerSearchService(lifecycle, embeddings);
      SearchRequest request = vectorOnly();

      CallContext call = foreground(REQUEST_BUDGET);
      var failure =
          assertTimeoutPreemptively(
              REQUEST_BUDGET,
              () -> assertThrows(WorkerServiceException.class, () -> service.search(request, call)));

      assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
      String detail = String.valueOf(failure.getMessage());
      assertTrue(detail.contains("SEMANTIC_BUDGET_EXCEEDED"), () -> "detail: " + detail);
      assertTrue(detail.contains("contended"), () -> "the cause is in the detail: " + detail);
      assertEquals(1, embeddings.calls.get(), "the dense leg tried its encoder");
    }
  }

  @Test
  // V1b: VECTOR-only, blocked for 3 s inside D - margin, waits and returns the vector results
  void vectorOnlyBlockedInsideItsBudgetWaitsAndReturnsVectors() throws Exception {
    try (SessionHandle embeddingHandle = gpuHandle("v1b-embedding")) {
      CountDownLatch held = new CountDownLatch(1);
      Thread bulk =
          Thread.ofPlatform()
              .daemon(true)
              .name("v1b-bulk-holder")
              .start(
                  () -> {
                    try (SessionHandle.Lease _ = holdBackground(embeddingHandle)) {
                      held.countDown();
                      Thread.sleep(HELD_FOR.toMillis());
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  });
      assertTrue(held.await(10, TimeUnit.SECONDS), "the bulk holder took the device");
      var embeddings = new HandleBackedEmbeddings(embeddingHandle);
      var service = new WorkerSearchService(lifecycle, embeddings);
      SearchRequest request = vectorOnly();

      long start = System.nanoTime();
      CallContext call = foreground(WAITING_BUDGET);
      SearchResponse response =
          assertTimeoutPreemptively(WAITING_BUDGET, () -> service.search(request, call));
      long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      bulk.join(TimeUnit.SECONDS.toMillis(10));

      assertTrue(waitedMs >= HELD_FOR.toMillis() - 250, () -> "it waited: " + waitedMs + " ms");
      assertEquals(1, embeddings.calls.get(), "the dense leg ran once");
      assertEquals("VECTOR", response.getSearchTrace().getEffectiveMode());
      var degradation = response.getSearchTrace().getDegradation();
      assertFalse(degradation.getHybridFallback(), "VECTOR-only never falls back to keywords");
      assertTrue(response.getTotalHits() >= 1, "the vector results came back");
    }
  }

  private static SearchRequest vectorOnly() {
    return SearchRequest.newBuilder()
        .setQuery("Hello")
        .setLimit(10)
        .setMode(SearchMode.SEARCH_MODE_VECTOR)
        .build();
  }

  private RunningRuntime newLifecycleWithOneVectorDoc() throws Exception {
    Path base = Files.createTempDirectory("justsearch-vector-only-budget-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: vectoronlybudget\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .ephemeral()
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, "doc-1");
    fields.put(SchemaFields.DOC_UID, "doc-1#0");
    fields.put(SchemaFields.PATH, "doc-1");
    fields.put(SchemaFields.CONTENT, "Hello world");
    fields.put(SchemaFields.VECTOR, new float[] {1f, 0f, 0f, 0f});
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return runtime;
  }
}
