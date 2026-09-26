package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.core.execution.TestEngineExecutors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HealthEventStreamController} per tempdoc 430 §"In scope — substrate"
 * (item 9) + slice 436 retrofit. Verifies the synchronous connected/snapshot lifecycle
 * + onClose registration + request-future lifecycle + UPDATE forwarding path under the universal
 * envelope wire shape (constant SSE event name {@code "frame"}).
 */
@DisplayName("HealthEventStreamController")
final class HealthEventStreamControllerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private static final Source EVENT_SOURCE = Source.forProcess("head", "instance-1", "1.0");

  private ConditionStore conditions;
  private OccurrenceLog occurrences;
  private HealthEventChangeRegistry registry;
  private HealthEventStreamController controller;

  @BeforeEach
  void setUp() {
    conditions = new ConditionStore();
    occurrences = new OccurrenceLog();
    registry = new HealthEventChangeRegistry();
    Telemetry telemetry = mock(Telemetry.class);
    controller = new HealthEventStreamController(
            processExecutors,
              conditions, occurrences, registry, telemetry);
  }

  @AfterEach
  void tearDown() {
    controller.shutdown();
  }

  /** Builds a mocked SseClient whose ctx().queryParam("since") returns null (no resume). */
  private SseClient mockSseClient() {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(null);
    return client;
  }

  @Test
  @DisplayName("subscribe emits connected + snapshot lifecycle frames as 'frame' events")
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
        "expected connected lifecycle: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "expected snapshot lifecycle: " + sent);
  }

  @Test
  @DisplayName("snapshot frame carries surface:health-events streamId")
  void snapshotCarriesStreamId() {
    SseClient client = mockSseClient();
    controller.handle(client);

    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"streamId\":\"surface:health-events\""));
  }

  @Test
  @DisplayName("subscribe registers onClose handler")
  void subscribeRegistersOnClose() {
    SseClient client = mockSseClient();
    controller.handle(client);
    verify(client).onClose(any());
  }

  @Test
  @DisplayName("subscribe registers a pre-created request future and never calls keepAlive")
  void subscribeRegistersRequestFuture() {
    SseClient client = mockSseClient();
    controller.handle(client);
    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();
  }

  @Test
  @DisplayName("broadcast after subscribe forwards the envelope as a 'frame' event")
  void broadcastForwardsUpdateFrame() {
    SseClient client = mockSseClient();
    controller.handle(client);

    HealthEvent change =
        new HealthEvent(
            "index.unavailable",
            Instant.parse("2026-04-30T12:00:00Z"),
            EVENT_SOURCE,
            Severity.ERROR,
            Optional.of("health-events.index.unavailable.message"),
            new AssertedCondition(
                "worker",
                ConditionStatus.FALSE,
                "WorkerStarting",
                Instant.parse("2026-04-30T11:59:00Z"),
                Optional.empty(),
                Optional.empty(),
                List.of()));
    registry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, change);

    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"frameKind\":\"UPDATE\""));
    verify(client, atLeastOnce())
        .sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), contains("\"kind\":\"condition-added\""));
  }

  // ============================================================
  // Fix C: controller-level resume tests
  // ============================================================

  /** Mocks an SseClient whose ctx().queryParam("since") returns the supplied token. */
  private SseClient mockSseClientWithToken(String sinceToken) {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(sinceToken);
    return client;
  }

  @Test
  @DisplayName("Fix C: subscribe with valid in-window token replays from the buffer")
  void subscribeWithValidResumeTokenReplays() {
    String token = registry.channel().captureSnapshotBoundary().resumeToken();
    // Populate the channel's ring buffer with one UPDATE frame BEFORE the client connects.
    HealthEvent change =
        new HealthEvent(
            "index.unavailable",
            Instant.parse("2026-04-30T12:00:00Z"),
            EVENT_SOURCE,
            Severity.ERROR,
            Optional.of("health-events.index.unavailable.message"),
            new AssertedCondition(
                "worker",
                ConditionStatus.FALSE,
                "WorkerStarting",
                Instant.parse("2026-04-30T11:59:00Z"),
                Optional.empty(),
                Optional.empty(),
                List.of()));
    registry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, change);

    // The source-issued seq=0 boundary captured before the UPDATE must replay that UPDATE.
    SseClient client = mockSseClientWithToken(token);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    controller.handle(client);

    // Connected lifecycle expected; resume succeeded so NO snapshot, NO reset.
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "connected expected: " + sent);
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "no snapshot when resume succeeded: " + sent);
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"reset\"")),
        "no reset when resume succeeded: " + sent);
    // The replay frame is the UPDATE for condition-added (broadcast before connect).
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"condition-added\"")),
        "replay UPDATE frame expected: " + sent);
  }

  @Test
  @DisplayName("Fix C: subscribe with expired token emits reset + fresh snapshot")
  void subscribeWithExpiredResumeTokenResetsAndSnapshots() {
    // A token issued by a distinct source incarnation must be rejected as a restart cursor.
    SseStreamChannel previousSource =
        new SseStreamChannel(HealthEventChangeRegistry.STREAM_ID);
    previousSource.publish(io.justsearch.app.api.stream.SseFrameKind.UPDATE, Map.of("previous", true));
    String expiredToken = previousSource.framesSince(0).getFirst().resumeToken();
    SseClient client = mockSseClientWithToken(expiredToken);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    controller.handle(client);

    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"reset\"")),
        "reset expected on expired token: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "fresh snapshot expected after reset: " + sent);
  }
}
