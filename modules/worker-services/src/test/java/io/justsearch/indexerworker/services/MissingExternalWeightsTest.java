/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.embed.ModelAssetFingerprint;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** C3: selected external weights are required inputs, unlike optional fallback candidates. */
final class MissingExternalWeightsTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"embedding", "splade", "ner"})
  void missingReferencedWeightsLeaveCapturedCompanionUnresolved(String role) throws Exception {
    ResolvedConfig config = modelSet(role);
    var original = CandidateIndexTargetCapture.captureWithRuntimeInputs(config);
    assertNotNull(new SsotCommitMetadataSource(config, original.runtimeFingerprintInputs())
        .build().get(role + "_model_assets_sha256"));
    Files.delete(temp.resolve("models").resolve(role).resolve("weights.bin"));

    CandidateIndexTargetCapture.CaptureResult missing;
    try {
      missing = CandidateIndexTargetCapture.captureWithRuntimeInputs(config);
    } catch (IllegalStateException unresolved) {
      assertEquals("Candidate index target is indeterminate", unresolved.getMessage());
      return;
    }
    var metadata = new SsotCommitMetadataSource(config, missing.runtimeFingerprintInputs());
    assertNull(metadata.build().get(role + "_model_assets_sha256"),
        "missing selected weights cannot yield a companion identity");
    assertTrue(metadata.indeterminateFingerprintInputs().contains(role + "_model_assets_sha256"),
        "the selected model's companion identity must remain unresolved");
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"embedding", "splade", "ner"})
  void lexicalCommitAfterWeightRemovalKeepsCommittedCompanion(String role) throws Exception {
    ResolvedConfig config = modelSet(role);
    var captures = new AtomicInteger();
    Supplier<CommitMetadataSource> sources = () -> {
      captures.incrementAndGet();
      try {
        return source(config);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    };
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), sources,
        new JsonSchemaCommitMetadataValidator()).atPath(temp.resolve("index"))
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          "doc_id", "one", "doc_uid", "one#0", "title", "original", "content", "lexical text")));
      runtime.commitOps().commitAndTrack();
      String committed = runtime.latestCommitUserDataBestEffort().get(role + "_model_assets_sha256");
      String graph = committedGraph(runtime.latestCommitUserDataBestEffort(), role);
      assertNotNull(graph);
      assertNotNull(committed);
      Files.delete(temp.resolve("models").resolve(role).resolve("weights.bin"));
      int beforeCommit = captures.get();
      assertTrue(runtime.indexingCoordinator().updateDocument("one", Map.of("title", "changed")));
      runtime.commitOps().commitAndTrack();
      assertTrue(captures.get() > beforeCommit, "each commit must recapture the on-disk inputs");
      assertEquals(committed,
          runtime.latestCommitUserDataBestEffort().get(role + "_model_assets_sha256"),
          "a lexical commit must retain the committed identity while selected weights are missing");
      assertEquals(graph, committedGraph(runtime.latestCommitUserDataBestEffort(), role),
          "the selected graph identity must remain unchanged");
      assertEquals("changed", runtime.documentFieldOps().getDocumentField("one", "title"));
    }
  }

  private static String committedGraph(Map<String, String> metadata, String role) throws Exception {
    return new tools.jackson.databind.ObjectMapper()
        .readTree(metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY))
        .path(role + "_model_sha256").textValue();
  }

  private SsotCommitMetadataSource source(ResolvedConfig config) throws Exception {
    // Serving runtimes retain graph identity even when companion inputs cannot be resolved.
    var inputs = new SsotCommitMetadataSource.RuntimeFingerprintInputs(null,
        modelFingerprint("embedding"), modelFingerprint("splade"), modelFingerprint("ner"));
    return new SsotCommitMetadataSource(config, inputs);
  }

  private IndexFingerprint.ModelFingerprint modelFingerprint(String role) throws Exception {
    Path directory = temp.resolve("models").resolve(role);
    Path graph = directory.resolve("model.onnx");
    String sha = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(graph)));
    return IndexFingerprint.ModelFingerprint.of(true, Optional.of(sha))
        .withAssets(ModelAssetFingerprint.compute(directory, role, graph));
  }

  private ResolvedConfig modelSet(String externalRole) throws Exception {
    Path models = Files.createDirectories(temp.resolve("models"));
    for (String role : new String[] {"embedding", "splade", "ner"}) {
      Path model = Files.createDirectory(models.resolve(role));
      Files.write(model.resolve("model.onnx"), role.equals(externalRole)
          ? externalGraph() : new byte[] {58, 0});
      Files.writeString(model.resolve("weights.bin"), "selected external weights");
      Files.writeString(model.resolve("tokenizer.json"), "{}");
      Files.writeString(model.resolve("vocab.txt"), "token");
    }
    return new ResolvedConfigBuilder()
        .putDefault("justsearch.embed.backend", "onnx")
        .putDefault("justsearch.embed.onnx.model_path", models.resolve("embedding").toString())
        .putDefault("justsearch.splade.model_path", models.resolve("splade").toString())
        .putDefault("justsearch.ner.model_path", models.resolve("ner").toString()).build();
  }

  private static byte[] externalGraph() {
    byte[] location = "weights.bin".getBytes(StandardCharsets.UTF_8);
    var graph = new ByteArrayOutputStream();
    graph.writeBytes(new byte[] {58, (byte) (location.length + 18), 42,
        (byte) (location.length + 16), 106, (byte) (location.length + 12), 10, 8});
    graph.writeBytes("location".getBytes(StandardCharsets.UTF_8));
    graph.write(18); graph.write(location.length); graph.writeBytes(location);
    graph.writeBytes(new byte[] {112, 1});
    return graph.toByteArray();
  }
}
