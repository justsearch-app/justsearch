package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.encryption.KeyLockedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 838 — {@code POST/DELETE /api/chat/conversations/{sessionId}/title}, and the name on the
 * list row.
 *
 * <p>A conversation's name used to be the one fact about it the browser owned, in a plaintext
 * {@code localStorage} map that survived neither a cleared site-data nor a second client — and that
 * rendered a sealed conversation's subject while every other content field of it refused to. These
 * tests pin the seam that replaced it: what the endpoint accepts, what it refuses, and what the list
 * says.
 */
final class ChatControllerTitleTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Test
  @DisplayName("838 — a name is stored with its provenance and echoed back")
  void setTitleStoresNameAndProvenance() {
    FakeStore store = new FakeStore();
    store.create("uc-1");
    Captured c = setTitle(store, "uc-1", "{\"title\":\"Renewal postmortem\",\"source\":\"user\"}");

    assertEquals(200, c.status());
    assertTrue(c.body().get("ok").asBoolean());
    assertEquals("Renewal postmortem", c.body().get("title").asString());
    assertEquals("Renewal postmortem", store.titles.get("uc-1"));
    assertEquals("user", store.sources.get("uc-1"));
  }

  @Test
  @DisplayName("838 — the source defaults to the reader, so an omitted field never means 'auto'")
  void sourceDefaultsToUser() {
    FakeStore store = new FakeStore();
    store.create("uc-1");
    setTitle(store, "uc-1", "{\"title\":\"Renewal postmortem\"}");
    assertEquals("user", store.sources.get("uc-1"));
  }

  @Test
  @DisplayName("838 — an auto-generated name declares itself as such")
  void autoSourceIsCarried() {
    FakeStore store = new FakeStore();
    store.create("uc-1");
    setTitle(store, "uc-1", "{\"title\":\"Renewal Lock Failure\",\"source\":\"auto\"}");
    assertEquals("auto", store.sources.get("uc-1"));
  }

  @Test
  @DisplayName("838 — a blank name is a 400, because clearing is DELETE: one verb per meaning")
  void blankTitleIsRejected() {
    FakeStore store = new FakeStore();
    store.create("uc-1");
    for (String body : List.of("{\"title\":\"\"}", "{\"title\":\"   \"}", "{}", "not json")) {
      Captured c = setTitle(store, "uc-1", body);
      assertEquals(400, c.status(), "rejected body: " + body);
      assertEquals("INVALID_REQUEST", c.body().get("errorCode").asString());
    }
    assertNull(store.titles.get("uc-1"), "nothing was written by any of the refusals");
  }

  @Test
  @DisplayName("838 — an unrecognised source is a 400, not a value smuggled into the store")
  void unknownSourceIsRejected() {
    FakeStore store = new FakeStore();
    store.create("uc-1");
    Captured c = setTitle(store, "uc-1", "{\"title\":\"Renewal\",\"source\":\"imported\"}");
    assertEquals(400, c.status());
    assertEquals("INVALID_REQUEST", c.body().get("errorCode").asString());
    assertNull(store.titles.get("uc-1"));
  }

  @Test
  @DisplayName("838 — naming a conversation that does not exist is a 404, NOT a new ghost row")
  void unknownSessionIs404() {
    FakeStore store = new FakeStore();
    Captured c = setTitle(store, "uc-never", "{\"title\":\"Renewal postmortem\"}");

    assertEquals(404, c.status());
    assertEquals("NOT_FOUND", c.body().get("errorCode").asString());
    // The alternative — materialising a meta, the way setContextFloor does — would mint a
    // zero-message conversation that the list then shows as a row naming nothing.
    assertTrue(store.titles.isEmpty());
    assertTrue(store.known.isEmpty());
  }

  @Test
  @DisplayName("838 — a locked store answers 423 through the GLOBAL mapping, with no arm of its own")
  void lockedStoreRaisesTheOneLockedCondition() {
    FakeStore store = new FakeStore();
    store.create("uc-1");
    store.locked = true;
    // KeyLockedException reaching the handler's caller IS the contract: LocalApiServer maps it to
    // 423 {"locked":true,"errorCode":"STORE_LOCKED"} for every route, so a second mapping here would
    // be a second answer to one condition.
    assertThrows(
        KeyLockedException.class,
        () -> setTitle(store, "uc-1", "{\"title\":\"Renewal postmortem\"}"));
    assertTrue(store.titles.isEmpty(), "and nothing was written");
  }

  @Test
  @DisplayName("838 — DELETE clears the name, and is a no-op success on a conversation without one")
  void deleteClearsTheName() {
    FakeStore store = new FakeStore();
    store.create("uc-1");
    setTitle(store, "uc-1", "{\"title\":\"Renewal postmortem\"}");

    Captured c = invoke(store, ctrl -> ctrl::handleClearTitle, "uc-1", null);
    assertEquals(200, c.status());
    assertTrue(c.body().get("ok").asBoolean());
    assertNull(store.titles.get("uc-1"));

    assertEquals(200, invoke(store, ctrl -> ctrl::handleClearTitle, "uc-never", null).status());
  }

  @Test
  @DisplayName("838 — the list row carries the name and its provenance, and omits both when absent")
  void listRowCarriesTitleAndSource() {
    FakeStore store = new FakeStore();
    store.create("uc-named");
    store.create("uc-plain");
    store.setTitle("uc-named", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);

    Captured c = invoke(store, ctrl -> ctrl::handleListSessions, null, null);
    JsonNode rows = c.body().get("sessions");
    assertEquals("Renewal postmortem", rows.get(0).get("title").asString());
    assertEquals("user", rows.get(0).get("titleSource").asString());
    // Absence means "no name" — the field is omitted rather than sent as an empty string, exactly
    // like the parent pointers beside it.
    assertFalse(rows.get(1).has("title"));
    assertFalse(rows.get(1).has("titleSource"));
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private record Captured(int status, JsonNode body) {}

  private static Captured setTitle(FakeStore store, String sessionId, String body) {
    return invoke(store, ctrl -> ctrl::handleSetTitle, sessionId, body);
  }

  private interface HandlerPick {
    io.javalin.http.Handler pick(ChatController controller);
  }

  private static Captured invoke(
      FakeStore store, HandlerPick pick, String sessionId, String body) {
    ChatController controller = new ChatController(new io.justsearch.core.execution.TestEngineExecutors(), null, new SseWriter(null), null, store);
    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<Object> json = new AtomicReference<>();
    Context ctx = mockContext(sessionId, body, status, json);
    try {
      pick.pick(controller).handle(ctx);
    } catch (KeyLockedException locked) {
      throw locked;
    } catch (Exception e) {
      throw new AssertionError("handler threw", e);
    }
    JsonNode parsed = json.get() == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(json.get());
    return new Captured(status.get(), parsed);
  }

  private static Context mockContext(
      String sessionId, String body, AtomicInteger status, AtomicReference<Object> json) {
    Context ctx = mock(Context.class);
    if (sessionId != null) when(ctx.pathParam("sessionId")).thenReturn(sessionId);
    when(ctx.queryParam(anyString())).thenReturn(null);
    if (body != null) {
      when(ctx.bodyAsClass(Map.class))
          .thenAnswer(
              inv -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = MAPPER.readValue(body, Map.class);
                return parsed;
              });
    }
    doAnswer(
            inv -> {
              status.set(inv.getArgument(0, Integer.class));
              return ctx;
            })
        .when(ctx)
        .status(anyInt());
    doAnswer(
            inv -> {
              json.set(inv.getArgument(0));
              return ctx;
            })
        .when(ctx)
        .json(any(Object.class));
    return ctx;
  }

  /** A conversation store that holds names in memory, and can have its data key locked. */
  private static final class FakeStore implements ConversationStore {
    final List<String> known = new ArrayList<>();
    final Map<String, String> titles = new LinkedHashMap<>();
    final Map<String, String> sources = new LinkedHashMap<>();
    boolean locked;

    void create(String sessionId) {
      known.add(sessionId);
    }

    @Override
    public void setTitle(String sessionId, String title, String titleSource) {
      if (!known.contains(sessionId)) return;
      if (title == null || title.isBlank()) {
        titles.remove(sessionId);
        sources.remove(sessionId);
        return;
      }
      titles.put(sessionId, title.trim());
      sources.put(
          sessionId, TITLE_SOURCE_AUTO.equals(titleSource) ? TITLE_SOURCE_AUTO : TITLE_SOURCE_USER);
    }

    @Override
    public Optional<SessionSummary> getSessionMeta(String sessionId) {
      if (locked) throw new KeyLockedException();
      if (!known.contains(sessionId)) return Optional.empty();
      return Optional.of(summary(sessionId));
    }

    @Override
    public List<SessionSummary> listSessions(String shapeId, int limit) {
      return known.stream().map(this::summary).toList();
    }

    private SessionSummary summary(String sessionId) {
      return new SessionSummary(
          sessionId,
          "core.rag-ask",
          1L,
          2L,
          2,
          "why did the renewal fail?",
          null,
          null,
          null,
          null,
          titles.get(sessionId),
          sources.get(sessionId));
    }

    @Override
    public List<Map<String, Object>> loadHistory(String sessionId) {
      return List.of();
    }

    @Override
    public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {}

    @Override
    public void deleteSession(String sessionId) {}

    @Override
    public void branchFrom(
        String parentSessionId, String branchPointMessageId, String newSessionId) {}

    @Override
    public void setContextFloor(String sessionId, String floorMessageId) {}

    @Override
    public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
      return List.of();
    }

    @Override
    public void compactContext(String sessionId, String floorMessageId, String summaryText) {}

    @Override
    public void excludeMessage(String sessionId, String messageId, boolean excluded) {}

    @Override
    public List<String> excludedMessageIds(String sessionId) {
      return List.of();
    }
  }
}
