/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class ModelAssetFingerprintTest {
  @TempDir Path temp;

  @ParameterizedTest
  @CsvSource({"embedding,6d3c37e180bee9736c8cd2e4f1b75faa7bead572ae44a370f519ca1fa6726e81", "splade,e00b429e40b83040209a54c2fd68b55938e995797df7e32fffcd5c8bee72871c", "ner,f2920d0a83b5963172868f71847109a1369bfa1c7f3e653338d4b24ddff71a9a"})
  void selfContainedModelKeepsBaselineIdentity(String role, String expected) throws Exception {
    String expectedIdentity = java.io.File.separatorChar == '\\' && "embedding".equals(role)
        ? "d2182018faecfb8e160090ce75b828459b5dd00d35306af7d3ca978e39a30bf7" : expected;
    Path model = Files.createDirectory(temp.resolve("model"));
    Files.write(model.resolve("model.onnx"), new byte[] {58, 0});
    Files.writeString(model.resolve("tokenizer.json"), "unchanged tokenizer");
    Files.writeString(model.resolve("config.json"), "unchanged configuration");
    assertEquals(expectedIdentity, ModelAssetFingerprint.compute(model, role).orElseThrow());
    if (!System.getProperty("os.name").startsWith("Windows")) {
      Path shared = Files.writeString(temp.resolve("shared-tokenizer.json"), "unchanged tokenizer");
      Files.delete(model.resolve("tokenizer.json"));
      Files.createSymbolicLink(model.resolve("tokenizer.json"), shared);
      assertEquals(expectedIdentity, ModelAssetFingerprint.compute(model, role).orElseThrow(),
          "existing companion symlinks retain their identity");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "splade", "ner"})
  void everyReferencedFileAndConventionalSidecarAffectsIdentity(String role) throws Exception {
    Files.write(temp.resolve("model.onnx"), graph("weights.bin", "nested/other.bin"));
    Files.writeString(temp.resolve("weights.bin"), "first");
    Files.createDirectories(temp.resolve("nested"));
    Files.writeString(temp.resolve("nested/other.bin"), "second");
    Path conventional = Files.writeString(temp.resolve("unlisted.onnx_data"), "third");
    Optional<String> original = ModelAssetFingerprint.compute(temp, role);
    assertTrue(original.isPresent());
    for (String name : new String[] {"weights.bin", "nested/other.bin", "unlisted.onnx_data"}) {
      Path weights = temp.resolve(name);
      String old = Files.readString(weights);
      Files.writeString(weights, "changed " + old);
      assertNotEquals(original, ModelAssetFingerprint.compute(temp, role), name);
      Files.writeString(weights, old);
      assertEquals(original, ModelAssetFingerprint.compute(temp, role));
      Files.delete(weights);
      Optional<String> absent = ModelAssetFingerprint.compute(temp, role);
      assertTrue(absent.isPresent(), "missing weights must be recorded as absent");
      assertNotEquals(original, absent);
      Files.writeString(weights, old);
    }
    Files.delete(conventional);
    Files.createDirectory(conventional);
    assertTrue(ModelAssetFingerprint.compute(temp, role).isEmpty(), "unreadable weights are unresolved");
  }

  @ParameterizedTest
  @ValueSource(strings = {"../outside.bin", "/outside.bin", "nested/../outside.bin"})
  void escapingExternalReferenceIsUnresolved(String location) throws Exception {
    Files.write(temp.resolve("model.onnx"), graph(location));
    assertTrue(ModelAssetFingerprint.compute(temp, "embedding").isEmpty());
  }

  @Test
  @org.junit.jupiter.api.condition.DisabledOnOs(
      value = org.junit.jupiter.api.condition.OS.WINDOWS,
      disabledReason = "Unprivileged Windows cannot create symbolic links")
  void externalSymlinkOutsideModelDirectoryIsUnresolved() throws Exception {
    Path model = Files.createDirectory(temp.resolve("model"));
    Files.write(model.resolve("model.onnx"), graph("weights.bin"));
    Path outside = Files.writeString(temp.resolve("outside.bin"), "external weights");
    Files.createSymbolicLink(model.resolve("weights.bin"), outside);
    assertTrue(ModelAssetFingerprint.compute(model, "embedding").isEmpty());
  }

  @Test
  void malformedOrIncompleteReferencesAreUnresolved() throws Exception {
    for (byte[] graph : new byte[][] {
        {58, 127}, {58, 4, 42, 2, 112, 1}, {58, 4, 42, 2, 106, 0}}) {
      Files.write(temp.resolve("model.onnx"), graph);
      assertTrue(ModelAssetFingerprint.compute(temp, "embedding").isEmpty());
    }
  }

  private static byte[] graph(String... locations) {
    var graph = new ByteArrayOutputStream();
    for (String location : locations) {
      byte[] entry = join(field(1, "location".getBytes(StandardCharsets.UTF_8)),
          field(2, location.getBytes(StandardCharsets.UTF_8)));
      graph.writeBytes(field(5, join(field(13, entry), new byte[] {112, 1})));
    }
    return field(7, graph.toByteArray());
  }

  private static byte[] field(int number, byte[] value) {
    assertTrue(value.length < 128);
    return join(new byte[] {(byte) ((number << 3) | 2), (byte) value.length}, value);
  }

  private static byte[] join(byte[] first, byte[] second) {
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(first); bytes.writeBytes(second);
    return bytes.toByteArray();
  }
}
