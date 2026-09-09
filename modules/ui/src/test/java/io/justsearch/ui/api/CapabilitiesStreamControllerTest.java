package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.core.execution.TestEngineExecutors;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CapabilitiesStreamController} per tempdoc 429 §A.4 + §F.6 closure +
 * slice 436 envelope retrofit. Verifies the connected/snapshot lifecycle, UPDATE
 * forwarding, and onClose/keepAlive lifecycle hooks under the universal envelope wire
 * shape (constant SSE event name {@code "frame"}).
 */
@DisplayName("CapabilitiesStreamController")
final class CapabilitiesStreamControllerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private CapabilitiesChangeRegistry registry;
  private CapabilitiesStreamController controller;

  @BeforeEach
  void setUp() {
    registry = new CapabilitiesChangeRegistry();
    Telemetry telemetry = mock(Telemetry.class);
    controller = new CapabilitiesStreamController(
            processExecutors,
              registry, telemetry);
  }

  @AfterEach
  void tearDown() {
    controller.shutdown();
  }

  private SseClient mockSseClient() {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(null);
    return client;
  }

  @Test
  @DisplayName("subscribe emits connected + snapshot lifecycle frames")
  void subscribeSendsConnectedAndSnapshot() {
    SseClient client = mockSseClient();
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    controller.handle(client);

    verify(client, atLeastOnce()).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "expected connected: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "expected snapshot: " + sent);
  }

  @Test
  @DisplayName("snapshot frame carries registry:capabilities streamId")
  void snapshotCarriesStreamId() {
    SseClient client = mockSseClient();
    controller.handle(client);
    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"streamId\":\"registry:capabilities\""));
  }

  @Test
  @DisplayName("subscribe registers onClose handler")
  void subscribeRegistersOnClose() {
    SseClient client = mockSseClient();
    controller.handle(client);
    verify(client).onClose(any());
  }

  @Test
  @DisplayName("subscribe calls keepAlive to hold the connection")
  void subscribeCallsKeepAlive() {
    SseClient client = mockSseClient();
    controller.handle(client);
    verify(client).keepAlive();
  }

  @Test
  @DisplayName("broadcast after subscribe forwards an UPDATE envelope")
  void broadcastForwardsUpdate() {
    SseClient client = mockSseClient();
    controller.handle(client);

    registry.broadcast(
        CapabilitiesChangeRegistry.CapabilityChangeEvent.Kind.ADDED, "new-entry");

    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"frameKind\":\"UPDATE\""));
    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"kind\":\"added\""));
  }
}
