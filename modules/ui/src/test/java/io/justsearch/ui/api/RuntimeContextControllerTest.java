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
import io.justsearch.app.observability.runtime.RuntimeContext;
import io.justsearch.app.observability.runtime.RuntimeContextChangeRegistry;
import io.justsearch.app.observability.runtime.RuntimeContextHolder;
import io.justsearch.app.observability.runtime.SystemMode;
import io.justsearch.core.execution.TestEngineExecutors;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per slice 440 (Runtime mode STATE Resource) + slice 436 envelope retrofit. Verifies
 * the connected/snapshot lifecycle, UPDATE forwarding (carrying the full new
 * RuntimeContext), and onClose/keepAlive lifecycle hooks under the universal envelope
 * wire shape.
 */
@DisplayName("RuntimeContextController")
final class RuntimeContextControllerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private RuntimeContextHolder holder;
  private RuntimeContextChangeRegistry registry;
  private RuntimeContextController controller;

  @BeforeEach
  void setUp() {
    holder = new RuntimeContextHolder(new RuntimeContext(SystemMode.PRODUCTION, false));
    registry = new RuntimeContextChangeRegistry();
    controller = new RuntimeContextController(
            processExecutors,
              holder, registry);
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
  @DisplayName("SSE subscribe emits connected + snapshot lifecycle frames")
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

    controller.handleStream(client);

    verify(client, atLeastOnce()).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "expected connected: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "expected snapshot: " + sent);
  }

  @Test
  @DisplayName("snapshot frame carries system:runtime-context streamId")
  void snapshotCarriesStreamId() {
    SseClient client = mockSseClient();
    controller.handleStream(client);

    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"streamId\":\"system:runtime-context\""));
  }

  @Test
  @DisplayName("SSE subscribe registers onClose handler")
  void subscribeRegistersOnClose() {
    SseClient client = mockSseClient();
    controller.handleStream(client);
    verify(client).onClose(any());
  }

  @Test
  @DisplayName("SSE subscribe calls keepAlive to hold the connection")
  void subscribeCallsKeepAlive() {
    SseClient client = mockSseClient();
    controller.handleStream(client);
    verify(client).keepAlive();
  }

  @Test
  @DisplayName("broadcast after subscribe forwards UPDATE frame carrying new RuntimeContext")
  void broadcastForwardsUpdate() {
    SseClient client = mockSseClient();
    controller.handleStream(client);

    RuntimeContext next = new RuntimeContext(SystemMode.EVAL, true);
    holder.set(next);
    registry.broadcast(next);

    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"frameKind\":\"UPDATE\""));
    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"systemMode\":\"EVAL\""));
  }
}
