/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Three-way answer to "is this path there?" — the one file-level probe every deletion path uses
 * (task t-20261013-ef3c41).
 *
 * <p>{@link Files#exists} answers {@code false} for ANY failure (access denied, sharing violation,
 * an unreachable share), so "cannot tell" read as "gone" and indexed content was deleted on a read
 * error. Only {@link NoSuchFileException} is {@link #ABSENT}; every other failure is
 * {@link #UNKNOWN}. Even {@code ABSENT} is only a candidate: Windows reports an unplugged drive or a
 * missing parent folder as "not found" too, so a caller may treat it as a deletion only while the
 * root that holds the path is itself present (see {@link #isReadableDirectory}). Links are never
 * followed, matching the walks, which never follow them either.
 */
public enum PathPresence {
  /** The path exists (as anything: file, directory or link). */
  PRESENT,
  /** The file system reported the path as not found. Proof of deletion only with a present root. */
  ABSENT,
  /** The presence of the path could not be established (access denied, I/O error, ...). */
  UNKNOWN;

  /** Probes {@code path} without following links. */
  public static PathPresence probe(Path path) {
    if (path == null) {
      return UNKNOWN;
    }
    try {
      Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return PRESENT;
    } catch (NoSuchFileException notFound) {
      return ABSENT;
    } catch (IOException | SecurityException cannotTell) {
      return UNKNOWN;
    }
  }

  /** Probes a stored path string; a string that is not a valid path cannot be judged. */
  public static PathPresence probe(String path) {
    if (path == null || path.isBlank()) {
      return UNKNOWN;
    }
    try {
      return probe(Path.of(path));
    } catch (InvalidPathException invalid) {
      return UNKNOWN;
    }
  }

  /**
   * True when {@code dir} is, right now, a directory (not a link) whose entries can be listed. A
   * root that fails this cannot vouch for the absence of anything under it.
   */
  public static boolean isReadableDirectory(Path dir) {
    if (dir == null) {
      return false;
    }
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isDirectory()) {
        return false;
      }
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
        // Reading the first entry is what proves the folder can be enumerated; empty is fine.
        boolean firstEntryRead = entries.iterator().hasNext();
        return firstEntryRead || attributes.isDirectory();
      }
    } catch (IOException | SecurityException | java.nio.file.DirectoryIteratorException e) {
      return false;
    }
  }
}
