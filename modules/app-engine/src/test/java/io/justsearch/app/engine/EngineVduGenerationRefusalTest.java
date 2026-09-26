/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real generation guard, Java call boundary and VDU facade preserve retryable refusal. */
final class EngineVduGenerationRefusalTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unavailableTargetCannotBecomeSuccessOrAnEmptyRecovery(boolean ingestPresent, @TempDir Path directory)
      throws Exception {
    var generations = new IndexGenerationManager(directory.resolve("index"));
    var layout = generations.initializeOrLoad();
    var runtime = mock(RunningRuntime.class);
    var fields = mock(DocumentFieldOps.class);
    var indexing = mock(IndexingCoordinator.class);
    var commits = mock(CommitOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(commits);
    var services = mock(WorkerAppServices.class);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var registry = new DefaultEngineExecutorRegistry()) {
      queue.open();
      var worker = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          layout.basePath(), layout.activeGenerationPath(), ingestPresent ? runtime : null, runtime, null, 0L);
      when(services.ingestService()).thenReturn(worker);
      if (ingestPresent) generations.startMigration("port-refusal-test");
      try (var client = new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
        for (Runnable mutation : java.util.List.<Runnable>of(
            () -> client.updateVduResult("document", "", VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY,
                "", 0, TestEngineContexts.FOREGROUND),
            () -> client.markVduProcessing("document", 3, TestEngineContexts.FOREGROUND),
            () -> client.recoverVduProcessing(TestEngineContexts.FOREGROUND))) {
          var refusal = assertThrows(KnowledgeClientException.class, mutation::run);
          assertEquals(KnowledgeClientException.Status.UNAVAILABLE, refusal.status());
          var cause = assertInstanceOf(WorkerServiceException.class, refusal.getCause());
          assertEquals(WorkerServiceException.Status.UNAVAILABLE, cause.status());
          assertEquals(0, admission.activeWorkCount());
          assertEquals(0, queue.switchBufferDepth());
        }
      }
      verifyNoInteractions(fields, indexing, commits);
    }
  }
}
