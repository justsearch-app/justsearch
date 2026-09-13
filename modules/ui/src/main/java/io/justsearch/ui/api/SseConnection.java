/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/** One physical connection owns all subscriptions and its heartbeat, including late acquisitions. */
final class SseConnection {
  private final SseClient client;
  private final Runnable onClose;
  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private final List<Runnable> resources = new ArrayList<>();
  private boolean closed;

  SseConnection(SseClient client, Runnable onClose) {
    this.client = client;
    this.onClose = onClose;
  }

  void start() {
    client.onClose(this::terminate);
    if (client.terminated()) terminate();
    // Pre-created future also covers close during callback/future registration.
    if (client.ctx() != null) client.ctx().future(() -> completion);
  }

  synchronized boolean isClosed() {
    return closed;
  }

  void own(SseStreamChannel.Subscription subscription) {
    own(subscription::unsubscribe);
    subscription.onRetire(this::terminate);
  }

  void own(ScheduledFuture<?> heartbeat) {
    own(() -> heartbeat.cancel(false));
  }

  private void own(Runnable cleanup) {
    synchronized (this) {
      if (!closed) {
        resources.add(cleanup);
        return;
      }
    }
    cleanup.run();
  }

  void terminate() {
    Throwable failure = closeResources(null);
    if (failure instanceof RuntimeException problem) throw problem;
    if (failure instanceof Error problem) throw problem;
  }

  private Throwable closeResources(Throwable failure) {
    List<Runnable> cleanup;
    synchronized (this) {
      if (closed) return failure;
      closed = true;
      cleanup = new ArrayList<>(resources);
      resources.clear();
    }
    cleanup.addFirst(onClose);
    cleanup.add(client::close);
    try {
      for (Runnable action : cleanup) {
        try {
          action.run();
        } catch (RuntimeException | Error problem) {
          if (failure == null) failure = problem;
          else if (failure != problem) failure.addSuppressed(problem);
        }
      }
    } finally {
      completion.complete(null);
    }
    return failure;
  }

  void terminateAfter(Throwable failure) {
    closeResources(failure);
  }
}
