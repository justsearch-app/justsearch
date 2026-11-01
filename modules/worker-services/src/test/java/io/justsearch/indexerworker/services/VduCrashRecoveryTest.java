/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual process death, with an unrelated commit covering a partial VDU replacement. */
final class VduCrashRecoveryTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final String DOC = "parent";
  private static final String CONTENT = "Recovered visual content has several chunks. ".repeat(400);

  @ParameterizedTest(name="processDeathPreservesRecoverableParentAndSibling({0})")
  @ValueSource(booleans={false,true})
  void processDeathPreservesRecoverableParentAndSibling(boolean afterParentCommit) throws Exception {
    Path log = directory.resolve("child.log");
    Path args = directory.resolve("classpath.args");
    Files.writeString(args, "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    var child = new ProcessBuilder(java, "@" + args, Probe.class.getName(),
        directory.resolve("index").toString(), Boolean.toString(afterParentCommit))
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(child.waitFor(45,TimeUnit.SECONDS), "owned crash probe must exit");
      assertEquals(71, child.exitValue(), () -> read(log));
    } finally {
      if(child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10,TimeUnit.SECONDS), "owned crash probe must stop");
      }
    }
    try(var runtime=open(directory.resolve("index"))) {
      var fields=runtime.documentFieldOps();
      assertEquals("sibling survives",fields.getDocumentContent("sibling"));
      assertEquals(afterParentCommit ? CONTENT : "baseline", fields.getDocumentContent(DOC));
      assertEquals(afterParentCommit ? "COMPLETED" : "PROCESSING",fields.getDocumentField(DOC,SchemaFields.VDU_STATUS));
      var service=service(runtime);
      assertFalse(service.updateVduResult(result("old"),CallContext.none()).getSuccess(),
          "an old result has no authority after its owning process dies");
      assertEquals(afterParentCommit ? 0 : 1,service.recoverVduProcessing(
          RecoverVduProcessingRequest.getDefaultInstance(),CallContext.none()).getRecoveredCount());
      assertEquals("1",fields.getDocumentField(DOC,SchemaFields.VDU_RETRY_COUNT));
      if(!afterParentCommit) {
        for(String id:fields.queryDocIdsByFieldOrThrow(SchemaFields.PARENT_DOC_ID,DOC,100)) {
          assertNull(fields.getDocumentContent(id),"partial chunks cannot be read against the previous parent revision");
        }
        var context=context();
        assertTrue(service.markVduProcessing(mark("new"),context).getSuccess());
        assertTrue(service.updateVduResult(result("new"),context).getSuccess());
      }
      assertEquals(CONTENT,fields.getDocumentContent(DOC));
      assertEquals("COMPLETED",fields.getDocumentField(DOC,SchemaFields.VDU_STATUS));
      assertTrue(fields.queryDocIdsByFieldOrThrow(SchemaFields.PARENT_DOC_ID,DOC,100).size()>1);
    }
    try(var reopened=open(directory.resolve("index"))) {
      assertEquals(CONTENT,reopened.documentFieldOps().getDocumentContent(DOC));
      assertEquals("sibling survives",reopened.documentFieldOps().getDocumentContent("sibling"));
    }
  }

  private RunningRuntime open(Path path) {
    var runtime=IndexSchema.fromCatalog(VduRevisionTestSupport.catalog(0)).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();return runtime;
  }
  private static WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(null,null,null,IndexingPacing.unthrottled(),null,null,runtime,runtime,null,0L);
  }
  private static CallContext context() {
    var base=CallContext.none();return new CallContext(null,null,null,base.engineContext().withWorkId(UUID.randomUUID()),base.provenance(),base.childLifetime());
  }
  private static MarkVduProcessingRequest mark(String attempt) throws Exception {
    var builder=MarkVduProcessingRequest.newBuilder().setDocId(DOC).setMaxRetries(3);
    token(builder,attempt);return builder.build();
  }
  private static UpdateVduResultRequest result(String attempt) throws Exception {
    var builder=UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setExtractedContent(CONTENT)
        .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);
    token(builder,attempt);return builder.build();
  }
  private static void token(Object builder,String value) throws Exception {
    try { builder.getClass().getMethod("setAttemptId",String.class).invoke(builder,value); }
    catch(NoSuchMethodException baseline) { /* same recovery invariant on the old transport */ }
  }
  private static String read(Path file) {
    try { return Files.readString(file); } catch(Exception error) { return error.toString(); }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      var fixture=new VduCrashRecoveryTest();
      var runtime=fixture.open(Path.of(args[0]));
      for(String id:List.of(DOC,"sibling")) runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,id,SchemaFields.DOC_UID,id+"-uid",SchemaFields.CONTENT,id.equals(DOC)?"baseline":"sibling survives",
          SchemaFields.SOURCE_SHA256,"a".repeat(64),SchemaFields.VDU_STATUS,"PENDING",SchemaFields.VDU_RETRY_COUNT,"0")));
      runtime.commitOps().commitAndTrack(CommitReason.TIMER);runtime.commitOps().maybeRefreshBlocking();
      var indexing=spy(runtime.indexingCoordinator());
      if(!Boolean.parseBoolean(args[1])) {
        doAnswer(invocation -> {
          Object value=invocation.callRealMethod();
          runtime.commitOps().commitAndTrack(CommitReason.TIMER);
          Runtime.getRuntime().halt(71);
          return value;
        }).when(indexing).indexSingle(any(IndexDocument.class));
      }
      var facade=mock(RunningRuntime.class);
      when(facade.indexingCoordinator()).thenReturn(indexing);
      when(facade.documentFieldOps()).thenReturn(runtime.documentFieldOps());
      when(facade.commitOps()).thenReturn(runtime.commitOps());
      var service=service(facade);var context=context();
      if(!service.markVduProcessing(mark("old"),context).getSuccess()) throw new AssertionError("claim refused");
      if(!service.updateVduResult(result("old"),context).getSuccess()) throw new AssertionError("publication refused");
      Runtime.getRuntime().halt(71);
    }
  }
}
