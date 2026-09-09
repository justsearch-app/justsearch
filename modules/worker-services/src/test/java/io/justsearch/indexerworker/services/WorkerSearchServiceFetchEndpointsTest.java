package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WorkerSearchService fetch/suggest endpoints")
class WorkerSearchServiceFetchEndpointsTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(catalogWithExtractionProvenance()).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(lifecycle);
  }

  private static FieldCatalogDef catalogWithExtractionProvenance() {
    FieldCatalogDef base = FieldCatalogDef.forChunkTesting(0);
    List<FieldCatalogDef.FieldDef> fields = new ArrayList<>(base.fields());
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.EXTRACTION_STATUS,
            "keyword",
            true,
            true,
            List.of("filter", "facet"),
            null,
            null,
            false));
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.CONTENT_TRUNCATED,
            "boolean",
            true,
            true,
            List.of("filter"),
            null,
            null,
            false));
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.EXTRACTION_POLICY_ID,
            "keyword",
            true,
            true,
            List.of("filter"),
            null,
            null,
            false));
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.EXTRACTION_PARSER_ID,
            "keyword",
            true,
            true,
            List.of("filter"),
            null,
            null,
            false));
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.SOURCE_SHA256,
            "keyword",
            true,
            false,
            List.of(),
            null,
            null,
            false));
    return new FieldCatalogDef(base.version() + "+extraction-provenance", fields);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  @Nested
  @DisplayName("Suggest")
  class Suggest {

    @Test
    @DisplayName("returns empty suggestions for blank query")
    void returnsEmptyForBlankQuery() {
      SuggestResponse response =
          callSuggest(SuggestRequest.newBuilder().setQuery("   ").setLimit(10).build());
      assertEquals(0, response.getSuggestionsCount());
    }

    @Test
    @DisplayName("returns suggestions from indexed title/content matches")
    void returnsSuggestionsForPrefix() throws Exception {
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-1",
                  SchemaFields.DOC_UID, "doc-1#0",
                  SchemaFields.PATH, "C:/docs/report-q1.pdf",
                  SchemaFields.TITLE, "Report Q1",
                  SchemaFields.CONTENT, "Quarterly report with financial summary")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      SuggestResponse response =
          callSuggest(SuggestRequest.newBuilder().setQuery("rep").setLimit(10).build());

      assertTrue(response.getSuggestionsCount() > 0, "Expected at least one suggestion");
      assertTrue(
          response.getSuggestionsList().stream()
              .anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("report")),
          "Expected report-related suggestion from title/path");
    }
  }

  @Nested
  @DisplayName("FetchDocuments")
  class FetchDocuments {

    @Test
    @DisplayName("returns found and missing documents with metadata")
    void returnsFoundAndMissingDocs() throws Exception {
      String docId = "doc-1";
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, docId,
                  SchemaFields.DOC_UID, docId + "#0",
                  SchemaFields.PATH, "C:/docs/contract.pdf",
                  SchemaFields.TITLE, "Service Agreement",
                  SchemaFields.MIME, "application/pdf",
                  SchemaFields.CONTENT, "Contract body text")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      FetchDocumentsResponse response =
          callFetchDocuments(
              FetchDocumentsRequest.newBuilder()
                  .addDocIds(docId)
                  .addDocIds("missing-doc")
                  .build());

      assertEquals(2, response.getDocumentsCount());

      DocumentContent found = response.getDocuments(0);
      assertEquals(docId, found.getDocId());
      assertTrue(found.getFound());
      assertEquals("Contract body text", found.getContent());
      assertEquals("Service Agreement", found.getMetadataOrDefault("title", ""));
      assertEquals("C:/docs/contract.pdf", found.getMetadataOrDefault("path", ""));
      assertEquals("application/pdf", found.getMetadataOrDefault("mime", ""));

      DocumentContent missing = response.getDocuments(1);
      assertEquals("missing-doc", missing.getDocId());
      assertFalse(missing.getFound());
      assertTrue(missing.getError().contains("not found"));
    }

    @Test
    @DisplayName("trims content to gRPC max payload cap")
    void trimsLargeContent() throws Exception {
      String docId = "doc-large";
      String largeContent = "a".repeat(210_000);
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, docId,
                  SchemaFields.DOC_UID, docId + "#0",
                  SchemaFields.PATH, "C:/docs/large.txt",
                  SchemaFields.CONTENT, largeContent)));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      FetchDocumentsResponse response =
          callFetchDocuments(FetchDocumentsRequest.newBuilder().addDocIds(docId).build());

      assertEquals(1, response.getDocumentsCount());
      DocumentContent doc = response.getDocuments(0);
      assertTrue(doc.getFound());
      assertEquals(200_000, doc.getContent().length(), "Content should be capped at 200k chars");
    }
  }

  @Nested
  @DisplayName("FetchDocumentSlice")
  class FetchDocumentSlice {

    @Test
    @DisplayName("returns paged slice and VDU metadata")
    void returnsPagedSliceAndMetadata() throws Exception {
      String docId = "doc-slice";
      String content = "0123456789abcdefghij";
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.ofEntries(
                  Map.entry(SchemaFields.DOC_ID, docId),
                  Map.entry(SchemaFields.DOC_UID, docId + "#0"),
                  Map.entry(SchemaFields.PATH, "C:/docs/slice.txt"),
                  Map.entry(SchemaFields.TITLE, "Slice Test"),
                  Map.entry(SchemaFields.MIME, "text/plain"),
                  Map.entry(SchemaFields.CONTENT, content),
                  Map.entry(SchemaFields.CONTENT_SHA256, "b".repeat(64)),
                  Map.entry(SchemaFields.EXTRACTION_STATUS, "SUCCESS_PARTIAL"),
                  Map.entry(SchemaFields.CONTENT_TRUNCATED, true),
                  Map.entry(SchemaFields.EXTRACTION_POLICY_ID, "policy-v3"),
                  Map.entry(SchemaFields.EXTRACTION_PARSER_ID, "tika-3.2"),
                  Map.entry(SchemaFields.SOURCE_SHA256, "a".repeat(64)),
                  Map.entry(SchemaFields.VDU_STATUS, "done"),
                  Map.entry(SchemaFields.VDU_PROCESSED, "true"),
                  Map.entry(SchemaFields.VDU_PAGE_COUNT, "3"),
                  Map.entry(SchemaFields.VDU_ENRICHMENT, "OCR enriched"))));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      FetchDocumentSliceResponse response =
          callFetchDocumentSlice(
              FetchDocumentSliceRequest.newBuilder()
                  .setDocId(docId)
                  .setOffsetChars(5)
                  .setMaxChars(6)
                  .build());

      assertTrue(response.getFound());
      assertEquals("56789a", response.getContent());
      assertTrue(response.getTruncated());
      assertEquals(11, response.getNextOffsetChars());
      // Tempdoc 878: the total is the denominator the caller needs to choose between paging and
      // sampling. It was computed here already and thrown away.
      assertEquals(content.length(), response.getTotalChars());
      assertEquals("Slice Test", response.getMetadataOrDefault("title", ""));
      assertEquals("text/plain", response.getMetadataOrDefault("mime", ""));
      assertEquals("done", response.getMetadataOrDefault("vdu_status", ""));
      assertEquals("3", response.getMetadataOrDefault("vdu_page_count", ""));
      assertEquals("SUCCESS_PARTIAL", response.getExtractionStatus());
      assertTrue(response.hasContentTruncated());
      assertTrue(response.getContentTruncated());
      assertEquals("policy-v3", response.getExtractionPolicyId());
      assertEquals("tika-3.2", response.getExtractionParserId());
      assertEquals("a".repeat(64), response.getSourceSha256());
      assertEquals("b".repeat(64), response.getMetadataOrDefault("content_sha256", ""));
    }

    @Test
    void emptyStoredContentRetainsFoundAndRevision() throws Exception {
      String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "empty-slice", SchemaFields.DOC_UID, "empty-slice#0",
          SchemaFields.CONTENT, "", SchemaFields.CONTENT_SHA256, emptyHash,
          SchemaFields.EXTRACTION_STATUS, "SUCCESS_EMPTY")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();
      FetchDocumentSliceResponse response = callFetchDocumentSlice(
          FetchDocumentSliceRequest.newBuilder().setDocId("empty-slice").build());
      assertTrue(response.getFound());
      assertEquals("", response.getContent());
      assertEquals(0, response.getTotalChars());
      assertEquals("SUCCESS_EMPTY", response.getExtractionStatus());
      assertEquals(emptyHash, response.getMetadataOrDefault("content_sha256", ""));
    }

    @Test
    @DisplayName("pages UTF-16 content without splitting Unicode scalar values")
    void pagesWithoutSplittingSurrogatePairs() throws Exception {
      String docId = "doc-unicode-pages";
      String content = "A\uD83D\uDE00B\uD834\uDD1EC";
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, docId,
                  SchemaFields.DOC_UID, docId + "#0",
                  SchemaFields.PATH, "C:/docs/unicode.txt",
                  SchemaFields.CONTENT, content)));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      StringBuilder reconstructed = new StringBuilder();
      int offset = 0;
      List<Integer> pageLengths = new ArrayList<>();
      do {
        FetchDocumentSliceResponse page =
            callFetchDocumentSlice(
                FetchDocumentSliceRequest.newBuilder()
                    .setDocId(docId)
                    .setOffsetChars(offset)
                    .setMaxChars(1)
                    .build());
        assertTrue(page.getNextOffsetChars() > offset, "Every non-final page must make progress");
        reconstructed.append(page.getContent());
        pageLengths.add(page.getContent().length());
        offset = page.getNextOffsetChars();
        if (!page.getTruncated()) {
          break;
        }
      } while (true);

      assertEquals(content, reconstructed.toString());
      assertEquals(List.of(1, 2, 1, 2, 1), pageLengths);
      assertEquals(content.length(), offset);
    }

    @Test
    @DisplayName("rejects an offset inside a Unicode surrogate pair")
    void rejectsOffsetInsideSurrogatePair() throws Exception {
      String docId = "doc-unicode-offset";
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, docId,
                  SchemaFields.DOC_UID, docId + "#0",
                  SchemaFields.PATH, "C:/docs/unicode-offset.txt",
                  SchemaFields.CONTENT, "A\uD83D\uDE00B")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      WorkerServiceException error =
          assertThrows(
              WorkerServiceException.class,
              () ->
                  service.fetchDocumentSlice(
                      FetchDocumentSliceRequest.newBuilder()
                          .setDocId(docId)
                          .setOffsetChars(2)
                          .setMaxChars(1)
                          .build(),
                      CallContext.none()));

      assertEquals(WorkerServiceException.Status.INVALID_ARGUMENT, error.status());
      assertEquals("offset_chars splits a Unicode surrogate pair", error.getMessage());
    }

    @Test
    @DisplayName("878: an unknown doc_id leaves total_chars at 0 — unknown, not 'empty document'")
    void unknownDocIdReportsNoTotal() {
      FetchDocumentSliceResponse response =
          callFetchDocumentSlice(
              FetchDocumentSliceRequest.newBuilder()
                  .setDocId("doc-that-was-never-indexed")
                  .setOffsetChars(0)
                  .setMaxChars(10)
                  .build());

      assertFalse(response.getFound());
      assertEquals(0, response.getTotalChars());
    }

    @Test
    @DisplayName("returns INVALID_ARGUMENT when doc_id is blank")
    void returnsInvalidArgumentForBlankDocId() {
      WorkerServiceException error =
          assertThrows(
              WorkerServiceException.class,
              () ->
                  service.fetchDocumentSlice(
                      FetchDocumentSliceRequest.newBuilder()
                          .setDocId("   ")
                          .setOffsetChars(0)
                          .setMaxChars(10)
                          .build(),
                      CallContext.none()),
              "Expected INVALID_ARGUMENT error");

      assertEquals(WorkerServiceException.Status.INVALID_ARGUMENT, error.status());
      assertEquals("doc_id is required", error.getMessage());
    }
  }

  private SuggestResponse callSuggest(SuggestRequest request) {
    SuggestResponse response;
    try {
      response = service.suggest(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("suggest failed: " + e.getMessage());
    }
    assertNotNull(response, "Response should not be null");
    return response;
  }

  private FetchDocumentsResponse callFetchDocuments(FetchDocumentsRequest request) {
    FetchDocumentsResponse response;
    try {
      response = service.fetchDocuments(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("fetchDocuments failed: " + e.getMessage());
    }
    assertNotNull(response, "Response should not be null");
    return response;
  }

  private FetchDocumentSliceResponse callFetchDocumentSlice(FetchDocumentSliceRequest request) {
    FetchDocumentSliceResponse response;
    try {
      response = service.fetchDocumentSlice(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("fetchDocumentSlice failed: " + e.getMessage());
    }
    assertNotNull(response, "Response should not be null");
    return response;
  }
}
