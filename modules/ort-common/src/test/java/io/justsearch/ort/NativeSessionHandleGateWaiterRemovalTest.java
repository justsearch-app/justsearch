/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec D test G6 (D1, §3.2 rule 6): waiter removal at the device gate. Uses a device id of its own,
 * so no other test's gate is involved.
 */
final class NativeSessionHandleGateWaiterRemovalTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  /**
   * G6 (D1): a query queued behind a 1 s bulk run leaves the device's queue at its own deadline,
   * with the {@code GATE} phase (the {@code contended} cause), long before the bulk run ends; it is
   * never seated or run afterwards, and the seat the bulk run frees is free.
   */
  @Test
  void aQueryThatTimesOutBehindABulkRunLeavesTheQueueAndNeverRuns() throws Exception {
    int device = 43;
    CountDownLatch running = new CountDownLatch(1);
    OrtSession bulkSession = mock(OrtSession.class);
    when(bulkSession.run(anyMap())).thenAnswer(invocation -> {
      running.countDown();
      Thread.sleep(1_000);
      return mock(OrtSession.Result.class);
    });
    OrtSession querySession = mock(OrtSession.class);
    NativeSessionHandle bulk = handle("g6-bulk", device, bulkSession);
    NativeSessionHandle query = handle("g6-query", device, querySession);
    AtomicReference<Throwable> bulkFailure = new AtomicReference<>();
    Thread bulkRun = new Thread(() -> {
      try (SessionHandle.Lease lease =
          bulk.acquire(request(SessionAcquisitionRequest.Urgency.BACKGROUND))) {
        lease.run(Map.of()).close();
      } catch (Throwable thrown) {
        bulkFailure.set(thrown);
      }
    }, "g6-bulk-run");
    try {
      bulkRun.start();
      assertTrue(running.await(5, TimeUnit.SECONDS));

      long start = System.nanoTime();
      var timedOut =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () -> query.acquire(
                  SessionAcquisitionRequest.within(
                      SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofMillis(200))));
      long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, timedOut.phase());
      assertTrue(waitedMs < 700, "the caller left at its deadline, not with the bulk run: " + waitedMs);
      assertEquals(0, DeviceEncoderGates.forDevice(device).waiting(), "the waiter was removed");

      bulkRun.join(5_000);
      assertFalse(bulkRun.isAlive());
      assertEquals(null, bulkFailure.get());
      verify(querySession, never()).run(anyMap());
      verify(querySession, never()).run(anyMap(), any(OrtSession.RunOptions.class));
      try (SessionHandle.Lease next =
          bulk.acquire(
              SessionAcquisitionRequest.within(
                  SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofMillis(200)))) {
        assertFalse(next.isCpu(), "no dropped waiter holds the freed seat");
      }
    } finally {
      bulkRun.join(5_000);
      bulk.close();
      query.close();
    }
  }

  private static SessionAcquisitionRequest request(SessionAcquisitionRequest.Urgency urgency) {
    return SessionAcquisitionRequest.within(urgency, Duration.ofSeconds(30));
  }

  private static NativeSessionHandle handle(String name, int device, OrtSession gpuSession)
      throws Exception {
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
    set(handle, "gpuSession", gpuSession);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    return handle;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
