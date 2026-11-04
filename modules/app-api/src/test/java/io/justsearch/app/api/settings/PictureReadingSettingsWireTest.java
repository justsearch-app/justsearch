/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class PictureReadingSettingsWireTest {
  private final JsonMapper json = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void ordinarySettingsHaveNoExplicitPictureChoiceOrSwitchIntent() {
    var value = json.readValue("{}", SettingsV2.class);
    assertNull(value.pictureReadingEnabled());
    assertNull(value.pictureReadingSwitchIntent());
    assertFalse(json.writeValueAsString(SettingsV2.empty()).contains("pictureReadingSwitchIntent"));
  }

  @Test
  void explicitSwitchIntentRequiresItsChoiceAndSurvivesRoundTrip() {
    assertThrows(RuntimeException.class,
        () -> json.readValue("{\"pictureReadingSwitchIntent\":true}", SettingsV2.class));
    var value = json.readValue("{\"pictureReadingSwitchIntent\":true,\"pictureReadingEnabled\":false}", SettingsV2.class);
    var reopened = json.readValue(json.writeValueAsString(value), SettingsV2.class);
    assertEquals(Boolean.TRUE, reopened.pictureReadingSwitchIntent());
    assertEquals(Boolean.FALSE, reopened.pictureReadingEnabled());
  }
}
