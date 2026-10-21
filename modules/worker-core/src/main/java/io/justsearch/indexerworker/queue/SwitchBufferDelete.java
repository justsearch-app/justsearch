/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Exact-delete intent. Originless receipts from older versions require an absence check. */
public record SwitchBufferDelete(
    String path, Origin origin, String root, String rootIdentity, String collection) {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  public enum Origin { EXPLICIT, WATCHER, LEGACY }

  public SwitchBufferDelete(String path, Origin origin, String root) {
    this(path, origin, root, null, null);
  }

  public SwitchBufferDelete {
    if (path == null || path.isBlank() || origin == null) {
      throw new IllegalArgumentException("Delete requires a path and origin");
    }
    if (origin == Origin.WATCHER && (!Path.of(path).isAbsolute()
        || rootIdentity == null || rootIdentity.isBlank()
        || root == null || !Path.of(root).isAbsolute()
        || !Path.of(path).normalize().startsWith(Path.of(root).normalize()))) {
      throw new IllegalArgumentException("Watcher delete requires its absolute containing root");
    }
    if (origin != Origin.WATCHER && (root != null || rootIdentity != null || collection != null)) {
      throw new IllegalArgumentException("Only watcher deletes carry a root");
    }
  }

  public String encode() {
    if (origin == Origin.LEGACY) return path;
    var node = JSON.createObjectNode();
    node.put("version", 1);
    node.put("path", path);
    node.put("origin", origin.name());
    node.put("root", root);
    node.put("root_identity", rootIdentity);
    node.put("collection", collection);
    return JSON.writeValueAsString(node);
  }

  public static SwitchBufferDelete decode(String payload) {
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("Empty delete receipt");
    }
    if (!payload.startsWith("{")) return new SwitchBufferDelete(payload, Origin.LEGACY, null);
    var node = JSON.readTree(payload);
    if (!node.isObject() || !node.path("version").isIntegralNumber()
        || !node.path("version").canConvertToInt() || node.path("version").intValue() != 1
        || !node.path("path").isString() || !node.path("origin").isString()
        || !node.has("root") || (!node.get("root").isNull() && !node.get("root").isString())) {
      throw new IllegalArgumentException("Invalid delete receipt");
    }
    Origin origin = Origin.valueOf(node.path("origin").stringValue());
    if (origin == Origin.LEGACY) throw new IllegalArgumentException("Versioned delete needs an origin");
    for (String field : new String[] {"root_identity", "collection"}) {
      if (!node.has(field) || (!node.get(field).isNull() && !node.get(field).isString())) {
        throw new IllegalArgumentException("Invalid delete root metadata");
      }
    }
    return new SwitchBufferDelete(node.path("path").stringValue(), origin,
        node.get("root").isNull() ? null : node.get("root").stringValue(),
        node.get("root_identity").isNull() ? null : node.get("root_identity").stringValue(),
        node.get("collection").isNull() ? null : node.get("collection").stringValue());
  }
}
