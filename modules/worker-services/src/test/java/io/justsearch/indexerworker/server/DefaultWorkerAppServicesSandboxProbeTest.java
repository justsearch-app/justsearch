package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
import io.justsearch.indexerworker.extract.ExtractionSandboxRestartTags;
import io.justsearch.indexerworker.extract.OcrMetricCatalog;
import io.justsearch.indexerworker.extract.PersistentExtractionSandbox;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Six lines decide whether a Worker with a broken child command extracts every document
 * in-process or fails every document — and they were the one part of the probe with no test: the
 * probe function itself is covered in {@code ExtractionRoutingTest}, but nothing asserted that the
 * wiring acts on its verdict.
 */
final class DefaultWorkerAppServicesSandboxProbeTest {

  private static final String MODE_PROP = "justsearch.extraction.sandbox.mode";
  private static final String COMMAND_PROP = "justsearch.extraction.sandbox.command";

  @TempDir Path tempDir;

  private TestMetricRegistry registry;
  private ExtractionMetricCatalog catalog;
  private ListAppender<ILoggingEvent> logs;
  private ch.qos.logback.classic.Logger wiringLogger;

  @BeforeEach
  void setUp() {
    System.clearProperty(MODE_PROP);
    System.clearProperty(COMMAND_PROP);
    registry = new TestMetricRegistry(ExtractionMetricCatalog.DEFINITIONS);
    catalog = new ExtractionMetricCatalog(registry);
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
  void aFailingProbeWarnsRecordsAndFallsBackToInProcessForTheSession() throws Exception {
    // A command that cannot launch at all: ProcessBuilder.start rejects it immediately, so this
    // exercises the branch without waiting out PROBE_TIMEOUT.
    System.setProperty(MODE_PROP, "process");
    System.setProperty(COMMAND_PROP, "justsearch-no-such-extraction-child-binary");

    Path file = tempDir.resolve("probe-fallback.txt");
    Files.writeString(file, "content that must still be extracted", StandardCharsets.UTF_8);

    try (TimeboxedContentExtractor extractor =
        DefaultWorkerAppServices.buildContentExtractor(
            null,
            catalog,
            OcrMetricCatalog.noop(),
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop())) {
      assertTrue(warnedAboutTheProbe(), "a failed probe must be visible in the log");
      assertEquals(1L, probeFailures(), "a failed probe must be recorded as probe_failed");

      // The verdict must have been ACTED on. If the wiring had kept the pool, every extraction
      // would fail on the unlaunchable command; that it succeeds is what proves the fallback.
      assertEquals(
          "content that must still be extracted",
          extractor.extract(file).content().trim(),
          "the session must fall back to in-process extraction, not fail every document");
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
            null,
            catalog,
            OcrMetricCatalog.noop(),
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop())) {
      assertEquals("no child process here", extractor.extract(file).content().trim());
      assertEquals(0L, probeFailures(), "in_process spawns nothing, so nothing can fail a probe");
      assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("startup probe")));
    }
  }
}
