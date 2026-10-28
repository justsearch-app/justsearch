/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One witnessed native file replacement, conditional on its complete previous durable row. */
public record NativePolicyReadmission(SwitchBufferCapableQueue.SwitchBufferOp expectedOp,
    Map<String, String> expectedFullRow, JobQueue.EnqueueEntry entry, String collection) {
  public NativePolicyReadmission {
    // SELECT * includes SQL NULLs. Map.copyOf would reject legitimate job snapshots.
    expectedFullRow = expectedFullRow == null ? null
        : Collections.unmodifiableMap(new LinkedHashMap<>(expectedFullRow));
  }
}
