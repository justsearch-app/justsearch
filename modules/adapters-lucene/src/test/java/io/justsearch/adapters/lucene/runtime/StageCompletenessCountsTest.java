/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.StageCompletenessCounts;
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
 * Tempdoc 821 §3-C3 — {@link IndexCountOps#queryStageCompletenessCounts()}, the count primitive
 * the enrichment completeness auditor projects over.
 *
 * <p>Fixture: whole documents in mixed enrichment states, some carrying an actual {@code vector}
 * and some not, plus chunk documents — so the ARTIFACT tier (count the vector) and the STATUS tier
 * (count the bookkeeping field) can be shown to DISAGREE. That disagreement is the whole point:
 * the F-032 class is a status field reading COMPLETED while the artifact is absent.
 *
 * <p>The two adverse cases are the ones that make the numbers subtractable rather than merely
 * present: an artifact carried by a doc with NO status field (would push present above expected on
 * an installed-base index) and a terminal-FAILED doc that still carries its vector (would let one
 * doc sit in two buckets and understate the repair backlog).
 */
@DisplayName("enrichment stage completeness counts")
class StageCompletenessCountsTest extends LuceneExecutorTestBase {

  private static final String SEP = File.separator;
  private static final String ROOT = SEP + "lib" + SEP + "stage";
  private static final float[] VEC = {0.1f, 0.2f, 0.3f, 0.4f};

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
            + "    - name: stagecompleteness\n"
            + "      roots: ['ignored']\n"
            + "  vector:\n"
            + "    dimension: 4\n"
            // The F-032 shape (embedding_status=COMPLETED with no vector) cannot be written
            // FRESH under FAIL mode — StatusArtifactContract rejects it (711/714), and rightly
            // so. It arises later, from an RMW that drops the artifact while the status survives,
            // or from an index written before that contract existed. WARN mode is how this
            // fixture reproduces that already-on-disk state; it is the state the presence count
            // exists to expose, so a fixture that cannot express it would test nothing.
            + "  validation:\n"
            + "    mode: warn\n");
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

  // ---- artifactPresent: the ARTIFACT-tier numerator --------------------------------

  @Test
  @DisplayName("artifactPresent counts the artifact, not the status — and over the SAME population")
  void artifactPresentCountsTheArtifactOverTheStatusCarryingPopulation() {
    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();

    // 4 parents carry embedding_status; p1/p2 carry a vector, p3 claims COMPLETED without one,
    // p4 is FAILED without one.
    assertEquals(4, stages.embedding().expected());
    assertEquals(2, stages.embedding().artifactPresent());
    // p3 reads embedding_status=COMPLETED with NO vector — the F-032 shape. A status-derived
    // numerator would read 3 here, which is exactly the lie this count exists to stop.
    assertEquals(
        3,
        runtime.indexCountOps().queryEmbeddingCounts().completed(),
        "the status field claims one more COMPLETED than there are vectors — the divergence is"
            + " the diagnostic signal, so these two must NOT agree on this fixture");
    // Chunk scope is separate and its vectors are a different field: the parents' vectors must
    // not leak in, nor the chunks' out.
    assertEquals(3, stages.chunkEmbedding().expected());
    assertEquals(1, stages.chunkEmbedding().artifactPresent());
  }

  @Test
  @DisplayName("adverse: an artifact WITHOUT the status field is outside the stage entirely")
  void artifactWithoutStatusFieldIsNotCounted() {
    // The installed-base shape: a document written before embedding_status existed still carries
    // its vector. Counting it would make artifactPresent exceed expected — present 3 of 4 — and
    // the remainder would floor to 0, hiding the real backlog behind a clamp.
    index(parent("legacy-vec", ROOT + SEP + "legacy-vec.txt", null, null, null, true));
    commit();

    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(4, stages.embedding().expected(), "no embedding_status ⇒ outside the stage");
    assertEquals(
        2,
        stages.embedding().artifactPresent(),
        "its vector must NOT be counted — numerator and denominator share one population");
    assertPartitions(stages);
  }

  @Test
  @DisplayName("adverse: a FAILED doc that still carries its vector is failed, not present")
  void failedDocRetainingItsArtifactIsNotCountedPresent() {
    // Reachable on disk: EmbeddingBackfillOps writes FAILED via an RMW that resets the status and
    // LEAVES the vector. Counting such a doc in both buckets understates `missing` by one per
    // overlap — the exact under-reporting this surface exists to prevent.
    index(parent("failed-vec", ROOT + SEP + "failed-vec.txt", "FAILED", "FAILED", "FAILED", true));
    commit();

    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(5, stages.embedding().expected());
    assertEquals(2, stages.embedding().failed(), "p4 + the new one");
    assertEquals(
        2,
        stages.embedding().artifactPresent(),
        "a FAILED doc is reported as failed even though its vector survived — never as present");
    assertPartitions(stages);
  }

  @Test
  @DisplayName("a deleted-but-unmerged doc does not inflate artifactPresent")
  void deletedDocsAreExcluded() {
    assertEquals(2, runtime.indexCountOps().queryStageCompletenessCounts()
        .embedding().artifactPresent());

    // No forceMerge: the KNN structures still hold the tombstoned doc's vector, so a raw
    // FloatVectorValues walk would still count it. searcher.count intersects with liveDocs.
    runtime.indexingCoordinator().deleteById("p1");
    commit();

    StageCompletenessCounts after = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(1, after.embedding().artifactPresent(), "the deleted doc must not be counted");
    assertEquals(3, after.embedding().expected(), "and it leaves the denominator too");
    assertPartitions(after);
  }

  @Test
  @DisplayName("a segment that never indexed the vector field contributes zero, not an error")
  void segmentWithoutTheVectorFieldContributesZero() {
    // A fresh segment holding only vectorless documents: FieldExistsQuery must match nothing
    // there rather than throw or perturb the other segment's count.
    index(parent("p5", ROOT + SEP + "e.txt", "PENDING", "PENDING", "PENDING", false));
    index(parent("p6", ROOT + SEP + "f.txt", "PENDING", "PENDING", "PENDING", false));
    commit();

    StageCompletenessCounts after = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(2, after.embedding().artifactPresent(), "the vectorless segment adds nothing");
    assertEquals(6, after.embedding().expected(), "but its documents do count in the denominator");
  }

  @Test
  @DisplayName("counts are reader-version cached and invalidated by a commit")
  void countsAreCachedPerReaderVersion() {
    StageCompletenessCounts first = runtime.indexCountOps().queryStageCompletenessCounts();
    assertSame(
        first,
        runtime.indexCountOps().queryStageCompletenessCounts(),
        "the status path polls every couple of seconds — one reader version must serve one result");

    index(parent("p7", ROOT + SEP + "g.txt", "COMPLETED", "COMPLETED", "COMPLETED", true));
    commit();

    StageCompletenessCounts third = runtime.indexCountOps().queryStageCompletenessCounts();
    assertNotSame(third, first, "a new reader version must invalidate the cached instance");
    assertEquals(3, third.embedding().artifactPresent());
  }

  // ---- queryStageCompletenessCounts ---------------------------------------------

  @Test
  @DisplayName("expected is the field-carrying denominator; success and failure are separate")
  void stageCountsSplitTerminalSuccessFromFailure() {
    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();

    // embedding_status: p1 COMPLETED, p2 COMPLETED, p3 COMPLETED, p4 FAILED.
    assertEquals(4, stages.embedding().expected());
    assertEquals(3, stages.embedding().settledSuccess());
    assertEquals(1, stages.embedding().failed(), "FAILED is NOT folded into settledSuccess");

    // splade_status: p1 COMPLETED, p2 COMPLETED_EMPTY, p3 PENDING, p4 FAILED.
    assertEquals(4, stages.splade().expected());
    assertEquals(
        2, stages.splade().settledSuccess(), "COMPLETED_EMPTY is a terminal SPLADE success");
    assertEquals(1, stages.splade().failed());

    // ner_status: p1 COMPLETED, p2 COMPLETED_EMPTY, p3 COMPLETED, p4 FAILED.
    assertEquals(4, stages.ner().expected());
    assertEquals(3, stages.ner().settledSuccess());
    assertEquals(1, stages.ner().failed(), "the count the wire lacked before 821 §3-C3");

    // chunk_embedding_status is its own scope: 3 chunks, never the parents.
    assertEquals(3, stages.chunkEmbedding().expected(), "parents must not enter the chunk scope");
    assertEquals(1, stages.chunkEmbedding().settledSuccess());
    assertEquals(1, stages.chunkEmbedding().failed());
  }

  @Test
  @DisplayName("a stage's absent status field withdraws the document from its denominator")
  void absentStatusFieldWithdrawsTheDocument() {
    // Post-798: a document the backfill can never select (it selects by status VALUE) must not sit
    // in a denominator forever — otherwise `missing` never reaches zero and the auditor cries wolf.
    index(parent("legacy", ROOT + SEP + "legacy.txt", "COMPLETED", null, null, true));
    commit();

    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(5, stages.embedding().expected(), "it DOES carry embedding_status");
    assertEquals(4, stages.embedding().settledSuccess());
    assertEquals(4, stages.splade().expected(), "no splade_status ⇒ outside that denominator");
    assertEquals(4, stages.ner().expected(), "no ner_status ⇒ outside that denominator");
  }

  @Test
  @DisplayName("an empty index reports zeros, never a vacuous complete")
  void emptyIndexReportsZeros() throws Exception {
    runtime.close();
    runtime = openRuntime(tempDir.resolve("index-empty"));

    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(0, stages.embedding().expected());
    assertEquals(0, stages.embedding().artifactPresent());
    assertEquals(0, stages.chunkEmbedding().expected());
    assertEquals(0, stages.chunkEmbedding().artifactPresent());
    assertPartitions(stages);
  }

  /**
   * The invariant the whole surface rests on: for every stage, the artifact/status numerator plus
   * failures never exceeds the denominator, so the projection's {@code missing} needs no clamp.
   */
  private static void assertPartitions(StageCompletenessCounts stages) {
    assertTrue(
        stages.embedding().artifactPresent() + stages.embedding().failed()
            <= stages.embedding().expected(),
        "embedding: artifactPresent + failed must not exceed expected");
    assertTrue(
        stages.chunkEmbedding().artifactPresent() + stages.chunkEmbedding().failed()
            <= stages.chunkEmbedding().expected(),
        "chunk_embed: artifactPresent + failed must not exceed expected");
    assertTrue(
        stages.splade().settledSuccess() + stages.splade().failed() <= stages.splade().expected(),
        "splade: settledSuccess + failed must not exceed expected");
    assertTrue(
        stages.ner().settledSuccess() + stages.ner().failed() <= stages.ner().expected(),
        "ner: settledSuccess + failed must not exceed expected");
  }

  // ---- fixture ------------------------------------------------------------------

  private void seedCorpus() {
    String a = ROOT + SEP + "a.txt";
    String b = ROOT + SEP + "b.txt";
    String c = ROOT + SEP + "c.txt";
    String d = ROOT + SEP + "d.txt";

    index(parent("p1", a, "COMPLETED", "COMPLETED", "COMPLETED", true));
    index(parent("p2", b, "COMPLETED", "COMPLETED_EMPTY", "COMPLETED_EMPTY", true));
    // The F-032 shape: the status says COMPLETED, the artifact is absent.
    index(parent("p3", c, "COMPLETED", "PENDING", "COMPLETED", false));
    index(parent("p4", d, "FAILED", "FAILED", "FAILED", false));

    index(chunk("c1", a, "COMPLETED", true));
    index(chunk("c2", a, "PENDING", false));
    // A FAILED chunk whose vector survived the status reset — the overlap case, seeded here so
    // every stage-count assertion in this file runs against it, not just the adverse test.
    index(chunk("c3", b, "FAILED", true));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void index(IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  /** A {@code null} status omits the FIELD — the "this stage does not apply" shape (post-798). */
  private static IndexDocument parent(
      String id, String path, String embedding, String splade, String ner, boolean withVector) {
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
    if (withVector) {
      fields.put(SchemaFields.VECTOR, VEC.clone());
    }
    return new IndexDocument(fields);
  }

  private static IndexDocument chunk(
      String id, String parentPath, String chunkEmbedding, boolean withVector) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, parentPath);
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.CONTENT, "chunk of " + id);
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, chunkEmbedding);
    if (withVector) {
      fields.put(SchemaFields.CHUNK_VECTOR, VEC.clone());
    }
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
              { "id": "ner_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "roles": ["vector"], "rmwPolicy": "preserve-reread-or-reset:embedding_status", "vector": { "dimension": 4 } },
              { "id": "chunk_vector", "type": "vector", "stored": false, "docValues": false, "roles": ["chunk_vector"], "rmwPolicy": "preserve-reread-or-reset:chunk_embedding_status", "vector": { "dimension": 4 } }
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
