/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.core.context.RetainedStateBudget;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Loads the shipped policy directly from its authoritative register; there is no Java mirror. */
record EngineResourcePolicy(Map<String, Integer> execution, RetainedStateBudget retained) {
  private static final Set<String> POLICY_KEYS = Set.of("perContextLimit", "aggregateLimit",
      "retryAfterSeconds", "foregroundThreads", "foregroundQueue", "backgroundThreads",
      "backgroundQueue", "timerRegistrations", "directMemoryMiB");
  private static final Set<String> RETAINED_KINDS = Set.of("search-cursors", "pinned-readers",
      "representation-generations", "co-resident-encoders", "attempted-configurations");

  static EngineResourcePolicy load() {
    try (InputStream input = EngineResourcePolicy.class.getResourceAsStream("/engine/retained-state.v1.json")) {
      if (input == null) throw new IllegalStateException("Engine resource policy is missing");
      return parse(new ObjectMapper().readTree(input));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read Engine resource policy", e);
    }
  }

  static EngineResourcePolicy parse(JsonNode root) {
      if (root == null || !root.isObject()) throw new IllegalStateException("Invalid Engine resource policy");
      if (!root.path("schemaVersion").isIntegralNumber()
          || !root.path("schemaVersion").canConvertToInt()
          || root.path("schemaVersion").asInt() != 1) {
        throw new IllegalStateException("Unsupported Engine resource policy schema");
      }
      Map<String, Integer> execution = new LinkedHashMap<>();
      for (var property : root.path("policy").properties()) {
        if (!property.getValue().isIntegralNumber() || !property.getValue().canConvertToInt()
            || property.getValue().asInt() <= 0) {
          throw new IllegalStateException("Invalid Engine limit: " + property.getKey());
        }
        execution.put(property.getKey(), property.getValue().asInt());
      }
      if (!execution.keySet().equals(POLICY_KEYS)) {
        throw new IllegalStateException("Engine resource policy keys do not match the consumer");
      }
      var retained = new RetainedStateBudget();
      if (!root.path("retained").isArray()) throw new IllegalStateException("Missing retained policy");
      var kinds = new java.util.HashSet<String>();
      for (var row : root.path("retained")) {
        if (!row.path("kind").isString() || !row.path("awaitingProducer").isString()
            || !row.path("cap").isIntegralNumber() || !row.path("cap").canConvertToInt()
            || (row.has("perContextCap") && (!row.path("perContextCap").isIntegralNumber()
                || !row.path("perContextCap").canConvertToInt()))) {
          throw new IllegalStateException("Invalid retained policy row");
        }
        kinds.add(row.path("kind").asText());
        retained.declare(row.path("kind").asText(), row.path("cap").asInt(),
            row.has("perContextCap") ? Integer.valueOf(row.path("perContextCap").asInt()) : null,
            row.path("awaitingProducer").asText());
      }
      if (!kinds.equals(RETAINED_KINDS)) throw new IllegalStateException("Retained resource kinds do not match the consumer");
      return new EngineResourcePolicy(Map.copyOf(execution), retained);
  }
}
