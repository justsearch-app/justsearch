/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VduCancellationRetryTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final String DOC = "cancelled-picture";

  @Test void shutdownReturnsTheUnusedRetryAndAllowsTheNextPass() throws Exception {
    assertCancelledRetry(0);
  }

  @Test void shutdownReturnsTheFinalRetryAndAllowsTheNextPass() throws Exception {
    assertCancelledRetry(2);
  }

  private void assertCancelledRetry(int previous) throws Exception {
    try (var runtime = open(previous)) {
      var service = service(runtime);
      var ctx = context();
      assertEquals(previous + 1, service.markVduProcessing(claim(), ctx).getRetryCount());
      assertEquals(1, service.recoverVduProcessing(owned(ctx), ctx).getRecoveredCount());
      assertEquals(SchemaFields.VDU_STATUS_PENDING, committed(SchemaFields.VDU_STATUS));
      assertEquals(String.valueOf(previous), committed(SchemaFields.VDU_RETRY_COUNT),
          "shutdown abandons the attempt without spending a document retry");
      assertEquals(0, service.recoverVduProcessing(owned(ctx), ctx).getRecoveredCount());
      assertEquals(String.valueOf(previous), committed(SchemaFields.VDU_RETRY_COUNT));
      var next = service.markVduProcessing(claim(), context());
      assertTrue(next.getSuccess(), next.getError());
      assertEquals(previous + 1, next.getRetryCount());
    }
  }

  @Test void completedAttemptKeepsItsRetryWhenShutdownResetLoses() throws Exception {
    try (var runtime = open(1)) {
      var service = service(runtime);
      var ctx = context();
      assertTrue(service.markVduProcessing(claim(), ctx).getSuccess());
      assertTrue(service.updateVduResult(UpdateVduResultRequest.newBuilder().setDocId(DOC)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent("completed picture text").build(), ctx).getSuccess());
      assertEquals(0, service.recoverVduProcessing(owned(ctx), ctx).getRecoveredCount());
      assertEquals(SchemaFields.VDU_STATUS_COMPLETED, committed(SchemaFields.VDU_STATUS));
      assertEquals("2", committed(SchemaFields.VDU_RETRY_COUNT));
      assertEquals("completed picture text", committed(SchemaFields.CONTENT));
    }
  }

  @Test void startupRecoveryKeepsTheClaimedRetryWhenItsOwnerWasLost() throws Exception {
    try (var runtime = open(1)) {
      var service = service(runtime);
      assertTrue(service.markVduProcessing(claim(), context()).getSuccess());
      assertEquals(1, service.recoverVduProcessing(RecoverVduProcessingRequest.getDefaultInstance(),
          CallContext.none()).getRecoveredCount());
      assertEquals(SchemaFields.VDU_STATUS_PENDING, committed(SchemaFields.VDU_STATUS));
      assertEquals("2", committed(SchemaFields.VDU_RETRY_COUNT));
    }
  }

  private RunningRuntime open(int retries) {
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, DOC, SchemaFields.DOC_UID, "cancelled-picture-identity",
        SchemaFields.CONTENT, "original text", SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING,
        SchemaFields.VDU_RETRY_COUNT, String.valueOf(retries))));
    runtime.commitOps().commitAndTrack(CommitReason.TIMER);
    runtime.commitOps().maybeRefreshBlocking();
    return runtime;
  }

  private WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
  }

  private static MarkVduProcessingRequest claim() {
    return MarkVduProcessingRequest.newBuilder().setDocId(DOC).setMaxRetries(3).build();
  }

  private static CallContext context() {
    var base = CallContext.none();
    return new CallContext(null, null, null, base.engineContext().withWorkId(java.util.UUID.randomUUID()),
        base.provenance(), base.childLifetime());
  }

  private static RecoverVduProcessingRequest owned(CallContext ctx) {
    return RecoverVduProcessingRequest.newBuilder()
        .setProcedureOwner(ctx.engineContext().workId().orElseThrow().toString()).build();
  }

  private String committed(String field) throws Exception {
    try (var fs = FSDirectory.open(directory); var reader = DirectoryReader.open(fs)) {
      var hits = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, DOC)), 1);
      assertEquals(1, hits.scoreDocs.length);
      return reader.storedFields().document(hits.scoreDocs[0].doc).get(field);
    }
  }
}
