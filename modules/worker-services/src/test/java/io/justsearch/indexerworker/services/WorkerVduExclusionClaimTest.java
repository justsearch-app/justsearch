/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkerVduExclusionClaimTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @ParameterizedTest
  @EnumSource(value = VduUpdateOutcome.class, names = {
      "VDU_UPDATE_OUTCOME_SUCCESS_TEXT", "VDU_UPDATE_OUTCOME_SUCCESS_EMPTY", "VDU_UPDATE_OUTCOME_FAILED"})
  void excludedResultReleasesOnlyItsClaimWithoutSpendingRetries(VduUpdateOutcome outcome) throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(config("[]"));
    ConfigStore.setGlobal(installed);
    try (var runtime = open()) {
      String id = seed(runtime, true);
      var service = service(runtime, null);
      var ctx = context();
      assertTrue(service.markVduProcessing(mark(id, "attempt-a"), ctx).getSuccess());
      assertState(runtime, id, "PROCESSING", "3");
      installed.update(config("[\"private.png\"]"));

      assertFalse(service.updateVduResult(result(id, "attempt-a", outcome), context()).getSuccess());
      assertFalse(service.updateVduResult(result(id, "old-attempt", outcome), ctx).getSuccess());
      assertState(runtime, id, "PROCESSING", "3");
      var refused = service.updateVduResult(result(id, "attempt-a", outcome), ctx);
      assertFalse(refused.getSuccess());
      assertTrue(refused.getError().contains("excluded"), refused.getError());
      assertState(runtime, id, "PENDING", "2");
      assertEquals("original text", runtime.documentFieldOps().getDocumentContent(id));
      try (var fs = FSDirectory.open(temp.resolve("index")); var reader = DirectoryReader.open(fs)) {
        var hit = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1);
        var stored = reader.storedFields().document(hit.scoreDocs[0].doc);
        assertEquals("PENDING", stored.get(SchemaFields.VDU_STATUS), "release is committed");
        assertEquals("2", stored.get(SchemaFields.VDU_RETRY_COUNT), "retry refund survives reopen");
      }
      installed.update(config("[]"));
      assertFalse(service.updateVduResult(result(id, "attempt-a", outcome), ctx).getSuccess(),
          "a released attempt loses publication authority even after policy removal");
      assertTrue(service.markVduProcessing(mark(id, "attempt-b"), ctx).getSuccess());
      assertState(runtime, id, "PROCESSING", "3");
      assertTrue(service.updateVduResult(result(id, "attempt-b",
          VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT), ctx).getSuccess());
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void excludedSourceCannotBeClaimedOrSpendRetries() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(config("[\"private.png\"]"));
    ConfigStore.setGlobal(installed);
    try (var runtime = open()) {
      String id = seed(runtime, true);
      var refused = service(runtime, null).markVduProcessing(mark(id, "attempt-a"), context());
      assertFalse(refused.getSuccess());
      assertTrue(refused.getError().contains("excluded"), refused.getError());
      assertState(runtime, id, "PENDING", "2");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void excludedLegacySourceDoesNotQueueRefreshFromOwnerlessResult() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(config("[\"private.png\"]"));
    ConfigStore.setGlobal(installed);
    try (var runtime = open()) {
      String id = seed(runtime, false);
      var queue = mock(JobQueue.class);
      assertFalse(service(runtime, queue).updateVduResult(result(id, "",
          VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT), CallContext.none()).getSuccess());
      verifyNoInteractions(queue);
      assertState(runtime, id, "PENDING", "2");
      assertEquals("original text", runtime.documentFieldOps().getDocumentContent(id));
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(VduRevisionTestSupport.catalog(4)).atPath(temp.resolve("index"))
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private String seed(RunningRuntime runtime, boolean sourceProof) throws Exception {
    String id = Files.writeString(temp.resolve("private.png"), "source bytes").toString();
    var fields = new java.util.HashMap<String, Object>(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid", SchemaFields.CONTENT, "original text",
        SchemaFields.VDU_STATUS, "PENDING", SchemaFields.VDU_RETRY_COUNT, "2"));
    if (sourceProof) fields.put(SchemaFields.SOURCE_SHA256, "a".repeat(64));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack(CommitReason.TIMER);
    runtime.commitOps().maybeRefreshBlocking();
    return id;
  }

  private WorkerIngestService service(RunningRuntime runtime, JobQueue queue) {
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
    service.useExclusionRoots(() -> List.of(temp));
    return service;
  }

  private static void assertState(RunningRuntime runtime, String id, String status, String retries) throws Exception {
    assertEquals(status, runtime.documentFieldOps().getDocumentField(id, SchemaFields.VDU_STATUS));
    assertEquals(retries, runtime.documentFieldOps().getDocumentField(id, SchemaFields.VDU_RETRY_COUNT));
  }

  private static ResolvedConfig config(String patterns) {
    return ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns", patterns).build();
  }

  private static CallContext context() {
    var base = CallContext.none();
    return new CallContext(null, null, null, base.engineContext().withWorkId(UUID.randomUUID()),
        base.provenance(), base.childLifetime());
  }

  private static MarkVduProcessingRequest mark(String id, String attempt) {
    return MarkVduProcessingRequest.newBuilder().setDocId(id).setAttemptId(attempt).setMaxRetries(7).build();
  }

  private static UpdateVduResultRequest result(String id, String attempt, VduUpdateOutcome outcome) {
    return UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(id).setAttemptId(attempt).setOutcome(outcome)
        .setExtractedContent("result that must not publish while excluded").build();
  }
}
