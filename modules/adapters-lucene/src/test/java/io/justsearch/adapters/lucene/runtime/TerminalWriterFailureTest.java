/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.AlreadyClosedException;
import org.junit.jupiter.api.Test;

final class TerminalWriterFailureTest extends LuceneExecutorTestBase {

  @Test
  void tragicWriterReportsOnceAfterMutationReleasesItsBarrier() throws Exception {
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      AtomicInteger reports = new AtomicInteger();
      AtomicBoolean writeBarrierReleased = new AtomicBoolean();
      runtime.onTerminalWriterFailure(
          ignored -> {
            boolean acquired = runtime.session().writeBarrier.writeLock().tryLock();
            writeBarrierReleased.set(acquired);
            if (acquired) {
              runtime.session().writeBarrier.writeLock().unlock();
            }
            reports.incrementAndGet();
          });
      RuntimeException tragedy = new RuntimeException("forced writer tragedy");
      runtime.session().snapshot.writer().onTragicEvent(tragedy, "test");

      runtime.indexingCoordinator().indexSingle(document("tragic"));

      assertEquals(1, reports.get(), "the production mutation path must report the fault");
      assertTrue(writeBarrierReleased.get(), "the callback must run after the write barrier unlocks");
      runtime.session().reportTerminalWriterFailureIfPresent();

      assertEquals(1, reports.get(), "one terminal runtime must emit one fault");
    } finally {
      assertThrows(RuntimeException.class, runtime::close);
    }
  }

  @Test
  void nestedMutationDefersFaultUntilTheOutermostBarrierIsReleased() throws Exception {
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      AtomicInteger reports = new AtomicInteger();
      runtime.onTerminalWriterFailure(ignored -> reports.incrementAndGet());
      runtime.session().writeBarrier.readLock().lock();
      try {
        runtime
            .session()
            .snapshot
            .writer()
            .onTragicEvent(new RuntimeException("forced nested tragedy"), "test");

        runtime.indexingCoordinator().indexSingle(document("nested"));

        assertEquals(0, reports.get(), "the inner release must leave the outer read hold intact");
      } finally {
        runtime.session().writeBarrier.readLock().unlock();
      }

      runtime.session().reportTerminalWriterFailureIfPresent();
      assertEquals(1, reports.get());
    } finally {
      assertThrows(RuntimeException.class, runtime::close);
    }
  }

  @Test
  void usableWriterAndIntentionalDrainDoNotReport() throws Exception {
    try (RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      AtomicInteger reports = new AtomicInteger();
      runtime.onTerminalWriterFailure(ignored -> reports.incrementAndGet());

      assertThrows(
          IOException.class,
          () ->
              new IndexWriter(
                  runtime.session().snapshot.directory(), new IndexWriterConfig()));
      assertTrue(runtime.session().snapshot.writer().isOpen(), "lock contention is recoverable");
      runtime.indexingCoordinator().indexSingle(document("still-usable"));
      runtime.session().reportTerminalWriterFailureIfPresent();
      assertEquals(0, reports.get(), "recoverable I/O must not terminate a usable writer");

      runtime.retireTerminalWriterFailureNotifications();
      runtime.session().snapshot.writer().close();
      runtime.session().reportTerminalWriterFailureIfPresent();

      assertEquals(0, reports.get());
    }
  }

  @Test
  void terminalWriterFoundBeforeListenerIsNotConsumed() throws Exception {
    Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
    AtomicInteger fallbacks = new AtomicInteger();
    Thread.setDefaultUncaughtExceptionHandler(
        (ignoredThread, ignoredFailure) -> fallbacks.incrementAndGet());
    try (RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.session().snapshot.writer().close();
      Thread nrt = runtime.session().crtrt;
      nrt.getUncaughtExceptionHandler()
          .uncaughtException(nrt, new AlreadyClosedException("NRT ran before owner binding"));
      assertEquals(0, fallbacks.get(), "a terminal writer must await its owner, not enter JVM exit");
      AtomicInteger reports = new AtomicInteger();

      runtime.onTerminalWriterFailure(ignored -> reports.incrementAndGet());
      runtime.session().reportTerminalWriterFailureIfPresent();

      assertEquals(1, reports.get());
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(prior);
    }
  }

  @Test
  void listenerErrorPropagatesToTheJvmOwner() throws Exception {
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      runtime.onTerminalWriterFailure(
          ignored -> {
            throw new AssertionError("fault dispatcher unavailable");
          });
      runtime
          .session()
          .snapshot
          .writer()
          .onTragicEvent(new RuntimeException("forced listener-error tragedy"), "test");

      AssertionError failure =
          assertThrows(AssertionError.class, runtime.session()::reportTerminalWriterFailureIfPresent);

      assertEquals("fault dispatcher unavailable", failure.getMessage());
    } finally {
      assertThrows(RuntimeException.class, runtime::close);
    }
  }

  @Test
  void nrtThreadRoutesOnlyTerminalWriterFailureToTheRuntimeOwner() throws Exception {
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      AtomicInteger reports = new AtomicInteger();
      AtomicInteger fallbacks = new AtomicInteger();
      runtime.onTerminalWriterFailure(ignored -> reports.incrementAndGet());
      Thread nrt = new Thread();
      nrt.setUncaughtExceptionHandler((ignoredThread, ignoredFailure) -> fallbacks.incrementAndGet());
      runtime.session().routeTerminalWriterFailuresFrom(nrt);

      nrt.getUncaughtExceptionHandler()
          .uncaughtException(nrt, new IllegalStateException("unrelated thread failure"));
      assertEquals(0, reports.get());
      assertEquals(1, fallbacks.get(), "a usable writer leaves unrelated failures to the JVM owner");

      runtime
          .session()
          .snapshot
          .writer()
          .onTragicEvent(new RuntimeException("forced NRT tragedy"), "test");
      nrt.getUncaughtExceptionHandler()
          .uncaughtException(nrt, new AlreadyClosedException("NRT observed closed writer"));

      assertEquals(1, reports.get());
      assertEquals(1, fallbacks.get(), "terminal writer failure must not start a second JVM exit");

      nrt.getUncaughtExceptionHandler()
          .uncaughtException(nrt, new AssertionError("unrelated failure after writer tragedy"));
      assertEquals(1, reports.get());
      assertEquals(2, fallbacks.get(), "unrelated errors remain owned by the JVM handler");
    } finally {
      assertThrows(RuntimeException.class, runtime::close);
    }
  }

  @Test
  void retiredRuntimeConsumesCloseInducedNrtFailureAfterSnapshotIsCleared() throws Exception {
    Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
    AtomicInteger fallbacks = new AtomicInteger();
    Thread.setDefaultUncaughtExceptionHandler(
        (ignoredThread, ignoredFailure) -> fallbacks.incrementAndGet());
    try {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      AtomicInteger reports = new AtomicInteger();
      runtime.onTerminalWriterFailure(ignored -> reports.incrementAndGet());
      Thread nrt = runtime.session().crtrt;
      Thread.UncaughtExceptionHandler handler = nrt.getUncaughtExceptionHandler();

      runtime.close();
      assertTrue(runtime.session().snapshot == null);
      handler.uncaughtException(nrt, new AlreadyClosedException("close interrupted NRT refresh"));

      assertEquals(0, reports.get());
      assertEquals(0, fallbacks.get());
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(prior);
    }
  }

  @Test
  void claimedRuntimeConsumesLateNrtFailureAfterSnapshotIsCleared() throws Exception {
    Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
    AtomicInteger fallbacks = new AtomicInteger();
    Thread.setDefaultUncaughtExceptionHandler(
        (ignoredThread, ignoredFailure) -> fallbacks.incrementAndGet());
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      AtomicInteger reports = new AtomicInteger();
      runtime.onTerminalWriterFailure(ignored -> reports.incrementAndGet());
      Thread nrt = runtime.session().crtrt;
      Thread.UncaughtExceptionHandler handler = nrt.getUncaughtExceptionHandler();
      runtime
          .session()
          .snapshot
          .writer()
          .onTragicEvent(new RuntimeException("forced claimed tragedy"), "test");
      runtime.session().reportTerminalWriterFailureIfPresent();

      assertThrows(RuntimeException.class, runtime::close);
      assertTrue(runtime.session().snapshot == null);
      handler.uncaughtException(nrt, new AlreadyClosedException("late NRT observation"));

      assertEquals(1, reports.get());
      assertEquals(0, fallbacks.get());
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(prior);
    }
  }

  @Test
  void resumedProductionNrtThreadRoutesTerminalWriterFailure() throws Exception {
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      AtomicInteger reports = new AtomicInteger();
      runtime.onTerminalWriterFailure(ignored -> reports.incrementAndGet());

      runtime.commitOps().suspendNrtRefresh();
      runtime.commitOps().resumeNrtRefresh();
      Thread resumed = runtime.session().crtrt;
      runtime
          .session()
          .snapshot
          .writer()
          .onTragicEvent(new RuntimeException("forced resumed-NRT tragedy"), "test");

      resumed
          .getUncaughtExceptionHandler()
          .uncaughtException(resumed, new AlreadyClosedException("resumed NRT observed closed writer"));

      assertEquals(1, reports.get(), "the production replacement thread must route its tragedy");
    } finally {
      assertThrows(RuntimeException.class, runtime::close);
    }
  }

  @Test
  void failedCommitReportsAfterItsMonitorAndPreservesTheCommitFailure() throws Exception {
    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      CountDownLatch commitMonitorAcquired = new CountDownLatch(1);
      AtomicBoolean listenerObservedReleasedMonitor = new AtomicBoolean();
      AtomicInteger reports = new AtomicInteger();
      runtime.onTerminalWriterFailure(
          ignored -> {
            Thread.ofVirtual()
                .start(
                    () -> {
                      synchronized (runtime.commitOps()) {
                        commitMonitorAcquired.countDown();
                      }
                    });
            try {
              listenerObservedReleasedMonitor.set(
                  commitMonitorAcquired.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            reports.incrementAndGet();
            throw new IllegalStateException("listener failure must stay diagnostic");
          });
      runtime
          .session()
          .snapshot
          .writer()
          .onTragicEvent(new RuntimeException("forced commit tragedy"), "test");

      RuntimeException commitFailure =
          assertThrows(RuntimeException.class, () -> runtime.commitOps().commitAndTrack());

      assertTrue(listenerObservedReleasedMonitor.get(), "callback must run outside CommitOps monitor");
      assertEquals(1, reports.get());
      assertTrue(
          !String.valueOf(commitFailure.getMessage()).contains("listener failure"),
          "the listener failure must not replace the commit failure");
    } finally {
      assertThrows(RuntimeException.class, runtime::close);
    }
  }

  private static IndexDocument document(String id) {
    return new IndexDocument(
        Map.of(
            SchemaFields.DOC_ID, id,
            SchemaFields.DOC_UID, id + "#0",
            SchemaFields.CONTENT, "body"));
  }
}
