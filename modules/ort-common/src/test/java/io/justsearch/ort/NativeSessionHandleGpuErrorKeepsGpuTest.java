/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Spec E test E-S11b (S11 / F17), a preservation guard: GPU errors that are not device loss (CUDA
 * out of memory, the BFC arena message, and CUDA error 46, which is transient under exclusive-mode
 * contention) leave the GPU in use: the next request is seated on the GPU again.
 *
 * <p>Same seam as NativeSessionHandleDeviceLostTest, each case on a device of its own. It
 * uses only API that exists before the change, and passes before and after it.
 */
final class NativeSessionHandleGpuErrorKeepsGpuTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  static final String CUDA_2 =
      "Error code - ORT_RUNTIME_EXCEPTION - message: Non-zero status code returned while running"
          + " MatMul node. Name:'/MatMul' Status Message: CUDA failure 2: out of memory ; GPU=0 ;"
          + " hostname=HOST ; file=cuda_allocator.cc ; line=48 ; expr=cudaMalloc((void**)&p, size); ";

  static final String BFC_ARENA =
      "Error code - ORT_RUNTIME_EXCEPTION - message: Non-zero status code returned while running"
          + " Add node. Name:'/Add' Status Message: D:\\a\\_work\\1\\s\\onnxruntime\\core\\framework"
          + "\\bfc_arena.cc:359 void *__cdecl onnxruntime::BFCArena::AllocateRawInternal(size_t,bool,"
          + "onnxruntime::Stream *,bool,class std::function<void __cdecl(onnxruntime::Stream *,"
          + "struct onnxruntime::synchronize::Notification &)>) Available memory of 0 is smaller than"
          + " requested bytes of 268435456";

  static final String CUDA_46 =
      "Error code - ORT_RUNTIME_EXCEPTION - message: Non-zero status code returned while running"
          + " MatMul node. Name:'/MatMul' Status Message: CUDA failure 46: all CUDA-capable devices"
          + " are busy or unavailable ; GPU=0 ; hostname=HOST ; file=cuda_call.cc ; line=129 ;"
          + " expr=cudaStreamSynchronize(stream); ";

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource({"out-of-memory, 62", "bfc-arena, 63", "error-46, 64"})
  void aGpuErrorThatIsNotDeviceLossKeepsTheNextRequestOnTheGpu(String error, int device)
      throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenThrow(new OrtException(message(error)));
    NativeSessionHandle handle = handle("e-s11b-" + error, device, gpu);

    try (SessionHandle.Lease first = handle.acquire(foreground())) {
      assertFalse(first.isCpu(), "precondition: the first run is on the GPU");
      assertThrows(OrtException.class, () -> first.run(Map.of()));
    }
    try (SessionHandle.Lease next = handle.acquire(foreground())) {
      assertFalse(next.isCpu(), "a " + error + " error leaves the next request on the GPU");
    }
  }

  static String message(String error) {
    return switch (error) {
      case "out-of-memory" -> CUDA_2;
      case "bfc-arena" -> BFC_ARENA;
      case "error-46" -> CUDA_46;
      default -> throw new IllegalArgumentException(error);
    };
  }

  private static SessionAcquisitionRequest foreground() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30));
  }

  private static NativeSessionHandle handle(String name, int device, OrtSession gpu)
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
    set(handle, "cpuSession", mock(OrtSession.class));
    return handle;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
