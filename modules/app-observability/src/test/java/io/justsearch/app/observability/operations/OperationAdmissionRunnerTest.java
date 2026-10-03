/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** SQLite-backed contract tests for final operation admission and acceptance arbitration. */
@Timeout(20)
final class OperationAdmissionRunnerTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final EngineContext CONTEXT = new EngineContext(
      EngineContext.ClientKind.INTERNAL, "admission-runner", Optional.empty(), Optional.empty(),
      "SYSTEM", "SYSTEM_INTERNAL", EngineContext.Survival.DURABLE,
      EngineContext.Urgency.FOREGROUND);
  private static final OperationDescriptor DESCRIPTOR = OperationDescriptor.invocation(
      OperationKind.INGEST, "core.ingest-files", "{\"paths\":[\"F:/docs\"]}", false);

  @TempDir Path directory;

  @Test
  void concurrentSameKeyReservesAndAcceptsExactlyOnce() throws Exception {
    try (var store = store(); var callers = Executors.newFixedThreadPool(2)) {
      var runner = runner(store);
      var request = request(OperationKeys.generate(CLOCK), DESCRIPTOR);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var reservations = new AtomicInteger();
      java.util.function.Supplier<OperationAttemptRunner.AdmittedAttempt<String>> call =
          () -> runner.admitAndAccept(request, null, scope -> {
            reservations.incrementAndGet();
            entered.countDown();
            await(release);
            scope.accept(CONTEXT);
            return "reservation";
          });

      var first = callers.submit(call::get);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var second = callers.submit(call::get);
      assertEquals(1, reservations.get());
      assertFalse(second.isDone(), "the matching caller must wait for the first acceptance");
      release.countDown();

      var created = first.get(3, TimeUnit.SECONDS);
      var existing = second.get(3, TimeUnit.SECONDS);
      assertEquals(1, reservations.get());
      assertEquals(Optional.of("reservation"), created.admission());
      assertTrue(existing.admission().isEmpty());
      assertFalse(created.attempt().existing());
      assertTrue(existing.attempt().existing());
      assertEquals(created.attempt().accepted().id(), existing.attempt().accepted().id());
      assertEquals(1, store.openRecords().size());
    }
  }

  @Test
  void terminalRetryAndChangedInputNeverReserveAndCompletionCanReenter() throws Exception {
    try (var store = store(); var reentry = Executors.newSingleThreadExecutor()) {
      var runner = runner(store);
      var request = request(OperationKeys.generate(CLOCK), DESCRIPTOR);
      var first = runner.admitAndAccept(request, null, scope -> {
        scope.accept(CONTEXT);
        return "reservation";
      });
      var completed = runner.start(first.attempt(), ignored ->
          OperationExecution.finished(OperationResult.success("complete")));
      assertEquals(OperationState.COMPLETE,
          completed.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state());

      var skipped = new AtomicInteger();
      var terminal = runner.admitAndAccept(request, null, scope -> {
        skipped.incrementAndGet();
        throw new AssertionError("terminal retry cannot reserve");
      });
      assertTrue(terminal.attempt().existing());
      assertTrue(terminal.admission().isEmpty());
      assertEquals(first.attempt().accepted().id(), terminal.attempt().accepted().id());
      assertEquals(OperationState.COMPLETE,
          terminal.attempt().completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state());

      var reentered = new CompletableFuture<OperationAttemptRunner.AdmittedAttempt<String>>();
      var observed = terminal.attempt().completion().whenComplete((row, failure) -> {
        try {
          reentered.complete(reentry.submit(() -> runner.<String>admitAndAccept(request, null, scope -> {
            throw new AssertionError("reentry of a terminal key cannot reserve");
          })).get(2, TimeUnit.SECONDS));
        } catch (Throwable blocked) {
          reentered.completeExceptionally(blocked);
        }
      });
      assertTrue(reentered.get(3, TimeUnit.SECONDS).admission().isEmpty(),
          "terminal completion listeners reenter only after the key stripe is released");
      assertTrue(observed.toCompletableFuture().isDone());

      var changed = request(request.key(), OperationDescriptor.invocation(
          OperationKind.INGEST, "core.ingest-files", "{\"paths\":[\"F:/other\"]}", false));
      OperationStoreException conflict = assertThrows(OperationStoreException.class,
          () -> runner.admitAndAccept(changed, null, scope -> {
            skipped.incrementAndGet();
            return "must-not-run";
          }));
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, conflict.code());
      assertEquals(0, skipped.get());
    }
  }

  @Test
  void reservationRefusalLeavesNoRowAndTheKeyCanRetry() throws Exception {
    try (var store = store()) {
      var runner = runner(store);
      var request = request(OperationKeys.generate(CLOCK), DESCRIPTOR);
      var calls = new AtomicInteger();

      IllegalStateException refused = assertThrows(IllegalStateException.class,
          () -> runner.admitAndAccept(request, null, scope -> {
            calls.incrementAndGet();
            throw new IllegalStateException("reservation refused");
          }));
      assertEquals("reservation refused", refused.getMessage());
      assertTrue(store.find(request.key()).isEmpty());
      assertTrue(store.openRecords().isEmpty());

      var retried = runner.admitAndAccept(request, null, scope -> {
        calls.incrementAndGet();
        scope.accept(CONTEXT);
        return "second reservation";
      });
      assertEquals(2, calls.get());
      assertEquals(Optional.of("second reservation"), retried.admission());
      assertFalse(retried.attempt().existing());
      assertEquals(1, store.openRecords().size());
    }
  }

  @Test
  void preparedAdmissionConsumesTheExactPendingNonce() throws Exception {
    try (var store = store()) {
      var runner = runner(store);
      var initial = request(OperationKeys.generate(CLOCK), DESCRIPTOR);
      var preparation = new OperationStore.Preparation(UUID.randomUUID(),
          new OperationPreparedPayload(false, "{\"frozen\":true}"));
      var stable = runner.withPreparation(initial, scope -> {
        assertTrue(runner.savePreparation(scope.request(), preparation).isPresent());
        return scope.request();
      });

      var admitted = runner.admitAndAccept(stable, preparation.nonce(), scope -> {
        scope.accept(CONTEXT);
        return "prepared reservation";
      });

      assertEquals(Optional.of("prepared reservation"), admitted.admission());
      assertEquals(preparation,
          runner.acceptedPreparation(admitted.attempt().accepted().id()).orElseThrow());
      assertTrue(runner.pendingPreparation(stable).isEmpty());
    }
  }

  @Test
  void acceptanceScopeIsSingleUseThreadBoundAndClosedAfterCallback() throws Exception {
    try (var store = store(); var otherThread = Executors.newSingleThreadExecutor();
        var _ = store.subscribeCompletions(ignored -> {
          throw new AssertionError("raw acceptance must not publish completion");
        })) {
      var runner = runner(store);
      var request = request(OperationKeys.generate(CLOCK), DESCRIPTOR);
      var retained = new AtomicReference<OperationAttemptRunner.AcceptanceScope>();
      var owner = new AtomicReference<Thread>();

      var admitted = runner.admitAndAccept(request, null, scope -> {
        retained.set(scope);
        owner.set(Thread.currentThread());
        assertSame(owner.get(), Thread.currentThread());
        IllegalStateException wrongThread = get(otherThread.submit(() ->
            assertThrows(IllegalStateException.class, () -> scope.accept(CONTEXT))));
        assertEquals("Accept exactly once within the owning callback", wrongThread.getMessage());
        assertThrows(IllegalStateException.class,
            () -> runner.admitAndAccept(scope.request(), null, nested -> "nested"));
        scope.accept(CONTEXT);
        assertThrows(IllegalStateException.class, () -> scope.accept(CONTEXT));
        return "reservation";
      });

      assertEquals(Optional.of("reservation"), admitted.admission());
      assertNotNull(retained.get());
      assertThrows(IllegalStateException.class, () -> retained.get().accept(CONTEXT));
      assertEquals(1, store.openRecords().size());
    }
  }

  @Test
  void recoveryOwnerRequirementAcceptsOnlyDeclaredKinds() throws Exception {
    try (var store = store()) {
      var runner = runner(store);
      runner.requireRecoveryOwner(OperationKind.INGEST);
      IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
          () -> runner.requireRecoveryOwner(OperationKind.NOTE));
      assertTrue(missing.getMessage().contains("NOTE"));
      assertThrows(NullPointerException.class, () -> runner.requireRecoveryOwner(null));
    }
  }

  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(directory.resolve("operations.db"), CLOCK, ignored -> {});
  }

  private static OperationAttemptRunnerImpl runner(SqliteOperationStore store) {
    return new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.INGEST));
  }

  private static OperationAttemptRunner.Request request(String key, OperationDescriptor descriptor) {
    return new OperationAttemptRunner.Request(key, descriptor, CONTEXT, null);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("barrier interrupted", interrupted);
    }
  }

  private static <T> T get(java.util.concurrent.Future<T> future) {
    try {
      return future.get(3, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("fixture future interrupted", interrupted);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
      throw new AssertionError("fixture future failed", failure);
    }
  }
}
