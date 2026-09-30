/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ConsumerView;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.PromptCatalog;
import io.justsearch.agent.api.registry.Altitude;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Role;
import io.justsearch.agent.api.registry.SurfaceAltitude;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.app.services.registry.SurfaceConsumerIndex;
import io.justsearch.app.services.registry.emitter.UIDiagnosticChannelEmitter;
import io.justsearch.app.services.registry.emitter.UIOperationEmitter;
import io.justsearch.app.services.registry.emitter.UIResourceEmitter;
import io.justsearch.app.services.registry.emitter.UIWorkflowEmitter;
import io.justsearch.telemetry.Telemetry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP handler for the registry primitive catalogs.
 *
 * <p>Per tempdoc 429 §E.8.a: serves the substrate's per-primitive catalog endpoints at
 * {@code /api/registry/operations|resources|prompts}. Lives in {@code modules/ui/.../api/}
 * (Javalin layer) alongside {@link MessageCatalogController} — the existing codebase
 * convention is HTTP controllers in {@code modules/ui}, regardless of where the catalog
 * data is sourced.
 *
 * <p>Response envelope mirrors 1.1.d's {@link MessageCatalogController} pattern:
 *
 * <pre>{@code
 * {
 *   "$schema": "https://ssot.justsearch/v1/schemas/operation-catalog.json",
 *   "schemaVersion": "1.0",
 *   "catalogVersion": 142,
 *   "namespace": "registry-operation",
 *   "primitive": "Operation",
 *   "entries": [...]
 * }
 * }</pre>
 *
 * <p>{@code catalogVersion} comes from the shared {@link CapabilitiesChangeRegistry};
 * client-side gap detection works as documented in §C.E (FE compares last-seen vs
 * snapshot version on reconnect; mismatched → re-fetch).
 */
public final class RegistryController {

  private static final Logger log = LoggerFactory.getLogger(RegistryController.class);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private static final String SCHEMA_VERSION = "1.0";

  private final List<OperationCatalog> operationCatalogs;
  private final List<ResourceCatalog> resourceCatalogs;
  private final List<DiagnosticChannelCatalog> diagnosticChannelCatalogs;
  private final List<SurfaceCatalog> surfaceCatalogs;
  private final List<ConversationShapeCatalog> conversationShapeCatalogs;
  // Tempdoc 565 §26.C — the workflow catalogs projected to the picker wire (/api/registry/workflows).
  private final List<io.justsearch.agent.api.registry.WorkflowCatalog> workflowCatalogs;
  private final PromptCatalog prompts;
  private final CapabilitiesChangeRegistry changes;
  private final UIOperationEmitter operationEmitter;
  private final SurfaceConsumerIndex consumerIndex;

  /** Convenience constructor for the single-catalog case (existing wiring). */
  public RegistryController(
      OperationCatalog operations,
      ResourceCatalog resources,
      PromptCatalog prompts,
      CapabilitiesChangeRegistry changes,
      Telemetry telemetry) {
    this(
        List.of(operations),
        List.of(resources),
        List.of(),
        List.of(),
        List.of(),
        prompts,
        changes,
        telemetry);
  }

  /**
   * Pre-Phase-E constructor preserved for back-compat with callers that haven't yet
   * passed a {@code ConversationShapeCatalog} list. Defaults to an empty list — the
   * {@code /api/registry/shapes} endpoint returns an empty envelope until catalogs
   * are injected via the new constructor below.
   */
  public RegistryController(
      List<OperationCatalog> operationCatalogs,
      List<ResourceCatalog> resourceCatalogs,
      List<DiagnosticChannelCatalog> diagnosticChannelCatalogs,
      List<SurfaceCatalog> surfaceCatalogs,
      PromptCatalog prompts,
      CapabilitiesChangeRegistry changes,
      Telemetry telemetry) {
    this(
        operationCatalogs,
        resourceCatalogs,
        diagnosticChannelCatalogs,
        surfaceCatalogs,
        List.of(),
        prompts,
        changes,
        telemetry);
  }

  /**
   * Multi-catalog constructor (per tempdoc 429 §F.11 closure). Aggregates definitions
   * across all catalogs when emitting; supports the dual-catalog setup
   * ({@link io.justsearch.app.services.registry.operations.CoreOperationCatalog} +
   * {@link io.justsearch.agent.tools.AgentToolsOperationCatalog}),
   * and likewise the dual-Resource setup
   * (HealthResourceCatalog + RuntimeContextResourceCatalog from slice 440).
   *
   * <p>Slice 448 phase 2: extended with a {@code DiagnosticChannelCatalog} list to serve
   * the fourth registry primitive at {@code /api/registry/diagnostic-channels}.
   *
   * <p>Slice 449 phase 2: extended with a {@link SurfaceCatalog} list to serve the
   * Surface Manifest catalog at {@code /api/registry/surfaces}. Surface is a Manifest
   * (not a primitive) per slice 449 §0 D1, but the same {@code RegistryController}
   * envelope shape applies — the catalog is JSON-discoverable through the same
   * mechanism.
   */
  // telemetry is retained for the constructor-overload/wiring API; its backing field was
  // removed (write-only) and the other overloads delegate through this one.
  public RegistryController(
      List<OperationCatalog> operationCatalogs,
      List<ResourceCatalog> resourceCatalogs,
      List<DiagnosticChannelCatalog> diagnosticChannelCatalogs,
      List<SurfaceCatalog> surfaceCatalogs,
      List<ConversationShapeCatalog> conversationShapeCatalogs,
      PromptCatalog prompts,
      CapabilitiesChangeRegistry changes,
      Telemetry telemetry) {
    // Back-compat: workflow catalogs default empty (the /api/registry/workflows envelope is empty
    // until the 9-arg constructor injects them — tempdoc 565 §26.C).
    this(
        operationCatalogs,
        resourceCatalogs,
        diagnosticChannelCatalogs,
        surfaceCatalogs,
        conversationShapeCatalogs,
        List.of(),
        prompts,
        changes,
        telemetry);
  }

  /**
   * Tempdoc 565 §26.C — the full constructor adding the {@code WorkflowCatalog} list projected to the
   * picker wire ({@code /api/registry/workflows}). The launcher's hardcoded {@code WORKFLOW_ID} const
   * (§25.2) is retired for a projection of this catalog.
   */
  @SuppressWarnings("PMD.UnusedFormalParameter")
  public RegistryController(
      List<OperationCatalog> operationCatalogs,
      List<ResourceCatalog> resourceCatalogs,
      List<DiagnosticChannelCatalog> diagnosticChannelCatalogs,
      List<SurfaceCatalog> surfaceCatalogs,
      List<ConversationShapeCatalog> conversationShapeCatalogs,
      List<io.justsearch.agent.api.registry.WorkflowCatalog> workflowCatalogs,
      PromptCatalog prompts,
      CapabilitiesChangeRegistry changes,
      Telemetry telemetry) {
    this.operationCatalogs =
        List.copyOf(Objects.requireNonNull(operationCatalogs, "operationCatalogs"));
    this.resourceCatalogs =
        List.copyOf(Objects.requireNonNull(resourceCatalogs, "resourceCatalogs"));
    this.diagnosticChannelCatalogs =
        List.copyOf(
            Objects.requireNonNull(diagnosticChannelCatalogs, "diagnosticChannelCatalogs"));
    this.surfaceCatalogs =
        List.copyOf(Objects.requireNonNull(surfaceCatalogs, "surfaceCatalogs"));
    this.conversationShapeCatalogs =
        List.copyOf(
            Objects.requireNonNull(conversationShapeCatalogs, "conversationShapeCatalogs"));
    this.workflowCatalogs =
        List.copyOf(Objects.requireNonNull(workflowCatalogs, "workflowCatalogs"));
    this.prompts = Objects.requireNonNull(prompts, "prompts");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.operationEmitter = new UIOperationEmitter();
    // Slice 481 §7 step 3 follow-up (autonomous session, 2026-05-08): SurfaceConsumerIndex
    // built once at construction. The Surface catalog is final at construct time per
    // slice 449's catalog-discovery model, so a one-shot index is correct. Plugin-
    // contributed Surfaces (V1.5+) will require rebuilding the index on plugin install
    // — tracked as part of the Pass-3 enforcement design slice.
    this.consumerIndex = new SurfaceConsumerIndex(this.surfaceCatalogs);
  }

  public void handleOperations(Context ctx) {
    // Aggregate definitions across all catalogs, then emit via UIOperationEmitter.
    List<io.justsearch.agent.api.registry.Operation> allOps =
        operationCatalogs.stream().flatMap(c -> c.definitions().stream()).toList();
    OperationCatalog aggregate = OperationCatalog.of("core", allOps);
    List<?> entries = operationEmitter.emit(aggregate, List.of());
    // Slice 481 §7 step 3 follow-up: merge auto-derived Realized consumer hooks from
    // SurfaceConsumerIndex with each operation's declared consumers. Each Surface that
    // consumes the Operation per its `consumes.operations` field becomes a Realized
    // hook with consumerId = surfaceId, audience = surface.audience.
    List<Map<String, Object>> withDerivedConsumers = new ArrayList<>();
    for (Object e : entries) {
      @SuppressWarnings("unchecked")
      Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) e);
      String idValue = (String) entry.get("id");
      OperationRef ref = new OperationRef(idValue);
      @SuppressWarnings("unchecked")
      List<ConsumerHook> declared =
          MAPPER.convertValue(
              entry.getOrDefault("consumers", List.of()),
              MAPPER.getTypeFactory().constructCollectionType(List.class, ConsumerHook.class));
      List<ConsumerHook> merged =
          SurfaceConsumerIndex.merge(declared, consumerIndex.consumersOf(ref));
      entry.put("consumers", merged.stream().map(ConsumerView::from).toList());
      withDerivedConsumers.add(entry);
    }
    writeEnvelope(ctx, "registry-operation", "Operation", withDerivedConsumers);
  }

  public void handleResources(Context ctx) {
    List<Resource> all =
        resourceCatalogs.stream().flatMap(c -> c.definitions().stream()).toList();
    // Tempdoc 560 §4c: project each Resource onto the typed UIResourceView wire — ONE authority for
    // the Resource shape, so its record→JSON-Schema→{TS,Zod} projection is faithful AND precise.
    // Slice 481 §7 step 3 follow-up: Surface-derived consumer hooks merged in, flattened to the
    // discriminator-free ConsumerView (the wire never carried ConsumerHook's `kind` — the prior
    // Map round-trip erased it and the schema stripped it post-hoc; this makes the flat shape
    // authoritative).
    List<Map<String, Object>> projected = new ArrayList<>();
    for (Resource r : all) {
      Map<String, Object> m = UIResourceEmitter.toEntry(r);
      List<ConsumerHook> merged =
          SurfaceConsumerIndex.merge(r.consumers(), consumerIndex.consumersOf(r.id()));
      m.put("consumers", merged.stream().map(ConsumerView::from).toList());
      projected.add(m);
    }
    writeEnvelope(ctx, "registry-resource", "Resource", projected);
  }

  public void handlePrompts(Context ctx) {
    // Tempdoc 560 §28 Phase 2 — serves the composed Prompt catalog (core + plugin-contributed). Plugin
    // Prompts reach this catalog via the LocalApiServer merge of the OperationSubstrate's core
    // {@code prompts} and composed {@code pluginPrompts} catalogs (the example plugin is the first
    // producer; dev-gated). Promised-hook merging would still land here once named consumers exist.
    writeEnvelope(ctx, "registry-prompt", "Prompt", prompts.definitions());
  }

  /**
   * Slice 448 phase 2: serves the DiagnosticChannel catalog envelope at
   * {@code /api/registry/diagnostic-channels}. Aggregates definitions across all
   * registered DiagnosticChannel catalogs (V1 ships {@code EngineLogDiagnosticChannelCatalog}
   * only; plugin-supplied catalogs land later).
   */
  /**
   * Slice 449 phase 2: serves the Surface Manifest catalog envelope at
   * {@code /api/registry/surfaces}. Aggregates Surface entries across all
   * registered SurfaceCatalogs (V1 ships one entry — {@code core.library-surface} —
   * in phase 4; plugin-contributed Surfaces land via the V1.5 plugin-maturity
   * substrate per slice 449 §6.1).
   */
  public void handleSurfaces(Context ctx) {
    List<io.justsearch.agent.api.registry.Surface> all =
        surfaceCatalogs.stream().flatMap(c -> c.definitions().stream()).toList();
    // Tempdoc 571 §4c: altitude is DERIVED, not served from the catalog's declared field. Build the
    // role index over every registered Resource, then stamp each surface's wire with the altitude
    // derived from its consumed authority (channel / Resource role / placement). The catalog's
    // declared altitude field is ignored here — derivation is the single authority for the wire.
    Map<ResourceRef, Role> resourceRoles = new LinkedHashMap<>();
    for (ResourceCatalog c : resourceCatalogs) {
      for (Resource r : c.definitions()) {
        resourceRoles.putIfAbsent(r.id(), r.role());
      }
    }
    // Slice 489 round-7 §F6 (2026-05-12) — emit-time defense-in-depth.
    // SurfaceStateSchemaLoader already validates JSON well-formedness at boot
    // (slice 489 §F7), so production-shipped catalogs never reach this gate
    // with malformed schemas. The check guards against:
    //   - hot-reload classpath corruption during dev
    //   - plugin-contributed surfaces (V1.5+) that bypass the loader path
    //   - test fixtures that construct Surface records directly with bad schemas
    // Degrade gracefully (emit the surface without stateSchema; log WARN)
    // rather than fail the whole envelope.
    List<Object> projected = new ArrayList<>(all.size());
    for (io.justsearch.agent.api.registry.Surface s : all) {
      Altitude derived = SurfaceAltitude.derive(s, resourceRoles).altitude();
      projected.add(projectSurfaceForWire(s.withAltitude(derived)));
    }
    writeEnvelope(ctx, "registry-surface", "Surface", projected);
  }

  /**
   * Project a Surface to its wire shape. If {@code stateSchema.schema} source is not
   * well-formed JSON, the {@code stateSchema} field is stripped from the wire output
   * (with a WARN) so the surface still ships as non-URL-addressable instead of
   * failing the entire envelope.
   */
  private Object projectSurfaceForWire(io.justsearch.agent.api.registry.Surface s) {
    if (s.stateSchema().isEmpty()) {
      // No stateSchema declared — pass the Surface through unchanged. Jackson
      // serializes the Optional.empty as {"stateSchema": null}; clients already
      // treat absent/null stateSchema as "non-URL-addressable" per slice 489 §17.2.
      return s;
    }
    String schemaSource = s.stateSchema().get().schema();
    try {
      MAPPER.readTree(schemaSource);
      return s; // well-formed — pass through unchanged
    } catch (Exception e) {
      // Malformed schema source. Re-project the surface to a Map and strip the
      // stateSchema field. The Surface record itself is immutable; we project to
      // a Map via Jackson, then null out stateSchema.
      log.warn(
          "Surface {} has malformed stateSchema source ({}); emitting without stateSchema",
          s.id().value(),
          e.getMessage());
      @SuppressWarnings("unchecked")
      Map<String, Object> projected =
          new LinkedHashMap<>(MAPPER.convertValue(s, Map.class));
      projected.put("stateSchema", null);
      return projected;
    }
  }

  /**
   * Slice 491 §9.D Phase E (C0): serves the ConversationShape catalog envelope at
   * {@code /api/registry/shapes}. Aggregates definitions across all registered
   * ConversationShapeCatalogs (V1 ships {@link
   * io.justsearch.app.services.conversation.CoreConversationShapeCatalog} only;
   * plugin-supplied catalogs land via the V1.5 plugin-shape-contribution path
   * shipped in C5). Mirrors {@link #handleSurfaces} structurally — same envelope
   * shape, no consumer-hook enrichment (shapes are not consumed by Surface-like
   * cross-references today).
   */
  public void handleShapes(Context ctx) {
    List<io.justsearch.agent.api.registry.ConversationShape> all =
        conversationShapeCatalogs.stream()
            .flatMap(c -> c.definitions().stream())
            .toList();
    writeEnvelope(ctx, "registry-shape", "ConversationShape", all);
  }

  /**
   * Tempdoc 565 §26.C — serve the workflow catalog to the picker. The lean per-entry shape (id +
   * i18n label/description + node list) is the ONE picker-wire authority ({@link UIWorkflowEmitter}),
   * replacing the FE's hardcoded {@code WORKFLOW_ID} const (§25.2) with a projection of the catalog.
   */
  public void handleWorkflows(Context ctx) {
    List<Map<String, Object>> all =
        workflowCatalogs.stream()
            .flatMap(c -> UIWorkflowEmitter.project(c).stream())
            .toList();
    writeEnvelope(ctx, "registry-workflow", "Workflow", all);
  }

  public void handleDiagnosticChannels(Context ctx) {
    List<DiagnosticChannel> all =
        diagnosticChannelCatalogs.stream().flatMap(c -> c.definitions().stream()).toList();
    // Tempdoc 560 §4c (DiagnosticChannel slice): project each channel onto the typed
    // UIDiagnosticChannelView wire — ONE authority, so its record→JSON-Schema→{TS,Zod} projection is
    // faithful AND precise (retiring the hand-mirrored types/diagnostic.ts). DiagnosticChannel has no
    // `consumers` field on the record (its consumerPermission serves the audience-floor role), but the
    // wire carries derived consumers: the Surface-derived hooks are merged and flattened to
    // ConsumerView (the LAST kind-ful consumers wire — now consistent with Resource/Operation). The
    // consumers key is always emitted (even if empty), so clients can't confuse "no consumers" with
    // "field omitted" (the Pass-9 wire-shape-stability invariant).
    List<Map<String, Object>> projected = new ArrayList<>();
    for (DiagnosticChannel dc : all) {
      Map<String, Object> m = UIDiagnosticChannelEmitter.toEntry(dc);
      List<ConsumerHook> merged =
          SurfaceConsumerIndex.merge(List.of(), consumerIndex.consumersOf(dc.id()));
      m.put("consumers", merged.stream().map(ConsumerView::from).toList());
      projected.add(m);
    }
    writeEnvelope(ctx, "registry-diagnostic", "DiagnosticChannel", projected);
  }

  private void writeEnvelope(Context ctx, String namespace, String primitive, List<?> entries) {
    try {
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("$schema", "https://ssot.justsearch/v1/schemas/" + namespace + "-catalog.json");
      envelope.put("schemaVersion", SCHEMA_VERSION);
      envelope.put("catalogVersion", changes.currentSeq());
      envelope.put("namespace", namespace);
      envelope.put("primitive", primitive);
      envelope.put("entries", entries);
      byte[] body = MAPPER.writeValueAsBytes(envelope);
      ctx.contentType("application/json").result(body);
    } catch (Exception e) {
      log.error("Failed to serialize {} catalog envelope", primitive, e);
      throw new IllegalStateException("Registry catalog serialization failed", e);
    }
  }
}
