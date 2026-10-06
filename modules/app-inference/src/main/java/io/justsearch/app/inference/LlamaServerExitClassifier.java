/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.chat.FailedCode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
    String device = firstMatch(log, lower, DEVICE_SUSPECT);
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
