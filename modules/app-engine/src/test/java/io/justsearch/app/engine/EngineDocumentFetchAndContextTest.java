/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.process.GrpcDataIntegrationTest}.
 *
 * <p><b>What the retired test asserted, and why it survives the collapse.</b> Its subject was never
 * gRPC despite the name: it indexed two files with known content and then pinned what comes back
 * out of the index. Search finds a document by its content; {@code FetchDocuments} returns the
 * <em>actual</em> stored text rather than an empty shell (the "content field is not stored" bug it
 * was written to catch); a mixed batch of known and unknown ids returns one entry per request with
 * {@code found} set correctly; {@code FetchDocumentSlice} pages with offsets that actually advance;
 * and {@code RetrieveContext} finds relevant passages, honours a doc-id filter, and does not fall
 * over on an irrelevant question. All of that is index behaviour, reached now by a direct call on
 * {@code KnowledgeClient} and returning the same protos, so the assertions transfer without being
 * reshaped.
 *
 * <p><b>Two differences worth stating.</b>
 *
 * <ol>
 *   <li><b>{@code retrieveContext} takes a {@code Set<String>} of doc ids in-process</b>
 *       (KnowledgeClient.java:420) where the retired test passed a {@code List}. The server side
 *       has always copied the ids into a {@code HashSet} before use
 *       (WorkerSearchService.java:869), so the filter's meaning is unchanged — only the parameter
 *       type is.
 *   <li><b>The retired test's {@code @Order} values collided</b> — two methods were {@code
 *       @Order(6)} and two were {@code @Order(7)} — so its "order" was partly arbitrary. Every
 *       method here is read-only against an index that {@code @BeforeAll} finished populating, so
 *       nothing depends on the order; the numbers are renumbered 1..11 to make the declared order
 *       honest rather than to encode a dependency.
 * </ol>
 *
 * <p><b>What was dropped, and why.</b> The {@code mmf.keepAlive()} heartbeat and the
 * {@code awaitPort}/{@code isHealthy} handshake, which existed only to reach a second process.
 */
@DisplayName("Engine document fetch and context retrieval (in-process)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
final class EngineDocumentFetchAndContextTest {

  private static final String CONTENT_1 =
      """
      The quick brown fox jumps over the lazy dog.
      This is a test document about foxes and dogs.
      Foxes are cunning animals that live in forests.
      """;

  private static final String CONTENT_2 =
      """
      Database Configuration Guide

      To configure the database, set the following parameters:
      - host: localhost
      - port: 5432
      - username: admin
      - password: secretPassword123

      Make sure to restart the service after changes.
      """;

  @TempDir static Path tempDir;

  private static EngineTestHarness harness;

  /** Discovered from search after indexing, exactly as the retired test did it. */
  private static String docId1;

  private static String docId2;

  @BeforeAll
  static void indexTheCorpus() throws Exception {
    Path corpus = tempDir.resolve("corpus");
    Files.createDirectories(corpus);
    Path testFile1 = corpus.resolve("fox-document.txt");
    Path testFile2 = corpus.resolve("database-config.txt");
    Files.writeString(testFile1, CONTENT_1);
    Files.writeString(testFile2, CONTENT_2);

    harness = EngineTestHarness.start(tempDir.resolve("data"));
    assertTrue(harness.client().isHealthy(), "the engine must be healthy before indexing");

    assertEquals(
        2,
        harness.client().submitBatch(List.of(testFile1, testFile2)).getAcceptedCount(),
        "should accept 2 files for indexing");

    assertTrue(harness.awaitIndexed(2, 120_000), "documents should be indexed");

    // Path normalisation differs by platform, so the ids are discovered rather than computed —
    // the retired test's reasoning, unchanged.
    assertTrue(harness.awaitSearchable("fox", 60_000), "the fox document must become findable");
    assertTrue(
        harness.awaitSearchable("secretPassword123", 60_000),
        "the config document must become findable");
    docId1 = discoverDocIdBySearch("fox");
    docId2 = discoverDocIdBySearch("secretPassword123");

    assertNotNull(docId1, "should find the fox document after indexing");
    assertNotNull(docId2, "should find the database config document after indexing");
    assertFalse(docId1.equals(docId2), "the two probes must resolve to two different documents");
  }

  @AfterAll
  static void stopEngine() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  private static String discoverDocIdBySearch(String searchTerm) {
    SearchResponse response = harness.client().search(searchTerm, 1);
    return response.getResultsCount() > 0 ? response.getResults(0).getId() : null;
  }

  // =========================================================================
  // Search
  // =========================================================================

  @Test
  @Order(1)
  @DisplayName("Search finds indexed document by content")
  void searchFindsIndexedDocumentByContent() {
    SearchResponse response = harness.client().search("fox", 10);

    assertNotNull(response, "search response should not be null");
    assertTrue(response.getResultsCount() > 0, "should find at least 1 document");
    assertTrue(
        response.getResultsList().stream().anyMatch(r -> r.getId().equals(docId1)),
        "should find the fox document in search results");
  }

  @Test
  @Order(2)
  @DisplayName("Search finds database config by keyword")
  void searchFindsDatabaseConfigByKeyword() {
    SearchResponse response = harness.client().search("password", 10);

    assertNotNull(response, "search response should not be null");
    assertTrue(response.getResultsCount() > 0, "should find at least 1 document");
    assertTrue(
        response.getResultsList().stream().anyMatch(r -> r.getId().equals(docId2)),
        "should find the database config document in search results");
  }

  // =========================================================================
  // FetchDocuments
  // =========================================================================

  @Test
  @Order(3)
  @DisplayName("FetchDocuments returns actual file content")
  void fetchDocumentsReturnsActualContent() {
    FetchDocumentsResponse response = harness.client().fetchDocuments(List.of(docId1));

    assertNotNull(response, "FetchDocuments response should not be null");
    assertEquals(1, response.getDocumentsCount(), "should return 1 document");

    DocumentContent doc = response.getDocuments(0);
    assertEquals(docId1, doc.getDocId(), "document ID should match");
    assertTrue(doc.getFound(), "document should be found");

    String content = doc.getContent();
    assertFalse(content.isEmpty(), "content should not be empty");
    assertTrue(content.contains("fox"), "content should contain 'fox'");
    assertTrue(content.contains("lazy dog"), "content should contain 'lazy dog'");
  }

  @Test
  @Order(4)
  @DisplayName("FetchDocuments returns multiple documents")
  void fetchDocumentsReturnsMultipleDocuments() {
    FetchDocumentsResponse response = harness.client().fetchDocuments(List.of(docId1, docId2));

    assertNotNull(response, "FetchDocuments response should not be null");
    assertEquals(2, response.getDocumentsCount(), "should return 2 documents");

    for (DocumentContent doc : response.getDocumentsList()) {
      assertTrue(doc.getFound(), "document " + doc.getDocId() + " should be found");
      assertFalse(
          doc.getContent().isEmpty(), "document " + doc.getDocId() + " should have content");
    }
  }

  @Test
  @Order(5)
  @DisplayName("FetchDocuments handles mix of found and not found")
  void fetchDocumentsHandlesMixedResults() {
    FetchDocumentsResponse response =
        harness.client().fetchDocuments(List.of(docId1, "nonexistent-doc-id", docId2));

    assertNotNull(response, "FetchDocuments response should not be null");
    assertEquals(3, response.getDocumentsCount(), "should return 3 document entries");

    int foundCount = 0;
    int notFoundCount = 0;
    for (DocumentContent doc : response.getDocumentsList()) {
      if (doc.getFound()) {
        foundCount++;
        assertFalse(doc.getContent().isEmpty(), "a found doc should have content");
      } else {
        notFoundCount++;
        assertTrue(doc.getContent().isEmpty(), "a not-found doc should have empty content");
      }
    }

    assertEquals(2, foundCount, "should find 2 documents");
    assertEquals(1, notFoundCount, "should have 1 not found");
  }

  // =========================================================================
  // FetchDocumentSlice
  // =========================================================================

  @Test
  @Order(6)
  @DisplayName("FetchDocumentSlice returns paged content with correct offsets")
  void fetchDocumentSliceReturnsPagedContent() {
    FetchDocumentSliceResponse page1 = harness.client().fetchDocumentSlice(docId1, 0, 16);
    assertNotNull(page1, "slice response should not be null");
    assertEquals(docId1, page1.getDocId(), "doc ID should match");
    assertTrue(page1.getFound(), "document should be found");
    assertFalse(page1.getContent().isEmpty(), "first page content should not be empty");
    assertEquals(
        page1.getContent().length(),
        page1.getNextOffsetChars(),
        "next_offset_chars should equal returned content length when offset=0");
    assertTrue(page1.getTruncated(), "a 16-char page of a multi-line document must be truncated");

    FetchDocumentSliceResponse page2 =
        harness.client().fetchDocumentSlice(docId1, page1.getNextOffsetChars(), 16);
    assertTrue(page2.getFound(), "second page should be found");
    assertEquals(
        page1.getNextOffsetChars() + page2.getContent().length(),
        page2.getNextOffsetChars(),
        "next_offset_chars should advance by content length");

    String combined = (page1.getContent() + page2.getContent()).toLowerCase(Locale.ROOT);
    assertTrue(
        combined.contains("quick") || combined.contains("brown"),
        "combined pages should contain expected words, got: " + combined);
    assertFalse(page1.getMetadataMap().isEmpty(), "metadata should be present");
  }

  @Test
  @Order(7)
  @DisplayName("FetchDocumentSlice returns not-found for unknown doc IDs")
  void fetchDocumentSliceReturnsNotFoundForUnknownId() {
    FetchDocumentSliceResponse response =
        harness.client().fetchDocumentSlice("nonexistent-doc-id", 0, 50);

    assertNotNull(response, "slice response should not be null");
    assertFalse(response.getFound(), "unknown doc should not be found");
    assertTrue(response.getContent().isEmpty(), "unknown doc content should be empty");
    assertFalse(response.getError().isBlank(), "unknown doc should include an error message");
  }

  // =========================================================================
  // RetrieveContext
  // =========================================================================

  @Test
  @Order(8)
  @DisplayName("RetrieveContext finds relevant content for question")
  void retrieveContextFindsRelevantContent() {
    RetrieveContextResponse response =
        harness
            .client()
            .retrieveContext("What animal jumps over the lazy dog?", Set.of(docId1, docId2), 5);

    assertNotNull(response, "RetrieveContext response should not be null");
    String context = response.getContext();

    assertFalse(context.isEmpty(), "context should not be empty");
    String lower = context.toLowerCase(Locale.ROOT);
    assertTrue(
        lower.contains("fox") || lower.contains("dog"),
        "context should contain relevant terms (fox or dog), got: " + context);
  }

  @Test
  @Order(9)
  @DisplayName("RetrieveContext finds database password from config")
  void retrieveContextFindsDatabasePassword() {
    RetrieveContextResponse response =
        harness.client().retrieveContext("What is the database password?", Set.of(docId1, docId2), 5);

    assertNotNull(response, "RetrieveContext response should not be null");
    String context = response.getContext();

    assertFalse(context.isEmpty(), "context should not be empty");
    assertTrue(
        context.contains("password") || context.contains("secretPassword123"),
        "context should contain password-related content, got: " + context);
  }

  @Test
  @Order(10)
  @DisplayName("RetrieveContext respects document ID filter")
  void retrieveContextRespectsDocIdFilter() {
    // Only the fox document, which has no password in it.
    RetrieveContextResponse response =
        harness.client().retrieveContext("What is the password?", Set.of(docId1), 5);

    assertNotNull(response, "RetrieveContext response should not be null");
    assertFalse(
        response.getContext().contains("secretPassword123"),
        "should not find secretPassword123 when the filter names only the fox document");
  }

  @Test
  @Order(11)
  @DisplayName("RetrieveContext handles an irrelevant question")
  void retrieveContextHandlesIrrelevantQuestion() {
    RetrieveContextResponse response =
        harness.client().retrieveContext("What is the capital of France?", Set.of(docId1, docId2), 5);

    // The retired test named itself "returns empty" but asserted only non-null, on the stated
    // grounds that a BM25 retriever may still return low-relevance content. That judgement is
    // carried over: the property is "does not fail", not "returns nothing". The DisplayName is
    // corrected to say what is actually asserted.
    assertNotNull(response, "RetrieveContext response should not be null");
    assertNotNull(response.getContext(), "context must be a string, even when nothing is relevant");
  }
}
