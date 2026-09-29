/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tempdoc 541 fix-pass Tier 4 (C-revised) — bounded ring buffer of rebuild events that
 * happen <em>after</em> a sealed {@link BootTrace}.
 *
 * <p>Per investigation against {@code HeadAssembly.prepareKnowledgeServerBinding}: a pure
 * {@code RebuildPhase} doesn't fit because that method has 7 field mutations + 2 lifecycle
 * side effects (indexingJobsBridge.start, registerAgentToolHandlers) that cannot be
 * expressed as a "produce a new Output" body. Instead of forcing the side effects through a
 * pure phase, the substrate tracks rebuild EVENTS in a separate ring buffer. The
 * {@link BootTrace} stays the once-per-process initial snapshot; this history captures
 * everything that mutates substrate state afterward (worker reconnect, lazy-phase
 * resolution, future runtime rebinds).
 *
 * <p>The buffer holds up to {@link #DEFAULT_CAPACITY} = 20 records. Once full, the oldest
 * entry is evicted in append order. Reads return a defensive copy from oldest to newest.
 *
 * <p><strong>Thread safety:</strong> appends are single-writer by convention (the head's
 * main thread invoking {@code prepareKnowledgeServerBinding} or holding the agent-tools Memoized's
 * resolution); reads use a defensive {@link Collections#unmodifiableList} over a snapshot.
 * Concurrent appends would race; today there is no such path. If multi-writer becomes a
 * concern, wrap the ArrayList in a synchronized list and document the contract revision.
 *
 * <p>Endpoint shape: {@code GET /api/boot/phases} returns the {@link BootTrace} envelope
 * with an added {@code "rebuilds": [...]} field carrying the records in oldest→newest order.
 * FE consumers (BootPhasesPanel) render this as a "rebuild history" sub-section beneath the
 * main phases table.
 */
public final class RebuildHistory {

  /** Default ring capacity. */
  public static final int DEFAULT_CAPACITY = 20;

  private final int capacity;
  private final ArrayList<PhaseRecord> records;
  private final AtomicLong totalAppends = new AtomicLong();

  /** Construct with {@link #DEFAULT_CAPACITY} = 20. */
  public RebuildHistory() {
    this(DEFAULT_CAPACITY);
  }

  /** Construct with an explicit capacity ({@code >= 1}). */
  public RebuildHistory(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
    }
    this.capacity = capacity;
    this.records = new ArrayList<>(capacity);
  }

  /** Append a rebuild record. Evicts the oldest when capacity is reached. */
  public synchronized void record(PhaseRecord record) {
    if (record == null) {
      return;
    }
    if (records.size() == capacity) {
      records.remove(0);
    }
    records.add(record);
    totalAppends.incrementAndGet();
  }

  /** Defensive snapshot of records in oldest→newest order. */
  public synchronized List<PhaseRecord> snapshot() {
    return Collections.unmodifiableList(new ArrayList<>(records));
  }

  /** Total number of records ever appended (may exceed capacity due to ring eviction). */
  public long totalAppends() {
    return totalAppends.get();
  }

  /** Number of records currently held (≤ capacity). */
  public synchronized int size() {
    return records.size();
  }

  /** Ring capacity. */
  public int capacity() {
    return capacity;
  }
}
