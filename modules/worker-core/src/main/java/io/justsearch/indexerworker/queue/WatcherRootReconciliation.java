/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Durable reload debt, scoped to the directory incarnation that accepted the watcher event. */
public record WatcherRootReconciliation(String root, String rootIdentity, String collection) {
  private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();
  public static final String OPERATION = "WATCHER_RECONCILE";

  public WatcherRootReconciliation {
    if (root == null || root.isBlank() || !Path.of(root).isAbsolute()
        || !Path.of(root).normalize().toString().equals(root)
        || rootIdentity == null || rootIdentity.isBlank()) {
      throw new IllegalArgumentException("Reconciliation requires an absolute root and its identity");
    }
  }

  public String key() { return "watcher-reconcile:" + root; }

  public String encode() {
    var node = JSON.createObjectNode();
    node.put("version", 1);
    node.put("root", root);
    node.put("root_identity", rootIdentity);
    node.put("collection", collection);
    return JSON.writeValueAsString(node);
  }

  public static WatcherRootReconciliation decode(String payload) {
    var node = JSON.readTree(payload);
    if (node == null || !node.isObject() || !node.path("version").isIntegralNumber()
        || !node.path("version").canConvertToInt() || node.path("version").intValue() != 1
        || !node.path("root").isString() || !node.path("root_identity").isString()
        || !node.has("collection")
        || (!node.get("collection").isNull() && !node.get("collection").isString())) {
      throw new IllegalArgumentException("Invalid watcher reconciliation receipt");
    }
    return new WatcherRootReconciliation(node.path("root").stringValue(),
        node.path("root_identity").stringValue(),
        node.get("collection").isNull() ? null : node.get("collection").stringValue());
  }
}
