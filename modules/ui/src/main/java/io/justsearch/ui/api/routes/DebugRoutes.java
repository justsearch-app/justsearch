/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
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
      Handler adminRuntimeReloadHandler) {
    app.get("/api/debug/state", debugStateController::handleGetState, ActivityRole.OBSERVATION);
    app.get("/api/debug/commit-metadata", debugStateController::handleGetCommitMetadata, ActivityRole.OBSERVATION);
    app.get("/api/debug/effective-config", effectiveConfigController::handleGetEffectiveConfig, ActivityRole.OBSERVATION);
    app.get("/api/debug/events", debugStateController::handleGetEvents, ActivityRole.USER_ACTION);
    app.get("/api/debug/engine-log", debugStateController::handleGetEngineLog, ActivityRole.USER_ACTION);
    app.get("/api/debug/dashboard", debugDashboardHandler, ActivityRole.OBSERVATION);
    app.get("/api/debug/chunks", chunkInfoController::handleGetChunkInfo, ActivityRole.USER_ACTION);
    app.get("/api/debug/logging", logLevelController::handleGetLogLevels, ActivityRole.OBSERVATION);
    app.post("/api/debug/logging", logLevelController::handleSetLogLevel, ActivityRole.USER_ACTION);
    app.get("/api/debug/metrics/timeseries", timeSeriesController::handleGetTimeSeries, ActivityRole.OBSERVATION);
    app.get("/api/debug/metrics/timeseries/available", timeSeriesController::handleGetAvailable, ActivityRole.OBSERVATION);
    app.get("/api/debug/session-policies", sessionPoliciesController::handle, ActivityRole.OBSERVATION);
    app.post("/api/debug/reset-index", resetIndexHandler, ActivityRole.USER_ACTION);
    // Tempdoc 406 — admin-triggered runtime swap. Triggers a holder swap on the
    // ingest runtime via an in-process port ReloadRuntime; returns swap duration in ms.
    app.post("/api/admin/runtime/reload", adminRuntimeReloadHandler, ActivityRole.USER_ACTION);
  }
}
