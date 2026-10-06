/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Late-bound bridge for the normal-quit shutdown (tempdoc 805 G.1), mirroring {@link
 * UpgradeShutdownBridge}: the API starts before Worker connection, while the ordered shutdown owner
 * can only be assembled after all resources exist.
 *
 * <p>The installed action receives the request body (Spec F §4.3) when the lifecycle module hands
 * one over; run as a plain {@link Runnable}, it receives {@link LifecycleShutdownRequest#NONE}.
 */
public final class LifecycleShutdownBridge implements Runnable, LifecycleShutdownAction {
  private final AtomicReference<LifecycleShutdownAction> delegate = new AtomicReference<>();

  public void install(Runnable action) {
    Objects.requireNonNull(action);
    install((LifecycleShutdownAction) request -> action.run());
  }

  public void install(LifecycleShutdownAction action) {
    if (!delegate.compareAndSet(null, Objects.requireNonNull(action))) {
      throw new IllegalStateException("lifecycle shutdown action already installed");
    }
  }

  @Override
  public void run() {
    shutdown(LifecycleShutdownRequest.NONE);
  }

  @Override
  public void shutdown(LifecycleShutdownRequest request) {
    LifecycleShutdownAction action = delegate.get();
    if (action == null) {
      throw new IllegalStateException("lifecycle shutdown action is not ready");
    }
    action.shutdown(request);
  }
}
