/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.metrics;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EnrichmentAttemptTimingTest {
  @Test
  void zeroCompletionAttemptsRetainElapsedTimeAndCallCount() throws Exception {
    var constructor = OperationalMetrics.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    var metrics = constructor.newInstance();
    // Inject elapsed measurements: no wall-clock bounds or shared singleton state.
    for (long elapsed : new long[] {11, 17, 23, 31, 41}) {
      metrics.recordStageTiming("embed", 0, elapsed);
    }
    assertEquals(123L, metrics.getBatchTimingMs().get("embed"));
    assertEquals(5L, metrics.getBatchTimingCount().get("embed"));
    assertFalse(metrics.getEnrichmentCompleted().containsKey("embed"));
    metrics.recordStageTiming("embed", 2, 7);
    assertEquals(130L, metrics.getBatchTimingMs().get("embed"));
    assertEquals(6L, metrics.getBatchTimingCount().get("embed"));
  }
}
