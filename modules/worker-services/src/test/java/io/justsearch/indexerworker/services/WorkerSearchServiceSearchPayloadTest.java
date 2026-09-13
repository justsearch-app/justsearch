package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerSearchServiceSearchPayloadTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @Test
  void searchDoesNotReturnStoredContentFieldInHitFields() throws Exception {
    // UI search readiness regression:
    // - The index stores large extracted `content`.
    // - ui-web does not use `fields.content` in search results and loads text via /api/preview instead.
    // This test ensures gRPC SearchService.Search drops `content` from hit fields.
    String prev = System.getProperty("justsearch.config");
    try {
      Path base = Files.createTempDirectory("justsearch-grpc-search-test-");
      String yaml =
          "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
              + "index:\n  collections:\n    - name: grpcsearchtest\n      roots: ['ignored']\n"
              + "vector:\n  dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      // Minimal catalog, but content MUST be stored to reproduce the problematic payload.
      FieldCatalogDef catalog =
          new FieldCatalogDef(
              "test",
              List.of(
                  new FieldCatalogDef.FieldDef(
                      SchemaFields.DOC_ID,
                      "keyword",
                      true,
                      true,
                      List.of("id", "sort"),
                      null,
                      null,
                      false),
                  new FieldCatalogDef.FieldDef(
                      SchemaFields.DOC_UID,
                      "keyword",
                      false,
                      true,
                      List.of("sort", "tiebreak"),
                      null,
                      null,
                      false),
                  new FieldCatalogDef.FieldDef(
                      SchemaFields.PARENT_TOKEN_COUNT,
                      "long",
                      true,
                      true,
                      List.of("filter", "sort"),
                      null,
                      null,
                      false),
                  new FieldCatalogDef.FieldDef(
                      SchemaFields.CONTENT,
                      "text",
                      true,
                      false,
                      List.of("highlight"),
                      null,
                      "icu",
                      false)));

      var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      var runtime = lifecycle;
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-1",
                  SchemaFields.DOC_UID, "doc-1#0",
                  SchemaFields.PARENT_TOKEN_COUNT, "2048",
                  SchemaFields.CONTENT, "Hello world")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var service = new WorkerSearchService(lifecycle);

      SearchResponse response =
          service.search(
              SearchRequest.newBuilder()
                  .setQuery("Hello")
                  .setLimit(10)
                  .setMode(SearchMode.SEARCH_MODE_TEXT)
                  .build(),
              CallContext.none());
      assertNotNull(response);
      assertTrue(response.getResultsCount() >= 1);
      assertTrue(
          !response.getResults(0).getFieldsMap().containsKey(SchemaFields.CONTENT),
          "SearchResponse hit must not include stored content field");
      assertTrue(
          !response.getResults(0).getFieldsMap().containsKey(SchemaFields.PARENT_TOKEN_COUNT),
          "SearchResponse hit must not include ranking-only parent_token_count metadata");

      lifecycle.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }
}
