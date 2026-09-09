/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stable executor registrations shared by every index-service generation in one server. */
public final class WorkerExecutorRegistrations implements AutoCloseable {
  public static final String WATCHER_RECONCILE = "index.watcher-reconcile";
  public static final String EXTRACTION_TIMEBOX = "index.extraction-timebox";
  public static final String SANDBOX_READERS = "index.extraction-sandbox-readers";

  private final EngineExecutorRegistry.Registration watcherReconcile;
  private final EngineExecutorRegistry.Registration extractionTimebox;
  private final EngineExecutorRegistry.Registration sandboxReaders;

  public WorkerExecutorRegistrations(EngineExecutorRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    EngineExecutorRegistry.Limits limits = registry.limits(Kind.BACKGROUND);
    List<EngineExecutorRegistry.Registration> acquired = new ArrayList<>();
    try {
      watcherReconcile =
          acquire(
              registry,
              acquired,
              new EngineExecutorSpec(
                  WATCHER_RECONCILE,
                  Kind.BACKGROUND,
                  Mode.SCHEDULED,
                  1,
                  limits.maxQueue(),
                  2));
      extractionTimebox =
          acquire(
              registry,
              acquired,
              new EngineExecutorSpec(
                  EXTRACTION_TIMEBOX,
                  Kind.BACKGROUND,
                  Mode.PLATFORM,
                  1,
                  limits.maxQueue(),
                  2));
      sandboxReaders =
          acquire(
              registry,
              acquired,
              new EngineExecutorSpec(
                  SANDBOX_READERS,
                  Kind.BACKGROUND,
                  Mode.PLATFORM,
                  limits.maxThreads(),
                  limits.maxQueue(),
                  2));
    } catch (RuntimeException | Error failure) {
      closeReverse(acquired, failure);
      throw failure;
    }
  }

  public EngineExecutorRegistry.Registration watcherReconcile() {
    return watcherReconcile;
  }

  public EngineExecutorRegistry.Registration extractionTimebox() {
    return extractionTimebox;
  }

  public EngineExecutorRegistry.Registration sandboxReaders() {
    return sandboxReaders;
  }

  @Override
  public void close() {
    RuntimeException failure = null;
    for (EngineExecutorRegistry.Registration registration :
        List.of(sandboxReaders, extractionTimebox, watcherReconcile)) {
      try {
        registration.close();
      } catch (RuntimeException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static EngineExecutorRegistry.Registration acquire(
      EngineExecutorRegistry registry,
      List<EngineExecutorRegistry.Registration> acquired,
      EngineExecutorSpec spec) {
    EngineExecutorRegistry.Registration registration = registry.register(spec);
    acquired.add(registration);
    return registration;
  }

  private static void closeReverse(
      List<EngineExecutorRegistry.Registration> acquired, Throwable failure) {
    for (int i = acquired.size() - 1; i >= 0; i--) {
      try {
        acquired.get(i).close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
  }
}
