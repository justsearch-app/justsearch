/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.app.api.runtime.child.BoundChildProcess;
import io.justsearch.app.api.runtime.child.LinuxProcessIdentity;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Persisted identity of a child process owned by the Engine.
 *
 * <p>Both kinds publish a SHA-256 declared launch-configuration hash and a separate realized argv
 * hash. Extraction hashes the configured command before adding the incarnation's parent PID;
 * llama-server hashes applied inputs before launch fallback adjusts argv.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagedChild(
    String id,
    Kind kind,
    long pid,
    String startedAt,
    String executable,
    String endpoint,
    String modelPath,
    String declaredConfigHash,
    String realizedArgvHash,
    LinuxProcessIdentity processIdentity) {

  /** Existing Windows and legacy records retain their fields and constructor semantics. */
  public ManagedChild(String id, Kind kind, long pid, String startedAt, String executable,
      String endpoint, String modelPath, String declaredConfigHash, String realizedArgvHash) {
    this(id, kind, pid, startedAt, executable, endpoint, modelPath, declaredConfigHash, realizedArgvHash, null);
  }

  public enum Kind {
    LLAMA_SERVER,
    EXTRACTION
  }

  public enum IdentityMatch {
    MATCH,
    MISMATCH,
    UNKNOWN
  }

  public ManagedChild {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must be non-blank");
    if (kind == null) throw new IllegalArgumentException("kind must be non-null");
    if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
    if (startedAt == null || startedAt.isBlank()) {
      throw new IllegalArgumentException("startedAt must be non-blank");
    }
    if (executable == null || executable.isBlank()) {
      throw new IllegalArgumentException("executable must be non-blank");
    }
  }

  /** Capture the three OS identity axes immediately after spawn. */
  public static ManagedChild fromProcess(
      Process process,
      Kind kind,
      String endpoint,
      String modelPath,
      String declaredConfigHash,
      String realizedArgvHash) {
    var linux = io.justsearch.configuration.PlatformPaths.isLinux() ? LinuxProcessIdentity.observe(process.pid()) : null;
    ProcessHandle.Info observed = process.info();
    Instant start =
        observed.startInstant().orElseThrow(
            () -> new IllegalStateException("started child has no process start instant"));
    String executable = observed.command().filter(command -> !command.isBlank()).orElseThrow(
        () -> new IllegalStateException("started child has no observed executable"));
    if (linux != null && (linux.identity().isEmpty() || linux.executable().isEmpty()
        || !linux.identity().equals(LinuxProcessIdentity.read(process.pid()))))
      throw new IllegalStateException("Linux child identity changed or unavailable during capture");
    return new ManagedChild(
        UUID.randomUUID().toString(),
        kind,
        process.pid(),
        start.toString(),
        normalizePath(Path.of(linux != null ? linux.executable().orElseThrow() : executable)),
        endpoint,
        modelPath == null ? null : normalizePath(Path.of(modelPath)),
        declaredConfigHash,
        realizedArgvHash,
        linux != null ? linux.identity().orElseThrow() : null);
  }

  /** Windows paths compare case-insensitively; other platforms retain case. */
  public static String normalizePath(Path path) {
    String normalized = path.toAbsolutePath().normalize().toString();
    return io.justsearch.configuration.PlatformPaths.isWindows()
        ? normalized.toLowerCase(Locale.ROOT)
        : normalized;
  }

  /** Compare identity axes. Legacy records remain readable; Linux stop authority additionally
   * requires a saved native tuple in the terminator, never the legacy wall-clock match. */
  public IdentityMatch identityOf(ProcessHandle handle) {
    if (io.justsearch.configuration.PlatformPaths.isLinux() && processIdentity != null) {
      if (processIdentity.pid() != pid || handle.pid() != pid)
        return IdentityMatch.UNKNOWN;
      var live = LinuxProcessIdentity.observe(pid);
      if (live.identity().isEmpty() || live.executable().isEmpty()) return IdentityMatch.UNKNOWN;
      return live.identity().get().equals(processIdentity)
          && normalizePath(Path.of(live.executable().get())).equals(executable)
          ? IdentityMatch.MATCH : IdentityMatch.MISMATCH;
    }
    ProcessHandle.Info observed = handle.info();
    Optional<Instant> liveStart = observed.startInstant();
    Optional<String> liveCommand = observed.command();
    if (liveStart.isEmpty() || liveCommand.isEmpty()) return IdentityMatch.UNKNOWN;
    final Instant recordedStart;
    try {
      recordedStart = Instant.parse(startedAt);
    } catch (RuntimeException invalid) {
      return IdentityMatch.UNKNOWN;
    }
    if (handle.pid() != pid
        || !liveStart.get().equals(recordedStart)
        || !normalizePath(Path.of(liveCommand.get())).equals(executable)) {
      return IdentityMatch.MISMATCH;
    }
    return IdentityMatch.MATCH;
  }
}
