/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WorkerHost;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
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
  /** Reconcile a committed installer generation before boot composes any model or Worker view. */
  public static io.justsearch.app.api.UiSettings reconcileInstallerGenerationBoot(
      io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      io.justsearch.app.api.UiSettings loadedSettings,
      io.justsearch.configuration.resolved.ResolvedConfig preliminaryConfig) throws IOException {
    return RecordedIngestionCoordinator.reconcileInstallerGenerationBoot(
        operations, settingsStore, loadedSettings, preliminaryConfig);
  }

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

  public io.justsearch.core.component.ComponentHandle encoderComponent() { return encoderComponent; }

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

    default io.justsearch.configuration.resolved.ResolvedConfig captureConfiguration() {
      return null;
    }

    default KnowledgeServer create(GpuSchedulingGauge gauge,
        io.justsearch.core.execution.EngineExecutorRegistry executors,
        io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
        io.justsearch.core.component.ComponentHandle indexComponent,
        io.justsearch.core.component.ComponentHandle encoderComponent,
        io.justsearch.configuration.resolved.ResolvedConfig exactConfiguration) {
      return create(gauge, executors, ingestion, indexComponent, encoderComponent);
    }
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
  private KnowledgeServer.IndexStartContext retainedIndexStartContext;
  private volatile java.util.function.Supplier<io.justsearch.app.api.settings.QueryRoleSelection>
      queryRoleBootSelection = () -> null;

  public void bindQueryRoleBootSelection(
      java.util.function.Supplier<io.justsearch.app.api.settings.QueryRoleSelection> selection) {
    if (server != null) throw new IllegalStateException("Query witness binding followed Worker start");
    queryRoleBootSelection = Objects.requireNonNull(selection, "selection");
  }

  /** Fixed settings owner that resolves the current physical Worker only at preparation time. */
  public io.justsearch.app.services.settings.FixedSettingsComponentComposer.Owner
      queryRoleSettingsOwner(
          java.util.function.Supplier<io.justsearch.app.api.settings.QueryRoleSelection> prior) {
    Objects.requireNonNull(prior, "prior");
    return (candidate, desired, changedKeys) -> {
      KnowledgeServer current = server;
      if (current == null) {
        throw new IllegalStateException("Physical encoder owner is unavailable");
      }
      var prepared = current.prepareQueryRoleSettings(candidate, desired, changedKeys, prior.get());
      return new io.justsearch.app.services.settings.FixedSettingsComponentComposer.QueryRolePreparedOwner() {
        @Override public io.justsearch.app.api.settings.QueryRoleSelection selection() {
          return prepared.selection();
        }
        @Override public io.justsearch.core.component.EngineComponentSnapshot.Component observation() {
          return prepared.observation();
        }
        @Override public void includeObservation(
            io.justsearch.core.component.EngineComponentSnapshot.Component unexpected) {
          throw new UnsupportedOperationException("Query owner has no generation projection");
        }
        @Override public void withOwnerLocks(Runnable publication) {
          prepared.withOwnerLocks(publication);
        }
        @Override public void validate() {
          if (server != current) throw new IllegalStateException("Physical encoder owner changed");
          prepared.validate();
        }
        @Override public void install() { prepared.install(); }
        @Override public void notifyObservers() { prepared.notifyObservers(); }
        @Override public void retire() { prepared.retire(); }
        @Override public void abort() { prepared.abort(); }
      };
    };
  }
  private volatile EngineKnowledgeClient client;
  private volatile LiveMigrationStartAttempt liveMigrationStartAttempt;
  private record LiveMigrationStartAttempt(
      String generation, java.util.concurrent.CompletableFuture<Boolean> completion) {}

  java.util.concurrent.CompletableFuture<Boolean> liveMigrationStartCompletionForTests(
      String expectedGeneration) {
    LiveMigrationStartAttempt attempt = liveMigrationStartAttempt;
    if (attempt == null || !expectedGeneration.equals(attempt.generation())) {
      throw new IllegalStateException("No live migration start for " + expectedGeneration);
    }
    return attempt.completion();
  }
  private final java.util.List<io.justsearch.app.api.indexing.ProjectionSeedSource>
      projectionSeedSources = new java.util.ArrayList<>();

  /** Bind a source owner before the index half can resume or create a candidate. */
  public synchronized void registerProjectionSeedSource(
      io.justsearch.app.api.indexing.ProjectionSeedSource source) {
    if (server != null || client != null) {
      throw new IllegalStateException("Projection seed source registration must precede Engine start");
    }
    Objects.requireNonNull(source, "source");
    String sourceId = source.sourceId();
    if (projectionSeedSources.stream().anyMatch(existing ->
        existing.sourceId().equals(sourceId))) {
      throw new IllegalArgumentException("Duplicate projection source identity");
    }
    if (sourceId == null || sourceId.isBlank() || sourceId.length() > 256
        || sourceId.chars().anyMatch(Character::isISOControl)
        || projectionSeedSources.size() >= 64
        || projectionSeedSources.stream().mapToInt(existing -> existing.sourceId().length()).sum()
            + sourceId.length() > 4096) {
      throw new IllegalArgumentException("Projection source identity must be bounded");
    }
    projectionSeedSources.add(source);
  }

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
    var gpuCapabilities = new io.justsearch.gpu.GpuCapabilitiesService();
    return new ServerFactory() {
      @Override
      public io.justsearch.configuration.resolved.ResolvedConfig captureConfiguration() {
        return authority.get().get();
      }

      @Override
      public KnowledgeServer create(GpuSchedulingGauge gauge,
          io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
          io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
          io.justsearch.core.component.ComponentHandle indexComponent,
          io.justsearch.core.component.ComponentHandle encoderComponent) {
        return create(gauge, executorRegistry, ingestion, indexComponent, encoderComponent,
            captureConfiguration());
      }

      @Override
      public KnowledgeServer create(GpuSchedulingGauge gauge,
          io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
          io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
          io.justsearch.core.component.ComponentHandle indexComponent,
          io.justsearch.core.component.ComponentHandle encoderComponent,
          io.justsearch.configuration.resolved.ResolvedConfig startupConfiguration) {
        var configStore = authority.get();
        WorkerConfig workerConfig = WorkerConfig.load(startupConfiguration);
        // Dev reload signals arrive under this runtime directory from the owning dev tool.
        return new KnowledgeServer(
            executorRegistry, workerConfig,
            new InProcessWorkerSignalBus(gauge, workerConfig.dataDir().resolve("runtime")),
            childRegistry, ingestion, indexComponent, encoderComponent, startupConfiguration,
            configStore::get, configStore.publicationLock(), () -> {
              var device = gpuCapabilities.snapshot().effective();
              return new io.justsearch.core.component.DeviceMemoryLine(
                  device.totalVramBytes(), device.freeVramBytes());
            });
      }
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
  public synchronized void prepareStart() {
    if (retainedIndexStartContext != null) return;
    io.justsearch.configuration.resolved.ResolvedConfig exactConfiguration =
        serverFactory.captureConfiguration();
    if (exactConfiguration != null) {
      retainedIndexStartContext = KnowledgeServer.IndexStartContext.unresolved(
          exactConfiguration, queryRoleBootSelection.get());
    }
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
    liveMigrationStartAttempt = null;
    prepareStart();
    KnowledgeServer.IndexStartContext startContext = retainedIndexStartContext;
    io.justsearch.configuration.resolved.ResolvedConfig exactConfiguration = startContext == null
        ? serverFactory.captureConfiguration() : startContext.configuration();
    io.justsearch.app.api.settings.QueryRoleSelection exactBootQuery = startContext == null
        ? queryRoleBootSelection.get() : startContext.bootQuerySelection();
    KnowledgeServer started;
    try {
      started = serverFactory.create(gpuScheduling, executors, recordedIngestion,
          indexComponent, encoderComponent, exactConfiguration);
    } catch (RuntimeException | Error constructorFailure) {
      // The exact pre-construction input remains available for a counted initial recovery.
      throw constructorFailure;
    }
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
      started.installProjectionSeedSources(projectionSeedSources);
      started.bindBootRootBindings(authority.roots().snapshotBindings());
      if (startContext != null) started.bindIndexStartContext(startContext);
      else started.bindBootQueryRoleSelection(exactBootQuery);
      started.start();
    } catch (IOException | RuntimeException | Error failure) {
      retainedIndexStartContext = started.failedIndexStartContext();
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
    built.bindProjectionSourceIds(projectionSeedSources.stream()
        .map(io.justsearch.app.api.indexing.ProjectionSeedSource::sourceId)
        .sorted().toList());
    built.bindLiveMigrationStarter(buildingGeneration ->
        liveMigrationStartAttempt = new LiveMigrationStartAttempt(buildingGeneration,
            started.beginUnrecordedBuildingLiveAsync(buildingGeneration,
                () -> requestRestart(started))));
    this.client = built;
    try {
      recordedIngestion.bindProducer(built::enumerateRecordedRoot);
      recordedIngestion.bindBulkProducer(built::enumerateCapturedRoots, built,
          () -> requestRestart(started),
          operationKey -> {
            try {
              liveMigrationStartAttempt = new LiveMigrationStartAttempt(
                  IndexGenerationManager.recordedGenerationId(operationKey),
                  started.beginRecordedBuildingLiveAsync(
                      operationKey, () -> requestRestart(started)));
            } catch (IOException invalid) {
              throw new IllegalStateException("Recorded bulk operation key is noncanonical", invalid);
            }
          });
      clientReady = true;
    } catch (RuntimeException | Error failure) {
      try { close(); }
      catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
      throw failure;
    }
    log.info("Engine composed the index half in-process (no worker process, no channel)");
    retainedIndexStartContext = null;
    return built;
  }

  @Override
  public io.justsearch.configuration.resolved.ResolvedConfig.FileSource bundledHelpSource() {
    KnowledgeServer current = server;
    return current == null ? null : current.bundledHelpSource();
  }

  @Override
  public WorkerHost.ServingLease captureServingView() {
    KnowledgeServer current = server;
    if (current == null) throw new IllegalStateException("Index serving owner is unavailable");
    var lease = current.captureServingView();
    return servingLease(lease);
  }

  @Override
  public WorkerHost.ServingLease captureStartupHealthView() {
    KnowledgeServer current = server;
    if (current == null) throw new IllegalStateException("Index serving owner is unavailable");
    return servingLease(current.captureStartupHealthView());
  }

  private static WorkerHost.ServingLease servingLease(KnowledgeServer.ServingLease lease) {
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
    closeIndex(true);
  }

  @Override
  public synchronized void closeForRecovery() {
    closeIndex(false);
  }

  /** One same-physical-context index recovery, called with Bootstrap.initLock already held. */
  @Override
  public synchronized io.justsearch.core.component.ComponentRecoveryAction.Result recoverIndex(
      io.justsearch.core.component.ComponentRecoveryAction.Request request,
      io.justsearch.app.services.worker.KnowledgeServerBootstrap.RecoveryBody body)
      throws Exception {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(body, "body");
    KnowledgeServer incumbent = server;
    KnowledgeServer.IndexStartContext context;
    if (incumbent != null) {
      var reserved = incumbent.reserveIndexRecovery(request);
      if (reserved.isEmpty()) {
        return request.admitted().isPresent()
            ? io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED
            : io.justsearch.core.component.ComponentRecoveryAction.Result.REFUSED;
      }
      context = reserved.orElseThrow();
    } else {
      context = retainedIndexStartContext;
      if (context == null || request.cancelled()
          || !Objects.equals(request.expected().appliedVersion(), context.priorAppliedDigest())
          || !request.begin()) {
        return io.justsearch.core.component.ComponentRecoveryAction.Result.REFUSED;
      }
      context = context.forRecovery(context.priorAppliedDigest());
    }
    retainedIndexStartContext = context;
    boolean healthy;
    try {
      healthy = body.run(() -> !request.cancelled());
    } catch (Exception | Error failure) {
      if (hasRecoverySupersession(failure)) {
        return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
      }
      return completeFailedRecovery(request, body, context, failure);
    }
    if (request.cancelled()) {
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    if (!healthy) {
      return completeFailedRecovery(request, body, context,
          new IOException("Recovered index did not become healthy"));
    }
    KnowledgeServer replacement = server;
    if (replacement == null || replacement == incumbent) {
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    long modelWaitMs = Math.max(1L, request.expected().spec().startDeadline().toMillis());
    if (!replacement.awaitIndexRecoveryQueryWitness(modelWaitMs)) {
      return completeFailedRecovery(request, body, context,
          new IOException("Recovered query selection did not finish"));
    }
    final java.util.Optional<KnowledgeServer.IndexStartContext> finalized;
    try {
      finalized = replacement.finalizeIndexRecovery(context);
    } catch (KnowledgeServer.IndexRecoveryWitnessException missingWitness) {
      return completeFailedRecovery(request, body, context, missingWitness);
    }
    if (finalized.isEmpty()) {
      retireSupersededReplacement();
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    KnowledgeServer.IndexStartContext actual = finalized.orElseThrow();
    if (!samePhysicalContext(context, actual)) {
      retireSupersededReplacement();
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    String physicalDigest = actual.priorAppliedDigest();
    if (context.priorAppliedDigest() != null
        && !context.priorAppliedDigest().equals(physicalDigest)) {
      retireSupersededReplacement();
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    var current = request.current();
    var admitted = request.admitted().orElse(null);
    if (!sameRecoveryLineage(admitted, current, context.priorAppliedDigest())) {
      retireSupersededReplacement();
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    try {
      body.preparePublication();
    } catch (Exception | Error failure) {
      return completeFailedRecovery(request, body, context, failure);
    }
    if (request.cancelled()) {
      retireSupersededReplacement();
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    current = request.current();
    admitted = request.admitted().orElse(null);
    if (!sameRecoveryLineage(admitted, current, context.priorAppliedDigest())) {
      retireSupersededReplacement();
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    if (context.priorAppliedDigest() == null) {
      current = installInitialRecoveredVersions(current, physicalDigest);
      if (current == null) {
        retireSupersededReplacement();
        return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
      }
    }
    try {
      replacement.armIndexRecoveryServing(current);
    } catch (KnowledgeServer.IndexRecoverySupersededException superseded) {
      retireSupersededReplacement();
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    java.util.Optional<io.justsearch.core.component.EngineComponentSnapshot.Component> completed;
    boolean servingAccepted = false;
    try {
      if (request.cancelled()) {
        return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
      }
      completed = request.complete(current,
          io.justsearch.core.component.ComponentState.READY, null, null);
      if (completed.isEmpty()) {
        return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
      }
      replacement.confirmIndexRecoveryServing(completed.orElseThrow());
      servingAccepted = true;
    } catch (KnowledgeServer.IndexRecoverySupersededException superseded) {
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    } finally {
      if (!servingAccepted) {
        replacement.disarmIndexRecoveryServing();
        retireSupersededReplacement();
      }
    }
    replacement.acceptIndexRecovery();
    return io.justsearch.core.component.ComponentRecoveryAction.Result.recovered(
        completed.orElseThrow());
  }

  /** Keeps the current index lifetime stable while its native model owners are replaced. */
  @Override
  public synchronized io.justsearch.core.component.ComponentRecoveryAction.Result recoverEncoders(
      io.justsearch.core.component.ComponentRecoveryAction.Request request) throws Exception {
    Objects.requireNonNull(request, "request");
    if (server == null || request.cancelled()) {
      return io.justsearch.core.component.ComponentRecoveryAction.Result.REFUSED;
    }
    return server.recoverEncoders(request);
  }

  private io.justsearch.core.component.ComponentRecoveryAction.Result completeFailedRecovery(
      io.justsearch.core.component.ComponentRecoveryAction.Request request,
      io.justsearch.app.services.worker.KnowledgeServerBootstrap.RecoveryBody body,
      KnowledgeServer.IndexStartContext context, Throwable failure) {
    var current = request.current();
    var admitted = request.admitted().orElse(null);
    if (!sameRecoveryLineage(admitted, current, context.priorAppliedDigest())) {
      return io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED;
    }
    var fatal = body.fatalReasonCode();
    String reason = fatal == null
        ? io.justsearch.app.api.lifecycle.LifecycleReasonCode.COMPONENT_RECOVERY_FAILED.code()
        : fatal.code();
    String evidence = fatal == null
        ? "Physical recovery failed: " + recoveryFailureDetail(failure) : body.fatalDetail();
    var completed = request.complete(current,
        io.justsearch.core.component.ComponentState.FAILED, reason, evidence);
    return completed.<io.justsearch.core.component.ComponentRecoveryAction.Result>map(
        io.justsearch.core.component.ComponentRecoveryAction.Result::failed)
        .orElse(io.justsearch.core.component.ComponentRecoveryAction.Result.SUPERSEDED);
  }

  private static String recoveryFailureDetail(Throwable failure) {
    String outer = failure.getMessage();
    String concrete = outer;
    Throwable current = failure;
    for (int depth = 0; depth < 32; depth++) {
      Throwable cause = current.getCause();
      if (cause == null || cause == current) break;
      current = cause;
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        concrete = current.getMessage();
      }
    }
    if (outer == null || outer.isBlank()) return concrete == null ? failure.toString() : concrete;
    return concrete == null || concrete.equals(outer) ? outer : outer + ": " + concrete;
  }

  private void retireSupersededReplacement() {
    try {
      closeIndex(false);
    } catch (RuntimeException incomplete) {
      log.warn("Superseded index replacement retained after incomplete close", incomplete);
    }
  }

  private static boolean hasRecoverySupersession(Throwable failure) {
    for (Throwable current = failure; current != null && current != current.getCause();
         current = current.getCause()) {
      if (current instanceof KnowledgeServer.IndexRecoverySupersededException) return true;
    }
    return false;
  }

  private boolean sameRecoveryLineage(
      io.justsearch.core.component.EngineComponentSnapshot.Component admitted,
      io.justsearch.core.component.EngineComponentSnapshot.Component current,
      String priorAppliedDigest) {
    return admitted != null && current != null && admitted.spec().equals(current.spec())
        && admitted.recoveryAttempts() == current.recoveryAttempts()
        && Objects.equals(admitted.desiredVersion(), current.desiredVersion())
        && Objects.equals(priorAppliedDigest, current.appliedVersion());
  }

  private static boolean samePhysicalContext(KnowledgeServer.IndexStartContext expected,
      KnowledgeServer.IndexStartContext actual) {
    return expected.configuration().equals(actual.configuration())
        && (expected.generationState() == null
            || expected.generationState().equals(actual.generationState()))
        && (expected.generationManifest() == null
            || expected.generationManifest().equals(actual.generationManifest()))
        && (expected.bootOwnership() == null
            || expected.bootOwnership().equals(actual.bootOwnership()))
        && (expected.bootDisposition() == null
            || expected.bootDisposition() == actual.bootDisposition())
        && (expected.queryObservation() == null
            || sameQueryContext(expected.queryObservation(), actual.queryObservation()));
  }

  private static boolean sameQueryContext(
      io.justsearch.indexerworker.server.InferenceSurface.ComponentObservation expected,
      io.justsearch.indexerworker.server.InferenceSurface.ComponentObservation actual) {
    if (expected == null || actual == null) return expected == actual;
    if (!expected.querySelection().equals(actual.querySelection())
        || !expected.requestedRoles().equals(actual.requestedRoles())) return false;
    var selection = expected.querySelection().orElseThrow();
    return expected.missingRoles().stream().allMatch(role -> switch (role) {
      case RERANKER -> selection.reranker().state()
          != io.justsearch.app.api.settings.QueryRoleSelection.State.DISABLED
          || actual.missingRoles().contains(role);
      case CITATION -> selection.citation().state()
          != io.justsearch.app.api.settings.QueryRoleSelection.State.DISABLED
          || actual.missingRoles().contains(role);
      default -> false;
    });
  }

  private io.justsearch.core.component.EngineComponentSnapshot.Component
      installInitialRecoveredVersions(
          io.justsearch.core.component.EngineComponentSnapshot.Component current,
          String physicalDigest) {
    if (physicalDigest == null) return null;
    var replacement = new io.justsearch.core.component.EngineComponentSnapshot.Component(
        current.spec(), current.state(), current.reasonCode(), current.stateSince(),
        current.stateSinceMonotonicNanos(), physicalDigest,
        current.desiredVersion() == null ? physicalDigest : current.desiredVersion(),
        current.lastCompose(), current.recoveryAttempts(), current.evidence());
    io.justsearch.core.component.EngineComponentRegistry.PreparedBatch prepared;
    var publication = publicationLock().writeLock();
    publication.lock();
    try {
      if (!current.equals(indexComponent.snapshot())) return null;
      prepared = indexComponent.prepareReplacement(replacement);
      prepared.validate();
      prepared.install();
    } finally {
      publication.unlock();
    }
    prepared.notifyObservers();
    return prepared.snapshot().components().stream()
        .filter(component -> component.spec().name().equals(current.spec().name()))
        .findFirst().orElseThrow();
  }

  private void closeIndex(boolean publishIndexStopped) {
    // Ordered shutdown closes admission and drains physical users before this index owner.
    // A direct close with live work cannot safely destroy its client/queue dependencies.
    if (admission.activeWorkCount() != 0
        || (attempts.isClosing() && !attempts.awaitDrained(java.time.Duration.ZERO))) {
      throw new IllegalStateException("Live Engine work retains the index owner");
    }
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
        if (publishIndexStopped) s.close();
        else s.closeForRecovery();
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
