/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelVariant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A temporary AI home with a small registry whose hashes match test bytes, so removal runs against
 * real files in a temporary models folder only (never the real models folder).
 */
final class RemovalModelsFixture {
  static final byte[] RERANK_FP32 = bytes("reranker fp32 weights");
  static final byte[] RERANK_FP16 = bytes("reranker fp16 weights!");
  static final byte[] TOKENIZER = bytes("{\"tokenizer\":true}");
  static final byte[] CONFIG = bytes("{\"config\":1}");
  static final byte[] CHAT = bytes("answer model weights gguf");
  static final byte[] PROJECTOR = bytes("picture reading projector");
  static final byte[] EMBED = bytes("embedding weights");

  final Path home;
  final Path models;
  final Path registryFile;
  final ModelRegistry registry;

  RemovalModelsFixture(Path home) throws Exception {
    this(home, Function.identity());
  }

  /** {@code edit} may rewrite the registry JSON before it is loaded. */
  RemovalModelsFixture(Path home, Function<String, String> edit) throws Exception {
    this.home = home;
    this.models = home.resolve("models");
    Files.createDirectories(models);
    this.registryFile = home.resolve("registry.json");
    Files.writeString(registryFile, edit.apply(registryJson()));
    this.registry = ModelRegistryLoader.loadFromFile(registryFile);
  }

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static String sha(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))
          .toUpperCase(java.util.Locale.ROOT);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static String file(String name, byte[] data) {
    return "{\"filename\":\"" + name + "\",\"sha256\":\"" + sha(data) + "\",\"sizeBytes\":"
        + data.length + ",\"downloadUrl\":\"http://127.0.0.1:9/" + name + "\"}";
  }

  private static String variant(String name, String precision, String ep, byte[] data) {
    return "{\"filename\":\"" + name + "\",\"precision\":\"" + precision + "\",\"targetEP\":\"" + ep
        + "\",\"sha256\":\"" + sha(data) + "\",\"sizeBytes\":" + data.length
        + ",\"downloadUrl\":\"http://127.0.0.1:9/" + name + "\"}";
  }

  static String registryJson() {
    return "{\"schemaVersion\":2,\"purpose\":\"removal fixture\",\"packages\":["
        + "{\"id\":\"embedding\",\"necessity\":\"required\",\"label\":\"Embedding model\","
        + "\"targetDir\":\"onnx/embed\",\"tier\":\"retrieval-core\",\"minVramBytes\":0,"
        + "\"variants\":[" + variant("model.onnx", "FP32", "CPU", EMBED) + "],\"supportingFiles\":[]},"
        + "{\"id\":\"splade\",\"necessity\":\"improves-results\",\"label\":\"Sparse retrieval (SPLADE)\","
        + "\"targetDir\":\"splade/x\",\"tier\":\"retrieval-enrichment\",\"minVramBytes\":0,"
        + "\"variants\":[],\"supportingFiles\":[]},"
        + "{\"id\":\"reranker\",\"necessity\":\"improves-results\",\"label\":\"Search reranker\","
        + "\"targetDir\":\"onnx/reranker\",\"tier\":\"retrieval-enrichment\",\"minVramBytes\":0,"
        + "\"variants\":[" + variant("model.onnx", "FP32", "CPU", RERANK_FP32) + ","
        + variant("model_fp16.onnx", "FP16", "CUDA", RERANK_FP16) + "],"
        + "\"supportingFiles\":[" + file("tokenizer.json", TOKENIZER) + "," + file("config.json", CONFIG) + "]},"
        + "{\"id\":\"chat\",\"necessity\":\"adds-feature\",\"label\":\"Chat model\",\"targetDir\":\"\","
        + "\"tier\":\"llm\",\"minVramBytes\":0,"
        + "\"variants\":[" + variant("answer.gguf", "GGUF", "LLAMA_SERVER", CHAT) + "],"
        + "\"supportingFiles\":[" + file("mmproj-F16.gguf", PROJECTOR) + "]}"
        + "]}";
  }

  ModelPackage pkg(String id) {
    return registry.findPackage(id);
  }

  /** The canonical folder of one reranker variant (0 = FP32, 1 = FP16). */
  Path rerankerDir(int variant) {
    ModelPackage reranker = pkg("reranker");
    ModelVariant v = reranker.variants().get(variant);
    return models.resolve(InstallPlanner.effectiveTargetDir(reranker, v));
  }

  String rerankerRel(int variant, String name) {
    ModelPackage reranker = pkg("reranker");
    return InstallPlanner.effectiveTargetDir(reranker, reranker.variants().get(variant)) + "/" + name;
  }

  /** Writes both retained reranker variants with their supporting files; returns the paths. */
  Map<String, Path> writeReranker() throws Exception {
    Path a = rerankerDir(0);
    Path b = rerankerDir(1);
    Files.createDirectories(a);
    Files.createDirectories(b);
    Files.write(a.resolve("model.onnx"), RERANK_FP32);
    Files.write(a.resolve("tokenizer.json"), TOKENIZER);
    Files.write(a.resolve("config.json"), CONFIG);
    Files.write(b.resolve("model_fp16.onnx"), RERANK_FP16);
    Files.write(b.resolve("tokenizer.json"), TOKENIZER);
    Files.write(b.resolve("config.json"), CONFIG);
    return Map.of("fp32", a.resolve("model.onnx"), "fp16", b.resolve("model_fp16.onnx"));
  }

  void writeChat() throws Exception {
    Files.write(models.resolve("answer.gguf"), CHAT);
    Files.write(models.resolve("mmproj-F16.gguf"), PROJECTOR);
  }

  List<Path> stagedFiles() throws Exception {
    Path folder = models.resolve(ModelFileRemoval.REMOVAL_FOLDER);
    if (!Files.isDirectory(folder)) return List.of();
    try (var listing = Files.list(folder)) {
      return listing.toList();
    }
  }
}
