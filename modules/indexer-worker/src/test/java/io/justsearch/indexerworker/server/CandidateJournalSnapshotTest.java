/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexing.SchemaFields;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

final class CandidateJournalSnapshotTest {
  private static final String GENERATION_SOURCE = "candidate-journal-snapshot";
  private static final String H1 = "b".repeat(64);
  private static final String H2 = "c".repeat(64);
  private static final String UNIT_REVISION = "accepted-revision";

  @Test
  void refreshesBeforeReadingIndexedH2AndAcceptsTheExactH1Certificate(@TempDir Path tempDir)
      throws Exception {
    Fixture fixture = fixture(tempDir, true);
    try {
      clearInvocations(fixture.queue(), fixture.commits(), fixture.fields());
      var witness = invokeSnapshotWitness(fixture.server());

      assertTrue(witness.gaps().isEmpty());
      assertEquals(Set.of(DocumentIdentityStore.pathHash(fixture.path().toString())),
          witness.coveredUnitIds());
      assertEquals(H2, fixture.indexedHash().get(), "the mocked refresh must publish H2");

      InOrder order = inOrder(fixture.queue(), fixture.commits(), fixture.fields());
      order.verify(fixture.commits()).maybeRefreshBlocking();
      order.verify(fixture.queue()).listSwitchBufferOpsStrictForGeneration(fixture.generation());
      order.verify(fixture.fields()).getDocumentField(fixture.path().toString(),
          SchemaFields.SOURCE_SHA256);
      order.verify(fixture.queue()).matchesAcceptedFileProjection(
          fixture.path().toString(), UNIT_REVISION, H1, H2);
    } finally {
      fixture.server().close();
    }
  }

  @Test
  void missingExactCertificateReportsCandidateProjectionGapAndLeavesUnitUncovered(
      @TempDir Path tempDir) throws Exception {
    Fixture fixture = fixture(tempDir, false);
    try {
      clearInvocations(fixture.queue(), fixture.commits(), fixture.fields());
      var witness = invokeSnapshotWitness(fixture.server());

      assertEquals(1, witness.gaps().size());
      assertEquals("CANDIDATE_PROJECTION_MISSING", witness.gaps().getFirst().reason());
      assertTrue(witness.coveredUnitIds().isEmpty());
      assertEquals(H2, fixture.indexedHash().get(), "the negative case still reads after refresh");

      InOrder order = inOrder(fixture.queue(), fixture.commits(), fixture.fields());
      order.verify(fixture.commits()).maybeRefreshBlocking();
      order.verify(fixture.queue()).listSwitchBufferOpsStrictForGeneration(fixture.generation());
      order.verify(fixture.fields()).getDocumentField(fixture.path().toString(),
          SchemaFields.SOURCE_SHA256);
      order.verify(fixture.queue()).matchesAcceptedFileProjection(
          fixture.path().toString(), UNIT_REVISION, H1, H2);
    } finally {
      fixture.server().close();
    }
  }

  private static Fixture fixture(Path tempDir, boolean certificateMatches) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    var state = layout.genManager().startMigration(GENERATION_SOURCE, List.of());
    String generation = state.building_generation();
    Path candidatePath = layout.genManager().resolveGenerationPathStrict(generation);
    Path path = tempDir.resolve("candidate.txt").toAbsolutePath();

    KnowledgeServer server = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null);
    var queue = mock(SwitchBufferCapableQueue.class);
    var green = mock(RunningRuntime.class);
    var commits = mock(CommitOps.class);
    var fields = mock(DocumentFieldOps.class);
    AtomicReference<String> indexedHash = new AtomicReference<>(H1);
    var upsert = new SwitchBufferUpsert(
        path.toString(), "notes", null, UNIT_REVISION, H1);
    var operation = new SwitchBufferCapableQueue.SwitchBufferOp(
        generation, "path:" + path, "UPSERT", upsert.encode(), 1L, "journal-v1");

    when(queue.listSwitchBufferOpsStrictForGeneration(generation)).thenReturn(List.of(operation));
    when(queue.matchesAcceptedFileProjection(path.toString(), UNIT_REVISION, H1, H2))
        .thenReturn(certificateMatches);
    when(green.commitOps()).thenReturn(commits);
    doAnswer(invocation -> {
      indexedHash.set(H2);
      return null;
    }).when(commits).maybeRefreshBlocking();
    when(green.documentFieldOps()).thenReturn(fields);
    when(fields.getDocumentField(path.toString(), SchemaFields.SOURCE_SHA256))
        .thenAnswer(invocation -> indexedHash.get());

    setField(server, "indexGenerationManager", layout.genManager());
    setField(server, "buildingIndexPath", candidatePath);
    setField(server, "jobQueue", queue);
    setField(server, "ingestLifecycle", green);
    return new Fixture(server, queue, commits, fields, generation, path, indexedHash);
  }

  private static RecordedIngestionLifecycle.JournalWitness invokeSnapshotWitness(
      KnowledgeServer server) throws Exception {
    Method snapshot = KnowledgeServer.class.getDeclaredMethod("candidateJournalSnapshot");
    snapshot.setAccessible(true);
    Object value = snapshot.invoke(server);
    Method witness = value.getClass().getDeclaredMethod("witness");
    witness.setAccessible(true);
    return (RecordedIngestionLifecycle.JournalWitness) witness.invoke(value);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private record Fixture(
      KnowledgeServer server,
      SwitchBufferCapableQueue queue,
      CommitOps commits,
      DocumentFieldOps fields,
      String generation,
      Path path,
      AtomicReference<String> indexedHash) {}
}
