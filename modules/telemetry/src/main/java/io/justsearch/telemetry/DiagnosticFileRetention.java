/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Shared retention utility for diagnostic file writers.
 *
 * <p>Best-effort: individual file/directory failures are silently ignored. Directory-level failures
 * (e.g., dir doesn't exist, dir unreadable) return 0.
 *
 * <p>Does NOT use SLF4J — safe for use in crash-reporting paths where Logback may be dead.
 */
public final class DiagnosticFileRetention {
  private DiagnosticFileRetention() {}

  /**
   * Deletes regular files in {@code dir} whose name starts with {@code prefix} and whose
   * last-modified time is before {@code cutoff}.
   *
   * @return number of files successfully deleted
   */
  public static int pruneBefore(Path dir, String prefix, Instant cutoff) {
    return pruneBefore(dir, prefix, cutoff, path -> true);
  }

  /** As {@link #pruneBefore(Path, String, Instant)}, filtering names before filesystem access. */
  public static int pruneBefore(Path dir, String prefix, Instant cutoff, Predicate<Path> include) {
    if (dir == null || !Files.exists(dir)) return 0;
    int deleted = 0;
    try (Stream<Path> entries = Files.list(dir)) {
      for (Path p :
          entries
              .filter(include)
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().startsWith(prefix))
              .toList()) {
        try {
          if (Files.readAttributes(p, BasicFileAttributes.class)
              .lastModifiedTime()
              .toInstant()
              .isBefore(cutoff)) {
            Files.deleteIfExists(p);
            deleted++;
          }
        } catch (IOException ignored) {
          // best-effort per-file cleanup
        }
      }
    } catch (IOException ignored) {
      // dir unreadable or doesn't exist
    }
    return deleted;
  }

  /**
   * Deletes directories (recursively) in {@code parentDir} whose last-modified time is before
   * {@code cutoff}. Only considers direct children that are directories.
   *
   * <p>Used by AgentRunStore where each session is a subdirectory.
   *
   * @return number of directories successfully deleted
   */
  public static int pruneDirectoriesBefore(Path parentDir, Instant cutoff) {
    return pruneDirectoriesBefore(parentDir, cutoff, path -> true);
  }

  /** As {@link #pruneDirectoriesBefore(Path, Instant)}, filtering names before filesystem access. */
  public static int pruneDirectoriesBefore(
      Path parentDir, Instant cutoff, Predicate<Path> include) {
    if (parentDir == null || !Files.exists(parentDir)) return 0;
    int deleted = 0;
    try (Stream<Path> entries = Files.list(parentDir)) {
      for (Path dir :
          entries.filter(include).filter(Files::isDirectory).toList()) {
        try {
          BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
          if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
            deleteDirectoryTree(dir);
            deleted++;
          }
        } catch (IOException ignored) {
          // best-effort per-directory cleanup
        }
      }
    } catch (IOException ignored) {
      // parent dir unreadable
    }
    return deleted;
  }

  /**
   * Delete one directory tree. Exposed (tempdoc 859 slice C PR-2) so a caller that deletes a run
   * directory by IDENTITY rather than by age — {@code AgentRunStore.deleteConversationRuns}, the
   * capability behind a synthesized sidebar row's discard — removes it through the SAME walk this
   * class's age-based prune uses, instead of minting a third copy of the delete-a-tree loop.
   */
  public static void deleteDirectoryTree(Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
