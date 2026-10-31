package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link BackoffPolicy} seam (governance/logic-seams.v1.json) — the pure exponential-
 * backoff law extracted from BackfillScheduler. Plain JUnit + seeded enumeration (no jqwik, tempdoc
 * 555 §10). Encodes: exact doubling, the cap, and monotonicity — killing off-by-one exponent and
 * wrong-cap mutants that would cause retry starvation/thrashing.
 */
class BackoffPolicyTest {

  @Test
  void saturatesAcrossMultiplicationOverflowShiftWrappingAndExtremeCounts() {
    for (long base : new long[]{0, -100, 1, 1000, 60_000, Long.MAX_VALUE}) {
      for (long cap : new long[]{0, 60_000, Long.MAX_VALUE}) {
        long previous = -1;
        for (int count : new int[]{-1, 0, 1, 2, 15, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 1000, Integer.MAX_VALUE}) {
          long expected = count >= 63 && base > 0 ? cap : java.math.BigInteger.valueOf(Math.max(0, base))
              .shiftLeft(Math.max(0, Math.min(count, 63))).min(java.math.BigInteger.valueOf(cap)).longValueExact();
          long actual = BackoffPolicy.backoffMs(base, count, cap);
          assertEquals(expected, actual, "base=" + base + " cap=" + cap + " failures=" + count);
          assertTrue(actual >= previous && actual >= 0 && actual <= cap);
          previous = actual;
        }
      }
    }
  }

  @Test
  @DisplayName("law: delay = min(base * 2^failures, cap), exact below the cap")
  void exactExponentialBelowCap() {
    long base = 100L;
    long cap = 1_000_000L;
    assertEquals(100L, BackoffPolicy.backoffMs(base, 0, cap)); // 100 * 2^0
    assertEquals(200L, BackoffPolicy.backoffMs(base, 1, cap)); // 100 * 2^1
    assertEquals(400L, BackoffPolicy.backoffMs(base, 2, cap));
    assertEquals(800L, BackoffPolicy.backoffMs(base, 3, cap));
    assertEquals(102_400L, BackoffPolicy.backoffMs(base, 10, cap)); // 100 * 1024
  }

  @Test
  @DisplayName("law: the delay is clamped to the cap")
  void clampedToCap() {
    long base = 1000L;
    long cap = 60_000L;
    // 1000 * 2^6 = 64000 > 60000 → capped
    assertEquals(60_000L, BackoffPolicy.backoffMs(base, 6, cap));
    assertEquals(60_000L, BackoffPolicy.backoffMs(base, 20, cap));
    // exactly at/below the cap is not clamped: 1000 * 2^5 = 32000
    assertEquals(32_000L, BackoffPolicy.backoffMs(base, 5, cap));
  }

  @Test
  @DisplayName("law: delay is non-decreasing in the failure count")
  void monotoneInFailures() {
    long base = 50L;
    long cap = BackoffPolicy.SPLADE_BACKOFF_CAP_MS;
    long prev = -1L;
    for (int f = 0; f <= 30; f++) {
      long d = BackoffPolicy.backoffMs(base, f, cap);
      assertTrue(d >= prev, "backoff must be non-decreasing in failures (f=" + f + ")");
      assertTrue(d <= cap, "backoff must never exceed the cap");
      prev = d;
    }
  }

  @Test
  @DisplayName("law: negative inputs are clamped (no negative or wrapped delays)")
  void clampsNegatives() {
    assertEquals(0L, BackoffPolicy.backoffMs(-100L, 3, 60_000L));
    assertEquals(100L, BackoffPolicy.backoffMs(100L, -5, 60_000L)); // failures clamped to 0 → base
  }

  @Test
  @DisplayName("splade helper uses the 60s cap")
  void spladeHelperCap() {
    assertEquals(BackoffPolicy.SPLADE_BACKOFF_CAP_MS, BackoffPolicy.spladeBackoffMs(1000L, 10));
    assertEquals(2000L, BackoffPolicy.spladeBackoffMs(1000L, 1));
  }
}
