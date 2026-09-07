package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the privacy fix on the Engine's one logging configuration: the {@code io.justsearch} logger
 * defaults to INFO, not DEBUG, when {@code JUSTSEARCH_LOG_LEVEL} is unset.
 *
 * <p>Several call sites (e.g. {@code ChunkSearchOps}, {@code HybridSearchOps}, {@code TextQueryOps},
 * {@code SearchExecutor}) intentionally log raw user query/chat text at DEBUG/TRACE per the
 * WARN/DEBUG split documented in {@code docs/reference/contributing/logging-conventions.md} ("Query
 * Text Redaction") — that convention only holds if DEBUG is off by default. {@code
 * DiagnosticsServiceImpl} bundles the log directory into the diagnostics export ZIP with path-only
 * redaction ({@code addDirectoryRedacted} → {@code addFileRedactedStreaming} → {@code redactPaths})
 * — no query/content redaction — so plaintext at INFO or above leaves the machine on every
 * diagnostics export.
 *
 * <p>Provenance: this was {@code WorkerLogbackConfigurationTest} in {@code modules/indexer-worker},
 * where it pinned the same property on that module's {@code logback.xml} (tempdoc 734 O-2 — the
 * pre-fix file hardcoded {@code <logger name="io.justsearch" level="DEBUG"/>} and every Worker boot
 * wrote query text to {@code worker.log}). Lane F stage A item A13 deleted that second config —
 * one JVM, one logging configuration — and re-homed the parameterised logger into {@code
 * modules/ui/src/main/resources/logback.xml}. The assertion moved with it, so the property stays
 * pinned against the file that is actually loaded.
 */
final class EngineLogbackConfigurationTest {

  @Test
  void logbackXmlIsLoadedFromClasspath() {
    // If no logback.xml were found, Logback's BasicConfigurator would kick in and the
    // io.justsearch/root level assertions below would observe DEBUG, not INFO — this just
    // confirms a real (non-null) LoggerContext backs those assertions.
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    assertNotNull(ctx, "Logback LoggerContext must be present");
  }

  @Test
  void ioJustsearchDefaultsToInfoWhenEnvVarUnset() {
    // This process was launched by Gradle without JUSTSEARCH_LOG_LEVEL set (the CI/local test
    // task does not set it), so the ${JUSTSEARCH_LOG_LEVEL:-INFO} substitution in
    // modules/ui/src/main/resources/logback.xml must resolve to its default: INFO.
    assertNull(
        System.getenv("JUSTSEARCH_LOG_LEVEL"),
        "This test assumes JUSTSEARCH_LOG_LEVEL is unset in the test environment; if a build "
            + "config sets it, this test's premise no longer holds and it must be adapted.");

    Logger ioJustsearch = (Logger) LoggerFactory.getLogger("io.justsearch");
    assertEquals(
        Level.INFO,
        ioJustsearch.getLevel(),
        "io.justsearch must default to INFO so query/chat text logged at DEBUG/TRACE by "
            + "ChunkSearchOps/HybridSearchOps/TextQueryOps/SearchExecutor stays out of the Engine "
            + "log by default (and therefore out of the diagnostics export ZIP, which bundles the "
            + "log directory with path-only redaction). If this reads DEBUG, the logback.xml "
            + "default regressed back to the pre-fix hardcoded DEBUG level.");
  }

  @Test
  void rootLevelIsInfo() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    assertEquals(Level.INFO, root.getLevel(), "Root logger must remain INFO.");
  }

  @Test
  void luceneAndSqliteNoiseFloorsAreRehomed() {
    // Item A13: both libraries run in THIS JVM since A6, and their WARN floors lived only in the
    // Worker's deleted logback.xml. Without them a dev run at JUSTSEARCH_LOG_LEVEL=DEBUG buries
    // the Engine's own lines under Lucene segment/merge and SQLite statement chatter.
    Logger lucene = (Logger) LoggerFactory.getLogger("org.apache.lucene");
    Logger sqlite = (Logger) LoggerFactory.getLogger("org.sqlite");
    assertEquals(Level.WARN, lucene.getLevel(), "org.apache.lucene must stay at WARN");
    assertEquals(Level.WARN, sqlite.getLevel(), "org.sqlite must stay at WARN");
  }
}
