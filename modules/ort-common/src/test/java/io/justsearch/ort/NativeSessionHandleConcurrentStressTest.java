package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tempdoc 397 §14.16 (Phase 2) + §14.21 R4 coverage expansion: concurrent stress test
 * establishing the behavioural baseline for {@link NativeSessionHandle}'s lifecycle machinery. Any
 * future change to the state machine must keep this test green to prove no concurrency semantics
 * regressed.
 *
 * <p>Thread mix:
 *
 * <ul>
 *   <li>5 acquire threads: tight {@code try (var lease = handle.acquire()) {...}} loop
 *   <li>2 cpu-failure-report threads: {@code reportCpuSessionFailure()} every 3 s (staggered)
 *   <li>1 release-GPU thread: {@code releaseGpu()} every 2 s — no-op on CPU-only handle, but
 *       exercises the defensive path + {@code gpuInferenceSemaphore} acquire/release
 *   <li><b>R4:</b> 1 metadata-read thread: {@code inputNames()} + {@code outputNames()} every
 *       200 ms — exercises concurrent metadata reads against CPU-session recreation
 *   <li><b>R4:</b> 1 delayed-close thread: fires {@code close()} after {@value #CLOSE_AT_MS} ms
 *       into the run; subsequent acquires must complete without crash (contract: post-close
 *       acquires may degrade gracefully but never leak a dangling lease or throw NPE)
 * </ul>
 *
 * <p><b>Invariants exercised</b> (mapping to tempdoc 397 §13.1 Stage 4e.1 plan):
 *
 * <ul>
 *   <li>#3 deferred CPU recreation — covered (staggered failure reports under cpuSessionLock)
 *   <li>#5 {@code onBeforeGpuRelease} dispatch — partially covered (CPU-only, callback-free path)
 *   <li><b>NOT COVERED</b> #1 gpuSessionLock → cpuSessionLock ordering (requires CUDA)
 *   <li><b>NOT COVERED</b> #2 semaphore re-check after acquireUninterruptibly (requires CUDA)
 *   <li><b>NOT COVERED</b> #4 60 s retry trigger (requires GPU failure state)
 * </ul>
 *
 * <p>A GPU-enabled stress variant would close invariants #1/#2/#4 but needs CUDA native libs +
 * VRAM budget that aren't guaranteed in CI. Deferred to future work (proposed tempdoc 398).
 *
 * <p>Frequencies chosen to match realistic production load: CPU-session failure is rare
 * (BFCArena OOM on long inputs — hours between occurrences). A 3 s interval gives ~10 recreate
 * cycles across the 30 s run — enough to surface races without thrashing a 300 MB ONNX model to
 * its knees. Aggressive millisecond-scale injection is strictly worse: it forces session
 * rebuilds back-to-back and prevents acquire threads from observing a steady state (a first
 * draft at 50 ms intervals tripped an ORT-level DefaultLogger race between InferenceSession
 * dtor + ctor across threads — an ORT binding limitation, not a Java-side bug).
 *
 * <p>Tagged {@code @Tag("stress")} — excluded from default {@code ./gradlew test} by the
 * convention plugin. Opt in with {@code -PincludeStress=true} for nightly / merge-gate runs.
 * Skipped (via {@code assumeTrue}) if no embedding model is present on disk.
 */
@DisplayName("NativeSessionHandle — concurrent stress test (§14.16 + §14.21 R4)")
@Tag("stress")
final class NativeSessionHandleConcurrentStressTest {

  private static final int DURATION_MS =
      Integer.getInteger("justsearch.ort.stress.durationMs", 30_000);
  private static final int CPU_FAILURE_INTERVAL_MS = 3_000;
  private static final int RELEASE_GPU_INTERVAL_MS = 2_000;
  private static final int METADATA_READ_INTERVAL_MS = 200;
  /** Fire close() at this point in the run so acquire threads overlap the close window. */
  private static final int CLOSE_AT_MS = 25_000;
  private static final int ACQUIRE_THREADS = 5;
  private static final int CPU_FAILURE_THREADS = 2;
  private static final int RELEASE_THREADS = 1;
  private static final int METADATA_THREADS = 1;
  private static final int CLOSE_THREADS = 1;

  @Test
  @DisplayName("10-thread mix: no deadlocks, no NPEs, consistent final state, post-close safety")
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void stressTenThreads() throws Exception {
    Path modelDir = findModelDir();
    assumeTrue(
        modelDir != null,
        "No embedding model on disk under models/onnx/embedding or"
            + " models/onnx/gte-multilingual-base — walked "
            + io.justsearch.ort.testing.ModelDirTestResolver.MAX_WALK_DEPTH
            + " parent director(ies) up from "
            + System.getProperty("user.dir")
            + "; skipping stress test");
    Path cpuModelPath = modelDir.resolve("model.onnx");
    assumeTrue(Files.exists(cpuModelPath), "model.onnx not found at " + cpuModelPath);

    // CPU-only handle. See class-level Javadoc for the invariant coverage matrix.
    // Tempdoc 397 §14.28 U1: buildFallback deleted; stress test constructs the Composition
    // inline (ort-common cannot depend on worker-core's testFixtures helper — circular).
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            /* gpuConfig= */ null,
            /* cpuOptLevel= */ null,
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ false,
            NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
    Composition comp =
        new Composition(
            RuntimePolicy.defaults(), policy, new ModelArtifacts(cpuModelPath, cpuModelPath));
    // Tempdoc 414 v2 (A4): wire a recording events recorder so the stress run validates the
    // events-interface plumbing end-to-end. CPU-only handle cannot exercise GpuFallbackTaken
    // (the GPU branch in acquire() never fires when gpuConfig=null) — that case is gated on
    // the CUDA stress lane (parked tempdoc 398). The CpuSessionRecreated assertion below
    // validates that at least the CPU-recovery path's events plumbing is wired.
    io.justsearch.ort.telemetry.RecordingOrtSessionTelemetryEvents recorder =
        new io.justsearch.ort.telemetry.RecordingOrtSessionTelemetryEvents();
    SessionHandle handle =
        OrtSessionAssembler.buildManager("stress", comp, () -> false, recorder);

    // Warm-up: one acquire before threads start so the initial CPU session is materialised and
    // the cpuSessionLock's first contention moment isn't timed against cold initialisation.
    try (SessionHandle.Lease warm = handle.acquire(request())) {
      assertTrue(warm.isCpu());
    }

    AtomicBoolean running = new AtomicBoolean(true);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicInteger leasesAcquired = new AtomicInteger();
    AtomicInteger leasesClosed = new AtomicInteger();
    AtomicInteger cpuFailuresReported = new AtomicInteger();
    AtomicInteger releaseGpuCalls = new AtomicInteger();
    AtomicInteger metadataReads = new AtomicInteger();
    AtomicInteger postCloseAcquires = new AtomicInteger();
    AtomicInteger postRetirementLeasesIssued = new AtomicInteger();
    AtomicInteger retirementRefusals = new AtomicInteger();
    List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    int totalThreads =
        ACQUIRE_THREADS + CPU_FAILURE_THREADS + RELEASE_THREADS + METADATA_THREADS + CLOSE_THREADS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(totalThreads);
    Thread[] threads = new Thread[totalThreads];
    Thread.UncaughtExceptionHandler handler = (t, e) -> uncaught.add(e);
    int idx = 0;

    // Acquire threads: tight lease loop. Before retirement, a refusal is a defect. Once the
    // typed retirement state leaves ACTIVE, acquisition must refuse; after close returns no lease
    // may be issued at all.
    for (int i = 0; i < ACQUIRE_THREADS; i++) {
      threads[idx] = new Thread(() -> {
        try {
          startLatch.await();
          while (running.get()) {
            try {
              SessionHandle.Lease acquiredLease = handle.acquire(request());
              leasesAcquired.incrementAndGet();
              try (SessionHandle.Lease lease = acquiredLease) {
                if (closed.get()) {
                  postRetirementLeasesIssued.incrementAndGet();
                }
                if (lease.session() == null) {
                  throw new IllegalStateException("lease.session() is null on CPU path");
                }
                if (!lease.isCpu()) {
                  throw new IllegalStateException("CPU-only handle returned non-CPU lease");
                }
              } finally {
                leasesClosed.incrementAndGet();
              }
            } catch (RuntimeException rex) {
              if (handle.retirementStatus() != SessionHandle.RetirementStatus.ACTIVE) {
                retirementRefusals.incrementAndGet();
                if (closed.get()) {
                  postCloseAcquires.incrementAndGet();
                }
              } else {
                throw rex;
              }
            }
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      }, "stress-acquire-" + i);
      threads[idx].setUncaughtExceptionHandler(handler);
      idx++;
    }

    // CPU failure-report threads: every 3 s, request a recreation.
    for (int i = 0; i < CPU_FAILURE_THREADS; i++) {
      final long stagger = (long) (CPU_FAILURE_INTERVAL_MS / 2) * i;
      threads[idx] = new Thread(() -> {
        try {
          startLatch.await();
          Thread.sleep(stagger);
          while (running.get()) {
            handle.reportCpuSessionFailure(io.justsearch.ort.telemetry.CpuRecreateCause.UNKNOWN);
            cpuFailuresReported.incrementAndGet();
            Thread.sleep(CPU_FAILURE_INTERVAL_MS);
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      }, "stress-cpu-fail-" + i);
      threads[idx].setUncaughtExceptionHandler(handler);
      idx++;
    }

    // Release-GPU thread: no-op on CPU-only handle, but exercises the defensive path.
    threads[idx] = new Thread(() -> {
      try {
        startLatch.await();
        while (running.get()) {
          handle.releaseGpu();
          releaseGpuCalls.incrementAndGet();
          Thread.sleep(RELEASE_GPU_INTERVAL_MS);
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      } finally {
        doneLatch.countDown();
      }
    }, "stress-release-gpu");
    threads[idx].setUncaughtExceptionHandler(handler);
    idx++;

    // Tempdoc 397 §14.24 FD-ProbeDeletion: metadata-read thread deleted along with the
    // SessionHandle.inputNames/outputNames interface methods. Probe concurrency semantics are
    // no longer a runtime concern — the probe happens once at composition root, not during
    // inference. Keeping a placeholder no-op thread in this slot preserves the
    // totalThreads=10 count without re-wiring the latch + allocator.
    threads[idx] = new Thread(() -> {
      try {
        startLatch.await();
        while (running.get()) {
          Thread.sleep(METADATA_READ_INTERVAL_MS);
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      } finally {
        doneLatch.countDown();
      }
    }, "stress-noop-placeholder");
    threads[idx].setUncaughtExceptionHandler(handler);
    idx++;

    // R4 — Delayed-close thread: fires close() at CLOSE_AT_MS so acquire threads overlap the
    // close window. Post-close, other threads must not crash with NPE; they may throw or return
    // closed-session leases, both of which are tolerated.
    threads[idx] = new Thread(() -> {
      try {
        startLatch.await();
        Thread.sleep(CLOSE_AT_MS);
        handle.close();
        closed.set(true);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      } finally {
        doneLatch.countDown();
      }
    }, "stress-delayed-close");
    threads[idx].setUncaughtExceptionHandler(handler);

    for (Thread t : threads) {
      t.start();
    }

    startLatch.countDown();
    Thread.sleep(DURATION_MS);
    running.set(false);

    // 90 s join budget: worst case is an acquire thread mid-createSession on a 300 MB model
    // (~5 s) plus cpu-failure threads sleeping 3 s. 90 s is generous but still trips a real
    // deadlock.
    boolean allDone = doneLatch.await(90, TimeUnit.SECONDS);
    assertTrue(
        allDone,
        "Not all threads terminated within 90 s — possible deadlock. "
            + summarize(leasesAcquired, leasesClosed, cpuFailuresReported, releaseGpuCalls,
                metadataReads, postCloseAcquires));

    assertTrue(uncaught.isEmpty(), "Uncaught exceptions in stress threads: " + uncaught);

    assertEquals(
        leasesAcquired.get(),
        leasesClosed.get(),
        "Leases acquired and closed must balance (no dangling leases)");

    assertTrue(leasesAcquired.get() > 0, "No leases were acquired — threads didn't run?");
    assertTrue(cpuFailuresReported.get() > 0, "No CPU failures reported");
    // Tempdoc 414 v2 (A4): events-interface plumbing assertion. The cpu-failure threads call
    // reportCpuSessionFailure every 3 s, which (after a subsequent acquire on the CPU path)
    // produces CpuSessionRecreated events. If this count is zero, the events plumbing is
    // broken — the production OrtSessionTelemetryAdapter would emit no recovery_total either.
    long recreatedCount =
        recorder.countOf(io.justsearch.ort.telemetry.TransitionReason.CpuSessionRecreated.class);
    assertTrue(
        recreatedCount > 0,
        "No CpuSessionRecreated events captured — events-interface plumbing broken? "
            + "(transitions=" + recorder.transitions.size() + ")");
    // Tempdoc 397 §14.24 FD-ProbeDeletion: metadata-reads counter retained but no longer
    // asserted — the metadata-read thread was deleted with the SessionHandle.inputNames
    // interface method. Leaving the counter zero is expected.
    assertTrue(closed.get(), "close() thread did not run");
    assertEquals(SessionHandle.RetirementStatus.RETIRED, handle.retirementStatus());
    assertEquals(
        0,
        postRetirementLeasesIssued.get(),
        "retirement is monotonic: no CPU or GPU lease may be issued after close returns");
    // The window after close confirms callers actually exercised the refusal path.
    assertTrue(
        retirementRefusals.get() > 0,
        "Retirement refusal path did not run — window too narrow?");

    // close() is idempotent per the SessionHandle contract.
    handle.close();
    assertFalse(handle.isGpuAvailable());

    System.out.printf(
        "Stress test OK: %d ms | %d leases (acquired == closed) | %d cpu-failures | %d release-gpu "
            + "| %d metadata-reads | %d post-close acquires%n",
        DURATION_MS,
        leasesAcquired.get(),
        cpuFailuresReported.get(),
        releaseGpuCalls.get(),
        metadataReads.get(),
        postCloseAcquires.get());
  }

  // =========================================================================
  // Helpers.
  // =========================================================================

  // Tempdoc 710 Move 6: shared walker (obs:spladebatchsweeptest) — this used to walk only 5
  // parent dirs, silently skipping in every worktree session. Tries "models/onnx/embedding"
  // first, then falls back to "models/onnx/gte-multilingual-base" (this repo's real embedding
  // model directory).
  private static Path findModelDir() {
    io.justsearch.ort.testing.ModelDirTestResolver.Discovery embedding =
        io.justsearch.ort.testing.ModelDirTestResolver.discover(
            "models/onnx/embedding", null, "model.onnx");
    if (embedding.modelDir() != null) {
      return embedding.modelDir();
    }
    io.justsearch.ort.testing.ModelDirTestResolver.Discovery gte =
        io.justsearch.ort.testing.ModelDirTestResolver.discover(
            "models/onnx/gte-multilingual-base", null, "model.onnx");
    return gte.modelDir();
  }

  private static SessionAcquisitionRequest request() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(5));
  }

  private static String summarize(AtomicInteger... counters) {
    StringBuilder sb = new StringBuilder();
    String[] names = {
      "acquired", "closed", "cpuFails", "releaseGpu", "metadataReads", "postCloseAcquires"
    };
    for (int i = 0; i < counters.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(names[i]).append('=').append(counters[i].get());
    }
    return sb.toString();
  }
}
