/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.ort.CapabilityRequirements;
import io.justsearch.ort.ModelCapabilityResolver;
import io.justsearch.ort.ModelManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.TreeSet;

/** Identity of the companion inputs consumed by one encoder, including absent fallback files. */
public final class ModelAssetFingerprint {
  private ModelAssetFingerprint() {}

  public static Optional<String> compute(Path directory, String role) {
    try {
      Path root = directory.toAbsolutePath().normalize();
      ModelManifest manifest = ModelManifest.loadOrDefault(root);
      var paths = new TreeSet<Path>();
      paths.add(ModelCapabilityResolver.manifestPath(root));
      paths.add(root.resolve("ner".equals(role) ? manifest.tokenizer() : "tokenizer.json"));
      if ("splade".equals(role)) paths.add(root.resolve("vocab.txt"));
      if ("embedding".equals(role) || "ner".equals(role)) {
        paths.addAll(ModelCapabilityResolver.inputCandidates(root, manifest).forRequirements(
            "ner".equals(role) ? CapabilityRequirements.NER : CapabilityRequirements.EMBEDDING));
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path path : paths) {
        byte[] name = root.relativize(path).toString().getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());
        digest.update(name);
        if (Files.notExists(path)) {
          digest.update((byte) 0);
        } else {
          digest.update((byte) 1);
          byte[] bytes = Files.readAllBytes(path);
          digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length).array());
          digest.update(bytes);
        }
      }
      return Optional.of(HexFormat.of().formatHex(digest.digest()));
    } catch (IOException | RuntimeException unreadable) {
      return Optional.empty();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
