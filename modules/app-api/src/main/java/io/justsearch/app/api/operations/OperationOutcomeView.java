/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonValue;

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
  public record Result(String code, String executionId, List<Gap> gaps) {
    public Result {
      if (code != null) new OperationReceipt(code, executionId);
      else if (executionId != null) throw new IllegalArgumentException("Execution id requires a receipt code");
      gaps = gaps == null ? null : List.copyOf(gaps);
    }
  }

  /** Stable document/unit identity and a safe reason code; the generation owner supplies these. */
  public record Gap(@JsonProperty(required = true) String unitId, @JsonProperty(required = true) String reason) {
    public Gap {
      if (unitId == null || !unitId.matches("[A-Za-z0-9_.:/-]{1,128}")) {
        throw new IllegalArgumentException("Gap unit id must be a bounded identifier");
      }
      if (!io.justsearch.agent.api.registry.OperationResult.isDurableOutcomeCode(reason)) {
        throw new IllegalArgumentException("Gap reason must be a bounded code");
      }
    }
  }
}
