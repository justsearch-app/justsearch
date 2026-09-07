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
    if (query == null) {
      return new SearchResult(List.of(), 0, 0, null);
    }
    final int effectiveLimit = limit <= 0 ? 10 : limit;
    RuntimeSearchSort effectiveSort = sort == null ? RuntimeSearchSort.RELEVANCE : sort;

    long startTime = System.currentTimeMillis();
    try {
      return withSearcher(
          searcher -> {
            Sort luceneSort = buildRuntimeSort(effectiveSort, idField);
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
            boolean hasMore = topDocs.scoreDocs.length > effectiveLimit;
            if (hasMore && !hits.isEmpty()) {
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
