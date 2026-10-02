/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.UnlockDeferredScan;
import io.justsearch.core.execution.EngineExecutorRegistry;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One coalesced background backfill obligation, resumed on unlock and retried after failure. */
public final class FeedbackLookupMaintenance implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(FeedbackLookupMaintenance.class);
  private static final long MAX_BACKOFF_MS = 30_000L;
  private final NdjsonAppendStore<FeatureSnapshot> snapshots;
  private final StoreCipher cipher;
  private final UnlockDeferredScan scans;
  private final long baseBackoffMs;
  private final CompletableFuture<Void> ready = new CompletableFuture<>();
  private volatile boolean closed;
  private volatile Thread worker;

  public FeedbackLookupMaintenance(
      EngineExecutorRegistry executors, Path archive, StoreCipher cipher, DataKeyManager keys) {
    this.cipher = cipher;
    snapshots = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    baseBackoffMs = Math.min(MAX_BACKOFF_MS, TimeUnit.SECONDS.toMillis(executors.retryAfterSeconds()));
    scans = new UnlockDeferredScan(executors, "feedback-identity-lookup", this::initializeWithRetry);
    try {
      scans.attachTo(keys);
      scans.schedule();
    } catch (RuntimeException | Error failure) {
      scans.close();
      throw failure;
    }
  }

  /** Completes only after the complete identity generation is available, including locked boot. */
  public CompletableFuture<Void> ready() {
    return ready;
  }

  private void initializeWithRetry() {
    worker = Thread.currentThread();
    long backoffMs = baseBackoffMs;
    try {
      while (!closed && !Thread.currentThread().isInterrupted()) {
        try {
          snapshots.initializeLookup();
          if (snapshots.lookupInitialized()) ready.complete(null);
          return; // Locked archives wait for an unlock notification without polling.
        } catch (Exception failure) {
          if (closed || (cipher.enabled() && cipher.locked())) return;
          log.debug("Feedback identity backfill failed; retaining background retry", failure);
        }
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
      }
    } finally {
      worker = null;
    }
  }

  @Override
  public void close() {
    closed = true;
    Thread active = worker;
    if (active != null && active != Thread.currentThread()) active.interrupt();
    scans.close();
    ready.completeExceptionally(new IllegalStateException("Feedback lookup maintenance closed"));
  }
}
