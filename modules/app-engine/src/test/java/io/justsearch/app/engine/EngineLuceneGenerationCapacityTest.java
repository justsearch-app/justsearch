/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineLuceneGenerationCapacityTest {
  private static final String COMMIT_TIMER = "head.lucene.commit-timer";

  @TempDir Path tempDir;

  @Test
  void productionBundleCapsActualRuntimeGenerationsAndReusesOnlyAfterActualExit()
      throws Exception {
    var callbackStarted = new CountDownLatch(1);
    var callbackInterrupted = new CountDownLatch(1);
    var releaseCallback = new CountDownLatch(1);
    var closeCompleted = new AtomicBoolean();
    var closeFailure = new AtomicReference<Throwable>();
    var callbackReason = new AtomicReference<CommitReason>();
    var runtimes = new ArrayList<RunningRuntime>();
    Thread closer = null;

    var schema =
        IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(4), () -> Map.of(), metadata -> {});
    var config =
        new ResolvedConfigBuilder()
            .put(
                "index.commit.timer_interval_ms",
                500,
                "jvm_arg",
                "index.commit.timer_interval_ms",
                "10")
            .build();

    try (var registry = new DefaultEngineExecutorRegistry();
        var luceneExecutors = new LuceneExecutorRegistrations(registry)) {
      try {
        RunningRuntime previous =
            openRuntime(schema, config, luceneExecutors, tempDir.resolve("previous"));
        runtimes.add(previous);
        RunningRuntime active =
            openRuntime(schema, config, luceneExecutors, tempDir.resolve("active"));
        runtimes.add(active);
        RunningRuntime building =
            openRuntime(schema, config, luceneExecutors, tempDir.resolve("building"));
        runtimes.add(building);
        Path fourthPath = tempDir.resolve("fourth");

        assertEquals(3, commitTimerRow(registry).liveInstances());
        assertRuntimeGenerationRefused(schema, config, luceneExecutors, fourthPath);

        previous
            .commitOps()
            .setCommitCompletedListener(
                reason -> {
                  callbackReason.set(reason);
                  callbackStarted.countDown();
                  boolean interrupted = false;
                  while (releaseCallback.getCount() != 0) {
                    try {
                      releaseCallback.await();
                    } catch (InterruptedException ignored) {
                      interrupted = true;
                      callbackInterrupted.countDown();
                    }
                  }
                  if (interrupted) Thread.currentThread().interrupt();
                });
        previous
            .indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID, "previous-doc",
                        SchemaFields.DOC_UID, "previous-doc#0")));
        assertTrue(
            callbackStarted.await(5, TimeUnit.SECONDS),
            "the actual runtime commit timer must enter its completed-commit callback");
        assertEquals(CommitReason.TIMER, callbackReason.get());

        closer =
            new Thread(
                () -> {
                  try {
                    previous.close();
                  } catch (Throwable failure) {
                    closeFailure.set(failure);
                  } finally {
                    closeCompleted.set(true);
                  }
                },
                "lucene-previous-generation-close");
        closer.start();
        awaitShutdownInstance(registry);
        closer.interrupt();

        assertTrue(
            callbackInterrupted.await(1, TimeUnit.SECONDS),
            "runtime close must interrupt the outstanding commit callback");
        closer.join(100);
        assertTrue(closer.isAlive(), "runtime close must still await the callback's actual exit");
        assertFalse(closeCompleted.get(), "shutdown alone must not release the generation slot");
        assertEquals(3, commitTimerRow(registry).liveInstances());
        assertRuntimeGenerationRefused(schema, config, luceneExecutors, fourthPath);

        releaseCallback.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(closer.isAlive(), "runtime close must finish after the callback exits");
        assertNull(closeFailure.get(), "the runtime must close cleanly");

        RunningRuntime replacement =
            openRuntime(schema, config, luceneExecutors, fourthPath);
        runtimes.add(replacement);
        assertEquals(3, commitTimerRow(registry).liveInstances());
      } finally {
        releaseCallback.countDown();
        if (closer != null) {
          closer.interrupt();
          closer.join(TimeUnit.SECONDS.toMillis(5));
        }
        for (int i = runtimes.size() - 1; i >= 0; i--) {
          runtimes.get(i).close();
        }
      }
    }
  }

  private static RunningRuntime openRuntime(
      IndexSchema schema,
      ResolvedConfig config,
      LuceneExecutorRegistrations registrations,
      Path path) {
    return schema.atPath(path).withConfig(config).withExecutorRegistrations(registrations).open();
  }

  private static EngineExecutorSnapshot.Registration commitTimerRow(
      DefaultEngineExecutorRegistry registry) {
    return registry.snapshot().registrations().stream()
        .filter(row -> row.spec().name().equals(COMMIT_TIMER))
        .findFirst()
        .orElseThrow();
  }

  private static void awaitShutdownInstance(DefaultEngineExecutorRegistry registry)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (commitTimerRow(registry).shutdownInstances() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(
        1,
        commitTimerRow(registry).shutdownInstances(),
        "the closing runtime must shut down its concrete timer before awaiting termination");
  }

  private static void assertRuntimeGenerationRefused(
      IndexSchema schema,
      ResolvedConfig config,
      LuceneExecutorRegistrations registrations,
      Path path) {
    RunningRuntime unexpected = null;
    try {
      unexpected = openRuntime(schema, config, registrations, path);
      fail("a fourth live Lucene runtime generation must be refused");
    } catch (EngineExecutorRejectedException failure) {
      assertEquals(EngineExecutorRejectedException.Reason.INSTANCE_LIMIT, failure.reason());
    } finally {
      if (unexpected != null) unexpected.close();
    }
  }
}
