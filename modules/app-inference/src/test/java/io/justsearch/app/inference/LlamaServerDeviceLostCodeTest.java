/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.inference.LlamaServerExitClassifier.CrashClass;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Spec A §7.3 source 2 and reconciliation F17: which llama-server exits and outputs name a status
 * code on the closed device-lost list, which is exactly Spec E §6.1's (100, 214, 700, 702, 714-718,
 * 719, 803, 35, 999). Out of memory and cudaErrorDevicesUnavailable (46) never do.
 */
final class LlamaServerDeviceLostCodeTest {

  private static Optional<String> exitCode(String log) {
    return LlamaServerExitClassifier.deviceLostCode(LlamaServerExitClassifier.classify(1, log));
  }

  @Test
  void theCudaErrorsOnTheListNameTheirCodes() {
    assertEquals(Optional.of("719"), exitCode("CUDA error: unspecified launch failure"));
    assertEquals(Optional.of("702"), exitCode("CUDA error: the launch timed out and was terminated"));
    assertEquals(Optional.of("100"), exitCode("CUDA error: no CUDA-capable device is detected"));
  }

  @Test
  void everyCodeOnSpecEsListIsDeviceLossByItsCudaErrorText() {
    Map<String, String> ggml = Map.ofEntries(
        Map.entry("100", "CUDA error: no CUDA-capable device is detected"),
        Map.entry("214", "CUDA error: uncorrectable ECC error encountered"),
        Map.entry("700", "CUDA error: an illegal memory access was encountered"),
        Map.entry("702", "CUDA error: the launch timed out and was terminated"),
        Map.entry("714", "CUDA error: hardware stack error"),
        Map.entry("715", "CUDA error: an illegal instruction was encountered"),
        Map.entry("716", "CUDA error: misaligned address"),
        Map.entry("717", "CUDA error: operation not supported on global/shared address space"),
        Map.entry("718", "CUDA error: invalid program counter"),
        Map.entry("719", "CUDA error: unspecified launch failure"),
        Map.entry("803",
            "CUDA error: system has unsupported display driver / cuda driver combination"),
        Map.entry("35",
            "CUDA error: CUDA driver version is insufficient for CUDA runtime version"),
        Map.entry("999", "CUDA error: unknown error"));
    assertEquals(13, ggml.size());
    ggml.forEach((code, line) -> {
      String log = "srv  update_slots: decoding\n" + line
          + "\n  current device: 0, in function ggml_backend_cuda_synchronize";
      var exit = LlamaServerExitClassifier.classify(-1073740791, log);
      assertEquals(CrashClass.DEVICE_SUSPECT, exit.crashClass(), line);
      assertEquals(line, exit.evidence(), line);
      assertEquals(Optional.of(code), LlamaServerExitClassifier.deviceLostCode(exit), line);
    });
  }

  @Test
  void numberedAndNamedCodesOnTheListAreDeviceLoss() {
    assertEquals(Optional.of("700"), exitCode("CUDA error 700 at ggml-cuda.cu:85"));
    assertEquals(Optional.of("719"),
        exitCode("CUDA failure 719: unspecified launch failure ; GPU=0 ; hostname=x"));
    assertEquals(Optional.of("999"), exitCode("cuda call failed: cudaErrorUnknown"));
    assertEquals(Optional.of("803"), exitCode("CUDA_ERROR_SYSTEM_DRIVER_MISMATCH"));
    assertEquals(Optional.of("35"), exitCode("CUDA error 35"));
  }

  @Test
  void codesOffTheListAreNotDeviceLoss() {
    assertEquals(Optional.empty(), exitCode("CUDA error 46 (cudaErrorDevicesUnavailable)"));
    assertEquals(Optional.empty(), exitCode("CUDA error 2: out of memory"));
    assertEquals(Optional.empty(), exitCode("CUDA error 713"));
    assertEquals(Optional.empty(), exitCode("CUDA error 7190"));
    assertEquals(Optional.empty(), exitCode("main: unknown error while parsing arguments"));
  }

  @Test
  void otherDeviceSuspectExitsAndOutOfMemoryAreNotDeviceLoss() {
    assertEquals(Optional.empty(), exitCode("ggml_cuda: device lost"));
    assertEquals(Optional.empty(),
        exitCode("CUDA error: cudaErrorDevicesUnavailable: all CUDA-capable devices are busy"));
    assertEquals(Optional.empty(), exitCode("CUDA error: out of memory"));
    assertEquals(Optional.empty(), exitCode(
        "ggml_backend_cuda_buffer_type_alloc_buffer: allocating 1 MiB on device 0: cudaMalloc failed:"
            + " out of memory"));
    assertEquals(Optional.empty(), exitCode(""));
  }

  @Test
  void serverOutputNamesTheCodeWhileTheProcessLives() {
    assertEquals(Optional.of("719"), LlamaServerExitClassifier.deviceLostCode(
        "srv  update_slots: ...\nCUDA error: unspecified launch failure\n  current device: 0"));
    assertEquals(Optional.empty(), LlamaServerExitClassifier.deviceLostCode("all good"));
    assertEquals(Optional.empty(), LlamaServerExitClassifier.deviceLostCode((String) null));
  }
}
