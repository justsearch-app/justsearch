package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 598 R1 — regression for {@link WorkerSearchService#resolveAutoDense}, the single point that
 * resolves the capability-derived AUTO marker (proto {@code dense_auto}) into a concrete dense
 * decision before the orchestrator runs, so that both {@code SearchInputCapture} and
 * {@code SearchPlanner} (which independently read {@code pipeline.dense_enabled}) see the resolved
 * value.
 *
 * <p>The contract under test (generalizing the RAG {@code retrieveMode="auto"} rule to the search
 * wire): AUTO runs the dense leg iff query embeddings are currently permitted (index COMPATIBLE),
 * degrades to keyword otherwise, and never alters an explicit (non-AUTO) request.
 */
class WorkerSearchServiceAutoDenseTest {

  private static SearchRequest reqWith(PipelineConfig pipeline) {
    return SearchRequest.newBuilder().setQuery("q").setPipeline(pipeline).build();
  }

  private static PipelineConfig auto() {
    return PipelineConfig.newBuilder()
        .setSparseEnabled(true)
        .setDenseAuto(true)
        .setFusionAlgorithm("rrf")
        .build();
  }

  @Test
  @DisplayName("AUTO + dense serviceable (COMPATIBLE && embedder available) → dense leg enabled")
  void autoServiceableEnablesDense() {
    // Review Fix A: the param is the COMBINED gate (allowQueryEmbeddings && embedder available),
    // composed at the WorkerSearchService.search() call site; this pure function applies it.
    SearchRequest out = WorkerSearchService.resolveAutoDense(reqWith(auto()), true);
    assertTrue(out.getPipeline().getDenseEnabled(), "dense leg should run when serviceable");
    assertTrue(out.getPipeline().getSparseEnabled(), "sparse leg stays on (hybrid)");
  }

  @Test
  @DisplayName("AUTO + not serviceable (BLOCKED_LEGACY or embedder unavailable) → stays keyword-only")
  void autoNotServiceableStaysKeyword() {
    SearchRequest out = WorkerSearchService.resolveAutoDense(reqWith(auto()), false);
    assertFalse(out.getPipeline().getDenseEnabled(), "dense leg must not run when not serviceable");
    assertTrue(out.getPipeline().getSparseEnabled(), "keyword retrieval still serves");
  }

  @Test
  @DisplayName("Explicit pipeline (dense_auto unset) is returned unchanged, regardless of serviceability")
  void explicitRequestUnchanged() {
    SearchRequest explicit =
        reqWith(PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(false).build());
    // Identity: a non-AUTO request is not rewritten (no behavior change for explicit callers).
    assertSame(explicit, WorkerSearchService.resolveAutoDense(explicit, true));
    assertSame(explicit, WorkerSearchService.resolveAutoDense(explicit, false));
  }

  @Test
  @DisplayName("Explicit dense=true (not AUTO) is honored as-is even when blocked (planner degrades later)")
  void explicitDenseNotForcedOff() {
    SearchRequest explicitDense =
        reqWith(PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build());
    SearchRequest out = WorkerSearchService.resolveAutoDense(explicitDense, false);
    assertSame(explicitDense, out);
    assertTrue(out.getPipeline().getDenseEnabled());
  }
}
