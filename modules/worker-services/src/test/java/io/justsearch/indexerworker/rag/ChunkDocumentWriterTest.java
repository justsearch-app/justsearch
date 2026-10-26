package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ChunkDocumentWriter (Tier 2)")
final class ChunkDocumentWriterTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  @Test
  @DisplayName("regenerateChunksFromExistingParent writes offsets and inherits metadata")
  void regenerateChunksFromExistingParentWritesOffsetsAndInheritsMetadata() throws Exception {
    String parentDocId = "d:/docs/report.pdf";
    String mime = "application/pdf";
    String mimeBase = "application/pdf";
    String fileKind = "pdf";
    String parentDocUid = "stable-parent-uid";
    long parentTokenCount = 2048L;

    String content = "     " + repeat("lorem ipsum ", 600);
    assertTrue(content.length() > ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS);

    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, parentDocId,
        SchemaFields.DOC_UID, parentDocUid,
        SchemaFields.PATH, parentDocId,
        SchemaFields.CONTENT, content,
        SchemaFields.MIME, mime,
        SchemaFields.MIME_BASE, mimeBase,
        SchemaFields.FILE_KIND, fileKind,
        SchemaFields.LANGUAGE, "en-US",
        SchemaFields.PARENT_TOKEN_COUNT, String.valueOf(parentTokenCount)
    )));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    int regenerated = ChunkDocumentWriter.regenerateChunksFromExistingParent(lifecycle.documentFieldOps(), lifecycle.indexingCoordinator(), parentDocId, content, true);
    assertTrue(regenerated > 0);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    List<ChunkSplitter.Chunk> expected =
        ChunkSplitter.splitWithMetadata(content, ChunkDocumentWriter.CHUNK_TARGET_TOKENS, ChunkDocumentWriter.CHUNK_OVERLAP_TOKENS);
    int expectedChunks = expected.size();
    assertTrue(expectedChunks > 1);

    List<LuceneRuntimeTypes.SearchHit> hits = findChunks(parentDocId);
    assertEquals(expectedChunks, hits.size());

    // Sort by chunk index so we can compare 1:1.
    hits.sort(Comparator.comparingInt(h -> Integer.parseInt(h.fields().get(SchemaFields.CHUNK_INDEX))));

    // ChunkSplitter now returns offsets relative to the original content,
    // so we don't need to add leading whitespace offset separately.
    for (int i = 0; i < expectedChunks; i++) {
      var hit = hits.get(i);
      var fields = hit.fields();

      assertEquals(parentDocId, fields.get(SchemaFields.PARENT_DOC_ID));
      assertEquals(String.valueOf(expectedChunks), fields.get(SchemaFields.CHUNK_TOTAL));

      String chunkIndexStr = fields.get(SchemaFields.CHUNK_INDEX);
      assertNotNull(chunkIndexStr);
      assertEquals(String.valueOf(i), chunkIndexStr);
      assertEquals(parentDocUid + "#" + i, fields.get(SchemaFields.DOC_UID));

      String chunkContent = fields.get(SchemaFields.CHUNK_CONTENT);
      assertNotNull(chunkContent);
      assertEquals(expected.get(i).content(), chunkContent);

      long start = Long.parseLong(fields.get(SchemaFields.CHUNK_START_CHAR));
      long end = Long.parseLong(fields.get(SchemaFields.CHUNK_END_CHAR));
      assertTrue(start >= 0);
      assertTrue(end > start);
      // Offsets from ChunkSplitter are now relative to original content (including leading whitespace)
      assertEquals(expected.get(i).startChar(), start);
      assertEquals(expected.get(i).endChar(), end);

      assertEquals(mime, fields.get(SchemaFields.MIME));
      assertEquals(mimeBase, fields.get(SchemaFields.MIME_BASE));
      assertEquals(fileKind, fields.get(SchemaFields.FILE_KIND));
      assertNotNull(fields.get(SchemaFields.LANGUAGE));
      assertEquals(String.valueOf(parentTokenCount), fields.get(SchemaFields.PARENT_TOKEN_COUNT));

      // Verify offsets slice back to the same chunk text (modulo trim).
      String slice = content.substring((int) start, (int) end).trim();
      assertEquals(chunkContent, slice);
    }
  }

  @Test
  @DisplayName("regenerateChunks writes parent token count from supplied metadata")
  void regenerateChunksWritesParentTokenCountFromMetadata() throws Exception {
    String parentDocId = "d:/docs/guide.md";
    long parentTokenCount = 512L;
    String content = repeat("stage three fusion ", 500);

    int regenerated =
        ChunkDocumentWriter.regenerateChunks(
            lifecycle.documentFieldOps(),
            lifecycle.indexingCoordinator(),
            parentDocId,
            content,
            new ChunkDocumentWriter.ParentChunkMetadata(
                "text/markdown", "text/markdown", "markdown", "en", parentTokenCount, null,
                "parent-uid"),
            true);
    assertTrue(regenerated > 0);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    List<LuceneRuntimeTypes.SearchHit> hits = findChunks(parentDocId);
    assertFalse(hits.isEmpty());
    for (LuceneRuntimeTypes.SearchHit hit : hits) {
      assertEquals(
          String.valueOf(parentTokenCount),
          hit.fields().get(SchemaFields.PARENT_TOKEN_COUNT),
          "chunks must inherit parent_token_count from the supplied metadata");
    }
  }

  @Test
  @DisplayName("chunks inherit the parent's collection tag (tempdoc 811 item 3)")
  void chunksInheritParentCollection() throws Exception {
    // Without this, the default agent-history exclusion cannot bind on the chunk branch at all:
    // QueryFilterBuilder.buildChunkFilterQuery filters on `collection`, and a chunk that carries
    // no tag is invisible to that clause.
    String parentDocId = "d:/agent/session-1.md";
    String content = repeat("session transcript line ", 500);

    int regenerated =
        ChunkDocumentWriter.regenerateChunks(
            lifecycle.documentFieldOps(),
            lifecycle.indexingCoordinator(),
            parentDocId,
            content,
            new ChunkDocumentWriter.ParentChunkMetadata(
                "text/markdown", "text/markdown", "markdown", "en", null,
                SchemaFields.AGENT_HISTORY_COLLECTION, "parent-uid"),
            true);
    assertTrue(regenerated > 0);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    List<LuceneRuntimeTypes.SearchHit> hits = findChunks(parentDocId);
    assertFalse(hits.isEmpty());
    for (LuceneRuntimeTypes.SearchHit hit : hits) {
      assertEquals(
          SchemaFields.AGENT_HISTORY_COLLECTION,
          hit.fields().get(SchemaFields.COLLECTION),
          "every chunk must carry its parent's collection tag");
    }
  }

  @Test
  @DisplayName("regenerateChunksFromExistingParent reads the collection off the parent document")
  void regenerateChunksFromExistingParentInheritsCollection() throws Exception {
    String parentDocId = "d:/agent/session-2.md";
    String content = repeat("session transcript line ", 500);

    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, parentDocId,
        SchemaFields.DOC_UID, "stable-session-uid",
        SchemaFields.PATH, parentDocId,
        SchemaFields.CONTENT, content,
        SchemaFields.MIME, "text/markdown",
        SchemaFields.FILE_KIND, "markdown",
        SchemaFields.COLLECTION, SchemaFields.AGENT_HISTORY_COLLECTION
    )));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    int regenerated =
        ChunkDocumentWriter.regenerateChunksFromExistingParent(
            lifecycle.documentFieldOps(), lifecycle.indexingCoordinator(), parentDocId, content, true);
    assertTrue(regenerated > 0);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    List<LuceneRuntimeTypes.SearchHit> hits = findChunks(parentDocId);
    assertFalse(hits.isEmpty());
    for (LuceneRuntimeTypes.SearchHit hit : hits) {
      assertEquals(
          SchemaFields.AGENT_HISTORY_COLLECTION,
          hit.fields().get(SchemaFields.COLLECTION),
          "the VDU/replay path must inherit the tag from the existing parent document");
    }
  }

  @Test
  @DisplayName("missing parent uid fails before replacing searchable chunks")
  void missingParentUidDoesNotDeleteExistingChunks() throws Exception {
    String parentDocId = "d:/docs/preserved.md";
    String content = repeat("identity-safe replacement ", 500);
    var validMetadata =
        new ChunkDocumentWriter.ParentChunkMetadata(
            "text/markdown", "text/markdown", "markdown", "en", null, null, "parent-uid");

    int initial =
        ChunkDocumentWriter.regenerateChunks(
            lifecycle.documentFieldOps(),
            lifecycle.indexingCoordinator(),
            parentDocId,
            content,
            validMetadata,
            true);
    assertTrue(initial > 1);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    assertEquals(initial, findChunks(parentDocId).size());

    var missingUidMetadata =
        new ChunkDocumentWriter.ParentChunkMetadata(
            "text/markdown", "text/markdown", "markdown", "en", null, null, null);
    assertThrows(
        IllegalStateException.class,
        () ->
            ChunkDocumentWriter.regenerateChunks(
                lifecycle.documentFieldOps(),
                lifecycle.indexingCoordinator(),
                parentDocId,
                content,
                missingUidMetadata,
                true));

    lifecycle.commitOps().maybeRefreshBlocking();
    assertEquals(
        initial,
        findChunks(parentDocId).size(),
        "failing closed on identity must leave the previous chunks searchable");
  }

  @Test
  @DisplayName("index writer canonicalizes parent and chunk ids through redundant path hops")
  void indexChunksUsesTheSameCanonicalParentIdAsTheParentWriter() throws Exception {
    Path directory = java.nio.file.Files.createDirectories(tempDir.resolve("canonical"));
    Path submitted =
        directory.resolve("..").resolve(directory.getFileName()).resolve("document.txt");
    String canonicalParentId = PathNormalizer.normalizeKey(submitted);
    String nonCanonicalParentId =
        PathNormalizer.normalizePath(submitted.toAbsolutePath().toString());
    String content = repeat("canonical parent linkage ", 500);
    ExtractionResult extraction = new ExtractionResult(content, null, "text/plain");
    var metadata =
        new IndexingDocumentOps.ParentIndexMetadata(
            "text/plain", "text/plain", "text", "en", null);

    int indexed =
        IndexingDocumentOps.indexChunks(
            submitted,
            extraction,
            lifecycle.documentFieldOps(),
            lifecycle.indexingCoordinator(),
            metadata,
            null,
            "canonical-parent-uid",
            true);
    assertTrue(indexed > 1);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    assertEquals(indexed, findChunks(canonicalParentId).size());
    assertFalse(canonicalParentId.equals(nonCanonicalParentId), "fixture must detect normalization");
    assertTrue(findChunks(nonCanonicalParentId).isEmpty());
  }

  @Test
  @DisplayName("splade_status is stamped only when rag.chunk_splade.enabled is on (931 §E item 8)")
  void spladeStatusIsStampedOnlyWhenChunkSpladeIsEnabled() throws Exception {
    // The shared chunk-testing catalog omits splade_status, so this case needs one that carries it
    // (same shape as SSOT/catalogs/fields.v1.json: stored=false, docValues=true, role=filter).
    List<FieldCatalogDef.FieldDef> fields =
        new ArrayList<>(FieldCatalogDef.forChunkTesting(0).fields());
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.SPLADE_STATUS, "keyword", false, true, List.of("filter"), null, null,
            false));
    FieldCatalogDef catalog = new FieldCatalogDef("chunk-test+splade-status", fields);

    String content = repeat("chunk splade flag coverage ", 500);
    try (RunningRuntime runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog)
            .atPath(tempDir.resolve("splade-status")).withExecutorRegistrations(testLuceneExecutors())
            .open()) {

      // Flag OFF: no splade_status at all. PENDING would claim outstanding work for a stage every
      // backfill lane refuses to run, and would sit in IndexCountOps#countWithField's denominator
      // forever. Absence is the post-798 "this stage does not apply" encoding.
      int off =
          ChunkDocumentWriter.regenerateChunks(
              runtime.documentFieldOps(),
              runtime.indexingCoordinator(),
              "d:/docs/flag-off.md",
              content,
              new ChunkDocumentWriter.ParentChunkMetadata(
                  "text/markdown", "text/markdown", "markdown", "en", null, null, "parent-uid-off"),
              false);
      assertTrue(off > 1, "precondition: the fixture produces several chunks");
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(
          0,
          runtime
              .indexCountOps()
              .countByField(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING),
          "flag off: no chunk may carry splade_status=PENDING");

      // Flag ON: the stage applies, so the chunk is enrolled as PENDING for the backfill lanes.
      int on =
          ChunkDocumentWriter.regenerateChunks(
              runtime.documentFieldOps(),
              runtime.indexingCoordinator(),
              "d:/docs/flag-on.md",
              content,
              new ChunkDocumentWriter.ParentChunkMetadata(
                  "text/markdown", "text/markdown", "markdown", "en", null, null, "parent-uid-on"),
              true);
      assertTrue(on > 1);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(
          on,
          runtime
              .indexCountOps()
              .countByField(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING),
          "flag on: exactly the chunks written under the flag are enrolled as PENDING");
    }
  }

  @Test
  void invalidLaterPlanEntriesLeaveCommittedChildrenAndVectorsUntouched() throws Exception {
    lifecycle.close();
    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(3))
        .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    String parent = "invalid-plan-parent";
    String oldContent = "oldlexical ".repeat(600);
    seedDeclaredParent(parent, oldContent);
    var oldChunks = findChunks(parent);
    String oldId = oldChunks.get(0).docId();
    assertTrue(lifecycle.indexingCoordinator().updateDocument(oldId, Map.of(
        SchemaFields.CHUNK_VECTOR, new float[] {1, 0, 0},
        SchemaFields.CHUNK_EMBEDDING_STATUS, "COMPLETED",
        SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "7")));
    flush();
    String source = "\uD83D\uDE00".repeat(3000);
    String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(source);
    var first = ChunkSplitter.Chunk.of(0, source.substring(0, 2), 0, 2);
    var invalid = List.of(
        ChunkSplitter.Chunk.of(1, source.substring(2, 3), 2, 3),
        ChunkSplitter.Chunk.of(1, source.substring(3, 4), 3, 4),
        ChunkSplitter.Chunk.of(1, "wrong slice", 2, 4),
        ChunkSplitter.Chunk.of(9, source.substring(2, 4), 2, 4),
        ChunkSplitter.Chunk.of(1, "", 2, 2),
        ChunkSplitter.Chunk.of(1, " ", 2, 4),
        ChunkSplitter.Chunk.of(1, "invalid", -1, 2),
        ChunkSplitter.Chunk.of(1, "invalid", 2, source.length() + 1));
    for (var later : invalid) {
      var plan = new ChunkDocumentWriter.ChunkPlan(revision, List.of(first, later));
      assertThrows(IllegalArgumentException.class, () -> ChunkDocumentWriter.replaceDeclaredChunks(
          lifecycle.indexingCoordinator(), parent, source, chunkMetadata(), plan, false));
      flush();
      assertEquals(oldChunks.stream().map(LuceneRuntimeTypes.SearchHit::docId).sorted().toList(),
          findChunks(parent).stream().map(LuceneRuntimeTypes.SearchHit::docId).sorted().toList());
      assertEquals(oldContent, lifecycle.documentFieldOps().getDocumentField(parent, SchemaFields.CONTENT));
      assertEquals(oldChunks.get(0).fields().get(SchemaFields.CHUNK_CONTENT),
          findChunks(parent).stream().filter(hit -> hit.docId().equals(oldId)).findFirst().orElseThrow()
              .fields().get(SchemaFields.CHUNK_CONTENT));
      assertEquals("COMPLETED", lifecycle.documentFieldOps().getDocumentField(oldId, SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("7", lifecycle.documentFieldOps().getDocumentField(oldId, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      try (var directory = org.apache.lucene.store.FSDirectory.open(tempDir);
          var reader = new org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper(
              org.apache.lucene.index.DirectoryReader.open(directory), SchemaFields.SOFT_DELETE)) {
        var searcher = new org.apache.lucene.search.IndexSearcher(reader);
        var hit = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, oldId)), 1).scoreDocs[0];
        var leaf = reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(hit.doc, reader.leaves()));
        var vector = leaf.reader().getFloatVectorValues(SchemaFields.CHUNK_VECTOR);
        assertNotNull(vector);
        var iterator = vector.iterator();
        assertEquals(hit.doc - leaf.docBase, iterator.advance(hit.doc - leaf.docBase));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {1, 0, 0}, vector.vectorValue(iterator.index()));
      }
    }
    var wrongRevision = new ChunkDocumentWriter.ChunkPlan("f".repeat(64), List.of(first));
    assertThrows(IllegalArgumentException.class, () -> ChunkDocumentWriter.replaceDeclaredChunks(
        lifecycle.indexingCoordinator(), parent, source, chunkMetadata(), wrongRevision, false));
  }

  @Test
  void invalidPlanIsRefusedBeforeParentAndLexicalPublication() throws Exception {
    String parent = "prepublication-parent";
    String oldContent = "oldlexical ".repeat(600);
    seedDeclaredParent(parent, oldContent);
    String newContent = "newlexical " + "\uD83D\uDE00".repeat(3000);
    var good = ChunkDocumentWriter.plan(newContent, null, null);
    var chunks = new ArrayList<>(good.chunks());
    var last = chunks.get(chunks.size() - 1);
    chunks.set(chunks.size() - 1, ChunkSplitter.Chunk.of(last.index(), "wrong slice", last.startChar(), last.endChar()));
    var bad = new ChunkDocumentWriter.ChunkPlan(good.revision(), chunks);
    var candidate = new IndexDocument(Map.of(SchemaFields.DOC_ID, parent, SchemaFields.DOC_UID, "parent-uid",
        SchemaFields.CONTENT, newContent, SchemaFields.CONTENT_SHA256, good.revision()));
    assertThrows(IllegalArgumentException.class, () -> lifecycle.indexingCoordinator().indexSingle(
        IndexingDocumentOps.withChunkDeclaration(candidate, bad)));
    flush();
    assertEquals(oldContent, lifecycle.documentFieldOps().getDocumentField(parent, SchemaFields.CONTENT));
    assertEquals(io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(oldContent),
        lifecycle.documentFieldOps().getDocumentField(parent, SchemaFields.CONTENT_SHA256));
    assertEquals(String.valueOf(findChunks(parent).size()),
        lifecycle.documentFieldOps().getDocumentField(parent, SchemaFields.CHUNK_TOTAL));
    assertEquals(0, lifecycle.readPathOps().search(new TermQuery(new Term(SchemaFields.CONTENT, "newlexical")),
        10, Set.of(SchemaFields.CONTENT), LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null).hits().size());
    assertEquals(1, lifecycle.readPathOps().search(new TermQuery(new Term(SchemaFields.CONTENT, "oldlexical")),
        10, Set.of(SchemaFields.CONTENT), LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null).hits().size());
  }

  @Test
  void supplementaryChunksAreExactAndPendingAfterFreshWriteRegenerationAndReopen() throws Exception {
    lifecycle.close();
    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(3))
        .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    String parent = "unicode-parent";
    String content = "a" + "\uD83D\uDE00\uD840\uDC00".repeat(3000);
    seedDeclaredParent(parent, content);
    var oldIds = findChunks(parent).stream().map(LuceneRuntimeTypes.SearchHit::docId).toList();
    assertTrue(lifecycle.indexingCoordinator().updateDocument(oldIds.get(0), Map.of(
        SchemaFields.CHUNK_VECTOR, new float[] {1, 0, 0},
        SchemaFields.CHUNK_EMBEDDING_STATUS, "COMPLETED",
        SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "7")));
    flush();
    int total = ChunkDocumentWriter.regenerateChunksFromExistingParent(lifecycle.documentFieldOps(),
        lifecycle.indexingCoordinator(), parent, content, false);
    assertTrue(total > 1);
    flush();
    lifecycle.close();
    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(3))
        .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    try (var directory = org.apache.lucene.store.FSDirectory.open(tempDir);
        var reader = new org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper(
            org.apache.lucene.index.DirectoryReader.open(directory), SchemaFields.SOFT_DELETE)) {
      for (var leaf : reader.leaves()) {
        var vectors = leaf.reader().getFloatVectorValues(SchemaFields.CHUNK_VECTOR);
        if (vectors == null) continue;
        var iterator = vectors.iterator();
        for (int doc = iterator.nextDoc(); doc != Integer.MAX_VALUE; doc = iterator.nextDoc()) {
          assertFalse(leaf.reader().getLiveDocs() == null || leaf.reader().getLiveDocs().get(doc),
              "no old ordinal vector may survive replacement");
        }
      }
    }
    var hits = findChunks(parent);
    assertEquals(total, hits.size());
    for (var hit : hits) {
      assertFalse(oldIds.contains(hit.docId()), "replacement artifacts get fresh identities");
      int start = Integer.parseInt(hit.fields().get(SchemaFields.CHUNK_START_CHAR));
      int end = Integer.parseInt(hit.fields().get(SchemaFields.CHUNK_END_CHAR));
      assertFalse(start > 0 && Character.isHighSurrogate(content.charAt(start - 1))
          && Character.isLowSurrogate(content.charAt(start)));
      assertFalse(end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))
          && Character.isLowSurrogate(content.charAt(end)));
      assertEquals(content.substring(start, end), hit.fields().get(SchemaFields.CHUNK_CONTENT));
      assertEquals("PENDING", lifecycle.documentFieldOps().getDocumentField(hit.docId(), SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("0", lifecycle.documentFieldOps().getDocumentField(hit.docId(), SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
    }
  }

  @Test
  void partialReplacementAndEmptyPlanCleanupRetainCompletenessAfterReopen() throws Exception {
    String parent = "completeness-parent";
    for (String replacement : List.of("one short chunk", "")) {
      seedDeclaredParent(parent, "\uD83D\uDE00".repeat(3000));
      assertTrue(lifecycle.documentFieldOps().isUnmodified(parent, 123));
      lifecycle.indexingCoordinator().deleteById(findChunks(parent).get(0).docId());
      flush();
      reopenWithoutVectors();
      assertFalse(lifecycle.documentFieldOps().isUnmodified(parent, 123), "partial sets remain incomplete after reopen");
      var plan = ChunkDocumentWriter.plan(replacement, null, null);
      assertEquals(0, plan.total());
      lifecycle.indexingCoordinator().indexSingle(IndexingDocumentOps.withChunkDeclaration(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parent, SchemaFields.DOC_UID, "parent-uid", SchemaFields.PATH, parent,
          SchemaFields.CONTENT, replacement, SchemaFields.CONTENT_SHA256, plan.revision(),
          SchemaFields.MODIFIED_AT, 123L)), plan));
      assertEquals(0, ChunkDocumentWriter.replaceDeclaredChunks(lifecycle.indexingCoordinator(), parent,
          replacement, null, plan, false));
      flush();
      reopenWithoutVectors();
      assertEquals(0, findChunks(parent).size());
      assertEquals("0", lifecycle.documentFieldOps().getDocumentField(parent, SchemaFields.CHUNK_TOTAL));
      assertTrue(lifecycle.documentFieldOps().isUnmodified(parent, 123));
    }
  }

  private void reopenWithoutVectors() {
    lifecycle.close();
    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static ChunkDocumentWriter.ParentChunkMetadata chunkMetadata() {
    return new ChunkDocumentWriter.ParentChunkMetadata(null, null, "text", "en", null, null, "parent-uid");
  }

  private void seedDeclaredParent(String parent, String content) {
    var plan = ChunkDocumentWriter.plan(content, null, null);
    lifecycle.indexingCoordinator().indexSingle(IndexingDocumentOps.withChunkDeclaration(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, parent, SchemaFields.DOC_UID, "parent-uid", SchemaFields.PATH, parent,
        SchemaFields.CONTENT, content, SchemaFields.CONTENT_SHA256, plan.revision(), SchemaFields.MODIFIED_AT, 123L)), plan));
    ChunkDocumentWriter.replaceDeclaredChunks(lifecycle.indexingCoordinator(), parent, content, chunkMetadata(), plan, false);
    flush();
  }

  private void flush() {
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  private List<LuceneRuntimeTypes.SearchHit> findChunks(String parentDocId) {
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER);
    qb.add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)), BooleanClause.Occur.FILTER);
    Query q = qb.build();

    Set<String> projection =
        Set.of(
            SchemaFields.PARENT_DOC_ID,
            SchemaFields.DOC_UID,
            SchemaFields.CHUNK_INDEX,
            SchemaFields.CHUNK_TOTAL,
            SchemaFields.CHUNK_CONTENT,
            SchemaFields.CHUNK_START_CHAR,
            SchemaFields.CHUNK_END_CHAR,
            SchemaFields.MIME,
            SchemaFields.MIME_BASE,
            SchemaFields.FILE_KIND,
            SchemaFields.LANGUAGE,
            SchemaFields.COLLECTION,
            SchemaFields.PARENT_TOKEN_COUNT);

    var result = lifecycle.readPathOps().search(q, 10_000, projection, LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
    return new ArrayList<>(result.hits());
  }

  private static String repeat(String s, int times) {
    StringBuilder sb = new StringBuilder(s.length() * Math.max(0, times));
    for (int i = 0; i < times; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
