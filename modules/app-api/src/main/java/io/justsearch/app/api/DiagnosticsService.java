/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

import java.nio.file.Path;

/**
 * Local diagnostics surface exposed to the AppFacade.
 *
 * <p>Slice 3a-1-2 closure: backs the {@code core.export-diagnostics} Operation
 * (modules/app-services {@code ExportDiagnosticsHandler}) without forcing a
 * cross-module cycle through the modules/ui-side {@code DiagnosticsController}.
 * Production wiring: {@code DiagnosticsController} implements this interface;
 * {@code LocalApiServer} late-binds it onto HeadAssembly after both
 * are constructed.
 *
 * <p>Stability: stable (API contract).
 */
public interface DiagnosticsService {

  /**
   * Generate a privacy-redacted diagnostics ZIP under AI Home and return the
   * path. Throws on filesystem / I/O failure or when the underlying state
   * sources (telemetry, debug-state, etc.) are unavailable.
   *
   * @return absolute path to the produced ZIP
   */
  Path exportDiagnostics(EngineContext engineContext) throws Exception;

  /**
   * Variant carrying an optional frontend-supplied telemetry section (opaque JSON, e.g. the
   * wire-drift ring summary) to be embedded in the export. {@code null} or blank means "no
   * frontend section". Default delegates to {@link #exportDiagnostics(EngineContext)} so existing
   * implementations and the registry-operation path are unaffected.
   *
   * @param feTelemetryJson frontend telemetry as a JSON object string, or {@code null}
   * @return absolute path to the produced ZIP
   */
  default Path exportDiagnostics(String feTelemetryJson, EngineContext engineContext) throws Exception {
    return exportDiagnostics(engineContext);
  }

  /**
   * Build the bounded, allowlist-only text handed to the copy-diagnostic-summary operation.
   *
   * <p>The returned value is transient operation data. Implementations must not persist or log it,
   * and must not derive it from the diagnostics ZIP, debug-state dumps, logs, exception messages,
   * stack traces, environment dumps, or runtime manifests.
   *
   * @return deterministic UTF-8 diagnostic summary
   */
  default String buildDiagnosticSummary() throws Exception {
    throw new UnsupportedOperationException("Diagnostic summary unavailable");
  }
}
