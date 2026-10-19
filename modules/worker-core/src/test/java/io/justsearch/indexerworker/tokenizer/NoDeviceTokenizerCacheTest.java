/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.tokenizer;

import static org.junit.jupiter.api.Assertions.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.Platform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;

/** Runs in its own masked JVM: native initialization and device visibility are process-wide. */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
@EnabledIfEnvironmentVariable(named = "CUDA_VISIBLE_DEVICES", matches = "-1")
final class NoDeviceTokenizerCacheTest {
  @Test
  void cpuTokenizerStartsAfterAGpuArchitectureCacheWasCreated(
      @TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
    String previous = System.getProperty("ENGINE_CACHE_DIR");
    System.setProperty("ENGINE_CACHE_DIR", dir.toString());
    try {
      Platform platform = Platform.detectPlatform("tokenizers");
      assertTrue(platform.getCudaArch() == null || platform.getCudaArch().isBlank(),
          "this check must run with CUDA_VISIBLE_DEVICES=-1");
      Path cache = dir.resolve("tokenizers")
          .resolve(platform.getVersion() + "-cpu-" + platform.getClassifier());
      Path gpuCache = Files.createDirectories(cache.resolve("89"));
      Files.writeString(gpuCache.resolve("retained-gpu-library"), "retained", StandardCharsets.UTF_8);
      // Replay against the old loader too: absence of the new preparation is not the oracle.
      try {
        Class.forName("io.justsearch.indexerworker.tokenizer.HuggingFaceNativeRuntime")
            .getMethod("prepareCpuCache").invoke(null);
      } catch (ClassNotFoundException beforeRepair) {
        // The native tokenizer assertion below reproduces the actual startup failure.
      }
      Path tokenizer = dir.resolve("tokenizer.json");
      Files.writeString(tokenizer, """
          {"version":"1.0","truncation":null,"padding":null,"added_tokens":[],
          "normalizer":null,"pre_tokenizer":{"type":"Whitespace"},"post_processor":null,
          "decoder":null,"model":{"type":"WordLevel","vocab":{"[UNK]":0,"hello":1},
          "unk_token":"[UNK]"}}
          """, StandardCharsets.UTF_8);
      assertDoesNotThrow(() -> {
        try (var nativeTokenizer = HuggingFaceTokenizer.newInstance(tokenizer)) {
          assertArrayEquals(new long[] {1}, nativeTokenizer.encode("hello").getIds());
        }
      }, "CPU native execution must survive a cache left by GPU use");
      assertEquals("retained", Files.readString(gpuCache.resolve("retained-gpu-library"),
          StandardCharsets.UTF_8));
    } finally {
      if (previous == null) System.clearProperty("ENGINE_CACHE_DIR");
      else System.setProperty("ENGINE_CACHE_DIR", previous);
    }
  }
}
