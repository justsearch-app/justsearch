/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 915 §C.12 — the schema-mismatch decision must not depend on HOW the index is opened.
 *
 * <p>It used to. The active generation opens {@code openDeferred()} whenever it has segments, and a
 * deferred open reaches {@code ComponentsFactory} as read-only, where a guard failure is logged
 * rather than raised. So on the boot path most installs take — an existing index, with documents,
 * whose shape changed — the automatic blue/green migration never started. The status surface still
 * said {@code reindex_required}, which is why nothing looked broken; the Worker simply never acted.
 *
 * <p>Detection now happens BEFORE the open-mode choice, reading the last commit's user data off the
 * directory. These tests are all boot-level for that reason: every one of them passes at unit level
 * against the broken code, because the defect was never in a unit.
 */
@Timeout(180)
final class PreOpenSchemaMismatchBootTest {

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

  private IndexGenerationManager.State stateAfterBoot(WorkerBootFixture.Layout layout)
      throws IOException {
    return new IndexGenerationManager(layout.indexBase()).initializeOrLoad().state();
  }

  /** (a) The headline case: segments present, shape changed, production policy. */
  @Test
  void aChangedShapeOnAnIndexWithSegmentsStartsTheMigrationAtBoot(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), "f".repeat(64), 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    IndexGenerationManager.State after = stateAfterBoot(layout);
    assertEquals(
        IndexGenerationManager.MigrationState.MIGRATING.name(),
        after.migration_state(),
        "an index whose shape changed must start migrating at boot — this is the whole point of"
            + " making BLUE_GREEN_MIGRATE the production default");
    assertNotNull(after.building_generation(), "a Green generation was allocated");
    assertTrue(server.isRunning(), "and Blue keeps serving while it rebuilds");
  }

  /** (a) The same index under the refusing policy. */
  @Test
  void aChangedShapeOnAnIndexWithSegmentsIsRefusedUnderFailClosed(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), "f".repeat(64), 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    // A started KnowledgeServer keeps background threads logging while the assertions below
    // stream this list, and ListAppender.list is a plain ArrayList: CI caught the
    // ConcurrentModificationException the local runs did not. Snapshot-on-iterate instead.
    appender.list = new java.util.concurrent.CopyOnWriteArrayList<>();
    appender.start();
    root.addAppender(appender);
    try {
      KnowledgeServer refusing =
          new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
      IOException ex = assertThrows(IOException.class, refusing::start);
      assertTrue(
          KnowledgeServer.isSchemaMismatch(ex),
          "FAIL_CLOSED must refuse for the schema-mismatch reason, not some incidental failure");
      assertEquals(
          IndexGenerationManager.MigrationState.IDLE.name(),
          stateAfterBoot(layout).migration_state(),
          "refusing must not have started a migration");
      // And it refused because PRE-OPEN detection routed it, not because the open-time guard caught
      // it on the way past. Without this the assertions above are equally true of the second line of
      // defence — which is exactly what round 3's G25 falsification demonstrated.
      assertTrue(
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .anyMatch(m -> m.startsWith("PRE-OPEN PARITY_DIFF key=index_fingerprint")),
          "the pre-open routing must be what refused; got: "
              + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
      // And the refusal is legible to the Head. Live validation: it arrived as "Worker process
      // crashed (exit code 1) before writing port to signal file" with /api/health unreachable, and
      // the index_fingerprint mismatch existed only in worker.log - because only corruption ever
      // wrote the fatal-reason marker. A deliberate refusal is not a crash.
      assertEquals(
          io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH,
          io.justsearch.ipc.WorkerFatalReasonMarker.readAndClear(layout.dataDir()),
          "the dying worker must say WHY it refused, in the channel the Head reads");
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /**
   * (S15) The dev default, and the one policy the pre-open path deliberately changes the OPEN MODE
   * for: {@code REBUILD_BACKUP_FIRST}'s recovery lives inside {@code
   * RuntimeSession.openComponentsWithRecovery} and only runs on a writable open, so detection forces
   * one instead of duplicating the recovery. That recovery is DESTRUCTIVE — it empties the active
   * generation — so what has to be pinned is the ordering: the backup is taken before a writer ever
   * touches Blue, and it holds Blue's documents.
   */
  @Test
  void rebuildBackupFirstBacksUpTheMismatchedIndexBeforeEmptyingIt(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), "f".repeat(64), 3);
    WorkerBootFixture.publishConfig(
        layout.dataDir(), layout.indexBase(), "REBUILD_BACKUP_FIRST");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    // Review S7: this asserted `isRunning()`, which after item A9 is two booleans start() sets —
    // it no longer means "a socket is accepting", so it reads as a much stronger claim than it
    // makes. `appServices()` is the claim the test actually wants: start() ran to completion and
    // the service surface was built on the rebuilt index. (Its sibling below already asserts both.)
    assertNotNull(
        server.appServices(), "the Worker comes up on the rebuilt index with its services built");

    Path backup = soleSiblingWithSuffix(layout.activePath(), ".bak-");
    assertNotNull(
        backup,
        "the mismatched index must be backed up, never deleted: this policy empties the active"
            + " generation and the backup is the user's only copy");
    assertEquals(
        3, docCount(backup), "and the backup holds Blue as it was, so the copy was taken first");
    assertEquals(
        0, docCount(layout.activePath()), "while the active generation was rebuilt empty");
  }

  /**
   * (S15, second half) A typo in the policy key must not be a boot failure. Pre-open detection
   * forces a writable open for anything it does not recognise, so a value that reaches the Worker
   * verbatim used to raise, be refused by recovery, and take the Worker down. It normalizes to the
   * mode default now, in the config layer, where every consumer sees the same answer.
   */
  @Test
  void anUnrecognisedPolicyFallsBackInsteadOfKillingTheWorker(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), "f".repeat(64), 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "blue_green_migrat");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertTrue(server.isRunning(), "a misspelled policy is a typo, not a reason to refuse to boot");
    assertNotNull(
        server.appServices(), "and start() ran to completion: the service surface is built");
  }

  /**
   * (B4) A corrupt index that used to self-heal at boot. Pre-open inspection sits OUTSIDE {@code
   * RuntimeSession.openComponentsWithRecovery}, where backup-then-empty recovery lives, so raising
   * {@code CORRUPT_INDEX} from it killed the Worker on a case the product recovers from — and since
   * the cause of an unreadable commit is an {@code IndexFormatTooOldException}, it took the
   * legitimate older-Lucene-major upgrade path down with it.
   */
  @Test
  void aCorruptIndexStillSelfHealsAtBoot(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    corruptSegments(layout.activePath());
    WorkerBootFixture.publishConfig(
        layout.dataDir(),
        layout.indexBase(),
        "BLUE_GREEN_MIGRATE",
        java.util.Map.of("index.auto_recovery", "true"));

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    // A started KnowledgeServer keeps background threads logging while the assertions below
    // stream this list, and ListAppender.list is a plain ArrayList: CI caught the
    // ConcurrentModificationException the local runs did not. Snapshot-on-iterate instead.
    appender.list = new java.util.concurrent.CopyOnWriteArrayList<>();
    appender.start();
    root.addAppender(appender);
    try {
      server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
      server.start();

      assertTrue(server.isRunning(), "auto-recovery must still get its chance to run");
      assertNotNull(
          server.appServices(), "a Worker with no service surface is a Worker gone");
      var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertEquals(
          1,
          messages.stream()
              .filter(m -> m.startsWith("Could not read committed parity metadata"))
              .count(),
          "pre-open inspection must DECLINE, loudly, rather than answer 'mismatch' to a question it"
              + " could not read - and say it ONCE: the pre-open check and the open-time guard ask"
              + " the same question of the same bytes, and both used to answer in the log; got: "
              + messages);
      assertNotNull(
          soleSiblingWithSuffix(layout.activePath(), ".bak-"),
          "and the open path's corruption recovery must have run — the damaged index is backed up,"
              + " never deleted");
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /** Overwrites the commit file so the index cannot be read at all. */
  private static void corruptSegments(Path generation) throws IOException {
    try (var files = java.nio.file.Files.list(generation)) {
      Path segments =
          files
              .filter(p -> p.getFileName().toString().startsWith("segments"))
              .findFirst()
              .orElseThrow();
      java.nio.file.Files.write(segments, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
    }
  }

  /** The one sibling directory whose name adds {@code suffix} to {@code dir}'s, or null. */
  private static Path soleSiblingWithSuffix(Path dir, String suffix) throws IOException {
    String prefix = dir.getFileName().toString() + suffix;
    try (var siblings = java.nio.file.Files.list(dir.getParent())) {
      return siblings
          .filter(p -> p.getFileName().toString().startsWith(prefix))
          .findFirst()
          .orElse(null);
    }
  }

  /** Committed document count, or 0 when the directory holds no readable index. */
  private static int docCount(Path generation) throws IOException {
    try (org.apache.lucene.store.Directory d =
        org.apache.lucene.store.FSDirectory.open(generation)) {
      if (!org.apache.lucene.index.DirectoryReader.indexExists(d)) {
        return 0;
      }
      try (var reader = org.apache.lucene.index.DirectoryReader.open(d)) {
        return reader.numDocs();
      }
    }
  }

  /** (b) The negative: a matching index still opens deferred, silently. */
  @Test
  void aMatchingIndexWithSegmentsBootsWithoutMigratingOrWarning(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    // A started KnowledgeServer keeps background threads logging while the assertions below
    // stream this list, and ListAppender.list is a plain ArrayList: CI caught the
    // ConcurrentModificationException the local runs did not. Snapshot-on-iterate instead.
    appender.list = new java.util.concurrent.CopyOnWriteArrayList<>();
    appender.start();
    root.addAppender(appender);
    try {
      server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
      server.start();

      assertEquals(
          IndexGenerationManager.MigrationState.IDLE.name(),
          stateAfterBoot(layout).migration_state(),
          "a matching index must not be migrated — a detector that fires on everything is not a"
              + " detector");
      assertTrue(server.isRunning());
      assertFalse(
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .anyMatch(m -> m.contains("PARITY_DIFF") || m.contains("PRE-OPEN")),
          "and it must do so silently");
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /** (c) The real upgrade path, and the one O7 broke: a legacy index with no fingerprint at all. */
  @Test
  void aLegacyIndexWithSegmentsMigratesAtBoot(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), WorkerBootFixture.NO_FINGERPRINT, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    IndexGenerationManager.State after = stateAfterBoot(layout);
    assertEquals(
        IndexGenerationManager.MigrationState.MIGRATING.name(),
        after.migration_state(),
        "every index built before index_fingerprint existed looks like this — if the upgrade"
            + " rebuild does not start here it starts nowhere");
    assertNotNull(after.building_generation());
  }

  /** (e) The fresh install: an index that exists but holds nothing is not a migration candidate. */
  @Test
  void aFreshEmptyIndexDoesNotMigrateAtBoot(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), WorkerBootFixture.NO_FINGERPRINT, 0);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertEquals(
        IndexGenerationManager.MigrationState.IDLE.name(),
        stateAfterBoot(layout).migration_state(),
        "a first launch must not spend a rebuild on an index with nothing in it (the shared"
            + " ParityDiagnostics predicate, same one the status surface uses)");
    assertNull(stateAfterBoot(layout).building_generation());
  }

  /**
   * (d) The other half of O7: a schema mismatch escaping the deferred writer upgrade must not be
   * filed under the generic background-init WARN. That catch is why the failure was invisible even
   * on the boots where it did surface.
   */
  @Test
  void aSchemaMismatchFromTheDeferredUpgradeIsNotReportedAsNonFatal() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KnowledgeServer.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    // A started KnowledgeServer keeps background threads logging while the assertions below
    // stream this list, and ListAppender.list is a plain ArrayList: CI caught the
    // ConcurrentModificationException the local runs did not. Snapshot-on-iterate instead.
    appender.list = new java.util.concurrent.CopyOnWriteArrayList<>();
    appender.start();
    logger.addAppender(appender);
    try {
      Exception wrapped =
          new IllegalStateException("deferred upgrade failed", IndexMetadataParityGuard.schemaMismatch());
      KnowledgeServer.logBackgroundInitFailure(wrapped);
      KnowledgeServer.logBackgroundInitFailure(new IllegalStateException("ONNX session failed"));

      var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertTrue(
          messages.stream().anyMatch(m -> m.contains("Ingestion is STOPPED")),
          "a stopped ingestion pipeline is not a degraded capability; got: " + messages);
      assertFalse(
          messages.stream()
              .anyMatch(
                  m ->
                      m.contains("non-fatal")
                          && messages.indexOf(m) == 0),
          "the mismatch must not take the non-fatal branch; got: " + messages);
      assertTrue(
          messages.stream().anyMatch(m -> m.contains("non-fatal")),
          "an unrelated background failure still takes the non-fatal branch");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** The classifier the branch above turns on, including the wrapping the upgrade path applies. */
  @Test
  void schemaMismatchIsRecognisedThroughTheCauseChain() {
    assertTrue(KnowledgeServer.isSchemaMismatch(IndexMetadataParityGuard.schemaMismatch()));
    assertTrue(
        KnowledgeServer.isSchemaMismatch(
            new IllegalStateException("wrapped", IndexMetadataParityGuard.schemaMismatch())));
    assertFalse(KnowledgeServer.isSchemaMismatch(new IllegalStateException("unrelated")));
    assertFalse(
        KnowledgeServer.isSchemaMismatch(
            new IndexRuntimeIOException(
                IndexRuntimeIOException.Reason.CORRUPT_INDEX, "corrupt", null)),
        "corruption has its own recovery path and must not be mistaken for a shape change");
  }
}
