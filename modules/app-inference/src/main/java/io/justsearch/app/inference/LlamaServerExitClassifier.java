/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.chat.FailedCode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies an unexpected llama-server exit from its exit code and the log text written since
 * this launch began (Spec A §7.1). The class decides what the chat controller may do next (§7.2):
 * only {@link CrashClass#RESOURCE} steps the context window down, only {@link CrashClass#PORT_LOST}
 * is relaunched at the same rung (ruling CH3), and nothing else is retried with the same
 * configuration while the server is starting.
 *
 * <p>The order of the checks matters and is part of the contract:
 *
 * <ol>
 *   <li>A lost port bind is checked first. llama-server binds before it loads the model, so its
 *       log cannot also hold a load failure.
 *   <li>An unknown model architecture is always {@link CrashClass#CONFIG}, although the same line
 *       also contains {@code error loading model}.
 *   <li>Device-loss evidence before memory evidence. CUDA out-of-memory is not device loss
 *       (reconciliation F17), and none of the device patterns matches an allocation failure.
 *   <li>Memory evidence before the other CONFIG patterns: a weight allocation that fails is also
 *       logged as {@code error loading model}, and it is a window or VRAM problem, not a bad file.
 * </ol>
 */
public final class LlamaServerExitClassifier {

  /** Windows {@code STATUS_DLL_NOT_FOUND} (0xC0000135): a native dependency is missing. */
  public static final int EXIT_DLL_NOT_FOUND = -1073741515;

  /** Windows {@code STATUS_NO_MEMORY} (0xC0000017). */
  public static final int EXIT_NO_MEMORY = -1073741801;

  /** What an unexpected exit says about the configuration that produced it. */
  public enum CrashClass {
    /** The window or the model did not fit: step down one rung, never retry the same rung. */
    RESOURCE,
    /** The GPU itself failed. Never retried in place (Spec A §7.3). */
    DEVICE_SUSPECT,
    /** The inputs cannot work until they change: fail at once, no ladder. */
    CONFIG,
    /** The fresh port was taken before llama-server bound it: one relaunch on a new port (CH3). */
    PORT_LOST,
    /** Anything else, including a bare exit 1 and 0xC0000409 without memory text. */
    OTHER
  }

  /**
   * The verdict for one exit.
   *
   * @param crashClass the class
   * @param failedCode the code chat reports when this exit ends the attempt in STARTING
   * @param evidence the log line or exit code that decided the class, or {@code null} for OTHER
   */
  public record Classification(CrashClass crashClass, FailedCode failedCode, String evidence) {
    public Classification {
      Objects.requireNonNull(crashClass, "crashClass");
      Objects.requireNonNull(failedCode, "failedCode");
    }
  }

  private static final List<String> MODEL_ARCHITECTURE = List.of("unknown model architecture");

  private static final List<String> DEVICE_SUSPECT = List.of(
      "unspecified launch failure",
      "launch timed out",
      "device lost",
      "no cuda-capable device",
      "cudaerrordevicesunavailable");

  private static final List<String> RESOURCE = List.of(
      "out of memory",
      "cudamalloc failed",
      "failed to allocate",
      "failed to create context");

  private static final List<String> MODEL_LOAD = List.of(
      "error loading model",
      "failed to load model");

  private static final List<String> ADDRESS_IN_USE = List.of("address already in use");

  private LlamaServerExitClassifier() {}

  /**
   * Classifies one exit.
   *
   * @param exitCode the process exit code, or -1 when it is unknown
   * @param launchOutput what llama-server wrote since this launch began; may be null or empty
   */
  public static Classification classify(int exitCode, String launchOutput) {
    String log = launchOutput == null ? "" : launchOutput;
    String lower = log.toLowerCase(Locale.ROOT);

    if (LlamaServerBindFailure.isBindFailure(log)) {
      return new Classification(
          CrashClass.PORT_LOST, FailedCode.START_FAILED, line(log, LlamaServerBindFailure.MARKER));
    }
    if (exitCode == EXIT_DLL_NOT_FOUND) {
      return new Classification(CrashClass.CONFIG, FailedCode.MISSING_DLL, "exit " + exitCode);
    }
    String architecture = firstMatch(log, lower, MODEL_ARCHITECTURE);
    if (architecture != null) {
      return new Classification(CrashClass.CONFIG, FailedCode.MODEL_LOAD_FAILED, architecture);
    }
    String device = deviceLostLine(log);
    if (device == null) device = firstMatch(log, lower, DEVICE_SUSPECT);
    if (device != null) {
      return new Classification(CrashClass.DEVICE_SUSPECT, FailedCode.START_FAILED, device);
    }
    String memory = firstMatch(log, lower, RESOURCE);
    if (memory == null) memory = allocBufferFailure(log);
    if (memory != null) {
      return new Classification(CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED, memory);
    }
    if (exitCode == EXIT_NO_MEMORY) {
      return new Classification(
          CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED, "exit " + exitCode);
    }
    String load = firstMatch(log, lower, MODEL_LOAD);
    if (load != null) {
      return new Classification(CrashClass.CONFIG, FailedCode.MODEL_LOAD_FAILED, load);
    }
    String address = firstMatch(log, lower, ADDRESS_IN_USE);
    if (address != null) {
      return new Classification(CrashClass.CONFIG, FailedCode.START_FAILED, address);
    }
    return new Classification(CrashClass.OTHER, FailedCode.START_FAILED, null);
  }

  /**
   * One status code of the closed device-lost list (Spec E §6.1, reconciliation F17), with the
   * names and the {@code cudaGetErrorString} text llama-server's CUDA backend can print for it.
   *
   * @param code the CUDA runtime status code
   * @param runtimeName the CUDA runtime name ({@code cudaError…})
   * @param driverName the CUDA driver name ({@code CUDA_ERROR_…}), or null when there is none
   * @param text the runtime's error text, lower case
   */
  private record LostCode(int code, String runtimeName, String driverName, String text) {}

  /**
   * Exactly Spec E §6.1's list: 100, 214, 700, 702, 714-718, 719, 803, 35 and 999. Excluded, so
   * never device loss: 2 (out of memory), 46 ({@code cudaErrorDevicesUnavailable}), every
   * allocation failure, and any text without a listed code (such as a bare {@code device lost}).
   */
  private static final List<LostCode> DEVICE_LOST_CODES = List.of(
      new LostCode(100, "cudaErrorNoDevice", "CUDA_ERROR_NO_DEVICE", "no cuda-capable device"),
      new LostCode(214, "cudaErrorECCUncorrectable", "CUDA_ERROR_ECC_UNCORRECTABLE",
          "uncorrectable ecc error encountered"),
      new LostCode(700, "cudaErrorIllegalAddress", "CUDA_ERROR_ILLEGAL_ADDRESS",
          "an illegal memory access was encountered"),
      new LostCode(702, "cudaErrorLaunchTimeout", "CUDA_ERROR_LAUNCH_TIMEOUT", "launch timed out"),
      new LostCode(714, "cudaErrorHardwareStackError", "CUDA_ERROR_HARDWARE_STACK_ERROR",
          "hardware stack error"),
      new LostCode(715, "cudaErrorIllegalInstruction", "CUDA_ERROR_ILLEGAL_INSTRUCTION",
          "an illegal instruction was encountered"),
      new LostCode(716, "cudaErrorMisalignedAddress", "CUDA_ERROR_MISALIGNED_ADDRESS",
          "misaligned address"),
      new LostCode(717, "cudaErrorInvalidAddressSpace", "CUDA_ERROR_INVALID_ADDRESS_SPACE",
          "operation not supported on global/shared address space"),
      new LostCode(718, "cudaErrorInvalidPc", "CUDA_ERROR_INVALID_PC", "invalid program counter"),
      new LostCode(719, "cudaErrorLaunchFailure", "CUDA_ERROR_LAUNCH_FAILED",
          "unspecified launch failure"),
      new LostCode(803, "cudaErrorSystemDriverMismatch", "CUDA_ERROR_SYSTEM_DRIVER_MISMATCH",
          "system has unsupported display driver / cuda driver combination"),
      new LostCode(35, "cudaErrorInsufficientDriver", null,
          "cuda driver version is insufficient for cuda runtime version"),
      new LostCode(999, "cudaErrorUnknown", "CUDA_ERROR_UNKNOWN", "unknown error"));

  /** A numbered CUDA error, such as {@code CUDA error 719} or ORT's {@code CUDA failure 719:}. */
  private static final Pattern NUMBERED =
      Pattern.compile("cuda (?:error|failure)[ :]*(\\d{1,4})(?!\\d)");

  /**
   * The device-lost status code an exit's evidence names (Spec A §7.3 source 2), or empty when the
   * exit is not a DEVICE_SUSPECT naming a listed code.
   */
  public static Optional<String> deviceLostCode(Classification exit) {
    Objects.requireNonNull(exit, "exit");
    if (exit.crashClass() != CrashClass.DEVICE_SUSPECT || exit.evidence() == null) {
      return Optional.empty();
    }
    return deviceLostCode(exit.evidence());
  }

  /**
   * The device-lost status code {@code output} names, or empty: the first line that mentions CUDA
   * and names a code of {@link #DEVICE_LOST_CODES} by number, name or error text.
   */
  public static Optional<String> deviceLostCode(String output) {
    if (output == null) return Optional.empty();
    for (String raw : output.split("\n")) {
      LostCode code = lostCode(raw);
      if (code != null) return Optional.of(Integer.toString(code.code()));
    }
    return Optional.empty();
  }

  /** The first log line naming a listed device-lost code, stripped, or null. */
  private static String deviceLostLine(String log) {
    for (String raw : log.split("\n")) {
      if (lostCode(raw) != null) return raw.strip();
    }
    return null;
  }

  private static LostCode lostCode(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    if (!lower.contains("cuda")) return null;
    Matcher numbered = NUMBERED.matcher(lower);
    while (numbered.find()) {
      int number = Integer.parseInt(numbered.group(1));
      for (LostCode code : DEVICE_LOST_CODES) {
        if (code.code() == number) return code;
      }
    }
    for (LostCode code : DEVICE_LOST_CODES) {
      if (lower.contains(code.runtimeName().toLowerCase(Locale.ROOT))
          || (code.driverName() != null
              && lower.contains(code.driverName().toLowerCase(Locale.ROOT)))
          || lower.contains(code.text())) {
        return code;
      }
    }
    return null;
  }

  /** {@code alloc_buffer} and {@code failed} on the same line: a backend buffer allocation failed. */
  private static String allocBufferFailure(String log) {
    for (String raw : log.split("\n")) {
      String lower = raw.toLowerCase(Locale.ROOT);
      if (lower.contains("alloc_buffer") && lower.contains("failed")) return raw.strip();
    }
    return null;
  }

  private static String firstMatch(String log, String lower, List<String> patterns) {
    for (String pattern : patterns) {
      if (lower.contains(pattern)) return line(log, pattern);
    }
    return null;
  }

  /** The first log line containing {@code pattern} (case-insensitive), stripped. */
  private static String line(String log, String pattern) {
    String needle = pattern.toLowerCase(Locale.ROOT);
    for (String raw : log.split("\n")) {
      if (raw.toLowerCase(Locale.ROOT).contains(needle)) return raw.strip();
    }
    return pattern;
  }
}
