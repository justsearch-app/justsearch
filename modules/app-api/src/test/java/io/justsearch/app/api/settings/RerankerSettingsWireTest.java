/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class RerankerSettingsWireTest {
  @Test
  void preferenceDistinguishesUnspecifiedOffAndOnWithoutChangingPath() {
    var json = JsonMapper.builder().build();
    assertNull(json.readValue("{}", SettingsV2.class).rerankerEnabled());
    for (boolean enabled : new boolean[] {false, true}) {
      var settings = json.readValue("{\"rerankerEnabled\":" + enabled
          + ",\"rerankerModelPath\":\"/custom/ranker\"}", SettingsV2.class);
      var reopened = json.readValue(json.writeValueAsString(settings), SettingsV2.class);
      assertEquals(enabled, reopened.rerankerEnabled());
      assertEquals("/custom/ranker", reopened.rerankerModelPath());
    }
  }

  @Test
  void switchIntentNeedsAValueAndIsOmittedWhenAbsent() {
    var json = JsonMapper.builder().build();
    assertThrows(tools.jackson.core.JacksonException.class,
        () -> json.readValue("{\"rerankerSwitchIntent\":true}", SettingsV2.class),
        "A switch intent without a Best match first value is malformed");
    var marked = json.readValue("{\"rerankerSwitchIntent\":true,\"rerankerEnabled\":false}",
        SettingsV2.class);
    var reopened = json.readValue(json.writeValueAsString(marked), SettingsV2.class);
    assertEquals(Boolean.TRUE, reopened.rerankerSwitchIntent());
    assertEquals(Boolean.FALSE, reopened.rerankerEnabled());
    var projected = new SettingsV2(null, null, java.util.List.of(), null, null, null, null, null,
        null, null, null, null, Boolean.TRUE);
    assertNull(projected.rerankerSwitchIntent());
    assertFalse(json.writeValueAsString(projected).contains("rerankerSwitchIntent"),
        "A projection without an intent never echoes the marker");
  }
}
