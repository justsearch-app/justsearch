/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class HeadlessAppTokenizerPrivacyTest {
  @ParameterizedTest(name = "{displayName} [{index}]")
  @CsvSource(value = {"unset,unset,unset", "false,false,cu122", "true,true,cpu"}, nullValues = "unset")
  void entryDefaultsDjlToPrivateOfflineLoading(
      String tracking, String offline, String flavor, @TempDir Path dir) throws Exception {
    Path output = dir.resolve("privacy.log");
    Path arguments = dir.resolve("java.args");
    Files.writeString(arguments, "-cp\n\""
        + System.getProperty("java.class.path").replace('\\', '/') + "\"\n"
        + Probe.class.getName() + "\n"
        + (tracking == null ? "unset" : tracking) + "\n"
        + (offline == null ? "unset" : offline) + "\n"
        + (flavor == null ? "unset" : flavor) + "\n");
    runProbe(arguments, output);
  }

  @Test
  void entryLoadsBundledCpuTokenizerWithoutNetwork(@TempDir Path dir) throws Exception {
    Path arguments = dir.resolve("java.args");
    Files.writeString(arguments, "-cp\n\""
        + System.getProperty("java.class.path").replace('\\', '/') + "\"\n"
        + NativeProbe.class.getName() + "\n\"" + dir.toString().replace('\\', '/') + "\"\n");
    runProbe(arguments, dir.resolve("native.log"));
  }

  @ParameterizedTest(name = "{displayName} [{index}]")
  @CsvSource({"RUST_FLAVOR,cu122", "RUST_LIBRARY_PATH,/operator/libtokenizers.so"})
  void entryRefusesOverridesThatBypassCpuFlavor(
      String key, String value, @TempDir Path dir) throws Exception {
    Path arguments = dir.resolve("java.args");
    Files.writeString(arguments, "-cp\n\""
        + System.getProperty("java.class.path").replace('\\', '/') + "\"\n"
        + Probe.class.getName() + "\nunset\nunset\nunset\n");
    runProbe(arguments, dir.resolve("refusal.log"), java.util.Map.of(key, value));
  }

  private static void runProbe(Path arguments, Path output) throws Exception {
    runProbe(arguments, output, java.util.Map.of());
  }

  private static void runProbe(
      Path arguments, Path output, java.util.Map<String, String> overrides) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "@" + arguments)
        .redirectErrorStream(true).redirectOutput(output.toFile());
    // Exercise the entry point's settings, independently of the invoking test runner.
    for (String key : new String[] {
        "RUST_FLAVOR", "RUST_LIBRARY_PATH", "DJL_OFFLINE", "ENGINE_CACHE_DIR", "OPT_OUT_TRACKING"}) {
      builder.environment().remove(key);
    }
    builder.environment().putAll(overrides);
    Process child = builder.start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "entry initialization must be bounded");
      if (overrides.isEmpty()) {
        assertEquals(0, child.exitValue(), Files.readString(output));
      } else {
        assertTrue(child.exitValue() != 0, "unsafe tokenizer overrides must refuse startup");
        assertTrue(Files.readString(output).contains("Tokenizers require bundled CPU natives"),
            Files.readString(output));
      }
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "privacy probe must exit");
      }
    }
  }

  /** No cached library or network may hide a missing bundled CPU native on first startup. */
  public static final class NativeProbe {
    public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      System.setProperty("ENGINE_CACHE_DIR", dir.resolve("empty-cache").toString());
      System.setProperty("OPT_OUT_TRACKING", "true");
      System.setProperty("ai.djl.offline", "false");
      System.clearProperty("RUST_FLAVOR");
      System.clearProperty("RUST_LIBRARY_PATH");
      java.net.URL.setURLStreamHandlerFactory(protocol -> {
        if (!protocol.equals("http") && !protocol.equals("https")) return null;
        return new java.net.URLStreamHandler() {
          @Override
          protected java.net.URLConnection openConnection(java.net.URL url) {
            throw new AssertionError("Tokenizer startup must not fetch " + url);
          }
        };
      });
      Class.forName("io.justsearch.ui.HeadlessApp");
      Path tokenizer = dir.resolve("tokenizer.json");
      Files.writeString(tokenizer, """
          {"version":"1.0","truncation":null,"padding":null,"added_tokens":[],
          "normalizer":null,"pre_tokenizer":{"type":"Whitespace"},"post_processor":null,
          "decoder":null,"model":{"type":"WordLevel","vocab":{"[UNK]":0,"hello":1},
          "unk_token":"[UNK]"}}
          """);
      try (var nativeTokenizer =
          ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.newInstance(tokenizer)) {
        if (!java.util.Arrays.equals(new long[] {1}, nativeTokenizer.encode("hello").getIds())) {
          throw new AssertionError("Bundled CPU tokenizer must encode successfully");
        }
      }
      Path maps = Path.of("/proc/self/maps");
      if (Files.isRegularFile(maps)) {
        String loaded = Files.readString(maps);
        if (java.util.regex.Pattern.compile("/[^\\n]*(?:libcudart|libcublas|libcudnn)[^\\n]*")
            .matcher(loaded).find()) {
          throw new AssertionError("Tokenization loaded CUDA runtime libraries:\n" + loaded);
        }
      }
    }
  }

  /** A fresh JVM makes this independent of other tests initializing HeadlessApp first. */
  public static final class Probe {
    public static void main(String[] args) throws Exception {
      String[] keys = {"OPT_OUT_TRACKING", "ai.djl.offline", "RUST_FLAVOR"};
      for (int i = 0; i < keys.length; i++) {
        if ("unset".equals(args[i])) {
          System.clearProperty(keys[i]);
        } else {
          System.setProperty(keys[i], args[i]);
        }
      }
      System.setProperty("ai.djl.util.cuda.fork", "false");
      Class.forName("io.justsearch.ui.HeadlessApp");
      for (int i = 0; i < keys.length; i++) {
        String expected = keys[i].equals("RUST_FLAVOR") ? "cpu"
            : "unset".equals(args[i]) ? "true" : args[i];
        if (!expected.equals(System.getProperty(keys[i]))) {
          throw new AssertionError(keys[i] + " expected " + expected
              + " but was " + System.getProperty(keys[i]));
        }
      }
      if (!Boolean.getBoolean("ai.djl.util.cuda.fork")) {
        throw new AssertionError("CUDA discovery must run outside the Engine JVM");
      }
    }
  }
}
