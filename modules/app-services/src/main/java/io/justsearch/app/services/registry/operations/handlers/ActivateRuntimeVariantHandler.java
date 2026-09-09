/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.RuntimeVariantService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.activate-runtime-variant}.
 *
 * <p>Slice 3a-2-c continuation: BrainRuntimeSection Activate Runtime Variant
 * button. Delegates to {@link RuntimeVariantService#activate(String)} via
 * lazy supplier.
 *
 * <p>Args shape: {@code {"variantId": string}}. Returns the post-start
 * activation status snapshot in {@code structuredData} (mirrors the
 * AiRuntimeStatusResponse shape).
 */
public final class ActivateRuntimeVariantHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ActivateRuntimeVariantHandler.class);

  private final Supplier<RuntimeVariantService> supplier;

  public ActivateRuntimeVariantHandler(Supplier<RuntimeVariantService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String variantId;
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode v = root.get("variantId");
      if (v == null || !v.isTextual() || v.asString().isBlank()) {
        return OperationResult.failure("Missing required arg: variantId");
      }
      variantId = v.asString();
    } catch (Exception e) {
      return HandlerJson.invalidArgs(e);
    }

    RuntimeVariantService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("ActivateRuntimeVariantHandler: supplier threw", e);
      return OperationResult.failure("Runtime variant service unavailable: " + e.getMessage());
    }
    if (svc == null) {
      return OperationResult.failure("Runtime variant service unavailable");
    }

    try {
      Map<String, Object> status = svc.activate(variantId);
      return OperationResult.success("Runtime variant activation started: " + variantId, status);
    } catch (IllegalArgumentException e) {
      return OperationResult.failure(
          e.getMessage(), "INVALID_REQUEST", Map.of("variantId", variantId), false);
    } catch (IllegalStateException e) {
      // The AiRuntimeController's policy + already-running guards both throw
      // IllegalStateException. Distinguish by message — policy denials use the
      // canonical "is disabled by administrator policy" phrasing.
      String msg = e.getMessage() == null ? "" : e.getMessage();
      String code;
      boolean retryable;
      if (msg.contains("Online AI is disabled")) {
        code = "POLICY_ONLINE_AI_DISABLED";
        retryable = false;
      } else if (msg.contains("GPU acceleration is disabled")) {
        code = "POLICY_GPU_DISABLED";
        retryable = false;
      } else {
        // RuntimeActivationService.startActivate throws ISE when an
        // activation is already running.
        code = "RUNTIME_ACTIVATION_RUNNING";
        retryable = true;
      }
      return OperationResult.failure(e.getMessage(), code, Map.of("variantId", variantId), retryable);
    } catch (Exception e) {
      log.error("ActivateRuntimeVariantHandler: activate threw", e);
      return OperationResult.failure(
          "Runtime variant activation failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "RUNTIME_ACTIVATION_START_FAILED",
          Map.of("variantId", variantId),
          true);
    }
  }
}
