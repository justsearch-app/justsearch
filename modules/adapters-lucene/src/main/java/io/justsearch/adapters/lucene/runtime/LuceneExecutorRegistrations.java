/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/** The bounded executor registrations owned by one Lucene runtime. */
public final class LuceneExecutorRegistrations implements AutoCloseable {
  private final EngineExecutorRegistry registry;
  private final EngineExecutorRegistry.Registration commitTimer;
  private final EngineExecutorRegistry.Registration foregroundSearchFanout;
  private final EngineExecutorRegistry.Registration backgroundSearchFanout;

  public LuceneExecutorRegistrations(EngineExecutorRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    EngineExecutorRegistry.Registration commit = null;
    EngineExecutorRegistry.Registration foreground = null;
    EngineExecutorRegistry.Registration background = null;
    try {
      EngineExecutorRegistry.Limits backgroundLimits = registry.limits(EngineExecutorSpec.Kind.BACKGROUND);
      commit = registry.register(new EngineExecutorSpec(
          "head.lucene.commit-timer", EngineExecutorSpec.Kind.BACKGROUND,
          EngineExecutorSpec.Mode.SCHEDULED, 1, backgroundLimits.maxQueue(), 3));
      int maxInstances = registry.maxConcurrentWork();
      foreground = registry.register(EngineExecutorSpec.virtual(
          "head.lucene.search-fanout-foreground", EngineExecutorSpec.Kind.FOREGROUND,
          maxInstances));
      background = registry.register(
          EngineExecutorSpec.virtual("head.lucene.search-fanout-background",
              EngineExecutorSpec.Kind.BACKGROUND, maxInstances));
      this.commitTimer = commit;
      this.foregroundSearchFanout = foreground;
      this.backgroundSearchFanout = background;
    } catch (RuntimeException | Error failure) {
      if (foreground != null) {
        try { foreground.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      }
      if (background != null) {
        try { background.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      }
      if (commit != null) {
        try { commit.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      }
      throw failure;
    }
  }

  public ScheduledExecutorService openCommitTimer(ThreadFactory factory) {
    return commitTimer.openScheduled(Objects.requireNonNull(factory, "factory"));
  }

  public ExecutorService openSearchFanout(EngineContext.Urgency urgency) {
    Objects.requireNonNull(urgency, "urgency");
    return switch (urgency) {
      case FOREGROUND -> foregroundSearchFanout.openVirtual();
      case BACKGROUND -> backgroundSearchFanout.openVirtual();
    };
  }

  /** Package-visible metadata hook for runtime tests; the process registry remains externally owned. */
  EngineExecutorRegistry registry() { return registry; }

  @Override
  public void close() {
    RuntimeException first = null;
    for (EngineExecutorRegistry.Registration registration :
        new EngineExecutorRegistry.Registration[] {backgroundSearchFanout, foregroundSearchFanout, commitTimer}) {
      try { registration.close(); }
      catch (RuntimeException failure) { if (first == null) first = failure; else first.addSuppressed(failure); }
    }
    if (first != null) throw first;
  }
}
