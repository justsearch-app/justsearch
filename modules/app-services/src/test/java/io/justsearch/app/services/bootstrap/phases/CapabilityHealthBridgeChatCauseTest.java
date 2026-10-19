/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec A step 7 (F1): Health words a failed chat by its classified cause, from the message catalog,
 * instead of the evidence string the component status carries.
 */
@DisplayName("CapabilityHealthBridge — a failed chat is worded by its cause")
final class CapabilityHealthBridgeChatCauseTest {

  private static final Source HEAD = Source.forProcess("head", "instance-1", "1.0");

  /** The classified chat failures, and inference.crashed for the rest, as Condition reasons. */
  private static final List<String> CHAT_FAILURE_CAUSES = List.of(
      "InferenceOutOfMemory",
      "InferenceStartupTimeout",
      "InferenceStartFailed",
      "InferenceModelLoadFailed",
      "InferenceCrashed");

  private static AssertedCondition inferenceCondition(ConditionStore store) {
    HealthEvent event = store.currentSnapshot().stream()
        .filter(e -> "inference.capability".equals(e.id()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no inference.capability condition"));
    return (AssertedCondition) event.body();
  }

  private static AssertedCondition conditionAfterFailure(String reasonCode, String detail)
      throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      components.handle("generative").transition(ComponentState.FAILED, reasonCode, detail);
      ConditionStore conditions = new ConditionStore();
      try (AutoCloseable ignored = CapabilityHealthBridge.wireListeners(
          components, conditions, new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()), HEAD)) {
        return inferenceCondition(conditions);
      }
    }
  }

  @Test
  void aChatModelThatDidNotLoadIsNamedByItsCauseNotTheEvidence() throws Exception {
    AssertedCondition condition =
        conditionAfterFailure("inference.model_load_failed", "chat failed: model_load_failed");

    assertEquals("InferenceModelLoadFailed", condition.reason(),
        "the cause the catalog words, as ai.not-ready carries it");
    assertEquals(Optional.empty(), condition.message(),
        "no evidence string where Health shows words");
  }

  @Test
  void aFailureWithoutAClassifiedCauseKeepsItsMessage() throws Exception {
    AssertedCondition condition =
        conditionAfterFailure("inference.activation_failed", "Self-test failed after 4200ms");

    assertEquals("Degraded", condition.reason(), "the capability health, as before");
    assertEquals(Optional.of("Self-test failed after 4200ms"), condition.message());
  }

  @Test
  void everyChatFailureCauseHasWordsOnBothHealthEvents() throws Exception {
    Properties catalog = new Properties();
    try (InputStream in = CapabilityHealthBridgeChatCauseTest.class.getClassLoader()
        .getResourceAsStream("messages/health-events.en.properties")) {
      assertNotNull(in, "the health-events catalog is on the classpath");
      catalog.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    for (String cause : CHAT_FAILURE_CAUSES) {
      for (String id : List.of("inference.capability", "ai.not-ready")) {
        String key = "health-events." + id + ".reason." + cause + ".message";
        String words = catalog.getProperty(key);
        assertNotNull(words, key + " is missing");
        assertTrue(words.startsWith("The local AI model"), key + " = " + words);
      }
    }
  }
}
