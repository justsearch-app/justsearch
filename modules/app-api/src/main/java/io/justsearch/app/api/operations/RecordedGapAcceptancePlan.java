/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Frozen identity of one user-approved, nonterminal migration gap decision. */
public record RecordedGapAcceptancePlan(String reindexKey, String gapListHash) {
  public static final String SCHEMA = "recorded-gap-acceptance-v1";
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  public RecordedGapAcceptancePlan {
    OperationKeys.timestampMillis(reindexKey);
    if (gapListHash == null || !gapListHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Gap acceptance requires a SHA-256 list hash");
    }
  }

  public static RecordedGapAcceptancePlan decode(String json) {
    RecordedGapAcceptancePlan plan = JSON.readValue(json, RecordedGapAcceptancePlan.class);
    if (plan == null) throw new IllegalArgumentException("Gap acceptance plan cannot be null");
    return plan;
  }

  public String toReplayPayload() { return JSON.writeValueAsString(this); }
}
