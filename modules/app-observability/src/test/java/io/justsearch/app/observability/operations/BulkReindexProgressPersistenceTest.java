/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.BulkReindexProgress;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationOutcomeView;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BulkReindexProgressPersistenceTest {
  private static final EngineContext DURABLE_CONTEXT = context(EngineContext.Survival.DURABLE);
  private final OperationTestClock clock =
      new OperationTestClock(Instant.parse("2026-09-17T10:15:00Z").toEpochMilli());

  @TempDir Path temp;

  @Test
  void captureBuildAndSettlementSurviveStoreReopensWithExactTargetBytes() throws Exception {
    Path path = temp.resolve("phases.db");
    var target = target("{\"index\": \"primary\", \"roots\": [\"docs\"]}");
    OperationRecord row;
    var capturing = new BulkReindexProgress("g-" + OperationKeys.generate(clock), target,
        BulkReindexProgress.Phase.CAPTURING, null, null);
    // The generation is operation-key-owned; seed the row with that same synthetic UUIDv7 key.
    String key = capturing.generationId().substring(2);
    try (var store = store(path)) {
      row = acceptAndStart(store, key, "core.bulk-reindex", DURABLE_CONTEXT);
      assertTrue(store.checkpointBulkReindex(row.id(), capturing));
      assertEquals(Optional.of(capturing), store.bulkReindexProgress(row.id()));
    }

    var capture = new BulkReindexProgress.Capture(hash("manifest-v1"), 3);
    var building = new BulkReindexProgress(capturing.generationId(), target,
        BulkReindexProgress.Phase.BUILDING, capture, null);
    try (var reopened = store(path)) {
      assertEquals(Optional.of(capturing), reopened.bulkReindexProgress(row.id()));
      assertEquals(0, reopened.find(key).orElseThrow().unitsCompleted());
      assertEquals(0, reopened.find(key).orElseThrow().unitsFailed());
      assertTrue(reopened.checkpointBulkReindex(row.id(), building));
    }

    var settlement = new BulkReindexProgress.Settlement(7, hash("receipt-v7"), 1, 2,
        List.of(new OperationOutcomeView.Gap("unit:failed", "EXTRACTION_FAILED")),
        List.of(new BulkReindexProgress.ProcessingEvent(hash("path"), "rev-1", hash("source"),
            hash("content"), "FAILED", "FAILURE", "EXTRACTION_FAILED", "RETRYABLE")));
    var settled = new BulkReindexProgress(capturing.generationId(), target,
        BulkReindexProgress.Phase.SETTLED, capture, settlement);
    try (var reopened = store(path)) {
      assertEquals(Optional.of(building), reopened.bulkReindexProgress(row.id()));
      assertTrue(reopened.checkpointBulkReindex(row.id(), settled));
    }
    try (var reopened = store(path)) {
      assertEquals(Optional.of(settled), reopened.bulkReindexProgress(row.id()));
      var operation = reopened.find(key).orElseThrow();
      assertEquals(2, operation.unitsCompleted());
      assertEquals(1, operation.unitsFailed());
      assertEquals(settled.cursor(), operation.checkpointCursor());
    }
  }

  @Test
  void refusesWrongGenerationProducerSurvivalAndNonRunningRows() throws Exception {
    Path path = temp.resolve("refusals.db");
    try (var store = store(path)) {
      OperationRecord accepted = accept(store, OperationKeys.generate(clock),
          "core.bulk-reindex", DURABLE_CONTEXT);
      var target = target("{\"roots\":[]}");
      var wrongGeneration = capturing(OperationKeys.generate(clock), target);
      assertFalse(store.checkpointBulkReindex(accepted.id(), wrongGeneration));
      assertFalse(store.checkpointBulkReindex(accepted.id(), capturing(accepted.key(), target)));
      assertTrue(store.start(accepted.id()));
      assertTrue(store.checkpointBulkReindex(accepted.id(), capturing(accepted.key(), target)));

      OperationRecord wrongProducer = acceptAndStart(store, OperationKeys.generate(clock),
          "core.reindex-other", DURABLE_CONTEXT);
      assertFalse(store.checkpointBulkReindex(wrongProducer.id(), capturing(wrongProducer.key(), target)));

      OperationRecord interactive = acceptAndStart(store, OperationKeys.generate(clock),
          "core.bulk-reindex", context(EngineContext.Survival.INTERACTIVE));
      assertFalse(store.checkpointBulkReindex(interactive.id(), capturing(interactive.key(), target)));
    }
  }

  @Test
  void phasesAreSequentialIdempotentAndCannotRebindOrMutateTerminalEvidence() throws Exception {
    Path path = temp.resolve("phase-rules.db");
    try (var store = store(path)) {
      OperationRecord row = acceptAndStart(store, OperationKeys.generate(clock),
          "core.rebuild-index", DURABLE_CONTEXT);
      var firstTarget = target("{\"target\":1}");
      var otherTarget = target("{\"target\":2}");
      var capturing = capturing(row.key(), firstTarget);
      var capture = new BulkReindexProgress.Capture(hash("manifest"), 2);
      var building = new BulkReindexProgress(capturing.generationId(), firstTarget,
          BulkReindexProgress.Phase.BUILDING, capture, null);
      var settlement = new BulkReindexProgress.Settlement(1, hash("receipt"), 0, 0,
          List.of(), List.of());
      var settled = new BulkReindexProgress(capturing.generationId(), firstTarget,
          BulkReindexProgress.Phase.SETTLED, capture, settlement);

      assertFalse(store.checkpointBulkReindex(row.id(), building), "the first persisted phase must be capture");
      assertTrue(store.checkpointBulkReindex(row.id(), capturing));
      assertTrue(store.checkpointBulkReindex(row.id(), capturing), "an exact repeat is idempotent");
      assertFalse(store.checkpointBulkReindex(row.id(), capturing(row.key(), otherTarget)));
      assertFalse(store.checkpointBulkReindex(row.id(), settled), "settlement cannot skip building");
      assertTrue(store.checkpointBulkReindex(row.id(), building));
      assertFalse(store.checkpointBulkReindex(row.id(), capturing), "phase cannot regress");
      assertFalse(store.checkpointBulkReindex(row.id(), new BulkReindexProgress(capturing.generationId(),
          firstTarget, BulkReindexProgress.Phase.BUILDING,
          new BulkReindexProgress.Capture(hash("replacement-manifest"), 2), null)),
          "a captured plan cannot be rebound");
      assertTrue(store.checkpointBulkReindex(row.id(), settled));
      assertFalse(store.checkpointBulkReindex(row.id(), settled(row.key(), firstTarget, capture,
          new BulkReindexProgress.Settlement(2, hash("replacement"), 0, 0, List.of(), List.of()))),
          "settled evidence is immutable");

      assertTrue(store.finish(row.id(), OperationState.COMPLETE,
          new OperationReceipt("SUCCESS", null)).isPresent());
      assertFalse(store.checkpointBulkReindex(row.id(), settled), "terminal rows reject mutation");
      assertEquals(Optional.of(settled), store.bulkReindexProgress(row.id()));
    }
  }

  @Test
  void settlementRetainsAllGapsBeyondTheProcessingHistorySampleLimit() throws Exception {
    Path path = temp.resolve("full-gaps.db");
    try (var store = store(path)) {
      OperationRecord row = acceptAndStart(store, OperationKeys.generate(clock),
          "core.bulk-reindex", DURABLE_CONTEXT);
      var target = target("{\"index\":\"large\"}");
      var capture = new BulkReindexProgress.Capture(hash("large-manifest"), 211);
      assertTrue(store.checkpointBulkReindex(row.id(), capturing(row.key(), target)));
      assertTrue(store.checkpointBulkReindex(row.id(), new BulkReindexProgress("g-" + row.key(), target,
          BulkReindexProgress.Phase.BUILDING, capture, null)));
      List<OperationOutcomeView.Gap> gaps = new ArrayList<>();
      for (int i = 0; i < 205; i++) {
        gaps.add(new OperationOutcomeView.Gap("unit-" + i, "EXTRACTION_FAILED"));
      }
      var settled = new BulkReindexProgress("g-" + row.key(), target,
          BulkReindexProgress.Phase.SETTLED, capture,
          new BulkReindexProgress.Settlement(3, hash("large-receipt"), 205, 0, gaps, List.of()));

      assertTrue(store.checkpointBulkReindex(row.id(), settled));
      var read = store.bulkReindexProgress(row.id()).orElseThrow();
      assertEquals(205, read.settlement().gaps().size());
      assertEquals(gaps, read.settlement().gaps());
      assertEquals(6, store.find(row.key()).orElseThrow().unitsCompleted());
      assertEquals(205, store.find(row.key()).orElseThrow().unitsFailed());
    }
  }

  @Test
  void failedCheckpointTransactionRollsBackEveryOwnedColumn() throws Exception {
    Path path = temp.resolve("rollback.db");
    try (var store = store(path)) {
      OperationRecord row = acceptAndStart(store, OperationKeys.generate(clock),
          "core.bulk-reindex", DURABLE_CONTEXT);
      var target = target("{\"before\":true}");
      var beforeProgress = capturing(row.key(), target);
      assertTrue(store.checkpointBulkReindex(row.id(), beforeProgress));
      List<Object> before = bulkColumns(path, row.id());
      var attempted = new BulkReindexProgress("g-" + row.key(), target,
          BulkReindexProgress.Phase.BUILDING,
          new BulkReindexProgress.Capture(hash("rollback-manifest"), 2), null);

      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER refuse_bulk_progress BEFORE UPDATE ON operations WHEN OLD.id = "
            + row.id() + " BEGIN SELECT RAISE(ABORT, 'injected bulk checkpoint failure'); END");
        OperationStoreException failure = assertThrows(OperationStoreException.class,
            () -> store.checkpointBulkReindex(row.id(), attempted));
        assertEquals(OperationStoreException.Code.STORAGE_FAILED, failure.code());
        assertEquals(before, bulkColumns(path, row.id()));
        statement.execute("DROP TRIGGER refuse_bulk_progress");
      }
      assertEquals(Optional.of(beforeProgress), store.bulkReindexProgress(row.id()));
    }
  }

  @Test
  void inconsistentCountsTargetAndPartialMetadataAreRefusedWithoutRepair() throws Exception {
    Path countsPath = temp.resolve("bad-counts.db");
    try (var store = store(countsPath)) {
      OperationRecord row = settledRow(store, "core.bulk-reindex", DURABLE_CONTEXT);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + countsPath);
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET units_completed = units_completed + 1 WHERE id = " + row.id());
      }
      assertThrows(OperationStoreException.class, () -> store.bulkReindexProgress(row.id()));
      assertEquals(4, scalar(countsPath, "SELECT units_completed FROM operations WHERE id=" + row.id()));
    }

    Path targetPath = temp.resolve("bad-target.db");
    try (var store = store(targetPath)) {
      OperationRecord row = acceptAndStart(store, OperationKeys.generate(clock),
          "core.bulk-reindex", DURABLE_CONTEXT);
      assertTrue(store.checkpointBulkReindex(row.id(), capturing(row.key(), target("{\"target\":1}"))));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + targetPath);
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET target_settings_json = '{\"tampered\":true}' WHERE id = "
            + row.id());
      }
      assertThrows(OperationStoreException.class, () -> store.bulkReindexProgress(row.id()));
      assertEquals("{\"tampered\":true}", stringScalar(targetPath,
          "SELECT target_settings_json FROM operations WHERE id=" + row.id()));
    }

    Path partialPath = temp.resolve("partial.db");
    try (var store = store(partialPath)) {
      OperationRecord row = acceptAndStart(store, OperationKeys.generate(clock),
          "core.bulk-reindex", DURABLE_CONTEXT);
      assertTrue(store.checkpointBulkReindex(row.id(), capturing(row.key(), target("{\"partial\":true}"))));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + partialPath);
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET gaps_json = NULL WHERE id = " + row.id());
      }
      assertThrows(OperationStoreException.class, () -> store.bulkReindexProgress(row.id()));
      assertNull(stringScalar(partialPath,
          "SELECT gaps_json FROM operations WHERE id=" + row.id()));
    }

    Path corruptJsonPath = temp.resolve("corrupt-json.db");
    try (var store = store(corruptJsonPath)) {
      OperationRecord row = settledRow(store, "core.bulk-reindex", DURABLE_CONTEXT);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + corruptJsonPath);
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET processing_history_counts_json = '{broken' WHERE id = "
            + row.id());
      }
      assertThrows(OperationStoreException.class, () -> store.bulkReindexProgress(row.id()));
      assertEquals("{broken", stringScalar(corruptJsonPath,
          "SELECT processing_history_counts_json FROM operations WHERE id=" + row.id()));
    }

    Path cursorPath = temp.resolve("wrong-cursor.db");
    try (var store = store(cursorPath)) {
      OperationRecord row = settledRow(store, "core.bulk-reindex", DURABLE_CONTEXT);
      String mismatchedCursor = "bulk-receipt:5:" + hash("wrong-receipt");
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + cursorPath);
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET checkpoint_cursor = '" + mismatchedCursor
            + "' WHERE id = " + row.id());
      }
      assertThrows(OperationStoreException.class, () -> store.bulkReindexProgress(row.id()));
      assertEquals(mismatchedCursor, stringScalar(cursorPath,
          "SELECT checkpoint_cursor FROM operations WHERE id=" + row.id()));
    }
  }

  @Test
  void missingEvidenceCountDoesNotDefaultToZero() throws Exception {
    Path path = temp.resolve("missing-event-count.db");
    try (var store = store(path)) {
      OperationRecord row = settledRow(store, "core.bulk-reindex", DURABLE_CONTEXT);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET processing_history_counts_json = "
            + "json_remove(processing_history_counts_json, '$.failedEvents') WHERE id = " + row.id());
      }
      assertThrows(OperationStoreException.class, () -> store.bulkReindexProgress(row.id()));
    }
  }

  private OperationRecord settledRow(SqliteOperationStore store, String ref, EngineContext context) {
    OperationRecord row = acceptAndStart(store, OperationKeys.generate(clock), ref, context);
    var target = target("{\"counts\":true}");
    var capture = new BulkReindexProgress.Capture(hash("counts-manifest"), 4);
    assertTrue(store.checkpointBulkReindex(row.id(), capturing(row.key(), target)));
    assertTrue(store.checkpointBulkReindex(row.id(), new BulkReindexProgress("g-" + row.key(), target,
        BulkReindexProgress.Phase.BUILDING, capture, null)));
    assertTrue(store.checkpointBulkReindex(row.id(), new BulkReindexProgress("g-" + row.key(), target,
        BulkReindexProgress.Phase.SETTLED, capture,
        new BulkReindexProgress.Settlement(4, hash("counts-receipt"), 1, 0,
            List.of(new OperationOutcomeView.Gap("unit-failed", "EXTRACTION_FAILED")), List.of()))));
    return row;
  }

  private OperationRecord acceptAndStart(SqliteOperationStore store, String key, String ref,
      EngineContext context) {
    OperationRecord row = accept(store, key, ref, context);
    assertTrue(store.start(row.id()));
    return row;
  }

  private OperationRecord accept(SqliteOperationStore store, String key, String ref,
      EngineContext context) {
    var descriptor = OperationDescriptor.invocation(OperationKind.REINDEX, ref, "{}", false);
    return store.accept(key, descriptor, context, null).record();
  }

  private SqliteOperationStore store(Path path) throws Exception {
    return new SqliteOperationStore(path, clock, ignored -> {});
  }

  private static BulkReindexProgress capturing(String key, IndexTargetSnapshot target) {
    return new BulkReindexProgress("g-" + key, target, BulkReindexProgress.Phase.CAPTURING, null, null);
  }

  private static BulkReindexProgress settled(String key, IndexTargetSnapshot target,
      BulkReindexProgress.Capture capture, BulkReindexProgress.Settlement settlement) {
    return new BulkReindexProgress("g-" + key, target,
        BulkReindexProgress.Phase.SETTLED, capture, settlement);
  }

  private static IndexTargetSnapshot target(String canonicalJson) {
    return new IndexTargetSnapshot(hash(canonicalJson), canonicalJson);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static EngineContext context(EngineContext.Survival survival) {
    return new EngineContext(EngineContext.ClientKind.INTERNAL, "bulk-reindex-test", Optional.empty(),
        Optional.empty(), "system", "SYSTEM_INTERNAL", survival, EngineContext.Urgency.BACKGROUND);
  }

  private static List<Object> bulkColumns(Path path, long id) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.prepareStatement("""
            SELECT phase, building_generation_id, target_settings_json, gaps_json, processing_history_json,
              processing_history_counts_json, checkpoint_cursor, units_completed, units_failed, updated_at
            FROM operations WHERE id = ?
            """)) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        List<Object> values = new ArrayList<>();
        for (int column = 1; column <= 10; column++) values.add(result.getObject(column));
        return values;
      }
    }
  }

  private static long scalar(Path path, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private static String stringScalar(Path path, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getString(1);
    }
  }
}
