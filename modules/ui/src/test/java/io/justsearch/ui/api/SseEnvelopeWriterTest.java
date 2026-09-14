package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.ResumeTokenCodec;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SseEnvelopeWriter")
final class SseEnvelopeWriterTest {

  private static final StreamId STREAM = StreamId.registry("capabilities");

  private SseStreamChannel channel;
  private ScheduledExecutorService heartbeatScheduler;

  @BeforeEach
  void setUp() {
    channel = new SseStreamChannel(STREAM);
    heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "test-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  @AfterEach
  void tearDown() {
    heartbeatScheduler.shutdownNow();
  }

  private SseClient mockSseClient(String sinceToken) {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(sinceToken);
    return client;
  }

  private static String firstUpdateToken(SseStreamChannel source) {
    return source.framesSince(0).getFirst().resumeToken();
  }

  // ============================================================
  // Direct-instance tests (writer methods)
  // ============================================================

  @Test
  @DisplayName("every frame uses constant SSE event name 'frame'")
  void eventNameIsFrame() {
    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    w.sendConnected();
    w.sendSnapshot(Map.of("k", "v"));
    w.sendHeartbeat();

    verify(client, atLeastOnce()).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
  }

  @Test
  @DisplayName("frame body contains streamId / frameKind / seq / ts / payload / resumeToken")
  void frameBodyShape() {
    SseClient client = mock(SseClient.class);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));
    SseStreamChannel hChannel = new SseStreamChannel(StreamId.surface("health-events"));
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, hChannel, Clock.systemUTC());

    hChannel.publish(SseFrameKind.UPDATE, Map.of("foo", "bar"));
    w.sendConnected();

    assertEquals(1, sent.size(), "writer-issued frame; broadcast bypasses writer");
    String connected = sent.get(0);
    assertTrue(connected.contains("\"streamId\":\"surface:health-events\""), connected);
    assertTrue(connected.contains("\"frameKind\":\"LIFECYCLE\""), connected);
    assertTrue(connected.contains("\"seq\":2"), connected);
    assertTrue(connected.contains("\"ts\":"), connected);
    assertTrue(connected.contains("\"resumeToken\":"), connected);
    assertTrue(connected.contains("\"kind\":\"connected\""), connected);
  }

  @Test
  @DisplayName("sendSnapshot wraps extras in {kind: snapshot, ...extras}")
  void snapshotWrapsExtras() {
    SseClient client = mock(SseClient.class);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    w.sendSnapshot(Map.of("conditions", List.of(), "occurrences", List.of()));

    assertEquals(1, sent.size());
    String body = sent.get(0);
    assertTrue(body.contains("\"kind\":\"snapshot\""), body);
    assertTrue(body.contains("\"conditions\""), body);
    assertTrue(body.contains("\"occurrences\""), body);
  }

  // ============================================================
  // attemptResumeAndSubscribe — incarnation and atomic handoff edge cases
  // ============================================================

  @Test
  @DisplayName("attemptResumeAndSubscribe with valid in-window token replays missed UPDATE frames")
  void resumeWithinWindow() {
    // Populate the channel with 3 UPDATE frames
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 3));

    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    String tokenAtSeq1 = firstUpdateToken(channel);
    Optional<SseStreamChannel.Subscription> subscription =
        w.attemptResumeAndSubscribe(tokenAtSeq1);

    assertTrue(subscription.isPresent(), "resume within window returns a subscription");
    // The atomic resume prefix sends connected, then seq 2 + seq 3 (strictly after seq 1).
    verify(client, times(3)).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
    subscription.get().unsubscribe();
  }

  @Test
  @DisplayName("attemptResumeAndSubscribe with malformed token returns empty")
  void resumeMalformed() {
    SseEnvelopeWriter w = new SseEnvelopeWriter(mock(SseClient.class), channel, Clock.systemUTC());
    assertTrue(w.attemptResumeAndSubscribe("not-a-valid-token").isEmpty());
    assertTrue(w.attemptResumeAndSubscribe(null).isEmpty());
    assertTrue(w.attemptResumeAndSubscribe("").isEmpty());
  }

  @Test
  @DisplayName("attemptResumeAndSubscribe with mismatched streamId returns empty")
  void resumeWrongStream() {
    SseEnvelopeWriter w = new SseEnvelopeWriter(mock(SseClient.class), channel, Clock.systemUTC());
    SseStreamChannel wrongSource = new SseStreamChannel(StreamId.surface("health-events"));
    wrongSource.publish(SseFrameKind.UPDATE, Map.of("wrong", true));
    String wrongStreamToken = firstUpdateToken(wrongSource);
    assertTrue(w.attemptResumeAndSubscribe(wrongStreamToken).isEmpty());
  }

  @Test
  @DisplayName("attemptResumeAndSubscribe with token predating the buffer returns empty")
  void resumeOutsideWindow() {
    SseStreamChannel smallChannel =
        new SseStreamChannel(
            STREAM,
            new io.justsearch.app.observability.stream.StreamSequenceTracker(),
            new io.justsearch.app.observability.stream.FrameHistoryRingBuffer(2),
            Clock.systemUTC());
    // Capture a source-issued token before pushing enough UPDATEs to evict it.
    smallChannel.publish(SseFrameKind.UPDATE, Map.of("i", 0));
    String oldToken = firstUpdateToken(smallChannel);
    // Push 4 more UPDATE frames into capacity-2 buffer; the old token is evicted.
    for (int i = 1; i < 5; i++) {
      smallChannel.publish(SseFrameKind.UPDATE, Map.of("i", i));
    }
    SseEnvelopeWriter w =
        new SseEnvelopeWriter(mock(SseClient.class), smallChannel, Clock.systemUTC());

    assertTrue(w.attemptResumeAndSubscribe(oldToken).isEmpty());
  }

  @Test
  @DisplayName("positive same-incarnation lifecycle cursor is valid when no UPDATEs were lost")
  void resumeEmptyBufferPositiveSeq() {
    // Lifecycle frames consume sequence numbers but are not retained; the cursor is still valid
    // when it belongs to this channel incarnation and no UPDATEs were lost.
    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());
    String tokenAtSeq1 =
        channel.nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "prior")).resumeToken();
    channel.nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "later"));

    Optional<SseStreamChannel.Subscription> subscription =
        w.attemptResumeAndSubscribe(tokenAtSeq1);
    assertTrue(subscription.isPresent(), "same-incarnation lifecycle cursor is valid");
    verify(client).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
    subscription.get().unsubscribe();
  }

  @Test
  @DisplayName("a cursor from a distinct source incarnation is rejected after restart")
  void resumeFromRestartedServerLifetime() {
    SseStreamChannel previousChannel = new SseStreamChannel(STREAM);
    previousChannel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    String previousToken = firstUpdateToken(previousChannel);

    SseEnvelopeWriter w = new SseEnvelopeWriter(mock(SseClient.class), channel, Clock.systemUTC());
    assertTrue(w.attemptResumeAndSubscribe(previousToken).isEmpty());
  }

  @Test
  @DisplayName("a future same-incarnation cursor is rejected")
  void resumeFromFutureServerLifetime() {
    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    String issuedToken = firstUpdateToken(channel);
    var incarnation = ResumeTokenCodec.decode(issuedToken).orElseThrow().incarnation();

    String futureToken = ResumeTokenCodec.encode(STREAM, 999L, incarnation);
    assertTrue(w.attemptResumeAndSubscribe(futureToken).isEmpty());
  }

  @Test
  @DisplayName("attemptResumeAndSubscribe with a seq=0 cursor replays the full buffer")
  void resumeFromZeroReplaysAll() {
    SseClient client = mock(SseClient.class);
    String tokenAtZero = channel.captureSnapshotBoundary().resumeToken();
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    Optional<SseStreamChannel.Subscription> subscription =
        w.attemptResumeAndSubscribe(tokenAtZero);
    assertTrue(subscription.isPresent());
    verify(client, times(3)).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
    subscription.get().unsubscribe();
  }

  // ============================================================
  // attach() orchestration
  // ============================================================

  @Test
  @DisplayName("attach: connected → snapshot → subscribe + request future (no resume token)")
  void attachWithoutResumeToken() {
    SseClient client = mockSseClient(null);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("data", "hello"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    verify(client).onClose(any());
    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "connected expected: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "snapshot expected: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"data\":\"hello\"")),
        "snapshot data expected: " + sent);
    // No reset frame when there's no resume token
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"reset\"")),
        "no reset expected without token");
  }

  @Test
  @DisplayName("attach: with valid in-window token replays + skips snapshot")
  void attachReplaysWithValidToken() {
    // Populate 2 UPDATE frames first
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));

    String token = firstUpdateToken(channel);
    SseClient client = mockSseClient(token);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("data", "would-be-snapshot"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    // Connected + 1 replay frame (seq 2 only, since sinceSeq=1)
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "connected expected: " + sent);
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "no fresh snapshot when resume succeeded: " + sent);
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"reset\"")),
        "no reset when resume succeeded: " + sent);
  }

  @Test
  @DisplayName("attach: with expired token emits reset + fresh snapshot")
  void attachWithExpiredToken() {
    // Populate channel with seqs that move past the token
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1)); // not in this test buffer (capacity=2)
    SseStreamChannel smallChannel =
        new SseStreamChannel(
            STREAM,
            new io.justsearch.app.observability.stream.StreamSequenceTracker(),
            new io.justsearch.app.observability.stream.FrameHistoryRingBuffer(2),
            Clock.systemUTC());
    smallChannel.publish(SseFrameKind.UPDATE, Map.of("i", 0));
    String oldToken = firstUpdateToken(smallChannel);
    for (int i = 1; i < 5; i++) {
      smallChannel.publish(SseFrameKind.UPDATE, Map.of("i", i));
    }
    SseClient client = mockSseClient(oldToken);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        smallChannel,
        () -> Map.of("data", "fresh"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"reset\"")),
        "reset expected on expired token: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "snapshot expected after reset: " + sent);
  }

  @Test
  @DisplayName("attach: subscribe handle forwards subsequent broadcasts to client")
  void attachForwardsBroadcasts() {
    SseClient client = mockSseClient(null);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("initial", "yes"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    int frameCountBeforeBroadcast = sent.size();
    channel.publish(SseFrameKind.UPDATE, Map.of("after", "subscribe"));

    assertTrue(sent.size() > frameCountBeforeBroadcast, "broadcast should be forwarded");
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"after\":\"subscribe\"")),
        "broadcast payload reached client: " + sent);
  }

  @Test
  @DisplayName("attach: ctx() returns null safely (no resume attempted)")
  void attachWithNullCtx() {
    SseClient client = mock(SseClient.class);
    when(client.ctx()).thenReturn(null);

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("data", "hello"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    verify(client, never()).keepAlive();
    verify(client).onClose(any());
  }
}
