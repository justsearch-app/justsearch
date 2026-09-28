/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class HeadlessAppInitialIndexDeadlineTest {
  @Test
  void deadlineEndsOnlyTheApiWaitAndPreservesTheInitialPhysicalOwner() {
    try (var components = new TestEngineComponents()) {
      var index = components.register(new ComponentSpec("index", true, Set.of(),
          ComponentSpec.ComposeCapability.BESIDE, Duration.ofMillis(1), 2));
      index.transition(ComponentState.STARTING, "worker.starting", "index root lock");
      var opening = new CompletableFuture<String>();

      assertNull(HeadlessApp.awaitInitialIndexStart(opening, index));
      assertFalse(opening.isDone(), "the timeout must not cancel the physical open");

      opening.complete("ready");
      assertEquals("ready", HeadlessApp.awaitInitialIndexStart(opening, index));
    }
  }
}
