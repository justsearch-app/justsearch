/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guarded file operations for index directories.
 *
 * <p>This utility exists to prevent "delete the wrong folder" accidents and to provide a consistent
 * backup-first approach for destructive maintenance operations.
 *
 * <p>NOTE: This class performs filesystem operations and should only be used by the Worker-side
 * components that own Lucene (never by the Main/UI process).
 */
public final class SafeIndexPathOps {
  private static final Logger log = LoggerFactory.getLogger(SafeIndexPathOps.class);
  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private SafeIndexPathOps() {}

  /** Result of a best-effort "mark for deletion" operation. */
  public record MarkResult(Path effectivePath, boolean renamedToDel, boolean wroteMarker) {}

  /**
   * Renames {@code dir} to a sibling {@code <name>.bak-<timestamp>} inside {@code allowedRoot}.
   *
   * <p>This is intended as a safer alternative to deleting an index directory in-place.
   */
  public static Path backupDirectory(Path dir, Path allowedRoot) throws IOException {
    Path d = normalize(dir, "dir");
    Path root = normalize(allowedRoot, "allowedRoot");
    requireUnderRoot(d, root, "backupDirectory");
    IndexOwnership.inspect(d, root);
    if (IndexOwnership.representation(d.getFileName().toString()).role() != IndexOwnership.Role.CANONICAL) {
      throw new IOException("Only a canonical generation can be backed up: " + d);
    }

    Path backup =
        d.resolveSibling(d.getFileName().toString() + ".bak-" + TS.format(Instant.now()));
    requireUnderRoot(backup, root, "backupDirectory");

    IndexOwnership.requireAbsent(backup);
    log.info("Backing up directory {} -> {}", d, backup);
    return Files.move(d, backup);
  }

  /**
   * Best-effort: attempts to rename {@code dir} to a sibling {@code <name>.del-<timestamp>}. If the
   * rename fails (e.g., Windows locks), it writes a {@code DELETEME} marker file inside {@code dir}
   * and returns.
   */
  public static MarkResult markForDeletion(Path dir, Path allowedRoot) throws IOException {
    Path d = normalize(dir, "dir");
    Path root = normalize(allowedRoot, "allowedRoot");
    requireUnderRoot(d, root, "markForDeletion");
    IndexOwnership.inspect(d, root);
    if (IndexOwnership.representation(d.getFileName().toString()).role() == IndexOwnership.Role.MARKED) {
      throw new IOException("Generation is already marked for deletion: " + d);
    }

    Path del =
        d.resolveSibling(d.getFileName().toString() + ".del-" + TS.format(Instant.now()));
    requireUnderRoot(del, root, "markForDeletion");

    IndexOwnership.requireAbsent(del);
    try {
      log.info("Marking directory for deletion (rename) {} -> {}", d, del);
      Path moved = Files.move(d, del, StandardCopyOption.ATOMIC_MOVE);
      return new MarkResult(moved, true, false);
    } catch (Exception e) {
      // Expected sometimes on Windows when MMAP handles are still open. Fall back to marker file.
      boolean wroteMarker = writeDeleteMarkerBestEffort(d, e);
      return new MarkResult(d, false, wroteMarker);
    }
  }

  private static boolean writeDeleteMarkerBestEffort(Path dir, Exception renameFailure) {
    try {
      Path marker = dir.resolve("DELETEME");
      String body =
          "marked_at=" + Instant.now() + "\nreason=rename_failed\nerror=" + renameFailure + "\n";
      if (IndexOwnership.entryExists(marker)
          && !IndexOwnership.plainAttributes(marker).isRegularFile()) return false;
      IndexOwnership.replaceControl(marker, body.getBytes(StandardCharsets.UTF_8));
      log.warn("Rename to .del failed; wrote marker file {}", marker);
      return true;
    } catch (Exception ignored) {
      log.warn(
          "Rename to .del failed and marker write also failed for {}: {}",
          dir,
          ignored.getMessage());
      return false;
    }
  }

  private static Path normalize(Path p, String name) {
    if (p == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return p.toAbsolutePath().normalize();
  }

  private static void requireUnderRoot(Path target, Path allowedRoot, String op) {
    // Defensive: avoid accidental operations at filesystem roots.
    if (allowedRoot.getParent() == null) {
      throw new IllegalArgumentException(op + " refused: allowedRoot is a filesystem root: " + allowedRoot);
    }
    if (target.equals(allowedRoot)) {
      throw new IllegalArgumentException(
          op + " refused: target must not equal allowedRoot. target=" + target + " allowedRoot=" + allowedRoot);
    }
    if (!allowedRoot.equals(target.getParent())) {
      throw new IllegalArgumentException(
          op + " refused: target is outside allowedRoot. target=" + target + " allowedRoot=" + allowedRoot);
    }
  }
}
