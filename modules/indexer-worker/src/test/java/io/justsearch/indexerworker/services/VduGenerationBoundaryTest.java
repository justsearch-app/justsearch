/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class VduGenerationBoundaryTest {
  @TempDir Path tempDir;
  private static final String DOC = "generation-document";

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void activeServingTargetCommitsBeforeAcknowledging(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.call(operation);
      var order = inOrder(fixture.index, fixture.commits);
      order.verify(fixture.index).updateDocument(eq(DOC), anyMap());
      order.verify(fixture.commits).commitAndTrack(any());
      order.verify(fixture.commits).maybeRefreshBlocking();
      assertEquals(0, fixture.queue.switchBufferDepth());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void distinctServingRuntimeRefusesBeforeAnyEffect(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.service = fixture.service(mock(RunningRuntime.class));
      fixture.assertRefused(operation);
      verifyNoInteractions(fixture.index, fixture.fields, fixture.commits);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void acceptedMigrationBeforeRestartRefusesEvenWithTheSameRuntime(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.generations.startMigration("before-restart");
      fixture.assertRefused(operation);
      verifyNoInteractions(fixture.index, fixture.fields, fixture.commits);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void transitionInsideCoveringCommitCannotBeAcknowledgedAsCompleted(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      doAnswer(call -> {
        fixture.generations.startMigration("commit-interleaving");
        return null;
      }).when(fixture.commits).commitAndTrack(any());
      fixture.assertRefused(operation);
      verify(fixture.index).updateDocument(eq(DOC), anyMap());
      verify(fixture.commits).commitAndTrack(any());
      verify(fixture.commits).maybeRefreshBlocking();
    }
  }

  private final class Fixture implements AutoCloseable {
    private final Path base = tempDir.resolve("index");
    private final IndexGenerationManager generations = new IndexGenerationManager(base);
    private final Path activePath;
    private final SqliteJobQueue queue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    private final RunningRuntime runtime = mock(RunningRuntime.class);
    private final DocumentFieldOps fields = mock(DocumentFieldOps.class);
    private final IndexingCoordinator index = mock(IndexingCoordinator.class);
    private final CommitOps commits = mock(CommitOps.class);
    private WorkerIngestService service;

    private Fixture() throws Exception {
      activePath = generations.initializeOrLoad().activeGenerationPath();
      queue.open();
      when(runtime.documentFieldOps()).thenReturn(fields);
      when(runtime.indexingCoordinator()).thenReturn(index);
      when(runtime.commitOps()).thenReturn(commits);
      when(index.updateDocument(eq(DOC), anyMap())).thenReturn(true);
      when(fields.queryDocIdsByFieldOrThrow(
          SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING, 1000))
          .thenReturn(List.of(DOC));
      service = service(runtime);
    }

    private WorkerIngestService service(RunningRuntime serving) {
      return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, activePath, runtime, serving, null, 0L);
    }

    private void call(String operation) {
      switch (operation) {
        case "update" -> assertTrue(service.updateVduResult(UpdateVduResultRequest.newBuilder()
            .setDocId(DOC).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY).build(),
            CallContext.none()).getSuccess());
        case "mark" -> assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
            .setDocId(DOC).setMaxRetries(3).build(), CallContext.none()).getSuccess());
        case "recover" -> assertEquals(1, service.recoverVduProcessing(
            RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none()).getRecoveredCount());
        default -> throw new IllegalArgumentException(operation);
      }
    }

    private void assertRefused(String operation) {
      WorkerServiceException refusal = assertThrows(WorkerServiceException.class, () -> call(operation));
      assertEquals(WorkerServiceException.Status.UNAVAILABLE, refusal.status());
      assertEquals(0, queue.switchBufferDepth());
    }

    @Override public void close() throws Exception { queue.close(); }
  }
}
