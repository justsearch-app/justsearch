// SPDX-License-Identifier: Apache-2.0
/**
 * Shell V0 public entry point (slice 3a.1).
 *
 * Re-exports the surface that Stage 3a.2+ surface ports consume.
 * Substrate-internal helpers (e.g., layoutDispatch internals) are
 * not re-exported here — consumers should import from this barrel.
 *
 * Side-effect imports (custom-element registrations + theme CSS)
 * are pulled in by the entry-point side-effect import below; a
 * consumer that imports from this barrel transparently gets every
 * `<jf-*>` element registered and the design-token catalog loaded.
 */

// Side-effect: in dev mode, expose plugin-fixture helpers on
// window.__justsearchDevFixtures for E2E specs (slice 447-followup-tier3-tooling
// §B). Tree-shaken in production (gated on import.meta.env.DEV).
import './plugin-api/dev-fixtures.js';

// Side-effect: register every custom element + load default theme.
import './components/Form.js';
import './components/StatusCard.js';
import './components/ActionButton.js';
// 574 Move 3 — the visual-atom tier (jf-status-dot / jf-button / jf-status-badge).
import './components/StatusDot.js';
import './components/Button.js';
import './components/StatusBadge.js';
import './components/Table.js';
import './components/TimeseriesPolyline.js';
import './views/TimeseriesSparkline.js';
import './renderers/controls/TextControl.js';
import './renderers/controls/NumberControl.js';
import './renderers/controls/BooleanControl.js';
import './renderers/controls/EnumControl.js';
import './renderers/controls/DateControl.js';
import './renderers/controls/TimeControl.js';
import './renderers/controls/ObjectControl.js';
import './renderers/controls/ArrayControl.js';
import './renderers/layouts/VerticalLayout.js';
import './renderers/layouts/HorizontalLayout.js';
import './renderers/layouts/GroupLayout.js';
import './renderers/layouts/CategorizationLayout.js';
import './themes/default.css';
// Layer 4 product theme override — aliases shell-v0 semantic tokens
// to the React app's existing token catalog during coexistence
// (slice 3a.1.1). After 3a.8 React decommission this import is
// removed; the framework's default resolutions take over.
import './themes/app-bridge.css';
// Slice 3a.1.4 Phase 3 — Resource-view renderer registry default
// registrations (STATE / EVENT_STREAM / TABULAR / TIMESERIES).
import './renderers/resourceRegistryDefaults.js';
// Route surfaces are LAZY-loaded on first navigation — see
// ./views/lazySurfaceRegistry.ts (loaders) + chrome/Shell.ts renderOneSurface
// (the mount seam that triggers the import). This keeps the route-surface code
// (Library/Help/Brain/Settings/Browse/Health/Log/Activity/Memory) out
// of the eager app entry chunk (the ui-bundle app_main byte budget); they are
// only ever mounted when navigated to. The ONE exception kept eager is the
// default landing surface, so first paint never flashes a loader.
// (Tempdoc 565 §15: the standalone Workflow surface is retired — a workflow run is
// now a MODE of the one window, so it is no longer a lazy route surface.)
// Search is the eager default landing surface, registered transitively by chrome/Shell.ts.
// Tempdoc 560 §25/§26 — the core token-editor surface is retired; the ONE token editor ships as the
// bundled first-party `token-editor` plugin (src/shell-v0/plugins/token-editor/, installed in main.jsx).
// Slice 449 phase 10 — Lit chrome (jf-shell + jf-rail + jf-stage).
import './chrome/Shell.js';
// Slice 471 — surface override channel + provenance badge popover.
import './components/ProvenanceBadge.js';
// Slice 457 — jf-confirm-dialog Lit primitive.
import './components/ConfirmDialog.js';

// Tempdoc 511 §511-followup-D — module-load bootstrap of the
// aggregate-surfacing substrate. Previously each surface called
// `bootstrapAggregateSubstrate()` in its `connectedCallback`; the
// substrate's (Operation, button) / (Resource, list-item) /
// (HealthEvent, activity-row) strategies were registered redundantly
// across 3 surfaces with an implicit "new surface authors must
// remember to call this or the substrate silently no-ops" contract.
//
// ES module evaluation order (depth-first, post-order) guarantees
// that all strategy + component modules transitively imported above
// have finished executing before this top-level call runs. Verified
// by §511-indirect Spike B (probe test importing this barrel asserted
// 3 registered cells without manual bootstrap).
//
// First-mount surfaces are user-triggered via the Stage dispatcher;
// the chrome's `activeId` is `null` until navigation, so there is no
// race between this call and any `connectedCallback`.
import { bootstrapAggregateSubstrate } from './aggregate-substrate/bootstrap.js';
bootstrapAggregateSubstrate();
// Tempdoc 577 Phase 8 (570 Move C) — the core search-result verb-space registers
// through the one ContextActionRegistry seam at the same boot point.
import { registerSearchResultActions } from './commands/searchResultActions.js';
registerSearchResultActions();
export { confirmAsync, ConfirmDialog } from './components/ConfirmDialog.js';

// Shell (Lumino-backed dock + pane registry)
export { Shell } from './shell/Shell.js';
export type {
  PaneDescriptor,
  AddPaneOptions,
  SerializedLayout,
  SerializedAreaConfig,
  SerializedTabAreaConfig,
  SerializedSplitAreaConfig,
} from './shell/Shell.js';
export { LitWidget } from './shell/LitWidget.js';
export type { LitWidgetOptions } from './shell/LitWidget.js';

// Generic shell components
export { Form, setAtPath } from './components/Form.js';
export type { FormChangeEventDetail } from './components/Form.js';
export { StatusCard } from './components/StatusCard.js';
export type { StatusCardSeverity } from './components/StatusCard.js';
export { ActionButton } from './components/ActionButton.js';
export type {
  Risk,
  ActionInvokeEventDetail,
} from './components/ActionButton.js';
export { Table, deriveColumnsFromSchema } from './components/Table.js';
export type {
  TableColumn,
  RowClickEventDetail,
} from './components/Table.js';
export { TimeseriesPolyline } from './components/TimeseriesPolyline.js';
export { TimeseriesSparkline } from './views/TimeseriesSparkline.js';

// Streaming substrate
export {
  EnvelopeStream,
  type EnvelopeReducer,
  type EnvelopeStreamConfig,
  type EnvelopeStreamSnapshot,
  type EnvelopeStreamListener,
} from './streaming/EnvelopeStream.js';
export type {
  SseEnvelope,
  LifecycleKind,
  LifecyclePayloadBase,
  UpdatePayloadBase,
} from './streaming/envelope-types.js';
export {
  isLifecycleEnvelope,
  isUpdateEnvelope,
} from './streaming/envelope-types.js';

// Capabilities handshake
export { CapabilitiesHandshake } from './handshake/CapabilitiesHandshake.js';
export type {
  CapabilitiesListener,
  CapabilitiesHandshakeConfig,
} from './handshake/CapabilitiesHandshake.js';
export type {
  PrimitiveDescriptor,
  I18nCapability,
  StreamingEnvelopeCapability,
  ServerCapabilities,
  CapabilitiesView,
  CapabilitiesSnapshot,
} from './handshake/capabilities-types.js';
// Renderer registry (slice 3a.0 — re-exported for consumer access)
export {
  getRendererRegistry,
  rendererRegistry,
  dispatchRenderer,
  RANK_BASIC_CONTROL,
  RANK_LAYOUT,
  RANK_SPECIALIZED_CONTROL,
  RANK_STRUCTURAL_CONTROL,
} from './renderers/registry.js';
export type {
  RendererRank,
  RendererTester,
  RendererEntry,
} from './renderers/registry.js';

// Resource-view renderer registry (slice 3a.1.4 Phase 3) — typed-shape-
// based dispatch parallel to the JSON Forms renderer registry above.
export {
  registerResourceRenderer,
  dispatchResourceRenderer,
  isCategorySupported,
  getResourceRendererRegistry,
  clearResourceRendererRegistry,
  CATEGORIES,
} from './renderers/resourceRegistry.js';
export type {
  Category,
  ResourceRendererEntry,
} from './renderers/resourceRegistry.js';

// Renderer contract (slice 3a.1.5 — normative reference for plugin authors)
export { JsonFormsRendererBase } from './renderers/JsonFormsRendererBase.js';
export type {
  RendererProps,
  RendererOnChange,
} from './renderers/JsonFormsRendererBase.js';

// User-config dispatch + render overrides (slice 3a.1.7)
export type {
  RendererUserConfig,
  PaneUserConfig,
  DensityVariant,
} from './renderers/userConfig.js';

// Slice 471 — userConfig runtime singleton + surface override mutators.
// Slice 472 — extends with surfaceVisibility + surfaceOrder mutators.
export {
  getUserConfig,
  subscribeUserConfig,
  setSurfaceOverride,
  clearSurfaceOverride,
  clearAllSurfaceOverrides,
  setSurfaceVisibility,
  clearSurfaceVisibility,
  setSurfaceOrder,
  clearAllLayoutOverrides,
} from './state/userConfigState.js';
export { ProvenanceBadge } from './components/ProvenanceBadge.js';

// Slice 474 alpha — Theme Manifest (palette themes via CSS injection).
export {
  applyTheme,
  clearActiveTheme,
  getActiveThemeId,
  loadAndApplyTheme,
  subscribeActiveTheme,
} from './state/themeState.js';

// Plugin API (V1 contract scaffold)
export * from './plugin-api/index.js';

// Settings surface: `<jf-settings-surface>` self-registers from views/SettingsSurface.ts (no
// barrel export needed — mounted via the catalog like any other surface). `SettingsLitView`
// (slice 3a-2-b, the old React-coexistence path) was deleted in tempdoc 855 P1's orphan sweep:
// zero consumers beyond its own test + this export.

// Ledger surface — slice 3a-2-d Ledger half DELETED in slice 3a.1.9
// Phase 7. Mount via <jf-resource-view resource-id="core.operation-history">
// instead. The substrate's EVENT_STREAM × kind="operation-history"
// specialty renderer (registered in resourceRegistryDefaults) dispatches
// to <jf-table>, replacing what LedgerLitView did bespoke.

// Crash list surface (slice 3a-2-d Crash list half — failed-jobs table)
// CrashListLitView — DELETED in slice 3a.1.9 §B.B.D Stream A. Mount via
// <jf-resource-view resource-id="core.failed-indexing-jobs"> instead.
// The substrate's TABULAR × ONE_SHOT strategy + /api/indexing-jobs/failed
// endpoint replace the bespoke poll loop.

// Operation invocation substrate (slice 3a-1-2)
export {
  OperationClient,
  OperationError,
  type OperationInvocationRequest,
  type OperationInvocationSuccess,
  type OperationClientConfig,
} from './operations/OperationClient.js';
export {
  wireActionButton,
  type WireActionButtonOptions,
} from './operations/wireActionButton.js';
