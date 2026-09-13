package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentDispatchResult;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.services.conversation.spi.URLExtractor;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-D — regression guard for the HTTP→{@link AgentRequest} boundary. Live validation
 * caught that the autonomy dial reached the FE wire but was DROPPED here (defaulting to ASSIST), so
 * an {@code auto}-dial MEDIUM write still gated as {@code typed_confirm}. This pins the pass-through.
 */
final class ToolIteratingShapeRunnerTest {

  @Test
  @DisplayName("the final URL keeps the agent run session and original caller context")
  void finalUrlKeepsRunSessionAndOriginalCallerContext() {
    AtomicReference<Intent> routedIntent = new AtomicReference<>();
    AtomicReference<InvocationProvenance> routedProvenance = new AtomicReference<>();
    AtomicReference<EngineContext> routedContext = new AtomicReference<>();
    BackendIntentRouter router =
        (intent, provenance, engineContext) -> {
          routedIntent.set(intent);
          routedProvenance.set(provenance);
          routedContext.set(engineContext);
          return new IntentDispatchResult.Forwarded("envelope-1");
        };
    AtomicReference<EngineContext> agentContext = new AtomicReference<>();
    AgentService agent = new UrlEmittingAgent("run-session-s", agentContext);
    ToolIteratingShapeRunner runner =
        new ToolIteratingShapeRunner(
            () -> agent,
            StreamConsumerRegistry.of(List.of(new URLExtractor(router))));
    EngineContext incoming =
        EngineProvenance.context(
            EngineContext.ClientKind.WEBVIEW,
            "browser-client",
            Optional.of("browser-request"),
            Optional.of("grant-7"),
            TransportTag.BUTTON,
            EngineContext.Survival.DURABLE,
            EngineContext.Urgency.BACKGROUND);
    List<SseEvent> events = new ArrayList<>();

    runner.run(
        Map.of("messages", List.of(Map.of("role", "user", "content", "open it"))),
        Audience.USER,
        events::add,
        incoming);

    EngineContext agentRun = agentContext.get();
    assertEquals("browser-client", agentRun.clientId());
    assertEquals(Optional.of("browser-request"), agentRun.sessionId());
    assertEquals(Optional.of("grant-7"), agentRun.grantReference());
    assertEquals(TransportTag.AGENT_LOOP.name(), agentRun.transport());
    assertEquals(SourceTier.UNTRUSTED.name(), agentRun.sourceTier());
    assertEquals(EngineContext.Survival.DURABLE, agentRun.survival());
    assertEquals(EngineContext.Urgency.BACKGROUND, agentRun.urgency());

    EngineContext urlContext = routedContext.get();
    assertEquals(TransportTag.LLM_EMISSION, routedIntent.get().transport());
    assertEquals(TransportTag.LLM_EMISSION.name(), urlContext.transport());
    assertEquals(SourceTier.UNTRUSTED.name(), urlContext.sourceTier());
    assertEquals("browser-client", urlContext.clientId());
    assertEquals(Optional.of("run-session-s"), urlContext.sessionId());
    assertEquals(Optional.of("grant-7"), urlContext.grantReference());
    assertEquals(EngineContext.Survival.DURABLE, urlContext.survival());
    assertEquals(EngineContext.Urgency.BACKGROUND, urlContext.urgency());
    assertEquals(ExecutorTag.AGENT, routedProvenance.get().executor());
    assertEquals(Optional.of("browser-client"), routedProvenance.get().initiator());
    assertEquals(Optional.of("run-session-s"), routedProvenance.get().correlationId());
    assertTrue(events.stream().anyMatch(event -> event.name().equals("navigate.url_dispatched")));
  }

  private static final class UrlEmittingAgent implements AgentService {
    private final String sessionId;
    private final AtomicReference<EngineContext> receivedContext;

    private UrlEmittingAgent(
        String sessionId, AtomicReference<EngineContext> receivedContext) {
      this.sessionId = sessionId;
      this.receivedContext = receivedContext;
    }

    @Override
    public void runAgent(
        AgentRequest request,
        Consumer<AgentEvent> eventConsumer,
        EngineContext engineContext) {
      receivedContext.set(engineContext);
      eventConsumer.accept(new AgentEvent.SessionStarted(sessionId));
      eventConsumer.accept(
          new AgentEvent.TextChunk("Open justsearch://surface/core.library-surface"));
      eventConsumer.accept(new AgentEvent.AgentDone("done", 1, 0, 1));
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

    @Override
    public List<Operation> offeredOperations() {
      return List.of();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  @Test
  @DisplayName("parseRequest carries autonomyLevel + conversationId through to the AgentRequest")
  void parseRequestCarriesAutonomyLevel() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hi")),
                "maxIterations", 3,
                "conversationId", "conv-1",
                "autonomyLevel", "auto"));
    assertEquals("auto", r.autonomyLevel());
    assertEquals("conv-1", r.conversationId());
  }

  @Test
  @DisplayName("parseRequest leaves autonomyLevel null when absent (backend defaults to ASSIST)")
  void parseRequestDefaultsAutonomyLevelWhenAbsent() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));
    assertNull(r.autonomyLevel());
  }

  @Test
  @DisplayName("parseRequest carries docIds (scope chips) through to the AgentRequest")
  void parseRequestCarriesDocIds() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hi")),
                "docIds", List.of("/docs/taxes.md", "/docs/invoices.md")));
    assertEquals(List.of("/docs/taxes.md", "/docs/invoices.md"), r.docIds());
  }

  @Test
  @DisplayName("parseRequest defaults docIds to empty (unscoped) when absent")
  void parseRequestDefaultsDocIdsWhenAbsent() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));
    assertEquals(List.of(), r.docIds());
  }

  // ==================== sampling override (lane F PR 0b) ====================

  private static Map<String, Object> bodyWithSampling(Object sampling) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
    body.put("sampling", sampling);
    return body;
  }

  @Test
  @DisplayName("parseRequest carries the sampling override through to the AgentRequest")
  void parseRequestCarriesSamplingOverride() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            bodyWithSampling(Map.of("temperature", 0.0, "top_p", 0.5, "seed", 20260907)));
    assertEquals(0.0, r.sampling().temperature());
    assertEquals(0.5, r.sampling().topP());
    assertEquals(20260907L, r.sampling().seed());
  }

  @Test
  @DisplayName("parseRequest accepts a partial sampling override (absent knobs stay null)")
  void parseRequestAcceptsPartialSamplingOverride() {
    AgentRequest r = ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("seed", 7)));
    assertNull(r.sampling().temperature());
    assertNull(r.sampling().topP());
    assertEquals(7L, r.sampling().seed());
  }

  @Test
  @DisplayName("parseRequest leaves sampling null when absent (byte-identical to pre-PR-0b)")
  void parseRequestDefaultsSamplingWhenAbsent() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));
    assertNull(r.sampling());
  }

  @Test
  @DisplayName("parseRequest normalises an all-absent sampling object to null")
  void parseRequestNormalisesEmptySamplingToNull() {
    // One representation of "no override", so no downstream site has to distinguish the two.
    assertNull(ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of())).sampling());
  }

  @Test
  @DisplayName("parseRequest rejects a malformed sampling override (same 400 shape as bad messages)")
  void parseRequestRejectsMalformedSampling() {
    // Not an object.
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling("hot")));
    // Object with a non-numeric value.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolIteratingShapeRunner.parseRequest(
                bodyWithSampling(Map.of("temperature", "hot"))));
    // Out of the range SamplingParams itself enforces — refused at the boundary rather than
    // thrown mid-run, several LLM calls in.
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("temperature", 5.0))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("top_p", 1.5))));
  }

  @Test
  @DisplayName("parseRequest rejects an unknown key inside sampling")
  void parseRequestRejectsUnknownSamplingKey() {
    // The backend would silently ignore `topP`, leaving the run at the agent preset while the
    // caller believes it pinned it — a capture that reports a pin it never applied.
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ToolIteratingShapeRunner.parseRequest(
                    bodyWithSampling(Map.of("topP", 0.5))));
    assertTrue(e.getMessage().contains("topP"), "the message must name the offending key");
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("temp", 0.0))));
  }

  @Test
  @DisplayName("parseRequest does not COERCE a numeric string — the contract is validated")
  void parseRequestRejectsNumericStrings() {
    for (Object bad :
        List.of(Map.of("temperature", "0.0"), Map.of("top_p", "0.5"), Map.of("seed", "7"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(bad)),
          () -> "a typed-wrong request must be refused, not coerced: " + bad);
    }
  }

  @Test
  @DisplayName("parseRequest rejects NaN and infinite temperature / top_p")
  void parseRequestRejectsNonFiniteSampling() {
    // NaN compares FALSE against every bound, so a naive range check waves it through and it
    // reaches llama-server as a nonsense sampler setting.
    for (Double bad : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ToolIteratingShapeRunner.parseRequest(
                  bodyWithSampling(Map.of("temperature", bad))),
          () -> "temperature " + bad + " must be refused");
      assertThrows(
          IllegalArgumentException.class,
          () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("top_p", bad))),
          () -> "top_p " + bad + " must be refused");
    }
  }

  @Test
  @DisplayName("parseRequest rejects a non-integral or out-of-range seed rather than truncating it")
  void parseRequestRejectsUnrepresentableSeed() {
    // Truncating 1.5 to 1, or wrapping 1e30, pins the run to a seed the caller never asked for —
    // the one failure mode a seed exists to prevent.
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("seed", 1.5))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("seed", 1e30))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("seed", Double.NaN))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("seed", true))));
    // An integral double IS representable and must still be accepted — a JSON parser is free to
    // hand back 7 as 7.0, and refusing that would reject a well-formed request.
    assertEquals(
        7L,
        ToolIteratingShapeRunner.parseRequest(bodyWithSampling(Map.of("seed", 7.0)))
            .sampling()
            .seed());
  }
}
