/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.embed.EmbeddingProvider;
import org.junit.jupiter.api.Test;

/**
 * Spec E step E3 (section 1.3 item 2): backfill cycle entry is skipped while the bulk work gate
 * holds and any bulk encoder (embedding, sparse or NER) is on the GPU. CPU bulk is not held.
 */
final class LoopPacingBulkHoldTest {

  private static EmbeddingProvider provider(boolean usingGpu) {
    EmbeddingProvider provider = mock(EmbeddingProvider.class);
    when(provider.isUsingGpu()).thenReturn(usingGpu);
    return provider;
  }

  // held with embedding on the GPU: cycle entry is skipped and a running pass stops
  @Test
  void heldGpuEmbeddingSkipsTheCycle() {
    EmbeddingProvider gpu = provider(true);
    assertFalse(LoopPacingPolicy.shouldRunBackfill(false, false, true, false, gpu));
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(true, false, false, true, false, gpu));
    assertTrue(LoopPacingPolicy.shouldRunBackfill(false, false, false, false, gpu), "clear runs");
  }

  // held with only the sparse or NER encoder on the GPU: cycle entry is still skipped
  @Test
  void heldSparseOrNerOnGpuSkipsTheCycle() {
    EmbeddingProvider cpu = provider(false);
    assertFalse(LoopPacingPolicy.shouldRunBackfill(false, false, true, true, cpu));
    assertTrue(LoopPacingPolicy.shouldRunBackfill(false, false, true, false, cpu),
        "every bulk encoder on the CPU: not held");
  }
}
