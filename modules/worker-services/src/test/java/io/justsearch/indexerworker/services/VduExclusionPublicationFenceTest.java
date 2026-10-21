/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
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

  @ParameterizedTest(name="policySaveWaitsForVduPublicationAfterItsExclusionCheck({0})") @ValueSource(booleans={false,true})
  void policySaveWaitsForVduPublicationAfterItsExclusionCheck(boolean exhaustedClaim) throws Exception {
    var previous = ConfigStore.globalOrNull();
    var installed = new ConfigStore(config("[\"unrelated.txt\"]"));
    ConfigStore.setGlobal(installed);
    var checked = new CountDownLatch(1);
    var publish = new CountDownLatch(1);
    var saving = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
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
      var service = new WorkerIngestService(null,null,null,IndexingPacing.unthrottled(),null,null,view,view,null,0L);
      service.useExclusionRoots(() -> List.of(directory));
      var base = CallContext.none();
      var context = new CallContext(null,null,null,base.engineContext().withWorkId(UUID.randomUUID()),base.provenance(),base.childLifetime());
      if (!exhaustedClaim) assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(id).setAttemptId("attempt").setMaxRetries(3).build(),context).getSuccess());
      doAnswer(call -> {
        Object result = call.callRealMethod();
        checked.countDown();
        assertTrue(publish.await(10, TimeUnit.SECONDS));
        return result;
      }).when(fields).excludedProcessingIds(List.of(id));
      var result = executor.submit(() -> {
        if (exhaustedClaim) {
          return service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
              .setDocId(id).setAttemptId("attempt").setMaxRetries(3).build(),context).getSuccess();
        }
        return service.updateVduResult(UpdateVduResultRequest.newBuilder()
            .setDocId(id).setAttemptId("attempt").setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
            .setExtractedContent("new VDU text ".repeat(600)).setVduEnrichment("vdu marker").build(),context).getSuccess();
      });
      assertTrue(checked.await(10, TimeUnit.SECONDS), "pause immediately after the live exclusion check");
      var save = executor.submit(() -> { saving.countDown(); installed.update(config("[\"private.png\"]")); });
      assertTrue(saving.await(10, TimeUnit.SECONDS));
      try {
        assertThrows(TimeoutException.class, () -> save.get(250, TimeUnit.MILLISECONDS),
            "a later save must not complete between the exclusion check and publication");
      } finally { publish.countDown(); }
      assertEquals(!exhaustedClaim,result.get(10, TimeUnit.SECONDS));
      save.get(10,TimeUnit.SECONDS);
      assertEquals(exhaustedClaim ? "FAILED" : "COMPLETED",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_STATUS));
      if (!exhaustedClaim) assertEquals("vdu marker",runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_ENRICHMENT));
      else assertTrue(runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_ENRICHMENT).contains("Max retries"));
      assertEquals(Set.of(id),runtime.documentFieldOps().excludedProcessingIds(List.of(id)),
          "the later save now routes the document through normal exclusion handling");
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
