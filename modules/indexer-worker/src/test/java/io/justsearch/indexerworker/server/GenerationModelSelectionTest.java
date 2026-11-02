/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.index.IndexGenerationManager.GenerationManifest;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GenerationModelSelectionTest {
  @TempDir Path temp;

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.CsvSource({
      "embedding,tokenizer.json", "embedding,1_Pooling/config.json",
      "embedding,pooling_config.json", "embedding,prefix_config.json",
      "embedding,sentence_bert_config.json", "embedding,config_sentence_transformers.json",
      "ner,tokenizer.json", "ner,config.json", "splade,tokenizer.json", "splade,vocab.txt"})
  void companionChangesRejectCompatibilityWithoutChangingGraphIntegrity(String role, String companion) throws Exception {
    Path graph = temp.resolve("A/model.onnx");
    Files.createDirectories(graph.getParent());
    Files.write(graph, new byte[] {58, 0});
    var selection = GenerationModelSelection.from(new GenerationManifest(2, "A", "installed_models", 1L,
        "f".repeat(64), Map.of(role, new ModelArtifact(graph.toString(), sha256(graph))), "splade", 768)).orElseThrow();
    var config = io.justsearch.configuration.resolved.TestResolvedConfigHelper.withDefaults();
    var first = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, selection.runtimeFingerprintInputs()).build();
    String key = role + "_model_assets_sha256";
    Path index = temp.resolve("index");
    writeMetadata(index, first);
    assertTrue(io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.inspectCommittedParity(index, () -> first).isEmpty());

    Path asset = graph.getParent().resolve(companion);
    Files.createDirectories(asset.getParent());
    Files.writeString(asset, "{\"vocab\":{\"Li\":77}}");
    var next = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, selection.runtimeFingerprintInputs()).build();
    assertTrue(io.justsearch.adapters.lucene.runtime.ParityDiagnostics.requiresRebuild(
        io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.inspectCommittedParity(index, () -> next)),
        "the saved identity must reject changed companions after reopen");
    assertTrue(first.containsKey(key), "a graph-only attestation must gain a separate companion identity");
    assertFalse(first.get(key).equals(next.get(key)), "fallback appearance changes effective identity");


    var stored = new java.util.HashMap<String, String>();
    first.forEach((name, value) -> stored.put(name, value.toString()));
    assertTrue(io.justsearch.adapters.lucene.runtime.ParityDiagnostics.requiresRebuild(
        io.justsearch.adapters.lucene.runtime.ParityDiagnostics.diff(stored, next, 1)));
    assertTrue(selection.verify(role).isPresent(), "companion identity must not replace raw ONNX integrity");
    Files.writeString(asset, "{\"vocab\":{\"Li\":78}}");
    var changed = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, selection.runtimeFingerprintInputs()).build();
    assertFalse(next.get(key).equals(changed.get(key)));
    Files.delete(asset);
    var restored = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, selection.runtimeFingerprintInputs()).build();
    assertEquals(first.get(key), restored.get(key));
    stored.remove(key);
    assertTrue(io.justsearch.adapters.lucene.runtime.ParityDiagnostics.diff(stored, changed, 1).isEmpty(),
        "old graph-only attestations adopt the identity without forced recovery");
    Files.writeString(graph, "tampered graph");
    assertTrue(selection.verify(role).isEmpty());
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"embedding", "splade", "ner"})
  void externalWeightsChangeRejectsCompatibilityAfterCommitAndReopen(String role) throws Exception {
    Path graph = temp.resolve("external/model.onnx");
    Files.createDirectories(graph.getParent());
    Files.write(graph, externalGraph("weights.bin"));
    Path weights = Files.writeString(graph.resolveSibling("weights.bin"), "weights A");
    Files.writeString(graph.resolveSibling("tokenizer.json"), "unchanged tokenizer");
    var selection = GenerationModelSelection.accepted(Map.of(role,
        new ModelArtifact(graph.toString(), sha256(graph))), "splade", 768);
    var config = io.justsearch.configuration.resolved.TestResolvedConfigHelper.withDefaults();
    var first = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, selection.runtimeFingerprintInputs()).build();
    Path index = temp.resolve("external-index");
    writeMetadata(index, first);
    assertTrue(io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.inspectCommittedParity(index, () -> first).isEmpty());
    Files.writeString(weights, "weights B");
    var next = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, selection.runtimeFingerprintInputs()).build();
    assertTrue(selection.verify(role).isPresent(), "graph bytes and raw integrity remain unchanged");
    assertTrue(io.justsearch.adapters.lucene.runtime.ParityDiagnostics.requiresRebuild(
        io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.inspectCommittedParity(index, () -> next)),
        "changed external inference weights must reject the committed identity on reopen");
  }

  @Test
  void externalReferencesUseExactGenerationGraphRatherThanSiblingVariant() throws Exception {
    Path graph = temp.resolve("exact/model.onnx");
    Files.createDirectories(graph.getParent());
    Files.write(graph, externalGraph("selected.bin"));
    Files.write(graph.resolveSibling("model_fp16.onnx"), externalGraph("other.bin"));
    Files.writeString(graph.resolveSibling("selected.bin"), "selected weights");
    Path other = Files.writeString(graph.resolveSibling("other.bin"), "other weights");
    var selection = GenerationModelSelection.accepted(Map.of("embedding",
        new ModelArtifact(graph.toString(), sha256(graph))), "splade", 768);
    var first = selection.fingerprint("embedding");
    Files.writeString(other, "changed other weights");
    assertEquals(first, selection.fingerprint("embedding"));
    Files.writeString(graph.resolveSibling("selected.bin"), "changed selected weights");
    org.junit.jupiter.api.Assertions.assertNotEquals(first, selection.fingerprint("embedding"));
  }

  private static byte[] externalGraph(String location) {
    var entry = new java.io.ByteArrayOutputStream();
    entry.writeBytes(new byte[] {10, 8});
    entry.writeBytes("location".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] name = location.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    entry.write(18); entry.write(name.length); entry.writeBytes(name);
    var model = new java.io.ByteArrayOutputStream();
    model.writeBytes(new byte[] {58, (byte) (entry.size() + 6), 42,
        (byte) (entry.size() + 4), 106, (byte) entry.size()});
    model.writeBytes(entry.toByteArray()); model.writeBytes(new byte[] {112, 1});
    return model.toByteArray();
  }

  @Test
  void graphOnlyGenerationAdoptsWithoutClaimingCommittedCompanionProof() throws Exception {
    Path graph = temp.resolve("legacy-model/model.onnx");
    Files.createDirectories(graph.getParent()); Files.write(graph, new byte[] {58, 0});
    var selection = GenerationModelSelection.accepted(Map.of("embedding",
        new ModelArtifact(graph.toString(), sha256(graph))), "splade", 768);
    var expected = new java.util.HashMap<String, Object>(new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        io.justsearch.configuration.resolved.TestResolvedConfigHelper.withDefaults(), selection.runtimeFingerprintInputs()).build());
    expected.putIfAbsent("embedding_model_assets_sha256", "a".repeat(64));
    var old = new java.util.HashMap<String, Object>(expected);
    old.remove("embedding_model_assets_sha256");
    Path index = temp.resolve("legacy-index");
    writeMetadata(index, old);
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
        io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.class);
    var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    var previousLevel = logger.getLevel(); events.start(); logger.addAppender(events);
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    try {
      assertTrue(io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.inspectCommittedParity(index, () -> expected).isEmpty(),
          "graph-only indexes adopt the companion field without forced rebuilding");
      assertTrue(io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.certifiesCommittedCompatibility(index, expected, java.util.List.of()),
          "adoption must preserve compatible interrupted-generation recovery");
      assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains(
          "pre-adoption companion provenance unverified")), "legacy adoption must be explicit");
      try (var directory = org.apache.lucene.store.FSDirectory.open(index);
          var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
        assertFalse(reader.getIndexCommit().getUserData().containsKey("embedding_model_assets_sha256"),
            "a compatibility read cannot manufacture persisted companion proof");
      }
    } finally {
      logger.detachAppender(events); events.stop(); logger.setLevel(previousLevel);
    }
  }

  @Test
  void companionReadFailureIsIndeterminateAndGenerationsKeepIndependentAssets() throws Exception {
    Path a = temp.resolve("A/model.onnx"); Path b = temp.resolve("B/model.onnx");
    Files.createDirectories(a.getParent()); Files.createDirectories(b.getParent());
    Files.write(a, new byte[] {58, 0}); Files.write(b, new byte[] {58, 0});
    Files.writeString(a.getParent().resolve("tokenizer.json"), "vocabulary A");
    Files.writeString(b.getParent().resolve("tokenizer.json"), "vocabulary B");
    var first = GenerationModelSelection.accepted(Map.of("embedding", new ModelArtifact(a.toString(), sha256(a))), "splade", 768);
    var second = GenerationModelSelection.accepted(Map.of("embedding", new ModelArtifact(b.toString(), sha256(b))), "splade", 768);
    var config = io.justsearch.configuration.resolved.TestResolvedConfigHelper.withDefaults();
    var sourceA = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(config, first.runtimeFingerprintInputs()).build();
    var sourceB = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(config, second.runtimeFingerprintInputs()).build();
    Files.delete(a.getParent().resolve("tokenizer.json"));
    Files.createDirectory(a.getParent().resolve("tokenizer.json"));
    assertEquals(io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelState.INDETERMINATE,
        first.fingerprint("embedding").state(), "read failure must not look like an absent model");
    assertTrue(first.verify("embedding").isPresent(), "raw graph integrity is still independently valid");
    assertTrue(sourceA.containsKey("embedding_model_assets_sha256"));
    assertFalse(sourceA.get("embedding_model_assets_sha256").equals(sourceB.get("embedding_model_assets_sha256")));

    assertEquals(io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelState.NOT_CONFIGURED,
        first.fingerprint("ner").state());
    assertEquals(sourceB, new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(config, second.runtimeFingerprintInputs()).build());
  }

  @Test
  void selectsOnlyTheManifestFileAndItsOwnMetadataDirectory() throws Exception {
    Path x = temp.resolve("X/model.onnx");
    Path y = temp.resolve("Y/model.onnx");
    Files.createDirectories(x.getParent());
    Files.createDirectories(y.getParent());
    Files.writeString(x, "generation-X");
    Files.writeString(x.getParent().resolve("model_fp16.onnx"), "unrelated-sibling");
    Files.writeString(y, "desired-Y");
    String sha = sha256(x);
    var manifest = new GenerationManifest(2, "g-x", "installed_models", 1L,
        "f".repeat(64), Map.of("embedding", new ModelArtifact(x.toString(), sha)),
        "splade", 768);
    var selection = GenerationModelSelection.from(manifest).orElseThrow();
    assertEquals("splade", selection.sparseModel().orElseThrow());
    assertEquals(768, selection.vectorDimension().orElseThrow());

    var selected = selection.verify("embedding").orElseThrow();
    assertEquals(x, selected.file());
    assertEquals(x.getParent(), selected.metadataDirectory());
    assertEquals(sha, selected.sha256());
    assertEquals(sha, selection.availableFingerprint("embedding").orElseThrow());
    assertEquals(x, selection.variant("embedding", true).orElseThrow().modelFile());
    assertFalse(selection.verify("ner").isPresent());

    Files.writeString(x, "changed-X");
    assertTrue(selection.availableFingerprint("embedding").isEmpty());
    assertFalse(selection.verify("embedding").isPresent());
    Files.delete(x);
    assertFalse(selection.verify("embedding").isPresent());
    assertTrue(Files.isRegularFile(y));
  }

  @Test
  void legacyManifestDoesNotClaimModelSelection() {
    assertTrue(GenerationModelSelection.from(
        new GenerationManifest(1, "g-legacy", "initial", 1L)).isEmpty());
  }

  private static void writeMetadata(Path index, Map<String, Object> metadata) throws Exception {
    try (var directory = org.apache.lucene.store.FSDirectory.open(index);
        var analyzer = new org.apache.lucene.analysis.standard.StandardAnalyzer();
        var writer = new org.apache.lucene.index.IndexWriter(directory, new org.apache.lucene.index.IndexWriterConfig(analyzer))) {
      writer.addDocument(new org.apache.lucene.document.Document());
      var userData = new java.util.HashMap<String, String>();
      metadata.forEach((name, value) -> userData.put(name, value.toString()));
      writer.setLiveCommitData(userData.entrySet()); writer.commit();
    }
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(file)));
  }
}
