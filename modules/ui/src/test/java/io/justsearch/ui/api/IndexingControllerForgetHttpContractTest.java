/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.routes.IndexingRoutes;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Production route registrar/controller and real HTTP/schema boundary; fixed read-owner fixture. */
final class IndexingControllerForgetHttpContractTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String KEY = "01993ba0-0000-7000-8000-000000000001";
  private Javalin app;
  private String mode = "PAGE";
  private final AtomicInteger reads = new AtomicInteger();
  private final AtomicInteger effects = new AtomicInteger();
  private long after;
  private int limit;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @BeforeEach void start() {
    Class<?> port = assertDoesNotThrow(() -> Class.forName("io.justsearch.app.api.operations.ForgetService"),
        "the host must bind the explicit read-only FORGET owner port");
    Object owner = Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
      if (method.getName().equals("hasOwner")) return !mode.equals("MISSING");
      if (!method.getName().equals("unfinished") && !method.getName().equals("observe")) {
        effects.incrementAndGet(); throw new AssertionError("Observation cannot invoke an effect: " + method.getName());
      }
      reads.incrementAndGet();
      if (mode.equals("UNAVAILABLE")) throw new IllegalStateException("private fixture: unreadable operations authority");
      if (method.getName().equals("observe")) return Optional.empty();
      after = (long) args[0]; limit = (int) args[1];
      if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page");
      var item = Map.of("operationKey", KEY, "path", "/docs/report\\[1].txt ", "scope", "FILE",
          "status", "INCOMPLETE", "removal", "UNKNOWN", "keepOut", "UNKNOWN",
          "reason", "WITHDRAWAL_PENDING", "explanation", "Withdrawal is not confirmed; prior removal may already have happened.");
      return JSON.readValue(JSON.writeValueAsString(Map.of("items", List.of(item), "complete", false, "nextId", 37)),
          Class.forName("io.justsearch.app.api.operations.ForgetPage"));
    });
    var controller = assertDoesNotThrow(() -> IndexingController.class
        .getConstructor(Supplier.class, ExcludesService.class, Path.class, Telemetry.class, Supplier.class)
        .newInstance((Supplier<Object>) () -> null, null, null, null, (Supplier<Object>) () -> owner));
    app = Javalin.create(config -> {
      config.showJavalinBanner = false; config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    IndexingRoutes.register(app, controller); app.start(0);
  }
  @AfterEach void close() { if (app != null) app.stop(); }
  private HttpResponse<String> get(String path, String clientKind) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(5)).GET();
    if (clientKind != null) request.header("X-JustSearch-Client-Kind", clientKind);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test void S56UnfinishedHttpPageUsesCanonicalSchemaExactBytesAndExplicitContinuation() throws Exception {
    var result = get("/api/indexing/forgets?afterId=12&limit=7", null);
    assertEquals(200, result.statusCode());
    ContractSchemaAssertions.assertConforms("unfinished FORGET HTTP", "forget-page.v1.json", result.body());
    var page = JSON.readTree(result.body());
    assertFalse(page.path("complete").asBoolean()); assertEquals(37, page.path("nextId").asInt());
    assertEquals("/docs/report\\[1].txt ", page.path("items").get(0).path("path").asString());
    assertEquals("WITHDRAWAL_PENDING", page.path("items").get(0).path("reason").asString());
    assertEquals(12, after); assertEquals(7, limit); assertEquals(0, effects.get());
  }
  @Test void N56MissingIntentHttpObservationIsExplicitUnknownRatherThanFictitiousSuccess() throws Exception {
    var result = get("/api/indexing/forgets/" + KEY, null);
    assertEquals(200, result.statusCode());
    ContractSchemaAssertions.assertConforms("unknown FORGET HTTP", "forget-outcome.v1.json", result.body());
    var value = JSON.readTree(result.body());
    assertEquals(KEY, value.path("operationKey").asString()); assertEquals("UNKNOWN", value.path("status").asString());
    assertEquals("UNKNOWN", value.path("removal").asString()); assertEquals("UNKNOWN", value.path("keepOut").asString());
    assertTrue(value.path("path").isNull()); assertTrue(value.path("scope").isNull()); assertEquals(0, effects.get());
  }
  @Test void N55UnavailableAndUnsupportedOwnersNeverBecomeEmptySuccessfulHttpLists() throws Exception {
    for (String failed : List.of("UNAVAILABLE", "MISSING")) {
      mode = failed;
      for (String path : List.of("/api/indexing/forgets", "/api/indexing/forgets/" + KEY)) {
        var result = get(path, null); assertEquals(503, result.statusCode());
        var body = JSON.readTree(result.body()); assertFalse(body.has("items")); assertFalse(body.has("status"));
        assertEquals("SERVICE_UNAVAILABLE", body.path("errorCode").asString());
      }
    }
    assertEquals(0, effects.get());
  }
  @Test void N55NonWebviewHttpCallersCannotReadPrivateTargetsAndMalformedQueriesCannotMutate() throws Exception {
    for (String caller : List.of("MCP_CLIENT", "INTERNAL", "CLI", "PAIRED_DEVICE")) {
      assertEquals(403, get("/api/indexing/forgets", caller).statusCode());
      assertEquals(403, get("/api/indexing/forgets/" + KEY, caller).statusCode());
    }
    assertEquals(0, reads.get());
    for (String path : List.of("/api/indexing/forgets?afterId=-1", "/api/indexing/forgets?limit=0",
        "/api/indexing/forgets?limit=101", "/api/indexing/forgets?afterId=garbage",
        "/api/indexing/forgets?limit=2147483648", "/api/indexing/forgets/not-an-operation-key")) {
      assertEquals(400, get(path, null).statusCode(), path);
    }
    assertEquals(0, effects.get());
  }
}
