/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.*;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ui.api.OperationHistoryController;
import io.justsearch.ui.api.TestRequestContexts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

final class BulkReindexFailureTransportTest {
  @TempDir Path temp;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MESSAGE = "The rebuild or migration could not safely start or resume the new index; "
      + "check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"http-outcome", "http-history", "mcp-text", "mcp-structured"})
  void eachTransportExplainsTheRefusalAndRetainsItsCode(String surface) throws Exception {
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"));
        var executors = new TestEngineExecutors(); var http = HttpClient.newHttpClient()) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var context = TestRequestContexts.mcp("refusal-query");
      var row = operations.accept(key, OperationDescriptor.invocation(OperationKind.REINDEX,
          "core.bulk-reindex", "{}", false), context, InvocationProvenance.fromEngineContext(context,
              ExecutorTag.AGENT, Instant.now(), Optional.empty()), OperationHistoryMode.STANDARD).record();
      operations.finish(row.id(), OperationState.FAILED, new OperationReceipt("BULK_GENERATION_REFUSED", null));
      var controller = new OperationHistoryController(executors, new OperationHistoryStore(operations),
          new OperationHistoryChangeRegistry(), operations::outcome);
      var app = Javalin.create(config -> config.showJavalinBanner = false);
      app.get("/api/operation-history", controller::handleGet);
      app.get("/api/operation-history/{operationKey}", controller::handleOutcome);
      app.start("127.0.0.1", 0);
      try {
        final tools.jackson.databind.JsonNode wire;
        if (surface.startsWith("http-")) {
          String base = "http://127.0.0.1:" + app.port() + "/api/operation-history";
          String url = surface.equals("http-history") ? base : base + "/" + key;
          var response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          var body = JSON.readTree(response.body());
          wire = surface.equals("http-history") ? body.path("entries").get(0) : body;
        } else {
          var facade = mock(HeadAssembly.class);
          when(facade.operationOutcome(key)).thenAnswer(unused -> operations.outcome(key));
          var dispatcher = mock(OperationDispatcher.class);
          var tools = new McpToolSurface(List.of(), dispatcher, () -> null, () -> facade, Clock.systemUTC());
          var result = tools.callTool("justsearch_operation_outcome", Map.of("operationKey", key),
              "refusal-query", context);
          wire = surface.equals("mcp-text")
              ? JSON.readTree(JSON.valueToTree(result.get("content")).get(0).path("text").asText())
              : JSON.readTree(JSON.writeValueAsBytes(result.get("structuredContent")));
          verifyNoInteractions(dispatcher);
        }
        assertAll(
            () -> assertEquals(MESSAGE, wire.path("message").asText()),
            () -> assertEquals("BULK_GENERATION_REFUSED", surface.equals("http-history")
                ? wire.path("diagnosticsLink").asText() : wire.path("result").path("code").asText()));
      } finally {
        app.stop();
        controller.shutdown();
      }
    }
  }
}
