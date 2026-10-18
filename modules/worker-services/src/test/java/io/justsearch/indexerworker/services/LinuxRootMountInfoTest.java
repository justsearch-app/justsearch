/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
final class LinuxRootMountInfoTest {
  @TempDir Path temp;

  @Test
  void escapedPathsMountRootsAndLongestComponentMatchArePreserved() throws Exception {
    var mounts = LinuxRootIdentityLease.parseMounts(List.of(
        "1 0 8:1 / / rw shared:1 - ext4 /dev/sda rw",
        "2 1 8:2 /original\\040folder /media/my\\040disk rw - ext4 /dev/sdb rw",
        "3 2 8:2 /tab\\011line\\012slash\\134 /media/my\\040disk/nested rw - ext4 /dev/sdb rw"));
    var disk = LinuxRootIdentityLease.selectMount(mounts, Path.of("/media/my disk/file"));
    assertEquals("8:2", disk.device());
    assertEquals(Path.of("/original folder"), disk.filesystemRoot());
    var nested = LinuxRootIdentityLease.selectMount(mounts, Path.of("/media/my disk/nested/file"));
    assertEquals(Path.of("/tab\tline\nslash\\"), nested.filesystemRoot());
    assertEquals("1", LinuxRootIdentityLease.selectMount(mounts,
        Path.of("/media/my disk-other/file")).id());
    var stacked = LinuxRootIdentityLease.parseMounts(List.of(
        "1 0 8:1 / / rw - ext4 /dev/sda rw",
        "2 1 8:2 / / rw - ext4 /dev/sdb rw"));
    assertNull(LinuxRootIdentityLease.selectMount(stacked, Path.of("/file")));
  }

  @Test
  void namespaceObjectMountDoesNotInvalidateUnrelatedFilesystemRoots() throws Exception {
    var mounts = LinuxRootIdentityLease.parseMounts(List.of(
        "33 2 259:2 / / rw - ext4 /dev/nvme0n1p2 rw",
        "2620 720 0:5 mnt:[4026533644] /run/snapd/ns/firefox.mnt rw - nsfs nsfs rw"));
    assertEquals("33", LinuxRootIdentityLease.selectMount(mounts, Path.of("/home/user/documents")).id());
    var namespace = LinuxRootIdentityLease.selectMount(mounts, Path.of("/run/snapd/ns/firefox.mnt"));
    assertEquals("2620", namespace.id(), "the opaque mount remains in the topology");
    assertEquals(Path.of("mnt:[4026533644]"), namespace.filesystemRoot());
    assertNull(LinuxRootIdentityLease.volume(namespace, temp, 0),
        "a namespace object never supplies a filesystem identity");
  }

  @Test
  void nonabsoluteMountPointsAndUnrecognizedRootsStillRefuseIdentity() {
    for (String line : List.of(
        "1 0 8:1 relative / rw - ext4 /dev/sda rw",
        "1 0 0:5 mnt:[123] relative rw - nsfs nsfs rw",
        "1 0 0:5 mnt:invalid /run/ns rw - nsfs nsfs rw",
        "1 0 8:1 mnt:[123] / rw - ext4 /dev/sda rw")) {
      assertThrows(IOException.class, () -> LinuxRootIdentityLease.parseMounts(List.of(line)), line);
    }
  }

  @Test
  void malformedMountEvidenceIsNeverGuessed() {
    for (String line : List.of("", "1 0 8:1 / / rw", "1 0 8:1 / /bad\\041 rw - ext4 /dev/sda rw")) {
      assertThrows(IOException.class, () -> LinuxRootIdentityLease.parseMounts(List.of(line)));
    }
  }

  @Test
  void linuxTuplesAreVersionedAndCorruptTuplesAreRejectedWithoutLosingWindowsRecords()
      throws Exception {
    String value = LinuxRootIdentityLease.encodeVolume("ABCD-1234", "ext4", Path.of("/folder : ü"));
    assertTrue(LinuxRootIdentityLease.validVolume(value));
    assertFalse(LinuxRootIdentityLease.validVolume(value.replace("v1:", "v2:")));
    assertFalse(LinuxRootIdentityLease.validVolume("linux-volume-v1:abcd:ext4:cmVsYXRpdmU"));
    assertFalse(LinuxRootIdentityLease.validVolume("linux-volume-v1:abcd:ext4:%%%"));
    assertFalse(LinuxRootIdentityLease.validVolume("linux-volume-v1:abcd"));
    RootVolumeLedger ledger = RootVolumeLedger.under(temp);
    Path root = temp.resolve("root");
    ledger.record(root, "windows-volume:123abc");
    assertEquals("windows-volume:123abc", RootVolumeLedger.under(temp).recorded(root).orElseThrow());
    ledger.record(root, value);
    assertEquals(value, RootVolumeLedger.under(temp).recorded(root).orElseThrow());
    ledger.record(root, "linux-volume-v1:abcd:ext4:%%%");
    assertThrows(RootVolumeLedger.CorruptRecordException.class,
        () -> RootVolumeLedger.under(temp).recorded(root));
  }

  @Test
  void missingDanglingAndAmbiguousUuidMappingsAreUnknown() throws Exception {
    Path base = Files.createDirectories(Path.of("build/tmp/linux-root-volume").toAbsolutePath());
    Path root = Files.createTempDirectory(base, "uuid-alias-root-");
    try {
    long device = ((Number) Files.getAttribute(root, "unix:dev")).longValue();
    var mount = LinuxRootIdentityLease.selectMount(LinuxRootIdentityLease.parseMounts(
        Files.readAllLines(Path.of("/proc/self/mountinfo"))), root.toRealPath());
    Path aliases = Files.createDirectory(temp.resolve("aliases"));
    assertNull(LinuxRootIdentityLease.volume(mount, aliases, device));
    Path matching = null;
    try (var entries = Files.newDirectoryStream(Path.of("/dev/disk/by-uuid"))) {
      for (Path entry : entries) {
        if (((Number) Files.getAttribute(entry, "unix:rdev")).longValue() == device) {
          matching = entry.toRealPath();
          break;
        }
      }
    }
    assertNotNull(matching, "this explicit disk-backed fixture must expose a filesystem UUID");
    Files.createSymbolicLink(aliases.resolve("aaaa-1111"), matching);
    assertNotNull(LinuxRootIdentityLease.volume(mount, aliases, device));
    // btrfs mountinfo has an anonymous st_dev; its source block device remains authoritative.
    var anonymous = LinuxRootIdentityLease.parseMounts(List.of(
        "20 1 0:123 / / rw - btrfs " + matching + " rw")).getFirst();
    assertEquals(LinuxRootIdentityLease.encodeVolume("aaaa-1111", "btrfs", Path.of("/")),
        LinuxRootIdentityLease.volume(anonymous, aliases, 0));

    Files.createSymbolicLink(aliases.resolve("bbbb-2222"), matching);
    assertNull(LinuxRootIdentityLease.volume(mount, aliases, device));
    Files.delete(aliases.resolve("bbbb-2222"));
    Files.createSymbolicLink(aliases.resolve("cccc-3333"), temp.resolve("missing-device"));
    assertNull(LinuxRootIdentityLease.volume(mount, aliases, device));
    } finally {
      Files.delete(root);
    }
  }

  @Test
  void retainedDirectoryRejectsReplacementAndReleasesItsDescriptor() throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    try (RootIdentityLease lease = RootIdentity.hold(root)) {
      lease.requireCurrent();
      Files.move(root, temp.resolve("original"));
      Files.createDirectory(root);
      assertThrows(IOException.class, lease::requireCurrent);
    }
  }
}
