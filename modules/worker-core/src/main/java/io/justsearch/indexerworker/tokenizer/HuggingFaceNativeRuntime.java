/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.tokenizer;

import ai.djl.util.Platform;
import ai.djl.util.Utils;
import io.justsearch.configuration.PlatformPaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Prepares bundled CPU tokenizer files independently of CUDA architecture discovery. */
public final class HuggingFaceNativeRuntime {
  private static final List<String> WINDOWS_LIBRARIES = List.of(
      "libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll", "tokenizers.dll");

  private HuggingFaceNativeRuntime() {}

  public static synchronized void prepareCpuCache() {
    // Keep deliberate native-library/flavor overrides owned by DJL's loader.
    String override = Utils.getEnvOrSystemProperty("RUST_LIBRARY_PATH");
    String flavor = Utils.getEnvOrSystemProperty("RUST_FLAVOR");
    if (override != null && !override.isBlank()
        || flavor != null && !flavor.isBlank() && !flavor.equals("cpu")) return;
    Platform platform = Platform.detectPlatform("tokenizers");
    if (!PlatformPaths.isWindows() && !PlatformPaths.isLinux()) return;
    List<String> libraries = PlatformPaths.isWindows() ? WINDOWS_LIBRARIES
        : List.of(System.mapLibraryName("tokenizers"));
    Path cache = Utils.getEngineCacheDir("tokenizers")
        .resolve(platform.getVersion() + "-cpu-" + platform.getClassifier());
    try {
      Files.createDirectories(cache);
      // DJL 0.36's directory move accepts an existing destination as a completed extraction.
      // After GPU use that destination can contain only an architecture subdirectory, so a
      // subsequent CPU launch cannot load its libraries. Populate each missing CPU file instead;
      // retain architecture directories and files another live process may have loaded.
      for (String name : libraries) {
        Path target = cache.resolve(name);
        if (Files.isRegularFile(target)) continue;
        String resource = "/native/lib/" + platform.getClassifier() + "/cpu/" + name;
        try (InputStream input = Platform.class.getResourceAsStream(resource)) {
          if (input == null) throw new IOException("Bundled tokenizer library missing: " + resource);
          Path pending = Files.createTempFile(cache, name + ".", ".pending");
          try {
            Files.copy(input, pending, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
              Files.move(pending, target);
            } catch (FileAlreadyExistsException concurrentPreparation) {
              if (!Files.isRegularFile(target)) throw concurrentPreparation;
            }
          } finally {
            Files.deleteIfExists(pending);
          }
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot prepare CPU tokenizer libraries in " + cache, failure);
    }
  }
}
