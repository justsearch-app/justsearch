/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static io.justsearch.app.engine.EngineForgetInProcessTest.field;
import static io.justsearch.app.engine.EngineForgetInProcessTest.key;
import static io.justsearch.app.engine.EngineForgetInProcessTest.webview;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.IndexingJobChangeFeed;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real stream overflow, issued ServingLease retirement, actual owner locks and committed callbacks. */
@Timeout(90)
final class EngineForgetQueueRuntimeTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;
  @Test void Q55QueueTryUnwindsRuntimeDuringActualInlineOverflowRetirement() throws Exception { overflow(false); }
  @Test void Q55FileTryUnwindsRuntimeDuringActualInlineOverflowRetirement() throws Exception { overflow(true); }

  private void overflow(boolean holdFile) throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    Path target = Files.createDirectory(watched.resolve("target"));
    Path source = Files.writeString(target.resolve("file.txt"), "source physical content");
    var deltaEntered = new CountDownLatch(1); var deltaRelease = new CountDownLatch(1);
    var outerEntered = new CountDownLatch(1); var continueForget = new CountDownLatch(1);
    var errorEntered = new CountDownLatch(1); var snapshot = new CountDownLatch(1);
    var observerFailure = new AtomicReference<Throwable>();
    var floodThread = new AtomicReference<Thread>();
    var errorThread = new AtomicReference<Thread>();
    var first = new AtomicBoolean(true);
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of(watched))) {
      f.client.indexingHold().pause(null, "USER_PAUSED");
      f.seed(source, "source indexed content");
      String walkKey = OperationKeys.generate(Clock.systemUTC());
      var context = webview();
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Clock.systemUTC().instant(), Optional.empty());
      f.attempts.accept(new OperationAttemptRunner.Request(walkKey,
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context, provenance));
      var walk = f.queue.beginRecordedWalk(walkKey, "a".repeat(64), true);
      assertEquals(1, f.queue.enqueueRecordedEntries(walkKey, walk.enumerationEpoch(),
          List.of(new JobQueue.EnqueueEntry(source, Files.size(source))), "documents"));
      // An actual query-owner publication leaves A's resources owned by this issued lease.
      try (var retired = f.server.captureServingView()) {
        Object retiredView = field(retired, retired.getClass(), "captured");
        var disabled = new QueryRoleSelection(QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
        var query = f.server.prepareQueryRoleSettings(f.settings.inspect().settings(), f.config.get(), Set.of(), disabled);
        try {
          query.withOwnerLocks(query::install); query.notifyObservers();
        } finally { query.retire(); }
        assertTrue((Boolean) field(retiredView, retiredView.getClass(), "retiring"));
        assertNotNull(field(retiredView, retiredView.getClass(), "retireCleanup"));
        var currentServices = (DefaultWorkerAppServices) f.server.appServices();
        var actualIngest = currentServices.ingestService(); var heldIngest = spy(actualIngest);
        doAnswer(invocation -> {
          if (first.compareAndSet(true, false)) {
            outerEntered.countDown(); assertTrue(continueForget.await(10, TimeUnit.SECONDS));
          }
          return invocation.callRealMethod();
        }).when(heldIngest).tryBeginForgetMutation();
        set(currentServices, DefaultWorkerAppServices.class, "ingestService", heldIngest);
        var runtimeLock = (ReentrantLock) field(f.server, KnowledgeServer.class, "runtimeSwapLock");
        var display = new AtomicInteger(); var walks = new AtomicInteger();
        String sourceHash = io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(key(source));
        try (var ignoredDisplayObserver = f.queue.changeStream().subscribe(delta -> {
              if (delta instanceof IndexingJobChangeFeed.Delta.Update update
                  && sourceHash.equals(update.row().pathHash()) && "DONE".equals(update.row().state())) {
                observeOwners(f, observerFailure); display.incrementAndGet();
              }
            });
            var ignoredWalkObserver = f.queue.subscribeRecordedWalks(changed -> {
              if (walkKey.equals(changed)) { observeOwners(f, observerFailure); walks.incrementAndGet(); }
            });
            var ignoredStream = f.client.subscribeIndexingJobs(frame -> {
              if (frame.hasSnapshot()) snapshot.countDown();
              else if (deltaEntered.getCount() != 0) {
                deltaEntered.countDown();
                try { assertTrue(deltaRelease.await(20, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
              }
            }, failure -> {
              errorThread.set(Thread.currentThread()); errorEntered.countDown();
              try {
                assertThrows(IllegalStateException.class, () -> f.root.forgetService().prepare(
                    target.toString(), io.justsearch.configuration.excludes.LiteralExclusion.Scope.FOLDER, webview()),
                    "queue callbacks reject FORGET before taking root/runtime owners");
              } catch (Throwable assertion) { observerFailure.compareAndSet(null, assertion); }
              // This is the actual ServingLease close -> retired source cleanup -> runtime lock.
              retired.close();
            }, () -> {}, webview())) {
          assertTrue(snapshot.await(3, TimeUnit.SECONDS));
          var paths = new ArrayList<Path>();
          for (int i = 0; i < BoundedHandoff.DEFAULT_CAPACITY + 16; i++) paths.add(directory.resolve("pressure-" + i + ".txt"));
          f.queue.enqueue(paths);
          f.queue.markFailed(paths.getFirst(), "private fixture initial visible delta");
          assertTrue(deltaEntered.await(3, TimeUnit.SECONDS));
          if (holdFile) f.loop.fileMutationFence().lock();
          try {
            var forgetting = new FutureTask<>(() -> f.forgetAttempt(target, "FOLDER"));
            Thread.ofVirtual().start(forgetting); assertTrue(outerEntered.await(3, TimeUnit.SECONDS));
            var flood = new FutureTask<>(() -> {
              floodThread.set(Thread.currentThread());
              for (Path path : paths) f.queue.markFailed(path, "private fixture pressure");
              return true;
            });
            Thread.ofVirtual().start(flood);
            assertTrue(errorEntered.await(5, TimeUnit.SECONDS), "the actual bounded handoff must overflow");
            assertSame(floodThread.get(), errorThread.get(), "overflow handling runs inline on the real queue writer");
            assertTrue(awaitRuntimeWait(floodThread.get()), "the actual retired-view cleanup waits for FORGET's runtime owner");
            continueForget.countDown();
            var result = forgetting.get(3, TimeUnit.SECONDS);
            var outcome = JSON.valueToTree(result.errorDetails().get("forget"));
            assertEquals("INCOMPLETE", outcome.path("status").asString(), result.message());
            assertEquals("INSTALLED", outcome.path("keepOut").asString());
            assertTrue(flood.get(3, TimeUnit.SECONDS), "TRY acquisition unwinds outer owners instead of cycling");
            assertNull(field(retiredView, retiredView.getClass(), "retireCleanup"), "real retired-source cleanup completed");
            assertFalse(runtimeLock.isHeldByCurrentThread());
            assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(source)));
            if (holdFile) f.loop.fileMutationFence().unlock();
            holdFile = false;
            deltaRelease.countDown();
            String forgetKey = outcome.path("operationKey").asString();
            var retry = f.root.forgetService().retry(forgetKey, webview());
            var retried = JSON.valueToTree(retry.errorDetails().get("forget"));
            assertEquals("INCOMPLETE", retried.path("status").asString(),
                "unacknowledged recorded coverage remains truthful rather than a false empty queue");
            assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(source)));
            assertTrue(display.get() > 0, "FORGET's actual recorded-job transition publishes its display delta after owners");
            assertTrue(walks.get() > 0, "FORGET's actual receipt change publishes its walk notification after owners");
            assertNull(observerFailure.get());
            var withdrawn = f.root.forgetService().withdraw(forgetKey, webview());
            assertEquals("WITHDRAWN", JSON.valueToTree(withdrawn.structuredData().get("forget")).path("status").asString());
            assertEquals("source physical content", Files.readString(source));
          } finally {
            continueForget.countDown(); deltaRelease.countDown();
            if (holdFile) f.loop.fileMutationFence().unlock();
          }
        } finally {
          continueForget.countDown(); deltaRelease.countDown();
          set(currentServices, DefaultWorkerAppServices.class, "ingestService", actualIngest);
        }
      }
    } finally { continueForget.countDown(); deltaRelease.countDown(); }
  }

  private static void observeOwners(EngineForgetInProcessTest.Fixture f, AtomicReference<Throwable> failure) {
    try {
      assertFalse(Thread.holdsLock(f.root));
      assertFalse(Thread.holdsLock(field(f.authority.roots(), f.authority.roots().getClass(), "lifecycleLock")));
      assertFalse(((ReentrantLock) field(f.server, KnowledgeServer.class, "runtimeSwapLock")).isHeldByCurrentThread());
      assertFalse(((ReentrantLock) field(null, IndexGenerationManager.class, "STATE_CONTROL")).isHeldByCurrentThread());
      assertFalse(f.config.publicationLock().isWriteLockedByCurrentThread());
      assertFalse(f.loop.fileMutationFence().isHeldByCurrentThread());
      var services = (DefaultWorkerAppServices) f.server.appServices();
      try (var mutation = services.mutationAdmission().beginFinalFence(services.mutationOwnerToken(), 0)) {
        assertNotNull(mutation, "operation mutation admission also released before observers");
      }
    } catch (Throwable assertion) { failure.compareAndSet(null, assertion); }
  }
  private static boolean awaitRuntimeWait(Thread thread) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < until) {
      if (java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
          frame.getClassName().equals(KnowledgeServer.class.getName()) && frame.getMethodName().equals("retryCommittedGenerationRetirement"))) return true;
      if (!thread.isAlive()) return false;
      Thread.sleep(2);
    }
    return false;
  }
  private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }
}
