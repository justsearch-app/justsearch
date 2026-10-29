package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C — the repeat-rebuild brake must not charge boots it cannot attribute.
 *
 * <p>When the target {@code index_fingerprint} is uncomputable there is no target to charge an
 * attempt to. The first cut wrote {@code String.valueOf(null)}, which collapses every such boot onto
 * one shared {@code "null"} budget: three boots with an unreadable model file would then exhaust the
 * brake for a completely unrelated real shape, and the index that genuinely needed a rebuild would
 * never get one.
 */
final class KnowledgeServerBrakeSentinelTest {

  private static final String REAL_TARGET = "a-real-fingerprint";

  @Test
  void anUncomputableTargetNeverConsumesBudget(@TempDir Path tempDir) throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    mgr.initializeOrLoad();

    // Task t-20261013-ef3c41, round 3 (delegated decision): an unattributable boot no longer
    // reports attempt 1 and rebuilds unasked; with no brake it can consult, it is held. What this
    // test pins is unchanged: it never charges any budget.
    for (int i = 0; i < IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS + 5; i++) {
      org.junit.jupiter.api.Assertions.assertThrows(
          IndexGenerationManager.AutoRebuildBrakeUnreadableException.class,
          () -> KnowledgeServer.recordAutoRebuildAttemptOrSkip(mgr, null),
          "an unattributable boot is held rather than charged");
    }
    assertEquals(
        0,
        mgr.autoRebuildAttemptsFor(REAL_TARGET),
        "a real target must still start with its full budget after those boots");
    assertEquals(
        0,
        mgr.autoRebuildAttemptsFor("null"),
        "nothing may be recorded against the literal string \"null\"");
  }

  @Test
  void aRealTargetStillConsumesBudgetNormally(@TempDir Path tempDir) throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    mgr.initializeOrLoad();

    for (int expected = 1;
        expected <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS;
        expected++) {
      assertEquals(
          expected, KnowledgeServer.recordAutoRebuildAttemptOrSkip(mgr, REAL_TARGET));
    }
    assertTrue(
        KnowledgeServer.recordAutoRebuildAttemptOrSkip(mgr, REAL_TARGET)
            > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
        "the brake still bites for a target it can name");
  }
}
