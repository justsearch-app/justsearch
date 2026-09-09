/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.observability.stream.run.RunChannel;
import io.justsearch.app.observability.stream.run.RunChannelPolicy;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.observability.stream.run.RunDescriptor;
import io.justsearch.app.observability.stream.run.RunFrame;
import io.justsearch.app.observability.stream.run.RunId;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import io.justsearch.core.execution.TestEngineExecutors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 834 §15.1.3 — the run-stream endpoints: the ask-survival law (§3.4), the typed 404
 * contract (§1.6), and the error paths routed THROUGH the sink so a failing run does not terminate
 * invisibly for every non-creating observer.
 */
@DisplayName("RunStreamController")
final class RunStreamControllerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ConversationShapeRef SHAPE = new ConversationShapeRef("core.test-ask");

  private final List<Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void closeFixtures() {
    fixtures.forEach(Fixture::close);
  }

  private Fixture fixture() {
    Fixture fixture = new Fixture();
    fixtures.add(fixture);
    return fixture;
  }

  private Fixture fixture(Consumer<RunChannelRegistry> hook) {
    Fixture fixture = new Fixture(hook);
    fixtures.add(fixture);
    return fixture;
  }

  // ── §3.4: the ask-survival law ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("§3.4 — an ask whose observer dies mid-generation still reaches done AND persists")
  void askSurvivesItsObserverDying() {
    Fixture f = fixture();
    // A dead socket: every write throws, so the channel evicts the observer on the first frame and
    // observerCount reaches 0 while the run is still generating.
    SseClient client = f.client("{\"shapeId\":\"core.test-ask\",\"sessionId\":\"s1\",\"prompt\":\"hi\"}");
    doAnswer(
            inv -> {
              throw new IllegalStateException("socket closed");
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    f.controller.streamNewRun(client);

    RunChannel run = f.onlyRun();
    assertEquals(
        0, run.observerCount(), "the dead observer must be evicted, or the premise is not tested");
    assertEquals(1, f.ai.calls, "zero observers means KEEP GOING for a one-shot shape");
    List<Map<String, Object>> recorded = f.store.appended.get("s1");
    assertEquals(2, recorded.size(), "the user turn and the assistant turn are BOTH still recorded");
    assertEquals("assistant", recorded.get(1).get("role"));
    assertTrue(
        f.eventsOf(run).contains("done"),
        "and the run still reached its terminal frame: " + f.eventsOf(run));
  }

  @Test
  @DisplayName("§3.4 — a conversational run is structurally unparkable")
  void conversationalRunsAreOneShot() {
    Fixture f = fixture();
    f.controller.streamNewRun(
        f.client("{\"shapeId\":\"core.test-ask\",\"sessionId\":\"s1\",\"prompt\":\"hi\"}"));

    assertFalse(
        f.onlyRun() instanceof io.justsearch.app.observability.stream.run.SteppedRunChannel,
        "flattening column two into column one is the failure §0 names — it must not compile, and "
            + "the policy must not hand back a parkable handle either");
  }

  @Test
  @DisplayName("the run is retired when the dispatch returns, and its ring stays readable")
  void theRunIsRetiredWhenTheDispatchReturns() {
    Fixture f = fixture();
    f.controller.streamNewRun(
        f.client("{\"shapeId\":\"core.test-ask\",\"sessionId\":\"s1\",\"prompt\":\"hi\"}"));

    RunChannel run = f.onlyRun();
    assertTrue(run.retired());
    assertFalse(run.publish(new RunFrame("chunk", Map.of("text", "late"))));
    assertTrue(
        f.eventsOf(run).contains("done"), "the ring is still readable inside the linger window");
  }

  // ── §15.1.3: the error paths go through the sink ─────────────────────────────────────────────

  @Test
  @DisplayName("§15.1.3 — a mid-run engine exception reaches a SECOND observer, not just the creator")
  void aMidRunFailureReachesEveryObserver() {
    var secondObserver = new ArrayList<SseEnvelope>();
    // Attach as a second observer from INSIDE the run — the only moment a reattacher could exist —
    // then make the LLM call blow up. Before §15.1.3 that exception was written straight to the
    // CREATING request's Context, so this observer's stream would simply have stopped, with no
    // reason on it: a failing run terminating invisibly for every non-creating observer.
    Fixture f = fixture(registry -> registry.live().get(0).observe(secondObserver::add, 0));
    f.ai.failWith = "the model went away";

    f.controller.streamNewRun(
        f.client("{\"shapeId\":\"core.test-ask\",\"sessionId\":\"s1\",\"prompt\":\"hi\"}"));

    List<String> events =
        secondObserver.stream()
            .map(e -> RunFrame.from(e).orElseThrow().event())
            .toList();
    assertEquals(List.of("error"), events, "the failure landed ON THE RUN, where its observers are");
    Map<String, Object> body = RunFrame.from(secondObserver.get(0)).orElseThrow().data();
    assertEquals("BAD_REQUEST", body.get("errorCode"));
    assertEquals("the model went away", body.get("error"));
    assertTrue(f.sentEvents(), "and the creating observer saw it too");
  }

  @Test
  @DisplayName("§15.1.3 — an unregistered shape fails on the run with the typed NOT_FOUND code")
  void anUnknownShapeFailsOnTheRun() {
    Fixture f = fixture();
    f.controller.streamNewRun(f.client("{\"shapeId\":\"core.nope\",\"prompt\":\"hi\"}"));

    RunChannel run = f.onlyRun();
    List<RunFrame> frames =
        run.channel().framesSince(0).stream().map(e -> RunFrame.from(e).orElseThrow()).toList();
    assertEquals(1, frames.size());
    assertEquals("error", frames.get(0).event());
    assertEquals("NOT_FOUND", frames.get(0).data().get("errorCode"));
  }

  // ── §1.6: the 404 contract ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("§1.6 — observing an unknown runId is a typed 404, never a 200 with an empty stream")
  void observingAnUnknownRunIs404Unknown() {
    Fixture f = fixture();
    Captured c = f.observe("run-never-existed");

    assertEquals(404, c.status);
    assertEquals("run-never-existed", c.body.get("runId").asString());
    assertEquals("unknown", c.body.get("reason").asString());
  }

  @Test
  @DisplayName("§1.6 — observing a run past its linger says RETIRED, and names where the record is")
  void observingARetiredRunIs404RetiredWithARecordHint() {
    Fixture f = fixture();
    f.registry.open(
        new RunId("run-gone"),
        new RunDescriptor("core.test-ask", "conv-42", 1L),
        RunChannelPolicy.conversational());
    f.registry.retire(new RunId("run-gone"), Duration.ZERO);

    Captured c = f.observe("run-gone");

    assertEquals(404, c.status);
    assertEquals(
        "retired",
        c.body.get("reason").asString(),
        "'this run is over, read the record' is a different sentence from 'never heard of it'");
    assertEquals("/api/chat/conversations/conv-42", c.body.get("recordHint").asString());
  }

  @Test
  @DisplayName("§1.6 — a run inside its linger is still observable, not 404'd")
  void aLingeringRunIsStillObservable() {
    Fixture f = fixture();
    f.registry.open(
        new RunId("run-lingering"),
        new RunDescriptor("core.test-ask", "conv-42", 1L),
        RunChannelPolicy.conversational());
    f.registry.retire(new RunId("run-lingering"), Duration.ofMinutes(5));

    Captured c = f.observe("run-lingering");

    assertEquals(
        200,
        c.status,
        "a tab reloading as the answer lands must still replay it — that is what the linger is for");
  }

  @Test
  @DisplayName("§1.6 — a malformed runId is answered 404, not a 500")
  void aMalformedRunIdIs404() {
    Fixture f = fixture();
    Captured c = f.observe("../../etc/passwd");
    assertEquals(404, c.status);
    assertEquals("unknown", c.body.get("reason").asString());
  }

  // ── pre-stream validation (before any 200 commits) ───────────────────────────────────────────

  @Test
  @DisplayName("a missing shapeId is a 400 BEFORE the stream commits a 200")
  void missingShapeIdIs400() {
    Fixture f = fixture();
    Captured c = f.create("{\"prompt\":\"hi\"}");
    assertEquals(400, c.status);
    assertEquals("INVALID_REQUEST", c.body.get("errorCode").asString());
  }

  @Test
  @DisplayName("a body that will not parse is a 400, not a half-open stream")
  void malformedBodyIs400() {
    Fixture f = fixture();
    Captured c = f.create("{not json");
    assertEquals(400, c.status);
  }

  @Test
  @DisplayName("734 F4 — a locked store refuses the run with 423, before the SSE headers commit")
  void lockedStoreIs423() {
    Fixture f = fixture();
    f.store.locked = true;

    Captured c = f.create("{\"shapeId\":\"core.test-ask\",\"sessionId\":\"s1\",\"prompt\":\"hi\"}");

    assertEquals(423, c.status);
    assertEquals("STORE_LOCKED", c.body.get("errorCode").asString());
    assertTrue(c.body.get("locked").asBoolean());
    assertEquals(0, f.registry.size(), "and no run was opened for a turn that will not be recorded");
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private record Captured(int status, JsonNode body) {}

  private static final class Fixture implements AutoCloseable {
    private final RunChannelRegistry registry = new RunChannelRegistry();
    private final TestEngineExecutors processExecutors = new TestEngineExecutors();
    private final FakeStore store = new FakeStore();
    private final ScriptedAi ai = new ScriptedAi("the answer");
    private final RunStreamController controller;
    private final List<String> sent = new ArrayList<>();
    private final AtomicReference<RunChannel> liveRun = new AtomicReference<>();

    private Fixture() {
      this(null);
    }

    private Fixture(Consumer<RunChannelRegistry> midRunHook) {
      Consumer<RunChannelRegistry> hook =
          reg -> {
            if (!reg.live().isEmpty()) {
              liveRun.set(reg.live().get(0));
            }
            if (midRunHook != null) {
              midRunHook.accept(reg);
            }
          };
      ConversationEngine engine =
          new ConversationEngine(
              ConversationShapeCatalog.of("core", List.of(shape())),
              List.of(),
              PromptContributorRegistry.of(List.of()),
              ContextInjectorRegistry.of(List.of(new PromptEchoInjector(registry, hook))),
              StreamConsumerRegistry.of(List.of()),
              IterationControllerRegistry.of(List.of()),
              () -> ai,
              store);
      controller =
          new RunStreamController(
              processExecutors,
              registry, new ChatController(new io.justsearch.core.execution.TestEngineExecutors(), engine, new SseWriter(null), null, store));
    }

    private RunChannel onlyRun() {
      // The run is retired by the time streamNewRun returns, so live() is empty. Two ways back to
      // it: the reference the injector captured while the run WAS live (the only route when the
      // creating socket is dead and no frame ever reached it), else the run_started frame — which
      // is also the FE's only source for the id (§3.2).
      RunChannel captured = liveRun.get();
      if (captured != null) {
        return captured;
      }
      return registry
          .find(runIdFromFrames())
          .orElseThrow(() -> new AssertionError("no run was opened"));
    }

    private RunId runIdFromFrames() {
      // run_started is a lifecycle frame (not retained), so the id comes off the wire the creating
      // observer saw — which is also the FE's only source for it (§3.2).
      for (String frame : sent) {
        if (frame.contains("\"runId\"")) {
          return new RunId(MAPPER.readTree(frame).get("runId").asString());
        }
      }
      throw new AssertionError("no run_started frame was sent: " + sent);
    }

    private List<String> eventsOf(RunChannel run) {
      return run.channel().framesSince(0).stream()
          .map(e -> RunFrame.from(e).orElseThrow().event())
          .toList();
    }

    private boolean sentEvents() {
      return !sent.isEmpty();
    }

    private SseClient client(String body) {
      // Built BEFORE the stubbing below: creating a mock inside a when(...) argument leaves the
      // outer stubbing unfinished (Mockito's UnfinishedStubbingException).
      Context ctx = requestContext(body, new AtomicInteger(), new AtomicReference<>());
      SseClient client = mock(SseClient.class);
      when(client.ctx()).thenReturn(ctx);
      doAnswer(
              inv -> {
                Object data = inv.getArgument(1, Object.class);
                sent.add(data == null ? "" : data.toString());
                return null;
              })
          .when(client)
          .sendEvent(any(String.class), any(String.class));
      return client;
    }

    private Captured create(String body) {
      AtomicInteger status = new AtomicInteger(200);
      AtomicReference<Object> json = new AtomicReference<>();
      Context ctx = requestContext(body, status, json);
      try {
        controller.handleCreate(ctx);
      } catch (Exception e) {
        throw new AssertionError("handleCreate threw", e);
      }
      return captured(status, json);
    }

    private Captured observe(String runId) {
      AtomicInteger status = new AtomicInteger(200);
      AtomicReference<Object> json = new AtomicReference<>();
      Context ctx = requestContext("{}", status, json);
      when(ctx.pathParam("runId")).thenReturn(runId);
      try {
        controller.handleObserve(ctx);
      } catch (Exception e) {
        // A live run delegates to the real SseHandler, which needs a Jetty async context a mock
        // cannot provide. Reaching that point IS the assertion for the observable case: no 404 was
        // written. The 404 branch never touches SseHandler and is asserted on the status below.
        return captured(status, json);
      }
      return captured(status, json);
    }

    private static Captured captured(AtomicInteger status, AtomicReference<Object> json) {
      return new Captured(
          status.get(),
          json.get() == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(json.get()));
    }

    private static Context requestContext(
        String body, AtomicInteger status, AtomicReference<Object> json) {
      Context ctx = mock(Context.class);
      when(ctx.path()).thenReturn("/api/chat/runs/test/observe");
      when(ctx.body()).thenReturn(body);
      when(ctx.contentType(anyString())).thenReturn(ctx);
      when(ctx.attributeOrCompute(anyString(), any())).thenReturn(new Object());
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

    @Override
    public void close() {
      controller.shutdown();
      processExecutors.close();
    }
  }

  private static ConversationShape shape() {
    return new ConversationShape(
        SHAPE,
        new Presentation(
            new I18nKey("test.label"), new I18nKey("test.desc"), Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.PERSISTENT,
        List.of(),
        List.of(PromptEchoInjector.ID),
        List.of(),
        null,
        List.of(),
        true);
  }

  /** Stands in for {@code core.user-prompt}, plus an optional hook that runs INSIDE the run. */
  private static final class PromptEchoInjector implements ContextInjector {
    static final String ID = "core.test-prompt-echo";

    private final RunChannelRegistry registry;
    private final Consumer<RunChannelRegistry> midRunHook;

    private PromptEchoInjector(RunChannelRegistry registry, Consumer<RunChannelRegistry> hook) {
      this.registry = registry;
      this.midRunHook = hook;
    }

    @Override
    public String id() {
      return ID;
    }

    @Override
    public InjectorResult inject(ConversationContext ctx) {
      if (midRunHook != null) {
        midRunHook.accept(registry);
      }
      Object prompt = ctx.requestBody().get("prompt");
      Map<String, Object> msg = new LinkedHashMap<>();
      msg.put("role", "user");
      msg.put("content", prompt == null ? "" : prompt.toString());
      return InjectorResult.messagesOnly(List.of(msg));
    }
  }

  /** A conversation store whose lock state the test toggles, recording what was appended. */
  private static final class FakeStore implements ConversationStore {
    private final Map<String, List<Map<String, Object>>> appended = new LinkedHashMap<>();
    private boolean locked;

    @Override
    public boolean isLocked() {
      return locked;
    }

    @Override
    public List<Map<String, Object>> loadHistory(String sessionId) {
      if (locked) {
        throw new KeyLockedException();
      }
      return appended.getOrDefault(sessionId, List.of());
    }

    @Override
    public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {
      if (locked) {
        throw new KeyLockedException();
      }
      appended.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new LinkedHashMap<>(message));
    }

    @Override
    public List<SessionSummary> listSessions(String shapeId, int limit) {
      return List.of();
    }

    @Override
    public Optional<SessionSummary> getSessionMeta(String sessionId) {
      return Optional.empty();
    }

    @Override
    public void deleteSession(String sessionId) {}

    @Override
    public void branchFrom(String parentSessionId, String branchPointMessageId, String newSessionId) {}

    @Override
    public void setContextFloor(String sessionId, String floorMessageId) {}

    @Override
    public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
      return loadHistory(sessionId);
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

  /** Minimal streaming LLM: one scripted response, counting calls. */
  private static final class ScriptedAi implements OnlineAiService {
    private final String response;
    private int calls;
    /** When set, {@link #stream} throws instead of streaming — a mid-run engine failure. */
    private String failWith;

    private ScriptedAi(String response) {
      this.response = response;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      calls++;
      if (failWith != null) {
        throw new IllegalStateException(failWith);
      }
      sink.onContent().accept(response);
      sink.onComplete().accept("stop");
    }
  }
}
