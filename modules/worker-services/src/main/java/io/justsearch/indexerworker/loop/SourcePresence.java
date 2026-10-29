/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.PathPresence;
import java.nio.file.Path;

/**
 * The one Worker-side answer to "may this source's indexed content be removed?" (#758).
 *
 * <p>Every check that can end in a deletion — the admission gate, the post-extraction re-hash, the
 * pre-publish freshness check, the writer's pre-write check and the watcher's delete confirmation
 * — asks this, and only {@link Verdict#ABSENT} authorises the deletion. {@code ABSENT} means "not
 * found while the root that holds it is present and is the same root": the file is reported not
 * found, then the root vouches ({@link RootCheck}: a listable folder, the same folder and volume it
 * was), then the file is reported not found again, and the nearest folder above it that exists can
 * be listed. Windows reports an unplugged drive or a vanished share as "not found" as well, which
 * is why the root check sits between the two observations; a drive letter that now holds another
 * volume is why the root check asks for identity, not only presence. Any other failure is
 * "cannot tell", which keeps the content and retries.
 */
public final class SourcePresence {
  private SourcePresence() {}

  /**
   * The root-level question shared by every path that can end in a deletion, and by every path
   * that adds or updates content, so each asks what the prune and the walks ask (#758).
   */
  public interface RootCheck {
    /** Release-v13 exact work has read authority only; its absence never authorizes deletion. */
    default boolean isRelease030ExactFile(Path file) { return false; }

    /**
     * True while {@code folder} may vouch that something beneath it is gone: a present, listable
     * folder that is the same folder, on the same volume, as when it was first seen.
     */
    boolean vouches(Path folder);

    /**
     * True while content beneath {@code folder} may be added or updated: a present, listable folder
     * on the volume recorded for it; a root with nothing recorded is unproven (round 8). A folder
     * that is another volume at the same path, or cannot be read, admits nothing (round 5).
     */
    boolean admits(Path folder);

    /**
     * {@link #vouches} for the parent folder of an explicitly ingested file, which is not a root
     * (round 8, refined decision 2): the same answer unless the check tells the two apart.
     */
    default boolean vouchesForFile(Path folder) {
      return vouches(folder);
    }

    /** {@link #admits} for the parent folder of an explicitly ingested file, which is not a root. */
    default boolean admitsFile(Path folder) {
      return admits(folder);
    }
  }

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
  public static Verdict classify(Path source, Path root, RootCheck rootCheck) {
    if (source == null || rootCheck == null) {
      return Verdict.SOURCE_UNREADABLE;
    }
    Path file = source.toAbsolutePath().normalize();
    PathPresence first = PathPresence.probe(file);
    if (first == PathPresence.PRESENT) {
      return Verdict.PRESENT;
    }
    boolean rooted = answersAsRoot(file, root);
    if (!rooted && rootCheck.isRelease030ExactFile(file)) return Verdict.SOURCE_UNREADABLE;
    Path folder = vouchingFolder(file, root);
    boolean vouches = rooted ? rootCheck.vouches(folder) : rootCheck.vouchesForFile(folder);
    if (!vouches) {
      return Verdict.ROOT_UNAVAILABLE;
    }
    if (first == PathPresence.UNKNOWN) {
      return Verdict.SOURCE_UNREADABLE;
    }
    return switch (PathPresence.probe(file)) {
      // "Not found" beneath a folder that cannot be listed proves nothing.
      case ABSENT ->
          PathPresence.absentBeneathListableFolder(file, folder, null) == PathPresence.ABSENT
              ? Verdict.ABSENT
              : Verdict.SOURCE_UNREADABLE;
      case PRESENT -> Verdict.PRESENT;
      case UNKNOWN -> Verdict.SOURCE_UNREADABLE;
    };
  }

  /**
   * Whether the folder that answers for {@code file} is its ingestion root; otherwise it is the
   * parent folder of an explicitly ingested file (or of a source with no root).
   */
  public static boolean answersAsRoot(Path file, Path root) {
    if (root == null) {
      return false;
    }
    Path boundary = root.toAbsolutePath().normalize();
    return !boundary.equals(file) && file.startsWith(boundary);
  }

  /** The folder that answers for {@code file}: its ingestion root, or else its own folder. */
  static Path vouchingFolder(Path file, Path root) {
    if (root != null) {
      Path boundary = root.toAbsolutePath().normalize();
      if (!boundary.equals(file) && file.startsWith(boundary)) {
        return boundary;
      }
    }
    return file.getParent();
  }
}
