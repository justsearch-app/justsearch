/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.surface;

import io.justsearch.agent.api.registry.AliasRegistry;
import io.justsearch.agent.api.registry.Altitude;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.CatalogMatcher;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.agent.api.registry.SurfaceConsumes;
import io.justsearch.agent.api.registry.SurfaceRef;
import java.util.List;
import java.util.Set;

/**
 * Slice 449 phase 4 — V1 ships exactly one Surface manifest entry: the Library
 * calibration target.
 *
 * <p>Per slice 449 §7 + §B.A.4: Library is the calibration spike for the
 * Surface Manifest substrate. The other 6 React rail surfaces (Search, Browse,
 * Brain, Agent, Health, Help, Settings) stay in the React chrome's hand-coded
 * dispatcher until phase 8's calibration outcome decides scope expansion.
 *
 * <h3>Entry: {@code core.library-surface}</h3>
 *
 * <ul>
 *   <li><b>Audience</b>: {@link Audience#USER} — operationally a user-facing
 *       surface; no operator-class data flows through it. Audience composition
 *       rule (slice 449 §0 D2): provenance CORE → no floor lift; consumes no
 *       DiagnosticChannels → no channel-floor lift; effective audience = USER.
 *   <li><b>Placement</b>: {@link Placement#RAIL} — Library is a top-level
 *       rail entry today (per the React chrome's {@code railItems} array). The
 *       new substrate preserves the placement.
 *   <li><b>Operations</b>: 5 entries from the existing
 *       {@code CoreOperationCatalog} — {@code core.reindex},
 *       {@code core.add-watched-root}, {@code core.remove-watched-root},
 *       {@code core.preview-excludes}, {@code core.apply-excludes} (all
 *       declared via {@code useOperation()} in the React Library view per
 *       §B.A.4's investigation finding).
 *   <li><b>Resources</b>: empty in V1. Phase 7 ships {@code core.indexed-roots}
 *       as a TABULAR Resource (per §B.A.4) and updates this entry to consume
 *       it. Until then the entry's {@code consumes.resources} stays empty.
 *   <li><b>Mount tag</b>: {@code jf-library-surface}. Phase 7 builds the
 *       Lit element side-effect-registers it; the chrome dispatcher mounts
 *       the resolved tag.
 * </ul>
 */
public final class CoreSurfaceCatalog implements SurfaceCatalog {

  /** Shared namespace with the other core catalogs. */
  public static final String NAMESPACE = "core";

  private final AliasRegistry aliasRegistry;
  private final CatalogMatcher catalogMatcher;

  public CoreSurfaceCatalog() {
    this(AliasRegistry.empty(), CatalogMatcher.defaultMatcher());
  }

  public CoreSurfaceCatalog(AliasRegistry aliasRegistry, CatalogMatcher catalogMatcher) {
    this.aliasRegistry = aliasRegistry;
    this.catalogMatcher = catalogMatcher;
  }

  @Override
  public AliasRegistry aliasRegistry() {
    return aliasRegistry;
  }

  @Override
  public CatalogMatcher matcher() {
    return catalogMatcher;
  }

  public static final SurfaceRef LIBRARY_SURFACE_ID = new SurfaceRef("core.library-surface");

  /** The Lit custom-element tag the chrome mounts for this surface. */
  public static final String LIBRARY_MOUNT_TAG = "jf-library-surface";

  /** Slice 451 phase 9 — Help surface (FAQ + keyboard shortcuts + diagnostics export). */
  public static final SurfaceRef HELP_SURFACE_ID = new SurfaceRef("core.help-surface");

  public static final String HELP_MOUNT_TAG = "jf-help-surface";

  /** 569 §19 Phase 4 — Presentation gallery (style variations / skins: preview, apply, revert). */
  public static final SurfaceRef PRESENTATION_GALLERY_SURFACE_ID =
      new SurfaceRef("core.presentation-gallery-surface");

  public static final String PRESENTATION_GALLERY_MOUNT_TAG = "jf-presentation-gallery-surface";

  /** 569 §19 Phase 6 — Presentation editor (author a declaration: palette, live preview, linter). */
  public static final SurfaceRef PRESENTATION_EDITOR_SURFACE_ID =
      new SurfaceRef("core.presentation-editor-surface");

  public static final String PRESENTATION_EDITOR_MOUNT_TAG = "jf-presentation-editor-surface";

  /** Slice 452 phase 9 — Brain surface (LLM install, runtime, inference mode). */
  public static final SurfaceRef BRAIN_SURFACE_ID = new SurfaceRef("core.brain-surface");

  public static final String BRAIN_MOUNT_TAG = "jf-brain-surface";

  // Tempdoc 561 surface tier: the standalone `core.agent-surface` (jf-agent-surface) is retired.
  // Its conversation shape (SHAPE_AGENT_RUN) folds into the one interaction window
  // (core.unified-chat-surface), which now consumes the agent-run shape alongside ask/chat/extract.

  /** Slice 454 phase 9 — Settings surface (theme, keyboard, autostart, reset). */
  public static final SurfaceRef SETTINGS_SURFACE_ID = new SurfaceRef("core.settings-surface");

  public static final String SETTINGS_MOUNT_TAG = "jf-settings-surface";

  /** Tempdoc 629 — unified Security & Privacy surface (encryption control + at-rest status). */
  public static final SurfaceRef SECURITY_SURFACE_ID = new SurfaceRef("core.security-surface");

  public static final String SECURITY_MOUNT_TAG = "jf-security-surface";

  private static final OperationRef OP_RESET_SETTINGS = new OperationRef("core.reset-settings");

  /** Slice 455 phase 9 — Browse surface (file tree of watched roots). */
  public static final SurfaceRef BROWSE_SURFACE_ID = new SurfaceRef("core.browse-surface");

  public static final String BROWSE_MOUNT_TAG = "jf-browse-surface";

  /** Slice 456 phase 9 — Health surface (system status + quick actions). */
  public static final SurfaceRef HEALTH_SURFACE_ID = new SurfaceRef("core.health-surface");

  public static final String HEALTH_MOUNT_TAG = "jf-health-surface";

  // Search Thread S5b (the great retirement): the standalone `core.search-surface` (jf-search-surface,
  // Slice 463 Search HUD) Surface entry is RETIRED. Its retrieve tier folded into the one interaction
  // window (`core.unified-chat-surface`), matching the `core.agent-surface` retirement precedent above
  // (constants removed, comment left). FE deep-links alias via RETIRED_SURFACE_ALIASES.

  /** Slice 3a.2.e — Logs surface (EngineLog DiagnosticChannel consumer). */
  public static final SurfaceRef LOGS_SURFACE_ID = new SurfaceRef("core.logs-surface");

  public static final String LOGS_MOUNT_TAG = "jf-log-surface";

  /** Slice 3a.2.e — DiagnosticChannel id consumed by the Logs surface. */
  private static final DiagnosticChannelRef DC_ENGINE_LOG =
      new DiagnosticChannelRef("core.engine-log");

  /**
   * Tempdoc 571 §11 / 578 — the System hub: the one rail entry for "what is/was the system doing",
   * hosting Health · Logs · Activity as tabs. PRODUCT with EMPTY consumes — it COMPOSES members, it
   * does not itself project any authority (the honest "composes, not fuses" pattern
   * {@code core.system-self-view} already uses). The earlier "DIAGNOSTIC via engine-log channel"
   * framing was REVERTED in the post-review pass: declaring a channel the host never subscribes to
   * registered a false consumer hook (SurfaceConsumerIndex) and violated 571 §8's "consumes is a
   * truthful manifest" precondition. The DIAGNOSTIC / TRUST distinction lives INSIDE the composite
   * (per-tab altitude framing in {@code <jf-surface-tabs>}), where it actually matters.
   */
  public static final SurfaceRef SYSTEM_SURFACE_ID = new SurfaceRef("core.system-surface");

  public static final String SYSTEM_MOUNT_TAG = "jf-system-surface";

  /**
   * Tempdoc 583 §D.3b — the API explorer: a read-only DEVELOPER projection of the self-describing
   * route manifest ({@code GET /api/meta/routes}, §D.3a) — the live HTTP surface grouped by cohort
   * with each route's required capability. DEEPLINK (off-rail, reached by URL / command palette) — a
   * dev/operator legibility tool. Empty consumes ⟹ PRODUCT altitude (the manifest is fetched
   * out-of-band, cf. SystemSelfView / Activity).
   */
  public static final SurfaceRef API_EXPLORER_SURFACE_ID =
      new SurfaceRef("core.api-explorer-surface");

  public static final String API_EXPLORER_MOUNT_TAG = "jf-api-explorer-view";

  /**
   * Slice 491 §9.D Phase E C3 — compatibility address for grounded Ask
   * ({@code core.rag-ask}). The unified Search surface is the canonical
   * rail destination; this DEEPLINK surface preserves mode-specific links.
   */
  public static final SurfaceRef ASK_SURFACE_ID = new SurfaceRef("core.ask-surface");

  /**
   * The chat-shape mount tag. The generic {@code <jf-chat-shape-mount>}
   * Lit element (shipped in C0) resolves its {@code shape-id} attribute
   * to a registered {@code ViewFactory} and mounts the typed view.
   * Surfaces hosting a chat shape use this mount tag uniformly; the
   * {@code consumes.conversationShapes} field tells the mount which
   * shape to resolve.
   */
  public static final String CHAT_SHAPE_MOUNT_TAG = "jf-chat-shape-mount";

  /** ConversationShape selected by the Ask compatibility surface. */
  private static final ConversationShapeRef SHAPE_RAG_ASK =
      new ConversationShapeRef("core.rag-ask");

  /**
   * Slice 497 — Unified conversation surface. Consolidates Ask + Chat + Extract
   * into one rail entry with affordance-driven per-message shape routing. The FE
   * picks the shape via affordance toggles; the backend dispatches dynamically
   * through {@code POST /api/chat/dispatch}.
   */
  public static final SurfaceRef UNIFIED_CHAT_SURFACE_ID =
      new SurfaceRef("core.unified-chat-surface");

  public static final String UNIFIED_CHAT_MOUNT_TAG = "jf-unified-chat-view";

  /** Slice 496 §3.B: FreeChat surface. */
  public static final SurfaceRef FREE_CHAT_SURFACE_ID = new SurfaceRef("core.free-chat-surface");

  /** ConversationShape consumed by the FreeChat surface. */
  private static final ConversationShapeRef SHAPE_FREE_CHAT =
      new ConversationShapeRef("core.free-chat");

  /** Slice 496 §3.C: Extract surface. */
  public static final SurfaceRef EXTRACT_SURFACE_ID = new SurfaceRef("core.extract-surface");

  private static final ConversationShapeRef SHAPE_EXTRACT =
      new ConversationShapeRef("core.extract");

  /**
   * Slice 491 F5 — ConversationShape for the agent run. The agent loop's encapsulated runner
   * (ToolIteratingShapeRunner) mounts this shape. Tempdoc 561 surface tier: it is now consumed by
   * the one interaction window ({@code core.unified-chat-surface}) — the standalone agent surface
   * was retired — so the discoverable user mount for {@code core.agent-run} is the unified window,
   * whose FE view ({@code jf-unified-chat-view}) presets the agent affordance from the shape-id.
   */
  private static final ConversationShapeRef SHAPE_AGENT_RUN =
      new ConversationShapeRef("core.agent-run");

  /**
   * Tempdoc 560 Phase 2 — the Workflow surface. Mounts the {@code core.workflow-run} shape (the
   * executable Workflow type); declaring the reverse reference here makes it the discoverable
   * user mount for the F4 Pass-9 audit.
   */
  // Tempdoc 565 §15.C — WORKFLOW_SURFACE_ID / WORKFLOW_MOUNT_TAG retired (the standalone workflow
  // surface is gone). SHAPE_WORKFLOW_RUN now folds into the one unified-chat surface's consumed shapes.
  private static final ConversationShapeRef SHAPE_WORKFLOW_RUN =
      new ConversationShapeRef("core.workflow-run");

  /**
   * Slice 491 F5 — ConversationShapes consumed by the Browse surface. The future
   * context-menu integration (slice 491 §9.D E16; SummarizeView header comment) dispatches
   * each of these from BrowseSurface; declaring the reverse references here makes the
   * link load-bearing for the F4 audit before the FE wiring lands.
   */
  private static final ConversationShapeRef SHAPE_SUMMARIZE =
      new ConversationShapeRef("core.summarize");

  private static final ConversationShapeRef SHAPE_BATCH_SUMMARIZE =
      new ConversationShapeRef("core.batch-summarize");

  private static final ConversationShapeRef SHAPE_HIERARCHICAL_SUMMARIZE =
      new ConversationShapeRef("core.hierarchical-summarize");

  /**
   * Slice 486 F15-narrow — Activity surface (Operation history viewer).
   * Consumer of the {@code core.operation-history} EVENT_STREAM Resource
   * declared in {@link io.justsearch.app.observability.operations.OperationHistoryResourceCatalog}.
   * Narrow scope: shows the in-memory ring buffer (last ~200 entries) via
   * the existing jf-table specialty renderer for kind="operation-history".
   * No durable archive (lifts to Tier B per slice 486 §22.1).
   */
  public static final SurfaceRef ACTIVITY_SURFACE_ID = new SurfaceRef("core.activity-surface");

  public static final String ACTIVITY_MOUNT_TAG = "jf-activity-surface";

  // Tempdoc 575 §17 Face B — the System Self-View ("Now") constants were REMOVED by tempdoc 578
  // Workstream A: the standalone surface is retired and its live-strip folded into Health
  // (<jf-system-self-view variant="strip">). The FE element + its render logic live on; only the
  // catalog declaration is gone (a deep-link to core.system-self-view aliases to the System hub).

  /**
   * Tempdoc 571 §4c — the TRUST-role Resource the Activity surface consumes. The unified action
   * ledger (550 Outcome face) is what Activity actually renders ({@code jf-action-ledger} →
   * {@code /api/action-ledger/stream}); declaring it makes Activity's {@link Altitude#TRUST} DERIVE
   * from consumption rather than be hand-declared (closes the §8 R1 out-of-band gap). Supersedes the
   * pre-unified stand-in declaration of {@code core.operation-history} (slice 486 F15-narrow), which
   * predated the one-ledger read-view.
   */
  private static final ResourceRef RES_ACTION_LEDGER = new ResourceRef("core.action-ledger");

  // Health surface Operations
  private static final OperationRef OP_RESTART_WORKER = new OperationRef("core.restart-worker");
  private static final OperationRef OP_BULK_REINDEX = new OperationRef("core.bulk-reindex");
  private static final OperationRef OP_CLEAR_FAILED_JOBS = new OperationRef("core.clear-failed-jobs");
  /**
   * Slice 484 §3.6 / observations.md `core.index-gc` closure: declared in
   * {@link io.justsearch.app.services.registry.operations.CoreOperationCatalog#INDEX_GC}
   * with {@code IndexGcHandler} delegating to {@code MigrationOps.runIndexGc}.
   * The Health surface FE button at {@code HealthSurface.ts} now invokes a real
   * Operation.
   */
  private static final OperationRef OP_INDEX_GC = new OperationRef("core.index-gc");

  // Health surface Resources (slice 481 §E.5.1 finding closure: declared after
  // the autonomous-continuation surfaced that the FE consumes these via REST/SSE
  // but the Surface didn't declare them in consumes.resources).
  //
  // Slice 481 §D defect 1 — canonical first instance (Pass 9 verification, 2026-05-08).
  // RES_FAILED_INDEXING_JOBS is audience=OPERATOR per slice 481 §7 step 2 Phase C
  // decision (failed-jobs triage is admin work). Health surface is audience=USER.
  // Under the V1 audience composition rule (slice 449 §0 D2), the rule only checks
  // DiagnosticChannel.consumerPermission — Resource.audience does NOT currently
  // elevate the Surface, so Health stays USER on the wire. Under the structurally-
  // correct rule (slice 482 §3.4 MIN-floor on visible fields), Health stays USER
  // and the operator-only failed-jobs section is redacted at render time. Under the
  // naive extension of MAX-floor to Resource.audience, Health would elevate to
  // OPERATOR — the regression Pass 8 §5 flagged.
  //
  // This is the *canonical first instance* of §D defect 1. The substrate documents
  // the tension; slice 482's per-field redact is the design fix; the V1 rule's
  // current scope (DC-only) keeps Health USER until the rule is intentionally
  // extended. The structurally-honest answer is per-field redact; do not extend
  // MAX-floor to Resource.audience without the redact mechanism in place.
  private static final ResourceRef RES_HEALTH_EVENTS = new ResourceRef("core.health-events");
  private static final ResourceRef RES_CONDITION_RECOVERY_INDEX =
      new ResourceRef("core.condition-recovery-index");
  private static final ResourceRef RES_FAILED_INDEXING_JOBS =
      new ResourceRef("core.failed-indexing-jobs");

  // Operation references — already declared in CoreOperationCatalog; the
  // SurfaceAreaValidator's V1 doesn't enforce Operation cross-refs (V1.5's
  // slice 3a-1-8c cross-reference enforcement extends here per slice 449 §6.5).
  private static final OperationRef OP_REINDEX = new OperationRef("core.reindex");
  private static final OperationRef OP_ADD_WATCHED_ROOT =
      new OperationRef("core.add-watched-root");
  private static final OperationRef OP_REMOVE_WATCHED_ROOT =
      new OperationRef("core.remove-watched-root");
  private static final OperationRef OP_PREVIEW_EXCLUDES =
      new OperationRef("core.preview-excludes");
  private static final OperationRef OP_APPLY_EXCLUDES =
      new OperationRef("core.apply-excludes");

  /** Slice 449 phase 7c — TABULAR Resource that backs Library's row data. */
  private static final ResourceRef RES_INDEXED_ROOTS = new ResourceRef("core.indexed-roots");

  /** Slice 451 phase 9 — Help's full diagnostics-bundle interaction. */
  private static final OperationRef OP_EXPORT_DIAGNOSTICS =
      new OperationRef("core.export-diagnostics");
  /** Tempdoc 899 D5 — Help's bounded, transient bug-report summary interaction. */
  private static final OperationRef OP_COPY_DIAGNOSTIC_SUMMARY =
      new OperationRef("core.copy-diagnostic-summary");

  /** Slice 452 phase 9 — Brain's set of consumed Operations. */
  private static final OperationRef OP_START_AI_INSTALL = new OperationRef("core.start-ai-install");
  private static final OperationRef OP_CANCEL_AI_INSTALL = new OperationRef("core.cancel-ai-install");
  private static final OperationRef OP_REPAIR_AI_INSTALL = new OperationRef("core.repair-ai-install");
  private static final OperationRef OP_PREFLIGHT_AI_PACK = new OperationRef("core.preflight-ai-pack");
  private static final OperationRef OP_IMPORT_AI_PACK = new OperationRef("core.import-ai-pack");
  private static final OperationRef OP_ACTIVATE_RUNTIME_VARIANT =
      new OperationRef("core.activate-runtime-variant");
  private static final OperationRef OP_DEACTIVATE_RUNTIME_VARIANT =
      new OperationRef("core.deactivate-runtime-variant");
  private static final OperationRef OP_SWITCH_INFERENCE_MODE =
      new OperationRef("core.switch-inference-mode");
  private static final OperationRef OP_RELOAD_INFERENCE = new OperationRef("core.reload-inference");

  private static final List<Surface> DEFINITIONS =
      List.of(
          new Surface(
              LIBRARY_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.library-surface.label"),
                  new I18nKey("registry-surface.library-surface.description")),
              Audience.USER,
              Placement.RAIL,
              new SurfaceConsumes(
                  /* resources */ Set.of(RES_INDEXED_ROOTS),
                  /* operations */ Set.of(
                      OP_REINDEX,
                      OP_ADD_WATCHED_ROOT,
                      OP_REMOVE_WATCHED_ROOT,
                      OP_PREVIEW_EXCLUDES,
                      OP_APPLY_EXCLUDES),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              LIBRARY_MOUNT_TAG,
              Provenance.core("1.0"))
              // Tempdoc 571 §11 / 578 — Library ⊇ Browse: Library hosts the Browse file-tree as a tab
              // ("Folders" | "Browse"). Browse leaves the rail (its Placement is DEEPLINK below) and its
              // deep-link resolves to Library. Membership is the single home-authority.
              .withMembers(List.of(BROWSE_SURFACE_ID)),
          new Surface(
              HELP_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.help-surface.label"),
                  new I18nKey("registry-surface.help-surface.description")),
              Audience.USER,
              // Tempdoc 578 §5.6 Phase 4 — Help is reference content, not a workspace surface: DEEPLINK
              // (off the rail catalog + rail-customization) reached via the dedicated "?" chrome
              // affordance in the rail's bottom section. Stays URL-routable + command-palette reachable.
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(OP_EXPORT_DIAGNOSTICS, OP_COPY_DIAGNOSTIC_SUMMARY),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              HELP_MOUNT_TAG,
              Provenance.core("1.0")),
          // 569 §19 Phase 4 — the style-variations / skins gallery (preview · apply · revert · export).
          new Surface(
              PRESENTATION_GALLERY_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.presentation-gallery-surface.label"),
                  new I18nKey("registry-surface.presentation-gallery-surface.description")),
              Audience.USER,
              // Tempdoc 571 §11 / 578 — Skins is a MEMBER of Settings ⊇ Appearance: DEEPLINK (off rail).
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              PRESENTATION_GALLERY_MOUNT_TAG,
              Provenance.core("1.0")),
          // 569 §19 Phase 6 — the visual presentation editor (palette · live preview · inline linter).
          new Surface(
              PRESENTATION_EDITOR_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.presentation-editor-surface.label"),
                  new I18nKey("registry-surface.presentation-editor-surface.description")),
              Audience.USER,
              // Tempdoc 571 §11 / 578 — Editor is a MEMBER of Settings ⊇ Appearance: DEEPLINK (off rail).
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              PRESENTATION_EDITOR_MOUNT_TAG,
              Provenance.core("1.0")),
          // Tempdoc 583 §D.3b — the API explorer (read-only route-manifest projection). DEEPLINK
          // dev/operator tool; empty consumes ⟹ PRODUCT altitude (manifest fetched out-of-band).
          new Surface(
              API_EXPLORER_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.api-explorer-surface.label"),
                  new I18nKey("registry-surface.api-explorer-surface.description")),
              Audience.DEVELOPER,
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              API_EXPLORER_MOUNT_TAG,
              Provenance.core("1.0")),
          new Surface(
              BRAIN_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.brain-surface.label"),
                  new I18nKey("registry-surface.brain-surface.description")),
              Audience.USER,
              Placement.RAIL,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(
                      OP_START_AI_INSTALL,
                      OP_CANCEL_AI_INSTALL,
                      OP_REPAIR_AI_INSTALL,
                      OP_PREFLIGHT_AI_PACK,
                      OP_IMPORT_AI_PACK,
                      OP_ACTIVATE_RUNTIME_VARIANT,
                      OP_DEACTIVATE_RUNTIME_VARIANT,
                      OP_SWITCH_INFERENCE_MODE,
                      OP_RELOAD_INFERENCE),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              BRAIN_MOUNT_TAG,
              Provenance.core("1.0"))
              // Tempdoc 571 §11 / 578 — AI Brain ⊇ Memory: the runtime-config window hosts the AI's
              // learned-memory ("what it knows") as a tab. Memory is an FE CorePlugin-contributed
              // surface (core.memory-surface); composition spans declaration sources (578 Option A) —
              // the FE member→host resolution + rail-exclusion run over the merged catalog.
              .withMembers(List.of(new SurfaceRef("core.memory-surface"))),
          // Slice 497 / Tempdoc 561 + 565 §15.C: the one interaction window. Consolidates Ask +
          // Chat + Extract + Agent + Workflow into a single rail entry — every direct-LLM shape
          // lands here. Uses a custom mount tag (not jf-chat-shape-mount) because it
          // hosts multiple shapes via affordance-driven per-message dispatch; the standalone
          // core.agent-surface (561) and core.workflow-surface (565 §15.C) were retired and their
          // shapes folded in (a second visible workflow surface is now an interaction-surface gate fail).
          new Surface(
              UNIFIED_CHAT_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.unified-chat-surface.label"),
                  new I18nKey("registry-surface.unified-chat-surface.description")),
              Audience.USER,
              Placement.RAIL,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of(),
                  /* conversationShapes */ Set.of(
                      SHAPE_RAG_ASK,
                      SHAPE_FREE_CHAT,
                      SHAPE_EXTRACT,
                      SHAPE_AGENT_RUN,
                      SHAPE_WORKFLOW_RUN)),
              UNIFIED_CHAT_MOUNT_TAG,
              Provenance.core("1.0"),
              java.util.Optional.of(
                  SurfaceStateSchemaLoader.require(
                      UNIFIED_CHAT_SURFACE_ID,
                      List.of(
                          new io.justsearch.agent.api.registry.StateBinding(
                              "/query", "unified-chat", "query"),
                          new io.justsearch.agent.api.registry.StateBinding(
                              "/docIds", "unified-chat", "docIds"),
                          new io.justsearch.agent.api.registry.StateBinding(
                              "/affordance", "unified-chat", "affordance"))))),
          // Slice 491 §9.D Phase E C3 — Ask compatibility surface for the
          // RAG-grounded Q&A shape. Audience composition rule (slice 449 §0
          // D2): provenance CORE → no floor lift; consumes no DiagnosticChannels
          // → no channel-floor lift; effective audience = USER. The mount
          // tag is the generic <jf-chat-shape-mount>; the
          // consumes.conversationShapes field tells it which shape's view
          // factory to mount.
          new Surface(
              ASK_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.ask-surface.label"),
                  new I18nKey("registry-surface.ask-surface.description")),
              Audience.USER,
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of(),
                  /* conversationShapes */ Set.of(SHAPE_RAG_ASK)),
              CHAT_SHAPE_MOUNT_TAG,
              Provenance.core("1.0"),
              // Slice 496 §3.A: state schema so the Ask compatibility surface accepts
              // pre-filled context from other surfaces via the existing
              // store/snapshot/NavigationHandler system. The FE 'ask' store
              // (askChatState.ts) receives {query, docIds} from the snapshot.
              java.util.Optional.of(
                  SurfaceStateSchemaLoader.require(
                      ASK_SURFACE_ID,
                      List.of(
                          new io.justsearch.agent.api.registry.StateBinding(
                              "/query", "ask", "query"),
                          new io.justsearch.agent.api.registry.StateBinding(
                              "/docIds", "ask", "docIds"))))),
          // Tempdoc 855 §9.2 — Settings opens as a centered window over the stage
          // (<jf-settings-window>), not as a rail surface; it keeps a fixed bottom rail affordance.
          // The placement must stay in step with CorePlugin.ts (check-surface-composition LEG-2).
          new Surface(
              SETTINGS_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.settings-surface.label"),
                  new I18nKey("registry-surface.settings-surface.description")),
              Audience.USER,
              Placement.MODAL,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(OP_RESET_SETTINGS),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              SETTINGS_MOUNT_TAG,
              Provenance.core("1.0"))
              // Tempdoc 571 §11 / 578 — Settings ⊇ Appearance: the preferences window hosts the two
              // theming surfaces (Skins gallery, presentation Editor) as an Appearance tab group, so
              // "how it looks" has one home instead of separate rail icons.
              // Tempdoc 855 §5 item 1 / §9.3 — Settings ⊇ Security & Privacy: the settings window
              // absorbs core.security-surface as a member category under Privacy & Trust (set-once
              // config, not a daily rail destination); its deep-links keep working via the existing
              // member→host alias redirect (§9.4).
              .withMembers(
                  List.of(
                      PRESENTATION_GALLERY_SURFACE_ID,
                      PRESENTATION_EDITOR_SURFACE_ID,
                      SECURITY_SURFACE_ID)),
          // Tempdoc 629 (remaining-work) — the unified Security & Privacy surface: the encryption
          // control + recovery + encrypted backup/import + auto-lock (moved out of Settings) above the
          // read-only at-rest status.
          // Tempdoc 855 §5 item 1 — absorbed into the settings window as a member category (Privacy &
          // Trust). This partially reverses 629's placement but not its substance: 629's point was "own
          // home with real sections", which the settings window now provides directly.
          // Tempdoc 571 §11 / 578 — Security is a MEMBER of Settings (its "Security & Privacy"
          // category), so its home is its host: DEEPLINK keeps it off the rail (and out of every
          // placement==='RAIL' consumer) while staying URL-routable; the member→host redirect lands a
          // core.security-surface deep-link on Settings with the Security category active.
          new Surface(
              SECURITY_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.security-surface.label"),
                  new I18nKey("registry-surface.security-surface.description")),
              Audience.USER,
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              SECURITY_MOUNT_TAG,
              Provenance.core("1.0")),
          new Surface(
              BROWSE_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.browse-surface.label"),
                  new I18nKey("registry-surface.browse-surface.description")),
              Audience.USER,
              // Tempdoc 571 §11 / 578 — Browse is a MEMBER of Library (rendered as its "Browse" tab),
              // so its home is its host: DEEPLINK keeps it off the rail (and out of every
              // placement==='RAIL' consumer) while staying URL-routable; the member→host redirect lands
              // a core.browse-surface deep-link on Library with the Browse tab active.
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of(),
                  // Slice 491 F5: reverse references to the three summarize shapes
                  // (single-doc, batch, hierarchical). The future context-menu
                  // integration on BrowseSurface dispatches these (§9.D E16,
                  // SummarizeView header). The audit treats the declaration as
                  // the discoverable user mount; SummarizeView's branch-by-shape-id
                  // submit logic (F5 FE half) realizes it.
                  /* conversationShapes */
                  Set.of(SHAPE_SUMMARIZE, SHAPE_BATCH_SUMMARIZE, SHAPE_HIERARCHICAL_SUMMARIZE)),
              BROWSE_MOUNT_TAG,
              Provenance.core("1.0")),
          // Tempdoc 571 §11 / 578 — the System hub host: one rail entry hosting Health · Logs ·
          // Activity as tabs. EMPTY consumes ⟹ derives PRODUCT (composes, does not fuse — the
          // core.system-self-view precedent). Per-member altitude framing inside <jf-surface-tabs>
          // preserves the DIAGNOSTIC/TRUST distinction where it matters; the host declares no
          // authority it does not actually project (post-review honesty fix; 571 §8).
          new Surface(
                  SYSTEM_SURFACE_ID,
                  Presentation.of(
                      new I18nKey("registry-surface.system-surface.label"),
                      new I18nKey("registry-surface.system-surface.description")),
                  Audience.USER,
                  Placement.RAIL,
                  new SurfaceConsumes(
                      /* resources */ Set.of(),
                      /* operations */ Set.of(),
                      /* prompts */ Set.of(),
                      /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
                  SYSTEM_MOUNT_TAG,
                  Provenance.core("1.0"))
              .withMembers(List.of(HEALTH_SURFACE_ID, LOGS_SURFACE_ID, ACTIVITY_SURFACE_ID)),
          new Surface(
              HEALTH_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.health-surface.label"),
                  new I18nKey("registry-surface.health-surface.description")),
              Audience.USER,
              // Tempdoc 571 §11 / 578 — Health is a MEMBER of the System hub (its "Health" tab), so its
              // home is its host: DEEPLINK (off the rail, URL-routable). Still DIAGNOSTIC by derivation.
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(
                      RES_HEALTH_EVENTS,
                      RES_CONDITION_RECOVERY_INDEX,
                      RES_FAILED_INDEXING_JOBS),
                  /* operations */ Set.of(
                      OP_REINDEX,
                      OP_RESTART_WORKER,
                      OP_BULK_REINDEX,
                      OP_CLEAR_FAILED_JOBS,
                      OP_EXPORT_DIAGNOSTICS,
                      OP_INDEX_GC),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              HEALTH_MOUNT_TAG,
              Provenance.core("1.0"),
              java.util.Optional.empty(),
              // Tempdoc 571 §4c: altitude is DERIVED (RegistryController) from the consumed diagnostic
              // Resources (health-events / failed-indexing-jobs) ⟹ DIAGNOSTIC — not declared here. The
              // remediation Operations are affordances, not a second altitude (primary-authority rule).
              RiskTier.LOW),
          // Search Thread S5b (the great retirement): the `core.search-surface` Surface entry
          // (Placement.DEEPLINK since tempdoc 577 Goal 3 §3.6 / 570 Move A) is REMOVED outright — the
          // standalone rich surface (facets / trace / "why this result?" / date-filter pane) it kept
          // URL-routable is retired along with it; the retrieve tier's carry-forward scope is
          // jf-results-card + route chip + scope chips + landing only. FE deep-links to
          // core.search-surface alias to core.unified-chat-surface (RETIRED_SURFACE_ALIASES).
          // Slice 3a.2.e — Logs surface. Consumes the core.engine-log
          // DiagnosticChannel (slice 448 substrate). OPERATOR audience
          // because raw log tail is operator/dev-mode UX, not end-user.
          new Surface(
              LOGS_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.logs-surface.label"),
                  new I18nKey("registry-surface.logs-surface.description")),
              Audience.OPERATOR,
              // Tempdoc 571 §11 / 578 — Logs is a MEMBER of the System hub (its "Logs" tab). DEEPLINK
              // (off the rail) replaces the §11.8 hand-rolled Health|Logs embed with real composition.
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.of(DC_ENGINE_LOG)),
              LOGS_MOUNT_TAG,
              Provenance.core("1.0"),
              java.util.Optional.empty(),
              // Tempdoc 571 §4c: altitude is DERIVED from the consumed core.engine-log DiagnosticChannel
              // ⟹ DIAGNOSTIC — not declared here (consumes-a-channel ⟹ DIAGNOSTIC).
              RiskTier.LOW),
          // Slice 486 F15-narrow — Activity surface (the trust read-view / retrospective companion to
          // the conversation). Tempdoc 571: consumes the core.action-ledger TRUST-role Resource (the
          // unified 550 Outcome-face ledger it actually renders); USER audience. TRUST altitude is now
          // DERIVED from that consumption (RegistryController), not hand-declared. Its rail HOMING
          // (adjacent to the interaction window) is DERIVED from altitude in the Rail render (571 §6),
          // NOT from this catalog position (which is its natural declaration order).
          new Surface(
              ACTIVITY_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.activity-surface.label"),
                  new I18nKey("registry-surface.activity-surface.description")),
              Audience.USER,
              // Tempdoc 571 §11 / 578 — Activity (TRUST) is a MEMBER of the System hub (its trust-framed
              // "Activity" tab). DEEPLINK (off the rail); TRUST altitude still derived from action-ledger.
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(RES_ACTION_LEDGER),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
              ACTIVITY_MOUNT_TAG,
              Provenance.core("1.0"),
              java.util.Optional.empty(),
              // Tempdoc 571 §4c: altitude is DERIVED from the consumed core.action-ledger TRUST Resource
              // ⟹ TRUST — not declared here. CORE-only by construction (the surface-altitude gate
              // forecloses a plugin TRUST surface).
              RiskTier.LOW),
          // Tempdoc 575 §17 Face B: the System Self-View ("Now"). RETIRED as a standalone RAIL surface
          // by tempdoc 578 Workstream A (§5.5 "Now is too empty to stand alone") — its live-strip is now
          // folded into Health's top via <jf-system-self-view variant="strip">; a deep-link to
          // core.system-self-view redirects to the System hub → Health (RETIRED_SURFACE_ALIASES, FE).
          // Slice 496 §3.B: FreeChat surface — plain persistent conversation
          // with the local LLM. Uses the generic <jf-chat-shape-mount> tag
          // with core.free-chat as the consumed shape.
          new Surface(
              FREE_CHAT_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.free-chat-surface.label"),
                  new I18nKey("registry-surface.free-chat-surface.description")),
              Audience.USER,
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of(),
                  /* conversationShapes */ Set.of(SHAPE_FREE_CHAT)),
              CHAT_SHAPE_MOUNT_TAG,
              Provenance.core("1.0")),
          // Slice 496 §3.C: Extract surface — structured output.
          new Surface(
              EXTRACT_SURFACE_ID,
              Presentation.of(
                  new I18nKey("registry-surface.extract-surface.label"),
                  new I18nKey("registry-surface.extract-surface.description")),
              Audience.USER,
              Placement.DEEPLINK,
              new SurfaceConsumes(
                  /* resources */ Set.of(),
                  /* operations */ Set.of(),
                  /* prompts */ Set.of(),
                  /* diagnosticChannels */ Set.<DiagnosticChannelRef>of(),
                  /* conversationShapes */ Set.of(SHAPE_EXTRACT)),
              CHAT_SHAPE_MOUNT_TAG,
              Provenance.core("1.0")));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Surface> definitions() {
    return DEFINITIONS;
  }
}
