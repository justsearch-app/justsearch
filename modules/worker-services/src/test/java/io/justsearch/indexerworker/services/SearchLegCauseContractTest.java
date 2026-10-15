/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.REQUEST_BUDGET;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.handleBackedSplade;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.holdBackground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.pipeline;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.query;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.services.SearchReasonCode.LegCause;
import io.justsearch.indexerworker.services.input.BgeM3Encoding;
import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.TraceDegradation;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D test C2 (step 2b, ADR-0050 decision 1): the per-leg cause of a semantic budget miss. The
 * cause vocabulary is pinned in both directions; a cause implies {@code SEMANTIC_BUDGET_EXCEEDED}
 * and that reason always has a cause; and the cause reaches the trace for the leg that missed,
 * through the real search seam.
 */
final class SearchLegCauseContractTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** The wire causes (docs/reference/contracts/search-and-rag-reason-codes.md, "Leg causes"). */
  private static final Set<String> LEG_CAUSES = Set.of("contended", "loading", "stuck");

  private static final String SBE = "SEMANTIC_BUDGET_EXCEEDED";

  private String previousConfig;
  private RunningRuntime lifecycle;
  private final CountDownLatch releaseOnExit = new CountDownLatch(1);

  @BeforeEach
  void openIndex() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    Path base = Files.createTempDirectory("justsearch-leg-cause-test-");
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(
        cfg,
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: legcause\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n");
    System.setProperty("justsearch.config", cfg.toString());
    lifecycle =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .ephemeral()
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.PATH, "doc-1",
                SchemaFields.CONTENT, "Hello world")));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  @AfterEach
  void closeIndex() throws Exception {
    releaseOnExit.countDown();
    if (lifecycle != null) lifecycle.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
  }

  @Test
  void legCausesMatchTheEnumInBothDirections() {
    Set<String> wire =
        Arrays.stream(LegCause.values())
            .filter(c -> c != LegCause.NONE)
            .map(LegCause::wire)
            .collect(Collectors.toSet());
    assertEquals(LEG_CAUSES, wire);
    assertEquals("", LegCause.NONE.wire(), "NONE is never written");
  }

  @Test
  void aCauseImpliesTheBudgetReasonAndTheBudgetReasonHasACause() {
    for (LegCause cause : LegCause.values()) {
      for (SearchReasonCode reason : SearchReasonCode.values()) {
        boolean consistent = (cause != LegCause.NONE) == (reason == SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED);
        if (consistent) {
          assertDoesNotThrow(() -> new VectorEncoding.Failed(reason, cause));
          assertDoesNotThrow(() -> new SpladeEncoding.Failed(reason, cause));
          assertDoesNotThrow(() -> new BgeM3Encoding.Failed(reason, cause));
        } else {
          assertThrows(IllegalArgumentException.class, () -> new VectorEncoding.Failed(reason, cause));
          assertThrows(IllegalArgumentException.class, () -> new SpladeEncoding.Failed(reason, cause));
          assertThrows(IllegalArgumentException.class, () -> new BgeM3Encoding.Failed(reason, cause));
        }
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new VectorEncoding.Failed(SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED));
  }

  @Test
  void modelsStillLoadingIsTheLoadingCause() {
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.starting());
    service.setModelReadyLatchSupplier(() -> releaseOnExit);

    SearchResponse response =
        service.search(
            query(pipeline().setSparseEnabled(true).setDenseEnabled(true)),
            foreground(REQUEST_BUDGET));

    TraceDegradation d = response.getSearchTrace().getDegradation();
    assertEquals(SBE, d.getHybridFallbackReason());
    assertEquals("loading", d.getHybridFallbackCause());
    assertCausesOnlyWithTheBudgetReason(d);
  }

  @Test
  void aHeldDeviceIsTheContendedCauseOnTheLegThatWaited() throws Exception {
    try (SessionHandle embeddingHandle = gpuHandle("c2-embedding");
        SessionHandle.Lease _ = holdBackground(embeddingHandle)) {
      var service =
          new WorkerSearchService(
              lifecycle, new WorkerSearchServiceSemanticBudgetTest.HandleBackedEmbeddings(embeddingHandle));

      TraceDegradation d =
          service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET))
              .getSearchTrace()
              .getDegradation();

      assertEquals(SBE, d.getHybridFallbackReason());
      assertEquals("contended", d.getHybridFallbackCause());
      assertCausesOnlyWithTheBudgetReason(d);
    }
  }

  @Test
  void aSeatedRunThatDoesNotReturnIsTheStuckCause() throws Exception {
    CountDownLatch runMayReturn = new CountDownLatch(1);
    OrtSession stalled = mock(OrtSession.class);
    when(stalled.run(anyMap()))
        .thenAnswer(
            invocation -> {
              runMayReturn.await(60, TimeUnit.SECONDS);
              return mock(OrtSession.Result.class);
            });
    try (SessionHandle embeddingHandle = gpuHandle("c2-stuck", stalled)) {
      var service =
          new WorkerSearchService(
              lifecycle,
              new WorkerSearchServiceSemanticBudgetTest.HandleBackedEmbeddings(
                  embeddingHandle, /* runsNative= */ true));

      TraceDegradation d;
      try {
        d =
            service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET))
                .getSearchTrace()
                .getDegradation();
      } finally {
        runMayReturn.countDown();
      }

      assertEquals(SBE, d.getHybridFallbackReason());
      assertEquals("stuck", d.getHybridFallbackCause());
      assertCausesOnlyWithTheBudgetReason(d);
    }
  }

  @Test
  void aHeldSpladeDeviceCarriesTheCauseOnTheSpladeLegOnly() throws Exception {
    try (SessionHandle spladeHandle = gpuHandle("c2-splade", 1);
        SessionHandle.Lease _ = holdBackground(spladeHandle);
        SessionHandle denseHandle = gpuHandle("c2-dense")) {
      var bindings = new EncoderBindings();
      bindings.bindSpladeEncoder(handleBackedSplade(spladeHandle));
      var service =
          new WorkerSearchService(
              lifecycle,
              new WorkerSearchServiceSemanticBudgetTest.HandleBackedEmbeddings(denseHandle),
              bindings);

      TraceDegradation d =
          service.search(
                  query(pipeline().setSparseEnabled(true).setDenseEnabled(true).setSpladeEnabled(true)),
                  foreground(REQUEST_BUDGET))
              .getSearchTrace()
              .getDegradation();

      assertEquals(SBE, d.getSpladeSkipReason());
      assertEquals("contended", d.getSpladeSkipCause());
      assertEquals("", d.getHybridFallbackCause(), "the dense leg did not miss");
      assertCausesOnlyWithTheBudgetReason(d);
    }
  }

  private static void assertCausesOnlyWithTheBudgetReason(TraceDegradation d) {
    for (String[] leg :
        new String[][] {
          {d.getHybridFallbackReason(), d.getHybridFallbackCause()},
          {d.getSpladeSkipReason(), d.getSpladeSkipCause()}
        }) {
      String reason = leg[0];
      String cause = leg[1];
      if (cause.isEmpty()) {
        assertTrue(!SBE.equals(reason), "SEMANTIC_BUDGET_EXCEEDED without a cause");
      } else {
        assertEquals(SBE, reason, "a cause without SEMANTIC_BUDGET_EXCEEDED");
        assertTrue(LEG_CAUSES.contains(cause), () -> "unknown cause " + cause);
      }
    }
  }
}
