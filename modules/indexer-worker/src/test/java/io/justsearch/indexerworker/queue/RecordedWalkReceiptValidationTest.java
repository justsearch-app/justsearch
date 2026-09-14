/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Real SQLite proof for the strict, non-durable sealed-walk receipt projection. */
final class RecordedWalkReceiptValidationTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000701";
  private static final String PLAN = "a".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  @DisplayName("sealed receipt round-trips through the typed view and reopen")
  void sealedReceiptRoundTripsThroughTypedViewAndReopen() throws Exception {
    Path db = temp.resolve("roundtrip.db");
    long revision = sealEmpty(db);
    String raw = string(db, "SELECT receipt_json FROM ingestion_walk_progress WHERE operation_key = '" + KEY + "'");

    try (var queue = open(db)) {
      var receipt = queue.sealedRecordedWalkReceipt(KEY).orElseThrow();
      assertEquals(1, receipt.version(), "receipt version");
      assertEquals(revision, receipt.revision(), "receipt revision matches sealed row");
      assertEquals(sha256(raw), receipt.sha256(), "typed view hashes stored UTF-8 receipt");
      assertEquals(0, receipt.completedUnits(), "historical completed units");
      assertEquals(0, receipt.failedUnits(), "historical failed units");
      assertEquals(0, receipt.currentFailedUnits(), "current failed units");
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, receipt.enumerationOutcome(),
          "enumeration outcome");
    }
    try (var reopened = open(db)) {
      assertEquals(revision, reopened.sealedRecordedWalkReceipt(KEY).orElseThrow().revision(),
          "reopen must read the same immutable sealed receipt");
    }
  }

  @Test
  @DisplayName("receipt hash preserves valid stored whitespace and key order")
  void receiptHashUsesExactStoredUtf8() throws Exception {
    Path db = temp.resolve("raw-utf8.db");
    long revision = sealEmpty(db);
    String alternate = "{\n"
        + "  \"failedPathHashesTruncated\": false,\n"
        + "  \"enumerationOutcome\": \"COMPLETE\",\n"
        + "  \"currentFailedUnits\": 0,\n"
        + "  \"currentSkippedUnits\": 0,\n"
        + "  \"failedUnits\": 0,\n"
        + "  \"completedUnits\": 0,\n"
        + "  \"revision\": " + revision + ",\n"
        + "  \"version\": 1,\n"
        + "  \"failedPathHashes\": []\n"
        + "}";
    replaceReceipt(db, alternate);

    try (var queue = open(db)) {
      var receipt = queue.sealedRecordedWalkReceipt(KEY).orElseThrow();
      assertEquals(sha256(alternate), receipt.sha256(),
          "hash must cover exact stored UTF-8 bytes, including formatting");
      assertFalse(receipt.sha256().equals(sha256(compactReceipt(revision))),
          "canonicalized JSON hashing would lose the stored-byte identity");
    }
  }

  @Test
  @DisplayName("historical failures do not make a repaired current walk fail")
  void historicalFailureDoesNotBecomeCurrentFailure() throws Exception {
    Path db = temp.resolve("historical-failure.db");
    Path file = temp.resolve("historical-failure.txt");
    Files.writeString(file, "retryable then repaired");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var failedClaim = queue.pollPending(1).getFirst();
      assertTrue(queue.markClaimFailed(failedClaim, terminalFailure(), null), "terminal failure fixture");
      assertEquals(1, queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file)).accepted(),
          "deliberate replacement fixture");
      var repairedClaim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(
          repairedClaim, null, "b".repeat(64))), success());
      queue.closeRecordedWalkEnumeration(KEY, walk.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      queue.trySealRecordedWalk(KEY);

      var receipt = queue.sealedRecordedWalkReceipt(KEY).orElseThrow();
      assertEquals(1, receipt.failedUnits(), "historical terminal failure remains counted");
      assertEquals(0, receipt.currentFailedUnits(),
          "the repaired current member is not a current failure verdict");
    }
  }

  @Test
  @DisplayName("missing walks are gaps and present unsealed walks are not ready")
  void missingWalkIsGapAndUnsealedWalkIsEmpty() throws Exception {
    Path db = temp.resolve("empty-observation.db");
    try (var queue = open(db)) {
      assertGap("missing accepted walk is an evidence gap", () -> queue.sealedRecordedWalkReceipt(KEY));
      var opened = queue.beginRecordedWalk(KEY, PLAN, true);
      assertTrue(queue.sealedRecordedWalkReceipt(KEY).isEmpty(), "open walk is not sealed");
      queue.closeRecordedWalkEnumeration(KEY, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertTrue(queue.sealedRecordedWalkReceipt(KEY).isEmpty(),
          "closed but unsealed walk is not a receipt");
      assertNotNull(queue.trySealRecordedWalk(KEY).receiptJson(), "fixture seals the walk");
      assertTrue(queue.sealedRecordedWalkReceipt(KEY).isPresent(), "sealed walk is observable");
    }
  }

  @Test
  @DisplayName("invalid sealed evidence is rejected before read, duplicate seal, or ack")
  void invalidSealedEvidenceCannotBeReadResealedOrAcknowledged() throws Exception {
    long expectedRevision = sealEmpty(temp.resolve("invalid-revision-reference.db"));
    List<Corruption> corruptions = corruptions(expectedRevision);
    for (Corruption corruption : corruptions) {
      Path db = temp.resolve("invalid-" + corruption.label + ".db");
      long revision = sealEmpty(db);
      assertEquals(expectedRevision, revision, corruption.label + ": fixture revision must match");
      if (corruption.rowFailedUnits > 0) updateProgressCounters(db, 0, corruption.rowFailedUnits);
      replaceReceipt(db, corruption.json);
      try (var queue = open(db)) {
        assertGap(corruption.label + "/typed-read",
            () -> queue.sealedRecordedWalkReceipt(KEY));
        assertGap(corruption.label + "/progress-read", () -> queue.recordedWalk(KEY));
        assertGap(corruption.label + "/duplicate-seal", () -> queue.trySealRecordedWalk(KEY));
        assertGap(corruption.label + "/ack", () -> queue.acknowledgeRecordedWalk(KEY, revision));
        assertEquals(0L, longValue(db,
            "SELECT acknowledged_revision FROM ingestion_walk_progress WHERE operation_key = '" + KEY + "'"),
            corruption.label + ": invalid evidence must not advance acknowledgement");
      }
    }
  }

  @Test
  @DisplayName("invalid acknowledged evidence blocks aged recorded job cleanup atomically")
  void invalidAcknowledgedReceiptBlocksAgedJobCleanup() throws Exception {
    Path db = temp.resolve("invalid-aged-jobs.db");
    Path file = temp.resolve("invalid-aged-jobs.txt");
    long revision = createAcknowledgedMember(db, file);
    backdateCleanupCandidates(db, file);
    replaceReceipt(db, "{}");
    long jobsBefore = count(db, "SELECT count(*) FROM jobs");
    long ledgerBefore = count(db, "SELECT count(*) FROM ingestion_ledger");
    long progressBefore = count(db, "SELECT count(*) FROM ingestion_walk_progress");

    try (var queue = open(db)) {
      assertGap("aged recorded job cleanup must validate its candidate receipt",
          () -> queue.cleanupOldJobs(7));
    }
    assertCleanupRowsUnchanged(db, revision, jobsBefore, ledgerBefore, progressBefore,
        "job cleanup rejection");
  }

  @Test
  @DisplayName("invalid acknowledged evidence blocks aged ledger cleanup atomically")
  void invalidAcknowledgedReceiptBlocksAgedLedgerCleanup() throws Exception {
    Path db = temp.resolve("invalid-aged-ledger.db");
    Path file = temp.resolve("invalid-aged-ledger.txt");
    long revision = createAcknowledgedMember(db, file);
    backdateCleanupCandidates(db, file);
    replaceReceipt(db, "{}");
    long jobsBefore = count(db, "SELECT count(*) FROM jobs");
    long ledgerBefore = count(db, "SELECT count(*) FROM ingestion_ledger");
    long progressBefore = count(db, "SELECT count(*) FROM ingestion_walk_progress");

    try (var queue = open(db)) {
      assertGap("aged recorded ledger cleanup must validate its candidate receipt",
          () -> queue.cleanupOldLedgerEvents(7));
    }
    assertCleanupRowsUnchanged(db, revision, jobsBefore, ledgerBefore, progressBefore,
        "ledger cleanup rejection");
  }

  @Test
  @DisplayName("invalid acknowledged evidence blocks progress-only pruning")
  void invalidAcknowledgedReceiptBlocksProgressOnlyPruning() throws Exception {
    Path db = temp.resolve("invalid-aged-progress.db");
    long revision = sealEmpty(db);
    try (var queue = open(db)) {
      var receipt = queue.sealedRecordedWalkReceipt(KEY).orElseThrow();
      assertTrue(queue.acknowledgeRecordedWalk(KEY, receipt.revision()),
          "fixture acknowledgement");
    }
    backdateProgress(db);
    replaceReceipt(db, "{}");
    long progressBefore = count(db, "SELECT count(*) FROM ingestion_walk_progress");

    try (var queue = open(db)) {
      assertGap("progress-only prune must validate its candidate receipt",
          () -> queue.cleanupOldJobs(7));
    }
    assertEquals(progressBefore, count(db, "SELECT count(*) FROM ingestion_walk_progress"),
        "progress-only prune rejection retains the row");
    assertEquals(revision, longValue(db,
        "SELECT acknowledged_revision FROM ingestion_walk_progress WHERE operation_key = '"
            + KEY + "'"), "progress-only prune rejection retains acknowledgement");
  }

  @Test
  @DisplayName("v1 sorted duplicate hashes and the bounded truncation boundary remain readable")
  void versionOneHashCompatibilityAndTruncationBoundary() throws Exception {
    for (int failed : new int[] {2, 100, 101}) {
      Path db = temp.resolve("valid-hashes-" + failed + ".db");
      long revision = sealEmpty(db);
      updateProgressCounters(db, 0, failed);
      // Model an existing v1 receipt: its writer sorts one hash per path without deduplication.
      List<String> hashes = java.util.Collections.nCopies(Math.min(100, failed), "a".repeat(64));
      String receiptJson = json(fields(revision, 0, failed, failed, "COMPLETE", hashes, failed > 100));
      replaceReceipt(db, receiptJson);
      try (var queue = open(db)) {
        var receipt = queue.sealedRecordedWalkReceipt(KEY).orElseThrow();
        assertEquals(failed, receipt.currentFailedUnits(), "current failure count");
        assertEquals(sha256(receiptJson), receipt.sha256(), "exact v1 identity");
        assertTrue(queue.acknowledgeRecordedWalk(KEY, revision), "valid v1 evidence remains acknowledgeable");
      }
    }
  }

  @Test
  @DisplayName("invalid orphan progress rolls back an earlier unrecorded job deletion")
  void invalidOrphanProgressRollsBackEarlierDeletion() throws Exception {
    Path db = temp.resolve("rollback-earlier-delete.db");
    long revision = sealEmpty(db);
    try (var queue = open(db)) {
      assertTrue(queue.acknowledgeRecordedWalk(KEY, revision), "fixture acknowledgement");
      Path file = temp.resolve("ordinary-aged.txt");
      Files.writeString(file, "unrecorded cleanup candidate");
      assertEquals(1, queue.enqueue(List.of(file)));
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(
          claim, null, "b".repeat(64))), success());
    }
    try (var connection = connection(db); var statement = connection.createStatement()) {
      assertEquals(1, statement.executeUpdate("UPDATE jobs SET last_updated = 1"));
    }
    backdateProgress(db);
    replaceReceipt(db, "{}");
    long jobsBefore = count(db, "SELECT count(*) FROM jobs");
    long ledgerBefore = count(db, "SELECT count(*) FROM ingestion_ledger");
    try (var queue = open(db)) {
      assertGap("invalid orphan discovered after the unrecorded DELETE must roll back that DELETE",
          () -> queue.cleanupOldJobs(7));
    }
    assertCleanupRowsUnchanged(db, revision, jobsBefore, ledgerBefore, 1,
        "rollback after an earlier delete");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"completed_units", "failed_units", "revision",
      "enumeration_epoch", "enumeration_closed_at", "sealed_at", "acknowledged_revision"})
  void sqliteRealValuesCannotBeTruncatedIntoValidEvidence(String column) throws Exception {
    Path db = temp.resolve("fractional-row-" + column + ".db");
    long revision = sealEmpty(db);
    long original = longValue(db, "SELECT " + column + " FROM ingestion_walk_progress");
    try (var connection = connection(db);
        var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET " + column + " = ?")) {
      update.setDouble(1, original + 0.5);
      assertEquals(1, update.executeUpdate(), "corrupt exactly one numeric field");
    }
    assertEquals("real", string(db, "SELECT typeof(" + column + ") FROM ingestion_walk_progress"),
        "SQLite affinity permits a REAL value despite the INTEGER declaration");
    try (var queue = open(db)) {
      assertGap(column + "/typed-read", () -> queue.sealedRecordedWalkReceipt(KEY));
      assertGap(column + "/progress-read", () -> queue.recordedWalk(KEY));
      assertGap(column + "/duplicate-seal", () -> queue.trySealRecordedWalk(KEY));
      assertGap(column + "/ack", () -> queue.acknowledgeRecordedWalk(KEY, revision));
    }
    assertEquals("real", string(db, "SELECT typeof(" + column + ") FROM ingestion_walk_progress"),
        "rejection must not repair or overwrite corrupt evidence");
  }

  private List<Corruption> corruptions(long revision) throws Exception {
    List<Corruption> result = new ArrayList<>();
    Map<String, Object> valid = fields(revision, 0, 0, 0, "COMPLETE", List.of(), false);

    Map<String, Object> missing = new LinkedHashMap<>(valid);
    missing.remove("version");
    result.add(new Corruption("missing-field", json(missing)));

    Map<String, Object> unknown = new LinkedHashMap<>(valid);
    unknown.put("extra", 1);
    result.add(new Corruption("unknown-field", json(unknown)));

    result.add(new Corruption("duplicate-json-field",
        "{\"version\":1,\"revision\":" + revision
            + ",\"completedUnits\":0,\"failedUnits\":0,\"currentFailedUnits\":0"
            + ",\"currentSkippedUnits\":0,\"enumerationOutcome\":\"COMPLETE\""
            + ",\"failedPathHashes\":[],\"failedPathHashesTruncated\":false"
            + ",\"version\":1}"));

    Map<String, Object> wrongType = new LinkedHashMap<>(valid);
    wrongType.put("version", "1");
    result.add(new Corruption("wrong-type", json(wrongType)));

    Map<String, Object> fractional = new LinkedHashMap<>(valid);
    fractional.put("completedUnits", 1.5);
    result.add(new Corruption("fractional-counter", json(fractional)));

    Map<String, Object> version = new LinkedHashMap<>(valid);
    version.put("version", 2);
    result.add(new Corruption("unsupported-version", json(version)));

    Map<String, Object> rowRevision = new LinkedHashMap<>(valid);
    rowRevision.put("revision", revision + 1);
    result.add(new Corruption("row-revision-mismatch", json(rowRevision)));

    Map<String, Object> rowCounter = new LinkedHashMap<>(valid);
    rowCounter.put("completedUnits", 1);
    result.add(new Corruption("row-counter-mismatch", json(rowCounter)));

    Map<String, Object> rowOutcome = new LinkedHashMap<>(valid);
    rowOutcome.put("enumerationOutcome", "FAILED");
    result.add(new Corruption("row-outcome-mismatch", json(rowOutcome)));

    Map<String, Object> currentExceedsHistory = new LinkedHashMap<>(valid);
    currentExceedsHistory.put("currentFailedUnits", 1);
    result.add(new Corruption("current-failed-exceeds-history", json(currentExceedsHistory)));

    Map<String, Object> badHash = fields(revision, 0, 1, 1, "COMPLETE",
        List.of("A".repeat(64)), false);
    result.add(new Corruption("uppercase-hash", json(badHash), 1));

    Map<String, Object> badTruncation = new LinkedHashMap<>(valid);
    badTruncation.put("failedPathHashesTruncated", true);
    result.add(new Corruption("truncation-mismatch", json(badTruncation)));

    Map<String, Object> unsorted = fields(revision, 0, 2, 2, "COMPLETE",
        List.of("b".repeat(64), "a".repeat(64)), false);
    result.add(new Corruption("unsorted-hash", json(unsorted), 2));

    Map<String, Object> negative = new LinkedHashMap<>(valid);
    negative.put("failedUnits", -1);
    result.add(new Corruption("negative-counter", json(negative)));

    Map<String, Object> negativeSkipped = new LinkedHashMap<>(valid);
    negativeSkipped.put("currentSkippedUnits", -1);
    result.add(new Corruption("negative-skipped", json(negativeSkipped)));

    Map<String, Object> overflow = new LinkedHashMap<>(valid);
    overflow.put("completedUnits", new BigInteger("9223372036854775808"));
    result.add(new Corruption("counter-overflow", json(overflow)));

    List<String> oneHundred = new ArrayList<>();
    for (int i = 0; i < 100; i++) oneHundred.add(String.format("%064x", i));
    result.add(new Corruption("truncation-list-size",
        json(fields(revision, 0, 101, 101, "COMPLETE", oneHundred, false)), 101));

    result.add(new Corruption("trailing-json", compactReceipt(revision) + " {}"));
    result.add(new Corruption("non-object", "[]"));
    result.add(new Corruption("malformed", "{not-json"));
    result.add(new Corruption("oversized", compactReceipt(revision) + " ".repeat(16_384)));
    return result;
  }

  private static Map<String, Object> fields(long revision, long completed, long failed,
      long currentFailed, String outcome, List<String> hashes, boolean truncated) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("version", 1);
    values.put("revision", revision);
    values.put("completedUnits", completed);
    values.put("failedUnits", failed);
    values.put("currentFailedUnits", currentFailed);
    values.put("currentSkippedUnits", 0);
    values.put("enumerationOutcome", outcome);
    values.put("failedPathHashes", hashes);
    values.put("failedPathHashesTruncated", truncated);
    return values;
  }

  private static String compactReceipt(long revision) throws Exception {
    return json(fields(revision, 0, 0, 0, "COMPLETE", List.of(), false));
  }

  private static String json(Map<String, Object> values) throws Exception {
    return JSON.writeValueAsString(values);
  }

  private static long sealEmpty(Path db) throws Exception {
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.beginRecordedWalk(KEY, PLAN, true);
      var closed = queue.closeRecordedWalkEnumeration(KEY, 1,
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = queue.trySealRecordedWalk(KEY);
      assertEquals(closed.revision() + 1, sealed.revision(), "fixture seal revision");
      return sealed.revision();
    }
  }

  private static SqliteJobQueue open(Path db) throws Exception {
    var queue = new SqliteJobQueue(db);
    queue.open();
    return queue;
  }

  private static long createAcknowledgedMember(Path db, Path file) throws Exception {
    Files.writeString(file, "aged cleanup fixture");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(
          claim, null, "b".repeat(64))), success());
      queue.closeRecordedWalkEnumeration(KEY, walk.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = queue.trySealRecordedWalk(KEY);
      assertTrue(queue.acknowledgeRecordedWalk(KEY, sealed.revision()),
          "fixture acknowledgement");
      return sealed.revision();
    }
  }

  private static void backdateCleanupCandidates(Path db, Path file) throws Exception {
    long old = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L;
    String path = io.justsearch.indexerworker.util.PathNormalizer.normalizePath(
        file.toAbsolutePath().toString());
    try (Connection connection = connection(db);
        var job = connection.prepareStatement("UPDATE jobs SET last_updated = ? WHERE path = ?");
        var ledger = connection.prepareStatement(
            "UPDATE ingestion_ledger SET observed_at = ? WHERE operation_key = ?");
        var progress = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET sealed_at = ? WHERE operation_key = ?")) {
      job.setLong(1, old);
      job.setString(2, path);
      assertEquals(1, job.executeUpdate(), "aged recorded job fixture row");
      ledger.setLong(1, old);
      ledger.setString(2, KEY);
      assertEquals(1, ledger.executeUpdate(), "aged recorded ledger fixture row");
      progress.setLong(1, old);
      progress.setString(2, KEY);
      assertEquals(1, progress.executeUpdate(), "aged recorded progress fixture row");
    }
  }

  private static void backdateProgress(Path db) throws Exception {
    long old = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L;
    try (Connection connection = connection(db);
        var progress = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET sealed_at = ? WHERE operation_key = ?")) {
      progress.setLong(1, old);
      progress.setString(2, KEY);
      assertEquals(1, progress.executeUpdate(), "aged progress-only fixture row");
    }
  }

  private static void assertCleanupRowsUnchanged(Path db, long revision, long jobs,
      long ledger, long progress, String label) throws Exception {
    assertEquals(jobs, count(db, "SELECT count(*) FROM jobs"), label + ": jobs unchanged");
    assertEquals(ledger, count(db, "SELECT count(*) FROM ingestion_ledger"),
        label + ": ledger unchanged");
    assertEquals(progress, count(db, "SELECT count(*) FROM ingestion_walk_progress"),
        label + ": progress unchanged");
    assertEquals(revision, longValue(db,
        "SELECT acknowledged_revision FROM ingestion_walk_progress WHERE operation_key = '"
            + KEY + "'"), label + ": acknowledgement unchanged");
  }

  private static void replaceReceipt(Path db, String receipt) throws Exception {
    try (Connection connection = connection(db);
        var update = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET receipt_json = ? WHERE operation_key = ?")) {
      update.setString(1, receipt);
      update.setString(2, KEY);
      assertEquals(1, update.executeUpdate(), "receipt corruption fixture row");
    }
  }

  private static void updateProgressCounters(Path db, long completed, long failed) throws Exception {
    try (Connection connection = connection(db);
        var update = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET completed_units = ?, failed_units = ? WHERE operation_key = ?")) {
      update.setLong(1, completed);
      update.setLong(2, failed);
      update.setString(3, KEY);
      assertEquals(1, update.executeUpdate(), "counter corruption fixture row");
    }
  }

  private static void assertGap(String label, ThrowingAction action) {
    assertThrows(JobQueue.RecordedWalkGapException.class, action::run, label);
  }

  private static String string(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next(), "expected SQL row");
      return rows.getString(1);
    }
  }

  private static long longValue(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next(), "expected SQL row");
      return rows.getLong(1);
    }
  }

  private static long count(Path db, String sql) throws Exception {
    return longValue(db, sql);
  }

  private static Connection connection(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome terminalFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE);
  }

  private record Corruption(String label, String json, long rowFailedUnits) {
    private Corruption(String label, String json) {
      this(label, json, 0);
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    Object run() throws Exception;
  }
}
