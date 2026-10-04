/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.core.context.EngineContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP contract for the flat {@code /api/knowledge/ingest} operation alias. */
final class OperationsControllerIngestTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
  private static final String KEY = "01996c43-8300-7000-8000-000000000001";
  private static final UUID NONCE = UUID.fromString("0b05f8d6-c745-4cb0-8413-dc9501aaeb0c");

  private final PendingAuthorizationStore pending = new PendingAuthorizationStore();
  private OperationDispatcher dispatcher;
  private Javalin app;
  private HttpClient client;

  @BeforeEach
  void startServer() {
    dispatcher = mock(OperationDispatcher.class);
    var controller =
        new OperationsController(
            List.of(new AgentToolsOperationCatalog()),
            dispatcher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            pending);
    app =
        Javalin.create(
                config -> {
                  config.showJavalinBanner = false;
                  config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .post("/api/knowledge/ingest", controller::handleIngest)
            .post("/api/operations/{id}/invoke", controller::handleInvoke)
            .start("127.0.0.1", 0);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void stopServer() {
    if (app != null) app.stop();
  }

  @Test
  void flatBodyPreservesPublicJsonAndForwardsOnlyTransportControls() throws Exception {
    var receipt =
        OperationResult.success(
            "accepted",
            Map.of("operationKey", KEY, "operationRecordId", 41, "status", "ACCEPTED"));
    when(dispatcher.dispatch(
            any(), any(), any(), eq(Optional.of("capsule")), any(), eq(KEY), eq(NONCE)))
        .thenReturn(receipt);

    HttpResponse<String> response =
        post(
            "/api/knowledge/ingest",
            """
            {"paths":["F:/docs/a.md","F:/docs/b.md"],"collection":null,
             "idempotencyKey":"%s","confirmationToken":"capsule","preparationNonce":"%s"}
            """
                .formatted(KEY, NONCE),
            Map.of(
                "X-JustSearch-Client-Id", "ingest-http",
                "X-JustSearch-Session-Id", "session-7",
                "X-JustSearch-Grant-Reference", "grant-3",
                "X-JustSearch-Transport", "MCP"));

    assertEquals(200, response.statusCode());
    JsonNode body = json(response);
    assertTrue(body.path("success").asBoolean());
    assertEquals(KEY, body.path("structuredData").path("operationKey").asText());
    assertEquals(41, body.path("structuredData").path("operationRecordId").asInt());

    ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
    ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    ArgumentCaptor<EngineContext> context = ArgumentCaptor.forClass(EngineContext.class);
    verify(dispatcher)
        .dispatch(
            operation.capture(),
            arguments.capture(),
            provenance.capture(),
            eq(Optional.of("capsule")),
            context.capture(),
            eq(KEY),
            eq(NONCE));

    assertEquals(AgentToolsOperationCatalog.INGEST_FILES, operation.getValue().id());
    JsonNode publicArgs = MAPPER.readTree(arguments.getValue());
    assertEquals(List.of("F:/docs/a.md", "F:/docs/b.md"),
        MAPPER.convertValue(publicArgs.path("paths"), List.class));
    assertTrue(publicArgs.has("collection"));
    assertTrue(publicArgs.path("collection").isNull());
    assertFalse(publicArgs.has("idempotencyKey"));
    assertFalse(publicArgs.has("confirmationToken"));
    assertFalse(publicArgs.has("preparationNonce"));

    EngineContext forwarded = context.getValue();
    assertEquals(EngineContext.ClientKind.MCP_CLIENT, forwarded.clientKind());
    assertEquals("mcp-anonymous", forwarded.clientId());
    assertEquals(Optional.of("session-7"), forwarded.sessionId());
    assertEquals(Optional.of("grant-3"), forwarded.grantReference());
    assertEquals("MCP", forwarded.transport());
    assertEquals("UNTRUSTED", forwarded.sourceTier());
    assertEquals(EngineContext.Urgency.FOREGROUND, forwarded.urgency());
    assertEquals(forwarded.transport(), provenance.getValue().transport().name());
    assertEquals(NOW, provenance.getValue().occurredAt());
  }

  @Test
  void flatAliasAndGenericInvokeReachTheSameOperationContract() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenReturn(OperationResult.success("same owner"));

    HttpResponse<String> alias =
        post("/api/knowledge/ingest", "{\"paths\":[\"F:/docs\"],\"collection\":\"notes\"}");
    HttpResponse<String> generic =
        post(
            "/api/operations/core.ingest-files/invoke",
            "{\"args\":{\"paths\":[\"F:/docs\"],\"collection\":\"notes\"}}");

    assertEquals(200, alias.statusCode());
    assertEquals(json(alias), json(generic));
    ArgumentCaptor<Operation> operations = ArgumentCaptor.forClass(Operation.class);
    ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<InvocationProvenance> provenances =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    ArgumentCaptor<EngineContext> contexts = ArgumentCaptor.forClass(EngineContext.class);
    verify(dispatcher, org.mockito.Mockito.times(2))
        .dispatch(
            operations.capture(),
            arguments.capture(),
            provenances.capture(),
            eq(Optional.empty()),
            contexts.capture());
    assertEquals(
        List.of(AgentToolsOperationCatalog.INGEST_FILES, AgentToolsOperationCatalog.INGEST_FILES),
        operations.getAllValues().stream().map(Operation::id).toList());
    assertEquals(
        MAPPER.readTree(arguments.getAllValues().get(0)),
        MAPPER.readTree(arguments.getAllValues().get(1)));
    assertEquals(provenances.getAllValues().get(0), provenances.getAllValues().get(1));
    assertEquals(contexts.getAllValues().get(0), contexts.getAllValues().get(1));
  }

  @Test
  void emptyBodyIsZeroArgumentsButNonObjectAndMalformedBodiesAreBadRequests() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenReturn(OperationResult.success("validator owns empty arguments"));

    HttpResponse<String> empty = post("/api/knowledge/ingest", "");
    assertEquals(200, empty.statusCode());
    verify(dispatcher).dispatch(any(), eq("{}"), any(), eq(Optional.empty()), any());

    org.mockito.Mockito.clearInvocations(dispatcher);
    for (String invalid : List.of("[]", "null", "\"paths\"", "{broken")) {
      HttpResponse<String> response = post("/api/knowledge/ingest", invalid);
      assertEquals(400, response.statusCode(), invalid);
      assertEquals("BAD_REQUEST", json(response).path("errorClass").asText(), invalid);
    }
    verifyNoInteractions(dispatcher);
  }

  @Test
  void handlerFailureRemainsAnApplicationFailureAtHttp200() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenReturn(OperationResult.failure(
            "paths must contain between 1 and 100 entries", "BAD_REQUEST", Map.of(), false));

    HttpResponse<String> response = post("/api/knowledge/ingest", "{\"paths\":[]}");

    assertEquals(200, response.statusCode());
    JsonNode body = json(response);
    assertFalse(body.path("success").asBoolean(true));
    assertEquals("HANDLER_FAILURE", body.path("errorClass").asText());
    assertEquals("BAD_REQUEST", body.path("errorCode").asText());
    assertFalse(body.path("retryable").asBoolean(true));
  }

  @Test
  void keyedRetryReturnsRecordedMetadataAndChangedPublicInputConflicts() throws Exception {
    var firstArguments = new AtomicReference<String>();
    var recorded =
        OperationResult.success(
            "already accepted",
            Map.of("operationKey", KEY, "operationRecordId", 73, "status", "ACCEPTED"));
    when(dispatcher.dispatch(any(), any(), any(), any(), any(), eq(KEY)))
        .thenAnswer(
            invocation -> {
              String arguments = invocation.getArgument(1);
              String original = firstArguments.get();
              if (original == null) {
                firstArguments.set(arguments);
              } else if (!MAPPER.readTree(original).equals(MAPPER.readTree(arguments))) {
                throw new OperationStoreException(
                    OperationStoreException.Code.OPERATION_KEY_REUSED,
                    new IllegalStateException("private stored invocation"));
              }
              return recorded;
            });

    String first = "{\"paths\":[\"F:/docs/a.md\"],\"idempotencyKey\":\"" + KEY + "\"}";
    HttpResponse<String> accepted = post("/api/knowledge/ingest", first);
    HttpResponse<String> retry = post("/api/knowledge/ingest", first);
    HttpResponse<String> changed =
        post(
            "/api/knowledge/ingest",
            "{\"paths\":[\"F:/docs/b.md\"],\"idempotencyKey\":\"" + KEY + "\"}");

    assertEquals(200, accepted.statusCode());
    assertEquals(json(accepted), json(retry));
    assertEquals(KEY, json(retry).path("structuredData").path("operationKey").asText());
    assertEquals(73, json(retry).path("structuredData").path("operationRecordId").asInt());
    assertEquals(409, changed.statusCode());
    assertEquals("CONFLICT", json(changed).path("errorClass").asText());
    assertEquals("OPERATION_KEY_REUSED", json(changed).path("errorCode").asText());
    assertFalse(changed.body().contains("private stored invocation"));
  }

  @Test
  void confirmationResponseAndPendingRecordRetainPreparedIdentityAndPreview() throws Exception {
    var preview = new OperationApprovalPreview("Ingest 2 prepared roots: F:/docs/a, F:/docs/b");
    var required =
        new ConfirmationRequiredException(
            AgentToolsOperationCatalog.INGEST_FILES,
            GateBehavior.INLINE_CONFIRM,
            ConfirmStrategy.Inline.INSTANCE,
            SourceTier.UNTRUSTED,
            KEY,
            NONCE,
            preview);
    when(dispatcher.dispatch(any(), any(), any(), eq(Optional.empty()), any())).thenThrow(required);

    HttpResponse<String> response =
        post(
            "/api/knowledge/ingest",
            "{\"paths\":[\"F:/docs/a\",\"F:/docs/b\"]}",
            Map.of("X-JustSearch-Transport", "MCP"));

    assertEquals(428, response.statusCode());
    JsonNode body = json(response);
    assertEquals("CONFIRMATION_REQUIRED", body.path("errorClass").asText());
    assertEquals("core.ingest-files", body.path("operationId").asText());
    assertEquals(KEY, body.path("operationKey").asText());
    assertEquals(NONCE.toString(), body.path("preparationNonce").asText());
    assertEquals(preview.summary(), body.path("argsSummary").asText());
    var recorded = pending.peek(body.path("pendingId").asText()).orElseThrow();
    assertEquals(KEY, recorded.operationKey());
    assertEquals(NONCE, recorded.preparationNonce());
    assertEquals(preview, recorded.approvalPreview());
    assertEquals(SourceTier.UNTRUSTED, recorded.sourceTier());
    assertEquals(io.justsearch.agent.api.registry.TransportTag.MCP, recorded.transport());
    assertEquals("MCP", recorded.engineContext().transport());
    assertEquals("UNTRUSTED", recorded.engineContext().sourceTier());
    JsonNode pendingArguments = MAPPER.readTree(recorded.argsJson());
    assertEquals(List.of("F:/docs/a", "F:/docs/b"),
        MAPPER.convertValue(pendingArguments.path("paths"), List.class));
    assertFalse(pendingArguments.has("idempotencyKey"));
    assertFalse(pendingArguments.has("confirmationToken"));
    assertFalse(pendingArguments.has("preparationNonce"));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return post(path, body, Map.of());
  }

  private HttpResponse<String> post(String path, String body, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json");
    headers.forEach(request::header);
    return client.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) {
    return MAPPER.readTree(response.body());
  }
}
