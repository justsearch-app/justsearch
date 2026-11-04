/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.api.chat.GenerationUnavailableException;
import io.justsearch.core.context.EngineContext;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class IndexingSmokeProbeAdmissionTest {
  @Test
  void internalSmokeProbeBypassesBothPauseChecksButStillHonoursDrain() {
    var paused = new AtomicBoolean();
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    tracker.setAnswersPaused(paused::get);
    var probe = work(EngineContext.ClientKind.INTERNAL, "post-install-smoke-test",
        "SYSTEM_INTERNAL", "TRUSTED");
    try (var queued = tracker.queue(GenerationTracker.Kind.GENERATE, probe)) {
      paused.set(true);
      try (var lease = assertDoesNotThrow(queued::admit)) {
        assertEquals(1, tracker.inFlight());
      }
    }
    assertDoesNotThrow(() -> tracker.admit(GenerationTracker.Kind.GENERATE, probe).close());
    assertThrows(AnswersPausedException.class,
        () -> tracker.admit(GenerationTracker.Kind.GENERATE, null));
    try (var drain = tracker.closeIfIdle()) {
      assertThrows(GenerationUnavailableException.class,
          () -> tracker.admit(GenerationTracker.Kind.GENERATE, probe));
    }
    assertEquals(0, tracker.inFlight());
    assertEquals(0, tracker.queued());
  }

  @ParameterizedTest
  @CsvSource({
      "WEBVIEW, post-install-smoke-test, BUTTON, TRUSTED",
      "MCP_CLIENT, post-install-smoke-test, MCP, UNTRUSTED",
      "INTERNAL, post-install-smoke-test, HTTP_API, UNTRUSTED",
      "INTERNAL, post-install-smoke-test, AGENT_LOOP, UNTRUSTED",
      "INTERNAL, post-install-smoke-test, SYSTEM_INTERNAL, UNTRUSTED",
      "WEBVIEW, post-install-smoke-test, SYSTEM_INTERNAL, TRUSTED",
      "INTERNAL, ordinary-question, SYSTEM_INTERNAL, TRUSTED"
  })
  void clientLabelsAndOtherInternalWorkCannotExemptAnAnswer(
      EngineContext.ClientKind kind, String owner, String transport, String tier) {
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    tracker.setAnswersPaused(() -> true);
    assertThrows(AnswersPausedException.class,
        () -> tracker.admit(GenerationTracker.Kind.GENERATE, work(kind, owner, transport, tier)));
    assertEquals(0, tracker.inFlight());
    assertEquals(0, tracker.queued());
  }

  private static EngineWorkHandle work(
      EngineContext.ClientKind kind, String owner, String transport, String tier) {
    var work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(new EngineContext(kind, owner, Optional.empty(), Optional.empty(),
        tier, transport, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND));
    return work;
  }
}
