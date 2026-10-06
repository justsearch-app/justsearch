/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Spec E §6.2 with Spec D §2: a lost device is not a stuck one, and GPU errors that are not device
 * loss latch nothing (the reporting half of E-S11b).
 *
 * <p>A stuck device has a GPU run that has not returned: the device's breaker opens after {@code
 * stuckAfterMs}, turns query legs with a keyword fallback away at the gate, and closes when the run
 * returns; the device is not lost. A lost device has a run that returned a listed CUDA error: its
 * handles stop selecting the GPU before the gate, the breaker stays closed, and the same query leg
 * runs on CPU. Real {@link NativeSessionHandle}s on their devices' real gates and real clock; only
 * the native sessions are mocks.
 */
final class DeviceLostIsNotStuckTest {

  /** Spec D §2.1 {@code stuckAfterMs}. */
  private static final long STUCK_AFTER_MS = DeviceGatePolicy.DEFAULTS.stuckAfterMs();

  @Test
  void aStuckRunOpensTheBreakerButLatchesNothingWhileALostDeviceSkipsTheGate() throws Exception {
    int device = 65;
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch stuckRunning = new CountDownLatch(1);
    CountDownLatch stuckMayReturn = new CountDownLatch(1);
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap()))
        .thenAnswer(
            invocation -> {
              switch (runs.incrementAndGet()) {
                case 1:
                  return mock(OrtSession.Result.class); // warms the session for query legs
                case 2:
                  stuckRunning.countDown();
                  stuckMayReturn.await(60, TimeUnit.SECONDS);
                  return mock(OrtSession.Result.class);
                default:
                  throw new OrtException(NativeSessionHandleDeviceLostTest.CUDA_719);
              }
            });
    NativeSessionHandle handle =
        NativeSessionHandleDeviceLostTest.handle(
            "lost-not-stuck", device, gpu,
            NativeSessionHandleDeviceLostTest.cpuSessionReturning(new float[][] {{1f}}));
    DeviceEncoderGate gate = DeviceEncoderGates.forDevice(device);
    DeviceHealth health = DeviceHealth.forDevice(device);

    try (SessionHandle.Lease warm = handle.acquire(required())) {
      warm.run(Map.of()).close();
    }
    Thread stuck =
        new Thread(
            () -> {
              try (SessionHandle.Lease lease = handle.acquire(background())) {
                lease.run(Map.of()).close();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            "stuck-run");
    stuck.start();
    try {
      assertTrue(stuckRunning.await(10, TimeUnit.SECONDS), "the stuck run started");
      TimeUnit.MILLISECONDS.sleep(STUCK_AFTER_MS + 300);

      // Stuck: the breaker opens and turns the query leg away at the gate; nothing is latched.
      assertTrue(gate.breakerOpenSinceNanos().isPresent(), "a stuck run opens the breaker");
      SessionAcquireDeadlineExceededException turnedAway =
          assertThrows(SessionAcquireDeadlineExceededException.class,
              () -> handle.acquire(queryLeg()));
      assertTrue(turnedAway.breakerOpen(), "the query leg is turned away by the breaker");
      assertFalse(health.isLost(), "a stuck run is not device loss");
      assertEquals(QueryDevice.GPU, handle.queryDevice(), "a stuck device keeps its GPU session");
    } finally {
      stuckMayReturn.countDown();
      stuck.join(10_000);
    }
    assertFalse(stuck.isAlive());
    assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "the returned run closed the breaker");

    // Lost: the run returns 719; the device latches, the breaker stays closed, and the same query
    // leg runs on CPU without the gate.
    try (SessionHandle.Lease lease = handle.acquire(required())) {
      assertThrows(OrtException.class, () -> lease.run(Map.of()));
    }
    assertTrue(health.isLost());
    assertEquals(719, health.lostCode().orElseThrow());
    assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "device loss does not open the breaker");
    try (SessionHandle.Lease leg = handle.acquire(queryLeg())) {
      assertTrue(leg.isCpu(), "the query leg runs on CPU");
    }
    assertEquals(QueryDevice.CPU, handle.queryDevice());
  }

  @Test
  void gpuErrorsThatAreNotDeviceLossLatchNothingAndTellNoListener() throws Exception {
    Map<String, Integer> devices = Map.of("out-of-memory", 66, "bfc-arena", 67, "error-46", 68);
    Set<Integer> ours = Set.copyOf(devices.values());
    List<Integer> told = new CopyOnWriteArrayList<>();
    try (AutoCloseable listening =
        DeviceHealth.addLossListener(
            (cudaDevice, code) -> {
              if (ours.contains(cudaDevice)) told.add(cudaDevice);
            })) {
      for (Map.Entry<String, Integer> entry : devices.entrySet()) {
        OrtSession gpu = mock(OrtSession.class);
        when(gpu.run(anyMap()))
            .thenThrow(new OrtException(NativeSessionHandleGpuErrorKeepsGpuTest.message(entry.getKey())));
        NativeSessionHandle handle =
            NativeSessionHandleDeviceLostTest.handle(
                "not-lost-" + entry.getKey(), entry.getValue(), gpu, mock(OrtSession.class));
        try (SessionHandle.Lease lease = handle.acquire(required())) {
          assertThrows(OrtException.class, () -> lease.run(Map.of()));
        }
        assertFalse(DeviceHealth.forDevice(entry.getValue()).isLost(), entry.getKey());
      }
    }
    assertTrue(told.isEmpty(), "no device loss reported: " + told);
  }

  /**
   * P2: a CPU run never reports device loss, even if its error names a listed code, so a machine
   * without a usable NVIDIA GPU keeps its CPU semantic search and no restart is asked for.
   */
  @Test
  void aCpuRunNeverReportsDeviceLoss() throws Exception {
    OrtSession cpu = mock(OrtSession.class);
    when(cpu.run(anyMap())).thenThrow(new OrtException(NativeSessionHandleDeviceLostTest.CUDA_719));
    ModelSessionPolicy cpuOnly =
        ModelSessionPolicy.forFallback(
            null, null, true, false, NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
    NativeSessionHandle handle =
        (NativeSessionHandle)
            OrtSessionAssembler.buildManager(
                "cpu-only",
                new Composition(
                    RuntimePolicy.defaults(),
                    cpuOnly,
                    new ModelArtifacts(Path.of("missing", "model.onnx"), Path.of("missing", "model.onnx"))),
                () -> true);
    java.lang.reflect.Field cpuSession = NativeSessionHandle.class.getDeclaredField("cpuSession");
    cpuSession.setAccessible(true);
    cpuSession.set(handle, cpu);
    int device = cpuOnly.gpu().cudaDeviceId();
    List<Integer> told = new CopyOnWriteArrayList<>();
    try (AutoCloseable listening = DeviceHealth.addLossListener(
            (d, code) -> {
              if (d == device) told.add(d);
            })) {
      try (SessionHandle.Lease lease = handle.acquire(required())) {
        assertTrue(lease.isCpu(), "precondition: a CPU-only handle runs on CPU");
        assertThrows(OrtException.class, () -> lease.run(Map.of()));
      }
      assertFalse(DeviceHealth.forDevice(device).isLost(), "a CPU run latches no device");
    }
    assertTrue(told.isEmpty(), "no device loss reported: " + told);
    assertEquals(QueryDevice.CPU, handle.queryDevice());
    assertFalse(handle.isGpuAvailable());
  }

  private static SessionAcquisitionRequest required() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30));
  }

  private static SessionAcquisitionRequest background() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(60));
  }

  /** A hybrid query leg: foreground, released at its deadline, may not create sessions. */
  private static SessionAcquisitionRequest queryLeg() {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
        () -> false,
        /* mayCreateGpuSession= */ false,
        /* releaseCallerAtDeadline= */ true);
  }
}
