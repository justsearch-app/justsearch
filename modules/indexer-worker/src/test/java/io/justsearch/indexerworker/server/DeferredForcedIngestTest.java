/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import io.justsearch.adapters.lucene.runtime.DeferredRuntime;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.BatchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * An upgrade boots over an existing index, so the Worker opens read-only first and its writer and
 * indexing loop only exist once the background writer upgrade has run. The Head ingests the
 * bundled help in that window with {@code force_reindex}. That submit must succeed, and the force
 * must reach the loop the upgrade creates: the re-ingested file has the same modification time as
 * the indexed copy, so only the force makes the loop extract it again.
 *
 * <p>Real conditions: a real {@link KnowledgeServer} boots twice over one data directory; the
 * second boot is held in its deferred (read-only) state by not starting the background model
 * initialisation, and the writer upgrade is then run as that initialisation runs it.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
final class DeferredForcedIngestTest {
  private static final long CHECK_MS = 60_000L;

  @TempDir Path tempDir;

  @Test
  void forcedSubmitWhileDeferredSucceedsAndReachesTheUpgradedLoop() throws Exception {
    var layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    Path help = Files.writeString(
        Files.createDirectories(tempDir.resolve("help")).resolve("search-syntax.md"),
        "# Search syntax\n\nThe old text mentions an okapi.\n");
    FileTime indexedMtime = Files.getLastModifiedTime(help);
    String id = PathNormalizer.normalizeKey(help);

    // First install: an empty index opens its writer on the synchronous boot path.
    KnowledgeServer first = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    try {
      first.bindBootRootBindings(List.of());
      first.start();
      releaseModelReadiness(first);
      first.appServices().ingestService().submitBatch(forcedHelpBatch(help), CallContext.none());
      String oldHash = SourceContentHash.sha256(help);
      assertTrue(awaitTrue(() -> oldHash.equals(sourceHash(first, id))),
          "precondition: the first install indexes the help file");
    } finally {
      first.close();
    }

    // The upgrade ships new help text; the file keeps the modification time of the indexed copy.
    Files.writeString(help, "# Search syntax\n\nThe new text mentions a quokka.\n");
    Files.setLastModifiedTime(help, indexedMtime);
    String newHash = SourceContentHash.sha256(help);

    KnowledgeServer upgraded = spy(new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir())));
    doNothing().when(upgraded).startDeferredModelInitialization(any());
    try {
      upgraded.bindBootRootBindings(List.of());
      upgraded.start();
      DeferredRuntime deferred = assertInstanceOf(DeferredRuntime.class,
          field(upgraded, "ingestLifecycle"),
          "precondition: a boot over an existing index opens read-only first");

      var accepted = assertDoesNotThrow(() -> upgraded.appServices().ingestService()
              .submitBatch(forcedHelpBatch(help), CallContext.none()),
          "a forced submit while the writer is deferred must succeed");
      assertEquals(1, accepted.getAcceptedCount());

      var upgrade = KnowledgeServer.class.getDeclaredMethod(
          "upgradeDeferredServing", DeferredRuntime.class);
      upgrade.setAccessible(true);
      upgrade.invoke(upgraded, deferred);
      releaseModelReadiness(upgraded);

      assertTrue(awaitTrue(() -> newHash.equals(sourceHash(upgraded, id))),
          "the force submitted while deferred must make the upgraded loop re-extract the file");
    } finally {
      upgraded.close();
    }
  }

  /** The request the Head's help ingest sends: forced, tagged "help", an explicit add. */
  private static BatchRequest forcedHelpBatch(Path help) {
    return BatchRequest.newBuilder()
        .addFilePaths(help.toAbsolutePath().toString())
        .setForceReindex(true)
        .setTargetCollection("help")
        .setRootAdded(true)
        .build();
  }

  private static String sourceHash(KnowledgeServer server, String id) throws Exception {
    return server.lifecycleManagerForTests().documentFieldOps()
        .getDocumentField(id, SchemaFields.SOURCE_SHA256);
  }

  private static void releaseModelReadiness(KnowledgeServer server) throws Exception {
    ((CountDownLatch) field(server, "modelReadyLatch")).countDown();
  }

  private static Object field(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }

  private static boolean awaitTrue(ThrowingCondition condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHECK_MS);
    while (System.nanoTime() < deadline) {
      try {
        if (condition.holds()) return true;
      } catch (RuntimeException notYet) {
        // The index is mid-change; look again.
      }
      Thread.sleep(200L);
    }
    return condition.holds();
  }

  @FunctionalInterface
  private interface ThrowingCondition {
    boolean holds() throws Exception;
  }
}
