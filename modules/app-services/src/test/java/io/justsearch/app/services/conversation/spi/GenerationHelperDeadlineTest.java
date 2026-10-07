/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.GenerationDeadline;
import io.justsearch.app.services.worker.QueryUnderstandingService;
import io.justsearch.core.context.EngineContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** V3/C3: the actual helper captures a deadline at submission, before executor/lane waiting. */
class GenerationHelperDeadlineTest {
  @Test void queryUnderstandingSubmitsItsOwnBudgetWithoutChangingParent() throws Exception {
    var deadline = new AtomicLong();
    var ai = ai(deadline);
    var context = io.justsearch.app.services.intent.EngineProvenance.internal("deadline-test",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    var helper = new QueryUnderstandingService(ai, () -> true);
    var result = helper.extract("question", null, context).get();
    assertNull(result, "busy optional helper uses its ordinary fallback");
    long remaining = deadline.get() - System.nanoTime();
    assertTrue(remaining > 0 && remaining <= Duration.ofSeconds(8).toNanos(), "QU must carry its eight-second exchange deadline");
    assertEquals(EngineContext.Urgency.FOREGROUND, context.urgency());
    assertTrue(GenerationDeadline.capture(Duration.ofHours(1)) - System.nanoTime() > Duration.ofMinutes(59).toNanos(),
        "the optional scope must not narrow subsequent parent work");
  }

  @Test void rewriteSubmitsItsOwnBudgetAndFallsBackToRawQuestion() {
    var deadline = new AtomicLong(); var ai = ai(deadline);
    var context = mock(ConversationContext.class);
    when(context.requestBody()).thenReturn(Map.of("question", "why that?", "context",
        List.of(Map.of("role", "user", "content", "ports"), Map.of("role", "assistant", "content", "ephemeral"))));
    when(context.attributes()).thenReturn(new HashMap<>());
    var result = new QueryRewriteInjector(() -> ai, Duration.ofMillis(100)).inject(context);
    assertTrue(result.events().isEmpty()); assertTrue(context.attributes().isEmpty());
    long remaining = deadline.get() - System.nanoTime();
    assertTrue(remaining > 0 && remaining <= Duration.ofMillis(100).toNanos(), "rewrite must carry its own short exchange deadline");
  }

  private static OnlineAiService ai(AtomicLong deadline) {
    var ai = mock(OnlineAiService.class); when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any())).thenAnswer(call -> {
      deadline.set(GenerationDeadline.capture(Duration.ofDays(1)));
      return CompletableFuture.failedFuture(GenerationDeadline.busy());
    });
    return ai;
  }
}
