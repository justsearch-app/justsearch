/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.SandboxServiceUnavailableException;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexWriterUnavailableException;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.indexerworker.ingest.IngestionServiceUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Paced, summarized failures; only current full-pipeline health permits a file charge. */
final class BatchServiceFailures {
  private static final Logger log = LoggerFactory.getLogger(BatchServiceFailures.class);
  private static final long MAX_DELAY_MS = 8_000;
  private static final long LOG_INTERVAL_NS = 60_000_000_000L;
  private final List<Failure> pending = new ArrayList<>();
  private boolean active;
  private boolean succeeded;
  private long nextDelayMs = 1_000;
  private long delayMs;
  private long retryAtMs;
  private long lastLogNs;
  private long unreportedFailures;
  private Throwable sample;
  private boolean healthy;
  void begin() {
    pending.clear();
    succeeded = false;
    delayMs = 0;
    sample = null;
    healthy = false;
    active = true;
  }

  void success() {
    succeeded = true;
    nextDelayMs = 1_000;
  }

  boolean succeeded() { return succeeded; }

  void failure(Throwable failure, Path source, Path sourceRealPath, Consumer<Boolean> record) {
    if (sourceFailure(failure, source, sourceRealPath)) record.accept(false);
    else failure(failure, record);
  }

  void failure(Throwable failure, Consumer<Boolean> record) {
    noteFailure(failure);
    if (active) pending.add(new Failure(record, unavailable(failure)));
    else record.accept(false);
  }

  // Source IO remains per-file even if a simultaneous infrastructure probe would fail.
  static boolean sourceFailure(Throwable failure, Path source, Path sourceRealPath) {
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
      if (cause instanceof FileSystemException fs && fs.getFile() != null) {
        try {
          Path failed = Path.of(fs.getFile()).toAbsolutePath().normalize();
          if (source.toAbsolutePath().normalize().equals(failed)
              || (sourceRealPath != null
                  && sourceRealPath.toAbsolutePath().normalize().equals(failed))) return true;
        } catch (java.nio.file.InvalidPathException invalid) {
          // An invalid path cannot identify this source.
        }
      }
    }
    return !Files.isReadable(source)
        || (sourceRealPath != null && !Files.isReadable(sourceRealPath));
  }

  void unavailable(Throwable failure, Consumer<Boolean> record) {
    noteFailure(failure);
    if (active) {
      pending.add(new Failure(record, true));
    } else {
      // Standalone writes have no batch finalizer; preserve their immediate uncounted outcome.
      retryAtMs = System.currentTimeMillis() + 1_000;
      record.accept(true);
    }
  }

  void noteFailure(Throwable failure) {
    unreportedFailures++;
    if (sample == null) sample = failure;
  }

  boolean deferredBatch() {
    return delayMs > 0;
  }

  long retryDelayMs() {
    return delayMs;
  }

  long retryAtMs() {
    return retryAtMs;
  }

  // Classify both services before persisting outcomes so their rows and the loop share one deadline.
  long prepareRetry() {
    return prepareRetry(0, () -> true);
  }

  long prepareRetry(long minimumDelayMs) {
    return prepareRetry(minimumDelayMs, () -> true);
  }

  long prepareRetry(BooleanSupplier canary) { return prepareRetry(0, canary); }

  private long prepareRetry(long minimumDelayMs, BooleanSupplier canary) {
    active = false;
    if (pending.stream().anyMatch(f -> !f.unavailable())) healthy = canary.getAsBoolean();
    if (healthy) success();
    if (pending.stream().anyMatch(this::systemic)) {
      delayMs = Math.max(nextDelayMs, Math.min(MAX_DELAY_MS, minimumDelayMs));
      nextDelayMs = Math.min(MAX_DELAY_MS, delayMs * 2);
    }
    return delayMs;
  }

  private boolean systemic(Failure failure) {
    return failure.unavailable() || !healthy;
  }

  void finish() {
    long delay = prepareRetry();
    finish(System.currentTimeMillis() + delay);
  }

  void finish(long batchRetryAtMs) {
    retryAtMs = batchRetryAtMs;
    try {
      for (var failure : pending) {
        failure.record().accept(systemic(failure));
      }
      long now = System.nanoTime();
      if (sample != null && (lastLogNs == 0 || now - lastLogNs >= LOG_INTERVAL_NS)) {
        log.error("Ingestion service failures since last report: {}; retry pause {} ms",
            unreportedFailures, delayMs, sample);
        lastLogNs = now;
        unreportedFailures = 0;
      }
    } finally {
      pending.clear();
      sample = null;
    }
  }

  private static boolean unavailable(Throwable failure) {
    if (failure instanceof EngineExecutorRejectedException
        || failure instanceof IndexWriterUnavailableException
        || failure instanceof IngestionServiceUnavailableException
        || failure instanceof SandboxServiceUnavailableException) return true;
    return failure instanceof IndexRuntimeIOException indexError
        && (indexError.reason() == IndexRuntimeIOException.Reason.DRAINING
            || indexError.reason() == IndexRuntimeIOException.Reason.BACKPRESSURE);
  }

  private record Failure(Consumer<Boolean> record, boolean unavailable) {}
}
