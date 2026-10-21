/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Owner scenarios F3 S1/S2/S3b/S11: a lost key must never become a fresh empty installation. */
@Tag("cross-platform")
class KeystoreDurabilityTest {
  @TempDir Path dir;

  private Path primary() { return dir.resolve("encryption/keystore.json"); }
  private Path replica() { return dir.resolve("encryption/keystore.replica.json"); }
  private DataKeyManager manager() { return new DataKeyManager(new EncryptionKeystore(dir)); }
  private static char[] pass(String value) { return value.toCharArray(); }

  @Test
  void savesBothCopiesThroughForceBeforeMoveWriter() throws Exception {
    var writes = new ArrayList<Path>();
    try (var ignored = mockStatic(AtomicFileWrites.class, invocation -> {
      if (invocation.getMethod().getName().equals("replaceStrict")
          && invocation.getMethod().getParameterCount() == 2) {
        writes.add(invocation.getArgument(0));
      }
      return invocation.callRealMethod();
    })) {
      manager().setup(pass("old"));
    }
    // AtomicFileWritesTest.strictReplacementForcesBytesBeforeMove separately pins force -> move.
    assertEquals(List.of(primary(), replica()), writes);
    assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()));
  }

  @Test
  void setupCreatesValidatedCurrentReplica() throws Exception {
    var keys = manager();
    String recovery = keys.setup(pass("old"));
    assertTrue(Files.isRegularFile(replica()), "successful setup must create a recovery replica");
    var record = JsonMapper.builder().build().readValue(Files.readAllBytes(replica()), KeystoreRecord.class);
    assertEquals(keys.keystoreRecord(), record);
    assertArrayEquals(keys.dek(), EncryptionEnvelope.unlock(record, pass("old")));
    assertArrayEquals(keys.dek(), EncryptionEnvelope.recover(record, recovery));
  }

  @Test
  void successfulRotationsRevokeCredentialsThroughEitherCopy() throws Exception {
    var keys = manager();
    String oldRecovery = keys.setup(pass("old"));
    byte[] dek = keys.dek();
    keys.changePassphrase(pass("old"), pass("new"));
    String recovery = keys.regenerateRecovery();
    assertTrue(Files.isRegularFile(replica()), "successful save must leave the current replica");
    for (Path copy : List.of(primary(), replica())) {
      var record = JsonMapper.builder().build().readValue(Files.readAllBytes(copy), KeystoreRecord.class);
      assertArrayEquals(dek, EncryptionEnvelope.unlock(record, pass("new")));
      assertArrayEquals(dek, EncryptionEnvelope.recover(record, recovery));
      assertThrows(WrongPassphraseException.class, () -> EncryptionEnvelope.unlock(record, pass("old")));
      assertThrows(WrongPassphraseException.class, () -> EncryptionEnvelope.recover(record, oldRecovery));
    }
  }

  @Test
  void missingAndEmptyPrimaryRestoreReplicaAndReportRecovery() throws Exception {
    var keys = manager();
    keys.setup(pass("old"));
    byte[] dek = keys.dek();
    // Seed the same valid replica on the before-state so this check isolates restoration.
    Files.write(replica(), Files.readAllBytes(primary()));
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EncryptionKeystore.class);
    var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    messages.start();
    logger.addAppender(messages);
    try {
      for (boolean empty : List.of(false, true)) {
        if (empty) Files.write(primary(), new byte[0]);
        else Files.delete(primary());
        var restarted = assertDoesNotThrow(this::manager);
        assertEquals(DataKeyManager.State.LOCKED, restarted.state(), "replica must prevent silent reset");
        restarted.unlock(pass("old"));
        assertArrayEquals(dek, restarted.dek());
        assertArrayEquals(Files.readAllBytes(replica()), Files.readAllBytes(primary()));
      }
      assertEquals(2, messages.list.stream().filter(event -> event.getFormattedMessage()
          .contains("Restored encryption keystore from recovery replica")).count());
    } finally {
      logger.detachAppender(messages);
      messages.stop();
    }
  }

  @Test
  void badCopiesGiveNamedErrorAndRemainUntouched() throws Exception {
    Files.createDirectories(primary().getParent());
    Files.writeString(primary(), "");
    for (String bad : List.of("", "broken JSON", "null", "{}",
        "{\"version\":1,\"kdf\":\"PBKDF2-HMAC-SHA256\",\"iterations\":600000,\"salt\":\"AA==\"}")) {
      Files.writeString(replica(), bad);
      var error = assertThrows(CorruptDurableStoreException.class, this::manager);
      assertEquals("encryption-keystore", error.storeId());
      assertEquals("", Files.readString(primary()));
      assertEquals(bad, Files.readString(replica()));
    }
    Files.writeString(primary(), "broken primary");
    assertThrows(CorruptDurableStoreException.class, this::manager);
    assertEquals("broken primary", Files.readString(primary()));
    assertEquals("{\"version\":1,\"kdf\":\"PBKDF2-HMAC-SHA256\",\"iterations\":600000,\"salt\":\"AA==\"}",
        Files.readString(replica()));
  }

  @Test
  void unavailableReplicaNeverTurnsMissingPrimaryIntoFirstTimeSetup() throws Exception {
    var setup = EncryptionEnvelope.setup(pass("old"));
    Files.createDirectories(replica().getParent());
    Files.write(replica(), JsonMapper.builder().build().writeValueAsBytes(setup.record()));
    byte[] backup = Files.readAllBytes(replica());
    for (IOException failure : List.of(new AccessDeniedException(replica().toString()), new IOException("I/O failure"))) {
      try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
        files.when(() -> Files.readAllBytes(replica())).thenThrow(failure);
        var error = assertThrows(UncheckedIOException.class, this::manager);
        assertSame(failure, error.getCause());
      }
      assertFalse(Files.exists(primary()));
      assertArrayEquals(backup, Files.readAllBytes(replica()));
    }
  }

  @Test
  void failureBetweenCopiesCanBeRetriedWithoutLosingCiphertextOrExportKey() throws Exception {
    var keys = manager();
    keys.setup(pass("old"));
    var cipher = new StoreCipher(keys);
    String ciphertext = cipher.seal("saved conversation");
    KeystoreRecord old = keys.keystoreRecord();
    // A nonempty directory deterministically refuses publication on Linux and Windows.
    Files.deleteIfExists(replica());
    Files.createDirectories(replica());
    Files.writeString(replica().resolve("blocker"), "keep");
    assertThrows(UncheckedIOException.class, () -> keys.changePassphrase(pass("old"), pass("new")));
    assertEquals(old, keys.keystoreRecord(), "failed rotation must not advance in-memory wraps");
    assertEquals("saved conversation", cipher.open(ciphertext));
    assertArrayEquals(keys.dek(), EncryptionEnvelope.unlock(keys.keystoreRecord(), pass("old")),
        "the export wrap and live encryption key must still agree after failed publication");
    Files.delete(replica().resolve("blocker"));
    Files.delete(replica());
    assertDoesNotThrow(() -> keys.changePassphrase(pass("old"), pass("new")));
    assertTrue(Files.isRegularFile(replica()), "retry must repair the replica");
    assertEquals("saved conversation", cipher.open(ciphertext));
    assertArrayEquals(keys.dek(), EncryptionEnvelope.unlock(keys.keystoreRecord(), pass("new")));
    // Repeat for recovery rotation; it must not damage the passphrase wrap or ciphertext.
    Files.delete(replica());
    Files.createDirectory(replica());
    Files.writeString(replica().resolve("blocker"), "keep");
    assertThrows(UncheckedIOException.class, keys::regenerateRecovery);
    assertEquals("saved conversation", cipher.open(ciphertext));
    Files.delete(replica().resolve("blocker"));
    Files.delete(replica());
    String recovery = keys.regenerateRecovery();
    var restarted = manager();
    restarted.recover(recovery);
    assertEquals("saved conversation", new StoreCipher(restarted).open(ciphertext));
  }

  @Test
  void existingInstallationLoadsUnchangedAndNextSaveCreatesReplica() throws Exception {
    var setup = EncryptionEnvelope.setup(pass("old"));
    Files.createDirectories(primary().getParent());
    byte[] original = JsonMapper.builder().build().writeValueAsBytes(setup.record());
    Files.write(primary(), original);
    var keys = manager();
    assertArrayEquals(original, Files.readAllBytes(primary()));
    assertFalse(Files.exists(replica()));
    keys.changePassphrase(pass("old"), pass("new"));
    assertTrue(Files.isRegularFile(replica()), "legacy installation must gain replica on save");
    assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()));
  }

  @Test
  void unavailableStorageRefusesSilentSetupOrReplicaReplacement() throws Exception {
    var keys = manager();
    keys.setup(pass("old"));
    byte[] original = Files.readAllBytes(primary());
    Files.write(replica(), original); // Also exercise the preservation obligation on the before-state.
    byte[] backup = Files.readAllBytes(replica());
    for (IOException failure : List.of(new AccessDeniedException(primary().toString()), new IOException("I/O failure"))) {
      try (var ignored = mockStatic(Files.class, invocation -> {
        String method = invocation.getMethod().getName();
        // Files.exists returns false when storage cannot be examined; that must not be
        // interpreted as a first installation. Reads retain the actual storage failure.
        if (method.equals("exists") && primary().equals(invocation.getArgument(0))) return false;
        if (method.equals("readAllBytes") && primary().equals(invocation.getArgument(0))) throw failure;
        return invocation.callRealMethod();
      })) {
        var error = assertThrows(UncheckedIOException.class, this::manager);
        assertSame(failure, error.getCause());
      }
      assertArrayEquals(original, Files.readAllBytes(primary()));
      assertArrayEquals(backup, Files.readAllBytes(replica()));
    }
  }

  @Test
  void futureSchemaNeverRestoresOlderReplica() throws Exception {
    manager().setup(pass("old"));
    byte[] original = Files.readAllBytes(primary());
    Files.write(replica(), original);
    byte[] backup = Files.readAllBytes(replica());
    String future = new String(original, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\"version\":1", "\"version\":2");
    Files.writeString(primary(), future);
    assertThrows(UnsupportedStoreVersionException.class, this::manager);
    assertEquals(future, Files.readString(primary()));
    assertArrayEquals(backup, Files.readAllBytes(replica()));
  }

  @Test
  void futureReplicaCannotReplaceMissingPrimary() throws Exception {
    manager().setup(pass("old"));
    String future = Files.readString(primary()).replace("\"version\":1", "\"version\":2");
    Files.writeString(replica(), future);
    Files.delete(primary());
    assertThrows(UnsupportedStoreVersionException.class, this::manager);
    assertFalse(Files.exists(primary()));
    assertEquals(future, Files.readString(replica()));
  }

  @Test
  void processKillAtEachPublicationStepRetainsUnlockableData() throws Exception {
    // Steps: before/after primary rename, before/after replica rename. The paused subprocess
    // uses the real forced writer and filesystem; only the pause is injected.
    for (String action : List.of("password", "recovery")) {
      for (int step = 1; step <= 4; step++) {
        var keys = manager();
        if (keys.enabled()) keys.unlock(pass("old"));
        else keys.setup(pass("old"));
        String sealed = new StoreCipher(keys).seal("conversation before rotation");
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
          assertTrue(Files.readString(log).contains("PUBLICATION PAUSED"),
              action + " publication step " + step + ": " + Files.readString(log));
        } finally {
          child.destroyForcibly(); // Owned Process object, never a name or an unqualified PID.
          assertTrue(child.waitFor(10, TimeUnit.SECONDS), "owned child must exit");
        }
        var restarted = manager();
        try {
          restarted.unlock(pass("old"));
        } catch (WrongPassphraseException rotated) {
          assertDoesNotThrow(() -> restarted.unlock(pass("new")));
        }
        assertEquals("conversation before rotation", new StoreCipher(restarted).open(sealed));
        // Return to a stable starting credential for the next kill point.
        if (action.equals("password") && step >= 2) restarted.changePassphrase(pass("new"), pass("old"));
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path data = Path.of(args[0]);
    int step = Integer.parseInt(args[1]);
    var keys = new DataKeyManager(new EncryptionKeystore(data));
    keys.unlock(pass("old"));
    int[] publication = {0};
    try (var ignored = mockStatic(Files.class, invocation -> {
      boolean move = invocation.getMethod().getName().equals("move");
      if (move && ++publication[0] == step) pause();
      Object result = invocation.callRealMethod();
      if (move && ++publication[0] == step) pause();
      return result;
    })) {
      if (args[2].equals("password")) keys.changePassphrase(pass("old"), pass("new"));
      else keys.regenerateRecovery();
    }
  }

  private static void pause() throws IOException {
    System.out.println("PUBLICATION PAUSED");
    System.out.flush();
    System.in.read();
  }
}
