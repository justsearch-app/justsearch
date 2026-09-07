package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.app.observability.diagnostic.DiagnosticChannelStreamRegistry;
import io.justsearch.app.observability.diagnostic.EngineLogDiagnosticChannelCatalog;
import io.justsearch.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiagnosticChannelStreamController")
final class DiagnosticChannelStreamControllerTest {

  @Test
  @DisplayName("constructs cleanly with a single-channel registry")
  void constructs() {
    DiagnosticChannelStreamRegistry registry =
        new DiagnosticChannelStreamRegistry(new EngineLogDiagnosticChannelCatalog());
    DiagnosticChannelStreamController controller =
        new DiagnosticChannelStreamController(registry, mock(Telemetry.class));
    assertNotNull(controller);
    controller.shutdown();
  }

  @Test
  @DisplayName("handle(unknown channel id) propagates the registry's IllegalArgumentException")
  void handleUnknownChannel() {
    DiagnosticChannelStreamRegistry registry =
        new DiagnosticChannelStreamRegistry(new EngineLogDiagnosticChannelCatalog());
    DiagnosticChannelStreamController controller =
        new DiagnosticChannelStreamController(registry, mock(Telemetry.class));
    try {
      // We don't have a real SseClient here; the registry lookup happens inside attach,
      // but the bad channel id triggers the registry's exception before any SSE work.
      assertThrows(
          IllegalArgumentException.class,
          () -> controller.handle(null, new DiagnosticChannelRef("core.unknown")));
    } finally {
      controller.shutdown();
    }
  }
}
