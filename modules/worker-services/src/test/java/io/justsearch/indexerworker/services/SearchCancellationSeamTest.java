/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lane F review B3 — a search stops when its caller has gone.
 *
 * <p>On the wire this was the transport's job: a deadline or a disconnect cancelled the server call
 * and the handler's next write failed. Collapsing the two processes deleted that authority without
 * replacing it, so between items A6 and this fix an abandoned search ran to completion — holding a
 * call thread, an {@code IndexSearcher} and, on a multi-leg query, a virtual-thread fan-out, long
 * after the client's budget had released the caller. The client-side half of B3 (the caller returns
 * at its deadline) landed first; this is the worker-side half, without which the deadline releases
 * the caller and leaves the work running.
 *
 * <p><b>Why the assertion is a sequence and not a boolean.</b> "Cancellation works" is satisfied by
 * a single poll anywhere, and a single poll at the top of the pipeline is worth nothing — the whole
 * cost is downstream of it. What has to be true is that the poll happens at every seam between
 * phases, so the test drives a real search over a real index and, for each n, flips the signal
 * after n polls and records where the search stopped. The resulting list IS the seam inventory: if
 * a seam is deleted the list shortens and a stage name shifts, which no per-stage test would show.
 */
@DisplayName("search cancellation seams (lane F review B3)")
final class SearchCancellationSeamTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /**
   * The seams a sparse search passes through, in order. Adding a phase to the pipeline should add
   * a name here; deleting a poll will remove one and fail this test rather than silently making
   * abandoned searches expensive again.
   */
  private static final List<String> EXPECTED_SEAMS =
      List.of("capture", "plan", "dispatch", "retrieval", "execute");

  /** A signal that reports "not cancelled" for the first {@code flipAfter} polls, then "cancelled". */
  private static final class FlipAfter implements CallContext.CancelSignal {
    private final int flipAfter;
    private final AtomicInteger polls = new AtomicInteger();

    FlipAfter(int flipAfter) {
      this.flipAfter = flipAfter;
    }

    @Override
    public boolean isCancelled() {
      return polls.getAndIncrement() >= flipAfter;
    }

    int polls() {
      return polls.get();
    }
  }

  @Test
  @DisplayName("every phase boundary polls, in order, and stops the search where it is told to")
  void everyPhaseBoundaryIsASeam() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Lorem ipsum dolor")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchRequest request =
          SearchRequest.newBuilder()
              .setQuery("Lorem")
              .setLimit(10)
              .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
              .build();

      List<String> observed = new ArrayList<>();
      for (int flipAfter = 0; flipAfter < EXPECTED_SEAMS.size(); flipAfter++) {
        FlipAfter signal = new FlipAfter(flipAfter);
        WorkerServiceException thrown =
            assertThrows(
                WorkerServiceException.class,
                () -> service.search(request, new CallContext(null, null, signal, CallContext.none().engineContext(), CallContext.none().provenance())),
                "a search cancelled after " + signal.flipAfter + " polls must not return a result");
        assertEquals(
            WorkerServiceException.Status.CANCELLED,
            thrown.status(),
            "a cancellation must keep its own status — relabelling it INTERNAL turns an abandoned"
                + " search into a 500 and a failure-metric increment");
        observed.add(stageOf(thrown));
      }

      assertEquals(
          EXPECTED_SEAMS,
          observed,
          "each poll must be a distinct phase boundary, in pipeline order; a repeated or missing"
              + " name means a seam was deleted or added without updating this inventory");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("a caller that never cancels is polled at every seam and gets its answer")
  void theUncancelledPathIsUnchangedAndStillPolled() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-2", "Lorem ipsum dolor")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      // Never flips, but counts: this is what proves the seams are on the LIVE path and not on
      // some branch a cancelled search happens to take.
      FlipAfter signal = new FlipAfter(Integer.MAX_VALUE);

      var response =
          service.search(
              SearchRequest.newBuilder()
                  .setQuery("Lorem")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build(),
              new CallContext(null, null, signal, CallContext.none().engineContext(), CallContext.none().provenance()));

      assertNotNull(response);
      assertEquals(1, response.getTotalHits(), "the search still answers normally");
      assertTrue(
          signal.polls() >= EXPECTED_SEAMS.size(),
          "a successful search must cross every seam too — got " + signal.polls() + " polls");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("a context with no signal at all is not a cancelled context")
  void noneIsNeverCancelled() throws Exception {
    // CallContext.none() is what the boot warm-up and the tests pass. If the seam treated an
    // absent signal as cancelled, the Engine would fail its own warm-up at capture.
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-3", "Lorem ipsum dolor")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      var response =
          service.search(
              SearchRequest.newBuilder()
                  .setQuery("Lorem")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build(),
              CallContext.none());
      assertEquals(1, response.getTotalHits());
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  private static String stageOf(WorkerServiceException e) {
    String message = e.getMessage();
    int marker = message.lastIndexOf(": ");
    return marker < 0 ? message : message.substring(marker + 2);
  }

  private RunningRuntime newLifecycleWithOneDoc(String docId, String content)
      throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    Path base = Files.createTempDirectory("justsearch-cancel-seam-test-");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: composable\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, docId,
                    SchemaFields.DOC_UID, docId + "#0",
                    SchemaFields.PATH, docId,
                    SchemaFields.CONTENT, content)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
