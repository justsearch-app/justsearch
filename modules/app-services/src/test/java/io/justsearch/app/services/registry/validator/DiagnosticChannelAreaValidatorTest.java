package io.justsearch.app.services.registry.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ConsumerPermission;
import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.DeliveryMode;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.LoggerNamespaceSelector;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.ProducerKind;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.SubCategory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiagnosticChannelAreaValidator")
final class DiagnosticChannelAreaValidatorTest {

  private final DiagnosticChannelAreaValidator validator = new DiagnosticChannelAreaValidator();

  private static DiagnosticChannel channel(
      String id, String endpoint, LoggerNamespaceSelector selector) {
    return new DiagnosticChannel(
        new DiagnosticChannelRef(id),
        Presentation.of(
            new I18nKey("registry-diagnostic.x.label"),
            new I18nKey("registry-diagnostic.x.description")),
        Set.of(DataClass.USER_PATHS),
        ProducerKind.IN_PROCESS_LOGBACK,
        DeliveryMode.SSE_STREAM,
        selector,
        endpoint,
        ConsumerPermission.CORE,
        Provenance.core("1.0"));
  }

  @Test
  @DisplayName("well-formed catalog produces zero findings")
  void cleanCatalog() {
    DiagnosticChannelCatalog catalog =
        DiagnosticChannelCatalog.of(
            "core",
            List.of(
                channel(
                    "core.engine-log",
                    "/api/x",
                    LoggerNamespaceSelector.of(
                        Map.of("io.justsearch.", SubCategory.CORE_DIAGNOSTIC)))));
    assertTrue(validator.validate(catalog).isEmpty(), "no findings expected");
  }

  @Test
  @DisplayName("duplicate channel id produces a finding")
  void duplicateIdFinding() {
    DiagnosticChannel a =
        channel(
            "core.dup",
            "/api/a",
            LoggerNamespaceSelector.of(Map.of("io.x.", SubCategory.CORE_DIAGNOSTIC)));
    DiagnosticChannel b =
        channel(
            "core.dup",
            "/api/b",
            LoggerNamespaceSelector.of(Map.of("io.y.", SubCategory.CORE_DIAGNOSTIC)));
    DiagnosticChannelCatalog catalog = DiagnosticChannelCatalog.of("core", List.of(a, b));
    List<DiagnosticChannelAreaValidator.Finding> findings = validator.validate(catalog);
    assertEquals(1, findings.size());
    assertEquals("core.dup", findings.get(0).channelId());
    assertTrue(findings.get(0).issue().contains("duplicate"));
  }

  @Test
  @DisplayName("empty selector prefix mappings produces a structural finding")
  void emptySelectorFinding() {
    DiagnosticChannelCatalog catalog =
        DiagnosticChannelCatalog.of(
            "core",
            List.of(
                channel(
                    "core.empty-selector",
                    "/api/x",
                    new LoggerNamespaceSelector(
                        Map.of(), Map.of(), SubCategory.CORE_DIAGNOSTIC))));
    List<DiagnosticChannelAreaValidator.Finding> findings = validator.validate(catalog);
    assertEquals(1, findings.size());
    assertTrue(findings.get(0).issue().contains("prefixMappings"));
  }
}
