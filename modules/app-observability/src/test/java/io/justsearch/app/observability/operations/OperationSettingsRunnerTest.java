/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runner-owned settings commitment regressions over a real SQLite operation store. */
final class OperationSettingsRunnerTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
  private static final Set<OperationKind> SETTINGS_KINDS =
      Set.of(OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE);
  private static final UiSettings CANDIDATE = new UiSettings();
  private static final String PRIOR_KEY = "0194f72c-0000-7000-8000-000000000001";

  @TempDir Path temp;

  @Test
  void reservationArmsExpectedRevisionAndPublishesOnlyAfterOwnerCommit() throws Exception {
    try (var store = store("commit")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var request = request(OperationKind.SETTINGS_APPLY);
      var attempt = runner.accept(request);

      var result = runner.start(attempt, handle -> runner.applySettings(handle, witness(4), CANDIDATE)
          .success() ? OperationExecution.finished(OperationResult.success("handler"))
              : OperationExecution.finished(OperationResult.failure("unexpected")));

      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().join().state());
      assertNull(owner.markerObservations.get(0));
      assertEquals(4L, owner.markerObservations.get(1));
      assertEquals(List.of("reserve", "apply", "release"), owner.events);
      assertEquals(OperationState.COMPLETE, owner.releaseStates.getFirst());
      assertEquals(5L, result.response().structuredData().get("acceptedRevision"));
      assertEquals(true, result.response().structuredData().get("restartScheduled"));
      assertEquals(Map.of("theme", "dark"), result.response().structuredData().get("ui"));
    }
  }

  @Test
  void successfulSettingsObservationCannotOverrideCommittedFieldsOrReappearOnRetry() throws Exception {
    try (var store = store("observed-response")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var request = request(OperationKind.SETTINGS_APPLY);
      var result = runner.start(runner.accept(request), handle -> {
        runner.applySettings(handle, witness(4), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("handler observation", Map.of(
            "engineState", "Down", "chatEnabled", true, "operationKey", "untrusted observation",
            "acceptedRevision", -1L, "restartScheduled", false, "ui", Map.of("theme", "wrong"))));
      });
      assertEquals("Down", result.response().structuredData().get("engineState"));
      assertEquals(true, result.response().structuredData().get("chatEnabled"));
      assertEquals(request.key(), result.response().structuredData().get("operationKey"));
      assertEquals(5L, result.response().structuredData().get("acceptedRevision"));
      assertEquals(true, result.response().structuredData().get("restartScheduled"));
      assertEquals(Map.of("theme", "dark"), result.response().structuredData().get("ui"));
      assertEquals(OperationState.COMPLETE, result.record().state());
      var retry = runner.start(runner.accept(request), handle -> { throw new AssertionError("Must not resample observations"); });
      assertEquals(5L, retry.response().structuredData().get("acceptedRevision"));
      assertFalse(retry.response().structuredData().containsKey("engineState"));
      assertFalse(retry.response().structuredData().containsKey("chatEnabled"));
    }
  }

  @Test
  void fatalObservationProjectionRetainsCommittedFenceAndRequestsRestart() throws Exception {
    try (var store = store("fatal-observation")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var fatal = new OutOfMemoryError("simulated response projection failure");
      var observation = org.mockito.Mockito.mock(OperationResult.class);
      org.mockito.Mockito.when(observation.success()).thenReturn(true);
      org.mockito.Mockito.when(observation.structuredData()).thenThrow(fatal);
      assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(4), CANDIDATE);
        return OperationExecution.finished(observation);
      })));
      assertEquals(1, owner.retainCalls);
      assertEquals(0, owner.releaseCalls);
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
    }
  }

  @Test
  void failedSettingsBodyCannotPublishItsObservationAfterCommit() throws Exception {
    try (var store = store("failed-observation")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var result = runner.start(runner.accept(request(OperationKind.SETTINGS_APPLY)), handle -> {
        runner.applySettings(handle, witness(4), CANDIDATE);
        return OperationExecution.finished(new OperationResult(false, "failed observation", Optional.empty(),
            Map.of("engineState", "Unknown"), Optional.of("OBSERVATION_FAILED"), Map.of(), Optional.of(false)));
      });
      assertTrue(result.response().success());
      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(5L, result.response().structuredData().get("acceptedRevision"));
      assertFalse(result.response().structuredData().containsKey("engineState"));
    }
  }

  @Test
  void foreignFabricatedRetainedAndOffBodyThreadHandlesAreRefusedAndHandlerCannotCastControl()
      throws Exception {
    try (var store = store("handles")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var firstRunner = runner(store, owner);
      var secondRunner = runner(store, owner);
      var first = firstRunner.accept(request(OperationKind.SETTINGS_APPLY));
      var second = secondRunner.accept(request(OperationKind.OPERATION));
      var secondFuture = new CompletableFuture<OperationResult>();
      AtomicReference<OperationRecordHandle> foreign = new AtomicReference<>();
      secondRunner.start(second, handle -> {
        foreign.set(handle);
        return new OperationExecution(OperationResult.success("held"), secondFuture);
      });
      AtomicReference<OperationRecordHandle> retained = new AtomicReference<>();

      firstRunner.start(first, handle -> {
        retained.set(handle);
        assertThrows(ClassCastException.class,
            () -> SettingsCommitOwner.AttemptControl.class.cast(handle));
        assertThrows(IllegalArgumentException.class,
            () -> firstRunner.applySettings(foreign.get(), witness(0), CANDIDATE));
        OperationRecordHandle fabricated = new OperationRecordHandle() {
          @Override public long id() { return handle.id(); }
          @Override public String key() { return handle.key(); }
          @Override public void checkpoint(String cursor, long completed, long failed) {}
        };
        assertThrows(IllegalArgumentException.class,
            () -> firstRunner.applySettings(fabricated, witness(0), CANDIDATE));
        AtomicReference<Throwable> offThreadFailure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
          try {
            firstRunner.applySettings(handle, witness(0), CANDIDATE);
          } catch (Throwable failure) {
            offThreadFailure.set(failure);
          }
        });
        thread.start();
        try { thread.join(5000); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        assertFalse(thread.isAlive(), "Off-thread refusal must not wait for body completion");
        assertInstanceOf(IllegalArgumentException.class, offThreadFailure.get());
        firstRunner.applySettings(handle, witness(0), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("done"));
      });
      assertThrows(IllegalArgumentException.class,
          () -> firstRunner.applySettings(retained.get(), witness(0), CANDIDATE));
      secondFuture.complete(OperationResult.success("done"));
      assertEquals(OperationState.COMPLETE,
          second.completion().toCompletableFuture().join().state());
    }
  }

  @Test
  void duplicateApplyIsRefusedAfterTheSingleOwnerCall() throws Exception {
    try (var store = store("duplicate")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var request = request(OperationKind.SETTINGS_APPLY);
      var attempt = runner.accept(request);
      var result = runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(2), CANDIDATE);
        assertThrows(IllegalStateException.class,
            () -> runner.applySettings(handle, witness(2), CANDIDATE));
        return OperationExecution.finished(OperationResult.success("done"));
      });
      assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().join().state());
      assertEquals(1, owner.applyCalls);
      var retry = runner.start(runner.accept(request), handle -> {
        fail("A completed settings key must not apply again");
        return OperationExecution.finished(OperationResult.success("unreachable"));
      });
      assertEquals(OperationState.COMPLETE, retry.record().state());
      assertEquals(3L, retry.response().structuredData().get("acceptedRevision"));
      assertEquals(1, owner.applyCalls);
    }
  }

  @Test
  void completedSettingsRetryAfterStoreReopenReturnsOriginalRevisionWithoutOwnerApply() throws Exception {
    String name = "reopen";
    var request = request(OperationKind.SETTINGS_APPLY);
    try (var store = store(name)) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var attempt = runner.accept(request);
      runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(10), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("done"));
      });
      assertEquals(1, owner.applyCalls);
    }
    try (var reopened = store(name)) {
      var owner = new FakeOwner(reopened, ApplyMode.COMMIT);
      var runner = runner(reopened, owner);
      var retry = runner.start(runner.accept(request), handle -> {
        fail("A reopened completed settings key must not apply again");
        return OperationExecution.finished(OperationResult.success("unreachable"));
      });
      assertEquals(OperationState.COMPLETE, retry.record().state());
      assertEquals(11L, retry.response().structuredData().get("acceptedRevision"));
      assertEquals(new SettingsWitness(11, request.key()), retry.response().structuredData().get("witness"));
      assertFalse(retry.response().structuredData().containsKey("ui"));
      assertEquals(0, owner.applyCalls);
    }
  }

  @Test
  void openAndFailedSettingsReplayDoNotClaimACommittedWitness() throws Exception {
    try (var store = store("uncommitted-witness")) {
      var owner = new FakeOwner(store, ApplyMode.THROW_BEFORE_COMMIT);
      var runner = runner(store, owner);
      var request = request(OperationKind.SETTINGS_APPLY);
      var accepted = runner.accept(request);
      var open = runner.start(runner.accept(request), ignored -> {
        throw new AssertionError("Existing acceptance cannot execute");
      });
      assertEquals(OperationState.ACCEPTED, open.record().state());
      assertFalse(open.response().structuredData().containsKey("witness"));
      assertThrows(IllegalStateException.class, () -> runner.start(accepted, handle ->
          OperationExecution.finished(runner.applySettings(handle, witness(7), CANDIDATE))));
      var failed = runner.start(runner.accept(request), ignored -> {
        throw new AssertionError("Failed acceptance cannot execute");
      });
      assertEquals(OperationState.FAILED, failed.record().state());
      assertFalse(failed.response().structuredData().containsKey("witness"));
      assertFalse(failed.response().structuredData().containsKey("acceptedRevision"));
    }
  }

  @Test
  void ownerRuntimeExceptionBeforeCommitFailsTheOperation() throws Exception {
    try (var store = store("before-commit")) {
      var owner = new FakeOwner(store, ApplyMode.THROW_BEFORE_COMMIT);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(IllegalStateException.class, () -> runner.start(attempt,
          handle -> {
            runner.applySettings(handle, witness(7), CANDIDATE);
            return OperationExecution.finished(OperationResult.success("unreachable"));
          }));
      var row = store.find(attempt.accepted().key()).orElseThrow();
      assertEquals(OperationState.FAILED, row.state());
      assertEquals("UNCAUGHT_EXCEPTION", row.failureReason());
      assertEquals(List.of("reserve", "apply", "release"), owner.events);
      assertEquals(OperationState.FAILED, owner.releaseStates.getFirst());
    }
  }

  @Test
  void ownerRuntimeExceptionAfterCommitReturnsCompleteSettingsReceipt() throws Exception {
    try (var store = store("after-commit")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT_THEN_THROW);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var result = runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(8), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("unreachable"));
      });
      assertEquals(OperationState.COMPLETE, result.record().state());
      assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().join().state());
      assertTrue(result.response().success());
      assertEquals(9L, result.response().structuredData().get("acceptedRevision"));
      assertNull(owner.markerObservations.get(0));
      assertEquals(8L, owner.markerObservations.get(1));
      assertEquals(List.of("reserve", "apply", "release"), owner.events);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(PendingSettingsOutcome.class)
  void synchronousSettingsVerdictFinalizesBeforePendingAdapterStage(PendingSettingsOutcome outcome)
      throws Exception {
    String name = "pending-" + outcome.name().toLowerCase();
    ApplyMode mode = switch (outcome) {
      case COMMITTED -> ApplyMode.COMMIT;
      case FAILED -> ApplyMode.THROW_BEFORE_COMMIT;
      case UNCERTAIN -> ApplyMode.UNCERTAIN_THEN_THROW;
    };
    try (var store = store(name)) {
      var owner = new FakeOwner(store, mode);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var pending = new CompletableFuture<OperationResult>();
      if (outcome == PendingSettingsOutcome.UNCERTAIN) {
        assertThrows(RuntimeException.class, () -> runner.start(attempt, handle -> {
          try {
            runner.applySettings(handle, witness(12), CANDIDATE);
          } catch (RuntimeException swallowed) {
            // The pending adapter stage cannot turn an uncertain owner into success.
          }
          return new OperationExecution(OperationResult.success("pending"), pending);
        }));
        assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
        assertTrue(owner.retainCalls >= 1);
      } else {
        OperationAttemptRunner.Result result = assertDoesNotThrow(() -> runner.start(attempt, handle -> {
          try {
            runner.applySettings(handle, witness(12), CANDIDATE);
          } catch (RuntimeException swallowed) {
            assertEquals(PendingSettingsOutcome.FAILED, outcome);
          }
          return new OperationExecution(OperationResult.success("pending"), pending);
        }));
        OperationState expected = outcome == PendingSettingsOutcome.COMMITTED
            ? OperationState.COMPLETE : OperationState.FAILED;
        assertEquals(expected, result.record().state());
        assertTrue(result.completion().toCompletableFuture().isDone());
        assertEquals(expected, store.find(attempt.accepted().key()).orElseThrow().state());
        assertEquals(1, owner.releaseCalls);
      }
      pending.complete(OperationResult.success("late stage"));
      assertEquals(outcome == PendingSettingsOutcome.UNCERTAIN
          ? OperationState.RUNNING
          : outcome == PendingSettingsOutcome.COMMITTED ? OperationState.COMPLETE : OperationState.FAILED,
          store.find(attempt.accepted().key()).orElseThrow().state());
    }
  }

  @Test
  void sqlArmFailureTerminalizesBeforeMatchingOwnerRelease() throws Exception {
    try (var store = store("arm-failure")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      execute(storePath("arm-failure"),
          "CREATE TRIGGER refuse_settings_arm BEFORE UPDATE OF accepted_settings_revision ON operations "
              + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      assertThrows(OperationStoreException.class, () -> runner.start(attempt,
          handle -> {
            runner.applySettings(handle, witness(3), CANDIDATE);
            return OperationExecution.finished(OperationResult.success("unreachable"));
          }));
      assertEquals(OperationState.FAILED, store.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(List.of("reserve", "release"), owner.events);
      assertEquals(OperationState.FAILED, owner.releaseStates.getFirst());
      assertEquals(0, owner.applyCalls);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = ApplyMode.class,
      names = {"THROW_BEFORE_COMMIT", "COMMIT_THEN_THROW"})
  void terminalPersistenceFailureRetainsFenceRequestsRestartAndLeavesRunning(ApplyMode mode)
      throws Exception {
    String name = mode == ApplyMode.THROW_BEFORE_COMMIT ? "terminal-failed" : "terminal-complete";
    try (var store = store(name)) {
      var owner = new FakeOwner(store, mode);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      String terminal = mode == ApplyMode.THROW_BEFORE_COMMIT ? "FAILED" : "COMPLETE";
      execute(storePath(name),
          "CREATE TRIGGER refuse_settings_terminal BEFORE UPDATE OF state ON operations "
              + "WHEN NEW.state = '" + terminal + "' BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      assertThrows(RuntimeException.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(1), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(0, owner.releaseCalls);
      assertTrue(owner.retainCalls >= 1);
      assertTrue(runner.persistenceFailure().toCompletableFuture().isDone());
      assertEquals(OperationState.valueOf(terminal),
          runner.persistenceFailure().toCompletableFuture().join().intendedState());
    }
  }

  @Test
  void errorAfterReservationRetainsFenceRequestsRestartAndLeavesRunning() throws Exception {
    try (var store = store("error")) {
      var owner = new FakeOwner(store, ApplyMode.THROW_ERROR);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(AssertionError.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(2), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(1, owner.retainCalls);
      assertFalse(owner.events.contains("release"));
    }
  }

  @Test
  void uncertainOwnerCannotBeConvertedToFailedOrCompleteWhenHandlerSwallowsError() throws Exception {
    try (var store = store("uncertain")) {
      var owner = new FakeOwner(store, ApplyMode.UNCERTAIN_THEN_THROW);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(RuntimeException.class, () -> runner.start(attempt, handle -> {
        try {
          runner.applySettings(handle, witness(6), CANDIDATE);
        } catch (RuntimeException swallowed) {
          // The handler cannot turn an owner uncertainty into a terminal outcome.
        }
        return OperationExecution.finished(OperationResult.success("swallowed"));
      }));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertTrue(owner.retainCalls >= 1);
      assertTrue(runner.persistenceFailure().toCompletableFuture().isDone());
      assertEquals(OperationState.RUNNING,
          runner.persistenceFailure().toCompletableFuture().join().intendedState());
    }
  }

  @Test
  void bootInspectsAllSettingsRowsBeforePerRowReconciliation() throws Exception {
    try (var store = store("boot")) {
      var first = store.accept(OperationKeys.generate(CLOCK), descriptor(OperationKind.SETTINGS_APPLY),
          context(), null).record();
      var second = store.accept(OperationKeys.generate(CLOCK), descriptor(OperationKind.RECONFIGURE),
          context(), null).record();
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      new OperationAttemptRunnerImpl(store, CLOCK, SETTINGS_KINDS, owner);
      assertEquals("inspect:2", owner.events.getFirst());
      assertEquals(Set.of(first.key(), second.key()), Set.copyOf(owner.inspectedKeys));
      assertEquals(2, owner.reconciledKeys.size());
      assertTrue(owner.events.subList(1, owner.events.size()).stream()
          .allMatch(event -> event.startsWith("reconcile:")));
      assertEquals(OperationState.ACCEPTED, store.find(first.key()).orElseThrow().state());
      assertEquals(OperationState.ACCEPTED, store.find(second.key()).orElseThrow().state());
    }
  }

  @Test
  void swallowedFatalStillRequestsRestartBeforeBodyReturnsAndNeverTerminalizes() throws Exception {
    try (var store = store("swallowed-fatal")) {
      var owner = new FakeOwner(store, ApplyMode.THROW_ERROR);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(IllegalStateException.class, () -> runner.start(attempt, handle -> {
        assertThrows(AssertionError.class, () -> runner.applySettings(handle, witness(0), CANDIDATE));
        assertEquals(1, owner.retainCalls, "Restart must be requested even if the handler does not return promptly");
        return OperationExecution.finished(OperationResult.success("swallowed fatal"));
      }));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(1, owner.retainCalls);
      assertEquals(0, owner.releaseCalls);
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void typedRefusalSurvivesThrownOrSwallowedHandlerFailure(boolean swallowed) throws Exception {
    try (var store = store("typed-" + swallowed)) {
      var owner = new FakeOwner(store, ApplyMode.TYPED_REFUSAL);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var result = runner.start(attempt, handle -> {
        try { runner.applySettings(handle, witness(4), CANDIDATE); }
        catch (SettingsCommitOwner.Refused refused) { if (!swallowed) throw refused; }
        return OperationExecution.finished(OperationResult.success("wrong success"));
      });
      assertFalse(result.response().success());
      assertEquals("VERSION_CONFLICT", result.response().errorCode().orElseThrow());
      assertEquals(Map.of("currentRevision", 5L), result.response().errorDetails());
      assertEquals(Optional.of(false), result.response().retryable());
      assertEquals("VERSION_CONFLICT", result.record().failureReason());
      assertEquals(OperationState.FAILED, owner.releaseStates.getFirst());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void restartCallbackFailurePreservesPrimaryFatalAndCompletionObservation(boolean restartIsFatal) throws Exception {
    try (var store = store("restart-" + restartIsFatal)) {
      var owner = new FakeOwner(store, ApplyMode.THROW_ERROR);
      owner.restartFailure = restartIsFatal ? new LinkageError("restart failed") : new IllegalStateException("restart failed");
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var fatal = assertThrows(AssertionError.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(0), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      assertEquals("fatal after reservation", fatal.getMessage());
      assertArrayEquals(new Throwable[] {owner.restartFailure}, fatal.getSuppressed());
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
    }
  }

  @Test
  void fatalBeforeReservationDoesNotRequestSettingsRestart() throws Exception {
    try (var store = store("no-reservation")) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(AssertionError.class, () -> runner.start(attempt, handle -> { throw new AssertionError("before settings"); }));
      assertEquals(0, owner.retainCalls);
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
      assertNull(store.find(attempt.accepted().key()).orElseThrow().expectedSettingsRevision());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = ApplyMode.class, names = {"NULL_RECEIPT", "NO_RECEIPT"})
  void missingCommittedReceiptIsUncertainRatherThanPrecommitFailure(ApplyMode mode) throws Exception {
    try (var store = store("missing-receipt-" + mode.name())) {
      var owner = new FakeOwner(store, mode);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      assertThrows(IllegalStateException.class, () -> runner.start(attempt, handle -> {
        runner.applySettings(handle, witness(0), CANDIDATE);
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(0, owner.releaseCalls);
      assertEquals(1, owner.retainCalls);
    }
  }

  @Test
  void resumedBodyAndAsyncParentMayUseTheirOwnSynchronousSettingsAttempt() throws Exception {
    try (var store = store("resume-child")) {
      var old = store.accept(OperationKeys.generate(CLOCK), descriptor(OperationKind.INGEST), context(), null).record();
      store.start(old.id());
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = new OperationAttemptRunnerImpl(store, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE), owner);
      runner.reconcile(OperationKind.INGEST, row -> new OperationAttemptRunner.Reconciliation.Resume(handle -> {
        var child = runner.accept(request(OperationKind.SETTINGS_APPLY));
        var result = runner.start(child, childHandle -> OperationExecution.finished(runner.applySettings(childHandle, witness(0), CANDIDATE)));
        assertEquals(OperationState.COMPLETE, result.record().state());
        return OperationExecution.finished(OperationResult.success("resumed owner"));
      }));
      assertEquals(OperationState.COMPLETE, store.find(old.key()).orElseThrow().state());
      var parent = runner.accept(request(OperationKind.OPERATION));
      var pending = new CompletableFuture<OperationResult>();
      runner.start(parent, handle -> {
        assertThrows(IllegalStateException.class, () -> runner.applySettings(handle, witness(1), CANDIDATE));
        var child = runner.accept(request(OperationKind.SETTINGS_APPLY));
        var result = runner.start(child, childHandle -> OperationExecution.finished(runner.applySettings(childHandle, witness(1), CANDIDATE)));
        assertEquals(OperationState.COMPLETE, result.record().state());
        return new OperationExecution(OperationResult.success("parent still running"), pending);
      });
      assertEquals(OperationState.RUNNING, store.find(parent.accepted().key()).orElseThrow().state());
      pending.complete(OperationResult.success("parent complete"));
      assertEquals(OperationState.COMPLETE, parent.completion().toCompletableFuture().join().state());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = OperationKind.class, names = {"SETTINGS_APPLY", "RECONFIGURE"})
  void publicRecoveryCannotOverrideFixedOwnerWait(OperationKind kind) throws Exception {
    try (var store = store("fixed-owner-" + kind.name())) {
      var row = store.accept(OperationKeys.generate(CLOCK), descriptor(kind), context(), null).record();
      store.start(row.id());
      store.armSettingsRevision(row.id(), 0);
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      assertThrows(IllegalArgumentException.class, () -> runner.reconcile(kind, previous -> {
        fail("A producer must never get the fixed owner's interrupted row");
        return new OperationAttemptRunner.Reconciliation.Failed(new io.justsearch.app.api.operations.OperationReceipt("wrong_failure", null));
      }));
      assertEquals(OperationState.RUNNING, store.find(row.key()).orElseThrow().state());
      assertEquals(0, owner.releaseCalls);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void composedSettingsKindCannotSucceedWithoutCommitOwner(boolean pending) throws Exception {
    try (var store = store("missing-apply-" + pending)) {
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      var runner = runner(store, owner);
      var attempt = runner.accept(request(OperationKind.SETTINGS_APPLY));
      var completion = new CompletableFuture<OperationResult>();
      if (!pending) completion.complete(OperationResult.success("wrong success"));
      var result = runner.start(attempt, handle -> new OperationExecution(OperationResult.success("wrong success"), completion));
      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("SETTINGS_NOT_APPLIED", result.record().failureReason());
      assertFalse(result.response().success());
      assertNull(result.record().expectedSettingsRevision());
      assertTrue(result.completion().toCompletableFuture().isDone());
      completion.complete(OperationResult.success("later wrong success"));
      assertEquals(OperationState.FAILED, store.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(0, owner.applyCalls);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void bootPassesOnlyAcceptedPreparationOutsideSqlLocksAndKeepsMalformedRowsUnresolved(boolean malformed) throws Exception {
    String name = "recovery-preparation-" + malformed;
    var prepared = new io.justsearch.app.api.operations.OperationStore.Preparation(java.util.UUID.randomUUID(),
        new io.justsearch.app.api.operations.OperationPreparedPayload(false, "{\"frozenEvidence\":\"original\"}"));
    String key = OperationKeys.generate(CLOCK);
    long id;
    try (var store = store(name)) {
      var identity = descriptor(OperationKind.SETTINGS_APPLY);
      store.savePreparation(key, identity, prepared);
      var row = store.acceptPrepared(key, identity, context(), null, prepared.nonce()).record();
      id = row.id();
      store.start(id);
      store.armSettingsRevision(id, 0);
      if (malformed) execute(storePath(name), "UPDATE operations SET preparation_nonce='xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' WHERE id=" + id);
    }
    try (var reopened = store(name); var reads = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var owner = new FakeOwner(reopened, ApplyMode.COMMIT);
      owner.inspectionProbe = () -> {
        try { assertEquals(id, reads.submit(() -> reopened.find(key).orElseThrow().id()).get(3, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (Exception failure) { throw new AssertionError("Owner callback must run outside the SQL lock", failure); }
      };
      runner(reopened, owner);
      assertEquals(1, owner.recoveryInputs.size());
      var input = owner.recoveryInputs.getFirst();
      assertEquals(id, input.row().id());
      assertEquals(Optional.ofNullable(malformed ? null : prepared), input.preparation());
      assertEquals(OperationState.RUNNING, reopened.find(key).orElseThrow().state());
      assertEquals(0, owner.applyCalls);
    }
  }

  @Test
  void acceptedPreparationStorageFailureCannotBecomeAnEmptyRecoveryInput() throws Exception {
    try (var store = store("recovery-storage-failure")) {
      var row = store.accept(OperationKeys.generate(CLOCK), descriptor(OperationKind.SETTINGS_APPLY), context(), null).record();
      store.start(row.id());
      store.armSettingsRevision(row.id(), 0);
      var failingReads = org.mockito.Mockito.spy(store);
      var failure = new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, new java.sql.SQLException("read failed"));
      org.mockito.Mockito.doThrow(failure).when(failingReads).acceptedPreparation(row.id());
      var owner = new FakeOwner(store, ApplyMode.COMMIT);
      assertSame(failure, assertThrows(OperationStoreException.class,
          () -> new OperationAttemptRunnerImpl(failingReads, CLOCK, SETTINGS_KINDS, owner)));
      assertTrue(owner.recoveryInputs.isEmpty());
      assertEquals(OperationState.RUNNING, store.find(row.key()).orElseThrow().state());
    }
  }

  private OperationAttemptRunnerImpl runner(SqliteOperationStore store, FakeOwner owner) {
    var runner = new OperationAttemptRunnerImpl(store, CLOCK, SETTINGS_KINDS, owner);
    owner.events.clear();
    return runner;
  }

  private SqliteOperationStore store(String name) throws Exception {
    return new SqliteOperationStore(storePath(name), CLOCK, step -> {});
  }

  private Path storePath(String name) {
    return temp.resolve("operations-" + name + ".db");
  }

  private static OperationAttemptRunner.Request request(OperationKind kind) {
    return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK), descriptor(kind), context(), null);
  }

  private static OperationDescriptor descriptor(OperationKind kind) {
    return new OperationDescriptor(kind, "core.settings", "{}");
  }

  private static SettingsWitness witness(long revision) {
    return revision == 0 ? new SettingsWitness(0, null) : new SettingsWitness(revision, PRIOR_KEY);
  }

  private static EngineContext context() {
    return new EngineContext(EngineContext.ClientKind.INTERNAL, "settings-test", Optional.empty(),
        Optional.empty(), "system", "SYSTEM_INTERNAL", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.BACKGROUND);
  }

  private static void execute(Path path, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private enum ApplyMode {
    COMMIT, COMMIT_THEN_THROW, THROW_BEFORE_COMMIT, THROW_ERROR, UNCERTAIN_THEN_THROW,
    TYPED_REFUSAL, NULL_RECEIPT, NO_RECEIPT
  }

  private enum PendingSettingsOutcome {
    COMMITTED, FAILED, UNCERTAIN
  }

  private static final class FakeOwner implements SettingsCommitOwner {
    private final SqliteOperationStore store;
    private final ApplyMode mode;
    private final Map<Long, String> keys = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final List<Long> markerObservations = new ArrayList<>();
    private final List<OperationState> releaseStates = new ArrayList<>();
    private final List<String> inspectedKeys = new ArrayList<>();
    private List<RecoveryInput> recoveryInputs = List.of();
    private Runnable inspectionProbe = () -> {};
    private final List<String> reconciledKeys = new ArrayList<>();
    private int applyCalls;
    private int releaseCalls;
    private int retainCalls;
    private Throwable restartFailure;

    private FakeOwner(SqliteOperationStore store, ApplyMode mode) {
      this.store = store;
      this.mode = mode;
    }

    @Override
    public java.util.concurrent.CompletionStage<RecoveryIssue> recoveryIssue() {
      return new CompletableFuture<RecoveryIssue>().minimalCompletionStage();
    }

    @Override
    public Reservation reserve(long id, String key, SettingsWitness expected) {
      events.add("reserve");
      keys.put(id, key);
      markerObservations.add(store.find(key).orElseThrow().expectedSettingsRevision());
      return new Token(id, key, expected);
    }

    @Override
    public void apply(Reservation reservation, UiSettings candidate, AttemptControl control) {
      Token token = (Token) reservation;
      applyCalls++;
      events.add("apply");
      markerObservations.add(store.find(token.key()).orElseThrow().expectedSettingsRevision());
      switch (mode) {
        case TYPED_REFUSAL -> throw new Refused(OperationResult.failure("Revision changed", "VERSION_CONFLICT",
            Map.of("currentRevision", token.expected().acceptedRevision() + 1), false));
        case NULL_RECEIPT -> control.committed(null);
        case NO_RECEIPT -> { /* Simulate a defective owner returning without a commitment verdict. */ }
        case THROW_BEFORE_COMMIT -> throw new IllegalStateException("before commit");
        case THROW_ERROR -> throw new AssertionError("fatal after reservation");
        case UNCERTAIN_THEN_THROW -> {
          control.uncertain();
          throw new IllegalStateException("uncertain owner");
        }
        case COMMIT_THEN_THROW -> {
          control.committed(new Receipt(token.key(), Math.addExact(token.expected().acceptedRevision(), 1),
              OperationResult.success("Prepared settings result", Map.of("restartScheduled", true, "ui", Map.of("theme", "dark")))));
          throw new IllegalStateException("after commit");
        }
        case COMMIT -> control.committed(new Receipt(token.key(), Math.addExact(token.expected().acceptedRevision(), 1),
              OperationResult.success("Prepared settings result", Map.of("restartScheduled", true, "ui", Map.of("theme", "dark")))));
      }
    }

    @Override
    public void releaseAfterTerminal(long id) {
      if (!keys.containsKey(id)) return;
      events.add("release");
      releaseCalls++;
      releaseStates.add(store.find(keys.get(id)).orElseThrow().state());
    }

    @Override
    public void retainForRestart(long id, Throwable failure) {
      events.add("retain");
      retainCalls++;
      if (restartFailure instanceof RuntimeException runtime) throw runtime;
      if (restartFailure instanceof Error fatal) throw fatal;
    }

    @Override
    public void inspectRecovery(List<RecoveryInput> rows) {
      events.add("inspect:" + rows.size());
      inspectedKeys.addAll(rows.stream().map(input -> input.row().key()).toList());
      recoveryInputs = List.copyOf(rows);
      inspectionProbe.run();
    }

    @Override
    public OperationAttemptRunner.Reconciliation reconcile(OperationRecord row) {
      events.add("reconcile:" + row.key());
      reconciledKeys.add(row.key());
      return new OperationAttemptRunner.Reconciliation.Wait();
    }

    @Override
    public Reservation reserveReset(OperationRecord row, io.justsearch.app.api.operations.OperationStore.Preparation accepted) {
      throw new UnsupportedOperationException("Ordinary settings fixture");
    }
    @Override
    public void applyReset(Reservation reservation, AttemptControl control) {
      throw new UnsupportedOperationException("Ordinary settings fixture");
    }
    private record Token(long id, String key, SettingsWitness expected) implements Reservation {
      @Override public long expectedRevision() { return expected.acceptedRevision(); }
    }
  }
}
