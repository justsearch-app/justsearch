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

  public synchronized Optional<Permit> tryAcquire(String kind) {
    Entry entry = entry(kind);
    if (!entry.live) throw new IllegalStateException("producer is not connected: " + kind);
    if (entry.count == entry.cap) return Optional.empty();
    entry.count++;
    return Optional.of(new Permit(entry));
  }

  public synchronized List<Snapshot> snapshot() {
    return entries.entrySet().stream().map(e -> new Snapshot(e.getKey(), e.getValue().cap,
        e.getValue().perContextCap,
        e.getValue().live ? Integer.valueOf(e.getValue().count) : null,
        e.getValue().live ? null : e.getValue().stage)).toList();
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
