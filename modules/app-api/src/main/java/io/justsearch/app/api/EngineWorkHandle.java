/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;
import java.util.Optional;
import java.util.function.Consumer;

/** One reference to exact in-process work. Each reference closes at most once. */
public interface EngineWorkHandle extends AutoCloseable {
  EngineContext context();

  /** Retain across asynchronous ownership handoff; a closed reference cannot be retained. */
  EngineWorkHandle retain();

  Optional<String> cancellationReason();

  /** First reason wins. Callbacks request cancellation, never acknowledge completed work. */
  void cancel(String reason);

  /** Detach the waiting client; durable foreground work becomes background without cancellation. */
  void waitingClientGone();

  /** Registrations are detachable and run immediately if their event already happened. */
  Registration onCancel(Consumer<String> callback);
  Registration onBackground(Runnable callback);
  Registration onCompletion(Runnable callback);

  @Override
  void close();

  interface Registration extends AutoCloseable {
    @Override
    void close();
  }
}
