/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Reflection keeps these behavioral probes compilable on the pre-FORGET baseline. No fake owner. */
class ForgetOperationStoreTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OPERATION = "core.forget-indexed-path";
  private final OperationTestClock clock = new OperationTestClock(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli());
  @TempDir Path directory;

  private OperationKind forgetKind() {
    return assertDoesNotThrow(() -> OperationKind.fromWire("forget"),
        "the durable FORGET owner must accept its closed operation kind");
  }

  private OperationAttemptRunner.Request request(OperationKind kind, String operation) {
    var context = new EngineContext(EngineContext.ClientKind.WEBVIEW, "forget-test", Optional.empty(), Optional.empty(),
        "USER", "BUTTON", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
    return new OperationAttemptRunner.Request(OperationKeys.generate(clock),
        OperationDescriptor.invocation(kind, operation, "{}", false), context, null);
  }

  private Object progress(String phase, String reason) {
    return assertDoesNotThrow(() -> JSON.readValue("{\"version\":1,\"phase\":\"" + phase
        + "\",\"admitted\":true,\"installedToken\":null,\"settingsBase\":null,"
        + "\"priorFileWitness\":null,\"installedFileWitness\":null,\"withdrawalRequested\":false,\"reason\":\"" + reason + "\"}",
        Class.forName("io.justsearch.app.api.operations.ForgetProgress")));
  }

  private Object call(Object owner, String method, Class<?>[] signature, Object... arguments) {
    return assertDoesNotThrow(() -> owner.getClass().getMethod(method, signature).invoke(owner, arguments),
        "real owner boundary " + method + " must execute");
  }

  private Object snapshot(SqliteOperationStore store) {
    return call(store, "captureForgetAuthority", new Class<?>[0]);
  }

  private long sequence(Object snapshot) {
    return (Long) call(snapshot, "acceptanceSequence", new Class<?>[0]);
  }

  private String cursor(Object progress) {
    return (String) call(progress, "cursor", new Class<?>[0]);
  }

  private void park(OperationAttemptRunnerImpl runner, OperationRecordHandle handle, Object progress) {
    call(runner, "parkForget", new Class<?>[] {OperationRecordHandle.class, progress.getClass()}, handle, progress);
  }

  private Object finish(OperationAttemptRunnerImpl runner, OperationRecordHandle handle, long expected) {
    return call(runner, "finishForget", new Class<?>[] {OperationRecordHandle.class, Long.class,
        OperationState.class, OperationReceipt.class}, handle, expected, OperationState.COMPLETE,
        new OperationReceipt("SUCCESS", null));
  }

  @Test
  void RF55ParkReleasesLiveCapacityWithoutCompletingOrPruningIntentAndExplicitRetryKeepsIdentity() throws Exception {
    var kind = forgetKind();
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {})) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(kind));
      var attempt = runner.accept(request(kind, OPERATION));
      var effects = new AtomicInteger();
      runner.start(attempt, handle -> {
        effects.incrementAndGet(); park(runner, handle, progress("WAITING_FOR_PRODUCER", "OLD_PRODUCER"));
        return OperationExecution.finished(OperationResult.success("pending"));
      });
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertFalse(attempt.completion().toCompletableFuture().isDone());
      Function<OperationRecordHandle, OperationExecution> retry = handle -> {
        effects.incrementAndGet(); park(runner, handle, progress("WAITING_FOR_PRODUCER", "OLD_PRODUCER"));
        return OperationExecution.finished(OperationResult.success("pending"));
      };
      call(runner, "resumeForget", new Class<?>[] {String.class, Function.class}, attempt.accepted().key(), retry);
      var retained = store.find(attempt.accepted().key()).orElseThrow();
      assertEquals(attempt.accepted().id(), retained.id());
      assertEquals(2, effects.get()); assertEquals(2, retained.attempts());
      clock.setMillis(clock.millis() + Duration.ofDays(31).toMillis());
      store.pruneHistory();
      assertEquals(retained, store.find(retained.key()).orElseThrow());
      runner.beginClosing();
      assertTrue(runner.awaitDrained(Duration.ZERO), "a parked intent holds no executing segment");
      assertFalse(attempt.completion().toCompletableFuture().isDone());
    }
    try (var reopened = new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {})) {
      var recovered = new OperationAttemptRunnerImpl(reopened, clock, Set.of(kind));
      recovered.reconcile(kind, row -> new OperationAttemptRunner.Reconciliation.Wait());
      assertEquals(1, reopened.openRecords().size());
      assertEquals(OperationState.RUNNING, reopened.openRecords().getFirst().state());
      recovered.beginClosing(); assertTrue(recovered.awaitDrained(Duration.ZERO));
    }
  }

  @Test
  void RF55ConditionalCompletionDefersStoreAndRunnerObserversBeyondOuterPhysicalOwner() throws Exception {
    var kind = forgetKind();
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {})) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(kind));
      var attempt = runner.accept(request(kind, OPERATION));
      var outer = new java.util.concurrent.locks.ReentrantLock();
      var observed = new AtomicInteger();
      try (var subscription = store.subscribeCompletions(row -> {
        assertFalse(outer.isHeldByCurrentThread(), "completion cannot acquire arbitrary owners inside the physical fence");
        observed.incrementAndGet();
      })) {
        runner.start(attempt, handle -> {
          Object completion;
          outer.lock();
          try {
            handle.checkpoint(cursor(progress("READY", "NONE")), 0, 0);
            completion = finish(runner, handle, sequence(snapshot(store)));
            assertEquals(OperationState.COMPLETE, store.find(handle.key()).orElseThrow().state());
            assertEquals(0, observed.get());
            assertFalse(attempt.completion().toCompletableFuture().isDone());
          } finally { outer.unlock(); }
          call(completion, "publish", new Class<?>[0]);
          call(completion, "publish", new Class<?>[0]);
          return OperationExecution.finished(OperationResult.success("removed"));
        });
        assertEquals(1, observed.get(), "publication is idempotent");
        assertEquals(OperationState.COMPLETE, attempt.completion().toCompletableFuture().join().state());
      }
    }
  }

  @Test
  void RF55NewAcceptanceInsideSettlementPreventsCompletionAndRetainsRetryableIntent() throws Exception {
    var kind = forgetKind();
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {})) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(kind));
      var attempt = runner.accept(request(kind, OPERATION));
      runner.start(attempt, handle -> {
        long before = sequence(snapshot(store));
        runner.accept(request(OperationKind.OPERATION, "core.unrelated"));
        handle.checkpoint(cursor(progress("READY", "NONE")), 0, 0);
        Object decision = finish(runner, handle, before);
        assertEquals(Optional.empty(), call(decision, "record", new Class<?>[0]));
        call(decision, "publish", new Class<?>[0]);
        assertEquals(OperationState.RUNNING, store.find(handle.key()).orElseThrow().state());
        park(runner, handle, progress("WAITING_FOR_PRODUCER", "ACCEPTANCE_CHANGED"));
        return OperationExecution.finished(OperationResult.success("pending"));
      });
      assertFalse(attempt.completion().toCompletableFuture().isDone());
      runner.beginClosing(); assertTrue(runner.awaitDrained(Duration.ZERO));
    }
  }

  @Test
  void RF55AcceptanceSequenceSurvivesPruningOfHighestAcceptedId() throws Exception {
    var kind = forgetKind();
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {})) {
      var first = request(kind, OPERATION);
      var retained = store.accept(first.key(), first.descriptor(), first.context(), null).record();
      var second = request(OperationKind.OPERATION, "core.unrelated");
      var evicted = store.accept(second.key(), second.descriptor(), second.context(), null).record();
      store.finish(evicted.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      long accepted = sequence(snapshot(store));
      clock.setMillis(clock.millis() + Duration.ofDays(31).toMillis());
      store.pruneHistory();
      assertTrue(store.find(evicted.key()).isEmpty());
      assertEquals(retained.id(), store.openRecords().getFirst().id());
      assertEquals(accepted, sequence(snapshot(store)), "a pruned MAX(id) is not the acceptance sequence");
    }
  }

  @Test
  void RF55CappedAuthorityObservationCannotClaimACompleteProducerSnapshot() throws Exception {
    var kind = forgetKind();
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {})) {
      for (int i = 0; i < 1025; i++) {
        var accepted = request(kind, OPERATION);
        store.accept(accepted.key(), accepted.descriptor(), accepted.context(), null);
      }
      Object observed = snapshot(store);
      assertEquals(false, call(observed, "complete", new Class<?>[0]));
      assertEquals(1024, ((List<?>) call(observed, "records", new Class<?>[0])).size());
      assertEquals(1025, sequence(observed));
    }
  }

  @Test
  void WD55WithdrawalWinsAgainstStaleProgressAndCompletionAndSurvivesRestart() throws Exception {
    var kind = forgetKind();
    String key;
    try (var store = new SqliteOperationStore(directory.resolve("withdraw.db"), clock, step -> {})) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(kind));
      var attempt = runner.accept(request(kind, OPERATION)); key = attempt.accepted().key();
      runner.start(attempt, handle -> {
        handle.checkpoint(cursor(progress("ADMITTED", "NONE")), 0, 0);
        call(runner, "requestForgetWithdrawal", new Class<?>[] {String.class}, handle.key());
        // A physical segment captured this value before the user's withdrawal. Its checkpoint
        // and final CAS may not clear that request or overwrite it with completed removal.
        Object stale = progress("READY", "NONE");
        handle.checkpoint(cursor(stale), 0, 0);
        assertTrue(JSON.readTree(store.find(handle.key()).orElseThrow().checkpointCursor())
            .path("withdrawalRequested").asBoolean());
        Object completion = finish(runner, handle, sequence(snapshot(store)));
        assertTrue(((Optional<?>) call(completion,
            "record", new Class<?>[0])).isEmpty());
        park(runner, handle, stale);
        return OperationExecution.finished(OperationResult.success("withdrawal pending"));
      });
      assertEquals(OperationState.RUNNING, store.find(key).orElseThrow().state());
      runner.beginClosing(); assertTrue(runner.awaitDrained(Duration.ZERO));
    }
    try (var store = new SqliteOperationStore(directory.resolve("withdraw.db"), clock, step -> {})) {
      var recovered = new OperationAttemptRunnerImpl(store, clock, Set.of(kind));
      var row = store.find(key).orElseThrow();
      assertEquals(OperationState.RUNNING, row.state());
      assertTrue(JSON.readTree(row.checkpointCursor()).path("withdrawalRequested").asBoolean());
      recovered.reconcile(kind, ignored -> new OperationAttemptRunner.Reconciliation.Wait());
      assertFalse(recovered.persistenceFailure().toCompletableFuture().isDone());
      recovered.beginClosing(); assertTrue(recovered.awaitDrained(Duration.ZERO));
    }
  }

  @Test
  void WD55CompletionWinningFirstKeepsItsImmutableReceiptAfterWithdrawalRequest() throws Exception {
    var kind = forgetKind();
    try (var store = new SqliteOperationStore(directory.resolve("completed.db"), clock, step -> {})) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(kind));
      var attempt = runner.accept(request(kind, OPERATION));
      runner.start(attempt, handle -> {
        handle.checkpoint(cursor(progress("READY", "NONE")), 0, 0);
        Object completion = finish(runner, handle, sequence(snapshot(store)));
        call(completion, "publish", new Class<?>[0]);
        return OperationExecution.finished(OperationResult.success("complete"));
      });
      var complete = store.find(attempt.accepted().key()).orElseThrow();
      assertEquals(OperationState.COMPLETE, complete.state());
      call(runner, "requestForgetWithdrawal", new Class<?>[] {String.class}, complete.key());
      assertEquals(complete, store.find(complete.key()).orElseThrow());
      assertFalse(JSON.readTree(complete.checkpointCursor()).path("withdrawalRequested").asBoolean());
      assertTrue(store.finish(complete.id(), OperationState.CANCELLED,
          new OperationReceipt("forget_withdrawn", null)).isEmpty());
      assertEquals(complete, store.find(complete.key()).orElseThrow());
    }
  }
}
