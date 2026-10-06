/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The chat controller's single thread survives a step that fails in an unexpected way: the step's
 * caller gets the failure, and the next step runs. Before this, a drain that threw anything other
 * than IllegalStateException (an actuator's default drain(policy) throws
 * UnsupportedOperationException for a policy it does not know) ended the controller loop: the
 * failing step's caller was never answered, and every later step, chat start included, was refused
 * as "the chat controller has stopped".
 */
final class ChatControllerStepFailureTest {

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final BrokenDrainActuator actuator = new BrokenDrainActuator();
  private final ChatServerControllerImpl controller =
      new ChatServerControllerImpl(executors, actuator);

  @AfterEach
  void tearDown() {
    controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  @Test
  void aStepWhoseDrainThrowsFailsAloneAndTheControllerKeepsServing() throws Exception {
    actuator.drainBreaks.set(true);
    // submit with a bound: a caller whose step is lost would otherwise wait forever.
    String failed = outcome(() -> controller.submit(
        ChatActuation.draining(ChatActuation.Kind.APPLY, busy -> "busy", drained -> "applied"))
        .toCompletableFuture().get(5, TimeUnit.SECONDS));
    assertEquals("UnsupportedOperationException: drain broke", failed,
        "the step that raised the failure gets it");

    actuator.drainBreaks.set(false);
    String start = outcome(() -> {
      controller.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
          .toCompletableFuture().get(5, TimeUnit.SECONDS);
      return "started";
    });
    assertEquals("started", start, "the next step still runs");
    assertTrue(actuator.running.get());

    String applied = outcome(() -> controller.submit(
        ChatActuation.draining(ChatActuation.Kind.APPLY, busy -> "busy", drained -> "applied"))
        .toCompletableFuture().get(5, TimeUnit.SECONDS));
    assertEquals("applied", applied, "a draining step works again");
  }

  private interface Call {
    Object run() throws Exception;
  }

  /** The call's value, or the simple name and message of what it failed with. */
  private static String outcome(Call call) {
    try {
      return String.valueOf(call.run());
    } catch (Exception failure) {
      Throwable cause = failure;
      while ((cause instanceof java.util.concurrent.ExecutionException
              || cause instanceof java.util.concurrent.CompletionException)
          && cause.getCause() != null) {
        cause = cause.getCause();
      }
      return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
  }

  /** A chat server stand-in whose policy drain can be made to throw. */
  private static final class BrokenDrainActuator implements ChatActuator {
    final AtomicBoolean drainBreaks = new AtomicBoolean();
    final AtomicBoolean running = new AtomicBoolean();

    @Override
    public DrainHold drain(Duration bound) {
      return () -> { };
    }

    @Override
    public DrainHold drain(ChatDrainPolicy policy) {
      if (drainBreaks.get()) throw new UnsupportedOperationException("drain broke");
      return () -> { };
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.of(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          drained -> {
            running.set(activation.active());
            return null;
          });
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
    public void terminalStop(Duration timeout) {
      running.set(false);
    }
  }
}
