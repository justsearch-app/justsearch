/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The read-path twin of the chunk RMW revision guard (tempdoc 931 §E item 5).
 *
 * <p>{@code chunk_content} is indexed but not stored, so every read that has to show a chunk's text
 * re-slices it out of the parent's stored {@code content} by the chunk's stored offsets. Parent
 * write and chunk regeneration are two separate coordinator calls, so between them the index holds
 * the NEW parent next to the OLD chunk documents — and with an equal-or-longer rewrite the old
 * offsets still fit, so the slice comes back silently wrong: wrong excerpt on a search hit, wrong
 * passage in a RAG context. {@link WritePathOps} already refuses to re-slice across that boundary;
 * this refuses to READ across it.
 *
 * <p>A slice is returned only when the parent's current content revision equals the
 * {@code chunk_parent_content_sha256} the chunk was cut from. Anything else — a mismatch, or a
 * legacy chunk carrying no revision identity at all — yields {@link Optional#empty()}, which every
 * caller already treats the way it treats a missing parent (no text rather than wrong text).
 *
 * <p><b>Cost.</b> The comparison needs the parent's revision, and hashing a multi-megabyte parent
 * once per chunk hit would be a real per-query cost. Two things bound it: the parent-level
 * {@code content_sha256} (tempdoc 931 §C.6) is read straight off the parent's stored fields, so the
 * common path hashes nothing at all; and when it is absent (a document indexed before §C.6) the
 * computed digest is memoized per parent for the lifetime of this guard — one instance per read —
 * so N chunks of one parent cost one hash, not N. Trusting the stored value rests on §C.6's
 * invariant that {@code content_sha256} is written from the same digest wherever {@code content}
 * is: {@code IndexingDocumentOps#buildDocumentFields}, both {@code WorkerIngestService} VDU
 * content-overwrite branches, and {@code KnowledgeServerMigrationOps}' VDU replay are the only
 * production writers of {@code content}, and all four write the pair together.
 */
final class ChunkReadRevisionGuard {

  private static final Logger log = LoggerFactory.getLogger(ChunkReadRevisionGuard.class);

  /** A parent document's stored content plus its stored {@code content_sha256}, if it has one. */
  record ParentRevision(String content, String storedRevision) {}

  private final Map<String, String> revisionByParent = new HashMap<>();
  private final UnaryOperator<String> digest;
  private int mismatches;

  ChunkReadRevisionGuard() {
    this(ChunkParentRevision::sha256Hex);
  }

  /** Digest seam — lets a test count how many times a parent's content is actually hashed. */
  ChunkReadRevisionGuard(UnaryOperator<String> digest) {
    this.digest = digest;
  }

  /**
   * @return the chunk's exact parent slice, or empty when the parent now in the index is not the
   *     revision the chunk's offsets address
   */
  Optional<String> slice(
      String chunkDocId, DocumentFieldOps.ChunkSlice slice, ParentRevision parent) {
    if (slice == null || parent == null || parent.content() == null) {
      return Optional.empty();
    }
    String content = parent.content();
    if (slice.startChar() < 0
        || slice.endChar() < slice.startChar()
        || slice.endChar() > content.length()) {
      return Optional.empty();
    }
    String chunkRevision = slice.parentContentRevision();
    String parentRevision = revisionOf(slice.parentDocId(), parent);
    if (chunkRevision == null || chunkRevision.isBlank() || !chunkRevision.equals(parentRevision)) {
      mismatches++;
      log.debug(
          "chunk {} is not consistent with parent {} (chunk revision {}, parent revision {});"
              + " omitting its text rather than slicing the newer revision",
          chunkDocId,
          slice.parentDocId(),
          ChunkParentRevision.shortForm(chunkRevision),
          ChunkParentRevision.shortForm(parentRevision));
      return Optional.empty();
    }
    return Optional.of(content.substring(slice.startChar(), slice.endChar()));
  }

  /** Chunk reads refused because the parent was at a different revision. */
  int mismatchCount() {
    return mismatches;
  }

  private String revisionOf(String parentDocId, ParentRevision parent) {
    String memoized = revisionByParent.get(parentDocId);
    if (memoized != null) {
      return memoized;
    }
    String stored = parent.storedRevision();
    String revision =
        stored != null && !stored.isBlank() ? stored : digest.apply(parent.content());
    revisionByParent.put(parentDocId, revision);
    return revision;
  }
}
