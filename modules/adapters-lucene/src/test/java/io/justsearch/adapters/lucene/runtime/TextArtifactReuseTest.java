/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Corrupt and legacy on-disk artifacts exercise the reuse proof independently of worker fixtures. */
final class TextArtifactReuseTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void missingCompletedVectorRecomputesOnlyDenseStage() throws Exception {
    check(old -> old.removeFields(SchemaFields.VECTOR), runtime -> {
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "PENDING");
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED");
      assertStatus(runtime, SchemaFields.NER_STATUS, "COMPLETED");
    });
  }

  @Test
  void malformedSparsePayloadRecomputesOnlySparseStage() throws Exception {
    check(old -> {
      old.removeFields(SchemaFields.SPLADE_PAYLOAD);
      old.add(new StoredField(SchemaFields.SPLADE_PAYLOAD, new byte[] {99, 1}));
    }, runtime -> {
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "COMPLETED");
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "PENDING");
      assertStatus(runtime, SchemaFields.NER_STATUS, "COMPLETED");
    });
  }

  @Test
  void legacySparsePostingsAreReadBackBeforeReplacement() throws Exception {
    check(old -> old.removeFields(SchemaFields.SPLADE_PAYLOAD), runtime -> {
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED");
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "COMPLETED");
    });
  }

  @Test
  void completedEntitiesMustHaveValidStoredValues() throws Exception {
    check(old -> {
      old.removeFields(SchemaFields.ENTITY_PERSONS_RAW);
      old.add(new org.apache.lucene.document.StringField(SchemaFields.ENTITY_PERSONS_RAW,
          "Ada", org.apache.lucene.document.Field.Store.NO));
      old.add(new org.apache.lucene.document.SortedSetDocValuesField(SchemaFields.ENTITY_PERSONS_RAW,
          new org.apache.lucene.util.BytesRef("Ada")));
      old.add(new StoredField(SchemaFields.ENTITY_PERSONS_RAW, new byte[] {1}));
    }, runtime -> {
      assertStatus(runtime, SchemaFields.NER_STATUS, "PENDING");
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "COMPLETED");
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED");
    });
  }

  @Test
  void legacyFamilyWithoutAnExplicitDeclarationCannotCertifyReuse() throws Exception {
    check(old -> {
      old.removeFields(SchemaFields.CHUNK_TOTAL);
      old.removeFields(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
    }, runtime -> {
      assertStatus(runtime, SchemaFields.NER_STATUS, "PENDING");
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "PENDING");
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "PENDING");
    });
  }

  @Test
  void pendingDoesNotCarryAnUnfinishedVector() throws Exception {
    var schema = schema();
    try (var runtime = open(schema)) {
      var pending = complete(schema.ssotVectorDimension());
      pending.put(SchemaFields.EMBEDDING_STATUS, "PENDING");
      pending.put(SchemaFields.EMBEDDING_RETRY_COUNT, "2");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(pending));
      refresh(runtime);
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true);
      refresh(runtime);
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "PENDING");
      assertEquals("2", runtime.documentFieldOps().getDocumentField("parent", SchemaFields.EMBEDDING_RETRY_COUNT));
    }
    try (var directory = FSDirectory.open(temporary.resolve("index")); var reader = DirectoryReader.open(directory)) {
      var searcher = new IndexSearcher(reader);
      int doc = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, "parent")), 1).scoreDocs[0].doc;
      assertNull(WritePathOps.readFloatVector(searcher, doc, SchemaFields.VECTOR));
    }
  }

  @Test
  void coherentCompletedEmptyStagesStayEmpty() throws Exception {
    var schema = schema();
    try (var runtime = open(schema)) {
      var empty = fresh();
      empty.put(SchemaFields.SPLADE_STATUS, "COMPLETED_EMPTY");
      empty.put(SchemaFields.NER_STATUS, "COMPLETED_EMPTY");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(empty)); refresh(runtime);
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true); refresh(runtime);
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED_EMPTY");
      assertStatus(runtime, SchemaFields.NER_STATUS, "COMPLETED_EMPTY");
      assertNull(runtime.documentFieldOps().getDocumentField("parent", SchemaFields.ENTITY_PERSONS_RAW));
    }
  }

  @Test
  void laterMetadataCommitCannotAttestPreviouslyUnattestedVectors() throws Exception {
    var original = schema(false);
    try (var runtime = open(original)) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(complete(original.ssotVectorDimension())));
      refresh(runtime);
    }
    try (var runtime = open(schema())) {
      // The new commit now carries a graph stamp; the generation's opening proof still did not.
      refresh(runtime);
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true);
      refresh(runtime);
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "PENDING");
      assertStatus(runtime, SchemaFields.NER_STATUS, "COMPLETED");
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"splade", "ner"})
  void metadataOnlyModelChangeUsesTheIdentityAcceptedByTheProductAfterRestart(String role) throws Exception {
    var config = new ResolvedConfigBuilder().build();
    var original = IndexFingerprint.ModelFingerprint.present("a".repeat(64)).withAssets(Optional.of("b".repeat(64)));
    var changed = IndexFingerprint.ModelFingerprint.present("c".repeat(64)).withAssets(Optional.of("b".repeat(64)));
    var authority = new java.util.concurrent.atomic.AtomicReference<io.justsearch.indexing.runtime.CommitMetadataSource>(
        new SsotCommitMetadataSource(config,
            new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, original, original, original)));
    var schema = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog(), authority::get, ignored -> {});
    try (var runtime = open(schema)) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(complete(schema.ssotVectorDimension())));
      refresh(runtime);
      runtime.indexingCoordinator().transitionEnrichmentAuthority(() -> authority.set(new SsotCommitMetadataSource(config,
          new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, original,
              role.equals("splade") ? changed : original, role.equals("ner") ? changed : original))));
      refresh(runtime);
    }
    try (var runtime = open(schema)) {
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true);
      refresh(runtime);
      // The product serves completed sparse/entities under this committed identity. Historical
      // provenance is handled separately; reuse follows the accepted identity after reopening.
      assertStatus(runtime, role.equals("splade") ? SchemaFields.SPLADE_STATUS : SchemaFields.NER_STATUS, "COMPLETED");
    }
  }

  @Test
  void metadataAdoptionReusesTheCompanionIdentityAcceptedByTheProductAfterRestart() throws Exception {
    var config = new ResolvedConfigBuilder().build();
    var graphOnly = IndexFingerprint.ModelFingerprint.present("a".repeat(64));
    var knownAssets = graphOnly.withAssets(Optional.of("b".repeat(64)));
    var authority = new java.util.concurrent.atomic.AtomicReference<io.justsearch.indexing.runtime.CommitMetadataSource>(
        new SsotCommitMetadataSource(config,
            new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, graphOnly, graphOnly, graphOnly)));
    var schema = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog(), authority::get, ignored -> {});
    try (var runtime = open(schema)) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(complete(schema.ssotVectorDimension())));
      refresh(runtime);
    }
    authority.set(new SsotCommitMetadataSource(config,
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, knownAssets, knownAssets, knownAssets)));
    try (var runtime = open(schema)) {
      // Normal metadata adoption records today's assets without proving the historical outputs.
      refresh(runtime);
    }
    try (var runtime = open(schema)) {
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true);
      refresh(runtime);
      assertAll(() -> assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED"),
          () -> assertStatus(runtime, SchemaFields.NER_STATUS, "COMPLETED"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bgeDenseOutputUsesTheSparseModelsIdentity(boolean desiredBge) throws Exception {
    var config = new ResolvedConfigBuilder()
        .putSettings("justsearch.sparse_model", desiredBge ? "bge-m3" : "splade").build();
    var model = IndexFingerprint.ModelFingerprint.present("a".repeat(64)).withAssets(Optional.of("b".repeat(64)));
    var metadata = new SsotCommitMetadataSource(config, new SsotCommitMetadataSource.RuntimeFingerprintInputs(1024,
        IndexFingerprint.ModelFingerprint.notConfigured(), model, model), "splade");
    var catalog = new JustSearchConfigurationLoader().loadFieldCatalog().withVectorDimension(1024);
    var schema = IndexSchema.fromCatalog(catalog, metadata, ignored -> {});
    try (var runtime = schema.atPath(temporary.resolve("index")).withConfig(config)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(complete(1024))); refresh(runtime);
      assertTrue(runtime.indexingCoordinator().canReuseDocumentEmbedding(new IndexDocument(fresh()), List.of()));
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true); refresh(runtime);
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "COMPLETED");
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED");
    }
  }

  @Test
  void desiredBgeSettingCannotAuthorizeReuseAcrossAnOrdinaryEmbeddingModelChange() throws Exception {
    var config = new ResolvedConfigBuilder().putSettings("justsearch.sparse_model", "bge-m3").build();
    var original = IndexFingerprint.ModelFingerprint.present("a".repeat(64)).withAssets(Optional.of("b".repeat(64)));
    var changed = IndexFingerprint.ModelFingerprint.present("c".repeat(64)).withAssets(Optional.of("b".repeat(64)));
    var authority = new java.util.concurrent.atomic.AtomicReference<io.justsearch.indexing.runtime.CommitMetadataSource>(
        new SsotCommitMetadataSource(config,
            new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, original, original, original), "embedding"));
    var schema = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog(), authority::get, ignored -> {});
    try (var runtime = schema.atPath(temporary.resolve("index")).withConfig(config)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(complete(schema.ssotVectorDimension()))); refresh(runtime);
      runtime.indexingCoordinator().transitionEnrichmentAuthority(() -> authority.set(new SsotCommitMetadataSource(config,
          new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, changed, original, original), "embedding")));
      assertFalse(runtime.indexingCoordinator().canReuseDocumentEmbedding(new IndexDocument(fresh()), List.of()));
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true); refresh(runtime);
      assertStatus(runtime, SchemaFields.EMBEDDING_STATUS, "PENDING");
      assertStatus(runtime, SchemaFields.SPLADE_STATUS, "COMPLETED");
    }
  }

  private void check(Consumer<Document> corrupt, Consumer<RunningRuntime> assertions) throws Exception {
    var schema = schema();
    try (var runtime = open(schema)) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(complete(schema.ssotVectorDimension())));
      refresh(runtime);
    }
    // Write malformed historical state at the actual storage boundary, outside the live runtime.
    try (var directory = FSDirectory.open(temporary.resolve("index")); var analyzer = new StandardAnalyzer();
        var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      Document old = schema.fieldMapper().toDocument(complete(schema.ssotVectorDimension()), new FieldMapper.DroppedVectorReport());
      corrupt.accept(old);
      writer.updateDocument(new Term(SchemaFields.DOC_ID, "parent"), old);
      writer.commit();
    }
    try (var runtime = open(schema)) {
      runtime.indexingCoordinator().replaceTextFamily(new IndexDocument(fresh()), List.of(), true);
      refresh(runtime); assertions.accept(runtime);
    }
  }

  private IndexSchema schema() {
    return schema(true);
  }

  private IndexSchema schema(boolean attested) {
    var model = IndexFingerprint.ModelFingerprint.present("a".repeat(64)).withAssets(Optional.of("b".repeat(64)));
    var metadata = new SsotCommitMetadataSource(new ResolvedConfigBuilder().build(),
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, model, model, model));
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog(),
        new io.justsearch.indexing.runtime.CommitMetadataSource() {
          @Override public Map<String, Object> build() {
            var values = new HashMap<>(metadata.build());
            if (attested) values.put("embedding_model_sha256", "a".repeat(64));
            return values;
          }

          @Override public String denseModelRole() { return metadata.denseModelRole(); }
        }, ignored -> {});
  }

  private RunningRuntime open(IndexSchema schema) {
    return schema.atPath(temporary.resolve("index")).withConfig(new ResolvedConfigBuilder().build())
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static Map<String, Object> fresh() {
    var row = new HashMap<String, Object>();
    row.put(SchemaFields.DOC_ID, "parent"); row.put(SchemaFields.DOC_UID, "uid");
    row.put(SchemaFields.PATH, "/source.txt"); row.put(SchemaFields.CONTENT, "same text");
    row.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("same text"));
    row.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex("same text"));
    row.put(SchemaFields.CHUNK_TOTAL, 0); row.put(SchemaFields.MIME, "text/plain");
    row.put(SchemaFields.MIME_BASE, "text/plain"); row.put(SchemaFields.FILE_KIND, "text");
    row.put(SchemaFields.EMBEDDING_STATUS, "PENDING"); row.put(SchemaFields.SPLADE_STATUS, "PENDING");
    row.put(SchemaFields.NER_STATUS, "PENDING");
    return row;
  }

  private static Map<String, Object> complete(int dimension) {
    var row = fresh();
    var vector = new float[dimension]; vector[0] = 1;
    row.put(SchemaFields.VECTOR, vector); row.put(SchemaFields.EMBEDDING_STATUS, "COMPLETED");
    row.put(SchemaFields.SPLADE, Map.of("term", 1.0f)); row.put(SchemaFields.SPLADE_STATUS, "COMPLETED");
    row.put(SchemaFields.ENTITY_PERSONS_RAW, List.of("Ada")); row.put(SchemaFields.NER_STATUS, "COMPLETED");
    return row;
  }

  private static void assertStatus(RunningRuntime runtime, String field, String expected) {
    assertEquals(expected, runtime.documentFieldOps().getDocumentField("parent", field), field);
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
  }
}
