/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SqlitePathResolutionStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgeServerPathRetentionTest {
  @TempDir Path dataDir;

  @Test
  void dailyCleanupUsesCapturedStoresCurrentRetentionAndPreservesLiveAndRecentPaths() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore captured = new ConfigStore(config(90));
    ConfigStore.setGlobal(captured);
    long now = TimeUnit.DAYS.toMillis(200);
    var reads = new AtomicInteger();
    var queue = mock(JobQueue.class);
    try (var executors = new TestEngineExecutors();
        var schema = new SqliteJobQueue(dataDir.resolve("jobs.db"))) {
      schema.open();
      try (var paths = new SqlitePathResolutionStore(dataDir.resolve("jobs.db"));
          var server = server(executors, captured.get(), () -> {
            reads.incrementAndGet();
            return captured.get();
          })) {
        inject(server, "jobQueue", queue);
        inject(server, "pathResolutionStore", paths);
        recordRemoved(paths, "old", now - TimeUnit.DAYS.toMillis(20));
        recordRemoved(paths, "boundary", now - TimeUnit.DAYS.toMillis(5));
        recordRemoved(paths, "recent", now - TimeUnit.DAYS.toMillis(4));
        paths.record("live", "/live", 1L);

        server.runPeriodicCleanup(now);
        assertTrue(paths.lookup("old").isPresent());
        assertEquals(1, reads.get());

        ConfigStore.setGlobal(new ConfigStore(config(365)));
        captured.update(config(5));
        server.runPeriodicCleanup(now);
        assertEquals(2, reads.get(), "one immutable snapshot per cleanup");
        assertTrue(paths.lookup("old").isEmpty());
        assertTrue(paths.lookup("boundary").isPresent(), "cutoff is strictly older than retention");
        assertTrue(paths.lookup("recent").isPresent());
        assertTrue(paths.lookup("live").isPresent(), "present paths never expire");
        verify(queue, times(2)).cleanupOldJobs(30);
        verify(queue, times(2)).cleanupOldLedgerEvents(180);
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void explicitStartupSnapshotStaysTheLegacyCleanupAuthority() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(config(1)));
    long now = TimeUnit.DAYS.toMillis(200);
    try (var executors = new TestEngineExecutors();
        var schema = new SqliteJobQueue(dataDir.resolve("jobs.db"))) {
      schema.open();
      try (var paths = new SqlitePathResolutionStore(dataDir.resolve("jobs.db"));
          var server = server(executors, config(90), null)) {
        inject(server, "jobQueue", mock(JobQueue.class));
        inject(server, "pathResolutionStore", paths);
        recordRemoved(paths, "old", now - TimeUnit.DAYS.toMillis(20));
        server.runPeriodicCleanup(now);
        assertTrue(paths.lookup("old").isPresent(), "legacy snapshot must not read replacement global");
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void recordedReceiptGapDoesNotPreventIndependentPathHistoryCleanup() throws Exception {
    long now = TimeUnit.DAYS.toMillis(200);
    var queue = mock(JobQueue.class);
    when(queue.cleanupOldJobs(30)).thenThrow(new JobQueue.RecordedWalkGapException("missing receipt"));
    when(queue.cleanupOldLedgerEvents(180)).thenThrow(new JobQueue.RecordedWalkGapException("missing receipt"));
    try (var executors = new TestEngineExecutors();
        var schema = new SqliteJobQueue(dataDir.resolve("jobs.db"))) {
      schema.open();
      try (var paths = new SqlitePathResolutionStore(dataDir.resolve("jobs.db"));
          var server = server(executors, config(5), null)) {
        inject(server, "jobQueue", queue);
        inject(server, "pathResolutionStore", paths);
        recordRemoved(paths, "old", now - TimeUnit.DAYS.toMillis(20));
        recordRemoved(paths, "recent", now - TimeUnit.DAYS.toMillis(4));
        paths.record("live", "/live", 1L);
        assertTrue(paths.lookup("old").isPresent(), "the expired row must exist before pruning");
        assertDoesNotThrow(() -> server.runPeriodicCleanup(now));
        assertTrue(paths.lookup("old").isEmpty());
        assertTrue(paths.lookup("recent").isPresent());
        assertTrue(paths.lookup("live").isPresent());
        verify(queue).cleanupOldJobs(30);
        verify(queue).cleanupOldLedgerEvents(180);
      }
    }
  }

  private KnowledgeServer server(TestEngineExecutors executors, ResolvedConfig snapshot,
      java.util.function.Supplier<ResolvedConfig> live) {
    var worker = new WorkerConfig(dataDir, 1000, "test", Map.of(), "test", 500);
    if (live == null) {
      return new KnowledgeServer(executors, worker, null, ManagedChildRegistry.noop(),
          RecordedIngestionLifecycle.denied(), null, null, snapshot);
    }
    return new KnowledgeServer(executors, worker, null, ManagedChildRegistry.noop(),
        RecordedIngestionLifecycle.denied(), null, null, snapshot, live);
  }

  private ResolvedConfig config(int days) {
    return TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.data.dir", dataDir.toString(),
        "justsearch.path_resolution.retention_days", Integer.toString(days)));
  }

  private static void recordRemoved(SqlitePathResolutionStore paths, String key, long when) {
    paths.record(key, "/" + key, 1L);
    paths.markRemoved(key, when);
  }

  private static void inject(KnowledgeServer server, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }
}
