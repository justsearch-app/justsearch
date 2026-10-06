/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The KnowledgeServer's encoder readiness, as the chat controller's boot-order signal (Spec A §6.2
 * step 3). The KnowledgeServer publishes the {@value #ENCODERS} component READY once its query
 * sessions can serve; that publication is the hook. Whatever the encoder side does before it (a
 * query-encoder prewarm, say) therefore happens before chat starts, with no change here.
 *
 * <p>The hook only reads the registry's snapshots. It never holds, reads or touches an encoder.
 */
public final class QueryEncoderReadiness {

  /** The encoder component the KnowledgeServer publishes. */
  public static final String ENCODERS = "encoders";

  private QueryEncoderReadiness() {}

  /**
   * Runs {@code ready} once, the first time {@code registry} shows the encoder component READY:
   * now, or on a later publication. The subscription ends after that run.
   *
   * @return closes the subscription early (idempotent)
   */
  public static AutoCloseable whenReady(EngineComponentRegistry registry, Runnable ready) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(ready, "ready");
    AtomicBoolean fired = new AtomicBoolean();
    AtomicReference<EngineComponentRegistry.Subscription> subscription = new AtomicReference<>();
    AtomicBoolean closed = new AtomicBoolean();
    Runnable unsubscribe = () -> {
      var current = subscription.get();
      if (current != null && closed.compareAndSet(false, true)) current.close();
    };
    java.util.function.Consumer<EngineComponentSnapshot> observe = snapshot -> {
      if (encodersReady(snapshot) && fired.compareAndSet(false, true)) {
        try {
          ready.run();
        } finally {
          unsubscribe.run();
        }
      }
    };
    subscription.set(registry.subscribe(observe));
    // A READY published before the subscription is seen here; one published after it, there.
    observe.accept(registry.snapshot());
    if (fired.get()) unsubscribe.run();
    return unsubscribe::run;
  }

  static boolean encodersReady(EngineComponentSnapshot snapshot) {
    if (snapshot == null) return false;
    for (EngineComponentSnapshot.Component component : snapshot.components()) {
      if (ENCODERS.equals(component.spec().name())) {
        return component.state() == ComponentState.READY;
      }
    }
    return false;
  }
}
