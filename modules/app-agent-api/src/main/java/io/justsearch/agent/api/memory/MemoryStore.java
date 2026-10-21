/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.memory;

import java.util.List;

/**
 * Tempdoc 561 P-E — the ONE authority for the agent's learned memory ({@link MemoryRecord}). Mirrors
 * the thread/action-log discipline: a single durable store, with read-views that are projections of
 * it, never independent models. Its two user-facing projections are first-class obligations, not
 * afterthoughts: {@link #whatItKnows()} (inspect — single authority made legible) and {@link
 * #forget(String)} (user control — the on-device privacy story). A SECOND memory authority is a
 * build failure (the {@code operation-surface} register's memory single-authority declaration).
 */
public interface MemoryStore {

  /** Persist a learned item. Idempotent on {@code record.id()} (re-remember replaces). */
  void remember(MemoryRecord record);

  /**
   * The inspectable "what it knows" projection — every learned item, newest first.
   * @throws MemoriesUnavailableException if an unlocked store could not load completely; mutations
   *     refuse with the same error until a subsequent access successfully loads the store.
   */
  List<MemoryRecord> whatItKnows();

  /** User control — forget one item by id. No-op if absent. */
  void forget(String id);

  /** User control — forget everything. */
  void clear();

  /**
   * Tempdoc 806 W1 — TRUE when the store cannot be read or written right now (encrypted at rest with
   * the data key locked). It is the read-side companion {@link #whatItKnows()} cannot express: a
   * {@code List} has no value for "I cannot read", so empty-because-nothing-was-learned and
   * empty-because-locked collapse into the same answer and every consumer renders the unreadable state
   * as a positive claim ("No learned memory yet."). Mutations while locked throw {@code
   * KeyLockedException} rather than reporting a success the store cannot verify.
   *
   * <p>Default {@code false} — an unencrypted or in-memory store is always readable.
   */
  default boolean isLocked() {
    return false;
  }

  /** Null Object for environments without a configured memory store. */
  static MemoryStore noop() {
    return NoOpMemoryStore.INSTANCE;
  }
}
