package io.justsearch.app.observability.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.SubCategory;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiagnosticChannelStreamRegistry")
final class DiagnosticChannelStreamRegistryTest {

  @Test
  @DisplayName("registers one SseStreamChannel per declared DiagnosticChannelRef")
  void registersOnePerCatalogEntry() {
    DiagnosticChannelStreamRegistry registry =
        new DiagnosticChannelStreamRegistry(new EngineLogDiagnosticChannelCatalog());
    assertEquals(1, registry.registeredIds().size());
    assertTrue(registry.registeredIds().contains(EngineLogDiagnosticChannelCatalog.ENGINE_LOG_ID));
  }

  @Test
  @DisplayName("publish forwards an UPDATE envelope to subscribers")
  void publishForwardsToSubscribers() {
    DiagnosticChannelStreamRegistry registry =
        new DiagnosticChannelStreamRegistry(new EngineLogDiagnosticChannelCatalog());
    List<SseEnvelope> received = new ArrayList<>();
    registry.channel(EngineLogDiagnosticChannelCatalog.ENGINE_LOG_ID).subscribe(received::add);

    DiagnosticEvent event =
        new DiagnosticEvent(
            "INFO",
            "test message",
            "io.justsearch.example.X",
            "main",
            1L,
            Instant.parse("2026-05-07T10:00:00Z"),
            Map.of("trace_id", "abc"),
            Set.of(),
            SubCategory.CORE_DIAGNOSTIC);
    DiagnosticEventEnvelope envelope = DiagnosticEventEnvelope.ofLogEvent(event);

    registry.publish(EngineLogDiagnosticChannelCatalog.ENGINE_LOG_ID, envelope);

    assertEquals(1, received.size());
    assertSame(SseFrameKind.UPDATE, received.get(0).frameKind());
    assertSame(envelope, received.get(0).payload());
  }

  @Test
  @DisplayName("channel(unknown id) throws IllegalArgumentException")
  void unknownChannelThrows() {
    DiagnosticChannelStreamRegistry registry =
        new DiagnosticChannelStreamRegistry(new EngineLogDiagnosticChannelCatalog());
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.channel(new DiagnosticChannelRef("core.unknown-channel")));
  }
}
