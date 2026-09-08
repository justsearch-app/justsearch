/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Persisted identity of a child process owned by the Engine. */
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
    String realizedArgvHash) {

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
      Path executable,
      String endpoint,
      String modelPath,
      String declaredConfigHash,
      String realizedArgvHash) {
    Instant start =
        process.toHandle().info().startInstant().orElseThrow(
            () -> new IllegalStateException("started child has no process start instant"));
    return new ManagedChild(
        UUID.randomUUID().toString(),
        kind,
        process.pid(),
        start.toString(),
        normalizePath(executable),
        endpoint,
        modelPath == null ? null : normalizePath(Path.of(modelPath)),
        declaredConfigHash,
        realizedArgvHash);
  }

  /** Windows paths compare case-insensitively; other platforms retain case. */
  public static String normalizePath(Path path) {
    String normalized = path.toAbsolutePath().normalize().toString();
    return File.separatorChar == '\\'
        ? normalized.toLowerCase(Locale.ROOT)
        : normalized;
  }

  /** Compare every destructive-action identity axis, preserving unknown live identities. */
  public IdentityMatch identityOf(ProcessHandle handle) {
    Optional<Instant> liveStart = handle.info().startInstant();
    Optional<String> liveCommand = handle.info().command();
    if (liveStart.isEmpty() || liveCommand.isEmpty()) return IdentityMatch.UNKNOWN;
    final Instant recordedStart;
    try {
      recordedStart = Instant.parse(startedAt);
    } catch (RuntimeException invalid) {
      return IdentityMatch.UNKNOWN;
    }
    if (Math.abs(liveStart.get().toEpochMilli() - recordedStart.toEpochMilli()) > 1_000L
        || !normalizePath(Path.of(liveCommand.get())).equals(executable)) {
      return IdentityMatch.MISMATCH;
    }
    return IdentityMatch.MATCH;
  }
}
