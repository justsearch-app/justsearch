/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.Mockito.mock;

import io.justsearch.app.services.lifecycle.LifecycleProjection;
import io.justsearch.core.component.ComponentState;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The served schema-2 lifecycle and runtime manifest share one post-sampling observation. */
class StatusLifecycleHandlerManifestFallbackTest {

  @Test
  void servedLifecycleAndManifestAgreeAfterSamplingForEveryInputCombination(@TempDir Path temp)
      throws Exception {
    try (var components = new StatusComponentFixture();
        var publisher = new RuntimeManifestPublisher(temp)) {
      publisher.publishHead(54321, null);
      var graph = components.capabilities();
      StatusLifecycleHandler handler = new StatusLifecycleHandler(
          mock(io.justsearch.app.api.OnlineAiService.class),
          mock(io.justsearch.agent.api.AgentService.class),
          () -> null,
          null,
          null,
          temp.resolve("index"),
          Instant.now(),
          () -> "OK",
          null,
          null,
          null,
          graph.worker(),
          graph.inference());
      components.attach(handler);

      int combinations = 0;
      int normalizedReadyIndexes = 0;
      List<String> names = List.of("api", "index", "encoders", "generative");
      for (ComponentState api : ComponentState.values()) {
        for (ComponentState index : ComponentState.values()) {
          for (ComponentState encoders : ComponentState.values()) {
            for (ComponentState generative : ComponentState.values()) {
              ComponentState[] requested = {api, index, encoders, generative};
              for (String name : names) {
                components.transition(name, ComponentState.READY, null, name + " reset");
              }
              for (int position = 0; position < names.size(); position++) {
                ComponentState state = requested[position];
                components.transition(
                    names.get(position),
                    state,
                    state == ComponentState.READY ? null : names.get(position) + "." + state,
                    names.get(position) + " input " + state);
              }

              var response = handler.buildStatusSnapshot();
              var accepted = components.registry().snapshot();
              var expected = LifecycleProjection.project(accepted, Instant.now());
              publisher.publishLifecycle(accepted);

              assertEquals(2, response.schemaVersion());
              assertEquals(expected.lifecycle().lifecycle(), response.lifecycle());
              assertEquals(expected.lifecycle().components(), response.components());
              assertEquals(expected.engineComponents(), response.readiness().engineComponents());
              assertEquals(response.lifecycle().state().name(), publisher.current().lifecycle());
              if (index == ComponentState.READY) {
                assertEquals(
                    ComponentState.UNAVAILABLE,
                    accepted.components().stream()
                        .filter(component -> component.spec().name().equals("index"))
                        .findFirst()
                        .orElseThrow()
                        .state(),
                    "failed Worker contact normalizes a claimed READY index");
                normalizedReadyIndexes++;
              }
              combinations++;
            }
          }
        }
      }

      assertEquals(1_296, combinations);
      assertEquals(216, normalizedReadyIndexes);
    }
  }
}
