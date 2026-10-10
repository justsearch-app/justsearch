/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ActivityRole;
import io.javalin.http.Handler;

public final class InferenceRoutes {
  private InferenceRoutes() {}

  public static void register(
      Javalin app,
      Handler inferenceStatusHandler,
      Handler gpuCapabilitiesHandler,
      Handler setInferenceModeHandler,
      Handler detachExternalInferenceServerHandler,
      Handler retiredWorkerRestartTombstoneHandler,
      Handler recoverComponentHandler,
      Handler encoderRuntimeHandler,
      Handler inferenceFailuresHandler,
      Handler inferenceTransitionsHandler) {
    app.get("/api/inference/status", inferenceStatusHandler, ActivityRole.OBSERVATION);
    app.get("/api/gpu/capabilities", gpuCapabilitiesHandler, ActivityRole.OBSERVATION);
    app.post("/api/inference/mode", setInferenceModeHandler, ActivityRole.USER_ACTION);
    app.post("/api/inference/detach", detachExternalInferenceServerHandler, ActivityRole.USER_ACTION);
    app.post("/api/worker/restart", retiredWorkerRestartTombstoneHandler, ActivityRole.USER_ACTION);
    app.post("/api/engine/components/{name}/recover", recoverComponentHandler, ActivityRole.USER_ACTION);
    // Tempdoc 422: per-encoder runtime accelerator explainer.
    app.get("/api/inference/encoders", encoderRuntimeHandler, ActivityRole.OBSERVATION);
    // Tempdoc 518 Appendix F W2.1: failure-history ring buffer.
    app.get("/api/inference/failures", inferenceFailuresHandler, ActivityRole.OBSERVATION);
    // Tempdoc 518 Appendix F W3.2: mode-transition timeline.
    app.get("/api/inference/transitions", inferenceTransitionsHandler, ActivityRole.OBSERVATION);
  }
}
