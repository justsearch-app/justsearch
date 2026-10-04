/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.core.context.EngineContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real HTTP proof that catalog-declared survival is selected before Engine admission. */
@Timeout(20)
final class DeclaredSurvivalHttpAdmissionTest {
  private static final String TOKEN = "declared-survival-token";
  private static final String OPERATION_ID = AgentToolsOperationCatalog.INGEST_FILES.value();

  private enum OperationRoute {
    INGEST_ALIAS(OperationsController.INGEST_PATH, "{\"paths\":[\"F:/docs\"]}", OPERATION_ID, false, 200),
    GENERIC_INVOKE("/api/operations/" + OPERATION_ID + "/invoke",
        "{\"args\":{\"paths\":[\"F:/docs\"]}}", OPERATION_ID, false, 200),
    MIGRATION_ALIAS(OperationsController.MIGRATION_START_PATH, "{\"reason\":\"manual\"}",
        CoreOperationCatalog.REBUILD_INDEX.value(), false, 202),
    UNDO("/api/undo/" + OPERATION_ID, "{\"executionId\":\"prior-execution\"}", OPERATION_ID, true, 200);

    final String path;
    final String body;
    final String operationId;
    final boolean undo;
    final int acceptedStatus;

    OperationRoute(String path, String body, String operationId, boolean undo, int acceptedStatus) {
      this.path = path;
      this.body = body;
      this.operationId = operationId;
      this.undo = undo;
      this.acceptedStatus = acceptedStatus;
    }
  }

  @ParameterizedTest
  @EnumSource(OperationRoute.class)
  void declaredOperationRoutesAdmitOneDurableOwnerAndDetachItAfterTheResponse(
      OperationRoute route) throws Exception {
    try (var fixture = new Fixture()) {
      HttpResponse<String> response = fixture.post(route.path, route.body, TOKEN, "tauri://localhost");

      assertEquals(route.acceptedStatus, response.statusCode());
      assertEquals(1, fixture.dispatcher.calls.get());
      assertEquals(route.undo, fixture.dispatcher.undo.get());
      assertEquals(route.operationId, fixture.dispatcher.operation.get().id().value());
      assertEquals(Optional.of(EngineContext.Survival.DURABLE),
          fixture.dispatcher.operation.get().policy().declaredSurvival());
      if (route == OperationRoute.MIGRATION_ALIAS) {
        assertEquals(RiskTier.HIGH, fixture.dispatcher.operation.get().policy().risk());
        assertTrue(response.body().contains("\"success\":true"));
        assertFalse(response.body().contains("restartRequired"),
            "the alias exposes the operation response, not a synthetic migration receipt");
      }
      EngineContext dispatched = fixture.dispatcher.context.get();
      InvocationProvenance provenance = fixture.dispatcher.provenance.get();
      EngineWorkHandle retained = fixture.dispatcher.retained.get();
      assertNotNull(dispatched);
      assertNotNull(retained);
      assertEquals(EngineContext.Survival.DURABLE, dispatched.survival());
      assertEquals(EngineContext.Urgency.FOREGROUND, dispatched.urgency());
      assertEquals(EngineContext.ClientKind.WEBVIEW, dispatched.clientKind());
      assertEquals("survival-http", dispatched.clientId());
      assertEquals(Optional.of("session-1"), dispatched.sessionId());
      assertEquals(Optional.of("grant-1"), dispatched.grantReference());
      assertEquals("TRUSTED", dispatched.sourceTier());
      assertEquals(TransportTag.BUTTON.name(), dispatched.transport());
      assertEquals(TransportTag.BUTTON, provenance.transport());
      assertTrue(dispatched.workId().isPresent());
      assertEquals(dispatched.workId(), retained.context().workId());
      assertEquals(EngineContext.Survival.DURABLE, retained.context().survival());
      assertEquals(EngineContext.Urgency.BACKGROUND, retained.context().urgency(),
          "an operation response detaches retained durable work from its waiting client");
      assertEquals(1, fixture.admission.activeWorkCount(),
          "retaining the request owner must not consume a second admission slot");

      EngineAdmissionException refused = assertThrows(EngineAdmissionException.class,
          () -> fixture.admission.admit(withoutWorkId(dispatched), false));
      assertEquals(EngineAdmissionException.Reason.CONTEXT_LIMIT, refused.reason());
      assertEquals(1, fixture.admission.activeWorkCount());

      retained.close();
      fixture.dispatcher.retained.set(null);
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  @Test
  void nonOperationStreamKeepsItsRetainedRequestOwnerForeground() throws Exception {
    try (var fixture = new Fixture()) {
      HttpResponse<String> response = fixture.get("/fixture/events");

      assertEquals(200, response.statusCode());
      assertTrue(response.headers().firstValue("Content-Type").orElse("")
          .startsWith("text/event-stream"));
      EngineWorkHandle retained = fixture.streamWork.get();
      assertNotNull(retained);
      assertEquals(EngineContext.Survival.DURABLE, retained.context().survival());
      assertEquals(EngineContext.Urgency.FOREGROUND, retained.context().urgency(),
          "ordinary handler return must not blanket-detach a nonoperation stream owner");
      assertEquals(1, fixture.admission.activeWorkCount());

      retained.close();
      fixture.streamWork.set(null);
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  @Test
  void unknownOperationPreservesItsExistingErrorWithoutDispatchOrRetainedWork() throws Exception {
    try (var fixture = new Fixture()) {
      HttpResponse<String> response = fixture.post(
          "/api/operations/core.missing/invoke", "{\"args\":{}}", TOKEN, "tauri://localhost");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("OPERATION_NOT_FOUND"));
      assertEquals(0, fixture.dispatcher.calls.get());
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  @Test
  void actualSecurityFiltersStillGuardDeclaredSurvivalRoutes() throws Exception {
    try (var fixture = new Fixture()) {
      HttpResponse<String> missingToken = fixture.post(
          OperationsController.MIGRATION_START_PATH, "{\"reason\":\"manual\"}", null,
          "tauri://localhost");
      assertEquals(401, missingToken.statusCode());
      assertTrue(missingToken.body().contains("UI_TOKEN_REQUIRED"));

      HttpResponse<String> foreignOrigin = fixture.options(OperationsController.MIGRATION_START_PATH, "https://example.invalid");
      assertEquals(403, foreignOrigin.statusCode());
      assertEquals(403, fixture.postWithRawHost(OperationsController.MIGRATION_START_PATH,
          "{\"reason\":\"manual\"}", "example.invalid"));
      assertEquals(0, fixture.dispatcher.calls.get());
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  private static EngineContext withoutWorkId(EngineContext context) {
    return new EngineContext(context.clientKind(), context.clientId(), context.sessionId(),
        context.grantReference(), context.sourceTier(), context.transport(), context.survival(),
        context.urgency());
  }

  private static final class Fixture implements AutoCloseable {
    final EngineAdmissionController admission = new EngineAdmissionController(1, 2, 1);
    final AtomicReference<EngineWorkHandle> streamWork = new AtomicReference<>();
    final ExecutorService events = Executors.newSingleThreadExecutor();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final ControlledDispatcher dispatcher = new ControlledDispatcher(admission);
    final Javalin app;

    Fixture() {
      var controller = new OperationsController(
          List.of(new AgentToolsOperationCatalog(), new CoreOperationCatalog()), dispatcher, Clock.systemUTC());
      app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      app.exception(io.javalin.http.HttpResponseException.class,
          (failure, context) -> context.status(failure.getStatus()));
      new ApiSecurityFilters(true, TOKEN, new EventBuffer(), events, null, admission, admission,
          controller::admissionOperation).install(app);
      // A durable stream makes blanket waitingClientGone observable; it is a no-op for interactive work.
      app.before("/fixture/events", context -> {
        var incoming = RequestEngineContext.get(context);
        context.attribute(RequestEngineContext.ATTRIBUTE, new EngineContext(incoming.clientKind(), incoming.clientId(),
            incoming.sessionId(), incoming.grantReference(), incoming.sourceTier(), incoming.transport(),
            EngineContext.Survival.DURABLE, incoming.urgency()));
      });
      app.post(OperationsController.INGEST_PATH, controller::handleIngest);
      app.post(OperationsController.MIGRATION_START_PATH, controller::handleMigrationStart);
      app.post(OperationsController.INVOKE_PATH, controller::handleInvoke);
      app.post(OperationsController.UNDO_PATH, controller::handleUndo);
      app.get("/fixture/events", context -> {
        streamWork.set(admission.attach(RequestEngineContext.get(context)));
        context.contentType("text/event-stream").result("data: ready\n\n");
      });
      app.start("127.0.0.1", 0);
    }

    HttpResponse<String> post(String path, String body, String token, String origin) throws Exception {
      var request = HttpRequest.newBuilder(uri(path))
          .timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/json")
          .header("Origin", origin)
          .header("X-JustSearch-Client-Id", "survival-http")
          .header("X-JustSearch-Session-Id", "session-1")
          .header("X-JustSearch-Grant-Reference", "grant-1")
          .header("X-JustSearch-Transport", "BUTTON");
      if (token != null) request.header(LocalApiServer.SESSION_TOKEN_HEADER, token);
      return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
      return client.send(HttpRequest.newBuilder(uri(path))
          .timeout(Duration.ofSeconds(5))
          .header("Origin", "tauri://localhost")
          .header("X-JustSearch-Client-Id", "survival-http")
          .header("X-JustSearch-Session-Id", "session-1")
          .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> options(String path, String origin) throws Exception {
      return client.send(HttpRequest.newBuilder(uri(path))
          .timeout(Duration.ofSeconds(5))
          .header("Origin", origin)
          .header("Access-Control-Request-Method", "POST")
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    int postWithRawHost(String path, String body, String host) throws Exception {
      try (var socket = new Socket("127.0.0.1", app.port());
          var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
          var reader = new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
        socket.setSoTimeout(5_000);
        writer.write("POST " + path + " HTTP/1.1\r\n");
        writer.write("Host: " + host + "\r\n");
        writer.write("Origin: tauri://localhost\r\n");
        writer.write(LocalApiServer.SESSION_TOKEN_HEADER + ": " + TOKEN + "\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + body.getBytes(StandardCharsets.US_ASCII).length + "\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.write(body);
        writer.flush();
        String status = reader.readLine();
        assertNotNull(status);
        return Integer.parseInt(status.split(" ")[1]);
      }
    }

    private URI uri(String path) {
      return URI.create("http://127.0.0.1:" + app.port() + path);
    }

    @Override
    public void close() {
      EngineWorkHandle operation = dispatcher.retained.getAndSet(null);
      if (operation != null) operation.close();
      EngineWorkHandle stream = streamWork.getAndSet(null);
      if (stream != null) stream.close();
      app.stop();
      client.close();
      events.shutdownNow();
    }
  }

  private static final class ControlledDispatcher implements OperationDispatcher {
    final EngineAdmissionController admission;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<Boolean> undo = new AtomicReference<>();
    final AtomicReference<Operation> operation = new AtomicReference<>();
    final AtomicReference<InvocationProvenance> provenance = new AtomicReference<>();
    final AtomicReference<EngineContext> context = new AtomicReference<>();
    final AtomicReference<EngineWorkHandle> retained = new AtomicReference<>();

    ControlledDispatcher(EngineAdmissionController admission) {
      this.admission = admission;
    }

    @Override
    public OperationResult dispatch(Operation op, String argumentsJson, EngineContext engineContext) {
      return capture(op, null, engineContext, false);
    }

    @Override
    public OperationResult dispatch(Operation op, String argumentsJson,
        InvocationProvenance invocation, Optional<String> confirmationToken,
        EngineContext engineContext) {
      return capture(op, invocation, engineContext, false);
    }

    @Override
    public OperationResult undo(Operation op, String executionId, EngineContext engineContext) {
      return capture(op, null, engineContext, true);
    }

    @Override
    public OperationResult undo(Operation op, String executionId,
        InvocationProvenance invocation, Optional<String> confirmationToken,
        EngineContext engineContext) {
      return capture(op, invocation, engineContext, true);
    }

    private OperationResult capture(Operation op, InvocationProvenance invocation,
        EngineContext engineContext, boolean undoInvocation) {
      calls.incrementAndGet();
      operation.set(op);
      provenance.set(invocation);
      context.set(engineContext);
      undo.set(undoInvocation);
      EngineWorkHandle prior = retained.getAndSet(admission.attach(engineContext));
      if (prior != null) throw new AssertionError("dispatcher retained more than one request owner");
      return OperationResult.success("retained");
    }
  }
}
