/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.util.List;

/**
 * Extension of {@link JobQueue} that supports a durable write-behind buffer for migration
 * SWITCHING state.
 *
 * <p>During index migration cutover, mutation operations (submit, delete, sync) are durably
 * buffered in the switch buffer instead of being applied to the active queue. After cutover
 * completes, the buffered operations are replayed against the new index generation.
 *
 * <p>Implementations: {@link SqliteJobQueue}.
 */
public interface SwitchBufferCapableQueue extends JobQueue {

  /** A buffered operation stored during cutover (SWITCHING). */
  record SwitchBufferOp(String key, String op, String payload, long lastUpdatedMs) {}

  /**
   * Inserts or replaces an operation in the durable switch buffer.
   *
   * <p><b>IMPORTANT:</b> This method is fail-closed. If the write fails, it returns {@code false}
   * and the caller MUST NOT acknowledge the operation as successful.
   *
   * @param key unique key for the operation (used for dedup/replace)
   * @param op operation type identifier
   * @param payload serialized operation payload
   * @return true if the write succeeded
   */
  boolean putSwitchBuffer(String key, String op, String payload);

  /** Atomically buffers sync; maintenance preserves an earlier admission's attribution. */
  boolean putSyncRoot(String key, SwitchBufferSyncRoot payload);

  /** Returns the number of buffered ops currently in the durable switch buffer. */
  long switchBufferDepth();

  /** Returns all buffered ops, sorted by last_updated ascending (best-effort). */
  List<SwitchBufferOp> listSwitchBufferOps();

  /** Clears all buffered ops. Returns the number of ops removed. */
  int clearSwitchBuffer();
}
