/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import io.justsearch.app.api.UiSettings;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Owner-adopted S36.6/S36.12: saved pause intent and retirement of the AI-only preference. */
final class IndexingPauseContractTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void savedPausePolicySurvivesRoundTrip() {
    var input = json.readTree("{\"indexingPause\":{\"paused\":true,\"untilEpochMs\":1900000000000,\"reason\":\"USER_PAUSED\"}}");
    var loaded = json.treeToValue(input, UiSettings.class);
    var saved = json.readTree(json.writeValueAsString(loaded));
    assertEquals(input.get("indexingPause"), saved.get("indexingPause"),
        "manual and timed pause intent must survive a settings round trip");
  }

  @Test
  void oldAiPauseSettingLoadsButIsAbsentFromBothContracts() {
    var loaded = json.readValue("{\"pauseIndexingDuringAi\":true,\"theme\":\"dark\"}", UiSettings.class);
    var saved = json.readTree(json.writeValueAsString(loaded));
    assertEquals("dark", saved.get("theme").asText());
    assertFalse(saved.has("pauseIndexingDuringAi"), "retired preference must not be emitted");
    var canonical = json.readTree(json.writeValueAsString(UiSettingsV2.defaults()));
    assertFalse(canonical.has("pauseIndexingDuringAi"), "canonical API must retire the preference too");
  }
}
