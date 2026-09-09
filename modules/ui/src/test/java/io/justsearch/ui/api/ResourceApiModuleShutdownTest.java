/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class ResourceApiModuleShutdownTest {
  @Test
  void shutdownRetiresIntentAndRecoveryOwnersEvenWhenAnotherControllerFails() throws Exception {
    try (var executors = new DefaultEngineExecutorRegistry()) {
      var module = mock(ResourceApiModule.class, CALLS_REAL_METHODS);
      // Isolate teardown from the large substrate. Real timer owners below prove registration
      // retirement; unrelated controller mocks keep the production shutdown traversal intact.
      for (var field : ResourceApiModule.class.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) && field.getType().getSimpleName().endsWith("Controller")) {
          field.setAccessible(true);
          field.set(module, mock(field.getType()));
        }
      }
      set(module, "intentStreamController", new IntentStreamController(executors, new IntentEnvelopeChangeRegistry()));
      set(module, "conditionRecoveryIndexController", new ConditionRecoveryIndexController(
          executors, new ConditionStore(),
          new io.justsearch.app.observability.health.ConditionRecoveryIndexChangeRegistry()));
      set(module, "interactionThreadController", new InteractionThreadController(
          mock(io.justsearch.agent.api.conversation.ConversationStore.class),
          mock(io.justsearch.agent.api.AgentService.class), executors));
      var broken = mock(CapabilitiesStreamController.class);
      doThrow(new IllegalStateException("unrelated close failed")).when(broken).shutdown();
      set(module, "capabilitiesStreamController", broken);
      module.shutdown();
      assertTrue(executors.snapshot().registrations().isEmpty(),
          "front shutdown must retire its timer registrations before reconstruction");
      // A second front can reclaim the same logical names in the still-running process.
      var replacement = new IntentStreamController(executors, new IntentEnvelopeChangeRegistry());
      replacement.shutdown();
    }
  }

  private static void set(ResourceApiModule module, String name, Object value) throws Exception {
    var field = ResourceApiModule.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(module, value);
  }
}
