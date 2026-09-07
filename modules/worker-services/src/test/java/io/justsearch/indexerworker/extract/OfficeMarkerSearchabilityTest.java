package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Office fixtures: searchable end-to-end (extract → index → search)")
final class OfficeMarkerSearchabilityTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("DOCX marker is searchable via WorkerSearchService.search")
  void docxMarkerIsSearchable() throws Exception {
    assertFixtureSearchable(
        "/fixtures/office/office-marker.docx",
        "office-marker.docx",
        "JUSTSEARCH_OFFICE_DOCX_MARKER");
  }

  @Test
  @DisplayName("XLSX marker is searchable via WorkerSearchService.search")
  void xlsxMarkerIsSearchable() throws Exception {
    assertFixtureSearchable(
        "/fixtures/office/office-marker.xlsx",
        "office-marker.xlsx",
        "JUSTSEARCH_OFFICE_XLSX_MARKER");
  }

  @Test
  @DisplayName("PPTX marker is searchable via WorkerSearchService.search")
  void pptxMarkerIsSearchable() throws Exception {
    assertFixtureSearchable(
        "/fixtures/office/office-marker.pptx",
        "office-marker.pptx",
        "JUSTSEARCH_OFFICE_PPTX_MARKER");
  }

  private void assertFixtureSearchable(String resourcePath, String fileName, String marker)
      throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    Path cfg = null;

    try {
      Path dataDir = tempDir.resolve("data");
      Files.createDirectories(dataDir);

      String yaml =
          "app:\n  data_dir: "
              + dataDir.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: officetest\n      roots: ['ignored']\n"
              + "vector:\n  dimension: 0\n";

      cfg = Files.createTempFile(tempDir, "justsearch-office-search-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());

      var lifecycle =
          io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                  FieldCatalogDef.forTesting(0))
              .ephemeral()
              .open();
      try {
        var runtime = lifecycle;
        var service = new WorkerSearchService(lifecycle);

        Path file = copyResourceToTemp(resourcePath, fileName);
        var extractor = new ContentExtractor();
        ExtractionResult extraction = extractor.extract(file);
        assertNotNull(extraction.content(), "Expected extracted content to be non-null for " + fileName);
        assertTrue(
            extraction.content().contains(marker),
            "Expected marker in extracted text but got: [" + extraction.content() + "]");

        IndexDocument doc = buildIndexDocument(file, extraction);
        Object docIdRaw = doc.fields().get(SchemaFields.DOC_ID);
        assertNotNull(docIdRaw, "Expected " + SchemaFields.DOC_ID + " to be present in IndexDocument");
        assertTrue(docIdRaw instanceof String, "Expected " + SchemaFields.DOC_ID + " to be a String");
        String docId = (String) docIdRaw;

        runtime.indexingCoordinator().indexSingle(doc);
        runtime.commitOps().commitAndTrack();
        runtime.commitOps().maybeRefreshBlocking();

        SearchResponse resp = callSearch(service, marker);
        assertNotNull(resp);
        assertTrue(resp.getResultsCount() >= 1, "Expected at least 1 search hit for marker: " + marker);
        assertTrue(
            resp.getResultsList().stream().anyMatch(r -> docId.equals(r.getId())),
            "Expected search hits to include docId=" + docId + " but got ids="
                + resp.getResultsList().stream().map(r -> r.getId()).toList());
      } finally {
        lifecycle.close();
      }
    } finally {
      if (prevConfig == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prevConfig);
      }
      if (cfg != null) {
        try {
          Files.deleteIfExists(cfg);
        } catch (Exception ignored) {
          // best-effort
        }
      }
    }
  }

  private Path copyResourceToTemp(String resourcePath, String fileName) throws IOException {
    try (InputStream is = OfficeMarkerSearchabilityTest.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Missing test resource: " + resourcePath);
      }
      Path out = tempDir.resolve(fileName);
      Files.copy(is, out);
      return out;
    }
  }

  private static SearchResponse callSearch(WorkerSearchService service, String query) {
    return service.search(
        SearchRequest.newBuilder()
            .setQuery(query)
            .setLimit(10)
            .setMode(SearchMode.SEARCH_MODE_TEXT)
            .build(),
        CallContext.none());
  }

  private static IndexDocument buildIndexDocument(Path filePath, ExtractionResult extraction)
      throws Exception {
    return TestDocumentBuilder.buildDocument(filePath, extraction);
  }
}
