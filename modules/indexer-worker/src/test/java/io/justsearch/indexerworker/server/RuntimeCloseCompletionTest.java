/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.IndexRootLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.search.SearcherManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeCloseCompletionTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void incompleteLuceneCloseRetainsServerStoresAndExclusionUntilRetry() throws Exception {
    var server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(temp.resolve("data")), null);
    var runtime = withTestExecutors(IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)).ephemeral()).open();
    runtime.commitOps().stopCommitTimer();
    var admission = mock(SearcherManager.class);
    doThrow(new IOException("admission owner retained")).doNothing().when(admission).close();
    var admissionField = CommitOps.class.getDeclaredField("admissionManager");
    admissionField.setAccessible(true);
    var previousAdmission = (SearcherManager) admissionField.get(runtime.commitOps());
    if (previousAdmission != null) previousAdmission.close();
    admissionField.set(runtime.commitOps(), admission);
    var queue = mock(JobQueue.class);
    var exclusion = mock(IndexRootLock.class);
    set(server, "searchLifecycle", runtime);
    set(server, "jobQueue", queue);
    set(server, "indexRootLock", exclusion);
    Path index = runtime.openedIndexPath();
    try {
      assertThrows(IOException.class, server::close);
      assertFalse(server.awaitClosed(0));
      assertTrue(Files.isDirectory(index));
      verify(queue, never()).close();
      verify(exclusion, never()).close();
      assertDoesNotThrow(server::close);
      assertTrue(server.awaitClosed(0));
      assertFalse(Files.exists(index));
      verify(admission, times(2)).close();
      verify(queue).close();
      verify(exclusion).close();
    } finally {
      server.close();
      runtime.close();
    }
  }

  private static void set(KnowledgeServer server, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name); field.setAccessible(true); field.set(server, value);
  }
}
