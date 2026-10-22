/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.services.excludes.ExcludesServiceImpl;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Explicit Apply uses the real Engine deletion port after a conditional batch publication. */
@Timeout(120)
final class EngineExclusionApplyPublicationTest {
  @TempDir Path directory;

  @Test void explicitApplyRemovesEnrichmentPublishedAfterAnExclusionSave() throws Exception {
    var previous = ConfigStore.globalOrNull();
    var release = new CountDownLatch(1);
    var checked = new CountDownLatch(1);
    var deleting = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(3);
    try (var engine = EngineTestHarness.start(directory.resolve("data"))) {
      var store = ConfigStore.global();
      Path root = Files.createDirectories(directory.resolve("documents"));
      Path file = Files.writeString(root.resolve("private.txt"),"privatepublicationmarker original source text");
      String id = file.toAbsolutePath().toString();
      // Use the real client to own the saved root; wait until the native writer is finished.
      engine.client().addWatchedPath(root,TestEngineContexts.FOREGROUND);
      engine.client().submitBatch(List.of(file),TestEngineContexts.FOREGROUND);
      assertTrue(engine.awaitIndexed(1,60_000));
      assertTrue(engine.awaitSearchable("privatepublicationmarker",10_000));
      var client = spy(engine.client());
      doAnswer(call -> {
        deleting.countDown();
        return call.callRealMethod();
      }).when(client).deleteDocById(eq(id),any());
      var excludes = new ExcludesServiceImpl(() -> client);
      try (var view = engine.captureServingView()) {
        var runtime = (RunningRuntime)view.ingestRuntime();
        var input = runtime.documentFieldOps().getEnrichmentInputs(List.of(id));
        assertEquals(1,input.size());
        runtime.useSourceProcessingPolicy(source -> {
          boolean allowed = !view.services().ingestService().isUserExcluded(Path.of(source),root);
          checked.countDown();
          try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
          catch (InterruptedException failure) { throw new AssertionError(failure); }
          return allowed;
        });
        Integer dimension = runtime.schema().ssotVectorDimension();
        assertNotNull(dimension);
        float[] vector = new float[dimension]; vector[0]=1;
        var publication = pool.submit(() -> runtime.indexingCoordinator().updateDocumentsBatchIf(
            List.of(Map.entry(id,Map.of(SchemaFields.VECTOR,vector,SchemaFields.EMBEDDING_STATUS,"COMPLETED",
                SchemaFields.ENTITY_PERSONS_RAW,List.of("private entity"),SchemaFields.VDU_ENRICHMENT,"private VDU"))),input));
        assertTrue(checked.await(10,TimeUnit.SECONDS));
        var next = configWith(store.get(),"[\"*.txt\"]");
        var save = pool.submit(() -> store.update(next));
        Future<io.justsearch.app.api.ExcludesService.ExcludesResult> apply;
        try {
          assertDoesNotThrow(() -> save.get(2,TimeUnit.SECONDS),
              "the save publishes its snapshot without waiting for the in-flight index writer");
          apply = pool.submit(() -> excludes.applyExcludes(false,TestEngineContexts.FOREGROUND));
          assertTrue(deleting.await(5,TimeUnit.SECONDS),"explicit Apply reaches the normal exclusion delete");
          assertFalse(apply.isDone(),"Apply is ordered behind the in-flight batch writer");
        } finally { release.countDown(); }
        assertEquals(1,publication.get(10,TimeUnit.SECONDS).updatedCount(),"the already-checked batch really published");
        assertEquals(1,apply.get(10,TimeUnit.SECONDS).deletedById());
        assertTrue(engine.awaitDocumentAbsent(id,10_000),"Apply removes all parent/chunk enrichment after publication");
        assertNull(runtime.documentFieldOps().getDocumentContent(id));
        assertNull(runtime.documentFieldOps().getDocumentField(id,SchemaFields.VDU_ENRICHMENT));
        assertTrue(engine.awaitNotSearchable("privatepublicationmarker",10_000),"the excluded document is out of search");
      }
    } finally {
      release.countDown(); pool.shutdownNow();
      try { assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
      finally {
        var installed=ConfigStore.globalOrNull();
        if(installed!=null) ConfigStore.restoreGlobal(installed,previous);
      }
    }
  }

  private static ResolvedConfig configWith(ResolvedConfig current,String patterns) {
    var builder=ResolvedConfig.builder();
    current.resolutions().forEach((key,value) -> builder.putDefault(key,value.value()));
    builder.put("justsearch.ui.exclude_patterns",500,"test","saved policy",patterns);
    return builder.build();
  }

}
