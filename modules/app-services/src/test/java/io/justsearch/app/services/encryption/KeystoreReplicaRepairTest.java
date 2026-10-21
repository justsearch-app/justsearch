/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("cross-platform")
class KeystoreReplicaRepairTest {
  @TempDir Path dir;
  private Path primary() { return dir.resolve("encryption/keystore.json"); }
  private Path replica() { return dir.resolve("encryption/keystore.replica.json"); }
  private DataKeyManager manager() { return new DataKeyManager(new EncryptionKeystore(dir)); }
  private static char[] pass(String value) { return value.toCharArray(); }

  @Test
  void cleanRollbackRetainsReplicaThatRestoresOldCredentials() throws Exception {
    var keys = manager();
    String recovery = keys.setup(pass("old"));
    byte[] previous = Files.readAllBytes(primary());
    try (var ignored = mockStatic(AtomicFileWrites.class, invocation -> {
      if (invocation.getMethod().getName().equals("replaceStrict")
          && invocation.getMethod().getParameterCount() == 2 && replica().equals(invocation.getArgument(0))) {
        throw new IOException("replica refused");
      }
      return invocation.callRealMethod();
    })) {
      assertThrows(RuntimeException.class, () -> keys.changePassphrase(pass("old"), pass("new")));
    }
    assertTrue(Files.isRegularFile(replica()), "clean rollback must retain the old replica");
    assertArrayEquals(previous, Files.readAllBytes(replica()));
    Files.delete(primary());
    var restarted = manager();
    assertDoesNotThrow(() -> restarted.recover(recovery));
    assertArrayEquals(keys.dek(), restarted.dek());
    restarted.lock();
    assertDoesNotThrow(() -> restarted.unlock(pass("old")));
    restarted.lock();
    assertThrows(WrongPassphraseException.class, () -> restarted.unlock(pass("new")));
  }

  @ParameterizedTest(name = "startupRepairsReplicaFromAuthoritativePrimary({0})")
  @ValueSource(strings = {"missing", "empty", "corrupt", "stale", "future"})
  void startupRepairsReplicaFromAuthoritativePrimary(String damage) throws Exception {
    var keys = manager();
    String oldRecovery = keys.setup(pass("old"));
    byte[] old = Files.readAllBytes(primary());
    keys.changePassphrase(pass("old"), pass("new"));
    String currentRecovery = keys.regenerateRecovery();
    byte[] current = Files.readAllBytes(primary());
    switch (damage) {
      case "missing" -> Files.delete(replica());
      case "empty" -> Files.write(replica(), new byte[0]);
      case "corrupt" -> Files.writeString(replica(), "broken JSON");
      case "stale" -> Files.write(replica(), old);
      case "future" -> Files.writeString(replica(), Files.readString(primary()).replace("\"version\":1", "\"version\":2"));
      default -> throw new IllegalArgumentException(damage);
    }
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EncryptionKeystore.class);
    var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    messages.start();
    logger.addAppender(messages);
    try {
      var restarted = assertDoesNotThrow(this::manager);
      assertTrue(Files.isRegularFile(replica()), "startup must repair the missing replica");
      assertArrayEquals(current, Files.readAllBytes(primary()), "the valid primary is authoritative");
      assertArrayEquals(current, Files.readAllBytes(replica()), "replica must carry only current wraps");
      assertTrue(messages.list.stream().anyMatch(e -> e.getFormattedMessage()
          .contains("Repaired encryption keystore recovery replica from authoritative primary")));
      restarted.recover(currentRecovery);
      assertArrayEquals(keys.dek(), restarted.dek());
      assertRevokedThroughBothCopies("password", oldRecovery, keys.dek());
      for (Path copy : List.of(primary(), replica())) {
        var record = JsonMapper.builder().build().readValue(Files.readAllBytes(copy), KeystoreRecord.class);
        assertThrows(WrongPassphraseException.class, () -> EncryptionEnvelope.recover(record, oldRecovery));
      }
    } finally {
      logger.detachAppender(messages);
      messages.stop();
    }
  }

  @Test
  void lockedAbandonedTempWarnsAndRetriesWithoutBlockingSaves() throws Exception {
    var keys = manager();
    keys.setup(pass("old"));
    Path leftover = primary().resolveSibling("keystore.json.locked.tmp");
    Files.copy(primary(), leftover);
    var created = new ArrayList<Path>();
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EncryptionKeystore.class);
    var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    messages.start();
    logger.addAppender(messages);
    try (var ignored = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("delete") && leftover.equals(invocation.getArgument(0))) {
        throw new AccessDeniedException(leftover.toString());
      }
      Object result = invocation.callRealMethod();
      if (invocation.getMethod().getName().equals("createTempFile")) created.add((Path) result);
      return result;
    })) {
      assertDoesNotThrow(() -> keys.changePassphrase(pass("old"), pass("new")), "locked orphan must not block saves");
      assertDoesNotThrow(keys::regenerateRecovery, "later saves must retry cleanup and still succeed");
      assertTrue(Files.exists(leftover));
      assertEquals(4, created.size());
      assertEquals(created.size(), new HashSet<>(created).size(), "each publication uses a unique temp name");
      assertEquals(2, messages.list.stream().filter(e -> e.getFormattedMessage()
          .contains("Could not remove abandoned encryption keystore temp")).count());
    } finally {
      logger.detachAppender(messages);
      messages.stop();
    }
    keys.changePassphrase(pass("new"), pass("final"));
    assertFalse(Files.exists(leftover), "cleanup is retried on the next save");
    assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()));
  }

  @Test
  void killBetweenPublicationsRepairsCurrentWrapsOnRestart() throws Exception {
    for (String action : List.of("password", "recovery")) {
      for (int step : List.of(2, 3)) {
        var keys = manager();
        if (keys.enabled()) keys.unlock(pass("old"));
        else keys.setup(pass("old"));
        String oldRecovery = keys.regenerateRecovery();
        Path log = dir.resolve(action + step + ".log");
        Path args = dir.resolve("child.args");
        Files.writeString(args, "-XX:+EnableDynamicAgentLoading\n-cp\n\""
            + System.getProperty("java.class.path").replace('\\', '/') + "\"\n");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Process child = new ProcessBuilder(java.toString(), "@" + args, KeystoreDurabilityTest.class.getName(),
            dir.toString(), Integer.toString(step), action).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
          while (child.isAlive() && System.nanoTime() < deadline
              && !Files.readString(log).contains("PUBLICATION PAUSED")) Thread.sleep(10);
          assertTrue(Files.readString(log).contains("PUBLICATION PAUSED"), Files.readString(log));
        } finally {
          child.destroyForcibly();
          assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        }
        var restarted = assertDoesNotThrow(this::manager);
        assertTrue(Files.isRegularFile(replica()), "restart after interrupted publication must repair replica");
        assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()));
        restarted.unlock(pass(action.equals("password") ? "new" : "old"));
        assertArrayEquals(keys.dek(), restarted.dek());
        assertRevokedThroughBothCopies(action, oldRecovery, keys.dek());
        if (action.equals("password")) restarted.changePassphrase(pass("new"), pass("old"));
      }
    }
  }

  private void assertRevokedThroughBothCopies(String action, String oldRecovery, byte[] dek) throws Exception {
    for (Path copy : List.of(primary(), replica())) {
      var record = JsonMapper.builder().build().readValue(Files.readAllBytes(copy), KeystoreRecord.class);
      if (action.equals("password")) {
        assertThrows(WrongPassphraseException.class, () -> EncryptionEnvelope.unlock(record, pass("old")));
        assertArrayEquals(dek, EncryptionEnvelope.unlock(record, pass("new")));
      } else {
        assertThrows(WrongPassphraseException.class, () -> EncryptionEnvelope.recover(record, oldRecovery));
        assertArrayEquals(dek, EncryptionEnvelope.unlock(record, pass("old")));
      }
    }
  }
}
