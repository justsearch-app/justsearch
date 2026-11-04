/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.chat.AnswersPausedException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class IndexingAnswerAdmissionTest {
  @Test
  void pauseRefusesNewAnswersAndQueuedAnswersButFinishesAnIssuedLease() {
    var paused = new AtomicBoolean();
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    tracker.setAnswersPaused(paused::get);
    try (var streaming = tracker.admit(GenerationTracker.Kind.GENERATE, null);
        var queued = tracker.queue(GenerationTracker.Kind.GENERATE, null)) {
      paused.set(true);
      var refusal = assertThrows(AnswersPausedException.class,
          () -> tracker.admit(GenerationTracker.Kind.GENERATE, null));
      assertEquals("Answers paused while indexing", refusal.getMessage());
      assertThrows(AnswersPausedException.class, queued::admit);
      streaming.tokenProduced();
      assertTrue(tracker.generating(), "already admitted answers finish");
      assertEquals(1, tracker.inFlight());
      for (var kind : new GenerationTracker.Kind[] {
          GenerationTracker.Kind.AUX, GenerationTracker.Kind.ASSIST, GenerationTracker.Kind.VDU}) {
        assertDoesNotThrow(() -> tracker.admit(kind, null).close(), kind.name());
      }
    }
    assertEquals(0, tracker.inFlight());
    assertEquals(0, tracker.queued());
    paused.set(false);
    assertDoesNotThrow(() -> tracker.admit(GenerationTracker.Kind.GENERATE, null).close());
  }
}
