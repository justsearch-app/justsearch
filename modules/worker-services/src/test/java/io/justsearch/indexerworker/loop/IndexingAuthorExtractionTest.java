/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.QuerySyntax;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.extract.*;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual policy extraction, AUTO sandbox routing, admission, document production and Lucene. */
@Timeout(90)
class IndexingAuthorExtractionTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @Test void docxAuthorReachesIndex() throws Exception { assertAuthor("docx", "Ada Lovelace"); }
  @Test void odtAuthorReachesIndex() throws Exception { assertAuthor("odt", "Grace Hopper"); }
  @Test void pdfAuthorReachesIndex() throws Exception { assertAuthor("pdf", "Katherine Johnson"); }
  @Test void emlSenderReachesIndex() throws Exception { assertAuthor("eml", "Dorothy Vaughan"); }
  @Test void msgSenderReachesIndex() throws Exception { assertAuthor("msg", "Annie Easley"); }

  @Test void markdownAuthorRemainsFrontmatterFacet() throws Exception { assertAuthor("md", null); }

  private void assertAuthor(String extension, String author) throws Exception {
    Path file = tempDir.resolve("authored." + extension);
    try (var input = getClass().getResourceAsStream("/fixtures/authors/" + file.getFileName())) {
      assertNotNull(input, "owned author fixture");
      Files.copy(input, file);
    }
    String id = PathNormalizer.normalizeKey(file);
    var policy = TikaExtractionPolicy.defaults();
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
            .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
        var timebox = ExtractionSandboxFactory.create(TestWorkerExecutorRegistrations.ocr(),
            TestWorkerExecutorRegistrations.timebox(), TestWorkerExecutorRegistrations.readers(),
            ExtractionSandboxFactory.Mode.AUTO, policy, Duration.ofSeconds(60), null,
            ExtractionSandboxCommand.defaultCommand(policy, "512m"))) {
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
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);
      var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, file);
      var extracted = extractor.extractAll(List.of(claim));
      assertEquals(1, extracted.size(), "normal ingestion must produce the document");
      writer.write(extracted.getFirst(), null);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertTrue(runtime.documentFieldOps().getDocumentContent(id).contains("ordinary invoice"));
      String indexedAuthor = runtime.documentFieldOps().getDocumentField(id, SchemaFields.AUTHOR);
      if (author == null) {
        assertTrue(indexedAuthor == null || indexedAuthor.isBlank());
        assertEquals("mary jackson", runtime.documentFieldOps().getDocumentField(id, SchemaFields.META_AUTHOR));
      } else {
        assertNotNull(indexedAuthor, "missing indexed author for " + extension);
        assertTrue(indexedAuthor.contains(author), "indexed author/sender must preserve the parsed name");
      }
      String name = author == null ? "Mary Jackson" : author;
      var query = runtime.textQueryOps().buildTextQuery("author:\"" + name + "\"", null, QuerySyntax.SEARCH);
      var hits = runtime.readPathOps().search(query, 10, Set.of(SchemaFields.PATH),
          LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, "").hits();
      assertEquals(author == null ? Set.of() : Set.of(id),
          hits.stream().map(hit -> hit.docId()).collect(java.util.stream.Collectors.toSet()));
    }
  }
}
