package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for tempdoc 400 LR2-e virtual-thread context propagation
 * (fix landed in 049eec73d).
 *
 * <p>The 3-way virtual-thread fan-out in {@link SearchOrchestrator}'s search
 * loop must capture OTel context via {@code Context.current().with(retrievalSpan)}.
 * A bare {@code Context.current()} silently misses {@code retrievalSpan} — because
 * {@code retrievalSpan} is created via {@code setParent(parentCtx).startSpan()}
 * and never made current in the outer thread — which causes per-leg branch spans
 * to parent to operation root instead of {@code search/retrieval}. End-to-end
 * verification proved this in tempdoc §23.2 defect 2: 12 {@code search/branch}
 * spans across 4 HYBRID queries carried 12 distinct {@code trace_id} values, zero
 * matching the 4 parent {@code search/retrieval} trace_ids.
 *
 * <p>A full behavioral integration test would need real embedding + SPLADE
 * services to trigger the 3-leg path, so this is a source-level guard: cheap,
 * unambiguous, and catches the exact regression shape. If someone refactors
 * the capture into a helper method, update this test accordingly.
 */
@DisplayName("SearchOrchestrator LR2-e virtual-thread context propagation")
class SearchOrchestratorVirtualThreadContextRegressionTest {

  @Test
  @DisplayName("3-leg fan-out captures Context WITH retrievalSpan attached")
  void contextCaptureMustIncludeRetrievalSpan() throws Exception {
    Path src = resolveSourceFile();
    String content = Files.readString(src);
    assertTrue(
        content.contains("Context.current().with(retrievalSpan)"),
        """
        LR2-e regression: SearchOrchestrator's virtual-thread fan-out must
        capture OTel context as Context.current().with(retrievalSpan), NOT
        bare Context.current(). See tempdoc 400 §23.2 defect 2. Without the
        explicit .with(retrievalSpan), branch spans orphan (parent_span_id=0,
        new trace_id) and LR6-c per-branch aggregation is broken.
        """);
  }

  private static Path resolveSourceFile() {
    // The 3-way fan-out moved from SearchOrchestrator to SearchExecutor in tempdoc 517's
    // implementation slice (capture/plan/execute/respond decomposition). The regression
    // guard now points at the new home; the contract is unchanged.
    String rel = "src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java";
    Path[] candidates = {
      Path.of(rel),
      Path.of("modules/worker-services", rel),
      Path.of("../worker-services", rel),
    };
    for (Path p : candidates) {
      if (Files.isRegularFile(p)) {
        return p;
      }
    }
    fail("Could not locate SearchExecutor.java from cwd=" + System.getProperty("user.dir"));
    throw new AssertionError("unreachable");
  }
}
