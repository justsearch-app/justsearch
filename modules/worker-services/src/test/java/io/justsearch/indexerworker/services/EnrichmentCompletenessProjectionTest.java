/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.EnrichmentCoverage;
import io.justsearch.ipc.StageCompleteness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 821 §3-C3 — the per-stage completeness projection on {@code /api/status}.
 *
 * <p>What it exists for: the sibling {@code coveragePercent} fields divide by EVERY document, so a
 * stage that silently lost a sub-population reads identically to one with nothing to do. The
 * projection answers the different question — "of the documents this stage applies to, how many
 * actually have their artifact?" — with {@code missing} as the number an operator acts on.
 *
 * <p>These assertions are deliberately arithmetic rather than "the field is present": the whole
 * value of the surface is that {@code expected - present - failed} is a number a repair op could
 * be pointed at, and a projection that mis-sums is worse than none.
 */
@DisplayName("enrichment completeness projection (/api/status)")
final class EnrichmentCompletenessProjectionTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final float[] VEC = {0.1f, 0.2f, 0.3f, 0.4f};

  @TempDir Path tempDir;

  private RunningRuntime runtime;
  private String previousConfig;

  @BeforeEach
  void setUp() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path config = tempDir.resolve("config.yaml");
    Files.writeString(
        config,
        "app:\n  data_dir: "
            + dataDir.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: completenessprojection\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n"
            // The F-032 shape (embedding_status=COMPLETED with no vector) cannot be written FRESH
            // under FAIL mode — StatusArtifactContract rejects it (711/714), and rightly so. It
            // arises later, from an RMW that drops the artifact while the status survives, or from
            // an index written before that contract existed. WARN mode is how this fixture
            // reproduces that already-on-disk state — the exact state this projection exists to
            // make visible, so a fixture that could not express it would test nothing.
            + "  validation:\n    mode: warn\n");
    System.setProperty("justsearch.config", config.toString());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    if (previousConfig == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", previousConfig);
    }
  }

  @Test
  @DisplayName("artifact-tier stages count the vector; status-tier stages declare that they cannot")
  void perStageArithmeticAndTierDeclarations() throws Exception {
    runtime = open("mixed");
    // 4 parents. Vectors on p1/p2 only — p3 is the F-032 shape (embedding_status=COMPLETED with
    // no vector) and p4 is terminally FAILED.
    index(parent("p1", "COMPLETED", "COMPLETED", "COMPLETED", true));
    index(parent("p2", "COMPLETED", "COMPLETED_EMPTY", "COMPLETED_EMPTY", true));
    index(parent("p3", "COMPLETED", "PENDING", "PENDING", false));
    index(parent("p4", "FAILED", "FAILED", "FAILED", false));
    // 3 chunks; one carries its vector.
    index(chunk("c1", "p1", "COMPLETED", true));
    index(chunk("c2", "p1", "PENDING", false));
    index(chunk("c3", "p2", "FAILED", false));
    commit();

    Map<String, StageCompleteness> byStage = completenessByStage();
    assertEquals(
        List.of("embed", "chunk_embed", "splade", "ner"),
        new ArrayList<>(byStage.keySet()),
        "all four enrichment stages report, in a stable order");

    StageCompleteness embed = byStage.get("embed");
    assertEquals("ARTIFACT", embed.getTier(), "doc vectors are countable, so the tier is honest");
    assertEquals(4, embed.getExpected(), "every parent carries embedding_status");
    assertEquals(2, embed.getPresent(), "the ARTIFACT tier counts vectors, NOT the status field");
    assertEquals(1, embed.getFailed());
    // p3: status COMPLETED, vector absent. `missing` is the number that makes it actionable —
    // a status-derived surface would report 0 here and the sub-population would stay lost.
    assertEquals(1, embed.getMissing(), "expected - present - failed");

    StageCompleteness chunkEmbed = byStage.get("chunk_embed");
    assertEquals("ARTIFACT", chunkEmbed.getTier());
    assertEquals(3, chunkEmbed.getExpected(), "the chunk scope, not the parents'");
    assertEquals(1, chunkEmbed.getPresent());
    assertEquals(1, chunkEmbed.getFailed());
    assertEquals(1, chunkEmbed.getMissing());

    StageCompleteness splade = byStage.get("splade");
    // SPLADE writes a postings/feature field (docValues:false in fields.v1.json), so there is no
    // artifact to count. Declaring STATUS is the honest move; declaring ARTIFACT would imply a
    // verification that did not happen. NER likewise writes no countable per-doc artifact.
    assertEquals("STATUS", splade.getTier());
    assertEquals(4, splade.getExpected());
    assertEquals(2, splade.getPresent(), "COMPLETED + COMPLETED_EMPTY are terminal successes");
    assertEquals(1, splade.getFailed());
    assertEquals(1, splade.getMissing(), "p3 is still PENDING");

    StageCompleteness ner = byStage.get("ner");
    assertEquals("STATUS", ner.getTier());
    assertEquals(4, ner.getExpected());
    assertEquals(2, ner.getPresent());
    assertEquals(1, ner.getFailed());
    assertEquals(1, ner.getMissing());

    for (StageCompleteness s : byStage.values()) {
      assertEquals(
          s.getExpected(),
          s.getPresent() + s.getMissing() + s.getFailed(),
          s.getStageId() + ": the three buckets must partition `expected` exactly");
    }
  }

  @Test
  @DisplayName("adverse: an artifact without the status field cannot push present above expected")
  void artifactWithoutStatusFieldDoesNotInflatePresent() throws Exception {
    runtime = open("legacyvec");
    index(parent("p1", "COMPLETED", "COMPLETED", "COMPLETED", true));
    index(parent("p2", "PENDING", "PENDING", "PENDING", false));
    // The installed-base shape: written before embedding_status existed, still carrying its
    // vector. If `present` were counted over a different population than `expected`, this index
    // would report present=2 of expected=2 while p2 is genuinely unembedded — and `missing` would
    // floor to 0, which is the floor hiding the bug rather than the bug being impossible.
    index(parentWithoutStatuses("legacy-vec", true));
    commit();

    StageCompleteness embed = completenessByStage().get("embed");
    assertEquals(2, embed.getExpected(), "the status-less doc is outside the stage entirely");
    assertEquals(1, embed.getPresent(), "and its vector is not counted");
    assertEquals(0, embed.getFailed());
    assertEquals(1, embed.getMissing(), "p2 is the real backlog and must be reported as such");
  }

  @Test
  @DisplayName("adverse: a FAILED doc that kept its vector is reported failed, never present")
  void failedDocRetainingItsVectorIsNotDoubleCounted() throws Exception {
    runtime = open("failedvec");
    index(parent("p1", "COMPLETED", "COMPLETED", "COMPLETED", true));
    index(parent("p2", "PENDING", "PENDING", "PENDING", false));
    // Reachable on disk: EmbeddingBackfillOps writes FAILED through an RMW that resets the status
    // and LEAVES the vector. Counting it in both present and failed would make missing read 0
    // while p2 still needs work — one doc understating the backlog by one.
    index(parent("p3", "FAILED", "FAILED", "FAILED", true));
    commit();

    StageCompleteness embed = completenessByStage().get("embed");
    assertEquals(3, embed.getExpected());
    assertEquals(1, embed.getPresent(), "the FAILED doc's surviving vector is not `present`");
    assertEquals(1, embed.getFailed());
    assertEquals(1, embed.getMissing(), "p2 — not 0, which the overlap would have produced");
    assertEquals(
        embed.getExpected(),
        embed.getPresent() + embed.getMissing() + embed.getFailed(),
        "the buckets partition exactly; no clamp is involved");
  }

  @Test
  @DisplayName("failedNerCount reaches the wire alongside the other three stages' failure counts")
  void nerFailuresAreReportedSymmetrically() throws Exception {
    runtime = open("nerfail");
    index(parent("p1", "COMPLETED", "COMPLETED", "FAILED", true));
    index(parent("p2", "COMPLETED", "COMPLETED", "FAILED", true));
    index(parent("p3", "COMPLETED", "COMPLETED", "COMPLETED", true));
    commit();

    EnrichmentCoverage enrichment = buildStatus().getEnrichment();
    // Before 821 §3-C3 NER reported pending + completed only, so a permanently stalled NER
    // sub-population was invisible on this surface while its three siblings reported theirs.
    assertEquals(2, enrichment.getFailedNerCount());
    assertEquals(1, enrichment.getCompletedNerCount());
    assertEquals(0, enrichment.getPendingNerCount());
  }

  @Test
  @DisplayName("the thresholds the auditor owns are published, not left for consumers to mirror")
  void thresholdsArePublished() throws Exception {
    runtime = open("thresholds");
    index(parent("p1", "COMPLETED", "COMPLETED", "COMPLETED", true));
    commit();

    EnrichmentCoverage enrichment = buildStatus().getEnrichment();
    // Sourced from the Java constant, so a change there moves the wire — which is the point:
    // jseval's chunk-completeness oracle reads this instead of carrying its own copy of 2000.
    assertEquals(ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS, enrichment.getChunkMinChars());
    assertTrue(enrichment.getChunkMinChars() > 0, "a zero would read as 'not published'");
    // The same number `chunk.vectorsReady` is evaluated against — published so a consumer can
    // explain the boolean instead of hard-coding the bar.
    assertEquals(95.0, enrichment.getVectorReadyPercent(), 0.001);
  }

  @Test
  @DisplayName("an empty index reports zeros for every stage, never a vacuous complete")
  void emptyIndexReportsZeros() throws Exception {
    runtime = open("empty");
    commit();

    Map<String, StageCompleteness> byStage = completenessByStage();
    assertEquals(4, byStage.size(), "the stages still report; they are just all zero");
    for (StageCompleteness s : byStage.values()) {
      assertEquals(0, s.getExpected(), s.getStageId());
      assertEquals(0, s.getPresent(), s.getStageId());
      assertEquals(0, s.getMissing(), s.getStageId() + ": 0 expected means 0 missing, not 'done'");
    }
  }

  // ---- fixture ------------------------------------------------------------------

  private Map<String, StageCompleteness> completenessByStage() {
    List<StageCompleteness> list = buildStatus().getEnrichment().getCompletenessList();
    assertNotNull(list);
    return list.stream()
        .collect(
            Collectors.toMap(
                StageCompleteness::getStageId,
                Function.identity(),
                (a, b) -> {
                  throw new AssertionError("duplicate stage id: " + a.getStageId());
                },
                java.util.LinkedHashMap::new));
  }

  private io.justsearch.ipc.StatusResponse buildStatus() {
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(jobQueue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);

    return new IndexStatusOps(
            jobQueue,
            tempDir,
            runtime.indexCountOps(),
            runtime.indexCountOps(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            OperationalMetrics.getInstance(),
            mock(IndexingLoop.class),
            mock(WorkerSignalBus.class),
            0L)
        .buildStatusResponse();
  }

  /** The chunk-testing catalog plus the two status fields it omits. */
  private RunningRuntime open(String name) throws Exception {
    FieldCatalogDef base = FieldCatalogDef.forChunkTesting(VEC.length);
    List<FieldCatalogDef.FieldDef> fields = new ArrayList<>(base.fields());
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.SPLADE_STATUS, "keyword", true, true, List.of("filter"), null, null, false));
    fields.add(
        new FieldCatalogDef.FieldDef(
            SchemaFields.NER_STATUS, "keyword", true, true, List.of("filter"), null, null, false));
    return IndexSchema.fromCatalog(new FieldCatalogDef(base.version() + "+status", fields))
        .atPath(tempDir.resolve(name)).withExecutorRegistrations(testLuceneExecutors())
        .open();
  }

  private void index(IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static IndexDocument parent(
      String id, String embedding, String splade, String ner, boolean withVector) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, "/lib/" + id + ".txt");
    fields.put(SchemaFields.CONTENT, "content of " + id);
    fields.put(SchemaFields.EMBEDDING_STATUS, embedding);
    fields.put(SchemaFields.SPLADE_STATUS, splade);
    fields.put(SchemaFields.NER_STATUS, ner);
    if (withVector) {
      fields.put(SchemaFields.VECTOR, VEC.clone());
    }
    return new IndexDocument(fields);
  }

  /** A document carrying its vector but NO status field — the pre-status-field installed base. */
  private static IndexDocument parentWithoutStatuses(String id, boolean withVector) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, "/lib/" + id + ".txt");
    fields.put(SchemaFields.CONTENT, "content of " + id);
    if (withVector) {
      fields.put(SchemaFields.VECTOR, VEC.clone());
    }
    return new IndexDocument(fields);
  }

  private static IndexDocument chunk(
      String id, String parentId, String chunkEmbedding, boolean withVector) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, "/lib/" + parentId + ".txt");
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.PARENT_DOC_ID, parentId);
    fields.put(SchemaFields.CONTENT, "chunk " + id);
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, chunkEmbedding);
    if (withVector) {
      fields.put(SchemaFields.CHUNK_VECTOR, VEC.clone());
    }
    return new IndexDocument(fields);
  }
}
