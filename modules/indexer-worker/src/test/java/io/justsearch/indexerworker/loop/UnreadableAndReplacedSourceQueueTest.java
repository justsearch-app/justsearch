/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootRequest;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, S7 and S9, end to end through the entry points the Worker already had:
 * a real scan queues the file, the real durable queue issues the claim, the real batch extractor
 * and admission handle it, and the real queue records what happens next. Only the index side is
 * stubbed, so "the old text leaves the index" is read as a call to the stale-source handler.
 */
final class UnreadableAndReplacedSourceQueueTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final long DAY_MS = 86_400_000L;

  @TempDir Path tempDir;
  private Path dbPath;
  private SqliteJobQueue queue;
  private WorkerIngestService service;

  @BeforeEach
  void setUp() throws Exception {
    dbPath = tempDir.resolve("jobs.db");
    queue = new SqliteJobQueue(dbPath);
    queue.open();
    service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
        IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
  }

  @AfterEach
  void tearDown() throws Exception {
    queue.close();
  }

  // ---- S7: a source that exists but cannot be read ------------------------------------------

  @Test
  void aLockedSourceIsNotRequeuedWithinSeconds() throws Exception {
    assumeTrue(WINDOWS, "share modes are a Windows file-system feature");
    Path root = Files.createDirectory(tempDir.resolve("locked-root"));
    Path file = Files.writeString(root.resolve("open-in-word.docx"), "being edited");
    JobQueue.IndexJob claim = scanAndClaim(root, file);

    long before = System.currentTimeMillis();
    try (FileChannel ignored = openWithoutSharing(file)) {
      harness().extractor().extractAll(List.of(claim));
    }

    Row row = readRow(file);
    assertEquals("PENDING", row.state(), "a locked file stays queued");
    assertEquals(1, row.attempts(), "the retry is counted");
    assertTrue(row.retryAfter() - before >= 60_000L,
        "a locked file must not be re-extracted every second; retry_after was "
            + (row.retryAfter() - before) + " ms away");
  }

  @Test
  void aLockedSourceIsStillRetriedPastTheSevenDayWindow() throws Exception {
    assumeTrue(WINDOWS, "share modes are a Windows file-system feature");
    Path root = Files.createDirectory(tempDir.resolve("long-locked-root"));
    Path file = Files.writeString(root.resolve("always-open.docx"), "held open for weeks");
    JobQueue.IndexJob claim = scanAndClaim(root, file);

    try (FileChannel ignored = openWithoutSharing(file)) {
      harness().extractor().extractAll(List.of(claim));
      // The file has been unreadable for a month: far past the window that ends ordinary retries.
      ageFailureRun(file, System.currentTimeMillis() - 30 * DAY_MS);
      JobQueue.IndexJob again = queue.pollPending(1).getFirst();
      long before = System.currentTimeMillis();
      harness().extractor().extractAll(List.of(again));

      Row row = readRow(file);
      assertEquals("PENDING", row.state(),
          "a file that exists but is locked is never given up on; its indexed text stays");
      assertEquals(2, row.attempts());
      assertTrue(row.retryAfter() - before >= 600_000L,
          "the second retry backs off further, was " + (row.retryAfter() - before) + " ms");
    }
  }

  // ---- S9: a file replaced by a directory or a link -----------------------------------------

  @Test
  void aFileReplacedByADirectoryLeavesTheIndex() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("replaced-root"));
    Path file = Files.writeString(root.resolve("notes.txt"), "old text");
    JobQueue.IndexJob claim = scanAndClaim(root, file);
    Files.delete(file);
    Files.createDirectory(file);

    Harness h = harness();
    h.extractor().extractAll(List.of(claim));

    assertEquals(1, deletions(h.staleSourceHandler()),
        "a directory where the file was is not its source; the old text must not stay searchable");
    assertNotEquals("PENDING", readRow(file).state(), "and it is not retried forever");
  }

  @Test
  void aFileReplacedByADanglingJunctionLeavesTheIndex() throws Exception {
    assumeTrue(WINDOWS, "junctions are a Windows reparse point");
    Path root = Files.createDirectory(tempDir.resolve("junction-root"));
    Path file = Files.writeString(root.resolve("notes.txt"), "old text");
    JobQueue.IndexJob claim = scanAndClaim(root, file);
    Files.delete(file);
    createJunction(file, tempDir.resolve("no-such-target"));
    try {
      Harness h = harness();
      h.extractor().extractAll(List.of(claim));

      assertEquals(1, deletions(h.staleSourceHandler()),
          "a link where the file was is not its source; the old text must not stay searchable");
      assertNotEquals("PENDING", readRow(file).state(), "and it is not retried forever");
    } finally {
      Files.deleteIfExists(file); // removes the junction itself, never a target
    }
  }

  // ---- harness -------------------------------------------------------------------------------

  private record Harness(JobBatchExtractor extractor, StaleSourceHandler staleSourceHandler) {}

  private Harness harness() throws Exception {
    DocumentFieldOps fields = mock(DocumentFieldOps.class);
    when(fields.isUnmodified(anyString(), anyLong(), anyLong())).thenReturn(false);
    IndexCountOps counts = mock(IndexCountOps.class);
    when(counts.docCount()).thenReturn(7L);
    when(counts.docCountOrThrow()).thenReturn(7L);
    TimeboxedContentExtractor content = mock(TimeboxedContentExtractor.class);
    when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    DocumentIdentityStore identity = mock(DocumentIdentityStore.class);
    when(identity.resolve(anyString(), anyLong()))
        .thenReturn(new DocumentIdentityStore.Identity("hash", "uid", 1L, 1L));
    StaleSourceHandler staleSourceHandler = mock(StaleSourceHandler.class);
    IngestionOutcomeJournal journal = new IngestionOutcomeJournal(queue,
        mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class),
        mockCounter(), () -> false);
    JobBatchExtractor extractor = new JobBatchExtractor(new WorkerIngestionAuthority(),
        journal, queue, content, fields, counts,
        mock(BatchStats.class), mock(StaleSnapshotResolver.class), staleSourceHandler,
        IndexingPacing.unthrottled(), new AtomicBoolean(true), new java.util.HashSet<>(),
        () -> mock(PathResolutionStore.class), () -> identity,
        mock(IndexingDocumentOps.StageRecorder.class), () -> false, ignored -> {}, new FileMutationFence());
    return new Harness(extractor, staleSourceHandler);
  }

  @SuppressWarnings("unchecked")
  private static io.justsearch.telemetry.catalog.CounterMetric<IngestionOutcomeTags> mockCounter() {
    return mock(io.justsearch.telemetry.catalog.CounterMetric.class);
  }

  private JobQueue.IndexJob scanAndClaim(Path root, Path file) throws Exception {
    service.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString())
        .setMode(ScanMode.SCAN_MODE_INITIAL).build(), ignored -> {}, CallContext.none());
    List<JobQueue.IndexJob> claims = queue.pollPending(10);
    assertEquals(1, claims.size(), "the scan queues the one file");
    assertEquals(file, claims.getFirst().path());
    assertEquals(root, claims.getFirst().ingestionRoot());
    return claims.getFirst();
  }

  private static long deletions(StaleSourceHandler handler) {
    return mockingDetails(handler).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("deleteMissingSource"))
        .count();
  }

  private static FileChannel openWithoutSharing(Path file) throws IOException {
    return FileChannel.open(
        file,
        StandardOpenOption.READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
  }

  private static void createJunction(Path link, Path target) throws Exception {
    Process process =
        new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "mklink did not finish");
    assertEquals(0, process.exitValue(), "mklink /J failed: " + output);
  }

  private record Row(String state, int attempts, long retryAfter) {}

  private Row readRow(Path file) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT state, attempts, retry_after FROM jobs WHERE path = ?")) {
      stmt.setString(1, PathNormalizer.normalizePath(file.toAbsolutePath().toString()));
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next(), "no queue row for " + file);
        return new Row(rs.getString(1), rs.getInt(2), rs.getLong(3));
      }
    }
  }

  private void ageFailureRun(Path file, long firstFailedAtMs) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt = conn.prepareStatement(
            "UPDATE jobs SET first_failed_at = ?, retry_after = NULL WHERE path = ?")) {
      stmt.setLong(1, firstFailedAtMs);
      stmt.setString(2, PathNormalizer.normalizePath(file.toAbsolutePath().toString()));
      assertEquals(1, stmt.executeUpdate());
    }
  }
}
