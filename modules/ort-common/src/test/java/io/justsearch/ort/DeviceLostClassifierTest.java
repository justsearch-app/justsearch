/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtException;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec E test E-U8: the closed device-lost list (§6.1). Messages are literal, in the {@code
 * CUDA_CALL} format of the onnxruntime version on the classpath (1.24.3: {@code "%s failure %d: %s
 * ; GPU=%d ; hostname=%s ; file=%s ; line=%d ; expr=%s; %s"}), wrapped as ORT Java wraps a run's
 * status message.
 */
final class DeviceLostClassifierTest {

  private static String cudaCall(String library, int code, String text) {
    return "Error code - ORT_RUNTIME_EXCEPTION - message: Non-zero status code returned while running"
        + " MatMul node. Name:'/MatMul' Status Message: " + library + " failure " + code + ": "
        + text + " ; GPU=0 ; hostname=HOST ; file=cuda_call.cc ; line=129 ;"
        + " expr=cudaStreamSynchronize(stream); ";
  }

  @ParameterizedTest(name = "CUDA failure {0}")
  @CsvSource({
    "35, CUDA driver version is insufficient for CUDA runtime version",
    "100, no CUDA-capable device is detected",
    "214, uncorrectable ECC error encountered",
    "700, an illegal memory access was encountered",
    "702, the launch timed out and was terminated",
    "714, hardware stack error",
    "715, an illegal instruction was encountered",
    "716, misaligned address",
    "717, operation not supported on global/shared address space",
    "718, invalid program counter",
    "719, unspecified launch failure",
    "803, system has unsupported display driver / cuda driver combination",
    "999, unknown error"
  })
  void everyListedCudaCallCodeIsDeviceLoss(int code, String text) {
    assertEquals(
        OptionalInt.of(code),
        DeviceLostClassifier.classify(new OrtException(cudaCall("CUDA", code, text))));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "cudaErrorLaunchFailure, 719",
    "cudaErrorIllegalAddress, 700",
    "cudaErrorInsufficientDriver, 35",
    "cudaErrorUnknown, 999",
    "CUDA_ERROR_LAUNCH_FAILED, 719",
    "CUDA_ERROR_NO_DEVICE, 100",
    "CUDA_ERROR_SYSTEM_DRIVER_MISMATCH, 803"
  })
  void theRuntimeAndDriverNamesOfListedCodesAreDeviceLoss(String name, int code) {
    assertEquals(
        OptionalInt.of(code),
        DeviceLostClassifier.classify("CUDA error " + name + ":unspecified failure"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Out of memory and code 46 (transient under exclusive-mode contention).
        "CUDA failure 2: out of memory",
        "CUDA failure 46: all CUDA-capable devices are busy or unavailable",
        // Allocation failures and cuDNN/cuBLAS errors without a listed code.
        "CUBLAS failure 3: CUBLAS_STATUS_ALLOC_FAILED",
        "CUDNN failure 9: CUDNN_STATUS_NOT_SUPPORTED",
        "CUDNN failure 8: CUDNN_STATUS_EXECUTION_FAILED",
        "CUBLAS failure 13: CUBLAS_STATUS_EXECUTION_FAILED",
        "CUDA_ERROR_OUT_OF_MEMORY",
        "cudaErrorMemoryAllocation",
        "cudaErrorDevicesUnavailable",
        // A listed name inside a longer name is not that name.
        "cudaErrorLaunchFailureX",
        "CUDA_ERROR_LAUNCH_FAILED_EXTRA",
        // A listed number that is not a CUDA failure code.
        "CUBLAS failure 719: something else",
        "Failed to allocate 719 bytes",
        // No code at all.
        "Non-zero status code returned while running MatMul node."
      })
  void everythingElseIsNotDeviceLoss(String message) {
    assertTrue(DeviceLostClassifier.classify(message).isEmpty(), message);
  }

  @Test
  void theBfcArenaMessageAndAMissingMessageAreNotDeviceLoss() {
    assertTrue(
        DeviceLostClassifier.classify(
                new OrtException(NativeSessionHandleGpuErrorKeepsGpuTest.BFC_ARENA))
            .isEmpty());
    assertTrue(DeviceLostClassifier.classify((String) null).isEmpty());
    assertTrue(DeviceLostClassifier.classify((OrtException) null).isEmpty());
  }
}
