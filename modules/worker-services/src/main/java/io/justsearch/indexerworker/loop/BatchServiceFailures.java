/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexWriterUnavailableException;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.indexerworker.ingest.IngestionServiceUnavailableException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded service history, separate for the writer and identity store. A successful peer
 * makes unknown failures document-specific. Without one, only explicit infrastructure signals prove an outage;
 * unknown runtime failures get three grace batches before using the durable file retry ladder.
 * History is constant-size and resets on service success or worker restart. Restart grants three
 * new grace batches but never resets persisted attempts or exhaustion.
 */
final class BatchServiceFailures {
  private static final Logger log = LoggerFactory.getLogger(BatchServiceFailures.class);
  private static final long MAX_DELAY_MS = 8_000;
  private static final long LOG_INTERVAL_NS = 60_000_000_000L;
  private final List<Failure> pending = new ArrayList<>();
  private boolean active;
  private boolean succeeded;
  private int ambiguousBatches;
  private long nextDelayMs = 1_000;
  private long delayMs;
  private long retryAtMs;
  private long lastLogNs;
  private long unreportedFailures;
  private Throwable sample;

  void begin() {
    pending.clear();
    succeeded = false;
    delayMs = 0;
    sample = null;
    active = true;
  }

  void success() {
    succeeded = true;
    ambiguousBatches = 0;
    nextDelayMs = 1_000;
  }

  void failure(Throwable failure, Consumer<Boolean> record) {
    noteFailure(failure);
    if (active && failure instanceof RuntimeException
        && !(failure instanceof IllegalArgumentException)) {
      pending.add(new Failure(failure, record, false));
    } else {
      record.accept(false);
    }
  }

  void unavailable(Throwable failure, Consumer<Boolean> record) {
    noteFailure(failure);
    if (active) {
      pending.add(new Failure(failure, record, true));
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
    return prepareRetry(0);
  }

  long prepareRetry(long minimumDelayMs) {
    active = false;
    boolean ambiguous = pending.stream().anyMatch(f -> !f.unavailable() && !provenOutage(f.error()));
    if (!succeeded && ambiguous) ambiguousBatches = Math.min(4, ambiguousBatches + 1);
    if (pending.stream().anyMatch(this::systemic)) {
      delayMs = Math.max(nextDelayMs, Math.min(MAX_DELAY_MS, minimumDelayMs));
      nextDelayMs = Math.min(MAX_DELAY_MS, delayMs * 2);
    }
    return delayMs;
  }

  private boolean systemic(Failure failure) {
    return failure.unavailable() || provenOutage(failure.error())
        || (!succeeded && ambiguousBatches <= 3);
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

  private static boolean provenOutage(Throwable failure) {
    if (failure instanceof EngineExecutorRejectedException) return true;
    if (failure instanceof IndexWriterUnavailableException
        || failure instanceof IngestionServiceUnavailableException) return true;
    // Untagged DISK_IO can concern a document; the Lucene boundary tags index-owned IO.
    if (failure instanceof IndexRuntimeIOException indexError) {
      if (indexError.isIndexStorageFailure()) return true;
      return switch (indexError.reason()) {
        case DISK_FULL, LOCKED, CORRUPT_INDEX, SCHEMA_MISMATCH, BACKPRESSURE, DRAINING -> true;
        default -> false;
      };
    }
    // SQLite wraps SQLException in IllegalStateException. Constraints and unknown SQL errors
    // are ambiguous; these primary result codes specifically describe shared store availability.
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
      if (cause instanceof SQLException sql) {
        return switch (sql.getErrorCode() & 0xff) {
          case 5, 6, 8, 10, 11, 13, 14, 26 -> true; // BUSY, LOCKED, READONLY, IOERR, CORRUPT, FULL, CANTOPEN, NOTADB
          default -> false;
        };
      }
    }
    return false;
  }

  private record Failure(Throwable error, Consumer<Boolean> record, boolean unavailable) {}
}
