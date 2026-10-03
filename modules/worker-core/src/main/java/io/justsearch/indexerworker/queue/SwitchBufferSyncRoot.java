/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One persisted SYNC_ROOT representation shared by admission, coalescing and replay. */
public record SwitchBufferSyncRoot(String rootPath, boolean force, JobQueue.EnqueueProvenance provenance) {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  public SwitchBufferSyncRoot {
    if (rootPath == null || rootPath.isBlank() || !Path.of(rootPath).isAbsolute()) {
      throw new IllegalArgumentException("Buffered SYNC_ROOT requires an absolute root path");
    }
  }

  public String encode() {
    var node = MAPPER.createObjectNode();
    node.put("version", 1);
    node.put("root_path", rootPath);
    node.put("force", force);
    node.put("originator", provenance == null ? null : provenance.originator());
    node.put("transport", provenance == null ? null : provenance.transport());
    return MAPPER.writeValueAsString(node);
  }

  /** Legacy payloads contained only root_path and an optional force flag. */
  public static SwitchBufferSyncRoot decode(String payload) {
    var node = MAPPER.readTree(payload);
    if (node == null || !node.isObject() || !node.path("root_path").isString()
        || (node.has("force") && !node.path("force").isBoolean())) {
      throw new IllegalArgumentException("Invalid SYNC_ROOT path or force flag");
    }
    JobQueue.EnqueueProvenance provenance = null;
    if (node.has("version")) {
      if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToInt()
          || node.path("version").intValue() != 1) {
        throw new IllegalArgumentException("Unsupported SYNC_ROOT version");
      }
      if (!node.path("force").isBoolean() || !node.has("originator") || !node.has("transport")) {
        throw new IllegalArgumentException("Incomplete versioned SYNC_ROOT payload");
      }
      var originator = node.get("originator");
      var transport = node.get("transport");
      if (originator.isNull() != transport.isNull()) {
        throw new IllegalArgumentException("Incomplete SYNC_ROOT attribution");
      }
      if (!originator.isNull()) {
        if (!originator.isString() || !transport.isString()) {
          throw new IllegalArgumentException("Invalid SYNC_ROOT attribution");
        }
        provenance = new JobQueue.EnqueueProvenance(originator.stringValue(), transport.stringValue());
      }
    } else if (node.has("originator") || node.has("transport")) {
      throw new IllegalArgumentException("SYNC_ROOT attribution requires a version");
    }
    return new SwitchBufferSyncRoot(node.path("root_path").stringValue(),
        node.path("force").asBoolean(false), provenance);
  }
}
