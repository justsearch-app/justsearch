/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import org.junit.jupiter.api.Test;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;

/** Extraction fidelity survives the actual writer, index reopening and explicit re-extraction. */
class ExtractionFidelityIndexingTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @Test
  void raggedTableRepairSurvivesReopeningAndOnlyExplicitForceRepairsHistoricalText() throws Exception {
    Path file = Files.writeString(tempDir.resolve("ragged.html"),
        "<html><body><table><tr><td>Report</td></tr><tr><td>Name</td><td>UniqueSecret</td></tr>"
            + "</table></body></html>");
    String id = PathNormalizer.normalizeKey(file);
    Set<String> forced = new HashSet<>();
    var policy = TikaExtractionPolicy.defaults();
    try (var runtime = open();
        var timebox = io.justsearch.indexerworker.extract.ExtractionSandboxFactory.create(
            TestWorkerExecutorRegistrations.ocr(), TestWorkerExecutorRegistrations.timebox(),
            TestWorkerExecutorRegistrations.readers(),
            io.justsearch.indexerworker.extract.ExtractionSandboxFactory.Mode.PROCESS,
            policy, java.time.Duration.ofSeconds(30), null,
            io.justsearch.indexerworker.extract.ExtractionSandboxCommand.defaultCommand(policy, "256m"))) {
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
      var queue = mock(JobQueue.class);
      when(queue.ownsClaimForPublication(any())).thenReturn(true);
      var journal = mock(IngestionOutcomeJournal.class);
      doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
          .when(journal).recordOutcomeSafely(any(), anyString(), any());
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
      var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, file);

      var extracted = extractor.extractAll(List.of(claim));
      assertEquals(1, extracted.size());
      writer.write(extracted.getFirst(), null);
      refresh(runtime);

      assertTrue(runtime.documentFieldOps().getDocumentContent(id).contains("UniqueSecret"));
      assertEquals(policy.policyId(), runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
      assertTrue(runtime.indexingCoordinator().updateDocument(id,
          Map.of(SchemaFields.CONTENT, "Historical incomplete text",
              SchemaFields.CONTENT_PREVIEW, "Historical incomplete text",
              SchemaFields.CONTENT_SHA256, io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("Historical incomplete text"),
              SchemaFields.EXTRACTION_POLICY_ID, "tika-default-v7")));
      io.justsearch.indexerworker.rag.ChunkDocumentWriter.regenerateChunksFromExistingParent(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), id, "Historical incomplete text", false);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified(id, Files.getLastModifiedTime(file).toMillis(), Files.size(file)));
      assertTrue(extractor.extractAll(List.of(claim)).isEmpty(), "policy bump alone does not re-extract unchanged files");
      assertEquals("Historical incomplete text", runtime.documentFieldOps().getDocumentContent(id));
      var forcedClaim = new JobQueue.IndexJob(file, null, null, "force-scan", "force-unit", 1L, true, null, file);
      var repaired = extractor.extractAll(List.of(forcedClaim));
      assertEquals(1, repaired.size());
      writer.write(repaired.getFirst(), null);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().getDocumentContent(id).contains("UniqueSecret"));
      assertTrue(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_PREVIEW).contains("UniqueSecret"));
    }
    try (var runtime = open()) {
      assertTrue(runtime.documentFieldOps().getDocumentContent(id).contains("UniqueSecret"));
      assertTrue(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_PREVIEW).contains("UniqueSecret"));
      assertEquals(policy.policyId(), runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
    }
  }
}
