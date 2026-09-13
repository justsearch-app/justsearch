package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.agent.api.registry.Audience;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Tempdoc 561 P-E — the passive (answer-plane) learning producer's high-precision cue extraction. */
final class MemoryExtractionConsumerTest {

  @Test
  @DisplayName("explicit 'remember that X' directive → persists the fact (stripped of the cue)")
  void remembersDirective() {
    var store = new FakeStore();
    new MemoryExtractionConsumer(store).onDone("ok", ctx("remember that the project ships on Friday"));
    assertEquals(1, store.records.size());
    assertEquals("the project ships on Friday", store.records.get(0).content());
    assertEquals("fact", store.records.get(0).kind());
    assertEquals("chat", store.records.get(0).actor());
  }

  @Test
  @DisplayName("first-person preference → persists verbatim as a preference")
  void remembersPreference() {
    var store = new FakeStore();
    new MemoryExtractionConsumer(store).onDone("ok", ctx("I prefer dark mode and concise answers"));
    assertEquals(1, store.records.size());
    assertEquals("I prefer dark mode and concise answers", store.records.get(0).content());
    assertEquals("preference", store.records.get(0).kind());
  }

  @Test
  @DisplayName("a plain question with no memory cue → persists nothing (high precision)")
  void ignoresPlainTurn() {
    var store = new FakeStore();
    new MemoryExtractionConsumer(store).onDone("ok", ctx("what files did I index yesterday?"));
    assertTrue(store.records.isEmpty());
  }

  @Test
  @DisplayName("restating the same preference is idempotent (id is a content hash → no duplicate)")
  void idempotentOnRestate() {
    var store = new FakeStore();
    var consumer = new MemoryExtractionConsumer(store);
    consumer.onDone("ok", ctx("I prefer dark mode"));
    consumer.onDone("ok", ctx("I prefer dark mode"));
    assertEquals(1, store.records.size());
  }

  @Test
  @DisplayName("locked store → extraction is skipped, remember() is never called, nothing is thrown")
  void lockedStoreSkipsExtraction() {
    var store = new FakeStore();
    store.locked = true;
    var consumer = new MemoryExtractionConsumer(store);
    assertDoesNotThrow(() -> consumer.onDone("ok", ctx("remember that the project ships on Friday")));
    assertEquals(0, store.rememberCalls, "a locked store must not be asked to write");
    assertTrue(store.records.isEmpty());
  }

  @Test
  @DisplayName("the skipped write is OBSERVABLE: one WARN naming the skip and the lock as its cause")
  void lockedSkipWarnsDistinctly() {
    var store = new FakeStore();
    store.locked = true;
    var appender = attach();
    try {
      new MemoryExtractionConsumer(store).onDone("ok", ctx("remember that the deploy is Tuesday"));
      var warns = warnMessages(appender);
      assertEquals(1, warns.size(), () -> "expected exactly one WARN, got " + warns);
      assertTrue(warns.get(0).contains("locked"), () -> "the WARN must name the cause: " + warns.get(0));
    } finally {
      detach(appender);
    }
  }

  @Test
  @DisplayName("the WARN is once per lock EPISODE, not once per message (no per-turn log spam)")
  void lockedWarnIsDedupedPerEpisode() {
    var store = new FakeStore();
    store.locked = true;
    var consumer = new MemoryExtractionConsumer(store);
    var appender = attach();
    try {
      for (int i = 0; i < 5; i++) {
        consumer.onDone("ok", ctx("remember that fact number " + i));
      }
      assertEquals(1, warnMessages(appender).size(), "five locked turns must produce ONE WARN");

      // Unlock, write, re-lock: the episode ended, so the next drop is announced again.
      store.locked = false;
      consumer.onDone("ok", ctx("remember that the key was unlocked"));
      store.locked = true;
      consumer.onDone("ok", ctx("remember that it locked again"));
      assertEquals(2, warnMessages(appender).size(), "a NEW lock episode warns again");
    } finally {
      detach(appender);
    }
  }

  @Test
  @DisplayName("the episode ends when the STORE unlocks, even if no cue-bearing turn arrives meanwhile")
  void episodeEndsOnAnyUnlockedObservation() {
    var store = new FakeStore();
    store.locked = true;
    var consumer = new MemoryExtractionConsumer(store);
    var appender = attach();
    try {
      consumer.onDone("ok", ctx("remember that the deploy is Tuesday"));
      assertEquals(1, warnMessages(appender).size(), "the first drop is announced");

      // The lock episode ends — but the turns that happen while unlocked carry NO memory cue, so
      // nothing is written. The episode is over all the same.
      store.locked = false;
      consumer.onDone("ok", ctx("what files did I index yesterday?"));
      consumer.onDone("ok", ctx("summarize the last report"));
      assertEquals(1, warnMessages(appender).size(), "cueless turns warn about nothing");

      // A NEW episode: its first drop must be announced too, or the loss is silent.
      store.locked = true;
      consumer.onDone("ok", ctx("remember that it locked again"));
      assertEquals(
          2,
          warnMessages(appender).size(),
          "a second lock episode must warn again — the flag resets on ANY unlocked observation,"
              + " not only on a cue-bearing one");
    } finally {
      detach(appender);
    }
  }

  @Test
  @DisplayName("a store that locks between the pre-check and the write is not swallowed either")
  void racingLockStillWarns() {
    var store = new FakeStore();
    store.throwOnRemember = new KeyLockedException();
    var appender = attach();
    try {
      new MemoryExtractionConsumer(store).onDone("ok", ctx("remember that this races"));
      var warns = warnMessages(appender);
      assertEquals(1, warns.size(), () -> "expected exactly one WARN, got " + warns);
      assertTrue(warns.get(0).contains("locked"));
    } finally {
      detach(appender);
    }
  }

  @Test
  @DisplayName("an UNLOCKED store is unchanged — no WARN, the fact is persisted")
  void unlockedStoreIsUnchanged() {
    var store = new FakeStore();
    var appender = attach();
    try {
      new MemoryExtractionConsumer(store).onDone("ok", ctx("I prefer dark mode"));
      assertEquals(1, store.records.size());
      assertTrue(warnMessages(appender).isEmpty());
    } finally {
      detach(appender);
    }
  }

  @Test
  @DisplayName("a locked store with NO memory cue warns about nothing (the WARN is witnessed)")
  void lockedButNothingToRememberIsSilent() {
    var store = new FakeStore();
    store.locked = true;
    var appender = attach();
    try {
      new MemoryExtractionConsumer(store).onDone("ok", ctx("what files did I index yesterday?"));
      assertTrue(warnMessages(appender).isEmpty(), "no fact was dropped, so nothing may be reported");
    } finally {
      detach(appender);
    }
  }

  private static ListAppender<ILoggingEvent> attach() {
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(MemoryExtractionConsumer.class)).addAppender(appender);
    return appender;
  }

  private static void detach(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(MemoryExtractionConsumer.class)).detachAppender(appender);
    appender.stop();
  }

  private static List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static ConversationContext ctx(String userMessage) {
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", userMessage));
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> attrs = new HashMap<>();

      @Override
      public List<Map<String, Object>> messages() {
        return messages;
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
      public String sessionId() {
        return "conv-1";
      }

      @Override
      public Map<String, Object> requestBody() {
        return Map.of();
      }

      @Override
      public Map<String, Object> attributes() {
        return attrs;
      }
    };
  }

  /** A list-backed MemoryStore, idempotent on id (mirrors FileMemoryStore semantics). */
  private static final class FakeStore implements MemoryStore {
    private final Map<String, MemoryRecord> byId = new LinkedHashMap<>();
    final List<MemoryRecord> records = new ArrayList<>();
    /** Mirrors FileMemoryStore: locked ⇒ reads are empty and every write throws. */
    boolean locked = false;
    /** Simulates a store that locks BETWEEN the pre-check and the write. */
    RuntimeException throwOnRemember = null;
    int rememberCalls = 0;

    @Override
    public boolean isLocked() {
      return locked;
    }

    @Override
    public void remember(MemoryRecord record) {
      rememberCalls++;
      if (throwOnRemember != null) {
        throw throwOnRemember;
      }
      if (locked) {
        throw new KeyLockedException();
      }
      if (byId.putIfAbsent(record.id(), record) == null) {
        records.add(record);
      }
    }

    @Override
    public List<MemoryRecord> whatItKnows() {
      return List.copyOf(records);
    }

    @Override
    public void forget(String id) {
      if (byId.remove(id) != null) {
        records.removeIf(r -> r.id().equals(id));
      }
    }

    @Override
    public void clear() {
      byId.clear();
      records.clear();
    }
  }
}
