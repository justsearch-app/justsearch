/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Normal restart must not interrupt maintenance through a shared Lucene file channel. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RecoveryShutdownTest {
  @Test
  void closeDrainsMaintenanceWithoutInterruptingTheServingWriter(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    String activeGeneration = state(root).active_generation();
    Path updated = Files.writeString(root.resolve("roots/alpha/file-00.txt"), "shutdownrecoverycobalt");
    Path deleted = root.resolve("roots/beta/file-01.txt");
    Files.delete(deleted);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    var interrupted = new AtomicBoolean();
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          await(() -> search(server, "shutdownrecoverycobalt").contains(key(updated))
                  && !search(server, "nebula").contains(key(deleted))
                  && !Files.exists(root.resolve("data/upgrade-030-recovery.properties")),
              "real boot recovery settles before controlling maintenance shutdown", 90);
          var runtime = (RunningRuntime) server.lifecycleManagerForTests();
          var maintenance = (ScheduledExecutorService)
              Release030ExtractionRecoveryTest.field(server, "stuckJobReaper");
          var entered = new CountDownLatch(1);
          var release = new CountDownLatch(1);
          var observedInterrupt = new CountDownLatch(1);
          var work = maintenance.submit(() -> {
            runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
            entered.countDown();
            try {
              while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException cancellation) {
                  interrupted.set(true);
                  observedInterrupt.countDown();
                  // Record the unsafe signal before propagating it. Finish this real commit
                  // without poisoning the before-state fixture's shared native lock channel.
                }
              }
              runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
              runtime.commitOps().maybeRefreshBlocking();
              return null;
            } finally {
              if (interrupted.get()) Thread.currentThread().interrupt();
            }
          });
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          try (var closer = Executors.newSingleThreadExecutor()) {
            var closing = closer.submit(() -> { server.close(); return null; });
            try {
              await(maintenance::isShutdown, "server close stops scheduling maintenance", 10);
              assertFalse(closing.isDone(), "close waits for its current writer work");
              observedInterrupt.await(1, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            work.get(20, TimeUnit.SECONDS);
            closing.get(20, TimeUnit.SECONDS);
          } finally { release.countDown(); }
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          assertEquals(activeGeneration, state(root).active_generation());
          assertEquals("IDLE", state(root).migration_state());
          assertNull(state(root).building_generation());
          assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
          assertTrue(search(server, "shutdownrecoverycobalt").contains(key(updated)));
          assertFalse(search(server, "nebula").contains(key(deleted)));
        }
        assertFalse(interrupted.get(), "shutdown must drain maintenance without interrupting the shared writer");
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }
}
