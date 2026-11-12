/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.core.context.EngineContext;

/** Application-facing finite ingestion owner; queue and physical producer ownership stay in Engine. */
public interface RecordedIngestionService {
  /** Permanent composition fact; a real owner remains present while its index runtime is offline. */
  default boolean hasOwner() { return true; }

  /** Called only from a registered prepared parent's winning runner body with its admitted context. */
  OperationExecution execute(OperationRecordHandle parent, EngineContext context);

  record InterruptedImport(String operationKey, long interruptionRevision, String reason,
      java.util.List<RecordedRootPlan.Root> folders, boolean resumeAvailable) {
    public InterruptedImport { folders = java.util.List.copyOf(folders); }
  }

  default java.util.List<InterruptedImport> interruptedImports() { return java.util.List.of(); }

  default RecordedImportResume prepareImportResume(String operationKey, long interruptionRevision) {
    throw new IllegalArgumentException("Import resume is unavailable");
  }

  default OperationExecution resumeImport(RecordedImportResume plan, OperationRecordHandle accepted,
      EngineContext context) {
    throw new IllegalArgumentException("Import resume is unavailable");
  }

  default io.justsearch.agent.api.registry.OperationResult cancelImport(String operationKey, EngineContext context) {
    throw new UnsupportedOperationException("Import cancellation is unavailable");
  }

  /** User decision on the exact current nonterminal reindex gap list. */
  default io.justsearch.agent.api.registry.OperationResult acceptGaps(
      String reindexKey, String gapsListHash, EngineContext context) {
    throw new UnsupportedOperationException("Recorded gap acceptance is unavailable");
  }

  /** Request cancellation of an open recorded rebuild by key; success requires a durable refusal. */
  default io.justsearch.agent.api.registry.OperationResult cancelReindex(
      String reindexKey, EngineContext context) {
    throw new UnsupportedOperationException("Recorded rebuild cancellation is unavailable");
  }

  /**
   * Whether an accepted installed-model activation is still open (accepted, running, or waiting to
   * continue after a restart). "Remove files" refuses while one is (design D4): the activation was
   * prepared from the files that are on disk now.
   */
  default boolean installerActivationOpen() { return false; }

  /** Existing operations maintenance cadence retries deferred admission and flushes committed progress. */
  void maintain();

  /** Isolated compositions cannot execute recorded ingestion without its process owner. */
  static RecordedIngestionService unavailable() {
    return new RecordedIngestionService() {
      @Override public boolean hasOwner() { return false; }
      @Override public OperationExecution execute(OperationRecordHandle parent, EngineContext context) {
        throw new IllegalStateException("Recorded ingestion owner is unavailable");
      }
      @Override public void maintain() {}
    };
  }
}
