/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class EngineContextTest {
  @Test
  void survivalAndUrgencyAreIndependentForEveryClient() {
    for (var client : EngineContext.ClientKind.values()) {
      for (var survival : EngineContext.Survival.values()) {
        for (var urgency : EngineContext.Urgency.values()) {
          var context = new EngineContext(client, "client", Optional.of("session"),
              Optional.of("grant"), "UNTRUSTED", "MCP", survival, urgency);
          assertEquals(survival, context.survival());
          assertEquals(urgency, context.urgency());
        }
      }
    }
    assertEquals(Set.of("clientKind", "clientId", "sessionId", "grantReference", "sourceTier",
        "transport", "survival", "urgency", "workId"),
        Arrays.stream(EngineContext.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet()));
    assertEquals(Set.of("clientKind", "clientId", "sessionId", "grantReference", "sourceTier",
        "transport", "survival", "urgency", "workId", "toString", "hashCode"),
        Arrays.stream(EngineContext.class.getDeclaredMethods())
            .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .filter(m -> !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
            .filter(m -> m.getParameterCount() == 0)
            .map(java.lang.reflect.Method::getName).collect(Collectors.toSet()));
  }

  @Test
  void attributionCannotRetainArbitraryPayloadsOrControlCharacters() {
    for (String value : new String[] {"", " ", "x".repeat(257), "client\nforged-log"}) {
      assertThrows(IllegalArgumentException.class, () -> new EngineContext(
          EngineContext.ClientKind.CLI, value, Optional.empty(), Optional.empty(),
          "UNTRUSTED", "SYSTEM_INTERNAL", EngineContext.Survival.INTERACTIVE,
          EngineContext.Urgency.BACKGROUND));
    }
    assertThrows(NullPointerException.class, () -> new EngineContext(
        EngineContext.ClientKind.CLI, "client", Optional.empty(), Optional.empty(),
        "UNTRUSTED", "SYSTEM_INTERNAL", null, EngineContext.Urgency.BACKGROUND));
    assertThrows(NullPointerException.class, () -> new EngineContext(
        EngineContext.ClientKind.CLI, "client", Optional.empty(), Optional.empty(),
        "UNTRUSTED", "SYSTEM_INTERNAL", EngineContext.Survival.DURABLE, null));
  }
}
