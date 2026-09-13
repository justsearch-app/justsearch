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
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.observability.operations.OperationHistoryChangeRegistry;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import io.justsearch.app.observability.operations.OperationOutcome;
import io.justsearch.core.execution.TestEngineExecutors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationHistoryController")
final class OperationHistoryControllerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private OperationHistoryStore store;
  private OperationHistoryChangeRegistry registry;
  private OperationHistoryController controller;

  @BeforeEach
  void setUp() {
    store = new OperationHistoryStore();
    registry = new OperationHistoryChangeRegistry();
    controller = new OperationHistoryController(
            processExecutors,
              store, registry, key -> new io.justsearch.app.api.operations.OperationOutcomeView(
                  io.justsearch.app.api.operations.OperationOutcomeView.State.UNKNOWN,
                  null, 0, null, null, null, null, null, null));
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
  @DisplayName("snapshot frame carries surface:operation-history streamId")
  void snapshotCarriesStreamId() {
    SseClient client = mockSseClient();
    controller.handleStream(client);

    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"streamId\":\"surface:operation-history\""));
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
  @DisplayName("broadcast after subscribe forwards UPDATE frame with new entry")
  void broadcastForwardsUpdate() {
    SseClient client = mockSseClient();
    controller.handleStream(client);

    OperationHistoryEntry e =
        new OperationHistoryEntry(
            new OperationRef("core.test-appended"),
            "head",
            Instant.parse("2026-04-30T12:00:00Z"),
            Instant.parse("2026-04-30T12:00:01Z"),
            OperationOutcome.SUCCESS,
            Optional.empty(),
            InvocationProvenance.systemInternal(Instant.parse("2026-04-30T12:00:00Z")),
            Optional.empty());
    store.append(e);
    registry.broadcast(e);

    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"frameKind\":\"UPDATE\""));
    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("core.test-appended"));
  }
}
