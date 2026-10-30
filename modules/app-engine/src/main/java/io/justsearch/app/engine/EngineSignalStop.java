/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

/** Routes Unix termination before JVM hooks can release the ORT environment during native work. */
public final class EngineSignalStop {
  private static final Logger log = LoggerFactory.getLogger(EngineSignalStop.class);

  private final IntConsumer exit;
  private final IntConsumer halt;
  private final long deadlineMs;
  private final CompletableFuture<Runnable> close = new CompletableFuture<>();
  private volatile EngineShutdownSequence sequence;
  private volatile boolean bootFailed;
  private boolean nativeBoot;
  private int signalStatus;
  private QuitBudget budget;
  private boolean ready;
  private Runnable signalClose;

  public EngineSignalStop() {
    this(System::exit, code -> Runtime.getRuntime().halt(code),
        EngineSupervisionPolicy.GRACEFUL_STOP_DEADLINE_MS);
  }

  EngineSignalStop(IntConsumer exit, IntConsumer halt, long deadlineMs) {
    this.exit = exit;
    this.halt = halt;
    this.deadlineMs = deadlineMs;
  }

  /** Windows keeps its current console handling. Registration failure retains the JVM handler. */
  public void install() {
    if (io.justsearch.configuration.PlatformPaths.isWindows()) return;
    for (String name : new String[] {"TERM", "INT"}) {
      try {
        Signal.handle(new Signal(name), signal -> received(128 + signal.getNumber()));
      } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException failure) {
        log.warn("Could not route {} through ordered shutdown; retaining JVM handling", name, failure);
      }
    }
  }

  /** A signal winning before this barrier prevents all subsequent native initialization. */
  public synchronized boolean beginNativeInitialization() {
    if (signalStatus != 0) return false;
    nativeBoot = true;
    return true;
  }

  /** Call before exposing the sequence to other triggers, so a pending boot signal claims first. */
  public synchronized void publish(EngineShutdownSequence shutdown) {
    sequence = shutdown;
    if (signalStatus != 0) reserveSignalClose();
  }

  /** Finish installing hooks and dispatchers before a reserved boot stop may close their owners. */
  public synchronized void ready() {
    ready = true;
    if (signalStatus != 0 && signalClose != null) close.complete(signalClose);
  }

  /** Main retains ownership of fatal startup cleanup; a signal still bounds that cleanup. */
  public void bootFailed() {
    bootFailed = true;
  }

  synchronized void received(int status) {
    if (signalStatus != 0) return;
    signalStatus = status;
    budget = QuitBudget.of(System.currentTimeMillis() + deadlineMs);
    // This thread never joins the initializer or any close and never enters a JVM shutdown hook.
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);
    Thread.ofPlatform().daemon().name("engine-signal-deadline").start(() -> {
      try {
        TimeUnit.NANOSECONDS.sleep(Math.max(0L, deadlineNanos - System.nanoTime()));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      EngineShutdownSequence owner = sequence;
      halt.accept(bootFailed ? EngineExit.FATAL_OR_UNCAUGHT
          : owner == null ? EngineExit.FATAL_OR_UNCAUGHT
              : owner.signalDeadlineExitCode(EngineExit.FATAL_OR_UNCAUGHT));
    });
    if (!nativeBoot && !bootFailed) close.complete(() -> exit.accept(status));
    else if (sequence != null) reserveSignalClose();
    Thread.ofPlatform().name("engine-signal-stop").start(() -> close.join().run());
  }

  private void reserveSignalClose() {
    Runnable action = sequence.claimSignalStop(signalStatus, budget);
    signalClose = action == null ? () -> {} : action;
    if (ready) close.complete(signalClose);
  }
}
