/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Versioned authority selection for one frozen import; jsa1 remains only the selected basis. */
public record ImportApprovalBinding(int version, String id, String parentOperationKey,
    String predecessorId, String planHash, long interruptionRevision) {
  public static final int VERSION = 1;
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final Set<String> FIELDS = Set.of("version", "id", "parentOperationKey",
      "predecessorId", "planHash", "interruptionRevision");

  public ImportApprovalBinding {
    if (version != VERSION) throw new IllegalArgumentException("Unknown import approval binding version");
    requireId(id);
    if (predecessorId != null) {
      requireId(predecessorId);
      if (id.equals(predecessorId)) throw new IllegalArgumentException("Self-predecessor approval");
    }
    OperationKeys.timestampMillis(parentOperationKey);
    if (planHash == null || !planHash.matches("[a-f0-9]{64}") || interruptionRevision < 0) {
      throw new IllegalArgumentException("Invalid frozen import approval binding");
    }
  }

  public String encode() {
    var fields = new java.util.LinkedHashMap<String, Object>();
    fields.put("version", version); fields.put("id", id);
    fields.put("parentOperationKey", parentOperationKey); fields.put("predecessorId", predecessorId);
    fields.put("planHash", planHash); fields.put("interruptionRevision", interruptionRevision);
    return JSON.writeValueAsString(fields);
  }

  public static ImportApprovalBinding decode(String encoded) {
    Object decoded = JSON.readValue(encoded, Object.class);
    if (!(decoded instanceof Map<?, ?> fields) || !fields.keySet().equals(FIELDS)
        || !(fields.get("version") instanceof Integer version)
        || !(fields.get("id") instanceof String id)
        || !(fields.get("parentOperationKey") instanceof String parent)
        || fields.get("predecessorId") != null && !(fields.get("predecessorId") instanceof String)
        || !(fields.get("planHash") instanceof String hash)
        || !(fields.get("interruptionRevision") instanceof Number revision)
        || !(revision instanceof Integer || revision instanceof Long)) {
      throw new IllegalArgumentException("Malformed import approval binding");
    }
    return new ImportApprovalBinding(version, id, parent, (String) fields.get("predecessorId"), hash,
        revision.longValue());
  }

  public static void requireId(String id) {
    if (id == null || !UUID.fromString(id).toString().equals(id)) {
      throw new IllegalArgumentException("Invalid approval identity");
    }
  }
}
