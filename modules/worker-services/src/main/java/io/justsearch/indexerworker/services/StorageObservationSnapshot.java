/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexing.SourceEnvelope;
import io.justsearch.indexing.SourceEnvelope.StorageStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Batch storage evidence: no directory leases, source stats, or UUID discovery per file. */
public final class StorageObservationSnapshot {
  interface Evidence {
    List<String> mountInfo() throws IOException;
    Map<String, String> uuidDevices() throws IOException;
    String sourceDevice(LinuxRootIdentityLease.Mount mount) throws IOException;
  }

  private final Evidence evidence;
  private List<LinuxRootIdentityLease.Mount> mounts;
  private Map<LinuxRootIdentityLease.Mount, String> keys;
  private final boolean observed;

  private StorageObservationSnapshot(Evidence evidence, List<LinuxRootIdentityLease.Mount> mounts,
      Map<LinuxRootIdentityLease.Mount, String> keys, boolean observed) {
    this.evidence = evidence;
    this.mounts = mounts;
    this.keys = keys;
    this.observed = observed;
  }

  public static StorageObservationSnapshot capture() { return RootIdentity.storageSnapshot(); }

  static StorageObservationSnapshot notObserved() {
    return new StorageObservationSnapshot(null, List.of(), Map.of(), false);
  }

  static Evidence systemEvidence() {
    return new Evidence() {
      public List<String> mountInfo() throws IOException {
        return Files.readAllLines(Path.of("/proc/self/mountinfo"));
      }
      public Map<String, String> uuidDevices() throws IOException {
        return LinuxRootIdentityLease.readUuidDevices(Path.of("/dev/disk/by-uuid"));
      }
      public String sourceDevice(LinuxRootIdentityLease.Mount mount) throws IOException {
        return LinuxRootIdentityLease.sourceDevice(mount);
      }
    };
  }

  static StorageObservationSnapshot linux(Evidence evidence) {
    try {
      return linux(evidence, List.copyOf(LinuxRootIdentityLease.parseMounts(evidence.mountInfo())));
    } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
      return new StorageObservationSnapshot(evidence, List.of(), Map.of(), true);
    }
  }

  private static StorageObservationSnapshot linux(
      Evidence evidence, List<LinuxRootIdentityLease.Mount> mounts) {
    try {
      for (var mount : mounts) {
        if (!mount.point().normalize().equals(mount.point())) {
          throw new IOException("Noncanonical Linux mount point");
        }
      }
      var uuids = evidence.uuidDevices();
      Map<LinuxRootIdentityLease.Mount, String> keys = new HashMap<>();
      for (var mount : mounts) {
        try {
          String device = evidence.sourceDevice(mount);
          // Only anonymous mount devices (for example btrfs) need source-device identity.
          // A different resolved device for an ordinary mount is contradictory evidence.
          if (device == null || (!mount.device().startsWith("0:") && !mount.device().equals(device))) continue;
          String uuid = uuids.get(device);
          if (uuid == null) continue;
          // Reuse the identity lease's validation; the catalog stores the readable tuple.
          LinuxRootIdentityLease.encodeVolume(uuid, mount.type(), mount.filesystemRoot());
          String key = uuid.toLowerCase(java.util.Locale.ROOT) + ":" + mount.type() + ":" + mount.filesystemRoot();
          var envelope = new SourceEnvelope(null, StorageStatus.KNOWN, mount.point().toString(), key);
          envelope.fields(); // Invalid/noncanonical paths cannot publish known evidence.
          keys.put(mount, key);
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
          // Keep this effective mount in topology so it never falls back to an ancestor.
        }
      }
      return new StorageObservationSnapshot(evidence, mounts, Map.copyOf(keys), true);
    } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
      return new StorageObservationSnapshot(evidence, mounts, Map.of(), true);
    }
  }

  /** Compare once per source; reuse UUID/device evidence until the mount topology changes. */
  public void validateForPublication() {
    if (evidence == null) return;
    StorageObservationSnapshot refreshed;
    try {
      var latest = List.copyOf(LinuxRootIdentityLease.parseMounts(evidence.mountInfo()));
      if (mounts.equals(latest)) return;
      refreshed = linux(evidence, latest);
    } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
      // Retry observation once; its failure yields unknown rather than stale evidence.
      refreshed = linux(evidence);
    }
    mounts = refreshed.mounts;
    keys = refreshed.keys;
  }

  public SourceEnvelope envelope(Path source, String originId) {
    validateForPublication();
    if (!observed) return new SourceEnvelope(originId, StorageStatus.NOT_OBSERVED, null, null);
    if (source == null) return new SourceEnvelope(originId, StorageStatus.UNKNOWN, null, null);
    var mount = LinuxRootIdentityLease.selectMount(mounts, source.toAbsolutePath().normalize());
    String key = mount == null ? null : keys.get(mount);
    return key == null ? new SourceEnvelope(originId, StorageStatus.UNKNOWN, null, null)
        : new SourceEnvelope(originId, StorageStatus.KNOWN, mount.point().toString(), key);
  }
}
