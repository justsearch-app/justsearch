/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.PackImportService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.preflight-ai-pack}.
 *
 * <p>Slice 3a-2-c continuation: BrainPackImportSection Preflight button.
 * Delegates to {@link PackImportService#preflight(String)} via lazy supplier.
 *
 * <p>Args shape: {@code {"path": string}}. Returns the preflight result
 * map in {@code structuredData}.
 */
public final class PreflightAiPackHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(PreflightAiPackHandler.class);

  private final Supplier<PackImportService> supplier;

  public PreflightAiPackHandler(Supplier<PackImportService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String path;
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode p = root.get("path");
      if (p == null || !p.isTextual() || p.asString().isBlank()) {
        return OperationResult.failure("Missing required arg: path");
      }
      path = p.asString();
    } catch (Exception e) {
      return HandlerJson.invalidArgs(e);
    }

    PackImportService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("PreflightAiPackHandler: supplier threw", e);
      return OperationResult.failure("Pack import service unavailable: " + e.getMessage());
    }
    if (svc == null) {
      return OperationResult.failure("Pack import service unavailable");
    }

    try {
      Map<String, Object> result = svc.preflight(path);
      return OperationResult.success("Preflight complete", result);
    } catch (IllegalArgumentException e) {
      return OperationResult.failure(
          e.getMessage(), "INVALID_REQUEST", Map.of("path", path), false);
    } catch (Exception e) {
      // NOTE: AiPackPreflightException (modules/ui) carries a finer-grained
      // ApiErrorCode (PACK_PATH_NOT_FOUND, PACK_DIGEST_MISMATCH, etc.) but
      // lives in modules/ui — not visible to app-services. Phase D will lift
      // the type to app-api alongside the response types; until then, all
      // pack-preflight failures collapse to PACK_PREFLIGHT_FAILED.
      log.error("PreflightAiPackHandler: preflight threw", e);
      return OperationResult.failure(
          "Pack preflight failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "PACK_PREFLIGHT_FAILED",
          Map.of("path", path),
          true);
    }
  }
}
