/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.holdBackground;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec D (item 5a) test R1 and its variants: a RAG retrieval whose query embedding cannot answer
 * inside the retrieval's semantic sub-budget answers with BM25 context and the reason {@code
 * SEMANTIC_BUDGET_EXCEEDED}, inside its request deadline, instead of failing with
 * DEADLINE_EXCEEDED (S1, S2).
 *
 * <p>Real seam: every check enters through {@link WorkerSearchService#retrieveContext}, the
 * production entry point, over a real Lucene index with the production RAG pipeline. Only the
 * native encoder is replaced: the contended cases hold a real {@code NativeSessionHandle}'s device
 * permit with a BACKGROUND lease, and the embedder acquires through the same handle.
 *
 * <p>Uses only API that exists before the change, so each check runs on the before-state.
 */
final class WorkerSearchServiceRagBudgetTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** The request deadline, shortened so the suite stays fast. */
  static final Duration REQUEST_BUDGET = Duration.ofSeconds(3);

  private final Map<String, String> previousProperties = new HashMap<>();
  private final CountDownLatch releaseOnExit = new CountDownLatch(1);
  private RunningRuntime lifecycle;

  @Test
  void internalRerankReleasesRetrievalWhileNativeRunIsHeld() throws Exception {
    lifecycle = openIndexWithMode(previousProperties, testLuceneExecutors(), "bm25", "position",
        "Hello first passage", "Hello second passage", "Hello third passage");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var nativeSession = org.mockito.Mockito.mock(ai.onnxruntime.OrtSession.class);
    org.mockito.Mockito.when(nativeSession.run(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(call -> {
      entered.countDown();
      release.await(10, java.util.concurrent.TimeUnit.SECONDS);
      return org.mockito.Mockito.mock(ai.onnxruntime.OrtSession.Result.class);
    });
    try (var handle = gpuHandle("rag-rerank", nativeSession)) {
      var tokenizer = org.mockito.Mockito.mock(io.justsearch.reranker.RerankerTokenizer.class);
      org.mockito.Mockito.when(tokenizer.encodePairs(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.any(String[].class))).thenReturn(
          new io.justsearch.reranker.RerankerTokenizer.EncodedBatch(
              new long[][] {{1}, {1}, {1}}, new long[][] {{1}, {1}, {1}},
              new long[][] {{0}, {0}, {0}}, 3, 1, 0, 3));
      var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.INSTANCE);
      service.setSearchReranker(new io.justsearch.reranker.CrossEncoderReranker(handle,
          new io.justsearch.reranker.RerankerShape(8, false), tokenizer));
      service.setChunkRerankerConfig(new io.justsearch.reranker.RerankerConfig.ChunkRerankerConfig(
          true, Path.of("fixture.onnx"), 10, 10, 300, 2, 8, true, 0,
          io.justsearch.reranker.RerankerConfig.RerankOrder.BEFORE_DIVERSIFY));
      var result = new java.util.concurrent.CompletableFuture<RetrieveContextResponse>();
      Thread caller = new Thread(() -> {
        try {
          result.complete(service.retrieveContext(question("Hello"), foreground(Duration.ofSeconds(2))));
        } catch (Throwable failure) {
          result.completeExceptionally(failure);
        }
      });
      try {
        caller.start();
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS), "internal rerank entered native inference");
        // Native execution is bounded by the retrieval's two-second request, not the 300ms pre-check.
        caller.join(2_500);
        assertTrue(result.isDone(), "internal rerank retained retrieval's caller");
        assertTrue(assertDoesNotThrow(() -> result.get()).getContext().contains("Hello"));
      } finally {
        release.countDown();
        caller.join(5_000);
      }
    }
  }

  @Test
  void emptyKeywordFallbackRetainsSemanticLoadingReason() throws Exception {
    lifecycle = openIndex("position", "Hello world");
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.starting());
    service.setModelReadyLatchSupplier(() -> releaseOnExit);
    var response = service.retrieveContext(question("unmatchedquasar"), foreground(REQUEST_BUDGET));
    assertEquals("", response.getContext());
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("loading", response.getRetrievalModeCause());
  }

  @Test
  void bm25MmrReleasesBlockedQuestionEncode() throws Exception {
    blockedMmrReturnsContext(false);
  }

  @Test
  void bm25MmrReleasesBlockedPassageEncode() throws Exception {
    blockedMmrReturnsContext(true);
  }

  private void blockedMmrReturnsContext(boolean passage) throws Exception {
    lifecycle = openIndexWithMode(previousProperties, testLuceneExecutors(), "bm25", "mmr",
        "Hello first passage", "Hello second passage", "Hello third passage");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var nativeSession = org.mockito.Mockito.mock(ai.onnxruntime.OrtSession.class);
    org.mockito.Mockito.when(nativeSession.run(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(call -> {
      entered.countDown();
      release.await(10, java.util.concurrent.TimeUnit.SECONDS);
      return org.mockito.Mockito.mock(ai.onnxruntime.OrtSession.Result.class);
    });
    try (var handle = gpuHandle("bm25-mmr", nativeSession)) {
      var provider = org.mockito.Mockito.mock(EmbeddingProvider.class);
      org.mockito.Mockito.when(provider.availability()).thenReturn(EmbeddingProvider.Availability.READY);
      org.mockito.stubbing.Answer<float[]> encode = call -> {
        InferenceRequest authority = call.getArgument(1);
        try (var lease = handle.acquire(SessionAcquisitionRequest.from(authority));
            var ignored = lease.run(Map.of())) {
          return new float[] {1f, 0f, 0f, 0f};
        }
      };
      if (passage) {
        org.mockito.Mockito.when(provider.embedQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any())).thenReturn(new float[] {1f, 0f, 0f, 0f});
      } else {
        org.mockito.Mockito.when(provider.embedQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any())).thenAnswer(encode);
      }
      org.mockito.Mockito.when(provider.embedDocument(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.any())).thenAnswer(encode);
      var service = new WorkerSearchService(lifecycle, provider);
      var result = new java.util.concurrent.CompletableFuture<RetrieveContextResponse>();
      Thread caller = new Thread(() -> {
        try {
          result.complete(service.retrieveContext(question("Hello").toBuilder().setTopK(1).build(),
              foreground(Duration.ofSeconds(2))));
        } catch (Throwable failure) {
          result.completeExceptionally(failure);
        }
      });
      try {
        caller.start();
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS), "MMR entered native encode");
        caller.join(1_800);
        assertTrue(result.isDone(), "BM25 MMR retained its caller in native inference");
        var response = assertDoesNotThrow(() -> result.get());
        assertTrue(response.getContext().contains("Hello"));
      } finally {
        release.countDown();
        caller.join(5_000);
      }
    }
  }

  @AfterEach
  void closeIndex() throws Exception {
    releaseOnExit.countDown();
    if (lifecycle != null) lifecycle.close();
    restoreProperties(previousProperties);
  }

  @Test
  // R1: a held embedding device gives BM25 context with the reason, not a deadline failure
  void contendedQueryEmbeddingGivesBm25ContextWithTheReason() throws Exception {
    lifecycle = openIndex("position", "Hello world from the retrieval budget test");
    try (SessionHandle embeddingHandle = gpuHandle("r1-embedding");
        SessionHandle.Lease _ = holdBackground(embeddingHandle)) {
      var embeddings = new HandleBackedEmbeddings(embeddingHandle, false);
      var service = new WorkerSearchService(lifecycle, embeddings);

      long start = System.nanoTime();
      CallContext call = foreground(REQUEST_BUDGET);
      RetrieveContextResponse response =
          assertDoesNotThrow(() -> service.retrieveContext(question("Hello"), call));

      assertTrue(System.nanoTime() - start < REQUEST_BUDGET.toNanos(), "answered after D");
      assertEquals(1, embeddings.queryCalls.get(), "the query embedding tried its encoder");
      assertBm25Fallback(response);
    }
  }

  @Test
  // R1b: models still loading gives BM25 context with the reason within the deadline
  void modelsLoadingGivesBm25ContextWithinTheDeadline() throws Exception {
    lifecycle = openIndex("position", "Hello world from the retrieval budget test");
    var service = new WorkerSearchService(lifecycle, NoOpEmbeddingProvider.starting());
    service.setModelReadyLatchSupplier(() -> releaseOnExit);

    CallContext call = foreground(REQUEST_BUDGET);
    RetrieveContextResponse response =
        assertTimeoutPreemptively(
            REQUEST_BUDGET, () -> service.retrieveContext(question("Hello"), call));

    assertBm25Fallback(response);
  }

  @Test
  // R1c: MMR whose chunk embeddings are held stops at the semantic deadline and answers
  void mmrWithHeldDocumentEmbeddingsAnswersWithinTheDeadline() throws Exception {
    lifecycle =
        openIndex(
            "mmr",
            "Hello world, first passage about budgets",
            "Hello world, second passage about deadlines",
            "Hello world, third passage about fallbacks");
    try (SessionHandle embeddingHandle = gpuHandle("r1c-embedding");
        SessionHandle.Lease _ = holdBackground(embeddingHandle)) {
      // The query embedding answers; only the per-candidate MMR embeddings wait for the device.
      var embeddings = new HandleBackedEmbeddings(embeddingHandle, true);
      var service = new WorkerSearchService(lifecycle, embeddings);
      RetrieveContextRequest request =
          RetrieveContextRequest.newBuilder().setQuestion("Hello").setTopK(1).build();

      long start = System.nanoTime();
      CallContext call = foreground(REQUEST_BUDGET);
      RetrieveContextResponse response =
          assertDoesNotThrow(() -> service.retrieveContext(request, call));

      assertTrue(System.nanoTime() - start < REQUEST_BUDGET.toNanos(), "answered after D");
      assertTrue(embeddings.documentCalls.get() >= 1, "MMR tried to embed a candidate");
      assertTrue(response.getChunksCount() >= 1, "the retrieval returned context");
      assertTrue(response.getContext().contains("Hello"), response.getContext());
    }
  }

  @ParameterizedTest
  @CsvSource({"null,0", "null,1", "empty,2", "throw,1", "nan,1", "dimension,1"})
  void partialMmrEmbeddingsKeepAFullWorkerContext(String failure, int successes) throws Exception {
    String[] contents = {"Hello first passage", "Hello second passage", "Hello third passage",
        "Hello fourth passage", "Hello fifth passage", "Hello sixth passage"};
    lifecycle = openIndexWithMode(previousProperties, testLuceneExecutors(), "bm25", "mmr", contents);
    var provider = readyControlledProvider();
    var documentCalls = new AtomicInteger();
    when(provider.embedDocument(anyString(), any())).thenAnswer(call -> {
      if (documentCalls.getAndIncrement() < successes) return new float[] {1, 0, 0, 0};
      return switch (failure) {
        case "null" -> null;
        case "empty" -> new float[0];
        case "throw" -> throw new IllegalStateException("ordinary document encode failure");
        case "nan" -> new float[] {Float.NaN, 0, 0, 0};
        case "dimension" -> new float[] {1, 0};
        default -> throw new AssertionError(failure);
      };
    });
    var service = new WorkerSearchService(lifecycle, provider);
    var request = question("Hello").toBuilder().setTopK(3).setMaxContextTokens(10_000).build();
    var response = assertDoesNotThrow(() -> service.retrieveContext(request, foreground(REQUEST_BUDGET)));

    assertEquals("BM25", response.getRetrievalMode());
    assertEquals("BM25_CONFIGURED", response.getRetrievalModeReason());
    assertTrue(response.getUsedChunks());
    assertEquals(6, response.getChunksFound(), "the real union supplies all six eligible passages");
    assertEquals(3, response.getChunksCount(), "embedding failure alone cannot underfill context");
    assertEquals(3, response.getQuality().getChunksIncluded());
    assertEquals(successes + 1, documentCalls.get(), "first failure ends semantic selection");
    verify(provider, times(1)).embedQuery(anyString(), any());
    assertAlignedLiteralSections(response);
    for (var chunk : response.getChunksList()) {
      int sourceIndex = Integer.parseInt(chunk.getParentDocId().substring("doc-".length())) - 1;
      assertEquals(contents[sourceIndex].substring(chunk.getStartChar(), chunk.getEndChar()),
          chunk.getExcerpt());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "empty", "throw", "valid"})
  void denseQueryFailureAllowsOnlyTheExistingOneMmrRetry(String firstResult) throws Exception {
    lifecycle = openIndex("mmr", "Hello first passage", "Hello second passage", "Hello third passage");
    var provider = readyControlledProvider();
    var queryCalls = new AtomicInteger();
    when(provider.embedQuery(anyString(), any())).thenAnswer(call -> {
      InferenceRequest authority = call.getArgument(1);
      assertFalse(authority.mayCreateSessions());
      if (queryCalls.incrementAndGet() == 1) {
        switch (firstResult) {
          case "null": return null;
          case "empty": return new float[0];
          case "throw": throw new IllegalStateException("ordinary dense encode failure");
          case "valid": break;
          default: throw new AssertionError(firstResult);
        }
      }
      return new float[] {1, 0, 0, 0};
    });
    var service = new WorkerSearchService(lifecycle, provider);
    var response = service.retrieveContext(question("Hello").toBuilder().setTopK(2).build(),
        foreground(REQUEST_BUDGET));
    assertEquals(2, response.getChunksCount());
    assertEquals(firstResult.equals("valid") ? 1 : 2, queryCalls.get());
    verify(provider, times(3)).embedDocument(anyString(), any());
    assertAlignedLiteralSections(response);
  }

  @ParameterizedTest
  @ValueSource(strings = {"query", "document"})
  void workerParentCheckStillObservesCancellationAfterNormallyReturningProviders(String cancelAt)
      throws Exception {
    lifecycle = openIndexWithMode(previousProperties, testLuceneExecutors(), "bm25", "mmr",
        "Hello first passage", "Hello second passage", "Hello third passage");
    var cancelled = new AtomicBoolean();
    var provider = readyControlledProvider();
    when(provider.embedQuery(anyString(), any())).thenAnswer(call -> {
      if (cancelAt.equals("query")) cancelled.set(true);
      return new float[] {1, 0, 0, 0};
    });
    when(provider.embedDocument(anyString(), any())).thenAnswer(call -> {
      if (cancelAt.equals("document")) cancelled.set(true);
      return new float[] {1, 0, 0, 0};
    });
    var base = foreground(REQUEST_BUDGET);
    var call = new CallContext(null, null, cancelled::get, base.engineContext(), base.provenance(),
        base.childLifetime(), base.deadlineNanos());
    var service = new WorkerSearchService(lifecycle, provider);
    var failure = assertThrows(CancellationException.class, () -> service.retrieveContext(
        question("Hello").toBuilder().setTopK(2).build(), call));
    assertEquals("inference cancelled", failure.getMessage());
    verify(provider, times(1)).embedQuery(anyString(), any());
    verify(provider, times(3)).embedDocument(anyString(), any());
    // This is a worker-boundary preservation check. Outward fallback translation is held separately.
  }

  @Test
  void realAndSameParentSyntheticPassagesKeepTheirSpansThroughMmrAndAssembly() throws Exception {
    String syntheticText = "Hello synthetic sentence. ".repeat(300);
    lifecycle = openIndexWithMode(previousProperties, testLuceneExecutors(), "bm25", "mmr", syntheticText);
    String first = "Hello first real passage";
    String second = "Hello second real passage";
    String realText = first + "\n" + second;
    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "real-parent", SchemaFields.DOC_UID, "real-parent#0",
        SchemaFields.PATH, "real-parent", SchemaFields.CONTENT, realText)));
    for (int index = 0; index < 2; index++) {
      String text = index == 0 ? first : second;
      int start = realText.indexOf(text);
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.ofEntries(
          Map.entry(SchemaFields.DOC_ID, "real-chunk-" + index),
          Map.entry(SchemaFields.DOC_UID, "real-chunk-" + index + "#0"),
          Map.entry(SchemaFields.PATH, "real-parent"),
          Map.entry(SchemaFields.PARENT_DOC_ID, "real-parent"),
          Map.entry(SchemaFields.IS_CHUNK, "true"),
          Map.entry(SchemaFields.CHUNK_INDEX, Integer.toString(index)),
          Map.entry(SchemaFields.CHUNK_TOTAL, "2"),
          Map.entry(SchemaFields.CHUNK_CONTENT, text),
          Map.entry(SchemaFields.CHUNK_START_CHAR, Integer.toString(start)),
          Map.entry(SchemaFields.CHUNK_END_CHAR, Integer.toString(start + text.length())),
          Map.entry(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(realText)))));
    }
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    var provider = readyControlledProvider();
    var service = new WorkerSearchService(lifecycle, provider);
    var response = service.retrieveContext(question("Hello").toBuilder().setTopK(4)
        .setMaxContextTokens(10_000).build(), foreground(REQUEST_BUDGET));

    assertEquals(4, response.getChunksCount());
    assertTrue(response.getChunksList().stream().anyMatch(c -> c.getParentDocId().equals("real-parent")));
    assertTrue(response.getChunksList().stream().filter(c -> c.getParentDocId().equals("doc-1")).count() >= 2,
        "distinct synthetic passages of one parent both reach context");
    assertEquals(4, response.getChunksList().stream()
        .map(c -> c.getParentDocId() + ":" + c.getChunkIndex() + ":" + c.getStartChar() + ":" + c.getEndChar())
        .distinct().count());
    assertAlignedLiteralSections(response);
    for (int index = 0; index < response.getChunksCount(); index++) {
      var chunk = response.getChunks(index);
      String parent = chunk.getParentDocId().equals("doc-1") ? syntheticText : realText;
      String literal = parent.substring(chunk.getStartChar(), chunk.getEndChar());
      assertEquals(literal, response.getSections(index).getContent());
      assertFalse(response.getSections(index).getTruncated(), "the fixture has room for each literal span");
      String preview = chunk.getExcerpt();
      String previewBody = literal.length() > 240 ? preview.substring(0, preview.length() - 3) : preview;
      assertTrue(literal.startsWith(previewBody), "the preview body comes from the exact parent span");
      assertFalse(previewBody.isBlank());
      assertTrue(previewBody.length() <= 240, "the literal body keeps its existing character bound");
      assertTrue(preview.length() <= 243, "a truncated preview adds three ellipsis characters");
    }
    verify(provider, times(1)).embedQuery(anyString(), any());
    verify(provider, org.mockito.Mockito.atLeast(5)).embedDocument(anyString(), any());
  }

  private static EmbeddingProvider readyControlledProvider() {
    var provider = mock(EmbeddingProvider.class);
    when(provider.availability()).thenReturn(EmbeddingProvider.Availability.READY);
    when(provider.dimension()).thenReturn(4);
    when(provider.embedQuery(anyString(), any())).thenReturn(new float[] {1, 0, 0, 0});
    when(provider.embedDocument(anyString(), any())).thenReturn(new float[] {1, 0, 0, 0});
    return provider;
  }

  private static void assertAlignedLiteralSections(RetrieveContextResponse response) {
    assertEquals(response.getChunksCount(), response.getSectionsCount());
    String[] rendered = response.getContext().split(ContextBudgeter.SECTION_SEPARATOR);
    assertEquals(response.getSectionsCount(), rendered.length);
    for (int i = 0; i < response.getSectionsCount(); i++) {
      var section = response.getSections(i);
      assertEquals(i, section.getSectionIndex());
      assertEquals(i, section.getChunkIndex());
      String expectedPreview = section.getContent();
      if (expectedPreview.length() > 240) {
        // These worded fixtures have a space within the producer's 40-character lookback.
        // The ellipsis is a display marker, separate from the literal prefix and its 240-char cap.
        int boundary = expectedPreview.lastIndexOf(' ', 240);
        assertTrue(boundary >= 200, "the fixture supplies a word boundary within the lookback");
        expectedPreview = expectedPreview.substring(0, boundary).stripTrailing() + "...";
      }
      assertFalse(response.getChunks(i).getExcerpt().isBlank());
      assertEquals(expectedPreview, response.getChunks(i).getExcerpt(),
          "the citation is the exact literal section prefix with its producer-owned ellipsis");
      assertEquals(ContextBudgeter.sectionHeader(i + 1, section.getSourceLabel()) + section.getContent(),
          rendered[i]);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------------

  private static void assertBm25Fallback(RetrieveContextResponse response) {
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", response.getRetrievalModeReason());
    assertEquals("BM25", response.getRetrievalMode());
    assertTrue(response.getChunksCount() >= 1, "BM25 context remains available");
    assertTrue(response.getContext().contains("Hello"), response.getContext());
  }

  static RetrieveContextRequest question(String text) {
    return RetrieveContextRequest.newBuilder().setQuestion(text).setTopK(5).build();
  }

  private RunningRuntime openIndex(String diversifyMode, String... contents) throws Exception {
    return openIndex(previousProperties, testLuceneExecutors(), diversifyMode, contents);
  }

  static void restoreProperties(Map<String, String> saved) {
    saved.forEach(
        (key, value) -> {
          if (value == null) System.clearProperty(key);
          else System.setProperty(key, value);
        });
  }

  private static void setProperty(Map<String, String> saved, String key, String value) {
    saved.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  /**
   * An index of whole (unchunked) documents, which the RAG union leg serves as synthesized chunks,
   * with the production retrieval mode {@code auto} and the given diversification mode. The system
   * properties it sets are saved in {@code saved} for {@link #restoreProperties}.
   */
  static RunningRuntime openIndex(
      Map<String, String> saved,
      io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations executors,
      String diversifyMode,
      String... contents)
      throws Exception {
    return openIndexWithMode(saved, executors, "auto", diversifyMode, contents);
  }

  private static RunningRuntime openIndexWithMode(
      Map<String, String> saved,
      io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations executors,
      String retrievalMode, String diversifyMode, String... contents) throws Exception {
    Path base = Files.createTempDirectory("justsearch-rag-budget-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: ragbudget\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    setProperty(saved, "justsearch.config", cfg.toString());
    setProperty(saved, "rag.retrieve.mode", retrievalMode);
    setProperty(saved, "rag.diversify.mode", diversifyMode);

    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .ephemeral()
            .withExecutorRegistrations(executors)
            .open();
    for (int i = 0; i < contents.length; i++) {
      String id = "doc-" + (i + 1);
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, id,
                  SchemaFields.DOC_UID, id + "#0",
                  SchemaFields.PATH, id,
                  SchemaFields.CONTENT, contents[i])));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return runtime;
  }

  /**
   * An embedder whose encodes acquire their handle exactly as the ONNX embedder does. With {@code
   * queryAnswers}, the query embedding answers without the device, so only document embeddings
   * wait for it.
   */
  static final class HandleBackedEmbeddings implements EmbeddingProvider {
    private final SessionHandle handle;
    private final boolean queryAnswers;
    final AtomicInteger queryCalls = new AtomicInteger();
    final AtomicInteger documentCalls = new AtomicInteger();

    HandleBackedEmbeddings(SessionHandle handle, boolean queryAnswers) {
      this.handle = handle;
      this.queryAnswers = queryAnswers;
    }

    @Override
    public float[] embedQuery(String text, InferenceRequest request) {
      queryCalls.incrementAndGet();
      if (queryAnswers) return new float[] {1f, 0f, 0f, 0f};
      return embedOnDevice(request);
    }

    @Override
    public float[] embedDocument(String text, InferenceRequest request) {
      documentCalls.incrementAndGet();
      return embedOnDevice(request);
    }

    private float[] embedOnDevice(InferenceRequest request) {
      try (SessionHandle.Lease _ = handle.acquire(SessionAcquisitionRequest.from(request))) {
        return new float[] {1f, 0f, 0f, 0f};
      }
    }

    @Override
    public float[] embedQuery(String text) {
      throw new AssertionError("a query embed must carry its request");
    }

    @Override
    public float[] embedDocument(String text) {
      throw new AssertionError("a document embed must carry its request");
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      throw new AssertionError("no batch embeds in a retrieval");
    }

    @Override
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return 4;
    }

    @Override
    public Availability availability() {
      return Availability.READY;
    }

    @Override
    public boolean isUsingGpu() {
      return true;
    }
  }
}
