/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import tools.jackson.databind.JsonNode;

/** Shared format authority for the authored watched-roots registry and its migration reader. */
public final class WatchedRootsFormat {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private WatchedRootsFormat() {}

  public static void requireReadableObject(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw new CorruptDurableStoreException(
          "watched-roots", "expected a legacy array or versioned object");
    }
    JsonNode versionNode = root.get("schemaVersion");
    if (versionNode != null && !versionNode.isInt()) {
      throw new CorruptDurableStoreException("watched-roots", "schemaVersion must be an integer");
    }
    Integer observedVersion = versionNode == null ? null : versionNode.asInt();
    StoreFormatVersions.requireReadable(
        "watched-roots", observedVersion, CURRENT_SCHEMA_VERSION, 0, 0);
  }
}
