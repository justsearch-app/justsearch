/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import io.justsearch.ort.telemetry.CpuRecreateCause;
import io.justsearch.ort.telemetry.FailureCause;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import io.justsearch.ort.telemetry.TransitionReason;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
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
 * <p>Thread-safe: multiple threads may call {@link #acquire()} concurrently. GPU session
 * creation uses double-checked locking with a dedicated lock object.
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
// §14.28 U1) but ort-common does not depend on ipc-common (which owns the
// @BuildContract annotation), so we document the invariant here in a
// comment rather than as an annotation. Moving the annotation classes to a
// truly foundational module (or adding ipc-common to ort-common — rejected
// because ipc-common pulls gRPC) is deferred.
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

  private static final String ORT_VARIANT_ID = "onnxruntime-gpu";

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
  // buffers — all from the same arena. The semaphore ensures only one GPU run()
  // is in flight at a time. CPU sessions remain fully concurrent (no semaphore).
  private final Semaphore gpuInferenceSemaphore = new Semaphore(1);

  // Mutable state (volatile, guarded by locks)
  private final Object gpuSessionLock = new Object();
  private final Object cpuSessionLock = new Object();
  private volatile OrtSession cpuSession; // null when deferred until first need
  private volatile OrtSession gpuSession;
  private volatile OrtSession.RunOptions gpuRunOptions;
  private volatile boolean gpuSessionAttempted;
  private volatile boolean gpuAvailable;
  private volatile boolean gpuSessionReleasing;
  private volatile long gpuFailedAtMs;
  private volatile OrtCudaStatus ortCudaStatus;
  private final BooleanSupplier shouldUseGpu;
  private volatile boolean cpuSessionFailed; // set by reportCpuSessionFailure(), cleared on recreate
  // Tempdoc 414 A3: typed cause from the most recent reportCpuSessionFailure(cause) call.
  // Read by getCpuSession() when emitting CpuSessionRecreated; reset to UNKNOWN after consumption.
  private volatile CpuRecreateCause cpuFailureCause = CpuRecreateCause.UNKNOWN;
  private volatile boolean closed;

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
    }
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
   *   <li>If GPU is being released, returns CPU session
   *   <li>If GPU is not configured or arbitration says CPU, returns CPU session
   *   <li>If GPU not yet attempted, lazy-initializes under lock (double-checked)
   *   <li>If GPU failed and retry is enabled and 60s elapsed, re-attempts under lock
   *   <li>If GPU available, returns GPU session; otherwise returns CPU session
   * </ol>
   *
   * @return the session to use for this inference call (never null)
   */
  private OrtSession selectSession() {
    // Fast path: GPU session being released, use CPU for graceful degradation
    if (gpuSessionReleasing) {
      return getCpuSession();
    }

    // Fast path: GPU not configured or arbitration says use CPU
    if (gpuConfig == null || !shouldUseGpu.getAsBoolean()) {
      return getCpuSession();
    }

    // Lazy GPU session initialization (double-checked locking)
    if (!gpuSessionAttempted) {
      synchronized (gpuSessionLock) {
        if (!gpuSessionAttempted) {
          tryCreateGpuSession();
        }
      }
    }

    // GPU retry: if creation failed >60s ago, allow one re-attempt
    if (gpuRetryEnabled && gpuSessionAttempted && !gpuAvailable && gpuFailedAtMs > 0
        && System.currentTimeMillis() - gpuFailedAtMs > gpuRetryIntervalMs) {
      synchronized (gpuSessionLock) {
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
      }
    }

    return gpuAvailable ? gpuSession : getCpuSession();
  }

  // ---------------------------------------------------------------------------
  // Exclusive GPU access (prevents concurrent BFCArena exhaustion)
  // ---------------------------------------------------------------------------

  /**
   * Acquires the best session with exclusive GPU access. Returns a {@link SessionHandle.Lease}
   * that MUST be closed after inference completes (use try-with-resources).
   *
   * <p>If the GPU session is selected, the caller holds an exclusive semaphore — no other thread
   * can run GPU inference on this session manager until the lease is closed. CPU sessions are
   * returned without a semaphore (fully concurrent).
   *
   * <p>This prevents concurrent {@code session.run()} calls from accumulating activation memory in
   * the BFCArena beyond the {@code gpu_mem_limit}. Each {@code run()} creates its own
   * ExecutionFrame with independent activation buffers — serializing GPU runs keeps the arena
   * within its budget.
   *
   * @return a lease that provides the session, RunOptions, and auto-releases the semaphore
   */
  @Override
  public Lease acquire() {
    // Tempdoc 400 LR2-b: lease.acquire span captures semaphore wait vs run
    // split. Span becomes a child of whatever OTel context is current when
    // acquire() is called (encoder.ort_run in backfill; enrichment.batch or
    // no parent elsewhere). Zero-cost when LEASE_TRACING is false.
    Span leaseSpan = maybeLeaseSpan();
    try {
      OrtSession session = selectSession();
      if (session == gpuSession && session != null) {
        leaseSpan.setAttribute("lease.wait_queue_depth", (long) gpuInferenceSemaphore.getQueueLength());
        // Tempdoc 414 A1: time only the semaphore acquire — that's what
        // ort.session.semaphore_wait_us is supposed to measure. Microsecond
        // resolution captures the no-contention fast path.
        long waitStartNs = System.nanoTime();
        boolean acquired = false;
        try {
          try {
            gpuInferenceSemaphore.acquire();
            acquired = true;
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancellation =
                new CancellationException("GPU lease acquisition interrupted");
            cancellation.initCause(interrupted);
            throw cancellation;
          }
          // An interrupt can race with a successful acquire. Do not construct a lease for a
          // cancelled caller; the finally block releases this permit, while an already-issued
          // lease remains responsible for its own permit until its native call has exited.
          if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("GPU lease acquisition interrupted");
          }
          long waitUs = (System.nanoTime() - waitStartNs) / 1_000L;
          events.onSemaphoreWait(consumerName, waitUs);
          if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("GPU lease acquisition interrupted");
          }
          // Re-check after acquiring: GPU may have been released while we waited
          if (gpuSessionReleasing || session != gpuSession) {
            gpuInferenceSemaphore.release();
            acquired = false;
            OrtSession cpuFallback = getCpuSession();
            leaseSpan.setAttribute("lease.mode", "cpu");
            // Tempdoc 414: silent line-260 fallback now first-class.
            events.onTransition(new TransitionReason.GpuFallbackTaken(consumerName));
            return new Lease(cpuFallback, null, () -> {}, /* isCpu= */ true, ortRunRecorder);
          }
          leaseSpan.setAttribute("lease.mode", "gpu");
          Lease lease =
              new Lease(
                  session,
                  gpuRunOptions,
                  gpuInferenceSemaphore::release,
                  /* isCpu= */ false,
                  ortRunRecorder);
          acquired = false;
          return lease;
        } finally {
          if (acquired) {
            // No Lease owns this permit on an exceptional path. An active native lease reaches
            // this point with acquired=false and remains responsible for its own release.
            gpuInferenceSemaphore.release();
          }
        }
      }
      leaseSpan.setAttribute("lease.mode", "cpu");
      return new Lease(session, null, () -> {}, /* isCpu= */ true, ortRunRecorder);
    } finally {
      leaseSpan.end();
    }
  }

  private static Span maybeLeaseSpan() {
    if (!LEASE_TRACING) {
      return Span.getInvalid();
    }
    return LEASE_TRACER.spanBuilder("lease.acquire").startSpan();
  }

  // ---------------------------------------------------------------------------
  // GPU lifecycle management
  // ---------------------------------------------------------------------------

  /**
   * Releases the GPU session to free VRAM (called when Main claims GPU).
   *
   * <p>Thread-safe: concurrent {@link #selectSession()} calls will gracefully fall back to CPU
   * during release.
   */
  @Override
  public void releaseGpu() {
    gpuSessionReleasing = true;
    // Acquire the inference semaphore to ensure no in-flight GPU inference
    // is using pinned output tensors or the GPU session when we tear them down.
    // New GPU leases are already blocked by gpuSessionReleasing=true above.
    gpuInferenceSemaphore.acquireUninterruptibly();
    boolean releaseSucceeded = false;
    try {
      if (onBeforeGpuRelease != null) {
        try {
          onBeforeGpuRelease.run();
        } catch (RuntimeException e) {
          log.warn("{}: onBeforeGpuRelease callback failed", consumerName, e);
        }
      }

      closeGpuRunOptions();

      OrtSession session = gpuSession;
      if (session != null) {
        gpuSession = null;
        try {
          session.close();
        } catch (OrtException e) {
          log.debug("{}: GPU session close failed", consumerName, e);
        }
      }

      gpuSessionAttempted = false;
      gpuAvailable = false;
      gpuFailedAtMs = 0;
      ortCudaStatus = OrtCudaStatus.released(ORT_VARIANT_ID, nativePath);
      log.info("{}: GPU session released (yielding VRAM)", consumerName);
      releaseSucceeded = true;
    } finally {
      gpuInferenceSemaphore.release();
      gpuSessionReleasing = false;
      if (releaseSucceeded) {
        events.onTransition(new TransitionReason.GpuReleaseCompleted(consumerName));
      } else {
        events.onTransition(new TransitionReason.GpuReleaseFailed(consumerName));
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
   * attention on long inputs). The next call to {@link #selectSession()} will close the failed
   * session and create a fresh one, releasing the dead BFCArena allocations.
   *
   * <p>This uses a deferred-recreation pattern to avoid threading races: the old session stays
   * alive until the next {@code selectSession()} call replaces it under the lock. No thread can
   * hold a dangling reference because the replacement happens at the entry point, not mid-inference.
   */
  @Override
  public void reportCpuSessionFailure(CpuRecreateCause cause) {
    cpuFailureCause = cause != null ? cause : CpuRecreateCause.UNKNOWN;
    cpuSessionFailed = true;
    log.warn(
        "{}: CPU session failure reported (cause={}) — will recreate on next use",
        consumerName,
        cpuFailureCause);
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
    return gpuAvailable && !gpuSessionReleasing;
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
   * {@link #acquire()} materialising the session on first call).
   *
   * <p>WARNING: Do not use this for inference — use {@link #acquire()} instead.
   */
  OrtSession peekCpuSession() {
    return cpuSession;
  }

  /**
   * Acquires a CPU-only lease — fully concurrent, no GPU serialisation semaphore. Tempdoc 397
   * §14.5 W4. Used by encoders (notably SPLADE) that want to explicitly invoke inference on the
   * CPU session after a GPU BFCArena failure.
   *
   * <p>If the CPU session has been deferred (and not yet materialised via prior
   * {@link #selectSession()} / {@link #acquire()} calls on the CPU path), this method
   * force-materialises it via the private {@code getCpuSession()} before returning the lease.
   * The lazy double-checked-locking handles concurrent materialisation safely. If materialisation
   * fails, {@code getCpuSession()} wraps the {@link OrtException} in
   * {@link IllegalStateException}; callers that want typed ORT failures should call
   * {@link OrtSessionAssembler#probeModelNames} on the model path first to surface the probe error.
   */
  @Override
  public Lease acquireCpu() {
    OrtSession cpu = getCpuSession();
    return new Lease(cpu, null, () -> {}, /* isCpu= */ true, ortRunRecorder);
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
   * Closes both CPU and GPU sessions, GPU RunOptions, and releases all resources. Safe to call
   * multiple times (idempotent).
   */
  @Override
  public void close() {
    if (closed) return;
    closed = true;

    closeGpuRunOptions();

    OrtSession gpu = gpuSession;
    if (gpu != null) {
      gpuSession = null;
      try {
        gpu.close();
      } catch (OrtException e) {
        log.debug("{}: GPU session close failed", consumerName, e);
      }
    }

    OrtSession cpu = cpuSession;
    if (cpu != null) {
      cpuSession = null;
      try {
        cpu.close();
      } catch (OrtException e) {
        log.debug("{}: CPU session close failed", consumerName, e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  /**
   * Gets or lazily creates the CPU session. Handles both deferred creation (first call) and
   * recreation after a reported failure (D9: dead BFCArena allocations).
   */
  private OrtSession getCpuSession() {
    OrtSession session = cpuSession;
    if (session != null && !cpuSessionFailed) return session;

    synchronized (cpuSessionLock) {
      // Re-check under lock: another thread may have already recreated
      boolean recreatingAfterFailure = false;
      CpuRecreateCause causeForEmit = CpuRecreateCause.UNKNOWN;
      if (cpuSessionFailed) {
        OrtSession old = cpuSession;
        cpuSession = null;
        cpuSessionFailed = false;
        recreatingAfterFailure = true;
        // Tempdoc 414 A3: capture typed cause + reset for next failure.
        causeForEmit = cpuFailureCause;
        cpuFailureCause = CpuRecreateCause.UNKNOWN;
        if (old != null) {
          try {
            old.close();
            log.info("{}: closed failed CPU session (releasing BFCArena allocations)", consumerName);
          } catch (OrtException e) {
            log.debug("{}: failed CPU session close error (non-fatal)", consumerName, e);
          }
        }
      }
      session = cpuSession;
      if (session != null) return session;
      try {
        cpuSession = createCpuSession();
        if (recreatingAfterFailure) {
          // Tempdoc 414: F-009 NaN-on-CPU-OOM / BFCArena-failure recovery is now first-class.
          events.onTransition(
              new TransitionReason.CpuSessionRecreated(consumerName, causeForEmit));
        }
        return cpuSession;
      } catch (OrtException e) {
        throw new IllegalStateException(
            consumerName + ": CPU session creation failed", e);
      }
    }
  }

  private OrtSession createCpuSession() throws OrtException {
    try (SessionOptions cpuOpts = new SessionOptions()) {
      SessionOptionsApplier.applyBase(runtime, cpuOpts);
      return OnnxSessionCache.createCachedSession(env, cpuModelPath, cpuOpts, cpuOptLevel);
    }
  }

  private void tryCreateGpuSession() {
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
        if (!gpuPath.equals(cpuModelPath)) {
          session = createGpuSession(env, cpuModelPath);
          modelPathUsed = cpuModelPath;
          fallbackCause = gpuErr;
        } else {
          throw gpuErr;
        }
      }
      gpuSession = session;
      if (fallbackCause != null) {
        log.warn(
            "{}: GPU model {} failed to load, fell back to {}: {}",
            consumerName,
            gpuPath.getFileName(),
            modelPathUsed.getFileName(),
            fallbackCause.getMessage());
      }
      gpuAvailable = true;
      gpuRunOptions = SessionOptionsApplier.buildGpuRunOptions(policy);
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
    }
  }

  private void closeGpuRunOptions() {
    OrtSession.RunOptions opts = gpuRunOptions;
    if (opts != null) {
      gpuRunOptions = null;
      try {
        opts.close();
      } catch (Exception e) {
        log.debug("{}: GPU RunOptions close failed", consumerName, e);
      }
    }
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
