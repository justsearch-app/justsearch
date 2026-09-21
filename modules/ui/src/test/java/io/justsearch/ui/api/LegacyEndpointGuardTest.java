package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.router.InternalRouter;
import io.justsearch.ui.api.routes.AiRoutes;
import io.justsearch.ui.api.routes.DebugRoutes;
import io.justsearch.ui.api.routes.IndexingRoutes;
import io.justsearch.ui.api.routes.InferenceRoutes;
import io.justsearch.ui.api.routes.InfraRoutes;
import io.justsearch.ui.api.routes.KnowledgeRoutes;
import io.justsearch.ui.api.routes.StatusRoutes;
import java.util.Set;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that removed legacy endpoints are absent from the real route set.
 *
 * <p>Complements {@code docsApiDriftCheck} (which prevents docs from referencing these endpoints)
 * by proving the routes are absent from the actual route set. If a developer re-adds {@code
 * /api/search} or {@code /api/settings} to any {@code *Routes.register()} call, this test fails.
 *
 * <p>Legacy endpoints removed in commit 1ce7bee4 (2026-01-06):
 *
 * <ul>
 *   <li>{@code POST /api/search} — replaced by {@code POST /api/knowledge/search}
 *   <li>{@code GET /api/settings} — replaced by {@code GET /api/settings/v2}
 *   <li>{@code POST /api/settings} — replaced by {@code POST /api/settings/v2}
 * </ul>
 *
 * <p>Implementation note: uses Javalin's internal {@link InternalRouter} API to inspect registered
 * routes without starting an HTTP server. This API is used by Javalin's own RouteOverviewPlugin and
 * is stable in Javalin 6.x, but may change in Javalin 7.
 */
@DisplayName("Legacy endpoint resurrection prevention")
class LegacyEndpointGuardTest {

  /** All routes registered by the real {@code *Routes.register()} methods, as "METHOD /path". */
  private static Set<String> registeredRoutes;

  @BeforeAll
  static void setupRoutes() {
    Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
    registerRealRoutes(app);

    InternalRouter router = app.unsafeConfig().pvt.internalRouter;
    registeredRoutes =
        router.allHttpHandlers().stream()
            .filter(pe -> pe.getEndpoint().getMethod().isHttpMethod())
            .map(pe -> pe.getEndpoint().getMethod() + " " + pe.getEndpoint().getPath())
            .collect(Collectors.toUnmodifiableSet());
  }

  @Test
  @DisplayName("route registration sanity check — replacement endpoints are present")
  void replacementEndpointsAreRegistered() {
    assertTrue(
        registeredRoutes.contains("POST /api/knowledge/search"),
        "POST /api/knowledge/search should be registered — route registration may have failed");
    assertTrue(
        registeredRoutes.contains("GET /api/settings/v2"),
        "GET /api/settings/v2 should be registered — route registration may have failed");
  }

  @Test
  @DisplayName("tempdoc 521 §16.8 — InfraRoutes registers /infra/capabilities routes")
  void infraCapabilitiesRoutesAreRegistered() {
    assertTrue(
        registeredRoutes.contains("GET /infra/capabilities"),
        "GET /infra/capabilities should be registered by InfraRoutes (tempdoc 521 §16.8)");
    assertTrue(
        registeredRoutes.contains("GET /infra/capabilities/stream"),
        "GET /infra/capabilities/stream (SSE) should be registered by InfraRoutes "
            + "(tempdoc 521 §16.8)");
  }

  @Test
  @DisplayName("tempdoc 541 §4.2 — BootRoutes registers /api/boot/phases")
  void bootPhasesRouteIsRegistered() {
    assertTrue(
        registeredRoutes.contains("GET /api/boot/phases"),
        "GET /api/boot/phases should be registered by BootRoutes (tempdoc 541 §4.2)");
  }

  @Test
  @DisplayName("POST /api/search is absent (removed — use POST /api/knowledge/search)")
  void legacySearchPostIsAbsent() {
    assertFalse(
        registeredRoutes.contains("POST /api/search"),
        "POST /api/search was removed in 1ce7bee4 — use POST /api/knowledge/search");
  }

  @Test
  void reindexCannotFallBackToIndexingRoutesWithoutRecordedComposition() {
    assertFalse(registeredRoutes.contains("POST /api/indexing/reindex"),
        "ResourceApiModule owns recorded reindex; IndexingRoutes must not provide a direct-service fallback");
  }

  @Test
  @DisplayName("GET /api/settings is absent (removed — use GET /api/settings/v2)")
  void legacySettingsGetIsAbsent() {
    assertFalse(
        registeredRoutes.contains("GET /api/settings"),
        "GET /api/settings was removed in 1ce7bee4 — use GET /api/settings/v2");
  }

  @Test
  @DisplayName("POST /api/settings is absent (removed — use POST /api/settings/v2)")
  void legacySettingsPostIsAbsent() {
    assertFalse(
        registeredRoutes.contains("POST /api/settings"),
        "POST /api/settings was removed in 1ce7bee4 — use POST /api/settings/v2");
  }

  @Test
  @DisplayName("POST /api/summarize/batch/stream is absent (removed — use POST /api/chat/batch-summarize)")
  void legacySummarizeBatchStreamIsAbsent() {
    assertFalse(
        registeredRoutes.contains("POST /api/summarize/batch/stream"),
        "POST /api/summarize/batch/stream was removed in slice 491 C2.2 — "
            + "use POST /api/chat/batch-summarize");
  }

  @Test
  @DisplayName("POST /api/summarize/hierarchical/stream is absent (removed — use POST /api/chat/hierarchical-summarize)")
  void legacyHierarchicalSummaryStreamIsAbsent() {
    assertFalse(
        registeredRoutes.contains("POST /api/summarize/hierarchical/stream"),
        "POST /api/summarize/hierarchical/stream was removed in slice 491 C2.3 — "
            + "use POST /api/chat/hierarchical-summarize");
  }

  @Test
  @DisplayName("POST /api/ask/stream is absent (removed — use POST /api/chat/ask)")
  void legacyAskStreamIsAbsent() {
    assertFalse(
        registeredRoutes.contains("POST /api/ask/stream"),
        "POST /api/ask/stream was removed in slice 491 C3 — use POST /api/chat/ask");
  }

  @Test
  @DisplayName("All /api/agent/* routes are absent (moved to /api/chat/* in slice 491 C4)")
  void legacyAgentNamespaceIsAbsent() {
    String[] legacyAgentRoutes = {
      "POST /api/agent/run/stream",
      "POST /api/agent/approve",
      "POST /api/agent/reject",
      "GET /api/agent/tools",
      "GET /api/agent/session/last",
      "POST /api/agent/session/resume-last/stream",
      "GET /api/agent/sessions",
      "GET /api/agent/session/{id}",
      "POST /api/agent/session/{id}/resume/stream",
      "GET /api/agent/session/{id}/transcript",
      "GET /api/agent/session/{id}/events",
      "DELETE /api/agent/session/{id}",
      "POST /api/agent/undo",
      "GET /api/agent/history",
      "GET /api/agent/history/{batchId}",
    };
    for (String route : legacyAgentRoutes) {
      assertFalse(
          registeredRoutes.contains(route),
          route + " was removed in slice 491 C4 — use the /api/chat/* equivalent");
    }
  }

  /**
   * Register the real route set with mock controllers.
   *
   * <p>Mirrors {@code LocalApiServer.setupRoutes()} — calls every real {@code *Routes.register()}
   * method so the test exercises the actual route paths. Controllers are Mockito mocks because most
   * route files eagerly dereference them via method references during registration.
   */
  private static void registerRealRoutes(Javalin app) {
    Handler noop = ctx -> {};

    StatusRoutes.register(
        app,
        null,
        noop,
        noop,
        mock(UiReadyController.class),
        mock(SettingsController.class),
        mock(PolicyController.class),
        mock(DiagnosticsController.class));
    IndexingRoutes.register(app, mock(IndexingController.class));
    DebugRoutes.register(
        app,
        mock(DebugStateController.class),
        mock(EffectiveConfigController.class),
        mock(ChunkInfoController.class),
        noop,
        mock(LogLevelController.class),
        mock(TimeSeriesController.class),
        mock(SessionPoliciesController.class),
        noop,
        noop,
        noop);
    ChatController chatControllerMock = mock(ChatController.class);
    when(chatControllerMock.handler(any(), any())).thenReturn(noop);
    when(chatControllerMock.dynamicHandler(any())).thenReturn(noop);
    AiRoutes.register(
        app,
        mock(PreviewController.class),
        mock(AiInstallController.class),
        mock(AiPackController.class),
        mock(AiRuntimeController.class),
        mock(AiModelsController.class),
        chatControllerMock);
    InferenceRoutes.register(app, noop, noop, noop, noop, noop, noop, noop, noop, noop);
    KnowledgeRoutes.register(
        app, mock(KnowledgeSearchController.class), LoggerFactory.getLogger("test"));
    AgentRoutes.register(
        app,
        mock(AgentController.class),
        mock(AgentSessionController.class),
        mock(AgentToolsController.class));
    InfraRoutes.register(
        app,
        mock(io.justsearch.app.services.HeadAssembly.class),
        mock(CapabilitiesStreamController.class));
    // Tempdoc 541 §4.2: BootRoutes registers GET /api/boot/phases.
    io.justsearch.ui.api.routes.BootRoutes.register(
        app, mock(io.justsearch.app.services.HeadAssembly.class));
  }
}
