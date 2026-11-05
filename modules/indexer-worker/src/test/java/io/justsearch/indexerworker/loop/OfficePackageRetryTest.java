/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.ingest.*;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.*;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real failed-files action, SQLite admission/ledger, parser, writer and Lucene search. */
final class OfficePackageRetryTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;
  private static final String MARKER = "lastslidecapybara";

  @Test
  void manyPartDeckExtractsAndLastSlideIsSearchable() throws Exception {
    Path file = deck();
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)));
      assertEquals(1, process(queue), () -> "257-1000 entries must extract: " + queue.recentIngestionEvents(10));
      assertSearchable();
    }
  }

  @Test
  void failedFilesRetryIndexesPreviouslyCountRefusedDeck() throws Exception {
    Path file = deck();
    String hash = DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(file));
    Path database = temporary.resolve("queue.db");
    try (var queue = new SqliteJobQueue(database);
        var paths = new SqlitePathResolutionStore(database)) {
      queue.open();
      paths.record(hash, PathNormalizer.normalizeKey(file), 1L);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)));
      // A stopped library's exact terminal outcome; the separate fail-before run proves these
      // unchanged bytes are count-refused by the old parser, rather than changing the fixture.
      queue.markFailed(file, IngestionOutcome.of(IngestionOutcomeClass.BUDGET_EXCEEDED,
          "ARCHIVE_MEMBER_LIMIT", IngestionRetryPolicy.NONE));
      assertEquals("ARCHIVE_MEMBER_LIMIT", queue.recentIngestionEvents(10).getFirst().reasonCode());
      var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      service.setPathResolutionStore(paths);
      var response = service.retryIndexingJob(io.justsearch.ipc.RetryIndexingJobRequest.newBuilder()
          .setPathHash(hash).build(), CallContext.none());
      assertTrue(response.getRetried());
      assertEquals("FAILED", response.getPreviousState());
      assertEquals(1, process(queue), () -> "Failed-files retry must index: " + queue.recentIngestionEvents(10));
      assertEquals(0, queue.queueDepth());
      assertSearchable();
      assertTrue(queue.recentIngestionEvents(10).stream()
          .anyMatch(event -> event.outcomeClass().startsWith("SUCCESS")));
    }
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      assertTrue(queue.pollPending(1).isEmpty(), "Retry is consumed across restart");
      assertSearchable();
    }
  }

  @Test
  void childBudgetReasonsRemainExactInTheSqliteLedger() throws Exception {
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"))) {
      queue.open();
      for (String reason : List.of("ARCHIVE_MEMBER_LIMIT", "ARCHIVE_EXPANSION_LIMIT")) {
        Path file = temporary.resolve(reason + ".zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(file))) {
          int count = reason.equals("ARCHIVE_MEMBER_LIMIT") ? 1001 : 1;
          for (int i = 0; i < count; i++) {
            var entry = new ZipEntry(count > 1 && i == 0 ? "[Content_Types].xml" : "p" + i);
            entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
            output.putNextEntry(entry);
            output.write(count == 1 ? new byte[128 * 1024] : new byte[] {1});
            output.closeEntry();
          }
        }
        queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)));
        assertEquals(0, process(queue));
        var events = queue.recentIngestionEvents(10);
        assertEquals("BUDGET_EXCEEDED", events.getFirst().outcomeClass());
        assertEquals(reason, events.getFirst().reasonCode());
      }
      Path normal = Files.writeString(temporary.resolve("normal.txt"), MARKER);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(normal).withinRoot(temporary)));
      assertEquals(1, process(queue));
      assertSearchable();
    }
  }

  private int process(SqliteJobQueue queue) throws Exception {
    try (var runtime = open(); var owners = new TestEngineExecutors();
        var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
            "office-retry", 1), new PersistentExtractionSandbox(
                PipelineTestFixture.registration(owners, "office-readers", 1),
                ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), "256m"),
                TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), Duration.ofSeconds(30),
                1, 500, null), Duration.ofSeconds(45), null)) {
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "office-uid", 1, 1));
      var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class),
          mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
      var authority = new WorkerIngestionAuthority();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var fence = new FileMutationFence();
      var extractor = new JobBatchExtractor(authority, journal, queue, timebox,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {},
          () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);
      var jobs = extractor.extractAll(queue.pollPending(1));
      for (var job : jobs) writer.write(job, null);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      journal.drainPending();
      return jobs.size();
    }
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temporary.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private void assertSearchable() {
    try (var runtime = open()) {
      assertTrue(runtime.indexCountOps().countQuery(
          new TermQuery(new Term(SchemaFields.CONTENT, MARKER))) > 0,
          "The last slide must be searchable in the real index");
    }
  }

  private Path deck() throws Exception {
    // A small complete PresentationML graph: 130 slides and their relationships = 268 entries.
    var entries = new LinkedHashMap<String, String>();
    String relNs = "http://schemas.openxmlformats.org/package/2006/relationships";
    String office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    StringBuilder types = new StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>");
    var slides = new StringBuilder();
    var rels = new StringBuilder("<Relationships xmlns=\"" + relNs + "\">");
    for (int i = 1; i <= 130; i++) {
      types.append("<Override PartName=\"/ppt/slides/slide").append(i)
          .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
      slides.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i).append("\"/>");
      rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(office)
          .append("slide\" Target=\"slides/slide").append(i).append(".xml\"/>");
      entries.put("ppt/slides/slide" + i + ".xml", "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
          + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><p:cSld><p:spTree>"
          + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"
          + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Text\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
          + "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>"
          + (i == 130 ? MARKER : "Slide " + i) + "</a:t></a:r></a:p></p:txBody></p:sp>"
          + "</p:spTree></p:cSld></p:sld>");
      entries.put("ppt/slides/_rels/slide" + i + ".xml.rels", "<Relationships xmlns=\"" + relNs + "\"><Relationship Id=\"rIdLayout\" Type=\""
          + office + "slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>");
    }
    String presentationNs = "http://schemas.openxmlformats.org/presentationml/2006/main";
    String emptyTree = "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/>"
        + "</p:nvGrpSpPr><p:grpSpPr/></p:spTree>";
    types.append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>");
    entries.put("ppt/slideLayouts/slideLayout1.xml", "<p:sldLayout xmlns:p=\"" + presentationNs
        + "\" type=\"blank\"><p:cSld>" + emptyTree + "</p:cSld></p:sldLayout>");
    entries.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", "<Relationships xmlns=\"" + relNs
        + "\"><Relationship Id=\"rIdMaster\" Type=\"" + office
        + "slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>");
    entries.put("ppt/slideMasters/slideMaster1.xml", "<p:sldMaster xmlns:p=\"" + presentationNs
        + "\" xmlns:r=\"" + office.substring(0, office.length() - 1) + "\"><p:cSld>" + emptyTree
        + "</p:cSld><p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>"
        + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rIdLayout\"/></p:sldLayoutIdLst></p:sldMaster>");
    entries.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", "<Relationships xmlns=\"" + relNs
        + "\"><Relationship Id=\"rIdLayout\" Type=\"" + office
        + "slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>");
    rels.append("<Relationship Id=\"rIdMaster\" Type=\"").append(office)
        .append("slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>");
    entries.put("[Content_Types].xml", types + "</Types>");
    entries.put("_rels/.rels", "<Relationships xmlns=\"" + relNs + "\"><Relationship Id=\"rId1\" Type=\""
        + office + "officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>");
    entries.put("ppt/presentation.xml", "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
        + "xmlns:r=\"" + office.substring(0, office.length() - 1) + "\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rIdMaster\"/></p:sldMasterIdLst><p:sldIdLst>" + slides
        + "</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
    entries.put("ppt/_rels/presentation.xml.rels", rels + "</Relationships>");
    Path file = temporary.resolve("many-parts.pptx");
    try (var output = new ZipOutputStream(Files.newOutputStream(file))) {
      for (var entry : entries.entrySet()) {
        var member = new ZipEntry(entry.getKey());
        member.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
        output.putNextEntry(member);
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    try (var zip = new ZipFile(file.toFile())) {
      assertTrue(zip.size() > 256 && zip.size() <= 1000);
      assertTrue(zip.stream().allMatch(e -> e.getSize() < 100 * e.getCompressedSize()));
    }
    return file;
  }
}
