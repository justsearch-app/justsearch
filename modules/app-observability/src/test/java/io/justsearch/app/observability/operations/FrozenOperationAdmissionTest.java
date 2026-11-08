/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shared Head admission must refuse before callbacks, reservations or durable accepted work. */
final class FrozenOperationAdmissionTest {
  @TempDir Path directory;

  @Test void frozenPreparationAndAcceptanceInvokeNoCallbackAndCreateNoRow() throws Exception {
    try (var store = new SqliteOperationStore(directory.resolve("operations.db")); var _ = frozen()) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var request = request(OperationKind.OPERATION, "core.test");
      var callbacks = new AtomicInteger();
      assertThrows(IllegalStateException.class, () -> runner.withPreparation(request, scope -> callbacks.incrementAndGet()));
      assertThrows(IllegalStateException.class, () -> runner.admitAndAccept(request, null, scope -> {
        callbacks.incrementAndGet(); scope.accept(request.context()); return "reservation";
      }));
      assertThrows(IllegalStateException.class, () -> runner.accept(request));
      assertThrows(IllegalStateException.class, () -> runner.acceptPrepared(request, UUID.randomUUID()));
      assertEquals(0, callbacks.get());
      assertTrue(store.find(request.key()).isEmpty());
      assertTrue(runner.lookup(request).isEmpty(), "history lookup stays usable");
      assertFalse(runner.persistenceFailure().toCompletableFuture().isDone());
      runner.beginClosing(); assertTrue(runner.awaitDrained(Duration.ofSeconds(1)));
    }
  }

  @Test void frozenAcceptanceLeavesNoDurableOperation() throws Exception {
    try (var store = new SqliteOperationStore(directory.resolve("operations.db")); var _ = frozen()) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var request = request(OperationKind.OPERATION, "core.test");
      assertThrows(IllegalStateException.class, () -> runner.accept(request));
      assertTrue(store.find(request.key()).isEmpty(), "frozen acceptance cannot create a durable row");
      runner.beginClosing(); assertTrue(runner.awaitDrained(Duration.ofSeconds(1)));
    }
  }

  @Test void lateResumeAndStartCannotExecuteOrChangeAcceptedRows() throws Exception {
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      final OperationAttemptRunnerImpl runner;
      try (var _ = frozen()) {
        runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of(OperationKind.FORGET));
      }
      // A separately owned producer supplies an accepted row after this owner's empty boot
      // observation. This exercises late admission, independent of the boot-time pending gate.
      var request = request(OperationKind.FORGET, ForgetPlan.OPERATION_ID);
      var row = store.accept(request.key(), request.descriptor(), request.context(), null).record();
      var callbacks = new AtomicInteger();
      var retained = runner.lookup(request).orElseThrow();
      assertThrows(IllegalStateException.class, () -> runner.resumeForget(request.key(), control -> {
        callbacks.incrementAndGet(); return success();
      }));
      assertThrows(IllegalStateException.class, () -> runner.start(retained, control -> {
        callbacks.incrementAndGet(); return success();
      }));
      runner.reconcile(OperationKind.FORGET, ignored -> {
        callbacks.incrementAndGet(); return new OperationAttemptRunner.Reconciliation.Resume(control -> success());
      });
      assertEquals(0, callbacks.get()); assertEquals(row, store.find(row.key()).orElseThrow());
      runner.beginClosing(); assertTrue(runner.awaitDrained(Duration.ofSeconds(1)));
    }
  }

  @Test void ordinaryOwnerStillAcceptsAndExecutesAfterAnotherOwnerRequestsFrozen() throws Exception {
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      try (var _ = frozen()) {
        var accepted = runner.accept(request(OperationKind.OPERATION, "core.test"));
        var callbacks = new AtomicInteger();
        var result = runner.start(accepted, ignored -> { callbacks.incrementAndGet(); return success(); });
        assertEquals(1, callbacks.get());
        assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().get().state());
      }
      runner.beginClosing(); assertTrue(runner.awaitDrained(Duration.ofSeconds(1)));
    }
  }

  static OperationExecution success() {
    return OperationExecution.finished(OperationResult.success("done"));
  }

  static OperationAttemptRunner.Request request(OperationKind kind, String operation) {
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "fixture", Optional.empty(),
        Optional.empty(), "system", "SYSTEM_INTERNAL", EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
    return new OperationAttemptRunner.Request(OperationKeys.generate(Clock.systemUTC()),
        OperationDescriptor.invocation(kind, operation, "{}", false), context, null);
  }

  static AutoCloseable frozen() {
    String eval = System.getProperty("justsearch.eval.mode"), frozen = System.getProperty("justsearch.eval.frozen");
    System.setProperty("justsearch.eval.mode", "true"); System.setProperty("justsearch.eval.frozen", "true");
    return () -> {
      if (eval == null) System.clearProperty("justsearch.eval.mode"); else System.setProperty("justsearch.eval.mode", eval);
      if (frozen == null) System.clearProperty("justsearch.eval.frozen"); else System.setProperty("justsearch.eval.frozen", frozen);
    };
  }
}
