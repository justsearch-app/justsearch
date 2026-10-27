/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.buildRuntimeSort;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import net.jcip.annotations.ThreadSafe;
import java.util.function.IntUnaryOperator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal read-path collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates searcher lifecycle helpers and read-path utilities (cursor decode, projection,
 * doc-value reading) that were previously inlined in the runtime.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code close()}.
 * Access from the runtime must go through a volatile snapshot to ensure visibility across threads.
 */
@ThreadSafe
public final class ReadPathOps {
  private static final Logger log = LoggerFactory.getLogger(ReadPathOps.class);

  private final RuntimeSession session;
  private final String idField;

  ReadPathOps(RuntimeSession session, String idField) {
    this.session = session;
    this.idField = idField;
  }

  /**
   * Executes an operation with an acquired IndexSearcher, ensuring proper release.
   *
   * <p>Uses a local snapshot of the SearcherManager so that acquire and release always operate on
   * the same manager instance, even if the runtime's field is concurrently nulled by {@code
   * close()}.
   */
  <T> T withSearcher(SearcherOperation<T> operation) throws IOException {
    return new SearcherBridge(session).withSearcher(operation);
  }

  /**
   * Complete metadata-only inspection of one live reader. The caller must exclude all writers
   * through use of the result; a sequence witness additionally rejects owner-thread replay writes.
   * Stored-only ownership markers and legal lexical PATH aliases cannot be selected by postings.
   */
  public NativeOwnership inspectNativeOwnership(Set<String> paths, java.util.function.Predicate<String> reservedIdentity, long deadlineNanos) throws IOException {
    var lifecycle = session.snapshot;
    if (lifecycle == null || lifecycle.searcherManager() == null
        || lifecycle.writer() != null && !lifecycle.writer().isOpen()) {
      throw new IOException("Native ownership runtime is unavailable");
    }
    var writer = lifecycle.writer();
    // Read-only A has no in-process writer. An upgrade replaces the lifecycle snapshot and
    // invalidates this proof; writable A/B additionally reject every completed owner write.
    Long sequence = writer == null ? null : writer.getMaxCompletedSequenceNumber();
    lifecycle.searcherManager().maybeRefreshBlocking();
    Set<String> selected = Set.copyOf(paths);
    NativeMembership membership = withSearcher(searcher -> {
      Set<String> blocked = new HashSet<>();
      Set<String> fields = Set.of(idField, SchemaFields.PATH, SchemaFields.COLLECTION,
          SchemaFields.PARENT_DOC_ID, SchemaFields.PROJECTION_SOURCE_ID);
      for (var leaf : searcher.getIndexReader().leaves()) {
        var live = leaf.reader().getLiveDocs();
        var stored = leaf.reader().storedFields();
        for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
          if (System.nanoTime() >= deadlineNanos || Thread.currentThread().isInterrupted()) {
            throw new IOException("Native ownership inspection is incomplete");
          }
          if (live != null && !live.get(doc)) continue;
          var metadata = nativeMetadata(searcher, leaf.docBase + doc, stored.document(doc, fields), fields);
          String id = requiredNativeIdentity(metadata.get(idField));
          Set<String> associations = new HashSet<>();
          nativeAssociation(associations, id);
          nativeAssociation(associations, metadata.get(SchemaFields.PATH));
          boolean protectedOwner = protectedNativeOwner(metadata, id, reservedIdentity);
          Set<String> visited = new HashSet<>();
          visited.add(id);
          String associatedPath = nativeKey(metadata.get(SchemaFields.PATH));
          boolean consistent = true;
          String parent = metadata.get(SchemaFields.PARENT_DOC_ID);
          while (parent != null) {
            if (System.nanoTime() >= deadlineNanos) throw new IOException("Native ownership join is incomplete");
            requiredNativeIdentity(parent);
            if (!visited.add(parent) || visited.size() > 64) {
              throw new IOException("Native ownership parent chain is incomplete");
            }
            nativeAssociation(associations, parent);
            var hits = searcher.search(new TermQuery(new Term(idField, parent)), 2);
            if (hits.scoreDocs.length != 1) throw new IOException("Native ownership parent is missing or ambiguous");
            var ancestor = nativeMetadata(searcher, hits.scoreDocs[0].doc,
                searcher.storedFields().document(hits.scoreDocs[0].doc, fields), fields);
            if (!java.util.Objects.equals(metadata.get(SchemaFields.COLLECTION), ancestor.get(SchemaFields.COLLECTION))) consistent = false;
            if (!parent.equals(ancestor.get(idField))) throw new IOException("Native ownership parent identity changed");
            String parentPath = nativeKey(ancestor.get(SchemaFields.PATH));
            if (parentPath != null) {
              if (associatedPath != null && !associatedPath.equals(parentPath)) consistent = false;
              associatedPath = parentPath;
            }
            nativeAssociation(associations, ancestor.get(SchemaFields.PATH));
            protectedOwner |= protectedNativeOwner(ancestor, parent, reservedIdentity);
            parent = ancestor.get(SchemaFields.PARENT_DOC_ID);
          }
          associations.retainAll(selected);
          if (associations.isEmpty()) continue;
          // Multiple physical associations or a non-file identity cannot prove ordinary ownership.
          String source = metadata.get(SchemaFields.PATH);
          String parentId = metadata.get(SchemaFields.PARENT_DOC_ID);
          String ordinary = nativeKey(parentId == null ? id : parentId);
          if (protectedOwner || !consistent || ordinary == null
              || associatedPath != null && !associatedPath.equals(ordinary)
              || source != null && !java.util.Objects.equals(nativeKey(source), ordinary)
              || associations.stream().anyMatch(key -> !key.equals(ordinary))) {
            blocked.addAll(associations);
          }
        }
      }
      Set<String> absent = new HashSet<>();
      for (String path : selected) {
        if (System.nanoTime() >= deadlineNanos) throw new IOException("Native absence inspection is incomplete");
        if (searcher.count(IndexCountOps.idAndChunksQuery(path)) == 0) absent.add(path);
      }
      return new NativeMembership(Set.copyOf(blocked), Set.copyOf(absent));
    });
    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos || session.snapshot != lifecycle
        || writer != null && sequence.longValue() != writer.getMaxCompletedSequenceNumber()) {
      throw new IOException("Native ownership changed during inspection");
    }
    return new NativeOwnership(lifecycle, sequence, deadlineNanos, selected, membership);
  }

  private org.apache.lucene.document.Document nativeMetadata(IndexSearcher searcher, int doc,
      org.apache.lucene.document.Document metadata, Set<String> fields) throws IOException {
    requireNativeMetadata(metadata);
    var leaves = searcher.getIndexReader().leaves();
    var leaf = leaves.get(ReaderUtil.subIndex(doc, leaves));
    int local = doc - leaf.docBase;
    for (String field : fields) {
      var info = leaf.reader().getFieldInfos().fieldInfo(field);
      if (info == null || info.getDocValuesType() == org.apache.lucene.index.DocValuesType.NONE) continue;
      String value = null;
      // Scalar control metadata must not use the general projection's best-effort fallback
      // for an incompatible column. Read the actual column strictly in this same reader.
      switch (info.getDocValuesType()) {
        case SORTED -> {
          var column = DocValues.getSorted(leaf.reader(), field);
          if (column.advanceExact(local)) value = column.lookupOrd(column.ordValue()).utf8ToString();
        }
        case SORTED_SET -> {
          var column = DocValues.getSortedSet(leaf.reader(), field);
          if (column.advanceExact(local)) {
            if (column.docValueCount() != 1) throw new IOException("Native ownership metadata is not scalar");
            value = column.lookupOrd(column.nextOrd()).utf8ToString();
          }
        }
        default -> throw new IOException("Native ownership metadata column has an incompatible type");
      }
      if (value == null) continue;
      String stored = metadata.get(field);
      if (stored != null && !stored.equals(value)) {
        throw new IOException("Native ownership stored and doc-values metadata disagree");
      }
      if (stored == null) metadata.add(new org.apache.lucene.document.StoredField(field, value));
    }
    return metadata;
  }

  private void requireNativeMetadata(org.apache.lucene.document.Document metadata) throws IOException {
    for (String field : List.of(idField, SchemaFields.PATH, SchemaFields.COLLECTION, SchemaFields.PARENT_DOC_ID)) {
      if (metadata.getFields(field).length > 1) throw new IOException("Native ownership metadata is ambiguous");
      var value = metadata.getField(field);
      if (value != null && value.stringValue() == null) throw new IOException("Native ownership metadata is not text");
    }
  }

  private static String requiredNativeIdentity(String id) throws IOException {
    if (id == null || id.isBlank()) throw new IOException("Native ownership identity is missing");
    return id;
  }

  private static boolean protectedNativeOwner(org.apache.lucene.document.Document metadata, String id,
      java.util.function.Predicate<String> reservedIdentity) {
    return metadata.getField(SchemaFields.PROJECTION_SOURCE_ID) != null
        || reservedIdentity.test(id)
        || io.justsearch.configuration.InternalCollections.isReserved(metadata.get(SchemaFields.COLLECTION));
  }

  private static void nativeAssociation(Set<String> associations, String value) {
    String key = nativeKey(value);
    if (key != null) associations.add(key);
  }

  private static String nativeKey(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      var path = java.nio.file.Path.of(value);
      if (!path.isAbsolute()) return null;
      String key = path.normalize().toString();
      return io.justsearch.configuration.PlatformPaths.isWindows()
          ? key.toLowerCase(java.util.Locale.ROOT) : key;
    } catch (java.nio.file.InvalidPathException nonFile) { return null; }
  }

  private record NativeMembership(Set<String> protectedPaths, Set<String> absent) {}

  /** Attempt-local only: no reader/commit epoch is treated as an all-write permission. */
  public final class NativeOwnership {
    private final LifecycleSnapshot lifecycle;
    private final Long sequence;
    private final long deadlineNanos;
    private final Set<String> selected;
    private final NativeMembership membership;

    private NativeOwnership(LifecycleSnapshot lifecycle, Long sequence, long deadlineNanos,
        Set<String> selected, NativeMembership membership) {
      this.lifecycle = lifecycle; this.sequence = sequence; this.deadlineNanos = deadlineNanos;
      this.selected = selected; this.membership = membership;
    }

    public boolean ordinary(String path) {
      return current() && selected.contains(path) && !membership.protectedPaths().contains(path);
    }

    /** Same exact ID/chunk query as IndexCountOps, evaluated during preparation, never publication. */
    public boolean absent(String path) { return current() && membership.absent().contains(path); }

    private boolean current() {
      return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadlineNanos && session.snapshot == lifecycle
          && (sequence == null ? lifecycle.writer() == null : lifecycle.writer().isOpen()
              && lifecycle.writer().getMaxCompletedSequenceNumber() == sequence.longValue());
    }
  }

  org.apache.lucene.search.ScoreDoc decodeSearchAfterCursor(
      IndexSearcher searcher, String token, RuntimeSearchSort expectedSort) {
    SearchAfterCursorHelper.DecodedCursor decoded = SearchAfterCursorHelper.decode(token);
    if (decoded == null) {
      return null;
    }
    if (expectedSort != decoded.sort()) {
      throw new IllegalArgumentException("cursor_sort_mismatch");
    }
    String docId = decoded.docId();
    if (docId == null || docId.isBlank()) {
      throw new IllegalArgumentException("cursor_missing_doc_id");
    }
    int docNum = resolveDocNumById(searcher, docId);
    if (docNum < 0) {
      throw new IllegalArgumentException("cursor_doc_not_found");
    }
    return switch (expectedSort) {
      case RELEVANCE -> {
        if (decoded.score() == null) {
          throw new IllegalArgumentException("cursor_missing_score");
        }
        yield new FieldDoc(
            docNum,
            decoded.score(),
            new Object[] {Float.valueOf(decoded.score()), new BytesRef(docId)});
      }
      case MODIFIED_DESC, MODIFIED_ASC -> {
        if (decoded.modifiedAt() == null) {
          throw new IllegalArgumentException("cursor_missing_modified_at");
        }
        yield new FieldDoc(
            docNum,
            Float.NaN,
            new Object[] {Long.valueOf(decoded.modifiedAt()), new BytesRef(docId)});
      }
      case SIZE_DESC, SIZE_ASC -> {
        if (decoded.sizeBytes() == null) {
          throw new IllegalArgumentException("cursor_missing_size_bytes");
        }
        yield new FieldDoc(
            docNum,
            Float.NaN,
            new Object[] {Long.valueOf(decoded.sizeBytes()), new BytesRef(docId)});
      }
      case PATH_ASC, PATH_DESC ->
          new FieldDoc(docNum, Float.NaN, new Object[] {new BytesRef(docId)});
    };
  }

  int resolveDocNumById(IndexSearcher searcher, String docId) {
    if (searcher == null || docId == null || docId.isBlank()) {
      return -1;
    }
    try {
      TopDocs docs = searcher.search(new TermQuery(new Term(idField, docId)), 1);
      if (docs.scoreDocs == null || docs.scoreDocs.length == 0) {
        return -1;
      }
      return docs.scoreDocs[0].doc;
    } catch (IOException e) {
      log.debug("Failed to resolve cursor doc_id in current searcher: {}", e.getMessage());
      return -1;
    }
  }

  Set<String> buildStoredAllowlist(Set<String> projectionFields) {
    Set<String> allow = new HashSet<>();
    // Always include ID fields so we can construct stable SearchHit ids.
    allow.add(idField);
    allow.add(session.uidField);
    for (String f : projectionFields) {
      if (f == null || f.isBlank()) continue;
      FieldMapper.FieldDef def = session.fieldMapper.fieldDef(f);
      if (def != null && def.stored) {
        allow.add(def.id);
      }
    }
    // chunk_content remains indexed but is no longer stored. Its public projection is synthesized
    // from the exact parent slice, so fetch the geometry needed for that reconstruction without
    // exposing it unless the caller requested it explicitly.
    if (projectionFields.contains(SchemaFields.CHUNK_CONTENT)) {
      allow.add(SchemaFields.PARENT_DOC_ID);
      allow.add(SchemaFields.CHUNK_START_CHAR);
      allow.add(SchemaFields.CHUNK_END_CHAR);
      // Tempdoc 931 §E item 5: the revision those offsets address, so the reconstruction can
      // refuse a parent that has been rewritten since (ChunkReadRevisionGuard).
      allow.add(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
    }
    return allow;
  }

  void projectDocValues(
      IndexSearcher searcher, int docNum, Set<String> projectionFields, Map<String, String> out)
      throws IOException {
    if (projectionFields == null || projectionFields.isEmpty() || out == null) {
      return;
    }

    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(docNum, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = docNum - leaf.docBase;

    for (String field : projectionFields) {
      if (field == null || field.isBlank()) continue;
      if (out.containsKey(field)) continue;
      FieldMapper.FieldDef def = session.fieldMapper.fieldDef(field);
      if (def == null || !def.docValues) continue;
      if (SchemaFields.CONTENT.equals(def.id)) continue; // never project content via search hits

      switch (def.type) {
        case "keyword" -> {
          try {
            if (def.multiValued) {
              SortedSetDocValues sdv = DocValues.getSortedSet(leaf.reader(), def.id);
              if (sdv != null && sdv.advanceExact(docInLeaf)) {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < sdv.docValueCount(); i++) {
                  values.add(sdv.lookupOrd(sdv.nextOrd()).utf8ToString());
                }
                // Join for Map<String, String> compat; Phase E upgrades to List
                out.put(def.id, String.join(", ", values));
              }
            } else {
              SortedDocValues dv = DocValues.getSorted(leaf.reader(), def.id);
              if (dv != null && dv.advanceExact(docInLeaf)) {
                out.put(def.id, dv.lookupOrd(dv.ordValue()).utf8ToString());
              }
            }
          } catch (IllegalStateException ignored) {
            // field has no DocValues in this segment
          }
        }
        case "long", "boolean" -> {
          try {
            NumericDocValues dv = DocValues.getNumeric(leaf.reader(), def.id);
            if (dv != null && dv.advanceExact(docInLeaf)) {
              out.put(def.id, Long.toString(dv.longValue()));
            }
          } catch (IllegalStateException ignored) {
            // field has no NumericDocValues in this segment
          }
        }
        default -> {
          // no-op
        }
      }
    }
  }

  /**
   * Reads individual values from a multi-valued SortedSetDocValues field without joining.
   *
   * @return individual values, or empty list if the field has no values for this doc
   */
  List<String> projectMultiValuedDocValues(IndexSearcher searcher, int docNum, String fieldId)
      throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(docNum, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = docNum - leaf.docBase;

    try {
      SortedSetDocValues sdv = DocValues.getSortedSet(leaf.reader(), fieldId);
      if (sdv != null && sdv.advanceExact(docInLeaf)) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < sdv.docValueCount(); i++) {
          values.add(sdv.lookupOrd(sdv.nextOrd()).utf8ToString());
        }
        return values;
      }
    } catch (IllegalStateException ignored) {
      // field has no DocValues in this segment
    }
    return List.of();
  }

  long readLongDocValueOrStoredLong(IndexSearcher searcher, int docNum, String field)
      throws IOException {
    if (field == null || field.isBlank()) {
      return -1L;
    }

    // Prefer DocValues (fast, avoids stored field decoding).
    try {
      List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
      int leafIndex = ReaderUtil.subIndex(docNum, leaves);
      LeafReaderContext leaf = leaves.get(leafIndex);
      int docInLeaf = docNum - leaf.docBase;
      NumericDocValues dv = DocValues.getNumeric(leaf.reader(), field);
      if (dv != null && dv.advanceExact(docInLeaf)) {
        return dv.longValue();
      }
    } catch (IllegalStateException ignored) {
      // No NumericDocValues for this field in this segment.
    }

    // Fallback to stored field (numeric StoredField).
    Set<String> allow = Set.of(field);
    Map<String, String> stored =
        SearchResultFormatter.extractFromStoredFields(
            searcher.storedFields(), docNum, true, allow);
    String raw = stored.get(field);
    if (raw == null) {
      return -1L;
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException ignored) {
      return -1L;
    }
  }

  public SearchResult searchVector(float[] queryVector, int limit) {
    if (queryVector == null || queryVector.length == 0) {
      throw new IllegalArgumentException("queryVector must not be null or empty");
    }
    int effectiveLimit = limit <= 0 ? 10 : limit;
    KnnFloatVectorQuery knnQuery =
        buildKnnQuery(SchemaFields.VECTOR, queryVector, effectiveLimit, null);
    return search(knnQuery, effectiveLimit, null, RuntimeSearchSort.RELEVANCE, null);
  }

  public SearchResult searchVector(float[] queryVector, int limit, Query filter) {
    if (queryVector == null || queryVector.length == 0) {
      throw new IllegalArgumentException("queryVector must not be null or empty");
    }
    int effectiveLimit = limit <= 0 ? 10 : limit;
    KnnFloatVectorQuery knnQuery =
        buildKnnQuery(SchemaFields.VECTOR, queryVector, effectiveLimit, filter);
    return search(knnQuery, effectiveLimit, null, RuntimeSearchSort.RELEVANCE, null);
  }

  /**
   * The one site that builds a {@link KnnFloatVectorQuery} ON THE PRODUCT READ PATH — the doc-level
   * dense leg (both overloads above) and the chunk dense leg
   * ({@code ChunkSearchOps#searchChunkVector}) all come through here, so
   * {@code index.vector.exhaustive_search} cannot be honoured at one site and silently skipped at
   * another (lane F PR 0b).
   *
   * <p>Two BENCHMARK harnesses build their own query and deliberately bypass this factory —
   * {@code EngineVectorIndexBench} (a sentinel recall probe) and {@code VectorQuantizationGate} —
   * because each pins its own {@code k} and is measuring the approximation itself. They are not a
   * gap: a bench that inherited a global exact-search switch would stop measuring HNSW.
   *
   * <p>Default (switch off) is byte-identical to the three inline constructions it replaced:
   * {@code k = resolveVectorQueryK(limit)} and the caller's own filter, which for a null filter is
   * exactly what Lucene's 3-arg constructor does ({@code this(field, target, k, null)}).
   *
   * <p>Switch on, the query is made EXACT. Lucene 10.4's {@code AbstractKnnVectorQuery
   * .getLeafResults} has no exact branch for an unfiltered query at any {@code k}; with a filter it
   * takes {@code exactSearch} once the filter's cost is within the per-leaf top-k, so this raises
   * {@code k} to at least {@code reader.maxDoc()} (which bounds every leaf) and substitutes a
   * {@code MatchAllDocsQuery} when the caller supplied no filter. The extra reader acquisition is
   * paid only in that mode.
   */
  KnnFloatVectorQuery buildKnnQuery(String field, float[] queryVector, int limit, Query filter) {
    int queryK = resolveVectorQueryK(limit);
    if (!session.vectorExhaustiveSearch) {
      return new KnnFloatVectorQuery(field, queryVector, queryK, filter);
    }
    // This reads maxDoc from a searcher acquired HERE, and the search below acquires a second one.
    // An NRT reopen between the two would leave k sized for the older reader: still >= that
    // reader's maxDoc, so the query stays exact over everything the first reader saw, but a
    // document added in between could in principle fall outside k. Deliberately not held across
    // both — pinning one searcher for the whole call would mean bypassing the reopen-on-demand
    // seam that every other read goes through. Harmless for the switch's purpose: a deterministic
    // capture runs against a quiesced index, which is a precondition the fixture already states.
    int maxDoc;
    try {
      maxDoc = withSearcher(searcher -> searcher.getIndexReader().maxDoc());
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          LuceneRuntimeUtils.classifyIOException(e),
          "Failed to read maxDoc for exhaustive vector search",
          e);
    }
    return new KnnFloatVectorQuery(
        field,
        queryVector,
        Math.max(queryK, Math.max(1, maxDoc)),
        filter != null ? filter : new org.apache.lucene.search.MatchAllDocsQuery());
  }

  int resolveVectorQueryK(int limit) {
    int k = Math.max(1, limit);
    Integer configured = session.vectorEfSearchOverrideOrNull;
    if (configured != null && configured > k) {
      return configured;
    }
    return k;
  }

  public SearchResult search(
      Query query,
      int limit,
      Set<String> projectionFields,
      RuntimeSearchSort sort,
      String cursorToken) {
    return search(query, limit, projectionFields, sort, cursorToken, null);
  }

  /**
   * {@link #search} with an explicit Lucene {@link Sort}, overriding the one
   * {@code RuntimeSearchSort} would build (lane F PR 0b).
   *
   * <p>The chunk kNN leg needs this. {@code buildRuntimeSort(RELEVANCE, idField)} breaks a score
   * tie on {@code doc_id}, and on a CHUNK row that is {@code ChunkIds.newChunkDocId()} =
   * {@code "chunk:" + UUID.randomUUID()} — stable within one index, uncorrelated between two. The
   * bare chunk searches were fixed to sort on {@code parent_doc_id} then {@code chunk_index}, but
   * the dense leg reaches Lucene through THIS method and kept the doc-level sort, so it still
   * ordered its ties by the per-ingest UUID. Under {@code index.vector.exhaustive_search} that is
   * at its worst: the leg returns the whole corpus, so every exact-score tie it can have (chunks
   * with identical vectors — duplicated text — tie exactly) is in the result.
   *
   * <p>A cursor is REFUSED alongside an override: {@code decodeSearchAfterCursor} decodes the
   * cursor against the {@code RuntimeSearchSort}, so a cursor decoded under one sort and applied
   * under another would silently page through the wrong order. No caller needs both — the chunk
   * legs do not paginate — so this fails loudly rather than defining the combination.
   */
  SearchResult search(
      Query query,
      int limit,
      Set<String> projectionFields,
      RuntimeSearchSort sort,
      String cursorToken,
      Sort sortOverride) {
    if (query == null) {
      return new SearchResult(List.of(), 0, 0, null);
    }
    if (sortOverride != null && cursorToken != null && !cursorToken.isBlank()) {
      throw new IllegalArgumentException(
          "a search-after cursor cannot be combined with an explicit Sort override: the cursor is "
              + "encoded against the RuntimeSearchSort and would be decoded under a different order");
    }
    final int effectiveLimit = limit <= 0 ? 10 : limit;
    RuntimeSearchSort effectiveSort = sort == null ? RuntimeSearchSort.RELEVANCE : sort;

    long startTime = System.currentTimeMillis();
    try {
      return withSearcher(
          searcher -> {
            Sort luceneSort =
                sortOverride != null ? sortOverride : buildRuntimeSort(effectiveSort, idField);
            long requestedLong = (long) effectiveLimit + 1L;
            int requested = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, requestedLong));
            org.apache.lucene.search.ScoreDoc after =
                cursorToken == null || cursorToken.isBlank()
                    ? null
                    : decodeSearchAfterCursor(searcher, cursorToken, effectiveSort);

            TopDocs topDocs =
                after == null
                    ? searcher.search(query, requested, luceneSort, true)
                    : searcher.searchAfter(after, query, requested, luceneSort, true);

            // Get stored fields accessor once
            org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();

            Set<String> storedAllowlist =
                projectionFields == null || projectionFields.isEmpty()
                    ? null
                    : buildStoredAllowlist(projectionFields);
            boolean synthesizeChunkContent =
                projectionFields != null
                    && projectionFields.contains(SchemaFields.CHUNK_CONTENT);

            int take = Math.min(effectiveLimit, topDocs.scoreDocs.length);

            // Prefetch all documents for I/O batching (reduces latency for multiple hits)
            for (int i = 0; i < take; i++) {
              org.apache.lucene.search.ScoreDoc scoreDoc = topDocs.scoreDocs[i];
              storedFields.prefetch(scoreDoc.doc);
            }

            // Load stored fields for hits (avoid decoding huge stored content for interactive
            // search)
            List<SearchHit> hits = new ArrayList<>();
            Map<String, DocumentFieldOps.ChunkSlice> chunkSlices = new java.util.LinkedHashMap<>();
            for (int i = 0; i < take; i++) {
              org.apache.lucene.search.ScoreDoc scoreDoc = topDocs.scoreDocs[i];
              Map<String, String> fields =
                  SearchResultFormatter.extractFromStoredFields(
                      storedFields, scoreDoc.doc, false, storedAllowlist);
              // Project requested DocValues-only fields into the response map
              // (mime/language/etc).
              if (projectionFields != null && !projectionFields.isEmpty()) {
                projectDocValues(searcher, scoreDoc.doc, projectionFields, fields);
              }
              String docId = fields.get(idField);
              if (docId == null) {
                docId = fields.get(session.uidField);
              }
              if (docId == null) {
                docId = "doc-" + scoreDoc.doc;
              }
              if (synthesizeChunkContent) {
                DocumentFieldOps.ChunkSlice slice = DocumentFieldOps.chunkSliceFrom(fields);
                if (slice != null) {
                  chunkSlices.put(docId, slice);
                }
              }
              // Projection semantics: we may internally fetch id fields to construct
              // SearchHit.id, but we should not leak them in the returned field map unless
              // explicitly requested.
              if (projectionFields != null && !projectionFields.isEmpty()) {
                if (!projectionFields.contains(idField)) {
                  fields.remove(idField);
                }
                if (!projectionFields.contains(session.uidField)) {
                  fields.remove(session.uidField);
                }
              }
              hits.add(new SearchHit(docId, scoreDoc.score, fields));
            }

            if (synthesizeChunkContent && !chunkSlices.isEmpty()) {
              Map<String, String> chunkContent =
                  DocumentFieldOps.resolveChunkContents(
                      searcher, idField, chunkSlices, Map.of(), session.telemetryEvents);
              for (SearchHit hit : hits) {
                String content = chunkContent.get(hit.docId());
                if (content != null) {
                  hit.fields().put(SchemaFields.CHUNK_CONTENT, content);
                }
              }
            }
            if (synthesizeChunkContent) {
              for (SearchHit hit : hits) {
                if (!projectionFields.contains(SchemaFields.PARENT_DOC_ID)) {
                  hit.fields().remove(SchemaFields.PARENT_DOC_ID);
                }
                if (!projectionFields.contains(SchemaFields.CHUNK_START_CHAR)) {
                  hit.fields().remove(SchemaFields.CHUNK_START_CHAR);
                }
                if (!projectionFields.contains(SchemaFields.CHUNK_END_CHAR)) {
                  hit.fields().remove(SchemaFields.CHUNK_END_CHAR);
                }
                if (!projectionFields.contains(SchemaFields.CHUNK_PARENT_CONTENT_SHA256)) {
                  hit.fields().remove(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
                }
              }
            }

            String nextCursor = null;
            // An overridden Sort also suppresses the OUTBOUND cursor, for the same reason the
            // inbound one is refused: the encoder is keyed on effectiveSort, so a cursor minted
            // here would describe a position in an order these hits were not in.
            boolean hasMore = topDocs.scoreDocs.length > effectiveLimit;
            if (hasMore && !hits.isEmpty() && sortOverride == null) {
              // Use the last returned hit (not the lookahead doc) to construct the next cursor.
              org.apache.lucene.search.ScoreDoc last = topDocs.scoreDocs[take - 1];
              nextCursor =
                  SearchAfterCursorHelper.encode(effectiveSort, last, hits.getLast().docId());
            }

            long tookMs = System.currentTimeMillis() - startTime;
            // Lucene 10.x: TotalHits.value() is a method, not a field
            return new SearchResult(hits, topDocs.totalHits.value(), tookMs, nextCursor);
          });
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          LuceneRuntimeUtils.classifyIOException(e), "Search failed", e);
    }
  }

  @FunctionalInterface
  interface SearcherOperation<T> {
    T execute(IndexSearcher searcher) throws IOException;
  }
}
