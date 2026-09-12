/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal point-lookup collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates document field retrieval by ID: content, field values, existence checks,
 * and ID-based queries. All methods that need up-to-date data call {@code refreshBeforeFetch}
 * to ensure write-after-read visibility.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code
 * close()}. Access from the runtime must go through a volatile snapshot to ensure visibility
 * across threads.
 */
public final class DocumentFieldOps {
  private static final Logger log = LoggerFactory.getLogger(DocumentFieldOps.class);

  private final SearcherBridge bridge;
  private final String idField;
  private final RuntimeSession session;
  private final ReadPathOps readPathOps;

  /** Stored parent identity used to seed/rebuild the Worker document-identity store. */
  public record StoredDocumentIdentity(String docId, String docUid) {}

  /**
   * Character slice in a stored parent document that reconstructs one chunk's text, together with
   * the {@code chunk_parent_content_sha256} revision those offsets address (tempdoc 931 §E item 5).
   * The revision is what lets {@link ChunkReadRevisionGuard} tell an in-sync parent from one that
   * has been rewritten but whose chunks have not been regenerated yet.
   */
  record ChunkSlice(
      String parentDocId, int startChar, int endChar, String parentContentRevision) {}

  @FunctionalInterface
  interface ParentContentLoader {
    ChunkReadRevisionGuard.ParentRevision load(String parentDocId) throws IOException;
  }

  DocumentFieldOps(
      RuntimeSession session,
      SearcherBridge bridge,
      String idField,
      ReadPathOps readPathOps) {
    this.session = session;
    this.bridge = bridge;
    this.idField = idField;
    this.readPathOps = readPathOps;
  }

  /**
   * Forces a refresh when a commit happened after the last visible refresh.
   *
   * <p>This avoids surprising read-after-write behavior for "fetch by id" style APIs
   * that expect committed documents to be immediately visible.
   */
  private void maybeRefreshBlockingIfCommittedSinceRefresh() {
    LifecycleSnapshot snap = session.snapshot;
    org.apache.lucene.search.SearcherManager mgr =
        snap != null ? snap.searcherManager() : null;
    if (mgr == null) return;
    long commit = session.lastCommitNanos.get();
    long refreshed = session.lastRefreshNanos.get();
    if (commit <= 0L || commit <= refreshed) return;
    try {
      mgr.maybeRefreshBlocking();
    } catch (IOException e) {
      log.debug("maybeRefreshBlockingIfCommittedSinceRefresh failed: {}", e.getMessage());
    }
  }

  /**
   * Fetches the content of a document by ID.
   *
   * <p>Calls {@code refreshBeforeFetch} to ensure write-after-read visibility.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public String getDocumentContent(String docId) {
    if (docId == null) return null;
    return getDocumentContentBatch(List.of(docId)).get(docId);
  }

  /**
   * Fetches content for a batch of parent or chunk document IDs using a single shared searcher.
   *
   * <p>Parent documents return their stored {@code content}. Chunk documents reconstruct the exact
   * text from {@code parent_doc_id} plus the stored start/end offsets. Distinct parent content is
   * read once per batch, including when several requested chunks share the same parent.
   *
   * <p>Acquires ONE searcher, resolves all doc IDs, then iterates in ascending Lucene internal
   * doc-ID order for LZ4 block cache locality. Doc IDs not found in the index, missing parents, and
   * invalid slices are silently omitted from the result map (stale IDs from the pending query).
   *
   * <p>Calls {@code refreshBeforeFetch} to ensure write-after-read visibility.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public Map<String, String> getDocumentContentBatch(List<String> docIds) {
    if (docIds == null || docIds.isEmpty()) {
      return Map.of();
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        // Phase 1: Resolve all doc IDs to Lucene internal doc numbers.
        // Store as (luceneDocNum -> externalDocId) entries for sorted iteration.
        List<Map.Entry<Integer, String>> resolved = new ArrayList<>(docIds.size());
        for (String docId : docIds) {
          Query query = new TermQuery(new Term(idField, docId));
          var topDocs = searcher.search(query, 1);
          if (topDocs.scoreDocs.length > 0) {
            resolved.add(Map.entry(topDocs.scoreDocs[0].doc, docId));
          }
        }

        if (resolved.isEmpty()) {
          return Map.of();
        }

        // Phase 2: Sort by ascending Lucene doc number for sequential stored-field
        // access (LZ4 block cache locality).
        resolved.sort(Map.Entry.comparingByKey());

        // Phase 3: Extract parent content or chunk geometry in sorted order.
        Set<String> storedAllowlist =
            Set.of(
                SchemaFields.CONTENT,
                SchemaFields.CONTENT_SHA256,
                SchemaFields.PARENT_DOC_ID,
                SchemaFields.CHUNK_START_CHAR,
                SchemaFields.CHUNK_END_CHAR,
                SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
        org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
        Map<String, String> result = new LinkedHashMap<>(resolved.size());
        Map<String, ChunkReadRevisionGuard.ParentRevision> knownParents = new LinkedHashMap<>();
        Map<String, ChunkSlice> chunkSlices = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : resolved) {
          Map<String, String> fields =
              SearchResultFormatter.extractFromStoredFields(
                  storedFields, entry.getKey(), true, storedAllowlist);
          String content = fields.get(SchemaFields.CONTENT);
          if (content != null) {
            result.put(entry.getValue(), content);
            knownParents.put(entry.getValue(), parentRevisionFrom(fields));
            continue;
          }
          ChunkSlice slice = chunkSliceFrom(fields);
          if (slice != null) {
            chunkSlices.put(entry.getValue(), slice);
          }
        }
        result.putAll(
            resolveChunkContents(
                searcher, idField, chunkSlices, knownParents, session.telemetryEvents));
        return Collections.unmodifiableMap(result);
      });
    } catch (IOException e) {
      log.debug("Failed to batch-fetch content for {} docs: {}", docIds.size(), e.getMessage());
      return Map.of();
    }
  }

  /**
   * Fetches multiple field values for a batch of document IDs using a single shared searcher.
   *
   * <p>Same 3-phase pattern as {@link #getDocumentContentBatch}: acquires ONE searcher,
   * resolves all doc IDs, then reads fields in ascending doc-number order. For DocValues-backed
   * fields (status fields), reads are O(1) per field via {@code projectDocValues}. Stored
   * fields go through {@code extractFromStoredFields}.
   *
   * <p>Reduces 300+ individual {@code getDocumentField} calls to 1 searcher acquisition +
   * N TermQuery resolutions + batch DocValues/stored reads. ~8-10s savings per enrichment
   * pipeline (tempdoc 334 Phase 10).
   */
  public Map<String, Map<String, String>> getDocumentFieldsBatch(
      List<String> docIds, Set<String> fieldNames) {
    if (docIds == null || docIds.isEmpty() || fieldNames == null || fieldNames.isEmpty()) {
      return Map.of();
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        // Phase 1: Resolve all doc IDs to Lucene internal doc numbers.
        List<Map.Entry<Integer, String>> resolved = new ArrayList<>(docIds.size());
        for (String docId : docIds) {
          var topDocs = searcher.search(new TermQuery(new Term(idField, docId)), 1);
          if (topDocs.scoreDocs.length > 0) {
            resolved.add(Map.entry(topDocs.scoreDocs[0].doc, docId));
          }
        }
        if (resolved.isEmpty()) {
          return Map.of();
        }

        // Phase 2: Sort by ascending Lucene doc number for cache locality.
        resolved.sort(Map.Entry.comparingByKey());

        // Phase 3: Separate fields into DocValues vs stored, read in doc order.
        Set<String> dvFieldIds = new java.util.HashSet<>();
        Set<String> storedFieldNames = new java.util.HashSet<>();
        for (String fn : fieldNames) {
          FieldMapper.FieldDef def = session.fieldMapper.fieldDef(fn);
          if (def != null && def.docValues) {
            dvFieldIds.add(def.id);
          } else {
            storedFieldNames.add(fn);
          }
        }

        boolean hasStored = !storedFieldNames.isEmpty();
        org.apache.lucene.index.StoredFields storedFields =
            hasStored ? searcher.storedFields() : null;

        Map<String, Map<String, String>> result = new LinkedHashMap<>(resolved.size());
        for (Map.Entry<Integer, String> entry : resolved) {
          int docNum = entry.getKey();
          String docId = entry.getValue();
          Map<String, String> values = new HashMap<>(fieldNames.size());

          if (!dvFieldIds.isEmpty()) {
            readPathOps.projectDocValues(searcher, docNum, dvFieldIds, values);
          }
          if (hasStored) {
            boolean includeContent = storedFieldNames.contains(SchemaFields.CONTENT);
            Map<String, String> stored =
                SearchResultFormatter.extractFromStoredFields(
                    storedFields, docNum, includeContent, storedFieldNames);
            values.putAll(stored);
          }

          result.put(docId, values);
        }
        return Collections.unmodifiableMap(result);
      });
    } catch (IOException e) {
      log.debug(
          "Failed to batch-fetch fields {} for {} docs: {}",
          fieldNames, docIds.size(), e.getMessage());
      return Map.of();
    }
  }

  static ChunkSlice chunkSliceFrom(Map<String, String> fields) {
    if (fields == null) return null;
    String parentDocId = fields.get(SchemaFields.PARENT_DOC_ID);
    if (parentDocId == null || parentDocId.isBlank()) return null;
    try {
      int start = Integer.parseInt(fields.getOrDefault(SchemaFields.CHUNK_START_CHAR, "-1"));
      int end = Integer.parseInt(fields.getOrDefault(SchemaFields.CHUNK_END_CHAR, "-1"));
      String revision = fields.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
      return start >= 0 && end >= start ? new ChunkSlice(parentDocId, start, end, revision) : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  /** The revision identity of a parent document, read off the same stored-field map as its text. */
  static ChunkReadRevisionGuard.ParentRevision parentRevisionFrom(Map<String, String> fields) {
    if (fields == null) return null;
    return new ChunkReadRevisionGuard.ParentRevision(
        fields.get(SchemaFields.CONTENT), fields.get(SchemaFields.CONTENT_SHA256));
  }

  /** Resolves each distinct parent once, then applies all requested chunk slices. */
  static Map<String, String> resolveChunkContents(
      org.apache.lucene.search.IndexSearcher searcher,
      String idField,
      Map<String, ChunkSlice> chunks,
      Map<String, ChunkReadRevisionGuard.ParentRevision> knownParents,
      LuceneRuntimeTypes.TelemetryEvents telemetry)
      throws IOException {
    org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
    Set<String> parentFields = Set.of(SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256);
    return resolveChunkContents(
        chunks,
        knownParents,
        parentId -> {
          var parentHits = searcher.search(new TermQuery(new Term(idField, parentId)), 1);
          if (parentHits.scoreDocs.length == 0) return null;
          Map<String, String> fields =
              SearchResultFormatter.extractFromStoredFields(
                  storedFields, parentHits.scoreDocs[0].doc, true, parentFields);
          return parentRevisionFrom(fields);
        },
        telemetry);
  }

  /**
   * Resolves slices through an injectable loader so parent de-duplication is directly testable.
   *
   * <p>A chunk whose stored {@code chunk_parent_content_sha256} is not the revision the parent is
   * at right now is OMITTED rather than sliced out of the newer text — see
   * {@link ChunkReadRevisionGuard}. Every caller already handles a chunk missing from this map
   * (that is what a deleted parent looks like), so "not yet consistent" travels as an absence.
   */
  static Map<String, String> resolveChunkContents(
      Map<String, ChunkSlice> chunks,
      Map<String, ChunkReadRevisionGuard.ParentRevision> knownParents,
      ParentContentLoader parentLoader,
      LuceneRuntimeTypes.TelemetryEvents telemetry)
      throws IOException {
    if (chunks == null || chunks.isEmpty()) return Map.of();

    Map<String, ChunkReadRevisionGuard.ParentRevision> parents = new HashMap<>();
    if (knownParents != null) parents.putAll(knownParents);
    Set<String> attemptedParents = new LinkedHashSet<>(parents.keySet());

    for (ChunkSlice slice : chunks.values()) {
      String parentId = slice.parentDocId();
      if (!attemptedParents.add(parentId)) continue;
      ChunkReadRevisionGuard.ParentRevision parent = parentLoader.load(parentId);
      if (parent != null) parents.put(parentId, parent);
    }

    ChunkReadRevisionGuard guard = new ChunkReadRevisionGuard();
    Map<String, String> resolved = new LinkedHashMap<>();
    for (var entry : chunks.entrySet()) {
      ChunkSlice slice = entry.getValue();
      guard
          .slice(entry.getKey(), slice, parents.get(slice.parentDocId()))
          .ifPresent(text -> resolved.put(entry.getKey(), text));
    }
    if (telemetry != null && guard.mismatchCount() > 0) {
      telemetry.onChunkRevisionMismatch(guard.mismatchCount());
    }
    return resolved;
  }

  /**
   * Fetches a specific field value from a document by ID.
   *
   * <p>Prefers DocValues for DocValues-backed fields, falls back to stored fields.
   * Calls {@code refreshBeforeFetch} to ensure write-after-read visibility.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public String getDocumentField(String docId, String fieldName) {
    if (docId == null || fieldName == null) {
      return null;
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(idField, docId));
        var topDocs = searcher.search(query, 1);

        if (topDocs.scoreDocs.length == 0) {
          return null;
        }

        int docNum = topDocs.scoreDocs[0].doc;

        // Prefer DocValues for DocValues-backed fields (e.g., mime/language/size_bytes).
        FieldMapper.FieldDef def = session.fieldMapper.fieldDef(fieldName);
        if (def != null && def.docValues) {
          Map<String, String> projected = new HashMap<>();
          readPathOps.projectDocValues(searcher, docNum, Set.of(def.id), projected);
          String value = projected.get(def.id);
          if (value != null) {
            return value;
          }
        }

        // Fallback to stored fields.
        boolean includeContent = SchemaFields.CONTENT.equals(fieldName);
        Set<String> allow = Set.of(fieldName);
        Map<String, String> stored =
            SearchResultFormatter.extractFromStoredFields(
                searcher.storedFields(), docNum, includeContent, allow);
        return stored.get(fieldName);
      });
    } catch (IOException e) {
      log.debug("Failed to get field {} for {}: {}", fieldName, docId, e.getMessage());
      return null;
    }
  }

  /**
   * Fetches individual values of a multi-valued field from a document by ID.
   *
   * <p>Returns each value separately (no comma-join). Falls back to {@link #getDocumentField}
   * wrapped in a singleton list for non-multi-valued fields.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public List<String> getDocumentFieldValues(String docId, String fieldName) {
    if (docId == null || fieldName == null) {
      return List.of();
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(idField, docId));
        var topDocs = searcher.search(query, 1);

        if (topDocs.scoreDocs.length == 0) {
          return List.of();
        }

        int docNum = topDocs.scoreDocs[0].doc;

        FieldMapper.FieldDef def = session.fieldMapper.fieldDef(fieldName);
        if (def != null && def.docValues && def.multiValued) {
          List<String> values = readPathOps.projectMultiValuedDocValues(searcher, docNum, def.id);
          if (!values.isEmpty()) {
            return values;
          }
        }

        // Fallback: single-valued path (inline to avoid double searcher acquisition)
        FieldMapper.FieldDef singleDef = session.fieldMapper.fieldDef(fieldName);
        if (singleDef != null && singleDef.docValues) {
          Map<String, String> projected = new HashMap<>();
          readPathOps.projectDocValues(searcher, docNum, Set.of(singleDef.id), projected);
          String value = projected.get(singleDef.id);
          if (value != null) {
            return List.of(value);
          }
        }
        boolean includeContent = SchemaFields.CONTENT.equals(fieldName);
        Set<String> allow = Set.of(fieldName);
        Map<String, String> stored =
            SearchResultFormatter.extractFromStoredFields(
                searcher.storedFields(), docNum, includeContent, allow);
        String sv = stored.get(fieldName);
        return sv != null ? List.of(sv) : List.of();
      });
    } catch (IOException e) {
      log.debug("Failed to get field values {} for {}: {}", fieldName, docId, e.getMessage());
      return List.of();
    }
  }

  /**
   * Checks if a document exists and has the same lastModified timestamp.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public boolean isUnmodified(String docId, long currentLastModified) {
    // No blocking refresh here — false negatives (re-indexing unmodified docs) are harmless.
    // The CRTRT thread refreshes every 500ms, bounding staleness. Blocking refresh on every
    // point lookup causes "refresh storms" (the same anti-pattern Elasticsearch abandoned in 5.0).
    try {
      return bridge.withSearcher(
          searcher -> {
            org.apache.lucene.search.TopDocs docs =
                searcher.search(new TermQuery(new Term(idField, docId)), 1);
            if (docs.scoreDocs.length == 0) {
              log.trace("isUnmodified: not indexed yet: {}", docId);
              return false;
            }

            int docNum = docs.scoreDocs[0].doc;
            long modifiedAt =
                readPathOps.readLongDocValueOrStoredLong(searcher, docNum, SchemaFields.MODIFIED_AT);
            if (modifiedAt < 0) {
              log.trace("isUnmodified: no modified_at field (legacy doc): {}", docId);
              return false;
            }
            boolean unchanged = modifiedAt == currentLastModified;
            if (unchanged) {
              log.trace(
                  "isUnmodified: unchanged (stored={}, current={}): {}",
                  modifiedAt,
                  currentLastModified,
                  docId);
            }
            return unchanged;
          });
    } catch (IOException e) {
      log.debug("Failed to check if modified: {}", docId, e);
      return false;
    }
  }

  /**
   * Queries document IDs matching a specific field value.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public List<String> queryDocIdsByField(String field, String value, int limit) {
    return queryDocIdsByField(field, value, limit, false);
  }

  /** Control reads must distinguish an empty selection from an unreadable index. */
  public List<String> queryDocIdsByFieldOrThrow(String field, String value, int limit)
      throws IOException {
    return queryDocIdsByFieldOrThrow(field, value, limit, false);
  }

  /**
   * Queries document IDs matching a specific field value, omitting chunk documents.
   *
   * <p>A chunk carries {@code splade_status=PENDING} from creation whether or not chunk SPLADE is
   * enabled (ChunkDocumentWriter), so with the flag off that status is not a work signal: selecting
   * on it hands the backfill batch slots it can only rewrite, never advance. Chunks still reach the
   * combined pass through their own {@code chunk_embedding_status} selection.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public List<String> queryNonChunkDocIdsByField(String field, String value, int limit) {
    return queryDocIdsByField(field, value, limit, true);
  }

  private List<String> queryDocIdsByField(
      String field, String value, int limit, boolean excludeChunks) {
    try {
      return queryDocIdsByFieldOrThrow(field, value, limit, excludeChunks);
    } catch (IOException e) {
      log.debug("Failed to query {}={}: {}", field, value, e.getMessage());
      return List.of();
    }
  }

  private List<String> queryDocIdsByFieldOrThrow(
      String field, String value, int limit, boolean excludeChunks) throws IOException {
    if (field == null || value == null || limit <= 0) {
      return List.of();
    }
      return bridge.withSearcher(searcher -> {
        Query valueQuery = new TermQuery(new Term(field, value));
        Query query =
            excludeChunks
                ? new BooleanQuery.Builder()
                    .add(valueQuery, BooleanClause.Occur.MUST)
                    .add(
                        new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                        BooleanClause.Occur.MUST_NOT)
                    .build()
                : valueQuery;
        var topDocs = searcher.search(query, limit);

        List<String> docIds = new ArrayList<>(topDocs.scoreDocs.length);
        org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
        Set<String> storedAllowlist = Set.of(idField);
        for (var scoreDoc : topDocs.scoreDocs) {
          Map<String, String> fields =
              SearchResultFormatter.extractFromStoredFields(
                  storedFields, scoreDoc.doc, false, storedAllowlist);
          String docId = fields.get(idField);
          if (docId != null && !docId.isBlank()) {
            docIds.add(docId);
          }
        }
        return docIds;
      });
  }

  /** Accounting for one identity recovery scan. */
  public record ParentIdentityScanSummary(
      long parentsSeen, long parentsEmitted, long parentsSkipped) {}

  /**
   * Streams every live parent document's stored identity from one searcher snapshot.
   *
   * <p>This deliberately has no corpus-size cap: omitting a tail of the active index during an
   * identity-store import would cause those documents to be re-minted on their next write. The
   * result is handed to {@code batchConsumer} in slices of {@code batchSize} instead of returned as
   * one list, so the caller's peak heap is a batch rather than the corpus (tempdoc 931 §C.2).
   *
   * <p>A live parent whose {@code doc_id}/{@code doc_uid} docvalues are missing or blank is counted
   * in {@code parentsSkipped} and omitted. That shape predates the identity store or comes from a
   * partially-written legacy index; it mints a fresh identity at its next admission, which is a
   * recoverable outcome, whereas failing the scan takes the whole Worker down. Genuine I/O failure
   * is still surfaced — that is not a legacy shape, it is an unreadable index.
   */
  public ParentIdentityScanSummary scanParentDocumentIdentities(
      int batchSize, Consumer<List<StoredDocumentIdentity>> batchConsumer) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
    }
    java.util.Objects.requireNonNull(batchConsumer, "batchConsumer");
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(
          searcher -> {
            long seen = 0;
            long emitted = 0;
            long skipped = 0;
            List<StoredDocumentIdentity> batch = new ArrayList<>(batchSize);
            for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
              SortedDocValues docIds = DocValues.getSorted(leaf.reader(), SchemaFields.DOC_ID);
              SortedDocValues docUids = DocValues.getSorted(leaf.reader(), SchemaFields.DOC_UID);
              SortedDocValues isChunks = DocValues.getSorted(leaf.reader(), SchemaFields.IS_CHUNK);
              var liveDocs = leaf.reader().getLiveDocs();
              for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                if (liveDocs != null && !liveDocs.get(doc)) {
                  continue;
                }
                if (isChunks.advanceExact(doc)
                    && "true".equals(isChunks.lookupOrd(isChunks.ordValue()).utf8ToString())) {
                  continue;
                }
                seen++;
                if (!docIds.advanceExact(doc) || !docUids.advanceExact(doc)) {
                  skipped++;
                  continue;
                }
                String docId = docIds.lookupOrd(docIds.ordValue()).utf8ToString();
                String docUid = docUids.lookupOrd(docUids.ordValue()).utf8ToString();
                if (docId.isBlank() || docUid.isBlank()) {
                  skipped++;
                  continue;
                }
                batch.add(new StoredDocumentIdentity(docId, docUid));
                emitted++;
                if (batch.size() == batchSize) {
                  batchConsumer.accept(Collections.unmodifiableList(batch));
                  batch = new ArrayList<>(batchSize);
                }
              }
            }
            if (!batch.isEmpty()) {
              batchConsumer.accept(Collections.unmodifiableList(batch));
            }
            return new ParentIdentityScanSummary(seen, emitted, skipped);
          });
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.DISK_IO,
          "Failed to read parent document identities",
          e);
    }
  }
}
