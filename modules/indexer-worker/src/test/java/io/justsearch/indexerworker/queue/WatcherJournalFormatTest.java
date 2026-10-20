/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class WatcherJournalFormatTest {
  @TempDir Path temp;

  @Test void populatedLegacyDatabasePreservesReceiptsAndRequiresNewReaderAfterUpgrade() throws Exception {
    Path database = temp.resolve("jobs.db");
    String file = temp.resolve("document.txt").toString();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
         Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE jobs (path TEXT PRIMARY KEY, state TEXT NOT NULL DEFAULT 'PENDING', "
          + "attempts INTEGER NOT NULL DEFAULT 0, last_updated INTEGER NOT NULL, error_message TEXT)");
      sql.execute("CREATE TABLE switch_buffer (key TEXT PRIMARY KEY, op TEXT NOT NULL, "
          + "payload TEXT NOT NULL, last_updated INTEGER NOT NULL)");
      sql.execute("PRAGMA user_version=1");
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO switch_buffer VALUES (?, 'DELETE', ?, 123)")) {
        insert.setString(1, "path:" + file); insert.setString(2, file); insert.executeUpdate();
      }
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO jobs(path,state,attempts,last_updated) VALUES (?, 'PENDING', 2, 123)")) {
        insert.setString(1, file); insert.executeUpdate();
      }
    }
    var explicitNode = JsonMapper.builder().build().createObjectNode();
    explicitNode.put("version", 1).put("path", file).put("origin", "EXPLICIT")
        .putNull("root").putNull("root_identity").putNull("collection");
    String explicit = explicitNode.toString();
    String watcher = JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
        "version", 1, "path", file, "origin", "WATCHER", "root", temp.toString(),
        "root_identity", "persisted-directory-identity", "collection", "docs"));
    String debt = JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
        "version", 1, "root", temp.toString(), "root_identity", "persisted-directory-identity",
        "collection", "docs"));
    List<SwitchBufferCapableQueue.SwitchBufferOp> snapshot;
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
           Statement sql = connection.createStatement(); ResultSet version = sql.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertTrue(version.getInt(1) > 22,
            "old readers must refuse new DELETE/root-reconciliation formats before any database write");
      }
      var legacy = queue.listSwitchBufferOpsStrict().getFirst();
      assertEquals(file, legacy.payload());
      assertEquals("", legacy.generation());
      assertNotNull(legacy.revision());
      assertFalse(legacy.revision().isBlank());
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
           Statement sql = connection.createStatement();
           ResultSet row = sql.executeQuery("SELECT attempts,state FROM jobs")) {
        assertTrue(row.next()); assertEquals(2, row.getInt(1)); assertEquals("PENDING", row.getString(2));
      }
      assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", watcher));
      assertTrue(queue.putSwitchBufferForGeneration("other", "path:" + file, "DELETE", explicit));
      assertTrue(queue.putSwitchBuffer("watcher-reconcile:" + temp, "WATCHER_RECONCILE", debt));
      snapshot = queue.listSwitchBufferOpsStrict();
      assertEquals(List.of(file, watcher, explicit, debt), snapshot.stream().map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
    }
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      assertEquals(snapshot, queue.listSwitchBufferOpsStrict(), "reopen must retain order, generation and exact revisions");
      assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", watcher));
      assertEquals(3, queue.removeReplayedSwitchBufferOps(snapshot), "cleanup must retain the replaced exact version");
      assertEquals(1, queue.listSwitchBufferOpsStrict().size());
    }
  }
  @Test void baselineEnumerationCannotSuppressARecreatedLegacyWatcherFile() throws Exception {
    Path file = java.nio.file.Files.writeString(temp.resolve("recreated.txt"), "replacement content");
    try (var queue = new SqliteJobQueue(temp.resolve("enumeration.db"))) {
      queue.open();
      assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE", file.toString()));
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green",
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temp)), "docs"));
      assertEquals("UPSERT", queue.listSwitchBufferOpsStrict().getFirst().op(),
          "a real source snapshot must replace a stale conditional delete rather than suppress the recreated file");
      assertEquals(file, queue.pollPending(1).getFirst().path());
    }
  }

}
