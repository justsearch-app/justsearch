/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

final class SseSnapshotBoundaryTest {
  private static final StreamId STREAM = StreamId.surface("snapshot-boundary");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static SseClient client(String token, List<Map<?, ?>> sent, AtomicReference<Runnable> close) {
    SseClient client = mock(SseClient.class);
    Context context = mock(Context.class);
    when(client.ctx()).thenReturn(context);
    when(context.queryParam("since")).thenReturn(token);
    doAnswer(call -> {
      sent.add(MAPPER.readValue(call.getArgument(1, String.class), Map.class));
      return null;
    }).when(client).sendEvent(any(String.class), any(String.class));
    doAnswer(call -> {
      close.set(call.getArgument(0, Runnable.class));
      return null;
    }).when(client).onClose(any(Runnable.class));
    return client;
  }

  private static ScheduledExecutorService timer() {
    ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(timer).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
    return timer;
  }

  private static void attach(boolean multiplex, SseClient client, SseStreamChannel channel,
      Supplier<Map<String, Object>> snapshot) {
    if (multiplex) {
      MultiplexedSseWriter.attachAll(client,
          List.of(new MultiplexedSseWriter.ChannelSource(channel, snapshot)),
          new SseStreamChannel(StreamId.system("snapshot-heartbeat")), Clock.systemUTC(), timer(), 15);
    } else {
      SseEnvelopeWriter.attach(client, channel, snapshot, Clock.systemUTC(), timer(), 15);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publicationAfterSnapshotReadStillArrivesOnce(boolean multiplex) {
    SseStreamChannel channel = new SseStreamChannel(STREAM);
    List<Map<?, ?>> sent = new ArrayList<>();
    AtomicReference<Runnable> close = new AtomicReference<>();
    SseClient client = client(null, sent, close);
    attach(multiplex, client, channel, () -> {
      // The durable snapshot is already read; publication races its return/registration.
      Map<String, Object> snapshot = Map.of("entries", List.of());
      channel.publish(SseFrameKind.UPDATE, Map.of("operationKey", "during-query"));
      return snapshot;
    });
    try {
      assertEquals(1L, sent.stream().filter(frame -> "UPDATE".equals(frame.get("frameKind"))).count());
      Map<?, ?> snapshot = sent.stream().filter(frame ->
          "snapshot".equals(((Map<?, ?>) frame.get("payload")).get("kind"))).findFirst().orElseThrow();
      long checkpoint = ResumeTokenCodec.decode((String) snapshot.get("resumeToken")).orElseThrow().seq();
      assertEquals(0L, checkpoint, "snapshot checkpoints the boundary captured before its query");
    } finally {
      close.get().run();
    }
    assertEquals(0, channel.listenerCount());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void connectedFrameCannotAcknowledgeUndeliveredReplay(boolean multiplex) {
    SseStreamChannel channel = new SseStreamChannel(STREAM);
    channel.publish(SseFrameKind.UPDATE, Map.of("n", 1));
    String cursor = channel.framesSince(0).getFirst().resumeToken();
    channel.publish(SseFrameKind.UPDATE, Map.of("n", 2));
    List<Map<?, ?>> sent = new ArrayList<>();
    AtomicReference<Runnable> close = new AtomicReference<>();
    attach(multiplex, client(cursor, sent, close), channel, Map::of);
    try {
      assertEquals(cursor, sent.getFirst().get("resumeToken"), "disconnect after connected must still replay n=2");
      assertTrue(sent.stream().anyMatch(frame -> "UPDATE".equals(frame.get("frameKind"))));
    } finally {
      close.get().run();
    }
    assertEquals(0, channel.listenerCount());
  }
}
