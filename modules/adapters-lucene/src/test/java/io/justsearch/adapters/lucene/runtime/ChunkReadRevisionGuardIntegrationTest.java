package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §E item 5 — the read-path twin of the chunk RMW revision guard.
 *
 * <p>{@code chunk_content} is not stored, so every read re-slices a chunk's text out of its
 * parent's stored {@code content}. Parent write and chunk regeneration are separate coordinator
 * calls; in between, an equal-length parent rewrite leaves the OLD offsets fitting the NEW text.
 * These tests drive exactly that state and assert the reader returns nothing rather than a slice of
 * the wrong revision.
 */
final class ChunkReadRevisionGuardIntegrationTest extends LuceneExecutorTestBase {

  private static final String PARENT_OLD = "alpha beta";
  private static final String PARENT_NEW = "gamma zeta";

  private RunningRuntime runtime;
  private CountingTelemetry telemetry;
  private String prevConfig;
  private Path tempDir;
  private Path cfg;

  @BeforeEach
  void setup() throws Exception {
    assertEquals(
        PARENT_OLD.length(),
        PARENT_NEW.length(),
        "precondition: the rewrite is exactly the length the old offsets still fit");
    prevConfig = System.getProperty("justsearch.config");
    tempDir = Files.createTempDirectory("justsearch-chunk-readguard-");
    String yaml =
        "app:\n  data_dir: "
            + tempDir.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: readguardtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    cfg = Files.createTempFile("justsearch-readguard-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    telemetry = new CountingTelemetry();
    runtime.session().telemetryEvents = telemetry;
  }

  @AfterEach
  void cleanup() throws Exception {
    if (runtime != null) runtime.close();
    if (prevConfig == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", prevConfig);
    }
    if (cfg != null) Files.deleteIfExists(cfg);
  }

  /**
   * The parent-level {@code content_sha256} moves with the content (tempdoc 931 §C.6), so this is
   * the shape a production VDU re-extraction leaves behind before chunk regeneration runs.
   */
  @Test
  void aRewrittenParentStopsChunkReadsUntilTheChunksAreRegenerated() throws Exception {
    indexParent("parent-0", PARENT_OLD);
    indexChunk("chunk-0", "parent-0", PARENT_OLD, "alpha", 0, 5);
    indexChunk("chunk-1", "parent-0", PARENT_OLD, "beta", 6, 10);
    commit();

    assertEquals("alpha", runtime.documentFieldOps().getDocumentContent("chunk-0"));
    assertEquals("alpha", projectedChunkContent("chunk-0"));
    assertEquals(0, telemetry.mismatches, "an in-sync parent must not count a mismatch");

    rewriteParent("parent-0", PARENT_NEW);

    telemetry.mismatches = 0;
    assertNull(
        runtime.documentFieldOps().getDocumentContent("chunk-0"),
        "getDocumentContent must not return 'gamma', the newer revision's slice");
    assertEquals(1, telemetry.mismatches, "one count per inconsistent chunk read");

    telemetry.mismatches = 0;
    assertNull(
        projectedChunkContent("chunk-0"),
        "the search-hit projection must carry no chunk_content rather than wrong text");
    assertEquals(1, telemetry.mismatches);

    telemetry.mismatches = 0;
    Map<String, String> batch =
        runtime.documentFieldOps().getDocumentContentBatch(java.util.List.of("chunk-0", "chunk-1"));
    assertTrue(batch.isEmpty(), "neither chunk of the rewritten parent reconstructs");
    assertEquals(2, telemetry.mismatches, "counted once per chunk, not once per parent");

    // Regeneration: the chunks are re-cut from the parent revision that is actually there now.
    indexChunk("chunk-0", "parent-0", PARENT_NEW, "gamma", 0, 5);
    indexChunk("chunk-1", "parent-0", PARENT_NEW, "zeta", 6, 10);
    commit();

    telemetry.mismatches = 0;
    assertEquals("gamma", runtime.documentFieldOps().getDocumentContent("chunk-0"));
    assertEquals("zeta", runtime.documentFieldOps().getDocumentContent("chunk-1"));
    assertEquals("gamma", projectedChunkContent("chunk-0"));
    assertEquals(0, telemetry.mismatches, "reads succeed again once the chunks are regenerated");
  }

  /**
   * A parent written before {@code content_sha256} existed carries no stored revision, so the guard
   * has to hash its content to decide. The refusal must be identical.
   */
  @Test
  void aParentWithoutAStoredRevisionIsStillCheckedByHashingItsContent() throws Exception {
    indexParentWithoutRevision("parent-0", PARENT_OLD);
    indexChunk("chunk-0", "parent-0", PARENT_OLD, "alpha", 0, 5);
    commit();

    assertEquals("alpha", runtime.documentFieldOps().getDocumentContent("chunk-0"));
    assertEquals(0, telemetry.mismatches);

    assertTrue(
        runtime
            .indexingCoordinator()
            .updateDocument("parent-0", Map.of(SchemaFields.CONTENT, PARENT_NEW)));
    commit();

    telemetry.mismatches = 0;
    assertNull(runtime.documentFieldOps().getDocumentContent("chunk-0"));
    assertEquals(1, telemetry.mismatches);
  }

  /** A chunk written before the revision field existed has no identity to check — fail closed. */
  @Test
  void aChunkWithoutARevisionIdentityIsRefusedEvenWhenTheParentIsUnchanged() throws Exception {
    indexParent("parent-0", PARENT_OLD);
    Map<String, Object> chunk = chunkFields("chunk-0", "parent-0", "alpha", 0, 5);
    chunk.remove(SchemaFields.CHUNK_PARENT_CONTENT_SHA256);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
    commit();

    assertNull(runtime.documentFieldOps().getDocumentContent("chunk-0"));
    assertEquals(1, telemetry.mismatches);
  }

  private String projectedChunkContent(String chunkDocId) throws Exception {
    var result =
        runtime
            .readPathOps()
            .search(
                new TermQuery(new Term(SchemaFields.DOC_ID, chunkDocId)),
                5,
                Set.of(SchemaFields.CHUNK_CONTENT, SchemaFields.CHUNK_INDEX),
                null,
                null);
    assertEquals(1, result.hits().size(), "the chunk document itself must still be findable");
    return result.hits().getFirst().fields().get(SchemaFields.CHUNK_CONTENT);
  }

  private void rewriteParent(String parentId, String content) {
    assertTrue(
        runtime
            .indexingCoordinator()
            .updateDocument(
                parentId,
                Map.of(
                    SchemaFields.CONTENT,
                    content,
                    SchemaFields.CONTENT_SHA256,
                    ChunkParentRevision.sha256Hex(content))));
    commit();
  }

  private void indexParent(String parentId, String content) {
    Map<String, Object> doc = new HashMap<>();
    doc.put(SchemaFields.DOC_ID, parentId);
    doc.put(SchemaFields.DOC_UID, parentId + "#0");
    doc.put(SchemaFields.PATH, parentId);
    doc.put(SchemaFields.CONTENT, content);
    doc.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
  }

  private void indexParentWithoutRevision(String parentId, String content) {
    Map<String, Object> doc = new HashMap<>();
    doc.put(SchemaFields.DOC_ID, parentId);
    doc.put(SchemaFields.DOC_UID, parentId + "#0");
    doc.put(SchemaFields.PATH, parentId);
    doc.put(SchemaFields.CONTENT, content);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
  }

  private void indexChunk(
      String chunkId, String parentId, String parentContent, String text, int start, int end) {
    Map<String, Object> chunk = chunkFields(chunkId, parentId, text, start, end);
    chunk.put(
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
  }

  private static Map<String, Object> chunkFields(
      String chunkId, String parentId, String text, int start, int end) {
    Map<String, Object> chunk = new HashMap<>();
    chunk.put(SchemaFields.DOC_ID, chunkId);
    chunk.put(SchemaFields.DOC_UID, chunkId + "#0");
    chunk.put(SchemaFields.PATH, parentId);
    chunk.put(SchemaFields.IS_CHUNK, "true");
    chunk.put(SchemaFields.PARENT_DOC_ID, parentId);
    chunk.put(SchemaFields.CHUNK_INDEX, "0");
    chunk.put(SchemaFields.CHUNK_TOTAL, "2");
    chunk.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(start));
    chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(end));
    chunk.put(SchemaFields.CHUNK_CONTENT, text);
    return chunk;
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static final class CountingTelemetry implements LuceneRuntimeTypes.TelemetryEvents {
    private int mismatches;

    @Override
    public void onChunkRevisionMismatch(int count) {
      mismatches += count;
    }
  }
}
