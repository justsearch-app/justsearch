/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Where the lifecycle manager sends each {@link ChatActuation}. In the Engine this is the chat
 * controller (one thread, serialized, coalescing). Without one installed, a manager runs each step
 * on the calling thread ({@link #inline}), which is what isolated component tests use.
 */
public interface ChatActuationRouter {

  /** The caller's step was replaced by a later step with the same key before it ran. */
  final class SupersededException extends Exception {
    private static final long serialVersionUID = 1L;

    public SupersededException(String message) {
      super(message);
    }
  }

  /** The router is terminal: it accepts no further steps. */
  final class RefusedException extends Exception {
    private static final long serialVersionUID = 1L;

    public RefusedException(String message) {
      super(message);
    }
  }

  /**
   * Whether chat should be running (Spec A §4.1 {@code setActivation}): the forwarded {@code spec ∧
   * policy} intent, or a procedure's requirement. Step 2's internal form of {@code
   * io.justsearch.app.api.chat.Activation}: it also carries the transition reason the lifecycle
   * telemetry records, which the app-api type does not.
   *
   * @param active true to have the managed server running, false to have it stopped
   * @param reason why, carried into the transition's telemetry
   */
  record Activation(boolean active, TransitionReason reason) {
    public Activation {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * Runs {@code step} (after a drain when it asks for one) and returns its result to the caller,
   * blocking until it ran, was superseded or was refused.
   */
  <T> T route(ChatActuation<T> step) throws Exception;

  /**
   * Queues {@code step} and returns at once; the stage completes as {@link #route} would return.
   * For work the controller decides on its own (Spec A §7.2: a relaunch after a crash), so the
   * thread that saw the crash never waits for the relaunch. Without a controller the step runs on
   * the calling thread before this returns.
   */
  default <T> CompletionStage<T> submit(ChatActuation<T> step) {
    Objects.requireNonNull(step, "step");
    try {
      return CompletableFuture.completedFuture(route(step));
    } catch (Exception failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  /**
   * Publishes the desired activation and returns at once (Spec A §3.5 item 2). The stage completes
   * when the step ran; exceptionally with the step's failure, with {@link SupersededException} when
   * a later activation replaced it before it ran, or with {@link RefusedException} when the router
   * is terminal.
   */
  CompletionStage<Void> setActivation(Activation activation);

  /**
   * Stops the server for good: refuses every later step, interrupts the step in progress and stops
   * the process within {@code budget}.
   */
  void shutdown(StopReason reason, Duration budget);

  /** Refuse later steps before cancelling procedure bodies; physical stop follows in shutdown. */
  default void beginShutdown(StopReason reason) {
    // Isolated inline/test adapters have no queue to fence; shutdown owns their physical stop.
  }

  /** Runs every step on the calling thread; shutdown stops the process directly. */
  static ChatActuationRouter inline(ChatActuator actuator) {
    Objects.requireNonNull(actuator, "actuator");
    return new ChatActuationRouter() {
      @Override
      public <T> T route(ChatActuation<T> step) throws Exception {
        ChatActuator.DrainHold drained = null;
        if (step.drainsFirst()) {
          try {
            drained = actuator.drain(step.drainPolicy(DRAIN_BOUND));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return step.drainFailed(interrupted);
          } catch (IllegalStateException unavailable) {
            return step.drainFailed(unavailable);
          }
        }
        return actuator.execute(step, drained);
      }

      @Override
      public CompletionStage<Void> setActivation(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        try {
          route(actuator.activation(activation));
          return CompletableFuture.completedFuture(null);
        } catch (Exception failure) {
          return CompletableFuture.failedFuture(failure);
        }
      }

      @Override
      public void shutdown(StopReason reason, Duration budget) {
        actuator.terminalStop(Objects.requireNonNull(reason, "reason"), budget);
      }
    };
  }

  /** Spec A §3.3: the AFTER_IDLE drain bound. */
  Duration DRAIN_BOUND = Duration.ofSeconds(30);
}
