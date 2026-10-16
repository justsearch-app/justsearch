/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec E test E-S11a (S11, ORT side): after a GPU run fails because the device was lost (CUDA error
 * 719, the classic error after a driver reset), the handle's next request runs on CPU without
 * entering the device's gate, and semantic inference continues on CPU.
 *
 * <p>Seam: a real {@link NativeSessionHandle} built by {@link OrtSessionAssembler#buildManager} on
 * its device's real gate; only the native sessions are mocks. The GPU session's run throws ORT's
 * {@code CUDA_CALL} message for error 719 (onnxruntime 1.24.3's format). To show the request does
 * not enter the gate, the device's only seat is held by another caller when the request is made: a
 * request that went to the gate would wait there until its deadline. The check uses only API that
 * exists before the change.
 */
final class NativeSessionHandleDeviceLostTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  /** A CUDA device id no other test uses, so its gate and health start clean. */
  private static final int DEVICE = 61;

  @Test
  void lossDuringCreationCannotPublishOrCloseTheReturningGpuCandidate() throws Exception {
    int device = 961;
    OrtSession candidate = mock(OrtSession.class);
    OrtSession cpu = mock(OrtSession.class);
    NativeSessionHandle handle = handle("loss-during-creation", device, null, cpu);
    set(handle, "gpuSessionAttempted", false);
    set(handle, "gpuAvailable", false);
    boolean cpuLease;
    boolean gpuAvailable;
    String failureReason;
    try (handle;
        var cuda = org.mockito.Mockito.mockStatic(OrtCudaHelper.class);
        var _ = org.mockito.Mockito.mockConstruction(OrtSession.SessionOptions.class);
        var _ = org.mockito.Mockito.mockConstruction(ai.onnxruntime.providers.OrtCUDAProviderOptions.class);
        var _ = org.mockito.Mockito.mockStatic(SessionOptionsApplier.class);
        var cache = org.mockito.Mockito.mockStatic(OnnxSessionCache.class)) {
      cuda.when(() -> OrtCudaHelper.checkMissingCudaRuntimeDlls(org.mockito.ArgumentMatchers.any()))
          .thenReturn(java.util.List.of());
      cache.when(() -> OnnxSessionCache.createCachedGpuSession(org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
          .thenAnswer(invocation -> {
            DeviceHealth.forDevice(device).reportLost(719);
            return candidate;
          });
      try (var lease = handle.acquire(background(Duration.ofSeconds(5)))) {
        cpuLease = lease.isCpu();
      }
      gpuAvailable = handle.isGpuAvailable();
      failureReason = handle.status().failureReason();
    }
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertTrue(cpuLease, "a candidate returned after device loss must not be leased"),
        () -> assertFalse(gpuAvailable),
        () -> assertTrue(failureReason != null && failureReason.contains("lost"),
            "READY must not overwrite loss"),
        () -> verify(candidate, org.mockito.Mockito.never()).close());
  }

  static final String CUDA_719 =
      "Error code - ORT_RUNTIME_EXCEPTION - message: Non-zero status code returned while running"
          + " MatMul node. Name:'/encoder/layer.0/attention/self/query/MatMul' Status Message:"
          + " CUDA failure 719: unspecified launch failure ; GPU=0 ; hostname=HOST ;"
          + " file=D:\\a\\_work\\1\\s\\onnxruntime\\core\\providers\\cuda\\cuda_call.cc ; line=129 ;"
          + " expr=cudaStreamSynchronize(static_cast<cudaStream_t>(GetHandle())); ";

  @Test
  void afterADeviceLostRunTheNextRequestRunsOnCpuWithoutEnteringTheGate() throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenThrow(new OrtException(CUDA_719));
    float[][] embedding = {{0.25f, -0.5f, 0.75f}};
    OrtSession cpu = cpuSessionReturning(embedding);
    NativeSessionHandle handle = handle("e-s11a", DEVICE, gpu, cpu);

    try (SessionHandle.Lease first = handle.acquire(foreground(Duration.ofSeconds(30)))) {
      assertFalse(first.isCpu(), "precondition: the first run is on the GPU");
      assertThrows(OrtException.class, () -> first.run(Map.of()), "the GPU run fails with 719");
    }

    // Another caller holds the device's only seat: a request that reaches the gate waits for it.
    DeviceEncoderGate.Seat held =
        DeviceEncoderGates.forDevice(DEVICE)
            .seat(new Object(), background(Duration.ofSeconds(30)));
    try {
      SessionHandle.Lease next;
      try {
        next = handle.acquire(foreground(Duration.ofMillis(500)));
      } catch (RuntimeException waitedAtTheGate) {
        fail("the request after the device-lost run waited at the device's gate", waitedAtTheGate);
        return;
      }
      try (next) {
        assertTrue(next.isCpu(), "the request after the device-lost run runs on CPU");
        try (OrtSession.Result result = next.run(Map.of())) {
          assertArrayEquals(
              embedding[0],
              ((float[][]) result.get(0).getValue())[0],
              "semantic inference continues on CPU");
        }
      }
    } finally {
      held.close();
    }
    verify(gpu, times(1)).run(anyMap());
  }

  private static SessionAcquisitionRequest foreground(Duration timeout) {
    return SessionAcquisitionRequest.within(SessionAcquisitionRequest.Urgency.FOREGROUND, timeout);
  }

  private static SessionAcquisitionRequest background(Duration timeout) {
    return SessionAcquisitionRequest.within(SessionAcquisitionRequest.Urgency.BACKGROUND, timeout);
  }

  static OrtSession cpuSessionReturning(float[][] embedding) throws Exception {
    OnnxTensor output = mock(OnnxTensor.class);
    when(output.getValue()).thenReturn(embedding);
    OrtSession.Result result = mock(OrtSession.Result.class);
    when(result.get(0)).thenReturn(output);
    OrtSession cpu = mock(OrtSession.class);
    when(cpu.run(anyMap())).thenReturn(result);
    return cpu;
  }

  static NativeSessionHandle handle(String name, int device, OrtSession gpu, OrtSession cpu)
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
    set(handle, "gpuSession", gpu);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    set(handle, "cpuSession", cpu);
    return handle;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
