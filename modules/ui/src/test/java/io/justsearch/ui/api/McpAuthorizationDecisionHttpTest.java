/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.*;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.*;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.intent.*;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.ui.api.mcp.McpProtocolHandler;
import io.justsearch.ui.api.mcp.McpToolSurface;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real loopback MCP/approval routes, store and gate; handlers only count admitted executions. */
class McpAuthorizationDecisionHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WINDOW = "decision-test-window";
  @TempDir Path directory;
  private final Clock clock = Clock.systemUTC();
  private final HttpClient client = HttpClient.newHttpClient();
  private PendingAuthorizationStore pending = new PendingAuthorizationStore();
  private AuthorizationOutcomeStore outcomes = new AuthorizationOutcomeStore();
  private PendingAuthorizationChangeRegistry changes = new PendingAuthorizationChangeRegistry();
  private final List<PendingAuthorizationEvent> events = new CopyOnWriteArrayList<>();
  private final AtomicInteger executions = new AtomicInteger();
  private final List<OperationCatalog> catalogs = List.of(new AgentToolsOperationCatalog(), new CoreOperationCatalog());
  private boolean prepared;
  private SqliteOperationStore operations;
  private OperationDispatcher dispatcher;
  private Javalin app;
  private io.justsearch.app.observability.stream.SseStreamChannel.Subscription subscription;

  @BeforeEach void start() throws Exception {
    operations = new SqliteOperationStore(directory.resolve("operations.db"));
    var handlers = new HandlerRegistry();
    for (String id : List.of("core.ingest-files", "core.apply-excludes")) {
      handlers.register(new OperationRef(id), new OperationHandler() {
        @Override public OperationResult execute(String args, io.justsearch.core.context.EngineContext context) {
          executions.incrementAndGet();
          return OperationResult.success("Executed once");
        }
        @Override public OperationPreparation prepare(String args, InvocationProvenance provenance,
            io.justsearch.core.context.EngineContext context) {
          return prepared ? new OperationPreparation(args, "decision-test.v1", "{}")
              : OperationPreparation.passthrough(args);
        }
        @Override public OperationApprovalPreview approvalPreview(OperationPreparation value) {
          return new OperationApprovalPreview("Use the frozen test target");
        }
        @Override public void validatePreparation(OperationPreparation value) {
          if (value.replaySchema() == null) { OperationHandler.super.validatePreparation(value); return; }
          if (!"decision-test.v1".equals(value.replaySchema()) || !"{}".equals(value.replayPayloadJson())) {
            throw new IllegalArgumentException("Unexpected test preparation");
          }
        }
        @Override public OperationExecution executePrepared(OperationPreparation value, InvocationProvenance provenance,
            io.justsearch.core.context.EngineContext context, OperationRecordHandle record) {
          return OperationExecution.finished(execute(value.argumentsJson(), context));
        }
      });
    }
    var capsules = new ConsentCapsuleService();
    dispatcher = new OperationExecutorImpl(new OperationAttemptRunnerImpl(operations, clock, Set.of()),
        new EngineAdmissionController(8, 16, 8), handlers, null, Map.of(), clock,
        new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(), null, capsules, outcomes::append);
    pending.setLifecycleSinks(outcomes, changes);
    subscription = changes.subscribeTyped(events::add);
    var facade = mock(HeadAssembly.class);
    when(facade.operationOutcome(anyString())).thenAnswer(call -> operations.outcome(call.getArgument(0)));
    var surface = new McpToolSurface(catalogs, dispatcher, () -> null, () -> facade,
        clock, () -> null, pending, changes);
    var protocol = new McpProtocolHandler(surface, List.of(), clock);
    var controller = new AuthorizationController(capsules, pending, null, dispatcher, catalogs);
    app = Javalin.create(c -> { c.showJavalinBanner = false; c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });
    WindowAuthority.install(app, WINDOW);
    WindowAuthority.installMutationGuard(app);
    app.post("/mcp", protocol::handlePost);
    app.post("/api/authorizations/reject", controller::handleReject);
    app.post("/api/authorizations/approve", controller::handleApprove);
    app.start(0);
  }

  @AfterEach void stop() throws Exception {
    if (app != null) app.stop();
    if (subscription != null) subscription.unsubscribe();
    changes.channel().close();
    if (operations != null) operations.close();
  }

  private HttpResponse<String> post(String path, Object body, boolean window) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .header("Content-Type", "application/json");
    if (window) request.header(WindowAuthority.HEADER, WINDOW);
    return client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode tool(String name, Map<String, Object> arguments) throws Exception {
    var response = post("/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
        "params", Map.of("name", name, "arguments", arguments)), false);
    assertEquals(200, response.statusCode(), response.body());
    var rpc = JSON.readTree(response.body());
    assertTrue(rpc.has("result"), response.body());
    return rpc.get("result");
  }

  private String denyIngest(String key) throws Exception {
    assertTrue(tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key))
        .path("isError").asBoolean());
    var id = events.getLast().pendingId();
    assertEquals(key, pending.peek(id).orElseThrow().operationKey());
    assertEquals(200, post("/api/authorizations/reject", Map.of("pendingId", id), true).statusCode());
    return id;
  }

  @Test void deniedOperationOutcomeIsTerminalUsingExistingFailedState() throws Exception {
    String key = OperationKeys.generate(clock);
    denyIngest(key);
    var outcome = tool("justsearch_operation_outcome", Map.of("operationKey", key));
    var facts = outcome.path("structuredContent");
    assertEquals("failed", facts.path("state").asText(), outcome.toString());
    assertEquals("USER_DENIED", facts.path("reason").asText());
    assertEquals("Operation denied by the user", facts.path("message").asText());
    assertTrue(facts.path("completedAt").asLong() > 0);
    assertEquals(facts, JSON.readTree(outcome.path("content").path(0).path("text").asText()));
    assertEquals(0, executions.get());
  }

  @Test void repeatingDeniedKeyCannotPublishAnotherWaitingRequestButNewKeyCan() throws Exception {
    String key = OperationKeys.generate(clock);
    String deniedId = denyIngest(key);
    int eventCount = events.size();
    int rowCount = outcomes.recent().size();
    var repeat = tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    assertTrue(repeat.path("isError").asBoolean());
    assertTrue(repeat.toString().contains("denied by the user"), repeat.toString());
    assertEquals("USER_DENIED", repeat.path("structuredContent").path("errorCode").asText());
    assertFalse(repeat.path("structuredContent").path("retryable").asBoolean(true));
    assertEquals(eventCount, events.size());
    assertEquals(rowCount, outcomes.recent().size());
    assertEquals(0, pending.size());
    assertTrue(pending.peek(deniedId).isEmpty());
    String newKey = OperationKeys.generate(clock);
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", newKey));
    assertEquals(eventCount + 1, events.size());
    assertEquals(1, pending.size());
    assertEquals(newKey, pending.peek(events.getLast().pendingId()).orElseThrow().operationKey());
    assertEquals(0, executions.get());
  }

  @ParameterizedTest(name = "oneAnswerWithdrawsEveryPendingOfTheSameKey(deny={0})")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void oneAnswerWithdrawsEveryPendingOfTheSameKey(boolean deny) throws Exception {
    String key = OperationKeys.generate(clock);
    var ids = new ArrayList<String>();
    for (int i = 0; i < 2; i++) {
      tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
      ids.add(events.getLast().pendingId());
    }
    assertNotEquals(ids.get(0), ids.get(1));
    String route = deny ? "/api/authorizations/reject" : "/api/authorizations/approve";
    var answer = post(route, Map.of("pendingId", ids.get(0), "execute", true), true);
    assertEquals(200, answer.statusCode(), answer.body());
    assertEquals(0, pending.size());
    for (var id : ids) {
      assertTrue(pending.peek(id).isEmpty());
      var terminal = events.stream().filter(e -> e.pendingId().equals(id)
          && !e.disposition().equals("GATED")).toList();
      assertEquals(1, terminal.size(), "every window receives one withdrawal for " + id);
      // L9: the answered request carries the decision; its same-key copy is withdrawn by it.
      assertEquals(id.equals(ids.get(0)) ? deny ? "DENIED" : "APPROVED" : "WITHDRAWN", terminal.getFirst().disposition());
      assertEquals(410, post("/api/authorizations/approve", Map.of("pendingId", id, "execute", true), true).statusCode());
    }
    var decisions = outcomes.recent().stream().filter(row -> ids.contains(row.pendingId())
        && row.disposition() != AuthorizationDisposition.GATED).toList();
    assertEquals(List.of(deny ? "DENIED" : "APPROVED", "WITHDRAWN"),
        decisions.stream().map(row -> row.disposition().name()).toList(),
        "one recorded decision; the copy is recorded as withdrawn: " + decisions);
    assertEquals(deny ? 0 : 1, executions.get());
    var facts = tool("justsearch_operation_outcome", Map.of("operationKey", key)).path("structuredContent");
    assertEquals(deny ? "failed" : "complete", facts.path("state").asText(), facts.toString());
    assertEquals(deny ? "USER_DENIED" : "", facts.path("reason").asText());
    long waitingEvents = events.stream().filter(e -> e.disposition().equals("GATED")).count();
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    assertEquals(waitingEvents, events.stream().filter(e -> e.disposition().equals("GATED")).count());
    assertEquals(0, pending.size());
    assertEquals(deny ? 0 : 1, executions.get());
  }

  @Test void evictedDenialRequiresFreshAnswerAndReportsPending() throws Exception {
    String key = OperationKeys.generate(clock);
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    denyIngest(key); // Two pending ids still count as just one key decision.
    var context = TestRequestContexts.mcp("bounded-decisions");
    var provenance = TestRequestContexts.provenance(context, ExecutorTag.UI);
    for (int i = 0; i < 512; i++) {
      String id = pending.create("core.ingest-files", "{\"paths\":[\"/test/exact-target\"]}",
          SourceTier.UNTRUSTED, RiskTier.HIGH, GateBehavior.TYPED_CONFIRM, "bounded decisions", null,
          TransportTag.MCP, context, provenance, OperationKeys.generate(clock), false);
      assertTrue(pending.reject(id).isPresent());
      if (i == 510) {
        var replay = tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
        assertEquals("USER_DENIED", replay.path("structuredContent").path("errorCode").asText(),
            "511 newer keys retain the decision");
      }
    }
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    String id = events.getLast().pendingId();
    assertTrue(pending.peek(id).isPresent());
    assertEquals(0, executions.get());
    var facts = tool("justsearch_operation_outcome", Map.of("operationKey", key)).path("structuredContent");
    assertEquals("unknown", facts.path("state").asText(), facts.toString());
    assertEquals("awaiting-approval", facts.path("phase").asText());
    assertEquals("Operation awaits your approval", facts.path("message").asText());
    assertEquals(200, post("/api/authorizations/approve", Map.of("pendingId", id, "execute", true), true).statusCode());
    assertEquals(1, executions.get());
  }

  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void restartRequiresFreshAnswerAndOutcomeReportsTheNewPending(boolean frozen) throws Exception {
    prepared = frozen;
    String key = OperationKeys.generate(clock);
    String oldId = denyIngest(key);
    stop();
    pending = new PendingAuthorizationStore();
    outcomes = new AuthorizationOutcomeStore();
    changes = new PendingAuthorizationChangeRegistry();
    events.clear();
    start(); // Reopen the same on-disk operation store with fresh process-local authority.
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    String newId = events.getLast().pendingId();
    assertNotEquals(oldId, newId);
    assertTrue(pending.peek(newId).isPresent());
    assertEquals(0, executions.get());
    assertEquals(410, post("/api/authorizations/approve", Map.of("pendingId", oldId, "execute", true), true).statusCode());
    var facts = tool("justsearch_operation_outcome", Map.of("operationKey", key)).path("structuredContent");
    assertEquals("unknown", facts.path("state").asText(), facts.toString());
    assertEquals("awaiting-approval", facts.path("phase").asText());
    assertEquals("Operation awaits your approval", facts.path("message").asText());
    assertFalse(facts.has("completedAt"));
    assertEquals(200, post("/api/authorizations/approve", Map.of("pendingId", newId, "execute", true), true).statusCode());
    assertEquals(1, executions.get());
    assertEquals("complete", tool("justsearch_operation_outcome", Map.of("operationKey", key))
        .path("structuredContent").path("state").asText());
  }

  @ParameterizedTest @CsvSource({"core.apply-excludes,false", "core.ingest-files,false",
      "core.apply-excludes,true", "core.ingest-files,true"})
  void approvingNewerRequestPublishesOneDecisionAndExecutesOnce(String operationId, boolean frozen) throws Exception {
    prepared = frozen;
    var op = catalogs.stream().flatMap(c -> c.findById(new OperationRef(operationId)).stream()).findFirst().orElseThrow();
    String args = operationId.equals("core.ingest-files") ? "{\"paths\":[\"/test/exact-target\"]}" : "{}";
    var context = TestRequestContexts.mcp("approval-test");
    var provenance = TestRequestContexts.provenance(context, ExecutorTag.UI);
    var ids = new ArrayList<String>();
    for (int i = 0; i < 2; i++) {
      var gate = assertThrows(ConfirmationRequiredException.class,
          () -> dispatcher.dispatch(op, args, provenance, context));
      ids.add(pending.create(operationId, args, gate.sourceTier(), op.policy().risk(), gate.gateBehavior(),
          gate.getMessage(), null, TransportTag.MCP, context, provenance, gate.operationKey(), false,
          gate.preparationNonce(), gate.approvalPreview(), gate.pendingId()));
    }
    var approved = post("/api/authorizations/approve", Map.of("pendingId", ids.get(1), "execute", true), true);
    assertEquals(200, approved.statusCode(), approved.body());
    assertTrue(JSON.readTree(approved.body()).path("executed").asBoolean(), approved.body());
    assertEquals(1, executions.get());
    assertTrue(pending.peek(ids.get(0)).isPresent());
    var decisions = outcomes.recent().stream().filter(row -> row.disposition() == AuthorizationDisposition.APPROVED).toList();
    assertEquals(1, decisions.size(), "one keyed decision, without an unkeyed executor duplicate: " + decisions);
    assertEquals(ids.get(1), decisions.getFirst().pendingId());
    assertEquals(410, post("/api/authorizations/approve", Map.of("pendingId", ids.get(1), "execute", true), true).statusCode());
    assertEquals(1, executions.get());
  }

  /** GET /api/operation-history/{operationKey} as ResourceApiModule wires it (looked up so the baseline compiles). */
  @SuppressWarnings("unchecked")
  private JsonNode history(String key) throws Exception {
    var facade = mock(HeadAssembly.class);
    when(facade.operationOutcome(anyString())).thenAnswer(call -> operations.outcome(call.getArgument(0)));
    java.util.function.Function<String, Object> route;
    try {
      var wiring = ResourceApiModule.class.getDeclaredMethod("historyOutcome", HeadAssembly.class, PendingAuthorizationStore.class);
      wiring.setAccessible(true);
      route = (java.util.function.Function<String, Object>) wiring.invoke(null, facade, pending);
    } catch (NoSuchMethodException baseline) {
      route = facade::operationOutcome; // Before L10 the route read the recorded outcome only.
    }
    return JSON.readTree(JSON.writeValueAsString(route.apply(key)));
  }

  private JsonNode outcomeTool(String key) throws Exception {
    return tool("justsearch_operation_outcome", Map.of("operationKey", key)).path("structuredContent");
  }

  @Test void operationHistoryAgreesWithTheOutcomeToolWhileWaitingAndAfterDenial() throws Exception {
    String key = OperationKeys.generate(clock);
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    var waiting = outcomeTool(key);
    assertEquals("awaiting-approval", waiting.path("phase").asText(), waiting.toString());
    assertEquals(waiting, history(key), "L10: the history route says what the tool says while a request waits");
    denyIngest(key);
    var denied = outcomeTool(key);
    assertEquals("USER_DENIED", denied.path("reason").asText(), denied.toString());
    assertEquals(denied, history(key), "L10: the history route says what the tool says after a denial");
    assertEquals(0, executions.get());
  }

  @Test void anExpiredRequestIsDeniedAutomaticallyForTheToolTheHistoryAndARepeat() throws Exception {
    stop();
    pending = new PendingAuthorizationStore(clock, java.time.Duration.ofMillis(200));
    outcomes = new AuthorizationOutcomeStore();
    changes = new PendingAuthorizationChangeRegistry();
    events.clear();
    start();
    String key = OperationKeys.generate(clock);
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    String id = events.getLast().pendingId();
    Thread.sleep(400);
    var expired = outcomeTool(key);
    assertEquals("failed", expired.path("state").asText(), "L11: an unanswered request is a terminal denial: " + expired);
    assertEquals("APPROVAL_EXPIRED", expired.path("reason").asText());
    assertEquals("Denied automatically: nobody answered before the deadline", expired.path("message").asText());
    assertTrue(expired.path("completedAt").asLong() > 0);
    assertEquals(expired, history(key), "L10: both reads agree after expiry");
    assertEquals(1, events.stream().filter(e -> e.pendingId().equals(id) && e.disposition().equals("EXPIRED")).count());
    int eventCount = events.size();
    var repeat = tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    assertTrue(repeat.path("isError").asBoolean(), repeat.toString());
    assertEquals("APPROVAL_EXPIRED", repeat.path("structuredContent").path("errorCode").asText(), repeat.toString());
    assertFalse(repeat.path("structuredContent").path("retryable").asBoolean(true));
    assertEquals(eventCount, events.size(), "a repeat with the expired key does not ask again");
    assertEquals(410, post("/api/authorizations/approve", Map.of("pendingId", id, "execute", true), true).statusCode());
    assertEquals(0, executions.get());
  }

  @Test void anExpiredCopyLeavesTheKeyOpenWhileAnotherCopyStillWaits() throws Exception {
    stop();
    var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now());
    var manual = new Clock() {
      @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public java.time.Instant instant() { return now.get(); }
    };
    pending = new PendingAuthorizationStore(manual, java.time.Duration.ofMinutes(5));
    outcomes = new AuthorizationOutcomeStore();
    changes = new PendingAuthorizationChangeRegistry();
    events.clear();
    start();
    String key = OperationKeys.generate(clock);
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    String older = events.getLast().pendingId();
    now.set(now.get().plus(java.time.Duration.ofMinutes(3)));
    tool("justsearch_ingest", Map.of("paths", List.of("/test/exact-target"), "operationKey", key));
    String newer = events.getLast().pendingId();
    now.set(now.get().plus(java.time.Duration.ofMinutes(3)));
    assertTrue(pending.peek(older).isEmpty());
    var waiting = outcomeTool(key);
    assertEquals("awaiting-approval", waiting.path("phase").asText(), waiting.toString());
    assertEquals(200, post("/api/authorizations/approve", Map.of("pendingId", newer, "execute", true), true).statusCode());
    assertEquals(1, executions.get());
    assertEquals("complete", outcomeTool(key).path("state").asText());
  }
}
