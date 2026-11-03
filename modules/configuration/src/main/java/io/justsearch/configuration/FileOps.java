/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * File-system utilities with built-in fault handling.
 *
 * <p>These methods handle per-item failures internally (counting + summary logging) so callers
 * don't need catch blocks around file operations in loops.
 */
public final class FileOps {

  private FileOps() {} // utility class

  /** Result of a best-effort recursive delete: how many entries were deleted vs. failed. */
  public record DeleteResult(int deleted, int failed) {}

  /**
   * Recursively deletes a directory, counting per-file failures rather than throwing on them.
   *
   * <p>Per-file {@link IOException}s (e.g. Windows file locks) are counted silently. A single
   * summary line is logged at DEBUG if any deletions failed. The caller can inspect the returned
   * {@link DeleteResult} to decide whether the failures matter.
   *
   * <p>Entries are inspected without following links before descent. Failure to open the parent
   * directory is propagated; per-entry read, descent and delete failures are counted.
   *
   * @param dir the directory to delete (if null or nonexistent, returns zero counts)
   * @param log the logger for the summary line
   * @return counts of deleted and failed entries
   * @throws IOException if the parent directory cannot be opened
   */
  public static DeleteResult deleteRecursivelyBestEffort(Path dir, Logger log) throws IOException {
    if (dir == null || !Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return new DeleteResult(0, 0);
    }
    int[] counts = {0, 0};
    Path absolute = dir.toAbsolutePath().normalize();
    try (var parent = Files.newDirectoryStream(absolute.getParent())) {
      if (parent instanceof java.nio.file.SecureDirectoryStream<Path> secure) {
        deleteSecure(secure, absolute.getFileName(), counts);
      } else {
        deletePath(dir, counts);
      }
    }
    if (counts[1] > 0) {
      log.debug(
          "Deleted {}/{} entries in {}; {} failed (likely locked)",
          counts[0],
          counts[0] + counts[1],
          dir.getFileName(),
          counts[1]);
    }
    return new DeleteResult(counts[0], counts[1]);
  }
  private static void deleteSecure(java.nio.file.SecureDirectoryStream<Path> parent, Path name,
      int[] counts) {
    try {
      var attributes = parent.getFileAttributeView(name,
          java.nio.file.attribute.BasicFileAttributeView.class,
          java.nio.file.LinkOption.NOFOLLOW_LINKS).readAttributes();
      if (attributes.isDirectory() && !attributes.isOther() && !attributes.isSymbolicLink()) {
        try (var directory = parent.newDirectoryStream(name, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          for (Path entry : directory) deleteSecure(directory, entry.getFileName(), counts);
        }
        parent.deleteDirectory(name);
      } else {
        parent.deleteFile(name);
      }
      counts[0]++;
    } catch (IOException failure) { counts[1]++; }
  }

  private static void deletePath(Path path, int[] counts) {
    try {
      var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
          java.nio.file.LinkOption.NOFOLLOW_LINKS);
      // Inspect before opening a directory stream: Windows junctions are directories and other.
      if (attributes.isDirectory() && !attributes.isOther() && !attributes.isSymbolicLink()) {
        try (var entries = Files.newDirectoryStream(path)) {
          for (Path entry : entries) deletePath(entry, counts);
        }
      }
      Files.delete(path);
      counts[0]++;
    } catch (IOException failure) { counts[1]++; }
  }
}
