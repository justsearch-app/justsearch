package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
import io.justsearch.indexerworker.extract.ExtractionSandboxRestartTags;
import io.justsearch.indexerworker.extract.OcrMetricCatalog;
import io.justsearch.indexerworker.extract.PersistentExtractionSandbox;
import io.justsearch.indexerworker.extract.SandboxExtractionException;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The startup-probe branch in {@link DefaultWorkerAppServices#buildContentExtractor} (tempdoc 885
 * item 14).
 *
 * <p>The probe function itself is covered in {@code ExtractionRoutingTest}; this test pins what
 * wiring does with its verdict. A failed probe remains visible, decoder-only families remain
 * usable, and families that require isolation fail with the durable sandbox reason instead of
 * silently crossing into the Engine JVM.
 */
final class DefaultWorkerAppServicesSandboxProbeTest {

  private static final String MODE_PROP = "justsearch.extraction.sandbox.mode";
  private static final String COMMAND_PROP = "justsearch.extraction.sandbox.command";

  @TempDir Path tempDir;

  private TestMetricRegistry registry;
  private ExtractionMetricCatalog catalog;
  private io.justsearch.core.execution.TestEngineExecutors engineExecutors;
  private WorkerExecutorRegistrations executors;
  private ListAppender<ILoggingEvent> logs;
  private ch.qos.logback.classic.Logger wiringLogger;

  @BeforeEach
  void setUp() {
    System.clearProperty(MODE_PROP);
    System.clearProperty(COMMAND_PROP);
    registry = new TestMetricRegistry(ExtractionMetricCatalog.DEFINITIONS);
    catalog = new ExtractionMetricCatalog(registry);
    engineExecutors = new io.justsearch.core.execution.TestEngineExecutors();
    executors = new WorkerExecutorRegistrations(engineExecutors);
    logs = new ListAppender<>();
    logs.start();
    wiringLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultWorkerAppServices.class);
    wiringLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(MODE_PROP);
    System.clearProperty(COMMAND_PROP);
    if (wiringLogger != null) {
      wiringLogger.detachAppender(logs);
    }
    if (executors != null) {
      executors.close();
    }
    if (engineExecutors != null) {
      engineExecutors.close();
    }
    if (registry != null) {
      registry.close();
    }
  }

  private long probeFailures() {
    return registry.counterValue(
        ExtractionMetricCatalog.SANDBOX_RESTART_TOTAL,
        ExtractionSandboxRestartTags.of(PersistentExtractionSandbox.REASON_PROBE_FAILED));
  }

  private boolean warnedAboutTheProbe() {
    return logs.list.stream()
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("failed its startup probe"));
  }

  @Test
  @Timeout(60)
  void aFailingProbeKeepsAllFiveProcessFamiliesConfinedWhileDecodersStillWork() throws Exception {
    // A command that cannot launch at all: ProcessBuilder.start rejects it immediately, so this
    // exercises the branch without waiting out PROBE_TIMEOUT.
    System.setProperty(MODE_PROP, "auto");
    System.setProperty(COMMAND_PROP, "justsearch-no-such-extraction-child-binary");

    List<Path> processFiles =
        List.of(
            copyFixture("/fixtures/pdf/pdf-text-layer.pdf", "routed.pdf"),
            copyFixture("/fixtures/office/office-marker.docx", "routed.docx"),
            writeZip("routed.zip"),
            writePng("routed.png"),
            Files.write(tempDir.resolve("routed.bin"), new byte[] {0, 1, 2, 0, 3, 4}));
    ContentExtractor detector = new ContentExtractor();
    assertEquals(
        List.of("pdf", "office", "archive", "image", "binary"),
        processFiles.stream()
            .map(file -> IndexingDocumentOps.classifyFileKind(file, detector.detectMimeType(file)))
            .toList(),
        "the adverse fixture must exercise every routed family exactly once");

    try (TimeboxedContentExtractor extractor =
        DefaultWorkerAppServices.buildContentExtractor(
            executors,
            null,
            catalog,
            OcrMetricCatalog.noop(),
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop())) {
      assertTrue(warnedAboutTheProbe(), "a failed probe must be visible in the log");
      assertEquals(1L, probeFailures(), "a failed probe must be recorded as probe_failed");

      for (Path processFile : processFiles) {
        SandboxExtractionException failure =
            assertThrows(
                SandboxExtractionException.class,
                () -> extractor.extract(processFile),
                processFile.getFileName() + " must not cross the process boundary");
        assertTrue(
            failure.getMessage().contains(IngestionReasonCodes.SANDBOX_FAILED),
            processFile.getFileName() + " must carry the durable retry reason");
      }

      assertEquals(
          "plain text survives",
          extractor.extract(write("plain.txt", "plain text survives")).content().trim());
      assertEquals(
          "markdown survives",
          extractor.extract(write("notes.md", "markdown survives")).content().trim());
      String csv = extractor.extract(write("rows.csv", "left,right\n1,2\n")).content();
      assertTrue(csv.contains("left") && csv.contains("right") && csv.contains("1"));
    }
  }

  @Test
  @Timeout(30)
  void inProcessModeNeverRunsTheProbe() throws Exception {
    System.setProperty(MODE_PROP, "in_process");

    Path file = tempDir.resolve("plain.txt");
    Files.writeString(file, "no child process here", StandardCharsets.UTF_8);

    try (TimeboxedContentExtractor extractor =
        DefaultWorkerAppServices.buildContentExtractor(
            executors,
            null,
            catalog,
            OcrMetricCatalog.noop(),
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop())) {
      assertEquals("no child process here", extractor.extract(file).content().trim());
      assertEquals(0L, probeFailures(), "in_process spawns nothing, so nothing can fail a probe");
      assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("startup probe")));
    }
  }

  private Path copyFixture(String resource, String name) throws IOException {
    Path target = tempDir.resolve(name);
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Missing fixture: " + resource);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  private Path write(String name, String content) throws IOException {
    return Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
  }

  private Path writeZip(String name) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry("inside.txt"));
      zip.write("archive marker".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return Files.write(tempDir.resolve(name), bytes.toByteArray());
  }

  private Path writePng(String name) throws IOException {
    byte[] onePixelPng =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                    + "/x8AAusB9Wl2nGQAAAAASUVORK5CYII=");
    return Files.write(tempDir.resolve(name), onePixelPng);
  }
}
