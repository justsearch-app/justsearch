/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A matching parent timestamp cannot certify a partially committed chunk replacement. */
final class ChunkSetCompleteness {
  private static final Logger log = LoggerFactory.getLogger(ChunkSetCompleteness.class);
  // Reader keys include live-doc changes; a core key would incorrectly survive child deletion.
  // Values never hold a reader/key. Close listeners release the summaries promptly.
  private final Map<IndexReader.CacheKey, LeafSummary> leaves = new WeakHashMap<>();
  private final Map<IndexReader.CacheKey, ReaderSummary> readers = new WeakHashMap<>();

  private static final Set<String> CHILD_FIELDS = Set.of(SchemaFields.PARENT_DOC_ID,
      SchemaFields.CHUNK_INDEX, SchemaFields.CHUNK_TOTAL, SchemaFields.CHUNK_PARENT_CONTENT_SHA256);

  private static final Set<String> PARENT_FIELDS = Set.of(SchemaFields.CHUNK_TOTAL,
      SchemaFields.CHUNK_PARENT_CONTENT_SHA256, SchemaFields.CONTENT_SHA256);

  private record LeafSummary(Map<String, ChunkSummary> children,
      Map<Integer, Map<String, String>> parents) {}

  private record ReaderSummary(Map<String, ChunkSummary> children,
      Map<Integer, Map<String, String>> parents) {}

  private static final class ChunkSummary {
    private int total = -1;
    private String revision;
    private int rows;
    private boolean valid = true;
    private final Set<Integer> ordinals = new HashSet<>();

    void add(int ordinal, int expected, String digest) {
      rows++;
      if (rows == 1) { total = expected; revision = digest; }
      valid &= expected > 0 && expected == total && ordinal >= 0 && ordinal < expected
          && digest != null && !digest.isBlank() && digest.equals(revision)
          && ordinals.add(ordinal);
    }

    void merge(ChunkSummary other) {
      if (rows == 0) { total = other.total; revision = other.revision; }
      valid &= other.valid && total == other.total && java.util.Objects.equals(revision, other.revision);
      rows += other.rows;
      for (int ordinal : other.ordinals) valid &= ordinals.add(ordinal);
    }

    boolean matches(int expected, String digest) {
      return valid && rows == expected && total == expected && ordinals.size() == expected
          && java.util.Objects.equals(revision, digest);
    }
  }

  record Declaration(int total, String revision) {}

  record Assessment(boolean complete, Declaration backfill) {}

  void prepare(IndexSearcher searcher) throws IOException {
    summaries(searcher);
  }

  Assessment assess(IndexSearcher searcher, int parent, String parentId) throws IOException {
    // Reading even a tiny stored field can decompress the parent's entire large content block.
    // Prepare this metadata with the reader too; admission must not repeat that content-sized cost.
    var record = summaries(searcher).parents().get(parent);
    if (record == null) record = SearchResultFormatter.extractFromStoredFields(
        searcher.storedFields(), parent, false, PARENT_FIELDS);
    if (!record.containsKey(SchemaFields.CHUNK_TOTAL)
        || !record.containsKey(SchemaFields.CHUNK_PARENT_CONTENT_SHA256)) {
      Declaration declaration = legacyDeclaration(searcher, parent, parentId,
          record.get(SchemaFields.CONTENT_SHA256));
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

  private Declaration legacyDeclaration(IndexSearcher searcher, int parent, String parentId, String digest)
      throws IOException {
    var children = summaries(searcher).children().get(parentId);
    // Earlier canonical writers already stamped each child's intended total and parent revision.
    // Certify those stamps once, without splitting or rewriting any child on upgrade.
    if (children != null) {
      if (digest == null || digest.isBlank()) {
        String content = searcher.storedFields().document(parent, Set.of(SchemaFields.CONTENT))
            .get(SchemaFields.CONTENT);
        digest = ChunkParentRevision.sha256Hex(content == null ? "" : content);
      }
      return children.matches(children.total, digest) ? new Declaration(children.total, digest) : null;
    }
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

  boolean matchesDeclaration(
      IndexSearcher searcher, String parentId, int expected, String revision) throws IOException {
    var summary = summaries(searcher).children().get(parentId);
    return summary == null ? expected == 0 : summary.matches(expected, revision);
  }

  /** At most one metadata pass per live leaf, and one merge per reader, shared by every parent. */
  private synchronized ReaderSummary summaries(IndexSearcher searcher) throws IOException {
    var helper = searcher.getIndexReader().getReaderCacheHelper();
    if (helper != null && readers.containsKey(helper.getKey())) return readers.get(helper.getKey());
    long started = System.nanoTime();
    long metadataRows = 0;
    long parentRows = 0;
    var result = new HashMap<String, ChunkSummary>();
    var parentRecords = new HashMap<Integer, Map<String, String>>();
    for (var context : searcher.getIndexReader().leaves()) {
      var leaf = context.reader();
      var leafHelper = leaf.getReaderCacheHelper();
      var summary = leafHelper == null ? null : leaves.get(leafHelper.getKey());
      if (summary == null) {
        summary = readLeaf(leaf);
        for (var children : summary.children().values()) metadataRows += children.rows;
        parentRows += summary.parents().size();
        if (leafHelper != null) {
          leaves.put(leafHelper.getKey(), summary);
          leafHelper.addClosedListener(key -> { synchronized (this) { leaves.remove(key); } });
        }
      }
      for (var entry : summary.children().entrySet())
        result.computeIfAbsent(entry.getKey(), ignored -> new ChunkSummary()).merge(entry.getValue());
      for (var entry : summary.parents().entrySet())
        parentRecords.put(context.docBase + entry.getKey(), entry.getValue());
    }
    var prepared = new ReaderSummary(result, parentRecords);
    if (helper != null) {
      readers.put(helper.getKey(), prepared);
      helper.addClosedListener(key -> { synchronized (this) { readers.remove(key); } });
    }
    if (metadataRows > 0 || parentRows > 0) {
      log.debug("Prepared chunk admission reader: childRowsRead={}, parentRowsRead={}, parents={}, elapsedMs={}",
          metadataRows, parentRows, result.size(), (System.nanoTime() - started) / 1_000_000.0);
    }
    return prepared;
  }

  private static LeafSummary readLeaf(LeafReader leaf) throws IOException {
    var result = new HashMap<String, ChunkSummary>();
    var parents = leaf.getSortedDocValues(SchemaFields.PARENT_DOC_ID);
    var live = leaf.getLiveDocs();
    var stored = leaf.storedFields();
    var childDocs = new java.util.BitSet(leaf.maxDoc());
    if (parents == null) {
      // Custom/older schemas may index this keyword without doc values. Its postings still
      // select exactly the child rows; absence of doc values must never certify a zero set.
      var terms = leaf.terms(SchemaFields.PARENT_DOC_ID);
      if (terms != null) {
        var iterator = terms.iterator();
        for (var term = iterator.next(); term != null; term = iterator.next()) {
          String parent = term.utf8ToString();
          var postings = iterator.postings(null, PostingsEnum.NONE);
          for (int doc = postings.nextDoc(); doc != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
              doc = postings.nextDoc()) {
            childDocs.set(doc);
            if (live == null || live.get(doc)) addChild(result, stored, doc, parent);
          }
        }
      }
    } else {
      for (int doc = parents.nextDoc(); doc != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
          doc = parents.nextDoc()) {
        childDocs.set(doc);
        if (live != null && !live.get(doc)) continue;
        String parent = parents.lookupOrd(parents.ordValue()).utf8ToString();
        addChild(result, stored, doc, parent);
      }
    }
    var parentRecords = new HashMap<Integer, Map<String, String>>();
    boolean hasMetadata = PARENT_FIELDS.stream().anyMatch(f -> leaf.getFieldInfos().fieldInfo(f) != null);
    if (hasMetadata) {
      for (int doc = 0; doc < leaf.maxDoc(); doc++) {
        if (childDocs.get(doc) || live != null && !live.get(doc)) continue;
        parentRecords.put(doc, SearchResultFormatter.extractFromStoredFields(stored, doc, false, PARENT_FIELDS));
      }
    }
    return new LeafSummary(result, parentRecords);
  }

  private static void addChild(Map<String, ChunkSummary> result, StoredFields stored, int doc,
      String parent) throws IOException {
    var chunk = SearchResultFormatter.extractFromStoredFields(stored, doc, false, CHILD_FIELDS);
    var summary = result.computeIfAbsent(parent, ignored -> new ChunkSummary());
    try {
      summary.add(Integer.parseInt(chunk.get(SchemaFields.CHUNK_INDEX)),
          Integer.parseInt(chunk.get(SchemaFields.CHUNK_TOTAL)),
          chunk.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
    } catch (NumberFormatException invalid) {
      summary.rows++;
      summary.valid = false;
    }
  }

  /** Missing declarations only; content and vectors stay on the leased reader used for RMW. */
  Map<String, Integer> legacyParents(IndexSearcher searcher, String idField) throws IOException {
    var result = new HashMap<String, Integer>();
    var allow = Set.of(idField, SchemaFields.CHUNK_TOTAL,
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, SchemaFields.PARENT_DOC_ID);
    for (var context : searcher.getIndexReader().leaves()) {
      var leaf = context.reader();
      var live = leaf.getLiveDocs();
      var parents = leaf.getSortedDocValues(SchemaFields.PARENT_DOC_ID);
      var stored = leaf.storedFields();
      for (int doc = 0; doc < leaf.maxDoc(); doc++) {
        if (live != null && !live.get(doc) || parents != null && parents.advanceExact(doc)) continue;
        var fields = SearchResultFormatter.extractFromStoredFields(stored, doc, false, allow);
        if (fields.containsKey(SchemaFields.PARENT_DOC_ID)) continue;
        if (!fields.containsKey(SchemaFields.CHUNK_TOTAL)
            || !fields.containsKey(SchemaFields.CHUNK_PARENT_CONTENT_SHA256)) {
          String id = fields.get(idField);
          if (id != null) result.put(id, context.docBase + doc);
        }
      }
    }
    return result;
  }
}
