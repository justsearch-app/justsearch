/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** A policy save and all VDU parent/chunk publication have one ordering. */
final class VduExclusionPublicationFenceTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @ParameterizedTest(name="explicitApplyFollowsInFlightVduPublication({0})") @ValueSource(booleans={false,true})
  void explicitApplyFollowsInFlightVduPublication(boolean exhaustedClaim) throws Exception {
    var previous = ConfigStore.globalOrNull();
    var installed = new ConfigStore(config("[\"unrelated.txt\"]"));
    ConfigStore.setGlobal(installed);
    var checked = new CountDownLatch(1);
    var publish = new CountDownLatch(1);
    var applying = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(3);
    try (var runtime = IndexSchema.fromCatalog(VduRevisionTestSupport.catalog(4))
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      String id = Files.writeString(directory.resolve("private.png"), "source").toString();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,id,SchemaFields.DOC_UID,"uid",SchemaFields.SOURCE_SHA256,"a".repeat(64),
          SchemaFields.CONTENT,"original text",SchemaFields.VDU_STATUS,"PENDING",SchemaFields.VDU_RETRY_COUNT,exhaustedClaim ? "3" : "0")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var fields = spy(runtime.documentFieldOps());
      var view = spy(runtime);
      doReturn(fields).when(view).documentFieldOps();
      var fence = new java.util.concurrent.locks.ReentrantLock(true);
      var loop = mock(IndexingLoop.class);
      when(loop.withFileMutationFence(any())).thenAnswer(call -> {
        java.util.function.Supplier<?> mutation = call.getArgument(0);
        fence.lock();
        try { return mutation.get(); } finally { fence.unlock(); }
      });
      var service = new WorkerIngestService(mock(JobQueue.class),loop,mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(),null,null,view,view,null,0L);
      Object owner = new Object();
      service.setMutationAdmission(new WorkerMutationAdmission(owner),owner);
      service.useExclusionRoots(() -> List.of(directory));
      var base = CallContext.none();
      var context = new CallContext(null,null,null,base.engineContext().withWorkId(UUID.randomUUID()),base.provenance(),base.childLifetime());
      if (!exhaustedClaim) assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(id).setAttemptId("attempt").setMaxRetries(3).build(),context).getSuccess());
      doAnswer(call -> {
        Object result = call.callRealMethod();
        checked.countDown();
        assertTrue(publish.await(20, TimeUnit.SECONDS));
        return result;
      }).when(fields).excludedProcessingIds(List.of(id));
      var result = executor.submit(() -> {
        if (exhaustedClaim) {
          return service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
              .setDocId(id).setAttemptId("attempt").setMaxRetries(3).build(),context).getSuccess();
        }
        return service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64))
            .setDocId(id).setAttemptId("attempt").setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
            .setExtractedContent("new VDU text ".repeat(600)).setVduEnrichment("vdu marker").build(),context).getSuccess();
      });
      assertTrue(checked.await(10, TimeUnit.SECONDS), "pause immediately after the live exclusion check");
      var save = executor.submit(() -> installed.update(config("[\"private.png\"]")));
      Future<Boolean> apply;
      try {
        assertDoesNotThrow(() -> save.get(2,TimeUnit.SECONDS),
            "the save publishes its snapshot without waiting for the VDU file fence");
        apply = executor.submit(() -> {
          applying.countDown();
          return service.deleteById(DeleteByIdRequest.newBuilder().setDocId(id).build(),CallContext.none()).getSuccess();
        });
        assertTrue(applying.await(2,TimeUnit.SECONDS));
        assertFalse(apply.isDone(),"explicit Apply waits behind the in-flight VDU publication");
      } finally { publish.countDown(); }
      assertEquals(!exhaustedClaim,result.get(10, TimeUnit.SECONDS));
      assertTrue(apply.get(10,TimeUnit.SECONDS));
      runtime.commitOps().maybeRefreshBlocking();
      assertNull(runtime.documentFieldOps().getDocumentContent(id),
          "explicit Apply runs after publication and removes its text and enrichment");
      assertTrue(runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID,id,100).isEmpty());
    } finally {
      publish.countDown();
      executor.shutdownNow();
      try { assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
      finally { ConfigStore.restoreGlobal(installed,previous); }
    }
  }

  private static ResolvedConfig config(String patterns) {
    return ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns",patterns).build();
  }
}
