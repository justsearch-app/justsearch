/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import java.io.IOException;
import java.util.Optional;

/** Test-only physical attachment for mocked EngineRoot server factories. */
final class EngineRootRecordedLifecycleTestSupport {
  private EngineRootRecordedLifecycleTestSupport() {}

  static RecordedIngestionLifecycle.Attachment bindOffline(
      KnowledgeServer server, RecordedIngestionLifecycle lifecycle) {
    try {
      var attachment = lifecycle.attach(org.mockito.Mockito.mock(JobQueue.class), Optional::empty, () -> false);
      org.mockito.Mockito.doAnswer(invocation -> { attachment.close(); return null; }).when(server).close();
      return attachment;
    } catch (IOException failure) {
      throw new AssertionError("offline recorded lifecycle attachment failed", failure);
    }
  }
}
