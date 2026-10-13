/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.GenerationActivity;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Background inference in the chat lane (an automatic conversation title): it ranks below picture
 * reading, self-tests and the exchanges of people and agents, it is refused with a typed reason and
 * a time to ask again rather than held open, and the lane's aging bound keeps a long pass from
 * starving it.
 */
final class BackgroundDemandYieldTest {

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final StandIn actuator = new StandIn();
  private final AtomicLong now = new AtomicLong(1_000_000_000L);
  private final ChatServerControllerImpl controller = new ChatServerControllerImpl(executors,
      actuator, ChatActuationRouter.DRAIN_BOUND, spec -> SelfTestRun.passed(),
      Duration.ofSeconds(45), now::get);

  @AfterEach
  void tearDown() {
    controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  private static Demand demand(DemandKind kind) {
    return new Demand(kind, new EngineContext(EngineContext.ClientKind.WEBVIEW, "client-1",
        Optional.empty(), Optional.empty(), "tier", "http", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND), Duration.ZERO);
  }

  private CompletionStage<Void> background() throws ChatRefusal {
    return controller.demand(demand(DemandKind.BACKGROUND));
  }

  private ChatRefusal refused() {
    return assertThrows(ChatRefusal.class, this::background);
  }

  private void advance(Duration by) {
    now.addAndGet(by.toNanos());
  }

  @Test
  void aFreeLaneAdmitsBackgroundInferenceAtOnce() throws Exception {
    assertTrue(background().toCompletableFuture().isDone());
  }

  @Test
  void pictureReadingOwnsTheServerUntilTheAgingBoundThenOneRequestIsLetThrough() throws Exception {
    try (var pass = controller.holdVdu("vdu-batch")) {
      ChatRefusal first = refused();
      assertEquals(RefusalReason.BUSY, first.reason());
      assertEquals(Optional.of(ChatServerControllerImpl.BACKGROUND_RETRY_AFTER), first.retryAfter());
      assertFalse(first.forceAllowed(), "background work is never offered a forced load");

      advance(ChatServerControllerImpl.BACKGROUND_AGING.minusSeconds(1));
      assertEquals(RefusalReason.BUSY, refused().reason(), "not before the aging bound");

      advance(Duration.ofSeconds(1));
      assertTrue(background().toCompletableFuture().isDone(), "aged: let through once");
      assertFalse(pass.interruptRequested(), "and the pass is not asked to stop for it");

      assertEquals(RefusalReason.BUSY, refused().reason(), "the next one waits its own bound");
    }
    assertTrue(background().toCompletableFuture().isDone(), "the pass over, the lane is free");
  }

  @Test
  void aReplyGeneratingOrWaitingRanksAboveIt() throws Exception {
    actuator.generating.set(true);
    assertEquals(RefusalReason.BUSY, refused().reason());
    actuator.generating.set(false);
    actuator.queued.set(1);
    assertEquals(RefusalReason.BUSY, refused().reason());
    actuator.queued.set(0);
    assertTrue(background().toCompletableFuture().isDone());
    // A person's own chat is not held back by any of this.
    actuator.generating.set(true);
    assertTrue(controller.demand(demand(DemandKind.HUMAN_CHAT)).toCompletableFuture().isDone());
  }

  @Test
  void aSelfTestIsNeverAgedPast() throws Exception {
    try (var _ = controller.beginSelfTest("activation")) {
      assertEquals(RefusalReason.SELF_TEST, refused().reason());
      advance(ChatServerControllerImpl.BACKGROUND_AGING.multipliedBy(3));
      ChatRefusal later = refused();
      assertEquals(RefusalReason.SELF_TEST, later.reason(), "there is no server to answer it");
      assertEquals(Optional.of(ChatServerControllerImpl.BACKGROUND_RETRY_AFTER), later.retryAfter());
    }
  }

  /** A chat server stand-in whose generation activity the case sets. */
  private static final class StandIn implements ChatActuator {
    final AtomicBoolean running = new AtomicBoolean();
    final AtomicBoolean generating = new AtomicBoolean();
    final AtomicInteger queued = new AtomicInteger();

    private final GenerationActivity activity = new GenerationActivity() {
      @Override public boolean generating() {
        return generating.get();
      }

      @Override public int inFlight() {
        return generating.get() ? 1 : 0;
      }

      @Override public int queued() {
        return queued.get();
      }

      @Override public long idleSinceNanos() {
        return generating.get() ? Long.MIN_VALUE : 0L;
      }

      @Override public long epoch() {
        return 0;
      }

      @Override public AutoCloseable onEdge(EdgeListener listener) {
        return () -> { };
      }
    };

    @Override
    public DrainHold drain(Duration bound) {
      return () -> { };
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          "mode", null, null,
          drained -> {
            running.set(activation.active());
            return null;
          });
    }

    @Override
    public ChatActuation<Void> vduEnter() {
      return ChatActuation.<Void>of(ChatActuation.Kind.VDU_ENTER, drained -> null);
    }

    @Override
    public ChatActuation<Void> vduExit() {
      return ChatActuation.<Void>of(ChatActuation.Kind.VDU_EXIT, drained -> null);
    }

    @Override
    public ChatActuation<Void> selfTestStop() {
      return ChatActuation.<Void>of(ChatActuation.Kind.SELF_TEST, drained -> {
        running.set(false);
        return null;
      });
    }

    @Override
    public ChatActuation<Void> selfTestEnd() {
      return ChatActuation.<Void>of(ChatActuation.Kind.SELF_TEST_END, drained -> null);
    }

    @Override
    public GenerationActivity generationActivity() {
      return activity;
    }

    @Override
    public boolean serverRunning() {
      return running.get();
    }

    @Override
    public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      try {
        return step.run(drained);
      } finally {
        if (drained != null) drained.close();
      }
    }

    @Override
    public void terminalStop(StopReason reason, Duration timeout) {
      running.set(false);
    }
  }
}
