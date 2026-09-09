/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WorkerHost;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Engine's composition root (lane F design 3.2), and the only place that composes both halves.
 *
 * <p>Design 3.2 places the composition root <em>outside</em> the three rings: it "binds every
 * implementation and owns the two sequences that span rings, startup and shutdown". This module is
 * the only one permitted to depend on both halves of the Engine — the application half
 * ({@code app-services}) and the index half ({@code worker-services} / {@code worker-core} /
 * {@code indexer-worker}) — which is what ArchUnit rule 6b pins from the other side: nothing
 * outside {@code io.justsearch.app.engine..}, {@code io.justsearch.indexerworker..} and
 * {@code io.justsearch.adapters..} may reach into
 * {@code io.justsearch.indexerworker.{server,services,loop}..}.
 *
 * <p><b>What item A6 made this class do.</b> Before A6 the index half was a second JVM:
 * {@code KnowledgeServerBootstrap} created a memory-mapped signal file, spawned
 * {@code IndexerWorker.main}, waited for it to publish a port, and opened a gRPC channel to it.
 * This class replaces those four steps with three method calls — build the same
 * {@link WorkerConfig} the worker built for itself, construct a {@link KnowledgeServer}, start it —
 * and hands back an {@link EngineKnowledgeClient} whose calls are calls.
 *
 * <p><b>One config, no snapshot.</b> {@link WorkerConfig#load()} reads
 * {@code ConfigStore.global()}, which the Head has already resolved. In the split world the worker
 * received a serialised copy at config ordinal 450 and then checked it for divergence from the
 * Head's; in one JVM there is one {@code ResolvedConfig} and divergence is not a thing that can
 * happen. The snapshot tier itself is retired at item A19; nothing here depends on it either way.
 *
 * <p><b>What A6 deliberately left standing, and where it went.</b> Between A6 and A9
 * {@link KnowledgeServer#start()} still bound the gRPC server and still published a port — both
 * harmless (the in-process bus published it nowhere), and keeping them for one item is what let the
 * branch stay compiling and green rather than opening a red window. Item A9 deleted both.
 *
 * <p>Adding a port is a catalogue entry, an interface, and a binding in this class (design 3.3).
 * Nothing else in the repo may construct an implementation of a port.
 */
public final class EngineRoot implements WorkerHost {
  private final EngineResourcePolicy resources = EngineResourcePolicy.load();
  private final EngineAdmissionController admission = new EngineAdmissionController(resources);
  private final io.justsearch.core.execution.EngineExecutorRegistry executors =
      new DefaultEngineExecutorRegistry(resources);

  /** Process lifetime, deliberately independent of the restartable index-half close. */
  public io.justsearch.core.execution.EngineExecutorRegistry executors() { return executors; }

  /** The same owner is shared by the API front, library calls and ordered shutdown. */
  public io.justsearch.app.api.EngineAdmissionService admission() { return admission; }

  /** Existing mutation leases and new work admission freeze under one lock. */
  public io.justsearch.app.api.OperationLeaseService operationLeases() { return admission; }

  /** Targets and live counters share one root; future producers explicitly report no live count. */
  public io.justsearch.core.context.RetainedStateBudget retainedState() {
    return resources.retained();
  }

  /** Policy targets; admission and executor consumers will be connected in later C1 batches. */
  public java.util.Map<String, Integer> executionLimits() {
    return resources.execution();
  }

  private static final Logger log = LoggerFactory.getLogger(EngineRoot.class);

  /**
   * How long {@link #close()} waits for the index half to confirm it finished closing.
   *
   * <p>Zero would be almost right — {@code close()} is synchronous, so by the time it returns the
   * latch is already down on the happy path. A small budget instead of zero because close() joins
   * background threads on their own timeouts, and a shutdown that is merely slow should not be
   * reported as a shutdown that failed.
   */
  private static final long CLOSE_COMPLETION_TIMEOUT_MS = 2_000L;

  private final java.util.function.BiFunction<GpuSchedulingGauge,
      io.justsearch.core.execution.EngineExecutorRegistry, KnowledgeServer> serverFactory;
  private final long deadlineMs;
  private final int batchSize;
  private final IntConsumer terminalWriterFaultAction;
  private final Runnable requestedRestartAction;
  private final Object terminalWriterFaultOwnerLock = new Object();
  private boolean terminalWriterExitAccepted;

  private volatile KnowledgeServer server;
  private volatile EngineKnowledgeClient client;

  /**
   * Creates an embedded composition. A caller that can encounter terminal writer faults must own
   * process recovery explicitly; this form deliberately has no process-exit authority.
   *
   * @param deadlineMs the base call deadline the {@code RpcDeadlineCategory} multipliers apply to
   *     — the same {@code KnowledgeServerConfig.deadlineMs()} the wire client used
   * @param batchSize the per-batch submission clamp
   */
  public EngineRoot(long deadlineMs, int batchSize) {
    this(deadlineMs, batchSize, EngineRoot::missingExitAction);
  }

  /** Process composition whose terminal-writer path is owned by the enclosing Head lifecycle. */
  public static EngineRoot forProcess(
      long deadlineMs, int batchSize, IntConsumer terminalWriterFaultAction) {
    return new EngineRoot(deadlineMs, batchSize, terminalWriterFaultAction,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  public static EngineRoot forProcess(
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    return new EngineRoot(deadlineMs, batchSize, terminalWriterFaultAction, childRegistry);
  }

  public static EngineRoot forProcess(
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Runnable requestedRestartAction) {
    return new EngineRoot(deadlineMs, batchSize, terminalWriterFaultAction, childRegistry,
        requestedRestartAction);
  }

  private EngineRoot(long deadlineMs, int batchSize, IntConsumer exitAction) {
    this(deadlineMs, batchSize, exitAction, io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  private EngineRoot(
      long deadlineMs,
      int batchSize,
      IntConsumer exitAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    this(deadlineMs, batchSize, exitAction, childRegistry, EngineRoot::embeddedRestartRequired);
  }

  private EngineRoot(
      long deadlineMs,
      int batchSize,
      IntConsumer exitAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Runnable requestedRestartAction) {
    this(
        (gauge, executorRegistry) -> {
          WorkerConfig workerConfig = WorkerConfig.load();
          // Review S2: the hot-reload trigger is a file under <dataDir>/runtime/, written by the
          // dev MCP tool from another process. Supplying the directory here is what re-arms it;
          // the no-arg bus (tests, any composition without a data dir) leaves reload disabled
          // rather than watching a path nobody writes.
          return new KnowledgeServer(
              executorRegistry, workerConfig,
              new InProcessWorkerSignalBus(gauge, workerConfig.dataDir().resolve("runtime")),
              childRegistry);
        },
        deadlineMs,
        batchSize,
        exitAction,
        requestedRestartAction);
  }

  /** Test seam: supply the index half rather than building it from the global config. */
  EngineRoot(
      Function<GpuSchedulingGauge, KnowledgeServer> serverFactory, long deadlineMs, int batchSize) {
    this(serverFactory, deadlineMs, batchSize, EngineRoot::missingExitAction);
  }

  /** Test seam: supply both the index half and the process exit action. */
  EngineRoot(
      Function<GpuSchedulingGauge, KnowledgeServer> serverFactory,
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction) {
    this(serverFactory, deadlineMs, batchSize, terminalWriterFaultAction,
        EngineRoot::embeddedRestartRequired);
  }

  EngineRoot(
      Function<GpuSchedulingGauge, KnowledgeServer> serverFactory,
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction,
      Runnable requestedRestartAction) {
    this((gauge, ignored) -> serverFactory.apply(gauge), deadlineMs, batchSize,
        terminalWriterFaultAction, requestedRestartAction);
  }

  private EngineRoot(
      java.util.function.BiFunction<GpuSchedulingGauge,
          io.justsearch.core.execution.EngineExecutorRegistry, KnowledgeServer> serverFactory,
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction,
      Runnable requestedRestartAction) {
    this.requestedRestartAction = Objects.requireNonNull(requestedRestartAction, "requestedRestartAction");
    this.serverFactory = Objects.requireNonNull(serverFactory, "serverFactory");
    this.deadlineMs = deadlineMs;
    this.batchSize = batchSize;
    this.terminalWriterFaultAction =
        Objects.requireNonNull(terminalWriterFaultAction, "terminalWriterFaultAction");
  }

  @Override
  public synchronized KnowledgeClient start(GpuSchedulingGauge gpuScheduling, IpcTelemetry telemetry)
      throws IOException {
    Objects.requireNonNull(gpuScheduling, "gpuScheduling");
    if (client != null) {
      return client;
    }
    if (server != null) {
      throw new IOException("EngineRoot cannot start while its previous server close is incomplete");
    }
    KnowledgeServer started = serverFactory.apply(gpuScheduling, executors);
    synchronized (terminalWriterFaultOwnerLock) {
      if (terminalWriterExitAccepted) {
        throw new IOException("EngineRoot cannot restart after accepting a terminal writer fault");
      }
      this.server = started;
    }
    started.onTerminalWriterFailure(
        failure -> acceptTerminalWriterFailure(started, failure));
    started.onMigrationRestart(() -> requestRestart(started));
    try {
      started.start();
    } catch (IOException | RuntimeException e) {
      synchronized (terminalWriterFaultOwnerLock) {
        if (this.server == started) {
          this.server = null;
        }
      }
      throw e;
    }

    // THE gauge the indexing loop paces off, read from the field that owns it rather than from
    // IndexingPacing: the pacing field starts as IndexingPacing.unthrottled(), whose gauge is a
    // fresh orphan, and is only replaced with the real policy partway through start().
    ForegroundLoadGate gate = new ForegroundLoadGate(started.foregroundLoad());
    EngineKnowledgeClient built =
        new EngineKnowledgeClient(executors, started::appServices, gate, deadlineMs, batchSize, telemetry,
            () -> requestRestart(started), admission);
    this.client = built;
    log.info("Engine composed the index half in-process (no worker process, no channel)");
    return built;
  }

  private void requestRestart(KnowledgeServer source) {
    synchronized (terminalWriterFaultOwnerLock) {
      if (server != source) return;
      requestedRestartAction.run();
    }
  }

  private static void embeddedRestartRequired() {
    log.warn("Migration requires the embedded Engine owner to restart it");
  }

  private void acceptTerminalWriterFailure(KnowledgeServer source, Throwable failure) {
    synchronized (terminalWriterFaultOwnerLock) {
      if (terminalWriterExitAccepted || server != source) {
        return;
      }
      terminalWriterExitAccepted = true;
    }
    log.error("The active Lucene writer is permanently unusable; terminating the Engine", failure);
    Thread exitThread =
        new Thread(
            () -> terminalWriterFaultAction.accept(EngineExit.FATAL_OR_UNCAUGHT),
            "engine-terminal-writer-exit");
    exitThread.setDaemon(false);
    exitThread.start();
  }

  private static void missingExitAction(int exitCode) {
    throw new IllegalStateException(
        "EngineRoot has no process exit action for terminal writer exit " + exitCode);
  }

  @Override
  public long ownerPid() {
    return ProcessHandle.current().pid();
  }

  @Override
  public synchronized void close() {
    KnowledgeServer s;
    synchronized (terminalWriterFaultOwnerLock) {
      s = server;
    }
    EngineKnowledgeClient c = client;
    client = null;
    if (c != null) {
      c.close();
    }
    if (s != null) {
      try {
        s.close();
      } catch (IOException e) {
        log.warn("Error closing the in-process index half", e);
        throw new IllegalStateException("In-process index close incomplete; owner retained for retry", e);
      }
      // Stage-A checkpoint. The first version of this block read `s.isRunning()` and warned if it
      // was still true — which it never could be, because close() sets `running = false` in its
      // FIRST line (KnowledgeServer.java:2163) and isRunning() is `running && latch > 0`. The
      // check was constant-false after close() returned: a "production consumer" that could not
      // fire, added to answer a review finding that isRunning() had no consumer. That is the same
      // wrong-gate shape this checkpoint exists to remove, committed while removing it.
      //
      // What is actually worth knowing here is whether close() RAN TO COMPLETION, so the latch was
      // moved to close()'s last statement and that is what this awaits. `false` means close() threw
      // partway and left runtimes, threads or the index root lock open — which the next open on
      // this data directory would discover as a held lock, far from the cause.
      try {
        if (!s.awaitClosed(CLOSE_COMPLETION_TIMEOUT_MS)) {
          log.warn(
              "The in-process index half did not finish closing within {}ms. Its shutdown did not"
                  + " run to completion, so Lucene runtimes, background threads or the index root"
                  + " lock may still be held; a subsequent open on the same data directory can fail"
                  + " with a lock error whose real cause is here.",
              CLOSE_COMPLETION_TIMEOUT_MS);
          throw new IllegalStateException("In-process index close did not complete; owner retained for retry");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while confirming the index half finished closing");
        throw new IllegalStateException("Interrupted confirming in-process index close", e);
      }
      synchronized (terminalWriterFaultOwnerLock) {
        if (server == s) server = null;
      }
    }
  }
}
