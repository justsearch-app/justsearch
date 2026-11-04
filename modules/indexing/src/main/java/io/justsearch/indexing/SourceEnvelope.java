/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Conservative interpretation of optional stored provenance, never an authorization decision. */
public record SourceEnvelope(String originId, StorageStatus storageStatus, String storageMount,
    String storageKey) {
  public static final String VERSION = "1";
  public enum StorageStatus { KNOWN, UNKNOWN, NOT_OBSERVED }

  /** Missing/unsupported envelopes are legacy. Incomplete storage evidence cannot mean known. */
  public static Optional<SourceEnvelope> decode(Map<String, ?> fields) {
    if (fields == null || !VERSION.equals(fields.get(SchemaFields.ENVELOPE_VERSION))) return Optional.empty();
    Object origin = fields.get(SchemaFields.ORIGIN_ID);
    if (!(origin instanceof String id) || !canonicalUuid(id)) return Optional.empty();
    Object status = fields.get(SchemaFields.STORAGE_STATUS);
    if ("not_observed".equals(status)) {
      return Optional.of(new SourceEnvelope(id, StorageStatus.NOT_OBSERVED, null, null));
    }
    if ("known".equals(status)
        && fields.get(SchemaFields.STORAGE_MOUNT) instanceof String mount
        && fields.get(SchemaFields.STORAGE_KEY) instanceof String key
        && validMount(mount) && validStorageKey(key)) {
      return Optional.of(new SourceEnvelope(id, StorageStatus.KNOWN, mount, key));
    }
    return Optional.of(new SourceEnvelope(id, StorageStatus.UNKNOWN, null, null));
  }

  private static boolean canonicalUuid(String id) {
    try { return UUID.fromString(id).toString().equals(id); }
    catch (IllegalArgumentException malformed) { return false; }
  }

  /** UUID:type:filesystem-root; split only twice because a filesystem root can contain colons. */
  private static boolean validStorageKey(String key) {
    String[] identity = key.split(":", 3);
    return identity.length == 3 && identity[0].matches("[A-Za-z0-9-]+")
        && identity[1].matches("[A-Za-z0-9_.+-]+") && validMount(identity[2]);
  }

  private static boolean validMount(String mount) {
    try {
      var path = Path.of(mount);
      // Known storage records Linux/POSIX names, including when read on another platform.
      return mount.startsWith("/")
          && path.normalize().toString().replace(java.io.File.separatorChar, '/').equals(mount)
          && mount.chars().noneMatch(Character::isISOControl);
    } catch (IllegalArgumentException malformed) { return false; }
  }
}
