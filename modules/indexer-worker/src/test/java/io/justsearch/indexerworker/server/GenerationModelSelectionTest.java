/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.index.IndexGenerationManager.GenerationManifest;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GenerationModelSelectionTest {
  @TempDir Path temp;

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

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(Files.readString(file).getBytes(StandardCharsets.UTF_8)));
  }
}
