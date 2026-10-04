/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.app.api.operations.OperationStore;
import java.util.List;
import java.util.Objects;

/** Bounded read view of the accepted operations table, plus the existing live observation fan-in. */
public final class OperationHistoryStore {
  public static final int DEFAULT_CAPACITY = OperationStore.RECENT_HISTORY_LIMIT;
  private final OperationStore operations;
  private final int capacity;
  private final List<java.util.function.Consumer<OperationHistoryEntry>> appendListeners =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  public OperationHistoryStore(OperationStore operations) { this(operations, DEFAULT_CAPACITY); }

  public OperationHistoryStore(OperationStore operations, int capacity) {
    this.operations = Objects.requireNonNull(operations, "operations");
    if (capacity <= 0 || capacity > DEFAULT_CAPACITY) {
      throw new IllegalArgumentException("History capacity must be between 1 and " + DEFAULT_CAPACITY);
    }
    this.capacity = capacity;
  }

  public int capacity() { return capacity; }
  public int size() { return recent().size(); }

  /** Existing live fan-in; no subscriber owns persistence or changes the committed read view. */
  public void addAppendListener(java.util.function.Consumer<OperationHistoryEntry> listener) {
    appendListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /**
   * Publish a live observation. Acceptance/terminal writes belong to OperationStore, never here.
   * A STORAGE_FAILED observation can be delivered while the committed row still says RUNNING;
   * it therefore cannot insert a fictitious terminal record into recent().
   */
  public void append(OperationHistoryEntry entry) {
    Objects.requireNonNull(entry, "entry");
    for (var listener : appendListeners) {
      try { listener.accept(entry); }
      catch (RuntimeException ignored) {
        // An observer cannot change committed history or prevent another observer.
      }
    }
  }

  public List<OperationHistoryEntry> recent() { return recent(capacity); }

  /** Oldest first within the latest bounded terminal window; every read observes the durable source. */
  public List<OperationHistoryEntry> recent(int limit) {
    if (limit < 0) throw new IllegalArgumentException("History limit must be non-negative");
    return operations.recentHistory(Math.min(limit, capacity)).stream().map(OperationHistoryProjection::entry).toList();
  }
}
