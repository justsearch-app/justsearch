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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DocValues;
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
import org.apache.lucene.search.PrefixQuery;
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

  /**
   * Core single-document write. Caller is responsible for guards and queue-depth accounting.
   *
   * @param fields the document fields (already validated)
   * @param droppedVectors accumulator for vector fields dropped by the mapper (tempdoc 931 §C.3),
   *     or null when the caller does not count them
   */
  void indexDocument(Map<String, Object> fields, FieldMapper.DroppedVectorReport droppedVectors) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException(
            "IndexWriter not available (runtime not started or closed)");
      }
      String idValue = asString(fields.get(idField));
      if (idValue == null || idValue.isBlank()) {
        throw new IllegalArgumentException("IndexDocument missing required id field " + idField);
      }
      boolean hardDelete = asBoolean(fields.get(session.hardDeleteField));
      if (hardDelete) {
        w.deleteDocuments(new Term(idField, idValue));
        TelemetryEvents te = session.telemetryEvents;
        if (te != null) te.onHardDelete();
        return;
      }
      Document luceneDoc = session.fieldMapper.toDocument(fields, droppedVectors);
      boolean softDelete = asBoolean(fields.get(session.softDeleteField));
      if (softDelete) {
        appendSoftDeleteMaintenanceFields(luceneDoc, fields);
        w.softUpdateDocument(
            new Term(idField, idValue),
            luceneDoc,
            new NumericDocValuesField(session.softDeleteField, 1));
      } else {
        w.updateDocument(new Term(idField, idValue), luceneDoc);
      }
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to add document", e);
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
      if (w == null) {
        throw new IllegalStateException(
            "IndexWriter not available (runtime not started or closed)");
      }
      if (!hardDeletes.isEmpty()) {
        Term[] terms = hardDeletes.stream().map(id -> new Term(idField, id)).toArray(Term[]::new);
        w.deleteDocuments(terms);
      }
      for (DocWork work : updates) {
        w.updateDocument(new Term(idField, work.id()), work.doc());
      }
      for (DocWork work : softDeletes) {
        appendSoftDeleteMaintenanceFields(work.doc(), work.fields());
        w.softUpdateDocument(
            new Term(idField, work.id()),
            work.doc(),
            new NumericDocValuesField(session.softDeleteField, 1));
      }
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to apply batch", e);
    }
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
      if (w == null) {
        throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteDocuments(new Term(idField, id));
      session.pendingDocs.incrementAndGet();
      TelemetryEvents te = session.telemetryEvents;
      if (te != null) te.onHardDelete();
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to delete document", e);
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
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete document and chunks", e);
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
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete chunks for parent", e);
    }
  }

  /**
   * Deletes all documents (parents and chunks) under the given path prefix. No pendingDocs/queue
   * accounting — caller may handle this differently.
   */
  void deleteByPathPrefix(String pathPrefix) {
    deleteByPathPrefixExcludingAcceptedSurvivors(pathPrefix, List.of());
  }

  void deleteByPathPrefixExcludingAcceptedSurvivors(String pathPrefix, List<String> fileIds) {
    // Normalize the prefix (case-insensitive on Windows, ensure trailing separator)
    String normalized = normalizePathPrefix(pathPrefix);
    try {
      // Use 'path' field for prefix matching (works for both parent docs and chunks)
      Query query = QueryFilterBuilder.excludingAcceptedSurvivors(
          new PrefixQuery(new Term(SchemaFields.PATH, normalized)), fileIds, List.of());
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteDocuments(query);
      // Tempdoc 809 finding 3: publish the bulk-deletion signal AFTER the delete is submitted, so
      // any reader that observes the new epoch is guaranteed the deletion is already in the writer.
      session.bulkDeleteEpoch.incrementAndGet();
      log.info("deleteByPathPrefix: deletion submitted for path prefix: {}", normalized);
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete by path prefix", e);
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
    if (w == null) {
      throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
    }
    Query query = QueryFilterBuilder.excludingAcceptedSurvivors(
        new TermQuery(new Term(SchemaFields.COLLECTION, collection)), fileIds, List.of());
    int matched = 0;
    org.apache.lucene.search.SearcherManager mgr = snap.searcherManager();
    try {
      if (mgr != null) {
        IndexSearcher searcher = mgr.acquire();
        try {
          matched = searcher.count(query);
        } finally {
          mgr.release(searcher);
        }
      }
      w.deleteDocuments(query);
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
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete by collection", e);
    }
  }

  /**
   * Read-modify-write: loads existing stored fields, applies the caller's updates, and re-indexes.
   * Non-stored, non-docValues data-bearing fields the caller omits (vectors, SPLADE) are preserved
   * per their declared {@code rmwPolicy} in the field catalog (tempdoc 711) — a subset-field RMW can
   * no longer silently destroy the rest. The caller provides a leased searcher (via
   * ReadPathOps.withSearcher) so it stays valid for the read and the subsequent write.
   *
   * @return true if the document was found and updated, false if not found
   */
  boolean readModifyWrite(IndexSearcher searcher, String docId, Map<String, Object> updates)
      throws IOException {
    return readModifyWrite(searcher, docId, updates, new ParentSliceCache());
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
    var topDocs = searcher.search(new TermQuery(new Term(idField, docId)), 1);
    if (topDocs.scoreDocs.length == 0) {
      log.debug("readModifyWrite: document not found: {}", docId);
      return false;
    }
    int globalDocId = topDocs.scoreDocs[0].doc;

    // Load all stored fields from existing document, accumulating multi-valued fields into Lists
    Document oldDoc = searcher.storedFields().document(globalDocId);
    Map<String, Object> fields = new HashMap<>();
    for (IndexableField field : oldDoc.getFields()) {
      String name = field.name();
      Object val = field.stringValue() != null ? field.stringValue() : field.numericValue();
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
    applyRmwPolicies(searcher, globalDocId, updates, fields);

    // Apply updates (overwrites existing values, including anything the engine restored)
    fields.putAll(updates);

    // Write-time status/artifact contract (tempdoc 798). The merged map — existing stored fields
    // union rmwPolicy-preserved fields union the caller's updates — is exactly what will be
    // indexed, so it is the only place the RMW lane can tell a truthful COMPLETED from a lie.
    // IndexingCoordinator.validate() never runs here (only indexSingle/indexBatch call it), and
    // readModifyWriteBatch / updateDocumentPaths funnel through this method, so this one call
    // covers every partial-update path.
    StatusArtifactContract.enforce(session, fields, "read-modify-write:" + docId);

    // Re-index with updated fields
    FieldMapper.DroppedVectorReport droppedVectors = new FieldMapper.DroppedVectorReport();
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
    if (rmwSnap == null || rmwSnap.writer() == null) {
      throw new IllegalStateException("IndexWriter not available during read-modify-write");
    }
    rmwSnap.writer().updateDocument(new Term(idField, docId), newDoc);
    log.debug("readModifyWrite: updated document: {}", docId);
    return true;
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

  /**
   * Applies each catalog field's declared RMW disposition (tempdoc 711) for fields the caller
   * omitted: {@link FieldMapper#RMW_PRESERVE_REREAD} re-reads and carries the field forward;
   * {@code reset-status:<statusField>} drives the named status field so a backfill re-derives data
   * that cannot be cheaply re-read.
   */
  private void applyRmwPolicies(
      IndexSearcher searcher, int globalDocId, Map<String, Object> updates, Map<String, Object> fields)
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
   * reset-status lane: the field's data cannot be cheaply re-read (SPLADE weights live in postings),
   * so on drop we drive its declared status field. A COMPLETED status is downgraded to PENDING (the
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
    for (Map.Entry<String, Map<String, Object>> entry : batchUpdates) {
      long t0 = System.nanoTime();
      if (readModifyWrite(searcher, entry.getKey(), entry.getValue(), parentCache)) {
        updated++;
      } else {
        notFound++;
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
  int updateDocumentPaths(IndexSearcher searcher, String oldPath, String newPath)
      throws IOException {
    // 1. Update parent document: DOC_ID, PATH, FILENAME. A MOVE/RENAME does not change content, so
    // the vector, SPLADE, and NER enrichment stay valid; the RMW preservation engine (tempdoc 711)
    // carries the vector forward and the stored NER fields survive — no re-queue needed.
    // OS-independent: newPath may carry Windows (\) or POSIX (/) separators regardless of the
    // host OS. Paths.get() would treat a Windows path as a single segment on Linux (tempdoc 668;
    // same pattern as LuceneRuntimeUtils).
    int lastSep = Math.max(newPath.lastIndexOf('/'), newPath.lastIndexOf('\\'));
    String newFilename = lastSep >= 0 ? newPath.substring(lastSep + 1) : newPath;
    ParentSliceCache parentCache = new ParentSliceCache();
    boolean parentUpdated =
        readModifyWrite(
            searcher,
            oldPath,
            Map.ofEntries(
                Map.entry(SchemaFields.DOC_ID, newPath),
                Map.entry(SchemaFields.PATH, newPath),
                Map.entry(SchemaFields.FILENAME, newFilename)),
            parentCache);
    if (!parentUpdated) {
      log.debug("updateDocumentPaths: parent document not found: {}", oldPath);
      return 0;
    }
    parentCache.alias(oldPath, newPath);

    // 2. Find all chunks for the old parent path
    BooleanQuery chunkQuery =
        new BooleanQuery.Builder()
            .add(
                new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, oldPath)),
                BooleanClause.Occur.FILTER)
            .add(
                new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.FILTER)
            .build();
    var chunkDocs = searcher.search(chunkQuery, 10_000);

    // 3. Update each chunk's PARENT_DOC_ID and PATH (chunk DOC_ID stays as UUID). The chunk_vector
    // is carried forward by the RMW preservation engine (tempdoc 711); no re-embed re-queue needed.
    int count = 1; // parent
    for (var sd : chunkDocs.scoreDocs) {
      String chunkId = searcher.storedFields().document(sd.doc).get(SchemaFields.DOC_ID);
      readModifyWrite(
          searcher,
          chunkId,
          Map.of(
              SchemaFields.PARENT_DOC_ID, newPath,
              SchemaFields.PATH, newPath),
          parentCache);
      count++;
    }

    log.info(
        "updateDocumentPaths: {} -> {} ({} parent + {} chunks)",
        oldPath,
        newPath,
        1,
        chunkDocs.scoreDocs.length);
    return count;
  }

  private void guardWritable() {
    // Tempdoc 406 Phase 4b (A1): replaced state-machine check with snapshot/writer
    // null check. WritePathOps is only reachable through RunningRuntime, which by
    // construction has an open writer. Snapshot null means the runtime was closed.
    // Tempdoc 406 Gap G: also reject writes during drain — caller should retry on the
    // upgraded holder reference (UNAVAILABLE on the in-process port layer).
    if (session.draining) {
      throw new IllegalStateException(
          "Runtime is draining; retry on the new instance via the supplier holder");
    }
    LifecycleSnapshot snap = session.snapshot;
    if (snap == null || snap.writer() == null) {
      throw new IllegalStateException("Index is not writable (writer unavailable)");
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
    guardWritable();
    if (docId == null || docId.isBlank() || updates == null || updates.isEmpty()) {
      return false;
    }
    try {
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      return bridge.withSearcherNoRefresh(searcher -> readModifyWrite(searcher, docId, updates));
    } catch (IOException e) {
      log.error("Failed to update document {}", docId, e);
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to update document", e);
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
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to batch update documents", e);
    }
  }

  int updateDocumentPaths(String oldPath, String newPath) {
    guardWritable();
    if (oldPath == null || oldPath.isBlank() || newPath == null || newPath.isBlank()) {
      return 0;
    }
    try {
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      return bridge.withSearcherNoRefresh(searcher -> updateDocumentPaths(searcher, oldPath, newPath));
    } catch (IOException e) {
      log.error("Failed to update document paths {} -> {}", oldPath, newPath, e);
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to update document paths", e);
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
    if (w == null) {
      throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
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
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to settle index", e);
    }
  }

  /** Deletes all documents from the index. Used by profiling reset. */
  void deleteAll() {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteAll();
      session.bulkDeleteEpoch.incrementAndGet(); // tempdoc 809 finding 3 — see deleteByPathPrefix
      log.info("deleteAll: all documents deleted from index");
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete all documents", e);
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
  private static float[] readFloatVector(IndexSearcher searcher, int globalDocId, String field)
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
