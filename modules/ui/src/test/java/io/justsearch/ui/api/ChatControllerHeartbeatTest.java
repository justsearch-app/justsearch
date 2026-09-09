/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.services.conversation.ConversationEngine;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 859 D live-defect D2 — {@code POST /api/chat/dispatch} must BEAT while a run is parked.
 *
 * <p><b>The defect.</b> Search v3 dispatches its delegate runs through this route. A run parked at a
 * gate emits nothing at all — that is what "parked" means — so with no out-of-band frame the FE's
 * liveness watchdog ({@code STREAM_WATCHDOG_STALE_MS}, 40 s) declared the stream dead: the panel went
 * stale, its arms stopped working, and the run timed out server-side into {@code
 * BUDGET_EDGE_FINALIZE}. Reproduced three times on 2026-08-25; gates answered inside 10 s worked.
 * {@code AgentController}'s own routes have had the heartbeat since 604 — this route never did.
 *
 * <p><b>Why the test drives the engine rather than a real run.</b> The property is about the ROUTE,
 * not the shape: a handler that blocks must emit liveness. A blocking {@code engine.run} is exactly
 * a parked run as far as this route can tell, and it makes the wait bounded instead of real.
 */
final class ChatControllerHeartbeatTest {

  /** Fast enough that the test is not a wait, slow enough that "stopped after" is observable. */
  private static final long BEAT_MS = 25L;

  private static final long BOUND_MS = 5_000L;

  private ScheduledExecutorService scheduler;

  @AfterEach
  void stopScheduler() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  @Test
  @DisplayName("859 D2 — a dispatch blocked mid-run beats a heartbeat, and stops when it ends")
  void dispatchBeatsWhileTheRunIsParkedAndStopsAfterwards() throws Exception {
    SseWriter writer = mock(SseWriter.class);
    AtomicInteger beats = new AtomicInteger();
    when(writer.writeEvent(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              if ("heartbeat".equals(inv.getArgument(1, String.class))) {
                beats.incrementAndGet();
              }
              return true;
            });

    // The run PARKS: the engine blocks exactly as a gate-held loop does, emitting nothing.
    CountDownLatch parked = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    ConversationEngine engine = mock(ConversationEngine.class);
    doAnswer(
            inv -> {
              parked.countDown();
              released.await(BOUND_MS, TimeUnit.MILLISECONDS);
              return null;
            })
        .when(engine)
        .run(any(), any(), any(), any(), any());

    scheduler = Executors.newSingleThreadScheduledExecutor();
    ChatController controller =
        new ChatController(
            engine,
            writer,
            null,
            io.justsearch.agent.api.conversation.ConversationStore.noop(),
            io.justsearch.app.api.OnlineAiService::unavailable,
            io.justsearch.agent.api.AgentService::unavailable,
            new SseHeartbeat(writer::writeEvent, scheduler, BEAT_MS));

    Context ctx = mockContext("{\"shapeId\":\"core.agent-run\",\"prompt\":\"delegate this\"}");
    Thread handler =
        new Thread(
            () -> {
              try {
                controller.dynamicHandler("/api/chat/dispatch").handle(ctx);
              } catch (Exception e) {
                throw new AssertionError("dispatch threw", e);
              }
            });
    handler.setDaemon(true);
    handler.start();

    assertTrue(parked.await(BOUND_MS, TimeUnit.MILLISECONDS), "the run reached its park");
    // THE PROPERTY: a stream that is saying nothing about the run must still be saying it is alive.
    assertTrue(
        awaitAtLeast(beats, 2),
        "a parked dispatch must beat — this is the frame the FE watchdog resets on; got "
            + beats.get());

    released.countDown();
    handler.join(BOUND_MS);
    assertFalse(handler.isAlive(), "the handler returned once the run finished");

    // And the beat is not left running against a finished stream (the 638 PE asymmetry).
    int atEnd = beats.get();
    Thread.sleep(BEAT_MS * 4);
    assertEquals(atEnd, beats.get(), "the heartbeat must stop when the stream does");
  }

  @Test
  @DisplayName("859 D2 — a PRE-run refusal opens no stream, so it beats nothing")
  void aRefusedDispatchNeverBeats() throws Exception {
    SseWriter writer = mock(SseWriter.class);
    AtomicInteger beats = new AtomicInteger();
    when(writer.writeEvent(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              if ("heartbeat".equals(inv.getArgument(1, String.class))) {
                beats.incrementAndGet();
              }
              return true;
            });
    scheduler = Executors.newSingleThreadScheduledExecutor();
    ChatController controller =
        new ChatController(
            mock(ConversationEngine.class),
            writer,
            null,
            io.justsearch.agent.api.conversation.ConversationStore.noop(),
            io.justsearch.app.api.OnlineAiService::unavailable,
            io.justsearch.agent.api.AgentService::unavailable,
            new SseHeartbeat(writer::writeEvent, scheduler, BEAT_MS));

    // No shapeId: the handler answers and returns without ever running anything.
    controller.dynamicHandler("/api/chat/dispatch").handle(mockContext("{\"prompt\":\"hi\"}"));

    Thread.sleep(BEAT_MS * 4);
    assertEquals(0, beats.get(), "a refusal is not a stream, and must not schedule a beat");
  }

  private static boolean awaitAtLeast(AtomicInteger counter, int target) throws InterruptedException {
    long deadline = System.nanoTime() + BOUND_MS * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (counter.get() >= target) {
        return true;
      }
      Thread.sleep(5);
    }
    return false;
  }

  private static Context mockContext(String body) {
    Context ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/api/chat/dispatch");
    when(ctx.body()).thenReturn(body);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.attributeOrCompute(anyString(), any())).thenReturn(new Object());
    when(ctx.header(anyString())).thenReturn(null);
    return ctx;
  }

  @Test
  @DisplayName("859 D2 — shutdown stops the scheduler, and is idempotent (the 638 PE symmetry)")
  void shutdownStopsTheHeartbeatScheduler() {
    // An added START needs an added STOP, or this is tempdoc 638 PE's leak again under a new
    // controller's name — which is why `LocalApiServer` now calls this alongside AgentController's.
    ChatController controller =
        new ChatController(mock(ConversationEngine.class), mock(SseWriter.class), null);
    assertFalse(controller.isHeartbeatSchedulerShutdown(), "running before shutdown");
    controller.shutdown();
    assertTrue(controller.isHeartbeatSchedulerShutdown(), "shutdown() must stop the scheduler");
    // Idempotent — a double teardown must not throw.
    controller.shutdown();
    assertTrue(controller.isHeartbeatSchedulerShutdown());
  }

  /** Kept honest: the beat's payload is the liveness frame, not an event the FE must understand. */
  @Test
  @DisplayName("859 D2 — the beat is an out-of-band `heartbeat` frame carrying only a timestamp")
  void theBeatIsAnOutOfBandLivenessFrame() {
    SseWriter writer = mock(SseWriter.class);
    Context ctx = mockContext("{}");
    scheduler = Executors.newSingleThreadScheduledExecutor();
    new SseHeartbeat(writer::writeEvent, scheduler, BEAT_MS).beat(ctx);
    org.mockito.ArgumentCaptor<Map<String, ?>> payload = org.mockito.ArgumentCaptor.captor();
    org.mockito.Mockito.verify(writer)
        .writeEvent(org.mockito.ArgumentMatchers.eq(ctx), org.mockito.ArgumentMatchers.eq("heartbeat"), payload.capture());
    assertEquals(
        java.util.Set.of("ts"),
        payload.getValue().keySet(),
        "the frame says only that the stream is alive — it is not an AgentEvent and must not"
            + " pretend to be one");
  }
}
