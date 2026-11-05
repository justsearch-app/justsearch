/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class PictureReadingCandidateCompatibilityTest {
  private final JsonMapper json = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void absentDefaultsKeepAcceptedCandidateShape() throws Exception {
    var legacy = json.readValue("{\"chatEnabled\":false,\"maxTokens\":777}", UiSettings.class);
    var encoded = json.valueToTree(legacy);
    assertFalse(encoded.has("pictureReadingEnabled"), "an accepted legacy candidate must not acquire a new null field");
    assertFalse(encoded.has("pictureReadingPreferenceRevision"), "an accepted legacy candidate must not acquire a new zero field");
    assertFalse(encoded.path("chatEnabled").asBoolean(true));
    assertEquals(777, encoded.path("maxTokens").asInt());
  }

  @Test
  void explicitChoiceAndItsRevisionAreNeverOmitted() throws Exception {
    var selected = json.readValue("{\"pictureReadingEnabled\":false,\"pictureReadingPreferenceRevision\":7}", UiSettings.class);
    var encoded = json.valueToTree(selected);
    assertFalse(encoded.path("pictureReadingEnabled").asBoolean(true));
    assertEquals(7, encoded.path("pictureReadingPreferenceRevision").asLong());
    var reopened = json.readValue(json.writeValueAsString(selected), UiSettings.class);
    assertFalse(json.valueToTree(reopened).path("pictureReadingEnabled").asBoolean(true));
    assertEquals(7, json.valueToTree(reopened).path("pictureReadingPreferenceRevision").asLong());
  }
}
