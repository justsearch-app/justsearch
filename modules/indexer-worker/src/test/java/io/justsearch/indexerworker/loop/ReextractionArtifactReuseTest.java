/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.loop.ops.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** Real extraction, queue, replacement publication and bounded enrichment; models are counted doubles. */
final class ReextractionArtifactReuseTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void identicalPolicyReextractionDoesNotRepeatInference(boolean migration) throws Exception {
    Path file = Files.writeString(temporary.resolve("source.txt"), "searchable reuse evidence. ".repeat(500));
    try (var h = new Harness(migration)) {
      h.ingest(file, false);
      assertTrue(h.enrich().getOrDefault("chunk_embed.computed", 0L) > 0);
      var oldChildren = h.children(file);
      String text = h.runtime.documentFieldOps().getDocumentContent(PathNormalizer.normalizeKey(file));
      h.policyChanged(file);
      clearInvocations(h.embedding);
      h.ingest(file, false);
      assertEquals(text, h.runtime.documentFieldOps().getDocumentContent(PathNormalizer.normalizeKey(file)));
      assertTrue(Collections.disjoint(oldChildren, h.children(file)), "replacement publishes fresh child IDs");
      assertEquals(Map.of(), h.enrich(), "E1/E5: no repeated text inference after identical extraction");
      verify(h.embedding, never()).embedDocumentBatch(anyList());
      h.assertSearch();
      for (String stage : List.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_STATUS,
          SchemaFields.NER_STATUS, SchemaFields.SPLADE_STATUS)) {
        assertTrue(h.runtime.documentFieldOps().queryDocIdsByField(stage, "PENDING", 1000).isEmpty(),
            "M4: carried work is done: " + stage);
      }
    }
  }

  @Test
  void unchunkedReuseSurvivesRestartAndNextScanDoesNotExtract() throws Exception {
    Path file = Files.writeString(temporary.resolve("short.txt"), "searchable short evidence");
    try (var h = new Harness(false)) {
      h.ingest(file, false); h.enrich();
      h.policyChanged(file); h.ingest(file, false);
      assertEquals(Map.of(), h.enrich());
    }
    try (var h = new Harness(false)) {
      String key = PathNormalizer.normalizeKey(file);
      assertEquals("COMPLETED", h.runtime.documentFieldOps().getDocumentField(key, SchemaFields.EMBEDDING_STATUS));
      h.assertSearch();
      clearInvocations(h.parser);
      h.ingest(file, false);
      verify(h.parser, never()).extractArtifact(file);
      assertEquals(Map.of(), h.enrich(), "M3/M6: committed reuse is stable after restart and scan");
      h.policyChanged(file); h.ingest(file, false);
      verify(h.parser).extractArtifact(file);
      assertEquals(Map.of(), h.enrich());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitForceStillRecomputesEveryStage(boolean migration) throws Exception {
    Path file = Files.writeString(temporary.resolve("force.txt"), "forced repair evidence. ".repeat(500));
    try (var h = new Harness(migration)) {
      h.ingest(file, false); h.enrich();
      clearInvocations(h.embedding);
      h.ingest(file, true);
      if (migration) verify(h.embedding).embedDocumentBatch(List.of(
          h.runtime.documentFieldOps().getDocumentContent(PathNormalizer.normalizeKey(file))));
      else verify(h.embedding, never()).embedDocumentBatch(anyList());
      Map<String, Long> computed = h.enrich();
      assertTrue(computed.getOrDefault("chunk_embed.computed", 0L) > 0, "E7: passage inference repeats");
      assertTrue(computed.getOrDefault("ner.computed", 0L) > 0, "E7: entity inference repeats");
      assertTrue(computed.getOrDefault("splade.computed", 0L) > 0, "E7: sparse inference repeats");
      if (!migration) assertEquals(1L, computed.get("embed.computed"));
    }
  }

  @Test
  void changedTextRecomputesOnlyNonmatchingChunks() throws Exception {
    String original = "searchable reuse evidence. ".repeat(500);
    Path file = Files.writeString(temporary.resolve("partial.txt"), original);
    try (var h = new Harness(false)) {
      h.ingest(file, false); h.enrich();
      int total = h.children(file).size();
      Files.writeString(file, "differentx" + original.substring(10));
      h.ingest(file, false);
      var computed = h.enrich();
      assertEquals(1L, computed.get("embed.computed"));
      assertEquals(1L, computed.get("ner.computed"));
      long chunks = computed.getOrDefault("chunk_embed.computed", 0L);
      assertTrue(chunks > 0 && chunks < total, "E2: only changed slices recompute: " + computed + ", total=" + total);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mime", "file_kind", "chunk_start_char", "chunk_heading_text"})
  void incompatibleFormatOrChunkLayoutRecomputes(String field) throws Exception {
    Path file = Files.writeString(temporary.resolve("layout.txt"), "searchable layout evidence. ".repeat(500));
    try (var h = new Harness(false)) {
      h.ingest(file, false); h.enrich();
      String parent = PathNormalizer.normalizeKey(file);
      boolean chunk = field.startsWith("chunk_");
      String target = chunk ? h.children(file).getFirst() : parent;
      h.runtime.indexingCoordinator().updateDocument(target,
          Map.of(field, field.equals("chunk_start_char") ? "1" : "different"));
      h.policyChanged(file); h.ingest(file, false);
      var computed = h.enrich();
      assertTrue(computed.getOrDefault("chunk_embed.computed", 0L) > 0, "E2: changed layout/format recomputes");
      if (!chunk) assertEquals(1L, computed.get("embed.computed"));
    }
  }

  @Test
  void incompleteOldFamilyTakesOrdinaryReplacement() throws Exception {
    Path file = Files.writeString(temporary.resolve("incomplete.txt"), "searchable family evidence. ".repeat(500));
    try (var h = new Harness(false)) {
      h.ingest(file, false); h.enrich();
      int total = h.children(file).size();
      h.runtime.indexingCoordinator().deleteById(h.children(file).getFirst()); h.refresh();
      h.policyChanged(file); h.ingest(file, false);
      assertEquals((long) total, h.enrich().get("chunk_embed.computed"), "E2: an incomplete family cannot certify reuse");
    }
  }

  @Test
  void failedStagesGetFreshBudgetsWhileSuccessfulPeersReuse() throws Exception {
    Path file = Files.writeString(temporary.resolve("failed.txt"), "searchable failure evidence. ".repeat(500));
    try (var h = new Harness(false)) {
      h.ingest(file, false); h.enrich();
      String child = h.children(file).getFirst();
      var failure = new HashMap<String, Object>();
      failure.put(SchemaFields.CHUNK_EMBEDDING_STATUS, "FAILED");
      failure.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "3"); failure.put(SchemaFields.CHUNK_VECTOR, null);
      h.runtime.indexingCoordinator().updateDocument(child, failure);
      h.policyChanged(file); h.ingest(file, false);
      var pending = h.runtime.documentFieldOps().queryDocIdsByField(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING", 1000);
      assertEquals(1, pending.size());
      assertEquals("0", h.runtime.documentFieldOps().getDocumentField(pending.getFirst(), SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals(Map.of("chunk_embed.computed", 1L), h.enrich(), "E2/M2: failed stage repeats, successful stages do not");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "splade", "ner", "embedding-assets", "splade-assets", "ner-assets"})
  void changedModelIdentityNeverReusesThatStage(String change) throws Exception {
    Path file = Files.writeString(temporary.resolve("identity.txt"), "searchable identity evidence. ".repeat(500));
    try (var h = new Harness(true)) {
      h.ingest(file, false); h.enrich();
      h.changeModel(change);
      // A metadata commit must not retrospectively certify old artifacts under the new model.
      h.policyChanged(file);
      clearInvocations(h.embedding);
      h.ingest(file, false);
      if (change.startsWith("embedding")) verify(h.embedding).embedDocumentBatch(List.of(
          h.runtime.documentFieldOps().getDocumentContent(PathNormalizer.normalizeKey(file))));
      else verify(h.embedding, never()).embedDocumentBatch(anyList());
      var computed = h.enrich();
      if (change.startsWith("embedding")) {
        verify(h.embedding, atLeastOnce()).embedDocumentBatch(anyList());
        assertTrue(computed.getOrDefault("chunk_embed.computed", 0L) > 0);
        assertFalse(computed.containsKey("ner.computed"));
      } else {
        verify(h.embedding, never()).embedDocumentBatch(anyList());
        assertTrue(computed.getOrDefault(change.startsWith("ner") ? "ner.computed" : "splade.computed", 0L) > 0);
        assertFalse(computed.containsKey("chunk_embed.computed"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void legacyCompanionAdoptionReusesWhatTheProductServes(boolean adoptedBeforeRestart) throws Exception {
    Path file = Files.writeString(temporary.resolve("legacy-assets.txt"), "searchable historical model evidence. ".repeat(500));
    try (var h = new Harness(false, "index", false)) {
      h.ingest(file, false); h.enrich();
    }
    if (adoptedBeforeRestart) {
      try (var h = new Harness(false)) {
        // Adoption publishes current companion metadata, without running any model for old rows.
        h.refresh();
      }
    }
    try (var h = new Harness(false)) {
      long documents = h.runtime.indexCountOps().docCountOrThrow();
      int completed = h.runtime.indexCountOps().countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, "COMPLETED");
      var compatibility = new EmbeddingCompatibilityController(h.runtime::latestCommitUserDataBestEffort,
          () -> documents, () -> completed, () -> Optional.of("a".repeat(64)),
          () -> Optional.of((String) h.currentMetadata.get("embedding_model_assets_sha256")));
      compatibility.refresh();
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, compatibility.state());
      h.policyChanged(file); h.ingest(file, false);
      var computed = h.enrich();
      assertEquals(Map.of(), computed,
          "E3: reuse accepts companion adoption already accepted by the product");
      verify(h.embedding, never()).embedDocumentBatch(anyList());
      verify(h.sparse, never()).encodeBatch(anyList());
      verify(h.ner, never()).extractEntitiesBatch(anyList());
      h.assertSearch();
    }
  }

  @Test
  void newExtractionDropsOmittedMetadataAndVisualAttempts() throws Exception {
    Path file = Files.writeString(temporary.resolve("metadata.txt"), "searchable metadata evidence. ".repeat(500));
    try (var h = new Harness(false)) {
      h.ingest(file, false); h.enrich();
      String parent = PathNormalizer.normalizeKey(file);
      h.runtime.indexingCoordinator().updateDocument(parent, Map.of(
          SchemaFields.AUTHOR, "stale author", SchemaFields.TITLE, "stale title",
          SchemaFields.META_AUTHOR, "stale", SchemaFields.VDU_STATUS, "FAILED", SchemaFields.VDU_RETRY_COUNT, "3"));
      // A trailing blank changes source bytes while the text parser returns the same text.
      Files.writeString(file, Files.readString(file) + "\n");
      String oldText = h.runtime.documentFieldOps().getDocumentContent(parent);
      h.ingest(file, false);
      assertEquals(oldText, h.runtime.documentFieldOps().getDocumentContent(parent));
      assertNull(h.runtime.documentFieldOps().getDocumentField(parent, SchemaFields.AUTHOR));
      assertNull(h.runtime.documentFieldOps().getDocumentField(parent, SchemaFields.META_AUTHOR));
      assertNotEquals("stale title", h.runtime.documentFieldOps().getDocumentField(parent, SchemaFields.TITLE));
      assertNotEquals("3", h.runtime.documentFieldOps().getDocumentField(parent, SchemaFields.VDU_RETRY_COUNT));
      assertEquals(Map.of(), h.enrich(), "E4/E6: fresh metadata and visual routing, text artifacts reused");
    }
  }

  @Test
  void sourceEditAfterExtractionRejectsReuseAndPublication() throws Exception {
    Path file = Files.writeString(temporary.resolve("stale.txt"), "searchable stale evidence. ".repeat(500));
    try (var h = new Harness(false)) {
      h.ingest(file, false); h.enrich(); h.policyChanged(file);
      var oldChildren = h.children(file);
      String parent = PathNormalizer.normalizeKey(file);
      String oldText = h.runtime.documentFieldOps().getDocumentContent(parent);
      queueFor(h, file);
      var extractorField = IndexingLoop.class.getDeclaredField("extractor"); extractorField.setAccessible(true);
      var batchExtractor = (JobBatchExtractor) extractorField.get(h.loop);
      var extracted = batchExtractor.extractForBatch(h.queue.pollPending(10));
      assertEquals(1, extracted.size()); assertNotNull(extracted.getFirst().artifact());
      Files.writeString(file, "newer source bytes after extraction");
      var writerField = IndexingLoop.class.getDeclaredField("writer"); writerField.setAccessible(true);
      ((JobBatchWriter) writerField.get(h.loop)).write(extracted.getFirst(), null);
      h.refresh();
      assertEquals(oldText, h.runtime.documentFieldOps().getDocumentContent(parent));
      assertEquals(new HashSet<>(oldChildren), new HashSet<>(h.children(file)), "M5: stale work cannot replace the family");
      assertEquals(Map.of(), h.enrich());
    }
  }

  @Test
  void newMigrationTargetDoesNotTakeServingGenerationsDifferentModel() throws Exception {
    Path file = Files.writeString(temporary.resolve("generation.txt"), "searchable generation evidence. ".repeat(500));
    try (var serving = new Harness(false, "serving")) {
      serving.ingest(file, false); serving.enrich();
      try (var target = new Harness(true, "target")) {
        target.changeModel("embedding");
        target.manualRunning.set(false);
        target.loop.wireActiveLexicalSource(serving.runtime);
        target.manualRunning.set(true);
        target.ingest(file, false);
        verify(target.embedding).embedDocumentBatch(anyList());
        var computed = target.enrich();
        assertTrue(computed.getOrDefault("chunk_embed.computed", 0L) > 0,
            "E5: different target model computes its own passage vectors");
        serving.refresh(); serving.assertSearch();
        assertEquals(Map.of(), serving.enrich(), "M3/E5: serving generation keeps its completed work");
      }
    }
  }

  private void queueFor(Harness h, Path file) {
    h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)));
  }

  private final class Harness implements AutoCloseable {
    final RunningRuntime runtime;
    final SqliteJobQueue queue;
    final SqliteDocumentIdentityStore identity;
    final TestEngineExecutors owners = new TestEngineExecutors();
    final PolicyDrivenTikaExtractor parser = spy(new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool));
    final TimeboxedContentExtractor extractor;
    final IndexingLoop loop;
    final AtomicBoolean manualRunning;
    final EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    final SpladeEncoder sparse = mock(SpladeEncoder.class);
    final NerService ner = mock(NerService.class);
    final Set<String> forced = new HashSet<>();
    final io.justsearch.configuration.resolved.ResolvedConfig config =
        new ResolvedConfigBuilder().putSettings("rag.chunk_splade.enabled", "true").build();
    final Map<String, Object> currentMetadata = new HashMap<>();

    Harness(boolean migration) throws Exception {
      this(migration, "index");
    }

    Harness(boolean migration, String indexName) throws Exception {
      this(migration, indexName, true);
    }

    Harness(boolean migration, String indexName, boolean companionIdentityKnown) throws Exception {
      var model = IndexFingerprint.ModelFingerprint.present("a".repeat(64));
      if (companionIdentityKnown) model = model.withAssets(Optional.of("b".repeat(64)));
      var metadata = new SsotCommitMetadataSource(config,
          new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, model, model, model));
      currentMetadata.putAll(metadata.build());
      currentMetadata.put("embedding_model_sha256", "a".repeat(64));
      // The named answer also compiles against main's older interface for the before check.
      var modelAuthority = mock(CommitMetadataSource.class, call -> switch (call.getMethod().getName()) {
        case "build" -> Map.copyOf(currentMetadata);
        case "denseModelRole" -> "embedding";
        default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      });
      var schema = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog(),
          modelAuthority, ignored -> {});
      runtime = schema.atPath(temporary.resolve(indexName)).withConfig(config)
          .withExecutorRegistrations(testLuceneExecutors()).open();
      Path db = temporary.resolve("jobs.db");
      queue = new SqliteJobQueue(db);
      queue.useExtractionPolicy(TikaExtractionPolicy.defaults().policyId());
      queue.useDeclaredCollectionRoots(() -> List.of(new IngestCollectionPolicy.RootBinding(temporary, null)));
      queue.open();
      identity = new SqliteDocumentIdentityStore(db);
      extractor = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners, "reuse", 1),
          parser, Duration.ofSeconds(20), null);
      when(embedding.isAvailable()).thenReturn(true);
      when(embedding.availability()).thenReturn(EmbeddingProvider.Availability.READY);
      when(embedding.dimension()).thenReturn(schema.ssotVectorDimension());
      when(embedding.documentWindowCount(anyString())).thenReturn(1);
      when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
        List<String> texts = call.getArgument(0);
        return texts.stream().map(text -> vector(schema.ssotVectorDimension())).toList();
      });
      when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
        List<String> texts = call.getArgument(0);
        return texts.stream().map(text -> Map.of("reuse", 1.0f)).toList();
      });
      when(ner.isAvailable()).thenReturn(true);
      when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
        List<String> texts = call.getArgument(0);
        return texts.stream().map(text -> new NerResult(List.of("Ada Lovelace"), List.of(), List.of())).toList();
      });
      loop = new IndexingLoop(null, null, queue, runtime.indexingCoordinator(), runtime.commitOps(),
          runtime.documentFieldOps(), runtime.indexCountOps(), () -> config, mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(), null, null, null, null, extractor, null,
          new IndexingLoopOptions(false, null, identity, () -> migration, null, null, forced));
      loop.getEmbeddingLifecycle().setEmbeddingProvider(embedding);
      var running = IndexingLoop.class.getDeclaredField("running"); running.setAccessible(true);
      manualRunning = (AtomicBoolean) running.get(loop);
      manualRunning.set(true);
    }

    void changeModel(String change) {
      var original = IndexFingerprint.ModelFingerprint.present("a".repeat(64)).withAssets(Optional.of("b".repeat(64)));
      var changed = change.endsWith("-assets")
          ? IndexFingerprint.ModelFingerprint.present("a".repeat(64)).withAssets(Optional.of("c".repeat(64)))
          : IndexFingerprint.ModelFingerprint.present("c".repeat(64)).withAssets(Optional.of("b".repeat(64)));
      var metadata = new SsotCommitMetadataSource(config, new SsotCommitMetadataSource.RuntimeFingerprintInputs(null,
          change.startsWith("embedding") ? changed : original,
          change.startsWith("splade") ? changed : original, change.startsWith("ner") ? changed : original));
      runtime.indexingCoordinator().transitionEnrichmentAuthority(() -> {
        currentMetadata.clear(); currentMetadata.putAll(metadata.build());
        currentMetadata.put("embedding_model_sha256", change.startsWith("embedding") && !change.endsWith("-assets")
            ? "c".repeat(64) : "a".repeat(64));
      });
    }

    void policyChanged(Path file) {
      runtime.indexingCoordinator().updateDocument(PathNormalizer.normalizeKey(file),
          Map.of(SchemaFields.EXTRACTION_POLICY_ID, "previous-policy"));
      refresh();
    }

    void ingest(Path file, boolean force) throws Exception {
      if (force) forced.add(PathNormalizer.normalizeKey(file));
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)));
      var process = IndexingLoop.class.getDeclaredMethod("processBatch", List.class); process.setAccessible(true);
      var jobs = queue.pollPending(10);
      assertFalse(jobs.isEmpty(), "source must enter the real queue");
      process.invoke(loop, jobs);
      refresh();
      loop.getJournal().drainPending();
      assertEquals("DONE", queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)).get("state"));
    }

    List<String> children(Path file) {
      return runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID,
          PathNormalizer.normalizeKey(file), 1000);
    }

    void assertSearch() throws Exception {
      assertTrue(runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.CONTENT, "searchable")),
          10, Set.of(SchemaFields.DOC_ID), null, null).totalHits() > 0);
      assertTrue(runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.ENTITY_PERSONS_RAW, "Ada Lovelace")),
          10, Set.of(SchemaFields.DOC_ID), null, null).totalHits() > 0,
          "M3: carried entities still filter after publication and restart");
    }

    Map<String, Long> enrich() {
      var total = new TreeMap<String, Long>();
      for (int turn = 0; turn < 20; turn++) {
        var computed = enrichTurn();
        computed.forEach((key, value) -> total.merge(key, value, Long::sum));
        if (computed.isEmpty()) {
          for (String status : List.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_STATUS,
              SchemaFields.NER_STATUS, SchemaFields.SPLADE_STATUS)) {
            assertTrue(runtime.documentFieldOps().queryDocIdsByField(status, "PENDING", 1000).isEmpty(),
                "fixture left unfinished work: " + status);
          }
          return total;
        }
      }
      throw new AssertionError("fixture enrichment did not finish after 20 bounded turns: " + total);
    }

    private Map<String, Long> enrichTurn() {
      var fields = runtime.documentFieldOps(); var coordinator = runtime.indexingCoordinator();
      var log = LoggerFactory.getLogger(ReextractionArtifactReuseTest.class);
      var dense = new CombinedEnrichmentBackfillOps.BackfillContext(fields, coordinator,
          IndexingPacing.unthrottled(), () -> embedding, () -> true, () -> true, 1000, log,
          true, false, 1000, () -> false, () -> false, new WindowedEmbedProgress(), new HashSet<>(),
          ignored -> true, ignored -> true);
      var outcomes = new ArrayList<StageOutcome>();
      outcomes.add(CombinedEnrichmentBackfillOps.processDenseUnit(dense, null, EnrichmentAccounting.Stage.EMBED));
      refresh();
      outcomes.add(CombinedEnrichmentBackfillOps.processDenseUnit(dense, null, EnrichmentAccounting.Stage.CHUNK_EMBED));
      outcomes.add(NerBackfillOps.processNerUnit(new NerBackfillOps.BackfillContext(fields, coordinator,
          IndexingPacing.unthrottled(), () -> ner, () -> true, 1000, log, () -> true), null));
      outcomes.add(SpladeBackfillOps.processParentBackfill(new SpladeBackfillOps.BackfillContext(fields, coordinator,
          IndexingPacing.unthrottled(), () -> sparse, () -> true, 1000, true, log, () -> true), null));
      outcomes.add(SpladeBackfillOps.processChunkBackfill(new SpladeBackfillOps.BackfillContext(fields, coordinator,
          IndexingPacing.unthrottled(), () -> sparse, () -> true, 1000, true, log, () -> true), null));
      refresh();
      var result = new TreeMap<String, Long>();
      for (var outcome : outcomes) outcome.accounting().counters().forEach((key, count) -> {
        if (key.endsWith(".computed") && count > 0) result.merge(key, count, Long::sum);
      });
      return result;
    }

    void refresh() { runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking(); }

    @Override public void close() throws Exception {
      loop.close(); extractor.close(); parser.close(); owners.close(); identity.close(); queue.close(); runtime.close();
    }
  }

  private static float[] vector(int dimension) {
    var vector = new float[dimension]; vector[0] = 1; return vector;
  }
}
