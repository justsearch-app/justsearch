/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.Closeable;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Slice 445 substrate boundary: a typed, snapshot-then-deltas feed of mutations
 * on the indexing job collection. The {@link SqliteJobQueue} concrete
 * implementation lives in {@code indexer-worker} (uses Xerial SQLite-specific
 * commit hooks); this interface is the seam against which the in-process port layer in
 * {@code worker-services} subscribes.
 *
 * <p>Per slice 445 §A.3 (lean scope, verification commit {@code 044b21ab3}):
 * concrete to the indexing-jobs Resource for V1. A future generic
 * {@code TabularChangeFeed<K, V>} extraction is justified only after a second
 * TABULAR Resource motivates it.
 *
 * <p>Privacy contract: rows are stamped with {@code pathHash} (SHA-256 hex of
 * the absolute normalized path), never raw paths. Path resolution is a
 * separate ADR-0028-pinned API.
 */
public interface IndexingJobChangeFeed {

  /** Maximum number of recent FAILED / RETRY_EXHAUSTED rows in the view. */
  int FAILURE_WINDOW_SIZE = 200;

  /** Committed row change or window replacement. Discriminated union — switches must be exhaustive. */
  sealed interface Delta {
    /** All-queue state presence, or null when the commit did not change it. */
    Set<String> presentStates();

    /** New row inserted into the queue. Carries the full row state. */
    record Insert(JobRow row, Set<String> presentStates) implements Delta {
      public Insert(JobRow row) { this(row, null); }
      public Insert { if (presentStates != null) presentStates = Set.copyOf(presentStates); }
    }

    /** Existing row mutated. Carries the post-commit row state. */
    record Update(JobRow row, Set<String> presentStates) implements Delta {
      public Update(JobRow row) { this(row, null); }
      public Update { if (presentStates != null) presentStates = Set.copyOf(presentStates); }
    }

    /**
     * Row removed from the queue. Only the primary key (pathHash) is carried
     * since the row no longer exists.
     */
    record Delete(String pathHash, Set<String> presentStates) implements Delta {
      public Delete(String pathHash) { this(pathHash, null); }
      public Delete { if (presentStates != null) presentStates = Set.copyOf(presentStates); }
    }

    /**
     * Replace the window after a deletion whose identity was not retained, or when a vacancy
     * reveals older failures. Emitted at most once per commit, after its row deltas. The bounded
     * post-commit snapshot travels with the marker so delivery never queries a newer transaction.
     */
    record Resync(List<JobRow> items, Set<String> presentStates) implements Delta {
      public Resync(List<JobRow> items) { this(items, null); }
      public Resync {
        items = List.copyOf(items);
        if (presentStates != null) presentStates = Set.copyOf(presentStates);
      }
    }
  }

  /**
   * Wire-shape mirror of the head-side {@code IndexingJobView}. Carries
   * worker-internal columns plus the SHA-256 path hash. The mapping to
   * {@code IndexingJobView} happens in the head's {@code RemoteTabularBridge}.
   */
  record JobRow(
      String pathHash,
      String state,
      int attempts,
      long lastUpdatedMs,
      String errorMessage,
      long retryAfterMs,
      String collection,
      String scanId) {

    public JobRow {
      Objects.requireNonNull(pathHash, "pathHash");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(collection, "collection");
      errorMessage = errorMessage == null ? "" : errorMessage;
      // Tempdoc 812 D2: empty means "not part of a directory scan" (single-file ingest, watcher,
      // or a row written before the scan_id column existed) — never null on the wire.
      scanId = scanId == null ? "" : scanId;
    }
  }

  /** Subscriber handle. Idempotent close. */
  interface Subscription extends Closeable {
    @Override
    void close();
  }

  /**
   * Snapshot + subscription bundle. {@code snapshotSeq} is the monotonic
   * counter at the time of snapshot; live deltas have {@code seq > snapshotSeq}.
   */
  record SnapshotAndSubscription(
      long snapshotSeq, List<JobRow> items, Set<String> presentStates, Subscription subscription) {
    public SnapshotAndSubscription(long snapshotSeq, List<JobRow> items, Subscription subscription) {
      this(snapshotSeq, items, null, subscription);
    }
    public SnapshotAndSubscription {
      if (presentStates != null) presentStates = Set.copyOf(presentStates);
    }
  }

  /**
   * Atomically captures a window (all PROCESSING rows and the newest 200 FAILED /
   * RETRY_EXHAUSTED rows) AND adds
   * {@code subscriber} to the live-delta broadcast list. Implementations must
   * guarantee that no delta with {@code seq <= snapshotSeq} is delivered to
   * the subscriber, and that no commit-time mutation is dropped.
   */
  SnapshotAndSubscription subscribeWithSnapshot(Consumer<Delta> subscriber) throws SQLException;

  /** Subscribe without snapshot. For callers that already have current state. */
  Subscription subscribe(Consumer<Delta> subscriber);

  /** Current monotonic seq counter. {@code 0} before any deltas. */
  long currentSeq();
}
