/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Durable "this index was recovered to empty — rebuild it from source" marker (tempdoc 628 Stage B,
 * the G3 join).
 *
 * <p>When the adapter recovers a corrupt index by backing it up and opening a fresh empty index, the
 * empty index is structurally fine but has lost all content. Re-population must not be left to the
 * passive file-watcher (a source file that never changes again would never be re-indexed — the
 * silent-partial-index gap). So the adapter drops this marker as a sibling of the index directory; the
 * orchestration layer (Worker) reads it after open and deterministically kicks a full rebuild from the
 * source files still on disk, then clears it.
 *
 * <p>It is a sibling file (not inside the Lucene index dir) so it never interferes with Lucene's own
 * file set, and it is durable so a crash between recovery and the rebuild-kick simply re-triggers the
 * rebuild on the next start (idempotent). The adapter writes it but cannot itself trigger a rebuild —
 * {@code adapters-lucene} has no dependency on the blue/green migration machinery (worker-core), which
 * is exactly why the decision is relocated up to the orchestration layer.
 */
public final class IndexRecoveryMarker {

  private static final String SUFFIX = ".rebuild-pending";

  private IndexRecoveryMarker() {}

  /** Sibling marker path for the given index directory. */
  public static Path pathFor(Path indexDir) {
    Path fileName = indexDir.getFileName();
    String base = fileName != null ? fileName.toString() : "index";
    return indexDir.resolveSibling(base + SUFFIX);
  }

  /** Writes the marker recording why a rebuild is pending (best-effort, never throws checked). */
  public static void write(Path indexDir, String reason) {
    try {
      Path path = pathFor(indexDir);
      requireRegularIfPresent(path);
      byte[] body = ((reason == null ? "unknown" : reason) + "\n").getBytes(StandardCharsets.UTF_8);
      if (body.length > 1_024 || parseReason(body) == null) throw new IOException("Invalid rebuild marker reason");
      IndexOwnership.replaceControl(path, body);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write index rebuild-pending marker", e);
    }
  }

  /** True if a rebuild-pending marker exists for the given index directory. */
  public static boolean exists(Path indexDir) {
    try {
      requireRegularIfPresent(pathFor(indexDir));
      return IndexOwnership.entryExists(pathFor(indexDir));
    } catch (IOException refusal) { throw new UncheckedIOException(refusal); }
  }

  /** Reads the recorded reason, or {@code null} if no marker / unreadable. */
  public static String readReason(Path indexDir) {
    try {
      Path p = pathFor(indexDir);
      requireRegularIfPresent(p);
      if (!IndexOwnership.entryExists(p)) return null;
      String reason = parseReason(IndexOwnership.readBounded(p, 1_024));
      return reason == null ? "malformed" : reason;
    } catch (IOException e) {
      return "malformed";
    }
  }

  private static void requireRegularIfPresent(Path path) throws IOException {
    if (IndexOwnership.entryExists(path)
        && !IndexOwnership.plainAttributes(path).isRegularFile()) {
      throw new IndexOwnership.Unproven(path, "unexpected control object");
    }
  }

  private static String parseReason(byte[] bytes) throws java.nio.charset.CharacterCodingException {
    String text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    var lines = text.lines().toList();
    if (lines.size() != 1) return null;
    String reason = lines.get(0).trim();
    return reason.isBlank() || reason.chars().anyMatch(Character::isISOControl) ? null : reason;
  }

  /** Removes the marker once the rebuild has been kicked off (idempotent). */
  public static void clear(Path indexDir) {
    try {
      requireRegularIfPresent(pathFor(indexDir));
      Files.deleteIfExists(pathFor(indexDir));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to clear index rebuild-pending marker", e);
    }
  }
}
