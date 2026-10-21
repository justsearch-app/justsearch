/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("cross-platform")
class KeystoreRollbackTest {
  @TempDir Path dir;
  private Path primary() { return dir.resolve("encryption/keystore.json"); }
  private Path replica() { return dir.resolve("encryption/keystore.replica.json"); }
  private DataKeyManager manager() { return new DataKeyManager(new EncryptionKeystore(dir)); }

  // Verifier V5: failure without an in-process retry must preserve the known recovery key.
  @Test
  void failedRecoveryRotationWithoutRetryKeepsKnownRecoveryKeyAfterRestart() throws Exception {
    var keys = manager();
    String recovery = keys.setup("old".toCharArray());
    Files.deleteIfExists(replica());
    Files.createDirectory(replica());
    Files.writeString(replica().resolve("blocker"), "keep");
    assertThrows(RuntimeException.class, keys::regenerateRecovery);
    Files.delete(replica().resolve("blocker"));
    Files.delete(replica());
    var restarted = manager();
    assertDoesNotThrow(() -> restarted.recover(recovery), "reported failure must retain the known recovery key");
    assertArrayEquals(keys.dek(), restarted.dek());
  }

  // Verifier V6: a reported failure must retain the old password, not merely either password.
  @Test
  void failedPasswordChangeWithoutRetryKeepsOldPasswordAfterRestart() throws Exception {
    var keys = manager();
    keys.setup("old".toCharArray());
    Files.deleteIfExists(replica());
    Files.createDirectory(replica());
    Files.writeString(replica().resolve("blocker"), "keep");
    assertThrows(RuntimeException.class, () -> keys.changePassphrase("old".toCharArray(), "new".toCharArray()));
    Files.delete(replica().resolve("blocker"));
    Files.delete(replica());
    var restarted = manager();
    assertDoesNotThrow(() -> restarted.unlock("old".toCharArray()), "reported failure must retain the old password");
    restarted.lock();
    assertThrows(WrongPassphraseException.class, () -> restarted.unlock("new".toCharArray()));
  }

  @Test
  void replicaPublicationFailureRestoresPreviousBytesThroughForcedWriter() throws Exception {
    var keys = manager();
    String recovery = keys.setup("old".toCharArray());
    byte[] previous = Files.readAllBytes(primary());
    var primaryWrites = new ArrayList<byte[]>();
    try (var ignored = mockStatic(AtomicFileWrites.class, invocation -> {
      if (invocation.getMethod().getParameterCount() == 2
          && invocation.getMethod().getName().equals("replaceStrict")) {
        Path target = invocation.getArgument(0);
        if (target.equals(replica())) throw new IOException("replica publication refused");
        if (target.equals(primary())) primaryWrites.add(invocation.getArgument(1));
      }
      return invocation.callRealMethod();
    })) {
      assertThrows(RuntimeException.class, keys::regenerateRecovery);
    }
    assertEquals(2, primaryWrites.size(), "failed replica publication must force the rollback too");
    assertArrayEquals(previous, primaryWrites.get(1));
    assertArrayEquals(previous, Files.readAllBytes(primary()));
    assertDoesNotThrow(() -> manager().recover(recovery));
  }

  @Test
  void nextSuccessfulSaveRemovesOnlyAbandonedKeystoreTemps() throws Exception {
    var keys = manager();
    keys.setup("old".toCharArray());
    var leftovers = List.of(primary().resolveSibling("keystore.json.killed.tmp"),
        replica().resolveSibling("keystore.replica.json.killed.tmp"));
    for (Path leftover : leftovers) Files.copy(primary(), leftover);
    Path unrelated = primary().resolveSibling("unrelated.tmp");
    Files.writeString(unrelated, "keep");
    keys.changePassphrase("old".toCharArray(), "new".toCharArray());
    for (Path leftover : leftovers) assertFalse(Files.exists(leftover), "successful save must remove abandoned wraps");
    assertEquals("keep", Files.readString(unrelated));
    assertArrayEquals(Files.readAllBytes(primary()), Files.readAllBytes(replica()));
  }
}
