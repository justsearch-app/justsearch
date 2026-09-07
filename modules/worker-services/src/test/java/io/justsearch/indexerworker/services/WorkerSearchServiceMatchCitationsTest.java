package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.MatchCitationsRequest;
import io.justsearch.ipc.MatchCitationsResponse;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link WorkerSearchService#matchCitations}.
 *
 * <p>splitSentences tests are in {@link WorkerSearchServiceSplitSentencesTest} (no index needed).
 */
@DisplayName("WorkerSearchService MatchCitations")
class WorkerSearchServiceMatchCitationsTest {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private final Map<String, String> parentContentById = new HashMap<>();
  /** Chunk fixtures per parent, re-stamped whenever that parent grows to a new revision. */
  private final Map<String, List<Map<String, Object>>> chunkDocsByParent = new HashMap<>();

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  // ==================== Edge/error case tests ====================

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("returns empty response when embedding service unavailable")
    void embeddingUnavailable() {
      WorkerSearchService service = new WorkerSearchService(lifecycle);

      MatchCitationsRequest request =
          MatchCitationsRequest.newBuilder()
              .setAnswerText("Machine learning uses neural networks.")
              .addChunkDocIds("doc-1")
              .addChunkIndices(0)
              .build();

      MatchCitationsResponse response = callMatchCitations(service, request);

      assertEquals(0, response.getSentencesTotal());
      assertEquals(0, response.getSentencesMatched());
      assertEquals("EMBEDDING_UNAVAILABLE", response.getError());
    }

    @Test
    @DisplayName("returns empty response for blank answer text")
    void blankAnswerText() {
      WorkerSearchService service = new WorkerSearchService(lifecycle);

      MatchCitationsRequest request =
          MatchCitationsRequest.newBuilder()
              .setAnswerText("   ")
              .addChunkDocIds("doc-1")
              .addChunkIndices(0)
              .build();

      MatchCitationsResponse response = callMatchCitations(service, request);

      assertEquals(0, response.getSentencesTotal());
      assertEquals(0, response.getSentencesMatched());
      assertTrue(response.getError().isEmpty(), "No error for blank input");
    }

    @Test
    @DisplayName("returns empty response for empty chunk list")
    void emptyChunkList() {
      WorkerSearchService service = new WorkerSearchService(lifecycle);

      MatchCitationsRequest request =
          MatchCitationsRequest.newBuilder().setAnswerText("Machine learning is great.").build();

      MatchCitationsResponse response = callMatchCitations(service, request);

      assertEquals(0, response.getSentencesTotal());
      assertEquals(0, response.getSentencesMatched());
    }

    @Test
    @DisplayName("graceful degradation when embedding returns null")
    void embeddingReturnsNull() throws Exception {
      EmbeddingService svc = embeddingServiceAvailableButNullEmbed();
      WorkerSearchService service = new WorkerSearchService(lifecycle, svc);

      indexChunk("d:/docs/test.pdf", 0, "Machine learning is a subset of AI.");

      MatchCitationsRequest request =
          MatchCitationsRequest.newBuilder()
              .setAnswerText("Machine learning uses neural networks.")
              .addChunkDocIds("d:/docs/test.pdf")
              .addChunkIndices(0)
              .build();

      MatchCitationsResponse response = callMatchCitations(service, request);

      assertEquals(0, response.getSentencesMatched());
      assertTrue(response.getTookMs() >= 0, "Should report timing");
    }
  }

  // ==================== Happy-path test ====================

  @Nested
  @DisplayName("Happy path")
  class HappyPath {

    @Test
    @DisplayName("matches answer sentences to correct source chunks by embedding similarity")
    void matchesSentencesToCorrectChunks() throws Exception {
      // Two chunks with distinct topics
      String chunk0Text = "Machine learning is a subset of artificial intelligence";
      String chunk1Text = "Neural networks are inspired by the human brain";
      String parentDocId = "d:/docs/report.pdf";

      indexChunk(parentDocId, 0, chunk0Text);
      indexChunk(parentDocId, 1, chunk1Text);

      // Answer with two sentences — one about ML, one about neural networks.
      // BreakIterator splits on ". " so each sentence is a cache key.
      String sentence0 = "AI includes machine learning.";
      String sentence1 = "The brain inspires neural networks.";
      String answer = sentence0 + " " + sentence1;

      // Pre-populate embedding cache: ML-related → ML vector, neural → neural vector
      // Cosine(ML,ML) = 1.0, Cosine(neural,neural) = 1.0, Cosine(ML,neural) ≈ 0.0
      float[] mlVector = {0.9f, 0.1f, 0.0f, 0.0f};
      float[] neuralVector = {0.0f, 0.0f, 0.9f, 0.1f};

      // Cache keys must include the task prefixes that embedQuery/embedDocument prepend.
      EmbeddingService svc =
          embeddingServiceWithDeterministicVectors(
              Map.of(
                  "search_query: " + sentence0, mlVector, // answer sentence about ML
                  "search_query: " + sentence1, neuralVector, // answer sentence about brain
                  "search_document: " + chunk0Text, mlVector, // chunk about ML
                  "search_document: " + chunk1Text, neuralVector // chunk about neural networks
                  ));
      WorkerSearchService service = new WorkerSearchService(lifecycle, svc);

      MatchCitationsRequest request =
          MatchCitationsRequest.newBuilder()
              .setAnswerText(answer)
              .addChunkDocIds(parentDocId)
              .addChunkIndices(0)
              .addChunkDocIds(parentDocId)
              .addChunkIndices(1)
              .setSimilarityThreshold(0.5)
              .build();

      MatchCitationsResponse response = callMatchCitations(service, request);

      assertEquals(2, response.getSentencesTotal(), "Should find 2 sentences");
      assertEquals(2, response.getSentencesMatched(), "Both sentences should match");
      assertEquals(2, response.getMatchesCount(), "Should have 2 match entries");
      assertTrue(response.getError().isEmpty(), "No error expected");
      assertTrue(response.getTookMs() >= 0, "Should report timing");

      // Sentence 0 (ML-related) should match chunk 0 (ML content)
      var match0 = response.getMatches(0);
      assertEquals(0, match0.getSentenceIndex());
      assertEquals(0, match0.getSourceIndex(), "ML sentence should match request-array position 0");
      assertTrue(match0.getSimilarity() > 0.9, "Same-topic similarity should be high");

      // Sentence 1 (brain/neural) should match chunk 1 (neural networks content)
      var match1 = response.getMatches(1);
      assertEquals(1, match1.getSentenceIndex());
      assertEquals(
          1, match1.getSourceIndex(), "Neural sentence should match request-array position 1");
      assertTrue(match1.getSimilarity() > 0.9, "Same-topic similarity should be high");
    }
  }

  // ==================== Helpers ====================

  private void indexChunk(String parentDocId, int chunkIndex, String content) throws Exception {
    String previous = parentContentById.getOrDefault(parentDocId, "");
    String separator = previous.isEmpty() ? "" : "\n";
    int start = previous.length() + separator.length();
    String parentContent = previous + separator + content;
    parentContentById.put(parentDocId, parentContent);
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, parentDocId,
                    SchemaFields.DOC_UID, parentDocId + "#0",
                    SchemaFields.PATH, parentDocId,
                    SchemaFields.CONTENT, parentContent)));
    Map<String, Object> chunk = new HashMap<>();
    chunk.put(SchemaFields.DOC_ID, "chunk:" + parentDocId + "#" + chunkIndex);
    chunk.put(SchemaFields.DOC_UID, "chunk:" + parentDocId + "#" + chunkIndex + "#0");
    chunk.put(SchemaFields.PATH, parentDocId);
    chunk.put(SchemaFields.PARENT_DOC_ID, parentDocId);
    chunk.put(SchemaFields.IS_CHUNK, "true");
    chunk.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunkIndex));
    chunk.put(SchemaFields.CHUNK_TOTAL, "2");
    chunk.put(SchemaFields.CHUNK_CONTENT, content);
    chunk.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(start));
    chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(start + content.length()));
    chunkDocsByParent.computeIfAbsent(parentDocId, k -> new ArrayList<>()).add(chunk);
    // The parent grew, so it is at a NEW revision and every chunk already cut from it is stale --
    // the state the read-path guard (tempdoc 931 section E item 5) refuses to reconstruct from.
    // Production regenerates chunks after a parent rewrite; this fixture re-stamps them the same
    // way, so the guard sees the in-step state a real corpus is in.
    for (Map<String, Object> sibling : chunkDocsByParent.get(parentDocId)) {
      sibling.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(new HashMap<>(sibling)));
    }
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  private static MatchCitationsResponse callMatchCitations(
      WorkerSearchService service, MatchCitationsRequest request) {
    MatchCitationsResponse response;
    try {
      response = service.matchCitations(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("matchCitations failed: " + e.getMessage());
    }

    assertNotNull(response, "Response should not be null");
    return response;
  }

  /** EmbeddingService that reports available but returns null from embed() (backend is null). */
  private static EmbeddingService embeddingServiceAvailableButNullEmbed() throws Exception {
    EmbeddingService svc = new EmbeddingService(Path.of("dummy.gguf"), EmbeddingConfig.DISABLED);
    setField(svc, "initialized", true);
    setField(svc, "available", true);
    return svc;
  }

  /**
   * EmbeddingService with pre-populated cache entries for deterministic testing. Bypasses the
   * backend entirely by injecting vectors directly into the embedding cache. Vectors cluster by
   * topic: ML-related text → [0.9, 0.1, 0.0, 0.0], neural/brain → [0.0, 0.0, 0.9, 0.1].
   */
  @SuppressWarnings("unchecked")
  private static EmbeddingService embeddingServiceWithDeterministicVectors(
      Map<String, float[]> textToVector) throws Exception {
    EmbeddingService svc = new EmbeddingService(Path.of("dummy.gguf"), EmbeddingConfig.DISABLED);
    setField(svc, "initialized", true);
    setField(svc, "available", true);

    // Set backend to a non-null value so embedWithChunks() doesn't short-circuit.
    // Use Unsafe.allocateInstance to create a dummy AiBackend without calling constructor.
    sun.misc.Unsafe unsafe = getUnsafe();
    Object dummyBackend =
        unsafe.allocateInstance(
            io.justsearch.aibackend.backend.DeterministicBackend.class);
    Field backendField = EmbeddingService.class.getDeclaredField("backend");
    backendField.setAccessible(true);
    backendField.set(svc, dummyBackend);

    // Access the private embeddingCache and CachedEmbedding record via reflection
    Field cacheField = EmbeddingService.class.getDeclaredField("embeddingCache");
    cacheField.setAccessible(true);
    var cache =
        (java.util.concurrent.ConcurrentHashMap<String, Object>) cacheField.get(svc);

    // Get the private CachedEmbedding record constructor
    Class<?> cachedEmbeddingClass = null;
    for (Class<?> inner : EmbeddingService.class.getDeclaredClasses()) {
      if (inner.getSimpleName().equals("CachedEmbedding")) {
        cachedEmbeddingClass = inner;
        break;
      }
    }
    assertNotNull(cachedEmbeddingClass, "CachedEmbedding inner class should exist");

    var constructor = cachedEmbeddingClass.getDeclaredConstructors()[0];
    constructor.setAccessible(true);

    // Pre-populate cache with known vectors (far-future expiry so they don't evict)
    long farFuture = System.currentTimeMillis() + 600_000;
    for (var entry : textToVector.entrySet()) {
      var chunkedEmbedding =
          new EmbeddingService.ChunkedEmbedding(entry.getValue(), List.of(), 1);
      Object cachedEntry = constructor.newInstance(chunkedEmbedding, farFuture);
      cache.put(entry.getKey().strip(), cachedEntry);
    }

    return svc;
  }

  private static sun.misc.Unsafe getUnsafe() throws Exception {
    Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    return (sun.misc.Unsafe) f.get(null);
  }

  private static void setField(Object obj, String fieldName, Object value) throws Exception {
    Field field = obj.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    if (field.getType() == AtomicBoolean.class) {
      ((AtomicBoolean) field.get(obj)).set((Boolean) value);
    } else if (field.getType() == boolean.class) {
      field.setBoolean(obj, (Boolean) value);
    } else {
      field.set(obj, value);
    }
  }
}
