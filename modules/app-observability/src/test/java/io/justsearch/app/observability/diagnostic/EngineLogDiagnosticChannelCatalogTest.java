package io.justsearch.app.observability.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ConsumerPermission;
import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.DeliveryMode;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.ProducerKind;
import io.justsearch.agent.api.registry.SubCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EngineLogDiagnosticChannelCatalog")
final class EngineLogDiagnosticChannelCatalogTest {

  @Test
  @DisplayName("catalog ships exactly one DiagnosticChannel entry")
  void exactlyOneEntry() {
    DiagnosticChannelCatalog catalog = new EngineLogDiagnosticChannelCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new EngineLogDiagnosticChannelCatalog().namespace());
  }

  @Test
  @DisplayName("entry id is core.engine-log; producer + delivery mode V1 declared")
  void entryShape() {
    DiagnosticChannel entry = new EngineLogDiagnosticChannelCatalog().definitions().get(0);
    assertEquals(new DiagnosticChannelRef("core.engine-log"), entry.id());
    assertEquals(ProducerKind.IN_PROCESS_LOGBACK, entry.producer());
    assertEquals(DeliveryMode.SSE_STREAM, entry.deliveryMode());
    assertEquals("/api/diagnostic-channels/engine-log/stream", entry.endpoint());
  }

  @Test
  @DisplayName("dataClasses default set carries USER_PATHS, CONFIG_VALUES, EXCEPTION_BODIES")
  void dataClassesDeclared() {
    DiagnosticChannel entry = new EngineLogDiagnosticChannelCatalog().definitions().get(0);
    assertTrue(entry.dataClasses().contains(DataClass.USER_PATHS));
    assertTrue(entry.dataClasses().contains(DataClass.CONFIG_VALUES));
    assertTrue(entry.dataClasses().contains(DataClass.EXCEPTION_BODIES));
  }

  @Test
  @DisplayName("selector resolves io.justsearch.* → CORE_DIAGNOSTIC by prefix")
  void selectorPrefixDispatch() {
    DiagnosticChannel entry = new EngineLogDiagnosticChannelCatalog().definitions().get(0);
    assertSame(
        SubCategory.CORE_DIAGNOSTIC,
        entry.selector().resolve("io.justsearch.indexerworker.IndexerWorker"));
    assertSame(
        SubCategory.LIBRARY_TRACE,
        entry.selector().resolve("org.apache.lucene.index.IndexWriter"));
    assertSame(
        SubCategory.LIBRARY_TRACE, entry.selector().resolve("io.netty.channel.ChannelHandler"));
    assertSame(
        SubCategory.BOOT_TRACE,
        entry.selector().resolve("ch.qos.logback.classic.LoggerContext"));
  }

  @Test
  @DisplayName("phase-1 review C1: unmapped logger resolves to LIBRARY_TRACE (off-by-default)")
  void unmappedLoggerDefaultsToLibraryTrace() {
    DiagnosticChannel entry = new EngineLogDiagnosticChannelCatalog().definitions().get(0);
    assertSame(
        SubCategory.LIBRARY_TRACE,
        entry.selector().resolve("com.unknown.future.SomeLogger"),
        "Unmapped loggers must default to LIBRARY_TRACE (off-by-default subscription)"
            + " per phase-1 review C1 — privacy-conservative against unknown-unknown loggers.");
  }

  @Test
  @DisplayName("phase-1 review C2: engine-log declares OPERATOR_OVERRIDE consumer permission")
  void consumerPermissionOperatorOverride() {
    DiagnosticChannel entry = new EngineLogDiagnosticChannelCatalog().definitions().get(0);
    assertSame(
        ConsumerPermission.OPERATOR_OVERRIDE,
        entry.consumerPermission(),
        "core.engine-log emissions are operator-driven; default consumers must opt in");
  }

  @Test
  @DisplayName("findById resolves the engine-log entry")
  void findByIdResolves() {
    DiagnosticChannelCatalog catalog = new EngineLogDiagnosticChannelCatalog();
    assertTrue(
        catalog.findById(new DiagnosticChannelRef("core.engine-log")).isPresent(),
        "Catalog must resolve its own entry by id");
  }

  @Test
  @DisplayName("presentation labels resolve registry-diagnostic.engine-log.{label,description}")
  void presentationKeysDeclared() {
    DiagnosticChannel entry = new EngineLogDiagnosticChannelCatalog().definitions().get(0);
    assertEquals(
        "registry-diagnostic.engine-log.label", entry.presentation().labelKey().value());
    assertEquals(
        "registry-diagnostic.engine-log.description",
        entry.presentation().descriptionKey().value());
  }
}
