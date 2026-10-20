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
  /**
   * Spec E (Switch A): the Engine's one bulk-work gate. Process-scoped and built before either half,
   * so it sees a generation from its first edge, outlives index restarts, and the index half's
   * pacing reads it as soon as that half is composed.
   */
  private final io.justsearch.core.scheduling.BulkWorkGate bulkWork =
      new io.justsearch.core.scheduling.BulkWorkGate();
  private final Object generationActivityLock = new Object();
  private io.justsearch.app.api.chat.GenerationActivity generationActivity; // Guarded by the lock.
  private AutoCloseable generationEdges; // Guarded by generationActivityLock.
  private final io.justsearch.core.component.EngineComponentRegistry components;
  private final io.justsearch.core.component.ComponentHandle indexComponent;
  private final io.justsearch.core.component.ComponentHandle encoderComponent;

  /** Process lifetime, deliberately independent of the restartable index-half close. */
  public io.justsearch.core.execution.EngineExecutorRegistry executors() { return executors; }

  /** Carries the pacing callback through existing bootstrap wiring without exposing the gauge. */
  private final class GenerationExecutors implements io.justsearch.core.execution.EngineExecutorRegistry,
      io.justsearch.app.services.bootstrap.BootstrapInferenceFactory.GenerationLifetimeSource {
    private final io.justsearch.core.execution.EngineExecutorRegistry delegate;

    private GenerationExecutors(io.justsearch.core.execution.EngineExecutorRegistry delegate) {
      this.delegate = delegate;
    }

    @Override public java.util.function.Function<io.justsearch.app.api.EngineWorkHandle, Runnable>
        generationLifetime() {
      return EngineRoot.this::beginGeneration;
    }

    @Override public void bindGenerationActivity(io.justsearch.app.api.chat.GenerationActivity activity) {
      EngineRoot.this.bindGenerationActivity(activity);
    }

    @Override public void bindPressureReceiver(
        java.util.function.Consumer<io.justsearch.app.api.chat.PressureSignal> receiver) {
      EngineRoot.this.bindPressureReceiver(receiver);
    }

    @Override public io.justsearch.app.api.chat.DeviceLostRecovery deviceLostRecovery() {
      return EngineRoot.this.boundDeviceLostRecovery;
    }

    @Override public void bindDeviceLoss(io.justsearch.app.api.chat.ChatDeviceLoss deviceLoss) {
      EngineRoot.this.bindDeviceLoss(deviceLoss);
    }

    @Override public void bindProfileReceiver(
        java.util.function.Consumer<io.justsearch.app.api.status.operating.Profile> receiver) {
      EngineRoot.this.bindProfileReceiver(receiver);
    }

    @Override public Registration register(io.justsearch.core.execution.EngineExecutorSpec spec) {
      return delegate.register(spec);
    }
    @Override public Limits limits(io.justsearch.core.execution.EngineExecutorSpec.Kind kind) {
      return delegate.limits(kind);
    }
    @Override public int maxConcurrentWork() { return delegate.maxConcurrentWork(); }
    @Override public int retryAfterSeconds() { return delegate.retryAfterSeconds(); }
    @Override public io.justsearch.core.execution.EngineExecutorSnapshot snapshot() {
      return delegate.snapshot();
    }
    @Override public void close() { delegate.close(); }
  }

  /**
   * A generation's lifetime retains its work until actual producer exit. Spec E step E2: it no
   * longer enters the search gauge; chat generation reaches indexing pacing through {@link
   * #bulkWork}'s duty floor instead (see {@link #bindGenerationActivity}).
   */
  private Runnable beginGeneration(io.justsearch.app.api.EngineWorkHandle work) {
    var retained = work.retain();
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    return () -> {
      if (closed.compareAndSet(false, true)) retained.close();
    };
  }

  /**
   * Spec E step E2: the chat server's generation edges drive the gate's generating input. The last
   * bound activity wins (a replaced inference manager rebinds). Each edge re-reads the activity
   * under one lock, so edges and the initial read cannot leave a stale value behind.
   */
  private void bindGenerationActivity(io.justsearch.app.api.chat.GenerationActivity activity) {
    Objects.requireNonNull(activity, "activity");
    synchronized (generationActivityLock) {
      closeGenerationEdges();
      generationActivity = activity;
      generationEdges = activity.onEdge((generating, epoch) -> mirrorGeneration(activity));
      bulkWork.setGenerating(activity.generating());
    }
  }

  private void mirrorGeneration(io.justsearch.app.api.chat.GenerationActivity activity) {
    synchronized (generationActivityLock) {
      if (generationActivity == activity) bulkWork.setGenerating(activity.generating());
    }
  }

  // Called under generationActivityLock.
  private void closeGenerationEdges() {
    AutoCloseable edges = generationEdges;
    generationEdges = null;
    if (edges == null) return;
    try {
      edges.close();
    } catch (Exception failure) {
      log.warn("Generation edge subscription did not close", failure);
    }
  }

  /** Spec E (Switch A): the Engine's one bulk-work gate, shared with the status surface. */
  @Override
  public io.justsearch.core.scheduling.BulkWorkGate bulkWork() {
    return bulkWork;
  }

  // ---- device loss (Spec A step 10, Spec E step E8) ----

  /** The restart path the composition root binds; fail closed until then. */
  private volatile io.justsearch.app.api.chat.DeviceLostRecovery deviceLostRecovery =
      io.justsearch.app.api.chat.DeviceLostRecovery.UNAVAILABLE;

  /** What the chat controller holds: it reads {@link #deviceLostRecovery} when it acts. */
  private final io.justsearch.app.api.chat.DeviceLostRecovery boundDeviceLostRecovery =
      new io.justsearch.app.api.chat.DeviceLostRecovery() {
        @Override
        public java.util.List<java.time.Instant> priorRestarts() throws IOException {
          return deviceLostRecovery.priorRestarts();
        }

        @Override
        public void reserveRestart() throws IOException {
          deviceLostRecovery.reserveRestart();
        }

        @Override
        public void requestRestart() {
          deviceLostRecovery.requestRestart();
        }

        @Override
        public void requestRestart(Runnable accepted, java.util.function.Consumer<RuntimeException> refused) {
          deviceLostRecovery.requestRestart(accepted, refused);
        }
      };

  private final Object deviceLossLock = new Object();
  // Guarded by deviceLossLock: the chat controller's device-loss input; the last bound wins.
  private io.justsearch.app.api.chat.ChatDeviceLoss deviceLoss;
  // Guarded by deviceLossLock: started with the first binding, closed with the process.
  private EncoderDeviceLossReporter encoderDeviceLoss;
  // Guarded by deviceLossLock: the machine's suspends and resumes (Spec F §3.5), bound once.
  private io.justsearch.app.api.runtime.power.SystemPowerEvents powerEvents;
  // Guarded by deviceLossLock: the resume subscription, made once both inputs are bound.
  private io.justsearch.app.api.runtime.power.SystemPowerEvents.Subscription resumeSubscription;

  /**
   * Spec A §7.4: the machine's power events, whose resumes reach the chat controller's {@code
   * reportResume}. Bound once by the composition root, before or after the controller binds its
   * input; the first binding wins. The subscription closes with the process resources.
   */
  public void bindPowerEvents(io.justsearch.app.api.runtime.power.SystemPowerEvents events) {
    Objects.requireNonNull(events, "events");
    synchronized (deviceLossLock) {
      if (powerEvents != null) return;
      powerEvents = events;
      subscribeResumeLocked();
    }
  }

  /** Subscribes once to resumes when both the power events and the controller's input are bound. */
  private void subscribeResumeLocked() {
    if (powerEvents == null || deviceLoss == null || resumeSubscription != null) return;
    resumeSubscription = powerEvents.subscribe(event -> {
      if (event.kind() != io.justsearch.app.api.runtime.power.PowerEvent.Kind.RESUME) return;
      io.justsearch.app.api.chat.ChatDeviceLoss bound;
      synchronized (deviceLossLock) {
        bound = deviceLoss;
      }
      bound.reportResume();
    });
    processResources.closeWithProcess(resumeSubscription);
  }

  /**
   * Spec A step 10: the Engine's device-lost restart path (its exit ledger and the requested
   * restart that records a {@code DEVICE_LOST} self-intent). Bound once by the composition root,
   * before the chat controller can act on a device loss.
   */
  public void bindDeviceLostRecovery(io.justsearch.app.api.chat.DeviceLostRecovery recovery) {
    deviceLostRecovery = Objects.requireNonNull(recovery, "recovery");
  }

  /**
   * Spec E §6.2 item 6: encoder device loss is reported to the chat controller's input, once per
   * Engine incarnation. The reporter starts with the first binding and follows a rebinding (a
   * replaced inference manager); it closes with the process resources.
   */
  private void bindDeviceLoss(io.justsearch.app.api.chat.ChatDeviceLoss target) {
    Objects.requireNonNull(target, "target");
    synchronized (deviceLossLock) {
      deviceLoss = target;
      subscribeResumeLocked();
      if (encoderDeviceLoss != null) return;
      encoderDeviceLoss = EncoderDeviceLossReporter.start(new io.justsearch.app.api.chat.ChatDeviceLoss() {
        @Override
        public void reportDeviceLost(io.justsearch.app.api.chat.DeviceLostEvidence evidence) {
          io.justsearch.app.api.chat.ChatDeviceLoss bound;
          synchronized (deviceLossLock) {
            bound = deviceLoss;
          }
          bound.reportDeviceLost(evidence);
        }

        @Override
        public void reportResume() {
          io.justsearch.app.api.chat.ChatDeviceLoss bound;
          synchronized (deviceLossLock) {
            bound = deviceLoss;
          }
          bound.reportResume();
        }
      });
      processResources.closeWithProcess(encoderDeviceLoss);
    }
  }

  private final Object residencyLock = new Object();
  private io.justsearch.app.services.gpu.GpuResidencySampler residency; // Guarded by residencyLock.
  // Guarded by residencyLock: a receiver bound before the sampler starts.
  private java.util.function.Consumer<io.justsearch.app.api.chat.PressureSignal> pressureReceiver;

  /**
   * Spec E step E3 (section 3.2): opens the Engine's one GPU memory reader and starts the residency
   * sampler on one Engine daemon thread with a 2 s fixed delay. The sampler raises and clears the
   * bulk work gate's external pressure and reports it to the chat controller; both close with the
   * process resources. Call once the GPU device is known. Idempotent.
   *
   * <p>Step E6: the reader opens after the device probe, with the pinned device (Spec E section 5:
   * its LUID and CUDA ordinal), so on several NVIDIA adapters it reads the one chat and the encoders
   * use. With nothing pinned, or a pinned device without a LUID, it opens with no device and maps a
   * single adapter itself.
   *
   * <p>Step E4: the sampler also polls the user's foreground context for game detection (Spec E
   * section 4), and takes its boot sample on this thread before it is scheduled, so a game already
   * running is on the gate and reported to the chat controller before chat first starts (CH7). If the
   * foreground reader cannot be opened, the sampler runs without game detection.
   */
  public void startGpuResidency() {
    synchronized (residencyLock) {
      if (residency != null) return;
    }
    var limits = executors.limits(io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND);
    var owner = executors.register(new io.justsearch.core.execution.EngineExecutorSpec(
        "engine.gpu-residency-sampler",
        io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.SCHEDULED, 1, limits.maxQueue(), 1));
    java.util.concurrent.ScheduledExecutorService scheduler;
    io.justsearch.app.api.gpu.GpuMemoryReader reader;
    try {
      scheduler = owner.openScheduled(
          Thread.ofPlatform().daemon().name("gpu-residency-sampler").factory());
      reader = io.justsearch.gpu.memory.WindowsGpuMemoryReader.open(
          io.justsearch.app.services.gpu.DeviceFactsSource.process().facts().pinnedGpuDevice(),
          java.time.Clock.systemUTC());
    } catch (RuntimeException | Error failure) {
      try {
        owner.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
    io.justsearch.app.api.gpu.UserContextReader userContext;
    try {
      userContext = io.justsearch.gpu.context.UserContextSignals.open();
    } catch (RuntimeException failure) {
      log.warn("The foreground reader did not open; game detection is off for this run", failure);
      startGpuResidency(reader, scheduler, owner);
      return;
    }
    startGpuResidency(reader, userContext, scheduler, owner);
  }

  /**
   * Starts the residency sampler over the given reader and scheduler, without game detection; the
   * sampler owns the reader. {@code schedulerOwner} closes after the sampler, with the process
   * resources.
   */
  void startGpuResidency(io.justsearch.app.api.gpu.GpuMemoryReader reader,
      java.util.concurrent.ScheduledExecutorService scheduler, AutoCloseable schedulerOwner) {
    startGpuResidency(reader, null, scheduler, schedulerOwner);
  }

  /**
   * Starts the residency sampler over the given readers and scheduler; the sampler owns both readers
   * ({@code userContext} may be null: no game detection). The boot sample runs on this thread before
   * the schedule starts. {@code schedulerOwner} closes after the sampler, with the process resources.
   */
  void startGpuResidency(io.justsearch.app.api.gpu.GpuMemoryReader reader,
      io.justsearch.app.api.gpu.UserContextReader userContext,
      java.util.concurrent.ScheduledExecutorService scheduler, AutoCloseable schedulerOwner) {
    var sampler = new io.justsearch.app.services.gpu.GpuResidencySampler(reader, userContext,
        this::gameDetectionEnabled,
        io.justsearch.app.services.gpu.GpuResidencySampler::engineAndDescendants, bulkWork,
        java.time.Clock.systemUTC(), scheduler);
    synchronized (residencyLock) {
      if (residency != null) {
        // Lost a start race: this one never ran, so it releases what it was given.
        sampler.close();
        try {
          schedulerOwner.close();
        } catch (Exception failure) {
          log.warn("GPU residency scheduler did not close", failure);
        }
        return;
      }
      residency = sampler;
      processResources.closeWithProcess(schedulerOwner);
      processResources.closeWithProcess(sampler);
      if (pressureReceiver != null) sampler.bindPressureReceiver(pressureReceiver);
      sampler.bootSample();
      sampler.start();
    }
  }

  /**
   * {@code justsearch.gpu.game_detection} from the current configuration, read on every sampler tick
   * (apply scope hot). Without a configuration (embedded tests), detection stays on.
   */
  private boolean gameDetectionEnabled() {
    io.justsearch.configuration.resolved.ResolvedConfig config = serverFactory.captureConfiguration();
    return config == null || config.ai() == null || !"off".equals(config.ai().gpuGameDetection());
  }

  /** Spec E step E3: the residency sampler, once {@link #startGpuResidency()} ran; else null. */
  @Override
  public io.justsearch.app.services.gpu.GpuResidencySampler gpuResidency() {
    synchronized (residencyLock) {
      return residency;
    }
  }

  /** The chat controller's pressure input; the last bound wins (a replaced manager rebinds). */
  private void bindPressureReceiver(
      java.util.function.Consumer<io.justsearch.app.api.chat.PressureSignal> receiver) {
    Objects.requireNonNull(receiver, "receiver");
    synchronized (residencyLock) {
      pressureReceiver = receiver;
      if (residency != null) residency.bindPressureReceiver(receiver);
    }
  }

  /**
   * Spec E step E7b: installs the device-memory planner for {@code store}'s configurations, before
   * the boot rebuild. Every prepared configuration then carries its plan at ordinal 150, and each
   * published one publishes that plan to the process's {@link
   * io.justsearch.app.services.gpu.DevicePlanState}. The planner reads the process's device probe and
   * model files only; it initializes no ONNX Runtime class.
   */
  public static void installDevicePlanning(io.justsearch.configuration.resolved.ConfigStore store) {
    installDevicePlanning(store,
        io.justsearch.app.services.gpu.GpuProfileResolver.planner(
            () -> io.justsearch.app.services.gpu.DeviceFactsSource.process().facts(),
            io.justsearch.indexerworker.server.InferenceCompositionRoot::gpuRoleWeightsMiB),
        io.justsearch.app.services.gpu.DevicePlanState.process());
  }

  /** {@link #installDevicePlanning(io.justsearch.configuration.resolved.ConfigStore)} over a given planner and state. */
  static void installDevicePlanning(io.justsearch.configuration.resolved.ConfigStore store,
      io.justsearch.app.services.config.ConfigStoreRebuilder.DevicePlanner planner,
      io.justsearch.app.services.gpu.DevicePlanState state) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(planner, "planner");
    Objects.requireNonNull(state, "state");
    io.justsearch.app.services.config.ConfigStoreRebuilder.rememberDevicePlanner(planner);
    store.addListener(event -> state.publish(planOf(event.current())));
  }

  private static io.justsearch.app.services.gpu.GpuProfileResolver.Resolution planOf(
      io.justsearch.configuration.resolved.ResolvedConfig config) {
    try {
      return io.justsearch.app.services.config.ConfigStoreRebuilder.planOf(config)
          .orElse(io.justsearch.app.services.gpu.GpuProfileResolver.Resolution.unresolved());
    } catch (RuntimeException failure) {
      log.warn("The device-memory plan did not resolve; the profile stays unresolved", failure);
      return io.justsearch.app.services.gpu.GpuProfileResolver.Resolution.unresolved();
    }
  }

  /**
   * Spec E step E7b: the bulk-work gate and the chat controller follow {@code state}'s profile from
   * now on (the current one is delivered at once).
   */
  public void followDevicePlan(io.justsearch.app.services.gpu.DevicePlanState state) {
    Objects.requireNonNull(state, "state");
    state.subscribe(resolution -> {
      java.util.function.Consumer<io.justsearch.app.api.status.operating.Profile> receiver;
      synchronized (profileLock) {
        devicePlan = resolution;
        receiver = profileReceiver;
      }
      bulkWork.setProfile(resolution.gateProfile());
      if (receiver != null) receiver.accept(resolution.statusProfile());
    });
  }

  /** The chat controller's profile input; the last bound wins (a replaced manager rebinds). */
  private void bindProfileReceiver(
      java.util.function.Consumer<io.justsearch.app.api.status.operating.Profile> receiver) {
    Objects.requireNonNull(receiver, "receiver");
    io.justsearch.app.services.gpu.GpuProfileResolver.Resolution plan;
    synchronized (profileLock) {
      profileReceiver = receiver;
      plan = devicePlan;
    }
    if (plan != null) receiver.accept(plan.statusProfile());
  }

  private final Object profileLock = new Object();
  private io.justsearch.app.services.gpu.GpuProfileResolver.Resolution devicePlan; // Guarded.
  private java.util.function.Consumer<io.justsearch.app.api.status.operating.Profile>
      profileReceiver; // Guarded by profileLock.

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

  @Override
  public io.justsearch.core.scheduling.UserActivity userActivity() {
    return admission.userActivity();
  }

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

    /**
     * Spec E step E3: the production factory composes the worker bus over the Engine's bulk work
     * gate, so {@code isBulkHeld()} and the {@code shouldYieldGpuBackfill()} fold read it. Test
     * factories that build their own index half ignore the gate.
     */
    default KnowledgeServer create(GpuSchedulingGauge gauge,
        io.justsearch.core.scheduling.BulkWorkGate bulkWork,
        io.justsearch.core.execution.EngineExecutorRegistry executors,
        io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
        io.justsearch.core.component.ComponentHandle indexComponent,
        io.justsearch.core.component.ComponentHandle encoderComponent,
        io.justsearch.configuration.resolved.ResolvedConfig exactConfiguration) {
      return create(gauge, executors, ingestion, indexComponent, encoderComponent,
          exactConfiguration);
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
        @Override public java.util.Optional<io.justsearch.core.component.ComposeEvidence> composition() {
          return java.util.Optional.of(prepared.composition());
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
        @Override public void abort(Throwable cause) { prepared.abort(cause); }
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
    return forProcess(operations, attempts, deadlineMs, batchSize, terminalWriterFaultAction,
        unrecorded(childRegistry), requestedRestartAction, authority, configStore,
        processResources);
  }

  /** The Engine's boot: helper children start and stop through its {@code childControl}. */
  public static EngineRoot forProcess(io.justsearch.app.api.operations.OperationStore operations,
      io.justsearch.app.api.operations.OperationAttemptRunner attempts, long deadlineMs, int batchSize,
      IntConsumer terminalWriterFaultAction,
      io.justsearch.app.api.runtime.child.ChildControl childControl,
      Runnable requestedRestartAction, io.justsearch.app.services.bootstrap.OperationAuthority authority,
      io.justsearch.configuration.resolved.ConfigStore configStore,
      DefaultEngineProcessResources processResources) {
    Objects.requireNonNull(childControl, "childControl");
    Objects.requireNonNull(configStore, "configStore");
    Objects.requireNonNull(processResources, "processResources");
    if (configStore.publicationLock() != processResources.publicationLock()) {
      throw new IllegalArgumentException("ConfigStore and process resources must share publication lock");
    }
    return new EngineRoot(operations, attempts, serverFactory(childControl, () -> configStore),
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

  /** Compositions without the Engine's boot: children are registered but not recorded. */
  private static io.justsearch.app.api.runtime.child.ChildControl unrecorded(
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    return io.justsearch.app.api.runtime.child.DefaultChildControl.unrecorded(
        Objects.requireNonNull(childRegistry, "childRegistry"));
  }

  private static ServerFactory serverFactory(
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry,
      java.util.function.Supplier<io.justsearch.configuration.resolved.ConfigStore> authority) {
    return serverFactory(unrecorded(childRegistry), authority);
  }

  private static ServerFactory serverFactory(
      io.justsearch.app.api.runtime.child.ChildControl childControl,
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
        return create(gauge, null, executorRegistry, ingestion, indexComponent, encoderComponent,
            startupConfiguration);
      }

      @Override
      public KnowledgeServer create(GpuSchedulingGauge gauge,
          io.justsearch.core.scheduling.BulkWorkGate bulkWork,
          io.justsearch.core.execution.EngineExecutorRegistry executorRegistry,
          io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
          io.justsearch.core.component.ComponentHandle indexComponent,
          io.justsearch.core.component.ComponentHandle encoderComponent,
          io.justsearch.configuration.resolved.ResolvedConfig startupConfiguration) {
        var configStore = authority.get();
        WorkerConfig workerConfig = WorkerConfig.load(startupConfiguration);
        // Dev reload signals arrive under this runtime directory from the owning dev tool. Spec E
        // step E3: the bus reads the Engine's bulk work gate (Spec E section 1.3 item 1).
        return new KnowledgeServer(
            executorRegistry, workerConfig,
            new InProcessWorkerSignalBus(
                gauge, workerConfig.dataDir().resolve("runtime"), bulkWork),
            childControl, ingestion, indexComponent, encoderComponent, startupConfiguration,
            configStore::get, configStore.publicationLock(), () -> {
              // Spec E step E6: admission stays a live NVML read, of the pinned device by its PCI
              // bus id; with nothing pinned it reads device 0 as before (one GPU: the same device).
              var device = gpuCapabilities.snapshotOn(
                  io.justsearch.app.services.gpu.DeviceFactsSource.process().facts()
                      .pinnedPciBusId().orElse(null)).effective();
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
    resources.retained().connect("representation-generations", () -> {
      KnowledgeServer owner = server;
      return owner == null ? null : owner.retainedGenerationCount();
    });
    resources.retained().connect("co-resident-encoders", () -> {
      KnowledgeServer owner = server;
      return owner == null ? null : owner.retainedEncoderCount();
    });
    this.admission = processResources.admission();
    this.executors = new GenerationExecutors(processResources.executors());
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
    processResources.closeWithProcess(io.justsearch.app.inference.ChatArtifactIdentity.start(
        processResources.executors()));
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
      started = serverFactory.create(gpuScheduling, bulkWork, executors, recordedIngestion,
          indexComponent, encoderComponent, exactConfiguration);
      started.bindRetainedStatePolicy(resources.retained().cap("representation-generations"),
          resources.retained().cap("co-resident-encoders"));
      started.bindBulkPacing(bulkWork);
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
    // THE gauge the indexing loop paces off, rather than IndexingPacing's pre-start orphan.
    ForegroundLoadGate gate = new ForegroundLoadGate(started.foregroundLoad());
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
          if (this.server == started) {
            this.server = null;
          }
        }
      }
      throw failure;
    }

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
      recordedIngestion.bindProducer(built::enumerateRecordedRoot,
          built::enumerateRecoveredRecordedRoot, built::enumerateWatchedRecordedRoot);
      recordedIngestion.bindBulkProducer(built::enumerateWatchedCapturedRoots, built,
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

  @Override
  public java.util.Optional<WorkerHost.ServingLease> tryCaptureHealthView() {
    KnowledgeServer current = server;
    if (current == null) throw new IllegalStateException("Index serving owner is unavailable");
    return current.tryCaptureHealthView().map(EngineRoot::servingLease);
  }

  @Override
  public boolean runtimeReloadInProgress() {
    KnowledgeServer current = server;
    return current != null && current.runtimeReloadInProgress();
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
        if (server == s) {
          server = null;
        }
      }
    }
  }

  /** Stop attachment producers before the process drains admitted work and closes Head. */
  public void quiesceProducers() {
    quiesceProducers(null);
  }

  /**
   * {@link #quiesceProducers()} within {@code timeout} (Spec F §4.3 QB2); null is this root's own
   * producer deadline. Producers that exit in time are handed off as before.
   */
  public void quiesceProducers(java.time.Duration timeout) {
    try {
      recordedIngestion.stopProducers(timeout == null ? deadlineMs : timeout.toMillis());
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
