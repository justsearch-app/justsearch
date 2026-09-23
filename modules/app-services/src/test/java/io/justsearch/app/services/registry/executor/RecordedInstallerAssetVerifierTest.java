/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.settings.SettingsWitness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecordedInstallerAssetVerifierTest {

  @TempDir
  Path temp;

  @Test
  void verifiesModelAndAssetBytes() throws IOException {
    Path modelPath = write("embedding.onnx", "model-bytes");
    Path assetPath = write("tokenizer.json", "asset-bytes");

    RecordedInstallerGenerationPlan plan = plan(
        List.of(model("embedding", "fp32", modelPath, "model-bytes")),
        List.of(asset("tokenizer", assetPath, "asset-bytes")));

    assertDoesNotThrow(() -> RecordedInstallerAssetVerifier.verify(plan));
  }

  @Test
  void rejectsMissingModelUsingItsIdentity() throws IOException {
    Path modelPath = temp.resolve("missing.onnx").toAbsolutePath().normalize();
    Path assetPath = write("tokenizer.json", "asset-bytes");
    RecordedInstallerGenerationPlan plan = plan(
        List.of(model("embedding", "fp32", modelPath, "model-bytes")),
        List.of(asset("tokenizer", assetPath, "asset-bytes")));

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> RecordedInstallerAssetVerifier.verify(plan));

    assertTrue(failure.getMessage().contains("model embedding/fp32"), failure::getMessage);
  }

  @Test
  void rejectsAssetByteDriftUsingItsIdentity() throws IOException {
    Path modelPath = write("embedding.onnx", "model-bytes");
    Path assetPath = write("tokenizer.json", "asset-bytes");
    RecordedInstallerGenerationPlan plan = plan(
        List.of(model("embedding", "fp32", modelPath, "model-bytes")),
        List.of(asset("tokenizer", assetPath, "asset-bytes")));
    Files.writeString(assetPath, "drift-bytes");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> RecordedInstallerAssetVerifier.verify(plan));

    assertTrue(failure.getMessage().contains("asset tokenizer"), failure::getMessage);
  }

  @Test
  void rejectsSamePathWithDifferentByteIdentity() throws IOException {
    Path sharedPath = write("shared.bin", "model-bytes");
    RecordedInstallerGenerationPlan plan = plan(
        List.of(model("embedding", "fp32", sharedPath, "model-bytes")),
        List.of(asset("shared", sharedPath, "different-bytes")));

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> RecordedInstallerAssetVerifier.verify(plan));

    assertTrue(failure.getMessage().contains("model embedding/fp32"), failure::getMessage);
    assertTrue(failure.getMessage().contains("asset shared"), failure::getMessage);
  }

  @Test
  void rejectsSymbolicLinkWithoutReadingItsTarget() throws IOException {
    Path target = write("real.onnx", "model-bytes");
    Path link = temp.resolve("linked.onnx");
    createSymbolicLinkOrSkip(link, target);
    Path assetPath = write("tokenizer.json", "asset-bytes");
    RecordedInstallerGenerationPlan plan = plan(
        List.of(model("embedding", "fp32", link, "model-bytes")),
        List.of(asset("tokenizer", assetPath, "asset-bytes")));

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> RecordedInstallerAssetVerifier.verify(plan));

    assertTrue(failure.getMessage().contains("model embedding/fp32"), failure::getMessage);
  }

  private Path write(String fileName, String contents) throws IOException {
    Path path = temp.resolve(fileName).toAbsolutePath().normalize();
    Files.writeString(path, contents);
    return path;
  }

  private static RecordedInstallerGenerationPlan plan(
      List<RecordedInstallerGenerationPlan.ModelIdentity> models,
      List<RecordedInstallerGenerationPlan.AssetIdentity> assets) {
    Path root = models.getFirst().path().getParent();
    String operationKey = OperationKeys.generate(Clock.systemUTC());
    return new RecordedInstallerGenerationPlan(
        operationKey, "serving-generation",
        new RecordedRootPlan("serving-generation", List.of(new RecordedRootPlan.Root(
            root, "documents", true, false, List.of(), List.of()))),
        new IndexTargetSnapshot(sha256("{}"), "{}"), new SettingsWitness(1, operationKey),
        RecordedInstallerGenerationPlan.CandidateSettings.fromJson("{}"), models, assets,
        provenance());
  }

  private static RecordedInstallerGenerationPlan.ModelIdentity model(
      String packageId, String variantId, Path path, String expectedContents) {
    return new RecordedInstallerGenerationPlan.ModelIdentity(packageId, variantId, path,
        sha256(expectedContents), expectedContents.getBytes(StandardCharsets.UTF_8).length,
        provenance());
  }

  private static RecordedInstallerGenerationPlan.AssetIdentity asset(
      String assetId, Path path, String expectedContents) {
    return new RecordedInstallerGenerationPlan.AssetIdentity(
        assetId, path, sha256(expectedContents),
        expectedContents.getBytes(StandardCharsets.UTF_8).length, provenance());
  }

  private static RecordedInstallerGenerationPlan.AcquisitionProvenance provenance() {
    return new RecordedInstallerGenerationPlan.AcquisitionProvenance(
        RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY,
        "manifest-1", "a".repeat(64));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException unsupported) {
      assumeTrue(false, "symbolic links are unsupported by this filesystem: " + unsupported);
    } catch (FileSystemException failure) {
      String reason = String.valueOf(failure.getReason()).toLowerCase(Locale.ROOT);
      if (reason.contains("not supported") || reason.contains("not permitted")
          || reason.contains("privilege")) {
        assumeTrue(false, "symbolic links are unsupported in this environment: " + failure);
      }
      throw failure;
    }
  }
}
