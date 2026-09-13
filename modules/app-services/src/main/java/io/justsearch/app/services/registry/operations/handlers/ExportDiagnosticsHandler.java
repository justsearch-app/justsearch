/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DiagnosticsService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.export-diagnostics}.
 *
 * <p>Slice 3a-1-2 closure: real handler for HealthView's "Export Diagnostics"
 * button. Delegates to {@link DiagnosticsService#exportDiagnostics()} via a
 * lazy supplier (the service is late-bound by LocalApiServer after
 * DiagnosticsController is constructed).
 *
 * <p>Returns the produced ZIP path in {@code structuredData.path} so callers
 * (FE, agent loops) can surface it without a separate filesystem probe.
 */
public final class ExportDiagnosticsHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ExportDiagnosticsHandler.class);

  private final Supplier<DiagnosticsService> diagnosticsSupplier;

  public ExportDiagnosticsHandler(Supplier<DiagnosticsService> diagnosticsSupplier) {
    this.diagnosticsSupplier = Objects.requireNonNull(diagnosticsSupplier, "diagnosticsSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    DiagnosticsService diagnostics;
    try {
      diagnostics = diagnosticsSupplier.get();
    } catch (RuntimeException e) {
      log.warn("ExportDiagnosticsHandler: diagnostics supplier threw", e);
      return OperationResult.failure("Diagnostics service unavailable: " + e.getMessage());
    }
    if (diagnostics == null) {
      return OperationResult.failure("Diagnostics service unavailable");
    }
    try {
      Path outZip = diagnostics.exportDiagnostics(extractFeTelemetry(argumentsJson), engineContext);
      return OperationResult.success(
          "Diagnostics exported to " + outZip.toAbsolutePath(),
          Map.of("path", outZip.toAbsolutePath().toString()));
    } catch (Exception e) {
      log.error("ExportDiagnosticsHandler: export threw", e);
      return OperationResult.failure(
          "Diagnostics export failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }

  /**
   * Optional {@code feTelemetry} object from the invocation args (the FE wire-drift ring
   * summary, bound on the export {@code jf-operation} — tempdoc 683 X1). Absent, malformed,
   * or non-object input must never fail the export: this is telemetry, not input.
   */
  private static String extractFeTelemetry(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = HandlerJson.MAPPER.readTree(argumentsJson).get("feTelemetry");
      if (node == null || !node.isObject()) {
        return null;
      }
      return HandlerJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (Exception e) {
      log.debug("Ignoring unparseable feTelemetry in export-diagnostics args", e);
      return null;
    }
  }
}
