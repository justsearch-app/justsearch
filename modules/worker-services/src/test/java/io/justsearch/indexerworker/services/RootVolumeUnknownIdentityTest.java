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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void C10UnrecordedFolderCannotProveAbsenceWithoutVolumeIdentity(boolean declared) throws Exception {
    Path folder = Files.createDirectory(temp.resolve("ad-hoc"));
    RootWitness witness = RootWitness.of(RootVolumeLedger.under(temp), ignored -> null, ignored -> declared);
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
      assertFalse(witness.vouches(folder));
      assertFalse(witness.vouchesForFile(folder));
      assertFalse(witness.provenOnRecordedVolume(folder));
      assertFalse(RootPresenceGuard.capture(folder, null, RootVolumeLedger.under(temp)).rootCurrent());
      assertEquals(io.justsearch.indexerworker.loop.SourcePresence.Verdict.ROOT_UNAVAILABLE,
          io.justsearch.indexerworker.loop.SourcePresence.classify(folder.resolve("gone.txt"), null, witness));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void C10PreviouslyPositiveProofCannotSurviveLossOfCurrentVolumeIdentity(boolean declared) throws Exception {
    Path folder = Files.createDirectory(temp.resolve("previously-proven"));
    RootWitness witness = RootWitness.of(RootVolumeLedger.under(temp), ignored -> null, ignored -> declared);
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(true)) {
      witness.recordDeliberateAdd(folder, false);
      assertTrue(witness.vouches(folder));
      assertTrue(witness.vouchesForFile(folder));
      try (var ignoredLostVolume = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
        assertEquals(io.justsearch.indexerworker.loop.SourcePresence.Verdict.ROOT_UNAVAILABLE,
            io.justsearch.indexerworker.loop.SourcePresence.classify(folder.resolve("gone.txt"), null, witness));
        assertFalse(witness.vouches(folder));
        assertFalse(witness.vouchesForFile(folder));
      }
    }
  }

  @Test
  void deliberatelyAddedAdHocFolderStillAdmitsPresentFilesWithoutVolumeIdentity() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("explicit-folder"));
    Files.writeString(folder.resolve("present.txt"), "present content");
    RootVolumeLedger ledger = RootVolumeLedger.under(temp);
    RootWitness witness = RootWitness.of(ledger, ignored -> null, ignored -> false);
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
      witness.recordDeliberateAdd(folder, false);
      assertFalse(witness.refusesRoot(folder), "the explicit folder scan is accepted");
      assertTrue(witness.admits(folder), "WorkerIngestionAuthority must admit the scan's present files");
      assertTrue(witness.admitsFile(folder), "explicit present-file admission keeps the same rule");
      assertFalse(witness.vouches(folder), "admission never grants deletion authority");
    }
  }

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
      assertFalse(witness.provenOnRecordedVolume(root));
      assertFalse(witness.refusesRoot(root));
      assertTrue(witness.admits(root));
      assertFalse(witness.vouches(root));
      assertTrue(witness.admitsFile(root));
      assertFalse(witness.vouchesForFile(root));
      assertFalse(RootPresenceGuard.capture(root, null, ledger).rootCurrent());
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
