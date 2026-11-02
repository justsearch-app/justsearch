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

/** Ordinary ingestion repairs historical extraction through the actual parser, writer and index. */
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

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"old-policy", "missing-policy", "configured-policy"})
  void ordinaryIngestionRepairsHistoricalExtractionAndStaysCurrentAfterReopening(String legacy) throws Exception {
    checkHistoricalRepair(legacy);
  }

  @Test
  void missingSourceEvidenceRepairsUnchangedContent() throws Exception {
    checkHistoricalRepair("missing-hash");
  }

  private void checkHistoricalRepair(String legacy) throws Exception {
    Path file = Files.writeString(tempDir.resolve("ragged.html"),
        "<html><body><table><tr><td>Report</td></tr><tr><td>Name</td><td>UniqueSecret</td></tr>"
            + "</table>" + "<p>UniqueSecret corrected content with searchable words.</p>".repeat(400) + "</body></html>");
    String id = PathNormalizer.normalizeKey(file);
    Set<String> forced = new HashSet<>();
    var policy = legacy.equals("configured-policy")
        ? TikaExtractionPolicy.fromWorkerLimits(new io.justsearch.configuration.resolved.ResolvedConfig.Worker(1_000_000, 5_000_000L))
        : TikaExtractionPolicy.defaults();
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
      String historical = "Historical incomplete text ".repeat(400);
      var fields = new HashMap<String, Object>();
      fields.put(SchemaFields.DOC_ID, id);
      fields.put(SchemaFields.DOC_UID, "stable-uid");
      fields.put(SchemaFields.PATH, id);
      fields.put(SchemaFields.CONTENT, historical);
      fields.put(SchemaFields.CONTENT_SHA256, io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(historical));
      fields.put(SchemaFields.CONTENT_PREVIEW, historical);
      fields.put(SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis());
      fields.put(SchemaFields.SIZE_BYTES, Files.size(file));
      if (!legacy.equals("missing-hash")) fields.put(SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file));
      if (!legacy.equals("missing-policy")) fields.put(SchemaFields.EXTRACTION_POLICY_ID,
          legacy.equals("missing-hash") ? policy.policyId()
              : legacy.equals("configured-policy") ? TikaExtractionPolicy.defaults().policyId() : "tika-default-v7");
      runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(fields));
      refresh(runtime);
      io.justsearch.indexerworker.rag.ChunkDocumentWriter.regenerateChunksFromExistingParent(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), id, historical, false);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified(id, Files.getLastModifiedTime(file).toMillis(), Files.size(file)),
          "the fixture must have complete historical chunks and unchanged source metadata");
      var repaired = extractor.extractAll(List.of(claim));
      assertEquals(1, repaired.size(), "ordinary ingestion must repair unknown or outdated extraction evidence");
      writer.write(repaired.getFirst(), null);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().getDocumentContent(id).contains("UniqueSecret"));
      assertTrue(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_PREVIEW).contains("UniqueSecret"));
      var children = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
      assertFalse(children.isEmpty(), "corrected content must have replacement chunks");
      for (String child : children) {
        assertTrue(runtime.documentFieldOps().getDocumentContent(child).contains("UniqueSecret"));
        assertFalse(runtime.documentFieldOps().getDocumentContent(child).contains("Historical"));
      }
      assertTrue(extractor.extractAll(List.of(claim)).isEmpty(), "current source and policy skip repeated extraction");
    }
    try (var runtime = open()) {
      assertTrue(runtime.documentFieldOps().getDocumentContent(id).contains("UniqueSecret"));
      assertTrue(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_PREVIEW).contains("UniqueSecret"));
      assertEquals(policy.policyId(), runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
      assertTrue(runtime.documentFieldOps().isUnmodified(id, Files.getLastModifiedTime(file).toMillis(),
          Files.size(file), SourceContentHash.sha256(file), policy.policyId()), "reopened extraction evidence remains current");
      var parser = mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
      when(parser.extractionPolicy()).thenReturn(policy);
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
      var journal = mock(IngestionOutcomeJournal.class);
      var extractor = new JobBatchExtractor(new WorkerIngestionAuthority(), journal, mock(JobQueue.class), parser,
          runtime.documentFieldOps(), runtime.indexCountOps(), new BatchStats(), mock(StaleSnapshotResolver.class),
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false,
          delta -> {}, new FileMutationFence());
      var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, file);
      assertTrue(extractor.extractAll(List.of(claim)).isEmpty());
      verify(journal).recordOutcomeSafely(eq(file), eq("UNCHANGED"), any());
      verify(parser, never()).extractArtifact(any());
    }
  }
}
