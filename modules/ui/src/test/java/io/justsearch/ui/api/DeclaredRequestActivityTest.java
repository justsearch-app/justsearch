/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.ui.api.routes.InferenceRoutes;
import io.justsearch.ui.api.routes.StatusRoutes;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Real route registration and HTTP admission; attribution cannot invent input. */
class DeclaredRequestActivityTest {
  @Test void registeredUserOperationsAndUnattendedReadsHaveTheirDeclaredHttpBehavior() throws Exception {
    var admission = new EngineAdmissionController(100, 100, 1);
    var executor = Executors.newSingleThreadExecutor();
    var app = Javalin.create(c -> c.showJavalinBanner = false);
    new ApiSecurityFilters(false, null, new EventBuffer(), executor, null, admission, admission).install(app);
    var registration = LegacyEndpointGuardTest.class.getDeclaredMethod("registerRealRoutes", Javalin.class);
    registration.setAccessible(true);
    registration.invoke(null, app);
    RunRoutes.register(app, mock(RunStreamController.class), mock(AgentSessionController.class));
    var routes = app.unsafeConfig().pvt.internalRouter.allHttpHandlers().stream()
        .map(entry -> entry.getEndpoint()).filter(endpoint -> endpoint.getMethod().isHttpMethod())
        .filter(endpoint -> !endpoint.getMethod().name().equals("OPTIONS")).toList();
    app.start("127.0.0.1", 0);
    try (var http = HttpClient.newHttpClient()) {
      for (var route : routes) {
        String path = route.getPath();
        boolean observation = path.equals("/api/ui/ready") || path.endsWith("/observe")
            || (path.endsWith("/virtual-operations") && route.getMethod().name().equals("POST"))
            || path.endsWith("/tool-result");
        boolean user = !observation && (!route.getMethod().name().equals("GET")
            || path.equals("/api/preview") || path.equals("/api/knowledge/suggest")
            || Set.of("/api/diagnostics/ingestion/recent", "/api/chat/sessions/{id}", "/api/chat/sessions/{id}/events", "/api/chat/agent/virtual-operations", "/api/debug/logging", "/api/debug/metrics/timeseries", "/api/debug/metrics/timeseries/available", "/api/inference/failures", "/api/runtime/instances", "/api/runtime/instances/{id}", "/api/operations/{id}/preview", "/api/chat/sessions/last", "/api/chat/sessions/{id}/transcript", "/api/chat/agent/history", "/api/chat/agent/history/{batchId}", "/api/debug/events", "/api/debug/engine-log", "/api/debug/chunks", "/api/indexing/suggested-roots", "/api/registry/prompts", "/api/meta/openapi.json", "/api/knowledge/search").contains(path));
        long before = IndependentObserverHttpTest.version(admission);
        String concrete = path.replaceAll("\\{[^}]+}", "fixture");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + concrete))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .method(route.getMethod().name(), route.getMethod().name().equals("GET")
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"));
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(user, IndependentObserverHttpTest.version(admission) > before,
            route.getMethod() + " " + path + " operation input semantics");
      }
    } finally { app.stop(); executor.shutdownNow(); }
  }

  @Test void brainTimerAndAutomaticPostLeavePassBoundaryUntouched() throws Exception {
    var admission = new EngineAdmissionController(100, 100, 1);
    var executor = Executors.newSingleThreadExecutor();
    var app = Javalin.create(c -> c.showJavalinBanner = false);
    new ApiSecurityFilters(false, null, new EventBuffer(), executor, null, admission, admission).install(app);
    Handler ok = c -> c.result("ok");
    InferenceRoutes.register(app, ok, ok, ok, ok, ok, ok, ok, ok, ok);
    StatusRoutes.register(app, null, ok, ok, mock(UiReadyController.class),
        mock(SettingsController.class), mock(PolicyController.class), mock(DiagnosticsController.class));
    app.start("127.0.0.1", 0);
    try (var http = HttpClient.newHttpClient()) {
      for (String transport : List.of("", "BUTTON", "MCP")) {
        for (String path : List.of("/api/inference/transitions", "/api/inference/status", "/api/ui/ready")) {
          long before = IndependentObserverHttpTest.version(admission);
          var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
              .timeout(Duration.ofSeconds(10));
          if (!transport.isEmpty()) request.header("X-JustSearch-Transport", transport);
          request.GET();
          assertEquals(200, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
          assertEquals(before, IndependentObserverHttpTest.version(admission), path + ": " + transport);
        }
      }
      long before = IndependentObserverHttpTest.version(admission);
      var ready = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/ui/ready"))
          .POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json").build();
      assertEquals(200, http.send(ready, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(before, IndependentObserverHttpTest.version(admission), "unattended readiness POST is not input");
      var action = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/inference/mode"))
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      assertEquals(200, http.send(action, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertTrue(IndependentObserverHttpTest.version(admission) > before, "explicit inference action records input");
    } finally { app.stop(); executor.shutdownNow(); }
  }

  @Test void everyProductionRouteDeclaresActivityAtRegistration() throws Exception {
    Path root = Path.of(System.getProperty("user.dir"));
    while (!Files.isDirectory(root.resolve("modules/ui/src/main/java"))) root = root.getParent();
    try (var files = Files.walk(root.resolve("modules/ui/src/main/java/io/justsearch/ui/api"))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var matcher = java.util.regex.Pattern.compile("(?m)^\\s*app\\.(?:get|post|put|delete|patch|head|sse|options)\\(").matcher(source);
        while (matcher.find()) {
          int start = matcher.end(), end = start, depth = 1;
          boolean quoted = false, escaped = false;
          while (depth > 0) {
            char c = source.charAt(end++);
            if (quoted) {
              if (escaped) escaped = false;
              else if (c == '\\') escaped = true;
              else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '(') depth++;
            else if (c == ')') depth--;
          }
          assertTrue(source.substring(start, end).contains("ActivityRole."),
              file.getFileName() + ": route must declare activity: " + source.substring(start, Math.min(end, start + 100)));
        }
      }
    }
  }

  @Test void newUndeclaredPollingOperationCannotInventActivity() throws Exception {
    var admission = new EngineAdmissionController(20, 100, 1);
    var executor = Executors.newSingleThreadExecutor();
    var app = Javalin.create(c -> c.showJavalinBanner = false);
    new ApiSecurityFilters(false, null, new EventBuffer(), executor, null, admission, admission).install(app);
    app.post("/api/new-poll", c -> c.result("ok"));
    app.start("127.0.0.1", 0);
    try (var http = HttpClient.newHttpClient()) {
      long before = IndependentObserverHttpTest.version(admission);
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/new-poll"))
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(before, IndependentObserverHttpTest.version(admission), "method/default WEBVIEW/BUTTON is not activity");
    } finally { app.stop(); executor.shutdownNow(); }
  }
}
