// SPDX-License-Identifier: Apache-2.0
/**
 * Shell — Lit-side chrome root (slice 449 phase 10).
 *
 * Replaces the React `<GlassShell>` + `<App>` skeleton for the
 * substrate-driven dispatch path. Layout zones:
 *   - top  : minimal title bar (theme + status badge)
 *   - left : <jf-rail> (Activity Rail)
 *   - main : <jf-stage> (Surface dispatcher)
 *   - bottom : minimal status deck (api state + queue)
 *
 * Reads the SurfaceCatalog at boot; consumes the `placement: 'RAIL'`
 * subset of entries to populate the rail. Active-view state is owned
 * here (not Zustand-coupled).
 *
 * Mount path: `?lit-chrome=1` URL flag — `main.jsx` branches to mount
 * `<jf-shell>` instead of the React `<App>` when present. Production
 * default remains the React app for V1 coexistence; phase 11 (React
 * decommission) flips the default.
 *
 * Side-effect registers `<jf-shell>`, `<jf-rail>`, `<jf-stage>`.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { startSurfaceTransition } from './viewTransition.js';
import '../components/RecentsMenu.js';
import {
  NAVIGATE_TO_SURFACE_EVENT,
  type NavigateToSurfaceDetail,
} from '../controllers/navigateRequest.js';
// 478 §4.A — `unsafeStatic` no longer needed in Stage.render().
// SurfaceFactory.mount() returns an HTMLElement directly; Lit's
// `html` template renders Node references natively. Keep the
// fallback static-html path for legacy entries (factory absent)
// during the V1.5.1 migration; V1.5.2 marks SurfaceFactory required.
import { html as staticHtml, unsafeStatic } from 'lit/static-html.js';
import {
  getSurface,
  listSurfaces,
  mountSurface,
  onSurfaceCatalogChange,
} from '../../api/registry/SurfaceCatalogClient.js';
import type { Surface } from '../../api/types/surface.js';
import { OperationClient } from '../operations/OperationClient.js';
import { operationFailureMessage } from '../operations/operationFailureMessage.js';
import { originatorToTransport } from '../operations/originatorTransport.js';
import { startDragDetect, type DragKind } from '../utils/dragDetect.js';
import { startAiStateStore, subscribeAiState } from '../state/aiStateStore.js';
import { unavailableBecause } from '../state/availability.js';
import '../components/Control.js';
// 569 §14 — host authorities the global presentation-intent Effect listeners drive.
import { applyAppearance } from '../state/themeState.js';
import {
  enqueueUiModeSettings,
  setUiMode,
  getUiMode,
  subscribeUiMode,
  type UiMode,
} from '../state/uiModeState.js';
import { applyPresentation, listPresentations } from '../state/presentationState.js';
import './OverlayHost.js';
import '../components/DragOverlay.js';
import '../components/IndexingOverlay.js';
import '../components/StatusDeck.js';
// §21.E — register the PendingEffect chrome surface. Renders nothing
// when the queue is empty; floats in lower-right when a proposal lands.
import '../components/PendingEffectQueue.js';
import '../components/AiActivityDigest.js';
import '../components/TaskList.js';
import '../components/AgentActivityPanel.js';
import '../components/RetrospectivePanel.js';
import '../components/FailedJobsDrawer.js';
import '../components/SourcesPane.js';
import '../components/ContextInspectorPane.js';
import '../components/AppUpdateBanner.js';
import { startAppUpdateMonitor } from '../state/appUpdateState.js';
import type { AgentActivityPanel } from '../components/AgentActivityPanel.js';
// §25.β3 — elicit chrome surface (modal form host for Action handlers
// asking the user mid-invocation questions).
import '../components/ElicitHost.js';
// §25.β4 — capability-consent chrome surface (allow-once / allow-always /
// deny prompts for plugin capability requests).
// Tempdoc 550 G9: ConsentHost retired — AuthorizationHost is now the single ceremony surface
// and presents jf-consent-request (capability consent) alongside the 550 authorize prompt.
import { restoreConsentFromStorage } from '../substrates/consent/index.js';
// Tempdoc 550 C3 — the unified Authorize ceremony host + the broker the dispatcher uses
// to route a gated 428 to it for a human decision.
import '../components/AuthorizationHost.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
// §25.δ2 — per-action audit log surface (filterable Effect Journal viewer).
import '../components/EffectAuditLog.js';
import type { EffectAuditLog } from '../components/EffectAuditLog.js';
// 543-fwd #12 — macro dry-run diff panel (opens via jf-open-macro-dry-run).
import '../components/MacroDryRun.js';
import { registerAction as registerKernelAction } from '../substrates/actions/index.js';
// §25.δ3 — persisted macros (auto-register as palette Actions on restore).
import { restoreMacrosFromStorage } from '../substrates/macros/index.js';
// §25.ζ#6 — first production projector (search-result). Closes §13.6 #6
// from STRUCTURAL-ENABLED to PRODUCTION-USED.
import { bootSearchResultProjector } from '../substrates/evaluationContext/searchResultProjector.js';
// §28.W8 — synthetic agent emitter demo. Exercises DataEffect arm +
// PendingEffect + originator-grouped undo end-to-end without needing
// real backend AI.
import '../components/AgentEmitterDemo.js';
import type { AgentEmitterDemo } from '../components/AgentEmitterDemo.js';
// Search Thread S6 — `components/InspectorPane.ts` retired; `<jf-document-pane>` (mounted inside
// UnifiedChatView's conversation-zone) is now the visual consumer of inspectorState.
// Tempdoc 541 §4.2: side-effect import registers <jf-boot-phases-panel> — the named
// production consumer of GET /api/boot/phases.
import '../components/BootPhasesPanel.js';
// Slice 471 — provenance badge (docked in the 559 OverlayHost top-right slot)
// is mounted by the chrome whenever any non-core override is active in userConfig.
import '../components/ProvenanceBadge.js';
import '../components/BookmarksPopover.js';
import type { BookmarksPopover } from '../components/BookmarksPopover.js';
// Slice 490 §4.D — Advisory chrome: toast host (HUD), inbox drawer
// (DRAWER), rail badge (RAIL). All three subscribe to a shell-owned
// AdvisoryStore (Group B4 follow-up — replaces the singleton pattern).
import '../components/advisory/AdvisoryToastHost.js';
import '../components/advisory/AdvisoryInboxDrawer.js';
import '../components/advisory/AdvisoryRailBadge.js';
import type { AdvisoryInboxDrawer } from '../components/advisory/AdvisoryInboxDrawer.js';
import {
  type AdvisoryStore,
  createAdvisoryStore,
} from '../components/advisory/AdvisoryStore.js';
import {
  setSelected,
  setOpen as setInspectorOpen,
  getInspectorState as getInspectorStateInternal,
  subscribeInspector,
  type InspectorState,
} from '../state/inspectorState.js';
import { isWideLayout, subscribeWide } from '../state/responsiveState.js';
// 687 R5b — the narrow-viewport reading mount: the SAME <jf-document-pane> presents through the
// OverlayHost right-drawer slot below the breakpoint (UnifiedChatView's grid mount is wide-only).
import '../components/documentPane/DocumentPane.js';
import type { CitationSelectDetail } from '../components/chat/CitationsPanel.js';
import {
  getUserConfig,
  subscribeUserConfig,
  setRailExpanded,
} from '../state/userConfigState.js';
import { updateShellContext, subscribeShellContext } from '../state/shellContextState.js';
// Tempdoc 543 §3.B + §20.7 A0 — Scope substrate. Direct restoreScope
// import deliberately removed: WorkspaceProfile.activateProfile is
// now the SOLE Scope writer in chrome boot. Anything that needs to
// write Scope flows through a profile activation (or its own dedicated
// substrate consumer; today there are none).
// Tempdoc 543 §13.2.1 — EvaluationContext substrate: bumpScopeVersion
// wired into the ShellContext notification stream as the perf-invariant
// cache-invalidation producer.
import { bumpScopeVersion } from '../substrates/evaluationContext/index.js';
// Tempdoc 543 §13.2.2 — Effect Journal: restore cross-session entries
// at boot so prior-session navigations / effects are queryable.
import {
  restoreJournalFromStorage,
  markUndoableOperation,
  undoLastEffect,
  redoLastEffect,
} from '../substrates/effects/index.js';
// 543-fwd #1 — journal-suppressed dispatcher for the cursor-based undo/redo.
import { dispatchEffectToChrome } from '../substrates/actions/index.js';
// §32 R-E1 — track agent operations as long-running tasks.
import { startTask, completeTask } from '../substrates/tasks/index.js';
// §32 #1 — project the backend indexing-jobs stream into the Task tray.
import { startIndexingJobsBridge } from '../substrates/tasks/indexingJobsBridge.js';
// Tempdoc 840 Phase 5 — project the staged AI model acquisition into the SAME Task tray, so the
// footer chip + floating task list carry "what is downloading right now" with no new surface.
import { startAiInstallTasksBridge } from '../substrates/tasks/aiInstallTasksBridge.js';
// Tempdoc 655 — surface a pending authorization created by an out-of-band caller (MCP).
import { startPendingAuthorizationBridge } from '../operations/pendingAuthorizationBridge.js';
import { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { setSharedShellEventsMultiplex } from '../streaming/shellEventsMultiplexInstance.js';
import { SHELL_EVENTS_STREAM_PATH } from '../streaming/shellEventStreamIds.js';
import { startEffectIngest } from '../operations/ActionLedgerClient.js';
// Tempdoc 543 §3.C — Action substrate boot side-effect: registers
// the canonical `core.action.cite-selection` Action at module-load
// and exposes registerAction/listActions/invokeAction/applyEffect to
// downstream consumers (Slice 8 HoverPreview reads it).
// §21.B — Shell registers Actions via the substrate's deps-bound API
// (registerShellActions / projectOperationsToActions imported below
// alongside the rest of the substrate exports). The legacy B3
// invokeAndApply shim retires here; the toggle-palette Action now
// owns the palette toggle directly.
import '../substrates/actions/index.js';
// Tempdoc 543 §12.3 #5 — HoverPreview kernel host. Defines
// <jf-hover-preview-host> + listens at document level for
// data-hover-aggregate-* annotations and stacks strategies via
// renderAggregateMulti('merge'). Bootstrap registers the canonical
// Operation strategy + flips 'hover-preview' policy to 'merge'.
import '../hover/HoverPreviewHost.js';
import { bootstrapAggregateSubstrate } from '../aggregate-substrate/bootstrap.js';
// Tempdoc 543 §13.2.3 — ContributionManifest substrate. The canonical
// first-party manifest installs at boot via the declarative path,
// proving the substrate end-to-end (vs the imperative registerAction
// from Slice 7's canonical Action).
import { installCoreDemoManifest, installCoreWalkthroughManifest } from '../substrates/manifest/canonicalManifest.js';
// Tempdoc 543 §13.6 — Workspace Profiles substrate. Boot restores
// the persisted profile registry + auto-mirrors UserStateDocument
// profiles + re-applies the active profile's Scope snapshot.
// Per §20.7 A0: WorkspaceProfile is the SOLE Scope writer.
// UserStateDocument profiles auto-mirror into WorkspaceProfile entries
// at boot + on every profile switch.
import {
  restoreProfilesFromStorage,
  activateProfile as activateWorkspaceProfile,
  getProfile as getWorkspaceProfile,
  saveProfile as saveWorkspaceProfile,
} from '../substrates/profiles/index.js';
import {
  getActiveProfileId,
  listProfiles,
  subscribeProfileSwitch,
} from '../state/UserStateDocument.js';
import { clearSelection } from '../state/selectionState.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';
import { getLayout } from '../layout/LayoutManifest.js';
import { createHostApi } from '../plugin-api/HostApiImpl.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { startAutoLock, type AutoLockHandle } from '../utils/autoLock.js';
import {
  registerCommand,
  setActiveSurfaceCommands,
} from '../commands/CommandRegistry.js';
// §21.B — shell commands + Operation projection now go through Action.
import {
  registerShellActions,
  projectOperationsToActions,
} from '../substrates/actions/index.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
import '../components/PluginErrorOverlay.js';
// Tempdoc 521 §16.4 — walkthrough card lit element (side-effect register).
import '../components/WalkthroughCard.js';
// Tempdoc 521 §16.7 deeper (Phase B) — split-stage right-pane picker.
import '../components/PanePicker.js';
import { setSecondaryActiveSurface } from '../state/userConfigState.js';
import { present } from '../display/present.js';
import { placementToLandmarkRole } from '../display/landmarks.js';
import {
  registerKeybinding as registerKeybindingEntry,
  attachKeybindingDispatcher,
  loadPersistedKeybindings,
} from '../commands/KeybindingRegistry.js';
import { invokeCommand } from '../commands/CommandRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import {
  listOperations as catalogListOperations,
  onCatalogChange as onOperationCatalogChange,
} from '../../api/registry/OperationCatalogClient.js';
import '../commands/CommandPalette.js';
import type { CommandPalette } from '../commands/CommandPalette.js';
// Tempdoc 508-followup §δ2 — hover-preview overlay element.
import '../components/Peek.js';
// Tempdoc 855 — the centered window hosting the MODAL settings surface.
import './SettingsWindow.js';
import type { SettingsWindow } from './SettingsWindow.js';
type SurfaceCatalogEntry = Surface;
import { icon } from '../components/Icon.js';
import { copyToClipboard } from '../utils/clipboardCopy.js';
// Slice 492 — Intent substrate three-tier wiring. Sources fire Intents;
// the router delegates to handlers; handlers realize the intent. This
// supplants slice 489's reverted dual-path Shell.ts integration; the
// router substrate (parser, surfaceSchemas, storeRegistry, projector)
// remains as shipped on origin/main.
import { createNavigationHandler } from '../router/navigationHandler.js';
import { createInvocationHandler } from '../router/invocationHandler.js';
import { createIntentRouter, type IntentRouter } from '../router/intentRouter.js';
import { resolveSurface as catalogResolveSurface, resolveOperation as catalogResolveOperation, refreshPromotedAliases } from '../router/catalogResolver.js';
import { requestMemberTab } from '../router/memberTabIntent.js';
import { promoteAlias } from '../router/promotedAliases.js';
import { resolutionTelemetryListener } from '../router/resolutionTelemetry.js';
import { strictPolicy } from '../router/recoveryPolicy.js';
import { createURLSource } from '../router/sources/URLSource.js';
import { createTauriDeepLinkSource } from '../router/sources/TauriDeepLinkSource.js';
import { createBackendStreamSource } from '../router/sources/BackendStreamSource.js';
import { deactivateProjection, flushPendingProjection } from '../router/URLProjector.js';
import {
  fetchAndRegisterSurfaceSchemas,
  registerCoreStores,
} from '../router/bootstrap.js';
import type { StateSnapshot } from '../router/types.js';
import type { TransportTag } from '../router/transports.js';
// Slice 501 — Navigation chrome (journal + saved views).
import {
  recordNavigation,
  subscribeJournal,
  getJournalState,
  canGoBack,
  canGoForward,
  navigateBack,
  navigateForward,
  isNavigatingHistoryNow,
} from '../state/NavigationJournal.js';
import { canonicalize, parseUrl } from '../router/parser.js';
import { deriveRichLabel } from '../utils/deriveRichLabel.js';
// The app's ONE "is the reader typing?" pair (857 PR-A) — adopted here by tempdoc 864 Layer 2(a),
// which retired this file's private fourth copy of the same descent.
import {
  deepActiveElement,
  isTypingTarget,
  shouldIgnoreKeyEvent,
} from '../utils/keyboardHandler.js';
import { modalOwnsFocus } from '../primitives/modality.js';
import { SURFACE_ICONS, railAccessibleName } from '../utils/surfaceIcons.js';
import {
  isViewSaved,
  saveView,
  removeView,
  getSavedViews,
  subscribeSavedViews,
} from '../state/savedViewState.js';
// Slice 491 §9.D Phase E (C2/C3) — side-effect imports that register typed
// chat views in the viewFactoryRegistry at module-load time.
// <jf-chat-shape-mount> resolves shape-ids by looking up the registry; without
// these imports, the mount would render the "no view factory registered"
// error placeholder.
import '../components/chat/ChatShapeMount.js';
import '../views/NavigateView.js';
import '../views/SummarizeView.js';
// Tempdoc 561 surface tier: AskView/FreeChatView/ExtractView retired — the one window
// (UnifiedChatView) is the view for every interaction shape (rag-ask/free-chat/extract/agent-run).
import '../views/UnifiedChatView.js';
// Lazy route-surface loaders — the other navigable surfaces are imported on
// first navigation (renderOneSurface, below) instead of eagerly, keeping them
// out of the eager app entry chunk (ui-bundle app_main budget).
import { ensureSurfaceLoaded, isLazySurface } from '../views/lazySurfaceRegistry.js';

// Surface tag → rail icon mapping: imported from shared module (slice 501).
// See utils/surfaceIcons.ts for the mapping.

// Tempdoc 578 §5.6 Phase 4 — Help is no longer a rail surface (DEEPLINK); it is reached via the
// dedicated "?" affordance the rail renders in its bottom section.
// Tempdoc 855 §9.2 — Settings followed it off the catalog rail list (Placement.MODAL: it opens as a
// window over the stage), so it too is a fixed bottom affordance rather than a bottom-pinned surface.
/** Tempdoc 578 — the Help surface id the rail's "?" affordance deep-links to. */
const HELP_SURFACE_ID = 'core.help-surface';
/** Tempdoc 855 — the MODAL surface the rail's fixed Settings affordance opens. */
const SETTINGS_SURFACE_ID = 'core.settings-surface';

// Tempdoc 571 §6 — the one interaction window (561). TRUST surfaces home adjacent to it in the rail,
// derived from altitude (see Rail.render). Mirrors the Java interaction-surface gate's canonicalSurface.
const INTERACTION_SURFACE_ID = 'core.unified-chat-surface';

// Tempdoc 923 / F-22 — these remain valid compatibility addresses that preselect one interaction
// shape, but they are not independent places. Split-pane discovery must offer the canonical Search
// window once, not re-expose its Ask/Chat/Extract modes as parallel windows.
const LEGACY_INTERACTION_MODE_SURFACE_IDS = new Set([
  'core.ask-surface',
  'core.free-chat-surface',
  'core.extract-surface',
]);

function canonicalSplitSurfaceId(surfaceId: string): string {
  return LEGACY_INTERACTION_MODE_SURFACE_IDS.has(surfaceId)
    ? INTERACTION_SURFACE_ID
    : surfaceId;
}

function isSplitPaneCandidate(surface: SurfaceCatalogEntry, primaryId: string): boolean {
  return canonicalSplitSurfaceId(surface.id) !== canonicalSplitSurfaceId(primaryId)
    && !LEGACY_INTERACTION_MODE_SURFACE_IDS.has(surface.id);
}

// Tempdoc 571 §4b — altitude-band rank for the cross-altitude move-ban. PRODUCT + TRUST share the
// product region (TRUST's finer "adjacent to chat" placement is the Rail render's job); DIAGNOSTIC
// sinks to the diagnostics region; TOOL (headless) last. A user reorder is clamped to keep each
// surface in its own band, so a saved surfaceOrder cannot drag a DIAGNOSTIC surface into the product
// region (or vice versa) — altitude, not user drag, owns which band a surface lives in.
function altitudeBandRank(altitude: string | undefined): number {
  switch (altitude) {
    case 'DIAGNOSTIC':
      return 1;
    case 'TOOL':
      return 2;
    default:
      return 0; // PRODUCT + TRUST
  }
}

/**
 * Clamp a user-reordered surface list to within-altitude bands (tempdoc 571 §4b). A STABLE sort by
 * {@link altitudeBandRank} preserves the user's within-band order but re-groups surfaces by band, so a
 * reorder can only permute within an altitude — never move a surface across one.
 */
export function clampReorderToAltitudeBands(
  surfaces: SurfaceCatalogEntry[],
): SurfaceCatalogEntry[] {
  return [...surfaces].sort((a, b) => altitudeBandRank(a.altitude) - altitudeBandRank(b.altitude));
}

// `deriveTitleFromSurfaceId` is imported from '../utils/deriveRichLabel.js'
// (slice 489 T1/G3). The local copy that used to live here was removed in
// tempdoc 521 §22 Phase A.1 — both Shell and PanePicker now share the
// single utility helper.

export class Shell extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    surfaces: { state: true },
    activeId: { state: true },
    dragActive: { state: true },
    dragKind: { state: true },
    userConfig: { state: true },
    copyUrlFeedback: { state: true },
    journalCanBack: { state: true },
    journalCanForward: { state: true },
    isBookmarked: { state: true },
    // Tempdoc 738 — the surfaced Simple/Detailed toggle mirrors the uiMode authority for its active state.
    uiMode: { state: true },
  };

  declare apiBase: string;
  declare surfaces: SurfaceCatalogEntry[];
  declare activeId: string | null;
  declare dragActive: boolean;
  declare dragKind: DragKind | null;
  declare userConfig: RendererUserConfig;
  /** Slice 489 T1/G1 — transient state for Copy URL button feedback. */
  declare copyUrlFeedback: 'copied' | 'failed' | null;
  /** Slice 501 — journal-derived back/forward enabled state. */
  declare journalCanBack: boolean;
  declare journalCanForward: boolean;
  /** Slice 501 — whether the current URL is bookmarked. */
  declare isBookmarked: boolean;
  /** Tempdoc 738 — app-wide Simple/Detailed mode, mirrored for the topbar toggle's active state. */
  declare uiMode: UiMode;

  private dragUnsubscribe: (() => void) | null = null;
  private userConfigUnsubscribe: (() => void) | null = null;
  private catalogUnsubscribe: (() => void) | null = null;
  // Tempdoc 586 F-2 — Simple/Detailed mode re-filters the rail (see refreshSurfaces).
  private uiModeUnsubscribe: (() => void) | null = null;
  /**
   * Slice 490 Group B4 — Shell-owned AdvisoryStore. Passed to the three advisory
   * chrome elements via property bindings; replaces the previous module-level
   * singleton lookup that had a first-caller-wins apiBase race.
   */
  private advisoryStore: AdvisoryStore | null = null;
  /**
   * Tempdoc 662 — the ONE multiplexed connection to `/api/shell-events/stream`, replacing 5
   * always-on EventSources (intent, the two advisory classes, action-ledger, indexing-jobs)
   * that exhausted the browser's ~6-per-host connection pool and starved the cheap
   * `/api/status`/`/api/inference/status` polls under load (tempdoc 649). Constructed once at
   * mount, threaded explicitly to the consumers below, and ALSO published via
   * `setSharedShellEventsMultiplex` for the one consumer the generic SurfaceCatalog mounter
   * can't reach by direct property binding (`ActionLedgerView`, see
   * `shellEventsMultiplexInstance.ts`).
   */
  private shellEventsMultiplex: MultiplexedStream | null = null;
  private operationClient: OperationClient | null = null;
  private _aiState: import('../state/aiStateStore.js').AiState | null = null;
  private _aiDependentIds: Set<string> = new Set();
  private _aiUnsub: (() => void) | null = null;
  // Slice 492 — Intent substrate.
  private intentRouter: IntentRouter | null = null;
  private sourceTeardowns: Array<() => void> = [];
  /**
   * Slice 492 critical-analysis follow-up — guards the async-teardown race:
   * if `disconnectedCallback` runs while a source's async `start(...)`
   * Promise is still pending, the teardown function arrives after the
   * Shell has already torn down. The flag tells the then-callback to
   * invoke the teardown immediately instead of pushing it onto
   * `sourceTeardowns` (where it would never be reached).
   */
  private disconnected = false;
  /** The previously-active surface, for "Go back" (`goBack`/`navigateBack`). Search Thread S5b —
   *  set directly by `setActiveSurface`'s own bookkeeping (the navigation toast that used to set
   *  this as a side effect is retired). */
  private previousActiveId: string | null = null;
  /** Tempdoc 507 §3 — CORE-tier PluginHostApi injected into surfaces at mount. */
  private hostApi_: PluginHostApi | null = null;
  /** Tempdoc 629 (#10) — app-wide auto-lock idle watcher handle (started at mount, stopped at unmount). */
  private autoLock_: AutoLockHandle | null = null;
  /** Tempdoc 508 §3 — global keybinding for command palette. */
  private keybindingTeardown: (() => void) | null = null;
  // Slice 501 — journal subscription + keyboard handler + savedViews teardowns.
  private journalUnsubscribe: (() => void) | null = null;
  private savedViewsUnsubscribe: (() => void) | null = null;
  // Tempdoc 543 §3.B — Scope substrate consumer: profile-switch + audience projection.
  private scopeProfileSwitchUnsub: (() => void) | null = null;
  // Tempdoc 543 §13.2.1 — EvaluationContext substrate consumer: bump
  // scope-version on every ShellContext change so projector memo
  // invalidates correctly.
  private evalContextScopeBumpUnsub: (() => void) | null = null;
  // §32 #1 — indexing-jobs → Task tray bridge teardown handle.
  private indexingJobsBridgeStop: (() => void) | null = null;
  // Tempdoc 840 Phase 5 — AI-install stages → Task tray bridge teardown handle.
  private aiInstallTasksBridgeStop: (() => void) | null = null;
  private appUpdateMonitorStop: (() => void) | null = null;
  // Tempdoc 655 — pending-authorization (out-of-band gate, e.g. MCP) bridge teardown handle.
  private pendingAuthorizationBridgeStop: (() => void) | null = null;
  private effectIngestStop: (() => void) | null = null;
  // Tempdoc 543 §20.7 A1/A2 — Effect dispatch listeners. applyEffect
  // (Action substrate, Slice 7) emits jf-open-pane / jf-close-pane /
  // jf-invoke-operation events at document level. Before A1/A2 these
  // dispatched into the void; now Shell routes them to live chrome
  // mechanisms.
  private openPaneListener: ((e: Event) => void) | null = null;
  private closePaneListener: ((e: Event) => void) | null = null;
  private invokeOperationListener: ((e: Event) => void) | null = null;
  private undoOperationListener: ((e: Event) => void) | null = null;
  // 569 §14 — global presentation-intent Effect v3 listeners.
  private setAppearanceListener: ((e: Event) => void) | null = null;
  private setUiModeListener: ((e: Event) => void) | null = null;
  private applyPresentationListener: ((e: Event) => void) | null = null;
  private boundKeyHandler = (e: KeyboardEvent) => this.handleGlobalKey(e);

  // Slice 496 §3.A: bound handler for navigate-with-context events.
  // Tempdoc 609 §R (T1.3) — "return to running job" from an overlay (TaskList / status-bar chip).
  private onNavigateToSurface = (ev: Event): void => {
    const id = (ev as CustomEvent<NavigateToSurfaceDetail>).detail?.surfaceId;
    if (id) this.activateSurface(id, {}, 'BUTTON');
  };

  private onNavigateWithContext = (ev: Event): void => {
    const detail = (ev as CustomEvent<{ target: string; state: Record<string, unknown> }>).detail;
    if (detail?.target) {
      this.activateSurface(
        detail.target,
        detail.state as import('../router/types.js').StateSnapshot ?? {},
        'BUTTON',
      );
    }
  };

  /**
   * Tempdoc 543 §20.7 A0 (profile unification) — mirror every
   * UserStateDocument profile into the WorkspaceProfile registry
   * (idempotent — saveProfile overwrites). Each WorkspaceProfile
   * carries the UserStateDocument profile's audience in its Scope
   * snapshot. After this runs, every UserStateDocument profile has
   * a corresponding WorkspaceProfile entry, so subsequent
   * `activateWorkspaceProfile(id)` calls find a target.
   *
   * Audience is the only Scope field UserStateDocument profiles
   * own today. Deferred slots (corpus/library/model/agent-role/
   * enabled-plugins) stay null/'' until per-domain state modules
   * ship; future per-domain producers populate the WorkspaceProfile
   * directly, not the UserStateDocument profile.
   */
  private mirrorUserStateProfilesIntoWorkspaceRegistry(): void {
    for (const p of listProfiles()) {
      const existing = getWorkspaceProfile(p.id);
      saveWorkspaceProfile({
        id: p.id,
        label: p.label,
        enabledManifestIds: existing?.enabledManifestIds ?? [],
        scope: { audience: p.viewerAudience ?? '' },
        createdAt: existing?.createdAt ?? new Date().toISOString(),
        ...(existing?.inheritsFrom !== undefined
          ? { inheritsFrom: existing.inheritsFrom }
          : {}),
        ...(existing?.description !== undefined
          ? { description: existing.description }
          : {}),
      });
    }
  }

  /**
   * Tempdoc 543 §20.7 A0 — activate the WorkspaceProfile corresponding
   * to UserStateDocument's active profile id. Single Scope writer: all
   * ShellContext updates that flow from a profile switch route through
   * activateWorkspaceProfile (which calls restoreScope internally).
   */
  private activateUserStateActiveProfile(): void {
    const profileId = getActiveProfileId();
    void activateWorkspaceProfile(profileId).catch((err) => {
      console.warn(
        `[Shell] activateWorkspaceProfile('${profileId}') failed`,
        err,
      );
    });
  }

  // 504 A-3 / Search Thread S6 — citation-click → open the doc for reading + highlight the cited
  // passage. `citation-select` is produced from several sites (inline [n] marks in MarkdownBlock, the
  // docked evidence-rail's SourcesPane inside UnifiedChatView, AND the Shell-level drawer SourcesPane
  // mounted in the OverlayHost right-drawer slot below — a sibling tree UnifiedChatView cannot itself
  // hear bubble through it) — Shell stays the ONE listener for all of them (a `composed:true` event
  // reaches here regardless of which subtree dispatched it). Rather than reaching into a specific pane
  // instance (the retired InspectorPane.highlightCitation imperative call), this pushes the line range
  // onto the shared inspectorState `selected` (via the new optional highlightStartLine/highlightEndLine
  // fields) — UnifiedChatView's own inspectorState subscription derives `readingDocPath` +
  // `readingHighlightRange` from it and DocumentPane renders the highlight declaratively.
  private onCitationSelect = (ev: Event): void => {
    const detail = (ev as CustomEvent<CitationSelectDetail>).detail;
    if (!detail?.parentDocId) return;
    const filename = detail.parentDocId.split(/[/\\]/).pop() ?? detail.parentDocId;
    setSelected({
      id: detail.parentDocId,
      title: filename,
      path: detail.parentDocId,
      highlightStartLine: detail.startLine,
      highlightEndLine: detail.endLine,
    });
  };

  constructor() {
    super();
    this.apiBase = '';
    this.surfaces = [];
    this.activeId = null;
    this.dragActive = false;
    this.dragKind = null;
    this.userConfig = getUserConfig();
    this.copyUrlFeedback = null;
    this.uiMode = getUiMode();
  }

  static styles = css`
    :host {
      display: grid;
      /* Tempdoc 813 §5c — the rail track reads its width from the ONE --rail-width token
         (tokens.css) instead of an inline literal, so a viewport-docked overlay can reserve the
         same band the rail actually occupies rather than re-deriving it. */
      grid-template-columns: var(--rail-width) 1fr;
      grid-template-rows: 2.5rem auto 1fr 1.75rem;
      grid-template-areas:
        'topbar topbar'
        'update update'
        'rail   stage'
        'rail   status';
      width: 100vw;
      height: 100vh;
      box-sizing: border-box;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
      background: var(--surface-1);
    }
    /* Search Thread S5b — the expanded (labels-visible) rail needs its GRID TRACK widened too:
       the Rail's own :host([expanded]) width is clamped by this column otherwise.
       Tempdoc 813 §5c — expressed as an override of the ONE --rail-width token so everything
       that reserves the rail's band (the OverlayHost bottom-left slot) follows the expansion
       automatically; a second literal here would have left the reservation calibrated to the
       collapsed rail and occluded again the moment the user expands it. */
    :host([data-rail-expanded]) {
      --rail-width: 11rem;
    }
    .topbar {
      grid-area: topbar;
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0 1rem;
      border-bottom: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .topbar .brand {
      font-weight: 600;
      color: var(--text-primary);
    }
    .stage {
      grid-area: stage;
      overflow: auto;
    }
    jf-status-deck {
      grid-area: status;
    }
    /* Slice 501 — navigation chrome buttons. */
    .nav-btn,
    jf-control.nav-btn::part(control) {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.5rem;
      height: 1.5rem;
      padding: 0;
      border: none;
      border-radius: 0.25rem;
      background: transparent;
      color: var(--text-secondary);
      cursor: pointer;
      transition: background var(--duration-fast) var(--ease-standard), color var(--duration-fast) var(--ease-standard);
    }
    .nav-btn:hover:not([disabled]),
    jf-control.nav-btn::part(control):hover:not([aria-disabled='true']) {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    /* The back/forward nav buttons are composed jf-controls (596 typed availability): the unavailable
       state is aria-disabled (focusable, reason reachable), not native [disabled]. Dim both forms. */
    .nav-btn[disabled],
    jf-control.nav-btn::part(control)[aria-disabled='true'] {
      opacity: 0.3;
      cursor: default;
    }
    .surface-title {
      font-size: var(--font-size-xs);
      font-weight: 500;
      color: var(--text-primary);
      /* Tempdoc 559 Authority II: this is the page's single <h1> (the canonical
         heading projected from the active surface), so reset default h1 metrics
         and keep it inline in the topbar flex row. */
      margin: 0 0 0 0.25rem;
      line-height: inherit;
    }
    .bookmark-btn.active {
      color: var(--text-tint);
    }
    /* Slice 489 T1/G1 — Copy URL topbar affordance. */
    .topbar .spacer {
      flex: 1;
    }
    /* Tempdoc 738 — the Simple/Detailed segmented toggle (surfaces the uiMode disclosure authority). */
    .ui-mode-toggle {
      display: inline-flex;
      align-items: center;
      gap: 1px;
      padding: 1px;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
    }
    .ui-mode-opt {
      border: none;
      background: transparent;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-xs);
      padding: 0.125rem 0.5rem;
      border-radius: 0.3125rem;
      cursor: pointer;
      transition: background var(--duration-fast) var(--ease-standard), color var(--duration-fast) var(--ease-standard);
    }
    .ui-mode-opt:hover:not(.active) {
      color: var(--text-primary);
    }
    .ui-mode-opt.active {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    .copy-url {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.125rem 0.5rem;
      border-radius: 0.25rem;
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
      transition: background var(--duration-fast) var(--ease-standard), color var(--duration-fast) var(--ease-standard), border-color var(--duration-fast) var(--ease-standard);
    }
    .copy-url:hover {
      background: var(--surface-2);
      color: var(--text-primary);
    }
    .copy-url:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: 2px;
    }
    .copy-url.copied {
      color: var(--text-tint);
      border-color: var(--accent-tint-45);
    }
    .copy-url.failed {
      color: var(--text-danger);
      border-color: var(--accent-danger-45);
    }
    .empty {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.appUpdateMonitorStop = startAppUpdateMonitor();
    // Tempdoc 629 (#10): start the app-wide auto-lock idle watcher. The fetch is resolved lazily so it
    // works once hostApi_ is bound during mount; until then the tick's fetch rejects and is a no-op.
    this.autoLock_ = startAutoLock((p, i) =>
      this.hostApi_
        ? this.hostApi_.data.fetch(p, i)
        : Promise.reject(new Error('host not ready')),
    );
    // Slice 496 §3.A: listen for navigate-with-context events from surfaces.
    this.addEventListener('navigate-with-context', this.onNavigateWithContext);
    this.addEventListener('citation-select', this.onCitationSelect);
    // Tempdoc 609 §R (T1.3) — overlay components (TaskList, running-job chip) request "return to job"
    // navigation via a document-level event; route it through activateSurface.
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, this.onNavigateToSurface);
    // Tempdoc 662 — construct the ONE multiplexed connection before any consumer that needs
    // it (advisory store, indexing-jobs bridge below; the intent source + action-ledger
    // chrome elements further down this method / in the render template). Publish it via the
    // singleton too, for the one consumer (ActionLedgerView, mounted through the generic
    // SurfaceCatalog dispatcher) that direct property binding can't reach.
    if (!this.shellEventsMultiplex) {
      this.shellEventsMultiplex = new MultiplexedStream({
        url: `${this.apiBase.replace(/\/$/, '')}${SHELL_EVENTS_STREAM_PATH}`,
      });
      if (typeof EventSource !== 'undefined') {
        this.shellEventsMultiplex.start();
      }
      setSharedShellEventsMultiplex(this.shellEventsMultiplex);
    }
    // Slice 490 Group B4 — construct the advisory store at mount with the
    // shell's apiBase. Children read it via .store property bindings; no
    // singleton, no first-caller-wins race.
    if (!this.advisoryStore) {
      this.advisoryStore = createAdvisoryStore(this.apiBase, this.shellEventsMultiplex ?? undefined);
    }
    // Parallel 508 — AI State Store subscription (main).
    startAiStateStore(this.apiBase);
    // §32 #1 — project live backend indexing jobs into the ambient Task tray
    // (read-only; cancel/retry stay on the core.indexing-jobs Resource view).
    this.indexingJobsBridgeStop = startIndexingJobsBridge(this.apiBase, {
      multiplex: this.shellEventsMultiplex ?? undefined,
    });
    // Tempdoc 840 Phase 5 — same tray, second producer: the staged model acquisition. Read-only
    // (pause/cancel stay on Brain, which owns the ceremony); rides the shared install poller, so it
    // opens no transport of its own.
    this.aiInstallTasksBridgeStop = startAiInstallTasksBridge();
    // Tempdoc 655 — surface a pending authorization created by a caller other than a live
    // frontend request (an MCP tool call) via the same `<jf-authorization-host>` ceremony.
    this.pendingAuthorizationBridgeStop = startPendingAuthorizationBridge(this.apiBase, {
      multiplex: this.shellEventsMultiplex ?? undefined,
    });
    // Tempdoc 550 thesis I (process-spanning ONE log): bridge FE-local effects into the one
    // authoritative backend action-event log, so the activity timeline is one log (no read-time
    // client merge). Always-mounted shell → ingests effects regardless of which surface is open.
    this.effectIngestStop = startEffectIngest({ apiBase: this.apiBase });
    this._aiUnsub = subscribeAiState((s) => {
      const prevActivity = this._aiState?.activity?.state;
      this._aiState = s;
      this.requestUpdate();
      if (s.activity.state !== prevActivity && this.activeId) {
        this.updateDocumentTitle(this.activeId);
      }
    });
    // Tempdoc 543 §21.B — shell commands have collapsed into Actions.
    // registerShellActions installs Actions for the 7 navigation/view
    // entries (replaces the legacy registerShellCommands). Each Action
    // returns a typed Effect; invokeCommand routes to invokeAndApply
    // via the Command→Action resolver in CommandRegistry.ts. Effect
    // Journal records every invocation; PendingEffect can intercept in
    // §21.E.
    registerShellActions({
      navigate: (target) => this.activateSurface(target, {}, 'BUTTON'),
      // Search Thread S2 — focus the one bar: broadcast; the unified window (and
      // any future bar host) listens and focuses its composer textarea.
      focusComposer: () => window.dispatchEvent(new CustomEvent('jf-focus-composer')),
      toggleInspector: () => {
        setInspectorOpen(!getInspectorStateInternal().isOpen);
      },
      togglePalette: () => {
        const palette = this.shadowRoot?.querySelector('jf-command-palette') as CommandPalette | null;
        if (palette) {
          if (palette.open) palette.hide();
          else palette.show();
        }
      },
    });
    // §25.δ2 — register the toggle-audit-log Action. Idempotent (the
    // Action registry guards against duplicates via Map semantics).
    try {
      registerKernelAction({
        id: 'core.action.shell.show-audit-log',
        title: 'Show Effect Audit Log',
        category: 'Diagnostics',
        provenance: CORE_PROVENANCE,
        handler: () => {
          const log = this.shadowRoot?.querySelector(
            'jf-effect-audit-log',
          ) as EffectAuditLog | null;
          if (log) log.open = !log.open;
          return { kind: 'noop' as const };
        },
      });
    } catch {
      /* idempotent — second registration noop */
    }
    // §28.W8 — register the toggle-agent-emitter Action.
    try {
      registerKernelAction({
        id: 'core.action.shell.show-agent-emitter',
        title: 'Show Synthetic Agent Emitter',
        category: 'Diagnostics',
        provenance: CORE_PROVENANCE,
        handler: () => {
          const demo = this.shadowRoot?.querySelector(
            'jf-agent-emitter-demo',
          ) as AgentEmitterDemo | null;
          if (demo) demo.open = !demo.open;
          return { kind: 'noop' as const };
        },
      });
    } catch {
      /* idempotent */
    }
    // §32 U5 — register the toggle-agent-activity Action.
    try {
      registerKernelAction({
        id: 'core.action.shell.show-agent-activity',
        title: 'Show Agent Activity',
        category: 'Diagnostics',
        provenance: CORE_PROVENANCE,
        handler: () => {
          const panel = this.shadowRoot?.querySelector(
            'jf-agent-activity-panel',
          ) as AgentActivityPanel | null;
          if (panel) panel.open = !panel.open;
          return { kind: 'noop' as const };
        },
      });
    } catch {
      /* idempotent */
    }
    // 543-fwd #1 — global undo/redo over the Effect Journal cursor. The
    // handlers dispatch through dispatchEffectToChrome (journal-suppressed) so
    // moving the cursor re-applies side-effects without writing new history.
    // This is the first production trigger for the cursor-based undo + the new
    // redo (the by-originator "Undo AI" buttons are a separate forward-inverse
    // model). A toast names what moved so the keypress isn't silent.
    try {
      registerKernelAction({
        id: 'core.action.shell.undo',
        title: 'Undo',
        category: 'Edit',
        provenance: CORE_PROVENANCE,
        handler: () => {
          const undone = undoLastEffect((e) => dispatchEffectToChrome(e));
          dispatchEffectToChrome({
            kind: 'toast',
            message: undone ? 'Undone' : 'Nothing to undo',
            severity: 'info',
          });
          return { kind: 'noop' as const };
        },
      });
    } catch {
      /* idempotent */
    }
    try {
      registerKernelAction({
        id: 'core.action.shell.redo',
        title: 'Redo',
        category: 'Edit',
        provenance: CORE_PROVENANCE,
        handler: () => {
          const redone = redoLastEffect((e) => dispatchEffectToChrome(e));
          dispatchEffectToChrome({
            kind: 'toast',
            message: redone ? 'Redone' : 'Nothing to redo',
            severity: 'info',
          });
          return { kind: 'noop' as const };
        },
      });
    } catch {
      /* idempotent */
    }
    // commandId uses the legacy 'shell.*' alias; resolveActionIdFromCommandId
    // maps it to 'core.action.shell.*' (same pattern as shell.toggle-palette).
    registerKeybindingEntry({
      key: 'mod+z',
      commandId: 'shell.undo',
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    registerKeybindingEntry({
      key: 'mod+shift+z',
      commandId: 'shell.redo',
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    // Keybinding still references the legacy 'shell.toggle-palette'
    // id; CommandRegistry's invokeCommand resolver maps it to
    // 'core.action.shell.toggle-palette' transparently.
    registerKeybindingEntry({
      key: 'mod+k',
      commandId: 'shell.toggle-palette',
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    // Search Thread S2 — the bar is reachable from anywhere: Ctrl+L always; '/'
    // only outside editable targets (the dispatcher's editable-target guard skips
    // modifier-less bindings when typing).
    registerKeybindingEntry({
      key: 'mod+l',
      commandId: 'shell.focus-composer',
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    // Tempdoc 864 Layer 4 — the modifier-less-printable policy: a bare printable may be a global
    // binding only if a `when` clause scopes it to named surfaces. `/` is the app's only one, and
    // the two named here are the surfaces that OWN a composer. (Was gate-enforced by
    // `scripts/ci/check-printable-keybinding-policy.mjs`, retired 930 chunk H.)
    //
    // What that does and does NOT buy today, stated exactly (864 review F4, closed by PR B): on
    // `core.unified-chat-surface` the key works — `UnifiedChatView` listens for the dispatched
    // `jf-focus-composer` (`views/UnifiedChatView.ts` connectedCallback). `core.search-v3-surface`
    // now listens too (`views/search-v3/SearchV3View.ts` connectedCallback, tempdoc 864 Layer
    // 1(c2)), routed through the same `focusComposer()` every other entry path uses. Both named
    // surfaces have a live listener; the clause's job is unchanged — it stops `/` being swallowed on
    // every OTHER surface.
    registerKeybindingEntry({
      key: '/',
      commandId: 'shell.focus-composer',
      when: "activeSurface == 'core.unified-chat-surface' || activeSurface == 'core.search-v3-surface'",
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    loadPersistedKeybindings();
    this.keybindingTeardown = attachKeybindingDispatcher((commandId) => invokeCommand(commandId));

    // Tempdoc 543 §3.B + §20.7 A0 — Scope substrate consumer via the
    // unified profile pathway. WorkspaceProfile is the SOLE Scope
    // writer; subscribeProfileSwitch handler re-mirrors UserStateDocument
    // changes into the WorkspaceProfile registry then activates the new
    // profile. ShellContext.activeProfile + audience flow through the
    // single activateWorkspaceProfile call site.
    //
    // The mirror + activate are sequenced before the rest of boot so
    // ShellContext is primed before any later subscriber reads it.
    this.scopeProfileSwitchUnsub = subscribeProfileSwitch(() => {
      this.mirrorUserStateProfilesIntoWorkspaceRegistry();
      this.activateUserStateActiveProfile();
    });

    // Tempdoc 543 §13.2.1 — EvaluationContext substrate consumer.
    // Bump scope version on every ShellContext change so projector
    // memo invalidates. subscribeShellContext fires the listener once
    // immediately with the current state; that initial fire is fine
    // (just primes version 1).
    this.evalContextScopeBumpUnsub = subscribeShellContext(() => {
      bumpScopeVersion();
    });

    // Tempdoc 543 §13.2.2 — Effect Journal: hydrate prior-session
    // entries (idempotent — second connect skips).
    restoreJournalFromStorage();
    // §25.β4 — Capability consent: hydrate persisted allow-always /
    // deny decisions (idempotent).
    restoreConsentFromStorage();
    // §25.δ3 — Macros: hydrate persisted macros (each restores as a
    // palette Action under 'core.action.macro.<id>'; idempotent).
    restoreMacrosFromStorage();
    // §25.ζ#6 — register the search-result projector so the
    // EvaluationContext layer has at least one live consumer
    // (idempotent — re-registration replaces with the same reference).
    bootSearchResultProjector();

    // Tempdoc 543 §12.3 #5 — register canonical Operation hover-preview
    // strategy + flip 'hover-preview' dispatch policy to 'merge'.
    // Idempotent (bootstrap.ts internal guard).
    bootstrapAggregateSubstrate();

    // Tempdoc 543 §13.2.3 — install canonical first-party demo
    // ContributionManifest. Idempotent. Proves the declarative path
    // end-to-end (manifest → installer → atomic registrations →
    // listInstalledManifests / getAction). Fire-and-forget; errors
    // surface as console warnings rather than blocking chrome boot.
    installCoreDemoManifest().catch((err) => {
      console.warn('[Shell] installCoreDemoManifest failed', err);
    });
    // 548 §4.3(d) — the welcome walkthrough now ships as a ContributionManifest
    // (migrated out of CorePlugin.register), proving the manifest is the
    // canonical declaration root for a real first-party feature.
    installCoreWalkthroughManifest().catch((err) => {
      console.warn('[Shell] installCoreWalkthroughManifest failed', err);
    });

    // Tempdoc 543 §20.7 A1 — jf-open-pane / jf-close-pane listeners.
    // Slice 7's applyEffect emits these custom events for the
    // open-pane / close-pane Effect kinds; without listeners they
    // dispatch into the void. For paneId === 'inspector' we route to
    // the inspector open/close mechanism. Unknown paneIds emit a
    // diagnostic toast (visible failure, not silent).
    this.openPaneListener = (e: Event) => {
      const detail = (e as CustomEvent<{ paneId?: string }>).detail;
      const paneId = detail?.paneId;
      if (paneId === 'inspector') {
        setInspectorOpen(true);
        return;
      }
      emitEphemeralToast({
        message: `open-pane: unknown paneId '${paneId ?? '(none)'}'`,
        severity: 'warning',
      });
    };
    this.closePaneListener = (e: Event) => {
      const detail = (e as CustomEvent<{ paneId?: string }>).detail;
      const paneId = detail?.paneId;
      if (paneId === 'inspector') {
        setInspectorOpen(false);
        return;
      }
      emitEphemeralToast({
        message: `close-pane: unknown paneId '${paneId ?? '(none)'}'`,
        severity: 'warning',
      });
    };
    document.addEventListener('jf-open-pane', this.openPaneListener);
    document.addEventListener('jf-close-pane', this.closePaneListener);

    // 569 §14 — global presentation-intent Effect listeners (Effect v3). The
    // dispatcher emits these jf-* events; the GLOBAL app authorities live here in
    // the chrome (alongside open-pane). Surface-scoped intents (save-settings,
    // set-search-*) live in their surfaces. A user statechart can thus restyle,
    // switch UI mode, or swap skin through the gated dispatcher with no app-state
    // imported into the Effect substrate. apply-presentation is always re-certified
    // by applyPresentation's conformance floor, so a statechart cannot apply an
    // unsafe skin.
    this.setAppearanceListener = (e: Event) => {
      const d =
        (e as CustomEvent<{ theme?: 'light' | 'dark' | 'system'; highContrast?: boolean }>)
          .detail ?? {};
      void applyAppearance({
        ...(d.theme !== undefined ? { theme: d.theme } : {}),
        ...(d.highContrast !== undefined ? { highContrast: d.highContrast } : {}),
      });
    };
    this.setUiModeListener = (e: Event) => {
      const mode = (e as CustomEvent<{ mode?: string }>).detail?.mode;
      if (mode) setUiMode(mode);
    };
    this.applyPresentationListener = (e: Event) => {
      const id = (e as CustomEvent<{ presentationId?: string }>).detail?.presentationId;
      if (!id) return;
      const decl = listPresentations().find((p) => p.id === id);
      if (!decl) {
        emitEphemeralToast({
          message: `apply-presentation: unknown presentation '${id}'`,
          severity: 'warning',
        });
        return;
      }
      applyPresentation(decl);
    };
    document.addEventListener('jf-set-appearance', this.setAppearanceListener);
    document.addEventListener('jf-set-ui-mode', this.setUiModeListener);
    document.addEventListener('jf-apply-presentation', this.applyPresentationListener);

    // Tempdoc 543 §20.7 A2 — jf-invoke-operation listener routes
    // through OperationClient (the existing operation-invocation
    // plumbing OpButton / RowActions / BrainSurface all use).
    // §32 S2 — bridge: map the effect's `originator` to the backend
    // TransportTag (see originatorToTransport) so the (SourceTier ×
    // RiskTier) trust lattice engages — agent → AGENT_LOOP (UNTRUSTED →
    // write/destructive ops require TYPED_CONFIRM instead of silently
    // auto-firing). The prior 'EFFECT' value was NOT a valid TransportTag
    // and silently degraded to BUTTON (TRUSTED), under-gating agent ops
    // (§32.9.2).
    this.invokeOperationListener = (e: Event) => {
      const detail = (
        e as CustomEvent<{
          operationId?: string;
          args?: Record<string, unknown>;
          originator?: 'user' | 'agent' | 'system';
          journalEntryId?: number;
          consented?: boolean;
        }>
      ).detail;
      const operationId = detail?.operationId;
      if (!operationId) return;
      const transport = originatorToTransport(detail?.originator);
      const client =
        this.operationClient ?? new OperationClient({ apiBase: this.apiBase });
      // §32 R-E1 — track agent-originated operations as long-running tasks so
      // the task list shows in-flight agent work (running → succeeded/failed).
      const taskId =
        detail?.originator === 'agent'
          ? startTask({ label: `Agent operation: ${operationId}` })
          : null;
      // §32 #2B + tempdoc 550 C3 — invokeWithConsent (invoke-first). On the backend's 428
      // trust gate: if the dispatch already carried consent (detail.consented, the legacy
      // confirm-first path) approve unconditionally; otherwise hand the backend-issued
      // PendingAuthorization to the unified ceremony host (`<jf-authorization-host>` via the
      // broker) for a human decision, then approve-by-pendingId + re-invoke. AUTO ops just
      // invoke once.
      void client
        .invokeWithConsent(
          operationId,
          { args: detail.args ?? {}, transport },
          { consented: detail?.consented === true, requestConsent: requestAuthorization },
        )
        .then((result) => {
          if (taskId) completeTask(taskId, 'succeeded');
          // §32 U2 — undoSupported ops return an executionId; associate it
          // with the journal entry so the audit log can offer "Undo"
          // (→ POST /api/undo/{operationId}).
          if (result.executionId && detail.journalEntryId !== undefined) {
            markUndoableOperation(
              detail.journalEntryId,
              operationId,
              result.executionId,
            );
          }
        })
        .catch((err) => {
          if (taskId) completeTask(taskId, 'failed');
          emitEphemeralToast({
            message: operationFailureMessage(err),
            severity: 'error',
          });
        });
    };
    document.addEventListener(
      'jf-invoke-operation',
      this.invokeOperationListener,
    );

    // §32 U2 — jf-undo-operation listener: reverses a backend-undoable
    // operation via OperationClient → POST /api/undo/{operationId}.
    // Dispatched by the audit-log "Undo" affordance for undoSupported ops.
    // Tempdoc 875 §C.7: the reversal now meets the same trust lattice its
    // forward form did, so this uses undoWithConsent — the SAME broker /
    // `<jf-authorization-host>` ceremony the invoke listener above uses. The
    // only op with undoSupported=true is HIGH risk, and both TRUSTED×HIGH and
    // UNTRUSTED×HIGH resolve to TYPED_CONFIRM, so a plain undo() here would
    // 428 on every real undo and surface as an unexplained failure.
    this.undoOperationListener = (e: Event) => {
      const detail = (
        e as CustomEvent<{ operationId?: string; executionId?: string }>
      ).detail;
      const operationId = detail?.operationId;
      const executionId = detail?.executionId;
      if (!operationId || !executionId) return;
      const client =
        this.operationClient ?? new OperationClient({ apiBase: this.apiBase });
      void client
        .undoWithConsent(operationId, executionId, {
          requestConsent: requestAuthorization,
        })
        .catch((err) => {
          emitEphemeralToast({
            message: operationFailureMessage(err, true),
            severity: 'error',
          });
        });
    };
    document.addEventListener('jf-undo-operation', this.undoOperationListener);

    // Tempdoc 543 §13.6 + §20.7 A0 — Workspace Profiles boot.
    //   1. restoreProfilesFromStorage rehydrates registry + factory state.
    //   2. mirror UserStateDocument profiles into the registry (every
    //      legacy profile gets a matching WorkspaceProfile entry with
    //      audience baked into its Scope snapshot).
    //   3. activate the WorkspaceProfile for UserStateDocument's
    //      current activeProfileId — this is the sole Scope writer.
    // The standalone WorkspaceProfile active-id persistence path is
    // bypassed: UserStateDocument's activeProfileId is canonical.
    restoreProfilesFromStorage();
    this.mirrorUserStateProfilesIntoWorkspaceRegistry();
    this.activateUserStateActiveProfile();

    // §11.7 / §13.7 — register one core template demonstrating the
    // TemplateCatalog → CommandRegistry projection. A "Find related
    // to ..." template that uses {primarySelection} when available
    // and otherwise prompts the user. Templates ship as commands;
    // searching the palette for "Find related" surfaces this entry.
    import('../commands/TemplateCatalog.js').then(({ registerTemplate }) => {
      registerTemplate({
        id: 'core.find-related',
        label: 'Find related to selection',
        category: 'Search',
        source: 'core',
        provenance: CORE_PROVENANCE,
        // Only show when a search-hit is selected.
        when: 'selectionKind == search-hit',
        template: 'related to {primarySelection}',
        onInvoke: (expanded) => {
          const encoded = encodeURIComponent(expanded);
          // Search Thread S5b — the standalone Search rail surface is retired; the retrieve tier
          // folded into the one window.
          location.hash = `#justsearch://surface/core.unified-chat-surface?query=${encoded}`;
        },
      });
      // Tempdoc 521 §16.2 deeper — in-tree consumer for the {compute:<expr>} slot, demonstrating the
      // Raycast inter-slot reference form: the user picks a week count, the compute slot projects
      // weeks → days, the resolved literal becomes a `modifiedFromMs` query the IntentRouter plumbed
      // into SearchSurface's date-filter pane (Before/After inputs). Search Thread S5b — that
      // filter pane is retired with the standalone Search surface (it was outside the retrieve
      // tier's declared carry-forward scope: jf-results-card + route chip + scope chips + landing);
      // `core.unified-chat-surface` has no `modifiedFromMs` consumer, so repointing this template's
      // hash target would be a silent no-op (Hard Invariant: never a silent no-op) rather than a
      // working port. Retired outright alongside the feature it demonstrated, not left dangling.
    }).catch(() => { /* swallow */ });

    // §11.6 / §13.6 — Raycast-style core fallback contributions for
    // the palette-no-results context. Plugins extend by contributing
    // emptyStateContributions through PluginContribution.
    import('../commands/EmptyStateRegistry.js').then(({ registerEmptyState }) => {
      registerEmptyState({
        id: 'core.palette.copy-query',
        context: 'palette-no-results',
        priority: 1,
        source: 'core',
        provenance: CORE_PROVENANCE,
        render: (input) => {
          const el = document.createElement('button');
          el.type = 'button';
          el.textContent = `Copy "${input.query ?? ''}" to clipboard`;
          el.addEventListener('click', () => {
            try { void navigator.clipboard?.writeText(input.query ?? ''); } catch { /* */ }
          });
          return el;
        },
      });
      registerEmptyState({
        id: 'core.palette.search-from-here',
        context: 'palette-no-results',
        priority: 2,
        source: 'core',
        provenance: CORE_PROVENANCE,
        render: (input) => {
          const q = input.query ?? '';
          const el = document.createElement('button');
          el.type = 'button';
          el.textContent = `Search documents for "${q}"`;
          el.addEventListener('click', () => {
            // §13 critical-analysis A5: dispatch through IntentRouter
            // so URL projection, telemetry, observability listeners
            // (audit, replay-dedup) all see the navigation. The prior
            // `location.hash =` mutation skipped middleware.
            // Search Thread S5b — the standalone Search rail surface is retired; the retrieve tier
            // folded into the one window.
            this.activateSurface(
              'core.unified-chat-surface',
              { query: q },
              'BUTTON',
            );
            const palette = this.shadowRoot?.querySelector('jf-command-palette') as CommandPalette | null;
            palette?.hide();
          });
          return el;
        },
      });
    }).catch(() => { /* swallow */ });

    if (!this.hostApi_) {
      this.hostApi_ = createHostApi('core', 'CORE', {
        apiBase: this.apiBase,
        registerSurfacePort: () => {},
        navigate: (target) => this.activateSurface(target, {}, 'BUTTON'),
        navigateBack: () => this.goBack(),
        navigateForward: () => {},
        registerCommand: (id, label, handler, labelKey) => {
          // Fallback CORE host: this branch runs in standalone mode when no
          // PluginRegistry wires up a real plugin-scoped HostApi. Real
          // plugin registrations go through PluginRegistry.installPlugin
          // which stamps the manifest's Provenance. Here we stamp CORE.
          // 557 P2: forward the optional labelKey so the palette localizes it.
          registerCommand({
            id,
            label,
            ...(labelKey !== undefined ? { labelKey } : {}),
            source: 'plugin',
            handler,
            provenance: CORE_PROVENANCE,
          });
        },
        registerKeybinding: (key, handler) => {
          // Plugins register a synthetic command and bind to it.
          const commandId = `plugin-keybinding.${key}`;
          registerCommand({
            id: commandId,
            label: `Plugin keybinding (${key})`,
            source: 'plugin',
            provenance: CORE_PROVENANCE,
            handler,
          });
          registerKeybindingEntry({ key, commandId, source: 'plugin', provenance: CORE_PROVENANCE });
        },
        showNotification: (message, options) => {
          emitEphemeralToast({
            message,
            severity: options?.severity ?? 'info',
            durationMs: options?.durationMs,
            actionLabel: options?.actionLabel,
            onAction: options?.onAction,
          });
        },
      });
    }
    this.refreshSurfaces();

    // Tempdoc 508 §3.2 — project operations into the command registry.
    // Subscribe to catalog changes so commands stay in sync if operations
    // are added/removed.
    const projectOps = () => {
      const ops = catalogListOperations();
      if (ops.length === 0 || !this.hostApi_) return;
      // §21.B — projection now goes through Action substrate. Each
      // Operation becomes an Action whose handler returns an
      // `invoke-operation` Effect; A1+A2 listener routes through
      // OperationClient transparently.
      projectOperationsToActions(ops);
    };
    projectOps();
    // §2.A: re-projects on operation-catalog change; by then the i18n catalog
    // (loaded during init, same as surface labels) resolves `ops.*.label`.
    onOperationCatalogChange(projectOps);

    this.dragUnsubscribe = startDragDetect({
      onStateChange: (s) => {
        this.dragActive = s.isDragging;
        this.dragKind = s.dragKind;
      },
      onFolderDrop: (paths) => {
        void this.handleFolderDrop(paths);
      },
    });
    // Search Thread S6 — the inspectorState subscription (the Shell-level `data-inspector-open` grid
    // column + `<jf-inspector-pane>` mount) retired; `UnifiedChatView` now subscribes directly and
    // renders `<jf-document-pane>` in its own conversation-zone column.
    // Slice 471 — userConfig subscribe drives both Stage's override
    // dispatch + the ProvenanceBadge visibility.
    // Slice 472 — also re-filter rail when surfaceVisibility /
    // surfaceOrder change.
    // 687 R5b — narrow reading mount inputs (the wide-layout decision + the shared inspector
    // selection). 798 round 8 — that decision is the reading SURFACE's measured width, not the raw
    // viewport, and it is the same one UnifiedChatView's grid mount reads; the two must stay exact
    // complements or the pane renders twice, or nowhere.
    this.narrowReadingUnsubs = [
      subscribeWide(() => {
        this.wideForReading = isWideLayout();
        this.requestUpdate();
      }),
      subscribeInspector((st) => {
        this.inspectorForReading = st;
        this.requestUpdate();
      }),
    ];
    this.userConfigUnsubscribe = subscribeUserConfig((cfg) => {
      this.userConfig = cfg;
      this.syncRailExpandedAttr();
      this.refreshSurfaces();
    });
    this.syncRailExpandedAttr();
    // Tempdoc 586 F-2 — re-filter the rail live when the Simple/Detailed mode changes (mirrors the
    // surfaceVisibility re-filter above). subscribeUiMode fires immediately, so this also seeds the
    // initial filtered rail consistently with the persisted mode.
    this.uiModeUnsubscribe = subscribeUiMode((m) => {
      this.uiMode = m;
      this.refreshSurfaces();
    });
    // Slice 471 / 469 — re-filter rail when SurfaceCatalog mutates
    // (plugin install/uninstall via mergePluginSurfaceContributions
    // / removePluginSurfaceContributions; or boot-time catalog refresh).
    // Without this, a plugin uninstall leaves the rail showing the
    // plugin's surface until the next userConfig change.
    this.catalogUnsubscribe = onSurfaceCatalogChange(() => {
      this.refreshSurfaces();
    });
    // Slice 501 — Navigation journal subscription + keyboard shortcuts.
    this.journalCanBack = canGoBack();
    this.journalCanForward = canGoForward();
    this.journalUnsubscribe = subscribeJournal(() => {
      this.journalCanBack = canGoBack();
      this.journalCanForward = canGoForward();
    });
    this.savedViewsUnsubscribe = subscribeSavedViews(() => {
      this.updateBookmarkState();
    });
    document.addEventListener('keydown', this.boundKeyHandler, true);
    // Slice 492 — Intent substrate bootstrap.
    //
    // Three-tier wiring per the substrate-completion design:
    //   1. NavigationHandler  — distributes state to stores, sets activeId,
    //                           pushes URL, activates projector.
    //   2. InvocationHandler  — passthrough to OperationClient.invoke.
    //   3. IntentRouter       — thin seam over the two handlers; listener
    //                           fan-out for observability.
    //   4. IntentSources      — URLSource (boot + popstate);
    //                           TauriDeepLinkSource (cold + warm deep-link);
    //                           BackendStreamSource (SSE /api/intent/stream
    //                           consumer — receives LLM-emitted nav intents
    //                           from slice 491's MarkdownUrlExtractor and
    //                           any future agent-loop / MCP / scheduled
    //                           emitters).
    //
    // Bootstrap order matters: register stores FIRST so the schema's
    // storeId bindings resolve to live adapters at hydration time; fetch
    // schemas SECOND so the URLSource's boot-read finds them.
    this.disconnected = false;
    registerCoreStores();
    refreshPromotedAliases();
    this.addEventListener('suggestion-click', ((e: CustomEvent<{ id: string; addressKind: string }>) => {
      const detail = (e as CustomEvent<{ id: string; addressKind?: string }>).detail;
      const target = detail?.id;
      const attemptedId = (e.target as HTMLElement)?.closest('jf-navigation-receipt')?.getAttribute('target');
      if (target && attemptedId && target !== attemptedId) {
        promoteAlias(attemptedId, target);
        refreshPromotedAliases();
      }
      // Tempdoc 550 Authorize face (#6): a 499 "did you mean?" recovery does not stop at
      // learning the alias for next time — the picked suggestion FEEDS THE SAME pending model.
      // Re-dispatching the resolved target through the intent router runs the canonical
      // resolve→authorize pipeline (the gate decides whether the action proceeds or pends),
      // so resolution-recovery and authorization are one ordered flow, not two surfaces.
      // BUTTON transport: a human pressed the suggestion chip (TRUSTED source tier). Only the
      // navigate address kind re-dispatches here — an 'invoke' recovery cannot be replayed
      // without the original arguments, which the receipt does not carry.
      if (target && detail?.addressKind !== 'invoke') {
        this.activateSurface(target, {}, 'BUTTON');
      }
    }) as EventListener);
    const isKnownSurface = (id: string): boolean =>
      listSurfaces().some((s) => s.id === id);
    const operationClient = new OperationClient({ apiBase: this.apiBase });
    this.operationClient = operationClient;
    const navigationHandler = createNavigationHandler({
      setActiveSurface: (id) => {
        // Slice 496 P1+P6 — track previous surface for navigation toast.
        const prev = this.activeId;
        this.activeId = id;
        // Tempdoc 508 §11.1 / §13.1 — drive ShellContext so all four
        // registries (commands, context actions, inspector tabs,
        // keybindings) can scope their entries to the active surface
        // via `when: 'activeSurface == X'`. Synchronous call avoids
        // the async-import race where two rapid navigations could
        // land out-of-order (defect 508 §13 critical-analysis A1).
        updateShellContext({ activeSurface: id });
        // §11.2 / §13.2 — clear selection on surface change.
        // Working assumption: selection is per-surface; navigating
        // away drops it. If a future surface needs per-surface
        // memo, it can re-publish on activation.
        clearSelection();
        // Tempdoc 508 §3.2 source 4 — surface-context commands.
        // Clear when activating a new surface. Each surface can register
        // its own context commands via host_.registration.registerCommand,
        // but those are 'plugin' source. Surface-context source is for
        // shell-internal per-surface commands (none yet; reserved).
        setActiveSurfaceCommands(id, []);
        // Slice 489 T1/G3 — document.title reflects the active surface so
        // bookmarks, browser tabs, and OS task-switcher labels carry the
        // surface context. The lookup uses the catalog's localized
        // labelKey; missing entries fall through to the bare app name.
        // Fires uniformly across all intent sources (URL paste, rail click,
        // Tauri deep-link, backend-emitted intent) because every navigation
        // path funnels through NavigationHandler.setActiveSurface.
        this.updateDocumentTitle(id);
        if (prev && prev !== id) {
          // Tempdoc 609 §R (T1.1) — animate the surface swap. activeId was set synchronously above, so
          // Lit's update is microtask-pending; this captures the before-snapshot now and awaits the flush.
          startSurfaceTransition(this);
          // Q12: the inspector pane holds surface-specific context (e.g. a
          // search hit); close it on a genuine surface change so stale content
          // doesn't persist across surfaces.
          setInspectorOpen(false);
          // Search Thread S5b — the navigation TOAST is retired (replace-with-nothing: navigation is
          // already visible in the rail/title, so no push notification rides along). The
          // `previousActiveId` bookkeeping it used to do as a side effect moves here, in
          // `setActiveSurface` itself, so `goBack`/`navigateBack` (the host API's back action) keeps
          // working without the toast that used to be its only caller of this assignment.
          this.previousActiveId = prev;
        }
        // Tempdoc 855 §11.1 — a REALIZED stage navigation reaches this callback only from the
        // normal (non-MODAL) branch, so the address has moved off the settings window: dismiss it.
        // `dismiss()` and not `requestClose()` — history ALREADY moved (this is exactly the real
        // browser-Back path), and a second `history.back()` would navigate one entry too far.
        const settingsWindow = this.shadowRoot?.querySelector(
          'jf-settings-window',
        ) as SettingsWindow | null;
        if (settingsWindow?.open) settingsWindow.dismiss();
      },
      isKnownSurface,
      // Tempdoc 855 §11.1 — the MODAL branch's injected placement lookup (ShellAddress carries no
      // placement). A MODAL target opens as a window OVER the stage instead of replacing it.
      getPlacement: (id) => getSurface(id)?.placement,
      openModal: (id, _state, info) => {
        // Phase 0 declares exactly one MODAL surface, and one window hosts it. Guarding on the id
        // keeps a future second MODAL surface from silently opening the Settings window.
        if (id !== SETTINGS_SURFACE_ID) return;
        const win = this.shadowRoot?.querySelector('jf-settings-window') as SettingsWindow | null;
        if (!win) return;
        // Tempdoc 855 §11.1 D4 — remember whether THIS open-episode added a history entry. Only the
        // opening navigation can, and a repeat navigation while the window is already open pushes
        // nothing (the D3 duplicate skip), so the flag is captured on the closed→open transition and
        // left alone afterwards.
        if (!win.open) this.settingsOpenPushed = info.pushed;
        win.open = true;
      },
    });
    const invocationHandler = createInvocationHandler({
      client: operationClient,
    });
    this.intentRouter = createIntentRouter({
      navigationHandler,
      invocationHandler,
      isKnownSurface,
      resolveSurface: catalogResolveSurface,
      resolveOperation: catalogResolveOperation,
      // Tempdoc 571 §11 / 578 — when a navigation is redirected member→host, hand the host the
      // requested member so its <jf-surface-tabs> opens that tab (works even if the host is already
      // the active surface — the host subscribes to memberTabIntent). The membership check keeps a
      // plain typo/synonym auto-correct from firing it.
      onRedirect: (originalId, targetId) => {
        if (getSurface(targetId)?.members?.includes(originalId)) {
          requestMemberTab(targetId, originalId);
        }
      },
      // 548 S4-A: a `query` intent is lowered to a navigation of the search surface. Search Thread
      // S5b — the standalone Search rail surface is retired; the retrieve tier folded into the one
      // window (core.unified-chat-surface), so a bare `query` intent now lowers to the SAME surface
      // an `answer` intent does (below) — only the shape/affordance they land in differs.
      // Wire the binding explicitly here (the router also defaults to these) so the
      // surface id + state key are visible at the construction site rather than a
      // hidden default — matches the `core.unified-chat-surface` / `query` used elsewhere
      // in this class and by buildSearchAdapter.
      querySurfaceId: 'core.unified-chat-surface',
      queryStateKey: 'query',
      // 548 §4.5: an `answer` intent is lowered to the shape-hosting chat surface.
      answerSurfaceId: 'core.unified-chat-surface',
      answerStateKey: 'query',
    });
    this.intentRouter.subscribe(resolutionTelemetryListener);
    this.intentRouter.subscribe((intent, outcome) => {
      const t = intent.transport;
      if (t !== 'URL_BAR' && t !== 'URL_DEEPLINK') return;
      if (outcome.status !== 'unresolved' && outcome.status !== 'auto-corrected') return;
      if (!this.shadowRoot) return;
      let toast = this.shadowRoot.querySelector('jf-resolution-toast') as import('../components/chat/ResolutionToast.js').ResolutionToast | null;
      if (!toast) {
        import('../components/chat/ResolutionToast.js');
        toast = document.createElement('jf-resolution-toast') as import('../components/chat/ResolutionToast.js').ResolutionToast;
        this.shadowRoot.appendChild(toast);
        toast.addEventListener('undo-auto-correct', ((e: CustomEvent<{ originalId: string }>) => {
          if (!this.intentRouter) return;
          void this.intentRouter.dispatch(
            { address: { kind: 'navigate', target: e.detail.originalId, state: {} }, transport: 'BUTTON' },
            { recoveryPolicyOverride: strictPolicy },
          );
        }) as EventListener);
      }
      if (outcome.status === 'unresolved') {
        toast.show(outcome.attemptedId, outcome.alternatives);
      } else if (outcome.status === 'auto-corrected') {
        toast.showAutoCorrection(outcome.originalId, outcome.canonicalId);
      }
    });
    // Slice 501 — record navigations in the journal for back/forward,
    // and update bookmark state after every navigation.
    this.intentRouter.subscribe((intent, outcome) => {
      if (intent.address.kind === 'navigate' && (outcome.status === 'dispatched' || outcome.status === 'auto-corrected')) {
        if (!isNavigatingHistoryNow()) {
          const url = canonicalize(intent.address);
          const label = deriveRichLabel(
            intent.address.target,
            url,
            present({ kind: 'surface', id: intent.address.target }).label,
          );
          recordNavigation(intent.address.target, url, label, intent.transport);
        }
        // Update bookmark star after URL projection writes (async — next microtask).
        queueMicrotask(() => this.updateBookmarkState());
      }
    });
    // Inline router-dispatch binding (was previously a 4-line
    // `bindRouterDispatch` helper; removed in the slice-492 follow-up
    // since the indirection had no behavioral benefit).
    const router = this.intentRouter;
    const dispatch = (intent: Parameters<typeof router.dispatch>[0],
                      options?: Parameters<typeof router.dispatch>[1]): void => {
      void router.dispatch(intent, options);
    };
    // Sources are constructed eagerly; their `start(...)` returns a
    // teardown handle that disconnectedCallback runs. URLSource's start
    // is synchronous; TauriDeepLinkSource's is async (Tauri plugin
    // dynamic-import). The Promise branch is race-aware: if
    // disconnectedCallback fires while the Promise is pending, the
    // teardown function is invoked immediately on resolution rather
    // than buried in `sourceTeardowns` after teardown already ran.
    const sources = [
      createURLSource(),
      createTauriDeepLinkSource(),
      // Tempdoc 662: subscribes the intent streamId on the shared multiplexer constructed
      // earlier in this method, instead of opening its own EventSource.
      createBackendStreamSource({ multiplex: this.shellEventsMultiplex! }),
    ];
    void fetchAndRegisterSurfaceSchemas(this.apiBase).then(() => {
      // Surface schemas arrived asynchronously. If we disconnected
      // before that resolved, skip source bootstrap entirely.
      if (this.disconnected) return;
      for (const source of sources) {
        try {
          const teardown = source.start(dispatch);
          if (teardown instanceof Promise) {
            void teardown.then((fn) => {
              if (this.disconnected) {
                try {
                  fn();
                } catch {
                  // teardown errors are non-fatal
                }
              } else {
                this.sourceTeardowns.push(fn);
              }
            });
          } else {
            this.sourceTeardowns.push(teardown);
          }
        } catch (err) {
          console.warn(`[Shell] source ${source.ref} failed to start:`, err);
        }
      }
      // Slice 489 T1/G2 (+G3) boot completion — if neither URLSource nor the
      // Tauri cold-launch path dispatched a navigate intent (i.e., the
      // chrome was opened without a `#justsearch://` hash and without a
      // deep-link argv), dispatch a boot navigate for the rail's default
      // activeId so:
      //   - URL hash becomes the canonical surface URL (G2 projection
      //     starts feeding the URL bar from store state)
      //   - NavigationHandler activates the URLProjector (subscribes to
      //     the schema's stores; subsequent edits debounce-write to URL)
      //   - setActiveSurface fires → updateDocumentTitle ratifies title
      //     through the canonical path (paranoia: refreshSurfaces already
      //     set it; re-running is idempotent)
      const hash = window.location.hash;
      const urlDispatched =
        typeof hash === 'string' && hash.startsWith('#justsearch://');
      if (!urlDispatched) {
        // Tempdoc 609 §R (T2.2) — resume where you left off: on a cold boot with no deep-link, restore the
        // LAST surface (with its saved state) from the persisted NavigationJournal; fall back to the rail
        // default when the journal is empty (a first-ever launch, or after clearing history). The journal
        // survives reload via localStorage, so this is the "reopen into your prior context" behavior.
        const journal = getJournalState();
        const last =
          journal.cursor >= 0 && journal.cursor < journal.entries.length
            ? journal.entries[journal.cursor]
            : journal.entries[journal.entries.length - 1];
        let target = this.activeId;
        let state: StateSnapshot = {};
        if (last) {
          target = last.surfaceId;
          const parsed = parseUrl(last.url);
          if (parsed && parsed.kind === 'navigate') state = parsed.state;
        }
        if (target) {
          dispatch(
            {
              // URL_BAR transport mirrors what URLSource would have stamped if a hash existed; "no URL
              // ingress" + "default/resume boot" sits in the same location-derived intent-source category.
              address: { kind: 'navigate', target, state },
              transport: 'URL_BAR',
            },
            // pushHistory:true is the URLSource convention for a fresh boot.
            { pushHistory: true },
          );
        }
      }
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.appUpdateMonitorStop?.();
    this.appUpdateMonitorStop = null;
    this.autoLock_?.stop();
    this.autoLock_ = null;
    this.removeEventListener('navigate-with-context', this.onNavigateWithContext);
    this.removeEventListener('citation-select', this.onCitationSelect);
    document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, this.onNavigateToSurface);
    this.dragUnsubscribe?.();
    for (const u of this.narrowReadingUnsubs) u();
    this.narrowReadingUnsubs = [];
    this.userConfigUnsubscribe?.();
    this.catalogUnsubscribe?.();
    this.uiModeUnsubscribe?.();
    this.uiModeUnsubscribe = null;
    this.keybindingTeardown?.();
    this._aiUnsub?.();
    this.scopeProfileSwitchUnsub?.();
    this.scopeProfileSwitchUnsub = null;
    this.evalContextScopeBumpUnsub?.();
    this.evalContextScopeBumpUnsub = null;
    // §32 #1 — close the indexing-jobs SSE stream.
    this.indexingJobsBridgeStop?.();
    this.indexingJobsBridgeStop = null;
    // Tempdoc 840 Phase 5 — release the install-poller subscription and clear the mirrored rows.
    this.aiInstallTasksBridgeStop?.();
    this.aiInstallTasksBridgeStop = null;
    // Tempdoc 655 — close the pending-authorization bridge subscription.
    this.pendingAuthorizationBridgeStop?.();
    this.pendingAuthorizationBridgeStop = null;
    this.effectIngestStop?.();
    this.effectIngestStop = null;
    // Tempdoc 543 §20.7 A1/A2 — Effect dispatch listener teardowns.
    if (this.openPaneListener) {
      document.removeEventListener('jf-open-pane', this.openPaneListener);
      this.openPaneListener = null;
    }
    if (this.closePaneListener) {
      document.removeEventListener('jf-close-pane', this.closePaneListener);
      this.closePaneListener = null;
    }
    // 569 §14 — global presentation-intent Effect listener teardowns.
    if (this.setAppearanceListener) {
      document.removeEventListener('jf-set-appearance', this.setAppearanceListener);
      this.setAppearanceListener = null;
    }
    if (this.setUiModeListener) {
      document.removeEventListener('jf-set-ui-mode', this.setUiModeListener);
      this.setUiModeListener = null;
    }
    if (this.applyPresentationListener) {
      document.removeEventListener('jf-apply-presentation', this.applyPresentationListener);
      this.applyPresentationListener = null;
    }
    if (this.invokeOperationListener) {
      document.removeEventListener(
        'jf-invoke-operation',
        this.invokeOperationListener,
      );
      this.invokeOperationListener = null;
    }
    if (this.undoOperationListener) {
      document.removeEventListener(
        'jf-undo-operation',
        this.undoOperationListener,
      );
      this.undoOperationListener = null;
    }
    // Slice 501 — navigation chrome teardown.
    this.journalUnsubscribe?.();
    this.journalUnsubscribe = null;
    this.savedViewsUnsubscribe?.();
    this.savedViewsUnsubscribe = null;
    document.removeEventListener('keydown', this.boundKeyHandler, true);
    // Slice 492 — tear down intent substrate.
    //
    // The flag set BEFORE iterating tells any in-flight async
    // `source.start(...).then(...)` callback to invoke its teardown
    // immediately rather than push it onto `sourceTeardowns` (which
    // we're about to drain). Order: flag → drain → discard.
    this.disconnected = true;
    for (const fn of this.sourceTeardowns) {
      try {
        fn();
      } catch {
        // teardown errors are non-fatal
      }
    }
    this.sourceTeardowns = [];
    this.intentRouter = null;
    deactivateProjection();
    this.advisoryStore?.stop();
    this.advisoryStore = null;
    // Tempdoc 662 — close the multiplexed connection + clear the singleton so a stale
    // instance can't be read by a component mounted after this shell unmounts.
    this.shellEventsMultiplex?.stop();
    this.shellEventsMultiplex = null;
    setSharedShellEventsMultiplex(null);
  }

  /**
   * Slice 492 — canonical surface-activation entry point. Constructs a
   * Navigation Intent and dispatches it through the router; the
   * NavigationHandler distributes state to stores, sets activeId, pushes
   * the URL, and activates the projector. Exactly one path to surface
   * activation: this method, ultimately resolving to
   * NavigationHandler.handle.
   *
   * The `transport` parameter is **required** so callers stamp their
   * origin honestly (slice-492 follow-up fix). Pre-follow-up the method
   * hard-coded `'RAIL'` regardless of caller, which lied about
   * origin to downstream listeners / future audit consumers.
   *
   * Used by:
   *   - Rail click (Shell.handleRailClick → `'RAIL'`).
   *   - Drop-completion redirect (Shell.handleFolderDrop → `'BUTTON'`).
   *   - Future palette / programmatic activations.
   */
  activateSurface(
    surfaceId: string,
    state: StateSnapshot,
    transport: TransportTag,
  ): void {
    void this.intentRouter?.dispatch({
      address: { kind: 'navigate', target: surfaceId, state },
      transport,
    });
  }

  /**
   * Slice 489 T1/G3 — reflect the active surface on `document.title`. Format:
   * `"<label> · JustSearch"` when the surface catalog has a localized
   * label; `"JustSearch"` as a fallback (surface missing from rail set,
   * label catalog not yet fetched, etc).
   */
  private updateDocumentTitle(surfaceId: string): void {
    // §2.A: surface-label resolution (catalog labelKey → id-derived fallback)
    // lives in the one display projector now.
    const label = surfaceId ? present({ kind: 'surface', id: surfaceId }).label : '';
    const activity = this._aiState?.activity?.state;
    const activityPrefix = activity === 'thinking' ? '⟳ ' : activity === 'streaming' ? '● ' : '';
    document.title = label ? `${activityPrefix}${label} · JustSearch` : 'JustSearch';
  }

  /**
   * Tempdoc 855 §11.1 D4 — did the navigation that opened the settings window add a history entry?
   * Set on the closed→open transition from `OpenModalInfo.pushed`; read once when the window asks to
   * close. False for a boot / deep-link entry, where the settings address was already the location.
   */
  private settingsOpenPushed = false;

  /**
   * Move the address OFF the settings window (855 §11.1 D4). Two cases, and picking the wrong one
   * is the defect this replaces:
   *
   *   - the opening navigation pushed an entry → `history.back()`. Popstate re-dispatches the prior
   *     address through the normal branch, restoring URL + projection + stage in one shot.
   *   - it did NOT (boot / deep-link straight onto the settings address) → there is no prior entry
   *     to return to, and `history.back()` would leave the app. Navigate FORWARD to the stage
   *     surface instead: that pushes the canonical stage address, re-runs `setActiveSurface`, and —
   *     the actual bug — activates the stage surface's URL projection, which the MODAL branch never
   *     does, so a boot-entered session was otherwise left with NO projection for its whole life.
   */
  private onSettingsWindowClose(): void {
    if (this.settingsOpenPushed) {
      this.settingsOpenPushed = false;
      window.history.back();
      return;
    }
    // Same default-pick as refreshSurfaces' boot landing: the one interaction window, else the
    // first rail surface.
    const fallback =
      this.surfaces.find((s) => s.id === 'core.unified-chat-surface') ?? this.surfaces[0];
    const target = this.activeId ?? fallback?.id ?? null;
    if (target) this.activateSurface(target, {}, 'BUTTON');
  }

  private goBack(): void {
    if (this.previousActiveId) {
      this.activateSurface(this.previousActiveId, {}, 'BUTTON');
    }
  }

  /**
   * Slice 489 T1/G1 — clipboard copy of the current canonical URL. The
   * topbar button (rendered in render()) wires this. The click handler is
   * the required user gesture for `navigator.clipboard.writeText`.
   */
  private async handleCopyUrlClick(): Promise<void> {
    flushPendingProjection();
    const url = window.location.href;
    const ok = await copyToClipboard(url);
    this.copyUrlFeedback = ok ? 'copied' : 'failed';
    // Auto-clear feedback after a short visual window.
    window.setTimeout(() => {
      this.copyUrlFeedback = null;
    }, 1500);
  }

  /**
   * Tempdoc 738 — surface the app-wide Simple/Detailed disclosure authority (uiModeState) as reachable
   * chrome, so a Simple-mode user can always recover the technical detail the surfaces hide. "Detailed"
   * is the user-facing label for the `advanced` mode.
   */
  private renderUiModeToggle(): TemplateResult {
    // Read the authority LIVE (not the cached `this.uiMode`) so the toggle can never desync from what
    // the surfaces render — e.g. when the async settings seed sets the mode after this component's
    // constructor ran. `this.uiMode` + the subscription remain only to trigger the re-render.
    const mode = getUiMode();
    return html`<div class="ui-mode-toggle" role="group" aria-label="Detail level">
      ${(['simple', 'advanced'] as const).map(
        (m) => html`<button
          type="button"
          class=${mode === m ? 'ui-mode-opt active' : 'ui-mode-opt'}
          aria-pressed=${mode === m ? 'true' : 'false'}
          @click=${() => this.handleUiModeSelect(m)}
        >
          ${m === 'simple' ? 'Simple' : 'Detailed'}
        </button>`,
      )}
    </div>`;
  }

  private handleUiModeSelect(mode: UiMode): void {
    if (this.uiMode === mode) return;
    // Immediate, app-wide: drives the rail-trim (586 F-2) + every 728 disclosure consumer via the store.
    setUiMode(mode);
    // Persist best-effort so the choice survives restart, independent of whether SettingsSurface (which
    // owns the jf-save-settings listener) is mounted — the topbar must not depend on it. Same endpoint +
    // `{ ui: {...} }` body shape SettingsSurface uses (SettingsSurface.saveSettingsListener).
    const data = this.hostApi_?.data;
    if (!data) return;
    void enqueueUiModeSettings((path, init) => data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
      signal: init?.signal ?? undefined,
    }), { ui: { mode } })
      .catch((error: unknown) => {
        this.hostApi_?.ui.showNotification(error instanceof Error ? error.message : 'Could not save detail level.', { severity: 'error' });
      });
  }

  private toggleBookmarksPopover(): void {
    const popover = this.renderRoot.querySelector(
      '#bookmarks-popover',
    ) as BookmarksPopover | null;
    popover?.toggle();
  }

  private handleBookmarkNavigate(url: string): void {
    const addr = parseUrl(url);
    if (addr && addr.kind === 'navigate' && this.intentRouter) {
      void this.intentRouter.dispatch(
        { address: addr, transport: 'BUTTON' },
        { pushHistory: true },
      );
    }
  }

  private currentCanonicalUrl(): string {
    const hash = window.location.hash;
    return hash.startsWith('#') ? hash.slice(1) : '';
  }

  private updateBookmarkState(): void {
    this.isBookmarked = isViewSaved(this.currentCanonicalUrl());
  }

  private handleBookmarkToggle(): void {
    flushPendingProjection();
    const url = this.currentCanonicalUrl();
    if (!url) return;
    if (isViewSaved(url)) {
      const views = getSavedViews();
      const existing = views.find((v) => v.url === url);
      if (existing) removeView(existing.id);
    } else {
      const surfaceId = this.activeId ?? '';
      const label = deriveRichLabel(
        surfaceId,
        url,
        present({ kind: 'surface', id: surfaceId }).label,
      );
      saveView(label || 'Untitled', url, this.activeId ?? '');
    }
    this.updateBookmarkState();
  }

  /**
   * Slice 501 — global keyboard shortcut handler (capture phase).
   * Guards: skip when the reader is typing, by the app's one shared definition of that.
   */
  private handleGlobalKey(e: KeyboardEvent): void {
    // Tempdoc 864 Layer 2(a) — THE shared typing guard (857 PR-A), not a fourth copy of it. This
    // handler carried a private descent that omitted `SELECT` — precisely the omission 857 was
    // written to close on the other copies — while guarding the app's most powerful chords: a `Ctrl+D`
    // or `Alt+←` pressed with a `<select>` focused fired over the reader's type-ahead. The SUBJECT is
    // still "where is focus?" (a capture-phase `event.target` is retargeted to the shadow host and
    // would miss every input in a shadow tree); only the private predicate is gone.
    if (isTypingTarget(deepActiveElement())) return;
    // Tempdoc 864 Layer 2(b) — IME composition and auto-repeat, by the app's ONE definition (a held
    // `Ctrl+D` used to flap the bookmark on and off for as long as the key was down).
    if (shouldIgnoreKeyEvent(e)) return;
    // Tempdoc 864 Layer 2(d) — these chords navigate and mutate; none of them belongs to a reader
    // who is inside a modal. `showModal()` makes the background inert to the POINTER, but this is a
    // `document` listener and keystrokes made inside the dialog still arrive here.
    if (modalOwnsFocus()) return;
    if (e.altKey && e.key === 'ArrowLeft') {
      e.preventDefault();
      if (this.intentRouter) {
        void navigateBack(this.intentRouter.dispatch.bind(this.intentRouter));
      }
    } else if (e.altKey && e.key === 'ArrowRight') {
      e.preventDefault();
      if (this.intentRouter) {
        void navigateForward(this.intentRouter.dispatch.bind(this.intentRouter));
      }
      // Tempdoc 821 §4 — the Ctrl+L branch that used to call handleCopyUrlClick() here was removed:
      // Search Thread S2 reassigned mod+l to shell.focus-composer app-wide (registered with no `when`
      // scoping at Shell.ts:935-940, "focus the bar from anywhere"), and this raw handler ran in
      // parallel with that registered keybinding (neither call site calls stopPropagation), so every
      // Ctrl+L press both dispatched an unconsumed jf-focus-composer event AND copied the URL. Copy
      // URL now has no keyboard chord — click the button.
    } else if (e.ctrlKey && e.key === 'd') {
      e.preventDefault();
      this.handleBookmarkToggle();
    } else if (e.ctrlKey && e.shiftKey && e.key === 'A') {
      e.preventDefault();
      this.handleAskAiShortcut();
    }
  }

  private handleAskAiShortcut(): void {
    const ai = this._aiState;
    if (!ai?.capabilities?.chat) {
      emitEphemeralToast({
        message: 'AI offline — start AI in Brain surface',
        severity: 'warning',
      });
      return;
    }
    this.activateSurface('core.unified-chat-surface', {}, 'BUTTON');
  }

  private async handleFolderDrop(paths: string[]): Promise<void> {
    // Slice 492 — drops are Invocation intents dispatched through the central
    // router. The InvocationHandler wraps OperationClient.invoke and stamps
    // the transport on the X-JustSearch-Transport header (BUTTON is the
    // closest existing TransportTag for drag-drop UI actions; the existing
    // §13 anti-pattern #4 framing — descriptive labels only).
    for (const path of paths) {
      try {
        await this.intentRouter?.dispatch({
          address: {
            kind: 'invoke',
            target: 'core.add-watched-root',
            args: { path },
          },
          transport: 'BUTTON',
        });
      } catch {
        // surface error UX TBD; for V1, drop silently logs
      }
    }
    // Switch to Library so the user sees the new root. Through the canonical
    // activateSurface entry → router → NavigationHandler. BUTTON transport
    // matches the drag-drop UI action category (slice 489 §13 anti-pattern
    // #4 framing — descriptive labels only).
    this.activateSurface('core.library-surface', {}, 'BUTTON');
  }

  private refreshSurfaces(): void {
    const all = listSurfaces();
    // Tempdoc 571 §11 / 578 — host/member composition: a surface declared as a member of a host has
    // its home INSIDE that host (rendered as a tab by <jf-surface-tabs>), so it is excluded from the
    // rail. Membership is the single home-authority — derived from the wire's `members`, never a
    // separately hand-set Placement (so "off-rail" and "routable" stop being two signals to sync).
    const memberIds = new Set(all.flatMap((s) => s.members ?? []));
    // Tempdoc 855 §11 S1 — Settings is NEVER a rail/stage surface, whatever the catalog says. The
    // wire declares MODAL, but a session running against a stale cached catalog (or a plugin catalog
    // that re-declares it RAIL) would otherwise render a SECOND settings button next to the pinned
    // affordance below AND let the Stage mount `jf-settings-surface` a second time, competing with
    // the window's persistent mount (§11.2). The one MODAL surface is excluded unconditionally.
    let railSurfaces = all.filter(
      (s) => s.placement === 'RAIL' && !memberIds.has(s.id) && s.id !== SETTINGS_SURFACE_ID,
    );

    // Tempdoc 507 §6 Phase 5 — layout zone composition: if the active
    // layout specifies which surfaces appear in the rail zone, filter to
    // only those surfaces.
    const layoutId = this.userConfig?.activeLayoutId;
    if (layoutId) {
      const layout = getLayout(layoutId);
      const zoneSurfaces = layout?.zones?.rail?.surfaces;
      if (zoneSurfaces && zoneSurfaces.length > 0) {
        const allowed = new Set(zoneSurfaces);
        railSurfaces = railSurfaces.filter((s) => allowed.has(s.id));
      }
    }

    // Slice 472 — apply userConfig surfaceVisibility filter.
    // Absent entries default to visible; explicit `false` hides.
    const visibility = this.userConfig?.surfaceVisibility;
    if (visibility) {
      railSurfaces = railSurfaces.filter((s) => visibility[s.id] !== false);
    }

    // Tempdoc 586 F-2 — Simple-mode rail trim. The Simple/Detailed toggle (uiModeState) now
    // actually shapes the rail: in Simple mode the advanced/diagnostic surface (the System
    // dashboard) drops off so a non-technical user sees a cleaner set; AI Brain stays
    // (local-model management is user-facing). Detailed restores it. Applied after the
    // visibility filter and before ordering, so altitude-band clamping below still holds.
    // (Tempdoc 855 §5 item 2 — the Theme Editor used to be listed here too, but it is now
    // DEEPLINK-placement, not RAIL, so it never reaches `railSurfaces` in the first place;
    // listing it in this set would have been dead code.)
    if (getUiMode() === 'simple') {
      const hiddenInSimple = new Set(['core.system-surface']);
      railSurfaces = railSurfaces.filter((s) => !hiddenInSimple.has(s.id));
    }

    // Slice 472 — apply userConfig surfaceOrder. Surfaces in the
    // order list keep that order; surfaces not in the list keep
    // catalog order after them. Surfaces in the order list that
    // aren't in the (post-visibility) set are silently skipped
    // (graceful degradation per `archive/source-tempdocs/421-extensibility.md`
    // §"Failure modes" + Eclipse perspective lesson per 470 §B.A.6:
    // user mutations win on restore; missing pane ids drop quietly).
    const order = this.userConfig?.surfaceOrder;
    if (order && order.length > 0) {
      const present = new Set(railSurfaces.map((s) => s.id));
      const ordered: SurfaceCatalogEntry[] = [];
      const seen = new Set<string>();
      for (const id of order) {
        if (present.has(id) && !seen.has(id)) {
          const surface = railSurfaces.find((s) => s.id === id);
          if (surface) {
            ordered.push(surface);
            seen.add(id);
          }
        }
      }
      // Append surfaces not in the order list, in catalog order.
      for (const surface of railSurfaces) {
        if (!seen.has(surface.id)) {
          ordered.push(surface);
        }
      }
      // Tempdoc 571 §4b — the cross-altitude move-ban: honor the user reorder only WITHIN each altitude
      // band. A saved surfaceOrder that interleaves bands cannot smuggle a DIAGNOSTIC surface into the
      // product region; altitude (not user drag) owns band membership. The Rail render derives the final
      // visual bands from the same altitude — this keeps `this.surfaces` itself band-consistent for every
      // consumer (activeId default, secondary surface), not only the rail.
      railSurfaces = clampReorderToAltitudeBands(ordered);
    }

    this.surfaces = railSurfaces;
    this._aiDependentIds = new Set(
      railSurfaces
        .filter((s) => s.consumes?.conversationShapes && s.consumes.conversationShapes.length > 0)
        .map((s) => s.id),
    );
    if (this.surfaces.length > 0 && !this.activeId) {
      // Tempdoc 577 Goal 3 §3.6 — Search is retired as a RAIL peer (DEEPLINK), so it is no longer in
      // `this.surfaces` (rail-only). The boot landing is the ONE interaction window
      // (core.unified-chat-surface), whose `retrieve` affordance is the search entry tier; fall back
      // to the first rail surface. Tempdoc 855 §11.5 — the bottom-pinned exclusion is gone with
      // `BOTTOM_RAIL_IDS`: Settings is MODAL now, so it is not in this RAIL-filtered list at all.
      const home = this.surfaces.find((s) => s.id === 'core.unified-chat-surface');
      this.activeId = (home ?? this.surfaces[0])?.id ?? null;
      // Slice 489 T1/G3 — boot default path bypasses NavigationHandler
      // (URLSource only dispatches when the location actually encodes a
      // surface). The title hook still needs to fire so the initial tab
      // label matches the rendered surface. The activeId-via-router path
      // hits updateDocumentTitle through setActiveSurface; this branch
      // mirrors that.
      if (this.activeId) {
        this.updateDocumentTitle(this.activeId);
      }
    }
  }

  private wideForReading = isWideLayout();
  private inspectorForReading: InspectorState = getInspectorStateInternal();
  private narrowReadingUnsubs: Array<() => void> = [];

  /** 687 R5b — the OverlayHost right-drawer reading mount (narrow viewports only). */
  private renderNarrowReadingPane() {
    const st = this.inspectorForReading;
    if (this.wideForReading || !st.isOpen || !st.selected?.path) return nothing;
    const hl =
      typeof st.selected.highlightStartLine === 'number' &&
      typeof st.selected.highlightEndLine === 'number'
        ? { startLine: st.selected.highlightStartLine, endLine: st.selected.highlightEndLine }
        : null;
    return html`<jf-document-pane
      slot="right-drawer"
      overlay
      api-base=${this.apiBase}
      .docPath=${st.selected.path}
      .highlightRange=${hl}
      @pane-close=${() => setInspectorOpen(false)}
    ></jf-document-pane>`;
  }

  /** S5b — mirror the persisted rail-expanded state onto the host so the grid track widens. */
  private syncRailExpandedAttr(): void {
    if (this.userConfig?.railExpanded) this.setAttribute('data-rail-expanded', '');
    else this.removeAttribute('data-rail-expanded');
  }

  private isRailVisible(): boolean {
    const layoutId = this.userConfig?.activeLayoutId;
    if (!layoutId) return true;
    const layout = getLayout(layoutId);
    if (!layout) return true;
    return layout.zones.rail?.visible !== false;
  }

  /**
   * Tempdoc 508-followup §β3 — status-deck visibility honors
   * {@code layout.zones.statusBar.visible}. Default visible when the
   * layout omits the key (back-compat with the original hardcoded
   * always-on behavior).
   */
  private isStatusDeckVisible(): boolean {
    const layoutId = this.userConfig?.activeLayoutId;
    if (!layoutId) return true;
    const layout = getLayout(layoutId);
    if (!layout) return true;
    return layout.zones.statusBar?.visible !== false;
  }

  /**
   * Tempdoc 521 §16.7 deeper — split-stage resolution. Returns the
   * splitAxis the active layout requested, or null if the layout uses
   * exclusive stage mode (default). The Stage element renders a single
   * surface when this returns null, two surfaces in a CSS grid when set.
   */
  private resolveSplitAxis(): 'horizontal' | 'vertical' | null {
    const layoutId = this.userConfig?.activeLayoutId;
    if (!layoutId) return null;
    const layout = getLayout(layoutId);
    if (!layout) return null;
    const stage = layout.zones.stage;
    if (!stage || stage.exclusive !== false) return null;
    return stage.splitAxis ?? 'horizontal';
  }

  /**
   * Tempdoc 521 §16.7 deeper — secondary surface lookup. Reads
   * userConfig.secondaryActiveSurface first; falls back to "first rail
   * surface that isn't the primary" so split mode always renders two
   * panes even before the user has explicitly picked a right pane.
   */
  private resolveSecondarySurface(primaryId: string): SurfaceCatalogEntry | null {
    const configured = this.userConfig?.secondaryActiveSurface;
    const wanted = configured ? canonicalSplitSurfaceId(configured) : undefined;
    if (wanted && wanted !== primaryId) {
      const target = getSurface(wanted);
      if (target && isSplitPaneCandidate(target, primaryId)) return target;
    }
    // Tempdoc 521 §22 Phase D — read the curated default off the
    // primary surface's `splitPairing.secondary` declaration. The
    // hard-coded curated map in this method (§21 Phase B) was identity-
    // branching; now the surface contribution declares its preferred
    // pair, so plugin surfaces participate in the same mechanism. The
    // user-pick override above still wins; this is the next-best
    // default before falling back to "first non-primary rail surface."
    const primarySurface = getSurface(primaryId);
    const declaredPair = primarySurface?.splitPairing?.secondary;
    const pairedId = declaredPair ? canonicalSplitSurfaceId(declaredPair) : undefined;
    if (pairedId && pairedId !== primaryId) {
      const paired = getSurface(pairedId);
      if (paired && isSplitPaneCandidate(paired, primaryId)) return paired;
    }
    // Fallback: first rail surface that isn't the primary.
    const fallback = this.surfaces.find((s) => isSplitPaneCandidate(s, primaryId));
    return fallback ?? null;
  }

  private handleRailClick(id: string): void {
    // Slice 492 — rail clicks dispatch through the canonical activation
    // entry; the router + NavigationHandler take it from there. Exactly
    // one path to surface activation.
    this.activateSurface(id, {}, 'RAIL');
  }

  override render(): TemplateResult {
    const active =
      this.surfaces.find((s) => s.id === this.activeId) ??
      listSurfaces().find((s) => s.id === this.activeId);
    const splitAxis = this.resolveSplitAxis();
    const secondary =
      splitAxis !== null && active ? this.resolveSecondarySurface(active.id) : null;
    return html`
      <div class="topbar" role="banner">
        <span class="brand">JustSearch</span>
        ${/* 596 face 1.1 — typed availability, not a suppressed title-on-disabled. When there is
             nowhere to go the reason is REACHABLE (aria-disabled + focus/hover tooltip) on the
             composed jf-control, instead of a `title` the browser hides on a disabled control. */ ''}
        <jf-control
          class="nav-btn"
          label="Go back"
          .availability=${this.journalCanBack
            ? undefined
            : unavailableBecause('No earlier view to go back to')}
          .onActivate=${() => {
            if (this.intentRouter) {
              void navigateBack(this.intentRouter.dispatch.bind(this.intentRouter));
            }
          }}
        >${icon({ name: 'chevron-left', size: 14 })}</jf-control>
        <jf-control
          class="nav-btn"
          label="Go forward"
          .availability=${this.journalCanForward
            ? undefined
            : unavailableBecause('No later view to go forward to')}
          .onActivate=${() => {
            if (this.intentRouter) {
              void navigateForward(this.intentRouter.dispatch.bind(this.intentRouter));
            }
          }}
        >${icon({ name: 'chevron-right', size: 14 })}</jf-control>
        ${/* Tempdoc 609 §R (T1.2) — the navigation-trail menu (read-only projection of NavigationJournal). */ ''}
        <jf-recents-menu
          class="nav-btn"
          .dispatch=${this.intentRouter ? this.intentRouter.dispatch.bind(this.intentRouter) : null}
        ></jf-recents-menu>
        <h1 class="surface-title">${active ? present({ kind: 'surface', id: active.id }).label : ''}</h1>
        <span class="spacer"></span>
        ${this.renderUiModeToggle()}
        <button
          type="button"
          class=${`nav-btn bookmark-btn${this.isBookmarked ? ' active' : ''}`}
          aria-label=${this.isBookmarked ? 'Remove bookmark' : 'Bookmark this view'}
          title=${this.isBookmarked ? 'Remove bookmark (Ctrl+D)' : 'Bookmark this view (Ctrl+D)'}
          @click=${() => this.handleBookmarkToggle()}
        >${icon({ name: 'bookmark', size: 14 })}</button>
        <button
          type="button"
          class="nav-btn"
          aria-label="View bookmarks"
          title="View saved bookmarks"
          @click=${() => this.toggleBookmarksPopover()}
        >${icon({ name: 'chevron-down', size: 12 })}</button>
        <button
          type="button"
          class=${`copy-url${
            this.copyUrlFeedback ? ` ${this.copyUrlFeedback}` : ''
          }`}
          aria-label="Copy current URL"
          title="Copy URL"
          @click=${() => {
            void this.handleCopyUrlClick();
          }}
        >
          ${icon({ name: 'copy', size: 12 })}
          ${this.copyUrlFeedback === 'copied'
            ? 'Copied!'
            : this.copyUrlFeedback === 'failed'
              ? 'Failed'
              : 'Copy URL'}
        </button>
      </div>
      <jf-app-update-banner hidden></jf-app-update-banner>
      ${this.isRailVisible() ? html`<jf-rail
        role=${placementToLandmarkRole('RAIL')}
        aria-label="Surfaces"
        .surfaces=${this.surfaces}
        .active=${this.activeId ?? ''}
        .aiActivity=${this._aiState?.activity?.state ?? 'idle'}
        .aiChatAvailable=${this._aiState?.capabilities?.chat ?? true}
        .aiDependentIds=${this._aiDependentIds}
        .expanded=${this.userConfig?.railExpanded ?? false}
        @rail-select=${(e: CustomEvent<{ id: string }>) =>
          this.handleRailClick(e.detail.id)}
        @rail-expand-toggle=${() => setRailExpanded(!(this.userConfig?.railExpanded ?? false))}
      >
        <!-- Slice 490 §4.D / Group B3 — rail-mounted badge slotted into
             <jf-rail>'s "bottom-chrome" region, honoring Placement.RAIL.
             Store injected directly (Group B4 follow-up). -->
        <jf-advisory-rail-badge
          slot="bottom-chrome"
          .store=${this.advisoryStore}
          @advisory-toggle-inbox=${this.handleAdvisoryToggle}
        ></jf-advisory-rail-badge>
      </jf-rail>` : nothing}
      <div class="stage" role=${placementToLandmarkRole('STAGE')} aria-label="Main content">
        <jf-stage
          .surface=${active ?? null}
          .secondarySurface=${secondary}
          .splitAxis=${splitAxis}
          .userConfig=${this.userConfig}
          .hostApi_=${this.hostApi_}
          .aiAvailable=${this._aiState?.capabilities?.chat ?? true}
          api-base=${this.apiBase}
        ></jf-stage>
      </div>
      ${this.isStatusDeckVisible()
        ? html`<jf-status-deck role=${placementToLandmarkRole('STATUS')} api-base=${this.apiBase}></jf-status-deck>`
        : nothing}
      <!-- Search Thread S6 — the jf-inspector-pane drawer mount retired; jf-document-pane
           renders inside UnifiedChatView's own conversation-zone column instead. -->
      <!-- Tempdoc 559 Authority I: viewport-docked chrome overlays dock into the
           OverlayHost's named slots (the slot owns placement). Modals,
           anchored/hover popovers, drawers and toggled panels follow as separate
           clusters; they remain direct children below for now. -->
      <jf-overlay-host>
        <jf-provenance-badge slot="top-right"></jf-provenance-badge>
        ${/* Tempdoc 577 §2.12 Phase 5 — the "since you last looked" digest moves OFF the top-right
            slot, which collides with the chat affordance bar's posture dial + History toggle (the
            digest's pointer-events:auto box intercepted their clicks — live-audit §2.11 #5). The
            top-center transient slot clears the top-right run controls. */ ''}
        <jf-ai-activity-digest
          slot="top-center"
          api-base=${this.apiBase}
          .multiplex=${this.shellEventsMultiplex}
        ></jf-ai-activity-digest>
        <jf-advisory-toast-host
          slot="top-right"
          .store=${this.advisoryStore}
          .operationClient=${this.operationClient}
        ></jf-advisory-toast-host>
        <jf-task-list slot="bottom-left"></jf-task-list>
        ${this.renderNarrowReadingPane()}
        <jf-pending-effect-queue slot="bottom-right"></jf-pending-effect-queue>
        <!-- center slot: full-screen modals (one shows at a time). -->
        <jf-drag-overlay
          slot="center"
          ?active=${this.dragActive}
          drag-kind=${this.dragKind ?? ''}
        ></jf-drag-overlay>
        <jf-indexing-overlay-host slot="center" api-base=${this.apiBase}></jf-indexing-overlay-host>
        <jf-command-palette slot="center"></jf-command-palette>
        ${/* Tempdoc 855 — the settings window. Declared once here (the palette pattern) and kept
              mounted so the hosted surface stays connected while closed (§11.2). */ ''}
        <jf-settings-window
          slot="center"
          api-base=${this.apiBase}
          .host_=${this.hostApi_ ?? undefined}
          @settings-window-close=${() => this.onSettingsWindowClose()}
        ></jf-settings-window>
        <jf-elicit-host slot="center"></jf-elicit-host>
        <jf-authorization-host slot="center"></jf-authorization-host>
        <jf-macro-dry-run slot="center"></jf-macro-dry-run>
        <jf-plugin-error-overlay slot="top-right"></jf-plugin-error-overlay>
        <jf-agent-emitter-demo slot="bottom-center"></jf-agent-emitter-demo>
        <jf-agent-activity-panel slot="right-drawer"></jf-agent-activity-panel>
        ${/* Tempdoc 561 surface tier: the agent retrospective (Sessions/Timeline/History) folded into
              the one window as a right-drawer panel — replaces the retired standalone agent surface. */ ''}
        <jf-interaction-retrospective-panel
          slot="right-drawer"
          api-base=${this.apiBase}
          role=${placementToLandmarkRole('DRAWER')}
          aria-label="Agent retrospective"
          .host_=${this.hostApi_ ?? undefined}
        ></jf-interaction-retrospective-panel>
        ${/* Tempdoc 565 §3.A: the answer's grounding sources (clickable local passages). */ ''}
        <jf-sources-pane
          slot="right-drawer"
          api-base=${this.apiBase}
          role=${placementToLandmarkRole('DRAWER')}
          aria-label="Answer sources"
          .host_=${this.hostApi_ ?? undefined}
        ></jf-sources-pane>
        ${/* Tempdoc 610 §K: the context inspector ("what the assistant sees"), fed via the
              contextInspectorDrawer store (UnifiedChatView pushes the view). */ ''}
        <jf-context-inspector-pane
          slot="right-drawer"
          role=${placementToLandmarkRole('DRAWER')}
          aria-label="What the assistant sees"
        ></jf-context-inspector-pane>
        <jf-walkthrough-card slot="bottom-left"></jf-walkthrough-card>
        <!-- Slice 490 §4.D — Advisory inbox drawer in the OverlayHost right-drawer slot. -->
        <jf-advisory-inbox-drawer
          slot="right-drawer"
          id="advisory-inbox-drawer"
          role=${placementToLandmarkRole('DRAWER')}
          aria-label="Advisories"
          .store=${this.advisoryStore}
          .operationClient=${this.operationClient}
        ></jf-advisory-inbox-drawer>
        <!-- Tempdoc 599 §16/B1 — per-folder failed-files drill-down (right-drawer). -->
        <jf-failed-jobs-drawer
          slot="right-drawer"
          api-base=${this.apiBase}
          role=${placementToLandmarkRole('DRAWER')}
          aria-label="Failed files"
          .host_=${this.hostApi_ ?? undefined}
        ></jf-failed-jobs-drawer>
      </jf-overlay-host>
      <!-- Tempdoc 508-followup §δ2 — hover-preview overlay. Listens for
           jf-peek-request/dismiss events from the palette (Alt+hover). -->
      <jf-peek
        api-base=${this.apiBase}
        .host_=${this.hostApi_ ?? undefined}
      ></jf-peek>
      <!-- §25.δ2 — Per-action audit log. Hidden until the
           core.action.shell.show-audit-log Action toggles it open. -->
      <jf-effect-audit-log></jf-effect-audit-log>
      <!-- Tempdoc 543 §12.3 #5 — kernel-rendered hover-preview host. -->
      <jf-hover-preview-host></jf-hover-preview-host>
      <jf-bookmarks-popover
        id="bookmarks-popover"
        @bookmark-navigate=${(e: CustomEvent<{ url: string }>) => {
          this.handleBookmarkNavigate(e.detail.url);
        }}
      ></jf-bookmarks-popover>
    `;
  }

  private handleAdvisoryToggle(): void {
    const drawer = this.renderRoot.querySelector(
      '#advisory-inbox-drawer',
    ) as AdvisoryInboxDrawer | null;
    drawer?.toggle();
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-shell')) {
  customElements.define('jf-shell', Shell);
}

// ---------- Rail ----------

export class Rail extends JfElement {
  static properties = {
    surfaces: { type: Array },
    active: { type: String },
    aiActivity: { attribute: false },
    aiChatAvailable: { type: Boolean, attribute: false },
    aiDependentIds: { attribute: false },
    // Search Thread S5b — the rail's collapsed (icon-only, default) / expanded (icon + label)
    // chrome state. `reflect: true` so `:host([expanded])` can widen the rail in CSS.
    expanded: { type: Boolean, reflect: true },
  };

  declare surfaces: SurfaceCatalogEntry[];
  declare active: string;
  declare aiActivity: import('../state/aiStateStore.js').ActivityState;
  declare aiChatAvailable: boolean;
  declare aiDependentIds: Set<string>;
  declare expanded: boolean;

  constructor() {
    super();
    this.surfaces = [];
    this.active = '';
    this.aiActivity = 'idle';
    this.aiChatAvailable = true;
    this.aiDependentIds = new Set();
    this.expanded = false;
  }

  static styles = css`
    :host {
      grid-area: rail;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.375rem;
      padding: 0.5rem 0;
      border-right: 1px solid var(--border-subtle);
      background: var(--surface-1);
      transition: width var(--duration-fast) var(--ease-standard);
    }
    /* Search Thread S5b — expanded (labels-visible) rail: buttons stretch full-width and align
       their content to the leading edge instead of the collapsed icon-only centering. */
    :host([expanded]) {
      align-items: stretch;
      padding: 0.5rem 0.5rem;
    }
    button {
      width: 2.25rem;
      height: 2.25rem;
      border-radius: 0.4rem;
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid transparent;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background var(--duration-fast), color var(--duration-fast);
    }
    :host([expanded]) button {
      width: 100%;
      height: auto;
      min-height: 2.25rem;
      justify-content: flex-start;
      gap: 0.625rem;
      padding: 0.35rem 0.6rem;
    }
    button:hover {
      background: var(--surface-secondary);
      color: var(--text-primary);
    }
    button.active {
      background: var(--accent-tint-16);
      color: var(--text-tint);
      border-color: var(--accent-tint-30);
    }
    button.ai-dimmed {
      opacity: 0.4;
    }
    button.ai-dimmed:hover {
      opacity: 0.7;
    }
    .rail-label {
      font-size: var(--font-size-sm);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .expand-toggle {
      color: var(--text-muted);
    }
    :host([expanded]) .expand-toggle .btn-wrap {
      transform: rotate(180deg);
    }
    .btn-wrap {
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .activity-dot {
      position: absolute;
      top: 2px;
      right: 2px;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--accent-tint);
      animation: pulse 1.5s ease-in-out infinite;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }
    .spacer {
      flex: 1;
    }
    .divider {
      width: 1.25rem;
      border-top: 1px solid var(--border-subtle);
      margin: 0.25rem 0;
    }
    /* Tempdoc 571 — the altitude-derived Diagnostics band: a flex sub-column matching the rail's
       own column gap + a leading divider that visually separates it from the product surfaces. */
    .band {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.375rem;
    }
    .band-divider {
      width: 1.25rem;
      border-top: 1px solid var(--border-subtle);
      margin: 0.25rem 0 0;
    }
  `;

  private select(id: string): void {
    this.dispatchEvent(
      new CustomEvent('rail-select', {
        detail: { id },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private renderButton(s: SurfaceCatalogEntry): TemplateResult {
    const iconName = SURFACE_ICONS[s.id] ?? 'hard-drive';
    const isActive = this.active === s.id;
    const isAiDependent = this.aiDependentIds.has(s.id);
    const dimmed = isAiDependent && !this.aiChatAvailable && !isActive;
    const showDot = isAiDependent && this.aiActivity !== 'idle';
    const classes = [isActive ? 'active' : '', dimmed ? 'ai-dimmed' : ''].filter(Boolean).join(' ');
    // Tempdoc 602 R8 — icon-only rail control: name the intent for surfaces whose
    // glyph under-specifies it (Library's folder-plus reads as "new"); else the label.
    const railName = railAccessibleName(s.id, present({ kind: 'surface', id: s.id }).label);
    return html`
      <button
        class=${classes}
        title=${railName}
        aria-label=${railName}
        aria-current=${isActive ? 'page' : nothing}
        data-surface-id=${s.id}
        data-altitude=${s.altitude ?? 'PRODUCT'}
        @click=${() => this.select(s.id)}
      >
        <span class="btn-wrap">
          ${icon({ name: iconName, size: 18 })}
          ${showDot ? html`<span class="activity-dot"></span>` : nothing}
        </span>
        ${this.expanded ? html`<span class="rail-label">${railName}</span>` : nothing}
      </button>
    `;
  }

  /**
   * Search Thread S5b — the rail-foot chevron toggling collapsed (icon-only) ↔ expanded
   * (icon + label) chrome. Fires `rail-expand-toggle`; Shell owns the persisted userConfig write
   * (mirrors `rail-select`'s dispatch-up-then-Shell-decides shape).
   */
  private renderExpandToggle(): TemplateResult {
    return html`
      <button
        class="expand-toggle"
        title=${this.expanded ? 'Collapse rail' : 'Expand rail'}
        aria-label=${this.expanded ? 'Collapse rail' : 'Expand rail'}
        aria-pressed=${this.expanded ? 'true' : 'false'}
        data-testid="rail-expand-toggle"
        @click=${() =>
          this.dispatchEvent(
            new CustomEvent('rail-expand-toggle', { bubbles: true, composed: true }),
          )}
      >
        <span class="btn-wrap">${icon({ name: 'chevron-right', size: 16 })}</span>
        ${this.expanded ? html`<span class="rail-label">Collapse</span>` : nothing}
      </button>
    `;
  }

  /**
   * Tempdoc 578 §5.6 Phase 4 — the dedicated Help "?" affordance. Help is no longer a rail surface
   * (DEEPLINK), so it is reached via this fixed bottom-chrome button rather than a catalog rail slot.
   * Fires the same `rail-select` path so navigation/active-state work uniformly.
   */
  private renderHelpButton(): TemplateResult {
    const isActive = this.active === HELP_SURFACE_ID;
    return html`
      <button
        class=${isActive ? 'active' : ''}
        title="Help"
        aria-label="Help"
        aria-current=${isActive ? 'page' : nothing}
        data-help-affordance
        @click=${() => this.select(HELP_SURFACE_ID)}
      >
        <span class="btn-wrap">${icon({ name: 'help-circle', size: 18 })}</span>
      </button>
    `;
  }

  /**
   * Tempdoc 855 §9.2 — the fixed Settings affordance. Settings is a MODAL surface now (it opens as
   * a window over the stage), so it is no longer in the catalog-derived rail list; this keeps its
   * bottom-pinned place and fires the same `rail-select` path, so every entry point stays one flow.
   * `aria-haspopup="dialog"` is the honest semantic: the button opens a window, it does not make a
   * page current (which is why the catalog button's `aria-current` has no counterpart here).
   */
  private renderSettingsButton(): TemplateResult {
    const railName = railAccessibleName(
      SETTINGS_SURFACE_ID,
      present({ kind: 'surface', id: SETTINGS_SURFACE_ID }).label,
    );
    return html`
      <button
        title=${railName}
        aria-label=${railName}
        aria-haspopup="dialog"
        data-surface-id=${SETTINGS_SURFACE_ID}
        @click=${() => this.select(SETTINGS_SURFACE_ID)}
      >
        <span class="btn-wrap">
          ${icon({ name: SURFACE_ICONS[SETTINGS_SURFACE_ID] ?? 'settings', size: 18 })}
        </span>
        ${this.expanded ? html`<span class="rail-label">${railName}</span>` : nothing}
      </button>
    `;
  }

  override render(): TemplateResult {
    // Tempdoc 571 §6 — homing is a projection of declared altitude (NOT catalog hand-placement):
    //  • DIAGNOSTIC surfaces (Logs, Health) band together below the product surfaces;
    //  • a TRUST surface (Activity) stays first-class in the product band AND homes adjacent to the
    //    interaction window — order within the band = [up to & incl. the interaction surface] → [TRUST]
    //    → [the rest], so Activity sits right after chat by construction (derived, not hand-reordered).
    // An absent altitude ⇒ PRODUCT (the benign default), so the split degrades safely.
    const altitudeOf = (s: SurfaceCatalogEntry): string => s.altitude ?? 'PRODUCT';
    const diagnostics = this.surfaces.filter((s) => altitudeOf(s) === 'DIAGNOSTIC');
    const productAll = this.surfaces.filter((s) => altitudeOf(s) !== 'DIAGNOSTIC');
    const trust = productAll.filter((s) => altitudeOf(s) === 'TRUST');
    const rest = productAll.filter((s) => altitudeOf(s) !== 'TRUST');
    const anchor = rest.findIndex((s) => s.id === INTERACTION_SURFACE_ID);
    const product =
      trust.length === 0
        ? productAll
        : anchor === -1
          ? [...trust, ...rest]
          : [...rest.slice(0, anchor + 1), ...trust, ...rest.slice(anchor + 1)];
    return html`
      ${product.map((s) => this.renderButton(s))}
      ${diagnostics.length > 0
        ? html`<div
            class="band"
            data-testid="rail-band-diagnostics"
            role="group"
            aria-label="Diagnostics"
          >
            <div class="band-divider" title="Diagnostics"></div>
            ${diagnostics.map((s) => this.renderButton(s))}
          </div>`
        : nothing}
      <div class="spacer"></div>
      <!-- Slice 490 §4.D / Group B3 — "bottom-chrome" slot for rail-mounted
           chrome elements (advisory rail badge). Renders above the help /
           settings divider so the badge is co-located with rail-level
           controls, honoring the slice 490 §4.D Placement.RAIL commitment. -->
      <slot name="bottom-chrome"></slot>
      <div class="divider"></div>
      ${this.renderHelpButton()}
      ${this.renderSettingsButton()}
      <div class="divider"></div>
      ${this.renderExpandToggle()}
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-rail')) {
  customElements.define('jf-rail', Rail);
}

// ---------- Stage ----------

export class Stage extends JfElement {
  static properties = {
    surface: { type: Object },
    // Tempdoc 521 §16.7 deeper — split-stage substrate. When
    // splitAxis is set AND secondarySurface is present, Stage renders
    // two surfaces in a CSS grid. Otherwise behavior is single-surface
    // (back-compat with all existing layouts).
    secondarySurface: { type: Object, attribute: false },
    splitAxis: { type: String, attribute: false },
    apiBase: { attribute: 'api-base', type: String },
    userConfig: { attribute: false },
    hostApi_: { attribute: false },
    aiAvailable: { type: Boolean, attribute: false },
  };

  declare surface: SurfaceCatalogEntry | null;
  declare secondarySurface: SurfaceCatalogEntry | null;
  declare splitAxis: 'horizontal' | 'vertical' | null;
  declare apiBase: string;
  declare userConfig: RendererUserConfig | undefined;
  declare hostApi_: PluginHostApi | null;
  declare aiAvailable: boolean;

  /**
   * Tempdoc 609 (instance-retention) — mounted-surface element cache keyed by resolved surface id.
   * Each visited surface's element instance is minted once and then REUSED for the lifetime of the
   * Stage: across both re-renders of the same surface (so an `aiAvailable` flip flows through the
   * `data-ai-available` attribute with no remount) AND across navigation away-and-back. On navigation
   * Lit removes the old surface's node from the panel (→ `disconnectedCallback`, so its streams /
   * subscriptions tear down — the 578 "one mounted, streams off when hidden" hygiene is preserved) and
   * inserts the target's cached node (→ `connectedCallback`, which re-subscribes). Because the SAME
   * instance is reused, all of a surface's component `@state` (drafts, selection, chat thread, expanded
   * trees, …) survives a tab switch by construction — the class-wide 609 retention fix, with no
   * per-surface externalization. The cache is append-only in the normal case, bounded by the number of
   * distinct surfaces visited (~13 dormant detached elements). Tempdoc 609 §R (P1) adds a memory BACKSTOP:
   * the cache is capped at {@link MAX_RETAINED}, beyond which the least-recently-used *dormant* surface is
   * evicted (see {@link evictDormantLru}). This is a safety cap (like Vue `<keep-alive>`'s `max`), not an
   * everyday path — at ~13 surfaces it never triggers; it only bounds growth if many (e.g. plugin) surfaces
   * appear. The one thing retention does NOT preserve is DOM scroll offset (reset on re-attach), which
   * surfaces save/restore explicitly (e.g. SearchSurface's scroll instance field). Reconnect-safety (each
   * `connectedCallback` may now fire more than once on one instance) is a surface-level contract: subscribe
   * in connect, tear down in disconnect.
   */
  private _surfaceElCache = new Map<string, HTMLElement>();

  /**
   * Tempdoc 609 §R (P1) — retention-cache size cap (memory backstop). Mutable (not `readonly`) so unit
   * tests can lower it to exercise eviction without driving dozens of real surfaces. Generous by default:
   * well above the ~13 core surfaces, so normal use is still effectively append-only.
   */
  static MAX_RETAINED = 24;

  constructor() {
    super();
    this.surface = null;
    this.secondarySurface = null;
    this.splitAxis = null;
    this.apiBase = '';
    this.userConfig = undefined;
    this.hostApi_ = null;
    this.aiAvailable = true;
  }

  static styles = css`
    :host {
      display: block;
      width: 100%;
      height: 100%;
    }
    /* Tempdoc 510 Design A — framework default for AI-dependent surfaces
       when the AI is not currently available. Surfaces inherit the dim
       state without subscribing to AiStateStore themselves. Surfaces that
       want richer messaging can override via their own :host([data-ai-available="false"])
       rules. */
    [data-ai-available='false'] {
      filter: saturate(0.6);
    }
    .empty {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    /* Tempdoc 521 §16.7 deeper — split-stage CSS grid. Two surfaces
       sized 1fr each; gap is a hairline divider users can see. The
       axis (horizontal = side-by-side, vertical = stacked) is set
       via the host([data-split-axis="..."]) attribute. */
    .split {
      display: grid;
      width: 100%;
      height: 100%;
      gap: 1px;
      background: var(--border-subtle);
    }
    .split.horizontal { grid-template-columns: 1fr 1fr; grid-template-rows: 1fr; }
    .split.vertical { grid-template-rows: 1fr 1fr; grid-template-columns: 1fr; }
    .pane {
      background: var(--surface-0);
      overflow: hidden;
      min-width: 0;
      min-height: 0;
    }
    /* Right pane stacks the PanePicker strip on top of the surface body. */
    .right-pane {
      display: flex;
      flex-direction: column;
    }
    .right-pane-body {
      flex: 1;
      min-height: 0;
      overflow: hidden;
    }
  `;

  /**
   * Resolve the active surface for mounting.
   *
   * Slice 471 — surface override dispatch:
   *   1. Start with the rail-selected surface.
   *   2. If userConfig.surfaceOverride[surface.id] is set, look up
   *      the override target in the live SurfaceCatalog.
   *   3. If the override target is present, dispatch to IT instead.
   *   4. If the override target is missing (uninstalled / not yet
   *      loaded), fall through to the original surface — graceful
   *      degradation per `archive/source-tempdocs/421-extensibility.md`
   *      §"Failure modes".
   */
  private resolveSurface(): SurfaceCatalogEntry | null {
    const original = this.surface;
    if (!original) return null;
    const overrideId = this.userConfig?.surfaceOverride?.[original.id];
    if (!overrideId) return original;
    const overrideTarget = getSurface(overrideId);
    return overrideTarget ?? original;
  }

  override render(): TemplateResult {
    const primary = this.resolveSurface();
    // Tempdoc 609 (instance-retention) — the cache is APPEND-ONLY across navigation: a surface's element
    // instance is retained when you navigate away (it leaves the DOM, so `disconnectedCallback` fires and
    // its streams/subscriptions tear down — 578 hygiene intact — but its component `@state` is preserved)
    // and reconnected on return. This makes recoverable task state survive a tab switch for EVERY surface
    // by construction, with no per-surface externalization. Bounded by the number of distinct surfaces
    // visited (~13 dormant detached elements). No prune: pruning would discard the very state we retain.
    // Tempdoc 521 §16.7 deeper — split-stage path. When a secondary
    // surface and a splitAxis are wired, render the two panes in a
    // CSS grid. Falls back to single-surface render whenever either
    // is absent (so layouts that don't set stage.exclusive=false
    // behave identically to before).
    if (primary && this.secondarySurface && this.splitAxis) {
      // Tempdoc 521 §16.7 deeper (Phase B) — right-pane picker. The
      // candidate list is every independent catalog surface that ISN'T the primary. Legacy
      // Ask/Chat/Extract mode addresses normalize to the one Search window and therefore never
      // appear as competing panes; plugin-contributed surfaces still participate automatically.
      // Tempdoc 521 §22 Phase A.1 — derived titles, not raw surface ids.
      // deriveTitleFromSurfaceId turns 'core.library-surface' into
      // 'Library', matching what the rest of the chrome (document title,
      // navigation toast) shows.
      const candidates = listSurfaces()
        .filter((s) => isSplitPaneCandidate(s, primary.id))
        .map((s) => ({
          id: s.id,
          label: present({ kind: 'surface', id: s.id }).label,
        }));
      const onPick = (id: string): void => setSecondaryActiveSurface(id);
      return html`
        <div class="split ${this.splitAxis}">
          <div class="pane">${this.renderOneSurface(primary)}</div>
          <div class="pane right-pane">
            <jf-pane-picker
              .candidates=${candidates}
              selected-id=${this.secondarySurface.id}
              .onPick=${onPick}
            ></jf-pane-picker>
            <div class="right-pane-body">
              ${this.renderOneSurface(this.secondarySurface)}
            </div>
          </div>
        </div>
      `;
    }
    if (!primary) {
      return html`<div class="empty">No surface selected.</div>`;
    }
    return this.renderOneSurface(primary);
  }

  /**
   * Tempdoc 609 §R (P1) — memory backstop for the append-only retention cache. Normally a no-op (the
   * cache holds ~13 dormant elements, well under {@link MAX_RETAINED}); when many surfaces push it over
   * the cap, evict least-recently-used entries (Map iteration is insertion order = LRU-first, since each
   * access re-inserts to the tail). Only DORMANT elements are evictable — `el.isConnected` is skipped, so
   * the live/visible pane(s) can never be dropped. An evicted surface simply rebuilds fresh on next visit
   * (the pre-609 behavior), losing only its retained @state; its `disconnectedCallback` already ran when it
   * detached, so dropping the reference is sufficient teardown (GC reclaims it). Like Vue `<keep-alive>`'s
   * `max` — a safety cap, not an everyday path.
   */
  private evictDormantLru(): void {
    if (this._surfaceElCache.size <= Stage.MAX_RETAINED) return;
    for (const [id, el] of this._surfaceElCache) {
      if (this._surfaceElCache.size <= Stage.MAX_RETAINED) break;
      if (el.isConnected) continue; // never evict the live pane(s)
      this._surfaceElCache.delete(id);
    }
  }

  /** Mount a single surface entry. Extracted from render() so the split
   *  path (tempdoc 521 §16.7 deeper) can call it twice. */
  private renderOneSurface(surface: SurfaceCatalogEntry): TemplateResult {
    // Route surfaces are lazy-loaded (kept out of the eager app entry chunk).
    // On first navigation the custom element isn't defined yet: kick off the
    // dynamic import, show a loading state, and re-render once the element
    // upgrades (whenDefined). See ../views/lazySurfaceRegistry.ts. The default
    // landing surface (jf-unified-chat-view) is eager, so it never hits this path.
    const mountTag = surface.mountTag;
    if (isLazySurface(mountTag) && !customElements.get(mountTag)) {
      void ensureSurfaceLoaded(mountTag);
      void customElements.whenDefined(mountTag).then(() => this.requestUpdate());
      return html`<div class="empty">Loading ${present({ kind: 'surface', id: surface.id }).label}…</div>`;
    }
    // 478 §4.A — preferred dispatch path: catalog's mountSurface()
    // helper. This verifies the factory's DISPATCH_BRAND at runtime
    // (rejects factories not minted by the catalog) AND calls
    // factory.mount() on success. The catalog's mint-site captures
    // the validated mountTag in a closure; the factory encapsulates
    // the customElements.get + new klass() pattern. Stage gets back
    // an HTMLElement directly — no template-string dispatch
    // primitive (unsafeStatic) needed.
    try {
      // Slice 491 F2: mintFactory closes over the surface entry and stamps the
      // shape-id attribute itself when mountTag === 'jf-chat-shape-mount'; no
      // post-mount workaround needed here.
      // Tempdoc 609 (M2) — reuse the cached element for this surface id across re-renders; mint only
      // when absent. Returning the SAME node instance lets Lit keep it in place (no remount).
      // Tempdoc 609 §R (P1) — touch = mark most-recently-used: re-inserting moves the key to the Map's
      // tail (insertion order), so the LRU backstop evicts genuinely-dormant surfaces first and never the
      // active one (it is always the most recently touched).
      let el = this._surfaceElCache.get(surface.id) ?? null;
      if (el !== null) {
        this._surfaceElCache.delete(surface.id);
        this._surfaceElCache.set(surface.id, el);
      } else {
        el = mountSurface(surface, { apiBase: this.apiBase, host_: this.hostApi_ ?? undefined });
        if (el !== null) {
          this._surfaceElCache.set(surface.id, el);
          this.evictDormantLru();
        }
      }
      if (el !== null) {
        // Set every render (not just on mint) so live shell dependencies propagate to cached
        // elements without reconstructing them — the whole point of the M2 cache.
        if (this.apiBase) {
          el.setAttribute('api-base', this.apiBase);
        } else {
          el.removeAttribute('api-base');
        }
        (el as unknown as { host_?: PluginHostApi }).host_ = this.hostApi_ ?? undefined;
        const isAiSurface = surface.consumes?.conversationShapes && surface.consumes.conversationShapes.length > 0;
        if (isAiSurface) {
          el.setAttribute('data-ai-available', String(this.aiAvailable));
        }
        return html`${el}`;
      }
    } catch (err) {
      return html`<div class="empty">
        Surface ${surface.id}: mount failed —
        ${err instanceof Error ? err.message : String(err)}.
      </div>`;
    }
    // Legacy fallback (factory absent — transitional during V1.5.1
    // migration; V1.5.2 marks SurfaceFactory required and removes
    // this branch). Trust chain is the same as before:
    //   1. `tag` is `surface.mountTag` from a Surface record.
    //   2. Catalog populated by boot fetch + plugin contributions.
    //   3. customElements.get() membership-checks the tag (HTML
    //      spec PCEN regex ensures only valid names return truthy).
    //   4. unsafeStatic dispatches the validated tag.
    const tag = surface.mountTag;
    if (!customElements.get(tag)) {
      return html`<div class="empty">Surface element &lt;${tag}&gt; not registered.</div>`;
    }
    // Slice 491 §9.D Phase E (C3) — surfaces that mount via <jf-chat-shape-mount>
    // need a shape-id attribute. Derive it from the surface's declared
    // conversationShapes consumption (the SurfaceConsumes substrate landed in
    // C0 puts the link on the consumer side; here we project it onto the
    // generic mount element). V1 picks the first declared shape; a surface
    // hosting multiple shapes would need a richer placement substrate.
    if (tag === 'jf-chat-shape-mount') {
      const shapeRefs = surface.consumes?.conversationShapes ?? [];
      const shapeId = shapeRefs[0] ?? '';
      // 507/508-merge T2.4 — forward host_ so the inner FreeChatView /
      // AgentView etc. see the host API the surface owner was given.
      return html`<jf-chat-shape-mount
        shape-id=${shapeId}
        api-base=${this.apiBase}
        .host_=${this.hostApi_ ?? undefined}
      ></jf-chat-shape-mount>`;
    }
    const tagStatic = unsafeStatic(tag);
    return staticHtml`<${tagStatic} api-base=${this.apiBase}></${tagStatic}>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-stage')) {
  customElements.define('jf-stage', Stage);
}
