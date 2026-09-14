/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationKind;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Internal child identity; the frozen one-root plan is persisted separately from its digest. */
public record RecordedIngestChild(String parentOperationKey, RecordedRootPlan plan) {
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Set<String> FIELDS = Set.of(
      "mode", "parentOperationKey", "replaySchema", "rootPlanSha256");

  public RecordedIngestChild {
    OperationKeys.timestampMillis(parentOperationKey);
    Objects.requireNonNull(plan, "plan");
    if (plan.roots().size() != 1) throw new IllegalArgumentException("An ingest child owns exactly one root");
  }

  public OperationDescriptor descriptor() {
    return new OperationDescriptor(OperationKind.INGEST, null, JSON.writeValueAsString(Map.of(
        "mode", "ingest-child", "parentOperationKey", parentOperationKey,
        "replaySchema", RecordedRootPlan.SCHEMA,
        "rootPlanSha256", CanonicalOperationArguments.digest(plan.toReplayPayload()))));
  }

  public OperationPreparedPayload payload() {
    return new OperationPreparedPayload(false, plan.toReplayPayload());
  }

  /** Strict private recovery read. A record kind by itself never selects a trusted producer. */
  public static RecordedIngestChild from(OperationDescriptor descriptor, OperationPreparedPayload payload) {
    String parentKey = parentKey(descriptor);
    Objects.requireNonNull(payload, "payload");
    if (payload.sealed()) throw new IllegalArgumentException("Invalid ingest child binding");
    var child = new RecordedIngestChild(parentKey, RecordedRootPlan.fromReplayPayload(payload.value()));
    if (!child.descriptor().hasSameIdentity(descriptor)) {
      throw new IllegalArgumentException("Ingest child plan digest mismatch");
    }
    return child;
  }

  /**
   * Strict identity-only observation for fencing an unavailable payload. This syntactic parent
   * association proves no root membership, preparation integrity or replay authority.
   */
  public static String parentKey(OperationDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.kind() != OperationKind.INGEST || descriptor.operationRef() != null) {
      throw new IllegalArgumentException("Invalid ingest child binding");
    }
    final Object decoded;
    try {
      decoded = JSON.readValue(descriptor.identityJson(), Object.class);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid ingest child identity");
    }
    if (!(decoded instanceof Map<?, ?> identity) || !identity.keySet().equals(FIELDS)
        || !"ingest-child".equals(identity.get("mode"))
        || !RecordedRootPlan.SCHEMA.equals(identity.get("replaySchema"))
        || !(identity.get("rootPlanSha256") instanceof String hash)
        || !hash.matches("[a-f0-9]{64}")
        || !(identity.get("parentOperationKey") instanceof String parentKey)) {
      throw new IllegalArgumentException("Invalid ingest child identity");
    }
    OperationKeys.timestampMillis(parentKey);
    return parentKey;
  }

}
