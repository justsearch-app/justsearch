/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.util.List;
import java.util.Map;

/** Ephemeral expectations for one file under a freshly held root; never a durable certificate. */
public record WatcherReconciliationFile(String candidate,
    List<SwitchBufferCapableQueue.SwitchBufferOp> journal,
    SwitchBufferCapableQueue.SwitchBufferOp rootReceipt, String path, String sourceSha256,
    long size, boolean orderedApply, boolean targetMatches, Map<String, String> expectedRow, boolean policyOmitted) {
  public WatcherReconciliationFile(String candidate,
      List<SwitchBufferCapableQueue.SwitchBufferOp> journal,
      SwitchBufferCapableQueue.SwitchBufferOp rootReceipt, String path, String sourceSha256,
      long size, boolean orderedApply, boolean targetMatches, Map<String, String> expectedRow) {
    this(candidate, journal, rootReceipt, path, sourceSha256, size, orderedApply, targetMatches,
        expectedRow, false);
  }
  public WatcherReconciliationFile {
    journal = List.copyOf(journal);
    expectedRow = expectedRow == null ? null
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(expectedRow));
  }
}
