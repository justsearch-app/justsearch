/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** A renewal approves only the retained plan and the interruption the person inspected. */
public record RecordedImportResume(String operationKey, long interruptionRevision, RecordedRootPlan plan) {
  public static final String OPERATION_ID = "core.resume-import";
  public static final String SCHEMA = "import-resume.v1";
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  public RecordedImportResume {
    OperationKeys.timestampMillis(operationKey);
    if (interruptionRevision < 1) throw new IllegalArgumentException("Invalid interruption revision");
    Objects.requireNonNull(plan, "plan");
  }

  public String encode() {
    return JSON.writeValueAsString(java.util.Map.of("operationKey", operationKey,
        "interruptionRevision", interruptionRevision, "plan", JSON.readTree(plan.toReplayPayload())));
  }
  public static RecordedImportResume decode(String payload) {
    var node = JSON.readTree(payload);
    if (node == null || !node.isObject() || node.size() != 3
        || !node.path("operationKey").isTextual()
        || !node.path("interruptionRevision").isIntegralNumber()) {
      throw new IllegalArgumentException("Invalid import renewal");
    }
    return new RecordedImportResume(node.path("operationKey").asString(),
        node.path("interruptionRevision").asLong(),
        RecordedRootPlan.fromReplayPayload(JSON.writeValueAsString(node.path("plan"))));
  }
}
