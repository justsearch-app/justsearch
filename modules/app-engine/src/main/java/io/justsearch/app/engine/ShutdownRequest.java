/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The out-of-band shutdown request: {@code <dataDir>/runtime/shutdown-request.v1.json} (design 7.3,
 * stage B item B2).
 *
 * <p><b>Why a file.</b> The cooperative trigger is {@code POST /api/lifecycle/shutdown}, and it is
 * enough right up to the case the supervisor exists for: a hung Engine answers no HTTP. Windows has
 * no graceful signal for a JVM, so the supervisor needs a channel that does not depend on the thing
 * it is trying to stop being healthy. A file in the runtime directory is that channel. It is not a
 * better trigger than the endpoint — it is the one that still works when the endpoint does not.
 *
 * <p><b>What it deliberately does not solve.</b> A JVM wedged at a safepoint does not read files
 * either. The request carries a {@code deadlineEpochMs} for exactly that: the supervisor waits that
 * long, then kills. The file is a request, never a guarantee, and the deadline is the admission.
 *
 * <p><b>Two writers, one shape, no shared code.</b> The Tauri supervisor (Rust) and the dev-runner
 * (Node) both write this file, and neither can call this class. They agree by SHAPE, which is why
 * the field set is pinned by a test rather than left to a comment: a fourth writer that spells
 * {@code deadlineEpochMs} as {@code deadline_ms} produces a file this parser rejects, and a
 * rejected request is a shutdown that silently does not happen.
 *
 * <p><b>Parsing fails closed.</b> Every malformed, unknown-reason or unreadable file yields {@link
 * Optional#empty()}. The alternative — guessing a reason — means a file corrupted mid-write could
 * stop the product. An unreadable request is logged and ignored; the supervisor's deadline still
 * covers the case where ignoring it was wrong.
 */
public record ShutdownRequest(
    Reason reason, long deadlineEpochMs, String nonce, String issuedBy, String preparationId) {

  private static final Logger log = LoggerFactory.getLogger(ShutdownRequest.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The file name inside {@code <dataDir>/runtime/}. */
  public static final String FILENAME = "shutdown-request.v1.json";

  /** The staging name for the atomic write; never read. */
  private static final String TMP_FILENAME = FILENAME + ".tmp";

  /**
   * Why the Engine is being asked to stop. The wire form is the lower-case name; design 7.3 step 6
   * branches on it, and 7.1 charges {@link #RESTART} and {@link #UPGRADE} to nobody's crash budget.
   */
  public enum Reason {
    /** The product is closing. llama-server stops (7.3 step 6). */
    QUIT,
    /** A restart is wanted; llama-server is LEFT RUNNING for the next Engine to adopt (7.2). */
    RESTART,
    /** The updater is about to overwrite binaries. llama-server stops so nothing holds them. */
    UPGRADE,
    /** The supervisor believes the Engine is hung. llama-server is left for adoption. */
    HANG;

    /** The lower-case wire form, e.g. {@code "quit"}. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the ordered shutdown stops llama-server for this reason (design 7.3 step 6).
     *
     * <p>{@code quit} and {@code upgrade} stop it: the installer must be able to overwrite the
     * binary, and nothing may hold VRAM behind a closed product. {@code restart} and {@code hang}
     * leave it running for the next Engine to adopt (7.2) — the model is loaded and the VRAM is
     * warm, and making a restarted Engine reload it costs about forty seconds of encoder load for
     * no gain.
     *
     * <p>Expressed as a switch over every constant rather than a set membership test, so adding a
     * fifth reason is a compile error here instead of a silent default.
     */
    public boolean stopsGenerativeBackend() {
      return switch (this) {
        case QUIT, UPGRADE -> true;
        case RESTART, HANG -> false;
      };
    }

    static Optional<Reason> fromWire(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      for (Reason r : values()) {
        if (r.wire().equals(raw.trim().toLowerCase(Locale.ROOT))) {
          return Optional.of(r);
        }
      }
      return Optional.empty();
    }
  }

  /** The request file's path inside a runtime directory. */
  public static Path pathIn(Path runtimeDir) {
    return runtimeDir.resolve(FILENAME);
  }

  /**
   * Reads and validates the request, if one is present and well-formed.
   *
   * @param runtimeDir the {@code <dataDir>/runtime/} directory
   * @return the request, or empty when absent, unreadable, malformed, or carrying an unknown reason
   */
  public static Optional<ShutdownRequest> read(Path runtimeDir) {
    if (runtimeDir == null) {
      return Optional.empty();
    }
    Path file = pathIn(runtimeDir);
    try {
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
      if (root == null || !root.isObject()) {
        log.warn("Ignoring {}: not a JSON object", FILENAME);
        return Optional.empty();
      }
      Optional<Reason> reason = Reason.fromWire(text(root, "reason"));
      if (reason.isEmpty()) {
        log.warn(
            "Ignoring {}: reason {} is not one of quit/restart/upgrade/hang",
            FILENAME,
            text(root, "reason"));
        return Optional.empty();
      }
      JsonNode deadline = root.get("deadlineEpochMs");
      if (deadline == null || !deadline.isIntegralNumber()) {
        log.warn("Ignoring {}: deadlineEpochMs is missing or not an integer", FILENAME);
        return Optional.empty();
      }
      return Optional.of(
          new ShutdownRequest(
              reason.get(),
              deadline.asLong(),
              text(root, "nonce"),
              text(root, "issuedBy"),
              text(root, "preparationId")));
    } catch (Exception malformedOrUnreadable) {
      // Deliberately broad and deliberately non-fatal: a half-written file caught mid-rename, a
      // truncated write, a file another process holds. None of them is a reason to stop.
      log.warn("Ignoring {}: {}", FILENAME, malformedOrUnreadable.toString());
      return Optional.empty();
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode n = root.get(field);
    return n == null || !n.isString() ? null : n.stringValue();
  }

  /**
   * Writes the request atomically (staging file, then rename), creating the runtime directory.
   *
   * <p>Atomic because the reader polls: a consumer that catches a half-written file would see a
   * malformed request, log it, and — correctly — ignore the shutdown it was being asked to perform.
   */
  public void writeTo(Path runtimeDir) throws IOException {
    Files.createDirectories(runtimeDir);
    Path tmp = runtimeDir.resolve(TMP_FILENAME);
    Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, pathIn(runtimeDir), StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, pathIn(runtimeDir), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** The exact JSON shape the Rust and Node writers must also produce. */
  public String toJson() {
    StringBuilder sb = new StringBuilder(160);
    sb.append("{\n  \"reason\": \"").append(reason.wire()).append("\",\n");
    sb.append("  \"deadlineEpochMs\": ").append(deadlineEpochMs);
    if (nonce != null) {
      sb.append(",\n  \"nonce\": \"").append(nonce).append('"');
    }
    if (issuedBy != null) {
      sb.append(",\n  \"issuedBy\": \"").append(issuedBy).append('"');
    }
    if (preparationId != null) {
      // Item B6. The receipt written on the far side of this file is preparation- AND nonce-bound,
      // and the updater rejects one that is not — so both identifiers have to survive the trip.
      sb.append(",\n  \"preparationId\": \"").append(preparationId).append('"');
    }
    return sb.append("\n}\n").toString();
  }

  /**
   * Deletes the request, so a consumed shutdown is not re-read.
   *
   * <p>Best-effort by design: the Engine is on its way down when this runs, and a delete that fails
   * must not derail the sequence. The supervisor deletes it too, on the next start.
   */
  public static void clear(Path runtimeDir) {
    try {
      Files.deleteIfExists(pathIn(runtimeDir));
    } catch (IOException e) {
      log.warn("Could not delete {} ({}); the next start clears it", FILENAME, e.getMessage());
    }
  }
}
