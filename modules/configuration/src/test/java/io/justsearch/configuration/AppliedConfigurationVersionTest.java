/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AppliedConfigurationVersionTest {
  private static final String PORT = EnvRegistry.API_PORT.configKey();

  @Test
  void declaredAppliedChangeMattersButUnrelatedKeyDoesNot() {
    Set<String> keys = Set.of(PORT);
    String initial = AppliedConfigurationVersion.digest(keys, Map.of(PORT, 8080, "unrelated", 1));
    assertEquals(initial, AppliedConfigurationVersion.digest(keys, Map.of(PORT, 8080, "unrelated", 2)));
    assertNotEquals(initial, AppliedConfigurationVersion.digest(keys, Map.of(PORT, 8081)));
    assertThrows(IllegalArgumentException.class,
        () -> AppliedConfigurationVersion.digest(keys, Map.of("unrelated", 8080)));
  }

  @Test
  void normalizationAndDefaultAreTheOwnersAppliedValue() {
    var implicit = ResolvedConfig.builder().build();
    var explicit = ResolvedConfig.builder().putDefault(PORT, " 08080 ").build();
    assertEquals(AppliedConfigurationVersion.digest(Set.of(PORT), Map.of(PORT, implicit.ports().apiPort())),
        AppliedConfigurationVersion.digest(Set.of(PORT), Map.of(PORT, explicit.ports().apiPort())));
  }

  @Test
  void nestedOrderIsCanonicalAndNullIsDifferentFromMissing() {
    var nested = new LinkedHashMap<String, Object>();
    nested.put("z", 1);
    nested.put("a", 2);
    assertEquals(AppliedConfigurationVersion.digest(Set.of("owner"), Map.of("owner", nested)),
        AppliedConfigurationVersion.digest(Set.of("owner"), Map.of("owner", Map.of("a", 2, "z", 1))));
    var absent = new LinkedHashMap<String, Object>();
    absent.put(PORT, null);
    assertNotEquals(AppliedConfigurationVersion.digest(Set.of(PORT), absent),
        AppliedConfigurationVersion.digest(Set.of(PORT), Map.of(PORT, "")));
    assertThrows(IllegalArgumentException.class,
        () -> AppliedConfigurationVersion.digest(Set.of(PORT), Map.of()));
  }
}
