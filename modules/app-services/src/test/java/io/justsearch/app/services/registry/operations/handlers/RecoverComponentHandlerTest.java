package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.bootstrap.BootstrapLateBindings;
import io.justsearch.app.services.worker.ComponentRecoveryAuthority;
import org.junit.jupiter.api.Test;

final class RecoverComponentHandlerTest {

  @Test
  void observesAuthorityPublishedAfterHandlerConstruction() {
    var lateBindings = new BootstrapLateBindings();
    var handler = new RecoverComponentHandler(lateBindings::componentRecoveryAuthority);

    OperationResult unbound = handler.execute("{\"name\":\"index\"}", TestEngineContexts.internal());
    assertFailure(unbound, ApiErrorCode.SERVICE_UNAVAILABLE, true);

    lateBindings.setComponentRecoveryAuthority(
        name -> ComponentRecoveryAuthority.Outcome.ACCEPTED);
    OperationResult accepted = handler.execute("{\"name\":\" index \"}", TestEngineContexts.internal());
    assertTrue(accepted.success());
    assertEquals("index", accepted.structuredData().get("component"));
    assertEquals("ACCEPTED", accepted.structuredData().get("recovery"));
  }

  @Test
  void mapsEveryDeclinedOutcomeToItsHttpErrorContract() {
    assertOutcome(ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING,
        ApiErrorCode.ADMISSION_ENGINE_LIMIT, true);
    assertOutcome(ComponentRecoveryAuthority.Outcome.EXHAUSTED,
        ApiErrorCode.WORKER_RECOVERY_EXHAUSTED, false);
    assertOutcome(ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE,
        ApiErrorCode.INVALID_STATE, false);
    assertOutcome(ComponentRecoveryAuthority.Outcome.UNKNOWN_COMPONENT,
        ApiErrorCode.NOT_FOUND, false);
    assertOutcome(ComponentRecoveryAuthority.Outcome.OWNER_UNAVAILABLE,
        ApiErrorCode.SERVICE_UNAVAILABLE, true);
  }

  @Test
  void mapsExecutorAdmissionRefusalToTheSameEngineLimitContract() {
    var refusal = new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 1);
    var handler =
        new RecoverComponentHandler(
            () ->
                name -> {
                  throw refusal;
                });

    // Merge f09566a32 preserves the typed refusal for the common error writer.
    var thrown = assertThrows(EngineAdmissionException.class,
        () -> handler.execute("{\"name\":\"index\"}", TestEngineContexts.internal()));
    assertSame(refusal, thrown);
    assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, thrown.reason());
    assertEquals(1, thrown.retryAfterSeconds());
  }

  @Test
  void unexpectedAuthorityBugsPropagateToTheExecutorsNormalFailurePath() {
    RuntimeException supplierFailure = new IllegalStateException("late-binding bug");
    var brokenSupplier =
        new RecoverComponentHandler(
            () -> {
              throw supplierFailure;
            });
    assertSame(
        supplierFailure,
        assertThrows(
            RuntimeException.class,
            () -> brokenSupplier.execute("{\"name\":\"index\"}", TestEngineContexts.internal())));

    RuntimeException authorityFailure = new IllegalArgumentException("authority bug");
    var brokenAuthority =
        new RecoverComponentHandler(
            () ->
                name -> {
                  throw authorityFailure;
                });
    assertSame(
        authorityFailure,
        assertThrows(
            RuntimeException.class,
            () -> brokenAuthority.execute("{\"name\":\"index\"}", TestEngineContexts.internal())));
  }

  @Test
  void missingOrNonStringNameCannotReachAuthority() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var handler =
        new RecoverComponentHandler(
            () ->
                name -> {
                  calls.incrementAndGet();
                  return ComponentRecoveryAuthority.Outcome.ACCEPTED;
                });

    for (String json : java.util.List.of("{}", "{\"name\":\"\"}", "{\"name\":4}", "nope")) {
      assertFailure(handler.execute(json, TestEngineContexts.internal()),
          ApiErrorCode.INVALID_STATE, false);
    }
    assertEquals(0, calls.get());
  }

  private static void assertOutcome(ComponentRecoveryAuthority.Outcome outcome,
      ApiErrorCode expected, boolean retryable) {
    var handler = new RecoverComponentHandler(() -> name -> outcome);
    assertFailure(handler.execute("{\"name\":\"index\"}", TestEngineContexts.internal()),
        expected, retryable);
  }

  private static void assertFailure(OperationResult result, ApiErrorCode code, boolean retryable) {
    assertFalse(result.success());
    assertEquals(code.name(), result.errorCode().orElseThrow());
    assertEquals(retryable, result.retryable().orElseThrow());
  }
}
