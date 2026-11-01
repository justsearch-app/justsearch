/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.ort.CapabilityRequirements;
import io.justsearch.ort.ModelCapabilityResolver;
import io.justsearch.ort.ModelManifest;
import io.justsearch.ort.OnnxExternalData;
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
      return compute(directory, role, ModelManifest.loadOrDefault(directory)
          .resolveExistingModelFile(directory));
    } catch (RuntimeException unreadable) {
      return Optional.empty();
    }
  }

  /** Uses the exact selected graph, even when another variant exists beside it. */
  public static Optional<String> compute(Path directory, String role, Path graph) {
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
      Path selected = graph.toAbsolutePath().normalize();
      var externalPaths = new TreeSet<Path>();
      for (String location : OnnxExternalData.externalDataLocations(selected)) {
        Path relative = Path.of(location);
        if (relative.isAbsolute()) return Optional.empty();
        for (Path part : relative) {
          if ("..".equals(part.toString())) return Optional.empty();
        }
        externalPaths.add(selected.getParent().resolve(relative).normalize());
      }
      try (var files = Files.newDirectoryStream(selected.getParent(), "*.onnx_data")) {
        for (Path file : files) externalPaths.add(file);
      }
      for (Path path : externalPaths) {
        if (!path.startsWith(root)
            || (Files.exists(path) && !path.toRealPath().startsWith(root.toRealPath()))) {
          return Optional.empty();
        }
      }
      paths.addAll(externalPaths);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path path : paths) {
        byte[] name = root.relativize(path).toString().getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());
        digest.update(name);
        if (Files.notExists(path)) {
          digest.update((byte) 0);
        } else {
          digest.update((byte) 1);
          long size = Files.size(path);
          digest.update(java.nio.ByteBuffer.allocate(8).putLong(size).array());
          try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            long readBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
              digest.update(buffer, 0, read);
              readBytes += read;
            }
            if (readBytes != size) return Optional.empty();
          }
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
