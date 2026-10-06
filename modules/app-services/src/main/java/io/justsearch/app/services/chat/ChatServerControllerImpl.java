/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single owner of every start, stop and relaunch of the managed chat server (Spec A step 2,
 * ADR-0050). It replaces {@code RuntimeReconciler} as the guarded single writer
 * (governance/runtime-state.v1.json).
 *
 * <p>One registered, non-daemon platform thread named {@value #THREAD_NAME} runs every actuation the
 * lifecycle manager describes, one at a time (Spec A §3.5):
 *
 * <ul>
 *   <li>Every request publishes a new immutable {@link DesiredInputs} version.
 *   <li><b>Coalescing.</b> A waiting step with a coalescing key is replaced by a later step with the
 *       same key; a step that drains first is re-evaluated after its drain, so A→B→C during a drain
 *       launches C only. Each replaced caller is told it was superseded.
 *   <li><b>No reentrancy.</b> A request made from the controller thread throws {@link
 *       IllegalStateException}. The controller holds no lock of its own while a step runs, and it
 *       drains with no lifecycle lock held.
 *   <li><b>Terminal.</b> {@link #shutdown} makes the controller terminal first: it refuses every
 *       waiting and later step, stops the server for good through the actuator's fence, interrupts
 *       the step in progress and retires the thread.
 * </ul>
 */
public final class ChatServerControllerImpl implements ChatActuationRouter, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ChatServerControllerImpl.class);

  /** The controller thread's name (Spec A §3.5). */
  public static final String THREAD_NAME = "chat-controller";

  private static final String EXECUTOR_NAME = "head.chat-controller";
  private static final Duration CLOSE_BUDGET = Duration.ofSeconds(12);

  /**
   * The controller's desired-state snapshot. Immutable; every request publishes a new version.
   *
   * @param version increases by one with every accepted request and with shutdown
   * @param terminal why the controller stopped for good, or {@code null} while it accepts steps
   */
  public record DesiredInputs(long version, StopReason terminal) {}

  private static final class Pending {
    private ChatActuation<?> step;
    private final long version;
    private final CompletableFuture<Object> result = new CompletableFuture<>();

    private Pending(ChatActuation<?> step, long version) {
      this.step = step;
      this.version = version;
    }
  }

  private final ChatActuator actuator;
  private final Duration drainBound;
  private final EngineExecutorRegistry.Registration executorOwner;
  private final ExecutorService executor;

  private final Object monitor = new Object();
  private final ArrayDeque<Pending> queue = new ArrayDeque<>();
  private DesiredInputs desired = new DesiredInputs(0, null);
  private Pending current;
  private boolean loopEnded;
  private boolean retired;
  private volatile Thread controllerThread;

  /** The Engine's controller: Spec A's 30 s AFTER_IDLE drain bound. */
  public ChatServerControllerImpl(EngineExecutorRegistry executors, ChatActuator actuator) {
    this(executors, actuator, ChatActuationRouter.DRAIN_BOUND);
  }

  ChatServerControllerImpl(
      EngineExecutorRegistry executors, ChatActuator actuator, Duration drainBound) {
    Objects.requireNonNull(executors, "executors");
    this.actuator = Objects.requireNonNull(actuator, "actuator");
    this.drainBound = Objects.requireNonNull(drainBound, "drainBound");
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    this.executorOwner = executors.register(new EngineExecutorSpec(EXECUTOR_NAME,
        EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM,
        1, limits.maxQueue(), 1));
    try {
      this.executor = executorOwner.open(Thread.ofPlatform().daemon(false).name(THREAD_NAME).factory());
      executor.execute(this::loop);
    } catch (RuntimeException | Error failure) {
      try {
        executorOwner.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /** The current desired-state snapshot. */
  public DesiredInputs desiredInputs() {
    synchronized (monitor) {
      return desired;
    }
  }

  // ==================== Requests ====================

  @Override
  @SuppressWarnings("unchecked")
  public <T> T route(ChatActuation<T> step) throws Exception {
    Objects.requireNonNull(step, "step");
    return (T) await(enqueue(step));
  }

  /** Queues the step and returns at once; never blocks on the controller thread. */
  @Override
  @SuppressWarnings("unchecked")
  public <T> CompletionStage<T> submit(ChatActuation<T> step) {
    Objects.requireNonNull(step, "step");
    try {
      return enqueue(step).result.thenApply(value -> (T) value);
    } catch (RefusedException refused) {
      return CompletableFuture.failedFuture(refused);
    } catch (IllegalStateException fromController) {
      return CompletableFuture.failedFuture(fromController);
    }
  }

  /**
   * Publishes the activation as a coalescing step and returns at once; the stage completes when
   * the step ran, was superseded by a later activation or was refused.
   */
  @Override
  public CompletionStage<Void> setActivation(Activation activation) {
    Objects.requireNonNull(activation, "activation");
    Pending pending;
    try {
      pending = enqueue(actuator.activation(activation));
    } catch (RefusedException refused) {
      return CompletableFuture.failedFuture(refused);
    }
    return pending.result.thenApply(ignored -> null);
  }

  private Pending enqueue(ChatActuation<?> step) throws RefusedException {
    if (Thread.currentThread() == controllerThread) {
      throw new IllegalStateException(
          "a chat actuation was requested from the chat controller thread (" + step + ")");
    }
    Pending pending;
    List<Pending> superseded = new ArrayList<>();
    synchronized (monitor) {
      if (desired.terminal() != null || loopEnded) {
        throw new RefusedException("the chat controller has stopped ("
            + (desired.terminal() == null ? "closed" : desired.terminal()) + "); " + step + " refused");
      }
      desired = new DesiredInputs(desired.version() + 1, null);
      pending = new Pending(step, desired.version());
      if (step.coalesceKey() != null) {
        for (Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
          Pending waiting = it.next();
          if (step.coalesceKey().equals(waiting.step.coalesceKey())) {
            it.remove();
            pending.step = pending.step.absorb(waiting.step);
            superseded.add(waiting);
          }
        }
      }
      queue.add(pending);
      monitor.notifyAll();
    }
    for (Pending replaced : superseded) supersede(replaced, pending.version);
    return pending;
  }

  private Object await(Pending pending) throws Exception {
    boolean interrupted = false;
    try {
      for (;;) {
        try {
          return pending.result.get();
        } catch (InterruptedException interrupt) {
          interrupted = true;
          synchronized (monitor) {
            if (queue.remove(pending)) {
              pending.result.completeExceptionally(interrupt);
              throw interrupt;
            }
            // The caller used to run the step itself; its interrupt still reaches the step.
            if (current == pending && controllerThread != null) controllerThread.interrupt();
          }
        } catch (ExecutionException failed) {
          Throwable cause = failed.getCause();
          if (cause instanceof Exception exception) throw exception;
          if (cause instanceof Error error) throw error;
          throw failed;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static void supersede(Pending replaced, long byVersion) {
    replaced.result.completeExceptionally(new SupersededException(
        replaced.step + " (version " + replaced.version + ") was superseded by version " + byVersion));
  }

  // ==================== The controller thread ====================

  private void loop() {
    controllerThread = Thread.currentThread();
    try {
      for (;;) {
        Pending next;
        synchronized (monitor) {
          while (queue.isEmpty() && desired.terminal() == null) monitor.wait();
          next = queue.poll();
          if (next == null) return;
          current = next;
        }
        try {
          runStep(next);
        } finally {
          synchronized (monitor) {
            current = null;
            // A forwarded caller interrupt belongs to the finished step, never to the next one.
            if (desired.terminal() == null) Thread.interrupted();
            monitor.notifyAll();
          }
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      List<Pending> refused;
      synchronized (monitor) {
        loopEnded = true;
        current = null;
        refused = new ArrayList<>(queue);
        queue.clear();
        monitor.notifyAll();
      }
      for (Pending pending : refused) refuse(pending);
    }
  }

  private void runStep(Pending pending) {
    ChatActuator.DrainHold drained = null;
    if (pending.step.drainsFirst()) {
      try {
        drained = actuator.drain(pending.step.drainPolicy(drainBound));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        complete(pending, () -> pending.step.drainFailed(interrupted));
        return;
      } catch (IllegalStateException unavailable) {
        complete(pending, () -> pending.step.drainFailed(unavailable));
        return;
      }
      Pending runner = pending;
      Pending replaced = null;
      synchronized (monitor) {
        if (desired.terminal() != null) {
          drained.close();
          replaced = pending;
          runner = null;
        } else if (pending.step.coalesceKey() != null) {
          // Re-evaluate after the drain: a newer step with the same key replaces this one and
          // inherits the drained admission.
          for (Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending newer = it.next();
            if (pending.step.coalesceKey().equals(newer.step.coalesceKey())) {
              it.remove();
              newer.step = newer.step.absorb(pending.step);
              replaced = pending;
              runner = newer;
              current = newer;
              break;
            }
          }
        }
      }
      if (runner == null) {
        refuse(replaced);
        return;
      }
      if (replaced != null) supersede(replaced, runner.version);
      execute(runner, drained);
      return;
    }
    execute(pending, null);
  }

  private void execute(Pending pending, ChatActuator.DrainHold drained) {
    complete(pending, () -> actuator.execute(pending.step, drained));
  }

  private interface StepResult {
    Object get() throws Exception;
  }

  private static void complete(Pending pending, StepResult result) {
    try {
      pending.result.complete(result.get());
    } catch (Exception | Error failure) {
      pending.result.completeExceptionally(failure);
    }
  }

  private void refuse(Pending pending) {
    StopReason terminal;
    synchronized (monitor) {
      terminal = desired.terminal();
    }
    pending.result.completeExceptionally(new RefusedException(pending.step
        + " was refused: the chat controller has stopped ("
        + (terminal == null ? "closed" : terminal) + ")"));
  }

  // ==================== Terminal ====================

  @Override
  public void shutdown(StopReason reason, Duration budget) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(budget, "budget");
    long deadline = System.nanoTime() + budget.toNanos();
    List<Pending> refused;
    synchronized (monitor) {
      if (desired.terminal() == null) {
        desired = new DesiredInputs(desired.version() + 1, reason);
        LOG.info("Chat controller is terminal ({})", reason);
      }
      refused = new ArrayList<>(queue);
      queue.clear();
      monitor.notifyAll();
    }
    for (Pending pending : refused) refuse(pending);
    RuntimeException stopFailure = null;
    try {
      // The fence is lock-independent: a launch the step in progress makes after it fails.
      actuator.terminalStop(budget);
    } catch (RuntimeException failure) {
      stopFailure = failure;
    }
    synchronized (monitor) {
      if (current != null && controllerThread != null
          && Thread.currentThread() != controllerThread) {
        controllerThread.interrupt();
      }
    }
    retire(deadline);
    if (stopFailure != null) throw stopFailure;
  }

  private void retire(long deadline) {
    if (Thread.currentThread() == controllerThread) return;
    try {
      synchronized (monitor) {
        while (!loopEnded) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            LOG.warn("Chat controller step did not finish within the shutdown budget");
            break;
          }
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        }
        if (retired) return;
        retired = true;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    executor.shutdown();
    executorOwner.close();
  }

  /** Stops for good with {@link StopReason#QUIT} unless already terminal. */
  @Override
  public void close() {
    shutdown(StopReason.QUIT, CLOSE_BUDGET);
  }
}
