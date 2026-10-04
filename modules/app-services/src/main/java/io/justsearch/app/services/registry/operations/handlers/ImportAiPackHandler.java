/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;
import static io.justsearch.agent.api.registry.OperationExecution.finished;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRecordHandle;

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
 * Handler for {@code core.import-ai-pack}.
 *
 * <p>Slice 3a-2-c continuation: BrainPackImportSection Import button.
 * Delegates to {@link PackImportService#startImport(String, boolean)} via
 * lazy supplier.
 *
 * <p>Args shape: {@code {"path": string, "allowDowngrade"?: boolean}}.
 * Returns the post-start status snapshot (AiPackImportStatus) in
 * {@code structuredData}.
 */
public final class ImportAiPackHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ImportAiPackHandler.class);

  private final Supplier<PackImportService> supplier;

  public ImportAiPackHandler(Supplier<PackImportService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    return executeRecorded(argumentsJson, null, engineContext, null).response();
  }

  @Override
  public OperationExecution executeRecorded(String argumentsJson, InvocationProvenance provenance,
      EngineContext engineContext, OperationRecordHandle record) {
    String path;
    boolean allowDowngrade;
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode p = root.get("path");
      if (p == null || !p.isTextual() || p.asString().isBlank()) {
        return finished(OperationResult.failure("Missing required arg: path"));
      }
      path = p.asString();
      JsonNode ad = root.get("allowDowngrade");
      allowDowngrade = ad != null && ad.isBoolean() && ad.asBoolean();
    } catch (Exception e) {
      return finished(HandlerJson.invalidArgs(e));
    }

    PackImportService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("ImportAiPackHandler: supplier threw", e);
      return finished(OperationResult.failure("Pack import service unavailable: " + e.getMessage()));
    }
    if (svc == null) {
      return finished(OperationResult.failure("Pack import service unavailable"));
    }

    try {
      var attempt = svc.startImport(path, allowDowngrade);
      return new OperationExecution(OperationResult.success("Pack import started", statusMap(attempt.started())),
          attempt.completion().thenApply(ImportAiPackHandler::outcome));
    } catch (IllegalArgumentException e) {
      return finished(OperationResult.failure(
          e.getMessage(), "INVALID_REQUEST", Map.of("path", path), false));
    } catch (IllegalStateException e) {
      // AiPackImportService throws ISE when an import is already running.
      return finished(OperationResult.failure(
          e.getMessage(), "Pack import already running".equals(e.getMessage())
              ? "PACK_IMPORT_RUNNING" : "PACK_IMPORT_START_FAILED", Map.of("path", path), true));
    } catch (Exception e) {
      log.error("ImportAiPackHandler: startImport threw", e);
      return finished(OperationResult.failure(
          "Pack import failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "PACK_IMPORT_START_FAILED",
          Map.of("path", path),
          true));
    }
  }

  private static OperationResult outcome(io.justsearch.app.api.AiPackImportStatus status) {
    if ("completed".equals(status.state)) return OperationResult.success("Pack import completed", statusMap(status));
    String code = status.errorCode == null || status.errorCode.isBlank() ? "PACK_IMPORT_INCOMPLETE" : status.errorCode;
    return OperationResult.failure(status.message, code, Map.of(), false);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> statusMap(io.justsearch.app.api.AiPackImportStatus status) {
    return HandlerJson.MAPPER.convertValue(status, Map.class);
  }

}
