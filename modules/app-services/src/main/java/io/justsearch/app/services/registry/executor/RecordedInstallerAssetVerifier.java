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
import java.util.Map;
import java.util.Objects;

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

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long actualSize = 0;
      try (InputStream input = Files.newInputStream(expected.path(), LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
          if (read > 0) {
            digest.update(buffer, 0, read);
            actualSize += read;
          }
        }
      }
      if (actualSize != expected.sizeBytes()) {
        throw mismatch(expected, "size drift: expected " + expected.sizeBytes()
            + " bytes, got " + actualSize);
      }
      String actualSha256 = HexFormat.of().formatHex(digest.digest());
      if (!actualSha256.equals(expected.sha256())) {
        throw mismatch(expected, "SHA-256 drift: expected " + expected.sha256()
            + ", got " + actualSha256);
      }
    } catch (IOException failure) {
      throw new IllegalArgumentException(expected.label() + " file could not be read: "
          + expected.path(), failure);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

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
