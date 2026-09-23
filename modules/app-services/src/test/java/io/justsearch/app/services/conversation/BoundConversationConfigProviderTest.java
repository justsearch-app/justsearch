/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.core.context.EngineContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundConversationConfigProviderTest {
  @Test
  void rebasedNestedWorkRetainsOneSettingsViewUntilTurnCloses() {
    var a = ConfigStoreRebuilder.prepare(new UiSettings());
    var b = ConfigStoreRebuilder.prepare(new UiSettings());
    var current = new AtomicReference<>(a);
    var provider = new BoundConversationConfigProvider(current::get);
    EngineContext admitted = TestEngineContexts.internal().withWorkId(UUID.randomUUID());
    EngineContext nested = new EngineContext(admitted.clientKind(), admitted.clientId(),
        Optional.of("nested"), admitted.grantReference(), admitted.sourceTier(), "WORKFLOW",
        admitted.survival(), admitted.urgency(), admitted.workId());
    try (var ignored = provider.bind(admitted, a)) {
      current.set(b);
      assertSame(a, provider.resolve(nested));
    }
    assertSame(b, provider.resolve(nested));
  }

  @Test
  void concurrentTurnsCanHoldDifferentSettingsViews() {
    var a = ConfigStoreRebuilder.prepare(new UiSettings());
    var b = ConfigStoreRebuilder.prepare(new UiSettings());
    var provider = new BoundConversationConfigProvider(() -> b);
    EngineContext first = TestEngineContexts.internal().withWorkId(UUID.randomUUID());
    EngineContext second = TestEngineContexts.internal().withWorkId(UUID.randomUUID());
    try (var firstBinding = provider.bind(first, a);
        var secondBinding = provider.bind(second, b)) {
      assertNotNull(firstBinding);
      assertNotNull(secondBinding);
      assertSame(a, provider.resolve(first));
      assertSame(b, provider.resolve(second));
    }
  }
}
