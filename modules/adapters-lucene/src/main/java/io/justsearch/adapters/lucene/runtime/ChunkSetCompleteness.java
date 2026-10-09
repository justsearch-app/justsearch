/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.io.IOException;
import java.util.BitSet;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;

/** A matching parent timestamp cannot certify a partially committed chunk replacement. */
final class ChunkSetCompleteness {
  private ChunkSetCompleteness() {}

  static boolean isComplete(IndexSearcher searcher, int parent, String parentId) throws IOException {
    var stored = searcher.storedFields();
    var fields = SearchResultFormatter.extractFromStoredFields(stored, parent, true,
        Set.of(SchemaFields.CONTENT, SchemaFields.MIME_BASE, SchemaFields.FILE_KIND));
    String content = fields.get(SchemaFields.CONTENT);
    int expected = 0;
    if (content != null && content.length() >= ChunkSplitter.CHUNK_THRESHOLD_CHARS) {
      var mode = ChunkSplitter.Mode.fromMimeOrFileKind(
          fields.get(SchemaFields.MIME_BASE), fields.get(SchemaFields.FILE_KIND));
      int split = ChunkSplitter.splitWithMetadata(content, ChunkSplitter.DEFAULT_CHUNK_TOKENS,
          ChunkSplitter.DEFAULT_OVERLAP_TOKENS, mode).size();
      if (split > 1) expected = split;
    }
    var query = new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentId));
    // Count first: no retrieval cap may silently omit a tail of a large file.
    int actual = searcher.count(query);
    if (actual != expected) return false;
    if (expected == 0) return true;
    String revision = ChunkParentRevision.sha256Hex(content);
    var ordinals = new BitSet(expected);
    var allow = Set.of(SchemaFields.CHUNK_INDEX, SchemaFields.CHUNK_TOTAL,
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
    for (var hit : searcher.search(query, actual).scoreDocs) {
      var chunk = SearchResultFormatter.extractFromStoredFields(stored, hit.doc, false, allow);
      try {
        int ordinal = Integer.parseInt(chunk.get(SchemaFields.CHUNK_INDEX));
        int total = Integer.parseInt(chunk.get(SchemaFields.CHUNK_TOTAL));
        if (total != expected || ordinal < 0 || ordinal >= expected || ordinals.get(ordinal)
            || !revision.equals(chunk.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256))) return false;
        ordinals.set(ordinal);
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return ordinals.cardinality() == expected;
  }
}
