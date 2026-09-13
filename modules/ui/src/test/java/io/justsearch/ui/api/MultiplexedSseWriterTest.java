/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.FrameHistoryRingBuffer;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.observability.stream.StreamSequenceTracker;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tempdoc 662 — the cross-channel multiplexer's fan-in/resume/heartbeat behavior. */
@DisplayName("MultiplexedSseWriter")
final class MultiplexedSseWriterTest {

  private static final StreamId STREAM_A = StreamId.system("test-a");
  private static final StreamId STREAM_B = StreamId.system("test-b");
  private static final StreamId STREAM_C = StreamId.system("test-c");
  private static final StreamId HEARTBEAT_STREAM = StreamId.system("test-heartbeat");

  private ScheduledExecutorService heartbeatScheduler;

  @AfterEach
  void tearDown() {
    if (heartbeatScheduler != null) {
      heartbeatScheduler.shutdownNow();
    }
  }

  private static SseClient mockSseClient(String sinceParam) {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(sinceParam);
    return client;
  }

  private static List<String> captureSentFrames(SseClient client) {
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));
    return sent;
  }

  private static String firstUpdateToken(SseStreamChannel source) {
    return source.framesSince(0).getFirst().resumeToken();
  }

  // ============================================================
  // attachAll — fan-in (connected / snapshot / event-only)
  // ============================================================

  @Test
  @DisplayName("attachAll: sends connected per channel; snapshot only for channels with a supplier")
  void fanInConnectedAndSnapshot() {
    SseStreamChannel chA = new SseStreamChannel(STREAM_A);
    SseStreamChannel chB = new SseStreamChannel(STREAM_B); // event-only — null supplier
    SseStreamChannel chC = new SseStreamChannel(STREAM_C);
    SseStreamChannel heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM);

    SseClient client = mockSseClient(null);
    List<String> sent = captureSentFrames(client);
    heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(chA, () -> Map.of("v", "a")),
            new MultiplexedSseWriter.ChannelSource(chB, null),
            new MultiplexedSseWriter.ChannelSource(chC, () -> Map.of("v", "c")));

    MultiplexedSseWriter.attachAll(
        client, sources, heartbeatChannel, Clock.systemUTC(), heartbeatScheduler, 15L);

    long connectedCount = sent.stream().filter(s -> s.contains("\"kind\":\"connected\"")).count();
    assertEquals(3, connectedCount, "one connected frame per subscribed channel: " + sent);

    assertTrue(
        sent.stream()
            .anyMatch(s -> s.contains("\"streamId\":\"system:test-a\"") && s.contains("\"v\":\"a\"")),
        "channel A snapshot expected: " + sent);
    assertTrue(
        sent.stream()
            .anyMatch(s -> s.contains("\"streamId\":\"system:test-c\"") && s.contains("\"v\":\"c\"")),
        "channel C snapshot expected: " + sent);
    assertFalse(
        sent.stream()
            .anyMatch(s -> s.contains("\"streamId\":\"system:test-b\"") && s.contains("\"kind\":\"snapshot\"")),
        "event-only channel B must never get a snapshot frame: " + sent);

    verify(client).onClose(any());
    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();
  }

  @Test
  @DisplayName("attachAll: throws on an empty source list")
  void rejectsEmptySources() {
    SseStreamChannel heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM);
    heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MultiplexedSseWriter.attachAll(
                mockSseClient(null),
                List.of(),
                heartbeatChannel,
                Clock.systemUTC(),
                heartbeatScheduler,
                15L));
  }

  // ============================================================
  // attachAll — bundle resume
  // ============================================================

  @Test
  @DisplayName(
      "attachAll: per-channel bundle resume — in-window channel resumes silently, "
          + "out-of-window channel resets+snapshots, untouched channel gets its own initial snapshot")
  void bundleResumeMixedOutcomes() {
    // Channel A: in-window resume target.
    SseStreamChannel chA = new SseStreamChannel(STREAM_A);
    chA.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    chA.publish(SseFrameKind.UPDATE, Map.of("a", 2));
    String tokenAInWindow = firstUpdateToken(chA);

    // Channel B: capacity-2 ring buffer, 5 UPDATEs pushed — token at seq=1 predates the buffer.
    SseStreamChannel chB =
        new SseStreamChannel(
            STREAM_B, new StreamSequenceTracker(), new FrameHistoryRingBuffer(2), Clock.systemUTC());
    chB.publish(SseFrameKind.UPDATE, Map.of("i", 0));
    String tokenBOutOfWindow = firstUpdateToken(chB);
    for (int i = 1; i < 5; i++) {
      chB.publish(SseFrameKind.UPDATE, Map.of("i", i));
    }

    // Channel C: no token supplied in the bundle at all.
    SseStreamChannel chC = new SseStreamChannel(STREAM_C);

    SseStreamChannel heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM);
    SseClient client = mockSseClient(tokenAInWindow + "," + tokenBOutOfWindow);
    List<String> sent = captureSentFrames(client);
    heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(chA, () -> Map.of("would-be", "fresh-a")),
            new MultiplexedSseWriter.ChannelSource(chB, () -> Map.of("fresh", "b")),
            new MultiplexedSseWriter.ChannelSource(chC, () -> Map.of("fresh", "c")));

    MultiplexedSseWriter.attachAll(
        client, sources, heartbeatChannel, Clock.systemUTC(), heartbeatScheduler, 15L);

    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();

    // Channel A: resumed — replayed seq 2 only, no fresh snapshot, no reset.
    assertEquals(
        2,
        sent.stream().filter(s -> s.contains("\"streamId\":\"system:test-a\"")).count(),
        "channel A gets connected plus its one replayed UPDATE: " + sent);
    assertFalse(
        sent.stream().anyMatch(s -> s.contains("\"streamId\":\"system:test-a\"") && s.contains("\"kind\":\"snapshot\"")),
        "channel A must not get a fresh snapshot when resume succeeds: " + sent);
    assertFalse(
        sent.stream().anyMatch(s -> s.contains("\"streamId\":\"system:test-a\"") && s.contains("\"kind\":\"reset\"")),
        "channel A must not get a reset when resume succeeds: " + sent);

    // Channel B: resume failed (token predates buffer) — reset THEN fresh snapshot, scoped to B.
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"streamId\":\"system:test-b\"") && s.contains("\"kind\":\"reset\"")),
        "channel B must get a reset on an out-of-window token: " + sent);
    assertTrue(
        sent.stream()
            .anyMatch(
                s ->
                    s.contains("\"streamId\":\"system:test-b\"")
                        && s.contains("\"kind\":\"snapshot\"")
                        && s.contains("\"fresh\":\"b\"")),
        "channel B must get a fresh snapshot after reset: " + sent);

    // Channel C: no token in the bundle — straight snapshot, no reset.
    assertTrue(
        sent.stream()
            .anyMatch(
                s ->
                    s.contains("\"streamId\":\"system:test-c\"")
                        && s.contains("\"kind\":\"snapshot\"")
                        && s.contains("\"fresh\":\"c\"")),
        "channel C (no bundle entry) must get its own initial snapshot: " + sent);
    assertFalse(
        sent.stream().anyMatch(s -> s.contains("\"streamId\":\"system:test-c\"") && s.contains("\"kind\":\"reset\"")),
        "channel C must not get a reset (it never had a token): " + sent);
  }

  // ============================================================
  // attachAll — ONE shared heartbeat, never on a data channel
  // ============================================================

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("attachAll: schedules exactly ONE heartbeat, ticking only the dedicated heartbeat channel")
  void sharedHeartbeatOnDedicatedChannel() {
    SseStreamChannel chA = new SseStreamChannel(STREAM_A);
    SseStreamChannel chB = new SseStreamChannel(STREAM_B);
    SseStreamChannel heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM);

    SseClient client = mockSseClient(null);
    List<String> sent = captureSentFrames(client);

    ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<Object> mockFuture = mock(ScheduledFuture.class);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    when(mockScheduler.scheduleAtFixedRate(
            taskCaptor.capture(), eq(15L), eq(15L), eq(java.util.concurrent.TimeUnit.SECONDS)))
        .thenReturn((ScheduledFuture) mockFuture);

    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(chA, () -> Map.of("v", "a")),
            new MultiplexedSseWriter.ChannelSource(chB, () -> Map.of("v", "b")));

    MultiplexedSseWriter.attachAll(
        client, sources, heartbeatChannel, Clock.systemUTC(), mockScheduler, 15L);

    verify(mockScheduler, times(1))
        .scheduleAtFixedRate(any(), anyLong(), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS));
    verify(client.ctx()).future(any());
    verify(client, never()).keepAlive();

    int beforeTick = sent.size();
    taskCaptor.getValue().run(); // manually fire the scheduled heartbeat tick
    assertEquals(beforeTick + 1, sent.size(), "exactly one frame sent per heartbeat tick");
    String heartbeatFrame = sent.get(sent.size() - 1);
    assertTrue(heartbeatFrame.contains("\"streamId\":\"system:test-heartbeat\""), heartbeatFrame);
    assertTrue(heartbeatFrame.contains("\"kind\":\"heartbeat\""), heartbeatFrame);
  }

  // ============================================================
  // attachAll — onClose cleanup
  // ============================================================

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("attachAll: onClose unsubscribes every channel and cancels the shared heartbeat")
  void onCloseUnsubscribesAllAndCancelsHeartbeat() {
    SseStreamChannel chA = new SseStreamChannel(STREAM_A);
    SseStreamChannel chB = new SseStreamChannel(STREAM_B);
    SseStreamChannel heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM);

    SseClient client = mockSseClient(null);
    List<String> sent = captureSentFrames(client);
    ArgumentCaptor<Runnable> onCloseCaptor = ArgumentCaptor.forClass(Runnable.class);

    ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<Object> mockFuture = mock(ScheduledFuture.class);
    when(mockScheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
        .thenReturn((ScheduledFuture) mockFuture);

    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(chA, () -> Map.of("v", "a")),
            new MultiplexedSseWriter.ChannelSource(chB, () -> Map.of("v", "b")));

    MultiplexedSseWriter.attachAll(
        client, sources, heartbeatChannel, Clock.systemUTC(), mockScheduler, 15L);
    verify(client).onClose(onCloseCaptor.capture());

    onCloseCaptor.getValue().run();

    verify(mockFuture).cancel(false);
    int beforeClose = sent.size();
    chA.publish(SseFrameKind.UPDATE, Map.of("a", "after-close"));
    chB.publish(SseFrameKind.UPDATE, Map.of("b", "after-close"));
    assertEquals(
        beforeClose,
        sent.size(),
        "no further frames forwarded after onClose unsubscribes every channel: " + sent);
  }

  // ============================================================
  // attachAll — mid-loop failure cleanup (tempdoc 662 post-implementation fix)
  // ============================================================

  @Test
  @DisplayName(
      "attachAll: a later channel's snapshotExtras throwing unsubscribes the EARLIER "
          + "channel(s) already subscribed in this attempt, and propagates the exception")
  void midLoopFailureUnsubscribesEarlierChannels() {
    SseStreamChannel chA = new SseStreamChannel(STREAM_A);
    SseStreamChannel chB = new SseStreamChannel(STREAM_B);
    SseStreamChannel heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM);
    SseClient client = mockSseClient(null);
    List<String> sent = captureSentFrames(client);
    heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    RuntimeException boom = new RuntimeException("boom — simulates e.g. a gRPC-backed snapshot failure");
    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(chA, () -> Map.of("v", "a")), // succeeds, subscribes
            new MultiplexedSseWriter.ChannelSource(
                chB,
                () -> {
                  throw boom;
                }));

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                MultiplexedSseWriter.attachAll(
                    client, sources, heartbeatChannel, Clock.systemUTC(), heartbeatScheduler, 15L));
    assertEquals(boom, thrown, "the original exception propagates, not a wrapped/swallowed one");

    // The connection owns cleanup from the start of the attach attempt, including when a later
    // source fails before the method returns.
    verify(client).onClose(any());

    int beforeChAPublish = sent.size();
    chA.publish(SseFrameKind.UPDATE, Map.of("a", "after-failed-attach"));
    assertEquals(
        beforeChAPublish,
        sent.size(),
        "chA's listener from the failed attachAll attempt must have been unsubscribed — "
            + "a stray publish must not reach the (already-failed) client: "
            + sent);
  }

  @Test
  @DisplayName(
      "attachAll: cleanup survives an earlier channel's unsubscribe() itself throwing — the "
          + "ORIGINAL exception still propagates (as a suppressed-exception carrier), and "
          + "cleanup still attempts every subscription, not just the ones before the failure")
  void midLoopFailureCleanupSurvivesUnsubscribeThrowing() {
    // TWO already-subscribed channels (chA, chC) whose unsubscribe() BOTH throw, so this
    // actually exercises "the loop doesn't stop at the first cleanup failure" and "multiple
    // suppressed exceptions accumulate" — not just a single-element list, which the
    // @DisplayName's claim requires but a one-channel setup wouldn't verify.
    SseStreamChannel chA = mock(SseStreamChannel.class);
    when(chA.streamId()).thenReturn(STREAM_A);
    SseStreamChannel.SnapshotBoundary boundaryA =
        new SseStreamChannel(STREAM_A).captureSnapshotBoundary();
    when(chA.captureSnapshotBoundary()).thenReturn(boundaryA);
    RuntimeException unsubscribeFailureA = new RuntimeException("chA unsubscribe boom");
    AtomicBoolean chAUnsubscribeAttempted = new AtomicBoolean(false);
    SseStreamChannel.Subscription subscriptionA =
        new SseStreamChannel.Subscription() {
          @Override
          public void unsubscribe() {
            chAUnsubscribeAttempted.set(true);
            throw unsubscribeFailureA;
          }

          @Override
          public void onRetire(Runnable listener) {}
        };
    when(chA.nextEnvelope(any(), any()))
        .thenReturn(
            new SseEnvelope(
                STREAM_A,
                SseFrameKind.LIFECYCLE,
                1L,
                Instant.now(),
                Map.of("kind", "connected"),
                new SseStreamChannel(STREAM_A)
                    .nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "connected"))
                    .resumeToken()));
    when(chA.subscribeAndReplay(any(), eq(boundaryA), any(), any()))
        .thenAnswer(
            invocation -> {
              java.util.function.Consumer<SseStreamChannel.Subscription> onRegistered =
                  invocation.getArgument(3);
              onRegistered.accept(subscriptionA);
              invocation.<Runnable>getArgument(2).run();
              return Optional.of(subscriptionA);
            });

    SseStreamChannel chC = mock(SseStreamChannel.class);
    when(chC.streamId()).thenReturn(STREAM_C);
    SseStreamChannel.SnapshotBoundary boundaryC =
        new SseStreamChannel(STREAM_C).captureSnapshotBoundary();
    when(chC.captureSnapshotBoundary()).thenReturn(boundaryC);
    RuntimeException unsubscribeFailureC = new RuntimeException("chC unsubscribe boom");
    AtomicBoolean chCUnsubscribeAttempted = new AtomicBoolean(false);
    SseStreamChannel.Subscription subscriptionC =
        new SseStreamChannel.Subscription() {
          @Override
          public void unsubscribe() {
            chCUnsubscribeAttempted.set(true);
            throw unsubscribeFailureC;
          }

          @Override
          public void onRetire(Runnable listener) {}
        };
    when(chC.nextEnvelope(any(), any()))
        .thenReturn(
            new SseEnvelope(
                STREAM_C,
                SseFrameKind.LIFECYCLE,
                1L,
                Instant.now(),
                Map.of("kind", "connected"),
                new SseStreamChannel(STREAM_C)
                    .nextEnvelope(SseFrameKind.LIFECYCLE, Map.of("kind", "connected"))
                    .resumeToken()));
    when(chC.subscribeAndReplay(any(), eq(boundaryC), any(), any()))
        .thenAnswer(
            invocation -> {
              java.util.function.Consumer<SseStreamChannel.Subscription> onRegistered =
                  invocation.getArgument(3);
              onRegistered.accept(subscriptionC);
              invocation.<Runnable>getArgument(2).run();
              return Optional.of(subscriptionC);
            });

    SseStreamChannel heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM);
    SseClient client = mockSseClient(null);
    captureSentFrames(client);
    heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    RuntimeException snapshotFailure = new RuntimeException("boom — a later channel's snapshot fails");
    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(chA, () -> Map.of("v", "a")), // succeeds, subscribes
            new MultiplexedSseWriter.ChannelSource(chC, () -> Map.of("v", "c")), // succeeds, subscribes
            new MultiplexedSseWriter.ChannelSource(
                new SseStreamChannel(STREAM_B),
                () -> {
                  throw snapshotFailure;
                }));

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                MultiplexedSseWriter.attachAll(
                    client, sources, heartbeatChannel, Clock.systemUTC(), heartbeatScheduler, 15L));

    assertEquals(
        snapshotFailure,
        thrown,
        "the ORIGINAL failure must still be what's thrown, not either cleanup failure that "
            + "happened while handling it");
    assertTrue(
        chAUnsubscribeAttempted.get(),
        "chA's unsubscribe must still be attempted even though it throws");
    assertTrue(
        chCUnsubscribeAttempted.get(),
        "chC's unsubscribe must ALSO be attempted — a failure on chA must not stop the loop "
            + "before reaching chC");
    assertEquals(
        2,
        thrown.getSuppressed().length,
        "BOTH cleanup failures must be recorded, not just the last one: " + Arrays.toString(thrown.getSuppressed()));
    List<Throwable> suppressed = List.of(thrown.getSuppressed());
    assertTrue(suppressed.contains(unsubscribeFailureA), "chA's cleanup failure must be suppressed-attached");
    assertTrue(suppressed.contains(unsubscribeFailureC), "chC's cleanup failure must be suppressed-attached");
  }

  // ============================================================
  // parseTokenBundle
  // ============================================================

  @Test
  @DisplayName("parseTokenBundle: null/blank returns an empty map")
  void parseTokenBundleEmpty() {
    assertTrue(MultiplexedSseWriter.parseTokenBundle(null).isEmpty());
    assertTrue(MultiplexedSseWriter.parseTokenBundle("").isEmpty());
    assertTrue(MultiplexedSseWriter.parseTokenBundle("   ").isEmpty());
  }

  @Test
  @DisplayName("parseTokenBundle: splits + decodes a comma-joined bundle, keyed by streamId")
  void parseTokenBundleValid() {
    SseStreamChannel sourceA = new SseStreamChannel(STREAM_A);
    sourceA.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    SseStreamChannel sourceB = new SseStreamChannel(STREAM_B);
    sourceB.publish(SseFrameKind.UPDATE, Map.of("b", 1));
    String tokenA = firstUpdateToken(sourceA);
    String tokenB = firstUpdateToken(sourceB);

    Map<String, String> parsed = MultiplexedSseWriter.parseTokenBundle(tokenA + "," + tokenB);

    assertEquals(2, parsed.size());
    assertEquals(tokenA, parsed.get(STREAM_A.value()));
    assertEquals(tokenB, parsed.get(STREAM_B.value()));
  }

  @Test
  @DisplayName("parseTokenBundle: malformed entries are dropped silently, valid ones survive")
  void parseTokenBundleMalformedDropped() {
    SseStreamChannel sourceA = new SseStreamChannel(STREAM_A);
    sourceA.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    String tokenA = firstUpdateToken(sourceA);

    Map<String, String> parsed =
        MultiplexedSseWriter.parseTokenBundle("not-a-valid-token," + tokenA + ",,  ");

    assertEquals(1, parsed.size());
    assertEquals(tokenA, parsed.get(STREAM_A.value()));
  }
}
