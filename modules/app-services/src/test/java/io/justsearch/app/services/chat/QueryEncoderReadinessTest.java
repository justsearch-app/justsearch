/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/*
 * Spec A §6.2 step 3 / U16: the boot-order hook fires once, on the KnowledgeServer's encoders
 * READY publication, whether that came before or after the hook was attached.
 */
final class QueryEncoderReadinessTest {

  private final TestEngineComponents registry = TestEngineComponents.fourComponents();

  @AfterEach
  void close() {
    registry.close();
  }

  @Test
  void firesOnceWhenTheEncodersBecomeReadyLater() throws Exception {
    AtomicInteger fired = new AtomicInteger();
    try (var ignored = QueryEncoderReadiness.whenReady(registry, fired::incrementAndGet)) {
      registry.handle("encoders").transition(ComponentState.STARTING, null, "loading");
      registry.handle("generative").transition(ComponentState.READY, null, "chat up");
      assertEquals(0, fired.get(), "neither a starting encoder nor another component opens it");

      registry.handle("encoders").transition(ComponentState.READY, null, "query sessions up");
      assertEquals(1, fired.get());

      registry.handle("encoders").transition(ComponentState.RELOADING, null, "reload");
      registry.handle("encoders").transition(ComponentState.READY, null, "reloaded");
      assertEquals(1, fired.get(), "a later reload never fires it again");
    }
  }

  @Test
  void firesAtOnceWhenTheEncodersAreAlreadyReady() throws Exception {
    registry.handle("encoders").transition(ComponentState.READY, null, "query sessions up");
    AtomicInteger fired = new AtomicInteger();
    try (var ignored = QueryEncoderReadiness.whenReady(registry, fired::incrementAndGet)) {
      assertEquals(1, fired.get());
      registry.handle("encoders").transition(ComponentState.RELOADING, null, "reload");
      registry.handle("encoders").transition(ComponentState.READY, null, "reloaded");
      assertEquals(1, fired.get());
    }
  }

  @Test
  void aClosedHookNeverFires() throws Exception {
    AtomicInteger fired = new AtomicInteger();
    var hook = QueryEncoderReadiness.whenReady(registry, fired::incrementAndGet);
    hook.close();
    hook.close();
    registry.handle("encoders").transition(ComponentState.READY, null, "query sessions up");
    assertEquals(0, fired.get());
  }
}
