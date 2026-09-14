/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.justsearch.ui.api.ChunkInfoController;
import io.justsearch.ui.api.DebugStateController;
import io.justsearch.ui.api.EffectiveConfigController;
import io.justsearch.ui.api.LogLevelController;
import io.justsearch.ui.api.SessionPoliciesController;
import io.justsearch.ui.api.TimeSeriesController;

public final class DebugRoutes {
  private DebugRoutes() {}

  public static void register(
      Javalin app,
      DebugStateController debugStateController,
      EffectiveConfigController effectiveConfigController,
      ChunkInfoController chunkInfoController,
      Handler debugDashboardHandler,
      LogLevelController logLevelController,
      TimeSeriesController timeSeriesController,
      SessionPoliciesController sessionPoliciesController,
      Handler resetIndexHandler,
      Handler adminRuntimeReloadHandler,
      Handler adminInferenceReloadHandler) {
    app.get("/api/debug/state", debugStateController::handleGetState);
    app.get("/api/debug/commit-metadata", debugStateController::handleGetCommitMetadata);
    app.get("/api/debug/effective-config", effectiveConfigController::handleGetEffectiveConfig);
    app.get("/api/debug/events", debugStateController::handleGetEvents);
    app.get("/api/debug/engine-log", debugStateController::handleGetEngineLog);
    app.get("/api/debug/dashboard", debugDashboardHandler);
    app.get("/api/debug/chunks", chunkInfoController::handleGetChunkInfo);
    app.get("/api/debug/logging", logLevelController::handleGetLogLevels);
    app.post("/api/debug/logging", logLevelController::handleSetLogLevel);
    app.get("/api/debug/metrics/timeseries", timeSeriesController::handleGetTimeSeries);
    app.get("/api/debug/metrics/timeseries/available", timeSeriesController::handleGetAvailable);
    app.get("/api/debug/session-policies", sessionPoliciesController::handle);
    app.post("/api/debug/reset-index", resetIndexHandler);
    // Tempdoc 406 — admin-triggered runtime swap. Triggers a holder swap on the
    // ingest runtime via gRPC ReloadRuntime; returns swap duration in ms.
    app.post("/api/admin/runtime/reload", adminRuntimeReloadHandler);
    // Tempdoc 412 Phase 5 — admin-triggered inference runtime restart via
    // OnlineAiRuntimeControl.reloadRuntime() (RESTART_IF_ONLINE).
    app.post("/api/admin/inference/reload", adminInferenceReloadHandler);
  }
}
