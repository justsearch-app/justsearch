/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The same unknown-identity policy for Windows serials and Linux filesystem UUIDs. */
final class RootVolumeUnknownIdentityTest {
  @TempDir Path temp;

  @Test
  void initiallyUnknownVolumeIsNotDeferredAndFinishesSeeding() throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    RootVolumeLedger ledger = RootVolumeLedger.under(temp);
    ledger.beginUpgradeSeeding(List.of(root));
    RootWitness witness = RootWitness.of(ledger, ignored -> null, ignored -> true);
    boolean previous = RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.getAndSet(true);
    try {
      assertTrue(witness.seedAfterUpgrade(root, ignored -> false), "no identity to compare or seed");
      assertFalse(ledger.awaitsSeeding(root));
      assertTrue(witness.provenOnRecordedVolume(root));
      assertFalse(witness.refusesRoot(root));
      assertTrue(witness.admits(root));
      assertTrue(witness.vouches(root));
      assertTrue(witness.admitsFile(root));
      assertTrue(witness.vouchesForFile(root));
      assertTrue(RootPresenceGuard.capture(root, null, ledger).rootCurrent());
      assertTrue(ledger.recorded(root).isEmpty(), "no synthetic volume record");
    } finally {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(previous);
    }
  }

  @Test
  void knownRecordedVolumeThatBecomesUnknownIsNeverProofAndIsRetried() throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    RootVolumeLedger ledger = RootVolumeLedger.under(temp);
    ledger.beginUpgradeSeeding(List.of(root));
    // A known parent record also answers for descendants still awaiting their upgrade seeding.
    ledger.record(temp, "windows-volume:123abc");
    RootWitness witness = RootWitness.of(ledger, ignored -> null, ignored -> true);
    boolean previous = RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.getAndSet(true);
    try {
      assertFalse(witness.provenOnRecordedVolume(root));
      assertTrue(witness.refusesRoot(root));
      assertFalse(witness.admits(root));
      assertFalse(witness.vouches(root));
      assertFalse(witness.admitsFile(root));
      assertFalse(witness.vouchesForFile(root));
      assertFalse(witness.seedAfterUpgrade(root, ignored -> true));
      assertTrue(ledger.awaitsSeeding(root), "keep retrying the known volume");
      assertFalse(RootPresenceGuard.capture(root, null, ledger).rootCurrent());
      assertEquals("windows-volume:123abc", ledger.recordedFor(root).orElseThrow());
    } finally {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(previous);
    }
  }

  @Test
  void knownExplicitFileFolderThatBecomesUnknownDoesNotAdmitOrPrune() throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    RootVolumeLedger ledger = RootVolumeLedger.under(temp);
    ledger.recordFileFolder(root, "windows-volume:123abc");
    RootWitness witness = RootWitness.of(ledger, ignored -> null, ignored -> false);
    boolean previous = RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.getAndSet(true);
    try {
      assertFalse(witness.admitsFile(root));
      assertFalse(witness.vouchesForFile(root));
      assertFalse(witness.admits(root));
      assertFalse(witness.vouches(root));
    } finally {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(previous);
    }
  }

  @Test
  void unreadableLedgerIsNotProofEvenWithoutAPlatformVolumeIdentity() throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    RootVolumeLedger ledger = RootVolumeLedger.under(temp);
    Files.writeString(temp.resolve(RootVolumeLedger.FILE_NAME), "not-a-root=invalid\n");
    RootWitness witness = RootWitness.of(ledger, ignored -> null, ignored -> true);
    boolean previous = RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.getAndSet(true);
    try {
      assertFalse(witness.provenOnRecordedVolume(root));
      assertFalse(witness.admits(root));
      assertFalse(witness.vouches(root));
    } finally {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(previous);
    }
  }
}
