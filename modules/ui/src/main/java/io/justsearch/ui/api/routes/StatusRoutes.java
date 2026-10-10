/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
import io.javalin.http.Handler;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.DiagnosticsController;
import io.justsearch.ui.api.PolicyController;
import io.justsearch.ui.api.SettingsController;
import io.justsearch.ui.api.TelemetryHealthController;
import io.justsearch.ui.api.UiReadyController;

public final class StatusRoutes {
  private StatusRoutes() {}

  public static void register(
      Javalin app,
      Telemetry telemetry,
      Handler statusHandler,
      Handler healthHandler,
      UiReadyController uiReadyController,
      SettingsController settingsController,
      PolicyController policyController,
      DiagnosticsController diagnosticsController) {
    registerLifecycleRoutes(app, statusHandler, healthHandler);

    if (telemetry instanceof LocalTelemetry lt) {
      var telemetryHealthController = new TelemetryHealthController(lt::getHealthSnapshot);
      app.get("/api/telemetry/health", telemetryHealthController::handleGetHealth, ActivityRole.OBSERVATION);
    }

    app.post("/api/ui/ready", uiReadyController::handlePost, ActivityRole.OBSERVATION);
    app.post("/api/ui/activity", ctx -> ctx.status(204), ActivityRole.USER_ACTION);
    app.get("/api/ui/ready", uiReadyController::handleGet, ActivityRole.OBSERVATION);
    app.get("/api/settings/v2", settingsController::handleGetSettingsV2, ActivityRole.OBSERVATION);
    app.post("/api/settings/v2", settingsController::handleUpdateSettingsV2, ActivityRole.USER_ACTION);
    app.get("/api/policy/effective", policyController::handleGetEffectivePolicy, ActivityRole.OBSERVATION);
    app.get("/api/policy/validate", policyController::handleValidatePolicy, ActivityRole.OBSERVATION);
    app.post("/api/policy/user/create", policyController::handleCreateUserPolicy, ActivityRole.USER_ACTION);
    app.post(
        "/api/policy/user/allowlist/pack-manifest/add",
        policyController::handleAddPackManifestShaToUserAllowlist, ActivityRole.USER_ACTION);
    app.post("/api/diagnostics/export", diagnosticsController::handleExport, ActivityRole.USER_ACTION);
    // Tempdoc 518 Appendix G Wave D.1: in-product trace explorer endpoint.
    app.get("/api/diagnostics/traces", diagnosticsController::handleRecentTraces, ActivityRole.OBSERVATION);
  }

  /** Shared registration seam used by the runtime-client contract projection tests. */
  public static void registerLifecycleRoutes(
      Javalin app, Handler statusHandler, Handler healthHandler) {
    app.get("/api/status", statusHandler, ActivityRole.OBSERVATION);
    app.get("/api/health", healthHandler, ActivityRole.OBSERVATION);
  }
}
