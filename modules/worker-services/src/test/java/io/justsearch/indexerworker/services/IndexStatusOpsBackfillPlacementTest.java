/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.BackfillScheduler;
import io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.ipc.EnrichmentCoverage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S33.2: status reports the aggregate predicate used by the idle-cycle GPU hold. */
final class IndexStatusOpsBackfillPlacementTest {
  @TempDir Path indexDirectory;

  @Test
  void placementTracksEveryEncoderIncludingMixedCpuGpuEnrichment() {
    var embedding = mock(EmbeddingProvider.class);
    var lifecycle = mock(EmbeddingProviderLifecycle.class);
    when(lifecycle.embeddingProvider()).thenReturn(embedding);
    var sparse = mock(SpladeEncoder.class);
    var bge = mock(BgeM3Encoder.class);
    var ner = mock(NerService.class);
    var scheduler = new BackfillScheduler(null, null, null, null, null,
        mock(IndexingPacing.class), lifecycle, new AtomicBoolean(), () -> null,
        () -> sparse, () -> bge, () -> ner, () -> null);
    // Reflection lets the unchanged baseline execute and fail an assertion, rather than compile
    // against a read-only accessor that did not exist there.
    var placement = assertDoesNotThrow(() -> BackfillScheduler.class.getMethod("backfillGpuBound"));
    assertEquals(false, assertDoesNotThrow(() -> placement.invoke(scheduler)));
    when(embedding.isUsingGpu()).thenReturn(true);
    assertEquals(true, assertDoesNotThrow(() -> placement.invoke(scheduler)));
    when(embedding.isUsingGpu()).thenReturn(false);
    when(sparse.isGpuAvailable()).thenReturn(true);
    assertEquals(true, assertDoesNotThrow(() -> placement.invoke(scheduler)));
    when(sparse.isGpuAvailable()).thenReturn(false);
    when(bge.isGpuAvailable()).thenReturn(true);
    assertEquals(true, assertDoesNotThrow(() -> placement.invoke(scheduler)));
    when(bge.isGpuAvailable()).thenReturn(false);
    when(ner.isGpuAvailable()).thenReturn(true);
    assertEquals(true, assertDoesNotThrow(() -> placement.invoke(scheduler)));
    when(ner.isGpuAvailable()).thenReturn(false);
    assertEquals(false, assertDoesNotThrow(() -> placement.invoke(scheduler)));
  }

  @Test
  void workerStatusPublishesPlacementAndKeepsAnAbsentLoopUnknown() {
    var field = EnrichmentCoverage.getDescriptor().findFieldByName("backfill_gpu_bound");
    assertNotNull(field, "S33.2 needs an observed backfill placement, not a GPU-profile guess");
    for (boolean gpuBound : new boolean[] {false, true}) {
      var loop = mock(IndexingLoop.class, invocation ->
          invocation.getMethod().getName().equals("backfillGpuBound") ? gpuBound
              : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
      var enrichment = status(loop);
      assertEquals(true, enrichment.hasField(field));
      assertEquals(gpuBound, enrichment.getField(field));
    }
    assertFalse(status(null).hasField(field));
  }

  private EnrichmentCoverage status(IndexingLoop loop) {
    var queue = mock(JobQueue.class);
    when(queue.failureSummary()).thenReturn(new JobQueue.FailureSummary(0, null, null, null, null));
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    return new IndexStatusOps(queue, indexDirectory, null, null, null, null, null, null, null,
        null, null, OperationalMetrics.getInstance(), loop, mock(WorkerSignalBus.class), 0L)
        .buildStatusResponse().getEnrichment();
  }
}
