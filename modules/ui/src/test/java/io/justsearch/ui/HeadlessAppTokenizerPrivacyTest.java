/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class HeadlessAppTokenizerPrivacyTest {
  @ParameterizedTest
  @CsvSource(value = {"unset,unset"}, nullValues = "unset")
  void entryDefaultsDjlToPrivateOfflineLoading(
      String tracking, String offline, @TempDir Path dir) throws Exception {
    Path output = dir.resolve("privacy.log");
    Path arguments = dir.resolve("java.args");
    Files.writeString(arguments, "-cp\n\""
        + System.getProperty("java.class.path").replace('\\', '/') + "\"\n"
        + Probe.class.getName() + "\n"
        + (tracking == null ? "unset" : tracking) + "\n"
        + (offline == null ? "unset" : offline) + "\n");
    Process child = new ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "@" + arguments)
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "entry initialization must be bounded");
      assertEquals(0, child.exitValue(), Files.readString(output));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "privacy probe must exit");
      }
    }
  }

  /** A fresh JVM makes this independent of other tests initializing HeadlessApp first. */
  public static final class Probe {
    public static void main(String[] args) throws Exception {
      String[] keys = {"OPT_OUT_TRACKING", "ai.djl.offline"};
      for (int i = 0; i < keys.length; i++) {
        if ("unset".equals(args[i])) {
          System.clearProperty(keys[i]);
        } else {
          System.setProperty(keys[i], args[i]);
        }
      }
      Class.forName("io.justsearch.ui.HeadlessApp");
      for (int i = 0; i < keys.length; i++) {
        String expected = "unset".equals(args[i]) ? "true" : args[i];
        if (!expected.equals(System.getProperty(keys[i]))) {
          throw new AssertionError(keys[i] + " expected " + expected
              + " but was " + System.getProperty(keys[i]));
        }
      }
    }
  }
}
