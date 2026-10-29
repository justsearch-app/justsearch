/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real persisted queue, admission, extractor, writer, parent/chunk search and reopen. */
final class RestoredMtimeQueuedRestartTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void shrinkAndSecondChangeAfterRestartReplaceParentAndChunkSearchResults() throws Exception {
    Path file = seed("obsoletequokka ".repeat(1500), false);
    FileTime stamp = Files.getLastModifiedTime(file);
    replaceAndProcess(file, stamp, "freshbadger ".repeat(300), "obsoletequokka", "freshbadger");
    replaceAndProcess(file, stamp, "latestwombat ".repeat(600), "freshbadger", "latestwombat");
  }

  @Test
  void missingStoredSizeIsReextractedOnce() throws Exception {
    Path file = seed("legacyotter ".repeat(300), true);
    try (var runtime = open()) {
      assertNull(runtime.documentFieldOps().getDocumentField(PathNormalizer.normalizeKey(file), SchemaFields.SIZE_BYTES));
    }
    assertEquals(1, process(file), "a missing stored size needs one extraction");
    assertEquals(0, process(file), "the refreshed size must make the next start skip extraction");
  }

  @Test
  void unchangedQueuedFileSkipsExtractionAcrossRestarts() throws Exception {
    Path file = seed("unchangedpanda ".repeat(300), false);
    assertEquals(0, process(file));
    assertEquals(0, process(file));
  }

  @Test
  void unchangedStampWithMissingChunksStillRepairsTheFile() throws Exception {
    Path file = seed("repairplatypus ".repeat(300), false);
    try (var runtime = open()) {
      runtime.indexingCoordinator().deleteChunksForParentDocId(PathNormalizer.normalizeKey(file));
      refresh(runtime);
    }
    assertEquals(1, process(file), "matching mtime and size cannot certify missing chunks");
    assertEquals(0, process(file));
  }

  private Path seed(String content, boolean omitSize) throws Exception {
    Path file = Files.writeString(temporary.resolve("source.txt"), content);
    try (var runtime = open(); var parser = new ContentExtractor()) {
      var policy = TikaExtractionPolicy.defaults();
      var artifact = ExtractionArtifact.full(parser.extract(file), policy, "stamp-fixture", false).validate(policy, null);
      var document = IndexingDocumentOps.buildDocument(file, artifact, null, null, null, false, null, null,
          (stage, ms, reason) -> {}, org.slf4j.LoggerFactory.getLogger(getClass()), null,
          new IndexingDocumentOps.SourceFileMetadata(Files.size(file), Files.getLastModifiedTime(file).toMillis(), null), "stable-uid");
      var fields = new HashMap<>(document.fields());
      if (omitSize) fields.remove(SchemaFields.SIZE_BYTES);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      refresh(runtime);
      String id = PathNormalizer.normalizeKey(file);
      String indexedContent = runtime.documentFieldOps().getDocumentContent(id);
      assertTrue(ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
          runtime.indexingCoordinator(), id, indexedContent, false) > 0);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified(id, Files.getLastModifiedTime(file).toMillis()),
          "the seeded parent and chunks must describe the same complete revision");
    }
    return file;
  }

  private void replaceAndProcess(Path file, FileTime stamp, String content, String oldToken, String newToken) throws Exception {
    Files.writeString(file, content);
    Files.setLastModifiedTime(file, stamp);
    assertEquals(stamp.toMillis(), Files.getLastModifiedTime(file).toMillis());
    assertEquals(1, process(file), "changed size must reach extraction despite restored mtime");
    try (var runtime = open()) {
      String id = PathNormalizer.normalizeKey(file);
      assertEquals(content.strip(), runtime.documentFieldOps().getDocumentContent(id).strip());
      assertEquals(String.valueOf(Files.size(file)), runtime.documentFieldOps().getDocumentField(id, SchemaFields.SIZE_BYTES));
      assertEquals(String.valueOf(stamp.toMillis()), runtime.documentFieldOps().getDocumentField(id, SchemaFields.MODIFIED_AT));
      for (String field : List.of(SchemaFields.CONTENT, SchemaFields.CHUNK_CONTENT)) {
        assertEquals(0, hits(runtime, field, oldToken), "old token must disappear from " + field);
        assertTrue(hits(runtime, field, newToken) > 0, "new token must be searchable in " + field);
      }
    }
    assertEquals(0, process(file), "persisted stamp must skip unchanged work after restart");
  }

  private static long hits(RunningRuntime runtime, String field, String token) throws Exception {
    return runtime.readPathOps().search(new TermQuery(new Term(field, token)), 100,
        Set.of(SchemaFields.DOC_ID), null, null).totalHits();
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temporary.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private int process(Path file) throws Exception {
    try (var runtime = open(); var queue = new SqliteJobQueue(temporary.resolve("queue.db"));
        var owners = new TestEngineExecutors(); var parser = new ContentExtractor();
        var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
            "stamp-restart", 1), parser, Duration.ofSeconds(20), null)) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary))));
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
      var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class),
          mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
      var authority = new WorkerIngestionAuthority();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var fence = new FileMutationFence();
      var extractor = new JobBatchExtractor(authority, journal, queue, timebox,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);
      var claims = queue.pollPending(1);
      assertEquals(1, claims.size());
      var jobs = extractor.extractAll(claims);
      for (var job : jobs) writer.write(job, null);
      refresh(runtime);
      journal.drainPending();
      assertEquals(0, queue.queueDepth());
      return jobs.size();
    }
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
