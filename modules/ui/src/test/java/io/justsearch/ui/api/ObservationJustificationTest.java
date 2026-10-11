/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.ui.api.routes.IndexingRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Deliberate history reads interrupt VDU; new observers need reviewable caller evidence. */
class ObservationJustificationTest {
  @ParameterizedTest
  @ValueSource(strings = {"registry-prompt", "registry-diagnostic", "inference-failures"})
  void messageCatalogsWithoutBootstrapConsumersAdvanceActivity(String namespace) throws Exception {
    var admission = new EngineAdmissionController(100, 100, 1);
    var executor = Executors.newSingleThreadExecutor();
    var app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    new ApiSecurityFilters(false, null, new EventBuffer(), executor, null, admission, admission).install(app);
    io.justsearch.ui.api.routes.MessageCatalogRoutes.register(app, null);
    app.start("127.0.0.1", 0);
    try (var http = HttpClient.newHttpClient()) {
      for (String requested : new String[] {"registry-resource", namespace}) {
        long captured = admission.userActivity().version();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
            + app.port() + "/api/messages/" + requested + "/en")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(requested.equals(namespace), admission.userActivity().version() > captured,
            "UI bootstrap catalog stays neutral; catalog without an unattended consumer is deliberate");
      }
    } finally {
      app.stop();
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/chat/sessions/{id}", "/api/chat/sessions/{id}/events",
      "/api/chat/agent/virtual-operations", "/api/debug/logging", "/api/debug/metrics/timeseries",
      "/api/debug/metrics/timeseries/available", "/api/inference/failures",
      "/api/runtime/instances", "/api/runtime/instances/{id}", "/api/operation-history"})
  void deliberateReadsWithoutUnattendedConsumersAdvanceActivity(String path) throws Exception {
    new IndependentDeclaredActivityTest().http("get", path, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"attach", "ag-ui"})
  void explicitLegacyAttachmentsAdvanceActivity(String suffix) throws Exception {
    var admission = new EngineAdmissionController(100, 100, 1);
    var executor = Executors.newSingleThreadExecutor();
    var app = Javalin.create(c -> c.showJavalinBanner = false);
    new ApiSecurityFilters(false, null, new EventBuffer(), executor, null, admission, admission).install(app);
    AgentRoutes.register(app, mock(AgentController.class), mock(AgentSessionController.class),
        mock(AgentToolsController.class));
    app.start("127.0.0.1", 0);
    try (var http = HttpClient.newHttpClient()) {
      long captured = admission.userActivity().version();
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
          + app.port() + "/api/chat/agent/fixture/" + suffix))
          .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(admission.userActivity().version() > captured,
          "Legacy attachment has no unattended caller; managed observe remains the UI reattach path");
    } finally {
      app.stop();
      executor.shutdownNow();
    }
  }

  @Test void ingestionHistoryReadAdvancesCapturedActivityThroughProductionFilters() throws Exception {
    var admission = new EngineAdmissionController(100, 100, 1);
    var executor = Executors.newSingleThreadExecutor();
    var app = Javalin.create(c -> c.showJavalinBanner = false);
    new ApiSecurityFilters(false, null, new EventBuffer(), executor, null, admission, admission).install(app);
    var controller = mock(IndexingController.class);
    doAnswer(invocation -> {
      io.javalin.http.Context context = invocation.getArgument(0);
      context.result("[]");
      return null;
    }).when(controller).handleRecentIngestionEvents(any());
    IndexingRoutes.register(app, controller);
    app.start("127.0.0.1", 0);
    try (var http = HttpClient.newHttpClient()) {
      long captured = admission.userActivity().version();
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
          + app.port() + "/api/diagnostics/ingestion/recent?limit=10"))
          .header("X-JustSearch-Client-Kind", "INTERNAL")
          .header("X-JustSearch-Transport", "SYSTEM_INTERNAL").GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(admission.userActivity().version() > captured,
          "Deliberate ingestion ledger read must invalidate the accepted VDU activity boundary");
      verify(controller).handleRecentIngestionEvents(any());
    } finally {
      app.stop();
      executor.shutdownNow();
    }
  }

  @Test void observationWithoutJustificationCannotShip() throws Exception {
    // Reflection keeps this regression executable on the unchanged before revision.
    Class<?> policy;
    try { policy = Class.forName("io.justsearch.ui.api.ObservationJustifications"); }
    catch (ClassNotFoundException missing) { fail("Observation declarations lack a justification guard"); return; }
    var validate = policy.getDeclaredMethod("validate", Javalin.class);
    validate.setAccessible(true);
    var app = Javalin.create();
    app.get("/api/new-history-read", c -> {}, ActivityRole.OBSERVATION);
    var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> validate.invoke(null, app));
    assertInstanceOf(IllegalStateException.class, rejected.getCause());
    assertTrue(rejected.getCause().getMessage().contains("GET /api/new-history-read"));
  }

  @Test void registeredRouteFamiliesHaveJustifications() throws Exception {
    Class<?> policy;
    try { policy = Class.forName("io.justsearch.ui.api.ObservationJustifications"); }
    catch (ClassNotFoundException missing) { fail("Observation declarations lack a justification guard"); return; }
    var app = Javalin.create();
    var register = LegacyEndpointGuardTest.class.getDeclaredMethod("registerRealRoutes", Javalin.class);
    register.setAccessible(true);
    register.invoke(null, app);
    RunRoutes.register(app, mock(RunStreamController.class), mock(AgentSessionController.class));
    io.justsearch.ui.api.routes.MessageCatalogRoutes.register(app, null);
    var validate = policy.getDeclaredMethod("validate", Javalin.class);
    validate.setAccessible(true);
    assertDoesNotThrow(() -> validate.invoke(null, app));
  }
}
