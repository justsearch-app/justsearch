/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-file download history that outlives a single install/repair pass (tempdoc 824 §3.4).
 *
 * <p>{@code repair()} delegates straight to {@code startInstall()}, so before this each pass had no
 * memory of the last one: identical transport, identical odds, no way to tell a file that has now
 * failed three times from one failing for the first time. Round 16's user clicked Repair four times
 * on the same 872-byte file and was offered Repair a fifth. Two things need memory to be possible —
 * transport escalation (§3.1's {@link TransportRetryPolicy} ladder, started a rung higher each
 * pass) and a terminal "this will not converge" verdict — and both are per FILE, across runs.
 *
 * <p><b>Why a file under {@code homeDir} and not the {@code DownloadResume} sidecar.</b> The
 * sidecar is deliberately tied to the {@code .partial} it anchors: {@link DownloadResume#clear}
 * deletes both on every {@code FRESH} decision, and {@link DownloadResume#deleteSidecar} removes it
 * on success. A transport failure at connection setup leaves NO partial bytes, so the next pass
 * decides {@code FRESH} ("no partial bytes on disk") and erases exactly the history this needs —
 * the sidecar cannot survive the event it would be recording. {@code <homeDir>/install-attempts.json}
 * is outside that lifecycle.
 *
 * <p><b>Degradation.</b> Every read and write is best-effort. An unreadable, corrupt or unwritable
 * file yields an empty memory, which reproduces today's behaviour exactly: tier 0 every pass, no
 * terminal verdict, Repair always offered. Losing the memory must never lose the install.
 */
public final class InstallAttemptMemory {

  private static final Logger log = LoggerFactory.getLogger(InstallAttemptMemory.class);
  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** File name under the AI home directory. */
  public static final String FILENAME = "install-attempts.json";

  /**
   * Consecutive failing passes after which automatic repair stops being offered for a file. Three:
   * under round 16's measured 82 % per-fallback failure rate a single failure is unremarkable, and
   * three passes of a retrying transport that all fail is no longer a story about luck.
   */
  public static final int MAX_FAILED_PASSES = 3;

  /** What one file's history records. */
  public record Attempt(
      String url,
      int attempts,
      int failedPasses,
      String lastError,
      long lastAttemptEpochMs,
      int lastStartTier) {}

  /** Serialized shape — a version tag plus the per-target-path map. */
  private record Persisted(int version, Map<String, Attempt> files) {}

  public static final int VERSION = 1;

  private final Path file;
  private final Map<String, Attempt> files;

  private InstallAttemptMemory(Path file, Map<String, Attempt> files) {
    this.file = file;
    this.files = files;
  }

  /** Loads the memory for {@code homeDir}; an absent or unreadable file yields an empty memory. */
  public static InstallAttemptMemory load(Path homeDir) {
    Path file = homeDir.resolve(FILENAME);
    Map<String, Attempt> loaded = new LinkedHashMap<>();
    try {
      if (Files.isRegularFile(file)) {
        Persisted p = JSON.readValue(Files.readString(file), Persisted.class);
        if (p != null && p.version() == VERSION && p.files() != null) {
          loaded.putAll(p.files());
        }
      }
    } catch (Exception e) {
      // Best-effort by construction: degrade to "no history", never fail the install.
      log.debug("Install attempt memory unreadable ({}); starting empty", e.toString());
      loaded.clear();
    }
    return new InstallAttemptMemory(file, loaded);
  }

  /**
   * Whether {@code outcome} ended at TRANSPORT — the only kind of failure this memory acts on.
   *
   * <p>Reads {@link ResumableFetch.Outcome#failure()}, which is non-null exactly on that path. It
   * used to match a prefix of the user-facing message instead, which made escalation and the
   * terminal verdict depend on the wording of English prose: rewording the failure would have
   * disabled both silently. A SHA mismatch is a registry/upstream problem no other transport fixes,
   * and spending 40 s of backoff on it is pure latency — so verification, bookkeeping, and
   * cancellation outcomes carry no failure and are not acted on.
   */
  public static boolean isTransportFailure(ResumableFetch.Outcome outcome) {
    return outcome != null && outcome.failure() != null;
  }

  /** This file's history, or null when it has none. */
  public Attempt get(String targetPath) {
    return files.get(targetPath);
  }

  /**
   * The transport tier the current pass should start at for {@code targetPath} — one rung per
   * earlier failing pass, so pass <em>n</em> meets a transport that pass <em>n-1</em> did not.
   *
   * <p>Deliberately NOT clamped here: {@link TransportRetryPolicy#withStartTier} owns the
   * {@code [0, MAX_TRANSPORT_TIER]} range, and a second clamp beside it is a fork that can drift
   * from the ladder it is clamping to.
   */
  public int startTierFor(String targetPath) {
    Attempt a = files.get(targetPath);
    return a == null ? 0 : Math.max(0, a.failedPasses());
  }

  /**
   * Whether automatic repair has provably stopped working for {@code targetPath} — {@link
   * #MAX_FAILED_PASSES} consecutive passes failed it at transport.
   */
  public boolean isTerminal(String targetPath) {
    Attempt a = files.get(targetPath);
    return a != null && a.failedPasses() >= MAX_FAILED_PASSES;
  }

  /**
   * Records that this pass failed {@code targetPath} at transport, and persists.
   *
   * @param transferAttempts transports this pass spent on the file ({@code
   *     ResumableFetch.Outcome.transferAttempts})
   */
  public void recordTransportFailure(
      String targetPath, String url, int transferAttempts, String error, int startTier) {
    Attempt prev = files.get(targetPath);
    files.put(
        targetPath,
        new Attempt(
            url,
            (prev == null ? 0 : prev.attempts()) + Math.max(1, transferAttempts),
            (prev == null ? 0 : prev.failedPasses()) + 1,
            error == null ? "" : error,
            System.currentTimeMillis(),
            startTier));
    save();
  }

  /**
   * Forgets {@code targetPath} — the file transferred, so its failure history is spent. Persists
   * only when something was actually forgotten, so a clean install writes nothing.
   */
  public void recordSuccess(String targetPath) {
    if (files.remove(targetPath) != null) {
      save();
    }
  }

  /**
   * Persists via sibling-temp + atomic rename ({@link AtomicFileWrites}), not a direct
   * {@code writeString}. A plain write truncates the existing file first, so a crash or a full disk
   * mid-write leaves a TRUNCATED json — which {@link #load} reads as "no history", resetting
   * {@code failedPasses} to 0 and re-offering a Repair that has already provably failed three
   * times (the exact non-convergence this memory exists to end). The atomic form can only leave the
   * previous complete file or the new complete file.
   */
  private void save() {
    try {
      AtomicFileWrites.replaceUtf8(file, JSON.writeValueAsString(new Persisted(VERSION, files)));
    } catch (IOException | RuntimeException e) {
      log.debug("Install attempt memory not persisted ({}); escalation degrades to tier 0", e.toString());
    }
  }
}
