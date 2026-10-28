/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 799 §N.2 — {@code worker.limits.max_content_length} / {@code max_file_size} are wired.
 *
 * <p>These tests exist to protect one invariant specifically: {@code policyId} is a PERSISTED
 * IDENTITY (stamped onto every {@code ExtractionArtifact}, stored by {@code SqliteJobQueue}). The
 * cheap implementation — making {@code defaults()} read config — would have left two materially
 * different policies both calling themselves {@code tika-default-v7}, so a staleness or re-extract
 * decision keyed on the id would treat them as equivalent. The tests below are what stop that
 * implementation from being reintroduced.
 */
class TikaExtractionPolicyWorkerLimitsTest {

  private static ResolvedConfig.Worker worker(int maxContentLength, long maxFileSize) {
    return new ResolvedConfig.Worker(maxContentLength, maxFileSize);
  }

  @Test
  @DisplayName("null config yields the deterministic defaults, unchanged")
  void nullConfigYieldsDefaults() {
    assertEquals(TikaExtractionPolicy.defaults(), TikaExtractionPolicy.fromWorkerLimits(null));
  }

  @Test
  @DisplayName("limits equal to the defaults keep the tika-default-v7 identity")
  void defaultEquivalentLimitsKeepDefaultIdentity() {
    TikaExtractionPolicy p =
        TikaExtractionPolicy.fromWorkerLimits(
            worker(
                TikaExtractionPolicy.DEFAULT_MAX_EXTRACTED_CHARS,
                TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
    assertEquals("tika-default-v7", p.policyId());
    assertEquals(TikaExtractionPolicy.defaults(), p);
  }

  @Test
  @DisplayName("THE INVARIANT: differing limits must NOT reuse the default policy id")
  void differingLimitsGetADistinctIdentity() {
    TikaExtractionPolicy configured =
        TikaExtractionPolicy.fromWorkerLimits(worker(1_000_000, 5_000_000L));
    assertNotEquals(
        "tika-default-v7",
        configured.policyId(),
        "a configured policy sharing the default id would make the persisted extraction identity "
            + "meaningless — this is the whole reason fromWorkerLimits exists");
    assertEquals("tika-config-v7-1000000-5000000", configured.policyId());
    assertEquals(1_000_000, configured.maxExtractedChars());
    assertEquals(5_000_000L, configured.maxInputBytes());
  }

  @Test
  @DisplayName("the id is deterministic — same limits, same id (it crosses a process boundary)")
  void identityIsDeterministic() {
    assertEquals(
        TikaExtractionPolicy.fromWorkerLimits(worker(123_456, 7_000_000L)).policyId(),
        TikaExtractionPolicy.fromWorkerLimits(worker(123_456, 7_000_000L)).policyId());
  }

  @Test
  @DisplayName("different limits produce different ids — no collision between configurations")
  void distinctLimitsProduceDistinctIds() {
    assertNotEquals(
        TikaExtractionPolicy.fromWorkerLimits(worker(1_000_000, 5_000_000L)).policyId(),
        TikaExtractionPolicy.fromWorkerLimits(worker(2_000_000, 5_000_000L)).policyId());
  }

  @Test
  @DisplayName("non-positive limits fall back rather than disabling the guard")
  void nonPositiveLimitsFallBack() {
    TikaExtractionPolicy p = TikaExtractionPolicy.fromWorkerLimits(worker(0, 0L));
    assertEquals(TikaExtractionPolicy.DEFAULT_MAX_EXTRACTED_CHARS, p.maxExtractedChars());
    assertEquals(TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES, p.maxInputBytes());
    assertEquals("tika-default-v7", p.policyId());
  }

  @Test
  @DisplayName("a smaller file cap also lowers the office cap — it can never exceed the input cap")
  void officeCapNeverExceedsInputCap() {
    TikaExtractionPolicy p = TikaExtractionPolicy.fromWorkerLimits(worker(1_000_000, 5_000_000L));
    assertTrue(
        p.maxOfficeInputBytes() <= p.maxInputBytes(),
        "office cap must stay within the overall input cap");
  }

  @Test
  @DisplayName("defaults() is untouched by this factory — the sandbox-child path depends on it")
  void defaultsRemainStable() {
    TikaExtractionPolicy a = TikaExtractionPolicy.defaults();
    TikaExtractionPolicy.fromWorkerLimits(worker(1_000_000, 5_000_000L));
    assertEquals(a, TikaExtractionPolicy.defaults());
    assertSame("tika-default-v7", TikaExtractionPolicy.defaults().policyId().intern());
  }
}
