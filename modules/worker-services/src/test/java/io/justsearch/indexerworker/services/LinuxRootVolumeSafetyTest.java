/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
final class LinuxRootVolumeSafetyTest {
  @TempDir Path temp;

  @Test
  void diskRootHasARestartStableVersionedVolume() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(Path.of("/dev/disk/by-uuid")),
        "real Linux UUID lookup requires /dev/disk/by-uuid");
    Path base = Files.createDirectories(Path.of("build/tmp/linux-root-volume").toAbsolutePath());
    Path root = Files.createTempDirectory(base, "uuid-root-");
    try {
      String volume = RootIdentity.capture(root).volume();
      assertNotNull(volume, "this explicit disk-backed fixture must expose a filesystem UUID");
      assertTrue(volume.startsWith("linux-volume-v1:"));
      RootVolumeLedger ledger = RootVolumeLedger.under(temp);
      ledger.record(root, volume);
      assertEquals(Optional.of(volume), RootVolumeLedger.under(temp).recorded(root));
      assertTrue(RootWitness.of(RootVolumeLedger.under(temp), ignored -> null, ignored -> true)
          .provenOnRecordedVolume(root));
      Path windowsRoot = temp.resolve("windows-record");
      ledger.record(windowsRoot, "windows-volume:123abc");
      assertEquals(Optional.of("windows-volume:123abc"),
          RootVolumeLedger.under(temp).recorded(windowsRoot));
    } finally {
      Files.delete(root);
    }
  }

  @Test
  void tmpfsHasNoIdentityAndOnlyTheVolumeComparisonIsSkipped() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(Path.of("/dev/disk/by-uuid")),
        "real Linux UUID lookup requires /dev/disk/by-uuid");
    Path root = Files.createTempDirectory(Path.of("/dev/shm"), "justsearch-root-unknown-");
    try {
      assertNull(RootIdentity.capture(root).volume());
      RootWitness witness = RootWitness.inMemory();
      assertFalse(witness.refusesRoot(root));
      assertTrue(witness.admits(root));
      assertTrue(witness.vouches(root));
      assertTrue(RootPresenceGuard.capture(root, null).rootCurrent());
    } finally {
      Files.delete(root);
    }
  }

  @Test
  void injectedDiskVolumeIsRestartStableAndVersioned() throws Exception {
    Path base = Files.createDirectories(Path.of("build/tmp/linux-root-volume").toAbsolutePath());
    Path root = Files.createTempDirectory(base, "uuid-root-");
    var evidence = NoVolumeIdentityExtension.withVolumeIdentity(true);
    try (evidence) {
      String volume = RootIdentity.capture(root).volume();
      assertNotNull(volume, "the injected UUID is independent of host mappings");
      assertTrue(volume.startsWith("linux-volume-v1:"));
      RootVolumeLedger ledger = RootVolumeLedger.under(temp);
      ledger.record(root, volume);
      assertEquals(Optional.of(volume), RootVolumeLedger.under(temp).recorded(root));
      assertTrue(RootWitness.of(RootVolumeLedger.under(temp), ignored -> null, ignored -> true)
          .provenOnRecordedVolume(root));
      Path windowsRoot = temp.resolve("windows-record");
      ledger.record(windowsRoot, "windows-volume:123abc");
      assertEquals(Optional.of("windows-volume:123abc"),
          RootVolumeLedger.under(temp).recorded(windowsRoot));
    } finally {
      Files.delete(root);
    }
  }

  @Test
  void missingLookupSkipsOnlyTheVolumeComparisonForAnOwnedRoot() throws Exception {
    Path root = Files.createDirectory(temp.resolve("unknown-volume"));
    var evidence = NoVolumeIdentityExtension.withVolumeIdentity(false);
    try (evidence) {
      assertNull(RootIdentity.capture(root).volume());
      RootWitness witness = RootWitness.inMemory();
      assertFalse(witness.refusesRoot(root));
      assertTrue(witness.admits(root));
      assertTrue(witness.vouches(root));
      assertTrue(RootPresenceGuard.capture(root, null).rootCurrent());
    } finally {
      Files.delete(root);
    }
  }
}
