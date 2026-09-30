/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationPreparationRunnerTest {
  @TempDir Path directory;
  private static final Clock CLOCK = Clock.systemUTC();
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "preparation-runner", Optional.empty(), Optional.empty(), "SYSTEM", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  private static final OperationDescriptor IDENTITY = OperationDescriptor.invocation(OperationKind.NOTE,
      "core.file-note", "{}", false);

  private static OperationAttemptRunner.Request request(String key) {
    return new OperationAttemptRunner.Request(key, IDENTITY, CONTEXT, null);
  }
  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(directory.resolve("operations.db"), CLOCK, step -> {});
  }
  private static OperationStore.Preparation preparation() {
    return new OperationStore.Preparation(UUID.randomUUID(), new OperationPreparedPayload(false, "{}"));
  }

  @Test
  void concurrentUnknownKeyPreparesOnceBeforeEitherAcceptance() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var request = request(OperationKeys.generate(CLOCK));
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      var count = new AtomicInteger();
      java.util.function.Supplier<OperationStore.Preparation> operation = () -> runner.withPreparation(request, scope ->
          runner.pendingPreparation(scope.request()).orElseGet(() -> {
            count.incrementAndGet(); entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return runner.savePreparation(scope.request(), preparation()).orElseThrow();
          }));
      var firstResult = new CompletableFuture<OperationStore.Preparation>();
      var secondResult = new CompletableFuture<OperationStore.Preparation>();
      Thread first = new Thread(() -> complete(firstResult, operation), "first-preparation");
      Thread second = new Thread(() -> complete(secondResult, operation), "second-preparation");
      first.start();
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        second.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        // Correct: second parks at the key lock. Negative control: it reaches the callback and
        // parks at release, after independently observing missing pending state. Both are settled
        // before release, so a scheduling delay cannot manufacture the one-preparation result.
        while (second.isAlive() && second.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, second.getState());
        release.countDown();
        assertEquals(firstResult.get(3, TimeUnit.SECONDS), secondResult.get(3, TimeUnit.SECONDS));
        assertEquals(1, count.get());
        assertTrue(store.openRecords().isEmpty());
      } finally {
        release.countDown();
        first.join(Duration.ofSeconds(3));
        if (second.getState() != Thread.State.NEW) second.join(Duration.ofSeconds(3));
      }
    }
  }

  private static <T> void complete(CompletableFuture<T> result, java.util.function.Supplier<T> supplier) {
    try { result.complete(supplier.get()); }
    catch (Throwable failure) { result.completeExceptionally(failure); }
  }

  @Test
  void preparationCallbackReleasesSqliteAndObservationWaitsUntilOutsideScope() throws Exception {
    try (var store = store(); var reads = Executors.newSingleThreadExecutor()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var stable = runner.withPreparation(request(null), scope -> {
        assertTrue(scope.existing().isEmpty());
        assertNotNull(scope.request().key());
        try { assertTrue(reads.submit(() -> store.find(scope.request().key())).get(3, TimeUnit.SECONDS).isEmpty()); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        var prepared = runner.savePreparation(scope.request(), preparation()).orElseThrow();
        assertThrows(IllegalStateException.class, () -> runner.accept(scope.request()));
        assertThrows(IllegalStateException.class, () -> runner.acceptPrepared(scope.request(), prepared.nonce()));
        assertThrows(IllegalStateException.class, () -> runner.lookup(scope.request()));
        return scope.request();
      });
      var attempt = runner.acceptPrepared(stable, runner.pendingPreparation(stable).orElseThrow().nonce());
      assertFalse(attempt.existing());
      assertEquals(OperationState.ACCEPTED, attempt.accepted().state());
      assertTrue(runner.acceptedPreparation(attempt.accepted().id()).isPresent());
      assertTrue(runner.accept(request(attempt.accepted().key())).existing());
      long observedId = runner.withPreparation(stable, scope -> scope.existing().orElseThrow().id());
      assertEquals(attempt.accepted().id(), observedId);
    }
  }

  @Test
  void lateTerminalObservationPublishesAfterReleasingPreparationLock() throws Exception {
    try (var store = store(); var callbacks = Executors.newSingleThreadExecutor()) {
      var finishBeforeReturn = new java.util.concurrent.atomic.AtomicBoolean();
      var port = (OperationStore) java.lang.reflect.Proxy.newProxyInstance(
          OperationStore.class.getClassLoader(), new Class<?>[] {OperationStore.class},
          (proxy, method, args) -> {
            try {
              Object result = method.invoke(store, args);
              if (method.getName().equals("accept") && finishBeforeReturn.compareAndSet(true, false)) {
                var snapshot = (OperationStore.Acceptance) result;
                store.finish(snapshot.record().id(), OperationState.COMPLETE, new OperationReceipt("COMPLETE", null));
              }
              return result;
            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
          });
      var runner = new OperationAttemptRunnerImpl(port, CLOCK, Set.of());
      var request = request(OperationKeys.generate(CLOCK));
      var initial = runner.accept(request);
      var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Exception>();
      var callbacksSeen = new AtomicInteger();
      var ignored = initial.completion().whenComplete((row, failure) -> {
        callbacksSeen.incrementAndGet();
        try { assertTrue(callbacks.submit(() -> runner.accept(request)).get(1, TimeUnit.SECONDS).existing()); }
        catch (Exception blocked) { callbackFailure.set(blocked); }
      });
      // Model the legitimate gap between a concurrent owner's durable finish and its future
      // publication: this acceptance returns its earlier OPEN snapshot, then controlFor reads
      // the freshly terminal row. The listener was installed before that observation.
      finishBeforeReturn.set(true);
      assertTrue(runner.accept(request).existing());
      assertEquals(1, callbacksSeen.get());
      assertNull(callbackFailure.get(), "completion callbacks may request the same key from another thread");
      assertTrue(ignored.toCompletableFuture().isDone());
    }
  }

  @Test
  void invalidAndConflictingKeysNeverEnterPreparationCallback() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var calls = new AtomicInteger();
      assertEquals(OperationStoreException.Code.INVALID_OPERATION_KEY,
          assertThrows(OperationStoreException.class,
              () -> runner.withPreparation(request("invalid"), stable -> calls.incrementAndGet())).code());
      var accepted = runner.accept(request(null));
      var other = new OperationAttemptRunner.Request(accepted.accepted().key(),
          OperationDescriptor.invocation(OperationKind.NOTE, "core.other", "{}", false), CONTEXT, null);
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class,
              () -> runner.withPreparation(other, stable -> calls.incrementAndGet())).code());
      assertEquals(0, calls.get());
    }
  }
}
