/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InferenceDecisionTest {
  @Test
  void capturedSettingsControlExistenceDespiteConflictingGlobals() {
    ConfigStore previous = ConfigStore.globalOrNull();
    String previousLite = System.getProperty("justsearch.lite.mode");
    String previousDisabled = System.getProperty("justsearch.ai.disabled");
    String previousLlm = System.getProperty("justsearch.llm.enabled");
    try {
      System.setProperty("justsearch.lite.mode", "false");
      System.setProperty("justsearch.ai.disabled", "true");
      System.setProperty("justsearch.llm.enabled", "false");
      var enabled = TestResolvedConfigHelper.fromEntries(Map.of(
          "justsearch.ai.disabled", "false", "justsearch.llm.enabled", "true"));
      var disabled = TestResolvedConfigHelper.fromEntries(Map.of(
          "justsearch.ai.disabled", "false", "justsearch.llm.enabled", "false"));
      ConfigStore.setGlobal(new ConfigStore(disabled));
      assertTrue(InferenceDecision.decideInferenceConfigured(enabled),
          "the already captured settings win over replacement global/raw AI flags");

      ConfigStore.setGlobal(new ConfigStore(enabled));
      System.setProperty("justsearch.llm.enabled", "true");
      assertFalse(InferenceDecision.decideInferenceConfigured(disabled),
          "the LLM hard-disable must control existence, not just smoke diagnostics");
      assertFalse(InferenceDecision.decideInferenceConfigured(
          TestResolvedConfigHelper.fromEntries(Map.of(
              "justsearch.ai.disabled", "true", "justsearch.llm.enabled", "true"))));
      System.setProperty("justsearch.lite.mode", "true");
      assertFalse(InferenceDecision.decideInferenceConfigured(enabled),
          "the process lite-mode gate remains stronger than LLM enablement");
      assertTrue(InferenceDecision.decideInferenceConfigured(enabled, false),
          "the retained non-lite decision must win over a contradictory raw global");
      System.setProperty("justsearch.lite.mode", "false");
      assertFalse(InferenceDecision.decideInferenceConfigured(enabled, true),
          "the retained lite decision must win over a contradictory raw global");
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
      restore("justsearch.lite.mode", previousLite);
      restore("justsearch.ai.disabled", previousDisabled);
      restore("justsearch.llm.enabled", previousLlm);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }
}
