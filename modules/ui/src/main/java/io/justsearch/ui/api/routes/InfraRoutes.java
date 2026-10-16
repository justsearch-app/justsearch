/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.observability.CapabilitiesService;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.util.RepoPaths;
import io.justsearch.ui.api.CapabilitiesStreamController;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 521 §16.8 — Javalin routes serving the `/infra/capabilities` substrate.
 *
 * <p>Extracted from {@code LocalApiServer.setupRoutes} so the route registration is
 * statically testable via {@code LegacyEndpointGuardTest} (which inspects Javalin's
 * internal router without spinning up an HTTP server).
 *
 * <p>Two routes:
 * <ul>
 *   <li>{@code GET /infra/capabilities} — JSON contract-version map. Returns 503 while
 *       {@link HeadAssembly#capabilitiesHandler()} is null (bootstrap not yet
 *       complete), 200 with the {@code CapabilitiesService.CapabilitiesView} once ready,
 *       500 on serialization failure.
 *   <li>{@code GET /infra/capabilities/stream} (SSE) — Capability change stream wired
 *       to a {@link CapabilitiesStreamController} handle.
 * </ul>
 */
public final class InfraRoutes {
  private static final Logger log = LoggerFactory.getLogger(InfraRoutes.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private InfraRoutes() {}

  public static void register(
      Javalin app,
      HeadAssembly HeadAssembly,
      CapabilitiesStreamController capabilitiesStreamController) {
    if (capabilitiesStreamController != null) {
      app.sse("/infra/capabilities/stream", capabilitiesStreamController::handle, ActivityRole.OBSERVATION);
    }
    if (HeadAssembly != null) {
      app.get(
          "/infra/capabilities",
          ctx -> {
            // Bootstrap-completion guard: capabilitiesHandler() returns non-null only
            // after createCapabilitiesHandler() runs in HeadAssembly construction.
            // Pre-completion requests see 503; post-completion requests build the view
            // inline (the legacy HttpHandler exists for external launchers wiring the
            // bare com.sun.net.httpserver.HttpServer).
            if (HeadAssembly.capabilitiesHandler() == null) {
              ctx.status(503)
                  .json(
                      Map.of(
                          "error", "Capabilities handler not initialized",
                          "errorCode", ApiErrorCode.SERVICE_UNAVAILABLE.name()));
              return;
            }
            try {
              var service =
                  new CapabilitiesService(
                      RepoPaths.findRepoRoot(),
                      () ->
                          HeadAssembly.capabilitiesChangeRegistry() == null
                              ? 0L
                              : HeadAssembly.capabilitiesChangeRegistry().currentSeq());
              var view = service.capabilities();
              ctx.contentType("application/json")
                  .result(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(view));
            } catch (Exception e) {
              log.warn("/infra/capabilities GET failed: {}", e.getMessage(), e);
              ctx.status(500)
                  .json(
                      Map.of(
                          "error",
                              e.getMessage() == null ? e.toString() : e.getMessage(),
                          "errorCode", ApiErrorCode.INTERNAL_ERROR.name()));
            }
          }, ActivityRole.OBSERVATION);
    }
  }
}
