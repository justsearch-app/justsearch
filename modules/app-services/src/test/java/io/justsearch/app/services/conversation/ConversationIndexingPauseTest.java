/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.chat.AnswersPausedException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class ConversationIndexingPauseTest {
  @Test
  void pausedGenerationHasAnExplicitStreamOutcome() {
    var event = ConversationEngine.refusalEvent(new CompletionException(new AnswersPausedException()));
    assertNotNull(event);
    assertEquals("error", event.name());
    assertEquals("CHAT_UNAVAILABLE", event.payload().get("errorCode"));
    assertEquals("INDEXING", event.payload().get("reason"));
    assertEquals("Answers paused while indexing", event.payload().get("error"));
    assertEquals(false, event.payload().get("forceAllowed"));
  }
}
