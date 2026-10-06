/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Preservation guard for the CPU recourse of turned-away query legs: only foreground requests with
 * a semantic deadline, on a handle whose CPU session is warm, may take it. Bulk work, prewarm and
 * the reranker (no semantic deadline) still wait for the device and miss at their deadline, and a
 * handle whose CPU session has never run still turns a query leg away at its deadline.
 *
 * <p>Same seam as NativeSessionHandleCpuRecourseTest. It uses only API that exists before the
 * change, and passes before and after it.
 */
final class NativeSessionHandleCpuRecourseBoundsTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  @Test
  void bulkPrewarmAndRerankerNeverRunOnTheCpuWhileTheDeviceIsBusy() throws Exception {
    NativeSessionHandle handle = handle("cpu-recourse-bounds", 83);
    try (SessionHandle.Lease warm =
            handle.acquireCpu(
                SessionAcquisitionRequest.within(
                    SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30)));
        OrtSession.Result ignored = warm.run(Map.of())) {
      assertTrue(warm.isCpu(), "precondition: the CPU session ran");
    }
    try (SessionHandle.Lease bulk = holdBackground(handle)) {
      var background =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () ->
                  handle.acquire(
                      SessionAcquisitionRequest.within(
                          SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofMillis(300))),
              "bulk work took the CPU session while the device was busy");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, background.phase());
      // Prewarm and the reranker: foreground, may create sessions, not released at a deadline.
      var unbudgeted =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () ->
                  handle.acquire(
                      SessionAcquisitionRequest.within(
                          SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofMillis(300))),
              "a request without a semantic deadline took the CPU session");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, unbudgeted.phase());
    }
  }

  @Test
  void aQueryLegWithoutAWarmCpuSessionIsStillTurnedAwayAtItsDeadline() throws Exception {
    NativeSessionHandle handle = handle("cpu-recourse-cold", 84);
    try (SessionHandle.Lease bulk = holdBackground(handle)) {
      var turnedAway =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () ->
                  handle.acquire(
                      new SessionAcquisitionRequest(
                          SessionAcquisitionRequest.Urgency.FOREGROUND,
                          System.nanoTime() + Duration.ofMillis(500).toNanos(),
                          () -> false,
                          /* mayCreateGpuSession= */ false,
                          /* releaseCallerAtDeadline= */ true)),
              "a CPU session that never ran was used as a warm one");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, turnedAway.phase());
    }
  }

  private static SessionHandle.Lease holdBackground(SessionHandle handle) {
    SessionHandle.Lease lease =
        handle.acquire(
            SessionAcquisitionRequest.within(
                SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofMinutes(1)));
    assertFalse(lease.isCpu(), "the fixture must hold the device seat");
    return lease;
  }

  private static NativeSessionHandle handle(String name, int device) throws Exception {
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

  private static OrtSession answering() throws Exception {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> mock(OrtSession.Result.class));
    return session;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
