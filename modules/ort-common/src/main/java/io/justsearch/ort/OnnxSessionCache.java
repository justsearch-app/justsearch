/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates CPU and CUDA sessions through the machine-wide optimized-model store. */
public final class OnnxSessionCache {
  private static final Logger log = LoggerFactory.getLogger(OnnxSessionCache.class);
  private static final String ORT_VERSION = getOrtVersion();

  private OnnxSessionCache() {}

  /** Caller owns the supplied options, including the configured CUDA provider. */
  public static OrtSession createCachedGpuSession(
      OrtEnvironment env, Path modelPath, SessionOptions opts) throws OrtException {
    return create(env, modelPath, opts, "cuda", OptLevel.EXTENDED_OPT);
  }

  public static OrtSession createCachedSession(OrtEnvironment env, Path modelPath)
      throws OrtException {
    return createCachedSession(env, modelPath, null);
  }

  public static OrtSession createCachedSession(
      OrtEnvironment env, Path modelPath, SessionOptions existingOpts) throws OrtException {
    return createCachedSession(env, modelPath, existingOpts, OptLevel.EXTENDED_OPT);
  }

  /** BASIC_OPT remains the required choice for FP16 on CPU; other models use EXTENDED_OPT. */
  public static OrtSession createCachedSession(
      OrtEnvironment env, Path modelPath, SessionOptions existingOpts, OptLevel minOptLevel)
      throws OrtException {
    return create(env, modelPath, existingOpts, "cpu", minOptLevel);
  }

  private static OrtSession create(OrtEnvironment env, Path model, SessionOptions existingOpts,
      String ep, OptLevel level) throws OrtException {
    if (existingOpts == null) {
      try (SessionOptions opts = new SessionOptions()) {
        return create(env, model, opts, ep, level);
      }
    }
    long start = System.currentTimeMillis();
    try {
      OrtSession session = OrtOptimizedModelStore.configured().loadOrCreate(model, ep, level,
          plan -> {
            SessionOptionsApplier.applyGraphPlan(plan, existingOpts);
            return env.createSession(plan.input().toString(), existingOpts);
          });
      log.info("{} ONNX model loaded/optimized in {}ms: {}", ep,
          System.currentTimeMillis() - start, model.getFileName());
      return session;
    } catch (IOException e) {
      // The native creator never throws IOException; store preparation/commit failures already
      // fall back internally. Keep this boundary for the injected optimizer's checked I/O seam.
      throw new OrtException("ORT optimized store failed: " + e.getMessage());
    }
  }

  private static String getOrtVersion() {
    try {
      return OrtEnvironment.getEnvironment().getVersion();
    } catch (Exception e) {
      return "unknown";
    }
  }

  /** Library version exposed to runtime health and benchmark hardware projections. */
  public static String ortVersion() {
    return ORT_VERSION;
  }
}
