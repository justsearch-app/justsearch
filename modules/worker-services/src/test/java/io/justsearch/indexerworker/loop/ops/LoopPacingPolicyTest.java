package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the backfill-pacing gate (tempdoc 630). The two yield reasons are distinct: <b>energy</b>
 * defers regardless of GPU/CPU (power), while <b>GPU yield</b> defers only on a real VRAM conflict
 * (embeddings actually on the GPU). The headline case — energy-reduced + CPU embeddings — is the one
 * the pre-fix code got wrong (it kept running because of the {@code !isUsingGpu()} escape).
 */
final class LoopPacingPolicyTest {

  /** Minimal {@link EmbeddingProvider} where only {@code isUsingGpu()} matters to the gate. */
  private static EmbeddingProvider provider(boolean usingGpu) {
    return new EmbeddingProvider() {
      @Override public float[] embedDocument(String text) { return new float[0]; }
      @Override public float[] embedQuery(String text) { return new float[0]; }
      @Override public List<float[]> embedDocumentBatch(List<String> texts) { return List.of(); }
      @Override public int dimension() { return 0; }
      @Override public boolean isAvailable() { return true; }
      @Override public boolean isUsingGpu() { return usingGpu; }
      @Override
      public io.justsearch.indexerworker.embed.EmbeddingService.ChunkedEmbedding embedWithSpans(
          String content, int[][] charSpans) {
        return null;
      }
    };
  }

  private static final EmbeddingProvider CPU = provider(false);
  private static final EmbeddingProvider GPU = provider(true);

  @Test
  @DisplayName("energy-reduced defers backfill even on CPU embeddings (the CPU-escape bug)")
  void energyDefersOnCpu() {
    // Pre-fix this returned true (ran), silently no-opping energy saver on GPU-less laptops.
    assertFalse(LoopPacingPolicy.shouldRunBackfill(false, true, CPU));
  }

  @Test
  @DisplayName("energy-reduced defers backfill on GPU embeddings too")
  void energyDefersOnGpu() {
    assertFalse(LoopPacingPolicy.shouldRunBackfill(false, true, GPU));
  }

  @Test
  @DisplayName("no yield reason ⇒ backfill runs (CPU and GPU)")
  void runsWhenIdle() {
    assertTrue(LoopPacingPolicy.shouldRunBackfill(false, false, CPU));
    assertTrue(LoopPacingPolicy.shouldRunBackfill(false, false, GPU));
  }

  @Test
  @DisplayName("Main GPU active defers only when embeddings are on the GPU (VRAM conflict)")
  void gpuYieldIsConflictOnly() {
    assertFalse(LoopPacingPolicy.shouldRunBackfill(true, false, GPU), "GPU embed + Main GPU ⇒ conflict");
    assertTrue(LoopPacingPolicy.shouldRunBackfill(true, false, CPU), "CPU embed ⇒ no VRAM conflict");
  }

  @Test
  @DisplayName("interrupt fires on energy-reduced + CPU (blocked), and on a GPU VRAM conflict")
  void interruptCovers() {
    // energy + CPU now blocks → interrupt true (pre-fix: not blocked on CPU).
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(true, false, true, CPU));
    // Tempdoc 885 item 3: userActive left the signature — foreground contention paces the
    // backfill (IndexingPacing) instead of interrupting it. The surviving yield reason this slot
    // used to cover is the GPU VRAM conflict: Main claimed the GPU and embeddings are on the GPU.
    assertTrue(
        LoopPacingPolicy.shouldInterruptBackfill(true, true, false, GPU),
        "GPU VRAM conflict blocks the backfill ⇒ interrupt");
    // running, idle, no yield ⇒ no interrupt.
    assertFalse(LoopPacingPolicy.shouldInterruptBackfill(true, false, false, CPU));
    // not running ⇒ interrupt.
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(false, false, false, CPU));
  }

  @Test
  @DisplayName("flipping the in-process gauge changes shouldRunBackfill (lane F item A5)")
  void gaugeDrivesTheBackfillGate() {
    // The gate's two inputs are exactly the gauge's two signals. This is the A5 acceptance:
    // whatever writes main_gpu_active / energy_reduced — the MMF byte today, the gauge in the
    // merged Engine — the pacing decision must follow it with no change in meaning.
    GpuSchedulingGauge gauge = new GpuSchedulingGauge();

    assertTrue(shouldRunBackfill(gauge, GPU), "idle gauge ⇒ backfill runs");

    gauge.setMainGpuActive(true);
    assertFalse(shouldRunBackfill(gauge, GPU), "GPU claimed + GPU embeddings ⇒ VRAM conflict");
    assertTrue(shouldRunBackfill(gauge, CPU), "GPU claimed + CPU embeddings ⇒ no conflict");

    gauge.setMainGpuActive(false);
    gauge.setEnergyReduced(true);
    assertFalse(shouldRunBackfill(gauge, CPU), "energy reduced defers regardless of GPU/CPU");
    assertFalse(shouldRunBackfill(gauge, GPU));

    gauge.setEnergyReduced(false);
    assertTrue(shouldRunBackfill(gauge, CPU), "both reasons cleared ⇒ backfill resumes");
    assertTrue(shouldRunBackfill(gauge, GPU));
  }

  private static boolean shouldRunBackfill(GpuSchedulingGauge gauge, EmbeddingProvider provider) {
    return LoopPacingPolicy.shouldRunBackfill(
        gauge.isMainGpuActive(), gauge.isEnergyReduced(), provider);
  }

  @Test
  @DisplayName(
      "isTimeCommitTriggered / isBufferCommitTriggered honor the passed-in threshold (tempdoc"
          + " 710 Wave-1.5 Move 4: thresholds moved from static constants to config parameters)")
  void commitTriggersHonorConfiguredThresholds() {
    assertFalse(LoopPacingPolicy.isTimeCommitTriggered(9_999L, 1, 10_000L));
    assertTrue(LoopPacingPolicy.isTimeCommitTriggered(10_000L, 1, 10_000L));
    assertFalse(LoopPacingPolicy.isTimeCommitTriggered(10_000L, 0, 10_000L), "no docs pending");
    // A smaller configured interval fires earlier — proves the value is honored, not hardcoded.
    assertTrue(LoopPacingPolicy.isTimeCommitTriggered(500L, 1, 250L));

    assertFalse(LoopPacingPolicy.isBufferCommitTriggered(999, 1000));
    assertTrue(LoopPacingPolicy.isBufferCommitTriggered(1000, 1000));
    // A smaller configured max fires earlier.
    assertTrue(LoopPacingPolicy.isBufferCommitTriggered(10, 5));
  }
}
