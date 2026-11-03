/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SyncDirectoryOpsChangedStampTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path temporary;


  @org.junit.jupiter.api.Test
  void failedLaterCensusPageWithholdsVerificationAndStillWalksDisk() throws Exception {
    Path added = Files.writeString(temporary.resolve("added.txt"), "new");
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0, "next"))
        .thenThrow(new IllegalStateException("later page unavailable"));
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled());
    var result = ops.execute(temporary.toString(), false, null, () -> false, null);
    assertEquals("", result.getError());
    assertTrue(result.getDeleteDetectionUnverified());
    assertEquals(1, result.getFilesAdded());
    verify(queue).enqueueEntries(argThat(entries -> entries.size() == 1 && entries.getFirst().path().equals(added)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"paths", "characters"})
  void censusCapsStillAllowPeriodicRecoveryOfAdditionsAndEdits(String cap) throws Exception {
    Path changed = Files.writeString(temporary.resolve("changed.txt"), "changed bytes");
    Path added = Files.writeString(temporary.resolve("added.txt"), "new");
    var hits = new ArrayList<LuceneRuntimeTypes.SearchHit>();
    hits.add(new LuceneRuntimeTypes.SearchHit(changed.toString(), 1f,
        java.util.Map.of(SchemaFields.SIZE_BYTES, "1", SchemaFields.MODIFIED_AT, "0")));
    hits.add(new LuceneRuntimeTypes.SearchHit(temporary.resolve("old.txt").toString(), 1f, java.util.Map.of()));
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(hits, 2, 0));
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled(), null,
        cap.equals("paths") ? 1 : 200_000, cap.equals("characters") ? changed.toString().length() : 16 * 1024 * 1024);
    for (int attempt = 0; attempt < 2; attempt++) {
      var result = ops.execute(temporary.toString(), false, null, () -> false, null);
      assertEquals("", result.getError());
      assertTrue(result.getDeleteDetectionUnverified());
      assertEquals(2, result.getFilesAdded());
    }
    verify(queue, times(2)).enqueueEntries(argThat(entries ->
        entries.stream().map(JobQueue.EnqueueEntry::path).collect(java.util.stream.Collectors.toSet())
            .equals(java.util.Set.of(added, changed))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"size", "mtime", "missing-size"})
  void ordinaryReconciliationAdmitsChangedIndexedFiles(String change) throws Exception {
    check(change, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"size", "mtime", "missing-size"})
  void candidateReconciliationAdmitsChangedIndexedFiles(String change) throws Exception {
    check(change, true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unchangedIndexedFilesStaySkipped(boolean candidate) throws Exception {
    check("unchanged", candidate);
  }


  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.CsvSource({
      "missing,false,DEFAULT", "missing,false,MAINTENANCE",
      "missing,true,DEFAULT", "missing,true,MAINTENANCE",
      "duplicate,false,DEFAULT", "duplicate,false,MAINTENANCE",
      "duplicate,true,DEFAULT", "duplicate,true,MAINTENANCE",
      "revision,false,DEFAULT", "revision,false,MAINTENANCE",
      "revision,true,DEFAULT", "revision,true,MAINTENANCE"})
  void ordinarySyncReadmitsDamagedIndexedFamily(String damage, boolean staleSearchReader,
      SyncDirectoryOps.AdmissionMode mode) throws Exception {
    try (var runtime = openRuntime()) {
      Path file = seedFamily(runtime);
      var queue = mock(JobQueue.class);
      when(queue.enqueueEntries(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
      when(queue.enqueueMaintenanceScanEntries(anyList(), nullable(String.class), any()))
          .thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
      var discoveryRuntime = runtime;
      if (staleSearchReader) {
        // Pruning explicitly refreshes its reader. Hold that independent phase out of this fixture
        // so both the census and the post-decision observation retain the old complete family.
        discoveryRuntime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class,
            org.mockito.AdditionalAnswers.delegatesTo(runtime));
        when(discoveryRuntime.pruneOps()).thenReturn(null);
      }
      var sync = serviceSync(discoveryRuntime, queue, false);
      assertEquals(0, sync.execute(temporary.toString(), false, null, () -> false, null, mode).getFilesAdded());
      damageFamily(runtime, file, damage, staleSearchReader);
      var result = sync.execute(temporary.toString(), false, null, () -> false, null, mode);
      assertEquals("", result.getError());
      assertEquals(1, result.getFilesAdded(), "the complete parent cannot suppress a damaged family");
      assertFalse(result.getDeleteDetectionUnverified());
      if (staleSearchReader) assertSearchReaderStillComplete(runtime, PathNormalizer.normalizeKey(file));
      assertDoesNotThrow(() -> {
        if (mode == SyncDirectoryOps.AdmissionMode.DEFAULT) {
          verify(queue).enqueueEntries(argThat(entries -> entries.size() == 1 && entries.getFirst().path().equals(file)));
        } else {
          verify(queue).enqueueMaintenanceScanEntries(
              argThat(entries -> entries.size() == 1 && entries.getFirst().path().equals(file)), isNull(), any());
        }
      });
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.CsvSource({
      "missing,false", "missing,true", "duplicate,false", "duplicate,true",
      "revision,false", "revision,true"})
  void candidateDiscoveryIncludesDamagedIndexedFamily(String damage, boolean staleSearchReader)
      throws Exception {
    try (var runtime = openRuntime()) {
      Path file = seedFamily(runtime);
      var sync = serviceSync(runtime, null, true);
      try (var complete = sync.discoverCandidateDifference(temporary.toString(), false, () -> false)) {
        assertTrue(complete.additions().isEmpty());
      }
      damageFamily(runtime, file, damage, staleSearchReader);
      try (var result = sync.discoverCandidateDifference(temporary.toString(), false, () -> false)) {
        assertEquals(List.of(file), result.additions(), "migration planning must include damaged families");
        assertTrue(result.deletions().isEmpty(), "an existing source is never a deletion");
      }
      if (staleSearchReader) assertSearchReaderStillComplete(runtime, PathNormalizer.normalizeKey(file));
    }
  }


  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"DEFAULT", "MAINTENANCE", "CANDIDATE"})
  void unchangedThousandFileSyncMeasuresIndexReadBudget(String mode) throws Exception {
    try (var runtime = openRuntime()) {
      for (int n = 0; n < 1_000; n++) {
        Path file = Files.writeString(temporary.resolve("file-" + n + ".txt"), "unchanged");
        String id = PathNormalizer.normalizeKey(file);
        String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("unchanged");
        var fields = new HashMap<String, Object>();
        fields.put(SchemaFields.DOC_ID, id);
        fields.put(SchemaFields.DOC_UID, "uid-" + n);
        fields.put(SchemaFields.PATH, id);
        fields.put(SchemaFields.CONTENT, "unchanged");
        fields.put(SchemaFields.SIZE_BYTES, Files.size(file));
        fields.put(SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis());
        fields.put(SchemaFields.SOURCE_SHA256, io.justsearch.indexerworker.loop.SourceContentHash.sha256(file));
        fields.put(SchemaFields.EXTRACTION_POLICY_ID, io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults().policyId());
        fields.put(SchemaFields.CONTENT_SHA256, revision);
        fields.put(SchemaFields.CHUNK_TOTAL, 0);
        fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
        runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(fields));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var pointQueries = new java.util.concurrent.atomic.AtomicInteger();
      countAdmissionQueries(runtime, pointQueries);
      var reads = spy(runtime.readPathOps());
      var reuse = spy(runtime.documentFieldOps());
      var countedRuntime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      when(countedRuntime.readPathOps()).thenReturn(reads);
      when(countedRuntime.documentFieldOps()).thenReturn(reuse);
      var queue = mock(JobQueue.class);
      var sync = serviceSync(countedRuntime, queue, mode.equals("CANDIDATE"));
      if (mode.equals("CANDIDATE")) {
        try (var result = sync.discoverCandidateDifference(temporary.toString(), false, () -> false)) {
          assertTrue(result.additions().isEmpty());
          assertTrue(result.deletions().isEmpty());
        }
      } else {
        assertEquals(0, sync.execute(temporary.toString(), false, null, () -> false, null,
            SyncDirectoryOps.AdmissionMode.valueOf(mode)).getFilesAdded());
      }
      long censusReads = mockingDetails(reads).getInvocations().stream()
          .filter(call -> call.getMethod().getName().equals("search") && call.getArguments().length == 5)
          .count();
      var batches = mockingDetails(reuse).getInvocations().stream()
          .filter(call -> call.getMethod().getName().equals("isUnmodified"))
          .map(call -> (List<?>) call.getArguments()[0]).toList();
      int familyReads = pointQueries.get();
      assertEquals(batches.stream().mapToInt(List::size).sum(), familyReads,
          "each candidate performs exactly one point query, including reader-epoch retries");
      assertEquals(4, censusReads, "the existing census stays paged at 256");
      assertTrue(batches.stream().allMatch(batch -> batch.size() <= 256), "reuse is bounded per batch");
      assertTrue(familyReads == 0 || familyReads == 1_000,
          "at most one family lookup is added for each suppressed file");
      System.out.printf("P6 %s: censusQueries=%d, familyPointQueries=%d, admissionBatches=%d, totalIndexQueries=%d%n",
          mode, censusReads, familyReads, batches.size(), censusReads + familyReads);
      verifyNoInteractions(queue);
    }
  }
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"DEFAULT", "MAINTENANCE", "CANDIDATE"})
  void completeIndexedFamilyStaysSuppressedUnlessForced(String mode) throws Exception {
    try (var runtime = openRuntime()) {
      Path file = seedFamily(runtime);
      var queue = mock(JobQueue.class);
      when(queue.enqueueEntries(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
      when(queue.enqueueMaintenanceScanEntries(anyList(), nullable(String.class), any()))
          .thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
      var sync = serviceSync(runtime, queue, mode.equals("CANDIDATE"));
      if (mode.equals("CANDIDATE")) {
        try (var unchanged = sync.discoverCandidateDifference(temporary.toString(), false, () -> false)) {
          assertTrue(unchanged.additions().isEmpty());
          assertTrue(unchanged.deletions().isEmpty());
        }
        try (var forced = sync.discoverCandidateDifference(temporary.toString(), true, () -> false)) {
          assertEquals(List.of(file), forced.additions());
          assertTrue(forced.deletions().isEmpty());
        }
      } else {
        var admission = SyncDirectoryOps.AdmissionMode.valueOf(mode);
        assertEquals(0, sync.execute(temporary.toString(), false, null, () -> false, null, admission).getFilesAdded());
        assertEquals(1, sync.execute(temporary.toString(), true, null, () -> false, null, admission).getFilesAdded());
      }
    }
  }

  /** Count actual Lucene point searches on this fixture's admission reader, including retries. */
  private static void countAdmissionQueries(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime,
      java.util.concurrent.atomic.AtomicInteger queries) throws Exception {
    var field = io.justsearch.adapters.lucene.runtime.CommitOps.class.getDeclaredField("admissionManager");
    field.setAccessible(true);
    var original = (org.apache.lucene.search.SearcherManager) field.get(runtime.commitOps());
    var leased = original.acquire();
    org.apache.lucene.search.SearcherManager counted;
    try {
      leased.getIndexReader().incRef();
      counted = new org.apache.lucene.search.SearcherManager(
          (org.apache.lucene.index.DirectoryReader) leased.getIndexReader(),
          new org.apache.lucene.search.SearcherFactory() {
            @Override
            public org.apache.lucene.search.IndexSearcher newSearcher(org.apache.lucene.index.IndexReader reader,
                org.apache.lucene.index.IndexReader previous) {
              return new org.apache.lucene.search.IndexSearcher(reader) {
                @Override
                public org.apache.lucene.search.TopDocs search(org.apache.lucene.search.Query query, int limit)
                    throws java.io.IOException {
                  queries.incrementAndGet();
                  return super.search(query, limit);
                }
              };
            }
          });
    } finally {
      original.release(leased);
    }
    field.set(runtime.commitOps(), counted);
    original.close();
  }

  private static final String FAMILY_CONTENT = "alpha beta gamma delta ".repeat(400);

  private io.justsearch.adapters.lucene.runtime.RunningRuntime openRuntime() throws Exception {
    return io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
        new io.justsearch.configuration.JustSearchConfigurationLoader().loadFieldCatalog()).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private Path seedFamily(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime) throws Exception {
    Path file = Files.writeString(temporary.resolve("chunked.txt"), FAMILY_CONTENT);
    String id = PathNormalizer.normalizeKey(file);
    String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(FAMILY_CONTENT);
    int total = familyTotal();
    assertTrue(total > 1, "fixture must have a multi-chunk family");
    var fields = new HashMap<String, Object>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, "family-parent");
    fields.put(SchemaFields.PATH, id);
    fields.put(SchemaFields.CONTENT, FAMILY_CONTENT);
    fields.put(SchemaFields.SIZE_BYTES, Files.size(file));
    fields.put(SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis());
    fields.put(SchemaFields.SOURCE_SHA256, io.justsearch.indexerworker.loop.SourceContentHash.sha256(file));
    fields.put(SchemaFields.EXTRACTION_POLICY_ID, io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults().policyId());
    fields.put(SchemaFields.CONTENT_SHA256, revision);
    fields.put(SchemaFields.CHUNK_TOTAL, total);
    fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
    runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(fields));
    for (int chunk = 0; chunk < total; chunk++) writeChunk(runtime, id, chunk, chunk, revision);
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return file;
  }

  private static int familyTotal() {
    return io.justsearch.indexing.chunking.ChunkSplitter.splitWithMetadata(FAMILY_CONTENT,
        io.justsearch.indexing.chunking.ChunkSplitter.DEFAULT_CHUNK_TOKENS,
        io.justsearch.indexing.chunking.ChunkSplitter.DEFAULT_OVERLAP_TOKENS).size();
  }

  private static void writeChunk(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime,
      String parent, int child, int ordinal, String revision) {
    runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(java.util.Map.of(
        SchemaFields.DOC_ID, parent + "#" + child, SchemaFields.DOC_UID, "family-parent#" + child,
        SchemaFields.PATH, parent, SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, parent,
        SchemaFields.CHUNK_INDEX, ordinal, SchemaFields.CHUNK_TOTAL, familyTotal(),
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision)));
  }

  private static void damageFamily(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime,
      Path file, String damage, boolean staleSearchReader) throws Exception {
    String parent = PathNormalizer.normalizeKey(file);
    if (staleSearchReader) runtime.commitOps().suspendNrtRefresh();
    if (damage.equals("missing")) runtime.indexingCoordinator().deleteById(parent + "#1");
    else writeChunk(runtime, parent, 1, damage.equals("duplicate") ? 0 : 1,
        damage.equals("revision") ? "old-revision"
            : io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(FAMILY_CONTENT));
    runtime.commitOps().commitAndTrack();
    assertFalse(runtime.documentFieldOps().isUnmodified(parent, Files.getLastModifiedTime(file).toMillis(),
        Files.size(file), io.justsearch.indexerworker.loop.SourceContentHash.sha256(file),
        io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults().policyId()),
        "fixture must have a committed damaged family with matching parent evidence");
    if (!staleSearchReader) runtime.commitOps().maybeRefreshBlocking();
    if (staleSearchReader) assertSearchReaderStillComplete(runtime, parent);
  }

  private static void assertSearchReaderStillComplete(
      io.justsearch.adapters.lucene.runtime.RunningRuntime runtime, String parent) {
    var oldFamily = runtime.readPathOps().search(
        new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(SchemaFields.PARENT_DOC_ID, parent)),
        familyTotal() + 1, java.util.Set.of(SchemaFields.DOC_ID, SchemaFields.CHUNK_INDEX,
            SchemaFields.CHUNK_PARENT_CONTENT_SHA256), LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
    assertEquals(familyTotal(), oldFamily.hits().size(), "the search reader must retain every old child");
    assertEquals(java.util.stream.IntStream.range(0, familyTotal()).boxed().collect(java.util.stream.Collectors.toSet()),
        oldFamily.hits().stream().map(hit -> Integer.parseInt(hit.fields().get(SchemaFields.CHUNK_INDEX)))
            .collect(java.util.stream.Collectors.toSet()), "the search reader must retain the old ordinals");
    String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(FAMILY_CONTENT);
    assertTrue(oldFamily.hits().stream().allMatch(hit -> revision.equals(
        hit.fields().get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256))), "the search reader must retain the old revision");
  }

  private static SyncDirectoryOps serviceSync(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime,
      JobQueue queue, boolean candidate) throws Exception {
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null,
        null, runtime, runtime, null, 0L);
    var field = WorkerIngestService.class.getDeclaredField(candidate ? "candidateDiscoveryOps" : "syncOps");
    field.setAccessible(true);
    var sync = (SyncDirectoryOps) field.get(service);
    // This disposable fixture supplies presence authority directly, without a registered volume.
    sync.setPruneGuardFactory(io.justsearch.adapters.lucene.runtime.PruneOps.PresenceGuard::forRoot);
    return sync;
  }
  private void check(String change, boolean candidate) throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "original");
    var stamp = Files.getLastModifiedTime(file);
    String id = PathNormalizer.normalizeKey(file);
    var fields = new HashMap<String, String>();
    fields.put(SchemaFields.PATH, id);
    fields.put(SchemaFields.EXTRACTION_POLICY_ID, io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults().policyId());
    fields.put(SchemaFields.SOURCE_SHA256, io.justsearch.indexerworker.loop.SourceContentHash.sha256(file));
    fields.put(SchemaFields.MODIFIED_AT, String.valueOf(stamp.toMillis()));
    if (!change.equals("missing-size")) fields.put(SchemaFields.SIZE_BYTES, String.valueOf(Files.size(file)));
    if (change.equals("size")) {
      Files.writeString(file, "replacement with a different size");
      Files.setLastModifiedTime(file, stamp);
    } else if (change.equals("mtime")) {
      Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(stamp.toMillis() + 20_000));
    }
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(
            List.of(new LuceneRuntimeTypes.SearchHit(id, 1.0f, fields)), 1, 0));
    var queue = mock(JobQueue.class);
    var admitted = new ArrayList<Path>();
    org.mockito.stubbing.Answer<Integer> admission = invocation -> {
      List<JobQueue.EnqueueEntry> entries = invocation.getArgument(0);
      for (var entry : entries) {
        assertEquals(Files.size(entry.path()), entry.sizeBytes());
        admitted.add(entry.path());
      }
      return entries.size();
    };
    when(queue.enqueueEntries(anyList())).thenAnswer(admission);
    when(queue.enqueueMaintenanceScanEntries(anyList(), nullable(String.class), any())).thenAnswer(admission);
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled());
    var reuse = mock(io.justsearch.adapters.lucene.runtime.DocumentFieldOps.class);
    when(reuse.isUnmodified(anyList(), any())).thenAnswer(call ->
        ((List<io.justsearch.adapters.lucene.runtime.DocumentFieldOps.ReuseCandidate>) call.getArgument(0))
            .stream().map(io.justsearch.adapters.lucene.runtime.DocumentFieldOps.ReuseCandidate::docId)
            .collect(java.util.stream.Collectors.toSet()));
    ops.setDocumentFieldOps(reuse);
    List<Path> expected = change.equals("unchanged") ? List.of() : List.of(file.toAbsolutePath());
    if (candidate) {
      try (var result = ops.discoverCandidateDifference(temporary.toString(), false, () -> false)) {
        assertEquals(expected, result.additions(), "candidate reconciliation must compare the stored source stamp");
        assertTrue(result.deletions().isEmpty());
        result.requireCurrentRootIdentity();
      }
      verifyNoInteractions(queue);
    } else {
      for (var mode : List.of(SyncDirectoryOps.AdmissionMode.DEFAULT, SyncDirectoryOps.AdmissionMode.MAINTENANCE)) {
        admitted.clear();
        var result = ops.execute(temporary.toString(), false, null, () -> false, null, mode);
        assertEquals("", result.getError());
        assertEquals(expected, admitted, mode + " reconciliation must compare the stored source stamp");
        assertEquals(expected.size(), result.getFilesAdded());
      }
    }
  }
}
