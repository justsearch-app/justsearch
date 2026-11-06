/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.excludes.ExcludesServiceImpl;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Legacy rows are seeded through a real index writer; removal uses the real Engine delete port. */
@Timeout(120)
final class EngineProtectedContentUpgradeTest {
  @TempDir Path directory;
  private final io.justsearch.indexerworker.ingest.IngestionSkipPolicy previousPolicy =
      io.justsearch.indexerworker.ingest.IngestionSkipPolicy.active();
  @org.junit.jupiter.api.AfterEach void restorePolicy() {
    io.justsearch.indexerworker.ingest.IngestionSkipPolicy.installResolved(previousPolicy);
  }

  @Test void upgradeRemovesOfflineAdHocHiddenAndUserDataCopiesIncludingChunksAndEnrichment() throws Exception {
    var previous = ConfigStore.globalOrNull();
    try (var engine = EngineTestHarness.start(directory.resolve("data"))) {
      Path root = directory.resolve("offline");
      Path data = engine.dataDir();
      List<Path> protectedFiles = List.of(root.resolve(".env"), root.resolve(".private/notes.txt"),
          directory.resolve("adhoc/identity.pem"), directory.resolve(".aws/nested/settings.txt"),
          data.resolve("user-copy.txt"));
      Path ordinary = directory.resolve("ordinary.txt");
      Path history = Files.writeString(Files.createDirectories(data.resolve("agent-history")).resolve("legacy.md"),
          "# Agent run\nlegacyhistoryuniquemarker");
      Path ownedHistory = history.resolveSibling("owned.md");
      Path runRecord = Files.writeString(Files.createDirectories(data.resolve("agent-runs")).resolve("run.json"), "run record unchanged");
      var client = spy(engine.client());
      doReturn(List.of(new IndexingService.WatchedRoot(null, root))).when(client).getWatchedRoots(any());
      var reconciled = new ArrayList<Path>();
      try (var view = engine.captureServingView()) {
        var runtime = (RunningRuntime) view.ingestRuntime();
        for (Path file : protectedFiles) seed(runtime, file, null, "protecteduniquemarker");
        seed(runtime, ordinary, null, "ordinaryuniquemarker");
        seed(runtime, history, null, "legacyhistoryuniquemarker");
        seed(runtime, ownedHistory, "agent-history", "ownedhistoryuniquemarker");
        assertTrue(engine.awaitSearchable("protecteduniquemarker", 10_000));
        assertTrue(engine.awaitNotSearchable("legacyhistoryuniquemarker", 10_000), "legacy untagged chunks stay private before cleanup");
        assertTrue(remove(new ExcludesServiceImpl(() -> client), data, reconciled::add));
        for (Path file : protectedFiles) {
          assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()), file.toString());
          assertNull(runtime.documentFieldOps().getDocumentContent(file.toString()));
          assertNull(runtime.documentFieldOps().getDocumentField(file.toString(), SchemaFields.VDU_ENRICHMENT));
        }
        assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(history.toString()));
        assertEquals(List.of(history), reconciled, "legacy transcripts are reconciled under the reserved collection");
        assertTrue(engine.awaitNotSearchable("protecteduniquemarker", 10_000));
        assertTrue(engine.awaitNotSearchable("legacyhistoryuniquemarker", 10_000));
        assertTrue(engine.awaitSearchable("ordinaryuniquemarker", 10_000));
        assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(ownedHistory.toString()));
        assertEquals("run record unchanged", Files.readString(runRecord));
        assertTrue(Files.exists(history), "cleanup never deletes source transcripts");
        assertTrue(runtime.folderBrowseEngine().listFolderFiles(data.resolve("agent-history").toString(), 10,
            Set.of(SchemaFields.CONTENT, SchemaFields.TITLE)).files().isEmpty());
        assertEquals(0, engine.client().listFolderFiles(data.resolve("agent-history").toString(), 10,
            List.of(SchemaFields.CONTENT, SchemaFields.TITLE), TestEngineContexts.BACKGROUND).getFilesCount());
        assertEquals(1, privateFiles(engine.client(), data.resolve("agent-history")).getFilesCount(),
            "verified window/headless authority preserves private folder reads");
      }
      engine.restart();
      assertTrue(engine.awaitNotSearchable("protecteduniquemarker", 10_000));
      assertTrue(engine.awaitSearchable("ordinaryuniquemarker", 10_000));
      var unused = mock(IndexingService.class);
      assertTrue(remove(new ExcludesServiceImpl(() -> unused), data, ignored -> fail("completed upgrade must do no work")));
      verifyNoInteractions(unused);
    } finally { ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous); }
  }

  @Test void failedOrUncheckedRemovalRetriesAndOldRuleVersionRunsAgainWhileSearchServes() throws Exception {
    var previous = ConfigStore.globalOrNull();
    var pool = Executors.newSingleThreadExecutor();
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    try (var engine = EngineTestHarness.start(directory.resolve("data"))) {
      Path secret = directory.resolve("private.key"), ordinary = directory.resolve("keep.txt");
      try (var view = engine.captureServingView()) {
        seed((RunningRuntime)view.ingestRuntime(), secret, null, "secretupgradeuniquemarker");
        seed((RunningRuntime)view.ingestRuntime(), ordinary, null, "publicupgradeuniquemarker");
      }
      var client = spy(engine.client());
      doReturn(new IndexingService.DeletionOutcome(false, "injected unavailable Worker"))
          .when(client).deleteDocByIdWithOutcome(eq(secret.toString()), any());
      var cleanup = new ExcludesServiceImpl(() -> client);
      assertFalse(remove(cleanup, engine.dataDir(), ignored -> {}));
      assertFalse(Files.exists(engine.dataDir().resolve(".protected-content-version")));
      doAnswer(call -> {
        entered.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS));
        return call.callRealMethod();
      }).when(client).deleteDocByIdWithOutcome(eq(secret.toString()), any());
      var pending = pool.submit(() -> remove(cleanup, engine.dataDir(), ignored -> {}));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertTrue(engine.awaitSearchable("publicupgradeuniquemarker", 5_000), "search serves during removal");
      assertFalse(pending.isDone());
      release.countDown();
      assertTrue(pending.get(30, TimeUnit.SECONDS));
      assertTrue(engine.awaitNotSearchable("secretupgradeuniquemarker", 10_000));
      Files.writeString(engine.dataDir().resolve(".protected-content-version"), "older-rule-version");
      clearInvocations(client);
      assertTrue(remove(cleanup, engine.dataDir(), ignored -> {}));
      // Reflection keeps this behavioral regression test runnable on the pre-fix baseline.
      assertTrue(mockingDetails(client).getInvocations().stream().anyMatch(call ->
          call.getMethod().getName().equals("forEachIndexedSourceWithCollection")));
    } finally {
      release.countDown(); pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void legacyHistoryIsReindexedWithItsCollectionOnParentsAndChunks(boolean configuredAlias) throws Exception {
    var previous = ConfigStore.globalOrNull();
    Path parent = Files.createDirectory(directory.resolve("physical-parent"));
    if (configuredAlias) parent = Files.createSymbolicLink(directory.resolve("configured-parent"), parent);
    try (var engine = EngineTestHarness.start(parent.resolve("data"))) {
      Path transcript = Files.writeString(Files.createDirectories(engine.dataDir().resolve("agent-history"))
          .resolve("legacy-run.md"), "# Agent run\n" + "rescopinghistorymarker ".repeat(1000));
      try (var view = engine.captureServingView()) {
        seed((RunningRuntime)view.ingestRuntime(), transcript, null, "rescopinghistorymarker");
      }
      assertTrue(remove(new ExcludesServiceImpl(engine::client), engine.dataDir(), path -> {
        assertEquals(1, engine.client().submitBatch(List.of(path), true, "agent-history",
            TestEngineContexts.BACKGROUND).getAcceptedCount());
      }));
      assertTrue(engine.awaitIndexed(1, 60_000));
      var request = io.justsearch.ipc.SearchRequest.newBuilder().setQuery("rescopinghistorymarker").setLimit(10)
          .setFilters(io.justsearch.ipc.SearchFilters.newBuilder().addCollection("agent-history")).build();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (engine.client().search(request, TestEngineContexts.FOREGROUND).getTotalHits() == 0
          && System.nanoTime() < deadline) Thread.sleep(50);
      assertTrue(engine.client().search(request, TestEngineContexts.FOREGROUND).getTotalHits() > 0,
          "the app's explicit history scope still finds the reconciled transcript");
      assertTrue(engine.awaitNotSearchable("rescopinghistorymarker", 10_000));
      try (var view = engine.captureServingView()) {
        var runtime = (RunningRuntime)view.ingestRuntime();
        assertEquals("agent-history", runtime.documentFieldOps().getDocumentField(transcript.toString(), SchemaFields.COLLECTION));
        var chunks = runtime.readPathOps().search(new org.apache.lucene.search.TermQuery(
            new org.apache.lucene.index.Term(SchemaFields.PARENT_DOC_ID, transcript.toString())),
            100, Set.of(SchemaFields.COLLECTION), null, null);
        assertTrue(chunks.totalHits() > 0);
        assertTrue(chunks.hits().stream().allMatch(hit -> "agent-history".equals(hit.fields().get(SchemaFields.COLLECTION))));
      }
    } finally { ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous); }
  }

  @Test void uncheckedEnumerationCannotWriteTheCompletionMarker() throws Exception {
    var enumerated = new java.util.concurrent.atomic.AtomicBoolean();
    var indexing = mock(IndexingService.class, invocation -> {
      if (invocation.getMethod().getName().equals("forEachIndexedSourceWithCollection")) {
        enumerated.set(true); return 1L;
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    assertFalse(remove(new ExcludesServiceImpl(() -> indexing), directory, ignored -> {}));
    assertFalse(Files.exists(directory.resolve(".protected-content-version")));
    assertTrue(enumerated.get(), "an incomplete pass must inspect the serving index without marking completion");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"private-key", "legacy-history", "pending-history", "deleted-history"})
  void candidateOnlyLegacyFilesAreRemovedBeforeCutoverPublication(String mode) throws Exception {
    boolean legacyHistory = !mode.equals("private-key");
    var previous = ConfigStore.globalOrNull();
    try (var ignoredVolume = EngineTestHarness.knownVolume();
        var engine = EngineTestHarness.start(directory.resolve("data"))) {
      Path documents = Files.createDirectories(directory.resolve("documents"));
      Path ordinary = Files.writeString(documents.resolve("ordinary.txt"), "candidatepublicmarker");
      engine.addRootAsThePerson(documents);
      Files.writeString(engine.dataDir().resolve("watched_roots.json"),
          new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("roots", List.of(
              Map.of("path", documents.toString(), "collection", "default", "lastIndexed", java.time.Instant.now().toString())))));
      assertEquals(1, engine.client().submitBatch(List.of(ordinary), TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIndexed(1, 60_000));
      var migration = engine.client().startMigration("protected-content-upgrade", TestEngineContexts.FOREGROUND);
      assertTrue(migration.accepted());
      String before = engine.status().getMigration().getActiveGenerationId();
      Path candidateOnly = legacyHistory
          ? Files.writeString(Files.createDirectories(engine.dataDir().resolve("agent-history")).resolve("candidate-only.md"),
              "# Agent run\ncandidateprotectedmarker")
          : directory.resolve("candidate-only.pem");
      boolean candidateOpened = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!candidateOpened && System.nanoTime() < deadline) {
        try (var view = engine.captureServingView()) {
          if (view.ingestRuntime() != view.searchRuntime()) {
            if (mode.equals("deleted-history")) {
              assertEquals(1, engine.client().submitBatch(List.of(candidateOnly), true, "agent-history", TestEngineContexts.FOREGROUND).getAcceptedCount());
              Files.delete(candidateOnly);
              assertTrue(engine.client().deleteHistoryTranscript(candidateOnly, TestEngineContexts.FOREGROUND).getSuccess());
              Files.writeString(candidateOnly.resolveSibling(candidateOnly.getFileName() + ".pending"), "pending");
            } else if (mode.equals("pending-history")) {
              Files.writeString(candidateOnly.resolveSibling(candidateOnly.getFileName() + ".pending"), "pending");
            } else seed((RunningRuntime)view.ingestRuntime(), candidateOnly, null, "candidateprotectedmarker");
            candidateOpened = true;
          }
        }
        if (!candidateOpened) Thread.sleep(50);
      }
      assertTrue(candidateOpened, "the real migration candidate must be open");
      assertFalse(remove(new ExcludesServiceImpl(engine::client), engine.dataDir(), ignored -> {}));
      assertFalse(Files.exists(engine.dataDir().resolve(".protected-content-version")));
      assertTrue(engine.awaitSearchable("candidatepublicmarker", 5_000));
      assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted());
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (before.equals(engine.status().getMigration().getActiveGenerationId()) && System.nanoTime() < deadline) Thread.sleep(50);
      assertNotEquals(before, engine.status().getMigration().getActiveGenerationId());
      if (legacyHistory && !mode.equals("deleted-history")) {
        assertHistorySearchable(engine, "candidateprotectedmarker");
        assertEquals("agent-history", engine.client().fetchDocumentSlice(candidateOnly.toString(), 0, 1,
            TestEngineContexts.FOREGROUND).getMetadataOrDefault("collection", ""));
        assertTrue(Files.readString(candidateOnly).contains("candidateprotectedmarker"));
        assertFalse(Files.exists(candidateOnly.resolveSibling(candidateOnly.getFileName() + ".pending")));
      } else {
        assertFalse(engine.client().fetchDocumentSlice(candidateOnly.toString(), 0, 1,
            TestEngineContexts.FOREGROUND).getFound(), "the first read after cutover must already be protected");
        assertTrue(engine.awaitDocumentAbsent(candidateOnly.toString(), 10_000), "candidate protection holds before restart");
      }
      assertFalse(Files.exists(candidateOnly.resolveSibling(candidateOnly.getFileName() + ".pending")));
      assertTrue(engine.awaitNotSearchable("candidateprotectedmarker", 10_000), "live cutover must never expose private content to default search");
      engine.restart();
      // Restart serves read-only search while its deferred writer is composed, just as the app
      // does. Healthy initialization waits for the existing serving-generation writer authority.
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      boolean writable = false;
      while (!writable && System.nanoTime() < deadline) {
        try { engine.client().captureServingGeneration(TestEngineContexts.BACKGROUND); writable = true; }
        catch (RuntimeException opening) { Thread.sleep(50); }
      }
      assertTrue(writable);
      assertTrue(remove(new ExcludesServiceImpl(engine::client), engine.dataDir(), ignored -> {}));
      if (legacyHistory && !mode.equals("deleted-history")) assertHistorySearchable(engine, "candidateprotectedmarker");
      else assertTrue(engine.awaitDocumentAbsent(candidateOnly.toString(), 10_000));
      assertTrue(engine.awaitNotSearchable("candidateprotectedmarker", 10_000));
      assertTrue(engine.awaitSearchable("candidatepublicmarker", 10_000));
    } finally { ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous); }
  }

  @Test void protectedRenameRetiresBothIndexedFamiliesAndQueuedWork() throws Exception {
    Path documents = Files.createDirectories(directory.resolve("documents"));
    try (var ignoredVolume = EngineTestHarness.knownVolume();
        var fixture = new EngineForgetInProcessTest.Fixture(directory.resolve("data"), List.of(documents))) {
      if (fixture.loop != null) fixture.loop.close();
      int sequence = 0;
      for (String destinationName : List.of("private.key", ".env.local", ".env.production", ".draft", "~draft.txt", "temp.tmp", "object.pyc", "thumbs.db", ".aws/nested/ordinary.txt", ".hidden/ordinary.txt", "ordinary.txt")) {
        Path source = Files.writeString(documents.resolve("notes" + sequence++ + ".txt"), "renamemarker");
        Path destination = documents.resolve(destinationName);
        Files.createDirectories(destination.getParent());
        fixture.seed(source, "renamemarker");
        Files.move(source, destination);
        if (!destinationName.equals("ordinary.txt")) fixture.queue.enqueue(List.of(source, destination), "documents");
        long oldPending = fixture.queue.countByPathPrefix(source.toString()).pendingCount();
        long newPending = fixture.queue.countByPathPrefix(destination.toString()).pendingCount();
        boolean ordinaryRejection = List.of("~draft.txt", "temp.tmp", "object.pyc", "thumbs.db").contains(destinationName);
        assertEquals(ordinaryRejection ? 0 : 1, fixture.client.updateDocumentPaths(Map.of(source, destination), TestEngineContexts.FOREGROUND));
        assertEquals(ordinaryRejection ? 3 : 0, fixture.runtime.indexCountOps().countByIdAndChunksStrict(source.toString()));
        assertEquals(destinationName.equals("ordinary.txt") ? 3 : 0,
            fixture.runtime.indexCountOps().countByIdAndChunksStrict(destination.toString()), destinationName);
        assertEquals(ordinaryRejection ? oldPending : 0, fixture.queue.countByPathPrefix(source.toString()).pendingCount());
        assertEquals(ordinaryRejection ? newPending : 0, fixture.queue.countByPathPrefix(destination.toString()).pendingCount());
      }
    }
  }

  @Test void legacyHistoryCannotReachDefaultSearchRetrievalFacetsOrFoldersBeforeCleanup() throws Exception {
    var previous = ConfigStore.globalOrNull();
    try (var engine = EngineTestHarness.start(directory.resolve("data"))) {
      Path transcript = engine.dataDir().resolve("agent-history/legacy.md");
      try (var view = engine.captureServingView()) {
        seed((RunningRuntime)view.ingestRuntime(), transcript, null, "legacysecretmarker");
      }
      assertTrue(engine.awaitNotSearchable("legacysecretmarker", 5_000));
      var facets = engine.client().search(io.justsearch.ipc.SearchRequest.newBuilder()
          .setQuery("*:*").setLimit(1).setPipeline(io.justsearch.ipc.PipelineConfigs.TEXT)
          .setQuerySyntax(io.justsearch.ipc.SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
          .setFacets(io.justsearch.ipc.FacetSpec.newBuilder().setInclude(true).setMaxDocsScanned(100)
              .addFields(io.justsearch.ipc.FacetFieldSpec.newBuilder().setField(SchemaFields.ENTITY_PERSONS_RAW).setSize(10)))
          .build(), TestEngineContexts.FOREGROUND);
      assertTrue(facets.getFacetsMap().values().stream().allMatch(counts -> counts.getCountsMap().isEmpty()), facets.toString());
      var retrieval = engine.client().retrieveContext("legacysecretmarker", Set.of(transcript.toString()), 5, TestEngineContexts.FOREGROUND);
      assertFalse(retrieval.getContext().contains("legacysecretmarker"), retrieval.toString());
      var citation = engine.client().matchCitations("legacysecretmarker", List.of(transcript.toString()), List.of(0), List.of(),
          0.0f, TestEngineContexts.FOREGROUND);
      assertEquals(0, citation.getMatchesCount(), citation.toString());
      assertEquals(0, engine.client().listFolderFiles(engine.dataDir().getParent().toString(), 100,
          List.of(SchemaFields.CONTENT, SchemaFields.TITLE), TestEngineContexts.FOREGROUND).getFilesCount());
      assertTrue(engine.client().listFolders(engine.dataDir().getParent().toString(), 100,
          TestEngineContexts.FOREGROUND).getFoldersList().stream().noneMatch(folder -> folder.getPath().contains("agent-history")));
    } finally { ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aliasedLegacyHistoryIsHiddenByTheRunningReadInterfacesBeforeCleanup(boolean dangling) throws Exception {
    var previous = ConfigStore.globalOrNull();
    try (var engine = EngineTestHarness.start(directory.resolve("data"))) {
      Path history = Files.createDirectories(engine.dataDir().resolve("agent-history"));
      Files.writeString(history.resolve("aliased.md"), "aliasedhistorymarker");
      Path alias = Files.createSymbolicLink(directory.resolve("alias"), engine.dataDir());
      Path source = alias.resolve("agent-history/aliased.md");
      try (var view = engine.captureServingView()) {
        seed((RunningRuntime)view.ingestRuntime(), source, null, "aliasedhistorymarker");
      }
      if (dangling) Files.delete(history.resolve("aliased.md"));
      assertTrue(engine.awaitNotSearchable("aliasedhistorymarker", 5_000));
      assertFalse(engine.client().retrieveContext("aliasedhistorymarker", Set.of(source.toString()), 5,
          TestEngineContexts.FOREGROUND).getContext().contains("aliasedhistorymarker"));
      assertEquals(0, engine.client().matchCitations("aliasedhistorymarker", List.of(source.toString()),
          List.of(0), List.of(), 0.0f, TestEngineContexts.FOREGROUND).getMatchesCount());
      assertEquals(0, engine.client().listFolderFiles(alias.resolve("agent-history").toString(), 100,
          List.of(SchemaFields.CONTENT, SchemaFields.TITLE), TestEngineContexts.FOREGROUND).getFilesCount());
      var slice = engine.client().fetchDocumentSlice(source + "#legacy_chunk", 0, 100,
          TestEngineContexts.FOREGROUND);
      assertNotEquals("PUBLIC", slice.getMetadataOrDefault("read_ownership", "PUBLIC"),
          "by-ID replies retain joined ownership even when no text can be returned");
    } finally { ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous); }
  }

  @Test void helpOwnershipKeepsLegacyUntaggedChildrenWhole() throws Exception {
    var previous = ConfigStore.globalOrNull();
    try (var engine = EngineTestHarness.start(directory.resolve("data"))) {
      Path help = ConfigStore.global().get().collections().bundledHelp().path().resolve("search.md");
      try (var view = engine.captureServingView()) {
        var runtime = (RunningRuntime) view.ingestRuntime();
        seed(runtime, help, "justsearch-help", "bundledhelpmarker");
        // Replace the child with an old untagged projection while retaining its owning parent.
        runtime.indexingCoordinator().deleteByIdAndChunks(help + "#legacy_chunk");
        var fields = new HashMap<String, Object>();
        fields.put(SchemaFields.DOC_ID, help + "#legacy_chunk");
        fields.put(SchemaFields.DOC_UID, help + "#legacy_chunk");
        fields.put(SchemaFields.PATH, help.toString());
        fields.put(SchemaFields.PARENT_DOC_ID, help.toString());
        fields.put(SchemaFields.IS_CHUNK, true);
        fields.put(SchemaFields.CONTENT, "bundledhelpmarker");
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
        runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
        assertTrue(remove(new ExcludesServiceImpl(engine::client), engine.dataDir(), ignored -> {}));
        assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(help.toString()));
        assertTrue(engine.awaitSearchable("bundledhelpmarker", 10_000));
      }
    } finally { ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous); }
  }

  @Test void publicIdSnapshotsFilterInTheWorkerAndBindTheirVisibilityScope() throws Exception {
    var previous = ConfigStore.globalOrNull();
    try (var engine = EngineTestHarness.start(directory.resolve("data"))) {
      Path ordinary = directory.resolve("public.txt");
      Path legacy = engine.dataDir().resolve("agent-history/legacy.md");
      Path dataCopy = engine.dataDir().resolve("user-copy.txt");
      Path help = directory.resolve("package/help.md");
      Path tagged = directory.resolve("old-history.md");
      try (var view = engine.captureServingView()) {
        var runtime = (RunningRuntime)view.ingestRuntime();
        seed(runtime, ordinary, null, "publicidmarker");
        seed(runtime, legacy, null, "privateidmarker");
        seed(runtime, dataCopy, "personal", "privateidmarker");
        seed(runtime, help, "justsearch-help", "reservedidmarker");
        seed(runtime, tagged, "agent-history", "reservedidmarker");
      }
      var ids = publicIds(engine.client());
      assertEquals(List.of(ordinary.toString()), ids.getDocIdsList());
      assertEquals(1, ids.getTotalCount());
      assertEquals(5, engine.client().listAllDocumentIds(0, 100, TestEngineContexts.FOREGROUND).getTotalCount());
      var mismatch = assertThrows(io.justsearch.app.api.knowledge.KnowledgeClientException.class,
          () -> engine.client().listAllDocumentIds(1, 1, ids.getSnapshotToken(), TestEngineContexts.FOREGROUND));
      assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.ABORTED, mismatch.status());
    } finally { ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous); }
  }

  private static io.justsearch.ipc.ListAllDocumentIdsResponse publicIds(
      io.justsearch.app.services.worker.KnowledgeClient client) throws Exception {
    try {
      return (io.justsearch.ipc.ListAllDocumentIdsResponse) client.getClass().getMethod("listAllDocumentIds",
          int.class, int.class, boolean.class, EngineContext.class)
          .invoke(client, 0, 100, false, TestEngineContexts.FOREGROUND);
    } catch (NoSuchMethodException baselineHasNoPublicScope) {
      return client.listAllDocumentIds(0, 100, TestEngineContexts.FOREGROUND);
    }
  }

  private static void assertHistorySearchable(EngineTestHarness engine, String marker) {
    var request = io.justsearch.ipc.SearchRequest.newBuilder().setQuery(marker).setLimit(10)
        .setFilters(io.justsearch.ipc.SearchFilters.newBuilder().addCollection("agent-history")).build();
    assertTrue(engine.client().search(request, TestEngineContexts.FOREGROUND).getTotalHits() > 0,
        "cutover must finish reserved-history reconciliation before publishing the candidate");
  }

  private static io.justsearch.ipc.ListFolderFilesResponse privateFiles(
      io.justsearch.app.services.worker.KnowledgeClient client, Path folder) throws Exception {
    try {
      return (io.justsearch.ipc.ListFolderFilesResponse) client.getClass().getMethod("listFolderFiles",
          String.class, int.class, List.class, boolean.class, EngineContext.class).invoke(client,
              folder.toString(), 10, List.of(SchemaFields.CONTENT, SchemaFields.TITLE), true, TestEngineContexts.BACKGROUND);
    } catch (NoSuchMethodException baselineHasNoPrivateBrowseScope) {
      return client.listFolderFiles(folder.toString(), 10, List.of(SchemaFields.CONTENT, SchemaFields.TITLE), TestEngineContexts.BACKGROUND);
    }
  }

  static boolean remove(ExcludesServiceImpl cleanup, Path data, Consumer<Path> reconcile) throws Exception {
    try {
      return (boolean) ExcludesServiceImpl.class.getMethod("removeProtectedSources", Path.class,
          EngineContext.class, Consumer.class).invoke(cleanup, data, TestEngineContexts.BACKGROUND, reconcile);
    } catch (NoSuchMethodException baselineHasNoUpgradeRemoval) { return false; }
    catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      throw failure;
    }
  }

  private static void seed(RunningRuntime runtime, Path path, String collection, String marker) {
    String id = path.toAbsolutePath().toString();
    for (boolean chunk : List.of(false, true)) {
      var fields = new HashMap<String, Object>();
      fields.put(SchemaFields.DOC_ID, chunk ? id + "#legacy_chunk" : id);
      fields.put(SchemaFields.DOC_UID, id + (chunk ? "#c" : "#p"));
      fields.put(SchemaFields.PATH, id);
      fields.put(SchemaFields.CONTENT, marker);
      fields.put(SchemaFields.TITLE, marker);
      fields.put(SchemaFields.VDU_ENRICHMENT, marker);
      fields.put(SchemaFields.SPLADE, Map.of("private", 1.0f));
      fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
      fields.put(SchemaFields.ENTITY_PERSONS_RAW, List.of("Private Person"));
      if (collection != null) fields.put(SchemaFields.COLLECTION, collection);
      if (chunk) {
        fields.put(SchemaFields.IS_CHUNK, true);
        fields.put(SchemaFields.PARENT_DOC_ID, id);
        fields.put(SchemaFields.CHUNK_CONTENT, marker);
        fields.put(SchemaFields.CHUNK_INDEX, 0);
      }
      Integer dimensions = runtime.schema().ssotVectorDimension();
      if (dimensions != null) { float[] vector = new float[dimensions]; vector[0] = 1; fields.put(SchemaFields.VECTOR, vector); }
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
    runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
  }
}
