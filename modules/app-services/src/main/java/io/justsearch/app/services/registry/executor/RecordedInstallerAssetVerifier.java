/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Revalidates the files named by an installer-produced generation plan. */
public final class RecordedInstallerAssetVerifier {

  private static final int BUFFER_SIZE = 64 * 1024;

  private RecordedInstallerAssetVerifier() {}

  /**
   * Verifies every recorded model and asset against its current regular-file bytes.
   *
   * @throws IllegalArgumentException when a recorded file is missing, a symbolic link, not a
   *     regular file, or differs in size or SHA-256; also when one path is recorded with different
   *     byte identity values
   */
  public static void verify(RecordedInstallerGenerationPlan plan) {
    Objects.requireNonNull(plan, "plan");
    Map<Path, ExpectedIdentity> identities = new HashMap<>();
    for (RecordedInstallerGenerationPlan.ModelIdentity model : plan.models()) {
      verify(new ExpectedIdentity(model.path(), model.sizeBytes(), model.sha256(),
          "model " + model.packageId() + "/" + model.variantId()), identities);
    }
    for (RecordedInstallerGenerationPlan.AssetIdentity asset : plan.assets()) {
      verify(new ExpectedIdentity(asset.path(), asset.sizeBytes(), asset.sha256(),
          "asset " + asset.assetId()), identities);
    }
  }

  /** Freeze the metadata beside a selected retained ONNX file in the accepted activation. */
  public static List<RecordedInstallerGenerationPlan.AssetIdentity> captureSupportingAssets(
      String packageId, Path modelFile,
      RecordedInstallerGenerationPlan.AcquisitionProvenance provenance) {
    Objects.requireNonNull(modelFile, "modelFile");
    Path directory = modelFile.getParent();
    if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Retained model directory is unavailable: " + modelFile);
    }
    try (Stream<Path> files = Files.walk(directory)) {
      List<Path> supporting = files.peek(file -> {
            if (Files.isSymbolicLink(file)) {
              throw new IllegalArgumentException("Retained model asset is a symbolic link: " + file);
            }
          }).filter(file -> !file.equals(directory))
          .filter(file -> {
            String name = file.getFileName().toString();
            return name.endsWith(".json") || name.endsWith(".txt")
                || name.endsWith(".model") || name.endsWith(".vocab");
          }).sorted().toList();
      if (supporting.size() > RecordedInstallerGenerationPlan.MAX_ASSETS) {
        throw new IllegalArgumentException("Retained model has too many supporting assets");
      }
      List<RecordedInstallerGenerationPlan.AssetIdentity> captured =
          new java.util.ArrayList<>(supporting.size());
      for (Path file : supporting) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          throw new IllegalArgumentException("Retained supporting asset is unavailable: " + file);
        }
        ActualIdentity actual = readIdentity(file);
        String relative = directory.relativize(file).toString().replace('\\', '/');
        captured.add(new RecordedInstallerGenerationPlan.AssetIdentity(
            packageId + "/" + relative, file.toAbsolutePath().normalize(), actual.sha256(),
            actual.sizeBytes(), provenance));
      }
      return List.copyOf(captured);
    } catch (IOException failure) {
      throw new IllegalArgumentException("Retained model assets could not be read: " + modelFile,
          failure);
    }
  }

  private static void verify(ExpectedIdentity expected, Map<Path, ExpectedIdentity> identities) {
    ExpectedIdentity previous = identities.putIfAbsent(expected.path(), expected);
    if (previous != null && !previous.sameBytes(expected)) {
      throw new IllegalArgumentException("Duplicate installer file path has differing identity for "
          + previous.label() + " and " + expected.label() + ": " + expected.path());
    }

    try {
      if (!Files.isRegularFile(expected.path(), LinkOption.NOFOLLOW_LINKS)) {
        throw mismatch(expected, "missing or not a regular file");
      }

      ActualIdentity actual = readIdentity(expected.path());
      if (actual.sizeBytes() != expected.sizeBytes()) {
        throw mismatch(expected, "size drift: expected " + expected.sizeBytes()
            + " bytes, got " + actual.sizeBytes());
      }
      if (!actual.sha256().equals(expected.sha256())) {
        throw mismatch(expected, "SHA-256 drift: expected " + expected.sha256()
            + ", got " + actual.sha256());
      }
    } catch (IOException failure) {
      throw new IllegalArgumentException(expected.label() + " file could not be read: "
          + expected.path(), failure);
    }
  }

  private static ActualIdentity readIdentity(Path path) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    long size = 0;
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[BUFFER_SIZE];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read > 0) {
          digest.update(buffer, 0, read);
          size += read;
        }
      }
    }
    return new ActualIdentity(size, HexFormat.of().formatHex(digest.digest()));
  }

  private record ActualIdentity(long sizeBytes, String sha256) {}

  private static IllegalArgumentException mismatch(ExpectedIdentity expected, String reason) {
    return new IllegalArgumentException(expected.label() + " identity mismatch at "
        + expected.path() + ": " + reason);
  }

  private record ExpectedIdentity(Path path, long sizeBytes, String sha256, String label) {
    private boolean sameBytes(ExpectedIdentity other) {
      return sizeBytes == other.sizeBytes && sha256.equals(other.sha256);
    }
  }
}
