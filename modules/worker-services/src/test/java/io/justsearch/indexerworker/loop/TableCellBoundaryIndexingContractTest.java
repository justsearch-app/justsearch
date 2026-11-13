package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real extraction child, worker admission/writer, stored previews and chunks; not Engine boot. */
class TableCellBoundaryIndexingContractTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  private final class Harness implements AutoCloseable {
    final RunningRuntime runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
    final TimeboxedContentExtractor timebox = ExtractionSandboxFactory.create(
        TestWorkerExecutorRegistrations.ocr(), TestWorkerExecutorRegistrations.timebox(),
        TestWorkerExecutorRegistrations.readers(), ExtractionSandboxFactory.Mode.PROCESS,
        TikaExtractionPolicy.defaults(), Duration.ofSeconds(45), null,
        ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), "256m"));
    final JobBatchExtractor extractor;
    final JobBatchWriter writer;

    Harness() {
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
      extractor = new JobBatchExtractor(authority, journal, queue, timebox,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false,
          delta -> {}, fence);
      writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);
    }

    List<ExtractedJob> extract(Path file) {
      return extractor.extractAll(List.of(new JobQueue.IndexJob(file, null, null, null, null, null,
          false, null, file)));
    }

    void ingest(Path file) throws Exception {
      var batch = extract(file);
      assertEquals(1, batch.size());
      assertNotNull(batch.getFirst().artifact(), "a new or modified source must be extracted");
      writer.write(batch.getFirst(), null);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
    }

    @Override public void close() throws Exception {
      try { timebox.close(); } finally { runtime.close(); }
    }
  }

  @Test
  void p4UnchangedPreviouslyIndexedWordSkipsExtraction() throws Exception {
    Path file = writeDocx("repercussive", "All", 300);
    String id = PathNormalizer.normalizeKey(file);
    try (var h = new Harness()) {
      h.ingest(file);
      String stored = h.runtime.documentFieldOps().getDocumentContent(id);
      assertEquals("tika-default-v12", h.runtime.documentFieldOps().getDocumentField(id,
          SchemaFields.EXTRACTION_POLICY_ID));
      var batch = h.extract(file);
      assertEquals(1, batch.size());
      assertNull(batch.getFirst().artifact(), "unchanged source/policy must not re-extract");
      assertEquals(stored, h.runtime.documentFieldOps().getDocumentContent(id));
    }
  }

  @Test
  void p4ChangedWordUpdatesStoredPreviewAndChunksConsistently() throws Exception {
    checkChangedWord(true);
  }

  @Test
  void s1ChangedWordPublishesParagraphBoundariesToContentPreviewAndChunks() throws Exception {
    checkChangedWord(false);
  }

  private void checkChangedWord(boolean sourceHasSeparators) throws Exception {
    Path file = writeDocx("historical", "original", 300);
    String id = PathNormalizer.normalizeKey(file);
    try (var h = new Harness()) {
      h.ingest(file);
      writeDocx(sourceHasSeparators ? "repercussive " : "repercussive",
          sourceHasSeparators ? "All " : "All", 301);
      Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
          Files.getLastModifiedTime(file).toMillis() + 10_000));
      h.ingest(file);
      var children = h.runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
      assertFalse(children.isEmpty(), "changed source must have chunks");
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      checks.add(() -> assertSeparated(h.runtime.documentFieldOps().getDocumentContent(id), "stored parent"));
      checks.add(() -> assertSeparated(h.runtime.documentFieldOps().getDocumentField(id,
          SchemaFields.CONTENT_PREVIEW), "preview"));
      for (String child : children) checks.add(() -> {
        String text = h.runtime.documentFieldOps().getDocumentContent(child);
        assertFalse(text.contains("historical"), "stale chunk " + child);
        assertFalse(text.contains("repercussiveAll"), "joined words in chunk " + child + ": " + text);
      });
      checks.add(() -> assertTrue(children.stream().anyMatch(child -> h.runtime.documentFieldOps()
          .getDocumentContent(child).matches("(?s).*repercussive\\s+All.*")), "no separated words in chunks"));
      assertAll(checks);
    }
  }

  private static void assertSeparated(String text, String where) {
    assertFalse(text.contains("historical"), where + " is stale");
    assertFalse(text.contains("repercussiveAll"), where + " contains joined words");
    assertTrue(text.matches("(?s).*repercussive\\s+All.*"), where + " lost word boundaries");
  }

  private Path writeDocx(String first, String second, int repetitions) throws Exception {
    Path file = temp.resolve("source.docx");
    String cell = ("<w:p><w:r><w:t>" + first + "</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>" + second + "</w:t></w:r></w:p>").repeat(repetitions);
    Map<String, String> entries = Map.of("[Content_Types].xml",
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
        "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body><w:tbl><w:tr><w:tc>" + cell + "</w:tc></w:tr></w:tbl><w:sectPr/></w:body></w:document>");
    try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
      for (var entry : new TreeMap<>(entries).entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return file;
  }
}
