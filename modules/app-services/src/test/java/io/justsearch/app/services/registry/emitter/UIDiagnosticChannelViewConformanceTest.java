package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.ConsumerPermission;
import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.DeliveryMode;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.LoggerNamespaceSelector;
import io.justsearch.agent.api.registry.PluginIdentity;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.ProducerKind;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.SubCategory;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 560 §4c (DiagnosticChannel slice) — wire-conformance pin for the {@code
 * /api/registry/diagnostic-channels} entry shape.
 *
 * <p>The DiagnosticChannel wire moved from raw-record serialization ({@code convertValue(dc, Map)})
 * to the typed {@link io.justsearch.agent.api.registry.UIDiagnosticChannelView}. This test proves the
 * view reproduces the historical raw-record wire component-for-component: for representative channels
 * (empty/non-empty {@code dataClasses}; empty/non-empty selector maps; 3- vs 4-field provenance), the
 * view-projected entry equals the raw-record entry once the {@code consumers} field is synthesized as
 * an empty array (the domain record has none; the controller derives it). The {@code type} field is
 * present on both sides — via {@code @JsonTypeInfo} on the raw record, via the view's field. Any
 * mis-wired field surfaces as inequality.
 */
@DisplayName("UIDiagnosticChannelView wire conformance")
final class UIDiagnosticChannelViewConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("the typed view reproduces the raw-record DiagnosticChannel wire for every field shape")
  void viewReproducesRawRecordWire() {
    for (DiagnosticChannel dc : List.of(minimalChannel(), richChannel())) {
      // Old wire: raw-record serialization (with the @JsonTypeInfo `type`), PLUS the consumers field
      // the controller adds post-hoc. The domain record has no consumers, so declared-only = [].
      Map<String, Object> oldEntry = new LinkedHashMap<>(MAPPER.convertValue(dc, Map.class));
      oldEntry.put("consumers", List.of());

      Map<String, Object> newEntry = UIDiagnosticChannelEmitter.toEntry(dc);

      JsonNode oldTree = MAPPER.readTree(MAPPER.writeValueAsString(oldEntry));
      JsonNode newTree = MAPPER.readTree(MAPPER.writeValueAsString(newEntry));
      assertEquals(
          oldTree,
          newTree,
          "UIDiagnosticChannelView wire diverged from the raw-record wire for " + dc.id().value());
    }
  }

  /** Minimal: empty dataClasses, empty selector maps, 3-arg (null-identity) provenance. */
  private static DiagnosticChannel minimalChannel() {
    return new DiagnosticChannel(
        new DiagnosticChannelRef("core.demo-min-log"),
        Presentation.of(new I18nKey("diag.min.label"), new I18nKey("diag.min.description")),
        Set.of(),
        ProducerKind.IN_PROCESS_LOGBACK,
        DeliveryMode.SSE_STREAM,
        LoggerNamespaceSelector.of(Map.of()),
        "/api/demo-min/stream",
        ConsumerPermission.CORE,
        new Provenance(TrustTier.CORE, "core", "1.0.0"));
  }

  /** Rich: non-empty dataClasses Set, prefix + override selector maps, 4-field signed provenance. */
  private static DiagnosticChannel richChannel() {
    return new DiagnosticChannel(
        new DiagnosticChannelRef("core.demo-rich-log"),
        new Presentation(
            new I18nKey("diag.rich.label"),
            new I18nKey("diag.rich.description"),
            Optional.of("trace"),
            Optional.of("diagnostic")),
        Set.of(DataClass.USER_PATHS, DataClass.CONFIG_VALUES),
        ProducerKind.EXTERNAL_OBSERVER,
        DeliveryMode.SSE_STREAM,
        new LoggerNamespaceSelector(
            Map.of("io.justsearch.", SubCategory.CORE_DIAGNOSTIC, "org.apache.", SubCategory.LIBRARY_TRACE),
            Map.of("io.justsearch.special.Quiet", SubCategory.BOOT_TRACE),
            SubCategory.LIBRARY_TRACE),
        "/api/demo-rich/stream",
        ConsumerPermission.OPERATOR_OVERRIDE,
        new Provenance(
            TrustTier.TRUSTED_PLUGIN, "vendor.acme", "1.2.3", new PluginIdentity(true, "sig-abc")));
  }
}
