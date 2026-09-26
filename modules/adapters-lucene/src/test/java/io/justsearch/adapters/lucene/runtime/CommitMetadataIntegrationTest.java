package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.RequiredFieldsCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;

class CommitMetadataIntegrationTest extends LuceneExecutorTestBase {
  @Test
  void twoLiveGenerationsStampAndReopenAgainstTheirOwnModelIdentities() throws Exception {
    Path parent = Files.createTempDirectory("lucene-two-model-identities");
    Path blue = parent.resolve("blue");
    Path green = parent.resolve("green");
    ResolvedConfig config = ResolvedConfig.builder().build();
    var noModel = IndexFingerprint.ModelFingerprint.notConfigured();
    var blueSource = new SsotCommitMetadataSource(config,
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(768,
            IndexFingerprint.ModelFingerprint.present("a".repeat(64)), noModel, noModel));
    var greenSource = new SsotCommitMetadataSource(config,
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(768,
            IndexFingerprint.ModelFingerprint.present("b".repeat(64)), noModel, noModel));
    var validator = new JsonSchemaCommitMetadataValidator();
    var blueRuntime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768),
        blueSource, validator).atPath(blue).withExecutorRegistrations(testLuceneExecutors()).open();
    var greenRuntime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768),
        greenSource, validator).atPath(green).withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      blueRuntime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "blue", SchemaFields.DOC_UID, "blue#0")));
      greenRuntime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "green", SchemaFields.DOC_UID, "green#0")));
      blueRuntime.commitOps().commitAndTrack();
      greenRuntime.commitOps().commitAndTrack();
    } finally {
      greenRuntime.close();
      blueRuntime.close();
    }
    String blueFingerprint = String.valueOf(blueSource.build().get(IndexFingerprint.COMMIT_META_KEY));
    String greenFingerprint = String.valueOf(greenSource.build().get(IndexFingerprint.COMMIT_META_KEY));
    assertNotEquals(blueFingerprint, greenFingerprint);
    try (var blueDirectory = new MMapDirectory(blue);
        var blueReader = DirectoryReader.open(blueDirectory);
        var greenDirectory = new MMapDirectory(green);
        var greenReader = DirectoryReader.open(greenDirectory)) {
      assertEquals(blueFingerprint,
          blueReader.getIndexCommit().getUserData().get(IndexFingerprint.COMMIT_META_KEY));
      assertEquals(greenFingerprint,
          greenReader.getIndexCommit().getUserData().get(IndexFingerprint.COMMIT_META_KEY));
    }
    assertTrue(IndexMetadataParityGuard.inspectCommittedParity(blue, blueSource::build).isEmpty());
    assertTrue(IndexMetadataParityGuard.inspectCommittedParity(green, greenSource::build).isEmpty());
    assertFalse(IndexMetadataParityGuard.inspectCommittedParity(blue, greenSource::build).isEmpty());
    assertFalse(IndexMetadataParityGuard.inspectCommittedParity(green, blueSource::build).isEmpty());
  }

  @Test
  void commitStampsUserData() throws Exception {
    Path dir = Files.createTempDirectory("lucene-commit-test");
    CommitMetadataSource meta = new SsotCommitMetadataSource();
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), meta, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    r.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "commit-1", SchemaFields.DOC_UID, "commit-1#0")));
    r.commitOps().commitAndTrack();
    r.close();

    try (var d = new MMapDirectory(dir); var reader = DirectoryReader.open(d)) {
      IndexCommit c = reader.getIndexCommit();
      Map<String, String> ud = c.getUserData();
      assertNotNull(ud.get("index_fingerprint"));
      assertNotNull(ud.get("boosts_fp"));
      assertNotNull(ud.get("schema_fp"));
      assertNotNull(ud.get("field_catalog_hash"));
      assertNotNull(ud.get("synonyms_hash"));
      assertEquals(
          expectedMetaKeys(meta),
          ud.keySet().stream().filter(key -> !key.startsWith("commit_")).collect(Collectors.toSet()));
      // Compare a known value. index_fingerprint specifically: it is the key a mismatch on which
      // costs the user a full rebuild, so "the commit stamped SOME string" is not enough — it must
      // be the same string this runtime computes.
      String expectedFingerprint = String.valueOf(meta.build().get("index_fingerprint"));
      assertEquals(expectedFingerprint, ud.get("index_fingerprint"));
      assertEquals("COMPLETE", ud.get("build_state"));

      // Tempdoc 931 §C.5: the canonical inputs the digest hashes are stamped beside it, verbatim.
      // Verbatim is the whole contract — the fallback comparison a later boot runs when its own
      // digest is uncomputable reads these bytes, so a re-rendered or abbreviated copy would make
      // every input look changed. Also pinned against the SSOT commit-metadata schema, which is
      // additionalProperties:false: the JsonSchemaCommitMetadataValidator above would have rejected
      // the commit outright if the key were not declared there.
      String expectedInputs = String.valueOf(meta.build().get("index_fingerprint_inputs"));
      assertEquals(expectedInputs, ud.get("index_fingerprint_inputs"));
      assertTrue(
          ud.get("index_fingerprint_inputs").contains("\"rendering_version\""),
          "the stamped value is the canonical rendering, not a summary of it");
    }
  }

  private static java.util.Set<String> expectedMetaKeys(CommitMetadataSource meta) {
    java.util.Set<String> keys = new HashSet<>(meta.build().keySet());
    keys.add("build_state");
    return keys;
  }

  @Test
  void metadataSourceSupplierInvokedPerBuild() throws Exception {
    Path dir = Files.createTempDirectory("lucene-meta-supplier");
    AtomicInteger supplierCalls = new AtomicInteger();
    Supplier<CommitMetadataSource> supplier =
        () -> {
          supplierCalls.incrementAndGet();
          return () ->
              Map.of(
                  "index_fingerprint", "fp",
                  "similarity_fp", "sim",
                  "boosts_fp", "boosts");
        };
    CommitMetadataValidator validator = metadata -> {};

    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), supplier, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "commit-supplier-1",
                SchemaFields.DOC_UID, "commit-supplier-1#0")));
    runtime.commitOps().commitAndTrack();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "commit-supplier-2",
                SchemaFields.DOC_UID, "commit-supplier-2#0")));
    runtime.commitOps().commitAndTrack();
    runtime.close();

    // Supplier should be invoked once per commit (parity check is skipped on a fresh index).
    assertEquals(2, supplierCalls.get());
  }

  @Test
  void commitMetadataDisabledSkipsStamping() throws Exception {
    Path dir = Files.createTempDirectory("lucene-commit-disabled");
    Path cfg = Files.createTempFile("app-config-", ".yaml");
    Files.writeString(
        cfg,
        """
        index:
          commit:
            meta:
              enabled: false
          vector:
            dimension: 768
        """);
    String previous = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    AtomicInteger validatorCalls = new AtomicInteger();
    CommitMetadataSource source = () -> Map.of("index_fingerprint", "ignored");
    CommitMetadataValidator validator = metadata -> validatorCalls.incrementAndGet();
    try {
      var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), source, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(SchemaFields.DOC_ID, "commit-0", SchemaFields.DOC_UID, "commit-0#0")));
      runtime.commitOps().commitAndTrack();
      runtime.close();

      try (var d = new MMapDirectory(dir); var reader = DirectoryReader.open(d)) {
        Map<String, String> userData = reader.getIndexCommit().getUserData();
        assertTrue(userData.isEmpty(), "Expected no commit metadata when disabled");
      }
      assertEquals(0, validatorCalls.get(), "Validator should not be invoked when metadata disabled");
    } finally {
      if (previous == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", previous);
      }
    }
  }

  @Test
  void commitWithInvalidMetadataFails() throws Exception {
    CommitMetadataSource badSource =
        () -> {
          Map<String, Object> m = new HashMap<>();
          m.put("index_fingerprint", "1.0.0");
          // Intentionally omit required fields like schema_fp
          return m;
        };
    CommitMetadataValidator validator = new RequiredFieldsCommitMetadataValidator();
    Path dir = Files.createTempDirectory("lucene-invalid-meta");
    var runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4), badSource, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "invalid-1", SchemaFields.DOC_UID, "invalid-1#0")));
    assertThrows(IllegalStateException.class, runtime.commitOps()::commitAndTrack);
    runtime.close();
  }
}
