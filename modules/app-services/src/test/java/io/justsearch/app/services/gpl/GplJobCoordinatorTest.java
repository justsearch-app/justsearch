package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.quality.Strictness;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.GplJobStatus;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.ipc.HitStage;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

/**
 * Unit tests for {@link GplJobCoordinator}.
 *
 * <p>Uses Mockito to stub KnowledgeClient (concrete class) and OnlineAiService. The
 * GplTrainingTripleStore uses a real temp-dir backed instance for write verification.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GplJobCoordinator")
class GplJobCoordinatorTest {

  @TempDir Path tempDir;

  @Mock KnowledgeClient knowledgeClient;
  @Mock OnlineAiService onlineAiService;

  private GplTrainingTripleStore tripleStore;
  private GplJobCoordinator coordinator;

  @BeforeEach
  void setUp() {
    tripleStore = new GplTrainingTripleStore(tempDir);
    coordinator = new GplJobCoordinator(() -> knowledgeClient, onlineAiService, false, tripleStore);
  }

  // ========== Status lifecycle ==========

  @Test
  @DisplayName("initial status is IDLE with zero counts")
  void initialStatusIsIdle() {
    GplJobStatus status = coordinator.getStatus();
    assertEquals(GplJobStatus.Status.IDLE, status.status());
    assertEquals(0L, status.processedDocs());
    assertEquals(0L, status.totalDocs());
    assertEquals(0L, status.tripleCount());
    assertFalse(status.lastRunAt() != null, "lastRunAt should be null before first run");
  }

  @Test
  @DisplayName("runAsync() returns false when job is already RUNNING")
  void runAsyncReturnsFalseWhenAlreadyRunning() throws Exception {
    // Set up a blocking LLM call to keep the job in RUNNING state
    CountDownLatch jobStarted = new CountDownLatch(1);
    CountDownLatch releaseJob = new CountDownLatch(1);

    // One page of 1 doc
    ListAllDocumentIdsResponse page1 =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-1")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    FetchDocumentsResponse fetchResp =
        FetchDocumentsResponse.newBuilder()
            .addDocuments(
                DocumentContent.newBuilder()
                    .setDocId("doc-1")
                    .setContent("some content")
                    .setFound(true)
                    .build())
            .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page1);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-1"))).thenReturn(fetchResp);
    when(onlineAiService.isAvailable()).thenReturn(true);

    // Block inside streamChat so the job stays RUNNING
    doAnswer(
            inv -> {
              jobStarted.countDown();
              @SuppressWarnings("unchecked")
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              try {
                releaseJob.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    boolean first = coordinator.runAsync();
    assertTrue(first, "first runAsync() should return true");
    assertTrue(jobStarted.await(2, TimeUnit.SECONDS), "job should start");

    boolean second = coordinator.runAsync();
    assertFalse(second, "second runAsync() while running should return false");

    releaseJob.countDown();
    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");
  }

  // ========== Triple store integration ==========

  @Test
  @DisplayName("multi-page corpus carries the first page snapshot token")
  void multiPageCorpusCarriesSnapshotToken() throws Exception {
    var firstPage = ListAllDocumentIdsResponse.newBuilder()
        .addAllDocIds(
            java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "doc-" + i)
                .toList())
        .setTotalCount(51)
        .setSnapshotToken("snapshot-1")
        .build();
    var secondPage = ListAllDocumentIdsResponse.newBuilder()
        .addDocIds("doc-50")
        .setTotalCount(51)
        .setSnapshotToken("snapshot-1")
        .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(firstPage);
    when(knowledgeClient.listAllDocumentIds(50, 50, "snapshot-1")).thenReturn(secondPage);
    when(knowledgeClient.fetchDocuments(any())).thenReturn(FetchDocumentsResponse.getDefaultInstance());

    assertTrue(coordinator.runAsync());
    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS));

    assertEquals(GplJobStatus.Status.COMPLETED, coordinator.getStatus().status());
    verify(knowledgeClient).listAllDocumentIds(50, 50, "snapshot-1");
  }

  @Test
  @DisplayName("run() writes one triple per query per document")
  void runWritesTriplesForEachQueryPerDocument() throws Exception {
    // Three documents, two queries each → 6 triples
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-1")
            .addDocIds("doc-2")
            .addDocIds("doc-3")
            .setTotalCount(3)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(3).build();

    FetchDocumentsResponse fetchResp =
        FetchDocumentsResponse.newBuilder()
            .addDocuments(docContent("doc-1", "content about topic A"))
            .addDocuments(docContent("doc-2", "content about topic B"))
            .addDocuments(docContent("doc-3", "content about topic C"))
            .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(3, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-1", "doc-2", "doc-3"))).thenReturn(fetchResp);
    when(onlineAiService.isAvailable()).thenReturn(true);

    // LLM returns two queries per document
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("query1\nquery2");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    boolean submitted = coordinator.runAsync();
    assertTrue(submitted);

    // Wait for the job to complete (COMPLETED or FAILED)
    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    GplJobStatus status = coordinator.getStatus();
    assertEquals(GplJobStatus.Status.COMPLETED, status.status(), "job should complete: " + status.lastError());
    assertEquals(3L, status.processedDocs());
    assertEquals(6L, tripleStore.count(), "3 docs × 2 queries = 6 triples");
  }

  @Test
  @DisplayName("run() skips documents not found in the index")
  void runSkipsNotFoundDocuments() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-exists")
            .addDocIds("doc-missing")
            .setTotalCount(2)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(2).build();

    FetchDocumentsResponse fetchResp =
        FetchDocumentsResponse.newBuilder()
            .addDocuments(docContent("doc-exists", "real content"))
            .addDocuments(
                DocumentContent.newBuilder()
                    .setDocId("doc-missing")
                    .setFound(false)
                    .build())
            .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(2, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-exists", "doc-missing"))).thenReturn(fetchResp);
    when(onlineAiService.isAvailable()).thenReturn(true);

    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("some query");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    // Only the found document should produce triples
    assertEquals(1L, tripleStore.count(), "only the found document should produce a triple");
  }

  @Test
  @DisplayName("getStatus() shows correct triple count from store")
  void getStatusShowsTripleCountFromStore() throws Exception {
    // Pre-populate the store with some triples
    tripleStore.append("doc-a", "pre-existing query", 0.9f);
    tripleStore.append("doc-b", "another query", 0.7f);

    GplJobStatus status = coordinator.getStatus();
    assertEquals(GplJobStatus.Status.IDLE, status.status());
    assertEquals(2L, status.tripleCount());
  }

  // ========== Edge case: blank document content ==========

  @Test
  @DisplayName("run() skips documents with blank content — no LLM call, no triple written")
  void runSkipsDocumentsWithBlankContent() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-blank")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    FetchDocumentsResponse fetchResp =
        FetchDocumentsResponse.newBuilder()
            .addDocuments(
                DocumentContent.newBuilder()
                    .setDocId("doc-blank")
                    .setContent("   ")  // blank content
                    .setFound(true)
                    .build())
            .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-blank"))).thenReturn(fetchResp);
    when(onlineAiService.isAvailable()).thenReturn(true);

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(GplJobStatus.Status.COMPLETED, coordinator.getStatus().status());
    assertEquals(0L, tripleStore.count(), "blank content doc should produce no triples");
    // LLM should never be called for blank documents
    verify(onlineAiService, never()).streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));
  }

  // ========== Edge case: query count cap ==========

  @Test
  @DisplayName("run() caps synthetic queries at 5 per document")
  void runCapsSyntheticQueriesAtFive() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-1")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    FetchDocumentsResponse fetchResp =
        FetchDocumentsResponse.newBuilder()
            .addDocuments(docContent("doc-1", "some document content"))
            .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-1"))).thenReturn(fetchResp);
    when(onlineAiService.isAvailable()).thenReturn(true);

    // LLM returns 7 queries — safety cap should limit to 5
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("query1\nquery2\nquery3\nquery4\nquery5\nquery6\nquery7");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(GplJobStatus.Status.COMPLETED, coordinator.getStatus().status());
    assertEquals(5L, tripleStore.count(), "query cap should limit output to 5 triples per doc");
  }

  // ========== Edge case: reranker throws ==========

  @Test
  @DisplayName("run() uses default score 1.0 and continues when reranker throws")
  void runWithRerankerThrowingUsesDefaultScore() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-1")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    FetchDocumentsResponse fetchResp =
        FetchDocumentsResponse.newBuilder()
            .addDocuments(docContent("doc-1", "content for reranker test"))
            .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-1"))).thenReturn(fetchResp);
    when(onlineAiService.isAvailable()).thenReturn(true);

    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("query1");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    // 360: Remote rerank RPC throws
    doThrow(new RuntimeException("reranker unavailable"))
        .when(knowledgeClient)
        .rerank(anyString(), any(), anyLong());

    // Create coordinator with reranker enabled (but RPC will fail)
    GplJobCoordinator coordinatorWithReranker =
        new GplJobCoordinator(() -> knowledgeClient, onlineAiService, true, tripleStore);
    coordinatorWithReranker.runAsync();

    assertTrue(coordinatorWithReranker.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    // Job should complete (not fail) even when reranker throws — default score 1.0 is used
    assertEquals(
        GplJobStatus.Status.COMPLETED,
        coordinatorWithReranker.getStatus().status(),
        "job should complete even when reranker throws: "
            + coordinatorWithReranker.getStatus().lastError());
    assertEquals(1L, tripleStore.count(), "triple should be written with default score");
  }

  // ========== Edge case: LLM call fails ==========

  @Test
  @DisplayName("run() skips document and continues when LLM call fails with error")
  void runSkipsDocumentWhenLlmCallFails() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-fail")
            .addDocIds("doc-ok")
            .setTotalCount(2)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(2).build();

    FetchDocumentsResponse fetchResp =
        FetchDocumentsResponse.newBuilder()
            .addDocuments(docContent("doc-fail", "content that triggers LLM failure"))
            .addDocuments(docContent("doc-ok", "content that succeeds"))
            .build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(2, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-fail", "doc-ok"))).thenReturn(fetchResp);
    when(onlineAiService.isAvailable()).thenReturn(true);

    // First call fails via onError; second call succeeds
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<Throwable> onError = inv.getArgument(4, Consumer.class);
              onError.accept(new RuntimeException("LLM unavailable"));
              return null;
            })
        .doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("success query");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(
        GplJobStatus.Status.COMPLETED,
        coordinator.getStatus().status(),
        "job should complete even when one LLM call fails");
    assertEquals(1L, tripleStore.count(), "only the successful doc should produce a triple");
  }

  // ========== Negative sampling ==========

  @Test
  @DisplayName("negative sampling writes positive and negative triples for search results")
  void negativeSampling_writesNegativeTriplesForResults() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-src")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-src")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-src", "source document content"))
                .build());
    when(onlineAiService.isAvailable()).thenReturn(true);

    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("find source doc");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    // Search returns source doc at rank 1 and 3 negatives
    SearchResponse searchResp =
        SearchResponse.newBuilder()
            .addResults(detailResult("doc-src", Map.of("sparse", 10.0f)))
            .addResults(detailResult("doc-neg1", Map.of("sparse", 7.0f)))
            .addResults(detailResult("doc-neg2", Map.of("sparse", 5.0f)))
            .addResults(detailResult("doc-neg3", Map.of("sparse", 3.0f)))
            .build();
    when(knowledgeClient.search(any(SearchRequest.class))).thenReturn(searchResp);

    // Mock content fetch for each negative doc (called by fetchSingleDocContent)
    for (String negId : List.of("doc-neg1", "doc-neg2", "doc-neg3")) {
      when(knowledgeClient.fetchDocuments(List.of(negId)))
          .thenReturn(
              FetchDocumentsResponse.newBuilder()
                  .addDocuments(docContent(negId, "negative document content"))
                  .build());
    }

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(
        GplJobStatus.Status.COMPLETED,
        coordinator.getStatus().status(),
        "job should complete: " + coordinator.getStatus().lastError());
    // 1 positive (source doc found at rank 1) + 3 negatives = 4 triples
    assertEquals(4L, tripleStore.count(), "should write 1 positive + 3 negative triples");

    // Verify NDJSON field content: writes are in result order (source at rank 1 first)
    ObjectMapper mapper = new ObjectMapper();
    List<String> lines = Files.readAllLines(tripleStore.storeFile(), StandardCharsets.UTF_8);
    assertEquals(4, lines.size());
    JsonNode pos = mapper.readTree(lines.get(0));
    assertEquals("doc-src#0", pos.get("query_id").asText());
    assertFalse(pos.get("is_negative").asBoolean(), "source doc triple must be positive");
    assertEquals(1, pos.get("rank_position").asInt(), "source doc is at rank 1 in results");
    assertTrue(pos.get("sparse").floatValue() > 0, "positive should carry non-zero sparse score from debug scores");
    for (int i = 1; i <= 3; i++) {
      JsonNode neg = mapper.readTree(lines.get(i));
      assertEquals("doc-src#0", neg.get("query_id").asText(), "negative should share query_id with positive");
      assertTrue(neg.get("is_negative").asBoolean(), "result at rank " + (i + 1) + " should be negative");
    }
  }

  @Test
  @DisplayName("negative sampling caps negatives at MAX_NEGATIVES_PER_QUERY (5)")
  void negativeSampling_capsAtMaxNegativesPerQuery() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-src")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);

    // Mockito checks stubs in LIFO order: the specific List.of("doc-src") stub registered
    // second is checked first and wins for that input; any() falls through for everything else.
    when(knowledgeClient.fetchDocuments(any()))
        .thenAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              List<String> docIds = inv.getArgument(0);
              FetchDocumentsResponse.Builder builder = FetchDocumentsResponse.newBuilder();
              for (String id : docIds) {
                builder.addDocuments(docContent(id, "content for " + id));
              }
              return builder.build();
            });
    when(knowledgeClient.fetchDocuments(List.of("doc-src")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-src", "source document content"))
                .build());

    when(onlineAiService.isAvailable()).thenReturn(true);
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("find document");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    // Search returns 20 results, none is the source doc
    SearchResponse.Builder searchRespBuilder = SearchResponse.newBuilder();
    for (int i = 1; i <= 20; i++) {
      searchRespBuilder.addResults(detailResult("doc-neg" + i, Map.of("sparse", (float) (20 - i))));
    }
    when(knowledgeClient.search(any(SearchRequest.class))).thenReturn(searchRespBuilder.build());

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(
        GplJobStatus.Status.COMPLETED,
        coordinator.getStatus().status(),
        "job should complete: " + coordinator.getStatus().lastError());
    // Source doc not in results → 1 positive (zero features) + 5 negatives (capped) = 6 triples
    assertEquals(
        6L,
        tripleStore.count(),
        "should write 1 positive (zero features) + 5 negatives (capped at MAX_NEGATIVES_PER_QUERY)");

    // Verify NDJSON field content: 5 negatives written first (in result order), then fallback positive
    ObjectMapper mapper = new ObjectMapper();
    List<String> lines = Files.readAllLines(tripleStore.storeFile(), StandardCharsets.UTF_8);
    assertEquals(6, lines.size());
    for (int i = 0; i < 5; i++) {
      JsonNode neg = mapper.readTree(lines.get(i));
      assertTrue(neg.get("is_negative").asBoolean(), "line " + i + " should be a negative triple");
      assertEquals("doc-src#0", neg.get("query_id").asText());
    }
    JsonNode pos = mapper.readTree(lines.get(5));
    assertFalse(pos.get("is_negative").asBoolean(), "fallback line should be the positive triple");
    assertEquals(0, pos.get("rank_position").asInt(), "source not in results → rank_position=0");
  }

  @Test
  @DisplayName("negative sampling uses explicit Stage 3A pipeline and writes branch columns")
  void negativeSampling_usesStage3aPipelineAndWritesBranchColumns() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder().addDocIds("doc-src").setTotalCount(1).build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-src")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-src", "source document content"))
                .build());
    when(knowledgeClient.fetchDocuments(List.of("doc-neg1")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-neg1", "negative document content"))
                .build());
    when(onlineAiService.isAvailable()).thenReturn(true);

    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("stage three query");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    SearchResponse searchResp =
        SearchResponse.newBuilder()
            .addResults(
                detailResult(
                    "doc-src",
                    Map.ofEntries(
                        Map.entry("sparse", 9.0f),
                        Map.entry("vector", 0.8f),
                        Map.entry("splade", 0.4f),
                        Map.entry("cc", 0.95f),
                        Map.entry("whole_branch", 0.95f),
                        Map.entry("whole_branch_rank", 1.0f),
                        Map.entry("chunk_branch", 0.40f),
                        Map.entry("chunk_branch_rank", 2.0f),
                        Map.entry("branch_merge_cc", 0.86f),
                        Map.entry("branch_merge_cc_weight_whole", 0.50f),
                        Map.entry("branch_merge_cc_weight_chunk", 0.50f),
                        Map.entry("branch_merge_cc_effective_weight_whole", 0.67f),
                        Map.entry("branch_merge_cc_effective_weight_chunk", 0.33f),
                        Map.entry("branch_merge_cc_modifier_whole", 1.0f),
                        Map.entry("branch_merge_cc_modifier_chunk", 0.25f),
                        Map.entry("parent_token_count", 900f))))
            .addResults(
                detailResult(
                    "doc-neg1",
                    Map.ofEntries(
                        Map.entry("chunk_sparse", 4.0f),
                        Map.entry("chunk_vector", 0.3f),
                        Map.entry("chunk_splade", 0.2f),
                        Map.entry("chunk_cc", 0.7f),
                        Map.entry("whole_branch", 0.00f),
                        Map.entry("whole_branch_rank", 0.0f),
                        Map.entry("chunk_branch", 0.70f),
                        Map.entry("chunk_branch_rank", 1.0f),
                        Map.entry("branch_merge_cc", 0.70f),
                        Map.entry("branch_merge_cc_weight_whole", 0.50f),
                        Map.entry("branch_merge_cc_weight_chunk", 0.50f),
                        Map.entry("branch_merge_cc_effective_weight_whole", 0.00f),
                        Map.entry("branch_merge_cc_effective_weight_chunk", 1.00f),
                        Map.entry("branch_merge_cc_modifier_whole", 1.0f),
                        Map.entry("branch_merge_cc_modifier_chunk", 0.62f),
                        Map.entry("chunk_parent_token_count", 2500f))))
            .build();
    when(knowledgeClient.search(any(SearchRequest.class))).thenReturn(searchResp);

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");
    assertEquals(GplJobStatus.Status.COMPLETED, coordinator.getStatus().status());

    ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(knowledgeClient).search(requestCaptor.capture());
    SearchRequest request = requestCaptor.getValue();
    assertTrue(request.getPipeline().getSparseEnabled());
    assertTrue(request.getPipeline().getDenseEnabled());
    assertTrue(request.getPipeline().getSpladeEnabled());
    assertEquals("cc", request.getPipeline().getFusionAlgorithm());
    assertFalse(request.getPipeline().getLambdamartEnabled());
    assertFalse(request.getPipeline().getCrossEncoderEnabled());
    assertFalse(request.getPipeline().getExpansionEnabled());
    // Tempdoc 549 Phase D2: GPL requests the numeric detail tier via include_detail (replacing
    // the deprecated debug flag) so its LTR feature collection sees the per-hit HitStage.detail.
    assertTrue(request.getIncludeDetail());

    ObjectMapper mapper = new ObjectMapper();
    List<String> lines = Files.readAllLines(tripleStore.storeFile(), StandardCharsets.UTF_8);
    assertEquals(2, lines.size(), "1 positive + 1 negative expected");

    JsonNode positive = mapper.readTree(lines.get(0));
    assertEquals(9.0f, positive.get("whole_sparse").floatValue(), 0.001f);
    assertEquals(0.8f, positive.get("whole_vector").floatValue(), 0.001f);
    assertEquals(0.4f, positive.get("whole_splade").floatValue(), 0.001f);
    assertEquals(0.95f, positive.get("whole_cc").floatValue(), 0.001f);
    assertEquals(0.95f, positive.get("branch_whole").floatValue(), 0.001f);
    assertEquals(0.40f, positive.get("branch_chunk").floatValue(), 0.001f);
    assertEquals(0.86f, positive.get("branch_cc").floatValue(), 0.001f);
    assertTrue(positive.get("branch_present_whole").booleanValue());
    assertTrue(positive.get("branch_present_chunk").booleanValue());
    assertEquals(900L, positive.get("parent_token_count").longValue());

    JsonNode negative = mapper.readTree(lines.get(1));
    assertEquals(4.0f, negative.get("chunk_sparse").floatValue(), 0.001f);
    assertEquals(0.3f, negative.get("chunk_vector").floatValue(), 0.001f);
    assertEquals(0.2f, negative.get("chunk_splade").floatValue(), 0.001f);
    assertEquals(0.7f, negative.get("chunk_cc").floatValue(), 0.001f);
    assertFalse(negative.get("branch_present_whole").booleanValue());
    assertTrue(negative.get("branch_present_chunk").booleanValue());
    assertEquals(2500L, negative.get("parent_token_count").longValue());

    Path reportPath = GplStage3aAnalysisReport.reportPathFor(tripleStore.storeFile());
    assertTrue(
        Files.exists(reportPath),
        "Stage 3A analysis report should be written after a successful run");
    Path stage3bReportPath = GplStage3bBranchFusionReport.reportPathFor(tripleStore.storeFile());
    assertTrue(
        Files.exists(stage3bReportPath),
        "Stage 3B branch report should be written after a successful run");
  }

  @Test
  @DisplayName("negative sampling handles search exception and still writes positive triple")
  void negativeSampling_handlesSearchException() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-src")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-src")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-src", "source document content"))
                .build());
    when(onlineAiService.isAvailable()).thenReturn(true);
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("find document");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    // Search throws — job should continue and write positive with zero features
    when(knowledgeClient.search(any(SearchRequest.class)))
        .thenThrow(new RuntimeException("search unavailable"));

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(
        GplJobStatus.Status.COMPLETED,
        coordinator.getStatus().status(),
        "job should complete even when search throws: " + coordinator.getStatus().lastError());
    // Search failed → positive triple still written with zero features (no negatives)
    assertEquals(
        1L,
        tripleStore.count(),
        "positive triple should still be written despite search failure");

    // Verify NDJSON field content: positive with zero features, no negatives
    ObjectMapper mapper = new ObjectMapper();
    List<String> lines = Files.readAllLines(tripleStore.storeFile(), StandardCharsets.UTF_8);
    assertEquals(1, lines.size());
    JsonNode pos = mapper.readTree(lines.get(0));
    assertFalse(pos.get("is_negative").asBoolean(), "fallback triple should be positive");
    assertEquals(0, pos.get("rank_position").asInt(), "search failed → rank_position=0");
    assertEquals("doc-src#0", pos.get("query_id").asText());
    assertEquals(0.0f, pos.get("sparse").floatValue(), "search failed → sparse=0.0");
  }

  @Test
  @DisplayName("negative sampling aborts on transient worker search outage")
  void negativeSampling_abortsOnTransientWorkerSearchOutage() throws Exception {
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-src")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-src")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-src", "source document content"))
                .build());
    when(onlineAiService.isAvailable()).thenReturn(true);
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("find document");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    // Lane F stage A item A14: this used to inject an io.grpc StatusRuntimeException, which the
    // in-process KnowledgeClient has been unable to throw since item A6 -- so the test was green
    // against a failure shape production could not produce. KnowledgeClientException.Status is the
    // 1:1 successor vocabulary (KnowledgeClientException.java:37-58) and is what
    // EngineKnowledgeClient actually raises, so the abort branch is now exercised for the real
    // reason.
    when(knowledgeClient.search(any(SearchRequest.class)))
        .thenThrow(
            new KnowledgeClientException(
                KnowledgeClientException.Status.DEADLINE_EXCEEDED, "worker restarting"));

    coordinator.runAsync();

    assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");
    assertEquals(GplJobStatus.Status.FAILED, coordinator.getStatus().status());
    assertEquals(0L, tripleStore.count(), "transient worker outage must not write fallback triples");
  }

  // ========== Q8 verification: cross-encoder resolve-once ==========

  @Test
  @DisplayName("reranker scores flow through to positive and negative triples (not 1.0)")
  void rerankerScoresFlowThroughToTriples() throws Exception {
    // 360: Mock remote rerank RPC returns a real score (0.75), not the 1.0 fallback
    when(knowledgeClient.rerank(anyString(), any(), anyLong()))
        .thenReturn(
            RerankResponse.newBuilder()
                .addSortedIndices(0).addScores(0.75f)
                .setSkipped(false).setElapsedMs(5)
                .build());

    GplJobCoordinator coordWithReranker =
        new GplJobCoordinator(() -> knowledgeClient, onlineAiService, true, tripleStore);

    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-src")
            .setTotalCount(1)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-src")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-src", "source document content"))
                .build());
    when(onlineAiService.isAvailable()).thenReturn(true);

    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("find source doc");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    // Search returns source doc + 1 negative
    SearchResponse searchResp =
        SearchResponse.newBuilder()
            .addResults(detailResult("doc-src", Map.of("sparse", 10.0f)))
            .addResults(detailResult("doc-neg1", Map.of("sparse", 5.0f)))
            .build();
    when(knowledgeClient.search(any(SearchRequest.class))).thenReturn(searchResp);
    when(knowledgeClient.fetchDocuments(List.of("doc-neg1")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-neg1", "negative document content"))
                .build());

    coordWithReranker.runAsync();

    assertTrue(coordWithReranker.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(
        GplJobStatus.Status.COMPLETED,
        coordWithReranker.getStatus().status(),
        "job should complete: " + coordWithReranker.getStatus().lastError());
    assertEquals(2L, tripleStore.count(), "1 positive + 1 negative = 2 triples");

    // Parse NDJSON and verify scores are 0.75, NOT the 1.0 fallback
    ObjectMapper mapper = new ObjectMapper();
    List<String> lines = Files.readAllLines(tripleStore.storeFile(), StandardCharsets.UTF_8);
    for (String line : lines) {
      JsonNode triple = mapper.readTree(line);
      assertEquals(
          0.75f,
          triple.get("score").floatValue(),
          0.001f,
          "score should be from reranker (0.75), not fallback (1.0): " + line);
    }
  }

  @Test
  @DisplayName("remote rerank RPC is called for each scoreQueryDoc invocation (360)")
  void remoteRerankCalledPerScoreQueryDoc() throws Exception {
    when(knowledgeClient.rerank(anyString(), any(), anyLong()))
        .thenReturn(
            RerankResponse.newBuilder()
                .addSortedIndices(0).addScores(0.8f)
                .setSkipped(false).setElapsedMs(3)
                .build());

    GplJobCoordinator coordWithReranker =
        new GplJobCoordinator(() -> knowledgeClient, onlineAiService, true, tripleStore);

    // 2 docs, 1 query each — produces 2 positive scoreQueryDoc calls minimum
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-1")
            .addDocIds("doc-2")
            .setTotalCount(2)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(2).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(2, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-1", "doc-2")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-1", "content one"))
                .addDocuments(docContent("doc-2", "content two"))
                .build());
    when(onlineAiService.isAvailable()).thenReturn(true);

    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("query");
              onComplete.accept("");
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    coordWithReranker.runAsync();

    assertTrue(coordWithReranker.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(
        GplJobStatus.Status.COMPLETED,
        coordWithReranker.getStatus().status(),
        "job should complete: " + coordWithReranker.getStatus().lastError());

    // Remote rerank should have been called at least twice (once per doc's positive score)
    verify(knowledgeClient, atLeast(2)).rerank(anyString(), any(), anyLong());
  }

  @Test
  @DisplayName("WARN logged when reranker is unavailable (rerankerAvailable=false)")
  void warnLoggedWhenRerankerUnavailable() throws Exception {
    Logger gplLogger =
        (Logger) LoggerFactory.getLogger(GplJobCoordinator.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    gplLogger.addAppender(appender);

    try {
      // coordinator uses rerankerAvailable=false (from setUp)
      ListAllDocumentIdsResponse page =
          ListAllDocumentIdsResponse.newBuilder()
              .addDocIds("doc-1")
              .setTotalCount(1)
              .build();
      ListAllDocumentIdsResponse emptyPage =
          ListAllDocumentIdsResponse.newBuilder().setTotalCount(1).build();

      when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
      when(knowledgeClient.listAllDocumentIds(1, 50)).thenReturn(emptyPage);
      when(knowledgeClient.fetchDocuments(List.of("doc-1")))
          .thenReturn(
              FetchDocumentsResponse.newBuilder()
                  .addDocuments(docContent("doc-1", "some content"))
                  .build());
      when(onlineAiService.isAvailable()).thenReturn(true);

      doAnswer(
              inv -> {
                @SuppressWarnings("unchecked")
                Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
                Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
                onChunk.accept("query");
                onComplete.accept("");
                return null;
              })
          .when(onlineAiService)
          .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

      coordinator.runAsync();

      assertTrue(coordinator.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getLevel() == Level.WARN
                          && e.getFormattedMessage()
                              .contains("cross-encoder reranker unavailable")),
          "expected WARN about unavailable cross-encoder reranker");
    } finally {
      gplLogger.detachAppender(appender);
      appender.stop();
    }
  }

  // ========== E2E-4: AI timeout sets FAILED, callback not called ==========

  @Test
  @DisplayName("E2E-4: AI timeout sets FAILED status and does NOT call onJobCompleted")
  void aiTimeoutSetsFailedAndSkipsCallback() throws Exception {
    // Track whether the callback fires
    java.util.concurrent.atomic.AtomicBoolean callbackFired =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    // Flip to false after the first doc's LLM call completes.
    java.util.concurrent.atomic.AtomicBoolean aiAvailable =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    // Capture the job thread so we can interrupt it to speed up waitForAiAvailability.
    java.util.concurrent.atomic.AtomicReference<Thread> jobThread =
        new java.util.concurrent.atomic.AtomicReference<>();

    // 2 docs: AI available for doc-1, unavailable for doc-2
    ListAllDocumentIdsResponse page =
        ListAllDocumentIdsResponse.newBuilder()
            .addDocIds("doc-1")
            .addDocIds("doc-2")
            .setTotalCount(2)
            .build();
    ListAllDocumentIdsResponse emptyPage =
        ListAllDocumentIdsResponse.newBuilder().setTotalCount(2).build();

    when(knowledgeClient.listAllDocumentIds(0, 50)).thenReturn(page);
    when(knowledgeClient.listAllDocumentIds(2, 50)).thenReturn(emptyPage);
    when(knowledgeClient.fetchDocuments(List.of("doc-1", "doc-2")))
        .thenReturn(
            FetchDocumentsResponse.newBuilder()
                .addDocuments(docContent("doc-1", "content one"))
                .addDocuments(docContent("doc-2", "content two"))
                .build());

    CountDownLatch threadCaptured = new CountDownLatch(1);
    // isAvailable: delegates to AtomicBoolean. When false, capture thread for interruption.
    when(onlineAiService.isAvailable())
        .thenAnswer(
            inv -> {
              boolean avail = aiAvailable.get();
              if (!avail) {
                if (jobThread.compareAndSet(null, Thread.currentThread())) {
                  threadCaptured.countDown();
                }
              }
              return avail;
            });

    // LLM streamChat succeeds for doc-1, then flips AI to unavailable.
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<String> onChunk = inv.getArgument(2, Consumer.class);
              Consumer<String> onComplete = inv.getArgument(3, Consumer.class);
              onChunk.accept("query for doc-1");
              onComplete.accept("");
              // After doc-1 completes, AI becomes unavailable.
              aiAvailable.set(false);
              return null;
            })
        .when(onlineAiService)
        .streamChat(any(), anyInt(), any(), any(), any(), any(SamplingParams.class));

    GplJobCoordinator coordWithCallback =
        new GplJobCoordinator(
            () -> knowledgeClient,
            onlineAiService,
            false,
            tripleStore,
            () -> callbackFired.set(true));

    coordWithCallback.runAsync();

    // Wait for the job to reach the AI-unavailable state, then interrupt
    // the job thread to break the 120s backoff loop quickly.
    assertTrue(threadCaptured.await(10, TimeUnit.SECONDS), "job thread not captured");
    Thread captured = jobThread.get();
    assertNotNull(captured, "should have captured the job thread");
    captured.interrupt();

    // Wait for the job to finish
    assertTrue(coordWithCallback.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");

    assertEquals(
        GplJobStatus.Status.FAILED,
        coordWithCallback.getStatus().status(),
        "job should be FAILED after AI timeout, not COMPLETED");
    assertFalse(
        callbackFired.get(), "onJobCompleted callback must NOT be called on AI timeout");
    assertEquals(
        1,
        coordWithCallback.getStatus().processedDocs(),
        "only doc-1 should be processed");
  }

  // ========== Helpers ==========

  private static DocumentContent docContent(String docId, String content) {
    return DocumentContent.newBuilder()
        .setDocId(docId)
        .setContent(content)
        .setFound(true)
        .build();
  }

  /**
   * Tempdoc 549 Phase E1: GPL reads the per-hit numeric tier as {@code union(HitStage.detail)}
   * (the retired {@code debug_scores} map's successor). Pack a hit's detail into one stage so the
   * feature-collection path sees exactly these keys.
   */
  private static SearchResult detailResult(String id, Map<String, Float> detail) {
    return SearchResult.newBuilder()
        .setId(id)
        .addTrace(HitStage.newBuilder().setId("fusion").putAllDetail(detail).build())
        .build();
  }
}
