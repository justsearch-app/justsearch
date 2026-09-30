package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
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
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.PromptCatalog;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog;
import io.justsearch.app.observability.diagnostic.EngineLogDiagnosticChannelCatalog;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@link RegistryController} per tempdoc 429 §E.8.a + §F.9 closure.
 *
 * <p>Verifies the JSON envelope shape and the catalogVersion is sourced from
 * the {@link CapabilitiesChangeRegistry}.
 */
@DisplayName("RegistryController")
final class RegistryControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RegistryController controller;
  private CapabilitiesChangeRegistry changeRegistry;

  @BeforeEach
  void setUp() {
    OperationCatalog operations = new CoreOperationCatalog();
    ResourceCatalog resources = ResourceCatalog.of("core", List.of());
    PromptCatalog prompts = PromptCatalog.of("core", List.of());
    changeRegistry = new CapabilitiesChangeRegistry();
    Telemetry telemetry = mock(Telemetry.class);
    controller = new RegistryController(operations, resources, prompts, changeRegistry, telemetry);
  }

  @Test
  @DisplayName("/api/registry/operations returns envelope with 32 seed entries")
  void operationsEnvelope() throws Exception {
    // Slice 445: CoreOperationCatalog now seeds 26 entries — slice 3a-2-c's 23
    // plus core.cancel-indexing-job, core.retry-indexing-job, core.resolve-path-hash
    // (TABULAR Resource item Operations + privacy-axis resolver).
    // Slice 447-followup §X.11.5 Phase 7: 27 with core.rebuild-index added.
    // Slice 484 §3.6 / observations.md core.index-gc closure: 28 with core.index-gc.
    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handleOperations(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals("registry-operation", envelope.get("namespace").asText());
    assertEquals("Operation", envelope.get("primitive").asText());
    assertEquals("1.0", envelope.get("schemaVersion").asText());
    assertEquals(0L, envelope.get("catalogVersion").asLong());
    assertTrue(envelope.get("entries").isArray());
    // Slice 491 §9.D Phase E (C4 / E3): core.navigate-to-surface added; total 29.
    // Tempdoc 560 WS4: the navigate-to-surface DEFINITION moved to AgentToolsOperationCatalog
    // (single canonical declaration), so the core catalog alone now seeds 28 (the production wire
    // still emits navigate-to-surface — it comes from the agent-tools catalog the full boot composes).
    // Tempdoc 626 §Recency: core.reconcile-root added to CoreOperationCatalog; total 29.
    // Tempdoc 737 §12b: core.set-chat-enabled added (intent write superseding
    // switch-inference-mode); total 30.
    // Tempdoc 899 D5: core.copy-diagnostic-summary added; total 31.
    // Tempdoc 931 §E item 10: core.settle-index added; total 32.
    assertEquals(32, envelope.get("entries").size());
  }

  @Test
  @DisplayName("/api/registry/resources returns empty entries (V1 stub)")
  void resourcesEnvelopeEmpty() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handleResources(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals("registry-resource", envelope.get("namespace").asText());
    assertEquals("Resource", envelope.get("primitive").asText());
    assertTrue(envelope.get("entries").isArray());
    assertEquals(0, envelope.get("entries").size());
  }

  @Test
  @DisplayName("/api/registry/prompts returns empty entries (V1 stub)")
  void promptsEnvelopeEmpty() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handlePrompts(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals("registry-prompt", envelope.get("namespace").asText());
    assertEquals("Prompt", envelope.get("primitive").asText());
    assertTrue(envelope.get("entries").isArray());
    assertEquals(0, envelope.get("entries").size());
  }

  @Test
  @DisplayName("/api/registry/diagnostic-channels returns engine-log entry envelope")
  void diagnosticChannelsEnvelope() throws Exception {
    // Slice 448 phase 2: register the engine-log catalog explicitly via the multi-catalog
    // constructor. The setUp() controller uses the convenience constructor which
    // defaults diagnostic channels to empty.
    OperationCatalog operations = new CoreOperationCatalog();
    ResourceCatalog resources = ResourceCatalog.of("core", List.of());
    PromptCatalog prompts = PromptCatalog.of("core", List.of());
    DiagnosticChannelCatalog engineLog = new EngineLogDiagnosticChannelCatalog();
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withDiagnostic =
        new RegistryController(
            List.of(operations),
            List.of(resources),
            List.of(engineLog),
            List.of(),
            prompts,
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);

    withDiagnostic.handleDiagnosticChannels(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals("registry-diagnostic", envelope.get("namespace").asText());
    assertEquals("DiagnosticChannel", envelope.get("primitive").asText());
    assertTrue(envelope.get("entries").isArray());
    assertEquals(1, envelope.get("entries").size());
    JsonNode entry = envelope.get("entries").get(0);
    assertEquals("core.engine-log", entry.get("id").asText());
    assertEquals("IN_PROCESS_LOGBACK", entry.get("producer").asText());
    assertEquals("OPERATOR_OVERRIDE", entry.get("consumerPermission").asText());
  }

  @Test
  @DisplayName("/api/registry/diagnostic-channels includes plugin-composed channels (tempdoc 560 §10.4)")
  void diagnosticChannelsEnvelopeIncludesPluginChannels() throws Exception {
    // Tempdoc 560 §10.4: the controller now receives core engine-log + the composed plugin catalog (the
    // Part B bridge's output). A plugin-contributed vendor.* channel must surface alongside core.engine-log.
    DiagnosticChannel vendorChannel =
        new DiagnosticChannel(
            new DiagnosticChannelRef("vendor.example.demo-log"),
            Presentation.of(
                new I18nKey("registry-diagnostic.vendor-example-demo-log.label"),
                new I18nKey("registry-diagnostic.vendor-example-demo-log.description")),
            java.util.Set.of(DataClass.CONFIG_VALUES),
            ProducerKind.IN_PROCESS_LOGBACK,
            DeliveryMode.SSE_STREAM,
            LoggerNamespaceSelector.of(java.util.Map.of()),
            "/api/diagnostic-channels/vendor-example-demo-log/stream",
            ConsumerPermission.OPERATOR_OVERRIDE,
            new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.example", "1.0"));
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withPlugin =
        new RegistryController(
            List.of(new CoreOperationCatalog()),
            List.of(ResourceCatalog.of("core", List.of())),
            // The Part B shape: core engine-log catalog + the composed plugin-channel catalog.
            List.of(
                new EngineLogDiagnosticChannelCatalog(),
                DiagnosticChannelCatalog.of("composed", List.of(vendorChannel))),
            List.of(),
            PromptCatalog.of("core", List.of()),
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withPlugin.handleDiagnosticChannels(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals(2, envelope.get("entries").size());
    List<String> ids = new java.util.ArrayList<>();
    envelope.get("entries").forEach(e -> ids.add(e.get("id").asText()));
    assertTrue(ids.contains("core.engine-log"));
    assertTrue(ids.contains("vendor.example.demo-log"), "the plugin-composed channel must surface");
  }

  @Test
  @DisplayName("/api/registry/resources includes plugin-composed resources (tempdoc 560 §28 Phase 2)")
  void resourcesEnvelopeIncludesPluginResources() throws Exception {
    io.justsearch.agent.api.registry.Resource vendorResource =
        new io.justsearch.agent.api.registry.Resource(
            new io.justsearch.agent.api.registry.ResourceRef("vendor.example.demo-resource"),
            Presentation.of(
                new I18nKey("registry-resource.vendor-example-demo-resource.label"),
                new I18nKey("registry-resource.vendor-example-demo-resource.description")),
            "vendor.example.demo-resource.schema.json",
            io.justsearch.agent.api.registry.Category.STATE,
            io.justsearch.agent.api.registry.SubscriptionMode.ONE_SHOT,
            "/api/registry/resources",
            "document",
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.example", "1.0"),
            io.justsearch.agent.api.registry.Privacy.noPaths(),
            java.util.Set.of(),
            java.util.Set.of(),
            "id");
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withPlugin =
        new RegistryController(
            List.of(new CoreOperationCatalog()),
            // The Phase-2 wiring shape: core resource catalogs + the composed plugin-resource catalog.
            List.of(
                ResourceCatalog.of("core", List.of()),
                ResourceCatalog.of("composed", List.of(vendorResource))),
            List.of(),
            List.of(),
            PromptCatalog.of("core", List.of()),
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withPlugin.handleResources(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    List<String> ids = new java.util.ArrayList<>();
    envelope.get("entries").forEach(e -> ids.add(e.get("id").asText()));
    assertTrue(
        ids.contains("vendor.example.demo-resource"), "the plugin-composed resource must surface");
  }

  @Test
  @DisplayName("/api/registry/prompts includes plugin-composed prompts (tempdoc 560 §28 Phase 2)")
  void promptsEnvelopeIncludesPluginPrompts() throws Exception {
    io.justsearch.agent.api.registry.Prompt vendorPrompt =
        new io.justsearch.agent.api.registry.Prompt(
            new io.justsearch.agent.api.registry.PromptRef("vendor.example.demo-prompt"),
            Presentation.of(
                new I18nKey("registry-prompt.vendor-example-demo-prompt.label"),
                new I18nKey("registry-prompt.vendor-example-demo-prompt.description")),
            "vendor.example.demo-prompt.template",
            List.of(),
            new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.example", "1.0"),
            io.justsearch.agent.api.registry.Audience.USER,
            List.of(
                new io.justsearch.agent.api.registry.ConsumerHook.Realized(
                    "registry", io.justsearch.agent.api.registry.Audience.OPERATOR)));
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withPlugin =
        new RegistryController(
            List.of(new CoreOperationCatalog()),
            List.of(ResourceCatalog.of("core", List.of())),
            List.of(),
            List.of(),
            // LocalApiServer merges core + plugin Prompts into one catalog before constructing the
            // controller; emulate that merged catalog here.
            PromptCatalog.of("registry-prompts", List.of(vendorPrompt)),
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withPlugin.handlePrompts(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    List<String> ids = new java.util.ArrayList<>();
    envelope.get("entries").forEach(e -> ids.add(e.get("id").asText()));
    assertTrue(
        ids.contains("vendor.example.demo-prompt"), "the plugin-composed prompt must surface");
  }

  @Test
  @DisplayName("/api/registry/diagnostic-channels returns empty when no catalogs registered")
  void diagnosticChannelsEnvelopeEmpty() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handleDiagnosticChannels(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals("registry-diagnostic", envelope.get("namespace").asText());
    assertEquals("DiagnosticChannel", envelope.get("primitive").asText());
    assertEquals(0, envelope.get("entries").size());
  }

  @Test
  @DisplayName("/api/registry/surfaces returns empty when no catalogs registered (V1)")
  void surfacesEnvelopeEmpty() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals("registry-surface", envelope.get("namespace").asText());
    assertEquals("Surface", envelope.get("primitive").asText());
    assertTrue(envelope.get("entries").isArray());
    assertEquals(0, envelope.get("entries").size());
  }

  // ----- Slice 489 round-7 §F6 — emit-time schema source validation -----

  private RegistryController controllerWithSurface(
      io.justsearch.agent.api.registry.Surface entry) {
    io.justsearch.agent.api.registry.SurfaceCatalog cat =
        io.justsearch.agent.api.registry.SurfaceCatalog.of("core", List.of(entry));
    OperationCatalog operations = new CoreOperationCatalog();
    ResourceCatalog resources = ResourceCatalog.of("core", List.of());
    PromptCatalog prompts = PromptCatalog.of("core", List.of());
    Telemetry telemetry = mock(Telemetry.class);
    return new RegistryController(
        List.of(operations),
        List.of(resources),
        List.of(),
        List.of(cat),
        prompts,
        changeRegistry,
        telemetry);
  }

  @Test
  @DisplayName(
      "/api/registry/surfaces emits well-formed stateSchema verbatim (F6 happy path)")
  void surfacesEmitsWellFormedStateSchema() throws Exception {
    io.justsearch.agent.api.registry.Surface entry =
        new io.justsearch.agent.api.registry.Surface(
            new io.justsearch.agent.api.registry.SurfaceRef("core.library-surface"),
            Presentation.of(
                new I18nKey(
                    "registry-surface.library-surface.label"),
                new I18nKey(
                    "registry-surface.library-surface.description")),
            io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Placement.RAIL,
            io.justsearch.agent.api.registry.SurfaceConsumes.empty(),
            "jf-library-surface",
            Provenance.core("1.0"),
            java.util.Optional.of(
                new io.justsearch.agent.api.registry.SurfaceStateSchema(
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
                    List.of(
                        new io.justsearch.agent.api.registry.StateBinding(
                            "/query", "search", "query")))));
    RegistryController withSurface = controllerWithSurface(entry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withSurface.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    JsonNode entryNode = envelope.get("entries").get(0);
    JsonNode stateSchema = entryNode.get("stateSchema");
    assertTrue(stateSchema != null && !stateSchema.isNull(),
        "well-formed stateSchema must appear on the wire");
    assertEquals(1, stateSchema.get("bindings").size());
  }

  @Test
  @DisplayName(
      "/api/registry/surfaces emits the host's `members` on the wire (tempdoc 571 §11 / 578 round-trip)")
  void surfacesEmitMembersOnTheWire() throws Exception {
    // The whole host/member composition feature depends on `members` reaching the FE. This guards the
    // raw-Jackson wire serialization (wire-emitter-elision): a host declared via withMembers(...) must
    // serialize its members array verbatim.
    io.justsearch.agent.api.registry.Surface host =
        new io.justsearch.agent.api.registry.Surface(
                new io.justsearch.agent.api.registry.SurfaceRef("core.library-surface"),
                Presentation.of(
                    new I18nKey("a"),
                    new I18nKey("b")),
                io.justsearch.agent.api.registry.Audience.USER,
                io.justsearch.agent.api.registry.Placement.RAIL,
                io.justsearch.agent.api.registry.SurfaceConsumes.empty(),
                "jf-library-surface",
                Provenance.core("1.0"))
            .withMembers(
                List.of(new io.justsearch.agent.api.registry.SurfaceRef("core.browse-surface")));
    RegistryController withSurface = controllerWithSurface(host);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withSurface.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode members = MAPPER.readTree(body.getValue()).get("entries").get(0).get("members");
    assertTrue(members != null && members.isArray(), "members must serialize as an array on the wire");
    assertEquals(1, members.size());
    assertEquals("core.browse-surface", members.get(0).asText());
  }

  @Test
  @DisplayName("/api/registry/surfaces DERIVES altitude from consumed authority, not the declared field (tempdoc 571 §4c)")
  void surfacesDeriveAltitudeOnTheWire() throws Exception {
    // Tempdoc 571 §4c: altitude is a PROJECTION of the authority a surface consumes, computed in
    // RegistryController and stamped onto the wire — NOT the catalog's declared field. This Activity
    // surface declares NO altitude (7-arg ctor → PRODUCT default) yet consumes the TRUST-role
    // core.action-ledger Resource; the wire must derive TRUST. Declaring PRODUCT while deriving TRUST
    // is the proof that derivation, not the declaration, is the wire authority.
    io.justsearch.agent.api.registry.ResourceRef ledgerRef =
        new io.justsearch.agent.api.registry.ResourceRef("core.action-ledger");
    io.justsearch.agent.api.registry.Surface activity =
        new io.justsearch.agent.api.registry.Surface(
            new io.justsearch.agent.api.registry.SurfaceRef("core.activity-surface"),
            Presentation.of(
                new I18nKey("a"),
                new I18nKey("b")),
            io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Placement.RAIL,
            new io.justsearch.agent.api.registry.SurfaceConsumes(
                java.util.Set.of(ledgerRef),
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of()),
            "jf-activity-surface",
            Provenance.core("1.0"));
    io.justsearch.agent.api.registry.SurfaceCatalog cat =
        io.justsearch.agent.api.registry.SurfaceCatalog.of("core", List.of(activity));
    RegistryController withSurface =
        new RegistryController(
            List.of(new CoreOperationCatalog()),
            // The TRUST-role authority must be in the resource index for the derivation to see it.
            List.of(new ActionLedgerResourceCatalog()),
            List.of(),
            List.of(cat),
            PromptCatalog.of("core", List.of()),
            changeRegistry,
            mock(Telemetry.class));

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withSurface.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode entryNode = MAPPER.readTree(body.getValue()).get("entries").get(0);
    assertEquals(
        "TRUST",
        entryNode.get("altitude").asText(),
        "altitude on the /api/registry/surfaces wire must be DERIVED from the consumed TRUST Resource");
  }

  @Test
  @DisplayName("/api/registry/surfaces derives PRODUCT for an empty-consumes surface (the benign default)")
  void surfacesDeriveProductForEmptyConsumes() throws Exception {
    // Even with a stale TRUST declared on the record, an empty-consumes surface derives PRODUCT — the
    // declared field cannot smuggle a foreclosed altitude onto the wire (571 §8 R3).
    io.justsearch.agent.api.registry.Surface entry =
        new io.justsearch.agent.api.registry.Surface(
            new io.justsearch.agent.api.registry.SurfaceRef("core.activity-surface"),
            Presentation.of(
                new I18nKey("a"),
                new I18nKey("b")),
            io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Placement.RAIL,
            io.justsearch.agent.api.registry.SurfaceConsumes.empty(),
            "jf-activity-surface",
            Provenance.core("1.0"),
            java.util.Optional.empty(),
            io.justsearch.agent.api.registry.RiskTier.LOW,
            io.justsearch.agent.api.registry.Altitude.TRUST);
    RegistryController withSurface = controllerWithSurface(entry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withSurface.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode entryNode = MAPPER.readTree(body.getValue()).get("entries").get(0);
    assertEquals(
        "PRODUCT",
        entryNode.get("altitude").asText(),
        "an empty-consumes surface derives PRODUCT regardless of the declared field");
  }

  @Test
  @DisplayName(
      "/api/registry/surfaces strips stateSchema when schema source is malformed JSON (F6 degrade)")
  void surfacesStripsMalformedStateSchema() throws Exception {
    // Construct a Surface with deliberately malformed schema source. The
    // SurfaceStateSchema compact constructor only enforces non-blank, so
    // "{not json" survives construction — this is exactly the kind of
    // misconfiguration F6 catches at emit time.
    io.justsearch.agent.api.registry.Surface entry =
        new io.justsearch.agent.api.registry.Surface(
            new io.justsearch.agent.api.registry.SurfaceRef("core.library-surface"),
            Presentation.of(
                new I18nKey(
                    "registry-surface.library-surface.label"),
                new I18nKey(
                    "registry-surface.library-surface.description")),
            io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Placement.RAIL,
            io.justsearch.agent.api.registry.SurfaceConsumes.empty(),
            "jf-library-surface",
            Provenance.core("1.0"),
            java.util.Optional.of(
                new io.justsearch.agent.api.registry.SurfaceStateSchema(
                    "{not json",
                    List.of(
                        new io.justsearch.agent.api.registry.StateBinding(
                            "/query", "search", "query")))));
    RegistryController withSurface = controllerWithSurface(entry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withSurface.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    JsonNode entryNode = envelope.get("entries").get(0);
    // The surface still ships — fields other than stateSchema unchanged.
    assertEquals("core.library-surface", entryNode.get("id").asText());
    // stateSchema stripped (emitted as null per the projection).
    JsonNode stateSchema = entryNode.get("stateSchema");
    assertTrue(
        stateSchema == null || stateSchema.isNull(),
        "malformed stateSchema must be stripped to null on the wire");
  }

  @Test
  @DisplayName("/api/registry/surfaces serves a registered Surface entry")
  void surfacesEnvelopeWithEntry() throws Exception {
    // Build a minimal Surface catalog inline; phase 4 will ship CoreSurfaceCatalog.
    io.justsearch.agent.api.registry.Surface entry =
        new io.justsearch.agent.api.registry.Surface(
            new io.justsearch.agent.api.registry.SurfaceRef("core.library-surface"),
            Presentation.of(
                new I18nKey(
                    "registry-surface.library-surface.label"),
                new I18nKey(
                    "registry-surface.library-surface.description")),
            io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Placement.RAIL,
            io.justsearch.agent.api.registry.SurfaceConsumes.empty(),
            "jf-library-surface",
            Provenance.core("1.0"));
    io.justsearch.agent.api.registry.SurfaceCatalog cat =
        io.justsearch.agent.api.registry.SurfaceCatalog.of("core", List.of(entry));

    OperationCatalog operations = new CoreOperationCatalog();
    ResourceCatalog resources = ResourceCatalog.of("core", List.of());
    PromptCatalog prompts = PromptCatalog.of("core", List.of());
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withSurfaces =
        new RegistryController(
            List.of(operations),
            List.of(resources),
            List.of(),
            List.of(cat),
            prompts,
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withSurfaces.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    assertEquals("registry-surface", envelope.get("namespace").asText());
    assertEquals("Surface", envelope.get("primitive").asText());
    assertEquals(1, envelope.get("entries").size());
    JsonNode entryNode = envelope.get("entries").get(0);
    assertEquals("core.library-surface", entryNode.get("id").asText());
    assertEquals("USER", entryNode.get("audience").asText());
    assertEquals("RAIL", entryNode.get("placement").asText());
    assertEquals("jf-library-surface", entryNode.get("mountTag").asText());
  }

  @Test
  @DisplayName("/api/registry/surfaces includes plugin-composed surfaces (tempdoc 560 §10.4)")
  void surfacesEnvelopeIncludesPluginSurfaces() throws Exception {
    // Tempdoc 560 §10.4: RegistryController receives core surfaces + the composed plugin surface
    // catalog (the bridge output). A plugin RAIL surface must surface alongside the core ones.
    io.justsearch.agent.api.registry.Surface vendor =
        new io.justsearch.agent.api.registry.Surface(
            new io.justsearch.agent.api.registry.SurfaceRef("vendor.example.demo-surface"),
            Presentation.of(
                new I18nKey(
                    "registry-surface.vendor-example-demo-surface.label"),
                new I18nKey(
                    "registry-surface.vendor-example-demo-surface.description")),
            io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Placement.RAIL,
            io.justsearch.agent.api.registry.SurfaceConsumes.empty(),
            "jf-log-surface",
            Provenance.core("1.0"));
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withPlugin =
        new RegistryController(
            List.of(new CoreOperationCatalog()),
            List.of(ResourceCatalog.of("core", List.of())),
            List.of(),
            // The Part-B shape: a core surface catalog + the composed plugin surface catalog.
            List.of(
                io.justsearch.agent.api.registry.SurfaceCatalog.of("composed", List.of(vendor))),
            PromptCatalog.of("core", List.of()),
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withPlugin.handleSurfaces(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    List<String> ids = new java.util.ArrayList<>();
    envelope.get("entries").forEach(e -> ids.add(e.get("id").asText()));
    assertTrue(ids.contains("vendor.example.demo-surface"), "the plugin-composed surface must surface");
  }

  @Test
  @DisplayName("/api/registry/shapes includes plugin-composed conversation shapes (tempdoc 560 §10.4)")
  void shapesEnvelopeIncludesPluginShapes() throws Exception {
    io.justsearch.agent.api.registry.ConversationShape vendor =
        new io.justsearch.agent.api.registry.ConversationShape(
            new io.justsearch.agent.api.registry.ConversationShapeRef("vendor.example.demo-shape"),
            Presentation.of(
                new I18nKey(
                    "registry-shape.vendor-example-demo-shape.label"),
                new I18nKey(
                    "registry-shape.vendor-example-demo-shape.description")),
            io.justsearch.agent.api.registry.Audience.USER,
            Provenance.core("1.0"),
            io.justsearch.agent.api.conversation.ExecutionMode.SHAPE_DRIVEN,
            io.justsearch.agent.api.conversation.IterationMode.ONE_SHOT,
            io.justsearch.agent.api.conversation.PersistenceMode.EPHEMERAL,
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of(),
            false);
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withPlugin =
        new RegistryController(
            List.of(new CoreOperationCatalog()),
            List.of(ResourceCatalog.of("core", List.of())),
            List.of(),
            List.of(),
            // The conversationShapeCatalogs arg (full constructor): the composed plugin shape catalog.
            List.of(
                io.justsearch.agent.api.registry.ConversationShapeCatalog.of(
                    "composed", List.of(vendor))),
            PromptCatalog.of("core", List.of()),
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withPlugin.handleShapes(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());
    List<String> ids = new java.util.ArrayList<>();
    envelope.get("entries").forEach(e -> ids.add(e.get("id").asText()));
    assertTrue(ids.contains("vendor.example.demo-shape"), "the plugin-composed shape must surface");
  }

  @Test
  @DisplayName(
      "863 §4.A.1 wire probe: /api/registry/shapes carries EXACTLY the pre-863 keys plus"
          + " recordsToThread")
  void shapesEnvelopeKeySetAfterRecordsToThreadPromotion() throws Exception {
    // The probe tempdoc 863 §4.A.1 asks for, run as a measurement rather than settled by reasoning
    // about Jackson. `writeEnvelope` serialises `ConversationShape` objects straight into the live
    // envelope, and a record COMPONENT is serialised where the bare no-arg accessor `recordsToThread()`
    // (no get/is prefix) was NOT — so promoting it can ADD A KEY TO A LIVE WIRE ENVELOPE. The twelve
    // names below are the pre-863 component list, written out verbatim: if the promotion had NOT
    // changed the wire, this test fails on the extra expectation and says so.
    Telemetry telemetry = mock(Telemetry.class);
    RegistryController withCore =
        new RegistryController(
            List.of(new CoreOperationCatalog()),
            List.of(ResourceCatalog.of("core", List.of())),
            List.of(),
            List.of(),
            List.of(
                io.justsearch.app.services.conversation.CoreConversationShapeCatalog.catalog()),
            PromptCatalog.of("core", List.of()),
            changeRegistry,
            telemetry);

    Context ctx = mock(Context.class);
    when(ctx.contentType("application/json")).thenReturn(ctx);
    withCore.handleShapes(ctx);

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    JsonNode envelope = MAPPER.readTree(body.getValue());

    JsonNode agentRun = null;
    JsonNode workflowRun = null;
    for (JsonNode entry : envelope.get("entries")) {
      if ("core.agent-run".equals(entry.get("id").asText())) {
        agentRun = entry;
      } else if ("core.workflow-run".equals(entry.get("id").asText())) {
        workflowRun = entry;
      }
    }
    assertTrue(agentRun != null && workflowRun != null, "both core shapes must be in the envelope");

    java.util.Set<String> keys = new java.util.TreeSet<>();
    keys.addAll(agentRun.propertyNames());
    assertEquals(
        new java.util.TreeSet<>(
            List.of(
                // The twelve pre-863 record components, in the wire's own names.
                "id",
                "presentation",
                "audience",
                "provenance",
                "executionMode",
                "iterationMode",
                "persistenceMode",
                "promptContributorIds",
                "contextInjectorIds",
                "streamConsumerIds",
                "iterationControllerId",
                "eventSchema",
                // The thirteenth, added by this slice. Its presence here is the probe's RESULT:
                // the promotion IS a wire addition, and the FE mirror
                // (modules/ui-web/src/api/types/conversation-shape.ts) carries it for that reason.
                "recordsToThread")),
        keys,
        "the shapes envelope's per-entry key set");

    assertTrue(agentRun.get("recordsToThread").asBoolean(), "core.agent-run declares true");
    assertTrue(
        !workflowRun.get("recordsToThread").asBoolean(),
        "core.workflow-run stays action-plane-only");
  }

  @Test
  @DisplayName("catalogVersion advances after a broadcast")
  void catalogVersionAdvancesAfterBroadcast() throws Exception {
    Context ctx1 = mock(Context.class);
    when(ctx1.contentType("application/json")).thenReturn(ctx1);
    controller.handleOperations(ctx1);
    ArgumentCaptor<byte[]> body1 = ArgumentCaptor.forClass(byte[].class);
    verify(ctx1).result(body1.capture());
    long before = MAPPER.readTree(body1.getValue()).get("catalogVersion").asLong();

    changeRegistry.broadcast(
        CapabilitiesChangeRegistry.CapabilityChangeEvent.Kind.MODIFIED, "test");

    Context ctx2 = mock(Context.class);
    when(ctx2.contentType("application/json")).thenReturn(ctx2);
    controller.handleOperations(ctx2);
    ArgumentCaptor<byte[]> body2 = ArgumentCaptor.forClass(byte[].class);
    verify(ctx2).result(body2.capture());
    long after = MAPPER.readTree(body2.getValue()).get("catalogVersion").asLong();

    assertTrue(after > before, "catalogVersion must advance after broadcast: " + before + " -> " + after);
  }
}
