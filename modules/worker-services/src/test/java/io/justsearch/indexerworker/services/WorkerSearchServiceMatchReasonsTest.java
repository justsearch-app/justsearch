package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class WorkerSearchServiceMatchReasonsTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @Test
  void searchPopulatesMatchedFieldsBasedOnPreviewPresence() throws Exception {
    String prev = System.getProperty("justsearch.config");
    try {
      Path base = Files.createTempDirectory("justsearch-grpc-search-match-reasons-");
      String yaml =
          "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
              + "index:\n  collections:\n    - name: grpcsearchtest\n      roots: ['ignored']\n"
              + "vector:\n  dimension: 4\n";
      Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      // Minimal catalog (ensure content_preview is stored so the service can reason about preview matches).
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
                      SchemaFields.CONTENT,
                      "text",
                      true,
                      false,
                      List.of("highlight"),
                      null,
                      "icu",
                      false),
                  new FieldCatalogDef.FieldDef(
                      SchemaFields.CONTENT_PREVIEW,
                      "text",
                      true,
                      false,
                      List.of("highlight"),
                      null,
                      "icu",
                      false)));

      var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      var runtime = lifecycle;

      String q = "needle";
      String earlyContent = q + " " + "a".repeat(5000);
      String lateContent = "a".repeat(5000) + " " + q;

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-early",
                  SchemaFields.DOC_UID, "doc-early#0",
                  SchemaFields.CONTENT, earlyContent,
                  SchemaFields.CONTENT_PREVIEW, earlyContent.substring(0, 4096))));

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-late",
                  SchemaFields.DOC_UID, "doc-late#0",
                  SchemaFields.CONTENT, lateContent,
                  SchemaFields.CONTENT_PREVIEW, lateContent.substring(0, 4096))));

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var service = new WorkerSearchService(lifecycle);

      SearchResponse response =
          service.search(
              SearchRequest.newBuilder()
                  .setQuery(q)
                  .setLimit(10)
                  .setMode(SearchMode.SEARCH_MODE_TEXT)
                  .build(),
              CallContext.none());
      assertNotNull(response);
      assertTrue(response.getResultsCount() >= 2, "Expected at least two hits");

      var early =
          response.getResultsList().stream()
              .filter(r -> "doc-early".equals(r.getId()))
              .findFirst()
              .orElse(null);
      var late =
          response.getResultsList().stream()
              .filter(r -> "doc-late".equals(r.getId()))
              .findFirst()
              .orElse(null);

      assertNotNull(early, "Expected doc-early in results");
      assertNotNull(late, "Expected doc-late in results");

      assertEquals(List.of(SchemaFields.CONTENT_PREVIEW), early.getMatchedFieldsList());
      assertEquals(List.of(SchemaFields.CONTENT), late.getMatchedFieldsList());

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
