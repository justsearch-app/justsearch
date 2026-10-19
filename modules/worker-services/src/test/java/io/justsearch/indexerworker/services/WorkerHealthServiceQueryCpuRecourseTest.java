/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.holdBackground;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ort.QueryCpuRecourse;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract X2 of b6d536, the worker side of the query-on-CPU status: after a foreground query leg
 * the device turned away successfully encoded on the warm CPU session, the worker's health check reports when
 * that happened (epoch ms), which the Head reads as {@code encoders.health.query_on_cpu}.
 *
 * <p>The leg acquires a real {@code NativeSessionHandle} on its device's real gate while a bulk
 * lease holds the device's seat; only the native sessions are mocks.
 */
final class WorkerHealthServiceQueryCpuRecourseTest {

  @Test
  void theHealthCheckReportsOnlySuccessfullyServedCpuQueryLegs() throws Exception {
    try (SessionHandle handle = gpuHandle("health-cpu-recourse", 57, answering())) {
      Field cpuSession = handle.getClass().getDeclaredField("cpuSession");
      cpuSession.setAccessible(true);
      cpuSession.set(handle, answering());
      try (SessionHandle.Lease warm =
              handle.acquireCpu(
                  SessionAcquisitionRequest.within(
                      SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30)));
          OrtSession.Result ignored = warm.run(Map.of())) {
        assertTrue(warm.isCpu(), "precondition: the warm-up ran on the CPU session");
      }

      long before = System.currentTimeMillis();
      var priorSuccess = QueryCpuRecourse.lastTakenNanos();
      try (SessionHandle.Lease bulk = holdBackground(handle);
          var observed = QueryCpuRecourse.observe();
          SessionHandle.Lease leg = handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
        assertFalse(bulk.isCpu(), "precondition: the bulk lease holds the device's seat");
        assertTrue(leg.isCpu(), "the turned-away query leg was taken to the CPU session");
        assertEquals(priorSuccess, QueryCpuRecourse.lastTakenNanos(),
            "a lease selection cannot claim successful CPU service");
        try (OrtSession.Result result = leg.run(Map.of())) {
          assertNotNull(result, "the CPU leg successfully returned");
        }
        try { observed.getClass().getMethod("succeeded").invoke(observed); }
        catch (NoSuchMethodException beforeRepair) {
          // The baseline counts at acquisition and has no success notification API.
        }
      }

      long at =
          new WorkerHealthService("1.0.0-test")
              .check(HealthCheckRequest.getDefaultInstance())
              .getQueryCpuRecourseAtMs();
      long reported = System.currentTimeMillis();
      // The time is monotonic in the worker and placed on the wall clock at the report; allow the
      // rounding of that placement.
      assertTrue(
          at >= before - 5 && at <= reported + 5,
          () -> "the reported time " + at + " is not that of the turned-away leg ["
              + before + ", " + reported + "]");
    }
  }

  /** A hybrid query leg: foreground, keyword fallback (may not create sessions), released at D. */
  private static SessionAcquisitionRequest hybridLeg(Duration budget) {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + budget.toNanos(),
        () -> false,
        /* mayCreateGpuSession= */ false,
        /* releaseCallerAtDeadline= */ true);
  }

  private static OrtSession answering() throws Exception {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> mock(OrtSession.Result.class));
    return session;
  }
}
