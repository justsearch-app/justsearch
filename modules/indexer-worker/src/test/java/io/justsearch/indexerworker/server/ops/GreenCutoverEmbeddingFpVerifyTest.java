package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 598 R3 — the embedding-fingerprint half of green cutover verification
 * ({@link KnowledgeServerMigrationOps#verifyGreenMetadata}, the IO-free core of the cutover guard).
 * A blue/green rebuild must not promote a green that lacks a current-model embedding fingerprint
 * (else the promoted generation serves BLOCKED_LEGACY despite a "successful" rebuild — the tempdoc
 * 593 §H trap); but a keyword-only rebuild (no embedding model resolvable) must still promote.
 */
class GreenCutoverEmbeddingFpVerifyTest {

  private static final Logger LOG = LoggerFactory.getLogger(GreenCutoverEmbeddingFpVerifyTest.class);
  private static final String EMBED_KEY = EmbeddingCompatibilityController.COMMIT_META_KEY;

  /** The real current schema fingerprint, so the schema check passes and we isolate the embed check. */
  private static String indexFingerprint() {
    return String.valueOf(new SsotCommitMetadataSource().build().get("index_fingerprint"));
  }

  private static Map<String, String> completeGreen() {
    Map<String, String> ud = new HashMap<>();
    ud.put("build_state", "COMPLETE");
    ud.put("index_fingerprint", indexFingerprint());
    return ud;
  }

  @Test
  void recordedGreenUsesItsFrozenTargetInsteadOfTheServingFingerprint() {
    Map<String, String> ud = completeGreen();
    ud.put("index_fingerprint", "candidate-target");
    ud.put(EMBED_KEY, "candidate-embedding");

    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(
        ud, "candidate-target", "candidate-embedding", LOG));
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(
        ud, "different-target", "candidate-embedding", LOG));
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(
        ud, "candidate-target", "different-embedding", LOG));
  }

  @Test
  @DisplayName("matching embedding fingerprint → green verifies")
  void matchingFpVerifies() {
    Map<String, String> ud = completeGreen();
    ud.put(EMBED_KEY, "abc123");
    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, null, "abc123", LOG));
  }

  @Test
  @DisplayName("missing embedding fingerprint when a model is expected → green REJECTED")
  void missingFpWhenExpectedRejected() {
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(completeGreen(), null, "abc123", LOG));
  }

  /**
   * Tempdoc 915 §C, the third refusal. If THIS runtime cannot compute an expected
   * {@code index_fingerprint}, it cannot attest that the green it just built is the shape it meant
   * to build. Promoting anyway would swap in a generation on no evidence, so the cutover refuses and
   * retries on the next boot. Distinct from a mismatch: nothing here disagrees, we simply do not
   * know.
   */
  @Test
  @DisplayName("expected fingerprint uncomputable -> green REJECTED rather than promoted blind")
  void uncomputableExpectedFingerprintRejects() {
    Map<String, String> ud = completeGreen();
    ud.put(EMBED_KEY, "abc123");
    // Sanity: with everything resolvable this same green verifies, so the refusal below is
    // attributable to the indeterminate input and nothing else.
    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, null, "abc123", LOG));
    ch.qos.logback.classic.Logger captured =
        (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger("green-verify-uncomputable-expected");
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    captured.addAppender(appender);
    try {
      IndexFingerprint.installModelFingerprintProviders(
          IndexFingerprint.ModelFingerprint::indeterminate,
          IndexFingerprint.ModelFingerprint::notConfigured,
          IndexFingerprint.ModelFingerprint::notConfigured);
      assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, null, "abc123", captured));
      // The verdict alone is not evidence: without the refusal the code falls through to the
      // mismatch branch and returns false anyway, for a reason that is not true. Pin the reason.
      assertTrue(
          appender.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .anyMatch(m -> m.contains("could not compute an expected index_fingerprint")),
          "the refusal must be attributed to the uncomputable expected value, not reported as a"
              + " mismatch: "
              + appender.list.stream()
                  .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                  .toList());
    } finally {
      IndexFingerprint.resetModelFingerprintProviders();
      captured.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("mismatched embedding fingerprint -> green REJECTED")
  void mismatchedFpRejected() {
    Map<String, String> ud = completeGreen();
    ud.put(EMBED_KEY, "stale-model-sha");
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, null, "abc123", LOG));
  }

  @Test
  @DisplayName("no embedding model expected (keyword-only rebuild) → embedding check skipped, verifies")
  void noModelSkipsEmbeddingCheck() {
    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(completeGreen(), null, null, LOG));
    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(completeGreen(), null, "  ", LOG));
  }

  @Test
  @DisplayName("incomplete green (build_state != COMPLETE) → REJECTED even with a valid embedding fp")
  void incompleteGreenRejected() {
    Map<String, String> ud = completeGreen();
    ud.put("build_state", "BUILDING");
    ud.put(EMBED_KEY, "abc123");
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, null, "abc123", LOG));
  }
}
