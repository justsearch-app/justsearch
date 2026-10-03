/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.OperationHistoryChangeRegistry;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Vertical HTTP proof for the recorded {@code core.ingest-files} owner. */
final class RecordedIngestHttpIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK = Clock.systemUTC();

  @TempDir Path temp;

  @Test
  void aliasExposesDurableRunningProgressAndTerminalOutcome() throws Exception {
    Path input = Files.writeString(temp.resolve("document.txt"), "recorded ingestion");
    try (var harness = Harness.open(temp.resolve("progress"), input, false)) {
      String key = OperationKeys.generate(CLOCK);
      JsonNode accepted = harness.post(input, key, Map.of());

      assertTrue(accepted.path("success").asBoolean());
      String responseKey = accepted.path("structuredData").path("operationKey").asText();
      assertEquals(key, responseKey);
      assertTrue(accepted.path("structuredData").path("operationRecordId").canConvertToLong());
      assertEquals(1, harness.ingestion.executions.get());

      harness.ingestion.record.get().checkpoint("fixture:1", 2, 0);
      JsonNode first = harness.getOutcome(responseKey);
      assertEquals("running", first.path("state").asText());
      assertTrue(
          !first.has("phase") || first.path("phase").isNull(),
          "ordinary ingestion has no producer-defined phase");
      assertEquals(2, first.path("unitsCompleted").asLong());
      assertEquals(0, first.path("unitsFailed").asLong());

      harness.ingestion.record.get().checkpoint("fixture:2", 5, 1);
      JsonNode second = harness.getOutcome(responseKey);
      assertEquals("running", second.path("state").asText());
      assertTrue(!second.has("phase") || second.path("phase").isNull());
      assertTrue(second.path("unitsCompleted").asLong() >= first.path("unitsCompleted").asLong());
      assertTrue(second.path("unitsFailed").asLong() >= first.path("unitsFailed").asLong());

      harness.ingestion.completion.complete(OperationResult.success("finished"));
      JsonNode terminal = harness.getOutcome(responseKey);
      assertEquals("complete", terminal.path("state").asText());
      assertEquals(5, terminal.path("unitsCompleted").asLong());
      assertEquals(1, terminal.path("unitsFailed").asLong());
      assertEquals("SUCCESS", terminal.path("result").path("code").asText());
    }
  }

  @Test
  void sameKeyRetryUsesTheRecordedPreparationAndChangedInputConflicts() throws Exception {
    Path input = Files.writeString(temp.resolve("original.txt"), "original");
    Path changed = Files.writeString(temp.resolve("changed.txt"), "changed");
    try (var harness = Harness.open(temp.resolve("retry"), input, false)) {
      String key = OperationKeys.generate(CLOCK);
      JsonNode first = harness.post(input, key, Map.of());
      long recordId = first.path("structuredData").path("operationRecordId").asLong();
      assertEquals(1, harness.preparations.get());
      assertEquals(1, harness.ingestion.executions.get());

      Files.delete(input);
      harness.preparationAvailable = false;
      JsonNode retry = harness.post(input, key, Map.of());
      assertTrue(retry.path("success").asBoolean());
      assertEquals(key, retry.path("structuredData").path("operationKey").asText());
      assertEquals(recordId, retry.path("structuredData").path("operationRecordId").asLong());
      assertEquals(1, harness.preparations.get(), "recorded retry must not prepare again");
      assertEquals(1, harness.ingestion.executions.get(), "recorded retry must not execute again");

      harness.ingestion.completion.complete(OperationResult.success("finished"));
      JsonNode completedRetry = harness.post(input, key, Map.of());
      assertTrue(completedRetry.path("success").asBoolean());
      assertEquals(recordId, completedRetry.path("structuredData").path("operationRecordId").asLong());
      assertEquals("complete", harness.getOutcome(key).path("state").asText());
      assertEquals(1, harness.preparations.get(), "terminal retry must not prepare again");
      assertEquals(1, harness.ingestion.executions.get(), "terminal retry must not execute again");

      HttpResponse<String> conflict = harness.postResponse(changed, key, Map.of());
      assertEquals(409, conflict.statusCode());
      assertEquals("OPERATION_KEY_REUSED", JSON.readTree(conflict.body()).path("errorCode").asText());
      assertEquals(1, harness.preparations.get());
      assertEquals(1, harness.ingestion.executions.get());
    }
  }

  @Test
  void explicitNullCollectionReachesTheRealPreparedHandler() throws Exception {
    Path input = Files.writeString(temp.resolve("nullable.txt"), "nullable collection");
    try (var harness = Harness.open(temp.resolve("nullable"), input, false)) {
      String key = OperationKeys.generate(CLOCK);
      var arguments = new java.util.LinkedHashMap<String, Object>();
      arguments.put("paths", List.of(input.toString()));
      arguments.put("collection", null);
      arguments.put("idempotencyKey", key);
      HttpResponse<String> response = harness.postArguments(arguments, Map.of());
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(JSON.readTree(response.body()).path("success").asBoolean(), response.body());
      assertEquals(1, harness.preparations.get());
      assertEquals(1, harness.ingestion.executions.get());
      assertTrue(harness.store.find(key).isPresent());
    }
  }

  @Test
  void untrustedMcpConfirmationDoesNotInvokeTheRecordedProducer() throws Exception {
    Path input = Files.writeString(temp.resolve("confirmation.txt"), "confirmation");
    try (var harness = Harness.open(temp.resolve("confirmation"), input, true)) {
      String key = OperationKeys.generate(CLOCK);
      HttpResponse<String> response =
          harness.postResponse(input, key, Map.of("X-JustSearch-Transport", "MCP"));
      JsonNode body = JSON.readTree(response.body());

      assertEquals(428, response.statusCode());
      assertEquals("CONFIRMATION_REQUIRED", body.path("errorClass").asText());
      assertEquals(key, body.path("operationKey").asText());
      assertTrue(body.path("preparationNonce").isTextual());
      assertEquals("UNTRUSTED", body.path("sourceTier").asText());
      assertEquals(1, harness.preparations.get(), "confirmation previews the real frozen plan");
      assertEquals(0, harness.ingestion.executions.get());
      assertTrue(harness.store.find(key).isEmpty(), "confirmation cannot accept a producer row");
    }
  }

  private static final class Harness implements AutoCloseable {
    private final SqliteOperationStore store;
    private final TestEngineExecutors executors;
    private final OperationHistoryController history;
    private final Javalin server;
    private final HttpClient client;
    private final ControlledIngestion ingestion = new ControlledIngestion();
    private final AtomicInteger preparations = new AtomicInteger();
    private volatile boolean preparationAvailable = true;

    private Harness(Path directory, Path root, boolean gated) throws Exception {
      Files.createDirectories(directory);
      store = new SqliteOperationStore(directory.resolve("operations.db"));
      executors = new TestEngineExecutors();
      var historyStore = new OperationHistoryStore(store);
      history = new OperationHistoryController(
          executors, historyStore, new OperationHistoryChangeRegistry(), store::outcome, CLOCK);
      var attempts = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.INGEST));
      var handlers = new HandlerRegistry();
      handlers.register(
          AgentToolsOperationCatalog.INGEST_FILES,
          new IngestTool(
              ingestion,
              context -> {
                preparations.incrementAndGet();
                requirePreparation();
                return List.of(new RootBinding(root.getParent(), "documents"));
              },
              context -> {
                requirePreparation();
                return "generation-1";
              },
              () -> {
                requirePreparation();
                return List.of();
              }));
      OperationExecutorImpl dispatcher =
          gated
              ? new OperationExecutorImpl(
                  attempts,
                  new EngineAdmissionController(),
                  handlers,
                  historyStore::append,
                  Map.of(),
                  CLOCK,
                  new CoreTrustEvaluator(),
                  CoreIntentSourceCatalog.catalog(),
                  null,
                  new ConsentCapsuleService())
              : new OperationExecutorImpl(
                  attempts,
                  new EngineAdmissionController(),
                  handlers,
                  historyStore::append,
                  CLOCK);
      var operations =
          new OperationsController(List.of(new AgentToolsOperationCatalog()), dispatcher, CLOCK);
      server =
          Javalin.create(
                  config -> {
                    config.showJavalinBanner = false;
                    config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                  })
              .post("/api/knowledge/ingest", operations::handleIngest)
              .get("/api/operation-history/{operationKey}", history::handleOutcome)
              .start("127.0.0.1", 0);
      client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    static Harness open(Path directory, Path root, boolean gated) throws Exception {
      return new Harness(directory, root, gated);
    }

    private void requirePreparation() {
      if (!preparationAvailable) throw new AssertionError("recorded retry prepared again");
    }

    JsonNode post(Path path, String key, Map<String, String> headers) throws Exception {
      HttpResponse<String> response = postResponse(path, key, headers);
      assertEquals(200, response.statusCode(), response.body());
      return JSON.readTree(response.body());
    }

    HttpResponse<String> postResponse(Path path, String key, Map<String, String> headers)
        throws Exception {
      return postArguments(Map.of("paths", List.of(path.toString()), "idempotencyKey", key), headers);
    }

    HttpResponse<String> postArguments(Map<String, Object> arguments, Map<String, String> headers)
        throws Exception {
      String body = JSON.writeValueAsString(arguments);
      HttpRequest.Builder request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.port() + "/api/knowledge/ingest"))
              .timeout(Duration.ofSeconds(3))
              .header("Content-Type", "application/json");
      headers.forEach(request::header);
      return client.send(
          request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode getOutcome(String key) throws Exception {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + server.port()
                          + "/api/operation-history/"
                          + key))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return JSON.readTree(response.body());
    }

    @Override
    public void close() throws java.io.IOException {
      ingestion.completion.complete(OperationResult.success("test cleanup"));
      server.stop();
      history.shutdown();
      executors.close();
      store.close();
    }
  }

  private static final class ControlledIngestion implements RecordedIngestionService {
    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicReference<OperationRecordHandle> record = new AtomicReference<>();
    private final CompletableFuture<OperationResult> completion = new CompletableFuture<>();

    @Override
    public OperationExecution execute(OperationRecordHandle accepted, EngineContext context) {
      executions.incrementAndGet();
      record.set(accepted);
      return new OperationExecution(OperationResult.success("started"), completion);
    }

    @Override
    public void maintain() {}
  }
}
