/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.*;
import org.junit.jupiter.api.Test;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.StructuredContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractorProvider;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import java.nio.charset.Charset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;

/** Exercises actual admission, unchanged/force decisions, text extraction, producer and index. */
class IndexingWindows1252ExtractionTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;
  private static final String TEXT = "European writing costs €: ‚ „ “ ” ‘ ’ – — … ™ œ Š Ž café déjà vu. ";
  private static final String TITLE = "European € „title“ ™";

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @ParameterizedTest(name = "EN1 indexed {0} large={1} structured={2}")
  @CsvSource({"txt,false,false", "md,false,true", "csv,false,true", "json,false,false", "java,false,true",
      "txt,true,true", "md,true,false", "csv,true,false", "json,true,true", "java,true,false"})
  void en1IndexedContentAndFrontmatterSurviveReopen(String extension, boolean large, boolean structured) throws Exception {
    String body = TEXT.repeat(large ? 17_000 : 3).stripTrailing() + "\nClosing € „line“ with accents café.";
    String source = switch (extension) {
      case "md" -> "---\ntitle: " + TITLE + "\nauthor: André\n---\n" + body;
      case "json" -> "{\n\"text\":\"" + body.replace("\n", "\\n") + "\"\n}";
      case "java" -> "// " + body;
      default -> body;
    };
    Path file = Files.write(tempDir.resolve("western." + extension), source.getBytes(Charset.forName("windows-1252")));
    String id = PathNormalizer.normalizeKey(file);
    Set<String> forced = new HashSet<>();
    ContentExtractorProvider parser = structured ? new StructuredContentExtractor() : new ContentExtractor();
    String expected = source;
    if ("java".equals(extension)) {
      Path utf8 = Files.writeString(Files.createDirectories(tempDir.resolve("utf8")).resolve(file.getFileName()), source);
      expected = parser.extract(utf8).content().strip();
    }
    try (var runtime = open();
        var timebox = new TimeboxedContentExtractor(TestWorkerExecutorRegistrations.timebox(), parser)) {
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
      assertTrue(expected.equals(runtime.documentFieldOps().getDocumentContent(id).strip()), "stored content must match every source character");
      assertEquals(TikaExtractionPolicy.defaults().policyId(),
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_POLICY_ID));
    }
    try (var runtime = open()) {
      assertTrue(expected.equals(runtime.documentFieldOps().getDocumentContent(id).strip()), "stored content must match every source character");
      if ("md".equals(extension)) {
        assertEquals(TITLE, runtime.documentFieldOps().getDocumentField(id, SchemaFields.TITLE));
        assertEquals("andré", runtime.documentFieldOps().getDocumentField(id, SchemaFields.META_AUTHOR));
      }
    }
  }
  @Test
  void enN3IndexFingerprintStillMatchesTheUnchangedCatalogBaseline() {
    var catalog = new JustSearchConfigurationLoader().loadFieldCatalog();
    var shapes = catalog.fields().stream().map(def -> new FieldShape(def.id(), def.type(), def.stored(),
        def.docValues(), def.multiValued(), def.analyzer(), def.roles(), def.vectorDimension(), null)).toList();
    var input = new Inputs(catalog.version(), shapes, "fixed-analyzers", "float32", new Hnsw(16, 200),
        new Chunking(500, 50, 100, 2000, "v1"), 4096, new Analysis("fixed-lucene", "fixed-icu"),
        ModelFingerprint.notConfigured(), ModelFingerprint.notConfigured(), ModelFingerprint.notConfigured());
    assertEquals("7a30d6336b017b65cc24a10abf7f6f0fd41fed3a9aa2836310733e124fd35651",
        IndexFingerprint.compute(input).orElseThrow());
  }
}
