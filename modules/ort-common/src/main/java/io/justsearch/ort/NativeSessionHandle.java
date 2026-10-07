/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import static io.justsearch.ort.SessionAcquireDeadlineExceededException.Phase.LOADING;

import io.justsearch.ort.telemetry.CpuRecreateCause;
import io.justsearch.ort.telemetry.FailureCause;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import io.justsearch.ort.telemetry.TransitionReason;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native implementation of {@link SessionHandle}: owns the ORT session lifecycle for GPU-capable
 * ONNX consumers. Renamed from {@code OrtSessionManager} in §14.23 (Phase B). The class still
 * holds the ~600 lines of lifecycle/concurrency machinery; only the name changed to reflect its
 * architectural role now that external callers only see {@link SessionHandle}.
 *
 * <p>Owns the CPU session, GPU session, GPU RunOptions, and OrtCudaStatus. Consumers delegate
 * all session selection, GPU arbitration, retry, and teardown to it.
 *
 * <p>Thread-safe: multiple threads may call {@link #acquire(SessionAcquisitionRequest)}
 * concurrently. GPU session creation uses double-checked locking with a dedicated lock object.
 *
 * <p>Construction is internal (§14.19 Phase 4): external callers must route through
 * {@link OrtSessionAssembler#buildManager}. {@link Builder} is package-private and only reachable
 * from the assembler + testFixtures. Java visibility replaces the pre-4 ArchUnit allowlist.
 * Post-§14.28 U1 the fallback-method family on {@link OrtSessionAssembler} is deleted; tests +
 * benchmarks that need a {@link SessionHandle} from raw inputs route through
 * {@code io.justsearch.ort.testing.InferenceCompositionRootTestHelper}.
 */
// Tempdoc 400 LR6-b: this class is covered by an invariant chain (single-
// construction via package-private Builder + assembler callpath, §14.27 /
// §14.28 U1). This class documents that invariant without an annotation.
// @BuildContract now lives in core-contracts, which ort-common already
// has as a compile-only dependency; no ipc-common dependency is needed.
public final class NativeSessionHandle implements SessionHandle {

  private static final Logger log = LoggerFactory.getLogger(NativeSessionHandle.class);

  // Tempdoc 400 LR2-b: lease.acquire span emission. Gated by detailed tracing
  // via the DETAILED_TRACING flag read once at class-load time (same env var
  // JUSTSEARCH_INDEX_TRACING_LEVEL that gates IndexingLoop.maybeSpan and
  // EncoderOrtRunSpans). Uses GlobalOpenTelemetry so it picks up whatever
  // TracingBootstrap was installed at worker startup.
  private static final Tracer LEASE_TRACER = GlobalOpenTelemetry.getTracer("ort.lease");

  private static final boolean LEASE_TRACING;

  static {
    String level;
    try {
      level =
          io.justsearch.configuration.EnvRegistry.INDEX_TRACING_LEVEL.getString("none");
    } catch (RuntimeException e) {
      // Defensive: if EnvRegistry can't resolve (e.g. stripped in a test
      // harness), fall back to disabled rather than throw at class load.
      level = "none";
    }
    LEASE_TRACING = !"none".equalsIgnoreCase(level);
  }

  /**
   * Default retry interval for GPU session re-creation after failure. Used by
   * {@link ModelSessionPolicy#forFallback} when callers don't supply a custom interval.
   */
  static final long DEFAULT_GPU_RETRY_INTERVAL_MS = 60_000L;

  /**
   * The longest a query that has a warm CPU session waits for a device seat before it runs on that
   * CPU session instead: 1 000 ms, S4's "a search waits at most about one second for any encoder on
   * the device". The wait is also at most half of the query's remaining budget, so the CPU encode
   * keeps the other half.
   */
  static final long CPU_RECOURSE_SEAT_WAIT_MS = 1_000L;

  private static final String ORT_VARIANT_ID = "onnxruntime-gpu";

  /**
   * The status reason of a role whose FP16 GPU session failed on a planned profile, where the FP32
   * file is not loaded on the GPU in its place (Spec E invariant 4).
   */
  public static final String PLANNED_FP32_GPU_REFUSED = "planned_profile_fp32_gpu_refused";

  // Configuration (final, set at construction)
  private final String consumerName;
  private final OrtEnvironment env;
  private final Path cpuModelPath;
  private final Path gpuModelPath; // nullable
  private final Path nativePath;
  private final GpuSessionConfig gpuConfig; // nullable = CPU-only
  private final boolean deferCpuSession;
  private final boolean gpuRetryEnabled;
  private final long gpuRetryIntervalMs;
  private final OptLevel cpuOptLevel;
  // Tempdoc 397 §14.24 FA + §14.26 T1-B: policy records drive the applier. runtime + policy
  // are required (assembler callers compose them via RuntimePolicyResolver or
  // ModelSessionPolicy.forFallback before calling the Builder).
  private final RuntimePolicy runtime;
  private final ModelSessionPolicy policy;
  private Runnable onBeforeGpuRelease; // nullable, mutable for late-binding
  // Tempdoc 710 Move 2: recording hook bound via setOrtRunRecorder, threaded into every
  // Lease produced by acquire()/acquireCpu() from this point forward. Volatile — bound once
  // at construction time (typically from the owning encoder's constructor, right after it
  // creates its EncoderProfileAccumulator) but read on every acquire() call, potentially from
  // a different thread.
  private volatile OrtRunRecorder ortRunRecorder = OrtRunRecorder.NOOP;
  // Tempdoc 414: typed lifecycle-event seam. NOOP when no adapter is wired (test contexts,
  // non-instrumented benchmarks). Production wires OrtSessionTelemetryAdapter via the Builder.
  private final OrtSessionTelemetryEvents events;

  // GPU inference serialization: prevents concurrent session.run() calls from
  // accumulating activation memory in the BFCArena beyond gpu_mem_limit.
  // Each concurrent run() creates its own ExecutionFrame with independent activation
  // buffers — all from the same arena. Spec D §3.2: the device's gate is the GPU permit, shared
  // with every other handle on the same device, so GPU runs are ordered (foreground before waiting
  // bulk work, with aging) across handles and not only within this one. CPU sessions remain fully
  // concurrent (not gated).
  private final DeviceEncoderGate gpuGate;
  // Spec E §6.2: whether the configured CUDA device is still usable by this process.
  private final DeviceHealth deviceHealth;

  // Spec D §1.5: the run threads of the device each lease runs on, shared with every other handle
  // on that device, so the cap on runs that may outlive their caller holds per device. A detached
  // GPU run keeps its device seat until it returns. The reranker has run threads of its own on each
  // device (see runThreads).
  private final DetachableRun.Device gpuRunThreads;
  private final DetachableRun.Device cpuRunThreads;

  // Mutable state (volatile, guarded by locks)
  private final ReentrantLock gpuSessionLock = new ReentrantLock();
  private final ReentrantLock cpuSessionLock = new ReentrantLock();
  private final Object lifecycleLock = new Object();
  private final Object closeLock = new Object();
  private final IdentityHashMap<OrtSession, SessionInstance> sessionInstances =
      new IdentityHashMap<>();
  private volatile OrtSession cpuSession; // null when deferred until first need
  private volatile OrtSession gpuSession;
  private volatile OrtSession.RunOptions gpuRunOptions;
  private volatile boolean gpuSessionAttempted;
  private volatile boolean gpuAvailable;
  private volatile boolean gpuSessionReleasing;
  private volatile long gpuReleaseEpoch;
  private volatile long gpuFailedAtMs;
  private volatile OrtCudaStatus ortCudaStatus;
  private final BooleanSupplier shouldUseGpu;
  private volatile boolean cpuSessionFailed; // set by reportCpuSessionFailure(), cleared on recreate
  // Tempdoc 414 A3: typed cause from the most recent reportCpuSessionFailure(cause) call.
  // Read by getCpuSession() when emitting CpuSessionRecreated; reset to UNKNOWN after consumption.
  private volatile CpuRecreateCause cpuFailureCause = CpuRecreateCause.UNKNOWN;
  private volatile boolean retired;
  private volatile RetirementStatus retirementStatus = RetirementStatus.ACTIVE;
  private int nativeActionsInProgress;

  // Spec D §4.2: warmth belongs to the GPU session generation. Creating or releasing the GPU
  // session moves gpuGeneration; a FOREGROUND run that was allowed to create sessions and succeeded
  // on the current generation sets gpuWarmGeneration. A GPU session whose generation is not warm is
  // never selected for a query that may not create sessions. Written under lifecycleLock.
  private volatile long gpuGeneration;
  private volatile long gpuWarmGeneration;
  // The CPU session instance that has completed a run: the warm CPU session a turned-away query
  // may use (see acquire). A recreated CPU session is not warm until it has run. Written under
  // lifecycleLock.
  private volatile OrtSession cpuWarmSession;
  // Notified after any session is created, released or reported failed (Spec D §4.2 item 2).
  private final List<Runnable> generationListeners = new CopyOnWriteArrayList<>();

  private static final long RETIRE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
  private static final long CANCELLATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  /** Lease accounting belongs to one exact native session identity. Guarded by lifecycleLock. */
  private static final class SessionInstance {
    private final OrtSession session;
    private int leases;
    private boolean unavailable;
    // Spec D §2.3: a GPU session whose release outwaited recompositionWaitMs is retired to the run
    // that holds it. The lease release that brings its leases to zero closes it, with the run
    // options it ran with.
    private boolean closeWhenReleased;
    private OrtSession.RunOptions retiredRunOptions;
    // Spec E invariant 5: counted in RetiredGpuSessions from its retirement until it is closed or
    // let go of with its device.
    private boolean retiredOpen;

    private SessionInstance(OrtSession session) {
      this.session = session;
    }
  }

  private NativeSessionHandle(Builder builder) throws OrtException {
    this.consumerName = builder.consumerName;
    // Reap prior runs' leaked ONNX Runtime native-lib extraction dirs before this JVM's own
    // extraction (triggered by getEnvironment below). ORT's cleanup is a shutdown hook that does
    // not run on force-kill, which this codebase does routinely — so reap at startup instead.
    OrtNativeTempReaper.reapStaleOnce();
    this.env = OrtEnvironment.getEnvironment();
    this.cpuModelPath = builder.cpuModelPath;
    this.gpuModelPath = builder.gpuModelPath;
    this.nativePath =
        builder.nativePath != null ? builder.nativePath : builder.cpuModelPath.getParent();
    this.onBeforeGpuRelease = builder.onBeforeGpuRelease;
    this.shouldUseGpu =
        builder.shouldUseGpu != null ? builder.shouldUseGpu : () -> false;
    this.events =
        builder.events != null ? builder.events : OrtSessionTelemetryEvents.NOOP;

    // Tempdoc 397 §14.26 T1-B: policy construction lives at the assembler boundary.
    // The handle requires a resolved RuntimePolicy + ModelSessionPolicy — the former flat
    // Builder fields (.gpuConfig, .deferCpuSession, .cpuOptLevel, .gpuRetryEnabled,
    // .gpuRetryIntervalMs) are gone. Post-§14.28 U1 the only session-construction entry is
    // OrtSessionAssembler.buildManager; test harnesses route via
    // io.justsearch.ort.testing.InferenceCompositionRootTestHelper (testFixtures).
    this.runtime = java.util.Objects.requireNonNull(builder.runtime, "runtime");
    this.policy = java.util.Objects.requireNonNull(builder.policy, "policy");

    // Derive scalar fields from the policy record (single source of truth).
    // Tempdoc 397 §14.28 U2: the policy record is now self-describing —
    // arenaCapBytes > 0 ⇔ session will run on GPU. ModelSessionPolicyResolver zeroes
    // arenaCapBytes for non-CUDA variants; ModelSessionPolicy.forFallback zeroes it for
    // null GpuSessionConfig. The dual-branch derivation that inspected policy.variant()
    // as a second source of truth is gone.
    long arenaCapBytes = policy.gpu().arenaCapBytes();
    this.gpuConfig =
        arenaCapBytes > 0L
            ? new GpuSessionConfig(policy.gpu().cudaDeviceId(), arenaCapBytes)
            : null;
    this.gpuGate = DeviceEncoderGates.forDevice(policy.gpu().cudaDeviceId());
    this.deviceHealth = DeviceHealth.forDevice(policy.gpu().cudaDeviceId());
    this.gpuRunThreads = runThreads(consumerName, "cuda:" + policy.gpu().cudaDeviceId());
    this.cpuRunThreads = runThreads(consumerName, "cpu");
    this.deferCpuSession = policy.lifecycle().deferCpuSession();
    this.gpuRetryEnabled = policy.lifecycle().gpuRetryEnabled();
    this.gpuRetryIntervalMs = policy.lifecycle().gpuRetryIntervalMs();
    this.cpuOptLevel = policy.cpu().optLevel();

    // Initialize GPU status
    if (gpuConfig == null) {
      this.ortCudaStatus = OrtCudaStatus.notConfigured();
    } else {
      this.ortCudaStatus = OrtCudaStatus.pending(ORT_VARIANT_ID, nativePath);
    }

    // Create CPU session (unless deferred)
    if (!deferCpuSession) {
      this.cpuSession = createCpuSession();
      synchronized (lifecycleLock) {
        sessionInstances.put(cpuSession, new SessionInstance(cpuSession));
      }
    }
    if (gpuConfig != null) {
      deviceHealth.register(this);
      RetiredGpuSessions.watch(this, gpuConfig.gpuDeviceId(), consumerName);
    }
  }

  /** True when this handle's GPU is configured and its device was lost in this process. */
  private boolean deviceLost() {
    return gpuConfig != null && deviceHealth.isLost();
  }

  /**
   * Spec E §6.2 items 3 and 5: the device was lost. The handle lets go of its GPU sessions without
   * closing them natively (a close on a lost CUDA context may hang; the process takes them along),
   * so no run is issued on them again and {@link #close()} leaves them alone. A run still in flight
   * on one releases its lease and device seat as usual. The session generation moves, so readiness
   * and prewarm see the CPU session as the query session from now on.
   */
  void onDeviceLost(int code) {
    int retiredLetGo = 0;
    synchronized (lifecycleLock) {
      List<SessionInstance> dropped = new java.util.ArrayList<>();
      sessionInstances.values().removeIf(instance -> {
        if (instance.session == cpuSession) return false;
        instance.unavailable = true;
        instance.closeWhenReleased = false;
        dropped.add(instance);
        return true;
      });
      for (SessionInstance instance : dropped) {
        if (instance.retiredOpen) {
          instance.retiredOpen = false;
          retiredLetGo++;
        }
      }
      gpuSession = null;
      gpuRunOptions = null;
      gpuAvailable = false;
      gpuGeneration++;
      ortCudaStatus =
          OrtCudaStatus.providerFailed(
              ORT_VARIANT_ID,
              nativePath,
              "CUDA device " + gpuConfig.gpuDeviceId() + " lost (CUDA error " + code + ")");
      lifecycleLock.notifyAll();
    }
    log.warn(
        "{}: CUDA device {} lost (CUDA error {}); GPU session dropped without a native close,"
            + " running on CPU until the Engine restarts",
        consumerName,
        gpuConfig.gpuDeviceId(),
        code);
    for (int i = 0; i < retiredLetGo; i++) {
      RetiredGpuSessions.closed(gpuConfig.gpuDeviceId(), consumerName);
    }
    fireGenerationChanged();
  }

  // ---------------------------------------------------------------------------
  // Session selection (primary API for inference)
  // ---------------------------------------------------------------------------

  /**
   * Selects the best available ORT session for inference.
   *
   * <p>Decision logic:
   *
   * <ol>
   *   <li>If the device was lost in this process ({@link DeviceHealth}), returns CPU session
   *   <li>If GPU is being released, returns CPU session
   *   <li>If GPU is not configured or arbitration says CPU, returns CPU session
   *   <li>If GPU not yet attempted, lazy-initializes under lock (double-checked)
   *   <li>If GPU failed and retry is enabled and 60s elapsed, re-attempts under lock
   *   <li>If GPU available, returns GPU session; otherwise returns CPU session
   * </ol>
   *
   * <p>A request that may not create sessions ({@link
   * SessionAcquisitionRequest#mayCreateGpuSession()} false: a query leg with a keyword fallback)
   * never creates or retries the GPU session and never selects a GPU session that is not warm; it
   * uses the CPU session instead, and reports {@code LOADING} when no CPU session exists yet
   * (Spec D §4.2 item 5).
   *
   * @return the session to use for this inference call (never null)
   */
  private OrtSession selectSession(SessionAcquisitionRequest request) {
    request.remainingNanos();
    // Spec E §6.2 item 2: a lost device is read first. Its requests run on CPU and never reach the
    // device's gate, so they neither wait for it nor meet its breaker.
    if (deviceLost()) {
      return cpuSessionFor(request);
    }
    long releaseEpoch = gpuReleaseEpoch;
    // Fast path: GPU session being released, use CPU for graceful degradation
    if (gpuSessionReleasing) {
      return cpuSessionFor(request);
    }

    // Fast path: GPU not configured or arbitration says use CPU
    if (gpuConfig == null || !shouldUseGpu.getAsBoolean()) {
      return cpuSessionFor(request);
    }

    // Spec E invariant 5: while this role's retired GPU session is open, no new GPU session.
    if (gpuSession == null && retiredGpuSessionOpen()) {
      return retiredSessionRecourse(request);
    }

    if (!request.mayCreateGpuSession()) {
      OrtSession warm = warmGpuSession();
      return warm != null ? warm : cpuSessionFor(request);
    }

    // Lazy GPU session initialization (double-checked locking)
    if (!gpuSessionAttempted) {
      acquireOwnerLock(gpuSessionLock, request, "GPU session creation");
      try {
        if (releaseEpoch != gpuReleaseEpoch || gpuSessionReleasing
            || !shouldUseGpu.getAsBoolean() || deviceLost()) return getCpuSession(request);
        if (retiredGpuSessionOpen()) return retiredSessionRecourse(request);
        if (!gpuSessionAttempted) {
          tryCreateGpuSession();
        }
      } finally {
        gpuSessionLock.unlock();
      }
    }

    // GPU retry: if creation failed >60s ago, allow one re-attempt
    if (gpuRetryEnabled && gpuSessionAttempted && !gpuAvailable && gpuFailedAtMs > 0
        && System.currentTimeMillis() - gpuFailedAtMs > gpuRetryIntervalMs) {
      acquireOwnerLock(gpuSessionLock, request, "GPU session retry");
      try {
        if (releaseEpoch != gpuReleaseEpoch || gpuSessionReleasing
            || !shouldUseGpu.getAsBoolean()) return getCpuSession(request);
        if (retiredGpuSessionOpen()) return retiredSessionRecourse(request);
        if (gpuSessionAttempted && !gpuAvailable) {
          long sinceFailureMs = System.currentTimeMillis() - gpuFailedAtMs;
          log.info(
              "{}: GPU retry — {}s since last failure, re-attempting",
              consumerName,
              sinceFailureMs / 1000);
          events.onTransition(new TransitionReason.GpuRetryAttempted(consumerName, sinceFailureMs));
          gpuSessionAttempted = false;
          gpuFailedAtMs = 0;
          tryCreateGpuSession();
        }
      } finally {
        gpuSessionLock.unlock();
      }
    }

    request.remainingNanos(LOADING);
    return gpuAvailable ? gpuSession : getCpuSession(request);
  }

  /** Whether a GPU session of this role, retired to its run, is still open on this device. */
  private boolean retiredGpuSessionOpen() {
    return RetiredGpuSessions.isOpen(gpuConfig.gpuDeviceId(), consumerName);
  }

  /**
   * The session for {@code request} while this role's retired GPU session is open (Spec E
   * invariant 5). A request that may create sessions (bulk work, a VECTOR-only leg, prewarm) runs on
   * the CPU session for as long as the retired run lasts: holding it would be a deferral. A query
   * leg takes the CPU session only when it is warm; otherwise it falls back to keyword search with
   * cause {@code LOADING}. Nothing is marked attempted, so the GPU session is created once the
   * retired one has closed.
   */
  private OrtSession retiredSessionRecourse(SessionAcquisitionRequest request) {
    if (request.mayCreateGpuSession() || isCpuWarm()) return getCpuSession(request);
    request.remainingNanos(LOADING);
    throw new SessionAcquireDeadlineExceededException(
        consumerName + ": retired GPU session still open; no warm CPU session", LOADING);
  }

  /** Called by {@link RetiredGpuSessions} when this role's last retired GPU session closed. */
  void retiredGpuSessionsClosed() {
    fireGenerationChanged();
  }

  /** The GPU session when it exists, is not being released and is warm; otherwise null. */
  private OrtSession warmGpuSession() {
    synchronized (lifecycleLock) {
      OrtSession session = gpuSession;
      boolean warm = session != null && gpuAvailable && !gpuSessionReleasing
          && gpuWarmGeneration == gpuGeneration;
      return warm ? session : null;
    }
  }

  /**
   * The CPU session for this request. A request that may not create sessions does not create a
   * deferred CPU session that was never created; it reports {@code LOADING} instead.
   */
  private OrtSession cpuSessionFor(SessionAcquisitionRequest request) {
    if (!request.mayCreateGpuSession() && cpuSession == null) {
      request.remainingNanos(LOADING);
      throw new SessionAcquireDeadlineExceededException(
          consumerName + ": no warm query session yet", LOADING);
    }
    return getCpuSession(request);
  }

  /**
   * The device a query (a request that may not create sessions) selects now: {@code GPU} only when
   * arbitration allows the GPU and its session exists and is warm (Spec D §1.1, §4.2).
   */
  @Override
  public QueryDevice queryDevice() {
    if (gpuConfig == null || deviceLost() || !shouldUseGpu.getAsBoolean()) return QueryDevice.CPU;
    return warmGpuSession() != null ? QueryDevice.GPU : QueryDevice.CPU;
  }

  /**
   * Whether a query would run now without creating a session (Spec D §4.2 item 3). When the GPU is
   * configured, allowed by arbitration and has not failed, that is a warm GPU session; otherwise it
   * is an existing, healthy CPU session. A CPU session does not need a prior run.
   */
  @Override
  public boolean isQueryWarm() {
    if (retired) return false;
    boolean gpuPath = gpuConfig != null && !deviceLost() && shouldUseGpu.getAsBoolean()
        && !gpuSessionReleasing && !(gpuSessionAttempted && !gpuAvailable);
    if (gpuPath) return warmGpuSession() != null;
    return cpuSession != null && !cpuSessionFailed;
  }

  @Override
  public void addGenerationListener(Runnable listener) {
    generationListeners.add(java.util.Objects.requireNonNull(listener, "listener"));
  }

  /** Listeners only schedule work; a failing listener does not affect the session change. */
  private void fireGenerationChanged() {
    for (Runnable listener : generationListeners) {
      try {
        listener.run();
      } catch (RuntimeException e) {
        log.warn("{}: session generation listener failed", consumerName, e);
      }
    }
  }

  /**
   * The recorder for a lease on {@code session}. A FOREGROUND GPU run that was allowed to create
   * sessions marks the generation it was selected on warm when it succeeds; a run on a replaced
   * session marks nothing.
   */
  private OrtRunRecorder recorderFor(
      SessionAcquisitionRequest request, OrtSession session, long generation) {
    OrtRunRecorder base = ortRunRecorder;
    if (request.urgency() != SessionAcquisitionRequest.Urgency.FOREGROUND
        || !request.mayCreateGpuSession()) {
      return base;
    }
    return elapsedNanos -> {
      synchronized (lifecycleLock) {
        if (gpuSession == session && gpuGeneration == generation) gpuWarmGeneration = generation;
      }
      base.recordOrtRunNs(elapsedNanos);
    };
  }

  // ---------------------------------------------------------------------------
  // Exclusive GPU access (prevents concurrent BFCArena exhaustion)
  // ---------------------------------------------------------------------------

  /**
   * Acquires the best session with exclusive GPU access. Returns a {@link SessionHandle.Lease}
   * that MUST be closed after inference completes (use try-with-resources).
   *
   * <p>If the GPU session is selected, the caller holds a seat of the device's {@link
   * DeviceEncoderGate} until the lease is closed: with the default single seat, no other GPU run on
   * the device (from this or any other handle) runs meanwhile, and waiters are seated foreground
   * first, with aging (Spec D §3.2). CPU sessions are returned without a seat (fully concurrent).
   *
   * <p>A foreground request with a semantic deadline whose handle has a warm CPU session (see
   * {@link #prewarmCpuForQueries}) is not left without semantics by a busy or stuck device: while
   * the device's breaker is open, or when no seat comes within {@link #CPU_RECOURSE_SEAT_WAIT_MS}
   * (at most half its remaining budget), it gets a CPU lease and runs within what is left of its
   * budget. A free seat is still taken first, so an idle device serves it on the GPU.
   *
   * <p>This prevents concurrent {@code session.run()} calls from accumulating activation memory in
   * the BFCArena beyond the {@code gpu_mem_limit}. Each {@code run()} creates its own
   * ExecutionFrame with independent activation buffers — serializing GPU runs keeps the arena
   * within its budget.
   *
   * @return a lease that provides the session, RunOptions, and releases its seat when closed
   */
  @Override
  public Lease acquire(SessionAcquisitionRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    // Tempdoc 400 LR2-b: lease.acquire span captures semaphore wait vs run
    // split. Span becomes a child of whatever OTel context is current when
    // acquire() is called (encoder.ort_run in backfill; enrichment.batch or
    // no parent elsewhere). Zero-cost when LEASE_TRACING is false.
    Span leaseSpan = maybeLeaseSpan();
    try {
      ensureNotRetired();
      OrtSession session = selectSession(request);
      long selectedGeneration = gpuGeneration;
      if (session == gpuSession && session != null) {
        leaseSpan.setAttribute("lease.wait_queue_depth", (long) gpuGate.waiting());
        boolean cpuRecourse = hasCpuRecourse(request);
        if (cpuRecourse && gpuGate.breakerOpenSinceNanos().isPresent()) {
          return cpuRecourseLease(request, leaseSpan, "stuck");
        }
        // Tempdoc 414 A1: time only the wait for the device seat — that's what
        // ort.session.semaphore_wait_us is supposed to measure. Microsecond
        // resolution captures the no-contention fast path.
        long waitStartNs = System.nanoTime();
        DeviceEncoderGate.Seat seat = null;
        try {
          try {
            seat = gpuGate.seat(session, cpuRecourse ? seatWaitOf(request) : request);
          } catch (SessionAcquireDeadlineExceededException turnedAway) {
            if (!cpuRecourse) throw turnedAway;
            return cpuRecourseLease(
                request, leaseSpan, turnedAway.breakerOpen() ? "stuck" : "contended");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancellation =
                new CancellationException("GPU lease acquisition interrupted");
            cancellation.initCause(interrupted);
            throw cancellation;
          }
          request.remainingNanos();
          // An interrupt can race with a successful seating. Do not construct a lease for a
          // cancelled caller; the finally block releases this seat, while an already-issued
          // lease remains responsible for its own seat until its native call has exited.
          if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("GPU lease acquisition interrupted");
          }
          long waitUs = (System.nanoTime() - waitStartNs) / 1_000L;
          events.onSemaphoreWait(consumerName, waitUs);
          request.remainingNanos();
          if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("GPU lease acquisition interrupted");
          }
          // Re-check after seating: GPU may have been released while we waited
          SessionInstance instance;
          synchronized (lifecycleLock) {
            instance = retainIfAvailable(session, session == gpuSession && !gpuSessionReleasing);
          }
          if (instance == null) {
            seat.close();
            seat = null;
            leaseSpan.setAttribute("lease.mode", "cpu");
            // Tempdoc 414: silent line-260 fallback now first-class.
            events.onTransition(new TransitionReason.GpuFallbackTaken(consumerName));
            return acquireCpuLease(request, cpuSessionFor(request));
          }
          leaseSpan.setAttribute("lease.mode", "gpu");
          Lease lease =
              new Lease(
                  session,
                  gpuRunOptions,
                  new DetachableRun.Release(idempotentRelease(instance, seat::close), gpuRunThreads),
                  /* isCpu= */ false,
                  recorderFor(request, session, selectedGeneration),
                  request,
                  gpuConfig.gpuDeviceId());
          seat = null;
          return lease;
        } finally {
          if (seat != null) {
            // No Lease owns this seat on an exceptional path. An issued lease reaches this point
            // with seat=null and remains responsible for its own release.
            seat.close();
          }
        }
      }
      leaseSpan.setAttribute("lease.mode", "cpu");
      return acquireCpuLease(request, session);
    } finally {
      leaseSpan.end();
    }
  }

  /**
   * Whether {@code request} may run on the CPU session when the device cannot seat it within its
   * budget: a foreground request with a semantic deadline (a hybrid query leg with a keyword
   * fallback, a VECTOR-only leg, a RAG retrieval embed), on a handle whose CPU session is warm.
   * Bulk work (background), prewarm and the reranker (no semantic deadline) never are.
   */
  private boolean hasCpuRecourse(SessionAcquisitionRequest request) {
    if (request.urgency() != SessionAcquisitionRequest.Urgency.FOREGROUND
        || !request.releaseCallerAtDeadline()) {
      return false;
    }
    return isCpuWarm();
  }

  /** Whether the current CPU session exists, has not been reported failed and has run. */
  private boolean isCpuWarm() {
    OrtSession session = cpuSession;
    return session != null && !cpuSessionFailed && cpuWarmSession == session;
  }

  /**
   * The request a query with CPU recourse waits for a device seat under: its own, with a deadline
   * of at most {@link #CPU_RECOURSE_SEAT_WAIT_MS} and at most half of its remaining budget.
   */
  private static SessionAcquisitionRequest seatWaitOf(SessionAcquisitionRequest request) {
    long now = System.nanoTime();
    long wait =
        Math.min(
            (request.deadlineNanos() - now) / 2,
            TimeUnit.MILLISECONDS.toNanos(CPU_RECOURSE_SEAT_WAIT_MS));
    return new SessionAcquisitionRequest(
        request.urgency(),
        now + wait,
        request.cancellationRequested(),
        request.mayCreateGpuSession(),
        request.releaseCallerAtDeadline());
  }

  /**
   * A query the device turned away (its breaker is open: {@code stuck}; or no seat within the
   * seat wait: {@code contended}) runs on the warm CPU session within its remaining budget, through
   * the CPU selection a lost device uses. Its lease is a CPU lease, so the encoder's span records
   * the CPU; a CPU run that also misses the budget is the query's ordinary deadline miss.
   */
  private Lease cpuRecourseLease(SessionAcquisitionRequest request, Span leaseSpan, String cause) {
    request.remainingNanos();
    // The exported trace records the device used: lease.mode on the lease span, encoder.gpu=false
    // on the encoder's ort_run span (it is a CPU lease), and this event on the caller's span.
    leaseSpan.setAttribute("lease.mode", "cpu");
    Span current = Span.current();
    if (current.isRecording()) {
      current.addEvent(
          "cpu_fallback.triggered",
          io.opentelemetry.api.common.Attributes.of(
              io.opentelemetry.api.common.AttributeKey.stringKey("fallback.cause"),
              "device_" + cause,
              io.opentelemetry.api.common.AttributeKey.stringKey("fallback.encoder"),
              consumerName));
    }
    log.debug("{}: device {} {}; query runs on the warm CPU session", consumerName,
        gpuConfig.gpuDeviceId(), cause);
    return acquireCpuLease(request, cpuSessionFor(request));
  }

  /**
   * Makes this handle's CPU session warm for queries the device turns away (see {@link #acquire}):
   * creates the CPU session if it was deferred and runs it once on a short synthetic input. Does
   * nothing on a handle without a GPU, where the CPU session is the query session itself.
   *
   * @return whether the CPU session is warm afterwards
   */
  /**
   * The current plan of this handle's device (Spec E revision 4 section 2.2, step E7b), when the
   * handle runs on the GPU on a planned profile; empty otherwise, so calls keep today's shape. The
   * plan is limited to this session's own arena cap: a plan whose budget fell applies to the next
   * call at once, one whose budget rose only after the role is recomposed with the higher cap.
   */
  @Override
  public java.util.Optional<GpuArenaPlan> arenaPlan() {
    if (gpuConfig == null || !policy.gpu().plannedProfile()) return java.util.Optional.empty();
    EncoderRole role = roleOf(consumerName);
    java.util.Optional<GpuArenaPlan> plan = DeviceArenaPlans.current(gpuConfig.gpuDeviceId());
    if (role == null) return plan;
    double capMiB = gpuConfig.gpuMemLimitBytes() / (1024.0 * 1024.0);
    return plan.map(p -> p.withRoleCapAtMost(role, capMiB));
  }

  private static EncoderRole roleOf(String consumerName) {
    for (EncoderRole role : EncoderRole.values()) {
      if (role.consumerName().equals(consumerName)) return role;
    }
    return null;
  }

  @Override
  public boolean prewarmCpuForQueries(SessionAcquisitionRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    if (gpuConfig == null || retired) return false;
    if (isCpuWarm()) return true;
    long start = System.nanoTime();
    try (Lease lease = acquireCpuLease(request)) {
      long created = System.nanoTime();
      java.util.Map<String, ai.onnxruntime.OnnxTensor> inputs = syntheticInputs(lease.session());
      if (inputs == null) {
        log.info("{}: CPU session has inputs other than token ids; not warmed for queries",
            consumerName);
        return false;
      }
      try (OrtSession.Result ignored = lease.run(inputs)) {
        log.info(
            "{}: CPU session warm for turned-away queries in {} ms (session {} ms, first run {} ms)",
            consumerName,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            TimeUnit.NANOSECONDS.toMillis(created - start),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - created));
      } finally {
        inputs.values().forEach(ai.onnxruntime.OnnxTensor::close);
      }
    } catch (OrtException | RuntimeException e) {
      log.warn("{}: CPU session not warmed for turned-away queries: {}", consumerName, e.toString());
      return false;
    }
    return isCpuWarm();
  }

  /**
   * A one-row, {@value #SYNTHETIC_TOKENS}-token input for a token-id model (every input a 2-D
   * int64 tensor: ids, attention mask, token types), or null for any other model.
   */
  private java.util.Map<String, ai.onnxruntime.OnnxTensor> syntheticInputs(OrtSession session)
      throws OrtException {
    java.util.Map<String, ai.onnxruntime.OnnxTensor> inputs = new java.util.LinkedHashMap<>();
    try {
      for (var entry : session.getInputInfo().entrySet()) {
        if (!(entry.getValue().getInfo() instanceof ai.onnxruntime.TensorInfo info)
            || info.type != ai.onnxruntime.OnnxJavaType.INT64
            || info.getShape().length != 2) {
          inputs.values().forEach(ai.onnxruntime.OnnxTensor::close);
          return null;
        }
        long[] row = new long[SYNTHETIC_TOKENS];
        java.util.Arrays.fill(row, entry.getKey().contains("mask") ? 1L : 0L);
        inputs.put(entry.getKey(), ai.onnxruntime.OnnxTensor.createTensor(env, new long[][] {row}));
      }
    } catch (OrtException | RuntimeException e) {
      inputs.values().forEach(ai.onnxruntime.OnnxTensor::close);
      throw e;
    }
    return inputs.isEmpty() ? null : inputs;
  }

  private static final int SYNTHETIC_TOKENS = 16;

  private static Span maybeLeaseSpan() {
    if (!LEASE_TRACING) {
      return Span.getInvalid();
    }
    return LEASE_TRACER.spanBuilder("lease.acquire").startSpan();
  }

  private static void acquireOwnerLock(
      ReentrantLock lock, SessionAcquisitionRequest request, String action) {
    while (true) {
      long waitNanos = Math.min(request.remainingNanos(LOADING), CANCELLATION_POLL_NANOS);
      try {
        if (lock.tryLock(waitNanos, TimeUnit.NANOSECONDS)) return;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        CancellationException cancellation =
            new CancellationException("native session acquisition interrupted during " + action);
        cancellation.initCause(interrupted);
        throw cancellation;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GPU lifecycle management
  // ---------------------------------------------------------------------------

  /**
   * Releases the GPU session to free VRAM while preserving CPU fallback.
   *
   * <p>Thread-safe: concurrent {@link #acquire(SessionAcquisitionRequest)} calls will gracefully
   * fall back to CPU during release.
   *
   * <p>The release never waits without a bound (Spec D §2.3). It waits at most {@link
   * DeviceGatePolicy#recompositionWaitMs()} for the session's in-flight runs, and stops waiting if
   * the releasing thread is interrupted. Past that, the session is retired to the run that holds
   * it: the handle lets go of it at once, and the lease release that ends its last run closes it
   * (the release callback, its run options, then the session). A session is never closed during a
   * run. Its GPU memory stays allocated until that run returns.
   */
  @Override
  public void releaseGpu() {
    synchronized (lifecycleLock) {
      // A lost device's GPU sessions were already let go of (onDeviceLost); none is closed natively.
      if (retired || deviceLost()) return;
      nativeActionsInProgress++;
    }
    // Serialize with creation/publication and other releases. Queued creators from before this
    // handoff must fall back to CPU even after the release-in-progress flag clears.
    gpuSessionLock.lock();
    gpuReleaseEpoch++;
    gpuSessionReleasing = true;
    boolean releaseSucceeded = false;
    try {
      // No new GPU lease is issued on the session from here on (gpuSessionReleasing, and the
      // instance is unavailable). Wait, with a bound, for the leases already issued: no in-flight
      // GPU inference may be using pinned output tensors or the session when they are torn down.
      OrtSession releasing = gpuSession;
      if (releasing != null && !awaitGpuRunsOrRetire(releasing)) {
        log.warn(
            "{}: GPU session still running after {} ms; retired to its run, which closes it when"
                + " the native call returns (its GPU memory stays allocated until then)",
            consumerName,
            gpuGate.policy().recompositionWaitMs());
        gpuSession = null;
        gpuSessionAttempted = false;
        gpuAvailable = false;
        gpuFailedAtMs = 0;
        ortCudaStatus = OrtCudaStatus.released(ORT_VARIANT_ID, nativePath);
        return;
      }
      if (onBeforeGpuRelease != null) {
        try {
          onBeforeGpuRelease.run();
        } catch (RuntimeException e) {
          log.warn("{}: onBeforeGpuRelease callback failed", consumerName, e);
        }
      }

      OrtSession session = gpuSession;
      if (session != null) {
        SessionInstance instance;
        synchronized (lifecycleLock) {
          instance = instanceFor(session);
          instance.unavailable = true;
        }
        boolean closedSession = false;
        try {
          if (!closeGpuRunOptions()) return;
          session.close();
          closedSession = true;
        } catch (OrtException e) {
          log.warn("{}: GPU session close failed; resource retained for retry", consumerName, e);
        } finally {
          synchronized (lifecycleLock) {
            if (closedSession) {
              sessionInstances.remove(session);
              gpuGeneration++;
            }
          }
        }
        if (!closedSession) {
          gpuAvailable = false;
          return;
        }
        gpuSession = null;
      }

      gpuSessionAttempted = false;
      gpuAvailable = false;
      gpuFailedAtMs = 0;
      ortCudaStatus = OrtCudaStatus.released(ORT_VARIANT_ID, nativePath);
      log.info("{}: GPU session released (yielding VRAM)", consumerName);
      releaseSucceeded = true;
    } finally {
      gpuSessionReleasing = false;
      gpuSessionLock.unlock();
      synchronized (lifecycleLock) {
        nativeActionsInProgress--;
        lifecycleLock.notifyAll();
      }
      if (releaseSucceeded) {
        events.onTransition(new TransitionReason.GpuReleaseCompleted(consumerName));
      } else {
        events.onTransition(new TransitionReason.GpuReleaseFailed(consumerName));
      }
      fireGenerationChanged();
    }
  }

  /**
   * Makes {@code session} unavailable to new leases and waits for its issued leases, at most {@link
   * DeviceGatePolicy#recompositionWaitMs()} and only until the waiting thread is interrupted (whose
   * interrupt is kept). Returns whether they were all released. If not, the session is retired to
   * its last lease: it leaves the handle's GPU state now, with its run options, and that lease's
   * release closes it. Called by {@link #releaseGpu()} under {@code gpuSessionLock}.
   */
  private boolean awaitGpuRunsOrRetire(OrtSession session) {
    long deadline =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(gpuGate.policy().recompositionWaitMs());
    synchronized (lifecycleLock) {
      SessionInstance instance = instanceFor(session);
      instance.unavailable = true;
      while (instance.leases != 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) break;
        try {
          TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      if (instance.leases == 0) return true;
      instance.closeWhenReleased = true;
      instance.retiredOpen = true;
      RetiredGpuSessions.opened(gpuConfig.gpuDeviceId(), consumerName);
      instance.retiredRunOptions = gpuRunOptions;
      gpuRunOptions = null;
      gpuGeneration++;
      return false;
    }
  }

  /**
   * Closes a GPU session that {@link #releaseGpu()} retired to its run, once that run's lease has
   * released it: the release callback first (the run is over, so nothing uses the resources it
   * frees), then the run options the session ran with, then the session. Runs on the thread that
   * released the last lease, before that lease frees its device seat.
   */
  private void closeRetiredGpuSession(SessionInstance instance) {
    try {
      Runnable callback = onBeforeGpuRelease;
      if (callback != null) {
        try {
          callback.run();
        } catch (RuntimeException e) {
          log.warn("{}: onBeforeGpuRelease callback failed", consumerName, e);
        }
      }
      boolean closed = false;
      boolean wasRetiredOpen = false;
      try {
        if (instance.retiredRunOptions != null) instance.retiredRunOptions.close();
        instance.session.close();
        closed = true;
        log.info("{}: retired GPU session closed after its run returned", consumerName);
      } catch (Exception e) {
        log.warn("{}: retired GPU session close failed; resource retained", consumerName, e);
      } finally {
        if (closed) {
          synchronized (lifecycleLock) {
            sessionInstances.remove(instance.session);
            wasRetiredOpen = instance.retiredOpen;
            instance.retiredOpen = false;
          }
        }
      }
      if (wasRetiredOpen) RetiredGpuSessions.closed(gpuConfig.gpuDeviceId(), consumerName);
    } finally {
      synchronized (lifecycleLock) {
        nativeActionsInProgress--;
        lifecycleLock.notifyAll();
      }
    }
  }

  /**
   * Replaces the GPU release callback. Used for late-binding wiring when the callback owner is not
   * available at construction time (e.g., composition root creates the session manager before the
   * encoder that owns the callback).
   */
  @Override
  public void setLifecycleCallback(GpuLifecycleCallback callback) {
    this.onBeforeGpuRelease = callback == null ? null : callback::onBeforeRelease;
  }

  /**
   * Replaces the ORT-run recording hook (tempdoc 710 Move 2). Used for the same late-binding
   * reason as {@link #setLifecycleCallback}: the composition root builds the session handle
   * before the encoder that owns the {@code EncoderProfileAccumulator} exists.
   */
  @Override
  public void setOrtRunRecorder(OrtRunRecorder recorder) {
    this.ortRunRecorder = recorder == null ? OrtRunRecorder.NOOP : recorder;
  }

  // ---------------------------------------------------------------------------
  // CPU session failure recovery (D9: dead BFCArena allocations)
  // ---------------------------------------------------------------------------

  /**
   * Reports that the CPU session experienced an inference failure (e.g., OOM from quadratic
   * attention on long inputs). The next call to {@link #acquire(SessionAcquisitionRequest)} will
   * close the failed session and create a fresh one, releasing the dead BFCArena allocations.
   *
   * <p>This uses a deferred-recreation pattern to avoid threading races: the old session stays
   * alive until the next {@code selectSession()} call replaces it under the lock. No thread can
   * hold a dangling reference because the replacement happens at the entry point, not mid-inference.
   */
  @Override
  public void reportCpuSessionFailure(CpuRecreateCause cause) {
    synchronized (lifecycleLock) {
      cpuFailureCause = cause != null ? cause : CpuRecreateCause.UNKNOWN;
      cpuSessionFailed = true;
      SessionInstance failed = sessionInstances.get(cpuSession);
      if (failed != null) failed.unavailable = true;
      lifecycleLock.notifyAll();
    }
    log.warn(
        "{}: CPU session failure reported (cause={}) — will recreate on next use",
        consumerName,
        cpuFailureCause);
    fireGenerationChanged();
    // Tempdoc 400 LR2-c: emit cpu_fallback.triggered span event on the current
    // span (if any). Callers have typically already set up an encoder.ort_run
    // span, so the event shows up on that span's event list. Noop when no
    // span is current.
    Span current = Span.current();
    if (current != null && current.isRecording()) {
      current.addEvent(
          "cpu_fallback.triggered",
          io.opentelemetry.api.common.Attributes.of(
              io.opentelemetry.api.common.AttributeKey.stringKey("fallback.cause"),
              "cpu_session_failure",
              io.opentelemetry.api.common.AttributeKey.stringKey("fallback.encoder"),
              consumerName));
    }
  }

  // ---------------------------------------------------------------------------
  // Observability
  // ---------------------------------------------------------------------------

  /** Returns true if GPU is currently available and not being released. */
  @Override
  public boolean isGpuAvailable() {
    return gpuAvailable && !gpuSessionReleasing && !deviceLost();
  }

  /** Returns true if GPU is configured (regardless of current availability). */
  public boolean isGpuConfigured() {
    return gpuConfig != null;
  }

  /** Returns the current ORT CUDA status for observability (never null). */
  @Override
  public OrtCudaStatus status() {
    return ortCudaStatus;
  }

  /**
   * Returns the active CPU session, or null if deferred and not yet created. Package-private
   * since §14.21 (tempdoc 397): same-package tests use it to assert deferred-CPU invariants;
   * external callers must observe deferred semantics behaviourally (e.g., via
   * {@link #acquire(SessionAcquisitionRequest)} materialising the session on first call).
   *
   * <p>WARNING: Do not use this for inference — use {@link
   * #acquire(SessionAcquisitionRequest)} instead.
   */
  OrtSession peekCpuSession() {
    return cpuSession;
  }

  /**
   * Acquires a CPU-only lease — fully concurrent, no GPU device gate seat. Tempdoc 397
   * §14.5 W4. Used by encoders (notably SPLADE) that want to explicitly invoke inference on the
   * CPU session after a GPU BFCArena failure.
   *
   * <p>If the CPU session has been deferred (and not yet materialised via prior
   * {@link #acquire(SessionAcquisitionRequest)} calls on the CPU path), this method
   * force-materialises it before returning the lease.
   * The lazy double-checked-locking handles concurrent materialisation safely. If materialisation
   * fails, {@code getCpuSession()} wraps the {@link OrtException} in
   * {@link IllegalStateException}; callers that want typed ORT failures should call
   * {@link OrtSessionAssembler#probeModelNames} on the model path first to surface the probe error.
   */
  @Override
  public Lease acquireCpu(SessionAcquisitionRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    return acquireCpuLease(request);
  }

  /** Returns the OrtEnvironment (shared, thread-safe singleton). */
  @Override
  public OrtEnvironment environment() {
    return env;
  }

  // ---------------------------------------------------------------------------
  // BFC arena failure detection (shared utility)
  // ---------------------------------------------------------------------------

  /**
   * Returns true if the OrtException indicates a BFC arena allocation failure. Identified by the
   * ORT_RUNTIME_EXCEPTION message pattern from BFCArena::AllocateRawInternal.
   *
   * <p>Consumers can use this for per-call GPU→CPU fallback without killing the GPU session.
   *
   * @param e the ORT exception to check
   * @return true if this is a BFC arena allocation failure
   */
  public static boolean isBfcArenaFailure(OrtException e) {
    String msg = e.getMessage();
    return msg != null
        && msg.contains("AllocateRawInternal")
        && msg.contains("Available memory of")
        && msg.contains("is smaller than requested bytes of");
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Closes both CPU and GPU sessions, GPU RunOptions, and releases all resources. A refused
   * retirement retains ownership and can be retried after the outstanding work releases.
   */
  @Override
  public void close() {
    retire(Duration.ofNanos(RETIRE_TIMEOUT_NANOS));
  }

  /** Package-private deadline seam for deterministic retirement-timeout tests. */
  void retire(Duration timeout) {
    if (timeout.isNegative()) throw new IllegalArgumentException("retirement timeout must be non-negative");
    synchronized (closeLock) {
      long deadline = System.nanoTime() + timeout.toNanos();
      synchronized (lifecycleLock) {
        retired = true;
        retirementStatus = RetirementStatus.RETIRING;
        sessionInstances.values().forEach(instance -> instance.unavailable = true);
        lifecycleLock.notifyAll();
        while ((!allLeasesReleased() || nativeActionsInProgress != 0)
            && awaitLifecycleChange(deadline)) {
          // The exact session holders and native creation/close owners signal this monitor.
        }
        if (!allLeasesReleased() || nativeActionsInProgress != 0) {
          retirementStatus = RetirementStatus.REFUSED;
          int outstandingLeases = sessionInstances.values().stream()
              .mapToInt(instance -> instance.leases).sum();
          throw new IllegalStateException(consumerName + ": native session retirement timed out; "
              + outstandingLeases + " outstanding leases and " + nativeActionsInProgress
              + " native actions; resources retained for retry");
        }
      }

      if (!closeGpuRunOptions()) {
        retirementStatus = RetirementStatus.REFUSED;
        return;
      }
      List<SessionInstance> retained;
      synchronized (lifecycleLock) {
        retained = List.copyOf(sessionInstances.values());
      }
      for (SessionInstance instance : retained) {
        if (!closeRetiredSession(instance)) {
          retirementStatus = RetirementStatus.REFUSED;
          return;
        }
        if (gpuSession == instance.session) {
          gpuSession = null;
          gpuAvailable = false;
        }
        if (cpuSession == instance.session) cpuSession = null;
      }
      retirementStatus = RetirementStatus.RETIRED;
    }
  }

  @Override
  public RetirementStatus retirementStatus() {
    return retirementStatus;
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  /**
   * Gets or lazily creates the CPU session. Handles both deferred creation (first call) and
   * recreation after a reported failure (D9: dead BFCArena allocations).
   */
  private OrtSession getCpuSession(SessionAcquisitionRequest request) {
    acquireOwnerLock(cpuSessionLock, request, "CPU session ownership");
    try {
      request.remainingNanos(LOADING);
      ensureNotRetired();
      OrtSession session = cpuSession;
      if (session != null && !cpuSessionFailed) return session;

      boolean recreatingAfterFailure = false;
      CpuRecreateCause causeForEmit = CpuRecreateCause.UNKNOWN;
      if (cpuSessionFailed) {
        OrtSession old = cpuSession;
        recreatingAfterFailure = true;
        causeForEmit = cpuFailureCause;
        if (old != null) {
          SessionInstance oldInstance;
          synchronized (lifecycleLock) {
            oldInstance = instanceFor(old);
            oldInstance.unavailable = true;
            while (oldInstance.leases != 0) {
              try {
                long waitNanos =
                    Math.min(request.remainingNanos(LOADING), CANCELLATION_POLL_NANOS);
                TimeUnit.NANOSECONDS.timedWait(lifecycleLock, waitNanos);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancellation =
                    new CancellationException(
                        "CPU lease acquisition interrupted during session recreation");
                cancellation.initCause(interrupted);
                throw cancellation;
              }
              ensureNotRetired();
            }
            nativeActionsInProgress++;
          }
          boolean closedOld = false;
          try {
            old.close();
            closedOld = true;
            log.info("{}: closed failed CPU session (releasing BFCArena allocations)", consumerName);
          } catch (OrtException e) {
            log.warn("{}: failed CPU session close; replacement refused until retry", consumerName, e);
          } finally {
            synchronized (lifecycleLock) {
              nativeActionsInProgress--;
              lifecycleLock.notifyAll();
              if (closedOld) sessionInstances.remove(old);
            }
          }
          if (!closedOld) {
            throw new SessionTemporarilyUnavailableException(
                consumerName + ": failed CPU session could not close");
          }
          cpuSession = null;
        }
        cpuSessionFailed = false;
        cpuFailureCause = CpuRecreateCause.UNKNOWN;
        request.remainingNanos(LOADING);
      }
      session = cpuSession;
      if (session != null) return session;
      synchronized (lifecycleLock) {
        ensureNotRetiredLocked();
        nativeActionsInProgress++;
      }
      try {
        OrtSession created = createCpuSession();
        synchronized (lifecycleLock) {
          if (!retired) {
            cpuSession = created;
            sessionInstances.put(created, new SessionInstance(created));
            created = null;
          }
        }
        if (created != null) {
          retainPrivateCandidate(created);
          closePrivateCandidate(created);
          throw new SessionRetiredException(
              consumerName + ": session handle retired during CPU creation");
        }
        if (recreatingAfterFailure) {
          // Tempdoc 414: F-009 NaN-on-CPU-OOM / BFCArena-failure recovery is now first-class.
          events.onTransition(
              new TransitionReason.CpuSessionRecreated(consumerName, causeForEmit));
        }
        fireGenerationChanged();
        return cpuSession;
      } catch (OrtException e) {
        throw new SessionTemporarilyUnavailableException(
            consumerName + ": CPU session creation failed", e);
      } finally {
        synchronized (lifecycleLock) {
          nativeActionsInProgress--;
          lifecycleLock.notifyAll();
        }
      }
    } finally {
      cpuSessionLock.unlock();
    }
  }

  private OrtSession createCpuSession() throws OrtException {
    try (SessionOptions cpuOpts = new SessionOptions()) {
      SessionOptionsApplier.applyBase(runtime, cpuOpts);
      return OnnxSessionCache.createCachedSession(env, cpuModelPath, cpuOpts, cpuOptLevel);
    }
  }

  private Lease acquireCpuLease(SessionAcquisitionRequest request) {
    return acquireCpuLease(request, getCpuSession(request));
  }

  private Lease acquireCpuLease(SessionAcquisitionRequest request, OrtSession candidate) {
    OrtSession session = candidate;
    while (true) {
      request.remainingNanos();
      synchronized (lifecycleLock) {
        ensureNotRetiredLocked();
        SessionInstance instance = retainIfAvailable(session, session == cpuSession && !cpuSessionFailed);
        if (instance != null) {
          return new Lease(
              session,
              null,
              new DetachableRun.Release(idempotentRelease(instance, () -> {}), cpuRunThreads),
              /* isCpu= */ true,
              cpuRecorderFor(session),
              request);
        }
      }
      session = getCpuSession(request);
    }
  }

  /** The recorder for a CPU lease on {@code session}: a successful run makes that session warm. */
  private OrtRunRecorder cpuRecorderFor(OrtSession session) {
    OrtRunRecorder base = ortRunRecorder;
    return elapsedNanos -> {
      if (cpuWarmSession != session) {
        synchronized (lifecycleLock) {
          if (cpuSession == session && !cpuSessionFailed) cpuWarmSession = session;
        }
      }
      base.recordOrtRunNs(elapsedNanos);
    };
  }

  private SessionInstance retainIfAvailable(OrtSession session, boolean identityCurrent) {
    ensureNotRetiredLocked();
    if (!identityCurrent || session == null) return null;
    SessionInstance instance = instanceFor(session);
    if (instance.unavailable) return null;
    instance.leases++;
    return instance;
  }

  private SessionInstance instanceFor(OrtSession session) {
    return sessionInstances.computeIfAbsent(session, SessionInstance::new);
  }

  /**
   * The release of one lease on {@code instance}, then {@code afterRelease} (a GPU lease's device
   * seat). The release that ends the last run of a session retired by {@link #releaseGpu()} closes
   * that session first, so no other run is seated on the device before it is closed.
   */
  private Runnable idempotentRelease(SessionInstance instance, Runnable afterRelease) {
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      if (!released.compareAndSet(false, true)) return;
      boolean closeRetired;
      synchronized (lifecycleLock) {
        if (instance.leases <= 0) {
          throw new IllegalStateException("lease accounting underflow for exact native session");
        }
        instance.leases--;
        closeRetired = instance.closeWhenReleased && instance.leases == 0;
        if (closeRetired) {
          instance.closeWhenReleased = false;
          nativeActionsInProgress++;
        }
        lifecycleLock.notifyAll();
      }
      try {
        if (closeRetired) closeRetiredGpuSession(instance);
      } finally {
        afterRelease.run();
      }
    };
  }

  private void ensureNotRetired() {
    synchronized (lifecycleLock) {
      ensureNotRetiredLocked();
    }
  }

  private void ensureNotRetiredLocked() {
    if (retired) throw new SessionRetiredException(consumerName + ": session handle is retired");
  }

  private boolean allLeasesReleased() {
    return sessionInstances.values().stream().allMatch(instance -> instance.leases == 0);
  }

  private boolean awaitLifecycleChange(long deadlineNanos) {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) return false;
    try {
      TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private boolean closeRetiredSession(SessionInstance instance) {
    OrtSession session = instance.session;
    synchronized (lifecycleLock) {
      if (instance.leases != 0) return false;
      nativeActionsInProgress++;
    }
    boolean closedSession = false;
    boolean wasRetiredOpen = false;
    try {
      session.close();
      closedSession = true;
      return true;
    } catch (OrtException e) {
      log.warn("{}: native session close failed; resource retained for retry", consumerName, e);
      return false;
    } finally {
      synchronized (lifecycleLock) {
        nativeActionsInProgress--;
        if (closedSession) {
          sessionInstances.remove(session);
          wasRetiredOpen = instance.retiredOpen;
          instance.retiredOpen = false;
        }
        lifecycleLock.notifyAll();
      }
      if (wasRetiredOpen) RetiredGpuSessions.closed(gpuConfig.gpuDeviceId(), consumerName);
    }
  }

  private void retainPrivateCandidate(OrtSession session) {
    synchronized (lifecycleLock) {
      SessionInstance instance = instanceFor(session);
      instance.unavailable = true;
    }
  }

  private void closePrivateCandidate(OrtSession session) throws OrtException {
    session.close();
    synchronized (lifecycleLock) {
      sessionInstances.remove(session);
    }
  }

  private void tryCreateGpuSession() {
    synchronized (lifecycleLock) {
      ensureNotRetiredLocked();
      nativeActionsInProgress++;
    }
    // Tempdoc 374 alpha.21 Bug Q: nativePath is the bundled cuda12 dir, which ships
    // the CUDA *runtime* DLLs (cuBLAS, cuFFT, cuDNN, etc.) but NOT the ORT EP DLLs
    // (which auto-extract from onnxruntime-gpu.jar to %TEMP% at runtime). Pre-alpha.21
    // this called checkMissingCudaDlls which validates BOTH sets and would always
    // report the EP DLLs as missing — INFO-spam log line on every encoder boot
    // implying a problem when the JAR-bundled fallback IS the expected design.
    // checkMissingCudaRuntimeDlls validates only the runtime DLLs we actually expect
    // cuda12 to ship. Per OrtCudaHelper:90-94 javadoc.
    List<String> missingDlls = OrtCudaHelper.checkMissingCudaRuntimeDlls(nativePath);
    if (!missingDlls.isEmpty()) {
      log.warn(
          "{}: CUDA runtime DLLs missing from {}: {} — GPU session creation will likely fail",
          consumerName,
          nativePath,
          missingDlls);
    }
    OrtCudaHelper.prepareCudaDependencies(nativePath);

    Path gpuPath = gpuModelPath != null ? gpuModelPath : cpuModelPath;
    try {
      // Inlined FP16 → FP32 fallback (tempdoc 397 §7.3). Try the preferred GPU model first;
      // if it fails with OrtException and the CPU-model path is distinct, retry with the CPU
      // model path on the CUDA provider. Identical paths propagate the first exception.
      Path modelPathUsed = gpuPath;
      OrtException fallbackCause = null;
      OrtSession session;
      try {
        session = createGpuSession(env, gpuPath);
      } catch (OrtException gpuErr) {
        if (!gpuPath.equals(cpuModelPath) && policy.gpu().plannedProfile()) {
          refuseFp32OnPlannedProfile(gpuPath, gpuErr);
          return;
        }
        if (!gpuPath.equals(cpuModelPath)) {
          session = createGpuSession(env, cpuModelPath);
          modelPathUsed = cpuModelPath;
          fallbackCause = gpuErr;
        } else {
          throw gpuErr;
        }
      }
      OrtSession.RunOptions runOptions;
      try {
        runOptions = SessionOptionsApplier.buildGpuRunOptions(policy);
      } catch (OrtException runOptionsFailure) {
        retainPrivateCandidate(session);
        try {
          closePrivateCandidate(session);
        } catch (OrtException closeFailure) {
          runOptionsFailure.addSuppressed(closeFailure);
        }
        throw runOptionsFailure;
      }
      boolean rejectCreated;
      synchronized (lifecycleLock) {
        rejectCreated = retired;
        SessionInstance instance = new SessionInstance(session);
        instance.unavailable = rejectCreated;
        gpuSession = session;
        gpuRunOptions = runOptions;
        gpuGeneration++;
        sessionInstances.put(session, instance);
      }
      if (rejectCreated) {
        return;
      }
      if (fallbackCause != null) {
        log.warn(
            "{}: GPU model {} failed to load, fell back to {}: {}",
            consumerName,
            gpuPath.getFileName(),
            modelPathUsed.getFileName(),
            fallbackCause.getMessage());
      }
      gpuAvailable = true;
      ortCudaStatus = OrtCudaStatus.ready(ORT_VARIANT_ID, nativePath);
      log.info(
          "{}: GPU session initialized — model={}, device={}, memLimit={}MB,"
              + " arena=kSameAsRequested, arenaShrinkage=enabled",
          consumerName,
          modelPathUsed.getFileName(),
          gpuConfig.gpuDeviceId(),
          gpuConfig.gpuMemLimitBytes() / (1024 * 1024));
      events.onTransition(new TransitionReason.GpuInitialized(consumerName));
    } catch (OrtException | UnsatisfiedLinkError e) {
      log.warn("{}: GPU session creation failed, using CPU fallback: {}", consumerName,
          e.getMessage());
      log.debug("{}: GPU session creation failed (stack trace)", consumerName, e);
      gpuAvailable = false;
      gpuFailedAtMs = System.currentTimeMillis();
      events.onTransition(
          new TransitionReason.GpuInitFailed(
              consumerName, FailureCause.classifyGpuInitException(e)));
      ortCudaStatus =
          OrtCudaStatus.providerFailed(ORT_VARIANT_ID, nativePath, e.getMessage());
    } finally {
      gpuSessionAttempted = true;
      synchronized (lifecycleLock) {
        nativeActionsInProgress--;
        lifecycleLock.notifyAll();
      }
      fireGenerationChanged();
    }
  }

  /**
   * Spec E invariant 4: on a planned profile the role's cap budgets the FP16 weights, so when the
   * FP16 GPU session cannot be created the FP32 file (about 600 MiB larger) is not loaded on the
   * GPU in its place. The role runs on its CPU session, and the status says why. The plan stays as
   * resolved; a later GPU retry tries the FP16 file again.
   */
  private void refuseFp32OnPlannedProfile(Path gpuPath, OrtException gpuErr) {
    log.warn(
        "{}: GPU model {} failed to load on a planned profile; the FP32 file is not loaded on the"
            + " GPU, running on CPU: {}",
        consumerName,
        gpuPath.getFileName(),
        gpuErr.getMessage());
    gpuAvailable = false;
    gpuFailedAtMs = System.currentTimeMillis();
    events.onTransition(
        new TransitionReason.GpuInitFailed(
            consumerName, FailureCause.classifyGpuInitException(gpuErr)));
    ortCudaStatus =
        OrtCudaStatus.providerFailed(
            ORT_VARIANT_ID, nativePath, PLANNED_FP32_GPU_REFUSED + ": " + gpuErr.getMessage());
  }

  private boolean closeGpuRunOptions() {
    OrtSession.RunOptions opts = gpuRunOptions;
    if (opts != null) {
      try {
        opts.close();
        gpuRunOptions = null;
      } catch (Exception e) {
        log.warn("{}: GPU RunOptions close failed; resource retained for retry", consumerName, e);
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // GPU session construction: delegates to SessionOptionsApplier (tempdoc 397 §14.24 FA).
  // The applier walks RuntimePolicy + ModelSessionPolicy field-by-field — no hardcoded
  // option values remain here.
  // ---------------------------------------------------------------------------

  private OrtSession createGpuSession(OrtEnvironment env, Path modelPath) throws OrtException {
    try (SessionOptions opts = new SessionOptions();
        OrtCUDAProviderOptions cudaOpts = new OrtCUDAProviderOptions(gpuConfig.gpuDeviceId())) {
      SessionOptionsApplier.applyCudaProviderOptions(runtime, policy, cudaOpts);
      opts.addCUDA(cudaOpts);
      SessionOptionsApplier.applyBase(runtime, opts);
      SessionOptionsApplier.applyGpuSessionOptions(runtime, opts);
      // Route through OnnxSessionCache for CUDA-EP graph-optimisation caching (tempdoc 391
      // Issue B): first run writes <model>.cuda.optimized; subsequent runs load the pre-
      // optimised graph with NO_OPT, skipping ~6 s of graph optimisation on every cold start.
      return OnnxSessionCache.createCachedGpuSession(env, modelPath, opts);
    }
  }

  /**
   * The run threads a lease of {@code consumerName} on {@code device} takes (Spec D §1.5). Query
   * encoders share their device's run threads. The reranker has its own on each device: a search's
   * rerank releases its caller at the caller's deadline, and the rerank runs it leaves behind are
   * bounded by the reranker's own run threads, so they never take the run threads query legs need,
   * and a query leg never waits behind them.
   */
  private static DetachableRun.Device runThreads(String consumerName, String device) {
    String reranker = EncoderRole.RERANKER.consumerName();
    return DetachableRun.device(reranker.equals(consumerName) ? reranker + "@" + device : device);
  }

  // ---------------------------------------------------------------------------
  // Builder
  // ---------------------------------------------------------------------------

  /**
   * Creates a builder for a NativeSessionHandle. Package-private since §14.19 (tempdoc 397):
   * external callers route through {@link OrtSessionAssembler#buildManager}. Java visibility now
   * enforces what ArchUnit rule 1 used to guard.
   */
  static Builder builder(String consumerName, Path cpuModelPath) {
    java.util.Objects.requireNonNull(consumerName, "consumerName");
    java.util.Objects.requireNonNull(cpuModelPath, "cpuModelPath");
    return new Builder(consumerName, cpuModelPath);
  }

  /**
   * Package-private builder for {@link NativeSessionHandle}. Only reachable from
   * {@link OrtSessionAssembler#buildManager} and from the testFixtures helper; Java visibility
   * enforces the single-apply-path contract. Tempdoc 397 §14.26 T1-B eliminated the former flat
   * policy-substitute setters ({@code .gpuConfig}, {@code .deferCpuSession},
   * {@code .cpuOptLevel}, {@code .gpuRetryEnabled}, {@code .gpuRetryIntervalMs}); raw-input
   * callers (tests + benchmarks) now construct a {@link ModelSessionPolicy} via
   * {@link ModelSessionPolicy#forFallback} and pass it via {@link #policy(ModelSessionPolicy)}.
   */
  @io.justsearch.contracts.BuildContract(
      description =
          "Single construction path for NativeSessionHandle: package-private Builder reachable only"
              + " from OrtSessionAssembler#buildManager and testFixtures (§14.19 Phase 4). Java"
              + " visibility enforces what ArchUnit rule 1 used to guard.",
      tempdoc = "397 §14.19 / §14.26 T1-B / §14.28 U1",
      enforcer = "NativeSessionHandleBuilderVisibilityTest")
  static final class Builder {
    private final String consumerName;
    private final Path cpuModelPath;
    private Path gpuModelPath;
    private Path nativePath;
    private BooleanSupplier shouldUseGpu;
    private Runnable onBeforeGpuRelease;
    private RuntimePolicy runtime;
    private ModelSessionPolicy policy;
    private OrtSessionTelemetryEvents events;

    private Builder(String consumerName, Path cpuModelPath) {
      this.consumerName = consumerName;
      this.cpuModelPath = cpuModelPath;
    }

    /** Sets the GPU-preferred model path (typically FP16). */
    public Builder gpuModelPath(Path gpuModelPath) {
      this.gpuModelPath = gpuModelPath;
      return this;
    }

    /** Sets the ORT native library path for DLL pre-flight checks. */
    public Builder nativePath(Path nativePath) {
      this.nativePath = nativePath;
      return this;
    }

    /** Sets the runtime GPU arbitration callback. */
    public Builder shouldUseGpu(BooleanSupplier shouldUseGpu) {
      this.shouldUseGpu = shouldUseGpu;
      return this;
    }

    /** Registers a callback invoked before GPU session release. */
    public Builder onBeforeGpuRelease(Runnable callback) {
      this.onBeforeGpuRelease = callback;
      return this;
    }

    /**
     * Sets the {@link RuntimePolicy} used by {@link SessionOptionsApplier} (tempdoc 397 §14.24
     * FA). Required; typical production callers pass {@code comp.runtime()} from
     * {@link RuntimePolicyResolver}, fallback callers pass {@link RuntimePolicy#defaults()}.
     */
    public Builder runtime(RuntimePolicy runtime) {
      this.runtime = runtime;
      return this;
    }

    /**
     * Sets the full {@link ModelSessionPolicy} used by {@link SessionOptionsApplier} (tempdoc
     * 397 §14.24 FA + §14.26 T1-B). Required; production callers pass the resolver output,
     * fallback callers pass {@link ModelSessionPolicy#forFallback}.
     */
    public Builder policy(ModelSessionPolicy policy) {
      this.policy = policy;
      return this;
    }

    /**
     * Sets the lifecycle-event recorder. Defaults to {@link OrtSessionTelemetryEvents#NOOP}.
     * Tempdoc 414.
     */
    public Builder events(OrtSessionTelemetryEvents events) {
      this.events = events;
      return this;
    }

    /** Builds the NativeSessionHandle. */
    public NativeSessionHandle build() throws OrtException {
      return new NativeSessionHandle(this);
    }
  }
}
