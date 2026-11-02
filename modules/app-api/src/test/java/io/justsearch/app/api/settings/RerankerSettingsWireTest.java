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
}
