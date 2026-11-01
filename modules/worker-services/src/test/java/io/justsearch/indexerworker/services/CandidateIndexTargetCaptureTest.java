/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CandidateIndexTargetCaptureTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"embedding", "splade", "ner"})
  void candidateCommitPersistsOwnCompanionsAndRejectsChangedTokenizerOnReopen(String role) throws Exception {
    Path models = createModelSet("candidate-" + role, "embedding-a");
    var config = candidate(models);
    var captured = CandidateIndexTargetCapture.captureWithRuntimeInputs(config);
    var source = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, captured.runtimeFingerprintInputs());
    Path index = tempDir.resolve("index-" + role);
    try (var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
        io.justsearch.configuration.FieldCatalogDef.forTesting(768), source,
        new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator())
        .atPath(index).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(
          java.util.Map.of("doc_id", "one", "doc_uid", "one#0", "content", "candidate text")));
      runtime.commitOps().commitAndTrack();
      for (String model : java.util.List.of("embedding", "splade", "ner")) {
        org.junit.jupiter.api.Assertions.assertNotNull(
            runtime.latestCommitUserDataBestEffort().get(model + "_model_assets_sha256"));
      }
    }
    Files.writeString(models.resolve(role).resolve("tokenizer.json"), "{\"changed\":true}");
    var changed = CandidateIndexTargetCapture.captureWithRuntimeInputs(config);
    var changedSource = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        config, changed.runtimeFingerprintInputs());
    var diffs = io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard.inspectCommittedParity(
        index, changedSource::build);
    org.junit.jupiter.api.Assertions.assertTrue(diffs.stream()
        .anyMatch(diff -> diff.key().equals(role + "_model_assets_sha256")));
    assertEquals(captured.selectedModels().get(role).sha256(), changed.selectedModels().get(role).sha256());
  }

  @Test
  void candidateModelBytesChangeTargetWithoutProcessWideProviders() throws Exception {
    Path common = createModelSet("common", "embedding-a");
    Path changed = createModelSet("changed", "embedding-b");

    IndexTargetSnapshot targetA = CandidateIndexTargetCapture.capture(candidate(common));
    IndexTargetSnapshot targetB = CandidateIndexTargetCapture.capture(candidate(changed));

    assertNotEquals(targetA.fingerprint(), targetB.fingerprint());
    assertNotEquals(targetA.canonicalInputsJson(), targetB.canonicalInputsJson());
  }

  @Test
  void detailedCaptureDelegatesToLegacyTargetAndCarriesCandidateInputs() throws Exception {
    Path models = createModelSet("detailed", "embedding-a");
    ResolvedConfig candidate = candidate(models);

    CandidateIndexTargetCapture.CaptureResult detailed =
        CandidateIndexTargetCapture.captureWithRuntimeInputs(candidate);

    assertEquals(detailed.target(), CandidateIndexTargetCapture.capture(candidate));
    assertEquals(
        sha256("embedding-a"),
        detailed.runtimeFingerprintInputs().embeddingModel().sha());
    assertEquals(
        sha256("splade"),
        detailed.runtimeFingerprintInputs().spladeModel().sha());
    assertEquals(sha256("ner"), detailed.runtimeFingerprintInputs().nerModel().sha());
    assertEquals(models.resolve("embedding/model.onnx").toAbsolutePath().normalize().toString(),
        detailed.selectedModels().get("embedding").id());
    assertEquals(sha256("embedding-a"), detailed.selectedModels().get("embedding").sha256());
    assertEquals(3, detailed.selectedModels().size());
  }

  @Test
  void detailedCaptureDoesNotBorrowProcessWideProviderInputs() throws Exception {
    Path models = createModelSet("provider-independent", "embedding-a");
    IndexFingerprint.installEffectiveVectorDimension(() -> 384);
    IndexFingerprint.installModelFingerprintProviders(
        () -> IndexFingerprint.ModelFingerprint.present("a".repeat(64)),
        () -> IndexFingerprint.ModelFingerprint.present("b".repeat(64)),
        () -> IndexFingerprint.ModelFingerprint.present("c".repeat(64)));
    try {
      CandidateIndexTargetCapture.CaptureResult detailed =
          CandidateIndexTargetCapture.captureWithRuntimeInputs(candidate(models));

      assertEquals(
          sha256("embedding-a"),
          detailed.runtimeFingerprintInputs().embeddingModel().sha());
      assertEquals(sha256("splade"), detailed.runtimeFingerprintInputs().spladeModel().sha());
      assertEquals(sha256("ner"), detailed.runtimeFingerprintInputs().nerModel().sha());
      assertNotEquals(
          "a".repeat(64), detailed.runtimeFingerprintInputs().embeddingModel().sha());
    } finally {
      IndexFingerprint.resetModelFingerprintProviders();
    }
  }

  @Test
  void detailedCaptureFreezesRetainedQueryModelsAlongsideIndexModels() throws Exception {
    Path models = createModelSet("query-models", "embedding-a");
    createModel(models.resolve("reranker"), "reranker-a", false);
    createModel(models.resolve("citation"), "citation-a", false);
    ResolvedConfig candidate = new ResolvedConfigBuilder()
        .putDefault("justsearch.embed.backend", "onnx")
        .putDefault("justsearch.embed.onnx.model_path", models.resolve("embedding").toString())
        .putDefault("justsearch.splade.model_path", models.resolve("splade").toString())
        .putDefault("justsearch.ner.model_path", models.resolve("ner").toString())
        .putDefault("justsearch.rerank.model_path", models.resolve("reranker").toString())
        .putDefault("justsearch.citation.scorer.model_path", models.resolve("citation").toString())
        .build();

    var detailed = CandidateIndexTargetCapture.captureWithRuntimeInputs(candidate);

    assertEquals(5, detailed.selectedModels().size());
    assertEquals(sha256("reranker-a"), detailed.selectedModels().get("reranker").sha256());
    assertEquals(sha256("citation-a"),
        detailed.selectedModels().get("citation-scorer").sha256());
  }

  @Test
  void missingManifestSelectedModelFailsClosed() throws Exception {
    Path models = createModelSet("missing", "embedding");
    Path embedding = models.resolve("embedding");
    Files.writeString(
        embedding.resolve("model_manifest.json"),
        "{\"cpu\":\"missing.onnx\"}",
        StandardCharsets.UTF_8);
    Files.delete(embedding.resolve("model.onnx"));

    assertThrows(IOException.class, () -> CandidateIndexTargetCapture.capture(candidate(models)));
  }

  @Test
  void malformedManifestFailsClosed() throws Exception {
    Path models = createModelSet("malformed", "embedding");
    Files.writeString(
        models.resolve("embedding").resolve("model_manifest.json"),
        "{not-json",
        StandardCharsets.UTF_8);

    assertThrows(
        RuntimeException.class, () -> CandidateIndexTargetCapture.capture(candidate(models)));
  }

  private ResolvedConfig candidate(Path models) {
    return new ResolvedConfigBuilder()
        .putDefault("justsearch.embed.backend", "onnx")
        .putDefault(
            "justsearch.embed.onnx.model_path", models.resolve("embedding").toString())
        .putDefault("justsearch.splade.model_path", models.resolve("splade").toString())
        .putDefault("justsearch.ner.model_path", models.resolve("ner").toString())
        .build();
  }

  private Path createModelSet(String name, String embeddingBytes) throws IOException {
    Path root = Files.createDirectories(tempDir.resolve(name));
    createModel(root.resolve("embedding"), embeddingBytes, false);
    createModel(root.resolve("splade"), "splade", true);
    createModel(root.resolve("ner"), "ner", false);
    return root;
  }

  private static void createModel(Path directory, String modelBytes, boolean splade)
      throws IOException {
    Files.createDirectories(directory);
    Files.write(directory.resolve("model.onnx"), graphBytes(modelBytes));
    Files.writeString(directory.resolve("tokenizer.json"), "{}", StandardCharsets.UTF_8);
    if (splade) {
      Files.writeString(directory.resolve("vocab.txt"), "token", StandardCharsets.UTF_8);
    }
  }

  private static byte[] graphBytes(String value) {
    byte[] label = value.getBytes(StandardCharsets.UTF_8);
    var model = new java.io.ByteArrayOutputStream();
    model.writeBytes(new byte[] {58, 0, 18, (byte) label.length});
    model.writeBytes(label);
    return model.toByteArray();
  }

  private static String sha256(String value) throws NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(graphBytes(value)));
  }
}
