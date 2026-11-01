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
  // Recovery resets through the conditional mutation: still PROCESSING, then PENDING.
  private static final java.util.Map<String, Object> STILL_PROCESSING =
      java.util.Map.of(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
  private static final java.util.Map<String, Object> RESET_TO_PENDING =
      java.util.Map.of(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING);

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void activeServingTargetCommitsBeforeAcknowledging(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.prepare(operation);
      fixture.call(operation);
      var order = inOrder(fixture.index, fixture.commits);
      fixture.verifyMutation(order.verify(fixture.index), operation);
      order.verify(fixture.commits).commitAndTrack(any());
      order.verify(fixture.commits).maybeRefreshBlocking();
      assertEquals(0, fixture.queue.switchBufferDepth());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void distinctServingRuntimeRefusesBeforeAnyEffect(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.prepare(operation);
      fixture.service = fixture.service(mock(RunningRuntime.class));
      fixture.assertRefused(operation);
      verifyNoInteractions(fixture.index, fixture.fields, fixture.commits);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void acceptedMigrationBeforeRestartRefusesEvenWithTheSameRuntime(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.prepare(operation);
      fixture.generations.startMigration("before-restart");
      fixture.assertRefused(operation);
      verifyNoInteractions(fixture.index, fixture.fields, fixture.commits);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "mark", "recover"})
  void transitionInsideCoveringCommitCannotBeAcknowledgedAsCompleted(String operation) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.prepare(operation);
      doAnswer(call -> {
        fixture.generations.startMigration("commit-interleaving");
        return null;
      }).when(fixture.commits).commitAndTrack(any());
      fixture.assertRefused(operation);
      fixture.verifyMutation(verify(fixture.index), operation);
      verify(fixture.commits).commitAndTrack(any());
      verify(fixture.commits,times(operation.equals("recover") ? 1 : 2)).maybeRefreshBlocking();
    }
  }

  private static CallContext context() {
    var base=CallContext.none();return new CallContext(null,null,null,base.engineContext().withWorkId(java.util.UUID.randomUUID()),base.provenance(),base.childLifetime());
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
    private final String attempt=java.util.UUID.randomUUID().toString();
    private final CallContext ctx=context();
    private final java.util.Map<String,String> state=new java.util.HashMap<>(java.util.Map.of(
        SchemaFields.DOC_UID,"uid",SchemaFields.SOURCE_SHA256,"a".repeat(64),SchemaFields.CONTENT,"baseline",
        SchemaFields.VDU_STATUS,"PENDING",SchemaFields.VDU_RETRY_COUNT,"0"));
    private WorkerIngestService service;

    private Fixture() throws Exception {
      activePath = generations.initializeOrLoad().activeGenerationPath();
      queue.open();
      when(runtime.documentFieldOps()).thenReturn(fields);
      when(runtime.indexingCoordinator()).thenReturn(index);
      when(runtime.commitOps()).thenReturn(commits);
      when(fields.getEnrichmentInputs(List.of(DOC))).thenAnswer(call -> java.util.Map.of(DOC,
          new DocumentFieldOps.EnrichmentInput("baseline",java.util.Map.copyOf(state),null,java.util.Map.of())));
      when(fields.getDocumentFieldOrThrow(DOC,SchemaFields.VDU_RETRY_COUNT)).thenAnswer(call->state.get(SchemaFields.VDU_RETRY_COUNT));
      when(index.updateDocument(eq(DOC), anyMap())).thenAnswer(call->{
        java.util.Map<String,Object> updates=call.getArgument(1);updates.forEach((key,value)->state.put(key,value.toString()));return true;
      });
      when(index.updateDocumentIf(eq(DOC),anyMap(),anyMap())).thenReturn(true);
      when(index.updateDocumentIf(eq(DOC), eq(STILL_PROCESSING), eq(RESET_TO_PENDING)))
          .thenReturn(true);
      when(fields.queryDocIdsByFieldOrThrow(
          SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING, 1000))
          .thenReturn(List.of(DOC));
      service = service(runtime);
    }

    private WorkerIngestService service(RunningRuntime serving) {
      return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, activePath, runtime, serving, null, 0L);
    }

    private void prepare(String operation) {
      if(operation.equals("update")) assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setAttemptId(attempt).build(),ctx).getSuccess());
      clearInvocations(index,fields,commits);
    }

    private void call(String operation) {
      switch (operation) {
        case "update" -> assertTrue(service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64))
            .setDocId(DOC).setAttemptId(attempt).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY).build(),
            ctx).getSuccess());
        case "mark" -> assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
            .setDocId(DOC).setAttemptId(attempt).setMaxRetries(3).build(), ctx).getSuccess());
        case "recover" -> assertEquals(1, service.recoverVduProcessing(
            RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none()).getRecoveredCount());
        default -> throw new IllegalArgumentException(operation);
      }
    }

    /** Update and mark mutate unconditionally; recovery uses the conditional reset. */
    private void verifyMutation(IndexingCoordinator verifier, String operation) {
      if ("recover".equals(operation)) {
        verifier.updateDocumentIf(eq(DOC), eq(STILL_PROCESSING), eq(RESET_TO_PENDING));
      } else if(operation.equals("update")) {
        verifier.updateDocumentIf(eq(DOC),anyMap(),anyMap());
      } else {
        verifier.updateDocument(eq(DOC), anyMap());
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
