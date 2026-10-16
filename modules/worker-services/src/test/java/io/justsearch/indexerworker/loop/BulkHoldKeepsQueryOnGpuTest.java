/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ort.GpuArbiter;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-CH12a (CH12): the bulk hold is not the main-GPU claim. With the profile pinned to P12 and
 * bulk work held for external memory use, the query encoders' GPU arbiter (the composition root's
 * {@code () -> !signalBus.isMainGpuActive()}) still allows the GPU, so the query embed runs there,
 * and the GPU-handoff transition releases no embedding session. Only a real main-GPU claim does.
 */
final class BulkHoldKeepsQueryOnGpuTest {

  // E-CH12a: held for external use, the query embed stays on the GPU and nothing is released
  @Test
  void heldBulkWorkKeepsTheQueryEmbedOnGpuAndReleasesNothing() {
    var gauge = new GpuSchedulingGauge();
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setExternalPressure(true);
    var bus = new InProcessWorkerSignalBus(gauge, null, gate);
    assertTrue(bus.isBulkHeld(), "bulk work is held");
    assertTrue(bus.shouldYieldGpuBackfill(), "bulk GPU work yields");
    assertFalse(bus.isMainGpuActive(), "the hold is not the main-GPU claim");

    GpuArbiter queryArbiter = () -> !bus.isMainGpuActive();
    assertTrue(queryArbiter.shouldUseGpu(), "the query embed runs on the GPU while held");

    EmbeddingService embedding = mock(EmbeddingService.class);
    when(embedding.isUsingGpu()).thenReturn(true);
    var lifecycle = new EmbeddingProviderLifecycle(
        bus, mock(JobQueue.class), mock(IndexCountOps.class), mock(CommitOps.class));
    lifecycle.setEmbeddingProvider(embedding);
    lifecycle.handleGpuStateTransition();
    verify(embedding, never()).releaseGpuSession();

    // The same transition does release on a real main-GPU claim, so the check above can fail.
    gauge.setMainGpuActive(true);
    lifecycle.handleGpuStateTransition();
    verify(embedding, times(1)).releaseGpuSession();
  }
}
