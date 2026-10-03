/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.stream.ResumeTokenCodec;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Multiplexes source-atomic attachments on one physical connection and cleanup owner. */
public final class MultiplexedSseWriter {

  private MultiplexedSseWriter() {}

  /**
   * One channel to multiplex onto the connection, paired with its optional snapshot-on-subscribe
   * supplier. {@code snapshotExtras == null} means the channel is event-only (no state to
   * snapshot — mirrors {@link SseEnvelopeWriter#attachEventOnly}'s contract for that channel).
   */
  public record ChannelSource(SseStreamChannel channel, Supplier<Map<String, Object>> snapshotExtras) {
    public ChannelSource {
      Objects.requireNonNull(channel, "channel");
    }
  }

  /**
   * Attaches all {@code sources} onto one {@code client} connection. Mirrors {@link
   * SseEnvelopeWriter#attach}'s source-boundary validation and buffered delivery for each channel,
   * sharing one client, early connection owner, parsed resume bundle and heartbeat.
   *
   * @param heartbeatChannel a channel dedicated to heartbeat lifecycle frames only — must NOT be
   *     one of {@code sources}' channels (heartbeats are not real channel data).
   */
  public static List<SseStreamChannel.Subscription> attachAll(
      SseClient client,
      List<ChannelSource> sources,
      SseStreamChannel heartbeatChannel,
      Clock clock,
      ScheduledExecutorService heartbeatScheduler,
      long heartbeatSeconds) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(heartbeatChannel, "heartbeatChannel");
    Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("sources must not be empty");
    }
    SseEnvelopeWriter.forceSseHeaders(client);

    Map<String, String> tokensByStreamId =
        parseTokenBundle(client.ctx() == null ? null : client.ctx().queryParam("since"));

    SseConnection connection = new SseConnection(client, () -> {});
    List<SseStreamChannel.Subscription> subscriptions = new ArrayList<>(sources.size());
    try {
      connection.start();
      for (ChannelSource source : sources) {
        if (connection.isClosed()) break;
        SseEnvelopeWriter writer = new SseEnvelopeWriter(client, source.channel(), clock, connection);
        var subscription = writer.attachSource(
            tokensByStreamId.get(source.channel().streamId().value()), source.snapshotExtras());
        subscriptions.add(subscription);
      }
      if (!connection.isClosed()) {
        SseEnvelopeWriter heartbeatWriter =
            new SseEnvelopeWriter(client, heartbeatChannel, clock, connection);
        connection.own(heartbeatScheduler.scheduleAtFixedRate(heartbeatWriter::sendHeartbeat,
            heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS));
      }
      return List.copyOf(subscriptions);
    } catch (RuntimeException | Error failure) {
      connection.terminateAfter(failure);
      throw failure;
    }
  }

  /**
   * Splits a comma-joined bundle of per-channel resume tokens (the multiplexed {@code ?since=}
   * value) and decodes each with the existing {@link ResumeTokenCodec}, keyed by the decoded
   * {@code streamId}'s wire value. Malformed entries cannot be routed and are dropped; a source
   * without a routed token takes fresh attachment (snapshot for stateful sources). Decodable legacy
   * tokens retain routing information, then fail incarnation validation and explicitly reset.
   */
  static Map<String, String> parseTokenBundle(String since) {
    if (since == null || since.isBlank()) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String raw : since.split(",")) {
      String token = raw.trim();
      if (token.isEmpty()) {
        continue;
      }
      ResumeTokenCodec.decode(token).ifPresent(decoded -> out.put(decoded.streamId().value(), token));
    }
    return out;
  }
}
