/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitSurrogateQueuedRestartTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @Test
  void restartKeepsLegacyReplacementsAndPolicyWithoutUserAction() throws Exception {
    checkQueuedRestart(false);
  }

  @Test
  void forcedReextractionRepairsStoredContentHashAndPreviewAcrossRestart() throws Exception {
    checkQueuedRestart(true);
  }

  private void checkQueuedRestart(boolean force) throws Exception {
    String prefix = "Ordinary café text. ".repeat(300).substring(0, 4095);
    String expected = prefix + "😀target preserved source";
    String legacy = prefix + "��target preserved source";
    Path file = Files.writeString(tempDir.resolve("unchanged.txt"), expected);
    String id = PathNormalizer.normalizeKey(file);
    Path db = tempDir.resolve("queue.db");
    // Simulate a prior install's stored document and durable work waiting at shutdown.
    try (var runtime = open(); var queue = new SqliteJobQueue(db)) {
      queue.open();
      var parentFields = new HashMap<String, Object>(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "stable-uid", SchemaFields.PATH, id,
          SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(),
          SchemaFields.SIZE_BYTES, Files.size(file),
          SchemaFields.CONTENT, legacy, SchemaFields.EXTRACTION_POLICY_ID, "tika-default-v3"));
      var plan = ChunkDocumentWriter.plan(legacy, "text/plain", "txt");
      parentFields.put(SchemaFields.CONTENT_SHA256, plan.revision());
      parentFields.put(SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file));
      ChunkDocumentWriter.declare(parentFields, plan);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(parentFields));
      ChunkDocumentWriter.replaceDeclaredChunks(runtime.indexingCoordinator(), id, legacy,
          new ChunkDocumentWriter.ParentChunkMetadata("text/plain", "text/plain", "txt", "en",
              null, null, "stable-uid"), plan, false);
      refresh(runtime);
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(tempDir))));
    }
    // Reopen both persisted stores and construct a fresh extractor under the current policy.
    Set<String> forced = new HashSet<>();
    try (var runtime = open(); var queue = new SqliteJobQueue(db);
        var owners = new TestEngineExecutors(); var parser = new ContentExtractor();
        var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
            "encoding-restart", 1), parser, Duration.ofSeconds(10), null)) {
      queue.open();
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
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), forced,
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);

      var claims = queue.pollPending(1);
      assertEquals(1, claims.size(), "queued work survives restart");
      assertTrue(extractor.extractAll(claims).isEmpty(), "unchanged legacy content is not rewritten");
      assertEquals(legacy, runtime.documentFieldOps().getDocumentContent(id));
      assertEquals("tika-default-v3", runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
      assertEquals(0, queue.queueDepth());
      if (!force) return;
      forced.add(id);
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(tempDir))));
      var extracted = extractor.extractAll(queue.pollPending(1));
      assertEquals(1, extracted.size(), "force bypasses the unchanged decision");
      writer.write(extracted.getFirst(), null);
      refresh(runtime);
      journal.drainPending();
      assertEquals(0, queue.queueDepth());
      assertRepairedFields(runtime, id, expected, prefix);
      assertEquals(TikaExtractionPolicy.defaults().policyId(),
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
    }
    try (var runtime = open()) {
      assertRepairedFields(runtime, id, expected, prefix);
      assertEquals(TikaExtractionPolicy.defaults().policyId(),
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
    }
  }

  private static void assertRepairedFields(RunningRuntime runtime, String id, String expected, String prefix)
      throws Exception {
    var fields = runtime.documentFieldOps();
    assertEquals(expected, fields.getDocumentContent(id).strip());
    assertEquals(prefix, fields.getDocumentField(id, SchemaFields.CONTENT_PREVIEW));
    String stored = fields.getDocumentContent(id);
    String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(hash, fields.getDocumentField(id, SchemaFields.CONTENT_SHA256));
    assertTrue(runtime.textQueryOps().searchText("😀", 10, null).hits().stream()
        .anyMatch(hit -> id.equals(hit.docId())), "search must depend on the supplementary character");
  }
}
