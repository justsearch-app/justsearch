package io.justsearch.app.observability.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.slf4j.LoggerFactory;
import io.justsearch.agent.api.registry.SubCategory;
import io.justsearch.app.api.stream.SseEnvelope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

@DisplayName("DiagnosticChannelAppender")
final class DiagnosticChannelAppenderTest {

  private DiagnosticChannelStreamRegistry registry;
  private DiagnosticChannelAppender appender;
  private LoggerContext lc;
  private List<SseEnvelope> captured;

  @BeforeEach
  void setUp() {
    EngineLogDiagnosticChannelCatalog catalog = new EngineLogDiagnosticChannelCatalog();
    registry = new DiagnosticChannelStreamRegistry(catalog);
    captured = new ArrayList<>();
    registry.channel(EngineLogDiagnosticChannelCatalog.ENGINE_LOG_ID).subscribe(captured::add);
    appender = new DiagnosticChannelAppender(registry, catalog);
    // Use the SLF4J-bound LoggerContext so MDC.put() in tests is visible to
    // event.getMDCPropertyMap() (the §B.A.G1 verification path). A fresh
    // `new LoggerContext()` does not share the MDCAdapter with SLF4J's MDC.
    lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    appender.setContext(lc);
    appender.start();
  }

  @AfterEach
  void tearDown() {
    appender.stop();
    MDC.clear();
  }

  private LoggingEvent makeEvent(String loggerName, String message) {
    LoggingEvent e =
        new LoggingEvent(
            "fqcn", lc.getLogger(loggerName), Level.INFO, message, null, new Object[0]);
    e.setTimeStamp(System.currentTimeMillis());
    return e;
  }

  @Test
  @DisplayName("io.justsearch.* loggers resolve to CORE_DIAGNOSTIC")
  void coreDiagnosticBranch() {
    appender.doAppend(makeEvent("io.justsearch.example.X", "hello"));
    assertEquals(1, captured.size());
    DiagnosticEventEnvelope env = (DiagnosticEventEnvelope) captured.get(0).payload();
    assertSame(SubCategory.CORE_DIAGNOSTIC, env.event().subCategory());
    assertEquals("hello", env.event().message());
  }

  @Test
  @DisplayName("org.apache.lucene.* loggers resolve to LIBRARY_TRACE")
  void libraryTraceBranch() {
    appender.doAppend(makeEvent("org.apache.lucene.index.IndexWriter", "merging"));
    assertEquals(1, captured.size());
    DiagnosticEventEnvelope env = (DiagnosticEventEnvelope) captured.get(0).payload();
    assertSame(SubCategory.LIBRARY_TRACE, env.event().subCategory());
  }

  @Test
  @DisplayName("phase-1.5 C1: unmapped logger defaults to LIBRARY_TRACE (privacy-conservative)")
  void unmappedLoggerDefaultsLibraryTrace() {
    appender.doAppend(makeEvent("com.unknown.future.Foo", "x"));
    assertEquals(1, captured.size());
    DiagnosticEventEnvelope env = (DiagnosticEventEnvelope) captured.get(0).payload();
    assertSame(SubCategory.LIBRARY_TRACE, env.event().subCategory());
  }

  @Test
  @DisplayName("§B.A.G2 fulfilled: marked DELIVERY_INTERNAL events are dropped at append()")
  void recursionMarkerDropsEvent() {
    LoggingEvent e = makeEvent("io.justsearch.example.X", "self-emit");
    e.addMarker(DiagnosticChannelInternalMarker.get());
    appender.doAppend(e);
    assertTrue(
        captured.isEmpty(),
        "events marked with " + DiagnosticChannelInternalMarker.NAME + " must NOT publish");
  }

  @Test
  @DisplayName("§B.A.G2 also covers compound markers containing the delivery-internal marker")
  void compoundMarkerAlsoDrops() {
    LoggingEvent e = makeEvent("io.justsearch.example.X", "compound");
    Marker compound = MarkerFactory.getDetachedMarker("compound-test-" + System.nanoTime());
    compound.add(DiagnosticChannelInternalMarker.get());
    e.addMarker(compound);
    appender.doAppend(e);
    assertTrue(captured.isEmpty(), "compound markers containing delivery-internal must drop");
  }

  @Test
  @DisplayName("§B.A.G1 fulfilled: appender captures MDC at log-call time")
  void mdcCaptured() {
    MDC.put("trace_id", "trace-abc");
    MDC.put("request_id", "req-xyz");
    try {
      appender.doAppend(makeEvent("io.justsearch.example.X", "with-mdc"));
    } finally {
      MDC.clear();
    }
    assertEquals(1, captured.size());
    DiagnosticEventEnvelope env = (DiagnosticEventEnvelope) captured.get(0).payload();
    assertEquals("trace-abc", env.event().mdc().get("trace_id"));
    assertEquals("req-xyz", env.event().mdc().get("request_id"));
  }

  @Test
  @DisplayName("event carries channel-default dataClasses set")
  void dataClassesFromChannel() {
    appender.doAppend(makeEvent("io.justsearch.example.X", "x"));
    DiagnosticEventEnvelope env = (DiagnosticEventEnvelope) captured.get(0).payload();
    // EngineLogDiagnosticChannelCatalog declares USER_PATHS / CONFIG_VALUES / EXCEPTION_BODIES
    assertTrue(env.event().dataClasses().contains(io.justsearch.agent.api.registry.DataClass.USER_PATHS));
    assertTrue(env.event().dataClasses().contains(io.justsearch.agent.api.registry.DataClass.CONFIG_VALUES));
    assertTrue(env.event().dataClasses().contains(io.justsearch.agent.api.registry.DataClass.EXCEPTION_BODIES));
  }
}
