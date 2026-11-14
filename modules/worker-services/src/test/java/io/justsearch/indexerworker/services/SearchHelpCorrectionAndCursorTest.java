/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchHelpCorrectionAndCursorTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void zeroHitRetryCountsHelpWithTheCorrectedRowsQuery() throws Exception {
    withRuntime((runtime, service) -> {
      var response = service.search(request().setQuery("quokkb").build(), CallContext.none());
      assertEquals(4, response.getMatchCount(), "the zero-hit typo retry must return corrected rows");
      assertTrue(response.getSearchTrace().getStagesList().stream().anyMatch(stage ->
          stage.getId().equals("correction") && stage.getStatus().equals("executed")));
      assertEquals(2, response.getHelpMatches().getCount(), "Help follows the corrected query too");
      assertFalse(response.getHelpMatches().getCapped());
    });
  }

  @Test
  void onlyFirstPageCarriesHelpMatches() throws Exception {
    withRuntime((runtime, service) -> {
      var request = request().setLimit(1).build();
      var first = service.search(request, CallContext.none());
      assertEquals(2, first.getHelpMatches().getCount());
      assertFalse(first.getNextCursor().isBlank());
      var next = service.search(request.toBuilder().setCursor(first.getNextCursor()).build(), CallContext.none());
      assertEquals(1, next.getResultsCount());
      assertNotEquals(first.getResults(0).getId(), next.getResults(0).getId());
      assertFalse(next.hasHelpMatches(), "continuation pages do not recompute the first-page line");
    });
  }

  private static SearchRequest.Builder request() {
    return SearchRequest.newBuilder().setQuery("quokka").setLimit(10)
        .addExcludedCollections("justsearch-help")
        .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true));
  }

  private void withRuntime(java.util.function.BiConsumer<RunningRuntime, WorkerSearchService> check)
      throws Exception {
    String previous = System.getProperty("justsearch.config");
    Path config = temp.resolve("config.yaml");
    Files.writeString(config, "app:\n  data_dir: '" + temp.toString().replace('\\', '/')
        + "'\nsearch:\n  corrections:\n    enabled: 'true'\n"
        + "    zero_hit_retry_enabled: 'true'\n    max_edit_distance: 1\n    df_threshold: 2\n");
    System.setProperty("justsearch.config", config.toString());
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      assertTrue(runtime.resolvedConfig().search().corrections().enabled());
      for (int i = 0; i < 6; i++) {
        String id = (i < 4 ? "/files/" : "/help/") + i;
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid-" + i,
            SchemaFields.PATH, id, SchemaFields.CONTENT, "quokka telemetry",
            SchemaFields.COLLECTION, i < 4 ? "files" : "justsearch-help")));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      check.accept(runtime, new WorkerSearchService(runtime));
    } finally {
      if (previous == null) System.clearProperty("justsearch.config");
      else System.setProperty("justsearch.config", previous);
    }
  }
}
