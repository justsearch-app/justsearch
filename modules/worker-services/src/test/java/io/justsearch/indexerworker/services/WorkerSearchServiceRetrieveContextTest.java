package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.ipc.ChunkRef;
import io.justsearch.ipc.ContextFormat;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for WorkerSearchService.retrieveContext (P1.4).
 *
 * <p>Verifies chunk vs fallback behavior, diversification, and metadata fields.
 */
@DisplayName("WorkerSearchService RetrieveContext (P1.4)")
class WorkerSearchServiceRetrieveContextTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;
  private final Map<String, String> parentContentById = new HashMap<>();

  @BeforeEach
  void setUp() throws Exception {
    // Clear any existing config property to ensure clean test isolation
    System.clearProperty("justsearch.config");

    // Use chunk-aware testing catalog with explicit index path
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();

    service = new WorkerSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  @Nested
  @DisplayName("Chunk vs fallback behavior")
  class ChunkVsFallback {

    @Test
    @DisplayName("returns chunk context when chunks exist")
    void returnsChunkContextWhenChunksExist() throws Exception {
      String parentDocId = "d:/docs/report.pdf";

      final String chunk0Text = "Machine learning is a subset of artificial intelligence.";
      final String chunk1Text = "Neural networks are inspired by the human brain structure.";
      String parentContent = chunk0Text + "\n" + chunk1Text;

      // Index a parent document
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, rememberParentContent(parentDocId, parentContent),
          SchemaFields.MIME, "application/pdf")));

      // Index chunk documents for the same parent
      final int chunk0Start = parentContent.indexOf(chunk0Text);
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:abc-001",
          SchemaFields.DOC_UID, "chunk:abc-001#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "3",
          SchemaFields.CHUNK_CONTENT, chunk0Text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk0Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk0Start + chunk0Text.length())))));

      final int chunk1Start = parentContent.indexOf(chunk1Text);
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:abc-002",
          SchemaFields.DOC_UID, "chunk:abc-002#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "1",
          SchemaFields.CHUNK_TOTAL, "3",
          SchemaFields.CHUNK_CONTENT, chunk1Text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk1Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk1Start + chunk1Text.length())))));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Request context about machine learning
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("What is machine learning?")
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Should use chunks when they exist");
      assertTrue(response.getChunksFound() > 0, "Should find at least one chunk");
      assertTrue(response.getChunksCount() > 0, "Should return structured chunks for click-to-verify citations");
      assertEquals(parentDocId, response.getChunks(0).getParentDocId(), "Chunk should reference the parent doc id");
      assertTrue(response.getChunks(0).getEndChar() > response.getChunks(0).getStartChar(), "Chunk must have a non-empty span");
      assertTrue(response.getContext().contains("machine learning") ||
                 response.getContext().contains("Machine learning"),
          "Context should contain relevant chunk content");
    }

    @Test
    @DisplayName("serves unchunked docs via the PRIMARY path, not fallback (tempdoc 749 union leg)")
    void servesUnchunkedDocViaPrimaryPath() throws Exception {
      String parentDocId = "d:/docs/simple.txt";

      // Index only a parent document (no chunks) — the sub-2000-char shape that used to be
      // invisible to IS_CHUNK:true retrieval and silently fell back (tempdoc 749).
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "This document discusses quantum computing and qubits.",
          SchemaFields.MIME, "text/plain")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Request context about quantum computing
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("What is quantum computing?")
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      // Tempdoc 749 option B: the doc-level union leg synthesizes a whole-doc chunk in the
      // PRIMARY candidate set — this must no longer take the FULLTEXT_FALLBACK path.
      assertTrue(response.getUsedChunks(), "Unchunked doc must be served via synthesized chunk");
      assertTrue(response.getChunksCount() > 0, "Should have synthesized chunk metadata");
      assertNotEquals("FULLTEXT_FALLBACK", response.getRetrievalMode(),
          "Chunkless docs are primary-path citizens since the 749 union leg");
      var chunk = response.getChunks(0);
      assertEquals(parentDocId, chunk.getParentDocId());
      assertEquals(0, chunk.getChunkIndex(), "Whole-doc synthetic chunk is index 0");
      assertEquals(1, chunk.getChunkTotal(), "Whole-doc synthetic chunk is total 1");
      assertTrue(chunk.getEndChar() > chunk.getStartChar(), "Synthetic chunk has a real span");
      assertTrue(response.getContext().contains("quantum") ||
                 response.getContext().contains("Quantum"),
          "Context should contain the unchunked doc's content");
    }

    @Test
    @DisplayName("FULLTEXT_FALLBACK still fires when neither chunks nor union match")
    void fallbackStillReachableWhenNothingMatches() throws Exception {
      String parentDocId = "d:/docs/unrelated.txt";

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "This document discusses quantum computing and qubits.",
          SchemaFields.MIME, "text/plain")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // A question sharing no terms with the doc: BM25 chunk search AND the doc-level union
      // both come up empty, so the fallback remains the last resort.
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("zebra migration seasons")
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertEquals("FULLTEXT_FALLBACK", response.getRetrievalMode(),
          "Nothing-matches queries still reach the fallback safety net");
    }

    @Test
    @DisplayName("returns empty context when question is blank")
    void returnsEmptyContextWhenQuestionBlank() throws Exception {
      String parentDocId = "d:/docs/test.pdf";

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "Some content here.")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("")  // Blank question
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertEquals("", response.getContext(), "Should return empty context for blank question");
      assertFalse(response.getUsedChunks());
      assertEquals(0, response.getChunksFound());
    }

    @Test
    @DisplayName("returns empty context when docIds is empty")
    void returnsEmptyContextWhenNoDocIds() throws Exception {
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("Some question")
          // No docIds added
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertEquals("", response.getContext(), "Should return empty context when no docIds");
      assertFalse(response.getUsedChunks());
      assertEquals(0, response.getChunksFound());
    }
  }

  @Nested
  @DisplayName("Doc-level union leg (tempdoc 749 option B)")
  class UnionLeg {

    @Test
    @DisplayName("chunked parent is never double-surfaced by the union; only its real chunks cite")
    void chunkedParentNotDoubleSurfaced() throws Exception {
      // (a) A LONG parent (>2000 chars, above CHUNK_THRESHOLD_CHARS) that owns 2 real chunk docs.
      String longParent = "d:/docs/photosynthesis-long.md";
      final String longChunk0 =
          "Photosynthesis converts sunlight into chemical energy inside plant cells.";
      final String longChunk1 =
          "The photosynthesis process relies on chlorophyll in plants to capture sunlight.";
      String longContent =
          longChunk0
              + "\n"
              + "Photosynthesis in plants converts sunlight into chemical energy. ".repeat(40)
              + "\n"
              + longChunk1;
      assertTrue(longContent.length() > 2000, "Long parent must exceed the chunking threshold");
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, longParent,
          SchemaFields.DOC_UID, longParent + "#0",
          SchemaFields.PATH, longParent,
          SchemaFields.CONTENT, rememberParentContent(longParent, longContent),
          SchemaFields.MIME, "text/markdown")));

      int longChunk0Start = longContent.indexOf(longChunk0);
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:long-000",
          SchemaFields.DOC_UID, "chunk:long-000#0",
          SchemaFields.PATH, longParent,
          SchemaFields.PARENT_DOC_ID, longParent,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "2",
          SchemaFields.CHUNK_CONTENT, longChunk0,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(longChunk0Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(longChunk0Start + longChunk0.length())))));

      int longChunk1Start = longContent.indexOf(longChunk1);
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:long-001",
          SchemaFields.DOC_UID, "chunk:long-001#0",
          SchemaFields.PATH, longParent,
          SchemaFields.PARENT_DOC_ID, longParent,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "1",
          SchemaFields.CHUNK_TOTAL, "2",
          SchemaFields.CHUNK_CONTENT, longChunk1,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(longChunk1Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(longChunk1Start + longChunk1.length())))));

      // (b) A SHORT chunkless parent (<2000 chars) that also matches the query — the union path
      // is the only way it reaches retrieval.
      String shortParent = "d:/docs/photosynthesis-short.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, shortParent,
          SchemaFields.DOC_UID, shortParent + "#0",
          SchemaFields.PATH, shortParent,
          SchemaFields.CONTENT,
          "Photosynthesis is how plants make food from sunlight and water.",
          SchemaFields.MIME, "text/markdown")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("photosynthesis sunlight plants")
          .addDocIds(longParent)
          .addDocIds(shortParent)
          .setTopK(10)
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Both parents should retrieve via the chunk pipeline");

      int shortCitations = 0;
      int longCitations = 0;
      for (int i = 0; i < response.getChunksCount(); i++) {
        var chunk = response.getChunks(i);
        if (shortParent.equals(chunk.getParentDocId())) {
          shortCitations++;
          // The short parent's only citation is the synthesized whole-doc chunk.
          assertEquals(1, chunk.getChunkTotal(),
              "Chunkless short parent must surface as a single whole-doc chunk (total=1)");
          assertEquals(0, chunk.getChunkIndex(),
              "Whole-doc synthetic chunk is index 0");
        } else if (longParent.equals(chunk.getParentDocId())) {
          longCitations++;
          // The union must NOT synthesize a whole-doc chunk for the chunked long parent: every
          // long-parent citation comes from a seeded real chunk (chunkTotal==2). A synthesized
          // whole-doc chunk for a >2000-char parent would carry a different total (its split
          // count, or 1), so this assertion fails the instant double-surfacing occurs.
          assertEquals(2, chunk.getChunkTotal(),
              "Long parent citations must be its real chunks only (total=2), never a synthesized "
                  + "whole-doc chunk. chunkIndex=" + chunk.getChunkIndex());
        }
      }

      assertEquals(1, shortCitations,
          "Chunkless short parent must be cited exactly once (no duplicate whole-doc chunk)");
      assertTrue(longCitations >= 1,
          "Long parent must still be cited via its real chunks");
    }

    @Test
    @DisplayName("unscoped union (empty docIds) serves a chunkless parent via the primary path")
    void unscopedUnionServesChunklessParent() throws Exception {
      String parent = "d:/docs/tardigrade.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parent,
          SchemaFields.DOC_UID, parent + "#0",
          SchemaFields.PATH, parent,
          SchemaFields.CONTENT,
          "Tardigrades are microscopic animals that survive extreme conditions.",
          SchemaFields.MIME, "text/markdown")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // No docIds — the unscoped searchFullDocs leg of the union is what must find the parent.
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("tardigrades extreme conditions")
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      // The harness has no embedding service, so this is the BM25 doc-level union leg.
      assertTrue(response.getUsedChunks(), "Unscoped chunkless parent must be served, not skipped");
      assertNotEquals("FULLTEXT_FALLBACK", response.getRetrievalMode(),
          "Unscoped union is a primary-path result, not the fallback safety net");
      assertTrue(response.getChunksCount() > 0, "Should synthesize a citation");
      assertEquals(parent, response.getChunks(0).getParentDocId(),
          "The synthesized citation references the chunkless parent");
    }

    @Test
    @DisplayName("excluded parent is not re-injected by the union leg")
    void excludedParentNotReinjected() throws Exception {
      String parent = "d:/docs/hidden.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parent,
          SchemaFields.DOC_UID, parent + "#0",
          SchemaFields.PATH, parent,
          SchemaFields.CONTENT,
          "Volcanic eruptions release ash and lava from the earth's mantle.",
          SchemaFields.MIME, "text/markdown")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Scoped to the parent, but the user has hidden it via an excluded-chunk ref. The union
      // leg must honour the exclusion and NOT resurrect the doc as a synthesized whole-doc chunk.
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("volcanic eruptions ash lava")
          .addDocIds(parent)
          .setTopK(5)
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parent).setChunkIndex(0))
          .build();

      var response = callRetrieveContext(request);

      for (int i = 0; i < response.getChunksCount(); i++) {
        assertNotEquals(parent, response.getChunks(i).getParentDocId(),
            "An excluded parent must never re-surface via the union leg");
      }
    }
  }

  @Nested
  @DisplayName("Diversification behavior")
  class Diversification {

    @Test
    @DisplayName("diversifies chunks from different positions (begin/middle/end)")
    void diversifiesChunksFromDifferentPositions() throws Exception {
      String parentDocId = "d:/docs/longdoc.pdf";

      String[] chunkTexts = new String[10];
      StringBuilder parentContent = new StringBuilder();
      for (int i = 0; i < chunkTexts.length; i++) {
        String position = i < 3 ? "beginning" : (i < 7 ? "middle" : "end");
        chunkTexts[i] =
            "Chunk " + i + " from " + position + " discusses data science concepts.";
        if (!parentContent.isEmpty()) {
          parentContent.append('\n');
        }
        parentContent.append(chunkTexts[i]);
      }
      String parentText = rememberParentContent(parentDocId, parentContent.toString());

      // Index parent
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, parentText)));

      // Index chunks from beginning, middle, and end (10 chunks total)
      for (int i = 0; i < 10; i++) {
        String chunkText = chunkTexts[i];
        int start = chunkStart(parentDocId, chunkText);
        lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
            SchemaFields.DOC_ID, "chunk:div-" + i,
            SchemaFields.DOC_UID, "chunk:div-" + i + "#0",
            SchemaFields.PATH, parentDocId,
            SchemaFields.PARENT_DOC_ID, parentDocId,
            SchemaFields.IS_CHUNK, "true",
            SchemaFields.CHUNK_INDEX, String.valueOf(i),
            SchemaFields.CHUNK_TOTAL, "10",
            SchemaFields.CHUNK_CONTENT, chunkText,
            SchemaFields.CHUNK_START_CHAR, String.valueOf(start),
            SchemaFields.CHUNK_END_CHAR, String.valueOf(start + chunkText.length())))));
      }

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("data science concepts")
          .addDocIds(parentDocId)
          .setTopK(5)  // Request 5 chunks
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Should use chunks");
      assertTrue(response.getChunksFound() >= 3, "Should find multiple chunks");

      // Verify diversification by checking context contains chunks from different positions
      String ctx = response.getContext();
      // At least some chunks from beginning and end should be present
      boolean hasBeginning = ctx.contains("beginning");
      boolean hasMiddle = ctx.contains("middle");
      boolean hasEnd = ctx.contains("end");

      int positionsCovered = (hasBeginning ? 1 : 0) + (hasMiddle ? 1 : 0) + (hasEnd ? 1 : 0);
      assertTrue(positionsCovered >= 2,
          "Diversification should cover at least 2 different positions (begin/middle/end). " +
          "Found: beginning=" + hasBeginning + ", middle=" + hasMiddle + ", end=" + hasEnd);
    }
  }

  // ========== Helper methods ==========

  // ==================== Metadata Filter Tests (362) ====================

  @Nested
  @DisplayName("Metadata filtering in retrieve-context (362)")
  class MetadataFiltering {

    @Test
    @DisplayName("metadata filter scopes to matching parent docs")
    void metadataFilterScopesToMatchingParents() throws Exception {
      String vergeParent = "d:/docs/verge-article.md";
      String vergeChunk = "The Verge reports on AI advancements in search.";
      String techCrunchParent = "d:/docs/tc-article.md";
      String techCrunchChunk = "TechCrunch reports on AI advancements in search.";
      // Index two parent docs with different meta_source values
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, vergeParent,
          SchemaFields.DOC_UID, "verge#0",
          SchemaFields.PATH, vergeParent,
          SchemaFields.CONTENT, rememberParentContent(vergeParent, vergeChunk),
          SchemaFields.MIME, "text/x-web-markdown",
          SchemaFields.META_SOURCE, "the verge")));

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, techCrunchParent,
          SchemaFields.DOC_UID, "tc#0",
          SchemaFields.PATH, techCrunchParent,
          SchemaFields.CONTENT, rememberParentContent(techCrunchParent, techCrunchChunk),
          SchemaFields.MIME, "text/x-web-markdown",
          SchemaFields.META_SOURCE, "techcrunch")));

      // Index chunks for both parents
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:verge-001",
          SchemaFields.DOC_UID, "chunk:verge-001#0",
          SchemaFields.PATH, vergeParent,
          SchemaFields.PARENT_DOC_ID, vergeParent,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, vergeChunk,
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(vergeChunk.length())))));

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:tc-001",
          SchemaFields.DOC_UID, "chunk:tc-001#0",
          SchemaFields.PATH, techCrunchParent,
          SchemaFields.PARENT_DOC_ID, techCrunchParent,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, techCrunchChunk,
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(techCrunchChunk.length())))));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Filter by meta_source = "the verge" — should only return Verge chunks
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("AI advancements")
          .setTopK(5)
          .addMetaSource("the verge")
          .build();

      var response = callRetrieveContext(request);

      assertFalse(response.getContext().isBlank(), "Should have context from The Verge doc");
      assertTrue(response.getContext().contains("Verge"),
          "Context should be from The Verge, not TechCrunch");
      assertFalse(response.getContext().contains("TechCrunch"),
          "Context should NOT include TechCrunch content");
    }

    @Test
    @DisplayName("no matching parents returns empty context")
    void noMatchingParentsReturnsEmpty() throws Exception {
      String parentDocId = "d:/docs/article.md";
      String chunkText = "Article about technology.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, "article#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, rememberParentContent(parentDocId, chunkText),
          SchemaFields.MIME, "text/x-web-markdown",
          SchemaFields.META_SOURCE, "the verge")));

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:art-001",
          SchemaFields.DOC_UID, "chunk:art-001#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, chunkText,
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunkText.length())))));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Filter by meta_source that doesn't exist
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("technology")
          .setTopK(5)
          .addMetaSource("nonexistent-source")
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getContext().isEmpty(),
          "Should return empty context when no parents match the filter");
    }
  }

  @Nested
  @DisplayName("Retrieved-source exclusion (610 §J.3)")
  class SourceExclusion {

    private void indexChunk(String parentDocId, int idx, String text) throws Exception {
      int start = chunkStart(parentDocId, text);
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:excl-" + idx,
          SchemaFields.DOC_UID, "chunk:excl-" + idx + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, String.valueOf(idx),
          SchemaFields.CHUNK_TOTAL, "3",
          SchemaFields.CHUNK_CONTENT, text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(start + text.length())))));
    }

    @Test
    @DisplayName("excluded chunk is dropped from retrieval; the others remain")
    void excludedChunkIsAbsent() throws Exception {
      String parentDocId = "d:/docs/reliability.md";
      String chunk0 = "Reliability budget overview for the quarter.";
      String chunk1 = "Reliability SECRETMARKER chunk that the user will hide.";
      String chunk2 = "Reliability conclusion and summary.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT,
          rememberParentContent(parentDocId, String.join("\n", chunk0, chunk1, chunk2)),
          SchemaFields.MIME, "text/markdown")));
      indexChunk(parentDocId, 0, chunk0);
      indexChunk(parentDocId, 1, chunk1);
      indexChunk(parentDocId, 2, chunk2);
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Baseline: without exclusion, chunk 1's marker is present.
      RetrieveContextRequest baseline = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5).build();
      var baseResp = callRetrieveContext(baseline);
      assertTrue(baseResp.getContext().contains("SECRETMARKER"),
          "Baseline (no exclusion) should include chunk 1's content");

      // With chunk 1 excluded: its marker is gone, the other two remain.
      RetrieveContextRequest excluded = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5)
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parentDocId).setChunkIndex(1))
          .build();
      var exclResp = callRetrieveContext(excluded);
      assertFalse(exclResp.getContext().contains("SECRETMARKER"),
          "Excluded chunk 1 must NOT appear in the assembled context");
      assertTrue(exclResp.getContext().contains("overview"),
          "Chunk 0 (not excluded) should remain");
      assertTrue(exclResp.getContext().contains("conclusion"),
          "Chunk 2 (not excluded) should remain");
      // No excluded chunk surfaces as a citation either.
      for (int i = 0; i < exclResp.getChunksCount(); i++) {
        assertNotEquals(1, exclResp.getChunks(i).getChunkIndex(),
            "Excluded chunk 1 must not be a returned citation");
      }
    }

    @Test
    @DisplayName("empty exclusion list is a no-op")
    void emptyExclusionIsNoOp() throws Exception {
      String parentDocId = "d:/docs/noop.md";
      String chunkText = "Reliability KEEPME overview content.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, rememberParentContent(parentDocId, chunkText),
          SchemaFields.MIME, "text/markdown")));
      indexChunk(parentDocId, 0, chunkText);
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5).build();
      var resp = callRetrieveContext(request);
      assertTrue(resp.getContext().contains("KEEPME"),
          "With no exclusions the chunk is retrieved as before");
    }

    @Test
    @DisplayName("excluding ALL chunks of a scoped doc does not re-inject it via the whole-doc fallback")
    void allChunksExcludedNoFallbackReinjection() throws Exception {
      String parentDocId = "d:/docs/secret.md";
      String chunk0 = "Reliability SECRETMARKER chunk zero.";
      String chunk1 = "Reliability SECRETMARKER chunk one.";
      // The full document carries the marker too — the fallback fetches full-doc CONTENT, so this is
      // exactly what must NOT come back when every chunk is hidden.
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, rememberParentContent(parentDocId, chunk0 + "\n" + chunk1),
          SchemaFields.MIME, "text/markdown")));
      indexChunk(parentDocId, 0, chunk0);
      indexChunk(parentDocId, 1, chunk1);
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5)
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parentDocId).setChunkIndex(0))
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parentDocId).setChunkIndex(1))
          .build();
      var resp = callRetrieveContext(request);
      assertFalse(resp.getContext().contains("SECRETMARKER"),
          "A doc with all chunks hidden must NOT reappear via the whole-document fallback");
    }
  }

  @Nested
  @DisplayName("Tempdoc 822 §3a: the numbering contract (section n <=> citations[n-1])")
  class NumberingContract {

    private void indexChunk(String parentDocId, int idx, String text) throws Exception {
      int start = chunkStart(parentDocId, text);
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:" + parentDocId + "-" + idx,
          SchemaFields.DOC_UID, "chunk:" + parentDocId + "-" + idx + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, String.valueOf(idx),
          SchemaFields.CHUNK_TOTAL, "9",
          SchemaFields.CHUNK_CONTENT, text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(start + text.length())))));
    }

    /**
     * The invariant the printed {@code [n]} rests on: the header's 1-based number, the section's
     * position, and the position of the citation the FE renders as {@code sources[n - 1]} are the
     * SAME number. It holds by construction today — the budget loop appends to the used-hit list
     * and to the section list in one iteration — which is exactly why a future reorder (a filter
     * applied to one list, a sort of the other) could break it silently. This test makes that
     * loud.
     *
     * <p>Note the per-document ordinal is deliberately NOT the ordinal in play: the fixtures index
     * chunks 5/6/7 of their parents, so a header numbered from {@code chunkIndex} would print
     * {@code [6]} where the contract requires {@code [1]}.
     */
    @Test
    @DisplayName("header number n <=> sections[n-1] <=> chunks[n-1], and never the chunk's own ordinal")
    void headerNumberMatchesSectionAndCitationPosition() throws Exception {
      String docA = "d:/docs/alpha.txt";
      String docB = "d:/docs/bravo.txt";
      String chunkA5 = "Machine learning is a subset of artificial intelligence.";
      String chunkA6 = "Machine learning models are trained on labelled examples.";
      String chunkB7 = "Machine learning pipelines run on neural network accelerators.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, docA,
          SchemaFields.DOC_UID, docA + "#0",
          SchemaFields.PATH, docA,
          SchemaFields.CONTENT, rememberParentContent(docA, chunkA5 + "\n" + chunkA6),
          SchemaFields.MIME, "text/plain")));
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, docB,
          SchemaFields.DOC_UID, docB + "#0",
          SchemaFields.PATH, docB,
          SchemaFields.CONTENT, rememberParentContent(docB, chunkB7),
          SchemaFields.MIME, "text/plain")));
      indexChunk(docA, 5, chunkA5);
      indexChunk(docA, 6, chunkA6);
      indexChunk(docB, 7, chunkB7);

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      var response = callRetrieveContext(RetrieveContextRequest.newBuilder()
          .setQuestion("What is machine learning?")
          .addDocIds(docA)
          .addDocIds(docB)
          .setTopK(5)
          .build());

      assertTrue(response.getUsedChunks(), "Should use chunks when they exist");
      assertEquals(3, response.getSectionsCount(), "all three chunks fit the 200K budget");
      assertEquals(
          response.getSectionsCount(),
          response.getChunksCount(),
          "one citation per rendered section — the FE's sources array is this list");

      String[] rendered = response.getContext().split(ContextBudgeter.SECTION_SEPARATOR);
      assertEquals(response.getSectionsCount(), rendered.length, "one rendered block per section");

      for (int i = 0; i < response.getSectionsCount(); i++) {
        var section = response.getSections(i);
        assertEquals(i, section.getSectionIndex(), "section " + i + " keeps its 0-based position");
        assertEquals(i, section.getChunkIndex(), "section " + i + " points at citation " + i);
        assertEquals(
            ContextBudgeter.sectionHeader(i + 1, section.getSourceLabel()) + section.getContent(),
            rendered[i],
            "rendered block " + i + " must be section " + i + " under the 1-based header "
                + (i + 1));
        assertEquals(
            section.getContent(),
            response.getChunks(i).getExcerpt(),
            "citation " + i + " must be the chunk section " + i + " rendered");
      }

      // The per-document ordinals are 5/6/7, so a header derived from them could not read [1..3].
      assertTrue(
          response.getChunksList().stream().noneMatch(c -> c.getChunkIndex() < 5),
          "fixture must keep per-document ordinals distinct from positions: "
              + response.getChunksList());
    }
  }

  @Nested
  @DisplayName("Tempdoc 725 W2b: contextFormat is a genuine no-op end-to-end (LABELED always renders)")
  class ContextFormatIsIgnored {

    /**
     * Ground-truth regression for the W2b format wrong-gate fix: {@code RagContextOps} never
     * reads {@code request.getContextFormat()} (grep-verified: no call site in the module), and
     * {@link io.justsearch.indexing.rag.ContextBudgeter} has only one rendering — {@code "[n]
     * label\n" + content} (LABELED) — with no XML/PLAIN branch at all. This test drives the REAL
     * production path (this class's {@code WorkerSearchService}, backed by a real Lucene runtime,
     * exactly as {@link ChunkVsFallback#returnsChunkContextWhenChunksExist()} above) and proves
     * that requesting {@code CONTEXT_FORMAT_XML} on the wire still yields LABELED-shaped output —
     * grounding {@code McpToolSurface.callAnswer}'s decision to request LABELED explicitly
     * (tempdoc 725 orphan #5) in the actual renderer, not just in a mocked call site.
     */
    @Test
    @DisplayName("requesting CONTEXT_FORMAT_XML still renders LABELED \"[n] label\" sections")
    void xmlFormatRequestStillRendersLabeled() throws Exception {
      String parentDocId = "d:/docs/report.pdf";
      final String chunk0Text = "Machine learning is a subset of artificial intelligence.";

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, rememberParentContent(parentDocId, chunk0Text),
          SchemaFields.MIME, "application/pdf")));

      final int chunk0Start = 0;
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:xml-001",
          SchemaFields.DOC_UID, "chunk:xml-001#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, chunk0Text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk0Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk0Start + chunk0Text.length())))));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("What is machine learning?")
          .addDocIds(parentDocId)
          .setTopK(5)
          .setContextFormat(ContextFormat.CONTEXT_FORMAT_XML)
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Should use chunks when they exist");
      assertTrue(
          response.getContext().startsWith("[1] "),
          "LABELED is the only format ContextBudgeter renders, regardless of what was requested: "
              + response.getContext());
      assertFalse(
          response.getContext().contains("<source "),
          "No XML rendering exists yet — the request must not silently produce XML-shaped output: "
              + response.getContext());
    }
  }

  /**
   * Tempdoc 821 §3-C2 — end-to-end collection scoping over a real index. The unit-level routing
   * decision is pinned in {@code RagContextOpsCollectionScopeTest}; this pins the observable
   * retrieval behavior the decision produces.
   */
  @Nested
  @DisplayName("Collection scoping (821 §3-C2)")
  class CollectionScoping {

    private static final String NORMAL_DOC = "d:/docs/quarterly-budget.md";
    private static final String AGENT_DOC = "d:/agent/session-42.md";

    /** Indexes a parent + one chunk, both tagged with {@code collection} when non-null. */
    private void indexDocWithChunk(String parentDocId, String collection, String chunkText)
        throws Exception {
      Map<String, Object> parent = new HashMap<>(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, chunkText,
          SchemaFields.MIME, "text/markdown"));
      Map<String, Object> chunk = new HashMap<>(Map.of(
          SchemaFields.DOC_ID, "chunk:" + parentDocId,
          SchemaFields.DOC_UID, "chunk:" + parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, chunkText,
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunkText.length())));
      // Tempdoc 931 §C.1: the parent revision the offsets address. Without it the read path
      // refuses to reconstruct the chunk's text and the scope assertions see an empty context.
      chunk.put(
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(chunkText));
      if (collection != null) {
        // ChunkDocumentWriter propagates the parent's collection onto chunks (811 item 3); the
        // fixture mirrors that, since the scope binds on the chunk branch.
        parent.put(SchemaFields.COLLECTION, collection);
        chunk.put(SchemaFields.COLLECTION, collection);
      }
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(parent));
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(chunk));
    }

    @BeforeEach
    void indexBothCollections() throws Exception {
      indexDocWithChunk(
          NORMAL_DOC, null, "Retention policy NORMALMARKER for the quarterly budget review.");
      indexDocWithChunk(
          AGENT_DOC, "agent-history", "Retention policy AGENTMARKER discussed in the agent run.");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();
    }

    @Test
    @DisplayName("absent collection keeps the default scope: agent-history is excluded")
    void absentCollectionExcludesAgentHistory() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder().setQuestion("retention policy").setTopK(5).build());

      assertTrue(response.getContext().contains("NORMALMARKER"),
          "the untagged document must still be retrieved: " + response.getContext());
      assertFalse(response.getContext().contains("AGENTMARKER"),
          "the 811 D-1 default exclusion must still bind when no scope is given: "
              + response.getContext());
    }

    @Test
    @DisplayName("an explicit agent-history scope is a positive include, not a match-nothing")
    void explicitAgentHistoryScopeIncludesOnlyAgentHistory() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("retention policy")
              .setTopK(5)
              .addCollection("agent-history")
              .build());

      assertTrue(response.getContext().contains("AGENTMARKER"),
          "an explicit scope must INCLUDE that collection: " + response.getContext());
      assertFalse(response.getContext().contains("NORMALMARKER"),
          "an explicit scope must exclude everything outside it: " + response.getContext());
    }

    @Test
    @DisplayName("a collection-only request keeps the chunk path, not the parent pre-filter")
    void collectionOnlyRequestStaysOnTheChunkPath() throws Exception {
      // The DISCRIMINATING fixture: parent UNTAGGED, chunk TAGGED. This is the only shape that
      // tells the two routings apart —
      //   chunk path  : buildChunkFilterQuery matches the tagged CHUNK  -> content returned;
      //   parent path : findMatchingParentDocIds runs with IS_CHUNK MUST_NOT, so it sees only the
      //                 UNTAGGED parent, matches nothing, and returns buildEmptyFilterResponse.
      // With a tagged parent (as the shared fixture has) both routings resolve the same document,
      // so the assertions below would hold either way and prove nothing.
      String splitDoc = "d:/agent/split-tagged.md";
      String text = "Retention policy SPLITMARKER recorded mid-run.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, splitDoc,
          SchemaFields.DOC_UID, splitDoc + "#0",
          SchemaFields.PATH, splitDoc,
          SchemaFields.CONTENT, rememberParentContent(splitDoc, text),
          SchemaFields.MIME, "text/markdown")));
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(withParentRevision(Map.of(
          SchemaFields.DOC_ID, "chunk:" + splitDoc,
          SchemaFields.DOC_UID, "chunk:" + splitDoc + "#0",
          SchemaFields.PATH, splitDoc,
          SchemaFields.PARENT_DOC_ID, splitDoc,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.COLLECTION, "agent-history",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_CONTENT, text,
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(text.length())))));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("retention policy")
              .setTopK(5)
              .addCollection("agent-history")
              .build());

      assertTrue(response.getUsedChunks(), "collection-only retrieval must use the chunk path");
      assertTrue(response.getContext().contains("SPLITMARKER"),
          "the chunk-branch filter must find a chunk whose PARENT carries no collection tag;"
              + " routing this through parent resolution would return the empty-filter shape: "
              + response.getContext());
    }

    @Test
    @DisplayName("an unknown collection scopes to nothing without falling back to everything")
    void unknownCollectionMatchesNothing() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("retention policy")
              .setTopK(5)
              .addCollection("no-such-collection")
              .build());

      assertFalse(response.getContext().contains("AGENTMARKER"),
          "an unmatched scope must not leak agent-history: " + response.getContext());
      assertFalse(response.getContext().contains("NORMALMARKER"),
          "an unmatched scope must not silently widen back to the default scope: "
              + response.getContext());
    }
  }

  /**
   * Tempdoc 821 §3-C2 (review fix 1) — the two WHOLE-DOCUMENT legs. Both bypass the chunk search
   * and, before this, applied no filter at all: {@code searchFullDocsForDocs} was called with a
   * {@code null} filter, so a scoped request answered from outside its scope the moment the chunk
   * leg came back blank, and {@code return_full_documents=true} made the scope a no-op outright.
   *
   * <p>Fixture, and why it is shaped this way: reaching FULLTEXT_FALLBACK needs BOTH the chunk leg
   * and the tempdoc-749 union leg to come back empty. Chunkless documents do NOT work — the union
   * leg exists precisely to answer for them. So each document here HAS a chunk whose text does not
   * match the question, while its parent {@code content} does: the chunk leg finds nothing, the
   * union leg skips parents that have chunks, and the whole-document leg is left holding the query.
   * {@code doc_ids} is supplied explicitly because {@code searchFullDocsForDocs} keeps its
   * return-empty-on-empty-scope contract (tempdoc 749) — that is the scoped-chat request shape.
   */
  @Nested
  @DisplayName("Whole-document legs honour the scope (821 §3-C2 review fix 1)")
  class WholeDocumentLegScoping {

    private static final String IN_SCOPE = "d:/notes/in-scope.md";
    private static final String OUT_OF_SCOPE = "d:/notes/out-of-scope.md";
    private static final String AGENT_DOC = "d:/agent/transcript.md";

    private void indexDocWithNonMatchingChunk(String docId, String collection, String content)
        throws Exception {
      String chunkText = "Unrelated appendix about stationery inventory.";
      String parentContent = content + "\n" + chunkText;
      Map<String, Object> parent = new HashMap<>(Map.of(
          SchemaFields.DOC_ID, docId,
          SchemaFields.DOC_UID, docId + "#0",
          SchemaFields.PATH, docId,
          SchemaFields.CONTENT, parentContent,
          SchemaFields.MIME, "text/markdown"));
      int chunkStart = parentContent.indexOf(chunkText);
      Map<String, Object> chunk = new HashMap<>(Map.of(
          SchemaFields.DOC_ID, "chunk:" + docId,
          SchemaFields.DOC_UID, "chunk:" + docId + "#0",
          SchemaFields.PATH, docId,
          SchemaFields.PARENT_DOC_ID, docId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, chunkText,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(chunkStart),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunkStart + chunkText.length())));
      if (collection != null) {
        parent.put(SchemaFields.COLLECTION, collection);
        chunk.put(SchemaFields.COLLECTION, collection);
      }
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(parent));
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(chunk));
    }

    @BeforeEach
    void indexCorpus() throws Exception {
      indexDocWithNonMatchingChunk(
          IN_SCOPE, "project-notes", "Escalation ladder INSCOPEMARKER for on-call.");
      indexDocWithNonMatchingChunk(
          OUT_OF_SCOPE, null, "Escalation ladder OUTOFSCOPEMARKER for on-call.");
      indexDocWithNonMatchingChunk(
          AGENT_DOC, "agent-history", "Escalation ladder AGENTMARKER from a run.");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();
    }

    @Test
    @DisplayName("(a) FULLTEXT_FALLBACK returns only in-scope documents")
    void fallbackLegHonoursTheScope() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("escalation ladder")
              .setTopK(5)
              .addDocIds(IN_SCOPE)
              .addDocIds(OUT_OF_SCOPE)
              .addCollection("project-notes")
              .build());

      assertEquals("FULLTEXT_FALLBACK", response.getRetrievalMode(),
          "fixture must actually exercise the whole-document fallback leg");
      assertTrue(response.getContext().contains("INSCOPEMARKER"),
          "the in-scope document must still be returned: " + response.getContext());
      assertFalse(response.getContext().contains("OUTOFSCOPEMARKER"),
          "the fallback leg must not answer from outside the requested scope: "
              + response.getContext());
    }

    @Test
    @DisplayName("(b) return_full_documents=true honours the scope")
    void returnFullDocumentsHonoursTheScope() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("escalation ladder")
              .setTopK(5)
              .addDocIds(IN_SCOPE)
              .addDocIds(OUT_OF_SCOPE)
              .addCollection("project-notes")
              .setReturnFullDocuments(true)
              .build());

      assertEquals("FULL_DOCUMENT", response.getRetrievalMode(),
          "fixture must actually exercise the return_full_documents leg");
      assertTrue(response.getContext().contains("INSCOPEMARKER"), response.getContext());
      assertFalse(response.getContext().contains("OUTOFSCOPEMARKER"),
          "return_full_documents skips the chunk leg, so the scope must bind here or not at all: "
              + response.getContext());
    }

    @Test
    @DisplayName("811 D-1 residue: the whole-doc legs now also apply the default exclusion")
    void unscopedWholeDocLegStillExcludesAgentHistory() {
      // Behaviour-visible closure, stated plainly: an UNSCOPED request over explicitly-supplied
      // doc_ids used to get agent-history full text back here, even though the chunk leg has
      // refused it since 811 D-1. The two legs now agree.
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("escalation ladder")
              .setTopK(5)
              .addDocIds(OUT_OF_SCOPE)
              .addDocIds(AGENT_DOC)
              .build());

      assertTrue(response.getContext().contains("OUTOFSCOPEMARKER"),
          "an ordinary document is unaffected: " + response.getContext());
      assertFalse(response.getContext().contains("AGENTMARKER"),
          "the default agent-history exclusion must bind on the whole-document leg too: "
              + response.getContext());
    }

    @Test
    @DisplayName("an unscoped request over ordinary docs is unchanged")
    void unscopedOrdinaryRequestUnchanged() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("escalation ladder")
              .setTopK(5)
              .addDocIds(IN_SCOPE)
              .addDocIds(OUT_OF_SCOPE)
              .build());

      assertTrue(response.getContext().contains("INSCOPEMARKER"),
          "a collection-tagged doc is NOT excluded by the default scope — only agent-history is: "
              + response.getContext());
      assertTrue(response.getContext().contains("OUTOFSCOPEMARKER"), response.getContext());
    }
  }

  // ==================== Helper ====================

  /**
   * Stamps a chunk fixture with the revision of the parent content its offsets address -- what
   * {@code ChunkDocumentWriter} writes since tempdoc 931 section C.1, and what the read path checks
   * before re-slicing the chunk's text out of the parent (section E item 5). Without it the chunk
   * reads as textless, which is exactly the guard doing its job on a fixture that never registered
   * its parent.
   */
  private Map<String, Object> withParentRevision(Map<String, Object> chunk) {
    String parentDocId = String.valueOf(chunk.get(SchemaFields.PARENT_DOC_ID));
    String parentContent = parentContentById.get(parentDocId);
    if (parentContent == null) {
      throw new IllegalStateException("No registered parent content for " + parentDocId);
    }
    Map<String, Object> stamped = new HashMap<>(chunk);
    stamped.put(
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
    return stamped;
  }

  private String rememberParentContent(String parentDocId, String content) {
    parentContentById.put(parentDocId, content);
    return content;
  }

  private int chunkStart(String parentDocId, String chunkText) {
    String parentContent = parentContentById.get(parentDocId);
    if (parentContent == null) {
      throw new IllegalStateException("No registered parent content for " + parentDocId);
    }
    int start = parentContent.indexOf(chunkText);
    if (start < 0) {
      throw new IllegalStateException("Chunk text is not a slice of parent " + parentDocId);
    }
    return start;
  }

  private RetrieveContextResponse callRetrieveContext(RetrieveContextRequest request) {
    RetrieveContextResponse response;
    try {
      response = service.retrieveContext(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("RetrieveContext failed: " + e.getMessage());
    }

    assertNotNull(response, "Response should not be null");
    return response;
  }
}
