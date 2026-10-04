package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

/**
 * Tempdoc 834 S3a — {@code attach}'s RESUME branch now subscribes and replays atomically.
 * The frames on the wire are unchanged; what changes is that no broadcast can slip between
 * the replay and the subscribe.
 */
@DisplayName("SseEnvelopeWriter — atomic resume path (834 S3a)")
final class SseEnvelopeWriterAtomicResumeTest {

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

  private SseClient mockSseClient(String sinceToken, List<String> sent) {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(sinceToken);
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(org.mockito.ArgumentMatchers.eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
    return client;
  }

  private static String firstUpdateToken(SseStreamChannel source) {
    return source.framesSince(0).getFirst().resumeToken();
  }

  @Test
  @DisplayName("attemptResumeAndSubscribe replays the missed frames and forwards live ones")
  void resumeSubscribesAndReplays() {
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));

    List<String> sent = new ArrayList<>();
    SseClient client = mockSseClient(null, sent);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    Optional<SseStreamChannel.Subscription> sub =
        w.attemptResumeAndSubscribe(firstUpdateToken(channel));

    assertTrue(sub.isPresent(), "in-window cursor returns a subscription");
    assertEquals(2, sent.size(), "connected plus replayed seq 2 (strictly after the cursor)");

    channel.publish(SseFrameKind.UPDATE, Map.of("a", 3));
    assertEquals(3, sent.size(), "live frame forwarded through the same subscription");

    sub.get().unsubscribe();
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 4));
    assertEquals(3, sent.size(), "unsubscribe detaches");
  }

  @Test
  @DisplayName("a token outside the window registers no listener and replays nothing")
  void windowMissRegistersNothing() {
    List<String> sent = new ArrayList<>();
    SseClient client = mockSseClient(null, sent);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    channel.publish(SseFrameKind.UPDATE, Map.of("seed", true));
    String issuedToken = firstUpdateToken(channel);
    var incarnation = ResumeTokenCodec.decode(issuedToken).orElseThrow().incarnation();

    // A same-incarnation future cursor is rejected, as is a token from another source.
    assertTrue(w.attemptResumeAndSubscribe(ResumeTokenCodec.encode(STREAM, 5L, incarnation)).isEmpty());
    assertTrue(w.attemptResumeAndSubscribe("not-a-valid-token").isEmpty());
    assertTrue(w.attemptResumeAndSubscribe(null).isEmpty());
    SseStreamChannel wrongSource = new SseStreamChannel(StreamId.surface("health-events"));
    wrongSource.publish(SseFrameKind.UPDATE, Map.of("wrong", true));
    assertTrue(
        w.attemptResumeAndSubscribe(firstUpdateToken(wrongSource)).isEmpty(),
        "a token addressed to another stream is a miss");

    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    assertTrue(sent.isEmpty(), "no listener was registered by any miss");
  }

  @Test
  @DisplayName("attach with an in-window token: replay, no snapshot, one live subscription")
  void attachResumesWithoutSnapshot() {
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));

    List<String> sent = new ArrayList<>();
    SseClient client = mockSseClient(firstUpdateToken(channel), sent);
    SseEnvelopeWriter.attach(
        client, channel, () -> Map.of("snap", true), Clock.systemUTC(), heartbeatScheduler, 30L);

    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();

    String joined = String.join("\n", sent);
    assertTrue(joined.contains("\"connected\""), joined);
    assertFalse(joined.contains("\"snapshot\""), "an in-window resume must not snapshot");
    assertFalse(joined.contains("\"reset\""), "an in-window resume must not reset");

    int before = sent.size();
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 3));
    assertEquals(before + 1, sent.size(), "exactly ONE subscription is live, not two");
  }

  @Test
  @DisplayName("attach with an out-of-window token still resets, snapshots and subscribes once")
  void attachFallsBackToResetSnapshot() {
    List<String> sent = new ArrayList<>();
    channel.publish(SseFrameKind.UPDATE, Map.of("seed", true));
    String issuedToken = firstUpdateToken(channel);
    var incarnation = ResumeTokenCodec.decode(issuedToken).orElseThrow().incarnation();
    SseClient client = mockSseClient(ResumeTokenCodec.encode(STREAM, 99L, incarnation), sent);
    SseEnvelopeWriter.attach(
        client, channel, () -> Map.of("snap", true), Clock.systemUTC(), heartbeatScheduler, 30L);

    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();

    String joined = String.join("\n", sent);
    assertTrue(joined.contains("\"reset\""), joined);
    assertTrue(joined.contains("\"snapshot\""), joined);

    int before = sent.size();
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    assertEquals(before + 1, sent.size(), "exactly one subscription after the fallback");
  }

  @Test
  @DisplayName("attachEventOnly resumes atomically and never snapshots")
  void attachEventOnlyResumes() {
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));

    List<String> sent = new ArrayList<>();
    SseClient client = mockSseClient(firstUpdateToken(channel), sent);
    SseEnvelopeWriter.attachEventOnly(client, channel, Clock.systemUTC(), heartbeatScheduler, 30L);

    String joined = String.join("\n", sent);
    assertFalse(joined.contains("\"snapshot\""), "event-only streams never snapshot");
    assertFalse(joined.contains("\"reset\""), joined);
    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();

    int before = sent.size();
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 3));
    assertEquals(before + 1, sent.size(), "exactly ONE subscription is live");
  }
}
