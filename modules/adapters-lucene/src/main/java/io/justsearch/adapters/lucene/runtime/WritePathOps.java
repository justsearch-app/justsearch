/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.*;
import static io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import net.jcip.annotations.ThreadSafe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Internal write-path collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates core index-hub operations (single-doc write, batch apply, soft-delete
 * maintenance) that were previously inlined in the runtime. Guards (ensureStarted, guardWritable,
 * guardBackpressure) and queue-depth accounting remain in the facade.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code close()}.
 * Access from the runtime must go through a volatile snapshot to ensure visibility across threads.
 */
@ThreadSafe
public final class WritePathOps {
  private static final Logger log = LoggerFactory.getLogger(WritePathOps.class);

  // Shared lifecycle-status vocabulary driving the reset-status RMW lane (tempdoc 711). Every
  // *_status field in the catalog uses this same COMPLETED/PENDING token set.
  private static final String STATUS_COMPLETED = "COMPLETED";
  private static final String STATUS_PENDING = "PENDING";

  private final RuntimeSession session;
  private final String idField;
  private final SearcherBridge bridge;

  WritePathOps(RuntimeSession session, String idField, SearcherBridge bridge) {
    this.session = session;
    this.idField = idField;
    this.bridge = bridge;
  }

  /** Indexes and flushes the mapped document in the factory-owned disposable writer. */
  static void probeDocument(IndexWriter canary, Document document) throws IOException {
    canary.addDocument(document);
    canary.flush();
  }

  /**
   * Core single-document write. Caller is responsible for guards and queue-depth accounting.
   *
   * @param fields the document fields (already validated)
   * @param droppedVectors accumulator for vector fields dropped by the mapper (tempdoc 931 §C.3),
   *     or null when the caller does not count them
   */
  boolean indexDocument(Map<String, Object> fields, FieldMapper.DroppedVectorReport droppedVectors) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null || !w.isOpen()) {
        throw new IndexWriterUnavailableException(
            "IndexWriter not available (runtime not started or closed)");
      }
      String idValue = asString(fields.get(idField));
      if (idValue == null || idValue.isBlank()) {
        throw new IllegalArgumentException("IndexDocument missing required id field " + idField);
      }
      boolean hardDelete = asBoolean(fields.get(session.hardDeleteField));
      if (hardDelete) {
        w.deleteDocuments(new Term(idField, idValue));
        session.pendingDocs.incrementAndGet();
        TelemetryEvents te = session.telemetryEvents;
        if (te != null) te.onHardDelete();
        return false;
      }
      Document luceneDoc = session.fieldMapper.toDocument(fields, droppedVectors);
      boolean softDelete = asBoolean(fields.get(session.softDeleteField));
      if (softDelete) {
        appendSoftDeleteMaintenanceFields(luceneDoc, fields);
        w.softUpdateDocument(
            new Term(idField, idValue),
            luceneDoc,
            new NumericDocValuesField(session.softDeleteField, 1));
        session.pendingDocs.incrementAndGet();
      } else {
        w.updateDocument(new Term(idField, idValue), luceneDoc);
        session.pendingDocs.incrementAndGet();
      }
      return luceneDoc.getField(SchemaFields.VECTOR) != null
          && SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(luceneDoc.get(SchemaFields.EMBEDDING_STATUS));
    } catch (org.apache.lucene.store.AlreadyClosedException closed) {
      LifecycleSnapshot current = session.snapshot;
      if (current == null || current.writer() == null || !current.writer().isOpen()
          || java.util.Arrays.stream(closed.getStackTrace()).anyMatch(frame ->
              frame.getClassName().startsWith("org.apache.lucene.store."))) {
        throw new IndexWriterUnavailableException("Index storage closed during write", closed);
      }
      throw closed;
    } catch (IOException e) {
      throw indexingFailure(e, "Failed to add document");
    }
  }

  /**
   * Applies a pre-classified batch of updates, soft-deletes, and hard-deletes to the writer.
   */
  void applyBatch(
      List<DocWork> updates, List<DocWork> softDeletes, List<String> hardDeletes) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null || !w.isOpen()) {
        throw new IndexWriterUnavailableException(
            "IndexWriter not available (runtime not started or closed)");
      }
      if (!hardDeletes.isEmpty()) {
        Term[] terms = hardDeletes.stream().map(id -> new Term(idField, id)).toArray(Term[]::new);
        w.deleteDocuments(terms);
        session.pendingDocs.incrementAndGet();
      }
      for (DocWork work : updates) {
        w.updateDocument(new Term(idField, work.id()), work.doc());
        session.pendingDocs.incrementAndGet();
      }
      for (DocWork work : softDeletes) {
        appendSoftDeleteMaintenanceFields(work.doc(), work.fields());
        w.softUpdateDocument(
            new Term(idField, work.id()),
            work.doc(),
            new NumericDocValuesField(session.softDeleteField, 1));
        session.pendingDocs.incrementAndGet();
      }
    } catch (org.apache.lucene.store.AlreadyClosedException closed) {
      LifecycleSnapshot current = session.snapshot;
      if (current == null || current.writer() == null || !current.writer().isOpen()
          || java.util.Arrays.stream(closed.getStackTrace()).anyMatch(frame ->
              frame.getClassName().startsWith("org.apache.lucene.store."))) {
        throw new IndexWriterUnavailableException("Index storage closed during write", closed);
      }
      throw closed;
    } catch (IOException e) {
      throw indexingFailure(e, "Failed to apply batch");
    }
  }

  private RuntimeException indexingFailure(IOException failure, String message) {
    LifecycleSnapshot snapshot = session.snapshot;
    if (snapshot != null && snapshot.writer() != null
        && snapshot.writer().getTragicException() != null) {
      return new IndexWriterUnavailableException(message, failure);
    }
    return IndexRuntimeIOException.fromIndexOperation(failure, message);
  }

  void appendSoftDeleteMaintenanceFields(Document doc, Map<String, Object> fields) {
    Long ts = asLong(fields.get(softDeleteTimestampField(session.softDeleteField)));
    long effectiveTs = ts != null ? ts : System.currentTimeMillis();
    doc.add(
        new SortedNumericDocValuesField(softDeleteTimestampField(session.softDeleteField), effectiveTs));
    Long ordinal = asLong(fields.get(softDeleteVersionField(session.softDeleteField)));
    if (ordinal != null) {
      doc.add(
          new SortedNumericDocValuesField(softDeleteVersionField(session.softDeleteField), ordinal));
    }
    doc.add(new NumericDocValuesField(session.softDeleteField, 1));
  }

  /**
   * Deletes a single document by id. Caller is responsible for guards and queue-depth accounting.
   */
  void deleteById(String id) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null || !w.isOpen()) {
        throw new IndexWriterUnavailableException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteDocuments(new Term(idField, id));
      session.pendingDocs.incrementAndGet();
      TelemetryEvents te = session.telemetryEvents;
      if (te != null) te.onHardDelete();
    } catch (IOException e) {
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to delete document");
    }
  }

  /**
   * Deletes a parent document and all of its chunk documents. Caller is responsible for guards and
   * queue-depth accounting.
   */
  void deleteByIdAndChunks(String parentDocId) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      // 1) Delete the parent doc (exact id).
      w.deleteDocuments(new Term(idField, parentDocId));
      session.pendingDocs.incrementAndGet();

      // 2) Delete chunk docs using field-based query (P0.8: no string pattern matching)
      // This works for both legacy and new opaque chunk IDs.
      BooleanQuery chunkQuery =
          new BooleanQuery.Builder()
              .add(
                  new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)),
                  BooleanClause.Occur.FILTER)
              .add(
                  new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                  BooleanClause.Occur.FILTER)
              .build();
      w.deleteDocuments(chunkQuery);
      session.pendingDocs.incrementAndGet();
      TelemetryEvents te = session.telemetryEvents;
      if (te != null) te.onHardDelete();
    } catch (IOException e) {
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to delete document and chunks");
    }
  }

  /**
   * Deletes chunk documents for a parent document using field-based filtering. Caller is
   * responsible for guards and queue-depth accounting.
   */
  void deleteChunksForParentDocId(String parentDocId) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      // Build query: parent_doc_id == parentDocId AND is_chunk == "true"
      BooleanQuery query =
          new BooleanQuery.Builder()
              .add(
                  new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)),
                  BooleanClause.Occur.FILTER)
              .add(
                  new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                  BooleanClause.Occur.FILTER)
              .build();
      w.deleteDocuments(query);
      session.pendingDocs.incrementAndGet();
      TelemetryEvents te = session.telemetryEvents;
      if (te != null) te.onHardDelete();
      log.debug("deleteChunksForParentDocId: submitted delete for chunks of {}", parentDocId);
    } catch (IOException e) {
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to delete chunks for parent");
    }
  }

  /**
   * Deletes all documents (parents and chunks) under the given path prefix. Caller owns queue
   * accounting.
   */
  void deleteByPathPrefix(String pathPrefix) {
    deleteByPathPrefixExcludingAcceptedSurvivors(pathPrefix, List.of());
  }

  void deleteByPathPrefixExcludingAcceptedSurvivors(String pathPrefix, List<String> fileIds) {
    deleteByPathPrefixExcludingAcceptedSurvivors(pathPrefix, fileIds, List.of());
  }

  void deleteByPathPrefixExcludingAcceptedSurvivors(
      String pathPrefix, List<String> fileIds, List<String> excludedSubtrees) {
    String normalized = normalizePathPrefix(pathPrefix);
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null || !w.isOpen()) {
        throw new IndexWriterUnavailableException("IndexWriter not available (runtime not started or closed)");
      }
      Query query;
      try (DirectoryReader reader = DirectoryReader.open(w)) {
        query = QueryFilterBuilder.excludingAcceptedSurvivors(
            PruneOps.ownedPathPrefixQuery(new IndexSearcher(reader), idField, normalized, excludedSubtrees),
            fileIds, List.of());

      }
      w.deleteDocuments(query);
      session.pendingDocs.incrementAndGet();
      // Tempdoc 809 finding 3: publish the bulk-deletion signal AFTER the delete is submitted, so
      // any reader that observes the new epoch is guaranteed the deletion is already in the writer.
      session.bulkDeleteEpoch.incrementAndGet();
      log.info("deleteByPathPrefix: deletion submitted for path prefix: {}", normalized);
    } catch (IOException e) {
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to delete by path prefix");
    }
  }

  /**
   * Tempdoc 811 (C-2a) — deletes every document (parent and chunk) carrying the given collection
   * term. This is the removal route for ad-hoc ingests: {@link #deleteByPathPrefix} is
   * watched-root-prefix driven and can never reach a document indexed from a path under no watched
   * root, so before 811 those documents were permanently unaddressable.
   *
   * <p>Caller is responsible for refusing reserved/default collections — this method deletes exactly
   * what it is told to.
   *
   * @return the number of documents matched (and submitted for deletion)
   */
  int deleteByCollection(String collection) {
    return deleteByCollectionExcludingAcceptedSurvivors(collection, List.of());
  }

  int deleteByCollectionExcludingAcceptedSurvivors(String collection, List<String> fileIds) {
    if (collection == null || collection.isBlank()) {
      throw new IllegalArgumentException("deleteByCollection requires a non-blank collection");
    }
    LifecycleSnapshot snap = session.snapshot;
    IndexWriter w = snap != null ? snap.writer() : null;
    if (w == null || !w.isOpen()) {
      throw new IndexWriterUnavailableException("IndexWriter not available (runtime not started or closed)");
    }
    Query query = QueryFilterBuilder.excludingAcceptedSurvivors(
        new TermQuery(new Term(SchemaFields.COLLECTION, collection)), fileIds, List.of());
    try (DirectoryReader reader = DirectoryReader.open(w)) {
      int matched = new IndexSearcher(reader).count(query);
      w.deleteDocuments(query);
      session.pendingDocs.incrementAndGet();
      // Mirrors deleteByPathPrefix (tempdoc 809 finding 3): publish the bulk-deletion signal AFTER
      // the delete is submitted, so a reader observing the new epoch is guaranteed the deletion is
      // already in the writer.
      session.bulkDeleteEpoch.incrementAndGet();
      log.info(
          "deleteByCollection: deletion submitted for collection {} ({} documents matched)",
          collection,
          matched);
      return matched;
    } catch (IOException e) {
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to delete by collection");
    }
  }

  boolean backfillChunkDeclarations(
      IndexSearcher searcher, int parent, String docId, ChunkSetCompleteness.Declaration declaration)
      throws IOException {
    LifecycleSnapshot snap = session.snapshot;
    if (snap == null || snap.writer() == null) return false;
    // The coordinator holds its mutation lock. Re-read the current writer snapshot rather than
    // overwriting a parent/status change made since the admission searcher was acquired.
    try (var reader = DirectoryReader.open(snap.writer())) {
      var current = new IndexSearcher(reader);
      var hits = current.search(new TermQuery(new Term(idField, docId)), 1);
      if (hits.scoreDocs.length == 0) return false;
      int currentParent = hits.scoreDocs[0].doc;
      long verifiedModified = session.readPathOps.readLongDocValueOrStoredLong(searcher, parent, SchemaFields.MODIFIED_AT);
      long currentModified = session.readPathOps.readLongDocValueOrStoredLong(current, currentParent, SchemaFields.MODIFIED_AT);
      String content = current.storedFields().document(currentParent).get(SchemaFields.CONTENT);
      if (verifiedModified != currentModified
          || !declaration.revision().equals(ChunkParentRevision.sha256Hex(content == null ? "" : content))) return false;
      // Recheck children under the same lock: no partial/delete-only mutation can be certified
      // using the older admission snapshot. This does not invoke the splitter again.
      if (!session.chunkCompleteness.matchesDeclaration(current, docId, declaration.total(), declaration.revision())) return false;
      // This immutable snapshot is protected by the coordinator's mutation lock. Every
      // complete legacy parent shares its child summaries and the one subsequent refresh.
      // A partial set remains undeclared and therefore follows normal repair admission.
      var complete = new LinkedHashMap<String, ChunkSetCompleteness.Declaration>();
      for (var entry : session.chunkCompleteness.legacyParents(current, idField).entrySet()) {
        var assessment = session.chunkCompleteness.assess(current, entry.getValue(), entry.getKey());
        if (assessment.complete() && assessment.backfill() != null) {
          complete.put(entry.getKey(), assessment.backfill());
        }
      }
      var updates = new ArrayList<Map.Entry<String, Map<String, Object>>>();
      for (var entry : complete.entrySet()) {
        updates.add(Map.entry(entry.getKey(), Map.of(
            SchemaFields.CHUNK_TOTAL, entry.getValue().total(),
            SchemaFields.CHUNK_PARENT_CONTENT_SHA256, entry.getValue().revision(),
            SchemaFields.CONTENT_SHA256, entry.getValue().revision())));
      }
      readModifyWriteBatch(current, updates);
      return true;
    }
  }

  record SparseRead(Map<Integer, Map<String, Float>> features,
      Map<Integer, IOException> failures) {}

  SparseRead sparseFeatures(IndexSearcher searcher, Set<Integer> documents) {
    var result = new HashMap<Integer, Map<String, Float>>();
    var failures = new HashMap<Integer, IOException>();
    long started = System.nanoTime();
    long termsVisited = 0;
    long featuresRead = 0;
    for (var context : searcher.getIndexReader().leaves()) {
      var localDocuments = documents.stream().mapToInt(doc -> doc - context.docBase)
          .filter(doc -> doc >= 0 && doc < context.reader().maxDoc()).sorted().toArray();
      if (localDocuments.length == 0) continue;
      try {
        var terms = context.reader().terms(SchemaFields.SPLADE);
        if (terms == null) continue;
        var iterator = terms.iterator();
        for (var term = iterator.next(); term != null; term = iterator.next()) {
          termsVisited++;
          String feature = term.utf8ToString();
          var values = org.apache.lucene.document.FeatureField.newDoubleValues(SchemaFields.SPLADE, feature)
              .getValues(context, null);
          for (int doc : localDocuments) {
            int globalDoc = context.docBase + doc;
            if (failures.containsKey(globalDoc)) continue;
            try {
              if (values.advanceExact(doc)) {
                result.computeIfAbsent(globalDoc, ignored -> new HashMap<>())
                    .put(feature, (float) values.doubleValue());
                featuresRead++;
              }
            } catch (IOException failure) {
              failures.put(globalDoc, failure);
            }
          }
        }
      } catch (IOException failure) {
        // A dictionary failure makes every requested artifact in this leaf unreadable.
        for (int doc : localDocuments) failures.put(context.docBase + doc, failure);
      }
    }
    failures.keySet().forEach(result::remove);
    log.debug("Prepared sparse preservation: documents={}, termsVisited={}, featuresRead={}, failures={}, elapsedMs={}",
        documents.size(), termsVisited, featuresRead, failures.size(), (System.nanoTime() - started) / 1_000_000.0);
    return new SparseRead(result, failures);
  }

  private static final class SparsePreservationException extends IOException {
    SparsePreservationException(IOException cause) { super("Sparse artifact storage read failed", cause); }
  }

  /**
   * Per-batch memo of the parent documents a chunk RMW re-slices from: the stored content, and the
   * SHA-256 revision identity {@link #preserveChunkContent} compares against the chunk's own
   * (tempdoc 931 §C.1). Both are keyed by parent doc id so a batch of sibling chunks reads and
   * hashes each parent exactly once.
   */
  private static final class ParentSliceCache {
    private final Map<String, String> content = new HashMap<>();
    private final Map<String, String> revision = new HashMap<>();
    private SparseRead sparse;
    private final Map<Integer, Document> stored = new HashMap<>();
    // Source-wide fan-out may revisit a chunk explicitly patched in the same snapshot.
    private final Map<String, Map<String, Object>> appliedUpdates = new HashMap<>();

    String content(String parentId) {
      return content.get(parentId);
    }

    void putContent(String parentId, String parentContent) {
      content.put(parentId, parentContent);
    }

    void putContentIfAbsent(String parentId, String parentContent) {
      content.putIfAbsent(parentId, parentContent);
    }

    String revision(String parentId, String parentContent) {
      return revision.computeIfAbsent(parentId, k -> ChunkParentRevision.sha256Hex(parentContent));
    }

    /** After a rename the new parent id addresses the same content, hence the same revision. */
    void alias(String oldParentId, String newParentId) {
      String carried = content.get(oldParentId);
      if (carried == null) return;
      content.put(newParentId, carried);
      String carriedRevision = revision.get(oldParentId);
      if (carriedRevision != null) revision.put(newParentId, carriedRevision);
    }
  }

  private boolean readModifyWrite(
      IndexSearcher searcher,
      String docId,
      Map<String, Object> updates,
      ParentSliceCache parentCache)
      throws IOException {
    return readModifyWrite(searcher, docId, updates, parentCache, Map.of());
  }

  private boolean readModifyWrite(
      IndexSearcher searcher,
      String docId,
      Map<String, Object> updates,
      ParentSliceCache parentCache,
      Map<String, Object> expectedFields) throws IOException {
    return readModifyWrite(searcher, docId, updates, parentCache, expectedFields, null,
        new FieldMapper.DroppedVectorReport());
  }

  private boolean readModifyWrite(IndexSearcher searcher, String docId, Map<String, Object> updates,
      ParentSliceCache parentCache, Map<String, Object> expectedFields,
      DocumentFieldOps.EnrichmentInput input, FieldMapper.DroppedVectorReport droppedVectors)
      throws IOException {
    var priorUpdates = parentCache.appliedUpdates.get(docId);
    if (priorUpdates != null && (!java.util.Collections.disjoint(priorUpdates.keySet(), io.justsearch.indexing.SourceEnvelope.FIELDS)
        || !java.util.Collections.disjoint(updates.keySet(), io.justsearch.indexing.SourceEnvelope.FIELDS))) {
      var combined = new HashMap<>(priorUpdates);
      combined.putAll(updates);
      updates = combined;
    }
    var topDocs = searcher.search(new TermQuery(new Term(idField, docId)), 1);
    if (topDocs.scoreDocs.length == 0) {
      log.debug("readModifyWrite: document not found: {}", docId);
      return false;
    }
    int globalDocId = topDocs.scoreDocs[0].doc;

    // Load all stored fields from existing document, accumulating multi-valued fields into Lists
    if (parentCache.sparse != null && parentCache.sparse.failures().containsKey(globalDocId)) {
      throw new SparsePreservationException(parentCache.sparse.failures().get(globalDocId));
    }
    Document oldDoc = parentCache.stored.get(globalDocId);
    if (oldDoc == null) {
      try {
        oldDoc = searcher.storedFields().document(globalDocId);
      } catch (IOException failure) {
        throw new SparsePreservationException(failure);
      }
    }
    Map<String, Object> fields = new HashMap<>();
    for (IndexableField field : oldDoc.getFields()) {
      String name = field.name();
      Object val = field.binaryValue() != null ? org.apache.lucene.util.BytesRef.deepCopyOf(field.binaryValue())
          : field.stringValue() != null ? field.stringValue() : field.numericValue();
      if (val == null) continue;
      if (fields.containsKey(name)) {
        // Multi-valued field: accumulate into a mutable List
        Object existing = fields.get(name);
        if (existing instanceof List<?>) {
          @SuppressWarnings("unchecked")
          List<Object> mutable = (List<Object>) existing;
          mutable.add(val);
        } else {
          List<Object> list = new ArrayList<>();
          list.add(existing);
          list.add(val);
          fields.put(name, list);
        }
      } else {
        fields.put(name, val);
      }
    }

    for (FieldMapper.FieldDef def : session.fieldMapper.fieldDefs().values()) {
      if ("double".equals(def.type) && def.docValues) {
        Long bits = readNumericDocValue(searcher, globalDocId, def.id);
        if (bits != null) fields.put(def.id, Double.longBitsToDouble(bits));
      }
    }

    if (session.fieldMapper.fieldDef(SchemaFields.SPLADE) != null) {
      try {
        if (!updates.containsKey(SchemaFields.SPLADE_STATUS)) {
          String status = readKeywordDocValue(searcher, globalDocId, SchemaFields.SPLADE_STATUS);
          if (status != null) fields.put(SchemaFields.SPLADE_STATUS, status);
        }
        if (!updates.containsKey(SchemaFields.SPLADE_RETRY_COUNT)) {
          Long retry = readNumericDocValue(searcher, globalDocId, SchemaFields.SPLADE_RETRY_COUNT);
          if (retry != null) fields.put(SchemaFields.SPLADE_RETRY_COUNT, retry);
        }
      } catch (IOException failure) {
        throw new SparsePreservationException(failure);
      }
    }

    for (var expected : expectedFields.entrySet()) {
      if (!java.util.Objects.equals(fields.get(expected.getKey()), expected.getValue())) {
        return false;
      }
    }

    if (input != null) {
      Map<String, String> current = new HashMap<>();
      for (String name : DocumentFieldOps.ENRICHMENT_FIELDS) {
        Object stored = fields.get(name);
        if (stored != null) current.put(name, stored.toString());
      }
      session.readPathOps.projectDocValues(searcher, globalDocId, DocumentFieldOps.ENRICHMENT_FIELDS, current);
      if (!matchesEnrichmentInput(input.fields(), current, updates)) return false;
      if (input.content() == null && asBoolean(fields.get(SchemaFields.IS_CHUNK))
          && !hasReconstructableChunkProof(input, parentCache)) {
        // Quarantine preserves the captured identity comparison without inventing chunk text.
        // Only failure bookkeeping may use this lane; successful artifacts still require proof.
        if ((updates.containsKey(SchemaFields.CHUNK_VECTOR)
                && !"FAILED".equals(updates.get(SchemaFields.CHUNK_EMBEDDING_STATUS)))
            || updates.isEmpty() || updates.entrySet().stream().anyMatch(entry ->
            !(SchemaFields.CHUNK_VECTOR.equals(entry.getKey()) && entry.getValue() == null)
            && ((!ENRICHMENT_STAGE_FIELDS.containsKey(entry.getKey())
                && !ENRICHMENT_STAGE_FIELDS.containsValue(entry.getKey()))
            || (entry.getKey().endsWith("_status") && !"FAILED".equals(entry.getValue()))))) return false;
        updates = new HashMap<>(updates);
        updates.put(SchemaFields.CHUNK_CONTENT, "");
        for (String status : List.of(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.SPLADE_STATUS)) {
          String retry = ENRICHMENT_STAGE_FIELDS.get(status);
          if (current.containsKey(status) || updates.containsKey(status) || updates.containsKey(retry)) {
            if (!java.util.Objects.equals(input.fields().get(status), current.get(status))
                || !java.util.Objects.equals(input.fields().get(retry), current.get(retry))) return false;
            updates.put(status, "FAILED");
            if (current.get(retry) != null) updates.putIfAbsent(retry, current.get(retry));
          }
        }
      }
      // Reuse the RMW fields and the already-compared parent proof: no new index reads.
      var allows = session.sourceProcessingPolicy;
      if (allows != null) {
        String source = asString(fields.get(SchemaFields.PATH));
        String projection = asString(fields.get(SchemaFields.PROJECTION_SOURCE_ID));
        if ((projection == null || projection.isBlank()) && (source == null || source.isBlank())
            && input.parentId() != null && !input.parentId().isBlank()) {
          var parentFields = input.parentFields();
          source = parentFields == null ? null : parentFields.get(SchemaFields.PATH);
          if (source == null || source.isBlank()) source = input.parentId();
          projection = parentFields == null ? null : parentFields.get(SchemaFields.PROJECTION_SOURCE_ID);
        }
        if (projection != null && !projection.isBlank()) source = null;
        else if (source == null || source.isBlank()) source = docId;
        if (!allows.test(source)) return false;
      }
    }

    if ((updates.containsKey(SchemaFields.CONTENT)
        && !java.util.Objects.equals(fields.get(SchemaFields.CONTENT), updates.get(SchemaFields.CONTENT)))
        || (updates.containsKey(SchemaFields.CONTENT_SHA256)
        && !java.util.Objects.equals(ChunkParentRevision.sha256Hex(asString(fields.get(SchemaFields.CONTENT)) == null
            ? "" : asString(fields.get(SchemaFields.CONTENT))), updates.get(SchemaFields.CONTENT_SHA256)))) {
      updates = new HashMap<>(updates);
      // An explicit new artifact belongs to the replacement text; omitted artifacts belong
      // to the old text and must bypass the ordinary RMW preservation policies.
      for (String artifact : List.of(SchemaFields.VECTOR, SchemaFields.CHUNK_VECTOR, SchemaFields.SPLADE,
          SchemaFields.ENTITY_PERSONS_RAW, SchemaFields.ENTITY_ORGANIZATIONS_RAW, SchemaFields.ENTITY_LOCATIONS_RAW)) {
        updates.putIfAbsent(artifact, null);
      }
      for (String stage : asBoolean(fields.get(SchemaFields.IS_CHUNK))
          ? List.of("chunk_embedding", "splade") : List.of("embedding", "splade", "ner")) {
        String status = stage + "_status";
        if (session.fieldMapper.fieldDef(status) != null) {
          if (stage.equals("splade") && updates.get(SchemaFields.SPLADE) instanceof Map<?, ?> sparse
              && !sparse.isEmpty()) continue;
          updates.putIfAbsent(status, STATUS_PENDING);
          String retry=stage + "_retry_count";
          if (session.fieldMapper.fieldDef(retry) != null && !updates.containsKey(retry)) updates.put(retry,"0");
        }
      }
    }

    // A parent read here can serve every sibling chunk in batch/path RMW. More importantly, a
    // rename rewrites the parent first while this searcher still exposes the old identity; caching
    // its content lets all old-parent chunk slices survive that rewrite exactly.
    String storedContent = asString(fields.get(SchemaFields.CONTENT));
    if (storedContent != null) {
      parentCache.putContentIfAbsent(docId, storedContent);
    }

    preserveChunkContent(searcher, docId, updates, fields, parentCache);

    // RMW preservation engine (tempdoc 711): stored-field reconstruction above cannot see
    // non-stored, non-docValues data-bearing fields (KnnFloatVectorField vectors, SPLADE
    // FeatureFields). For each catalog field that declares an rmwPolicy and is absent from the
    // caller's update map, apply its declared disposition so the rewrite preserves it structurally
    // instead of relying on per-call-site discipline.
    applyRmwPolicies(searcher, globalDocId, updates, fields, parentCache);
    if (session.fieldMapper.fieldDef(SchemaFields.SPLADE) != null && updates.containsKey(SchemaFields.SPLADE)
        && (updates.get(SchemaFields.SPLADE) == null
            || updates.get(SchemaFields.SPLADE) instanceof Map<?, ?> sparse && sparse.isEmpty())
        && !updates.containsKey(SchemaFields.SPLADE_STATUS)) {
      applyResetStatus(searcher, globalDocId, SchemaFields.SPLADE_STATUS, updates, fields);
    }

    // Apply updates (overwrites existing values, including anything the engine restored)
    fields.putAll(updates);
    if (updates.containsKey(SchemaFields.CONTENT)) {
      String content = asString(fields.get(SchemaFields.CONTENT));
      fields.put(SchemaFields.CONTENT_SHA256,
          ChunkParentRevision.sha256Hex(content == null ? "" : content));
    }

    // Write-time status/artifact contract (tempdoc 798). The merged map — existing stored fields
    // union rmwPolicy-preserved fields union the caller's updates — is exactly what will be
    // indexed, so it is the only place the RMW lane can tell a truthful COMPLETED from a lie.
    // IndexingCoordinator.validate() never runs here (only indexSingle/indexBatch call it), and
    // readModifyWriteBatch / updateDocumentPaths funnel through this method, so this one call
    // covers every partial-update path.
    StatusArtifactContract.enforce(session, fields, "read-modify-write:" + docId);

    // Re-index with updated fields
    Document newDoc = session.fieldMapper.toDocument(fields, droppedVectors);
    if (droppedVectors.count() > 0) {
      log.warn(
          "read-modify-write dropped {} unusable vector field(s) for {}: {} ({})",
          droppedVectors.count(),
          docId,
          droppedVectors.sampleDocIds(),
          droppedVectors.lastReason());
      TelemetryEvents rmwEvents = session.telemetryEvents;
      if (rmwEvents != null) rmwEvents.onVectorFieldDropped(droppedVectors.count());
    }
    LifecycleSnapshot rmwSnap = session.snapshot;
    if (rmwSnap == null || rmwSnap.writer() == null || !rmwSnap.writer().isOpen()) {
      throw new IndexWriterUnavailableException("IndexWriter not available during read-modify-write");
    }
    rmwSnap.writer().updateDocument(new Term(idField, docId), newDoc);
    // Account per completed mutation, including the applied prefix of a batch that later fails.
    session.pendingDocs.incrementAndGet();
    parentCache.appliedUpdates.put(docId, updates);
    log.debug("readModifyWrite: updated document: {}", docId);
    return true;
  }

  private boolean hasReconstructableChunkProof(
      DocumentFieldOps.EnrichmentInput input, ParentSliceCache parentCache) {
    var slice = DocumentFieldOps.chunkSliceFrom(input.fields());
    String content = input.parentFields() == null ? null : input.parentFields().get(SchemaFields.CONTENT);
    // ChunkReadRevisionGuard also withholds legacy slices that bisect a surrogate pair.
    // Their matching proof still permits exact RMW preservation without exposing text to models.
    return slice != null && content != null && slice.endChar() <= content.length()
        && java.util.Objects.equals(slice.parentContentRevision(),
            parentCache.revision(slice.parentDocId(), content));
  }

  /**
   * Reconstructs the non-stored chunk text before a whole-document RMW rewrite.
   *
   * <p>Failure is deliberately closed: rewriting a chunk without this field would silently erase
   * its BM25 postings. The caller must rebuild an old or malformed index instead.
   */
  private void preserveChunkContent(
      IndexSearcher searcher,
      String chunkId,
      Map<String, Object> updates,
      Map<String, Object> fields,
      ParentSliceCache parentCache)
      throws IOException {
    FieldMapper.FieldDef chunkContentDef =
        session.fieldMapper.fieldDefs().get(SchemaFields.CHUNK_CONTENT);
    if (chunkContentDef == null
        || !FieldMapper.RMW_REDERIVE_PARENT_SLICE.equals(chunkContentDef.rmwPolicy)) {
      return;
    }
    if (updates.containsKey(SchemaFields.CHUNK_CONTENT)
        || !asBoolean(fields.get(SchemaFields.IS_CHUNK))) {
      return;
    }

    String parentId =
        asString(
            updates.containsKey(SchemaFields.PARENT_DOC_ID)
                ? updates.get(SchemaFields.PARENT_DOC_ID)
                : fields.get(SchemaFields.PARENT_DOC_ID));
    int start =
        parseRequiredChunkOffset(
            updates.containsKey(SchemaFields.CHUNK_START_CHAR)
                ? updates.get(SchemaFields.CHUNK_START_CHAR)
                : fields.get(SchemaFields.CHUNK_START_CHAR),
            "start",
            chunkId);
    int end =
        parseRequiredChunkOffset(
            updates.containsKey(SchemaFields.CHUNK_END_CHAR)
                ? updates.get(SchemaFields.CHUNK_END_CHAR)
                : fields.get(SchemaFields.CHUNK_END_CHAR),
            "end",
            chunkId);
    if (parentId == null || parentId.isBlank() || end < start) {
      throw new IOException("Cannot preserve chunk_content for malformed chunk " + chunkId);
    }

    String parentContent = parentCache.content(parentId);
    if (parentContent == null) {
      var parentDocs = searcher.search(new TermQuery(new Term(idField, parentId)), 1);
      if (parentDocs.scoreDocs.length == 0) {
        throw new IOException(
            "Cannot preserve chunk_content for " + chunkId + ": missing parent " + parentId);
      }
      Document parentDoc = searcher.storedFields().document(parentDocs.scoreDocs[0].doc);
      parentContent = parentDoc.get(SchemaFields.CONTENT);
      if (parentContent == null) {
        throw new IOException(
            "Cannot preserve chunk_content for " + chunkId + ": parent content unavailable");
      }
      parentCache.putContent(parentId, parentContent);
    }

    // Tempdoc 931 §C.1 — the offsets alone do not say WHICH parent revision they address. Parent
    // write and chunk regeneration are separate coordinator calls, so an NRT refresh between them
    // exposes the new parent content beside the not-yet-regenerated chunks; an equal-or-longer
    // rewrite passes the length check below and silently re-slices the wrong text. Refusing costs
    // nothing: such a chunk is stale by definition and regeneration deletes it.
    String storedRevision =
        asString(
            updates.containsKey(SchemaFields.CHUNK_PARENT_CONTENT_SHA256)
                ? updates.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256)
                : fields.get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
    String liveRevision = parentCache.revision(parentId, parentContent);
    if (storedRevision == null || !storedRevision.equals(liveRevision)) {
      throw new IOException(
          "Cannot preserve chunk_content for "
              + chunkId
              + ": parent content revision mismatch (stored "
              + ChunkParentRevision.shortForm(storedRevision)
              + ", live "
              + ChunkParentRevision.shortForm(liveRevision)
              + ")");
    }

    if (end > parentContent.length()) {
      throw new IOException(
          "Cannot preserve chunk_content for "
              + chunkId
              + ": slice ["
              + start
              + ","
              + end
              + ") exceeds parent length "
              + parentContent.length());
    }
    fields.put(SchemaFields.CHUNK_CONTENT, parentContent.substring(start, end));
  }

  private static int parseRequiredChunkOffset(Object value, String label, String chunkId)
      throws IOException {
    if (value == null) {
      throw new IOException(
          "Cannot preserve chunk_content for " + chunkId + ": missing " + label + " offset");
    }
    try {
      int parsed = Integer.parseInt(value.toString());
      if (parsed < 0) throw new NumberFormatException("negative");
      return parsed;
    } catch (NumberFormatException e) {
      throw new IOException(
          "Cannot preserve chunk_content for "
              + chunkId
              + ": invalid "
              + label
              + " offset "
              + value,
          e);
    }
  }

  /** Load stored fields once, then scan only completed legacy rows lacking a payload. */
  private SparseRead sparseForBatch(IndexSearcher searcher,
      List<Map.Entry<String, Map<String, Object>>> updates, ParentSliceCache cache) throws IOException {
    if (session.fieldMapper.fieldDef(SchemaFields.SPLADE) == null)
      return new SparseRead(Map.of(), Map.of());
    Set<Integer> documents = new HashSet<>();
    Map<Integer, IOException> failures = new HashMap<>();
    for (var update : updates) {
      if (update.getValue().containsKey(SchemaFields.SPLADE)) continue;
      var hits = searcher.search(new TermQuery(new Term(idField, update.getKey())), 1);
      if (hits.scoreDocs.length == 0) continue;
      int doc = hits.scoreDocs[0].doc;
      try {
        Document stored = searcher.storedFields().document(doc);
        cache.stored.put(doc, stored);
        var patch = update.getValue();
        String content = stored.get(SchemaFields.CONTENT);
        if ((patch.containsKey(SchemaFields.CONTENT)
                && !java.util.Objects.equals(content, patch.get(SchemaFields.CONTENT)))
            || (patch.containsKey(SchemaFields.CONTENT_SHA256)
                && !java.util.Objects.equals(ChunkParentRevision.sha256Hex(content == null ? "" : content),
                    patch.get(SchemaFields.CONTENT_SHA256)))) continue;
        Object status = patch.containsKey(SchemaFields.SPLADE_STATUS)
            ? patch.get(SchemaFields.SPLADE_STATUS)
            : readKeywordDocValue(searcher, doc, SchemaFields.SPLADE_STATUS);
        if (STATUS_COMPLETED.equals(status) && stored.getField(SparsePayload.FIELD) == null)
          documents.add(doc);
      } catch (IOException failure) {
        failures.put(doc, failure);
      }
    }
    var legacy = sparseFeatures(searcher, documents);
    failures.putAll(legacy.failures());
    return new SparseRead(legacy.features(), failures);
  }

  /**
   * Applies each catalog field's declared RMW disposition (tempdoc 711) for fields the caller
   * omitted: {@link FieldMapper#RMW_PRESERVE_REREAD} re-reads and carries the field forward;
   * sparse postings and their doc-value companions are carried forward as well. Content changes
   * invalidate omitted artifacts before these policies run.
   */
  private void applyRmwPolicies(
      IndexSearcher searcher, int globalDocId, Map<String, Object> updates, Map<String, Object> fields,
      ParentSliceCache parentCache)
      throws IOException {
    for (FieldMapper.FieldDef def : session.fieldMapper.rmwPolicyFields()) {
      if (updates.containsKey(def.id)) {
        continue; // caller supplied the field itself — its own value is authoritative
      }
      String policy = def.rmwPolicy;
      if (FieldMapper.RMW_PRESERVE_REREAD.equals(policy)) {
        float[] existing = readFloatVector(searcher, globalDocId, def.id);
        if (existing != null) {
          fields.put(def.id, existing);
        }
        // null => the doc has no vector for this field (common mid-ingest) — nothing to preserve.
      } else if (policy != null
          && policy.startsWith(FieldMapper.RMW_PRESERVE_REREAD_OR_RESET_PREFIX)) {
        // Tempdoc 717: preserve-reread with a reset-status fallback. Re-read the vector; if present,
        // carry it forward. If the re-read is null AND the paired status reads COMPLETED, that is a
        // genuine "status lies" state (F-032 class) — downgrade it to PENDING so a backfill
        // re-derives, instead of leaving a COMPLETED status pointing at a vector that is gone (the
        // hole plain preserve-reread left, tempdoc 714 §Reach). A null / PENDING / FAILED status is
        // deliberately NOT healed: a doc that never claimed to be embedded (mid-ingest, VDU
        // rejected/empty) must not be spuriously enrolled — so this lane does NOT reuse the
        // reset-status lane's null->PENDING healing (that is SPLADE's semantics, tempdoc 717 review).
        float[] existing = readFloatVector(searcher, globalDocId, def.id);
        if (existing != null) {
          fields.put(def.id, existing);
        } else {
          String statusField =
              policy.substring(FieldMapper.RMW_PRESERVE_REREAD_OR_RESET_PREFIX.length());
          if (!updates.containsKey(statusField)
              && STATUS_COMPLETED.equals(readKeywordDocValue(searcher, globalDocId, statusField))) {
            applyResetStatus(searcher, globalDocId, statusField, updates, fields);
          }
        }
      } else if (policy != null && policy.startsWith(FieldMapper.RMW_RESET_STATUS_PREFIX)) {
        if (SchemaFields.SPLADE.equals(def.id)) {
          Object status = updates.containsKey(SchemaFields.SPLADE_STATUS)
              ? updates.get(SchemaFields.SPLADE_STATUS) : fields.get(SchemaFields.SPLADE_STATUS);
          fields.remove(SchemaFields.SPLADE);
          if (status == null) {
            applyResetStatus(searcher, globalDocId, SchemaFields.SPLADE_STATUS, updates, fields);
          } else if (STATUS_COMPLETED.equals(status)) {
            try {
              Object payload = fields.get(SparsePayload.FIELD);
              if (payload instanceof org.apache.lucene.util.BytesRef bytes) {
                var features = SparsePayload.decode(bytes);
                fields.put(SchemaFields.SPLADE, features);
                log.debug("Sparse payload preservation: bytesRead={}, featuresRead={}, termsVisited=0", bytes.length, features.size());
              } else {
                if (fields.containsKey(SparsePayload.FIELD))
                  throw new IOException("Invalid stored sparse payload: expected one binary value");
                // Fan-out children may lie outside the explicit batch's sparse prefetch.
                var sparse = parentCache.sparse == null || !parentCache.stored.containsKey(globalDocId)
                    ? sparseFeatures(searcher, Set.of(globalDocId)) : parentCache.sparse;
                if (sparse.failures().containsKey(globalDocId)) throw sparse.failures().get(globalDocId);
                fields.put(SchemaFields.SPLADE, sparse.features().getOrDefault(globalDocId, Map.of()));
              }
            } catch (IOException failure) {
              throw new SparsePreservationException(failure);
            }
          }
          fields.remove(SparsePayload.FIELD);
          continue;
        }
        applyResetStatus(
            searcher,
            globalDocId,
            policy.substring(FieldMapper.RMW_RESET_STATUS_PREFIX.length()),
            updates,
            fields);
      } else if (FieldMapper.RMW_REDERIVE_PARENT_SLICE.equals(policy)
          && asBoolean(fields.get(SchemaFields.IS_CHUNK))
          && !fields.containsKey(SchemaFields.CHUNK_CONTENT)) {
        // preserveChunkContent runs before this catalog-driven loop. Keep the policy arm explicit
        // so a future refactor cannot validate the declaration yet silently skip its disposition.
        throw new IOException("chunk_content rederive-parent-slice policy was not satisfied");
      }
    }
  }

  /**
   * reset-status fallback: content replacement invalidates the old artifact, so drive its
   * declared status field. A COMPLETED status is downgraded to PENDING (the
   * data was just dropped; a backfill must re-derive it) and its retry counter reset; a missing
   * status is healed to PENDING; a non-terminal / FAILED status is preserved as-is (resurrecting
   * FAILED as PENDING would mask real failures). A caller-supplied status always wins.
   */
  private void applyResetStatus(
      IndexSearcher searcher,
      int globalDocId,
      String statusField,
      Map<String, Object> updates,
      Map<String, Object> fields)
      throws IOException {
    if (updates.containsKey(statusField)) {
      return; // caller-supplied status always wins
    }
    String retryField = deriveRetryField(statusField);
    boolean callerSuppliedRetry = retryField != null && updates.containsKey(retryField);
    String existingStatus = readKeywordDocValue(searcher, globalDocId, statusField);
    if (existingStatus == null && SchemaFields.SPLADE_STATUS.equals(statusField)
        && asBoolean(fields.get(SchemaFields.IS_CHUNK))
        && !session.resolvedConfig().rag().chunkSpladeEnabled()) {
      return;
    }
    if (existingStatus == null || STATUS_COMPLETED.equals(existingStatus)) {
      // Data just dropped (or the doc never carried a status) — force (re-)derivation.
      fields.put(statusField, STATUS_PENDING);
      if (retryField != null && !callerSuppliedRetry) {
        fields.put(retryField, "0");
      }
    } else {
      // Preserve a non-terminal / FAILED status; don't resurrect FAILED as PENDING.
      fields.put(statusField, existingStatus);
      if (retryField != null && !callerSuppliedRetry) {
        Long existingRetry = readNumericDocValue(searcher, globalDocId, retryField);
        if (existingRetry != null) {
          fields.put(retryField, Long.toString(existingRetry));
        }
      }
    }
  }

  /**
   * Derives the retry-counter field paired with a status field by the {@code <prefix>_status} ->
   * {@code <prefix>_retry_count} convention, or null if the catalog has no such field.
   */
  private String deriveRetryField(String statusField) {
    if (!statusField.endsWith("_status")) {
      return null;
    }
    String candidate =
        statusField.substring(0, statusField.length() - "_status".length()) + "_retry_count";
    return session.fieldMapper.fieldDef(candidate) != null ? candidate : null;
  }

  /** Explicit backing files own their evidence; pathless chunks inherit their linked parent. */
  private static boolean inheritsSource(Document child, String sourcePath) {
    String path = child.get(SchemaFields.PATH);
    return path == null || path.isBlank() || java.util.Objects.equals(path, sourcePath);
  }

  /** Parent and inheriting chunks share one serialized writer snapshot. Exceptions propagate even
   * after the parent write, so the caller cannot acknowledge a partially published source. */
  private boolean readModifyWriteSource(IndexSearcher searcher, String id, Map<String, Object> updates,
      ParentSliceCache cache, Map<String, Object> expected) throws IOException {
    if (!readModifyWrite(searcher, id, updates, cache, expected)) return false;
    Map<String, Object> envelope = new HashMap<>();
    for (String field : io.justsearch.indexing.SourceEnvelope.FIELDS) if (updates.containsKey(field)) envelope.put(field, updates.get(field));
    if (envelope.isEmpty()) return true;
    var parent = searcher.search(new TermQuery(new Term(idField, id)), 1);
    var stored = searcher.storedFields();
    var parentDoc = stored.document(parent.scoreDocs[0].doc, Set.of(SchemaFields.PATH, SchemaFields.IS_CHUNK));
    if ("true".equals(parentDoc.get(SchemaFields.IS_CHUNK))) return true;
    String path = parentDoc.get(SchemaFields.PATH);
    if (path == null || path.isBlank()) path = id;
    Query query = new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, id)), BooleanClause.Occur.FILTER)
        .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER).build();
    org.apache.lucene.search.ScoreDoc after = null;
    while (true) {
      var page = searcher.searchAfter(after, query, 1024);
      if (page.scoreDocs.length == 0) break;
      for (var hit : page.scoreDocs) {
        var child = stored.document(hit.doc, Set.of(idField, SchemaFields.PATH));
        if (inheritsSource(child, path)) readModifyWrite(searcher, child.get(idField), envelope, cache);
      }
      after = page.scoreDocs[page.scoreDocs.length - 1];
    }
    return true;
  }

  /**
   * Batch read-modify-write: executes {@link #readModifyWrite} for each entry against the same
   * point-in-time searcher snapshot. The caller provides a leased searcher (via {@link
   * ReadPathOps#withSearcher}) with a single NRT refresh covering all writes.
   *
   * <p>Follows the same pattern as {@link #updateDocumentPaths}, which also performs multiple
   * {@code readModifyWrite} calls under one searcher lease.
   *
   * @param searcher the leased IndexSearcher (point-in-time snapshot for all reads)
   * @param batchUpdates list of (docId, updates) pairs
   * @return result with counts of updated and not-found documents
   * @throws IOException if any IndexWriter write fails (batch is partially applied)
   */
  LuceneRuntimeTypes.BatchUpdateResult readModifyWriteBatch(
      IndexSearcher searcher, List<Map.Entry<String, Map<String, Object>>> batchUpdates)
      throws IOException {
    int updated = 0;
    int notFound = 0;
    long minNs = Long.MAX_VALUE, maxNs = 0, sumNs = 0;
    ParentSliceCache parentCache = new ParentSliceCache();
    parentCache.sparse = sparseForBatch(searcher, batchUpdates, parentCache);
    IOException storageFailure = null;
    for (Map.Entry<String, Map<String, Object>> entry : batchUpdates) {
      long t0 = System.nanoTime();
      try {
        if (readModifyWriteSource(searcher, entry.getKey(), entry.getValue(), parentCache, Map.of())) updated++;
        else notFound++;
      } catch (SparsePreservationException failure) {
        storageFailure = failure;
      }
      long elapsed = System.nanoTime() - t0;
      sumNs += elapsed;
      if (elapsed < minNs) minNs = elapsed;
      if (elapsed > maxNs) maxNs = elapsed;
    }
    int total = updated + notFound;
    if (total > 0) {
      log.info(
          "RMW batch: docs={}, min={}ms, max={}ms, avg={}ms, total={}ms",
          total,
          minNs / 1_000_000,
          maxNs / 1_000_000,
          (sumNs / total) / 1_000_000,
          sumNs / 1_000_000);
    }
    if (storageFailure != null) throw storageFailure;
    return new LuceneRuntimeTypes.BatchUpdateResult(updated, notFound);
  }

  /**
   * Updates document paths after a file MOVE/RENAME operation. Rewrites the parent document with
   * updated DOC_ID, PATH, and FILENAME fields, then updates all chunk documents' PARENT_DOC_ID and
   * PATH fields. Reuses {@link #readModifyWrite} for each document.
   *
   * <p>The caller provides a leased searcher (point-in-time snapshot). All writes go to the
   * IndexWriter and are not visible through the searcher, which is safe: we read old state and
   * write new state in a single pass.
   *
   * @return number of documents updated (parent + chunks), or 0 if old path not found
   */
  int updateDocumentPaths(IndexSearcher searcher, String oldPath, String newPath, Map<String, Object> envelope)
      throws IOException {
    if (oldPath.equals(newPath)) return 0;
    int lastSep = Math.max(newPath.lastIndexOf('/'), newPath.lastIndexOf('\\'));
    String newFilename = lastSep >= 0 ? newPath.substring(lastSep + 1) : newPath;
    ParentSliceCache parentCache = new ParentSliceCache();
    var source = searcher.search(new TermQuery(new Term(idField, oldPath)), 1);
    var destination = searcher.search(new TermQuery(new Term(idField, newPath)), 1);
    if (source.scoreDocs.length == 0 && destination.scoreDocs.length == 0) return 0;
    // A destination alone does not prove this mapping moved it. Only surviving old chunks
    // establish a partial rename to resume; an unrelated missing source must leave it untouched.
    if (source.scoreDocs.length == 0 && searcher.count(new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, oldPath)), BooleanClause.Occur.FILTER)
        .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER).build()) == 0) return 0;
    int count = 0;
    if (source.scoreDocs.length > 0) {
      // Delete only before the source parent moves. A retry with only a destination parent
      // must retain that parent and every child already moved in the previous attempt.
      if (searcher.count(IndexCountOps.idAndChunksQuery(newPath)) > 0) deleteByIdAndChunks(newPath);
      var updates = new HashMap<String, Object>(envelope);
      updates.putAll(Map.of(SchemaFields.DOC_ID, newPath, SchemaFields.PATH, newPath,
          SchemaFields.FILENAME, newFilename));
      readModifyWrite(searcher, oldPath, updates, parentCache);
      parentCache.alias(oldPath, newPath);
      count++;
    } else {
      if (!envelope.isEmpty()) {
        readModifyWrite(searcher, newPath, envelope, parentCache);
        count++;
      }
      String content = searcher.storedFields().document(destination.scoreDocs[0].doc)
          .get(SchemaFields.CONTENT);
      if (content != null) parentCache.putContent(oldPath, content);
      parentCache.alias(oldPath, newPath);
    }
    Query parents = new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, oldPath));
    if (!envelope.isEmpty() && source.scoreDocs.length == 0) parents = new BooleanQuery.Builder()
        .add(parents, BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, newPath)), BooleanClause.Occur.SHOULD).build();
    BooleanQuery chunkQuery = new BooleanQuery.Builder()
        .add(parents, BooleanClause.Occur.FILTER)
        .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER)
        .build();
    org.apache.lucene.search.ScoreDoc after = null;
    while (true) {
      var page = searcher.searchAfter(after, chunkQuery, 1024);
      if (page.scoreDocs.length == 0) break;
      for (var hit : page.scoreDocs) {
        var child = searcher.storedFields().document(hit.doc, Set.of(idField, SchemaFields.PATH));
        var updates = new HashMap<String, Object>();
        updates.put(SchemaFields.PARENT_DOC_ID, newPath);
        if (inheritsSource(child, oldPath) || inheritsSource(child, newPath)) {
          updates.put(SchemaFields.PATH, newPath);
          updates.putAll(envelope);
        }
        if (readModifyWrite(searcher, child.get(idField), updates, parentCache)) count++;
      }
      after = page.scoreDocs[page.scoreDocs.length - 1];
    }
    return count;
  }

  private void guardWritable() {
    // Tempdoc 406 Phase 4b (A1): replaced state-machine check with snapshot/writer
    // null check. WritePathOps is only reachable through RunningRuntime, which by
    // construction has an open writer. Snapshot null means the runtime was closed.
    // Also reject writes during drain with IllegalStateException.
    if (session.draining) {
      throw new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DRAINING,
          "Runtime is draining", null);
    }
    LifecycleSnapshot snap = session.snapshot;
    if (snap == null || snap.writer() == null) {
      throw new IndexWriterUnavailableException("Index is not writable (writer unavailable)");
    }
  }

  // Tempdoc 402 Phase D: these mutating methods are package-private. All external callers
  // write through IndexingCoordinator (single-writer invariant). The coordinator lives in the
  // same package and dispatches here under its dispatchLock, which is how the concurrent-RMW
  // race fix (393 § 1.4) is enforced.

  /**
   * Update a document via read-modify-write. Non-stored data-bearing fields the caller omits are
   * preserved per their catalog {@code rmwPolicy} (tempdoc 711).
   */
  boolean updateDocument(String docId, Map<String, Object> updates) {
    return updateDocumentIf(docId, Map.of(), updates);
  }

  /** Caller holds IndexingCoordinator's dispatch lock through the comparison and write. */
  boolean updateDocumentIf(
      String docId, Map<String, Object> expectedFields, Map<String, Object> updates) {
    guardWritable();
    if (docId == null || docId.isBlank() || updates == null || updates.isEmpty()) {
      return false;
    }
    try {
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      return bridge.withSearcherNoRefresh(searcher ->
          readModifyWriteSource(searcher, docId, updates, new ParentSliceCache(), expectedFields));
    } catch (IOException e) {
      log.error("Failed to update document {}", docId, e);
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to update document");
    }
  }

  /** Batch update via read-modify-write. See {@link #updateDocument(String, Map)}. */
  LuceneRuntimeTypes.BatchUpdateResult updateDocumentsBatch(
      List<Map.Entry<String, Map<String, Object>>> batchUpdates) {
    guardWritable();
    if (batchUpdates == null || batchUpdates.isEmpty()) {
      return new LuceneRuntimeTypes.BatchUpdateResult(0, 0);
    }
    try {
      long tRefreshStart = System.nanoTime();
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      long tRefreshEnd = System.nanoTime();
      var result = bridge.withSearcherNoRefresh(searcher -> readModifyWriteBatch(searcher, batchUpdates));
      long tWriteEnd = System.nanoTime();
      log.info(
          "updateDocumentsBatch: refresh={}ms, withSearcher+RMW={}ms, total={}ms",
          (tRefreshEnd - tRefreshStart) / 1_000_000,
          (tWriteEnd - tRefreshEnd) / 1_000_000,
          (tWriteEnd - tRefreshStart) / 1_000_000);
      return result;
    } catch (IOException e) {
      log.error("Failed to batch-update {} documents", batchUpdates.size(), e);
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to batch update documents");
    }
  }

  private static final Set<String> ENRICHMENT_REVISION_FIELDS = Set.of(
      SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256, SchemaFields.SOURCE_SHA256,
      SchemaFields.DOC_UID, SchemaFields.IS_CHUNK, SchemaFields.PARENT_DOC_ID,
      SchemaFields.CHUNK_START_CHAR, SchemaFields.CHUNK_END_CHAR,
      SchemaFields.CHUNK_PARENT_CONTENT_SHA256, SchemaFields.COLLECTION);

  private static final Map<String, String> ENRICHMENT_STAGE_FIELDS = Map.of(
      SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_RETRY_COUNT,
      SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,
      SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT,
      SchemaFields.NER_STATUS, SchemaFields.NER_RETRY_COUNT,
      SchemaFields.VDU_STATUS, SchemaFields.VDU_RETRY_COUNT);

  private static boolean matchesEnrichmentInput(
      Map<String, String> expected, Map<String, String> current, Map<String, Object> updates) {
    for (String field : ENRICHMENT_REVISION_FIELDS) {
      if (!java.util.Objects.equals(expected.get(field), current.get(field))) return false;
    }
    for (var stage : ENRICHMENT_STAGE_FIELDS.entrySet()) {
      String status = stage.getKey(), retry = stage.getValue();
      if (!updates.containsKey(status) && !updates.containsKey(retry)) continue;
      if (!java.util.Objects.equals(expected.get(status), current.get(status))
          || !java.util.Objects.equals(expected.get(retry), current.get(retry))) return false;
    }
    return true;
  }

  LuceneRuntimeTypes.EnrichmentPublicationReceipt publishEnrichmentBatch(
      List<Map.Entry<String, Map<String, Object>>> updates,
      Map<String, DocumentFieldOps.EnrichmentInput> inputs) {
    guardWritable();
    try {
      var snap = session.snapshot;
      if (snap != null && snap.searcherManager() != null) snap.searcherManager().maybeRefreshBlocking();
      return bridge.withSearcherNoRefresh(searcher -> publishEnrichmentBatch(searcher, updates, inputs));
    } catch (IOException e) {
      return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(List.of(), List.of(),
          IndexRuntimeIOException.fromIndexOperation(e, "Failed conditional batch update"));
    } catch (RuntimeException e) {
      return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(List.of(), List.of(), e);
    }
  }

  LuceneRuntimeTypes.EnrichmentPublicationReceipt publishEnrichmentBatch(IndexSearcher searcher,
      List<Map.Entry<String, Map<String, Object>>> updates,
      Map<String, DocumentFieldOps.EnrichmentInput> inputs) {
    List<String> accepted = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    Map<String, Set<String>> correctedFailedStatuses = new HashMap<>();
    Map<String, String> storageFailures = new HashMap<>();
    try {
      var cache = new ParentSliceCache();
      cache.sparse = sparseForBatch(searcher, updates, cache);
      Map<String, Map<String, String>> parents = new HashMap<>();
      Map<String, java.util.IdentityHashMap<Map<String, String>, Boolean>> parentComparisons = new HashMap<>();
      org.apache.lucene.index.StoredFields parentStoredFields = null;
      for (var update : updates) {
        var input = inputs.get(update.getKey());
        if (input == null) { rejected.add(update.getKey()); continue; }
        if (input.parentId() != null && !input.parentId().isBlank()) {
          if (!parents.containsKey(input.parentId())) {
            if (parentStoredFields == null) parentStoredFields = searcher.storedFields();
            parents.put(input.parentId(), session.documentFieldOps.enrichmentFields(
                searcher, parentStoredFields, input.parentId(), DocumentFieldOps.PARENT_REVISION_FIELDS));
          }
          // Shared captured proof is compared once per parent, including hashless full text.
          // Different expectations for the same parent retain independent comparisons.
          if (!parentComparisons
              .computeIfAbsent(input.parentId(), ignored -> new java.util.IdentityHashMap<>())
              .computeIfAbsent(input.parentFields(), expected -> java.util.Objects.equals(expected, parents.get(input.parentId())))) {
            rejected.add(update.getKey());
            continue;
          }
          var parent = parents.get(input.parentId());
          if (parent != null) cache.putContentIfAbsent(input.parentId(), parent.get(SchemaFields.CONTENT));
        }
        var droppedVectors = new FieldMapper.DroppedVectorReport();
        try {
          if (readModifyWrite(searcher, update.getKey(), update.getValue(), cache, Map.of(), input, droppedVectors)) {
            accepted.add(update.getKey());
            if (!droppedVectors.correctedFailedStatuses().isEmpty()) {
              correctedFailedStatuses.put(update.getKey(), droppedVectors.correctedFailedStatuses());
            }
          } else {
            rejected.add(update.getKey());
          }
        } catch (SparsePreservationException failure) {
          storageFailures.put(update.getKey(), "sparse_artifact_read");
          log.warn("Withheld enrichment publication for {}: sparse artifact storage read failed", update.getKey(), failure);
        }
      }
      return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(accepted, rejected, correctedFailedStatuses, storageFailures, null);
    } catch (IOException e) {
      return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(accepted, rejected, correctedFailedStatuses, storageFailures,
          IndexRuntimeIOException.fromIndexOperation(e, "Failed conditional batch update"));
    } catch (RuntimeException e) {
      return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(accepted, rejected, correctedFailedStatuses, storageFailures, e);
    }
  }

  int updateDocumentPaths(String oldPath, String newPath, Map<String, Object> envelope) {
    guardWritable();
    if (oldPath == null || oldPath.isBlank() || newPath == null || newPath.isBlank()) {
      return 0;
    }
    try {
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      return bridge.withSearcherNoRefresh(searcher -> updateDocumentPaths(searcher, oldPath, newPath, envelope));
    } catch (IOException e) {
      log.error("Failed to update document paths {} -> {}", oldPath, newPath, e);
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to update document paths");
    }
  }

  /**
   * Tempdoc 931 §E item 10 — purges deleted-but-unmerged documents so the index's {@code maxDoc}
   * converges on its {@code numDocs}.
   *
   * <p>Tombstones are not inert: they stay in the collection statistics BM25 scores against, so
   * two indexes built from the same corpus but carrying different tombstone counts answer the same
   * query with different hit counts. A paired evaluation that wants to attribute a delta to a code
   * change has to equalize merge state first.
   *
   * <p>Does NOT commit — the caller commits through {@code CommitOps.commitAndTrack(SETTLE)} so
   * the commit stays attributed (see {@code CommitFunnelArchTest}).
   *
   * @param expungeDeletesOnly when true, only {@code forceMergeDeletes}; when false, also
   *     {@code forceMerge} down to {@code maxSegments}
   * @param maxSegments target segment count for the force-merge branch (values below 1 clamp to 1)
   */
  void settle(boolean expungeDeletesOnly, int maxSegments) {
    guardWritable();
    LifecycleSnapshot snap = session.snapshot;
    IndexWriter w = snap != null ? snap.writer() : null;
    if (w == null || !w.isOpen()) {
      throw new IndexWriterUnavailableException("IndexWriter not available (runtime not started or closed)");
    }
    try {
      w.forceMergeDeletes(true);
      if (!expungeDeletesOnly) {
        w.forceMerge(Math.max(1, maxSegments), true);
      }
      log.info(
          "settle: expungeDeletesOnly={} maxSegments={}",
          expungeDeletesOnly,
          expungeDeletesOnly ? 0 : Math.max(1, maxSegments));
    } catch (IOException e) {
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to settle index");
    }
  }

  /** Deletes all documents from the index. Used by profiling reset. */
  void deleteAll() {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null || !w.isOpen()) {
        throw new IndexWriterUnavailableException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteAll();
      session.pendingDocs.incrementAndGet();
      session.bulkDeleteEpoch.incrementAndGet(); // tempdoc 809 finding 3 — see deleteByPathPrefix
      log.info("deleteAll: all documents deleted from index");
    } catch (IOException e) {
      throw IndexRuntimeIOException.fromIndexOperation(e, "Failed to delete all documents");
    }
  }

  /**
   * Reads a keyword-field doc-value for the given global doc ID, returning {@code null} if the
   * field has no DocValues in this segment or the value isn't set for this doc. Used by
   * {@link #readModifyWrite} to restore non-stored status fields that
   * {@code storedFields().document()} cannot return.
   */
  private static String readKeywordDocValue(
      IndexSearcher searcher, int globalDocId, String field) throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(globalDocId, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = globalDocId - leaf.docBase;
    try {
      SortedDocValues dv = DocValues.getSorted(leaf.reader(), field);
      if (dv != null && dv.advanceExact(docInLeaf)) {
        return dv.lookupOrd(dv.ordValue()).utf8ToString();
      }
    } catch (IllegalStateException e) {
      // Common case: field has no SortedDocValues in this segment (not indexed / sparse) — return
      // null. Rare case: reader-state corruption, schema drift, or concurrent segment merge. We
      // return null either way so the caller falls back to the stored-field path; the debug log
      // lets future investigators distinguish the rare causes from the common one (tempdoc 393 § 2.1).
      log.debug("docValues read failed for field={} doc={}: {}", field, globalDocId, e.getMessage());
    }
    return null;
  }

  /**
   * Reads a long-typed NumericDocValues for the given global doc ID, returning {@code null} if the
   * field has no NumericDocValues in this segment or the value isn't set for this doc.
   */
  private static Long readNumericDocValue(
      IndexSearcher searcher, int globalDocId, String field) throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(globalDocId, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = globalDocId - leaf.docBase;
    try {
      NumericDocValues dv = DocValues.getNumeric(leaf.reader(), field);
      if (dv != null && dv.advanceExact(docInLeaf)) {
        return dv.longValue();
      }
    } catch (IllegalStateException e) {
      // See readKeywordDocValue — same rationale for null-return + debug log (tempdoc 393 § 2.1).
      log.debug("docValues read failed for field={} doc={}: {}", field, globalDocId, e.getMessage());
    }
    return null;
  }

  /**
   * Re-reads a doc's stored float vector for {@code field} at the given global doc ID, returning a
   * defensive copy (Lucene may reuse the backing buffer), or {@code null} if the doc has no vector
   * for this field. Lucene 10.4 exposes ordinal-based read-back: resolve the leaf via docBase, then
   * {@code getFloatVectorValues(field)} -> iterator advance -> {@code vectorValue(ord)} (derisk E1,
   * tempdoc 711). Used by the {@code preserve-reread} RMW lane.
   */
  static float[] readFloatVector(IndexSearcher searcher, int globalDocId, String field)
      throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(globalDocId, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = globalDocId - leaf.docBase;
    FloatVectorValues values = leaf.reader().getFloatVectorValues(field);
    if (values == null) {
      return null; // no float-vector field in this segment (doc indexed without a vector)
    }
    KnnVectorValues.DocIndexIterator iter = values.iterator();
    if (iter.advance(docInLeaf) != docInLeaf) {
      return null; // this doc has no vector for the field
    }
    float[] v = values.vectorValue(iter.index());
    return v == null ? null : v.clone();
  }

  /** Pre-classified document for batch operations. */
  record DocWork(String id, Document doc, Map<String, Object> fields) {}
}
