package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RootCoverageCounts;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 813 Slice A — {@link IndexCountOps#queryRootCoverageCounts(String)}.
 *
 * <p>Fixture: two sibling roots whose names share a prefix ({@code /lib/foo} and {@code
 * /lib/foobar}) so the boundary-safety property is exercised, each holding parent documents in
 * mixed enrichment states plus chunk documents carrying their parent's path.
 */
@DisplayName("per-root enrichment coverage counts")
class RootCoverageCountsTest extends LuceneExecutorTestBase {

  private static final String SEP = File.separator;
  /** All-lowercase like a production {@code PathNormalizer.normalizePath} result. */
  private static final String ROOT_FOO = SEP + "lib" + SEP + "foo";

  private static final String ROOT_FOOBAR = SEP + "lib" + SEP + "foobar";

  @TempDir Path tempDir;

  @RegisterExtension
  SystemPropertyExtension sysprops = new SystemPropertyExtension("justsearch.config");

  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    Path config = tempDir.resolve("config.yaml");
    Files.writeString(
        config,
        "app:\n  data_dir: "
            + dataDir.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n"
            + "  collections:\n"
            + "    - name: rootcoverage\n"
            + "      roots: ['ignored']\n"
            + "  vector:\n"
            + "    dimension: 4\n");
    System.setProperty("justsearch.config", config.toString());
    runtime = openRuntime(tempDir.resolve("index"));
    seedCorpus();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  @DisplayName("parent denominator excludes chunks; settled numerator counts terminal states")
  void parentAndChunkTiersAreCountedSeparately() {
    RootCoverageCounts foo = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO);

    // 3 parents under /lib/foo. The 3 chunks under it and the /lib/foobar parent are excluded.
    assertEquals(3, foo.parentDocsTotalEmbedding(), "parent denominator must exclude chunk docs");
    assertEquals(3, foo.parentDocsTotalSplade());
    assertEquals(3, foo.parentDocsTotalNer());
    // p1 COMPLETED + p3 FAILED are terminal; p2 PENDING is not. embedding_status has no
    // COMPLETED_EMPTY member, so its terminal set is two-valued.
    assertEquals(2, foo.parentDocsSettledEmbedding());
    // p1 COMPLETED + p2 COMPLETED_EMPTY are terminal; p3 PENDING is not.
    assertEquals(
        2, foo.parentDocsSettledSplade(), "COMPLETED_EMPTY is a terminal SPLADE success");
    // p1 COMPLETED + p2 COMPLETED_EMPTY + p3 FAILED — every NER state here is terminal.
    assertEquals(
        3, foo.parentDocsSettledNer(), "a permanently FAILED stage must count as settled");
    // Chunk tier is its own denominator, never folded into the parent ratio.
    assertEquals(3, foo.chunkDocsTotal());
    assertEquals(2, foo.chunkDocsSettled(), "chunk COMPLETED + FAILED are terminal, PENDING is not");
  }

  @Test
  @DisplayName("/lib/foo does not match the sibling /lib/foobar")
  void siblingPrefixIsExcluded() {
    RootCoverageCounts foo = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO);
    RootCoverageCounts foobar = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOOBAR);

    // The whole index holds 4 parents and 4 chunks; if the trailing-separator boundary were
    // missing, "/lib/foo" would swallow foobar's documents too.
    assertEquals(3, foo.parentDocsTotalEmbedding(), "foobar's parent must not leak into foo");
    assertEquals(3, foo.chunkDocsTotal(), "foobar's chunk must not leak into foo");
    assertEquals(1, foobar.parentDocsTotalEmbedding());
    assertEquals(1, foobar.chunkDocsTotal());
    assertEquals(0, foobar.parentDocsSettledEmbedding(), "foobar's parent is still PENDING");
    assertEquals(0, foobar.chunkDocsSettled());
  }

  @Test
  @DisplayName("a trailing separator on the caller's prefix changes nothing")
  void trailingSeparatorIsIdempotent() {
    assertEquals(
        runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO),
        runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO + SEP));
  }

  @Test
  @DisplayName("a blank prefix returns zero rather than matching the whole index")
  void blankPrefixReturnsEmpty() {
    // Normalizing "" would yield a bare separator, which on Linux prefixes every absolute path.
    assertEquals(RootCoverageCounts.EMPTY, runtime.indexCountOps().queryRootCoverageCounts(""));
    assertEquals(RootCoverageCounts.EMPTY, runtime.indexCountOps().queryRootCoverageCounts(null));
    assertEquals(RootCoverageCounts.EMPTY, runtime.indexCountOps().queryRootCoverageCounts("   "));
  }

  @Test
  @DisplayName("case folding follows the platform's path semantics")
  void caseFoldingMatchesPlatform() {
    RootCoverageCounts shouted =
        runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO.toUpperCase(java.util.Locale.ROOT));
    if (PlatformPaths.isWindows()) {
      assertEquals(
          3,
          shouted.parentDocsTotalEmbedding(),
          "Windows paths are case-insensitive — must still match");
    } else {
      assertEquals(
          0, shouted.parentDocsTotalEmbedding(), "POSIX paths are case-sensitive — must not match");
    }
  }

  @Test
  @DisplayName("counts are reader-version cached and invalidated by a commit")
  void countsAreCachedPerReaderVersion() {
    RootCoverageCounts first = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO);
    RootCoverageCounts second = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO);
    assertSame(first, second, "same reader version must serve the cached instance");

    index(parent("p4", ROOT_FOO + SEP + "d.txt", "PENDING", "PENDING", "PENDING"));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    RootCoverageCounts third = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO);
    assertNotSame(third, first, "a new reader version must invalidate the cached instance");
    assertEquals(4, third.parentDocsTotalEmbedding(), "the newly committed parent must be visible");
  }

  @Test
  @DisplayName("a doc with no status field for a stage is in neither that stage's numerator nor "
      + "its denominator")
  void anAbsentStatusFieldWithdrawsTheDocumentFromThatStage() {
    RootCoverageCounts before = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO);
    assertEquals(3, before.parentDocsTotalNer());
    assertEquals(3, before.parentDocsTotalSplade());

    // A document indexed before splade_status / ner_status existed: it carries embedding_status
    // only. Those backfills select by status VALUE, so nothing will ever settle it — counting it
    // in their denominators would pin this folder below 100% forever (post-798: an absent status
    // field means the stage does not apply to the document).
    index(parent("legacy", ROOT_FOO + SEP + "legacy.txt", "COMPLETED", null, null));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    RootCoverageCounts after = runtime.indexCountOps().queryRootCoverageCounts(ROOT_FOO);
    assertEquals(
        4, after.parentDocsTotalEmbedding(), "it DOES carry embedding_status — that stage applies");
    assertEquals(3, after.parentDocsSettledEmbedding(), "its COMPLETED embedding is terminal");
    assertEquals(
        3, after.parentDocsTotalSplade(), "no splade_status ⇒ outside the SPLADE denominator");
    assertEquals(2, after.parentDocsSettledSplade(), "and outside its numerator");
    assertEquals(3, after.parentDocsTotalNer(), "no ner_status ⇒ outside the NER denominator");
    assertEquals(3, after.parentDocsSettledNer(), "and outside its numerator");
  }

  // ---- fixture ----------------------------------------------------------------

  private void seedCorpus() {
    String a = ROOT_FOO + SEP + "a.txt";
    String b = ROOT_FOO + SEP + "sub" + SEP + "b.txt";
    String c = ROOT_FOO + SEP + "c.txt";
    String barA = ROOT_FOOBAR + SEP + "a.txt";

    index(parent("p1", a, "COMPLETED", "COMPLETED", "COMPLETED"));
    index(parent("p2", b, "PENDING", "COMPLETED_EMPTY", "COMPLETED_EMPTY"));
    index(parent("p3", c, "FAILED", "PENDING", "FAILED"));
    index(parent("bar1", barA, "PENDING", "PENDING", "PENDING"));

    // A chunk's PATH is its PARENT's normalized absolute file path (ChunkDocumentWriter writes
    // SchemaFields.PATH = parentDocId), which is what makes prefix filtering valid on chunks.
    index(chunk("c1", a, "COMPLETED"));
    index(chunk("c2", a, "PENDING"));
    index(chunk("c3", b, "FAILED"));
    index(chunk("bar-c1", barA, "PENDING"));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void index(IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  /** A {@code null} status omits the FIELD — the "this stage does not apply" shape (post-798). */
  private static IndexDocument parent(
      String id, String path, String embedding, String splade, String ner) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, path);
    fields.put(SchemaFields.CONTENT, "content of " + id);
    if (embedding != null) {
      fields.put(SchemaFields.EMBEDDING_STATUS, embedding);
    }
    if (splade != null) {
      fields.put(SchemaFields.SPLADE_STATUS, splade);
    }
    if (ner != null) {
      fields.put(SchemaFields.NER_STATUS, ner);
    }
    return new IndexDocument(fields);
  }

  private static IndexDocument chunk(String id, String parentPath, String chunkEmbedding) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, parentPath);
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.CONTENT, "chunk of " + id);
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, chunkEmbedding);
    return new IndexDocument(fields);
  }

  private RunningRuntime openRuntime(Path indexDir) {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "chunk_embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "splade_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "ner_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] }
            ]
          }
          """;
      var fieldMapper = new FieldMapper(new ObjectMapper().readTree(json));
      return new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .atPath(indexDir).withExecutorRegistrations(testLuceneExecutors())
          .open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
