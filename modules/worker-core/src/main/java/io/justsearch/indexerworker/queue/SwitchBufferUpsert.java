/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Versioned durable UPSERT payload shared by admission and cutover replay. */
public record SwitchBufferUpsert(String path, String collection, JobQueue.EnqueueProvenance provenance) {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  public SwitchBufferUpsert {
    Objects.requireNonNull(path, "path");
    if (path.isBlank() || !Path.of(path).isAbsolute()) {
      throw new IllegalArgumentException("Buffered UPSERT requires an absolute path");
    }
  }

  public String encode() {
    var node = MAPPER.createObjectNode();
    node.put("version", 1);
    node.put("path", path);
    node.put("collection", collection);
    node.put("originator", provenance == null ? null : provenance.originator());
    node.put("transport", provenance == null ? null : provenance.transport());
    return MAPPER.writeValueAsString(node);
  }

  /** Pre-C1 payloads were raw absolute paths; their unknown attribution remains unknown. */
  public static SwitchBufferUpsert decode(String payload) {
    Objects.requireNonNull(payload, "payload");
    if (!payload.stripLeading().startsWith("{")) {
      return new SwitchBufferUpsert(payload, null, null);
    }
    JsonNode node = MAPPER.readTree(payload);
    if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToInt()
        || node.path("version").intValue() != 1) {
      throw new IllegalArgumentException("Unsupported buffered UPSERT version");
    }
    if (!node.has("path") || !node.has("collection")
        || !node.has("originator") || !node.has("transport")) {
      throw new IllegalArgumentException("Incomplete buffered UPSERT payload");
    }
    String originator = optionalText(node, "originator");
    String transport = optionalText(node, "transport");
    if ((originator == null) != (transport == null)) {
      throw new IllegalArgumentException("Incomplete buffered UPSERT attribution");
    }
    return new SwitchBufferUpsert(optionalText(node, "path"), optionalText(node, "collection"),
        originator == null ? null : new JobQueue.EnqueueProvenance(originator, transport));
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isString()) throw new IllegalArgumentException("Invalid buffered UPSERT " + field);
    return value.stringValue();
  }

  public JobQueue.EnqueueEntry entry() {
    return JobQueue.EnqueueEntry.stat(Path.of(path), provenance);
  }
}
