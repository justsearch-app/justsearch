/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.IndexRootLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(180)
class MigrationJournalDurabilityBootTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @CsvSource({"MIGRATING,missing", "SWITCHING,missing", "AWAITING_ACCEPTANCE,missing", "FAILED,missing",
      "MIGRATING,empty", "SWITCHING,empty", "AWAITING_ACCEPTANCE,empty", "FAILED,empty",
      "MIGRATING,corrupt", "SWITCHING,corrupt", "AWAITING_ACCEPTANCE,corrupt", "FAILED,corrupt",
      "MIGRATING,late", "SWITCHING,late", "AWAITING_ACCEPTANCE,late", "FAILED,late"})
  void unfinishedMigrationRefusesLostJournalAndPreservesEveryQueueFile(String phase, String damage)
      throws Exception {
    var layout = prepared();
    layout.genManager().startMigration("durability-test");
    layout.genManager().updateMigrationState(IndexGenerationManager.MigrationState.valueOf(phase));
    Path database = layout.dataDir().resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database)) { queue.open(); }
    Files.copy(database, layout.dataDir().resolve("jobs.db.bak"));
    Files.writeString(layout.dataDir().resolve("jobs.db.corrupt"), "older quarantine evidence");
    if ("missing".equals(damage)) Files.delete(database);
    if ("empty".equals(damage)) Files.write(database, new byte[0]);
    if ("corrupt".equals(damage)) {
      try (var file = new java.io.RandomAccessFile(database.toFile(), "rw")) {
        file.seek(100L);
        file.write(new byte[512]);
      }
    }
    if ("late".equals(damage)) {
      int page;
      int pageSize;
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE late_corruption (evidence TEXT)");
        statement.execute("INSERT INTO late_corruption VALUES ('retained evidence')");
        try (var result = statement.executeQuery("SELECT rootpage FROM sqlite_master WHERE name = 'late_corruption'")) {
          assertTrue(result.next()); page = result.getInt(1);
        }
        try (var result = statement.executeQuery("PRAGMA page_size")) {
          assertTrue(result.next()); pageSize = result.getInt(1);
        }
      }
      try (var file = new java.io.RandomAccessFile(database.toFile(), "rw")) {
        file.seek((page - 1L) * pageSize);
        file.write(new byte[64]);
      }
      // This unrelated b-tree is read by quick_check after schema initialization, rather than
      // by the schema-version preflight. Even its sidecar evidence must survive both refusals.
      Files.writeString(layout.dataDir().resolve("jobs.db-wal"), "retained WAL evidence");
    }
    var before = snapshot(layout.dataDir());
    for (int refusal = 0; refusal < 2; refusal++) {
      try (var executors = new TestEngineExecutors();
          var server = server(layout, executors)) {
        var failure = assertThrows(IOException.class, server::start);
        assertTrue(causesContain(failure, "index.migration_journal_unavailable"), failure.toString());
        assertNull(server.appServices(), "no search service may serve the old migration view");
      }
      assertEquals(before, snapshot(layout.dataDir()), "refusal must keep all queue evidence intact");
      assertEquals("migration_journal_unavailable",
          Files.readString(layout.dataDir().resolve("worker-fatal-reason")));
    }
  }

  @Test
  void thisVersionsTriageCannotAuthorizeAnEmptyJournalDuringMigration() throws Exception {
    var layout = prepared();
    try (var queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"))) { queue.open(); }
    WorkerBootFixture.seedInFlightGreen(layout, null, 1);
    Files.writeString(layout.dataDir().resolve("jobs.db.triage"),
        "{\"quarantine\":\"jobs.db.corrupt\",\"backup\":false,\"unfinished\":true,\"complete\":true}");
    var before = snapshot(layout.dataDir());
    for (int refusal = 0; refusal < 2; refusal++) {
      try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
        var failure = assertThrows(IOException.class, server::start);
        assertTrue(causesContain(failure, "index.migration_journal_unavailable"));
        assertNull(server.appServices());
      }
      assertEquals(before, snapshot(layout.dataDir()));
    }
  }

  @Test
  void healthyLegacyMigrationWithoutAnAdmissionWitnessStillResumes() throws Exception {
    var layout = prepared();
    try (var queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"))) { queue.open(); }
    WorkerBootFixture.seedInFlightGreen(layout, null, 1);
    assertFalse(Files.exists(layout.indexBase().resolve("migration-journal-admissions")));
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertDoesNotThrow(server::start);
      assertNotNull(server.appServices());
    }
  }

  @Test
  void losingIndexRootLockCannotTriageAnotherOwnersQueue() throws Exception {
    var layout = prepared();
    Path database = layout.dataDir().resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database)) { queue.open(); }
    try (var file = new java.io.RandomAccessFile(database.toFile(), "rw")) {
      file.seek(100L);
      file.write(new byte[512]);
    }
    var before = snapshot(layout.dataDir());
    try (var owner = new IndexRootLock(layout.indexBase());
        var executors = new TestEngineExecutors();
        var server = server(layout, executors)) {
      owner.acquire();
      assertThrows(IOException.class, server::start);
      assertEquals(before, snapshot(layout.dataDir()), "lock refusal must precede queue open and triage");
    }
  }

  @Test
  void restoredValidQueueCannotEraseAcknowledgedBufferAdmission() throws Exception {
    var layout = prepared();
    Path database = layout.dataDir().resolve("jobs.db");
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertDoesNotThrow(server::start);
    }
    Path stale = temporary.resolve("stale-valid-queue");
    Files.copy(database, stale);
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertDoesNotThrow(server::start);
      layout.genManager().startMigration("journal-staleness-test");
      assertTrue(((io.justsearch.indexerworker.queue.SwitchBufferCapableQueue) server.jobQueueForTests()).putSwitchBuffer("deleted-document", "DELETE", "{}"));
    }
    Files.copy(stale, database, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    var before = snapshot(layout.dataDir());
    for (int refusal = 0; refusal < 2; refusal++) {
      try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
        var failure = assertThrows(IOException.class, server::start);
        assertTrue(causesContain(failure, "index.migration_journal_unavailable"));
        assertNull(server.appServices());
      }
      assertEquals(before, snapshot(layout.dataDir()));
    }
  }

  @Test
  void failedExternalWitnessCannotAcknowledgeDirectOrTransactionalAdmission() throws Exception {
    var layout = prepared();
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertDoesNotThrow(server::start);
      Path witness = layout.indexBase().resolve("migration-journal-admissions");
      Files.deleteIfExists(witness);
      Files.createDirectory(witness);
      assertFalse(((io.justsearch.indexerworker.queue.SwitchBufferCapableQueue) server.jobQueueForTests()).putSwitchBuffer("deleted-document", "DELETE", "{}"),
          "an admission whose external witness could not be stored must not be acknowledged");
      var deletion = new io.justsearch.app.api.indexing.AcceptedProjection("durability", "document", 1,
          io.justsearch.app.api.indexing.AcceptedProjection.Kind.DELETE, null);
      assertThrows(IllegalStateException.class, () -> ((io.justsearch.indexerworker.queue.SwitchBufferCapableQueue) server.jobQueueForTests())
          .admitProjectionForGeneration("generation-under-test", deletion.encode()));
    }
  }

  private WorkerBootFixture.Layout prepared() throws Exception {
    var layout = WorkerBootFixture.layout(temporary);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    return layout;
  }

  private static KnowledgeServer server(WorkerBootFixture.Layout layout, TestEngineExecutors executors) {
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(layout.dataDir())));
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    return server;
  }

  private static boolean causesContain(Throwable failure, String expected) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().contains(expected)) return true;
    }
    return false;
  }

  private static Map<String, String> snapshot(Path data) throws Exception {
    var result = new TreeMap<String, String>();
    try (var entries = Files.list(data)) {
      for (Path file : entries.filter(path -> path.getFileName().toString().startsWith("jobs.db")).toList()) {
        result.put(file.getFileName().toString(), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
      }
    }
    return result;
  }
}
