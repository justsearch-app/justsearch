/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Populated legacy index and queue survive two deferrals and a restart without spending retries. */
final class LegacyVduSourceRefreshTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  @Test void hashlessSourceRefreshIsTargetedDurableAndEventuallyProvesItsRevision() throws Exception {
    var file=Files.writeString(directory.resolve("picture.png"),"revision-A");
    String id=file.toString();var db=directory.resolve("jobs.db");var index=directory.resolve("index");
    var fields=new ArrayList<>(FieldCatalogDef.forChunkTestingWithVduRetryCount(0).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256,"keyword",true,false,List.of(),null,null,false));
    var schema=IndexSchema.fromCatalog(new FieldCatalogDef("legacy-vdu-refresh",fields));
    try(var runtime=schema.atPath(index).withExecutorRegistrations(testLuceneExecutors()).open();var queue=openQueue(db)) {
      runtime.commitOps().stopCommitTimer();
      seed(runtime,id,"legacy-uid",null,"legacy text");
      seed(runtime,"unrelated","other-uid",null,"unrelated text");
      var service=service(runtime,queue);var ctx=context();
      for(int pass=0;pass<2;pass++) {
        var request=MarkVduProcessingRequest.newBuilder().setDocId(id).setMaxRetries(3);
        optional(request,"setAttemptId",UUID.randomUUID().toString());
        assertFalse(service.markVduProcessing(request.build(),ctx).getSuccess(),"hashless source must refresh before a model attempt");
        assertEquals("2",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_RETRY_COUNT));
        assertEquals("PENDING",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_STATUS));
        assertEquals(1,queue.queueDepth(),"only this document is queued, never a bulk reindex");
      }
    }
    try(var runtime=schema.atPath(index).withExecutorRegistrations(testLuceneExecutors()).open();var queue=openQueue(db)) {
      runtime.commitOps().stopCommitTimer();
      var pending=queue.pollPending(10);
      assertEquals(1,pending.size());assertEquals(file,pending.getFirst().path());
      assertEquals(SourceContentHash.sha256(file),pending.getFirst().plannedSourceSha256(),"source proof is durable across restart");
      // The source refresh's ordinary native publication records that witnessed revision.
      seed(runtime,id,"legacy-uid",SourceContentHash.sha256(file),"refreshed text");
      var service=service(runtime,queue);var ctx=context();var attempt=UUID.randomUUID().toString();
      var request=MarkVduProcessingRequest.newBuilder().setDocId(id).setMaxRetries(3);optional(request,"setAttemptId",attempt);
      assertTrue(service.markVduProcessing(request.build(),ctx).getSuccess());
      var result=UpdateVduResultRequest.newBuilder().setDocId(id).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT).setExtractedContent("completed refreshed text");optional(result,"setAttemptId",attempt);
      assertTrue(service.updateVduResult(result.build(),ctx).getSuccess());
      assertEquals("COMPLETED",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_STATUS));
      assertEquals("unrelated text",runtime.documentFieldOps().getDocumentContent("unrelated"));
    }
  }

  @Test void unavailableHashlessSourceDefersWithoutSpendingRetries() throws Exception {
    var index=directory.resolve("index");var schema=IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0));
    String id=directory.resolve("missing.png").toString();
    try(var runtime=schema.atPath(index).withExecutorRegistrations(testLuceneExecutors()).open();var queue=openQueue(directory.resolve("jobs.db"))) {
      runtime.commitOps().stopCommitTimer();seed(runtime,id,"legacy-uid",null,"legacy");var service=service(runtime,queue);
      for(int pass=0;pass<2;pass++) {
        var request=MarkVduProcessingRequest.newBuilder().setDocId(id);optional(request,"setAttemptId",UUID.randomUUID().toString());
        assertFalse(service.markVduProcessing(request.build(),context()).getSuccess());
        assertEquals("2",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_RETRY_COUNT));
        assertEquals("PENDING",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_STATUS));
        assertEquals(0,queue.queueDepth());
      }
    }
  }
  private static SqliteJobQueue openQueue(Path db) throws Exception { var queue=new SqliteJobQueue(db);queue.open();return queue; }
  private static WorkerIngestService service(RunningRuntime runtime,SqliteJobQueue queue) {
    return new WorkerIngestService(queue,null,null,IndexingPacing.unthrottled(),null,null,runtime,runtime,null,0L);
  }
  private static CallContext context() { var base=CallContext.none();return new CallContext(null,null,null,base.engineContext().withWorkId(UUID.randomUUID()),base.provenance(),base.childLifetime()); }
  private static void optional(Object builder,String name,String value) throws Exception {
    try { builder.getClass().getMethod(name,String.class).invoke(builder,value); }catch(NoSuchMethodException baseline) { /* the setter does not exist on the baseline build */ }
  }
  private static void seed(RunningRuntime runtime,String id,String uid,String source,String content) {
    Map<String,Object> fields=new HashMap<>();fields.put(SchemaFields.DOC_ID,id);fields.put(SchemaFields.DOC_UID,uid);fields.put(SchemaFields.CONTENT,content);
    fields.put(SchemaFields.VDU_STATUS,"PENDING");fields.put(SchemaFields.VDU_RETRY_COUNT,"2");if(source!=null) fields.put(SchemaFields.SOURCE_SHA256,source);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));runtime.commitOps().commitAndTrack(CommitReason.TIMER);runtime.commitOps().maybeRefreshBlocking();
  }
}
