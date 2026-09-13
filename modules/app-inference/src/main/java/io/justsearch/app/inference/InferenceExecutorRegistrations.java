/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Logical executor owners for one inference-manager lifetime. */
final class InferenceExecutorRegistrations implements AutoCloseable {
  final EngineExecutorRegistry.Registration http;
  final EngineExecutorRegistry.Registration foregroundRequests;
  final EngineExecutorRegistry.Registration backgroundRequests;
  final EngineExecutorRegistry.Registration foregroundCallbacks;
  final EngineExecutorRegistry.Registration backgroundCallbacks;
  final EngineExecutorRegistry.Registration streamWatchdog;
  final EngineExecutorRegistry.Registration llamaHealth;
  final EngineExecutorRegistry.Registration llamaRecovery;
  final EngineExecutorRegistry.Registration llamaExit;
  final int callbackQueueCapacity;
  final int retryAfterSeconds;

  InferenceExecutorRegistrations(EngineExecutorRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    var foreground = registry.limits(Kind.FOREGROUND);
    var background = registry.limits(Kind.BACKGROUND);
    callbackQueueCapacity = foreground.maxQueue();
    retryAfterSeconds = registry.retryAfterSeconds();
    List<EngineExecutorRegistry.Registration> acquired = new ArrayList<>();
    try {
      http = register(registry, acquired, "inference.http", Kind.BACKGROUND, Mode.PLATFORM,
          background.maxThreads(), background.maxQueue());
      foregroundRequests = register(registry, acquired, "inference.request.foreground",
          Kind.FOREGROUND, Mode.PLATFORM, foreground.maxThreads(), foreground.maxQueue());
      backgroundRequests = register(registry, acquired, "inference.request.background",
          Kind.BACKGROUND, Mode.PLATFORM, background.maxThreads(), background.maxQueue());
      foregroundCallbacks = register(registry, acquired, "inference.callback.foreground",
          Kind.FOREGROUND, Mode.PLATFORM, foreground.maxThreads(), foreground.maxQueue());
      backgroundCallbacks = register(registry, acquired, "inference.callback.background",
          Kind.BACKGROUND, Mode.PLATFORM, background.maxThreads(), background.maxQueue());
      streamWatchdog = register(registry, acquired, "inference.stream-watchdog",
          Kind.BACKGROUND, Mode.SCHEDULED, 1, background.maxQueue());
      llamaHealth = register(registry, acquired, "inference.llama-health",
          Kind.BACKGROUND, Mode.SCHEDULED, 1, background.maxQueue());
      llamaRecovery = register(registry, acquired, "inference.llama-recovery",
          Kind.BACKGROUND, Mode.SCHEDULED, 1, background.maxQueue());
      llamaExit = register(registry, acquired, "inference.llama-exit", Kind.BACKGROUND,
          Mode.PLATFORM, 1, background.maxQueue());
    } catch (RuntimeException | Error failure) {
      closeReverse(acquired, failure);
      throw failure;
    }
  }

  private static EngineExecutorRegistry.Registration register(
      EngineExecutorRegistry registry, List<EngineExecutorRegistry.Registration> acquired,
      String name, Kind kind, Mode mode, int threads, int queue) {
    var registration = registry.register(new EngineExecutorSpec(name, kind, mode, threads, queue, 1));
    acquired.add(registration);
    return registration;
  }

  @Override
  public void close() {
    closeReverse(List.of(http, foregroundRequests, backgroundRequests, foregroundCallbacks,
        backgroundCallbacks, streamWatchdog, llamaHealth, llamaRecovery, llamaExit), null);
  }

  private static void closeReverse(
      List<EngineExecutorRegistry.Registration> registrations, Throwable primary) {
    RuntimeException first = null;
    for (int i = registrations.size() - 1; i >= 0; i--) {
      try {
        registrations.get(i).close();
      } catch (RuntimeException failure) {
        if (primary != null) primary.addSuppressed(failure);
        else if (first == null) first = failure;
        else first.addSuppressed(failure);
      }
    }
    if (primary == null && first != null) throw first;
  }
}
