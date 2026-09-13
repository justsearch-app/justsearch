package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.metarank.lightgbm4j.LGBMBooster;
import io.github.metarank.lightgbm4j.LGBMDataset;
import io.github.metarank.lightgbm4j.LGBMException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Latency gate: LambdaMART inference on 50 candidates. Combines a robust median check
 * (regression detector for typical-case latency) with a tolerant p99 check (background-load
 * tolerant). Observations.md L58/L121/L153: the original 10ms p99 was environmentally
 * sensitive — 147ms reads under full-build contention; p99 dual-gated with p50 catches real
 * slowdowns even when p99 spikes from GC / background load.
 *
 * <p><b>Quarantined 2026-09-07 (lane F stage A).</b> The p99 gate is already load-tolerant; the
 * <em>p50</em> gate is not, and cannot be: 5 ms is tight because the typical reading is well under
 * 1 ms, which is exactly what makes it a useful regression detector and exactly what makes it
 * unsurvivable under contention. Measured four times in one session on a machine running a
 * concurrent Gradle build from a sibling worktree: 8.6 ms, 19.3 ms, 19.5 ms, 22.1 ms — and green on
 * every isolated re-run. That is the environment failing, not the model.
 *
 * <p>So it is tagged {@code load-sensitive} and excluded from {@code integrationTest} (which
 * {@code check} depends on, and therefore {@code build}), and run by the dedicated
 * {@code loadSensitiveTest} task instead. <b>The thresholds are unchanged</b> — raising them to buy
 * a green would delete the regression detector, which is the whole value. Run it on an idle machine:
 * {@code ./gradlew.bat :modules:app-services:loadSensitiveTest}.
 */
@Tag("load-sensitive")
@DisplayName("LambdaMartBenchmarkTest")
class LambdaMartBenchmarkTest {

  private static final int CANDIDATES = 50;
  private static final int WARMUP_ITERS = 20; // JIT + JNI stabilization
  private static final int MEASURED_ITERS = 100; // larger sample for stable percentile
  // p50 catches steady-state regressions; tight because typical reading is well under 1ms.
  private static final long P50_THRESHOLD_NS = 5_000_000L; // 5ms
  // p99 catches catastrophic regressions but tolerates background contention spikes.
  // Raised from 10ms after observations.md L58 (147ms reads under build contention).
  private static final long P99_THRESHOLD_NS = 30_000_000L; // 30ms

  @Test
  @DisplayName("inference on 50 candidates completes under 5ms median, 30ms p99")
  void inferenceLatencyUnder5ms() throws Exception {
    assumeTrue(lightgbmNativeAvailable(), "lightgbm4j native library not available");

    LGBMBooster model = buildTrainedModel();
    assumeTrue(model != null, "stub model build failed");

    LambdaMartReranker reranker = new LambdaMartReranker();
    reranker.setModel(model);

    // Input arrays for 50 candidates.
    float[] bm25s = new float[CANDIDATES];
    float[] vectors = new float[CANDIDATES];
    float[] splades = new float[CANDIDATES];
    for (int i = 0; i < CANDIDATES; i++) {
      bm25s[i] = (float) (i + 1);
      vectors[i] = 0.0f;
      splades[i] = 0.0f;
    }

    // Warm up.
    for (int i = 0; i < WARMUP_ITERS; i++) {
      List<Integer> result = reranker.rerank(bm25s, vectors, splades, CANDIDATES);
      assertTrue(result != null && result.size() == CANDIDATES);
    }

    // Measure.
    long[] nanoTimes = new long[MEASURED_ITERS];
    for (int i = 0; i < MEASURED_ITERS; i++) {
      long start = System.nanoTime();
      List<Integer> result = reranker.rerank(bm25s, vectors, splades, CANDIDATES);
      nanoTimes[i] = System.nanoTime() - start;
      assertTrue(result != null && result.size() == CANDIDATES);
    }

    Arrays.sort(nanoTimes);
    int p50Index = MEASURED_ITERS / 2; // 50 for 100 samples
    int p99Index = (int) Math.ceil(0.99 * MEASURED_ITERS) - 1; // 98 for 100 samples
    long p50Ns = nanoTimes[p50Index];
    long p99Ns = nanoTimes[p99Index];

    // Steady-state regression check: median must be tight regardless of background.
    assertTrue(
        p50Ns < P50_THRESHOLD_NS,
        "LambdaMART p50 latency " + (p50Ns / 1_000_000.0) + "ms exceeded 5ms threshold");
    // Catastrophic regression check: p99 tolerates background spikes but catches 10× slowdowns.
    assertTrue(
        p99Ns < P99_THRESHOLD_NS,
        "LambdaMART p99 latency " + (p99Ns / 1_000_000.0) + "ms exceeded 30ms threshold");
  }

  private static boolean lightgbmNativeAvailable() {
    try {
      LGBMBooster.loadNative();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Builds a 50-iteration LambdaMART model on a synthetic dataset of
   * 100 rows × 5 features. Returns null if native is unavailable.
   */
  private static LGBMBooster buildTrainedModel() {
    try {
      int numRows = 100;
      int numCols = LambdaMartFeatureSchema.NUM_FEATURES;
      float[] data = new float[numRows * numCols];

      // First 50 rows: positives, bm25 = 1..50; last 50: negatives
      for (int r = 0; r < 50; r++) {
        data[r * numCols + LambdaMartFeatureSchema.IDX_SPARSE] = r + 1.0f;
      }

      float[] labels = new float[numRows];
      for (int r = 0; r < 50; r++) labels[r] = 1.0f;

      // 10 groups of 10 rows each
      int[] groups = new int[10];
      Arrays.fill(groups, 10);

      LGBMDataset ds =
          LGBMDataset.createFromMat(data, numRows, numCols, true, "", null);
      try {
        ds.setField("label", labels);
        ds.setField("group", groups);
        LGBMBooster booster =
            LGBMBooster.create(
                ds,
                "objective=lambdarank metric=ndcg num_leaves=4 min_data_in_leaf=1"
                    + " verbosity=-1 num_threads=1 label_gain=0,1");
        try {
          for (int i = 0; i < 50; i++) {
            if (booster.updateOneIter()) break;
          }
        } catch (LGBMException e) {
          booster.close();
          return null;
        }
        return booster;
      } finally {
        try {
          ds.close();
        } catch (Exception e2) {
          // suppress
        }
      }
    } catch (Exception e) {
      return null;
    }
  }
}
