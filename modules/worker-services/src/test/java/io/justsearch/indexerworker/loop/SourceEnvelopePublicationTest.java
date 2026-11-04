/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.SourceEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SourceEnvelopePublicationTest extends LuceneExecutorTestBase {
  private static final String ORIGIN = "00000000-0000-4000-8000-000000000001";
  @TempDir Path temporary;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"watched-short", "watched-long", "import-short", "import-long", "exact-short", "exact-long", "legacy-short", "legacy-long"})
  void normalPublicationCarriesEnvelopeToTargetActiveAndEveryChunk(String admission) throws Exception {
    boolean chunked = admission.endsWith("long");
    boolean knownOrigin = admission.startsWith("watched") || admission.startsWith("import");
    Path file = temporary.resolve("source.txt");
    String content = "provenance lexical needle " + (chunked ? "one two three four five six seven eight.\n".repeat(600) : "short source");
    Files.writeString(file, content);
    Path targetPath = temporary.resolve("target");
    Path activePath = temporary.resolve("active");
    var catalog = new JustSearchConfigurationLoader().loadFieldCatalog();
    try (var target = IndexSchema.fromCatalog(catalog).atPath(targetPath).withExecutorRegistrations(testLuceneExecutors()).open();
        var active = IndexSchema.fromCatalog(catalog).atPath(activePath).withExecutorRegistrations(testLuceneExecutors()).open()) {
      target.commitOps().stopCommitTimer();
      active.commitOps().stopCommitTimer();
      JobQueue queue = mock(JobQueue.class, invocation -> {
        if (invocation.getMethod().getName().equals("sourceOriginId")) {
          return knownOrigin ? Optional.of(ORIGIN) : Optional.empty();
        }
        return RETURNS_DEFAULTS.answer(invocation);
      });
      when(queue.ownsClaimForPublication(any())).thenReturn(true);
      var parser = mock(TimeboxedContentExtractor.class);
      var policy = TikaExtractionPolicy.defaults();
      when(parser.extractionPolicy()).thenReturn(policy);
      when(parser.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
          new ContentExtractor.ExtractionResult(content, "source", "text/plain"),
          policy, "envelope-publication-test", false));
      var journal = mock(IngestionOutcomeJournal.class);
      var authority = new WorkerIngestionAuthority();
      var roots = mock(SourcePresence.RootCheck.class);
      when(roots.admits(any())).thenReturn(true);
      when(roots.admitsFile(any())).thenReturn(true);
      authority.useRootCheck(roots);
      var fence = new FileMutationFence();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-parent", 1, 1));
      var extractor = new JobBatchExtractor(authority, journal, queue, parser,
          target.documentFieldOps(), target.indexCountOps(), stats, stale, mock(StaleSourceHandler.class),
          IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false,
          ignored -> {}, fence);
      var writer = new JobBatchWriter(authority, target.indexingCoordinator(), mock(WorkerSignalBus.class),
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, parser,
          mock(OperationalMetrics.class), stats, stale, ignored -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> active, fence);
      Path root = admission.startsWith("exact") ? file : temporary;
      var claim = new JobQueue.IndexJob(file, "documents", null,
          admission.startsWith("import") ? "import-operation" : null, null, null, false, null, root);
      writer.beginBatch();
      useControlledStorage(writer);
      var extracted = assertDoesNotThrow(() -> extractor.extractAll(List.of(claim)));
      assertEquals(1, extracted.size());
      assertDoesNotThrow(() -> writer.write(extracted.getFirst(), null));
      for (var runtime : List.of(target, active)) {
        commit(runtime);
        assertEnvelopeRows(runtime == target ? targetPath : activePath, knownOrigin, chunked);
        String id = PathNormalizer.normalizeKey(file);
        assertTrue(runtime.indexingCoordinator().updateDocument(id, Map.of("ner_status", "COMPLETED_EMPTY")));
        commit(runtime);
        assertEnvelopeRows(runtime == target ? targetPath : activePath, knownOrigin, chunked);
      }
    }
    for (Path path : List.of(targetPath, activePath)) {
      try (var reopened = IndexSchema.fromCatalog(catalog).atPath(path).withExecutorRegistrations(testLuceneExecutors()).open()) {
        assertEnvelopeRows(path, knownOrigin, chunked);
        if (chunked) {
          reopened.indexingCoordinator().deleteById(PathNormalizer.normalizeKey(file));
          commit(reopened);
          assertNull(reopened.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(file), SchemaFields.DOC_ID));
          assertEnvelopeRows(path, knownOrigin, true);
        }
      }
    }
  }

  @Test
  void regeneratedChunksCopyTheExistingParentsCompleteEnvelope() throws Exception {
    var catalog = new JustSearchConfigurationLoader().loadFieldCatalog();
    try (var runtime = IndexSchema.fromCatalog(catalog).atPath(temporary.resolve("regenerate"))
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      String content = "regenerated parent words and sentences.\n".repeat(600);
      var fields = new HashMap<String, Object>(Map.of("doc_id", "parent", "doc_uid", "parent-uid",
          "path", "/parent", "content", content, "collection", "documents", "mime", "text/plain"));
      fields.putAll(Map.of("origin_id", ORIGIN, "envelope_version", "1", "storage_status", "known",
          "storage_mount", "/media/disk", "storage_key", "aaaa-1111:ext4:/subtree"));
      runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(fields));
      commit(runtime);
      int count = assertDoesNotThrow(() -> ChunkDocumentWriter.regenerateChunksFromExistingParent(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), "parent", content, false));
      assertTrue(count > 1);
      commit(runtime);
      try (var directory = org.apache.lucene.store.FSDirectory.open(temporary.resolve("regenerate"));
          var reader = DirectoryReader.open(directory)) {
        var searcher = new IndexSearcher(reader);
        var chunks = searcher.search(new TermQuery(new Term("parent_doc_id", "parent")), 1000);
        assertEquals(count, chunks.totalHits.value());
        for (var hit : chunks.scoreDocs) {
          var stored = searcher.storedFields().document(hit.doc);
          for (String field : List.of("origin_id", "envelope_version", "storage_status", "storage_mount", "storage_key")) {
            assertEquals(fields.get(field), stored.get(field), "inherited " + field);
          }
        }
      }
    }
  }

  @Test
  void unreadableParentEnvelopeCannotDeleteOrPublishReplacementChunks() throws Exception {
    var fields = mock(DocumentFieldOps.class);
    when(fields.getDocumentField("parent", SchemaFields.DOC_UID)).thenReturn("parent-uid");
    when(fields.getDocumentFieldsBatchOrThrow(anyList(), anySet())).thenThrow(new java.io.IOException("unreadable envelope"));
    var coordinator = mock(IndexingCoordinator.class);
    assertThrows(IndexRuntimeIOException.class, () -> ChunkDocumentWriter.regenerateChunksFromExistingParent(
        fields, coordinator, "parent", "replacement text with many words.\n".repeat(600), false));
    assertDoesNotThrow(() -> verifyNoInteractions(coordinator));
  }

  private static void useControlledStorage(JobBatchWriter writer) throws Exception {
    // On P1a there is no observer to inject; publication assertions below expose that absence.
    java.lang.reflect.Field field;
    try { field = JobBatchWriter.class.getDeclaredField("storageSnapshot"); }
    catch (NoSuchFieldException before) { return; }
    var type = Class.forName("io.justsearch.indexerworker.services.StorageObservationSnapshot");
    if (!io.justsearch.configuration.PlatformPaths.isLinux()) {
      var portable = type.getDeclaredMethod("notObserved");
      portable.setAccessible(true);
      field.setAccessible(true);
      field.set(writer, portable.invoke(null));
      return;
    }
    var evidence = Class.forName(type.getName() + "$Evidence");
    Object fixture = java.lang.reflect.Proxy.newProxyInstance(evidence.getClassLoader(), new Class<?>[] {evidence},
        (proxy, method, args) -> switch (method.getName()) {
          case "mountInfo" -> List.of("1 0 8:1 / / rw - ext4 /dev/fixture rw");
          case "uuidDevices" -> Map.of("8:1", "aaaa-1111");
          case "sourceDevice" -> "8:1";
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var capture = type.getDeclaredMethod("linux", evidence);
    capture.setAccessible(true);
    field.setAccessible(true);
    field.set(writer, capture.invoke(null, fixture));
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void assertEnvelopeRows(Path path, boolean origin, boolean chunks) throws Exception {
    try (var directory = org.apache.lucene.store.FSDirectory.open(path);
        var reader = DirectoryReader.open(directory)) {
      var searcher = new IndexSearcher(reader);
      var rows = searcher.search(new MatchAllDocsQuery(), 1000);
      assertTrue(rows.totalHits.value() > 0);
      int chunkCount = 0;
      for (var hit : rows.scoreDocs) {
        var stored = searcher.storedFields().document(hit.doc);
        assertEquals("1", stored.get("envelope_version"), "every published row needs its own envelope");
        assertEquals(origin ? ORIGIN : null, stored.get("origin_id"));
        Map<String, Object> envelope = new HashMap<>();
        for (String field : List.of("origin_id", "envelope_version", "storage_status", "storage_mount", "storage_key")) {
          if (stored.get(field) != null) envelope.put(field, stored.get(field));
        }
        assertTrue(SourceEnvelope.decode(envelope).isPresent());
        if (io.justsearch.configuration.PlatformPaths.isLinux()) {
          assertEquals("known", stored.get("storage_status"));
          assertEquals("/", stored.get("storage_mount"));
          assertEquals("aaaa-1111:ext4:/", stored.get("storage_key"));
        } else {
          assertEquals("not_observed", stored.get("storage_status"));
          assertNull(stored.get("storage_mount"));
          assertNull(stored.get("storage_key"));
        }
        assertEquals("documents", stored.get(SchemaFields.COLLECTION));
        if ("true".equals(stored.get(SchemaFields.IS_CHUNK))) chunkCount++;
      }
      assertEquals(chunks, chunkCount > 1);
    }
  }
}
