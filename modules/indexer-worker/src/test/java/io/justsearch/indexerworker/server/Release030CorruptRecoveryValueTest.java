/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Invalid values retain uncertain debt rather than stopping boot or dropping a root. */
@Timeout(180)
final class Release030CorruptRecoveryValueTest {
  @ParameterizedTest
  @ValueSource(strings = {"root-state", "format", "escaped-value"})
  void corruptValuesKeepOldContentAndOtherRootsRecover(String corruption, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path alpha = root.resolve("roots/alpha"), beta = root.resolve("roots/beta");
    Path heldAlpha = Files.move(alpha, root.resolve("held-alpha"));
    Path heldBeta = Files.move(beta, root.resolve("held-beta"));
    Path checkpoint = root.resolve("data/upgrade-030-recovery.properties");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          assertNotNull(server.lifecycleManagerForTests());
        }
        var properties = new Properties();
        try (var in = Files.newInputStream(checkpoint)) { properties.load(in); }
        if ("root-state".equals(corruption)) properties.setProperty("root|" + key(alpha), "unparseable");
        else if ("format".equals(corruption)) properties.setProperty("format", "unparseable");
        else properties.setProperty("retry|" + key(alpha), "bad-escape-placeholder");
        try (var out = Files.newOutputStream(checkpoint)) { properties.store(out, "one corrupt value"); }
        if ("escaped-value".equals(corruption)) {
          Files.writeString(checkpoint, Files.readString(checkpoint)
              .replace("=bad-escape-placeholder", "=" + "\\u" + "QQQQ"));
        }
        Files.move(heldBeta, beta);
        Path added = Files.writeString(beta.resolve("corrupt-value-new.txt"), "betavaluecobalt");
        try (var executors = new TestEngineExecutors();
            var server = assertDoesNotThrow(() -> boot(root, executors),
                "an unparsable sidecar value cannot stop recovery of other roots")) {
          activate(server);
          recover(server);
          assertTrue(Files.exists(checkpoint), "uncertain debt cannot be dropped");
          var recovered = new Properties();
          try (var in = Files.newInputStream(checkpoint)) { recovered.load(in); }
          assertEquals("pending", recovered.getProperty("root|" + key(alpha)),
              "a corrupt root status retains and normalizes that root's debt");
          assertTrue(search(server, "nebula").contains(key(alpha.resolve("file-01.txt"))),
              "invalid metadata never authorizes deletion of the uncertain root's old content");
          await(() -> { recover(server); return search(server, "betavaluecobalt").contains(key(added)); },
              "another root becomes current after bounded conservative recovery", 90);
          assertTrue(search(server, "nebula").contains(key(alpha.resolve("file-01.txt"))),
              "the still-unavailable root remains searchable after the other root recovers");
        }
      }
    } finally {
      if (Files.exists(heldAlpha)) Files.move(heldAlpha, alpha);
      if (Files.exists(heldBeta)) Files.move(heldBeta, beta);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static void recover(KnowledgeServer server) throws Exception {
    var method = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
    method.setAccessible(true); method.invoke(server);
  }
}
