/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpladeOptInStatusTest {
  @TempDir Path temp;

  @Test void unavailableBgeSelectionKeepsItsApplicabilityAcrossServingSuccessors() throws Exception {
    var raw = ResolvedConfig.builder().putDefault("rag.chunk_splade.enabled", "true")
        .putDefault("justsearch.splade.enabled", "false").build();
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(queue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);
    var source = new IndexStatusOps(queue, temp, null, null, null, null, null, null, null,
        null, null, OperationalMetrics.getInstance(), null, mock(WorkerSignalBus.class), 0L);
    var successor = new IndexStatusOps(queue, temp, null, null, null, null, null, null, null,
        null, null, OperationalMetrics.getInstance(), null, mock(WorkerSignalBus.class), 0L);
    for (var ops : new IndexStatusOps[] {source, successor}) {
      ops.setStageEnabled(false, false, false);
      ops.setResolvedConfigSupplier(() -> raw);
    }
    try {
      var capture = IndexStatusOps.class.getDeclaredMethod("setChunkSparsePermission", boolean.class);
      capture.setAccessible(true);
      capture.invoke(source, true); // BGE selection is independent of the separate SPLADE veto.
      var retain = IndexStatusOps.class.getDeclaredMethod("copyChunkSparsePermissionFrom", IndexStatusOps.class);
      retain.setAccessible(true);
      retain.invoke(successor, source);
    } catch (NoSuchMethodException beforeE4d) { /* The baseline preserves the raw chunk flag. */ }
    assertTrue(successor.buildStatusResponse().getEnrichment().getChunk().getSpladeEnabled());
    assertFalse(successor.buildStatusResponse().getEnrichment().getSpladeEnabled(),
        "the pre-existing BGE parent flag gap is deliberately unchanged");
  }

  @Test void permittedButUnavailableSparseWorkKeepsItsExistingChunkBacklog() throws Exception {
    var raw = ResolvedConfig.builder().putDefault("rag.chunk_splade.enabled", "true")
        .putDefault("justsearch.splade.enabled", "false").build();
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(queue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);
    var ops = new IndexStatusOps(queue, temp, null, null, null, null, null, null, null,
        null, null, OperationalMetrics.getInstance(), null, mock(WorkerSignalBus.class), 0L);
    ops.setStageEnabled(false, false, false);
    ops.setResolvedConfigSupplier(() -> raw);
    // Before E4d the raw chunk flag already preserved this backlog. Newer code also retains
    // the serving permission when a later global snapshot disagrees and model loading failed.
    try {
      var capture = IndexStatusOps.class.getDeclaredMethod("setChunkSparsePermission", boolean.class);
      capture.setAccessible(true);
      capture.invoke(ops, true);
    } catch (NoSuchMethodException beforeE4d) { /* The baseline uses its original raw chunk flag. */ }
    assertTrue(ops.buildStatusResponse().getEnrichment().getChunk().getSpladeEnabled());
    assertFalse(raw.ai().splade().enabled());
  }

  @Test void bgeSparseProviderKeepsExistingChunkApplicabilityOutsideTheGlobalVeto() {
    var raw = ResolvedConfig.builder().putDefault("rag.chunk_splade.enabled", "true")
        .putDefault("justsearch.splade.enabled", "false").build();
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(queue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);
    var loop = mock(io.justsearch.indexerworker.loop.IndexingLoop.class);
    when(loop.enrichmentStageAvailability()).thenReturn(
        new io.justsearch.indexerworker.loop.BackfillScheduler.StageAvailability(false, true, false));
    var ops = new IndexStatusOps(queue, temp, null, null, null, null, null, null, null,
        null, null, OperationalMetrics.getInstance(), loop, mock(WorkerSignalBus.class), 0L);
    // The existing parent flags use only separate wrappers. Preserve that known BGE gap.
    ops.setStageEnabled(false, false, false);
    ops.setResolvedConfigSupplier(() -> raw);
    var response = ops.buildStatusResponse();
    assertFalse(response.getEnrichment().getSpladeEnabled());
    assertTrue(response.getEnrichment().getChunk().getSpladeEnabled());
  }

  @Test void disabledServingRuntimeMakesChunkWorkInapplicableDespiteLiveDemand() {
    var raw = ResolvedConfig.builder().putDefault("rag.chunk_splade.enabled", "true")
        .putDefault("justsearch.splade.enabled", "true").build();
    var queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(queue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);
    var ops = new IndexStatusOps(queue, temp, null, null, null, null,
        null, null, null, null, null, OperationalMetrics.getInstance(), null,
        mock(WorkerSignalBus.class), 0L);
    // This serving runtime omitted SPLADE; another configuration now requests it.
    ops.setStageEnabled(false, false, false);
    ops.setResolvedConfigSupplier(() -> raw);
    var response = ops.buildStatusResponse();
    assertFalse(response.getEnrichment().getSpladeEnabled());
    assertFalse(response.getEnrichment().getChunk().getSpladeEnabled(),
        "S7/S13: live chunk demand cannot make an absent serving encoder pending");
    assertTrue(raw.rag().chunkSpladeEnabled(), "raw configuration stays visible");
    ops.setStageEnabled(false, true, false);
    assertTrue(ops.buildStatusResponse().getEnrichment().getChunk().getSpladeEnabled());
  }
}
