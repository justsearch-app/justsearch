/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

final class ConfigStorePublicationTest {
  @Test
  void preparedSwapConstructsEventBeforeCommitAndRejectsAStalePredecessor() {
    var initial = new ResolvedConfigBuilder().contributeEnvRegistry().build();
    var next = new ResolvedConfigBuilder().contributeEnvRegistry().build();
    var lock = new ReentrantReadWriteLock();
    var store = new ConfigStore(initial, lock);

    assertSame(lock, store.publicationLock());
    var prepared = store.prepareSwap(next);
    assertSame(initial, prepared.previous());
    assertSame(next, prepared.next());
    assertSame(initial, prepared.event().previous());
    assertSame(next, prepared.event().current());

    assertSame(prepared.event(), store.commitPrepared(prepared));
    assertSame(next, store.get());

    var other = new ResolvedConfigBuilder().contributeEnvRegistry().build();
    var otherStore = new ConfigStore(initial, lock);
    var later = otherStore.prepareSwap(next);
    otherStore.swap(other);
    assertThrows(IllegalStateException.class, () -> otherStore.commitPrepared(later));
    assertSame(other, otherStore.get());
  }

  @Test
  void ordinarySwapAndUpdateUseTheSharedPublicationLock() {
    var first = new ResolvedConfigBuilder().contributeEnvRegistry().build();
    var second = new ResolvedConfigBuilder().contributeEnvRegistry().build();
    var lock = new ReentrantReadWriteLock();
    var store = new ConfigStore(first, lock);
    var observed = new java.util.concurrent.atomic.AtomicReference<ConfigChangedEvent>();
    store.addListener(observed::set);

    var event = store.swap(second);
    store.notifyListeners(event);
    assertSame(second, store.get());
    assertSame(event, observed.get());
    assertEquals(second, event.current());
  }
}
