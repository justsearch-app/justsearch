/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-channel envelope writer. Snapshot reads occur outside source locks; attachment validates
 * their pre-query boundary and registers buffered delivery before emitting any candidate snapshot.
 * Lifecycle sequences identify control frames; their checkpoints acknowledge only delivered state.
 */
public final class SseEnvelopeWriter {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  public static final String EVENT_NAME = "frame";
  private static final int SNAPSHOT_ATTEMPTS = 3;

  private final SseClient client;
  private final SseStreamChannel channel;
  private final SseConnection connection;
  // Guarded by the physical client monitor, shared by every multiplexed writer's sends.
  private String checkpoint = "";

  public SseEnvelopeWriter(SseClient client, SseStreamChannel channel, Clock clock) {
    this(client, channel, clock, null);
  }

  SseEnvelopeWriter(SseClient client, SseStreamChannel channel, Clock clock,
      SseConnection connection) {
    this.client = Objects.requireNonNull(client, "client");
    this.channel = Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(clock, "clock");
    this.connection = connection;
  }

  public SseStreamChannel channel() {
    return channel;
  }

  public void sendConnected() {
    sendLifecycle("connected", Map.of());
  }

  public void sendSnapshot(Map<String, Object> extras) {
    sendLifecycle("snapshot", Objects.requireNonNull(extras, "extras"));
  }

  public void sendHeartbeat() {
    try {
      sendLifecycle("heartbeat", Map.of());
    } catch (RuntimeException | Error failure) {
      if (connection != null) connection.terminateAfter(failure);
      throw failure;
    }
  }

  public void sendReset(String reason) {
    synchronized (client) {
      checkpoint = "";
      sendLifecycle("reset", Map.of("reason", reason));
    }
  }

  public void sendClosing() {
    sendLifecycle("closing", Map.of());
  }

  /** Strong resume validates source incarnation and retained coverage before sending the prefix. */
  public Optional<SseStreamChannel.Subscription> attemptResumeAndSubscribe(String token) {
    return channel.subscribeAndReplay(this::sendFrame, token, () -> {
      synchronized (client) {
        checkpoint = token;
        sendConnected();
      }
    }, this::acquire);
  }

  /** Shared source attachment for standalone, multiplexed and event-only streams. */
  SseStreamChannel.Subscription attachSource(String token,
      Supplier<Map<String, Object>> snapshotExtras) {
    if (token != null && !token.isBlank()) {
      var resumed = attemptResumeAndSubscribe(token);
      if (resumed.isPresent()) return resumed.get();
    }
    for (int attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
      requireOpen();
      var boundary = channel.captureSnapshotBoundary();
      Map<String, Object> snapshot = snapshotExtras == null ? null
          : Objects.requireNonNull(snapshotExtras.get(), "snapshot");
      var attached = channel.subscribeAndReplay(this::sendFrame, boundary, () -> {
        synchronized (client) {
          checkpoint = snapshot == null && (token == null || token.isBlank())
              ? boundary.resumeToken() : "";
          sendConnected();
          if (token != null && !token.isBlank()) sendReset("resume-window-miss");
          if (snapshot != null) {
            sendSnapshotAt(snapshot, boundary.resumeToken());
          } else {
            // Event-only fresh attachment intentionally starts at the captured boundary.
            // A reset is acknowledged on the next update/heartbeat, after clearing client state.
            checkpoint = boundary.resumeToken();
          }
        }
      }, this::acquire);
      if (attached.isPresent()) return attached.get();
    }
    throw new IllegalStateException("SSE snapshot boundary expired during all attachment attempts");
  }

  public SseStreamChannel.Subscription subscribe() {
    return channel.subscribe(this::sendFrame);
  }

  private void acquire(SseStreamChannel.Subscription subscription) {
    if (connection != null) connection.own(subscription);
  }

  public static SseEnvelopeWriter attach(SseClient client, SseStreamChannel channel,
      Supplier<Map<String, Object>> snapshotExtras, Clock clock,
      ScheduledExecutorService heartbeatScheduler, long heartbeatSeconds) {
    return attachConnection(client, channel, Objects.requireNonNull(snapshotExtras, "snapshotExtras"),
        clock, heartbeatScheduler, heartbeatSeconds);
  }

  public static SseEnvelopeWriter attachEventOnly(SseClient client, SseStreamChannel channel,
      Clock clock, ScheduledExecutorService heartbeatScheduler, long heartbeatSeconds) {
    return attachConnection(client, channel, null, clock, heartbeatScheduler, heartbeatSeconds);
  }

  private static SseEnvelopeWriter attachConnection(SseClient client, SseStreamChannel channel,
      Supplier<Map<String, Object>> snapshotExtras, Clock clock,
      ScheduledExecutorService heartbeatScheduler, long heartbeatSeconds) {
    Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    forceSseHeaders(client);
    SseConnection connection = new SseConnection(client, () -> {});
    SseEnvelopeWriter writer = new SseEnvelopeWriter(client, channel, clock, connection);
    try {
      connection.start();
      if (connection.isClosed()) return writer;
      String token = client.ctx() == null ? null : client.ctx().queryParam("since");
      writer.attachSource(token, snapshotExtras);
      if (!connection.isClosed()) {
        connection.own(heartbeatScheduler.scheduleAtFixedRate(writer::sendHeartbeat,
            heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS));
      }
      return writer;
    } catch (RuntimeException | Error failure) {
      connection.terminateAfter(failure);
      throw failure;
    }
  }

  static void forceSseHeaders(SseClient client) {
    if (client.ctx() != null) {
      client.ctx().contentType("text/event-stream; charset=utf-8");
      client.ctx().header("Cache-Control", "no-cache");
      client.ctx().header("X-Accel-Buffering", "no");
    }
  }

  private void sendSnapshotAt(Map<String, Object> extras, String token) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("kind", "snapshot");
    body.putAll(extras);
    SseEnvelope frame = channel.nextEnvelope(SseFrameKind.LIFECYCLE, body);
    sendWire(withCheckpoint(frame, token));
    checkpoint = token;
  }

  private void sendLifecycle(String kind, Map<String, Object> extras) {
    synchronized (client) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("kind", kind);
      body.putAll(extras);
      sendWire(withCheckpoint(channel.nextEnvelope(SseFrameKind.LIFECYCLE, body), checkpoint));
    }
  }

  private static SseEnvelope withCheckpoint(SseEnvelope frame, String token) {
    return new SseEnvelope(frame.streamId(), frame.frameKind(), frame.seq(), frame.ts(),
        frame.payload(), token);
  }

  private void sendFrame(SseEnvelope frame) {
    synchronized (client) {
      sendWire(frame);
      checkpoint = frame.resumeToken();
    }
  }

  private void requireOpen() {
    if (client.terminated() || (connection != null && connection.isClosed())) {
      throw new IllegalStateException("SSE connection is closed");
    }
  }

  private void sendWire(SseEnvelope frame) {
    requireOpen();
    client.sendEvent(EVENT_NAME, MAPPER.writeValueAsString(frame));
    requireOpen();
  }
}
