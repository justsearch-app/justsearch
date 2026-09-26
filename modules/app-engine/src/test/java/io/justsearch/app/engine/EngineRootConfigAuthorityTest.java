/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SqlitePathResolutionStore;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EngineRootConfigAuthorityTest {
  @TempDir Path directory;

  @Test
  void explicitProcessCompositionRetainsStoreAcrossGlobalReplacement() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore captured = store(90);
    ConfigStore.setGlobal(captured);
    var root = EngineRoot.forProcess(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        1000, 100, ignored -> {}, ManagedChildRegistry.noop(), () -> {},
        OperationAuthority.inMemory(), captured);
    try {
      ConfigStore.setGlobal(store(directory.resolve("replacement-owner"), 365));
      var factoryField = EngineRoot.class.getDeclaredField("serverFactory");
      factoryField.setAccessible(true);
      var factory = (EngineRoot.ServerFactory) factoryField.get(root);
      try (var schema = new SqliteJobQueue(directory.resolve("jobs.db"))) {
        schema.open();
        try (var paths = new SqlitePathResolutionStore(directory.resolve("jobs.db"));
            var server = factory.create(new GpuSchedulingGauge(), root.executors(),
                RecordedIngestionLifecycle.denied(), null, null)) {
          var startupField = KnowledgeServer.class.getDeclaredField("startupConfiguration");
          startupField.setAccessible(true);
          assertSame(captured.get(), startupField.get(server), "startup and hot reads share store A");
          var workerField = KnowledgeServer.class.getDeclaredField("config");
          workerField.setAccessible(true);
          assertEquals(directory,
              ((io.justsearch.indexerworker.WorkerConfig) workerField.get(server)).dataDir());
          inject(server, "jobQueue", mock(JobQueue.class));
          inject(server, "pathResolutionStore", paths);
          long now = TimeUnit.DAYS.toMillis(200);
          paths.record("old", "/old", 1);
          paths.markRemoved("old", now - TimeUnit.DAYS.toMillis(20));
          var cleanup = KnowledgeServer.class.getDeclaredMethod("runPeriodicCleanup", long.class);
          cleanup.setAccessible(true);
          cleanup.invoke(server, now);
          assertTrue(paths.lookup("old").isPresent());
          captured.update(store(5).get());
          cleanup.invoke(server, now);
          assertTrue(paths.lookup("old").isEmpty(), "actual factory must retain current store A, not global B");
        }
      }
    } finally {
      root.close();
      root.processResources().close();
      restoreGlobal(previous);
    }
  }

  @Test
  void embeddedRootCanStillBeConstructedBeforeConfigurationIsInstalled() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    String previousDataDir = System.getProperty("justsearch.data.dir");
    restoreGlobal(null);
    System.setProperty("justsearch.data.dir", directory.toString());
    try {
      var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class), 1000, 100);
      try {
        assertNull(ConfigStore.globalOrNull());
      } finally {
        root.close();
        root.processResources().close();
      }
    } finally {
      if (previousDataDir == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previousDataDir);
      restoreGlobal(previous);
    }
  }

  private ConfigStore store(int days) {
    return store(directory, days);
  }

  private ConfigStore store(Path dataDir, int days) {
    var builder = new ResolvedConfigBuilder();
    builder.putDefault("justsearch.data.dir", dataDir.toString());
    builder.putDefault("justsearch.path_resolution.retention_days", Integer.toString(days));
    return new ConfigStore(builder.build());
  }

  private static void restoreGlobal(ConfigStore previous) {
    ConfigStore current = ConfigStore.globalOrNull();
    if (current != null) ConfigStore.restoreGlobal(current, previous);
    else if (previous != null) ConfigStore.setGlobal(previous);
  }

  private static void inject(KnowledgeServer server, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }
}
