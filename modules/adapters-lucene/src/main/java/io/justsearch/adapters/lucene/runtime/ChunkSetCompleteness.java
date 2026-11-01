/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Bits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A matching parent timestamp cannot certify a partially committed chunk replacement.
 *
 * <p>The chunk metadata of a segment is immutable: stored fields never change for a segment core,
 * only its deletions do. So the metadata rows are read once per segment core and cached under the
 * core key, and every reader applies its own live documents when a parent is assessed. A reader
 * reopen that leaves a segment untouched, or only changes its deletions, reads nothing again;
 * preparing a reader costs one lookup per segment plus the rows of segments it has never seen.
 * Assessing a parent costs its own child rows, never the index size.
 */
final class ChunkSetCompleteness {
  private static final Logger log = LoggerFactory.getLogger(ChunkSetCompleteness.class);
  // Core keys survive reopens with new deletions; liveness is therefore never cached here and is
  // applied from the assessing reader's own live docs. Close listeners release cores promptly.
  private final Map<IndexReader.CacheKey, CoreRows> cores = new WeakHashMap<>();

  private final Map<IndexReader.CacheKey, Boolean> zeroDeclarations = new WeakHashMap<>();

  /** Reuses admission's segment metadata; unchanged leaf readers need no further scan or IO. */
  synchronized boolean allParentsDeclareZeroChunks(IndexSearcher searcher) throws IOException {
    for (var context : searcher.getIndexReader().leaves()) {
      var leaf = context.reader();
      var helper = leaf.getReaderCacheHelper();
      var key = helper == null ? null : helper.getKey();
      Boolean cached = key == null ? null : zeroDeclarations.get(key);
      if (cached != null) {
        if (!cached) return false;
        continue;
      }
      var metadata = rows(leaf).parents;
      var live = leaf.getLiveDocs();
      boolean allZero = true;
      for (int doc = 0; doc < leaf.maxDoc(); doc++) {
        if (live != null && !live.get(doc)) continue;
        var record = metadata.get(doc);
        if (record == null) {
          // An older reader can still see a parent deleted when its core was first prepared.
          record = SearchResultFormatter.extractFromStoredFields(leaf.storedFields(), doc, false,
              Set.of(SchemaFields.CHUNK_TOTAL, SchemaFields.CHUNK_PARENT_CONTENT_SHA256,
                  SchemaFields.CONTENT_SHA256, SchemaFields.PARENT_DOC_ID));
        }
        String revision = record.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
        if (record.containsKey(SchemaFields.PARENT_DOC_ID)
            || !"0".equals(record.get(SchemaFields.CHUNK_TOTAL)) || revision == null
            || revision.isBlank() || !revision.equals(record.get(SchemaFields.CONTENT_SHA256))) {
          allZero = false;
          break;
        }
      }
      if (key != null) zeroDeclarations.put(key, allZero);
      if (!allZero) return false;
    }
    return true;
  }

  private static final Set<String> CHILD_FIELDS = Set.of(SchemaFields.PARENT_DOC_ID,
      SchemaFields.CHUNK_INDEX, SchemaFields.CHUNK_TOTAL, SchemaFields.CHUNK_PARENT_CONTENT_SHA256);

  private static final Set<String> PARENT_FIELDS = Set.of(SchemaFields.CHUNK_TOTAL,
      SchemaFields.CHUNK_PARENT_CONTENT_SHA256, SchemaFields.CONTENT_SHA256);

  /** A child row whose stored metadata has not been read (it was deleted when its core was read). */
  private static final int UNREAD = Integer.MIN_VALUE;
  /** A child row whose stored ordinal or total does not parse. */
  private static final int MALFORMED = Integer.MIN_VALUE + 1;

  /** Immutable-content rows of one segment core; only lazily read rows are filled in later. */
  private static final class CoreRows {
    final Map<String, ChildRows> children;
    final Map<Integer, Map<String, String>> parents;

    CoreRows(Map<String, ChildRows> children, Map<Integer, Map<String, String>> parents) {
      this.children = children;
      this.parents = parents;
    }
  }

  /** One parent's child rows in one core: segment-local doc ids and their stored metadata. */
  private static final class ChildRows {
    private int size;
    private int[] docs = new int[2];
    private int[] ordinals = new int[2];
    private int[] totals = new int[2];
    private String[] revisions = new String[2];

    void add(int doc) {
      if (size == docs.length) {
        int grown = size * 2;
        docs = Arrays.copyOf(docs, grown);
        ordinals = Arrays.copyOf(ordinals, grown);
        totals = Arrays.copyOf(totals, grown);
        revisions = Arrays.copyOf(revisions, grown);
      }
      docs[size] = doc;
      ordinals[size] = UNREAD;
      size++;
    }

    void read(int row, StoredFields stored, Map<String, String> revisionPool) throws IOException {
      var chunk = SearchResultFormatter.extractFromStoredFields(stored, docs[row], false, CHILD_FIELDS);
      try {
        ordinals[row] = Integer.parseInt(chunk.get(SchemaFields.CHUNK_INDEX));
        totals[row] = Integer.parseInt(chunk.get(SchemaFields.CHUNK_TOTAL));
      } catch (NumberFormatException invalid) {
        ordinals[row] = MALFORMED;
      }
      String revision = chunk.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
      // One retained string per distinct revision in a core, not one per chunk row.
      revisions[row] = revision == null ? null : revisionPool.computeIfAbsent(revision, r -> r);
    }

    void trim() {
      docs = Arrays.copyOf(docs, size);
      ordinals = Arrays.copyOf(ordinals, size);
      totals = Arrays.copyOf(totals, size);
      revisions = Arrays.copyOf(revisions, size);
    }
  }

  private static final class ChunkSummary {
    private int total = -1;
    private String revision;
    private int rows;
    private boolean valid = true;
    // Sparse 64-ordinal words bound storage by observed rows even for malformed huge ordinals.
    private final Map<Integer, Long> ordinalWords = new HashMap<>();
    private int uniqueOrdinals;

    void add(int ordinal, int expected, String digest) {
      rows++;
      if (ordinal == MALFORMED) {
        valid = false;
        return;
      }
      if (rows == 1) { total = expected; revision = digest; }
      valid &= expected > 0 && expected == total && ordinal >= 0 && ordinal < expected
          && digest != null && !digest.isBlank() && digest.equals(revision)
          && addOrdinal(ordinal);
    }

    private boolean addOrdinal(int ordinal) {
      int word = ordinal >>> 6;
      long bit = 1L << (ordinal & 63);
      long previous = ordinalWords.getOrDefault(word, 0L);
      if ((previous & bit) != 0) return false;
      ordinalWords.put(word, previous | bit);
      uniqueOrdinals++;
      return true;
    }

    boolean matches(int expected, String digest) {
      return valid && rows == expected && total == expected && uniqueOrdinals == expected
          && java.util.Objects.equals(revision, digest);
    }
  }

  record Declaration(int total, String revision) {}

  record Assessment(boolean complete, Declaration backfill) {}

  /** Reads the rows of segment cores this reader is the first to see; nothing else. */
  void prepare(IndexSearcher searcher) throws IOException {
    synchronized (this) {
      for (var context : searcher.getIndexReader().leaves()) rows(context.reader());
    }
  }

  Assessment assess(IndexSearcher searcher, int parent, String parentId) throws IOException {
    // Reading even a tiny stored field can decompress the parent's entire large content block.
    // The core rows carry this metadata; admission must not repeat that content-sized cost.
    var record = parentRecord(searcher, parent);
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
    var children = children(searcher, parentId);
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
    var summary = children(searcher, parentId);
    return summary == null ? expected == 0 : summary.matches(expected, revision);
  }

  /** The parent's prepared metadata, or null when its core holds none for it. */
  private synchronized Map<String, String> parentRecord(IndexSearcher searcher, int parent)
      throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    var context = leaves.get(ReaderUtil.subIndex(parent, leaves));
    return rows(context.reader()).parents.get(parent - context.docBase);
  }

  /** The live child rows of one parent across every segment of this reader; null when none. */
  private synchronized ChunkSummary children(IndexSearcher searcher, String parentId)
      throws IOException {
    ChunkSummary summary = null;
    for (var context : searcher.getIndexReader().leaves()) {
      var leaf = context.reader();
      var rows = rows(leaf).children.get(parentId);
      if (rows == null) continue;
      Bits live = leaf.getLiveDocs();
      StoredFields stored = null;
      Map<String, String> pool = null;
      for (int row = 0; row < rows.size; row++) {
        if (live != null && !live.get(rows.docs[row])) continue;
        if (rows.ordinals[row] == UNREAD) {
          // Deleted when its core was first read, yet live here: an older or unfiltered reader.
          if (stored == null) { stored = leaf.storedFields(); pool = new HashMap<>(); }
          rows.read(row, stored, pool);
        }
        if (summary == null) summary = new ChunkSummary();
        summary.add(rows.ordinals[row], rows.totals[row], rows.revisions[row]);
      }
    }
    return summary;
  }

  /** Must hold this monitor. Reads a core's rows on first sight and caches them by core key. */
  private CoreRows rows(LeafReader leaf) throws IOException {
    var helper = leaf.getCoreCacheHelper();
    var key = helper == null ? null : helper.getKey();
    var cached = key == null ? null : cores.get(key);
    if (cached != null) return cached;
    var read = readCore(leaf);
    if (key != null) {
      cores.put(key, read);
      helper.addClosedListener(closed -> { synchronized (this) { cores.remove(closed); } });
    }
    return read;
  }

  private static CoreRows readCore(LeafReader leaf) throws IOException {
    long started = System.nanoTime();
    var children = new HashMap<String, ChildRows>();
    var parentIds = leaf.getSortedDocValues(SchemaFields.PARENT_DOC_ID);
    var childDocs = new java.util.BitSet(leaf.maxDoc());
    if (parentIds == null) {
      // Custom/older schemas may index this keyword without doc values. Its postings still
      // select exactly the child rows; absence of doc values must never certify a zero set.
      var terms = leaf.terms(SchemaFields.PARENT_DOC_ID);
      if (terms != null) {
        var iterator = terms.iterator();
        for (var term = iterator.next(); term != null; term = iterator.next()) {
          var rows = children.computeIfAbsent(term.utf8ToString(), ignored -> new ChildRows());
          var postings = iterator.postings(null, PostingsEnum.NONE);
          for (int doc = postings.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS;
              doc = postings.nextDoc()) {
            childDocs.set(doc);
            rows.add(doc);
          }
        }
      }
    } else {
      for (int doc = parentIds.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS;
          doc = parentIds.nextDoc()) {
        childDocs.set(doc);
        String parent = parentIds.lookupOrd(parentIds.ordValue()).utf8ToString();
        children.computeIfAbsent(parent, ignored -> new ChildRows()).add(doc);
      }
    }
    // Rows deleted at first sight are only read if an older reader still sees them live.
    Bits live = leaf.getLiveDocs();
    var stored = leaf.storedFields();
    var pool = new HashMap<String, String>();
    long childRows = 0;
    for (var rows : children.values()) {
      rows.trim();
      for (int row = 0; row < rows.size; row++) {
        if (live != null && !live.get(rows.docs[row])) continue;
        rows.read(row, stored, pool);
        childRows++;
      }
    }
    var parents = new HashMap<Integer, Map<String, String>>();
    boolean hasMetadata = PARENT_FIELDS.stream().anyMatch(f -> leaf.getFieldInfos().fieldInfo(f) != null);
    if (hasMetadata) {
      for (int doc = 0; doc < leaf.maxDoc(); doc++) {
        if (childDocs.get(doc) || live != null && !live.get(doc)) continue;
        parents.put(doc, SearchResultFormatter.extractFromStoredFields(stored, doc, false, PARENT_FIELDS));
      }
    }
    if (childRows > 0 || !parents.isEmpty()) {
      log.debug("Prepared chunk admission segment: childRowsRead={}, parentRowsRead={}, parents={}, elapsedMs={}",
          childRows, parents.size(), children.size(), (System.nanoTime() - started) / 1_000_000.0);
    }
    return new CoreRows(children, parents);
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
