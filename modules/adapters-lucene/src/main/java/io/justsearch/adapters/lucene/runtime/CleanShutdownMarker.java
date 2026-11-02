/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Durable "the index was closed cleanly" marker (tempdoc 628 Gap 1).
 *
 * <p>The bounded {@code STRUCTURAL} integrity check (commit + segment-info checksums) is cheap enough to
 * run on every open and catches the corruption a crash mid-commit usually produces. But silent
 * <em>body</em> bit-rot in a committed data file is only caught by the {@code FULL} check, which is
 * O(index size) and therefore too slow to run on every clean restart. This marker lets the worker run
 * the heavy {@code FULL} scan <em>only after a crash</em>: the writable runtime drops the marker on a
 * graceful close (after the final commit), and the next open consumes it — an <em>absent</em> marker
 * means the previous shutdown was unclean (a crash) or this is a first run, so a thorough scan is
 * warranted.
 *
 * <p>{@code write.lock} cannot serve this role: Lucene's {@code NativeFSLockFactory} keeps the lock
 * <em>file</em> after a clean close too, so its presence does not distinguish clean from crash. Like
 * {@link IndexRecoveryMarker} this is a <em>sibling</em> file of the index directory, so it never
 * disturbs Lucene's own file set.
 */
public final class CleanShutdownMarker {

  private static final String SUFFIX = ".clean-shutdown";

  private CleanShutdownMarker() {}

  /** Sibling marker path for the given index directory. */
  public static Path pathFor(Path indexDir) {
    Path fileName = indexDir.getFileName();
    String base = fileName != null ? fileName.toString() : "index";
    return indexDir.resolveSibling(base + SUFFIX);
  }

  /** Records a clean shutdown (best-effort; a failure here just means the next open scans FULL). */
  public static void write(Path indexDir) {
    try {
      requireRegularIfPresent(pathFor(indexDir));
      IndexOwnership.replaceControl(pathFor(indexDir), "clean\n".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // best-effort: a missing marker only costs a FULL scan on the next open, never correctness.
    }
  }

  /**
   * Whether the previous shutdown was clean. Absent marker (crash or first run) → {@code false}.
   * Non-destructive: reading the answer and invalidating it are separate acts, because only one of
   * them is a consequence of opening (tempdoc 915, open item O14).
   */
  public static boolean wasClean(Path indexDir) {
    try {
      requireRegularIfPresent(pathFor(indexDir));
      return IndexOwnership.entryExists(pathFor(indexDir))
          && "clean\n".equals(new String(IndexOwnership.readBounded(pathFor(indexDir), 1_024), StandardCharsets.UTF_8));
    } catch (IOException unavailable) { return false; }
  }

  /**
   * Clears the marker, so a crash in <em>this</em> session is detectable on the next open.
   *
   * <p>Called when a WRITER opens, which is the moment an unclean death becomes possible — not when
   * the index is merely read. This used to be fused into the read: an open consumed the marker
   * whether or not it could ever invalidate it, so a runtime that serves the active generation
   * read-only for its whole life (a blue/green migration, and every boot of the exhausted-brake
   * state) deleted a marker it would never re-write, and the following boot declared an unclean
   * shutdown that had not happened and paid a FULL integrity verification for it. Live validation
   * saw one generation do that on five consecutive boots.
   */
  public static void consume(Path indexDir) throws IOException {
    requireRegularIfPresent(pathFor(indexDir));
    Files.deleteIfExists(pathFor(indexDir));
  }
  private static void requireRegularIfPresent(Path path) throws IOException {
    if (IndexOwnership.entryExists(path)
        && !IndexOwnership.plainAttributes(path).isRegularFile()) {
      throw new IndexOwnership.Unproven(path, "unexpected control object");
    }
  }

}
