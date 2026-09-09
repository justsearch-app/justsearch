/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.AlreadyClosedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgeServerTerminalWriterBindingTest {

  @BeforeAll
  static void ensureGlobalConfig() {
    if (ConfigStore.globalOrNull() == null) {
      ConfigStore.setGlobal(
          new ConfigStore(ResolvedConfig.builder().contributeEnvRegistry().build()));
    }
  }

  @Test
  void runningRuntimeIsBoundBeforeAppServicesConstruction(@TempDir Path tempDir) throws Exception {
    Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
    AtomicInteger fallbacks = new AtomicInteger();
    Thread.setDefaultUncaughtExceptionHandler(
        (ignoredThread, ignoredFailure) -> fallbacks.incrementAndGet());
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().open();
    KnowledgeServer server =
        new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);
    AtomicInteger reports = new AtomicInteger();
    try {
      server.onTerminalWriterFailure(ignored -> reports.incrementAndGet());
      server.publishIngestLifecycle(runtime);
      RuntimeInternals internals = internals(runtime);
      internals.writer().onTragicEvent(new RuntimeException("startup replay tragedy"), "test");

      internals
          .nrtThread()
          .getUncaughtExceptionHandler()
          .uncaughtException(
              internals.nrtThread(), new AlreadyClosedException("startup NRT observed tragedy"));

      assertEquals(1, reports.get());
      assertEquals(0, fallbacks.get());
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(prior);
      try {
        server.close();
      } catch (Exception ignored) {
        // The deliberately tragic writer makes close diagnostic; resource teardown still runs.
      }
    }
  }

  private static RuntimeInternals internals(RunningRuntime runtime) throws Exception {
    Field sessionField = RunningRuntime.class.getDeclaredField("session");
    sessionField.setAccessible(true);
    Object session = sessionField.get(runtime);
    Field crtrtField = session.getClass().getDeclaredField("crtrt");
    crtrtField.setAccessible(true);
    Field snapshotField = session.getClass().getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(session);
    Method writerMethod = snapshot.getClass().getDeclaredMethod("writer");
    writerMethod.setAccessible(true);
    return new RuntimeInternals(
        (Thread) crtrtField.get(session), (IndexWriter) writerMethod.invoke(snapshot));
  }

  private record RuntimeInternals(Thread nrtThread, IndexWriter writer) {}
}
