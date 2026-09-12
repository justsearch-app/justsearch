/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C1-4 proves persisted attribution rather than merely inspecting an in-memory context. */
final class IngestionProvenancePersistenceTest {
  @TempDir Path directory;

  @Test
  void admissionSurvivesRestartMaintenanceAndTerminalWrite() throws Exception {
    Path db = directory.resolve("jobs.db");
    Path document = directory.resolve("document.txt");
    var agent = new JobQueue.EnqueueProvenance("agent", "MCP");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(
          List.of(new JobQueue.EnqueueEntry(document, 12, agent)), "research"));
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(document))));
      assertEquals(1, queue.pollPending(1).size());
      queue.markDone(document, success(), null);
    }
    assertAttribution(db, "jobs", "agent", "MCP");
    assertAttribution(db, "ingestion_ledger", "agent", "MCP");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(document, 12,
          new JobQueue.EnqueueProvenance("user", "BUTTON")))));
    }
    assertAttribution(db, "jobs", "user", "BUTTON");
    assertAttribution(db, "ingestion_ledger", "agent", "MCP");
  }

  @Test
  void v13MigrationLeavesLegacyRowsUnknownAndAddsBothTables() throws Exception {
    Path db = createV13();
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
    }
    assertAttribution(db, "jobs", null, null);
    assertAttribution(db, "ingestion_ledger", null, null);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var row = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(row.next());
      assertEquals(15, row.getInt(1));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void laterAdmissionCannotRewriteClaimedOrUnknownOrigin(boolean unknown) throws Exception {
      var original = unknown ? null : new JobQueue.EnqueueProvenance("agent", "MCP");
      Path db = directory.resolve(original == null ? "unknown-race.db" : "agent-race.db");
      Path document = directory.resolve("same-path.txt");
      try (var queue = new SqliteJobQueue(db)) {
        queue.open();
        queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(document, 12, original)), "original");
        JobQueue.IndexJob claimed = queue.pollPending(1).getFirst();
        assertEquals(original, claimed.provenance());
        queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(document, 12,
            new JobQueue.EnqueueProvenance("user", "BUTTON"))), "later");
        queue.markDone(document, success(),
            io.justsearch.indexerworker.loop.LedgerEntryFactory.forPathOnly(
                claimed.path(), claimed.collection(), claimed.provenance()));
      }
      assertAttribution(db, "jobs", "user", "BUTTON");
      assertAttribution(db, "ingestion_ledger", original == null ? null : "agent",
          original == null ? null : "MCP");
  }

  @Test
  void failedV14MigrationRollsBackEveryColumnAndVersion() throws Exception {
    Path db = createV13();
    try (var queue = new SqliteJobQueue(db, 3, null, version -> {
      if (version == 14) throw new SQLException("injected V14 failure");
    })) {
      assertThrows(SQLException.class, queue::open);
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      for (String table : List.of("jobs", "ingestion_ledger")) {
        try (var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
          while (rows.next()) {
            assertNotEquals("originator", rows.getString("name"));
            assertNotEquals("transport", rows.getString("name"));
          }
        }
      }
      try (var row = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(row.next());
        assertEquals(13, row.getInt(1));
      }
    }
  }

  @Test
  void switchingPayloadSurvivesRestartWithCollectionAndReadsLegacyPaths() throws Exception {
    Path db = directory.resolve("switch.db");
    Path document = directory.resolve("buffered.txt");
    var payload = new SwitchBufferUpsert(document.toString(), "research",
        new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP"));
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.putSwitchBuffer("path:" + document, "UPSERT", payload.encode()));
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var restored = SwitchBufferUpsert.decode(queue.listSwitchBufferOps().getFirst().payload());
      assertEquals(payload, restored);
      io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, null, null, null, directory, directory, tools.jackson.databind.json.JsonMapper.builder().build(),
              () -> false, org.slf4j.LoggerFactory.getLogger(getClass())));
      assertEquals(0, queue.switchBufferDepth(), "successful production replay clears its durable buffer");
      assertEquals("research", queue.pollPending(1).getFirst().collection());
      queue.markDone(document, success(), null);
    }
    assertAttribution(db, "ingestion_ledger", "agent", "AGENT_LOOP");
    var legacy = SwitchBufferUpsert.decode(document.toString());
    assertNull(legacy.provenance());
    assertNull(legacy.collection());
    assertEquals(document, legacy.entry().path());
    assertThrows(IllegalArgumentException.class,
        () -> SwitchBufferUpsert.decode("{\"version\":2,\"path\":\"/future\"}"));
    assertThrows(IllegalArgumentException.class,
        () -> SwitchBufferUpsert.decode("{\"version\":1,\"originator\":\"agent\"}"));
  }

  @Test
  void malformedVersionedUpsertsRemainBufferedInsteadOfLosingAttribution() throws Exception {
    Path document = directory.resolve("malformed.txt");
    var valid = new SwitchBufferUpsert(document.toString(), null,
        new JobQueue.EnqueueProvenance("agent", "MCP"));
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    int mutation = 0;
    for (String field : List.of("version", "originator", "transport", "collection")) {
      var node = (tools.jackson.databind.node.ObjectNode) mapper.readTree(valid.encode());
      if (field.equals("version")) node.put(field, 4294967297L);
      else node.remove(field);
      try (var queue = new SqliteJobQueue(directory.resolve("malformed-" + mutation++ + ".db"))) {
        queue.open();
        queue.putSwitchBuffer("path:" + document, "UPSERT", mapper.writeValueAsString(node));
        io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
            new io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.DrainSwitchBufferContext(
                queue, null, null, null, directory, directory, mapper,
                () -> false, org.slf4j.LoggerFactory.getLogger(getClass())));
        assertEquals(1, queue.switchBufferDepth(), "malformed " + field + " must remain durable");
        assertTrue(queue.pollPending(1).isEmpty(), "malformed " + field + " must not be admitted");
      }
    }
  }

  private Path createV13() throws Exception {
    Path db = directory.resolve("v13.db");
    Path document = directory.resolve("legacy.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(List.of(document));
      queue.markDone(document, success(), null);
    }
    // Derive the complete V13 schema, including its already-normalized privacy-safe ledger.
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      for (String table : List.of("jobs", "ingestion_ledger")) {
        statement.execute("ALTER TABLE " + table + " DROP COLUMN originator");
        statement.execute("ALTER TABLE " + table + " DROP COLUMN transport");
      }
      statement.execute("PRAGMA user_version = 13");
    }
    return db;
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
        IngestionRetryPolicy.NONE, "Indexed");
  }

  private static void assertAttribution(Path db, String table, String originator, String transport)
      throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT originator, transport FROM " + table)) {
      assertTrue(row.next());
      assertEquals(originator, row.getString("originator"));
      assertEquals(transport, row.getString("transport"));
      assertFalse(row.next());
    }
  }
}
