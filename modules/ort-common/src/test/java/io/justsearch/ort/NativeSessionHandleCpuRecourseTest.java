/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Finding 1 of the search-gate verification (verify-d810): a foreground query leg that the device
 * cannot seat within its budget runs on the handle's warm CPU session instead of giving up its
 * semantics. An idle device still serves it on the GPU (P1). The trace record of the CPU run is
 * checked by NativeSessionHandleCpuRecourseTraceTest.
 *
 * <p>Real seam: a real {@code NativeSessionHandle} built through the production assembler, on its
 * device's real gate; only the native sessions are mocks. The CPU session is made warm the way any
 * CPU run makes it warm, through {@code acquireCpu} and a run, so the check uses only API that
 * exists before the change and runs on the before-state, where the leg fails at its deadline.
 */
final class NativeSessionHandleCpuRecourseTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  @Test
  void aContendedQueryLegRunsOnTheWarmCpuSessionWithinItsBudget() throws Exception {
    NativeSessionHandle handle = handle("cpu-recourse-hybrid", 81);
    warmCpu(handle);

    // P1: with the device free, the leg is seated on the GPU, warm CPU session or not.
    try (SessionHandle.Lease idle = handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
      assertFalse(idle.isCpu(), "an idle device serves the query leg on the GPU");
    }

    try (SessionHandle.Lease bulk = holdBackground(handle)) {
      long start = System.nanoTime();
      SessionHandle.Lease lease =
          assertDoesNotThrow(
              () -> handle.acquire(hybridLeg(Duration.ofSeconds(3))),
              "the leg was turned away although a warm CPU session was there");
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      try (lease) {
        assertTrue(lease.isCpu(), "the turned-away leg runs on the CPU session");
        assertTrue(lease.run(Map.of()) != null, "the CPU run answers");
      }
      assertTrue(elapsedMs >= 800, "the leg waited for a seat first, " + elapsedMs + " ms");
      assertTrue(elapsedMs < 2_000, "the leg kept time for its CPU run, " + elapsedMs + " ms");
    }
  }

  @Test
  void aContendedVectorOnlyLegAlsoRunsOnTheWarmCpuSession() throws Exception {
    NativeSessionHandle handle = handle("cpu-recourse-vector", 82);
    warmCpu(handle);
    try (SessionHandle.Lease bulk = holdBackground(handle)) {
      long start = System.nanoTime();
      try (SessionHandle.Lease lease =
          assertDoesNotThrow(
              () -> handle.acquire(vectorOnlyLeg(Duration.ofSeconds(3))),
              "the VECTOR-only leg was turned away although a warm CPU session was there")) {
        assertTrue(lease.isCpu(), "the turned-away VECTOR-only leg runs on the CPU session");
      }
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsedMs < 2_000, "the leg kept time for its CPU run, " + elapsedMs + " ms");
    }
  }

  /** A hybrid query leg: foreground, keyword fallback (may not create sessions), released at D. */
  static SessionAcquisitionRequest hybridLeg(Duration budget) {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + budget.toNanos(),
        () -> false,
        /* mayCreateGpuSession= */ false,
        /* releaseCallerAtDeadline= */ true);
  }

  /** A VECTOR-only leg: foreground, no keyword fallback, released at its deadline. */
  static SessionAcquisitionRequest vectorOnlyLeg(Duration budget) {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + budget.toNanos(),
        () -> false,
        /* mayCreateGpuSession= */ true,
        /* releaseCallerAtDeadline= */ true);
  }

  /** Holds the handle's device seat as a bulk-indexing run would. */
  static SessionHandle.Lease holdBackground(SessionHandle handle) {
    SessionHandle.Lease lease =
        handle.acquire(
            SessionAcquisitionRequest.within(
                SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofMinutes(1)));
    assertFalse(lease.isCpu(), "the fixture must hold the device seat");
    return lease;
  }

  /** Runs the CPU session once, as any CPU run does. */
  static void warmCpu(SessionHandle handle) throws Exception {
    try (SessionHandle.Lease lease =
            handle.acquireCpu(
                SessionAcquisitionRequest.within(
                    SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30)));
        OrtSession.Result ignored = lease.run(Map.of())) {
      assertTrue(lease.isCpu(), "precondition: the warm-up ran on the CPU session");
    }
  }

  /** A GPU handle on {@code device} whose GPU session is warm and whose CPU session exists. */
  static NativeSessionHandle handle(String name, int device) throws Exception {
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(device, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
    NativeSessionHandle handle =
        (NativeSessionHandle)
            OrtSessionAssembler.buildManager(
                name,
                new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(MODEL, MODEL)),
                () -> true);
    set(handle, "gpuSession", answering());
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    set(handle, "cpuSession", answering());
    return handle;
  }

  static OrtSession answering() throws Exception {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> mock(OrtSession.Result.class));
    return session;
  }

  static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
