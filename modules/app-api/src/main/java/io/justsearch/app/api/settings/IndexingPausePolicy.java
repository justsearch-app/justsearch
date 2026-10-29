/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.justsearch.core.scheduling.IndexingSchedule;
import java.util.ArrayList;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Authored manual intent and optional rules. Effective restrictions are never saved here. */
public record IndexingPausePolicy(boolean paused, Long untilEpochMs, String reason,
    @JsonInclude(JsonInclude.Include.NON_NULL) IndexingSchedule schedule,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean pauseOnBattery) {
  /** Missing optional battery fields in older files default to false; ingress rejects explicit null. */
  @JsonCreator
  public static IndexingPausePolicy fromJson(@JsonProperty("paused") boolean paused,
      @JsonProperty("untilEpochMs") Long untilEpochMs, @JsonProperty("reason") String reason,
      @JsonProperty("schedule") IndexingSchedule schedule,
      @JsonProperty("pauseOnBattery") Boolean pauseOnBattery) {
    return new IndexingPausePolicy(paused, untilEpochMs, reason, schedule, Boolean.TRUE.equals(pauseOnBattery));
  }

  public IndexingPausePolicy(boolean paused, Long untilEpochMs, String reason) {
    this(paused, untilEpochMs, reason, null, false);
  }

  public IndexingPausePolicy {
    if (paused && !"USER_PAUSED".equals(reason) && !"SETTINGS_RECOVERY".equals(reason)) {
      throw new IllegalArgumentException("Unknown indexing pause reason");
    }
    if (!paused && (untilEpochMs != null || reason != null)) {
      throw new IllegalArgumentException("A resumed policy has no deadline or reason");
    }
  }

  /** Validate tokens before databinding can coerce them. Duplicate tokens are checked by ingress. */
  public static void validateRules(JsonNode policy) {
    if (policy == null || policy.isNull()) return;
    if (!policy.isObject()) throw new IllegalArgumentException("indexingPause must be an object");
    for (String name : policy.propertyNames()) {
      if (!Set.of("paused", "untilEpochMs", "reason", "schedule", "pauseOnBattery").contains(name)) {
        throw new IllegalArgumentException("Unknown indexingPause field: " + name);
      }
    }
    var battery = policy.get("pauseOnBattery");
    if (battery != null && !battery.isBoolean()) {
      throw new IllegalArgumentException("pauseOnBattery must be boolean");
    }
    scheduleFromJson(policy.get("schedule"));
  }

  public static IndexingSchedule scheduleFromJson(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (!node.isObject() || node.size() != 4
        || !node.has("enabled") || !node.has("days")
        || !node.has("startMinute") || !node.has("endMinute")) {
      throw new IllegalArgumentException("schedule requires enabled, days, startMinute and endMinute only");
    }
    if (!node.get("enabled").isBoolean()) {
      throw new IllegalArgumentException("schedule.enabled must be boolean");
    }
    if (!node.get("days").isArray()) {
      throw new IllegalArgumentException("schedule.days must be an array");
    }
    var days = new ArrayList<Integer>();
    for (var day : node.get("days")) days.add(integer(day, "schedule.days"));
    return new IndexingSchedule(node.get("enabled").booleanValue(), days,
        integer(node.get("startMinute"), "schedule.startMinute"),
        integer(node.get("endMinute"), "schedule.endMinute"));
  }

  private static int integer(JsonNode node, String field) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return node.intValue();
  }
}
