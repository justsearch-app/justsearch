package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.lifecycle.AgentLifecycle;
import io.justsearch.agent.api.registry.Operation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 561 P-D2 — the presence axis has a REAL background producer (not an FE-only banner). A
 * background run proceeds without a watcher, persists to the ONE durable AgentRunStore record stamped
 * {@code background=true}, and the render-on-return inbox source ({@code presenceSince}) surfaces it.
 */
final class BackgroundRunServiceTest {

  /** A fake agent loop that persists + completes a run (as the real loop does) and emits its start. */
  private static final class FakeLoop implements AgentService {
    private final AgentRunStore runStore;

    FakeLoop(AgentRunStore runStore) {
      this.runStore = runStore;
    }

    @Override
    public void runAgent(
        AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
      runAgent(request, eventConsumer, false, engineContext);
    }

    // Mirrors AgentLoopService: a background run is stamped on its durable record (P-D2), so the
    // presence projection can surface it; an interactive run is not.
    @Override
    public void runAgent(
        AgentRequest request,
        Consumer<AgentEvent> eventConsumer,
        boolean background,
        EngineContext engineContext) {
      String sid = UUID.randomUUID().toString();
      runStore.startRun(sid, request, request.messages(), 1000);
      if (background) {
        runStore.markBackground(sid);
      }
      // Complete the run (state DONE, updatedAt = now) — mirrors the real loop's terminal checkpoint.
      runStore.updateCheckpoint(sid, "DONE", request.messages(), 1, 0, 120, "");
      eventConsumer.accept(new AgentEvent.SessionStarted(sid));
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    // Mirrors AgentLoopService.presenceSince — the real store projection under test.
    @Override
    public List<AgentLifecycle> presenceSince(Instant since) {
      return runStore.presenceRunsSince(since).stream()
          .map(AgentLifecycleProjection::fromRun)
          .toList();
    }
  }

  @Test
  @DisplayName("a background run persists stamped background=true and presenceSince surfaces it")
  void backgroundRunIsProducedAndSurfacedOnReturn(@TempDir Path tmp) {
    var runStore = new AgentRunStore(tmp.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);
    var background = new BackgroundRunService(loop);

    Instant before = Instant.now().minusSeconds(3600);

    String sid =
        background.runInBackground(
            AgentRequest.singleTurn(
                List.of(Map.of("role", "user", "content", "reindex the new files overnight"))),
            EngineContextTestFixtures.AGENT_LOOP_BACKGROUND);

    // The producer ran a real detached run and stamped the durable record.
    assertNotNull(sid, "the run started and its sessionId was captured");
    Map<String, Object> meta = runStore.readSnapshot(sid);
    assertNotNull(meta);
    assertEquals(Boolean.TRUE, meta.get("background"), "the run is marked background");
    assertEquals("DONE", meta.get("state"));

    // The render-on-return inbox source: "what completed while you were away".
    List<AgentLifecycle> presence = loop.presenceSince(before);
    assertEquals(1, presence.size(), "the background run is in the presence projection");
    assertEquals(sid, presence.get(0).sessionId());

    // A user who looked AFTER the run sees nothing new (the since-filter is exclusive).
    assertTrue(
        loop.presenceSince(Instant.now().plusSeconds(3600)).isEmpty(),
        "no background work after the user's last-seen mark");
  }

  @Test
  @DisplayName("an INTERACTIVE run is not in the presence projection (only background runs are)")
  void interactiveRunsAreNotPresence(@TempDir Path tmp) {
    var runStore = new AgentRunStore(tmp.resolve("agent-runs"));
    var loop = new FakeLoop(runStore);

    // Drive runAgent directly (an interactive run) — NOT through BackgroundRunService, so unmarked.
    loop.runAgent(
        AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "find invoices"))),
        ev -> {}, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(
        loop.presenceSince(Instant.now().minusSeconds(3600)).isEmpty(),
        "interactive runs are not background — the presence inbox stays empty");
  }
}
