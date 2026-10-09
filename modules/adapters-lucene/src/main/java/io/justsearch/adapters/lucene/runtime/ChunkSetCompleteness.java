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

  record Declaration(int total, String revision) {}

  record Assessment(boolean complete, Declaration backfill) {}

  static Assessment assess(IndexSearcher searcher, int parent, String parentId) throws IOException {
    var stored = searcher.storedFields();
    var record = SearchResultFormatter.extractFromStoredFields(stored, parent, false,
        Set.of(SchemaFields.CHUNK_TOTAL, SchemaFields.CHUNK_PARENT_CONTENT_SHA256,
            SchemaFields.CONTENT_SHA256));
    if (!record.containsKey(SchemaFields.CHUNK_TOTAL)
        || !record.containsKey(SchemaFields.CHUNK_PARENT_CONTENT_SHA256)) {
      Declaration declaration = legacyDeclaration(searcher, parent, parentId);
      return new Assessment(declaration != null, declaration);
    }
    int expected;
    try {
      expected = Integer.parseInt(record.get(SchemaFields.CHUNK_TOTAL));
    } catch (NumberFormatException invalid) {
      return new Assessment(false, null);
    }
    String revision = record.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
    if (expected < 0 || revision == null || revision.isBlank()
        || !revision.equals(record.get(SchemaFields.CONTENT_SHA256))) return new Assessment(false, null);
    return new Assessment(matchesDeclaration(searcher, parentId, expected, revision), null);
  }

  private static Declaration legacyDeclaration(IndexSearcher searcher, int parent, String parentId)
      throws IOException {
    var fields = SearchResultFormatter.extractFromStoredFields(searcher.storedFields(), parent, true,
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
    String revision = ChunkParentRevision.sha256Hex(content == null ? "" : content);
    return matchesDeclaration(searcher, parentId, expected, revision)
        ? new Declaration(expected, revision) : null;
  }

  static boolean matchesDeclaration(
      IndexSearcher searcher, String parentId, int expected, String revision) throws IOException {
    var stored = searcher.storedFields();
    var query = new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentId));
    // Count first: no retrieval cap may silently omit a tail of a large file.
    int actual = searcher.count(query);
    if (actual != expected) return false;
    if (expected == 0) return true;
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
