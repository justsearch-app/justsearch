/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SourceEnvelope;
import io.justsearch.indexing.SourceEnvelope.StorageStatus;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
final class StorageObservationSnapshotTest {
  private static final String ROOT = "1 0 8:1 / / rw - ext4 /dev/disk1 rw";
  private static final String DISK = "2 1 8:2 / /media/disk rw - ext4 /dev/disk2 rw";
  private static final String ORIGIN = "00000000-0000-4000-8000-000000000001";

  @Test
  void effectiveMountAndBindSubtreesHaveIndependentKeys() {
    var fixture = new Fixture(List.of(ROOT, DISK,
        "3 1 8:2 /subtree /media/bind rw - ext4 /dev/disk2 rw"));
    Object snapshot = fixture.capture();
    var disk = envelope(snapshot, "/media/disk/file");
    var bind = envelope(snapshot, "/media/bind/file");
    assertEquals(StorageStatus.KNOWN, disk.storageStatus());
    assertEquals("/media/disk", disk.storageMount());
    assertEquals("bbbb-2222:ext4:/", disk.storageKey());
    assertEquals("bbbb-2222:ext4:/subtree", bind.storageKey());
    assertEquals("aaaa-1111:ext4:/", envelope(snapshot, "/media/disk-other/file").storageKey());
    assertEquals(ORIGIN, disk.originId());
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"uuidless", "unreadable-uuid", "unreadable-source", "ambiguous", "malformed", "unreadable-mounts"})
  void uncertainEffectiveMountNeverFallsBackToAncestor(String failure) {
    var fixture = new Fixture(failure.equals("ambiguous") ? List.of(ROOT, DISK, DISK) : List.of(ROOT, DISK));
    if (failure.equals("uuidless")) fixture.uuids = Map.of("8:1", "aaaa-1111");
    if (failure.equals("unreadable-uuid")) fixture.uuidFailure = true;
    if (failure.equals("unreadable-source")) fixture.sourceFailure = true;
    if (failure.equals("malformed")) fixture.lines = List.of(ROOT, "malformed");
    if (failure.equals("unreadable-mounts")) fixture.mountFailure = true;
    var observed = envelope(fixture.capture(), "/media/disk/file");
    assertEquals(StorageStatus.UNKNOWN, observed.storageStatus());
    assertNull(observed.storageMount());
    assertNull(observed.storageKey());
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(ints = {1, 100})
  void mountReadsAndUuidDiscoveryAreConstantForTheWholeBatch(int files) {
    var fixture = new Fixture(List.of(ROOT, DISK));
    Object snapshot = fixture.capture();
    for (int i = 0; i < files; i++) {
      assertEquals(StorageStatus.KNOWN, envelope(snapshot, "/media/disk/file" + i).storageStatus());
    }
    assertEquals(2, fixture.mountReads.get(), "one snapshot bracketed by one publication validation");
    assertEquals(1, fixture.uuidReads.get(), "UUID discovery belongs to the batch");
    assertEquals(2, fixture.sourceReads.get(), "block-device lookup is once per mount, never per source");
  }

  @Test
  void changedOrUnreadableTopologyBeforePublicationCannotPublishStaleKnown() {
    for (boolean unreadable : List.of(false, true)) {
      var fixture = new Fixture(List.of(ROOT, DISK));
      Object snapshot = fixture.capture();
      fixture.lines = List.of(ROOT, "3 1 8:3 / /media/disk rw - ext4 /dev/disk3 rw");
      fixture.mountFailure = unreadable;
      assertEquals(StorageStatus.UNKNOWN, envelope(snapshot, "/media/disk/file").storageStatus());
      assertEquals(StorageStatus.UNKNOWN, envelope(snapshot, "/other/file").storageStatus());
      assertEquals(2, fixture.mountReads.get());
      assertEquals(1, fixture.uuidReads.get());
    }
  }

  @Test
  void ambiguousAndMalformedUuidAliasesCannotIdentifyADevice() {
    var devices = assertDoesNotThrow(() -> LinuxRootIdentityLease.class
        .getDeclaredMethod("uniqueUuidDevices", Map.class).invoke(null, Map.of(
            "8:1", List.of("AAAA-1111"), "8:2", List.of("bbbb-2222", "cccc-3333"),
            "8:3", List.of("invalid uuid"))));
    assertEquals(Map.of("8:1", "aaaa-1111"), devices);
  }

  @Test
  void nonLinuxObservationDoesNotInventStorage() {
    Object snapshot = assertDoesNotThrow(() -> {
      var method = snapshotType().getDeclaredMethod("notObserved");
      method.setAccessible(true);
      return method.invoke(null);
    });
    assertEquals(StorageStatus.NOT_OBSERVED, envelope(snapshot, "/file").storageStatus());
  }

  // Reflective calls keep the checks runnable against P1a without copying production behavior.
  private static Class<?> snapshotType() {
    return assertDoesNotThrow(() -> Class.forName("io.justsearch.indexerworker.services.StorageObservationSnapshot"),
        "publication requires a batch storage observer");
  }

  private static SourceEnvelope envelope(Object snapshot, String path) {
    return assertDoesNotThrow(() -> (SourceEnvelope) snapshotType()
        .getMethod("envelope", Path.class, String.class).invoke(snapshot, Path.of(path), ORIGIN));
  }

  private static final class Fixture {
    List<String> lines;
    Map<String, String> uuids = Map.of("8:1", "aaaa-1111", "8:2", "bbbb-2222");
    boolean mountFailure;
    boolean uuidFailure;
    boolean sourceFailure;
    final AtomicInteger mountReads = new AtomicInteger();
    final AtomicInteger uuidReads = new AtomicInteger();
    final AtomicInteger sourceReads = new AtomicInteger();
    Fixture(List<String> lines) { this.lines = lines; }

    Object capture() {
      return assertDoesNotThrow(() -> {
        Class<?> type = snapshotType();
        Class<?> evidence = Class.forName(type.getName() + "$Evidence");
        Object fixture = Proxy.newProxyInstance(evidence.getClassLoader(), new Class<?>[] {evidence},
            (proxy, method, args) -> switch (method.getName()) {
              case "mountInfo" -> {
                mountReads.incrementAndGet();
                if (mountFailure) throw new IOException("unreadable mountinfo");
                yield lines;
              }
              case "uuidDevices" -> {
                uuidReads.incrementAndGet();
                if (uuidFailure) throw new IOException("unreadable UUID inventory");
                yield uuids;
              }
              case "sourceDevice" -> {
                sourceReads.incrementAndGet();
                if (sourceFailure && ((LinuxRootIdentityLease.Mount) args[0]).device().equals("8:2")) {
                  throw new IOException("unreadable block device");
                }
                yield ((LinuxRootIdentityLease.Mount) args[0]).device();
              }
              default -> throw new UnsupportedOperationException(method.getName());
            });
        var capture = type.getDeclaredMethod("linux", evidence);
        capture.setAccessible(true);
        return capture.invoke(null, fixture);
      });
    }
  }
}
