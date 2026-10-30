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
import org.apache.lucene.document.DoublePoint;
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

  /** Recheck IDs retained by a backfill cache before starting another batch. */
  public Set<String> excludedProcessingIds(List<String> docIds) {
    var allows = session.sourceProcessingPolicy;
    if (allows == null || docIds.isEmpty()
        || io.justsearch.configuration.excludes.ExclusionPatterns.current().isEmpty()) return Set.of();
    try {
      var fields = getDocumentFieldsBatch(docIds,
          Set.of(SchemaFields.PATH, SchemaFields.PARENT_DOC_ID, SchemaFields.PROJECTION_SOURCE_ID));
      Map<String, String> parents = new HashMap<>();
      Set<String> excluded = new LinkedHashSet<>();
      for (String id : docIds) {
        if (!fields.containsKey(id)) {
          excluded.add(id);
          continue;
        }
        String source = processingSource(id, fields.get(id), parents);
        if (!allows.test(source)) excluded.add(id);
      }
      return excluded;
    } catch (IOException unreadable) {
      // Unknown source scope cannot authorise more processing; retain its pending state.
      return Set.copyOf(docIds);
    }
  }

  private String processingSource(String id, Map<String, String> fields,
      Map<String, String> parents) throws IOException {
    String projectionSource = fields.get(SchemaFields.PROJECTION_SOURCE_ID);
    if (projectionSource != null && !projectionSource.isBlank()) return null;
    String source = fields.get(SchemaFields.PATH);
    if (source != null && !source.isBlank()) return source;
    String parent = fields.get(SchemaFields.PARENT_DOC_ID);
    if (parent != null && !parent.isBlank()) {
      if (!parents.containsKey(parent)) {
        projectionSource = getDocumentFieldOrThrow(parent, SchemaFields.PROJECTION_SOURCE_ID);
        source = getDocumentFieldOrThrow(parent, SchemaFields.PATH);
        parents.put(parent, projectionSource != null && !projectionSource.isBlank()
            ? null : source == null || source.isBlank() ? parent : source);
      }
      return parents.get(parent);
    }
    return id;
  }

  private static boolean processingSelection(String field, String value) {
    return ("PENDING".equals(value) || SchemaFields.NER_STATUS_COMPLETED.equals(value))
        && Set.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_STATUS,
            SchemaFields.SPLADE_STATUS, SchemaFields.NER_STATUS, SchemaFields.VDU_STATUS).contains(field);
  }

  /** Stored parent identity used to seed/rebuild the Worker document-identity store. */
  public record StoredDocumentIdentity(String docId, String docUid) {}

  /**
   * Character slice in a stored parent document that reconstructs one chunk's text, together with
   * the {@code chunk_parent_content_sha256} revision those offsets address (tempdoc 931 Â§E item 5).
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
   * <p>Does NOT call {@code ensureStarted()} â€” caller (facade) is responsible for that guard.
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
   * <p>Does NOT call {@code ensureStarted()} â€” caller (facade) is responsible for that guard.
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

  /** One immutable reader's text, identity and stage state, including a chunk's parent. */
  public record EnrichmentInput(
      String content, Map<String, String> fields,
      String parentId, Map<String, String> parentFields) {}

  static final Set<String> ENRICHMENT_FIELDS = Set.of(
      SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256, SchemaFields.SOURCE_SHA256,
      SchemaFields.DOC_UID, SchemaFields.IS_CHUNK, SchemaFields.PARENT_DOC_ID,
      SchemaFields.CHUNK_START_CHAR, SchemaFields.CHUNK_END_CHAR,
      SchemaFields.CHUNK_PARENT_CONTENT_SHA256,
      SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_RETRY_COUNT,
      SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,
      SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT,
      SchemaFields.NER_STATUS, SchemaFields.NER_RETRY_COUNT,
      SchemaFields.VDU_STATUS, SchemaFields.VDU_RETRY_COUNT, SchemaFields.COLLECTION);

  static final Set<String> PARENT_REVISION_FIELDS = Set.of(
      SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256, SchemaFields.SOURCE_SHA256,
      SchemaFields.DOC_UID, SchemaFields.PATH, SchemaFields.PROJECTION_SOURCE_ID);

  /** Reads text and comparison proof together; an I/O failure never becomes a blank-content retry. */
  public Map<String, EnrichmentInput> getEnrichmentInputs(List<String> docIds) throws IOException {
    return getEnrichmentInputs(docIds, Long.MAX_VALUE);
  }

  /** Retains at most the parent text budget; admits one oversized first parent whole. */
  public Map<String, EnrichmentInput> getEnrichmentInputs(List<String> docIds, long maxContentChars)
      throws IOException {
    if (docIds == null || docIds.isEmpty()) return Map.of();
    maybeRefreshBlockingIfCommittedSinceRefresh();
    return bridge.withSearcher(searcher -> {
      Map<String, EnrichmentInput> inputs = new LinkedHashMap<>();
      Map<String, Map<String, String>> parents = new HashMap<>();
      var storedFields = searcher.storedFields();
      var revisionGuard = new ChunkReadRevisionGuard();
      long retainedChars = 0;
      for (String docId : docIds) {
        Map<String, String> fields = enrichmentFields(searcher, storedFields, docId, ENRICHMENT_FIELDS);
        if (fields == null) continue;
        String parentId = fields.get(SchemaFields.PARENT_DOC_ID);
        Map<String, String> parent = Map.of();
        String content = fields.get(SchemaFields.CONTENT);
        if ("true".equals(fields.get(SchemaFields.IS_CHUNK)) && (parentId == null || parentId.isBlank())) {
          content = null;
        }
        if (parentId != null && !parentId.isBlank()) {
          if (!parents.containsKey(parentId)) {
            parents.put(parentId, enrichmentFields(searcher, storedFields, parentId, PARENT_REVISION_FIELDS));
          }
          parent = parents.get(parentId);
          ChunkSlice slice = chunkSliceFrom(fields);
          content = slice == null || parent == null ? null
              : revisionGuard.slice(docId, slice, parentRevisionFrom(parent)).orElse(null);
        }
        long chars = content == null ? 0 : content.length();
        if (retainedChars > 0 && retainedChars + chars > maxContentChars) break;
        inputs.put(docId, new EnrichmentInput(content, fields, parentId, parent));
        retainedChars += chars;
        if (retainedChars >= maxContentChars) break;
      }
      return Collections.unmodifiableMap(inputs);
    });
  }

  Map<String, String> enrichmentFields(
      org.apache.lucene.search.IndexSearcher searcher, org.apache.lucene.index.StoredFields storedFields,
      String docId, Set<String> names) throws IOException {
    var hits = searcher.search(new TermQuery(new Term(idField, docId)), 1);
    if (hits.scoreDocs.length == 0) return null;
    int docNum = hits.scoreDocs[0].doc;
    Map<String, String> fields = new HashMap<>(SearchResultFormatter.extractFromStoredFields(
        storedFields, docNum, true, names));
    // The projector already skips stored values and fields without DocValues.
    readPathOps.projectDocValues(searcher, docNum, names, fields);
    return Collections.unmodifiableMap(fields);
  }

  static ChunkSlice chunkSliceFrom(Map<String, String> fields) {
    if (fields == null) return null;
    String parentDocId = fields.get(SchemaFields.PARENT_DOC_ID);
    if (parentDocId == null || parentDocId.isBlank()) return null;
    try {
      int start = Integer.parseInt(fields.getOrDefault(SchemaFields.CHUNK_START_CHAR, "-1"));
      int end = Integer.parseInt(fields.getOrDefault(SchemaFields.CHUNK_END_CHAR, "-1"));
      String revision = fields.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
      return start >= 0 && end > start ? new ChunkSlice(parentDocId, start, end, revision) : null;
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
   * at right now is OMITTED rather than sliced out of the newer text â€” see
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
   * <p>Does NOT call {@code ensureStarted()} â€” caller (facade) is responsible for that guard.
   */
  public String getDocumentField(String docId, String fieldName) {
    try {
      return getDocumentFieldOrThrow(docId, fieldName);
    } catch (IOException e) {
      log.debug("Failed to get field {} for {}: {}", fieldName, docId, e.getMessage());
      return null;
    }
  }

  /** Reads a control field without converting I/O failure into an absent/default value. */
  public String getDocumentFieldOrThrow(String docId, String fieldName) throws IOException {
    if (docId == null || fieldName == null) {
      return null;
    }
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
  }

  /**
   * Fetches individual values of a multi-valued field from a document by ID.
   *
   * <p>Returns each value separately (no comma-join). Falls back to {@link #getDocumentField}
   * wrapped in a singleton list for non-multi-valued fields.
   *
   * <p>Does NOT call {@code ensureStarted()} â€” caller (facade) is responsible for that guard.
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
   * <p>Does NOT call {@code ensureStarted()} â€” caller (facade) is responsible for that guard.
   */
  public boolean isUnmodified(String docId, long currentLastModified) {
    return isUnmodified(docId, currentLastModified, null);
  }

  /** Certifies the source stamp and complete chunks; missing stored size requires repair. */
  public boolean isUnmodified(String docId, long currentLastModified, long currentSizeBytes) {
    return isUnmodified(docId, currentLastModified, Long.valueOf(currentSizeBytes));
  }

  private boolean isUnmodified(String docId, long currentLastModified, Long currentSizeBytes) {
    return isUnmodified(docId, currentLastModified, currentSizeBytes, null);
  }

  /** An explicitly admitted source must also match its stored byte identity, when available. */
  public boolean isUnmodified(String docId, long currentLastModified, long currentSizeBytes,
      String sourceSha256) {
    return isUnmodified(docId, currentLastModified, Long.valueOf(currentSizeBytes), sourceSha256);
  }

  private boolean isUnmodified(String docId, long currentLastModified, Long currentSizeBytes,
      String sourceSha256) {
    // A complete stale reader is a false positive after committed deletion/replacement. Commit
    // prepares the reader; retry if another commit overtakes this admission's reader lease.
    try {
      while (true) {
        var admission = session.commitOps.withAdmissionSearcher(bridge,
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
              if (modifiedAt != currentLastModified) return false;
              if (currentSizeBytes != null) {
                long sizeBytes = readPathOps.readLongDocValueOrStoredLong(
                    searcher, docNum, SchemaFields.SIZE_BYTES);
                if (sizeBytes < 0 || sizeBytes != currentSizeBytes) return false;
              }
              if (sourceSha256 != null) {
                String storedHash = searcher.storedFields().document(docNum).get(SchemaFields.SOURCE_SHA256);
                if (storedHash != null && !sourceSha256.equals(storedHash)) return false;
              }
              var assessment = session.chunkCompleteness.assess(searcher, docNum, docId);
              boolean unchanged = assessment.complete();
              if (unchanged && assessment.backfill() != null) {
                unchanged = session.indexingCoordinator.backfillChunkDeclaration(
                    searcher, docNum, docId, assessment.backfill());
                // Only the one-time migration refreshes here. Subsequent admission reads metadata.
                if (unchanged) {
                  session.commitOps.maybeRefreshBlocking();
                  session.commitOps.refreshAdmissionAfterBackfill();
                }
              }
              if (unchanged) {
                log.trace(
                    "isUnmodified: unchanged (stored={}, current={}): {}",
                    modifiedAt,
                    currentLastModified,
                    docId);
              }
              return unchanged;
            });
        if (session.commitOps.admissionReaderStillCurrent(admission.epoch())) return admission.value();
      }
    } catch (IOException e) {
      log.debug("Failed to check if modified: {}", docId, e);
      return false;
    }
  }

  /**
   * Queries document IDs matching a specific field value.
   *
   * <p>Does NOT call {@code ensureStarted()} â€” caller (facade) is responsible for that guard.
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
   * <p>Does NOT call {@code ensureStarted()} â€” caller (facade) is responsible for that guard.
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
        FieldMapper.FieldDef def = session.fieldMapper.fieldDef(field);
        Query valueQuery;
        if (def != null && "double".equals(def.type)) {
          Double numeric = FieldMapper.asDouble(value);
          if (numeric == null) return List.of();
          valueQuery = DoublePoint.newExactQuery(field, numeric);
        } else {
          valueQuery = new TermQuery(new Term(field, value));
        }
        Query query =
            excludeChunks
                ? new BooleanQuery.Builder()
                    .add(valueQuery, BooleanClause.Occur.MUST)
                    .add(
                        new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                        BooleanClause.Occur.MUST_NOT)
                    .build()
                : valueQuery;
        var allows = session.sourceProcessingPolicy;
        if (allows != null && processingSelection(field, value)
            && !io.justsearch.configuration.excludes.ExclusionPatterns.current().isEmpty()) {
          // Stream eligible IDs so excluded heads neither starve later files nor require
          // repeated full-index searches. Status selections have no relevance ordering.
          List<String> eligible = new ArrayList<>();
          Map<String, String> parents = new HashMap<>();
          Set<String> storedAllowlist = Set.of(idField, SchemaFields.PATH,
              SchemaFields.PARENT_DOC_ID, SchemaFields.PROJECTION_SOURCE_ID);
          searcher.search(query, new org.apache.lucene.search.SimpleCollector() {
            private org.apache.lucene.index.StoredFields storedFields;

            @Override
            protected void doSetNextReader(LeafReaderContext leaf) throws IOException {
              if (eligible.size() >= limit) throw new org.apache.lucene.search.CollectionTerminatedException();
              storedFields = leaf.reader().storedFields();
            }

            @Override
            public void collect(int doc) throws IOException {
              Map<String, String> fields = SearchResultFormatter.extractFromStoredFields(
                  storedFields, doc, false, storedAllowlist);
              String id = fields.get(idField);
              if (id != null && !id.isBlank() && allows.test(processingSource(id, fields, parents))) {
                eligible.add(id);
                if (eligible.size() >= limit) throw new org.apache.lucene.search.CollectionTerminatedException();
              }
            }

            @Override
            public org.apache.lucene.search.ScoreMode scoreMode() {
              return org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES;
            }
          });
          return eligible;
        }
        var topDocs = searcher.search(query, limit);
        List<String> docIds = new ArrayList<>(topDocs.scoreDocs.length);
        org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
        Set<String> storedAllowlist = Set.of(idField);
        for (var scoreDoc : topDocs.scoreDocs) {
          Map<String, String> fields = SearchResultFormatter.extractFromStoredFields(
              storedFields, scoreDoc.doc, false, storedAllowlist);
          String docId = fields.get(idField);
          if (docId != null && !docId.isBlank()) docIds.add(docId);
        }
        return docIds;
      });
  }

  /** An indexed row's own backing source, including a child left by a partial deletion. */
  public record IndexedSource(String docId, String path, String docUid) {}

  /** Streams the existing ownership evidence; no queue history or separate source list is needed. */
  public long scanIndexedSources(int batchSize, Consumer<List<IndexedSource>> consumer) {
    if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
    java.util.Objects.requireNonNull(consumer, "consumer");
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        long skipped = 0;
        var stored = searcher.storedFields();
        var batch = new ArrayList<IndexedSource>(batchSize);
        for (var leaf : searcher.getIndexReader().leaves()) {
          var uids = DocValues.getSorted(leaf.reader(), SchemaFields.DOC_UID);
          var live = leaf.reader().getLiveDocs();
          for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
            if (live != null && !live.get(doc)) continue;
            var row = PruneOps.Row.read(searcher, stored, leaf.docBase + doc, idField);
            if (row == null) { skipped++; continue; }
            String uid = uids.advanceExact(doc) ? uids.lookupOrd(uids.ordValue()).utf8ToString() : null;
            batch.add(new IndexedSource(row.docId(), row.filePath(), uid));
            if (batch.size() == batchSize) {
              consumer.accept(List.copyOf(batch)); batch.clear();
            }
          }
        }
        if (!batch.isEmpty()) consumer.accept(List.copyOf(batch));
        return skipped;
      });
    } catch (IOException failure) {
      throw new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DISK_IO,
          "Could not enumerate indexed sources", failure);
    }
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
   * one list, so the caller's peak heap is a batch rather than the corpus (tempdoc 931 Â§C.2).
   *
   * <p>A live parent whose {@code doc_id}/{@code doc_uid} docvalues are missing or blank is counted
   * in {@code parentsSkipped} and omitted. That shape predates the identity store or comes from a
   * partially-written legacy index; it mints a fresh identity at its next admission, which is a
   * recoverable outcome, whereas failing the scan takes the whole Worker down. Genuine I/O failure
   * is still surfaced â€” that is not a legacy shape, it is an unreadable index.
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
