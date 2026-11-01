/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.loop.BackfillScheduler;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class DisambiguationReconciliationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  RunningRuntime runtime;
  DisambiguationService service;
  BackfillScheduler scheduler;
  AtomicBoolean running = new AtomicBoolean(true);
  IndexingPacing pacing = IndexingPacing.unthrottled();

  @BeforeEach void open() throws Exception {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(field(SchemaFields.NER_STATUS, false));
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.NER_RETRY_COUNT, "long", true, true, List.of(), null, null, false));
    fields.add(field(SchemaFields.SPLADE_STATUS, false));
    fields.add(field(SchemaFields.ENTITY_PERSONS_RAW, true));
    fields.add(field(SchemaFields.ENTITY_ORGANIZATIONS_RAW, true));
    fields.add(field(SchemaFields.ENTITY_LOCATIONS_RAW, true));
    runtime = IndexSchema.fromCatalog(new FieldCatalogDef("entity-reconciliation", fields))
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
    service = spy(new DisambiguationService(directory));
    service.open();
    scheduler = scheduler();
  }

  @AfterEach void close() throws Exception {
    if (service != null) service.close();
    if (runtime != null) runtime.close();
  }

  static FieldCatalogDef.FieldDef field(String name, boolean multi) {
    return new FieldCatalogDef.FieldDef(name, "keyword", true, true, List.of("filter", "facet"), null, null, multi);
  }

  BackfillScheduler scheduler() {
    return new BackfillScheduler(runtime.documentFieldOps(), runtime.indexingCoordinator(),
        runtime.indexCountOps(), runtime.commitOps(), mock(WorkerSignalBus.class), pacing,
        null, running, () -> null, () -> null, () -> null, () -> null, () -> service);
  }

  void cycle() throws Exception {
    var method = BackfillScheduler.class.getDeclaredMethod("runDisambiguationIfReady", boolean.class);
    method.setAccessible(true);
    method.invoke(scheduler, false);
  }

  void drain() throws Exception { for (int i = 0; i < 8; i++) cycle(); }

  static String sourceId(int number) {
    return String.format(Locale.ROOT, "doc-%04d", number);
  }

  static String uniqueName(int number) {
    return UUID.nameUUIDFromBytes(("person-" + number).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString().replace("-", "");
  }

  void seed(String id, String... persons) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.PATH, id + ".txt",
        SchemaFields.CONTENT, "entity content " + id,
        SchemaFields.NER_STATUS, persons.length == 0 ? "COMPLETED_EMPTY" : "COMPLETED",
        SchemaFields.ENTITY_PERSONS_RAW, List.of(persons))));
  }

  void refresh() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  Set<String> variants(String raw) {
    var snapshot = service.snapshot();
    return snapshot.expandCanonical("PERSON", snapshot.getCanonical("PERSON", raw));
  }

  @ParameterizedTest @ValueSource(ints = {0, 500, 501, 1001})
  void wholeCorpusAndSuccessfulQuiescence(int count) throws Exception {
    for (int i = 0; i < count; i++) seed(sourceId(i), uniqueName(i), uniqueName(i).toUpperCase(Locale.ROOT), "shared person");
    refresh();
    drain();
    for (int i = 0; i < count; i++) {
      assertEquals(2, variants(uniqueName(i)).size(), "aliases must be harvested for " + sourceId(i));
    }
    clearInvocations(service);
    drain();
    verify(service, never()).processBatch(anyMap());
  }

  @ParameterizedTest @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
      "false,false,true", "false,true,true"})
  void equalCountNerPublicationsInvalidateCompletedPass(boolean replaceId, boolean combined, boolean failAfterPublication) throws Exception {
    seed("original", "john smith", "JOHN SMITH");
    refresh(); drain();
    String id = replaceId ? "replacement" : "original";
    if (replaceId) runtime.indexingCoordinator().deleteById("original");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.PATH, id + ".txt",
        SchemaFields.CONTENT, "new content", SchemaFields.NER_STATUS, "PENDING")));
    refresh();
    var ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(
        new NerResult(List.of("sarah chen", "SARAH CHEN"), List.of(), List.of())));
    IndexingCoordinator publisher = runtime.indexingCoordinator();
    if (failAfterPublication) {
      publisher = spy(publisher);
      doAnswer(call -> {
        call.callRealMethod();
        throw new IllegalStateException("failure after real Lucene publication");
      }).when(publisher).updateDocumentsBatchIf(anyList(), anyMap());
    }
    if (combined) {
      CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          runtime.documentFieldOps(), publisher, runtime.commitOps(), pacing,
          () -> null, () -> null, () -> ner, () -> true, () -> true, 100,
          LoggerFactory.getLogger(getClass()), false, false, false, 50,
          new ArrayDeque<>(), new ArrayDeque<>(), new int[1], () -> false, () -> false,
          () -> false, new WindowedEmbedProgress()));
    } else {
      NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(runtime.documentFieldOps(),
          publisher, runtime.commitOps(), pacing, () -> ner, () -> true, 100,
          LoggerFactory.getLogger(getClass())));
    }
    refresh();
    assertEquals(1, runtime.indexCountOps().countByField(SchemaFields.NER_STATUS, "COMPLETED"));
    drain();
    assertEquals(Set.of("sarah chen", "SARAH CHEN"), variants("sarah chen"));
    assertEquals(Set.of("john smith"), variants("john smith"));
  }

  @Test void failedPassRetriesWithoutCountChanges() throws Exception {
    seed("a", "john smith", "JOHN SMITH"); refresh();
    doThrow(new SQLException("injected write failure")).doCallRealMethod().when(service).processBatch(anyMap());
    cycle();
    assertTrue(service.snapshot().isEmpty());
    drain();
    assertEquals(Set.of("john smith", "JOHN SMITH"), variants("john smith"));
  }

  @Test void holdDuringPageDoesNotRetireUnseenSourcesAndResumes() throws Exception {
    seed("a", "john smith", "JOHN SMITH");
    seed("b", "sarah chen", "SARAH CHEN"); refresh(); drain();
    // A new source forces a pass with existing sources still required.
    seed("c", "new person", "NEW PERSON"); refresh();
    AtomicInteger paced = new AtomicInteger();
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    var load = new ForegroundLoad(); load.started();
    pacing = new IndexingPacing(load, 20, 500L, () -> 0L, () -> nanos.getAndAdd(1_000_000L),
        ms -> { if (paced.incrementAndGet() == 1) running.set(false); });
    scheduler = scheduler();
    cycle();
    assertFalse(running.get(), "the hold must interrupt an actual paced page");
    assertEquals(2, variants("sarah chen").size());
    running.set(true);
    drain();
    assertEquals(2, variants("new person").size());
    assertEquals(2, variants("sarah chen").size());
  }

  @Test void sharedOwnershipSurvivesUntilFinalSourceAndEmptyCorpusRetiresEverything() throws Exception {
    seed("a", "john smith", "JOHN SMITH", "john smith");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "b", SchemaFields.DOC_UID, "b", SchemaFields.PATH, "b.txt",
        SchemaFields.CONTENT, "entity content b", SchemaFields.NER_STATUS, "COMPLETED",
        SchemaFields.ENTITY_PERSONS_RAW, List.of("john smith", "Sarah Chen", "SARAH CHEN"),
        SchemaFields.ENTITY_ORGANIZATIONS_RAW, List.of("Acme Corp", "ACME CORP"),
        SchemaFields.ENTITY_LOCATIONS_RAW, List.of("New York", "NEW YORK"))));
    refresh(); drain();
    var captured = service.snapshot();
    runtime.indexingCoordinator().deleteById("a"); refresh(); drain();
    assertEquals("john smith", service.snapshot().getCanonical("PERSON", "john smith"));
    assertEquals(Set.of("john smith"), variants("john smith"));
    assertEquals(2, variants("Sarah Chen").size());
    for (var typeAndRaw : Map.of("LOCATION", "New York", "ORGANIZATION", "Acme Corp").entrySet()) {
      var snapshot = service.snapshot();
      assertEquals(2, snapshot.expandCanonical(typeAndRaw.getKey(),
          snapshot.getCanonical(typeAndRaw.getKey(), typeAndRaw.getValue())).size());
    }
    assertEquals(2, captured.expandCanonical("PERSON", captured.getCanonical("PERSON", "john smith")).size());
    service.close(); service.open(); scheduler = scheduler(); drain();
    assertEquals(Set.of("john smith"), variants("john smith"));
    seed("c", "John Smith"); refresh(); drain();
    assertEquals(Set.of("john smith", "John Smith"), variants("john smith"));
    runtime.indexingCoordinator().deleteById("c"); refresh(); drain();
    seed("b"); refresh(); drain();
    assertTrue(service.snapshot().isEmpty());
    runtime.indexingCoordinator().deleteAll(); refresh(); drain();
    assertTrue(service.snapshot().isEmpty());
  }

  @Test void mutationBetweenBatchesRestartsPhysicalCursor() throws Exception {
    for (int i = 0; i < 501; i++) seed(sourceId(i), uniqueName(i), uniqueName(i).toUpperCase(Locale.ROOT), "shared person");
    refresh(); cycle();
    runtime.indexingCoordinator().deleteById(sourceId(0));
    seed("new", "sarah chen", "SARAH CHEN"); refresh();
    drain();
    assertEquals(2, variants("sarah chen").size());
    assertEquals(2, variants(uniqueName(500)).size());
    assertEquals(Set.of(uniqueName(0)), variants(uniqueName(0)));
  }

  @Test void replacementServiceReconcilesUnchangedCountsAfterPartialPass() throws Exception {
    for (int i = 0; i < 501; i++) seed(sourceId(i), uniqueName(i), uniqueName(i).toUpperCase(Locale.ROOT), "shared person");
    refresh(); cycle();
    service.close();
    service = spy(new DisambiguationService(directory)); service.open();
    scheduler = scheduler(); drain();
    for (int i = 0; i < 501; i++) assertEquals(2, variants(uniqueName(i)).size(), sourceId(i));
    clearInvocations(service); drain();
    verify(service, never()).processBatch(anyMap());
  }

  @Test void completedEmptyAdditionDoesNotTriggerAnotherPass() throws Exception {
    seed("a", "john smith", "JOHN SMITH"); refresh(); drain();
    clearInvocations(service);
    seed("empty"); refresh(); drain();
    verify(service, never()).processBatch(anyMap());
  }

  @Test void legacySidecarIsRebuiltFromLiveSourcesAndSurvivesRestart() throws Exception {
    seed("live", "john smith", "JOHN SMITH"); refresh();
    service.close();
    Path legacy = directory.resolve("legacy"); java.nio.file.Files.createDirectories(legacy);
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + legacy.resolve("entity-clusters.db"));
        var sql = connection.createStatement()) {
      sql.execute(io.justsearch.indexerworker.disambiguation.EntityClusterSchema.CREATE_CLUSTERS_TABLE);
      sql.execute(io.justsearch.indexerworker.disambiguation.EntityClusterSchema.CREATE_OVERRIDES_TABLE);
      sql.execute("PRAGMA user_version = 1");
      sql.executeUpdate("INSERT INTO entity_clusters VALUES ('obsolete alias', 'PERSON', 'old', 'obsolete canonical', 1, 0, 0)");
      sql.executeUpdate("INSERT INTO entity_clusters VALUES ('john smith', 'PERSON', 'live', 'john smith', 1, 0, 0)");
    }
    service = spy(new DisambiguationService(legacy)); service.open(); drain();
    assertEquals(2, variants("john smith").size());
    assertEquals("obsolete alias", service.snapshot().getCanonical("PERSON", "obsolete alias"));
    service.close(); service.open(); drain();
    assertEquals(2, variants("john smith").size());
    runtime.indexingCoordinator().deleteAll(); refresh(); drain();
    assertTrue(service.snapshot().isEmpty(), "an unowned legacy mapping must not survive reconciliation");
  }

  @Test void canonicalAndRawSearchFiltersAndFacetsFollowRetirement() throws Exception {
    seed("a", "john smith"); seed("b", "JOHN SMITH"); refresh(); drain();
    var bindings = new io.justsearch.indexerworker.server.EncoderBindings();
    bindings.bindDisambiguationService(service);
    var search = new io.justsearch.indexerworker.services.WorkerSearchService(runtime, null, bindings);
    search.setClusterSnapshotSupplier(service::snapshot);
    for (String filter : List.of("john smith", "JOHN SMITH")) {
      var response = search.search(entityQuery(filter), io.justsearch.indexerworker.services.CallContext.none());
      assertEquals(2, response.getTotalHits());
      assertEquals(1, response.getFacetsOrThrow(SchemaFields.ENTITY_PERSONS_RAW).getCountsCount());
      assertEquals(1, response.getEntityFacetVariantsOrThrow(SchemaFields.ENTITY_PERSONS_RAW).getEntriesCount());
    }
    runtime.indexingCoordinator().deleteById("a"); refresh(); drain();
    var survivor = search.search(entityQuery("JOHN SMITH"), io.justsearch.indexerworker.services.CallContext.none());
    assertEquals(1, survivor.getTotalHits());
    assertEquals(Set.of("JOHN SMITH"), survivor.getFacetsOrThrow(SchemaFields.ENTITY_PERSONS_RAW).getCountsMap().keySet());
    assertEquals(0, search.search(entityQuery("john smith"), io.justsearch.indexerworker.services.CallContext.none()).getTotalHits());
    assertTrue(survivor.getEntityFacetVariantsMap().isEmpty());
  }

  static io.justsearch.ipc.SearchRequest entityQuery(String filter) {
    return io.justsearch.ipc.SearchRequest.newBuilder().setQuery("entity").setLimit(20)
        .setPipeline(io.justsearch.ipc.PipelineConfig.newBuilder().setSparseEnabled(true))
        .setFilters(io.justsearch.ipc.SearchFilters.newBuilder().addEntityPersons(filter))
        .setFacets(io.justsearch.ipc.FacetSpec.newBuilder().setInclude(true)
            .addFields(io.justsearch.ipc.FacetFieldSpec.newBuilder().setField(SchemaFields.ENTITY_PERSONS_RAW).setSize(20)))
        .build();
  }

  @Test void requestRetainsOneSnapshotWhenPublicationChangesDuringCapture() throws Exception {
    seed("a", "john smith"); refresh(); drain();
    doAnswer(call -> {
      var captured = call.callRealMethod();
      seed("b", "JOHN SMITH"); refresh(); drain();
      return captured;
    }).doCallRealMethod().when(service).snapshot();
    var bindings = new io.justsearch.indexerworker.server.EncoderBindings();
    bindings.bindDisambiguationService(service);
    var search = new io.justsearch.indexerworker.services.WorkerSearchService(runtime, null, bindings);
    search.setClusterSnapshotSupplier(service::snapshot);
    var response = search.search(entityQuery("john smith"), io.justsearch.indexerworker.services.CallContext.none());
    assertEquals(1, response.getTotalHits());
    assertTrue(response.getEntityFacetVariantsMap().isEmpty());
    var later = search.search(entityQuery("john smith"), io.justsearch.indexerworker.services.CallContext.none());
    assertEquals(2, later.getTotalHits());
    assertEquals(1, later.getFacetsOrThrow(SchemaFields.ENTITY_PERSONS_RAW).getCountsCount());
    assertEquals(1, later.getEntityFacetVariantsOrThrow(SchemaFields.ENTITY_PERSONS_RAW).getEntriesCount());
  }

  @Test void excludedHeadsDoNotStarveEligibleTailAndTypesRemainSeparate() throws Exception {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var config = io.justsearch.configuration.resolved.ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[\"excluded*\"]").build();
    var installed = new io.justsearch.configuration.resolved.ConfigStore(config);
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(installed);
    try {
      runtime.useSourceProcessingPolicy(path -> path == null || !path.contains("excluded"));
      for (int i = 0; i < 501; i++) seed("excluded-" + i, uniqueName(i));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "tail", SchemaFields.DOC_UID, "tail", SchemaFields.PATH, "tail.txt",
          SchemaFields.CONTENT, "entity content tail", SchemaFields.NER_STATUS, "COMPLETED",
          SchemaFields.ENTITY_PERSONS_RAW, List.of(" john smith ", "john smith", "JOHN SMITH", " "),
          SchemaFields.ENTITY_LOCATIONS_RAW, List.of("john smith"))));
      refresh(); drain();
      assertEquals(2, variants("john smith").size());
      assertEquals(Set.of("john smith"), service.snapshot().expandCanonical("LOCATION", "john smith"));
      runtime.indexingCoordinator().deleteById("tail"); refresh(); drain();
      assertTrue(service.snapshot().isEmpty(), "excluded sources must never acquire membership");
    } finally { io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test void publicResetRestartsAttachedServiceAndCleanupFailureCanBeRetried() throws Exception {
    var queue = mock(io.justsearch.indexerworker.queue.JobQueue.class);
    when(queue.pollPending(anyInt())).thenReturn(List.of());
    var signals = mock(WorkerSignalBus.class);
    when(signals.isIndexingHeld()).thenReturn(true);
    var bindings = new io.justsearch.indexerworker.server.EncoderBindings();
    bindings.bindDisambiguationService(service);
    var loop = new io.justsearch.indexerworker.loop.IndexingLoop(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), queue,
        runtime.indexingCoordinator(), runtime.commitOps(), runtime.documentFieldOps(), runtime.indexCountOps(),
        () -> null, signals, IndexingPacing.unthrottled(), null, null, null, null, null, bindings,
        io.justsearch.indexerworker.loop.IndexingLoopOptions.withDefaults());
    try {
      var ingest = new io.justsearch.indexerworker.services.WorkerIngestService(queue, loop, signals,
          IndexingPacing.unthrottled(), null, directory.resolve("index"), runtime, runtime, null, 0L);
      when(queue.clearAll()).thenThrow(new IllegalStateException("cleanup fault")).thenReturn(0);
      assertFalse(ingest.resetIndex(io.justsearch.ipc.ResetIndexRequest.getDefaultInstance(),
          io.justsearch.indexerworker.services.CallContext.none()).getSuccess());
      for (int i = 0; i < 2; i++) {
        assertTrue(ingest.resetIndex(io.justsearch.ipc.ResetIndexRequest.getDefaultInstance(),
            io.justsearch.indexerworker.services.CallContext.none()).getSuccess());
        assertTrue(service.isAvailable());
        seed("after-reset-" + i, "sarah chen", "SARAH CHEN"); refresh(); drain();
        assertEquals(2, variants("sarah chen").size());
      }
    } finally { loop.close(); }
  }

  @Test void openServiceRemainsAvailableAcrossRepeatedProfilingResets() throws Exception {
    for (int i = 0; i < 2; i++) {
      seed("a", "john smith", "JOHN SMITH"); refresh(); drain();
      runtime.indexingCoordinator().deleteAll(); refresh();
      service.reset(); scheduler.resetState();
      assertTrue(service.isAvailable());
    }
    seed("b", "sarah chen", "SARAH CHEN"); refresh(); drain();
    assertEquals(2, variants("sarah chen").size());
  }
}
