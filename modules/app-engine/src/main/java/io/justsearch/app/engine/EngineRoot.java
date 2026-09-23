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
 * <p><b>One process configuration authority.</b> The factory retains the installed
 * {@code ConfigStore}. Each physical index start samples one immutable {@code ResolvedConfig}
 * for its composed resources; auxiliary hot readers observe that same store's current snapshot.
 * The old cross-process serialized copy at ordinal 450 remains retired.
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
  private final io.justsearch.app.services.bootstrap.OperationAuthority authority;

  /** Preloaded process authority shared with Head; independent of index restarts. */
  public io.justsearch.app.services.bootstrap.OperationAuthority authority() { return authority; }

  private final io.justsearch.app.api.operations.OperationStore operations;
  private final io.justsearch.app.api.operations.OperationAttemptRunner attempts;
  public io.justsearch.app.api.operations.OperationAttemptRunner operationAttempts() { return attempts; }

  /** Externally owned, shared by both halves; closed after the index half. */
  public io.justsearch.app.api.operations.OperationStore operations() { return operations; }
  private final DefaultEngineProcessResources processResources;
  private final EngineResourcePolicy resources;
  private final EngineAdmissionController admission;
  private final io.justsearch.core.execution.EngineExecutorRegistry executors;
  private final io.justsearch.core.component.EngineComponentRegistry components;
  private final io.justsearch.core.component.ComponentHandle indexComponent;
  private final io.justsearch.core.component.ComponentHandle encoderComponent;

  /** Process lifetime, deliberately independent of the restartable index-half close. */
  public io.justsearch.core.execution.EngineExecutorRegistry executors() { return executors; }

  /** Final process teardown is separate from this host's restartable index close. */
  public io.justsearch.app.api.EngineProcessResources processResources() { return processResources; }

  /** Shared short publication boundary for configuration and in-process serving references. */
  public java.util.concurrent.locks.ReentrantReadWriteLock publicationLock() {
    return processResources.publicationLock();
  }

  /** Process-owned observations shared by all four component owners and their projections. */
  public io.justsearch.core.component.EngineComponentRegistry components() { return components; }

  /** Value identity of the applied components and committed active index; never a desired revision. */
  public String appliedConfigurationRevision(io.justsearch.core.context.EngineContext context) {
    EngineKnowledgeClient capturedClient = client;
    if (capturedClient == null) {
      throw new io.justsearch.app.api.knowledge.KnowledgeClientException(
          io.justsearch.app.api.knowledge.KnowledgeClientException.Status.UNAVAILABLE,
          "Applied configuration requires a serving index");
    }
    var before = components.snapshot();
    var generation = capturedClient.captureAppliedGeneration(context);
    var after = components.snapshot();
    if (client != capturedClient || before.revision() != after.revision()) {
      throw new io.justsearch.app.api.knowledge.KnowledgeClientException(
          io.justsearch.app.api.knowledge.KnowledgeClientException.Status.ABORTED,
          "Engine composition changed during applied configuration capture");
    }
    return AppliedConfigurationRevision.digest(before, generation);
  }

  /** The shared physical and sampled index publisher, with the same reason-retention policy. */
  public io.justsearch.core.component.ComponentHandle indexComponent() { return indexComponent; }

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

  @FunctionalInterface
  interface ServerFactory {
    KnowledgeServer create(GpuSchedulingGauge gauge, io.justsearch.core.execution.EngineExecutorRegistry executors,
        io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
        io.justsearch.core.component.ComponentHandle indexComponent,
        io.justsearch.core.component.ComponentHandle encoderComponent);
  }
  private final ServerFactory serverFactory;
  private boolean clientReady;
  private final RecordedIngestionCoordinator recordedIngestion;

  public io.justsearch.app.api.operations.RecordedIngestionService recordedIngestion() { return recordedIngestion; }
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
  public EngineRoot(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts, long deadlineMs, int batchSize) {
    this(operations, attempts, deadlineMs, batchSize, EngineRoot::missingExitAction);
  }

  /** Process composition whose terminal-writer path is owned by the enclosing Head lifecycle. */
  public static EngineRoot forProcess(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      long deadlineMs, int batchSize, IntConsumer terminalWriterFaultAction) {
    return new EngineRoot(operations, attempts, deadlineMs, batchSize, terminalWriterFaultAction,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  public static EngineRoot forProcess(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    return new EngineRoot(operations, attempts, deadlineMs, batchSize, terminalWriterFaultAction, childRegistry);
  }

  public static EngineRoot forProcess(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Runnable requestedRestartAction) {
    return new EngineRoot(operations, attempts, deadlineMs, batchSize, terminalWriterFaultAction, childRegistry,
        requestedRestartAction);
  }

  /** Process boot must supply authority loaded before its asynchronous fork. */
  public static EngineRoot forProcess(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts, long deadlineMs, int batchSize,
      IntConsumer terminalWriterFaultAction, io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Runnable requestedRestartAction, io.justsearch.app.services.bootstrap.OperationAuthority authority,
      io.justsearch.configuration.resolved.ConfigStore configStore) {
    return forProcess(operations, attempts, deadlineMs, batchSize, terminalWriterFaultAction,
        childRegistry, requestedRestartAction, authority, configStore,
        new DefaultEngineProcessResources(configStore.publicationLock()));
  }

  /** Uses the same already composed process resources as the settings owner. */
  public static EngineRoot forProcess(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts, long deadlineMs, int batchSize,
      IntConsumer terminalWriterFaultAction, io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Runnable requestedRestartAction, io.justsearch.app.services.bootstrap.OperationAuthority authority,
      io.justsearch.configuration.resolved.ConfigStore configStore,
      DefaultEngineProcessResources processResources) {
    Objects.requireNonNull(configStore, "configStore");
    Objects.requireNonNull(processResources, "processResources");
    if (configStore.publicationLock() != processResources.publicationLock()) {
      throw new IllegalArgumentException("ConfigStore and process resources must share publication lock");
    }
    return new EngineRoot(operations, attempts, serverFactory(childRegistry, () -> configStore),
        deadlineMs, batchSize, terminalWriterFaultAction, requestedRestartAction, authority,
        processResources);
  }

  private EngineRoot(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts, long deadlineMs, int batchSize, IntConsumer exitAction) {
    this(operations, attempts, deadlineMs, batchSize, exitAction, io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  private EngineRoot(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      long deadlineMs,
      int batchSize,
      IntConsumer exitAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    this(operations, attempts, deadlineMs, batchSize, exitAction, childRegistry, EngineRoot::embeddedRestartRequired);
  }

  private EngineRoot(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      long deadlineMs,
      int batchSize,
      IntConsumer exitAction,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Runnable requestedRestartAction) {
    this(operations, attempts, deadlineMs, batchSize, exitAction, childRegistry, requestedRestartAction,
        io.justsearch.app.services.bootstrap.OperationAuthority.load(
            io.justsearch.configuration.PlatformPaths.resolveDataDir()));
  }

  private EngineRoot(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts, long deadlineMs, int batchSize,
      IntConsumer exitAction, io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      Runnable requestedRestartAction, io.justsearch.app.services.bootstrap.OperationAuthority authority) {
    this(operations, attempts,
        serverFactory(childRegistry, io.justsearch.configuration.resolved.ConfigStore::global),
        deadlineMs,
        batchSize,
        exitAction,
        requestedRestartAction, authority);
  }

  private static ServerFactory serverFactory(
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      java.util.function.Supplier<io.justsearch.configuration.resolved.ConfigStore> authority) {
    // Process boot supplies its explicit owner; embedded compatibility constructors resolve at start.
    return (gauge, executorRegistry, ingestion, indexComponent, encoderComponent) -> {
      var configStore = authority.get();
      var startupConfiguration = configStore.get();
      WorkerConfig workerConfig = WorkerConfig.load(startupConfiguration);
      // Dev reload signals arrive under this runtime directory from the owning dev tool.
      return new KnowledgeServer(
          executorRegistry, workerConfig,
          new InProcessWorkerSignalBus(gauge, workerConfig.dataDir().resolve("runtime")),
          childRegistry, ingestion, indexComponent, encoderComponent, startupConfiguration,
          configStore::get, configStore.publicationLock());
    };
  }

  /** Test seam: supply the index half rather than building it from the global config. */
  EngineRoot(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      ServerFactory serverFactory, long deadlineMs, int batchSize) {
    this(operations, attempts, serverFactory, deadlineMs, batchSize, EngineRoot::missingExitAction);
  }

  /** Test seam: supply both the index half and the process exit action. */
  EngineRoot(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      ServerFactory serverFactory,
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction) {
    this(operations, attempts, serverFactory, deadlineMs, batchSize, terminalWriterFaultAction,
        EngineRoot::embeddedRestartRequired);
  }

  EngineRoot(io.justsearch.app.api.operations.OperationStore operations, io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      ServerFactory serverFactory,
      long deadlineMs,
      int batchSize,
      IntConsumer terminalWriterFaultAction,
      Runnable requestedRestartAction) {
    this(operations, attempts, serverFactory, deadlineMs, batchSize, terminalWriterFaultAction,
        requestedRestartAction, io.justsearch.app.services.bootstrap.OperationAuthority.inMemory());
  }

  /** Test seam preserving the same supplied authority as the process factory. */
  EngineRoot(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      ServerFactory serverFactory, long deadlineMs, int batchSize,
      IntConsumer terminalWriterFaultAction, Runnable requestedRestartAction,
      io.justsearch.app.services.bootstrap.OperationAuthority authority) {
    this(operations, attempts, serverFactory, deadlineMs, batchSize, terminalWriterFaultAction,
        requestedRestartAction, authority, new DefaultEngineProcessResources());
  }

  private EngineRoot(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts,
      ServerFactory serverFactory, long deadlineMs, int batchSize,
      IntConsumer terminalWriterFaultAction, Runnable requestedRestartAction,
      io.justsearch.app.services.bootstrap.OperationAuthority authority,
      DefaultEngineProcessResources processResources) {
    this.processResources = Objects.requireNonNull(processResources, "processResources");
    this.resources = processResources.policy();
    this.admission = processResources.admission();
    this.executors = processResources.executors();
    this.components = processResources.components();
    this.indexComponent = new io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle(
        components.register(new io.justsearch.core.component.ComponentSpec("index", true,
            KnowledgeServer.componentDependencies(),
            io.justsearch.core.component.ComponentSpec.ComposeCapability.BESIDE,
            java.time.Duration.ofSeconds(60), 2)));
    this.encoderComponent = components.register(new io.justsearch.core.component.ComponentSpec(
        "encoders", false,
        io.justsearch.indexerworker.server.InferenceCompositionRoot.componentDependencies(),
        io.justsearch.core.component.ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY,
        java.time.Duration.ofMinutes(2), 2));
    this.authority = Objects.requireNonNull(authority, "authority");
    this.operations = Objects.requireNonNull(operations, "operations");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.recordedIngestion = new RecordedIngestionCoordinator(operations, attempts, admission, authority);
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
      if (!clientReady) throw new IOException("EngineRoot retains an unready client after incomplete startup or close");
      return client;
    }
    if (server != null) {
      throw new IOException("EngineRoot cannot start while its previous server close is incomplete");
    }
    KnowledgeServer started = serverFactory.create(gpuScheduling, executors, recordedIngestion,
        indexComponent, encoderComponent);
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
    } catch (IOException | RuntimeException | Error failure) {
      // A failed start may also have failed cleanup. Retain that physical owner until its
      // close latch confirms completion, so retry cannot overlap a live queue or activation.
      boolean closed = false;
      try {
        closed = started.awaitClosed(0);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        failure.addSuppressed(interrupted);
      }
      if (closed) {
        synchronized (terminalWriterFaultOwnerLock) {
          if (this.server == started) this.server = null;
        }
      }
      throw failure;
    }

    // THE gauge the indexing loop paces off, read from the field that owns it rather than from
    // IndexingPacing: the pacing field starts as IndexingPacing.unthrottled(), whose gauge is a
    // fresh orphan, and is only replaced with the real policy partway through start().
    ForegroundLoadGate gate = new ForegroundLoadGate(started.foregroundLoad());
    EngineKnowledgeClient built =
        new EngineKnowledgeClient(executors, started::appServices, gate, deadlineMs, batchSize, telemetry,
            () -> requestRestart(started), admission, authority.roots(),
            started::captureServingView);
    this.client = built;
    try {
      recordedIngestion.bindProducer(built::enumerateRecordedRoot);
      recordedIngestion.bindBulkProducer(built::enumerateCapturedRoots, built, () -> requestRestart(started));
      clientReady = true;
    } catch (RuntimeException | Error failure) {
      try { close(); }
      catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
      throw failure;
    }
    log.info("Engine composed the index half in-process (no worker process, no channel)");
    return built;
  }

  @Override
  public WorkerHost.ServingLease captureServingView() {
    KnowledgeServer current = server;
    if (current == null) throw new IllegalStateException("Index serving owner is unavailable");
    var lease = current.captureServingView();
    return new WorkerHost.ServingLease() {
      @Override
      public <T> T withClient(KnowledgeClient client,
          java.util.function.Function<KnowledgeClient, T> action) {
        if (!(client instanceof EngineKnowledgeClient engineClient)) {
          throw new IllegalArgumentException("Serving view belongs to an Engine client");
        }
        return engineClient.withServingLease(lease, () -> action.apply(client));
      }

      @Override public void close() { lease.close(); }
    };
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
    clientReady = false;
    KnowledgeServer s;
    synchronized (terminalWriterFaultOwnerLock) {
      s = server;
    }
    EngineKnowledgeClient c = client;
    try { recordedIngestion.stopProducers(deadlineMs); }
    catch (IOException incomplete) {
      throw new IllegalStateException("Recorded ingestion close incomplete; client and index retained for retry", incomplete);
    }
    if (c != null) {
      c.close();
      client = null;
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

  /** Stop attachment producers before the process drains admitted work and closes Head. */
  public void quiesceProducers() {
    try {
      recordedIngestion.stopProducers(deadlineMs);
    } catch (IOException incomplete) {
      throw new IllegalStateException("Recorded ingestion producer drain is incomplete", incomplete);
    }
  }

  /** Physical native owner evidence for the process exit authority. */
  public io.justsearch.app.api.NativeQuiescence nativeQuiescence() {
    KnowledgeServer current = server;
    return current == null ? io.justsearch.app.api.NativeQuiescence.QUIESCED
        : current.nativeQuiescence();
  }
}
