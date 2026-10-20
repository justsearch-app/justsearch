/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptionKeystorePermissionsTest {
  @TempDir Path data;
  @TempDir Path elsewhere;

  private DataKeyManager restart() { return new DataKeyManager(new EncryptionKeystore(data)); }
  private void assertPrivate() throws Exception {
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(data.resolve("encryption")));
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(data.resolve("encryption/keystore.json")));
  }

  @Test void setupReplacementAndRestartKeepPrivateAndUsable() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var keys = restart();
    keys.setup("old-passphrase".toCharArray());
    var dek = keys.dek();
    assertPrivate();
    keys.changePassphrase("old-passphrase".toCharArray(), "new-passphrase".toCharArray());
    assertPrivate();
    var recovery = keys.regenerateRecovery();
    assertPrivate();
    var next = restart();
    next.unlock("new-passphrase".toCharArray());
    assertArrayEquals(dek, next.dek());
    next.lock();
    next.recover(recovery);
    assertArrayEquals(dek, next.dek());
  }

  @Test void broadRestoredFolderIsRepairedBeforeLoad() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var keys = restart();
    keys.setup("passphrase".toCharArray());
    var dek = keys.dek();
    Files.setPosixFilePermissions(data.resolve("encryption"), PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(data.resolve("encryption/keystore.json"), PosixFilePermissions.fromString("rw-rw-r--"));
    var next = restart();
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(data.resolve("encryption")));
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(data.resolve("encryption/keystore.json")));
    next.unlock("passphrase".toCharArray());
    assertArrayEquals(dek, next.dek());
  }

  @Test void interruptedReplacementPreservesPassphraseAndRecoveryMaterial() throws Exception {
    var keys = restart();
    var recovery = keys.setup("passphrase".toCharArray());
    var dek = keys.dek();
    var file = data.resolve("encryption/keystore.json");
    var before = Files.readAllBytes(file);
    Thread.currentThread().interrupt();
    try { assertThrows(UncheckedIOException.class, keys::regenerateRecovery); }
    finally { Thread.interrupted(); }
    assertArrayEquals(before, Files.readAllBytes(file));
    var next = restart();
    next.unlock("passphrase".toCharArray());
    assertArrayEquals(dek, next.dek());
    next.lock();
    next.recover(recovery);
    assertArrayEquals(dek, next.dek());
    try (var paths = Files.list(file.getParent())) { assertEquals(1, paths.count()); }
  }

  @Test void missingDataDirectoryCanStillBeConfigured() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var missing = data.resolve("new-data");
    var keys = new DataKeyManager(new EncryptionKeystore(missing));
    keys.setup("passphrase".toCharArray());
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(missing.resolve("encryption")));
    new DataKeyManager(new EncryptionKeystore(missing)).unlock("passphrase".toCharArray());
  }

  @Test void linkedEncryptionStillUnlocksWithoutChangingTargetOrParentPermissions() throws Exception {
    assumeTrue(Files.getFileStore(data).supportsFileAttributeView("posix"));
    var outside = elsewhere;
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwxrwxr-x"));
    var mode = Files.getPosixFilePermissions(outside);
    Files.createSymbolicLink(data.resolve("encryption"), outside);
    var parentMode = Files.getPosixFilePermissions(data);
    var keys = assertDoesNotThrow(this::restart);
    keys.setup("passphrase".toCharArray());
    var next = assertDoesNotThrow(this::restart);
    next.unlock("passphrase".toCharArray());
    assertArrayEquals(keys.dek(), next.dek());
    assertEquals(parentMode, Files.getPosixFilePermissions(data));
    assertEquals(mode, Files.getPosixFilePermissions(outside));
    assertTrue(Files.isSymbolicLink(data.resolve("encryption")));
  }
}
