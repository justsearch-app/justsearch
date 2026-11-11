/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Whitelisted text artifacts for canonical replacement. Never exports an old metadata row. */
final class TextArtifactReuse {
  private static final List<String> FORMAT = List.of(SchemaFields.MIME, SchemaFields.MIME_BASE, SchemaFields.FILE_KIND);
  private static final List<String> LAYOUT = List.of(SchemaFields.DOC_UID, SchemaFields.IS_CHUNK,
      SchemaFields.PARENT_DOC_ID, SchemaFields.CHUNK_INDEX, SchemaFields.CHUNK_TOTAL,
      SchemaFields.CHUNK_START_CHAR, SchemaFields.CHUNK_END_CHAR, SchemaFields.CHUNK_START_LINE,
      SchemaFields.CHUNK_END_LINE, SchemaFields.CHUNK_HEADING_TEXT, SchemaFields.CHUNK_HEADING_LEVEL);
  private static final List<String> ENTITIES = List.of(SchemaFields.ENTITY_PERSONS_RAW,
      SchemaFields.ENTITY_ORGANIZATIONS_RAW, SchemaFields.ENTITY_LOCATIONS_RAW);
  private static final Set<String> STAGES = Set.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_RETRY_COUNT,
      SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,
      SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT, SchemaFields.NER_STATUS, SchemaFields.NER_RETRY_COUNT);

  private TextArtifactReuse() {}

  static List<IndexDocument> attach(RuntimeSession session, WritePathOps writes,
      IndexDocument parent, List<IndexDocument> children, boolean documentEmbeddingOnly) throws IOException {
    List<IndexDocument> fresh = new ArrayList<>(children.size() + 1);
    fresh.add(parent); fresh.addAll(children);
    Map<String, String> recorded = session.commitOps.artifactReuseMetadata();
    if (recorded.isEmpty()) return fresh;
    String parentId = (String) parent.fields().get(SchemaFields.DOC_ID);
    // New ingestion must not pay for a writer-reader reopen per file. A negative admission
    // observation is conservative; every positive still gets the current serialized proof below.
    var present = session.commitOps.withAdmissionSearcher(new SearcherBridge(session), searcher ->
        searcher.count(new TermQuery(new Term(session.fieldMapper.idField(), parentId))) > 0);
    if (!present.value()) return fresh;
    // One current reader, under coordinator serialization, before any parent or child is replaced.
    try (var reader = DirectoryReader.open(session.snapshot.writer())) {
      var searcher = new IndexSearcher(reader);
      var hit = searcher.search(new TermQuery(new Term(session.fieldMapper.idField(), parentId)), 2);
      if (hit.scoreDocs.length != 1) return fresh;
      int parentDoc = hit.scoreDocs[0].doc;
      var assessment = session.chunkCompleteness.assess(searcher, parentDoc, parentId);
      if (!assessment.complete() || assessment.backfill() != null) return fresh;
      Document oldParent = searcher.storedFields().document(parentDoc);
      String oldText = single(oldParent, SchemaFields.CONTENT);
      if ("true".equals(single(oldParent, SchemaFields.IS_CHUNK))
          || single(oldParent, SchemaFields.PARENT_DOC_ID) != null
          || oldText == null || !ChunkParentRevision.sha256Hex(oldText).equals(single(oldParent, SchemaFields.CONTENT_SHA256))
          || !same(oldParent, parent.fields(), FORMAT)
          || !Objects.equals(single(oldParent, SchemaFields.DOC_UID), parent.fields().get(SchemaFields.DOC_UID))
          || !Objects.equals(single(oldParent, SchemaFields.CHUNK_TOTAL), String.valueOf(children.size()))) return fresh;

      var metadataSource = session.metadataSourceSupplier.get();
      if (metadataSource == null) return fresh;
      var expected = metadataSource.build();
      if (expected == null) return fresh;
      JsonNode storedInputs = inputs(recorded.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
      JsonNode currentInputs = inputs(expected.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
      if (storedInputs == null || currentInputs == null
          || !storedInputs.has("chunking") || !storedInputs.get("chunking").equals(currentInputs.get("chunking"))) return fresh;
      boolean dense = modelMatches("embedding", recorded, expected, storedInputs, currentInputs);
      boolean sparse = modelMatches("splade", recorded, expected, storedInputs, currentInputs);
      boolean ner = modelMatches("ner", recorded, expected, storedInputs, currentInputs);
      // BGE-M3's dense output is produced by the sparse model. Ordinary dense output also needs
      // the earned embedding attestation, unless this runtime opened a provably empty generation.
      if ("splade".equals(metadataSource.denseModelRole())) dense = sparse;
      else dense &= "embedding".equals(metadataSource.denseModelRole())
          && (session.commitOps.artifactReuseStartedEmpty()
              || storedInputs.path("embedding_model_sha256").asText().equals(recorded.get("embedding_model_sha256")));
      if (Objects.equals(oldText, parent.fields().get(SchemaFields.CONTENT))) {
        fresh.set(0, carry(session, writes, searcher, parentDoc, oldParent, parent, dense,
            sparse && !documentEmbeddingOnly, ner && !documentEmbeddingOnly));
      }
      if (documentEmbeddingOnly) return fresh;
      if (children.isEmpty() || !(dense || sparse)) return fresh;
      var hits = searcher.search(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentId)), children.size() + 1);
      if (hits.scoreDocs.length != children.size()) return freshWithoutChildren(fresh, children);
      var byOrdinal = new HashMap<String, Integer>();
      var rows = new HashMap<Integer, Document>();
      var childIds = new java.util.HashSet<String>();
      for (var childHit : hits.scoreDocs) {
        var row = searcher.storedFields().document(childHit.doc);
        String id = single(row, session.fieldMapper.idField());
        if (id == null || id.isBlank() || !childIds.add(id)) return freshWithoutChildren(fresh, children);
        if (byOrdinal.put(single(row, SchemaFields.CHUNK_INDEX), childHit.doc) != null) return freshWithoutChildren(fresh, children);
        rows.put(childHit.doc, row);
      }
      for (int i = 0; i < children.size(); i++) {
        IndexDocument child = children.get(i);
        Integer doc = byOrdinal.get(Integer.toString(i));
        if (doc == null) continue;
        Document old = rows.get(doc);
        if (!same(old, child.fields(), LAYOUT) || !same(old, child.fields(), FORMAT)
            || !Objects.equals(single(old, SchemaFields.CHUNK_PARENT_CONTENT_SHA256), single(oldParent, SchemaFields.CONTENT_SHA256))) continue;
        String content = slice(old, oldText);
        if (content == null || !content.equals(child.fields().get(SchemaFields.CHUNK_CONTENT))) continue;
        fresh.set(i + 1, carry(session, writes, searcher, doc, old, child, dense, sparse, false));
      }
      return fresh;
    }
  }

  private static List<IndexDocument> freshWithoutChildren(List<IndexDocument> fresh, List<IndexDocument> children) {
    var result = new ArrayList<IndexDocument>(); result.add(fresh.getFirst()); result.addAll(children); return result;
  }

  private static JsonNode inputs(Object json) {
    if (!(json instanceof String text)) return null;
    if (!IndexFingerprint.isReadableInputs(text)) return null;
    try { return JsonMapper.builder().build().readTree(text); }
    catch (RuntimeException malformed) { return null; }
  }

  private static boolean modelMatches(String role, Map<String, String> stored, Map<String, Object> expected,
      JsonNode oldInputs, JsonNode newInputs) {
    String graph = role + "_model_sha256", assets = role + "_model_assets_sha256";
    return oldInputs.path(graph).isTextual() && IndexFingerprint.isWellFormedDigest(oldInputs.path(graph).asText())
        && oldInputs.path(graph).equals(newInputs.path(graph))
        // Match the product's graph-only adoption rule. A known companion mismatch still
        // invalidates the stage; historical provenance before adoption is handled separately.
        && expected.get(assets) instanceof String currentAssets
        && IndexFingerprint.isWellFormedDigest(currentAssets)
        && (!stored.containsKey(assets) || currentAssets.equals(stored.get(assets)));
  }

  private static boolean same(Document old, Map<String, Object> fresh, List<String> names) {
    for (String name : names) {
      Object value = fresh.get(name);
      if (!Objects.equals(single(old, name), value == null ? null : value.toString())) return false;
      if (old.getFields(name).length > 1) return false;
    }
    return true;
  }

  private static String single(Document document, String name) {
    var values = document.getFields(name);
    if (values.length != 1) return null;
    var value = values[0];
    return value.stringValue() != null ? value.stringValue()
        : value.numericValue() == null ? null : value.numericValue().toString();
  }

  private static String slice(Document row, String parent) {
    try {
      int start = Integer.parseInt(single(row, SchemaFields.CHUNK_START_CHAR));
      int end = Integer.parseInt(single(row, SchemaFields.CHUNK_END_CHAR));
      return start < 0 || end <= start || end > parent.length()
          || ChunkSplitter.bisectsSurrogatePair(parent, start) || ChunkSplitter.bisectsSurrogatePair(parent, end)
          ? null : parent.substring(start, end);
    } catch (NumberFormatException malformed) { return null; }
  }

  private static IndexDocument carry(RuntimeSession session, WritePathOps writes, IndexSearcher searcher,
      int doc, Document old, IndexDocument fresh, boolean dense, boolean sparse, boolean ner) throws IOException {
    var fields = new HashMap<>(fresh.fields());
    var state = new HashMap<String, String>();
    for (String name : STAGES) {
      String value = single(old, name); if (value != null) state.put(name, value);
    }
    session.readPathOps.projectDocValues(searcher, doc, STAGES, state);
    boolean chunk = "true".equals(fields.get(SchemaFields.IS_CHUNK));
    String vector = chunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR;
    String status = chunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS;
    String retry = chunk ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT;
    if (dense && fields.containsKey(status) && validState(old, state, status, retry)) {
      if ("COMPLETED".equals(state.get(status))) {
        float[] value = WritePathOps.readFloatVector(searcher, doc, vector);
        var definition = session.fieldMapper.fieldDef(vector);
        if (validVector(value, definition == null ? null : definition.vectorDim)) {
          fields.put(vector, value); carryState(fields, state, status, retry);
        }
      } else carryPending(fields, state, status, retry);
    }
    if (sparse && fields.containsKey(SchemaFields.SPLADE_STATUS)
        && validState(old, state, SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT)) {
      status = SchemaFields.SPLADE_STATUS; retry = SchemaFields.SPLADE_RETRY_COUNT;
      String value = state.get(status);
      if ("COMPLETED".equals(value) || "COMPLETED_EMPTY".equals(value)) {
        Map<String, Float> features = sparse(writes, searcher, doc, old);
        if (features != null && ("COMPLETED".equals(value) != features.isEmpty())) {
          if (!features.isEmpty()) fields.put(SchemaFields.SPLADE, features);
          carryState(fields, state, status, retry);
        }
      } else carryPending(fields, state, status, retry);
    }
    if (ner && fields.containsKey(SchemaFields.NER_STATUS)
        && validState(old, state, SchemaFields.NER_STATUS, SchemaFields.NER_RETRY_COUNT)) {
      status = SchemaFields.NER_STATUS; retry = SchemaFields.NER_RETRY_COUNT;
      var entities = new HashMap<String, Object>(); boolean valid = true;
      for (String name : ENTITIES) {
        var values = new ArrayList<String>();
        for (var field : old.getFields(name)) {
          String value = field.stringValue();
          if (value == null || value.isBlank()) valid = false;
          else values.add(value);
        }
        if (!values.isEmpty()) entities.put(name, values);
      }
      String value = state.get(status);
      if (valid && (("COMPLETED".equals(value) && !entities.isEmpty())
          || ("COMPLETED_EMPTY".equals(value) && entities.isEmpty()))) {
        fields.putAll(entities); carryState(fields, state, status, retry);
      } else carryPending(fields, state, status, retry);
    }
    return new IndexDocument(fields);
  }

  private static boolean validVector(float[] vector, Integer dimension) {
    if (vector == null || dimension == null || vector.length != dimension) return false;
    double norm = 0;
    for (float value : vector) { if (!Float.isFinite(value)) return false; norm += (double) value * value; }
    return norm > 0 && Double.isFinite(norm);
  }

  private static Map<String, Float> sparse(WritePathOps writes, IndexSearcher searcher, int doc, Document old)
      throws IOException {
    var payload = old.getFields(SparsePayload.FIELD);
    if (payload.length > 0) {
      if (payload.length != 1 || payload[0].binaryValue() == null) return null;
      try { return SparsePayload.decode(payload[0].binaryValue()); }
      catch (IOException malformed) { return null; }
    }
    var legacy = writes.sparseFeatures(searcher, Set.of(doc));
    if (legacy.failures().containsKey(doc)) throw legacy.failures().get(doc);
    var features = legacy.features().getOrDefault(doc, Map.of());
    for (float weight : features.values()) {
      if (!Float.isFinite(weight) || weight < Float.MIN_NORMAL || weight > 64.0f) return null;
    }
    return features;
  }

  private static void carryPending(Map<String, Object> fields, Map<String, String> old, String status, String retry) {
    if ("PENDING".equals(old.get(status))) carryState(fields, old, status, retry);
  }

  private static boolean validState(Document row, Map<String, String> state, String status, String retry) {
    var statuses = row.getFields(status); var retries = row.getFields(retry);
    if (statuses.length > 1 || retries.length > 1) return false;
    if (statuses.length == 1 && (statuses[0].stringValue() == null
        || !statuses[0].stringValue().equals(state.get(status)))) return false;
    if (retries.length == 1 && (retries[0].numericValue() == null
        || !retries[0].numericValue().toString().equals(state.get(retry)))) return false;
    String count = state.get(retry);
    if (count == null) return true;
    try { return Integer.parseInt(count) >= 0; }
    catch (NumberFormatException malformed) { return false; }
  }

  private static void carryState(Map<String, Object> fields, Map<String, String> old, String status, String retry) {
    fields.put(status, old.get(status));
    String count = old.get(retry);
    if (count != null) fields.put(retry, count);
  }
}
