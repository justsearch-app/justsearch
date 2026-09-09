/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.stream.run.RunChannel;
import io.justsearch.app.observability.stream.run.RunChannelPolicy;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.observability.stream.run.RunDescriptor;
import io.justsearch.app.observability.stream.run.RunFrame;
import io.justsearch.app.observability.stream.run.RunId;
import io.justsearch.app.observability.stream.run.RunStateSnapshot;
import io.justsearch.app.observability.stream.run.SteppedRunChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 834 §15.1.2 — the five run-stream protocol requirements, each as a test, over a mocked
 * managed {@code SseClient}.
 */
@DisplayName("RunStreamWriter")
final class RunStreamWriterTest {

  private static final long HEARTBEAT_SECONDS =
      StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final RunChannelRegistry registry = new RunChannelRegistry();

  private RunChannel openAsk(String id) {
    return registry.open(
        new RunId(id),
        new RunDescriptor("core.rag-ask", "conv-7", 1_700_000_000_000L),
        RunChannelPolicy.conversational());
  }

  // ── requirement 1: absent cursor ⇒ replay from 0 ─────────────────────────────────────────────

  @Test
  @DisplayName("req 1 — an absent cursor replays EVERYTHING, never snapshot-only")
  void absentCursorReplaysFromZero() {
    RunChannel run = openAsk("run-replay");
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    run.publish(new RunFrame("chunk", Map.of("text", "two")));
    Harness h = new Harness(null, null);

    assertTrue(RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent());

    assertEquals(
        List.of("run_started", "chunk", "chunk"),
        h.eventNames(),
        "everything published before the observer connected must still reach it — that loss is "
            + "exactly what the journal exists to prevent");
    assertTrue(h.sent.get(1).data().contains("one"));
    assertTrue(h.sent.get(2).data().contains("two"));
  }

  @Test
  @DisplayName("req 1 — live frames keep flowing after the replay, in order")
  void liveFramesFollowTheReplay() {
    RunChannel run = openAsk("run-live");
    run.publish(new RunFrame("chunk", Map.of("text", "before")));
    Harness h = new Harness(null, null);
    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    run.publish(new RunFrame("chunk", Map.of("text", "after")));
    run.publish(new RunFrame("done", Map.of("finalResponse", "hi")));

    assertEquals(List.of("run_started", "chunk", "chunk", "done"), h.eventNames());
  }

  // ── requirement 2: the window miss is explicit ───────────────────────────────────────────────

  @Test
  @DisplayName("req 2 — a window miss emits replay_truncated AND still subscribes")
  void windowMissIsAnnouncedAndStillSubscribes() {
    RunChannel run = openAsk("run-miss");
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    // A cursor from a future / different server lifetime: ahead of the stream, so outside the window.
    Harness h = new Harness("500", null);

    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    assertEquals(
        List.of("run_started", "replay_truncated", "chunk"),
        h.eventNames(),
        "the client is TOLD it missed part, then given the window that does exist");
    assertTrue(h.sent.get(1).data().contains("\"sinceSeq\":500"));
    assertTrue(h.sent.get(1).data().contains("oldestRetainedSeq"));

    run.publish(new RunFrame("done", Map.of("finalResponse", "x")));
    assertEquals(
        "done",
        h.sent.get(h.sent.size() - 1).event(),
        "a miss must not leave a permanently DEAD stream — subscribeAndReplay registers nothing "
            + "on empty, so the writer has to re-subscribe from 0");
    assertEquals(1, run.observerCount());
  }

  // ── requirement 3: one cursor grammar ────────────────────────────────────────────────────────

  @Test
  @DisplayName("req 3 — ?sinceSeq= parses and resumes from the cursor")
  void sinceSeqParses() {
    RunChannel run = openAsk("run-cursor");
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    run.publish(new RunFrame("chunk", Map.of("text", "two")));
    Harness h = new Harness("1", null);

    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    assertEquals(List.of("run_started", "chunk"), h.eventNames(), "only frames after seq 1");
    assertTrue(h.sent.get(1).data().contains("two"));
  }

  @Test
  @DisplayName("req 3 — a ?since= envelope token is REFUSED, not silently treated as 0")
  void envelopeCursorIsRefused() {
    RunChannel run = openAsk("run-wrong-grammar");
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    Harness h = new Harness(null, "cnVuOmZvbw==.7");

    Optional<RunStreamWriter> writer =
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS);

    assertTrue(writer.isEmpty(), "the attach did not happen");
    assertEquals(List.of("error"), h.eventNames());
    assertTrue(h.sent.get(0).data().contains("sinceSeq"), "the error names the right grammar");
    assertEquals(0, run.observerCount(), "and nothing was registered");
    verify(h.client, never()).keepAlive();
  }

  @Test
  @DisplayName("req 3 — a non-numeric or negative ?sinceSeq is refused too")
  void malformedCursorIsRefused() {
    RunChannel run = openAsk("run-bad-cursor");
    Harness bad = new Harness("not-a-number", null);
    assertTrue(RunStreamWriter.attach(bad.client, run, bad.scheduler, HEARTBEAT_SECONDS).isEmpty());
    assertEquals(List.of("error"), bad.eventNames());

    Harness negative = new Harness("-3", null);
    assertTrue(
        RunStreamWriter.attach(negative.client, run, negative.scheduler, HEARTBEAT_SECONDS)
            .isEmpty());
    assertEquals(List.of("error"), negative.eventNames());
  }

  // ── requirement 4: no id: line ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("req 4 — no id: line is ever written, so Last-Event-ID cannot become a 2nd cursor")
  void noIdLineIsEverWritten() {
    RunChannel run = openAsk("run-no-id");
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    Harness h = new Harness(null, null);

    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");
    run.publish(new RunFrame("done", Map.of()));

    verify(h.client, never()).sendEvent(anyString(), any(), anyString());
  }

  // ── requirement 5: the heartbeat is load-bearing ─────────────────────────────────────────────

  @Test
  @DisplayName("req 5 — the heartbeat is scheduled at the shared cadence and cancelled on close")
  void heartbeatFiresOnCadenceAndIsCancelledOnClose() {
    RunChannel run = openAsk("run-heartbeat");
    Harness h = new Harness(null, null);

    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    verify(h.scheduler)
        .scheduleAtFixedRate(
            any(Runnable.class),
            eq(HEARTBEAT_SECONDS),
            eq(HEARTBEAT_SECONDS),
            eq(TimeUnit.SECONDS));

    h.heartbeatTask().run();
    assertEquals(
        List.of("run_started", "heartbeat"),
        h.eventNames(),
        "the heartbeat is the ONLY write a parked run makes, so it is what makes onClose fire "
            + "at all (D1: onClose is write-cadence-bound, never socket-driven)");
    assertEquals(
        0,
        run.channel().framesSince(0).size(),
        "and it must never occupy a ring slot, or a parked run would evict its own narrative");

    h.onClose().run();
    verify(h.heartbeatFuture).cancel(false);
    assertEquals(0, run.observerCount(), "onClose unsubscribes — that is how observerCount drops");
  }

  // ── run_started, the snapshot primer, and retirement ─────────────────────────────────────────

  @Test
  @DisplayName("run_started is FIRST, carries the identity triple, and is not retained")
  void runStartedIsFirstAndNotRetained() {
    RunChannel run = openAsk("run-identity");
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    Harness h = new Harness(null, null);

    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    assertEquals("run_started", h.sent.get(0).event());
    String body = h.sent.get(0).data();
    assertTrue(body.contains("\"runId\":\"run-identity\""), body);
    assertTrue(body.contains("\"shapeId\":\"core.rag-ask\""), body);
    assertTrue(body.contains("\"conversationId\":\"conv-7\""), body);
    assertEquals(
        1,
        run.channel().framesSince(0).size(),
        "a lifecycle frame consumes a seq but takes no ring slot — only the chunk is retained");
  }

  @Test
  @DisplayName("the act-on-the-run primer precedes the replay (§6.1), on every subscribe")
  void snapshotPrecedesTheReplay() {
    RunChannel run =
        registry.open(
            new RunId("agent-primed"),
            new RunDescriptor("core.agent-run", "conv-7", 1L),
            RunChannelPolicy.agent());
    ((SteppedRunChannel) run)
        .setSnapshotSupplier(
            () -> new RunStateSnapshot(Map.of("iteration", 4, "pendingApprovals", List.of("c1"))));
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    Harness h = new Harness(null, null);

    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    assertEquals(
        List.of("run_started", "state_snapshot", "chunk"),
        h.eventNames(),
        "the ring evicts oldest, so the primer is the one frame guaranteed to arrive — it cannot "
            + "sit behind the replay");
    assertTrue(h.sent.get(1).data().contains("\"iteration\":4"));
  }

  @Test
  @DisplayName("a one-shot run sends no primer — there is no fact a user can act on (§6.4)")
  void oneShotRunsSendNoPrimer() {
    RunChannel run = openAsk("run-unprimed");
    Harness h = new Harness(null, null);

    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    assertFalse(h.eventNames().contains("state_snapshot"));
  }

  @Test
  @DisplayName("retiring the run closes the attached connection instead of hanging it open")
  void retireClosesTheConnection() {
    RunChannel run = openAsk("run-retire");
    Harness h = new Harness(null, null);
    assertTrue(
        RunStreamWriter.attach(h.client, run, h.scheduler, HEARTBEAT_SECONDS).isPresent(),
        "the attach must succeed for this case to mean anything");

    registry.retire(new RunId("run-retire"));

    verify(h.client).close();
  }

  @Test
  @DisplayName("a close during run_started cannot create an observer or heartbeat")
  void closeDuringRunStartedReleasesBeforeResourcesAreCreated() {
    RunChannel run = openAsk("run-close-during-primer");
    Harness h = new Harness(null, null);
    AtomicInteger creatorGone = new AtomicInteger();
    h.onSend = event -> {
      if (RunStreamWriter.RUN_STARTED_EVENT.equals(event)) {
        assertTrue(h.closeCallback != null, "onClose must be installed before the first write");
        h.simulateClientClose();
      }
    };

    assertTrue(
        RunStreamWriter.attach(
                h.client, run, h.scheduler, HEARTBEAT_SECONDS, creatorGone::incrementAndGet)
            .isPresent());

    assertEquals(List.of(RunStreamWriter.RUN_STARTED_EVENT), h.eventNames());
    assertEquals(1, creatorGone.get(), "the creator transition is one-shot");
    assertEquals(0, run.observerCount(), "the close happened before subscription creation");
    verify(h.scheduler, never())
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    assertTrue(h.connectionFuture().isDone(), "the pre-created request future completes on close");
  }

  @Test
  @DisplayName("a close during synchronous replay removes the returned subscription")
  void closeDuringSynchronousReplayDoesNotLeaveResourcesBehind() {
    RunChannel run = openAsk("run-close-during-replay");
    run.publish(new RunFrame("chunk", Map.of("text", "one")));
    Harness h = new Harness(null, null);
    AtomicInteger creatorGone = new AtomicInteger();
    h.onSend = event -> {
      if ("chunk".equals(event)) {
        h.simulateClientClose();
      }
    };

    assertTrue(
        RunStreamWriter.attach(
                h.client, run, h.scheduler, HEARTBEAT_SECONDS, creatorGone::incrementAndGet)
            .isPresent());

    assertEquals(List.of(RunStreamWriter.RUN_STARTED_EVENT, "chunk"), h.eventNames());
    assertEquals(1, creatorGone.get(), "the creator transition is one-shot");
    assertEquals(
        0,
        run.observerCount(),
        "the subscription returned by replay is immediately removed");
    verify(h.scheduler, never())
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    assertTrue(h.connectionFuture().isDone());
  }

  @Test
  @DisplayName("client close and run retirement race without repeating cleanup")
  void closeAndRetireRaceIsOneShot() throws Exception {
    RunChannel run = openAsk("run-close-retire-race");
    Harness h = new Harness(null, null);
    AtomicInteger creatorGone = new AtomicInteger();
    assertTrue(
        RunStreamWriter.attach(
                h.client, run, h.scheduler, HEARTBEAT_SECONDS, creatorGone::incrementAndGet)
            .isPresent());

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var close = executor.submit(() -> {
        start.await();
        h.simulateClientClose();
        return null;
      });
      var retire = executor.submit(() -> {
        start.await();
        registry.retire(run.id());
        return null;
      });
      start.countDown();
      close.get();
      retire.get();
    } finally {
      executor.shutdownNow();
    }

    assertEquals(1, creatorGone.get(), "close and terminal retirement share one cleanup path");
    assertEquals(0, run.observerCount());
    assertTrue(h.connectionFuture().isDone());
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private record Frame(String event, String data) {}

  private static final class Harness {
    private final SseClient client = mock(SseClient.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
    private final List<Frame> sent = new ArrayList<>();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private Runnable closeCallback;
    private Supplier<? extends CompletableFuture<?>> futureSupplier;
    private java.util.function.Consumer<String> onSend;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Harness(String sinceSeqParam, String sinceParam) {
      Context ctx = mock(Context.class);
      when(client.ctx()).thenReturn(ctx);
      when(client.terminated()).thenAnswer(inv -> terminated.get());
      when(ctx.queryParam(RunStreamWriter.CURSOR_PARAM)).thenReturn(sinceSeqParam);
      when(ctx.queryParam(RunStreamWriter.ENVELOPE_CURSOR_PARAM)).thenReturn(sinceParam);
      doAnswer(inv -> {
            futureSupplier = inv.getArgument(0);
            return null;
          })
          .when(ctx)
          .future(any());
      doAnswer(inv -> {
            closeCallback = inv.getArgument(0);
            return null;
          })
          .when(client)
          .onClose(any());
      doAnswer(inv -> {
            terminated.set(true);
            if (closeCallback != null) closeCallback.run();
            return null;
          })
          .when(client)
          .close();
      doAnswer(
              inv -> {
                Object data = inv.getArgument(1, Object.class);
                sent.add(
                    new Frame(
                        inv.getArgument(0, String.class), data == null ? "" : data.toString()));
                if (onSend != null) onSend.accept(inv.getArgument(0, String.class));
                return null;
              })
          .when(client)
          .sendEvent(any(String.class), any(String.class));
      when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
          .thenReturn((ScheduledFuture) heartbeatFuture);
    }

    private List<String> eventNames() {
      return sent.stream().map(Frame::event).toList();
    }

    private Runnable heartbeatTask() {
      ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler)
          .scheduleAtFixedRate(task.capture(), anyLong(), anyLong(), any(TimeUnit.class));
      return task.getValue();
    }

    private Runnable onClose() {
      ArgumentCaptor<Runnable> onClose = ArgumentCaptor.forClass(Runnable.class);
      verify(client).onClose(onClose.capture());
      return onClose.getValue();
    }

    private void simulateClientClose() {
      terminated.set(true);
      if (closeCallback != null) closeCallback.run();
    }

    private CompletableFuture<?> connectionFuture() {
      assertTrue(futureSupplier != null, "attach must register the request future");
      return futureSupplier.get();
    }
  }
}
