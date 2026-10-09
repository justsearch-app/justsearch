/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.handle;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.holdBackground;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.hybridLeg;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.warmCpu;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * What the CPU recourse records, at every tracing level: for the leg, on its thread, which device
 * its lease was on and whether the recourse took it (the search's trace reads it); for the process,
 * when the recourse last took a query leg (the worker health reports it to status). A CPU lease
 * the caller asked for, and a leg the GPU seats, are not recourse.
 */
final class NativeSessionHandleQueryCpuRecourseTest {

  @Test
  void aTurnedAwayLegIsRecordedForItsTraceAndForStatus() throws Exception {
    NativeSessionHandle handle = handle("query-cpu-recourse", 88);
    warmCpu(handle);

    // P1: a free device seats the leg on the GPU; that is not recourse.
    try (QueryCpuRecourse.Observation idle = QueryCpuRecourse.observe()) {
      try (SessionHandle.Lease lease = handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
        assertFalse(lease.isCpu(), "an idle device serves the query leg on the GPU");
      }
      assertEquals(new QueryCpuRecourse.Served("gpu", null), idle.served());
      assertEquals("device=gpu", idle.served().traceDetail());
    }

    // A CPU lease asked for (an OOM retry, a warm-up) is on the CPU without recourse.
    try (QueryCpuRecourse.Observation asked = QueryCpuRecourse.observe()) {
      warmCpu(handle);
      assertEquals(new QueryCpuRecourse.Served("cpu", null), asked.served());
    }

    OptionalLong before = QueryCpuRecourse.lastTakenNanos();
    try (SessionHandle.Lease bulk = holdBackground(handle);
        QueryCpuRecourse.Observation busy = QueryCpuRecourse.observe()) {
      assertFalse(bulk.isCpu(), "precondition: the bulk lease holds the device's seat");
      try (SessionHandle.Lease lease = handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
        assertTrue(lease.isCpu(), "the turned-away leg runs on the CPU session");
      }
      assertEquals(new QueryCpuRecourse.Served("cpu", "contended"), busy.served());
      assertEquals("device=cpu; cpu-recourse=contended", busy.served().traceDetail());
    }
    OptionalLong taken = QueryCpuRecourse.lastTakenNanos();
    assertEquals(before, taken, "selecting a CPU lease does not certify a successful encoding");
  }

  @Test
  void anObservationSeesOnlyTheLeasesOfItsOwnThreadAndScope() throws Exception {
    NativeSessionHandle handle = handle("query-cpu-recourse-scope", 89);
    try (QueryCpuRecourse.Observation outer = QueryCpuRecourse.observe()) {
      try (QueryCpuRecourse.Observation inner = QueryCpuRecourse.observe()) {
        try (SessionHandle.Lease lease = handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
          assertFalse(lease.isCpu());
        }
        assertEquals("gpu", inner.served().device());
      }
      assertNull(outer.served(), "the inner leg's lease is not the outer observation's");
      Thread other =
          new Thread(
              () -> {
                try (SessionHandle.Lease lease =
                    handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
                  assertFalse(lease.isCpu());
                }
              });
      other.start();
      other.join(10_000);
      assertNull(outer.served(), "another thread's lease is not this thread's leg");
    }
  }
}
