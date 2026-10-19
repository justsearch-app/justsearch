/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtException;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells a lost GPU apart from every other CUDA error an ORT run can raise (Spec E §6.1). A lost
 * device (a driver reset, a driver update underneath the process, a hardware fault, or a sticky
 * fault that leaves the CUDA context unusable) is reported by exactly one code of a closed list;
 * everything else, including out-of-memory, the BFC arena message, allocation failures and cuDNN or
 * cuBLAS errors without a listed code, is not device loss.
 *
 * <p>ORT reports a failed CUDA call as {@code "CUDA failure <code>: <text> ; GPU=<n> ; hostname=…
 * ; file=… ; line=… ; expr=…; "} (the {@code CUDA_CALL} format of onnxruntime 1.24.3, whose other
 * libraries use the same format under their own names, such as {@code CUBLAS failure}). The
 * classifier also accepts the CUDA runtime ({@code cudaError…}) and driver ({@code CUDA_ERROR_…})
 * names of the listed codes.
 */
public final class DeviceLostClassifier {

  /** The closed list: CUDA runtime code to its runtime name. Code 46 is excluded (RV3 F9). */
  private static final Map<Integer, String> LOST_CODES =
      Map.ofEntries(
          Map.entry(35, "cudaErrorInsufficientDriver"),
          Map.entry(100, "cudaErrorNoDevice"),
          Map.entry(214, "cudaErrorECCUncorrectable"),
          Map.entry(700, "cudaErrorIllegalAddress"),
          Map.entry(702, "cudaErrorLaunchTimeout"),
          Map.entry(714, "cudaErrorHardwareStackError"),
          Map.entry(715, "cudaErrorIllegalInstruction"),
          Map.entry(716, "cudaErrorMisalignedAddress"),
          Map.entry(717, "cudaErrorInvalidAddressSpace"),
          Map.entry(718, "cudaErrorInvalidPc"),
          Map.entry(719, "cudaErrorLaunchFailure"),
          Map.entry(803, "cudaErrorSystemDriverMismatch"),
          Map.entry(999, "cudaErrorUnknown"));

  /** The CUDA driver API names of the listed codes (the driver API has no code 35). */
  private static final Map<String, Integer> DRIVER_NAMES =
      Map.ofEntries(
          Map.entry("CUDA_ERROR_NO_DEVICE", 100),
          Map.entry("CUDA_ERROR_ECC_UNCORRECTABLE", 214),
          Map.entry("CUDA_ERROR_ILLEGAL_ADDRESS", 700),
          Map.entry("CUDA_ERROR_LAUNCH_TIMEOUT", 702),
          Map.entry("CUDA_ERROR_HARDWARE_STACK_ERROR", 714),
          Map.entry("CUDA_ERROR_ILLEGAL_INSTRUCTION", 715),
          Map.entry("CUDA_ERROR_MISALIGNED_ADDRESS", 716),
          Map.entry("CUDA_ERROR_INVALID_ADDRESS_SPACE", 717),
          Map.entry("CUDA_ERROR_INVALID_PC", 718),
          Map.entry("CUDA_ERROR_LAUNCH_FAILED", 719),
          Map.entry("CUDA_ERROR_SYSTEM_DRIVER_MISMATCH", 803),
          Map.entry("CUDA_ERROR_UNKNOWN", 999));

  private static final Pattern CUDA_CALL = Pattern.compile("(?<![A-Za-z])CUDA failure (\\d+):");
  private static final Pattern RUNTIME_NAME = Pattern.compile("(?<![A-Za-z])cudaError[A-Za-z]+");
  private static final Pattern DRIVER_NAME = Pattern.compile("(?<![A-Z_])CUDA_ERROR_[A-Z_]+");
  private static final Set<String> RUNTIME_NAMES = Set.copyOf(LOST_CODES.values());

  private DeviceLostClassifier() {}

  /** The listed code {@code failure} reports, or empty when it is not device loss. */
  public static OptionalInt classify(OrtException failure) {
    return failure == null ? OptionalInt.empty() : classify(failure.getMessage());
  }

  /** The listed code an ORT error message reports, or empty when it is not device loss. */
  public static OptionalInt classify(String message) {
    if (message == null || message.isEmpty()) return OptionalInt.empty();
    Matcher call = CUDA_CALL.matcher(message);
    while (call.find()) {
      int code = parseCode(call.group(1));
      if (LOST_CODES.containsKey(code)) return OptionalInt.of(code);
    }
    Matcher runtime = RUNTIME_NAME.matcher(message);
    while (runtime.find()) {
      String name = runtime.group();
      if (RUNTIME_NAMES.contains(name)) {
        for (Map.Entry<Integer, String> entry : LOST_CODES.entrySet()) {
          if (entry.getValue().equals(name)) return OptionalInt.of(entry.getKey());
        }
      }
    }
    Matcher driver = DRIVER_NAME.matcher(message);
    while (driver.find()) {
      Integer code = DRIVER_NAMES.get(driver.group());
      if (code != null) return OptionalInt.of(code);
    }
    return OptionalInt.empty();
  }

  private static int parseCode(String digits) {
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException tooLong) {
      return -1;
    }
  }
}
