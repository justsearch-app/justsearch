/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.mcp.McpProtocolHandler;
import io.justsearch.ui.api.mcp.McpToolSurface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real native-MCP proof that protocol parsing selects declared survival before admission.
 * The dispatcher is controlled, so this proves transport ownership and does not claim an index
 * effect or durable operation-store write.
 */
@Timeout(20)
final class DeclaredSurvivalMcpAdmissionTest {
  private static final String TOKEN = "declared-survival-mcp-token";
  private static final String INGEST_CALL = """
      {"jsonrpc":"2.0","id":"ingest-1","method":"tools/call","params":{
        "name":"justsearch_ingest","arguments":{"paths":["F:/docs"]}}}
      """;
  private static final String INGEST_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"tools/call","params":{
        "name":"justsearch_ingest","arguments":{"paths":["F:/docs"]}}}
      """;

  @Test
  void serverSessionOwnsOneDurableIngestSlotAndRotatingClientHeadersCannotReattributeIt()
      throws Exception {
    try (var fixture = new Fixture()) {
      String session = fixture.initialize();

      HttpResponse<String> first = fixture.post(
          INGEST_CALL, session, "forged-client-a", TOKEN, null);

      assertEquals(200, first.statusCode(), first.body());
      assertFalse(
          JsonMapper.builder()
              .build()
              .readTree(first.body())
              .path("result")
              .path("isError")
              .asBoolean());
      Capture firstCapture = fixture.dispatcher.capture.get();
      assertNotNull(firstCapture);
      assertEquals(AgentToolsOperationCatalog.INGEST_FILES.value(),
          firstCapture.operation().id().value());
      assertEquals(Optional.of(EngineContext.Survival.DURABLE),
          firstCapture.operation().policy().declaredSurvival());
      assertEquals(EngineContext.ClientKind.MCP_CLIENT, firstCapture.context().clientKind());
      assertEquals(session, firstCapture.context().clientId(),
          "the server-issued session, rather than a cooperative client header, owns admission");
      assertEquals(Optional.of(session), firstCapture.context().sessionId());
      assertEquals(TransportTag.MCP.name(), firstCapture.context().transport());
      assertEquals("UNTRUSTED", firstCapture.context().sourceTier());
      assertEquals(EngineContext.Survival.DURABLE, firstCapture.context().survival());
      assertEquals(EngineContext.Urgency.FOREGROUND, firstCapture.context().urgency());
      assertEquals(TransportTag.MCP, firstCapture.provenance().transport());
      assertEquals(Optional.of(session), firstCapture.provenance().initiator());
      assertEquals(1, firstCapture.activeOwners(),
          "attaching the dispatcher to the admitted request must retain one owner,"
              + " not reserve another slot");
      assertEquals(firstCapture.context().workId(), firstCapture.retained().context().workId());
      assertEquals(EngineContext.Urgency.BACKGROUND, firstCapture.retained().context().urgency(),
          "the operation stays foreground through dispatch and detaches only after its"
              + " HTTP response");
      assertEquals(1, fixture.admission.activeWorkCount());

      fixture.dispatcher.release();
      assertEquals(0, fixture.admission.activeWorkCount());

      HttpResponse<String> second = fixture.post(
          INGEST_CALL.replace("ingest-1", "ingest-2"), session, "forged-client-b", TOKEN, null);
      assertEquals(200, second.statusCode(), second.body());
      assertEquals(session, fixture.dispatcher.capture.get().context().clientId());
      assertEquals(Optional.of(session), fixture.dispatcher.capture.get().context().sessionId());
      fixture.dispatcher.release();
    }
  }

  @Test
  void capacityAndFreezeRefusalsKeepRpcIdentityAndNotificationsSilent() throws Exception {
    try (var fixture = new Fixture()) {
      String session = fixture.initialize();
      var blockerContext = new EngineContext(EngineContext.ClientKind.INTERNAL, "capacity-owner",
          Optional.empty(), Optional.empty(), "TRUSTED", TransportTag.SYSTEM_INTERNAL.name(),
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      try (var _ = fixture.admission.admit(blockerContext, false)) {
        HttpResponse<String> refused = fixture.post(
            INGEST_CALL.replace("ingest-1", "quota-17"), session, "forged", TOKEN, null);
        var body = JsonMapper.builder().build().readTree(refused.body());
        assertEquals(429, refused.statusCode(), refused.body());
        assertEquals("quota-17", body.path("id").asText());
        assertEquals("ADMISSION_ENGINE_LIMIT",
            body.path("error").path("data").path("errorCode").asText());
        assertTrue(body.path("error").path("data").path("retrySafe").asBoolean());

        assertSilentRefusal(fixture.post(
            INGEST_NOTIFICATION, session, "rotated", TOKEN, null), 429);
      }
      assertEquals(0, fixture.dispatcher.calls.get());

      var frozen = fixture.admission.freezeAdmission("native MCP refusal proof");
      try {
        assertSilentRefusal(fixture.post(
            INGEST_NOTIFICATION, session, "rotated-again", TOKEN, null), 503);
      } finally {
        fixture.admission.releaseAdmission(frozen.preparationId());
      }
      assertEquals(0, fixture.dispatcher.calls.get());
    }
  }

  @Test
  void directPingRemainsInteractiveAndUsesTheServerSessionIdentity() throws Exception {
    try (var fixture = new Fixture()) {
      String session = fixture.initialize();
      clearInvocations(fixture.admission);

      HttpResponse<String> response = fixture.post(
          "{\"jsonrpc\":\"2.0\",\"id\":\"ping-1\",\"method\":\"ping\"}",
          session, "forged-ping-client", TOKEN, null);

      assertEquals(200, response.statusCode(), response.body());
      var context = ArgumentCaptor.forClass(EngineContext.class);
      verify(fixture.admission).admit(context.capture(), eq(false));
      assertEquals(EngineContext.ClientKind.MCP_CLIENT, context.getValue().clientKind());
      assertEquals(session, context.getValue().clientId());
      assertEquals(Optional.of(session), context.getValue().sessionId());
      assertEquals(TransportTag.MCP.name(), context.getValue().transport());
      assertEquals("UNTRUSTED", context.getValue().sourceTier());
      assertEquals(EngineContext.Survival.INTERACTIVE, context.getValue().survival());
      assertEquals(EngineContext.Urgency.FOREGROUND, context.getValue().urgency());
      assertEquals(0, fixture.admission.activeWorkCount());
      assertEquals(0, fixture.dispatcher.calls.get());
    }
  }

  @Test
  void actualFiltersStillEnforceMcpOriginAndMutationToken() throws Exception {
    try (var fixture = new Fixture()) {
      HttpResponse<String> missingToken = fixture.post(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, null, null, null);
      assertEquals(401, missingToken.statusCode(), missingToken.body());
      assertTrue(missingToken.body().contains("UI_TOKEN_REQUIRED"));

      HttpResponse<String> foreignOrigin = fixture.post(
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}",
          null, null, TOKEN, "https://example.invalid");
      assertEquals(403, foreignOrigin.statusCode(), foreignOrigin.body());
      var body = JsonMapper.builder().build().readTree(foreignOrigin.body());
      assertEquals("2.0", body.path("jsonrpc").asText());
      assertTrue(body.path("id").isNull());
      assertEquals("MCP_ORIGIN_FORBIDDEN",
          body.path("error").path("data").path("errorCode").asText());
      assertEquals(0, fixture.admission.activeWorkCount());
      assertEquals(0, fixture.dispatcher.calls.get());
    }
  }

  private static void assertSilentRefusal(HttpResponse<String> response, int expectedStatus) {
    assertEquals(expectedStatus, response.statusCode(), response.body());
    assertEquals("", response.body(), "a refused JSON-RPC notification must have no response body");
  }

  private record Capture(
      Operation operation,
      InvocationProvenance provenance,
      EngineContext context,
      EngineWorkHandle retained,
      int activeOwners) {}

  private static final class Fixture implements AutoCloseable {
    final EngineAdmissionController admission = spy(new EngineAdmissionController(1, 1, 1));
    final ControlledDispatcher dispatcher = new ControlledDispatcher(admission);
    final ExecutorService events = Executors.newSingleThreadExecutor();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Javalin app;

    Fixture() {
      var catalog = new AgentToolsOperationCatalog();
      var surface = new McpToolSurface(
          List.of(catalog), dispatcher, () -> null, () -> null, Clock.systemUTC());
      var protocol = new McpProtocolHandler(
          surface, List.of(), Clock.systemUTC(), admission);
      app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      app.exception(io.javalin.http.HttpResponseException.class,
          (failure, context) -> context.status(failure.getStatus()));
      new ApiSecurityFilters(true, TOKEN, new EventBuffer(), events, null, admission, admission)
          .install(app, protocol::clientIdentity);
      app.post(LocalApiServer.MCP_ENDPOINT_PATH, protocol::handlePost);
      app.start("127.0.0.1", 0);
    }

    String initialize() throws Exception {
      HttpResponse<String> response = post("""
          {"jsonrpc":"2.0","id":"initialize-1","method":"initialize","params":{
            "clientInfo":{"name":"declared-survival-test"}}}
          """, null, "ignored-before-session", TOKEN, null);
      assertEquals(200, response.statusCode(), response.body());
      return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    HttpResponse<String> post(String body, String session, String clientId, String token,
        String origin) throws Exception {
      var request = HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + app.port() + LocalApiServer.MCP_ENDPOINT_PATH))
          .timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/json");
      if (session != null) request.header("Mcp-Session-Id", session);
      if (clientId != null) request.header("X-JustSearch-Client-Id", clientId);
      if (token != null) request.header(LocalApiServer.SESSION_TOKEN_HEADER, token);
      if (origin != null) request.header("Origin", origin);
      return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
      dispatcher.release();
      app.stop();
      client.close();
      events.shutdownNow();
    }
  }

  private static final class ControlledDispatcher implements OperationDispatcher {
    final EngineAdmissionController admission;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<Capture> capture = new AtomicReference<>();

    ControlledDispatcher(EngineAdmissionController admission) {
      this.admission = admission;
    }

    @Override
    public OperationResult dispatch(Operation operation, String argumentsJson,
        EngineContext engineContext) {
      return capture(operation, null, engineContext);
    }

    @Override
    public OperationResult dispatch(Operation operation, String argumentsJson,
        InvocationProvenance provenance, EngineContext engineContext) {
      return capture(operation, provenance, engineContext);
    }

    @Override
    public OperationResult dispatch(Operation operation, String argumentsJson,
        InvocationProvenance provenance, Optional<String> confirmationToken,
        EngineContext engineContext) {
      return capture(operation, provenance, engineContext);
    }

    @Override
    public OperationResult undo(Operation operation, String executionId,
        EngineContext engineContext) {
      throw new AssertionError("native MCP ingest must not enter undo dispatch");
    }

    @Override
    public OperationResult undo(Operation operation, String executionId,
        InvocationProvenance provenance, Optional<String> confirmationToken,
        EngineContext engineContext) {
      throw new AssertionError("native MCP ingest must not enter undo dispatch");
    }

    private OperationResult capture(Operation operation, InvocationProvenance provenance,
        EngineContext engineContext) {
      calls.incrementAndGet();
      EngineWorkHandle retained = admission.attach(engineContext);
      Capture previous = capture.getAndSet(new Capture(
          operation, provenance, engineContext, retained, admission.activeWorkCount()));
      if (previous != null) {
        retained.close();
        throw new AssertionError("dispatcher retained more than one MCP request owner");
      }
      return OperationResult.success("retained");
    }

    void release() {
      Capture current = capture.getAndSet(null);
      if (current != null) current.retained().close();
    }
  }
}
