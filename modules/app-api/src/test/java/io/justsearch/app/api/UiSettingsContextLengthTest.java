/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code contextLength} 0 means auto and must survive persistence (tempdoc 883 fold [R5]).
 *
 * <p>The setter used to clamp unconditionally to {@code Math.max(512, ...)}. Because Jackson
 * deserializes through the setter, that made "auto" unrepresentable on disk: a stored 0 came back
 * as a real 512-token override, which then outranked the derived window at ordinal 300. The bug was
 * only visible after a round trip, so the round trip is what this test asserts.
 */
@DisplayName("UiSettings.contextLength")
final class UiSettingsContextLengthTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Test
  @DisplayName("the shipped default is 0 = auto, not a window")
  void defaultIsAuto() {
    assertEquals(0, new UiSettings().getContextLength());
  }

  @Test
  @DisplayName("0 round-trips through Jackson unchanged")
  void zeroRoundTrips() throws Exception {
    UiSettings settings = new UiSettings();
    settings.setContextLength(0);

    String json = MAPPER.writeValueAsString(settings);
    UiSettings back = MAPPER.readValue(json, UiSettings.class);

    assertEquals(
        0,
        back.getContextLength(),
        "a stored 0 that comes back as 512 is a 512-token override the user never asked for");
  }

  @Test
  @DisplayName("a negative value normalizes to auto rather than to the floor")
  void negativeIsAuto() {
    UiSettings settings = new UiSettings();
    settings.setContextLength(-1);
    assertEquals(0, settings.getContextLength());
  }

  @Test
  @DisplayName("a positive value is still floored at 512")
  void positiveIsFloored() {
    UiSettings settings = new UiSettings();
    settings.setContextLength(1);
    assertEquals(512, settings.getContextLength());

    settings.setContextLength(32768);
    assertEquals(32768, settings.getContextLength());
  }

  @Test
  @DisplayName("a positive override round-trips unchanged")
  void overrideRoundTrips() throws Exception {
    UiSettings settings = new UiSettings();
    settings.setContextLength(16384);

    UiSettings back = MAPPER.readValue(MAPPER.writeValueAsString(settings), UiSettings.class);

    assertEquals(16384, back.getContextLength());
  }

  @Test
  @DisplayName("API port preserves null and explicit ephemeral intent")
  void apiPortRoundTrips() throws Exception {
    UiSettings settings = new UiSettings();
    assertNull(settings.configuredApiPort());
    settings.setApiPort(0);

    UiSettings back = MAPPER.readValue(MAPPER.writeValueAsString(settings), UiSettings.class);

    assertEquals(0, back.configuredApiPort());
  }

  @Test
  @DisplayName("API port rejects values outside the listener range")
  void apiPortRejectsOutOfRangeValues() {
    UiSettings settings = new UiSettings();
    assertThrows(IllegalArgumentException.class, () -> settings.setApiPort(-1));
    assertThrows(IllegalArgumentException.class, () -> settings.setApiPort(65536));
  }
}
