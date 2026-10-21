/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.Path;
import java.util.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class VduRevisionRaceTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final String DOC="parent";
  private static final String A="c6d49ee8-2352-4260-afbf-3350d8249aff";
  private static final String B="122cb387-658a-419e-ab0a-e0073f8a6111";

  @ParameterizedTest(name="oldSourceResultNeverPublishesOntoReplacement({0})") @EnumSource(value=VduUpdateOutcome.class,names={
      "VDU_UPDATE_OUTCOME_SUCCESS_TEXT","VDU_UPDATE_OUTCOME_SUCCESS_EMPTY",
      "VDU_UPDATE_OUTCOME_FAILED","VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT"})
  void oldSourceResultNeverPublishesOntoReplacement(VduUpdateOutcome outcome) throws Exception {
    try (var runtime=open()) {
      seed(runtime,"uid","a","same extracted text",false); var service=service(runtime); var ctx=context();
      assertTrue(service.markVduProcessing(mark(A),ctx).getSuccess());
      seed(runtime,"uid","b","same extracted text",false);
      var rejected=service.updateVduResult(result(A,outcome),ctx);
      assertFalse(rejected.getSuccess(),"different source bytes invalidate every outcome even when extracted text is identical");
      assertCurrent(runtime,"same extracted text","PENDING","0");
      assertTrue(service.markVduProcessing(mark(B),ctx).getSuccess(),"C1 must be claimable");
      assertTrue(service.updateVduResult(result(B,VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT),ctx).getSuccess());
      assertCurrent(runtime,"old-result-marker","COMPLETED","1");
    }
  }

  @Test void nativeBatchWriterReplacementInvalidatesPendingVdu() throws Exception {
    try(var runtime=open()) {
      var file=java.nio.file.Files.writeString(directory.resolve("source.png"),"source-A");
      io.justsearch.indexerworker.loop.VduNativePublicationFixture.publish(runtime,file,"same extracted text","native-uid");
      String id=io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file);
      var service=service(runtime);var ctx=context();
      var mark=MarkVduProcessingRequest.newBuilder().setDocId(id).setMaxRetries(3);optional(mark,"setAttemptId",A);
      var claim=service.markVduProcessing(mark.build(),ctx);assertTrue(claim.getSuccess(),claim.getError());
      java.nio.file.Files.writeString(file,"source-B");
      io.justsearch.indexerworker.loop.VduNativePublicationFixture.publish(runtime,file,"same extracted text","native-uid");
      var result=UpdateVduResultRequest.newBuilder().setDocId(id).setExtractedContent("stale-native-result")
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);optional(result,"setAttemptId",A);
      assertFalse(service.updateVduResult(result.build(),ctx).getSuccess(),"real native replacement must revoke C0 authority");
      assertEquals("same extracted text",runtime.documentFieldOps().getDocumentContent(id));
      assertEquals("PENDING",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_STATUS));
      try(var fs=FSDirectory.open(directory);var reader=DirectoryReader.open(fs)) {
        var chunks=new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID,id)),100);
        for(var chunk:chunks.scoreDocs) assertEquals(
            io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("same extracted text"),
            reader.storedFields().document(chunk.doc).get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256),
            "a rejected native result cannot replace C1's committed chunk proof");
      }
    }
  }

  @Test void linuxDistinctCaseAndAtomicReplacementKeepSeparateRevisionAuthority() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
    try(var runtime=open()) {
      var lower=java.nio.file.Files.writeString(directory.resolve("case.png"),"source-A");
      var upper=java.nio.file.Files.writeString(directory.resolve("CASE.png"),"source-sibling");
      assertFalse(java.nio.file.Files.isSameFile(lower,upper),"fixture requires real case-distinct files");
      var service=service(runtime);var ctx=context();
      for(var file:List.of(lower,upper)) {
        io.justsearch.indexerworker.loop.VduNativePublicationFixture.publish(runtime,file,"same extracted text",file.getFileName().toString());
        String id=io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file);
        var claim=MarkVduProcessingRequest.newBuilder().setDocId(id).setMaxRetries(3);optional(claim,"setAttemptId",file.equals(lower)?A:B);
        assertTrue(service.markVduProcessing(claim.build(),ctx).getSuccess());
      }
      var replacement=java.nio.file.Files.writeString(directory.resolve("replacement.png"),"source-B");
      java.nio.file.Files.move(replacement,lower,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      io.justsearch.indexerworker.loop.VduNativePublicationFixture.publish(runtime,lower,"new native text","case.png");
      String lowerId=io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(lower);
      String upperId=io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(upper);
      var stale=UpdateVduResultRequest.newBuilder().setDocId(lowerId).setExtractedContent("stale-lower")
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);optional(stale,"setAttemptId",A);
      assertFalse(service.updateVduResult(stale.build(),ctx).getSuccess(),"atomic replacement revokes just the replaced file's claim");
      var sibling=UpdateVduResultRequest.newBuilder().setDocId(upperId).setExtractedContent("current-sibling")
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);optional(sibling,"setAttemptId",B);
      assertTrue(service.updateVduResult(sibling.build(),ctx).getSuccess(),"case-distinct sibling keeps its claim");
      assertEquals("new native text",runtime.documentFieldOps().getDocumentContent(lowerId));
      assertEquals("current-sibling",runtime.documentFieldOps().getDocumentContent(upperId));
      try(var fs=FSDirectory.open(directory);var reader=DirectoryReader.open(fs)) {
        var searcher=new IndexSearcher(reader);
        for(var expected:Map.of(lowerId,"new native text",upperId,"current-sibling").entrySet()) {
          var hits=searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID,expected.getKey())),1);
          assertEquals(1,hits.scoreDocs.length);
          assertEquals(expected.getValue(),reader.storedFields().document(hits.scoreDocs[0].doc).get(SchemaFields.CONTENT));
        }
      }
    }
  }

  @Test void recreatedUidInvalidatesAnOtherwiseIdenticalSource() throws Exception {
    try (var runtime=open()) {
      seed(runtime,"old-uid","a","baseline",false);var service=service(runtime);var ctx=context();
      assertTrue(service.markVduProcessing(mark(A),ctx).getSuccess());
      runtime.indexingCoordinator().deleteByIdAndChunks(DOC);
      seed(runtime,"new-uid","a","baseline",false);
      assertFalse(service.updateVduResult(result(A,VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY),ctx).getSuccess());
      assertCurrent(runtime,"baseline","PENDING","0");
    }
  }

  @Test void ownerlessRequestCannotPublish() throws Exception {
    try (var runtime=open()) {
      seed(runtime,"uid","a","baseline",false);var service=service(runtime);
      assertTrue(service.markVduProcessing(mark(A),context()).getSuccess());
      assertFalse(service.updateVduResult(result(A,VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT),CallContext.none()).getSuccess());
      assertCurrent(runtime,"baseline","PROCESSING","1");
    }
  }

  @ParameterizedTest(name="oldAttemptCannotAlterSameOwnersReclaimedDocument({0})") @ValueSource(strings={"result","release","cancel"})
  void oldAttemptCannotAlterSameOwnersReclaimedDocument(String operation) throws Exception {
    try (var runtime=open()) {
      seed(runtime,"uid","a","baseline",false);var service=service(runtime);var ctx=context();
      assertTrue(service.markVduProcessing(mark(A),ctx).getSuccess());
      assertTrue(release(service,A,ctx));
      assertTrue(service.markVduProcessing(mark(B),ctx).getSuccess());
      switch(operation) {
        case "result" -> assertFalse(service.updateVduResult(result(A,VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT),ctx).getSuccess());
        case "release" -> assertFalse(release(service,A,ctx),"old release must not return successor's retry");
        default -> {
          var builder=RecoverVduProcessingRequest.newBuilder().setProcedureOwner(ctx.engineContext().workId().orElseThrow().toString());
          optional(builder,"addAttemptIds",A);
          assertEquals(0,service.recoverVduProcessing(builder.build(),ctx).getRecoveredCount(),"old cancellation cannot reset successor");
        }
      }
      assertCurrent(runtime,"baseline","PROCESSING","1");
      assertTrue(service.updateVduResult(result(B,VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT),ctx).getSuccess());
    }
  }

  @Test void acceptedReplacementDropsEarlierVectorsAndEntitiesBeforeBackfill() throws Exception {
    try (var runtime=open()) {
      seed(runtime,"uid","a","baseline",true);var service=service(runtime);var ctx=context();
      assertTrue(service.markVduProcessing(mark(A),ctx).getSuccess());
      assertTrue(service.updateVduResult(result(A,VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT),ctx).getSuccess());
      assertEquals(List.of(),runtime.documentFieldOps().getDocumentFieldValues(DOC,SchemaFields.ENTITY_PERSONS_RAW),
          "VDU C1 must not serve C0 entities while backfill is pending");
      assertEquals("PENDING",runtime.documentFieldOps().getDocumentField(DOC,SchemaFields.NER_STATUS));
      try (var fs=FSDirectory.open(directory);var reader=DirectoryReader.open(fs)) {
        var searcher=new IndexSearcher(reader);
        assertEquals(0,searcher.count(new TermQuery(new Term(SchemaFields.ENTITY_PERSONS_RAW,"old-person"))));
        for (var leaf:reader.leaves()) {
          var vectors=leaf.reader().getFloatVectorValues(SchemaFields.VECTOR);
          if (vectors!=null) {
            var it=vectors.iterator();
            for (int doc=it.nextDoc();doc!=Integer.MAX_VALUE;doc=it.nextDoc()) {
              if (leaf.reader().getLiveDocs()==null || leaf.reader().getLiveDocs().get(doc)) fail("live old vector survived VDU replacement");
            }
          }
        }
      }
    }
  }

  private RunningRuntime open() {
    var runtime=IndexSchema.fromCatalog(VduRevisionTestSupport.catalog(4)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open();runtime.commitOps().stopCommitTimer();return runtime;
  }
  private void seed(RunningRuntime runtime,String uid,String source,String content,boolean artifacts) {
    Map<String,Object> fields=new HashMap<>();
    fields.put(SchemaFields.DOC_ID,DOC);fields.put(SchemaFields.DOC_UID,uid);fields.put(SchemaFields.SOURCE_SHA256,source.repeat(64));
    fields.put(SchemaFields.CONTENT,content);fields.put(SchemaFields.VDU_STATUS,"PENDING");fields.put(SchemaFields.VDU_RETRY_COUNT,"0");
    if (artifacts) { fields.put(SchemaFields.VECTOR,new float[]{1,0,0,0});fields.put(SchemaFields.EMBEDDING_STATUS,"COMPLETED");
      fields.put(SchemaFields.NER_STATUS,"COMPLETED");fields.put(SchemaFields.ENTITY_PERSONS_RAW,List.of("old-person")); }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack(CommitReason.TIMER);runtime.commitOps().maybeRefreshBlocking();
  }
  private static WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(null,null,null,IndexingPacing.unthrottled(),null,null,runtime,runtime,null,0L);
  }
  private static CallContext context() {
    var base=CallContext.none();return new CallContext(null,null,null,base.engineContext().withWorkId(UUID.randomUUID()),base.provenance(),base.childLifetime());
  }
  private static MarkVduProcessingRequest mark(String attempt) throws Exception {
    var builder=MarkVduProcessingRequest.newBuilder().setDocId(DOC).setMaxRetries(3);optional(builder,"setAttemptId",attempt);return builder.build();
  }
  private static UpdateVduResultRequest result(String attempt,VduUpdateOutcome outcome) throws Exception {
    var builder=UpdateVduResultRequest.newBuilder().setDocId(DOC).setOutcome(outcome).setExtractedContent("old-result-marker").setPageCount(7);
    optional(builder,"setAttemptId",attempt);return builder.build();
  }
  private static void optional(Object builder,String method,String value) throws Exception {
    try { builder.getClass().getMethod(method,String.class).invoke(builder,value); }
    catch (NoSuchMethodException baseline) { /* The baseline has no attempt transport. */ }
  }
  private static boolean release(WorkerIngestService service,String attempt,CallContext ctx) throws Exception {
    try { return (boolean)service.getClass().getMethod("releaseVduProcessing",String.class,String.class,CallContext.class).invoke(service,DOC,attempt,ctx); }
    catch (NoSuchMethodException baseline) { return service.releaseVduProcessing(DOC,ctx); }
  }
  private void assertCurrent(RunningRuntime runtime,String content,String status,String retries) throws Exception {
    assertEquals(content,runtime.documentFieldOps().getDocumentContent(DOC));
    assertEquals(status,runtime.documentFieldOps().getDocumentField(DOC,SchemaFields.VDU_STATUS));
    try(var fs=FSDirectory.open(directory);var reader=DirectoryReader.open(fs)) {
      var searcher=new IndexSearcher(reader);
      if (!"COMPLETED".equals(status)) {
        assertEquals(0,searcher.count(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID,DOC))),
            "a rejected result cannot leave orphan chunks in the committed index");
      }
      var hits=searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID,DOC)),1);
      var stored=reader.storedFields().document(hits.scoreDocs[0].doc);
      assertEquals(content,stored.get(SchemaFields.CONTENT));assertEquals(status,stored.get(SchemaFields.VDU_STATUS));
      assertEquals(retries,stored.get(SchemaFields.VDU_RETRY_COUNT));
    }
  }
}
