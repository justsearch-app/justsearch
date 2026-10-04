package io.justsearch.systemtests.harness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixture's reason-code fail-fast (tempdoc 825, charter item 3). Cheap and in-process: the
 * discrimination it encodes is the whole point of the new terminal code, and it must not need a
 * 4-minute unrecoverable-boot E2E to stay honest.
 *
 * <p>Tempdoc 836 deliberately did NOT fail fast on {@code index.failed}: that code is emitted
 * mid-recovery too, so keying on it would abort runs that were about to succeed — exactly what the
 * green {@code WorkerBootRecoveryE2ETest} run does, 26s in, with the pin visible on the way.
 */
@DisplayName("IsolatedBackendFixture: fail fast only on the TERMINAL worker reason")
final class IsolatedBackendFixtureFailFastTest {

  private static final String TERMINAL =
      "{\"components\":{\"worker\":{\"state\":\"LIFECYCLE_STATE_ERROR\","
          + "\"reason_code\":\"component.recovery_exhausted\"}}}";
  private static final String RECOVERABLE =
      "{\"components\":{\"worker\":{\"state\":\"LIFECYCLE_STATE_ERROR\","
          + "\"reason_code\":\"index.failed\"}}}";

  @Test
  @DisplayName("the terminal code aborts the wait immediately, naming the cause")
  void terminalReasonAborts() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> IsolatedBackendFixture.failFastOnTerminalWorkerReason(TERMINAL, "worker READY"));

    assertTrue(
        e.getMessage().contains("component.recovery_exhausted"),
        "the failure must name the cause, not read as a bare timeout: " + e.getMessage());
    assertTrue(e.getMessage().contains("worker READY"), "…and what it was waiting for");
  }

  @Test
  @DisplayName("the recoverable pin does NOT abort — recovery may still be in flight")
  void recoverablePinKeepsWaiting() {
    assertDoesNotThrow(
        () -> IsolatedBackendFixture.failFastOnTerminalWorkerReason(RECOVERABLE, "worker READY"));
  }

  @Test
  @DisplayName("a null or unrelated body is not a reason to abort")
  void benignBodiesKeepWaiting() {
    assertDoesNotThrow(() -> IsolatedBackendFixture.failFastOnTerminalWorkerReason(null, "x"));
    assertDoesNotThrow(
        () -> IsolatedBackendFixture.failFastOnTerminalWorkerReason("<no response>", "x"));
  }
}
