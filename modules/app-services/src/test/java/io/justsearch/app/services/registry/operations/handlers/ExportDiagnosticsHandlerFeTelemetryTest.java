/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DiagnosticsService;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 683 X1: the export-diagnostics OPERATION path (the one the UI's jf-operation
 * button actually invokes) forwards the optional {@code feTelemetry} invocation arg to
 * {@link DiagnosticsService#exportDiagnostics(String)}, and forwards {@code null} when the
 * arg is absent or malformed — telemetry must never fail the export.
 */
class ExportDiagnosticsHandlerFeTelemetryTest {

  private static final class RecordingDiagnostics implements DiagnosticsService {
    final AtomicReference<String> received = new AtomicReference<>("UNSET");

    @Override
    public Path exportDiagnostics(io.justsearch.core.context.EngineContext engineContext)
        throws Exception {
      return exportDiagnostics(null, engineContext);
    }

    @Override
    public Path exportDiagnostics(
        String feTelemetryJson, io.justsearch.core.context.EngineContext engineContext) {
      received.set(feTelemetryJson);
      return Path.of("out.zip");
    }
  }

  @Test
  void forwardsFeTelemetryObjectFromArgs() {
    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    ExportDiagnosticsHandler handler = new ExportDiagnosticsHandler(() -> diagnostics);

    OperationResult result =
        handler.execute("{\"feTelemetry\":{\"wireDrift\":{\"total\":2}}}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success(), "export must succeed");
    String forwarded = diagnostics.received.get();
    assertTrue(
        forwarded != null && forwarded.contains("\"wireDrift\""),
        "feTelemetry object must be forwarded as JSON, got: " + forwarded);
  }

  @Test
  void forwardsNullWhenArgAbsentOrMalformed() {
    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    ExportDiagnosticsHandler handler = new ExportDiagnosticsHandler(() -> diagnostics);

    assertTrue(handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal()).success());
    assertNull(diagnostics.received.get(), "absent arg forwards null");

    assertTrue(handler.execute("{\"feTelemetry\":\"not-an-object\"}", io.justsearch.app.services.TestEngineContexts.internal()).success());
    assertNull(diagnostics.received.get(), "non-object arg forwards null");

    assertTrue(handler.execute("not json", io.justsearch.app.services.TestEngineContexts.internal()).success());
    assertNull(diagnostics.received.get(), "malformed args forward null");

    OperationResult viaNull = handler.execute(null, io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(viaNull.success());
    assertNull(diagnostics.received.get(), "null args forward null");
    assertEquals("out.zip", Path.of("out.zip").toString());
  }
}
