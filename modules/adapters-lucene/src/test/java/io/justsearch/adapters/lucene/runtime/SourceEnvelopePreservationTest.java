/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceEnvelopePreservationTest extends LuceneExecutorTestBase {
  private static final List<String> ENVELOPE = List.of("origin_id", "storage_status", "storage_mount",
      "storage_key", "envelope_version");
  private static final Map<String, Object> VALUES = Map.of("origin_id", "49c60c38-356d-42f1-b456-ea271314149d",
      "storage_status", "known", "storage_mount", "/data", "storage_key", "aa-bb:ext4:/", "envelope_version", "1");
  @TempDir Path directory;

  @Test
  void catalogKeepsEnvelopeThroughEnrichmentAndMetadataPatches() throws Exception {
    var catalog = new JustSearchConfigurationLoader().loadFieldCatalog();
    for (String name : ENVELOPE) {
      FieldDef field = catalog.field(name);
      assertNotNull(field, "optional envelope field must be catalogued: " + name);
      assertEquals("keyword", field.type()); assertTrue(field.stored());
      assertFalse(field.docValues()); assertTrue(field.roles().isEmpty());
    }
    try (var runtime = IndexSchema.fromCatalog(catalog).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      var fields = new HashMap<String, Object>(VALUES);
      fields.putAll(Map.of("doc_id", "source", "doc_uid", "source-uid", "content", "fixed lexical content",
          "path", "/data/source.txt", "collection", "documents", "ner_status", "PENDING"));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      for (var patch : List.of(Map.<String, Object>of("ner_status", "COMPLETED_EMPTY"),
          Map.<String, Object>of("path", "/data/moved.txt"))) {
        assertTrue(runtime.indexingCoordinator().updateDocument("source", patch));
        runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
        try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
          var searcher = new IndexSearcher(reader);
          int doc = searcher.search(new TermQuery(new Term("doc_id", "source")), 1).scoreDocs[0].doc;
          var stored = searcher.storedFields().document(doc);
          VALUES.forEach((name, value) -> assertEquals(value, stored.get(name)));
          assertEquals("fixed lexical content", stored.get("content"));
          assertEquals("documents", stored.get("collection"));
          assertEquals(1, searcher.count(new TermQuery(new Term("content", "lexical"))));
          for (var leaf : reader.leaves()) for (String name : ENVELOPE) {
            var info = leaf.reader().getFieldInfos().fieldInfo(name);
            if (info != null) {
              assertEquals(IndexOptions.NONE, info.getIndexOptions());
              assertEquals(DocValuesType.NONE, info.getDocValuesType());
            }
          }
        }
      }
    }
    try (var runtime = IndexSchema.fromCatalog(catalog).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        var searcher = new IndexSearcher(reader);
        int doc = searcher.search(new TermQuery(new Term("doc_id", "source")), 1).scoreDocs[0].doc;
        var stored = searcher.storedFields().document(doc);
        VALUES.forEach((name, value) -> assertEquals(value, stored.get(name)));
      }
    }
  }

  @Test
  void envelopeAdditionsUseBothExistingParityCertificationPaths() throws Exception {
    var json = new tools.jackson.databind.ObjectMapper();
    String legacy = json.writeValueAsString(Map.of("fields", List.of(), "models", Map.of("dense", "unchanged")));
    var additions = ENVELOPE.stream().map(name -> Map.of("id", name, "type", "keyword", "stored", true,
        "doc_values", false, "multi_valued", false, "roles", List.of())).toList();
    String candidate = json.writeValueAsString(Map.of("fields", additions, "models", Map.of("dense", "unchanged")));
    assertTrue(ParityDiagnostics.diff(metadata(legacy), new HashMap<>(metadata(candidate)), 12, List.of()).isEmpty(),
        "certified stored keyword additions must open populated indexes without rebuilding");
    assertTrue(ParityDiagnostics.diff(Map.of(IndexFingerprint.COMMIT_META_INPUTS_KEY, legacy),
        Map.of(IndexFingerprint.COMMIT_META_INPUTS_KEY, candidate), 12, List.of()).isEmpty());
    for (String incompatible : List.of(candidate.replace("keyword", "long"),
        candidate.replace("unchanged", "changed"))) {
      assertFalse(ParityDiagnostics.diff(metadata(candidate), new HashMap<>(metadata(incompatible)), 12, List.of()).isEmpty());
    }
    var uncertified = new HashMap<String, Object>(metadata(candidate));
    uncertified.put(IndexFingerprint.COMMIT_META_KEY, "f".repeat(64));
    assertFalse(ParityDiagnostics.diff(metadata(legacy), uncertified, 12, List.of()).isEmpty());
  }

  @Test
  void malformedEnvelopeNeverDecodesKnown() throws Exception {
    var type = assertDoesNotThrow(() -> Class.forName("io.justsearch.indexing.SourceEnvelope"));
    var decode = type.getMethod("decode", Map.class);
    var known = (java.util.Optional<?>) assertDoesNotThrow(() -> decode.invoke(null, VALUES));
    assertTrue(known.isPresent());
    assertEquals("KNOWN", type.getMethod("storageStatus").invoke(known.orElseThrow()).toString());
    var originUnknown = new HashMap<>(VALUES);
    originUnknown.remove("origin_id");
    var observed = (java.util.Optional<?>) assertDoesNotThrow(() -> decode.invoke(null, originUnknown));
    assertTrue(observed.isPresent(), "versioned envelopes without an origin are distinct from legacy");
    assertNull(type.getMethod("originId").invoke(observed.orElseThrow()));
    assertEquals("KNOWN", type.getMethod("storageStatus").invoke(observed.orElseThrow()).toString());
    var malformed = new ArrayList<Map<String, Object>>();
    malformed.add(Map.of());
    for (String name : ENVELOPE) {
      // A versioned envelope may omit origin_id for admissions without a durable declaration.
      if (!name.equals("origin_id")) {
        var missing = new HashMap<>(VALUES); missing.remove(name); malformed.add(missing);
      }
      var invalid = new HashMap<>(VALUES); invalid.put(name, "malformed"); malformed.add(invalid);
    }
    for (var fields : malformed) {
      var result = (java.util.Optional<?>) assertDoesNotThrow(() -> decode.invoke(null, fields));
      if (result.isPresent()) assertNotEquals("KNOWN", type.getMethod("storageStatus").invoke(result.orElseThrow()).toString());
    }
    for (String status : List.of("unknown", "not_observed")) {
      var fields = new HashMap<>(VALUES); fields.put("storage_status", status);
      var result = (java.util.Optional<?>) decode.invoke(null, fields);
      assertEquals(status.toUpperCase(java.util.Locale.ROOT), type.getMethod("storageStatus").invoke(result.orElseThrow()).toString());
    }
  }

  @Test
  void populatedLegacyIndexOpensAndStampsNewFingerprintOnlyAfterSuccessfulCommit() throws Exception {
    var json = new tools.jackson.databind.ObjectMapper();
    var configured = new JustSearchConfigurationLoader().loadFieldCatalog();
    var legacyFields = configured.fields().stream().filter(field -> !ENVELOPE.contains(field.id())).toList();
    var candidateFields = new ArrayList<>(legacyFields);
    for (String name : ENVELOPE) candidateFields.add(new FieldDef(name, "keyword", true, false,
        List.of(), null, null, false));
    var legacyCatalog = new FieldCatalogDef(configured.version(), legacyFields);
    var candidateCatalog = new FieldCatalogDef(configured.version(), candidateFields);
    var common = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource().build();
    var legacyInputs = (tools.jackson.databind.node.ObjectNode) json.readTree(
        common.get(IndexFingerprint.COMMIT_META_INPUTS_KEY).toString());
    var fields = legacyInputs.putArray("fields");
    var originalFields = json.readTree(common.get(IndexFingerprint.COMMIT_META_INPUTS_KEY).toString()).path("fields");
    for (var field : originalFields) if (!ENVELOPE.contains(field.path("id").asText())) fields.add(field);
    var candidateInputs = legacyInputs.deepCopy();
    for (String name : ENVELOPE) candidateInputs.withArray("fields").add(json.valueToTree(Map.of(
        "id", name, "type", "keyword", "stored", true, "doc_values", false,
        "multi_valued", false, "roles", List.of())));
    var oldMetadata = new HashMap<String, Object>(common);
    oldMetadata.putAll(metadata(json.writeValueAsString(legacyInputs)));
    var newMetadata = new HashMap<String, Object>(common);
    newMetadata.putAll(metadata(json.writeValueAsString(candidateInputs)));
    var validator = new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator();
    try (var runtime = IndexSchema.fromCatalog(legacyCatalog, () -> oldMetadata, validator).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of("doc_id", "legacy",
          "doc_uid", "legacy-uid", "content", "stable lexical phrase", "collection", "documents")));
      runtime.commitOps().commitAndTrack();
    }
    var failCommit = new java.util.concurrent.atomic.AtomicBoolean();
    io.justsearch.indexing.runtime.CommitMetadataValidator rejecting = value -> {
      if (failCommit.get()) throw new IllegalArgumentException("injected metadata rejection");
      validator.validate(value);
    };
    var candidateSchema = IndexSchema.fromCatalog(candidateCatalog, () -> newMetadata, rejecting);
    try (var runtime = assertDoesNotThrow(() -> candidateSchema.atPath(directory)
        .withIndexOpenGuard(new IndexMetadataParityGuard(() -> directory, () -> newMetadata, List::of))
        .withExecutorRegistrations(testLuceneExecutors()).open(), "optional provenance must not require a rebuild")) {
      runtime.commitOps().stopCommitTimer();
      assertEquals(oldMetadata.get(IndexFingerprint.COMMIT_META_KEY), committedFingerprint());
      assertTrue(runtime.indexingCoordinator().updateDocument("legacy", Map.of("path", "/moved/source.txt")));
      failCommit.set(true);
      assertThrows(RuntimeException.class, () -> runtime.commitOps().commitAndTrack());
      assertEquals(oldMetadata.get(IndexFingerprint.COMMIT_META_KEY), committedFingerprint());
      failCommit.set(false);
      runtime.commitOps().commitAndTrack();
      assertEquals(newMetadata.get(IndexFingerprint.COMMIT_META_KEY), committedFingerprint());
    } finally { failCommit.set(false); }
    try (var runtime = candidateSchema.atPath(directory)
        .withIndexOpenGuard(new IndexMetadataParityGuard(() -> directory, () -> newMetadata, List::of))
        .withExecutorRegistrations(testLuceneExecutors()).open();
        var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
      var searcher = new IndexSearcher(reader);
      assertEquals(1, reader.numDocs());
      assertEquals(1, searcher.count(new TermQuery(new Term("content", "lexical"))));
      int doc = searcher.search(new TermQuery(new Term("doc_id", "legacy")), 1).scoreDocs[0].doc;
      var stored = searcher.storedFields().document(doc);
      assertEquals("legacy-uid", stored.get("doc_uid"));
      assertEquals("stable lexical phrase", stored.get("content"));
      assertEquals("documents", stored.get("collection"));
      assertNull(stored.get("origin_id"), "opening a legacy index does not backfill provenance");
    }
  }

  private String committedFingerprint() throws Exception {
    try (var fs = org.apache.lucene.store.FSDirectory.open(directory); var reader = DirectoryReader.open(fs)) {
      return reader.getIndexCommit().getUserData().get(IndexFingerprint.COMMIT_META_KEY);
    }
  }

  private static Map<String, String> metadata(String inputs) throws Exception {
    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(inputs.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return Map.of(IndexFingerprint.COMMIT_META_KEY, digest, IndexFingerprint.COMMIT_META_INPUTS_KEY, inputs);
  }
}
