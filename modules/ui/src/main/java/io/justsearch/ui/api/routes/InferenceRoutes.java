/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.javalin.http.Handler;

public final class InferenceRoutes {
  private InferenceRoutes() {}

  public static void register(
      Javalin app,
      Handler inferenceStatusHandler,
      Handler gpuCapabilitiesHandler,
      Handler setInferenceModeHandler,
      Handler detachExternalInferenceServerHandler,
      Handler restartWorkerHandler,
      Handler encoderRuntimeHandler,
      Handler inferenceFailuresHandler,
      Handler inferenceTransitionsHandler) {
    app.get("/api/inference/status", inferenceStatusHandler);
    app.get("/api/gpu/capabilities", gpuCapabilitiesHandler);
    app.post("/api/inference/mode", setInferenceModeHandler);
    app.post("/api/inference/detach", detachExternalInferenceServerHandler);
    app.post("/api/worker/restart", restartWorkerHandler);
    // Tempdoc 422: per-encoder runtime accelerator explainer.
    app.get("/api/inference/encoders", encoderRuntimeHandler);
    // Tempdoc 518 Appendix F W2.1: failure-history ring buffer.
    app.get("/api/inference/failures", inferenceFailuresHandler);
    // Tempdoc 518 Appendix F W3.2: mode-transition timeline.
    app.get("/api/inference/transitions", inferenceTransitionsHandler);
  }
}
