/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C.14 (blocker B5) — a boot that RESUMES a migration whose Green is itself
 * mismatched, at a budget that has not been spent.
 *
 * <p>Wrapping this branch in the schema-mismatch handler was necessary and not sufficient. {@code
 * IndexGenerationManager.startMigration} deliberately no-ops while a migration is in flight, so the
 * handler re-resolved the SAME Green and re-opened it with the same builder that had just thrown:
 * the second {@code SCHEMA_MISMATCH} was raised inside the catch, uncaught, and killed {@code
 * start()}. On attempts 1, 2 and 3 — three consecutive dead Workers before the brake, whose whole
 * job is to bound exactly this repetition, could report anything at all. The existing brake test
 * seeds the budget already spent, so it only ever exercised boot #4.
 *
 * <p>The Worker must come up in every one of those boots. This test pins the fresh-budget boot; the
 * spent-budget one is {@code BrakeExhaustedWorkerServesReadOnlyTest}.
 */
@Timeout(180)
final class ResumedMigrationMismatchBootTest {

  /** The shape no runtime produces, stamped into the Green this boot must refuse to resume. */
  private static final String MISMATCHED = "f".repeat(64);

  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort
      }
      server = null;
    }
  }

  @Test
  void aResumedMigrationWithAMismatchedGreenStartsOverInsteadOfKillingTheWorker(
      @TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    String abandonedGreen = WorkerBootFixture.seedInFlightGreen(layout, MISMATCHED, 1);
    IndexGenerationManager genManager = layout.genManager();

    assertEquals(
        0,
        genManager.autoRebuildAttemptsFor(WorkerBootFixture.currentFingerprint()),
        "precondition: a FRESH budget — this is the boot the brake test cannot reach");

    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertNotNull(
        server.appServices(),
        "a mismatched Green must still build the service surface on Blue");
    assertFalse(
        server.rebuildBrakeExhaustedForTest(),
        "and it must not have short-circuited to the braked path — that would pass this test for"
            + " the wrong reason");

    IndexGenerationManager.State after =
        new IndexGenerationManager(layout.indexBase()).initializeOrLoad().state();
    assertEquals(
        IndexGenerationManager.MigrationState.MIGRATING.name(),
        after.migration_state(),
        "the rebuild is running again");
    assertNotNull(after.building_generation());
    // Against a FRESH Green, asserted on its content rather than its id. The id can legitimately
    // recycle: markForDeletion renames the abandoned directory to a `.del-<ts>` sibling, which frees
    // the name for newUniqueGenerationId in the same second — and when that rename loses to an open
    // Windows handle it falls back to a DELETEME marker and the id does change. The invariant that
    // holds either way, and the one that matters, is that the generation now being built is not the
    // one whose committed shape just failed the parity check.
    assertNotEquals(
        MISMATCHED,
        storedFingerprint(genManager.resolveGenerationPathStrict(after.building_generation())),
        "retrying the generation that just failed the parity check is how this boot died three"
            + " times in a row (abandoned: " + abandonedGreen + ")");
    assertEquals(
        WorkerBootFixture.currentFingerprint(),
        after.auto_rebuild_key(),
        "the attempt is charged to the shape being built, not to a shared bucket");
    assertEquals(
        Integer.valueOf(1),
        after.auto_rebuild_count(),
        "and it costs exactly one attempt, so the brake still bounds the repetition");

    // The ride-along: the resumed branch opens Blue read-only before it touches Green, and the
    // handler used to open a SECOND read-only runtime on the same directory and overwrite the
    // field — leaking a Directory + SearcherManager that nothing closes, on the index the Worker
    // then serves from for its whole lifetime. A leaked handle is invisible to every other
    // assertion a passing boot can make.
    assertEquals(
        1,
        server.readOnlyOpensForTest(),
        "Blue is opened once, and reused: the handler must not open a second runtime over it");
  }

  /** The {@code index_fingerprint} recorded by a generation's last commit, or "" if it has none. */
  private static String storedFingerprint(Path generation) throws Exception {
    if (!Files.exists(generation)) {
      return "";
    }
    try (Directory directory = FSDirectory.open(generation)) {
      if (!DirectoryReader.indexExists(directory)) {
        return "";
      }
      try (DirectoryReader reader = DirectoryReader.open(directory)) {
        String value = reader.getIndexCommit().getUserData().get(IndexFingerprint.COMMIT_META_KEY);
        return value == null ? "" : value;
      }
    }
  }
}
