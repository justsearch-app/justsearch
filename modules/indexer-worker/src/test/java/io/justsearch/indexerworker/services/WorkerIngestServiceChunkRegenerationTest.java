package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
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
import org.slf4j.LoggerFactory;

@DisplayName("WorkerIngestService chunk regeneration (Tier 2)")
final class WorkerIngestServiceChunkRegenerationTest {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private RunningRuntime lifecycle;
  private WorkerIngestService service;
  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();

    IndexingLoop stubLoop = new StubIndexingLoop();
    WorkerSignalBus stubBus = new StubWorkerSignalBus();
    Path indexBasePath = tempDir.resolve("indexBase");
    Files.createDirectories(indexBasePath);
    Path indexPath = tempDir.resolve("index");
    Files.createDirectories(indexPath);
    service = new WorkerIngestService(
        jobQueue, stubLoop, stubBus, IndexingPacing.unthrottled(), indexBasePath, indexPath,
        lifecycle, lifecycle, null, 0L, null);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  @DisplayName("updateVduResult regenerates chunks with offsets + metadata")
  void updateVduResultRegeneratesChunksWithOffsetsAndMetadata() throws Exception {
    String docId = PathNormalizer.normalizeKey(tempDir.resolve("report.pdf"));
    String parentDocUid = "00000000-0000-4000-8000-000000000042";
    String mime = "application/pdf";
    String mimeBase = "application/pdf";
    String fileKind = "pdf";

    String content =
        " \t\r\n# Unicode 🚀 heading\r\n```java\r\nString emoji = \"🧪\";\r\n```\r\n"
            + repeat("lorem 🐝 ipsum\r\n", 600)
            + "\r\n  ";
    assertTrue(content.length() > ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS);

    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, docId,
        SchemaFields.DOC_UID, parentDocUid,
        SchemaFields.PATH, docId,
        SchemaFields.CONTENT, "placeholder",
        SchemaFields.MIME, mime,
        SchemaFields.MIME_BASE, mimeBase,
        SchemaFields.FILE_KIND, fileKind,
        SchemaFields.LANGUAGE, "en-US"
    )));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    UpdateVduResultRequest request =
        UpdateVduResultRequest.newBuilder()
            .setDocId(docId)
            .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
            .setExtractedContent(content)
            .build();

    UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

    assertNotNull(response);
    assertTrue(response.getSuccess());

    List<ChunkSplitter.Chunk> expected =
        ChunkSplitter.splitWithMetadata(content, ChunkDocumentWriter.CHUNK_TARGET_TOKENS, ChunkDocumentWriter.CHUNK_OVERLAP_TOKENS);
    int expectedChunks = expected.size();
    assertTrue(expectedChunks > 1);

    List<LuceneRuntimeTypes.SearchHit> hits = findChunks(docId);
    assertEquals(expectedChunks, hits.size());
    hits.sort(Comparator.comparingInt(h -> Integer.parseInt(h.fields().get(SchemaFields.CHUNK_INDEX))));

    // ChunkSplitter now returns offsets relative to the original content,
    // so we don't need to add leading whitespace offset separately.
    for (int i = 0; i < expectedChunks; i++) {
      var fields = hits.get(i).fields();
      assertEquals(docId, fields.get(SchemaFields.PARENT_DOC_ID));
      assertEquals(String.valueOf(expectedChunks), fields.get(SchemaFields.CHUNK_TOTAL));
      assertEquals(String.valueOf(i), fields.get(SchemaFields.CHUNK_INDEX));
      assertEquals(parentDocUid + "#" + i, fields.get(SchemaFields.DOC_UID));
      assertEquals(expected.get(i).content(), fields.get(SchemaFields.CHUNK_CONTENT));

      long start = Long.parseLong(fields.get(SchemaFields.CHUNK_START_CHAR));
      long end = Long.parseLong(fields.get(SchemaFields.CHUNK_END_CHAR));
      assertEquals(expected.get(i).startChar(), start);
      assertEquals(expected.get(i).endChar(), end);
      assertEquals(
          content.substring(Math.toIntExact(start), Math.toIntExact(end)),
          fields.get(SchemaFields.CHUNK_CONTENT),
          "reconstructed chunk text must equal the exact parent-content slice without trimming");

      assertEquals(mime, fields.get(SchemaFields.MIME));
      assertEquals(mimeBase, fields.get(SchemaFields.MIME_BASE));
      assertEquals(fileKind, fields.get(SchemaFields.FILE_KIND));
      assertNotNull(fields.get(SchemaFields.LANGUAGE));
    }

    String normalizedId = IngestResponses.normalizeDocIdForMutation(docId);
    assertTrue(
        jobQueue.putSwitchBuffer(
            IngestResponses.switchBufferVduUpdateKey(normalizedId),
            "VDU_UPDATE",
            IngestResponses.updateVduSwitchBufferPayload(request, normalizedId)));
    assertEquals(1, jobQueue.listSwitchBufferOps().size());

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            jobQueue,
            lifecycle,
            new StubWorkerSignalBus(),
            IndexingPacing.unthrottled(),
            tempDir.resolve("indexBase"),
            tempDir.resolve("index"),
            new tools.jackson.databind.ObjectMapper(),
            () -> true,
            LoggerFactory.getLogger(WorkerIngestServiceChunkRegenerationTest.class)));
    lifecycle.commitOps().maybeRefreshBlocking();

    assertTrue(
        jobQueue.listSwitchBufferOps().isEmpty(),
        "a successfully committed replay must clear its durable operation");
    assertEquals(
        parentDocUid,
        lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.DOC_UID),
        "buffered VDU replay must preserve the parent identity");

    List<LuceneRuntimeTypes.SearchHit> regenerated = findChunks(docId);
    regenerated.sort(
        Comparator.comparingInt(h -> Integer.parseInt(h.fields().get(SchemaFields.CHUNK_INDEX))));
    assertEquals(expectedChunks, regenerated.size());
    for (int i = 0; i < regenerated.size(); i++) {
      assertEquals(
          parentDocUid + "#" + i,
          regenerated.get(i).fields().get(SchemaFields.DOC_UID),
          "chunk identity must be deterministic across live and buffered VDU regeneration");
    }
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
            SchemaFields.LANGUAGE);

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

  private static final class StubIndexingLoop extends IndexingLoop {
    StubIndexingLoop() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public long getLastCommitTime() {
      return System.currentTimeMillis();
    }

    @Override
    public String getCurrentState() {
      return "IDLE";
    }

    @Override
    public void start() {}

    @Override
    public void close() {}
  }

  private static final class StubWorkerSignalBus implements WorkerSignalBus {
    private final long startupTime = System.currentTimeMillis();

    @Override
    public void open() {}

    @Override
    public void writePort(int port) {}

    @Override
    public long readHeartbeat() {
      return System.currentTimeMillis();
    }

    @Override
    public boolean isShutdownRequested() {
      return false;
    }

    @Override
    public boolean shouldDie() {
      return false;
    }

    @Override
    public boolean isMainGpuActive() {
      return false;
    }

    @Override
    public long startupTime() {
      return startupTime;
    }

    @Override
    public void close() {}
  }
}
