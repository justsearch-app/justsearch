package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DiagnosticsService;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** Tests for {@link ExportDiagnosticsHandler} (slice 3a-1-2 closure). */
final class ExportDiagnosticsHandlerTest {

  @Test
  void executeReturnsSuccessWithPathFromService() {
    Path exported = Paths.get("/tmp/justsearch-diagnostics-20260506-120000.zip");
    ExportDiagnosticsHandler handler = new ExportDiagnosticsHandler(() -> engineContext -> exported);

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(result.success());
    assertEquals(exported.toAbsolutePath().toString(), result.structuredData().get("path"));
    assertTrue(result.message().contains(exported.toAbsolutePath().toString()));
  }

  @Test
  void executeReturnsFailureWhenServiceUnavailable() {
    ExportDiagnosticsHandler handler = new ExportDiagnosticsHandler(() -> null);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Diagnostics service unavailable"));
  }

  @Test
  void executeReturnsFailureWhenServiceThrowsUnsupported() {
    DiagnosticsService throwing =
        engineContext -> {
          throw new UnsupportedOperationException("Diagnostics service unavailable");
        };
    ExportDiagnosticsHandler handler = new ExportDiagnosticsHandler(() -> throwing);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("Diagnostics export failed"));
  }

  @Test
  void executeReturnsFailureWhenExportThrowsIO() {
    DiagnosticsService throwing =
        engineContext -> {
          throw new IOException("disk full");
        };
    ExportDiagnosticsHandler handler = new ExportDiagnosticsHandler(() -> throwing);
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertTrue(result.message().contains("disk full"));
  }
}
