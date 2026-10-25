/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The explicit unavailable receiver is service state, not document content. */
final class UnavailableIdentityBatchTest {
  @TempDir Path directory;

  @Test
  void unavailableIdentityReceiverNeverChargesTheBatch() throws Exception {
    Path database = directory.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      var content = mock(TimeboxedContentExtractor.class);
      when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      var writer = mock(IndexingCoordinator.class);
      var loop = new IndexingLoop(null, null, queue, writer, mock(CommitOps.class),
          mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> null,
          mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null,
          content, null, new IndexingLoopOptions(false, null,
              DocumentIdentityStore.UNAVAILABLE, null, null, null));
      var running = IndexingLoop.class.getDeclaredField("running");
      running.setAccessible(true);
      ((AtomicBoolean) running.get(loop)).set(true);
      var process = IndexingLoop.class.getDeclaredMethod("processBatch", List.class);
      process.setAccessible(true);
      var files = List.of(Files.writeString(directory.resolve("first.txt"), "body"),
          Files.writeString(directory.resolve("second.txt"), "body"));
      queue.enqueueEntries(files.stream().map(file -> JobQueue.EnqueueEntry.stat(file)
          .withinRoot(directory)).toList());
      for (int batch = 0; batch < 8; batch++) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var makeDue = connection.prepareStatement(
                "UPDATE jobs SET retry_after = 0 WHERE state = 'PENDING'")) {
          makeDue.executeUpdate();
        }
        var claims = queue.pollPending(10);
        assertEquals(2, claims.size());
        assertDoesNotThrow(() -> process.invoke(loop, claims));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var query = connection.prepareStatement("SELECT attempts, state FROM jobs");
            var rows = query.executeQuery()) {
          int count = 0;
          while (rows.next()) {
            assertEquals(0, rows.getInt(1),
                "the explicit unavailable store must not spend file attempts in batch " + batch);
            assertEquals("PENDING", rows.getString(2));
            count++;
          }
          assertEquals(2, count);
        }
      }
      var extractor = IndexingLoop.class.getDeclaredField("extractor");
      extractor.setAccessible(true);
      assertEquals(8_000, ((JobBatchExtractor) extractor.get(loop)).retryDelayMs());
      verifyNoInteractions(writer);
    }
  }
}
