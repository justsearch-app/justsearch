/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.core.context.EngineContext;

/** Application-facing finite ingestion owner; queue and physical producer ownership stay in Engine. */
public interface RecordedIngestionService {
  /** Called only from a registered prepared parent's winning runner body with its admitted context. */
  OperationExecution execute(OperationRecordHandle parent, EngineContext context);

  /** Existing operations maintenance cadence retries deferred admission and flushes committed progress. */
  void maintain();

  /** Isolated compositions cannot execute recorded ingestion without its process owner. */
  static RecordedIngestionService unavailable() {
    return new RecordedIngestionService() {
      @Override public OperationExecution execute(OperationRecordHandle parent, EngineContext context) {
        throw new IllegalStateException("Recorded ingestion owner is unavailable");
      }
      @Override public void maintain() {}
    };
  }
}
