/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The single Engine producer of the indexing pacing gauge. Urgency alone decides whether work
 * counts. Interactive calls balance on actual worker completion, including errors and cancellation;
 * durable work owns one held increment until completion or waiting-client detachment.
 *
 * <p>The thread-local below is only a nesting assertion. Context and work identity are explicit
 * parameters and are never discovered through thread state. Parallel calls for one durable work
 * item are legitimate; a second wrap on the same thread is a composition defect.
 */
public final class ForegroundLoadGate {
  private final ForegroundLoad load;
  private final ThreadLocal<Set<UUID>> nested = ThreadLocal.withInitial(HashSet::new);
  private final Map<UUID, Held> durable = new HashMap<>();

  public ForegroundLoadGate(ForegroundLoad load) { this.load = Objects.requireNonNull(load, "load"); }

  public <T> T call(EngineWorkHandle work, Supplier<T> body) {
    Objects.requireNonNull(work, "work");
    Objects.requireNonNull(body, "body");
    UUID id = work.context().workId().orElseThrow();
    Set<UUID> current = nested.get();
    if (!current.add(id)) throw new IllegalStateException("Foreground work was wrapped twice");
    try {
      EngineContext context = work.context();
      if (context.urgency() == EngineContext.Urgency.BACKGROUND) return body.get();
      if (context.survival() == EngineContext.Survival.DURABLE) {
        hold(work, id);
        return body.get();
      }
      load.started();
      try {
        return body.get();
      } finally {
        load.finished();
      }
    } finally {
      current.remove(id);
      if (current.isEmpty()) nested.remove();
    }
  }

  public void run(EngineWorkHandle work, Runnable body) {
    call(work, () -> { body.run(); return null; });
  }

  private void hold(EngineWorkHandle work, UUID id) {
    synchronized (durable) {
      if (durable.containsKey(id)) return;
      Held held = new Held(id);
      durable.put(id, held);
      load.started();
      // Insert first: registering after an already-completed transition can call back immediately.
      held.register(work.onBackground(held::finish));
      held.register(work.onCompletion(held::finish));
    }
  }

  private final class Held {
    private final UUID id;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final List<EngineWorkHandle.Registration> registrations = new ArrayList<>();

    private Held(UUID id) { this.id = id; }

    private void register(EngineWorkHandle.Registration registration) {
      synchronized (durable) {
        if (finished.get()) registration.close();
        else registrations.add(registration);
      }
    }

    private void finish() {
      synchronized (durable) {
        if (!finished.compareAndSet(false, true)) return;
        durable.remove(id, this);
        load.finished();
        registrations.forEach(EngineWorkHandle.Registration::close);
        registrations.clear();
      }
    }
  }
}
