package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 561 P-E — the agent's learning producer ({@code core_remember}). */
final class RememberFactHandlerTest {

  @Test
  @DisplayName("persists the content as an agent-actor memory; reports success")
  void rememberSucceeds() {
    var store = new FakeStore();
    OperationResult r =
        new RememberFactHandler(store)
            .execute("{\"content\":\"the user is named Sam\",\"kind\":\"fact\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(r.success());
    assertEquals(1, store.records.size());
    assertEquals("the user is named Sam", store.records.get(0).content());
    assertEquals("fact", store.records.get(0).kind());
    assertEquals("agent", store.records.get(0).actor());
  }

  @Test
  @DisplayName("captures the conversation provenance via the context-aware overload")
  void capturesConversationId() {
    var store = new FakeStore();
    EngineContext engineContext =
        io.justsearch.app.services.intent.EngineProvenance.context(
            EngineContext.ClientKind.INTERNAL,
            "test-agent",
            Optional.of("conv-9"),
            Optional.empty(),
            io.justsearch.agent.api.registry.TransportTag.AGENT_LOOP,
            EngineContext.Survival.INTERACTIVE,
            EngineContext.Urgency.FOREGROUND);
    InvocationProvenance provenance =
        InvocationProvenance.fromEngineContext(
            engineContext, ExecutorTag.AGENT, Instant.now(), Optional.empty());
    new RememberFactHandler(store)
        .execute(
            "{\"content\":\"prefers SI units\"}", provenance, engineContext);
    assertEquals("conv-9", store.records.get(0).sourceConversationId());
    assertEquals("fact", store.records.get(0).kind()); // kind defaults to "fact"
  }

  @Test
  @DisplayName("empty content → failure, nothing persisted")
  void rejectsEmptyContent() {
    var store = new FakeStore();
    OperationResult r = new RememberFactHandler(store).execute("{\"content\":\"  \"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(r.success());
    assertTrue(store.records.isEmpty());
  }

  /** A list-backed MemoryStore, idempotent on id. */
  private static final class FakeStore implements MemoryStore {
    private final Map<String, MemoryRecord> byId = new LinkedHashMap<>();
    final List<MemoryRecord> records = new ArrayList<>();

    @Override
    public void remember(MemoryRecord record) {
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
      byId.remove(id);
      records.removeIf(r -> r.id().equals(id));
    }

    @Override
    public void clear() {
      byId.clear();
      records.clear();
    }
  }
}
