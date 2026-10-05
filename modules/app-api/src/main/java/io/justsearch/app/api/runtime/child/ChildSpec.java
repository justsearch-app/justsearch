/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.gpu.GpuDevice;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Everything {@link ChildControl#start} needs to launch and later stop one held child.
 *
 * @param label a short diagnostic name, at most 64 characters, never a path
 */
public record ChildSpec(
    ChildKind kind,
    List<String> argv,
    Path workingDir,
    Map<String, String> envOverrides,
    Stdio stdio,
    boolean gpu,
    OptionalLong gpuFootprintBytes,
    Optional<GpuDevice> gpuDevice,
    CooperativeStop cooperativeStop,
    String label) {

  public static final int MAX_LABEL_LENGTH = 64;

  public ChildSpec {
    Objects.requireNonNull(kind, "kind");
    argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
    if (argv.isEmpty()) throw new IllegalArgumentException("argv must name an executable");
    envOverrides = Map.copyOf(Objects.requireNonNull(envOverrides, "envOverrides"));
    Objects.requireNonNull(stdio, "stdio");
    Objects.requireNonNull(gpuFootprintBytes, "gpuFootprintBytes");
    Objects.requireNonNull(gpuDevice, "gpuDevice");
    Objects.requireNonNull(cooperativeStop, "cooperativeStop");
    Objects.requireNonNull(label, "label");
    if (label.isBlank() || label.length() > MAX_LABEL_LENGTH
        || label.indexOf('/') >= 0 || label.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(
          "label must be 1-" + MAX_LABEL_LENGTH + " characters and not a path: " + label);
    }
    if (gpuFootprintBytes.isPresent() && gpuFootprintBytes.getAsLong() < 0) {
      throw new IllegalArgumentException("gpuFootprintBytes must be non-negative");
    }
  }

  /** How the child's standard streams are wired. */
  public sealed interface Stdio {
    /** The owner reads and writes the streams through {@link ChildHandle}. */
    record Pipes() implements Stdio {}

    /** Both output streams are appended to {@code file}; stdin is closed. */
    record AppendToLog(Path file) implements Stdio {
      public AppendToLog {
        Objects.requireNonNull(file, "file");
      }
    }
  }

  /**
   * The cooperative step a {@link StopMode#COOPERATIVE} stop runs before terminating.
   *
   * @param expectedCodes exit codes that count as a clean cooperative exit
   */
  public record CooperativeStop(Action action, Duration grace, Set<Integer> expectedCodes) {
    public static final CooperativeStop NONE =
        new CooperativeStop(Action.NONE, Duration.ZERO, Set.of());

    public CooperativeStop {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(grace, "grace");
      if (grace.isNegative()) throw new IllegalArgumentException("grace must not be negative");
      expectedCodes = Set.copyOf(Objects.requireNonNull(expectedCodes, "expectedCodes"));
      if (action == Action.NONE && !grace.isZero()) {
        throw new IllegalArgumentException("a NONE cooperative step has no grace");
      }
    }

    /** The cooperative action. */
    public enum Action {
      NONE,
      CLOSE_STDIN
    }
  }
}
