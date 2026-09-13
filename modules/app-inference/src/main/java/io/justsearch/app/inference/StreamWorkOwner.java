/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.EngineWorkHandle;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One queued/active model exchange, with cancellation bound to its actual producer and body. */
final class StreamWorkOwner implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(StreamWorkOwner.class);
  private final Object lock = new Object();
  private final EngineWorkHandle work;
  private final EngineWorkHandle.Registration cancellation;
  private final Consumer<String> onComplete;
  private final Consumer<Throwable> onError;
  private final AtomicBoolean terminal = new AtomicBoolean();
  private Thread producer;
  private InputStream body;
  private boolean closed;

  StreamWorkOwner(EngineWorkHandle work, Consumer<String> onComplete, Consumer<Throwable> onError) {
    this.work = work == null ? null : work.retain();
    this.onComplete = onComplete;
    this.onError = onError;
    try {
      this.cancellation = this.work == null ? null : this.work.onCancel(reason -> cancel());
    } catch (RuntimeException | Error failure) {
      if (this.work != null) this.work.close();
      throw failure;
    }
  }

  EngineWorkHandle work() { return work; }

  void start() {
    synchronized (lock) {
      checkCancelled();
      producer = Thread.currentThread();
    }
  }

  void body(InputStream stream) {
    synchronized (lock) {
      body = stream;
    }
    if (work != null && work.cancellationReason().isPresent()) closeBody(stream);
    checkCancelled();
  }

  void checkCancelled() {
    if (work != null) work.cancellationReason().ifPresent(reason -> {
      throw new EngineWorkCancelledException(reason);
    });
  }

  private void cancel() {
    InputStream activeBody;
    synchronized (lock) {
      if (closed) return;
      if (producer != null) producer.interrupt();
      activeBody = body;
    }
    if (activeBody != null) closeBody(activeBody);
  }

  void complete(String reason) {
    if (work != null && work.cancellationReason().isPresent()) {
      fail(new EngineWorkCancelledException(work.cancellationReason().orElseThrow()));
    } else if (terminal.compareAndSet(false, true)) onComplete.accept(reason);
  }

  void fail(Throwable failure) {
    Throwable result = work != null && work.cancellationReason().isPresent()
        ? new EngineWorkCancelledException(work.cancellationReason().orElseThrow()) : failure;
    if (terminal.compareAndSet(false, true)) onError.accept(result);
  }

  @Override public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      if (producer == Thread.currentThread() && work != null
          && work.cancellationReason().isPresent()) Thread.interrupted();
      producer = null;
      body = null;
    }
    if (cancellation != null) cancellation.close();
    if (work != null) work.close();
  }

  private static void closeBody(InputStream stream) {
    try {
      stream.close();
    } catch (IOException failure) {
      LOG.debug("Cancelled model response body could not close cleanly", failure);
    }
  }
}
