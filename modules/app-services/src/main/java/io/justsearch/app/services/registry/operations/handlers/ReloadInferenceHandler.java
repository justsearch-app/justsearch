/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.reload-inference}.
 *
 * <p>Slice 3a-2-c continuation: BrainRuntimeSection Apply Runtime button.
 * Delegates to {@link BrainRuntimeService#reloadInference()} via lazy
 * supplier (the service is late-bound by LocalApiServer after
 * InferenceHandlers is constructed).
 *
 * <p>Returns the post-apply current mode in {@code structuredData.mode}
 * mirroring the pre-existing {@code POST /api/inference/reload} response
 * shape.
 */
public final class ReloadInferenceHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ReloadInferenceHandler.class);

  private final Supplier<BrainRuntimeService> brainRuntimeSupplier;

  public ReloadInferenceHandler(Supplier<BrainRuntimeService> brainRuntimeSupplier) {
    this.brainRuntimeSupplier = Objects.requireNonNull(brainRuntimeSupplier, "brainRuntimeSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    BrainRuntimeService brainRuntime;
    try {
      brainRuntime = brainRuntimeSupplier.get();
    } catch (RuntimeException e) {
      log.warn("ReloadInferenceHandler: brain-runtime supplier threw", e);
      return OperationResult.failure("Brain runtime service unavailable: " + e.getMessage());
    }
    if (brainRuntime == null) {
      return OperationResult.failure("Brain runtime service unavailable");
    }

    try {
      String mode = brainRuntime.reloadInference();
      return OperationResult.success(
          "Inference runtime reloaded (mode=" + mode + ")", Map.of("mode", mode));
    } catch (Exception e) {
      ModeTransitionException mte = findCause(e, ModeTransitionException.class);
      if (mte != null) {
        // Tempdoc 518 P3 — pattern-match on the typed payload instead of the flat Reason enum.
        // Reload-specific: caller-fixable codes (INVALID_CONFIG / INSUFFICIENT_VRAM) are
        // PERMANENT; everything else is transient and may be retried once the underlying issue
        // clears.
        InferenceFailure failure = mte.failure();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", mte.reason().name());
        details.put("wireCode", failure.wireCode());
        String errorCode;
        boolean retryable;
        if (failure instanceof InferenceFailure.ConfigFailure cf
            && cf.code() == ConfigCode.INVALID_CONFIG) {
          errorCode = "INVALID_CONFIG";
          retryable = false;
        } else if (failure instanceof InferenceFailure.StartupFailure sf
            && sf.code() == StartupCode.INSUFFICIENT_VRAM) {
          errorCode = "INSUFFICIENT_VRAM";
          retryable = false;
        } else if (failure instanceof InferenceFailure.ConfigFailure cf
            && cf.code() == ConfigCode.CONFIG_REQUIRED) {
          errorCode = "CONFIG_REQUIRED";
          retryable = true;
        } else {
          errorCode = "INFERENCE_RELOAD_FAILED";
          retryable = true;
        }
        return OperationResult.failure(
            mte.getMessage() == null ? mte.getClass().getSimpleName() : mte.getMessage(),
            errorCode,
            details,
            retryable);
      }
      log.error("ReloadInferenceHandler: reloadInference threw", e);
      return OperationResult.failure(
          "Inference runtime reload failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "INFERENCE_RELOAD_FAILED",
          Map.of(),
          true);
    }
  }

  private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
    Throwable cur = e;
    int depth = 0;
    while (cur != null && depth < 10) {
      if (type.isInstance(cur)) {
        return type.cast(cur);
      }
      cur = cur.getCause();
      depth++;
    }
    return null;
  }
}
