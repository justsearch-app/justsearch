/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Read-only operation receipt shared by HTTP and MCP; all timestamps are UTC epoch milliseconds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationOutcomeView(@JsonProperty(required = true) State state, String phase,
    @JsonProperty(required = true) long historySince,
    Long acceptedAt, Long completedAt, Long unitsCompleted, Long unitsFailed,
    String reason, Result result) {
  public OperationOutcomeView {
    Objects.requireNonNull(state, "state");
  }

  public enum State {
    ACCEPTED, RUNNING, COMPLETE, FAILED, UNKNOWN, EXPIRED;

    @JsonValue
    public String wireValue() { return name().toLowerCase(Locale.ROOT); }
  }

  /** Metadata receipt or a pending generation's gap list; never an arbitrary handler response. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Result(String code, String executionId, List<Gap> gaps, String gapListHash) {
    public Result {
      if (code != null) new OperationReceipt(code, executionId);
      else if (executionId != null) throw new IllegalArgumentException("Execution id requires a receipt code");
      gaps = gaps == null ? null : List.copyOf(gaps);
      if (gapListHash != null && (gaps == null || gaps.isEmpty()
          || !gapListHash.matches("[0-9a-f]{64}"))) {
        throw new IllegalArgumentException("Gap-list hash requires a nonempty settled gap list");
      }
    }
  }

  /** Stable unit, safe reason, and optional immutable candidate-row evidence for approval binding. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonDeserialize(using = Gap.Deserializer.class)
  public record Gap(@JsonProperty(required = true) String unitId,
      @JsonProperty(required = true) String reason, String evidenceId)
      implements io.justsearch.agent.api.registry.PreciseWire {
    public Gap(String unitId, String reason) {
      this(unitId, reason, null);
    }

    public Gap {
      if (unitId == null || !unitId.matches("[A-Za-z0-9_.:/-]{1,128}")) {
        throw new IllegalArgumentException("Gap unit id must be a bounded identifier");
      }
      if (!io.justsearch.agent.api.registry.OperationResult.isDurableOutcomeCode(reason)) {
        throw new IllegalArgumentException("Gap reason must be a bounded code");
      }
      if (evidenceId != null && !evidenceId.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Gap evidence must be a SHA-256 digest");
      }
    }

    /** Reads released two-field gaps under the strict operation-store mapper. */
    public static final class Deserializer extends ValueDeserializer<Gap> {
      @Override public Gap deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        if (!node.isObject() || node.properties().stream().anyMatch(entry ->
            !java.util.Set.of("unitId", "reason", "evidenceId").contains(entry.getKey()))) {
          throw new IllegalArgumentException("Gap has an unknown or invalid field");
        }
        JsonNode unit = node.get("unitId");
        JsonNode reasonNode = node.get("reason");
        JsonNode evidence = node.get("evidenceId");
        if (unit == null || !unit.isTextual() || reasonNode == null || !reasonNode.isTextual()
            || evidence != null && !evidence.isNull() && !evidence.isTextual()) {
          throw new IllegalArgumentException("Gap has an invalid field type");
        }
        return new Gap(unit.asString(), reasonNode.asString(),
            evidence == null || evidence.isNull() ? null : evidence.asString());
      }
    }
  }
}
