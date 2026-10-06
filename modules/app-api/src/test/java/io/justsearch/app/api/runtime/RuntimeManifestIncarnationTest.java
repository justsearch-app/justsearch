/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.app.api.runtime.RuntimeManifest.IncarnationInfo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec F §3.2: the Engine names its supervised incarnation in the manifest, so a census can tell a
 * manifest of this run's Engine from one an earlier run left behind.
 */
final class RuntimeManifestIncarnationTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("the supervisor's <session>/<n> parses; anything else is no incarnation")
  void parsesTheSpawnEnvironment() {
    assertEquals(new IncarnationInfo("run-20261006-1", 3), IncarnationInfo.parse("run-20261006-1/3"));
    assertEquals(new IncarnationInfo("a/b", 1), IncarnationInfo.parse("a/b/1"));
    for (String bad : new String[] {null, "", "run", "run/", "/1", "run/0", "run/-1", "run/x",
        "run/1234567890", "x".repeat(65) + "/1"}) {
      assertNull(IncarnationInfo.parse(bad), String.valueOf(bad));
    }
  }

  @Test
  @DisplayName("the incarnation round-trips, is public, and is omitted when absent")
  void roundTripsAndStaysOptional() throws Exception {
    RuntimeManifest without = manifest(null);
    assertFalse(JSON.readTree(JSON.writeValueAsString(without)).has("incarnation"));

    RuntimeManifest with = manifest(new IncarnationInfo("run-census", 2));
    JsonNode written = JSON.readTree(JSON.writeValueAsString(with));
    assertEquals("run-census", written.path("incarnation").path("session").asString());
    assertEquals(2, written.path("incarnation").path("n").asInt());
    assertEquals(with, JSON.readValue(JSON.writeValueAsString(with), RuntimeManifest.class));
    assertSame(with.incarnation(), with.publicProjection().incarnation());
  }

  private static RuntimeManifest manifest(IncarnationInfo incarnation) {
    return RuntimeManifestBuilder.builder()
        .schemaVersion(RuntimeManifest.CURRENT_SCHEMA_VERSION)
        .instanceId("00000000-0000-0000-0000-000000000042")
        .pid(42)
        .startedAt("2026-10-06T00:00:00Z")
        .dataDir("/tmp/incarnation")
        .head(new RuntimeManifest.HeadInfo(null, null, null, null, null))
        .children(List.of())
        .incarnation(incarnation)
        .build();
  }
}
