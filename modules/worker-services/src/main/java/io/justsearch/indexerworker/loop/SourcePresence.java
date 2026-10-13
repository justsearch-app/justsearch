/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.PathPresence;
import java.nio.file.Path;

/**
 * The one Worker-side answer to "may this source's indexed content be removed?" (task
 * t-20261013-ef3c41).
 *
 * <p>Every check that can end in a deletion — the admission gate, the post-extraction re-hash, the
 * pre-publish freshness check and the writer's pre-write check — asks this, and only {@link
 * Verdict#ABSENT} authorises the deletion. {@code ABSENT} means "not found while the folder that
 * holds it is present": the file is reported not found, then the folder is confirmed to be a
 * listable directory, then the file is reported not found again. Windows reports an unplugged
 * drive or a vanished share as "not found" as well, which is why the folder check sits between the
 * two observations. Any other failure is "cannot tell", which keeps the content and retries.
 */
public final class SourcePresence {
  private SourcePresence() {}

  /** What can be said about a source right now. */
  public enum Verdict {
    /** The source exists (a regular file or anything else; links are not followed). */
    PRESENT,
    /** Confirmed absent: not found while its folder is present. The only deletion authority. */
    ABSENT,
    /** The source's presence could not be established (locked, access denied, I/O error). */
    SOURCE_UNREADABLE,
    /** The folder holding the source is unavailable, so nothing about the source can be said. */
    ROOT_UNAVAILABLE
  }

  /**
   * Classifies {@code source}.
   *
   * @param root the ingestion root that admitted the source; when {@code null}, or when it names
   *     the source itself (an explicitly ingested file), the source's parent folder vouches instead
   */
  public static Verdict classify(Path source, Path root) {
    if (source == null) {
      return Verdict.SOURCE_UNREADABLE;
    }
    Path file = source.toAbsolutePath().normalize();
    PathPresence first = PathPresence.probe(file);
    if (first == PathPresence.PRESENT) {
      return Verdict.PRESENT;
    }
    if (!PathPresence.isReadableDirectory(vouchingFolder(file, root))) {
      return Verdict.ROOT_UNAVAILABLE;
    }
    if (first == PathPresence.UNKNOWN) {
      return Verdict.SOURCE_UNREADABLE;
    }
    return switch (PathPresence.probe(file)) {
      case ABSENT -> Verdict.ABSENT;
      case PRESENT -> Verdict.PRESENT;
      case UNKNOWN -> Verdict.SOURCE_UNREADABLE;
    };
  }

  private static Path vouchingFolder(Path file, Path root) {
    if (root != null) {
      Path boundary = root.toAbsolutePath().normalize();
      if (!boundary.equals(file) && file.startsWith(boundary)) {
        return boundary;
      }
    }
    return file.getParent();
  }
}
