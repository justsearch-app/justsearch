/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DiagnosticsService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handler for the Head-local {@code core.copy-diagnostic-summary} operation. */
public final class CopyDiagnosticSummaryHandler implements OperationHandler {

  static final String SUMMARY_KEY = "summary";
  static final String SUCCESS_MESSAGE = "Diagnostic summary generated.";
  static final String FAILURE_MESSAGE = "Diagnostic summary unavailable.";

  private static final Logger log = LoggerFactory.getLogger(CopyDiagnosticSummaryHandler.class);

  private final Supplier<DiagnosticsService> diagnosticsSupplier;

  public CopyDiagnosticSummaryHandler(Supplier<DiagnosticsService> diagnosticsSupplier) {
    this.diagnosticsSupplier = Objects.requireNonNull(diagnosticsSupplier, "diagnosticsSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    DiagnosticsService diagnostics;
    try {
      diagnostics = diagnosticsSupplier.get();
    } catch (RuntimeException e) {
      log.warn(
          "CopyDiagnosticSummaryHandler: diagnostics supplier failed ({})",
          e.getClass().getSimpleName());
      return OperationResult.failure(FAILURE_MESSAGE);
    }
    if (diagnostics == null) {
      return OperationResult.failure(FAILURE_MESSAGE);
    }

    try {
      String summary = diagnostics.buildDiagnosticSummary();
      if (summary == null || summary.isBlank()) {
        return OperationResult.failure(FAILURE_MESSAGE);
      }
      return OperationResult.success(SUCCESS_MESSAGE, Map.of(SUMMARY_KEY, summary));
    } catch (Exception e) {
      log.warn(
          "CopyDiagnosticSummaryHandler: summary generation failed ({})",
          e.getClass().getSimpleName());
      return OperationResult.failure(FAILURE_MESSAGE);
    }
  }
}
