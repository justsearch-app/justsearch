/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecodingLossJournalTest {
  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private Path dbPath;

  @BeforeEach void open() throws Exception {
    dbPath = tempDir.resolve("queue.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
  }
  @AfterEach void close() throws Exception { jobQueue.close(); }

  @Test
  void mixedPartialReasonsSurviveIndividualRetryAndReopen() throws Exception {
    var journal = decodingJournal();
    var claims = enqueuePartialReasons();
    enqueuePartialTransitions(journal, claims);
    // Fail one member of a two-document group: the batch rolls back, then its neighbour succeeds.
    String rejectedHash = FileFreshnessSnapshot.capture(tempDir.resolve("partial-0.txt")).pathHash();
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TRIGGER reject_loss BEFORE INSERT ON ingestion_ledger "
          + "WHEN NEW.path_hash = '" + rejectedHash + "' BEGIN SELECT RAISE(ABORT, 'injected rollback'); END");
    }
    journal.drainPending();
    assertEquals(1, jobQueue.queueDepth());
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TRIGGER reject_loss");
    }
    journal.drainPending();
    assertEquals(0, jobQueue.queueDepth());
    jobQueue.close();
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
    assertPartialRollups();
  }

  @Test
  void mixedPartialReasonsSurviveAbandonedClaimRecovery() throws Exception {
    enqueuePartialReasons();
    jobQueue.close();
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
    assertEquals(4, jobQueue.recoverStuckJobs());
    var journal = decodingJournal();
    enqueuePartialTransitions(journal, jobQueue.pollPending(4));
    journal.drainPending();
    assertPartialRollups();
  }

  private IngestionOutcomeJournal decodingJournal() {
    return new IngestionOutcomeJournal(jobQueue,
        org.mockito.Mockito.mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class),
        org.mockito.Mockito.mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
  }

  private List<JobQueue.IndexJob> enqueuePartialReasons() throws Exception {
    var entries = new java.util.ArrayList<JobQueue.EnqueueEntry>();
    for (int i = 0; i < 4; i++) {
      Path file = Files.writeString(tempDir.resolve("partial-" + i + ".txt"), "valid words");
      entries.add(JobQueue.EnqueueEntry.stat(file).withinRoot(tempDir));
    }
    jobQueue.enqueueEntries(entries);
    return jobQueue.pollPending(4);
  }

  private void enqueuePartialTransitions(IngestionOutcomeJournal journal,
      List<JobQueue.IndexJob> claims) throws Exception {
    for (var claim : claims) {
      int i = Integer.parseInt(claim.path().getFileName().toString().substring(8, 9));
      var policy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
      var artifact = new io.justsearch.indexerworker.extract.ExtractionArtifact(
          io.justsearch.indexerworker.extract.ExtractionStatus.SUCCESS_PARTIAL,
          new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult("valid words", null, "text/plain"),
          policy.policyId(), "test", i >= 2, i == 3 ? List.of() : List.of("TEXT_LOST_WHILE_DECODING"))
          .validate(policy, "loss-" + i);
      var envelope = FileEnvelope.fromSnapshot(FileFreshnessSnapshot.capture(claim.path()));
      var entry = LedgerEntryFactory.forEnvelope(envelope, null, artifact, policy);
      journal.enqueueTransition(new JobQueue.IngestionLedgerTransition(claim, entry, null));
    }
  }

  private void assertPartialRollups() {
    var rows = jobQueue.ingestionOutcomeSummary(0);
    assertEquals(3, rows.size());
    var counts = new java.util.HashMap<String, Long>();
    for (var row : rows) {
      assertEquals("SUCCESS_PARTIAL", row.outcomeClass());
      assertEquals("NONE", row.retryPolicy());
      counts.put(row.reasonCode(), row.count());
    }
    assertEquals(java.util.Map.of("TEXT_LOST_WHILE_DECODING", 2L,
        "TEXT_LOST_WHILE_DECODING_AND_TRUNCATED", 1L, "SUCCESS_PARTIAL", 1L), counts);
    for (var event : jobQueue.recentIngestionEvents(10)) {
      if (event.reasonCode().equals("TEXT_LOST_WHILE_DECODING")) {
        assertFalse(event.diagnosticSummary().contains("truncated"));
      }
    }
  }

}
