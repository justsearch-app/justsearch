/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.util.Collections;
import java.util.Map;

/**
 * Runtime-facing wrapper over an encoder's ORT session state.
 *
 * <p>Introduced in tempdoc 397 §7.4 / Stage 2. Encoders consume this interface; the concrete
 * implementation is {@link NativeSessionHandle} (package-private construction via
 * {@link OrtSessionAssembler}). The handle surface is deliberately narrow: only what encoder
 * code actually needs (lease acquisition, environment access, input-name introspection at
 * construction time, and CUDA observability pass-throughs). Policy and construction live
 * upstream in {@link OrtSessionAssembler}; encoder classes never reference the implementation
 * directly.
 *
 * <p><strong>Admin API vs encoder API.</strong> Of the surface below:
 *
 * <ul>
 *   <li>{@link #acquire(SessionAcquisitionRequest)}, {@link #environment()}, {@link
 *       #isGpuAvailable()}, {@link #status()}
 *       are called by <em>encoder</em> code during inference.
 *   <li>{@link #releaseGpu()} and {@link #close()} are called by the <em>signal-bus
 *       coordinator</em> (today's {@code IndexingLoop}-like GPU-arbitration code) when the Main
 *       process claims the GPU or the Worker shuts down. Encoder code should not call these.
 * </ul>
 *
 * <p>Stage 3 migrations may extend this interface with a {@code setLifecycleCallback} method
 * when SPLADE's pinned-memory cleanup hook needs typed wiring. NER's Stage 2 migration does not
 * require it.
 *
 * <p><strong>Reacquisition.</strong> There is no explicit {@code reacquire()} method:
 * {@link #acquire(SessionAcquisitionRequest)} lazily recreates the GPU session on the next call after a prior
 * {@link #releaseGpu()}. This mirrors {@link NativeSessionHandle}'s existing semantics (see
 * {@code tryCreateGpuSession} guarded by {@code gpuSessionAttempted}).
 */
public interface SessionHandle extends AutoCloseable {

  /**
   * Acquires a session for one inference call. The returned {@link Lease} holds the selected
   * session (GPU if available and not being released, else CPU), the per-session
   * {@link ai.onnxruntime.OrtSession.RunOptions} (null for CPU sessions), and a {@code release}
   * runnable. Callers must close the lease via try-with-resources or explicit
   * {@code release.run()} to avoid holding the GPU device gate's seat.
   */
  Lease acquire(SessionAcquisitionRequest request);

  /**
   * Acquires a CPU-only lease for explicit fallback-path inference (e.g., SPLADE's
   * {@code runHeapFallback} on BFCArena failure). The returned lease is always backed by the
   * CPU session; {@link Lease#runOptions()} is always null; {@link Lease#isCpu()} is always true.
   * No GPU device gate seat is held — CPU sessions are fully concurrent by ORT's own
   * design.
   *
   * <p>Tempdoc 397 §14.5 W4. Intended for the pattern
   * <pre>{@code
   * try (var lease = sessions.acquire(request)) {
   *     try { session.run(...); }
   *     catch (OrtException e) {
   *         if (!lease.isCpu() && isBfcArenaFailure(e)) {
   *             try (var cpuLease = sessions.acquireCpu(request)) {
   *                 return runHeapFallback(cpuLease.session(), ...);
   *             }
   *         }
   *     }
   * }
   * }</pre>
   *
   * <p>When the CPU session is deferred and not yet materialised, the implementation performs
   * lazy double-checked-locking construction before returning (materialisation on demand). This
   * may throw {@link ai.onnxruntime.OrtException} if the CPU model is unavailable.
   */
  Lease acquireCpu(SessionAcquisitionRequest request);

  /** The shared JVM-singleton {@link OrtEnvironment} used for tensor construction. */
  OrtEnvironment environment();

  /** True if the GPU session is currently available (session created + not being released). */
  boolean isGpuAvailable();

  /**
   * The device a query run would select now (Spec D §1.1): {@link QueryDevice#GPU} only when a
   * GPU session is available for it and warm (§4.2), otherwise {@link QueryDevice#CPU}. A search
   * reads this to choose its semantic sub-budget cap.
   */
  default QueryDevice queryDevice() {
    return isGpuAvailable() ? QueryDevice.GPU : QueryDevice.CPU;
  }

  /**
   * Whether a query, which may not create sessions, would run now on the session it selects
   * (Spec D §4.2): a warm GPU session when the GPU is in use, otherwise an existing CPU session.
   * Readiness reads this. Handles without native session state are always warm.
   */
  default boolean isQueryWarm() {
    return true;
  }

  /**
   * Makes the CPU session of a GPU handle warm (created and run once) so that a query the device
   * cannot seat within its budget can run on it instead of falling back to keyword search. The
   * query encoders' prewarm calls this after their GPU session is warm. Handles without a GPU, or
   * without native session state, have nothing to warm and return false.
   *
   * @return whether the CPU session is warm for such queries afterwards
   */
  default boolean prewarmCpuForQueries(SessionAcquisitionRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    return false;
  }

  /**
   * Registers a listener run after any of this handle's sessions is created, released or reported
   * failed, which may change {@link #isQueryWarm()}. Listeners must only schedule work: they run
   * on the thread that changed the session. Handles without native session state never notify.
   */
  default void addGenerationListener(Runnable listener) {
    java.util.Objects.requireNonNull(listener, "listener");
  }

  /**
   * The device-memory plan this handle's calls are packed against (Spec E revision 4 §2.2), read
   * by the encoders on every call so that a new plan takes effect on the next call. Empty when no
   * plan is applied, in which case every call keeps today's shape. Step E7a applies no plan, so
   * every production handle returns empty.
   */
  default java.util.Optional<GpuArenaPlan> arenaPlan() {
    return java.util.Optional.empty();
  }

  /** Observability view of GPU state (readiness, fallback cause, etc.). */
  OrtCudaStatus status();

  /**
   * Releases the GPU session to free VRAM. Called by the signal-bus coordinator when the Main
   * process claims the GPU. Subsequent {@link #acquire(SessionAcquisitionRequest)} calls return CPU sessions until
   * arbitration returns the GPU to the Worker; at that point the next {@code acquire()}
   * lazily recreates the GPU session.
   */
  void releaseGpu();

  /**
   * Signals that the CPU session has returned bad output (NaN, BFCArena failure, etc.) — see
   * F-009. The handle tears down the current CPU session; the next {@link
   * #acquire(SessionAcquisitionRequest)} call that needs a CPU session will lazily recreate it.
   * Encoders call this from their
   * {@link ai.onnxruntime.OrtException} catch blocks, gated on {@link Lease#isCpu()} so only
   * CPU failures trigger recreation (GPU failures don't corrupt the CPU session). Tempdoc 397
   * §14.5 W6.
   *
   * <p>Tempdoc 414: callers pass a typed {@link io.justsearch.ort.telemetry.CpuRecreateCause} so
   * the {@code ort.session.recovery_total{cause}} metric carries the actual cause classification
   * (e.g., {@code BFC_ARENA_FAILURE} when {@link io.justsearch.ort.NativeSessionHandle#isBfcArenaFailure}
   * returned true). Use {@link io.justsearch.ort.telemetry.CpuRecreateCause#UNKNOWN} when the
   * caller can't classify.
   *
   * <p><strong>Thread-safety (§14.7 post-review Issue 5).</strong> Implementations must handle
   * concurrent access safely: a caller may invoke this method while another thread holds an
   * in-flight {@link Lease} backed by the session about to be torn down. Today's
   * {@link NativeSessionHandle}-backed implementation guards CPU-session lifecycle with internal
   * locking; native implementations in Stage 4 must preserve this. Callers are not required to
   * coordinate — the handle absorbs the race.
   */
  void reportCpuSessionFailure(io.justsearch.ort.telemetry.CpuRecreateCause cause);

  /**
   * Registers a callback invoked before the GPU session is torn down — used by encoders that
   * own GPU-allocated resources (e.g., SPLADE's pinned-memory output buffer) and must release
   * them before the session closes. Tempdoc 397 §14.5 W5.
   *
   * <p>Calling twice replaces the previous callback (at-most-one). Pass {@code null} to clear.
   * Contract matches {@link GpuLifecycleCallback}'s documentation: invocation happens once no GPU
   * inference on the session is in flight, so none can race with the cleanup (on the {@link
   * #releaseGpu()} caller's thread, or on the releasing thread of a run the session was retired
   * to).
   */
  void setLifecycleCallback(GpuLifecycleCallback callback);

  /**
   * Binds the recording hook invoked by every {@link Lease#run} / {@link Lease#runPinned} call
   * produced by subsequent {@link #acquire(SessionAcquisitionRequest)} / {@link
   * #acquireCpu(SessionAcquisitionRequest)} leases (tempdoc 710 Move 2).
   * A {@link Lease} captures the currently-bound recorder at acquisition time, so callers must
   * bind before the first {@code acquire()} that should be recorded — in practice, encoder
   * constructors bind immediately after constructing their {@code EncoderProfileAccumulator},
   * before any inference method can run.
   *
   * <p>Calling twice replaces the previous recorder (at-most-one, mirrors
   * {@link #setLifecycleCallback}). Pass {@code null} or omit to leave the default
   * {@link OrtRunRecorder#NOOP} in place — legitimate for CPU-only diagnostic sessions
   * (e.g. {@code ModelVerifier}) that never go through this interface at all.
   */
  void setOrtRunRecorder(OrtRunRecorder recorder);

  /** Typed disposition of this handle's monotonic native-session retirement. */
  enum RetirementStatus {
    ACTIVE,
    RETIRING,
    REFUSED,
    RETIRED
  }

  /**
   * Reports whether native retirement is active, still running, refused with resources retained,
   * or complete. A {@link #close()} timeout or native close failure is {@link
   * RetirementStatus#REFUSED}; callers may invoke {@code close()} again to retry.
   */
  RetirementStatus retirementStatus();

  /** Closes all sessions and releases resources. Idempotent. */
  @Override
  void close();

  /**
   * A lease on one exact ORT session instance. Closing it releases that instance's holder count
   * and the GPU device gate seat (if held). Release is idempotent; a lease never releases
   * or authorizes closure of a replacement session. Use with try-with-resources.
   *
   * <p>The {@code release} runnable captures any internal cleanup logic (seat release,
   * state-re-check) from the underlying session manager.
   *
   * <p>Tempdoc 397 §14.5 W3: the {@code isCpu} flag is set by {@link
   * SessionHandle#acquire(SessionAcquisitionRequest)} based on whether the underlying manager
   * handed back its CPU session or its GPU session.
   * Encoders that branch on "which path am I on?" — for CPU-fallback gating or
   * {@code reportCpuSessionFailure} guards — read this flag rather than compare raw session
   * identities against {@code peekCpuSession()}.
   *
   * <p><strong>Tempdoc 710 Move 2.</strong> {@link #run} / {@link #runPinned} are the choke
   * point every production ORT inference call must go through — an ArchUnit rule
   * ({@code OrtRunChokePointTest} in {@code app-launcher}) pins the invariant that no class
   * outside {@code io.justsearch.ort} invokes {@link OrtSession#run} directly. Callers that
   * still need the raw {@link #session()} (e.g. to pass it into a recursive per-doc/CPU-retry
   * helper that itself receives a {@code Lease}) should route through those helpers' own
   * {@code Lease}-typed parameters rather than unpacking {@code session()}/{@code runOptions()}.
   *
   * @param session the session to run inference on (non-null)
   * @param runOptions GPU-path {@link ai.onnxruntime.OrtSession.RunOptions}, or null for CPU
   * @param release invoked by {@link #close()} to release internal state
   * @param isCpu true if the lease is backed by the CPU session; false for the GPU session
   * @param recorder recording hook bound via {@link SessionHandle#setOrtRunRecorder} at the time
   *     this lease was acquired; never null ({@link OrtRunRecorder#NOOP} by default)
   * @param acquisition authority checked before each native run; null only for compatibility leases
   * @param cudaDevice the CUDA device ordinal a GPU lease's session runs on, or {@link #NO_DEVICE}
   *     for a CPU lease or a lease whose device is not known; a run on a known device that fails
   *     with a device-lost code latches that device's {@link DeviceHealth} (Spec E §6.2)
   */
  record Lease(
      OrtSession session,
      OrtSession.RunOptions runOptions,
      Runnable release,
      boolean isCpu,
      OrtRunRecorder recorder,
      SessionAcquisitionRequest acquisition,
      int cudaDevice)
      implements AutoCloseable {

    /** The {@link #cudaDevice()} of a lease that runs on no known CUDA device. */
    public static final int NO_DEVICE = -1;

    /** Compatibility constructor for leases without admitted request authority. */
    public Lease(OrtSession session, OrtSession.RunOptions runOptions, Runnable release,
        boolean isCpu, OrtRunRecorder recorder) {
      this(session, runOptions, release, isCpu, recorder, null, NO_DEVICE);
    }

    /** A lease on no known CUDA device. */
    public Lease(OrtSession session, OrtSession.RunOptions runOptions, Runnable release,
        boolean isCpu, OrtRunRecorder recorder, SessionAcquisitionRequest acquisition) {
      this(session, runOptions, release, isCpu, recorder, acquisition, NO_DEVICE);
    }

    public Lease {
      recorder = recorder != null ? recorder : OrtRunRecorder.NOOP;
      if (isCpu || cudaDevice < 0) cudaDevice = NO_DEVICE;
    }

    /**
     * Runs one ORT inference call with the lease's session + RunOptions (null-RunOptions-safe:
     * ORT Java's {@code run(Map, RunOptions)} overload does not accept a null {@code RunOptions},
     * so CPU leases — which never carry one — route through the single-arg overload instead) and
     * records its elapsed time via the bound {@link OrtRunRecorder} on success. A thrown
     * {@link OrtException} is not recorded (see {@link OrtRunRecorder} semantics) — callers
     * retrying on a fallback lease record that attempt separately, once each.
     *
     * <p>When the lease's acquisition asks to release the caller at its deadline (a query leg under
     * a semantic sub-budget, {@link SessionAcquisitionRequest#releaseCallerAtDeadline()}), this is
     * {@link #runWithin} with the acquisition's deadline (Spec D §1.5).
     *
     * @param inputs named input tensors
     * @return the ORT result; caller owns its lifecycle (try-with-resources)
     */
    public OrtSession.Result run(Map<String, OnnxTensor> inputs) throws OrtException {
      if (acquisition != null) {
        acquisition.remainingNanos();
        if (acquisition.releaseCallerAtDeadline()) {
          return DetachableRun.runWithin(this, inputs, acquisition.deadlineNanos());
        }
      }
      return runOnCallerThread(inputs);
    }

    /**
     * Runs one ORT inference call and returns to the caller no later than {@code deadlineNanos}
     * (Spec D §1.5). The native call runs on a platform run thread. If it has not returned by the
     * deadline, this throws {@link SessionAcquireDeadlineExceededException} with phase {@code RUN}
     * and the lease becomes detached: {@link #close()} is then a no-op, and the run thread releases
     * the lease, exactly once, when the native call returns. The run is not cancelled, so its
     * session (and GPU memory) stays held until then. Leases not issued by {@link
     * NativeSessionHandle} run on the caller's thread as {@link #run} does.
     *
     * @param inputs named input tensors; the run uses its own copies, so the caller may close them
     * @param deadlineNanos {@link System#nanoTime()} deadline for returning to the caller
     * @return the ORT result; caller owns its lifecycle (try-with-resources)
     */
    public OrtSession.Result runWithin(Map<String, OnnxTensor> inputs, long deadlineNanos)
        throws OrtException {
      if (acquisition != null) acquisition.remainingNanos();
      return DetachableRun.runWithin(this, inputs, deadlineNanos);
    }

    /** The native call on the current thread, recorded at the choke point. */
    OrtSession.Result runOnCallerThread(Map<String, OnnxTensor> inputs) throws OrtException {
      long start = System.nanoTime();
      OrtSession.Result result;
      try {
        result = runOptions != null ? session.run(inputs, runOptions) : session.run(inputs);
      } catch (OrtException failure) {
        throw reportIfDeviceLost(failure);
      }
      recorder.recordOrtRunNs(System.nanoTime() - start);
      return result;
    }

    /**
     * Spec E §6.2 item 1: a GPU run that fails with a code of the closed device-lost list latches
     * its device's {@link DeviceHealth}. The failure is returned unchanged, for the caller to throw.
     */
    private OrtException reportIfDeviceLost(OrtException failure) {
      if (cudaDevice != NO_DEVICE) {
        DeviceLostClassifier.classify(failure)
            .ifPresent(code -> DeviceHealth.forDevice(cudaDevice).reportLost(code));
      }
      return failure;
    }

    /**
     * Runs one ORT inference call into caller-managed pinned output tensors (SPLADE's
     * pinned-output fast path) and records its elapsed time via the bound {@link OrtRunRecorder}
     * on success. The returned {@link OrtSession.Result} is closed immediately — pinned outputs
     * are read from the caller's own buffer, not from the result.
     *
     * @param inputs named input tensors
     * @param pinnedOutputs caller-owned output tensors ORT writes into directly
     */
    public void runPinned(Map<String, OnnxTensor> inputs, Map<String, OnnxValue> pinnedOutputs)
        throws OrtException {
      if (acquisition != null) acquisition.remainingNanos();
      long start = System.nanoTime();
      try {
        session.run(inputs, Collections.emptySet(), pinnedOutputs, runOptions).close();
      } catch (OrtException failure) {
        throw reportIfDeviceLost(failure);
      }
      recorder.recordOrtRunNs(System.nanoTime() - start);
    }

    @Override
    public void close() {
      release.run();
    }
  }
}
