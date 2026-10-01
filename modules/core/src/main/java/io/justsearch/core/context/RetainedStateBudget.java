/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Root-owned retained-resource counters. Declared future producers do not report live zeroes. */
public final class RetainedStateBudget {
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  public synchronized void declare(String kind, int cap, String awaitingProducer) {
    declare(kind, cap, null, awaitingProducer);
  }

  /** Per-context metadata is retained for the cursor owner; C1 enforces only aggregate permits. */
  public synchronized void declare(String kind, int cap, Integer perContextCap, String awaitingProducer) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(awaitingProducer, "awaitingProducer");
    if (kind.isBlank() || cap < 1 || awaitingProducer.isBlank()
        || (perContextCap != null && (perContextCap < 1 || perContextCap > cap))) {
      throw new IllegalArgumentException("kind, positive cap and producer stage are required");
    }
    if (entries.putIfAbsent(kind, new Entry(cap, perContextCap, awaitingProducer)) != null) {
      throw new IllegalArgumentException("duplicate retained kind: " + kind);
    }
  }

  /** Called by the actual resource owner when it starts accounting every instance of this kind. */
  public synchronized void activate(String kind) {
    Entry entry = entry(kind);
    if (entry.live) throw new IllegalStateException("producer already active: " + kind);
    entry.live = true;
  }

  /** Project an owner's existing accounting; never maintain a second permit counter for it. */
  public synchronized void connect(String kind, java.util.function.Supplier<Integer> count) {
    Entry entry = entry(kind);
    if (entry.live) throw new IllegalStateException("producer already active: " + kind);
    entry.producer = Objects.requireNonNull(count, "count");
    entry.live = true;
  }

  public synchronized int cap(String kind) {
    return entry(kind).cap;
  }

  public synchronized Optional<Permit> tryAcquire(String kind) {
    Entry entry = entry(kind);
    if (!entry.live) throw new IllegalStateException("producer is not connected: " + kind);
    if (entry.producer != null) throw new IllegalStateException("owner enforces projected kind: " + kind);
    if (entry.count == entry.cap) return Optional.empty();
    entry.count++;
    return Optional.of(new Permit(entry));
  }

  public List<Snapshot> snapshot() {
    List<java.util.function.Supplier<Snapshot>> projections;
    synchronized (this) {
      projections = entries.entrySet().stream().map(e -> {
        Entry entry = e.getValue();
        String kind = e.getKey();
        var producer = entry.producer;
        Integer count = entry.live ? Integer.valueOf(entry.count) : null;
        String awaiting = entry.live ? null : entry.stage;
        return (java.util.function.Supplier<Snapshot>) () -> {
          Integer actual = producer == null ? count : producer.get();
          return new Snapshot(kind, entry.cap, entry.perContextCap, actual,
              awaiting);
        };
      }).toList();
    }
    // Owner callbacks take their lifecycle locks; do not call them under the budget monitor.
    return projections.stream().map(java.util.function.Supplier::get).toList();
  }

  private Entry entry(String kind) {
    Entry entry = entries.get(kind);
    if (entry == null) throw new IllegalArgumentException("undeclared retained kind: " + kind);
    return entry;
  }

  public record Snapshot(String kind, int cap, Integer perContextCap, Integer count, String awaitingProducer) {}

  private static final class Entry {
    private final int cap;
    private final Integer perContextCap;
    private final String stage;
    private int count;
    private boolean live;
    private java.util.function.Supplier<Integer> producer;

    private Entry(int cap, Integer perContextCap, String stage) {
      this.cap = cap;
      this.perContextCap = perContextCap;
      this.stage = stage;
    }
  }

  public final class Permit implements AutoCloseable {
    private final Entry entry;
    private boolean closed;

    private Permit(Entry entry) {
      this.entry = entry;
    }

    @Override
    public void close() {
      synchronized (RetainedStateBudget.this) {
        if (!closed) {
          closed = true;
          entry.count--;
        }
      }
    }
  }
}
