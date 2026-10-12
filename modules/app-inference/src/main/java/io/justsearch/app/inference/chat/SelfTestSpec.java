/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/**
 * One activation self-test: the candidate llama-server the chat controller launches while the main
 * server is stopped (Spec A T38), and how the caller probes it.
 *
 * @param command the candidate's argv, executable first; it listens on {@code port}
 * @param workingDirectory the candidate's working directory, or {@code null}
 * @param pathPrefix directories put in front of {@code PATH} for the candidate (DLL lookup)
 * @param logFile where the candidate's output is appended
 * @param port the loopback port the candidate listens on
 * @param healthTimeout how long the candidate may take to become healthy
 * @param probe the caller's checks against the healthy candidate
 * @param environment the captured device plan's child environment overrides
 */
public record SelfTestSpec(
    List<String> command,
    Path workingDirectory,
    List<Path> pathPrefix,
    Path logFile,
    int port,
    Duration healthTimeout,
    Probe probe,
    Map<String, String> environment) {

  /** The caller's view of the candidate. Runs on the controller thread while the candidate lives. */
  public interface Probe {
    /** True when the candidate answers its health check. */
    boolean healthy(int port);

    /** Exercises the healthy candidate; throws when it does not answer as expected. */
    void exercise(int port) throws Exception;
  }

  public SelfTestSpec(List<String> command, Path workingDirectory, List<Path> pathPrefix,
      Path logFile, int port, Duration healthTimeout, Probe probe) {
    this(command, workingDirectory, pathPrefix, logFile, port, healthTimeout, probe, Map.of());
  }

  public SelfTestSpec {
    environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    command = List.copyOf(Objects.requireNonNull(command, "command"));
    if (command.isEmpty()) throw new IllegalArgumentException("command is empty");
    pathPrefix = List.copyOf(Objects.requireNonNull(pathPrefix, "pathPrefix"));
    Objects.requireNonNull(logFile, "logFile");
    Objects.requireNonNull(healthTimeout, "healthTimeout");
    Objects.requireNonNull(probe, "probe");
  }
}
