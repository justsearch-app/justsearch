/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class VerifierVduBreakerContractTest {
  @Test void s2FatalBelowThresholdFailedTrialReopensImmediately() throws Exception {
    var breaker = new EngineCircuitBreaker(5, Duration.ofMinutes(1));
    breaker.recordFailure(new RuntimeException("native_error: server crashed"));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
    coolDown(breaker);
    assertDoesNotThrow(breaker::requireClosed, "cool-down must admit a recovery trial");
    breaker.recordFailure(new RuntimeException("generation error during recovery"));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state(),
        "S2: a nonfatal failed recovery trial must reopen after an original fatal trip below threshold");
    assertThrows(BackendException.class, breaker::requireClosed);
    coolDown(breaker);
    assertDoesNotThrow(breaker::requireClosed);
    breaker.recordSuccess();
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
  }

  @Test void p1FiveConsecutiveFailuresAndImmediateFatalStillTrip() {
    var breaker = new EngineCircuitBreaker(5, Duration.ofMinutes(1));
    for (int i = 0; i < 4; i++) {
      breaker.recordFailure(new RuntimeException("timeout"));
      assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
    }
    breaker.recordFailure(new RuntimeException("generation failed"));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
    breaker.reset();
    breaker.recordFailure(new RuntimeException("SIGSEGV"));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
  }

  @SuppressWarnings("unchecked")
  private void coolDown(EngineCircuitBreaker breaker) throws Exception {
    Field field = EngineCircuitBreaker.class.getDeclaredField("openedAt"); field.setAccessible(true);
    ((AtomicReference<Instant>) field.get(breaker)).set(Instant.now().minusSeconds(61));
  }
}
