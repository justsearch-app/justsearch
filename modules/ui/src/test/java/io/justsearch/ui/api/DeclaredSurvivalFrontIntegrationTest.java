/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.*;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.intent.*;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.mcp.McpProtocolHandler;
import io.justsearch.ui.api.mcp.McpToolSurface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real front, catalog, dispatcher and SQLite ownership; the prepared effect itself is inert. */
class DeclaredSurvivalFrontIntegrationTest {
  private static final String TOKEN = "front-composition-test";
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"alias", "invoke", "mcp"})
  void directFrontPersistsDurableContextUsingOneSlot(String front) throws Exception {
    try (var fixture = new Fixture(directory)) {
      String path = switch (front) {
        case "alias" -> OperationsController.INGEST_PATH;
        case "invoke" -> "/api/operations/core.ingest-files/invoke";
        default -> LocalApiServer.MCP_ENDPOINT_PATH;
      };
      String body = switch (front) {
        case "alias" -> "{\"paths\":[]}";
        case "invoke" -> "{\"args\":{\"paths\":[]}}";
        default -> """
            {"jsonrpc":"2.0","id":"ingest-1","method":"tools/call","params":{
              "name":"justsearch_ingest","arguments":{"paths":[]}}}
            """;
      };
      var response = fixture.post(path, body);
      assertEquals(200, response.statusCode(), response.body());
      EngineContext handler = fixture.handler.get();
      assertNotNull(handler, response.body());
      boolean nativeMcp = "mcp".equals(front);
      assertEquals(nativeMcp ? EngineContext.ClientKind.MCP_CLIENT : EngineContext.ClientKind.WEBVIEW,
          handler.clientKind());
      assertEquals(nativeMcp ? "mcp-anonymous" : "local-webview", handler.clientId());
      assertTrue(handler.sessionId().isEmpty());
      assertEquals(nativeMcp ? "MCP" : "BUTTON", handler.transport());
      assertEquals(nativeMcp ? "UNTRUSTED" : "TRUSTED", handler.sourceTier());
      assertEquals(EngineContext.Urgency.FOREGROUND, handler.urgency());
      assertEquals(EngineContext.Survival.DURABLE, handler.survival());
      assertTrue(handler.workId().isPresent());
      var rows = fixture.store.openRecords();
      assertEquals(1, rows.size());
      var row = rows.getFirst();
      assertEquals(OperationState.RUNNING, row.state());
      assertEquals(EngineContext.Survival.DURABLE, row.context().survival());
      assertEquals(handler.clientKind(), row.context().clientKind());
      assertEquals(handler.sessionId(), row.context().sessionId());
      assertEquals(EngineContext.Urgency.FOREGROUND, row.context().urgency());
      assertEquals(handler.clientId(), row.context().clientId());
      assertEquals(handler.sourceTier(), row.context().sourceTier());
      assertEquals(handler.transport(), row.context().transport());
      assertEquals(handler.grantReference(), row.context().grantReference());
      assertTrue(handler.grantReference().isPresent());
      assertTrue(row.context().workId().isEmpty());
      assertEquals(1, fixture.admission.activeWorkCount(), "one direct request needs exactly one slot");
      try (var owner = fixture.admission.attach(handler)) {
        assertEquals(handler.workId(), owner.context().workId());
        assertEquals(EngineContext.Survival.DURABLE, owner.context().survival());
        assertEquals(EngineContext.Urgency.BACKGROUND, owner.context().urgency(), "response ends foreground demand");
        assertTrue(owner.cancellationReason().isEmpty());
      }
      fixture.admission.cancelInteractive("interactive work cancelled");
      assertEquals(OperationState.RUNNING, fixture.store.find(row.key()).orElseThrow().state());
      fixture.completion.complete(OperationResult.success("effect finished"));
      assertEquals(0, fixture.admission.activeWorkCount());
      assertEquals(OperationState.COMPLETE, fixture.store.find(row.key()).orElseThrow().state());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final EngineAdmissionController admission = new EngineAdmissionController(1, 1, 1);
    final AtomicReference<EngineContext> handler = new AtomicReference<>();
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    final SqliteOperationStore store;
    final ExecutorService events = Executors.newSingleThreadExecutor();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Javalin app;

    Fixture(Path directory) throws Exception {
      var clock = Clock.systemUTC();
      var catalog = new AgentToolsOperationCatalog();
      var operation = catalog.findById(AgentToolsOperationCatalog.INGEST_FILES).orElseThrow();
      store = new SqliteOperationStore(directory.resolve("operations.db"));
      var runner = new OperationAttemptRunnerImpl(store, clock, java.util.Set.of(OperationKind.INGEST));
      var handlers = new HandlerRegistry();
      handlers.register(operation.id(), new OperationHandler() {
        @Override public OperationResult execute(String args, EngineContext context) { throw new AssertionError("raw dispatch"); }
        @Override public OperationPreparation prepare(String args, InvocationProvenance provenance, EngineContext context) {
          return new OperationPreparation(args, "front-composition.v1", "{}");
        }
        @Override public void validatePreparation(OperationPreparation preparation) {
          if (!"front-composition.v1".equals(preparation.replaySchema())) throw new IllegalArgumentException("schema");
        }
        @Override public OperationApprovalPreview approvalPreview(OperationPreparation preparation) {
          return new OperationApprovalPreview("Inert front composition fixture");
        }
        @Override public OperationExecution executePrepared(OperationPreparation preparation,
            InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
          assertTrue(handler.compareAndSet(null, context));
          assertEquals(1, admission.activeWorkCount());
          return new OperationExecution(OperationResult.success("effect started"), completion);
        }
      });
      var executor = new OperationExecutorImpl(runner, admission, handlers, null, Map.of(), clock,
          new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(), null, new ConsentCapsuleService());
      var grants = new DurableGrantStore();
      grants.grantAllowAlways(operation.id().value(), SourceTier.TRUSTED);
      grants.grantAllowAlways(operation.id().value(), SourceTier.UNTRUSTED);
      // Consent is explicitly seeded for this inert prepared scope; the real trust gate consumes it.
      executor.setDurableGrantStore(grants, new DurableGrantScope() {
        @Override public boolean coversArguments(Operation op, String args, EngineContext context) { return false; }
        @Override public boolean coversPreparation(Operation op, OperationPreparation prepared, EngineContext context) {
          return op.id().equals(operation.id()) && "front-composition.v1".equals(prepared.replaySchema())
              && "{\"paths\":[]}".equals(prepared.argumentsJson());
        }
      });
      var controller = new OperationsController(List.of(catalog), executor, clock);
      var protocol = new McpProtocolHandler(new McpToolSurface(List.of(catalog), executor,
          () -> null, () -> null, clock), List.of(), clock, admission);
      app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      new ApiSecurityFilters(true, TOKEN, new EventBuffer(), events, null, admission, admission,
          controller::admissionOperation).install(app, protocol::clientIdentity);
      app.post(OperationsController.INGEST_PATH, controller::handleIngest);
      app.post(OperationsController.INVOKE_PATH, controller::handleInvoke);
      app.post(LocalApiServer.MCP_ENDPOINT_PATH, protocol::handlePost);
      app.start("127.0.0.1", 0);
    }

    HttpResponse<String> post(String path, String body) throws Exception {
      return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
          .header(LocalApiServer.SESSION_TOKEN_HEADER, TOKEN)
          .header("Origin", "tauri://localhost")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() throws Exception {
      completion.complete(OperationResult.success("fixture cleanup"));
      app.stop();
      client.close();
      events.shutdownNow();
      store.close();
    }
  }
}
