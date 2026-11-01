/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class ReleasedRevisionWatcherAdmissionTest {
  @TempDir Path temporary;

  @Test
  void failedAdmissionHashCannotCertifyUnchangedReleasedFailure() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("source.txt"), "unchanged metadata");
    Path db = temporary.resolve("failed-hash.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs");
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE jobs SET state='FAILED',attempts=5,"
            + "unit_revision='release030/job/123/abc',planned_source_sha256=NULL,"
            + "content_hash=NULL");
        if (row(db).containsKey("admitted_source_sha256")) {
          statement.executeUpdate("UPDATE jobs SET admitted_source_sha256=NULL");
        }
      }
      var before = row(db);
      var metadata = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        hashes.when(() -> SourceContentHash.admissionIdentity(file, root)).thenReturn(null);
        // The watcher uses maintenance admission; isolate it from the old watcher's ordinary enqueue.
        queue.enqueueMaintenanceScanEntries(
            List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs", null);
      }
      assertEquals(metadata.size(), Files.size(file));
      assertEquals(metadata.lastModifiedTime(), Files.getLastModifiedTime(file));
      var after = row(db);
      assertEquals("PENDING", after.get("state"),
          "failed hashing supplies no observation that can preserve a legacy terminal failure");
      assertNotEquals(before.get("unit_revision"), after.get("unit_revision"));
      assertEquals(file, queue.pollPending(1).getFirst().path(), "uncertain source must remain claimable");
    }
  }

  @ParameterizedTest
  @CsvSource({"release030/job/123/abc,unchanged", "release030/job/123/abc,size",
      "release030/job/123/abc,hash", "release030/job/123/abc,collection",
      "release030/job/123/abc,root", "root1:receipt:1:abc,size", "root2:receipt:1:abc,size",
      "release030/job/123/abc,mtime", "root1:receipt:1:abc,mtime", "root2:receipt:1:abc,mtime"})
  void watcherRevivesChangedReleasedFailureAfterV25Upgrade(String revision, String change) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("source.txt"), "old!");
    var stamp = Files.getLastModifiedTime(file);
    Path db = temporary.resolve("v25.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs");
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE jobs SET state='FAILED',attempts=5,retry_after=9999999999999,"
          + "unit_revision='" + revision + "',planned_source_sha256="
          + (change.equals("hash") ? "'" + SourceContentHash.sha256(file) + "'" : "NULL"));
      statement.executeUpdate("ALTER TABLE jobs DROP COLUMN force_reindex");
      statement.executeUpdate("ALTER TABLE jobs DROP COLUMN admitted_source_sha256");
      statement.executeUpdate("PRAGMA user_version=25");
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var before = row(db);
      switch (change) {
        case "size" -> Files.writeString(file, "repaired source of a different size");
        case "mtime" -> {
          Files.writeString(file, "new!");
          Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
              System.currentTimeMillis() + 10_000));
        }
        case "hash" -> {
          Files.writeString(file, "new!");
          Files.setLastModifiedTime(file, stamp);
        }
        default -> { }
      }
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          null, null, null, null, null, 0L);
      var registry = new RootWatcherRegistry();
      Path admittedRoot = change.equals("root") ? temporary : root;
      String collection = change.equals("collection") ? "other" : "docs";
      assertTrue(registry.watch(admittedRoot.toString(), collection).watching());
      service.setRootWatcherRegistry(registry);
      service.acceptWatcherUpsert(registry.subscription(admittedRoot), collection, file);
      var after = row(db);
      if (change.equals("unchanged")) {
        assertEquals(before, after, "missing V26 evidence must keep an unchanged released retry run");
      } else {
        assertEquals("PENDING", after.get("state"), "the watcher must revive repaired released work");
        assertNotEquals(revision, after.get("unit_revision"));
        assertEquals("0", after.get("attempts"));
        assertEquals(collection, after.get("collection"));
        assertEquals(admittedRoot.toString(), after.get("ingestion_root"));
      }
    }
  }

  private static Map<String, String> row(Path db) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM jobs")) {
      assertTrue(rows.next());
      var result = new LinkedHashMap<String, String>();
      for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
        result.put(rows.getMetaData().getColumnName(i), rows.getString(i));
      }
      return result;
    }
  }
}
