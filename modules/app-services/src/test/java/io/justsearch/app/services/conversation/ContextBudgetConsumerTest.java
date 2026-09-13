/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.core.util.ContextBudget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 883 decision 3 — the conversation-side consumers of {@link ContextBudget}.
 *
 * <p>Each assertion below is written so it FAILS on the pre-883 code, not merely so it passes on
 * this one:
 *
 * <ul>
 *   <li>the hierarchical threshold cases straddle the old 5000-token literal in both directions — a
 *       4999-token document at a 4096-token window (old: single-pass into a window that cannot hold
 *       it) and a 6000-token document at 32768 (old: hierarchical for no reason);
 *   <li>the section target is asserted to DIFFER between two windows, which a constant cannot do;
 *   <li>the history-drop case asserts a log record that did not exist at all before (the old loop
 *       {@code break}ed silently).
 * </ul>
 */
class ContextBudgetConsumerTest {

  // ---------------------------------------------------------------------------
  // HierarchicalShapeRunner — the single-pass / map-reduce decision
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a 4999-token document at a 4096-token window does NOT go single-pass")
  void smallWindowForcesHierarchical() {
    RecordingAi ai = new RecordingAi();
    List<SseEvent> events = runHierarchical(document(4999), 4096, ai);

    assertFalse(
        phases(events).contains("standard"),
        "the old 5000-token literal sent this document single-pass into a window whose honest input"
            + " budget is 2304 tokens; phases were "
            + phases(events));
    assertTrue(phases(events).contains("splitting"), "phases were " + phases(events));
  }

  @Test
  @DisplayName("the same document at a 32768-token window DOES go single-pass")
  void largeWindowRaisesTheThreshold() {
    RecordingAi ai = new RecordingAi();
    List<SseEvent> events = runHierarchical(document(6000), 32768, ai);

    assertTrue(
        phases(events).contains("standard"),
        "a 6000-token document fits a 28108-token input budget whole; splitting it is latency spent"
            + " for nothing. phases were "
            + phases(events));
    assertFalse(phases(events).contains("splitting"));
  }

  @Test
  @DisplayName("the section target scales with the window — sections get larger, and fewer")
  void sectionTargetScalesWithTheWindow() {
    int smallSections = sectionCount(runHierarchical(document(40_000), 4096, new RecordingAi()));
    int largeSections = sectionCount(runHierarchical(document(40_000), 32768, new RecordingAi()));
    assertTrue(
        largeSections < smallSections,
        "a larger window must need FEWER map steps for the same document: "
            + smallSections
            + " sections at 4096 vs "
            + largeSections
            + " at 32768");
    assertEquals(
        ContextBudget.of(4096, null, 1024).sectionTarget(),
        1152,
        "pins WHY the counts differ: the target is a fraction of the budget, not 1800");
    assertEquals(ContextBudget.of(32768, null, 1024).sectionTarget(), 4096, "at its ceiling");
  }

  // ---------------------------------------------------------------------------
  // ExternalContextInjector — the history cap, and saying when it bit
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a dropped prior turn is REPORTED, with before/after token counts")
  void historyDropIsLogged() {
    // 12 turns of ~500 tokens each against a 4096-token window: the cap is 576 tokens, so only the
    // most recent turn survives. The old code dropped the other eleven and said nothing.
    List<Map<String, Object>> history = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      history.add(Map.of("role", "user", "content", words(500) + " turn" + i));
    }
    ConversationContext ctx = stubCtx(Map.of("context", history));

    List<ILoggingEvent> logs =
        captureFrom(
            ExternalContextInjector.class,
            () -> {
              InjectorResult result =
                  new ExternalContextInjector(() -> stubAi(4096)).inject(ctx);
              assertTrue(
                  result.messages().size() < history.size(),
                  "the cap must actually bite for this test to mean anything");
            });

    ILoggingEvent drop =
        logs.stream()
            .filter(e -> e.getFormattedMessage().contains("dropped"))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no drop was reported; logs were " + formatted(logs)));
    assertEquals("INFO", drop.getLevel().toString());
    assertTrue(drop.getFormattedMessage().contains("tokens"), drop.getFormattedMessage());
    assertTrue(drop.getFormattedMessage().contains("cap 576"), drop.getFormattedMessage());
  }

  @Test
  @DisplayName("nothing is logged when the whole history fits — a quiet path stays quiet")
  void noDropNoLog() {
    ConversationContext ctx =
        stubCtx(Map.of("context", List.of(Map.of("role", "user", "content", "hello"))));
    List<ILoggingEvent> logs =
        captureFrom(
            ExternalContextInjector.class,
            () -> new ExternalContextInjector(() -> stubAi(32768)).inject(ctx));
    assertTrue(logs.isEmpty(), "logs were " + formatted(logs));
  }

  @Test
  @DisplayName("the history cap is a fraction of the live window, not a flat 1000 tokens")
  void historyCapFollowsTheWindow() {
    List<Map<String, Object>> history = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      history.add(Map.of("role", "user", "content", words(500) + " turn" + i));
    }

    int keptSmall =
        new ExternalContextInjector(() -> stubAi(4096))
            .inject(stubCtx(Map.of("context", history)))
            .messages()
            .size();
    int keptLarge =
        new ExternalContextInjector(() -> stubAi(32768))
            .inject(stubCtx(Map.of("context", history)))
            .messages()
            .size();

    assertTrue(
        keptLarge > keptSmall,
        "a bigger window must buy more history: " + keptSmall + " vs " + keptLarge);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static List<SseEvent> runHierarchical(String content, int window, RecordingAi ai) {
    List<SseEvent> events = new ArrayList<>();
    Supplier<OnlineAiService> aiSupplier = () -> ai.withWindow(window);
    new HierarchicalShapeRunner(aiSupplier, () -> null)
        .run(Map.of("docId", "doc-1", "content", content), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    return events;
  }

  private static List<String> phases(List<SseEvent> events) {
    List<String> out = new ArrayList<>();
    for (SseEvent e : events) {
      if ("progress".equals(e.name())) {
        Map<String, Object> m = e.payload();
        Object phase = m.get("phase");
        if (phase != null) {
          out.add(phase.toString());
        }
      }
    }
    return out;
  }

  /** The runner states its own split: {@code totalStages} on the {@code sections} progress event. */
  private static int sectionCount(List<SseEvent> events) {
    for (SseEvent e : events) {
      if ("progress".equals(e.name()) && "sections".equals(e.payload().get("phase"))) {
        return ((Number) e.payload().get("totalStages")).intValue();
      }
    }
    throw new AssertionError("no sections event; phases were " + phases(events));
  }

  /**
   * A document of approximately {@code approxTokens} estimated tokens.
   *
   * <p>Sized against the CHAR arm of {@link io.justsearch.core.util.TokenEstimation#estimateTokens},
   * not the word arm. The estimator returns {@code max(wordEstimate, charEstimate)} and each
   * {@code "token "} is 6 chars / 1 word, so the char arm ({@code len/4} = 1.5 per word) always
   * dominates the word arm (1.3 per word) for this filler: {@code words(t * 2 / 3)} estimates to
   * {@code t}, and the earlier {@code words(t / 1.3)} estimated to {@code 1.15 * t}.
   *
   * <p>That 15% mattered: it put {@code document(4999)} at 5768 estimated tokens, ABOVE the retired
   * 5000-token literal, so {@link #smallWindowForcesHierarchical} passed on the old code as well as
   * the new one — the exact green-for-the-wrong-reason this test exists to rule out. Verified by
   * restoring the literal and watching both threshold cases go red.
   */
  private static String document(int approxTokens) {
    return words(approxTokens * 2 / 3);
  }

  private static String words(int count) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < count; i++) {
      sb.append("token ");
    }
    return sb.toString();
  }

  private static List<ILoggingEvent> captureFrom(Class<?> type, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(type);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private static String formatted(List<ILoggingEvent> logs) {
    return logs.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
  }

  private static OnlineAiService stubAi(int window) {
    return new RecordingAi().withWindow(window);
  }

  /** Records how many {@code streamChat} calls the runner made, and reports a fixed window. */
  private static final class RecordingAi {
    private int window = 4096;

    OnlineAiService withWindow(int w) {
      this.window = w;
      RecordingAi self = this;
      return new OnlineAiService() {
        @Override
        public boolean isAvailable() {
          return true;
        }

        @Override
        public boolean isStartingUp() {
          return false;
        }

        @Override
        public Integer llmContextTokens() {
          return self.window;
        }

        @Override
        public Integer configuredContextTokens() {
          return self.window;
        }

        @Override
        public CompletableFuture<String> askQuestion(String question, String context) {
          return CompletableFuture.completedFuture("");
        }

        @Override
        public CompletableFuture<String> summarize(String text) {
          return CompletableFuture.completedFuture("");
        }

        @Override
        public void streamChat(
            List<Map<String, Object>> messages,
            int maxTokens,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<Throwable> onError) {
          onChunk.accept("summary");
          onDone.accept("stop");
        }
      };
    }
  }

  private static ConversationContext stubCtx(Map<String, Object> body) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> attributes = new HashMap<>();
      private final Map<String, Object> requestBody = new LinkedHashMap<>(body);

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public Map<String, Object> requestBody() {
        return requestBody;
      }

      @Override
      public Map<String, Object> attributes() {
        return attributes;
      }

      @Override
      public String sessionId() {
        return "test-session";
      }

      @Override
      public String shapeId() {
        return "core.rag-ask";
      }
    };
  }
}
