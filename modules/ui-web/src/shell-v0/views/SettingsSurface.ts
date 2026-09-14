// SPDX-License-Identifier: Apache-2.0
/**
 * SettingsSurface — Lit-side Settings surface, hosted in the centered `<jf-settings-window>`
 * modal (tempdoc 855). ONE component owns the entire centralized lifecycle (loads/subscriptions,
 * §9.3) across every category; the ACTIVE category (declared in `views/settingsRegister.ts`, the
 * single settings register) selects which of its `render*()` methods render, via
 * `<jf-settings-nav>`'s vertical grouped nav + accordion + scroll-spy instead of the retired
 * horizontal `<jf-surface-tabs>` presentation.
 *
 * Full functional parity with the pre-855 flat page: Interface (mode), Appearance (theme),
 * Accessibility (density · high contrast · motion), Keyboard (default action), Desktop autostart
 * (Tauri-only), Reset to defaults via `core.reset-settings`, Delete all data (Tauri-only,
 * dangerous) — now reachable as per-category pages instead of one long scroll.
 *
 * Persists settings via POST /api/settings/v2 (matches Library + Brain
 * patterns). Reset routes through OperationClient.
 *
 * Side-effect registers `<jf-settings-surface>` for the chrome dispatcher.
 */

import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { captureSettingsPatch, saveAbsoluteSettings, type SettingsPatch } from '../../api/settingsAttempt.js';
// Tempdoc 883 D-A.7 — the override field must resync to STATE, not to Lit's memo of the last
// committed value: a rejected/floored/collapsed write leaves the DOM value the user typed, and a
// plain `.value=` binding sees no change and skips the update. `live()` compares against the live
// DOM property, which is the only comparison that can notice.
import { live } from 'lit/directives/live.js';
import { JfElement } from '../primitives/JfElement.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
// Tempdoc 511 §511-followup-B: reset-settings now routes through
// `<jf-operation>` → ActionButton → OperationClient internally; the
// surface no longer needs a direct OperationClient handle. Tauri-only
// affordances (autostart, delete-all-data) now route through
// host_.platform.capabilities so the legacy isTauriRuntime import was
// dropped as part of the surface migration.
import { icon } from '../components/Icon.js';
// §2.A: rail-customization labels resolve through the one surface-label
// authority — never the raw `core.*-surface` id.
import { present } from '../display/present.js';
// Tempdoc 883 D-A.7 — the context-window readout projects the SAME `core.ai.contextWindow` fact
// Brain renders (the one Display-value authority, 594 §9.2), so the two cannot word it differently.
import { projectFact } from '../display/facts.js';
import { formatCount } from '../display/format.js';
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { localizeResourceKey, onCatalogUpdated } from '../../i18n/resourceCatalog.js';
import { applyAppearance, getSurfaceMode, setSurfaceMode } from '../state/themeState.js';
// Tempdoc 874 — the Search v3 chat-column width preset (FE-only, user-state document).
import { getChatWidth, setChatWidth, type ChatWidth } from '../state/chatWidthState.js';
import {
  enqueueUiModeSettings,
  setUiMode,
  getUiMode,
  getUiModeRevision,
  subscribeUiMode,
} from '../state/uiModeState.js';
// 569 Move 1/3 — the body-tier apply path: a real region rendered from a declaration.
import {
  subscribePresentation,
  activeBodyFor,
  activeInteractionFor,
  type ActivePresentation,
} from '../state/presentationRuntime.js';
import {
  SETTINGS_INTERFACE_REGION,
  APPEARANCE_FLOW,
  CONFIRM_CEREMONY,
  THEME_VARIANT_DARK,
  THEME_VARIANT_LIGHT,
} from '../themes/builtinPresentations.js';
// 569 §14 — run the appearance behaviour as a declared statechart (Move 8 operative).
import { createMachine, type InteractionMachine } from '../substrates/interaction/index.js';
import { auditAndQuarantine } from '../state/runtimeConformance.js';
// 569 §19 Phase 6 — the presentation AUTHORING UI (paste-JSON / generate / fork) moved out of
// Settings into the dedicated `jf-presentation-editor-surface`; Settings keeps only the declared
// Interface-region render (the Move-3 keystone projection), not the authoring affordances.
import { applyAdaptationProfile, getAdaptationProfile } from '../state/adaptationProfile.js';
import '../components/DeclaredSurface.js';
import type { SurfaceChangeEventDetail } from '../components/DeclaredSurface.js';
// Tempdoc 543 §20.7 B1 — schema-driven form via x-ui-renderer dispatch.
import '../components/Form.js';
import '../components/AutonomyDial.js';
import '../components/StatusBadge.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
// Tempdoc 855 §15.2/§17 R2 — the shared switch atom + the radiogroup renderer's plain-props path
// (both consumed directly by hand-authored templates below, not through JsonForms).
import '../components/Switch.js';
// Tempdoc 855 §15.2 — the discrete-slider atom (Density's ordinal-scale control shape).
import '../components/DiscreteSlider.js';
import type { DiscreteSliderStep } from '../components/DiscreteSlider.js';
import '../renderers/controls/OptionButtonGroupRenderer.js';
import type { OptionButtonGroupOption } from '../renderers/controls/OptionButtonGroupRenderer.js';
import type { FormChangeEventDetail } from '../components/Form.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { listLayouts } from '../layout/LayoutManifest.js';
import {
  listAvailableThemes,
  type ThemeCatalogEntry,
} from '../themes/themesCatalog.js';
import {
  listSurfaces,
  getSurface,
  mountSurface,
  removePluginSurfaceContributions,
} from '../../api/registry/SurfaceCatalogClient.js';
import { ensureSurfaceLoaded, isLazySurface } from './lazySurfaceRegistry.js';
// Tempdoc 855 §9.3/§9.5 — the settings window's vertical grouped nav, driven by the one
// declared register (replaces the retired horizontal `<jf-surface-tabs>` presentation).
import '../components/SettingsNav.js';
import {
  SETTINGS_REGISTER,
  allCategories,
  findCategory,
  firstCategoryId,
  type SettingsCategory,
  type SettingsSectionEntry,
} from './settingsRegister.js';
import { deriveFocus, type Landmark } from '../primitives/navigation.js';
import { viewportWindow } from '../primitives/scrollViewport.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import type { Surface } from '../../api/types/surface.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';
import {
  getSessionPluginRegistry,
  pluginDeclaration,
  type InstalledPlugin,
} from '../plugin-api/index.js';
import {
  getViewerAudience,
  setViewerAudience,
  subscribeViewerAudience,
} from '../state/viewerAudienceState.js';
// Allowlisted in eslint.config.js — see 511-followup-B. The audience
// toggle UI needs the `Audience` union directly for radio-button
// rendering; mounting an aggregate component would be a worse fit.
import type { Audience } from '../../api/types/registry.js';
// Tempdoc 511 §511-followup-B: core.reset-settings mounted via the
// canonical (Operation, button) cell. The wire policy (RiskTier.MEDIUM,
// ConfirmStrategy.Inline, Audience.OPERATOR) drives ceremony — the
// confirm modal + manual `client.invoke` here are replaced.
import '../aggregate-substrate/components/JfOperation.js';
import type { OpErrorEventDetail } from '../components/OpButton.js';
import {
  checkForAppUpdate as checkForAppUpdateFromHost,
  installAppUpdate as installAppUpdateFromHost,
  refreshAppUpdateStatus,
  subscribeAppUpdate,
  type AppUpdateStatus,
} from '../state/appUpdateState.js';

interface UISettings {
  mode?: 'simple' | 'advanced';
  theme?: 'system' | 'dark' | 'light';
  highContrast?: boolean;
  defaultAction?: 'open' | 'reveal' | 'preview';
  vimMode?: boolean;
  pauseIndexingDuringAi?: boolean;
}

/**
 * Tempdoc 883 D-A.7 — the LLM slice of `GET/POST /api/settings/v2` this surface reads and writes.
 * `contextWindow` is the wire name; `SettingsPatch.merge` maps it onto
 * `UiSettings.contextLength`, where `0` means AUTO (the window is derived at launch) and a positive
 * value is an explicit override floored at 512.
 */
interface LLMSettings {
  contextWindow?: number;
}

interface AllSettings {
  ui?: UISettings;
  llm?: LLMSettings;
  settingsMode?: string;
}

export class SettingsSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    ui: { state: true },
    // Tempdoc 883 D-A.7 — the LLM settings slice (today: the context-window override).
    llm: { state: true },
    // Tempdoc 883 D-A.7 — the live AI snapshot, for the DERIVED context-window readout. The
    // window is not a stored preference, so the readout's source is the running engine, not `llm`.
    aiSnapshot: { state: true },
    readOnly: { state: true },
    saving: { state: true },
    autostart: { state: true },
    autostartLoaded: { state: true },
    updateStatus: { state: true },
    updateBusy: { state: true },
    // Tempdoc 778 — the default-on local feedback-capture flag + its privacy note.
    feedbackCaptureEnabled: { state: true },
    feedbackPrivacyNote: { state: true },
    error: { state: true },
    deleting: { state: true },
    // 569 §15 (Move 8) — the delete ceremony's declared statechart state + the typed-confirm input.
    deleteState: { state: true },
    confirmText: { state: true },
    // Slice 477 H1 — V1.5 user-authorship state
    activeThemeId: { state: true },
    userConfig: { state: true },
    railSurfaces: { state: true },
    // Tempdoc 855 §9.3 — the active settings-window CATEGORY (was `activeTab`): a native category
    // id from `settingsRegister.ts`, or a member category's catalog surface id.
    activeCategory: { state: true },
    // Tempdoc 855 §9.5 — the scroll-spy's derived in-view sub-anchor within the active category.
    activeAnchor: { state: true },
    plugins: { state: true },
    // Tempdoc 560 §28 — URL-loaded plugins that came back UNTRUSTED, keyed by id → source url,
    // so the operator can approve-and-trust them (fetch + hash + reload as TRUSTED on approval).
    untrustedLoads: { state: true },
    // Tempdoc 560 §28 (4d) — the operator's durable allow-always grants (operation + family).
    durableGrants: { state: true },
    // Tempdoc 560 §28 Phase 3 — the run-tier witness: live composed contributions (read-only).
    witnessEntries: { state: true },
    // Tempdoc 511-followup Track A
    viewerAudience: { state: true },
    // Tempdoc 543 §20.7 B6 — WorkspaceProfile registry snapshot for
    // the developer affordance.
    workspaceProfiles: { state: true },
    // §25.ζ#4 — selected parent profile id for the new-profile snapshot
    // affordance. Empty string means "flat profile, no inheritance".
    snapshotInheritsFrom: { state: true },
    // §28.W3 — recorded consent grants for the plugin-permissions panel.
    consents: { state: true },
    // 569 Move 1/3 — active presentation (drives which regions render from a declaration).
    presentation: { state: true },
    // Tempdoc 567 §8 (deferred → built) — custom-theme management drafts (host-owned, editor-independent):
    // the import paste-box visibility + JSON draft, and the inline-rename target id + label draft.
    themeImporting: { state: true },
    themeImportDraft: { state: true },
    renamingThemeId: { state: true },
    renameDraft: { state: true },
    // Tempdoc 567 §9.4 — the glass/solid surface-mode toggle (FE-only pref, mirrors high-contrast).
    surfaceMode: { state: true },
    // Tempdoc 874 — the Search v3 chat-column width preset (FE-only pref, mirrors surfaceMode).
    chatWidth: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  declare ui: UISettings;
  declare llm: LLMSettings;
  declare aiSnapshot: AiState | null;
  declare readOnly: boolean;
  declare saving: boolean;
  declare autostart: boolean | null;
  declare autostartLoaded: boolean;
  declare updateStatus: AppUpdateStatus | null;
  declare updateBusy: boolean;
  // Tempdoc 778 — null until loaded from GET /api/feedback/capture; default-on locally.
  declare feedbackCaptureEnabled: boolean | null;
  declare feedbackPrivacyNote: string;
  declare error: string | null;
  declare deleting: boolean;
  // 569 §15 (Move 8) — the BRANCHING delete-confirm ceremony, run as a declared statechart.
  declare deleteState: string;
  declare confirmText: string;
  // Slice 477 H1 — V1.5 user-authorship state
  declare activeThemeId: string | null;
  // Tempdoc 567 §8 (deferred → built) — custom-theme import/rename drafts.
  declare themeImporting: boolean;
  declare themeImportDraft: string;
  declare renamingThemeId: string | null;
  declare renameDraft: string;
  declare surfaceMode: 'glass' | 'solid';
  declare chatWidth: ChatWidth;
  declare userConfig: RendererUserConfig;
  declare railSurfaces: Surface[];
  /** Active settings-window category id: a native category id, or a member surface id. */
  declare activeCategory: string;
  /** The scroll-spy's derived in-view sub-anchor within the active native category, or null. */
  declare activeAnchor: string | null;
  declare plugins: InstalledPlugin[];
  // Tempdoc 560 §28 — pending operator-approval candidates (URL-loaded + UNTRUSTED), id → source url.
  declare untrustedLoads: Map<string, string>;
  // Tempdoc 560 §28 (4d) — durable allow-always grants the trust gate honors (operation + family).
  declare durableGrants: ReadonlyArray<{ kind: string; target: string; sourceTier: string }>;
  // Tempdoc 560 §28 Phase 3 — run-tier witness rows (kind/id/owner/buildWitnessed) from the live registry.
  declare witnessEntries: ReadonlyArray<{
    kind: string;
    id: string;
    owner: string | null;
    buildWitnessed: boolean;
  }>;
  // Tempdoc 511-followup Track A
  declare viewerAudience: Audience;
  // Tempdoc 543 §20.7 B6 — developer affordance: cached list of
  // WorkspaceProfile registry entries for the dev switcher.
  declare workspaceProfiles: ReadonlyArray<{ id: string; label: string }>;
  declare snapshotInheritsFrom: string;
  declare consents: ReadonlyArray<{
    contributorId: string;
    capability: string;
    decision: 'allow-once' | 'allow-always' | 'deny';
    decidedAt: string;
  }>;
  // 569 Move 1/3 — the active body/layout presentation tiers.
  declare presentation: ActivePresentation;
  private consentUnsub: (() => void) | null = null;
  private presentationUnsub: (() => void) | null = null;
  // 569 Move 6 — guard so the apply-time runtime audit runs once per applied presentation.
  private lastAuditedPresentationId: string | null = null;

  // Slice 477 H1 — subscription cleanup handles
  private themeUnsub: (() => void) | null = null;
  // 569 §14 — save-settings Effect listener (the appearance statechart persists through it).
  private saveSettingsListener: ((e: Event) => void) | null = null;
  // 569 §14 — the appearance behaviour, run as a declared statechart through the gated dispatcher.
  private appearanceMachine: InteractionMachine | null = null;
  // 569 §15 — the BRANCHING delete-confirm ceremony (CONFIRM_CEREMONY), run as a declared statechart.
  private deleteMachine: InteractionMachine | null = null;
  private deleteUnsub: (() => void) | null = null;
  private userConfigUnsub: (() => void) | null = null;
  private catalogUnsub: (() => void) | null = null;
  private memberTabUnsub: (() => void) | null = null;
  // Tempdoc 511-followup Track A
  private viewerAudienceUnsub: (() => void) | null = null;
  // Tempdoc 883 D-A.7 — shared AI-snapshot subscription (the derived context-window readout).
  private aiStateUnsub: (() => void) | null = null;
  // Tempdoc 543 §20.7 B6 — WorkspaceProfile registry subscription.
  private workspaceProfilesUnsub: (() => void) | null = null;
  private appUpdateUnsub: (() => void) | null = null;
  // Tempdoc 855 §9.5 — scroll-spy observers over the active category's content pane.
  private anchorScrollEl: HTMLElement | null = null;
  private anchorResizeObserver: ResizeObserver | null = null;
  private anchorScrollRaf = false;
  // Tempdoc 855 §9.3 — mount-on-activation cache for a MEMBER category (mirrors
  // `SurfaceTabs.renderPanel`'s pattern: reuse across re-renders of the SAME category, recreate on
  // a category switch so the previous member's streams tear down on disconnect).
  private _activeMemberEl: HTMLElement | null = null;
  private _activeMemberElId: string | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined as unknown as PluginHostApi;
    this.ui = {};
    this.llm = {};
    this.aiSnapshot = null;
    this.readOnly = false;
    this.saving = false;
    this.autostart = null;
    this.autostartLoaded = false;
    this.updateStatus = null;
    this.updateBusy = false;
    this.feedbackCaptureEnabled = null;
    this.feedbackPrivacyNote = '';
    this.error = null;
    this.deleting = false;
    this.deleteState = 'idle';
    this.confirmText = '';
    // Slice 477 H1
    this.activeThemeId = null;
    // Tempdoc 567 §8 (deferred → built)
    this.themeImporting = false;
    this.themeImportDraft = '';
    this.renamingThemeId = null;
    this.renameDraft = '';
    this.surfaceMode = getSurfaceMode();
    this.chatWidth = getChatWidth();
    this.userConfig = {} as RendererUserConfig;
    this.railSurfaces = SettingsSurface.railSurfacesForCustomization();
    this.activeCategory = firstCategoryId();
    this.activeAnchor = null;
    this.plugins = getSessionPluginRegistry().list();
    this.untrustedLoads = new Map();
    this.durableGrants = [];
    this.witnessEntries = [];
    // Tempdoc 511-followup Track A
    this.viewerAudience = getViewerAudience();
    // Tempdoc 543 §20.7 B6 — initialize empty; populated lazily on
    // connectedCallback (dynamic import keeps the substrate lazily-
    // loaded for non-DEVELOPER audiences).
    this.workspaceProfiles = [];
    this.snapshotInheritsFrom = '';
    this.consents = [];
    this.presentation = { id: null, bodies: {}, layout: null, interaction: {} };
  }

  static styles = [
    css`
    /* Tempdoc 855 §9.1 — Settings is a chrome-hosted MODAL content component: display:contents
       pass-through (layout-purity) so the hosting <jf-settings-window> frame controls sizing. */
    :host {
      display: contents;
    }
    /* Tempdoc 855 §9.3/§9.5 — the Discord-2025 IA: a fixed (non-scrolling) header, then a
       nav | content two-pane shell. Only .settings-content-pane scrolls; the nav scrolls its own
       .groups list internally when the category tree overflows a short window. */
    .settings-root {
      height: 100%;
      display: flex;
      flex-direction: column;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    .settings-shell {
      flex: 1;
      min-height: 0;
      display: flex;
      overflow: hidden;
    }
    .settings-content-pane {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
    }
    .settings-content-pane.member {
      display: flex;
      flex-direction: column;
    }
    /* §2 measured spec — the 728px reading column centered in the remaining pane. */
    .settings-content-inner {
      max-width: 728px;
      margin: 0 auto;
      padding: 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    .empty-member {
      padding: 1.5rem;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .header {
      flex-shrink: 0;
      background: var(--surface-1);
      padding: 1rem 1.5rem;
      border-bottom: 1px solid var(--border-subtle);
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
    }
    .header .subtitle {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    /* 574 B2 — plugin-trust + session-only status pills are the jf-status-badge atom now;
       the per-surface .badge base/.ok/.danger fork is deleted. */
    button {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      background: var(--surface-primary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.4rem 0.75rem;
      cursor: pointer;
      font-size: var(--font-size-sm);
    }
    button:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    /* 574 B1 — generic action buttons are the jf-button atom now; the .primary/.danger fork
       is deleted. The base button{} + .option-btn/.card/.rail-arrow rules below stay for the
       bespoke selectable-option + card-picker affordances (a distinct pattern, not the action
       button base). */
    /* Tempdoc 855 §15.1 — the flat row idiom (T1): sections sit on the page background, no card
       chrome. .section is a plain container; a hairline divider separates one section from the
       next (skipped for the FIRST section on the page — the pane's own padding is the top gap). */
    .section {
      padding: 0;
      background: transparent;
      border: none;
      border-radius: 0;
    }
    .section:not(:first-child) {
      margin-top: 1.5rem;
      padding-top: 1.5rem;
      border-top: 1px solid var(--border-subtle);
    }
    .settings-content-inner > div:first-of-type > .section:first-child {
      margin-top: 0;
      padding-top: 0;
      border-top: none;
    }
    .section h3 {
      margin: 0 0 0.75rem 0;
      font-size: var(--font-size-md);
      font-weight: 700;
      letter-spacing: normal;
      text-transform: none;
      color: var(--text-primary);
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    /* Composite-content exception (855 §15.1/§15.5): the handful of sections whose content is
       genuinely composite (the theme swatch grid, the plugin list, delivered contributions,
       workspace profiles, the declared Interface region) keep a LIGHTER contained panel instead of
       going fully flat — a subtle background, not the pre-remediation card's border+radius+padding
       combo. Everything else (toggle rows, pickers, the rail list, plugin permissions, …) is fully
       flat like every other section. */
    .section.section-composite {
      padding: 0.875rem;
      background: var(--surface-secondary);
      border-radius: 0.5rem;
    }
    .row {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
    }
    /* Tempdoc 855 §15.1 — the settings-row idiom: bold label + muted description left, a compact
       control (switch/select) inline-right; ~36px min row height, dividers between adjacent rows
       within a section (the §2 measured-spec row rhythm). renderSettingRow() is the one render
       helper that emits this markup; .column is the full-width-below variant for a radio-group /
       slider / swatch grid control. Reuses the pre-855 .toggle-row name (many call sites already
       matched this shape) rather than forking a second class for the identical CSS. */
    .toggle-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 1rem;
      min-height: 2.25rem;
      padding: 0.5rem 0;
    }
    .toggle-row + .toggle-row {
      border-top: 1px solid var(--border-subtle);
    }
    .toggle-row.column {
      flex-direction: column;
      align-items: stretch;
    }
    .toggle-label {
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    .toggle-desc {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    /* 855 §17 R1 — the cross-link row's affordance (see renderRelatedSettingsRow). */
    .link-row {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.25rem 0.5rem;
      border: none;
      border-radius: 0.375rem;
      background: transparent;
      color: var(--text-link);
      font-family: inherit;
      font-size: var(--font-size-sm);
      cursor: pointer;
    }
    .link-row:hover {
      background: var(--surface-tertiary);
    }
    .option-btn {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: transparent;
      color: var(--text-primary);
      cursor: pointer;
    }
    .option-btn:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    .option-btn.selected {
      border-color: var(--accent-tint);
      background: var(--accent-tint-08);
      color: var(--text-tint);
    }
    .option-label {
      font-size: var(--font-size-sm);
      font-weight: 500;
      margin-top: 0.25rem;
    }
    .option-desc {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    /* Tempdoc 855 §15.3 (T3) — the theme swatch grid: small two-tone tiles (Discord's Color Themes
       idiom) replacing the old text-card list. .theme-composite is the section-composite exception
       (a real grid of paint swatches is exactly the "composite content" the flat idiom carves out). */
    .theme-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 1rem;
    }
    .theme-tile-wrap {
      display: flex;
      flex-direction: column;
      align-items: center;
      width: 4.5rem;
    }
    .theme-tile {
      width: 3.5rem;
      height: 3.5rem;
      padding: 0;
      border: 1px solid var(--border-subtle);
      border-radius: 0.75rem;
      background: var(--surface-tertiary);
      cursor: pointer;
      position: relative;
      overflow: visible;
    }
    .theme-tile:hover:not(:disabled) {
      border-color: var(--border-strong, var(--border-subtle));
    }
    .theme-tile.selected {
      outline: 2px solid var(--accent-tint);
      outline-offset: 2px;
    }
    .theme-swatch {
      position: absolute;
      inset: 0;
      border-radius: inherit;
      overflow: hidden;
    }
    .theme-swatch-accent {
      position: absolute;
      right: 0;
      bottom: 0;
      width: 62%;
      height: 62%;
      border-top-left-radius: 0.625rem;
    }
    .theme-tile-neutral {
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-tertiary);
    }
    .theme-tile-check {
      position: absolute;
      bottom: -0.3rem;
      right: -0.3rem;
      display: flex;
      border-radius: 50%;
      background: var(--surface-primary);
      color: var(--text-tint);
    }
    .theme-tile-label {
      margin-top: 0.375rem;
      font-size: var(--font-size-xs);
      text-align: center;
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .theme-tile-actions {
      display: flex;
      gap: 0.125rem;
      margin-top: 0.125rem;
    }
    /* Inline-rename row replaces the tile while a custom theme is being renamed. */
    .custom-theme-renaming {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      width: 100%;
      padding: 0.375rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
    }
    .theme-rename-input {
      flex: 1;
      min-width: 0;
      padding: 0.375rem 0.5rem;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      font-size: var(--font-size-sm);
    }
    /* Import-from-JSON affordance (host-owned, editor-independent). */
    .theme-import {
      margin-top: 0.5rem;
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }
    .theme-import-json {
      width: 100%;
      box-sizing: border-box;
      padding: 0.5rem;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      font-family: var(--font-mono);
      font-size: var(--font-size-xs);
      resize: vertical;
    }
    .theme-import-actions {
      display: flex;
      gap: 0.5rem;
    }
    .theme-import-toggle {
      margin-top: 0.5rem;
    }
    select {
      padding: 0.375rem 0.5rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
    }
    p.help {
      margin: 0.5rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      line-height: 1.5;
    }
    /* Slice 477 H1 — V1.5 user-authorship sections */
    .rail-list,
    .plugin-list {
      list-style: none;
      padding: 0;
      margin: 0;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .rail-row {
      display: grid;
      grid-template-columns: auto 1fr auto auto;
      align-items: center;
      gap: 0.5rem;
      padding: 0.375rem 0.5rem;
      border: 1px solid transparent;
      border-radius: 0.25rem;
    }
    .rail-row:hover {
      border-color: var(--border-subtle);
      background: var(--surface-hover);
    }
    .rail-label {
      font-size: var(--font-size-sm);
      font-family: ui-monospace, monospace;
    }
    /* 574 B1 — the reorder arrows are jf-button(ghost,icon); their skin is the atom's. The
       .rail-arrow class is retained on the element only as a query hook (no skin rules). */
    .plugin-row {
      display: flex;
      gap: 0.75rem;
      padding: 0.5rem 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      align-items: flex-start;
      justify-content: space-between;
    }
    .plugin-meta {
      flex: 1;
      min-width: 0;
    }
    .plugin-id {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-family: ui-monospace, monospace;
      font-size: var(--font-size-sm);
    }
    .plugin-version {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    .plugin-display {
      font-size: var(--font-size-sm);
      margin-top: 0.125rem;
    }
    .plugin-extras {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
    }
  `,
  ];

  private uiModeUnsub: (() => void) | null = null;
  /** Tempdoc 855 fix-round F2 (S2) — see SettingsNav's identical field: `localizeResourceKey`
   *  falls back to the raw key on a cold deep-link boot; re-render when the catalog updates so
   *  labels resolve once the backend fetch settles instead of staying raw. */
  private catalogUpdatedUnsub: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this.catalogUpdatedUnsub = onCatalogUpdated(() => this.requestUpdate());
    // Tempdoc 874 — a retained instance re-attaches showing the preset the document holds NOW, not
    // the one it was constructed with (the window can be closed, the preference changed elsewhere).
    this.chatWidth = getChatWidth();
    // Tempdoc 738 — reflect external Simple/Detailed changes (e.g. the topbar toggle, which writes the
    // same uiModeState store) in the Interface section's selected state; the section renders from the
    // live getUiMode() so the two controls cannot disagree.
    this.uiModeUnsub = subscribeUiMode((m) => {
      // Keep this.ui.mode (which the declared Interface option-group binds to) in sync with the store,
      // so a topbar Simple/Detailed change updates the Settings toggle's selected state too.
      if (this.ui.mode !== m) this.ui = { ...this.ui, mode: m };
      this.requestUpdate();
    });
    // Tempdoc 571 §11 / 578 / 855 §9.3 — if reached via a member deep-link (Skins/Editor →
    // redirected here), open that member's category. A member category's id IS the catalog surface
    // id (`settingsRegister.ts`), so the intent's memberId selects it directly — no translation.
    // Drain a pending intent (mounting now) AND subscribe (member deep-link while THIS host is
    // already active still switches the category).
    const requested = takeMemberTabIntent('core.settings-surface');
    if (requested) this.activeCategory = requested;
    this.memberTabUnsub = subscribeMemberTab((hostId, memberId) => {
      if (hostId !== 'core.settings-surface') return false;
      this.activeCategory = memberId;
      return true;
    });
    // 559 Authority VI (slack/fill) — the reading-column fill policy IS available
    // (SurfaceLayout's `:host([data-fill='reading'])` + `--surface-content-max-width`),
    // but Settings does NOT adopt it: full-bleed is the current default. A measured
    // live A/B (559 Appendix A, "the reading-column tradeoff") found centering only
    // a partial win — it halves the 1.8k→1k px label↔control travel but leaves a
    // still-large gap and ~43% empty side margin — so adoption is deferred pending a
    // narrower-measure / row-regroup decision. To re-enable: setAttribute('data-fill','reading').
    // Tempdoc 511 §511-followup-D: aggregate-substrate bootstrap
    // moved to module-load in `shell-v0/index.ts`. By the time this
    // callback fires, all canonical strategies are already
    // registered.
    void this.loadSettings();
    void this.loadFeedbackCapture();
    if (this.host_.platform.capabilities.has('native-notifications')) {
      void this.loadAutostart();
      this.appUpdateUnsub = subscribeAppUpdate((status) => {
        this.updateStatus = status;
      });
      void this.loadAppUpdateStatus();
    } else {
      this.autostartLoaded = true;
    }
    this.themeUnsub = this.host_.theme.subscribeActiveTheme((id) => {
      this.activeThemeId = id;
    });
    this.userConfigUnsub = this.host_.layout.subscribeUserConfig((cfg) => {
      this.userConfig = cfg as unknown as RendererUserConfig;
    });
    // Tempdoc 511-followup Track A — viewer-audience store
    this.viewerAudienceUnsub = subscribeViewerAudience((a) => {
      this.viewerAudience = a;
    });
    // Tempdoc 883 D-A.7 — the shared always-on AI snapshot feeds the DERIVED context-window
    // readout. No second poll: this is the same store Brain and the status bar read.
    this.aiStateUnsub = subscribeAiState((s) => {
      this.aiSnapshot = s;
    });
    // 569 Move 1/3 — track the active presentation so the Interface region re-renders
    // through the engine when a declaration is applied (and reverts when cleared).
    this.presentationUnsub = subscribePresentation((p) => {
      this.presentation = p;
    });
    this.catalogUnsub = this.host_.layout.onSurfaceCatalogChange(() => {
      this.railSurfaces = SettingsSurface.railSurfacesForCustomization();
      this.plugins = getSessionPluginRegistry().list();
    });
    // Tempdoc 543 §20.7 B6 — populate WorkspaceProfile list lazily.
    void import('../substrates/profiles/index.js').then(
      ({ listProfiles: listWorkspaceProfiles, subscribeProfiles }) => {
        this.workspaceProfiles = [...listWorkspaceProfiles()];
        // Refresh on registry changes.
        const unsub = subscribeProfiles(() => {
          this.workspaceProfiles = [...listWorkspaceProfiles()];
        });
        // Stash for disconnect — store on the instance for teardown.
        this.workspaceProfilesUnsub = unsub;
      },
    );
    // §28.W3 — populate consent grants list lazily + subscribe to changes.
    void import('../substrates/consent/index.js').then(
      ({ listAllConsents, subscribeConsent }) => {
        this.consents = [...listAllConsents()];
        this.consentUnsub = subscribeConsent(() => {
          this.consents = [...listAllConsents()];
        });
      },
    );
    // 569 §14 — save-settings Effect listener: the appearance statechart (Move 8)
    // persists through this rather than calling fetch imperatively. Carries the
    // POST body verbatim ({ ui: {...} }) so the surface owns the endpoint/shape.
    this.saveSettingsListener = (e: Event) => {
      const settings = (e as CustomEvent<{ settings?: Record<string, unknown> }>).detail?.settings;
      if (!settings || this.readOnly) return;
      // This surface owns the persist lifecycle the statechart's save-settings edge triggers:
      // optimistic apply already happened (set-appearance effect); persistence is best-effort.
      let patch: SettingsPatch;
      try {
        patch = captureSettingsPatch(settings);
      } catch (err) {
        this.error = err instanceof Error ? err.message : String(err);
        return;
      }
      this.saving = true;
      const send = (path: string, init?: RequestInit) => this.doFetch(path, init);
      const writesMode = patch.ui != null && 'mode' in patch.ui;
      void (writesMode ? enqueueUiModeSettings(send, patch) : saveAbsoluteSettings(send, patch))
        .catch((err: unknown) => {
          this.error = err instanceof Error ? err.message : String(err);
        })
        .finally(() => {
          this.saving = false;
        });
    };
    document.addEventListener('jf-save-settings', this.saveSettingsListener);

    // 569 §14 — build the appearance behaviour machine from the ACTIVE presentation's
    // interaction tier (a user may re-author it), falling back to the built-in flow. The
    // default gated dispatcher routes every edge-effect through the 550 trust seam +
    // journals it — so the surface's behaviour IS the declared statechart, not imperative
    // code. (SETTINGS_DECLARED is boot-applied before this surface mounts, so the active
    // tier already carries APPEARANCE_FLOW.)
    this.appearanceMachine = createMachine(
      activeInteractionFor(APPEARANCE_FLOW.id) ?? APPEARANCE_FLOW,
    );

    // 569 §15 (Move 8) — the BRANCHING delete-confirm ceremony: a multi-state, GUARDED statechart
    // (idle → confirming → done; the CONFIRM edge guarded by `typed == true`) drives a real
    // destructive flow. The surface renders the confirm state from `deleteState` and runs the
    // bespoke Tauri delete (the §7 effect body) on entering `done`; the chart's declared effect is
    // the closed `toast`, dispatched + journaled through the same gated 550 seam.
    this.deleteMachine = createMachine(
      activeInteractionFor(CONFIRM_CEREMONY.id) ?? CONFIRM_CEREMONY,
    );
    this.deleteUnsub = this.deleteMachine.subscribe((state) => {
      this.deleteState = state;
      if (state === 'done') void this.runDelete();
    });

    // Tempdoc 560 §28 (4d) — load the operator's durable allow-always grants for the panel.
    void this.loadDurableGrants();
    void this.loadWitness();
  }

  /** Tempdoc 609 — settle transient state on hide: in-flight ops, errors, and the DESTRUCTIVE
   *  delete-confirm CEREMONY (the working rule's "transient confirmations" — a half-confirmed delete must
   *  not survive a tab switch). The theme-import paste draft + inline rename draft are user DRAFT work
   *  (recoverable), so they are deliberately KEPT — resetting them would re-introduce the draft-loss 609
   *  fixes. Settings data + activeTab are also untouched. */
  protected override settleTransients(): void {
    this.saving = false;
    this.deleting = false;
    this.error = null;
    this.deleteState = 'idle';
    this.confirmText = '';
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.uiModeUnsub?.();
    this.uiModeUnsub = null;
    this.catalogUpdatedUnsub?.();
    this.catalogUpdatedUnsub = null;
    this.themeUnsub?.();
    this.userConfigUnsub?.();
    this.catalogUnsub?.();
    this.memberTabUnsub?.();
    this.memberTabUnsub = null;
    this.viewerAudienceUnsub?.();
    this.aiStateUnsub?.();
    this.aiStateUnsub = null;
    this.workspaceProfilesUnsub?.();
    this.appUpdateUnsub?.();
    this.appUpdateUnsub = null;
    this.consentUnsub?.();
    if (this.saveSettingsListener) {
      document.removeEventListener('jf-save-settings', this.saveSettingsListener);
      this.saveSettingsListener = null;
    }
    this.appearanceMachine = null;
    this.deleteUnsub?.();
    this.deleteUnsub = null;
    this.deleteMachine = null;
    this.presentationUnsub?.();
    this.presentationUnsub = null;
    this.lastAuditedPresentationId = null;
    this.themeUnsub = null;
    this.userConfigUnsub = null;
    this.catalogUnsub = null;
    this.viewerAudienceUnsub = null;
    this.workspaceProfilesUnsub = null;
    this.consentUnsub = null;
    this.teardownAnchorObservers();
  }

  override updated(changed: PropertyValues): void {
    super.updated(changed);
    // 569 Move 6 — apply-time RUNTIME conformance: once per applied presentation, audit the
    // declared region's RENDERED DOM (computed-contrast oracle). A failure quarantines the region
    // to the built-in render (degrade-never-fail). No-op when no declaration drives the region.
    const body = activeBodyFor(SETTINGS_INTERFACE_REGION);
    const pid = this.presentation.id;
    if (body && pid !== this.lastAuditedPresentationId) {
      const el = this.shadowRoot?.querySelector('jf-declared-surface');
      if (el) {
        this.lastAuditedPresentationId = pid;
        auditAndQuarantine(SETTINGS_INTERFACE_REGION, el);
      }
    }
    // Tempdoc 855 §9.5 — (re)wire the scroll-spy observers over the active category's content pane
    // after every render (idempotent: a no-op when the pane element hasn't changed identity), and
    // re-measure so a content-length change (e.g. a plugin list growing) keeps anchors accurate.
    this.setupAnchorObservers();
  }

  private doFetch(path: string, init?: RequestInit): Promise<Response> {
    return this.host_.data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
      signal: init?.signal ?? undefined,
    });
  }

  private async loadSettings(): Promise<void> {
    const modeRevision = getUiModeRevision();
    try {
      const res = await this.doFetch('/api/settings/v2');
      if (!res.ok) return;
      const data = (await res.json()) as AllSettings;
      // Boot restoration normally seeds uiModeState first. Retain the fallback for isolated mounts,
      // but never let this slower surface-local GET overwrite a choice made while it was in flight.
      if (getUiModeRevision() === modeRevision) setUiMode(data.ui?.mode);
      this.ui = { ...(data.ui ?? {}), mode: getUiMode() };
      this.llm = data.llm ?? {};
      this.readOnly = data.settingsMode === 'in_memory';
      // §2.C: replay the persisted appearance on load (one writer) so the theme
      // survives reload and the high-contrast class is applied.
      this.applyAppearance();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * §2.C / C5 — delegate to the single appearance writer in themeState (the
   * appearance authority), so the boot path and the settings-change path use
   * the exact same DOM-mutation logic and cannot drift.
   */
  private applyAppearance(): void {
    // C1: delegate to the one appearance writer (themeState). paletteId omitted —
    // the palette is changed separately via selectTheme → host → applyAppearance.
    void applyAppearance({
      theme: this.ui.theme,
      highContrast: this.ui.highContrast === true,
    });
  }

  private async loadAutostart(): Promise<void> {
    try {
      const mod = await import('@tauri-apps/plugin-autostart');
      this.autostart = await mod.isEnabled();
    } catch {
      // Plugin not available — leave null
    } finally {
      this.autostartLoaded = true;
    }
  }

  /** Tempdoc 778 — load the default-on local feedback-capture flag + privacy note. */
  private async loadFeedbackCapture(): Promise<void> {
    try {
      const res = await authorizedFetch(`${this.apiBase || ''}/api/feedback/capture`);
      if (!res.ok) return;
      const data = (await res.json()) as { enabled?: boolean; privacyNote?: string };
      this.feedbackCaptureEnabled = data.enabled !== false;
      this.feedbackPrivacyNote = typeof data.privacyNote === 'string' ? data.privacyNote : '';
    } catch {
      // Backend not reachable — leave null; the section renders nothing rather than a wrong state.
    }
  }

  /** Tempdoc 778 — toggle local feedback capture; optimistic, reverts on failure. */
  private async toggleFeedbackCapture(): Promise<void> {
    if (this.readOnly || this.feedbackCaptureEnabled === null) return;
    const next = !this.feedbackCaptureEnabled;
    this.feedbackCaptureEnabled = next;
    try {
      const res = await authorizedFetch(`${this.apiBase || ''}/api/feedback/capture`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: next }),
      });
      if (!res.ok) this.feedbackCaptureEnabled = !next;
    } catch {
      this.feedbackCaptureEnabled = !next;
    }
  }

  /**
   * 569 §14 — an appearance change now RUNS THROUGH the declared APPEARANCE_FLOW statechart
   * (Move 8 made operative): the local reactive view updates optimistically (button highlight),
   * then the change is sent as named EVENTS into the machine, which dispatches the closed Effect
   * v3 kinds through the gated + journaled 550 seam — `set-appearance` / `set-ui-mode` (the
   * optimistic apply, via the Shell listeners) and `save-settings` (the persist, via this
   * surface's listener, which owns `saving`/`error`). The imperative applyAppearance / setUiMode /
   * fetch this method used to call are now those effects' host handlers, so the surface's
   * BEHAVIOUR is the statechart, not hand-wired code. Both render paths (the hand-authored
   * buttons and the declared option-buttons) funnel here, so both are statechart-driven.
   */
  private patch(updates: Partial<UISettings>): void {
    if (this.readOnly) return;
    this.ui = { ...this.ui, ...updates };
    const m = this.appearanceMachine;
    if (!m) return;
    if (updates.theme !== undefined) {
      m.send(`THEME_${String(updates.theme).toUpperCase()}`);
    }
    if (updates.highContrast !== undefined) {
      m.send(updates.highContrast ? 'HC_ON' : 'HC_OFF');
    }
    if (updates.mode !== undefined) {
      m.send(updates.mode === 'advanced' ? 'MODE_ADVANCED' : 'MODE_SIMPLE');
    }
    // Tempdoc 806 B.2 (round-12) — the declared Interface region also offers `vimMode` and
    // `defaultAction`. Without these two branches the edit reached line 897's local `this.ui` and
    // stopped there: the statechart's `save-settings` effect is the only persistence, so the toggle
    // rendered ON off the optimistic local copy while the backend's value never changed.
    if (updates.vimMode !== undefined) {
      m.send(updates.vimMode ? 'VIM_ON' : 'VIM_OFF');
    }
    if (updates.defaultAction !== undefined) {
      m.send(`DEFAULT_ACTION_${String(updates.defaultAction).toUpperCase()}`);
    }
  }

  private async toggleAutostart(): Promise<void> {
    if (!this.host_.platform.capabilities.has('native-notifications') || this.autostart === null) return;
    const next = !this.autostart;
    this.autostart = next;
    try {
      const mod = await import('@tauri-apps/plugin-autostart');
      if (next) await mod.enable();
      else await mod.disable();
    } catch (err) {
      this.autostart = !next;
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Tempdoc 511 §511-followup-B: `core.reset-settings` is mounted via
   * `<jf-operation>` (see `renderFooter`). Surface-side, we only react
   * to op-success/op-error to refresh + surface errors. The confirm
   * ceremony lives in the inner ActionButton driven by the wire's
   * `ConfirmStrategy.Inline`.
   */
  private handleResetSuccess(): void {
    this.error = null;
    void this.loadSettings();
  }

  private handleResetError(e: CustomEvent<OpErrorEventDetail>): void {
    const msg = e.detail?.message;
    this.error = typeof msg === 'string' ? msg : 'Reset failed.';
  }

  /**
   * 569 §15 (Move 8 / §7) — the bespoke destructive BODY, run by the surface when the declared
   * CONFIRM_CEREMONY statechart reaches `done`. The Tauri-shell delete is the team-owned residue
   * (outside the closed Effect vocabulary); the CEREMONY (states + the `typed == true` guard) is the
   * user-authored statechart that gates it. The imperative `showConfirmDialog` is replaced by the
   * declared `confirming` state the surface renders inline (the typed-confirm panel).
   */
  private async runDelete(): Promise<void> {
    if (!this.host_.platform.capabilities.has('native-notifications') || this.deleting) return;
    this.deleting = true;
    try {
      const mod = await import('@tauri-apps/api/core');
      const token = await mod.invoke<string>('prepare_delete_data');
      await mod.invoke('confirm_delete_data', { token });
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
      this.deleting = false;
    }
  }

  private async loadAppUpdateStatus(): Promise<void> {
    this.updateStatus = await refreshAppUpdateStatus();
  }

  private async checkForAppUpdate(): Promise<void> {
    this.updateBusy = true;
    this.error = null;
    try {
      this.updateStatus = await checkForAppUpdateFromHost();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
      await this.loadAppUpdateStatus();
    } finally {
      this.updateBusy = false;
    }
  }

  private async installAppUpdate(): Promise<void> {
    this.updateBusy = true;
    this.error = null;
    try {
      await installAppUpdateFromHost();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
      await this.loadAppUpdateStatus();
      this.updateBusy = false;
    }
  }

  /**
   * Tempdoc 855 §15.1 — the flat setting-row idiom: bold label + muted description, then a
   * CONTROL either inline-right (a compact control — switch/select/small link) or full-width below
   * (a radio-group/slider/swatch grid — pass `below: true`). One row shape backs every generic
   * setting row in the window; composite sections (theme grid, plugin list, …) don't use it.
   */
  private renderSettingRow(
    label: string,
    desc: string | TemplateResult | typeof nothing,
    control: TemplateResult,
    opts: { below?: boolean; testid?: string } = {},
  ): TemplateResult {
    const testid = opts.testid ?? nothing;
    if (opts.below) {
      return html`
        <div class="toggle-row column" data-testid=${testid}>
          <div class="toggle-label">${label}</div>
          ${desc === nothing ? nothing : html`<div class="toggle-desc">${desc}</div>`}
          <div>${control}</div>
        </div>
      `;
    }
    return html`
      <div class="toggle-row" data-testid=${testid}>
        <div>
          <div class="toggle-label">${label}</div>
          ${desc === nothing ? nothing : html`<div class="toggle-desc">${desc}</div>`}
        </div>
        ${control}
      </div>
    `;
  }

  /**
   * 569 Move 1/3 (the keystone) — render the Interface + Appearance region from the ACTIVE
   * Presentation Declaration's body, through the projection engine (`<jf-declared-surface>`),
   * when one is applied; otherwise the built-in Lit render. An absent or quarantined body
   * silently degrades to the built-in (degrade-never-fail, Move 6). The author supplies only
   * schema + uischema (composition over the renderer vocabulary); the engine co-projects the
   * accessible, operable, 558-contrast-safe controls the author never touches. Edits round-trip
   * through the SAME `patch()` the built-in render uses.
   *
   * Tempdoc 855 §15.1 — the declared body is a composite-content exception (a whole schema-driven
   * region, opaque to the flat-row idiom): it keeps the lighter `.section-composite` panel. The
   * cross-link row below it is an ordinary flat row, sitting at the branch join (see
   * `renderRelatedSettingsRow`'s doc comment for why it can't live inside `renderAppearance()`).
   */
  private renderInterfaceRegion(): TemplateResult {
    const body = activeBodyFor(SETTINGS_INTERFACE_REGION);
    if (!body) {
      return html`
        ${this.renderInterface()}${this.renderAppearance()}
        <div class="section">${this.renderRelatedSettingsRow('appearance', 'accessibility')}</div>
      `;
    }
    return html`
      <div class="section section-composite">
        <jf-declared-surface
          .declaration=${body}
          .data=${this.ui as Record<string, unknown>}
          .enabled=${!this.readOnly}
          @surface-change=${(e: CustomEvent<SurfaceChangeEventDetail>) =>
            void this.patch(e.detail.data as Partial<UISettings>)}
        ></jf-declared-surface>
      </div>
      <div class="section">${this.renderRelatedSettingsRow('appearance', 'accessibility')}</div>
    `;
  }

  private renderInterface(): TemplateResult {
    // Tempdoc 738 — render the selected state from the live uiMode authority (not the local
    // this.ui.mode snapshot) so this control stays in sync with the topbar Simple/Detailed toggle.
    const mode: 'simple' | 'advanced' = getUiMode();
    const options: OptionButtonGroupOption[] = [
      { value: 'simple', label: 'Simple', description: 'Standard view', icon: 'list' },
      { value: 'advanced', label: 'Detailed', description: 'Full controls + diagnostics', icon: 'maximize-2' },
    ];
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Interface</h3>
        ${this.renderSettingRow(
          'Mode',
          'Detailed mode shows technical detail and unlocks AI runtime configuration, GPU controls, ' +
            'Lucene search syntax, and library management tools.',
          html`
            <jf-option-button-group
              .options=${options}
              .value=${mode}
              .groupLabel=${'Detail level'}
              ?enabled=${!this.readOnly}
              @change=${(e: CustomEvent<{ value: string }>) =>
                void this.patch({ mode: e.detail.value as UISettings['mode'] })}
            ></jf-option-button-group>
          `,
          { below: true },
        )}
      </div>
    `;
  }

  /** Tempdoc 855 §15.2 — the theme-variant trio (System/Dark/Light) as three small square swatches
   *  with a check badge on the active one (Discord's "Default Themes" row idiom), reusing the ONE
   *  radiogroup keyboard model via `jf-option-button-group`'s swatch option shape rather than
   *  forking a second component (§17 R2 judgment). System is a literal diagonal dark/light split;
   *  Dark/Light are literal representative fills — these depict the MODE concept, not a theme
   *  palette, so they intentionally do NOT read from theme tokens (which vary per active theme). */
  private renderVariantOptions(): OptionButtonGroupOption[] {
    // Fix-round F1 — `swatch` is the serializable `SwatchSpec`, sourced from the SAME
    // `THEME_VARIANT_DARK`/`THEME_VARIANT_LIGHT` constants the declared `theme.x-enum-swatches`
    // schema extension uses (`builtinPresentations.ts`), so this hand-authored fallback and the
    // declared default path render the identical trio — one vocabulary, one color source, not two.
    return [
      {
        value: 'system',
        label: 'System',
        swatch: { split: [THEME_VARIANT_DARK, THEME_VARIANT_LIGHT] },
      },
      { value: 'dark', label: 'Dark', swatch: { fill: THEME_VARIANT_DARK } },
      { value: 'light', label: 'Light', swatch: { fill: THEME_VARIANT_LIGHT } },
    ];
  }

  private renderAppearance(): TemplateResult {
    const theme = this.ui.theme ?? 'system';
    return html`
      <div class="section">
        <h3>${icon({ name: 'palette', size: 12 })} Appearance</h3>
        ${this.renderSettingRow(
          'Theme variant',
          'Follows your OS, or pin dark/light.',
          html`
            <jf-option-button-group
              .options=${this.renderVariantOptions()}
              .value=${theme}
              .groupLabel=${'Color scheme'}
              ?enabled=${!this.readOnly}
              @change=${(e: CustomEvent<{ value: string }>) =>
                void this.patch({ theme: e.detail.value as UISettings['theme'] })}
            ></jf-option-button-group>
          `,
          { below: true },
        )}
        ${this.renderSettingRow(
          'Solid surfaces',
          'Opaque panels, no glass blur',
          html`
            <jf-switch
              .checked=${this.surfaceMode === 'solid'}
              label="Solid surfaces"
              @change=${() => this.toggleSurfaceMode()}
            ></jf-switch>
          `,
        )}
      </div>
    `;
  }

  /**
   * Tempdoc 855 §15.4/§17 R1 — the cross-link row. Appearance used to render its OWN High-contrast
   * toggle beside Accessibility's contrast picker: two controls, two stores, one `high-contrast`
   * class. The control now lives once (Accessibility); this row is what replaces it — a pointer, not
   * a second authority. Rendered from `renderInterfaceRegion()` (not `renderAppearance()`), because
   * that region renders EITHER the declared `<jf-declared-surface>` OR the built-in
   * Interface+Appearance render, never both — `renderAppearance()` is dead on the default declared
   * path (production boot applies `CORE_DECLARED`), so the row has to sit at the branch join to
   * render on both paths, exactly once.
   *
   * Deliberately small and reusable (the first formal cross-link row): it takes a register
   * coordinate — the same `{categoryId, sectionKey}` pair `<jf-settings-nav>` emits and
   * `searchRegister()` returns — and activates it through the SAME `activateSearchHit` path, so the
   * cross-category case (select the category, then jump once its content has rendered) is handled by
   * the existing composition rather than a second navigation rule. The label is the target section's
   * own `settings.section.*` catalog label, so a renamed section renames its cross-links too.
   */
  private renderRelatedSettingsRow(categoryId: string, sectionKey: string): TemplateResult {
    const section = SETTINGS_REGISTER.flatMap((g) => g.categories)
      .find((c) => c.id === categoryId)
      ?.sections?.find((s) => s.key === sectionKey);
    if (!section) return html``;
    return this.renderSettingRow(
      localizeResourceKey('settings.related.label'),
      nothing,
      html`
        <button
          class="link-row"
          type="button"
          @click=${() => this.activateSearchHit(categoryId, sectionKey)}
        >
          ${localizeResourceKey(section.labelKey)}${icon({ name: 'chevron-right', size: 14 })}
        </button>
      `,
    );
  }

  /**
   * Tempdoc 567 §9.4 — flip the glass/solid surface mode. `setSurfaceMode` (the theme authority)
   * applies it live through the one appearance writer AND persists it (FE-only, user-state document).
   */
  private toggleSurfaceMode(): void {
    this.surfaceMode = this.surfaceMode === 'solid' ? 'glass' : 'solid';
    setSurfaceMode(this.surfaceMode);
  }

  /**
   * 569 §19 Seam 4 — the adaptation / accessibility axes. Density + motion persist per-profile via the
   * one `applyAdaptationProfile` authority and project to global DOM state; the cascade re-projects
   * every surface, so a single switch is total.
   *
   * Tempdoc 855 §15.4/§17 R1 — High contrast is the ONE visible contrast control (Appearance's
   * duplicate became a cross-link row), and it deliberately does NOT go through
   * `applyAdaptationProfile`: its canonical store is the backend-persisted `UISettings.highContrast`,
   * so it writes through `patch()` like every other backed setting (→ appearance statechart →
   * `set-appearance` + the narrow `save-settings` POST). That leaves `themeState.applyAppearance` as
   * the single writer of the `high-contrast` root class.
   */
  /** Tempdoc 855 §15.2 — Density's three stops, as the `<jf-discrete-slider>` step vocabulary. */
  private static readonly DENSITY_STEPS: readonly DiscreteSliderStep[] = [
    { value: 'compact', label: 'Compact' },
    { value: 'comfortable', label: 'Comfortable' },
    { value: 'spacious', label: 'Spacious' },
  ];

  private renderAccessibility(): TemplateResult {
    const p = getAdaptationProfile();
    const density = p.density ?? 'comfortable';
    const motion = p.motion ?? 'full';
    return html`
      <div class="section" data-testid="settings-accessibility">
        <h3>${icon({ name: 'layers', size: 12 })} Accessibility</h3>
        ${this.renderSettingRow(
          'Density',
          nothing,
          html`
            <jf-discrete-slider
              .steps=${SettingsSurface.DENSITY_STEPS}
              .value=${density}
              label="Density"
              ?disabled=${this.readOnly}
              @change=${(e: CustomEvent<{ value: string }>) =>
                applyAdaptationProfile({
                  density: e.detail.value as 'compact' | 'comfortable' | 'spacious',
                })}
            ></jf-discrete-slider>
          `,
          { below: true },
        )}
        ${this.renderSettingRow(
          'High contrast',
          'Guaranteed AA — better visibility',
          html`
            <jf-switch
              .checked=${this.ui.highContrast === true}
              ?disabled=${this.readOnly}
              label="High contrast"
              @change=${(e: CustomEvent<{ checked: boolean }>) =>
                void this.patch({ highContrast: e.detail.checked })}
            ></jf-switch>
          `,
          { testid: 'settings-high-contrast' },
        )}
        ${this.renderSettingRow(
          'Reduce motion',
          'Turns off non-essential animations',
          html`
            <jf-switch
              .checked=${motion === 'reduced'}
              ?disabled=${this.readOnly}
              label="Reduce motion"
              @change=${(e: CustomEvent<{ checked: boolean }>) =>
                applyAdaptationProfile({ motion: e.detail.checked ? 'reduced' : 'full' })}
            ></jf-switch>
          `,
        )}
      </div>
    `;
  }

  /**
   * Tempdoc 874 — the chat-column width preset, as the same ordered-three-stop control shape Density
   * uses. Three named stops rather than a free slider: the width is one fact three Search v3
   * components share (`--measure-prose` caps the transcript, the composer band and the context bar),
   * so a bounded vocabulary is what lets the suite pin it. Its own registered section rather than a
   * row inside `renderAppearance()`, which is dead on the production declared path (see
   * `renderRelatedSettingsRow`'s comment above).
   */
  private static readonly CHAT_WIDTH_STEPS: readonly DiscreteSliderStep[] = [
    { value: 'narrow', label: 'Narrow' },
    { value: 'default', label: 'Default' },
    { value: 'wide', label: 'Wide' },
  ];

  private renderChatWidth(): TemplateResult {
    return html`
      <div class="section" data-testid="settings-chat-width">
        <h3>${icon({ name: 'arrow-right-left', size: 12 })} Chat width</h3>
        ${this.renderSettingRow(
          'Chat width',
          'How wide the chat column may grow. Narrower lines are easier to read; wider fits more on screen.',
          html`
            <jf-discrete-slider
              .steps=${SettingsSurface.CHAT_WIDTH_STEPS}
              .value=${this.chatWidth}
              label="Chat width"
              ?disabled=${this.readOnly}
              @change=${(e: CustomEvent<{ value: string }>) =>
                this.selectChatWidth(e.detail.value as ChatWidth)}
            ></jf-discrete-slider>
          `,
          { below: true },
        )}
      </div>
    `;
  }

  /**
   * Tempdoc 874 — pick a chat-width preset. `setChatWidth` persists it in the user-state document;
   * the mounted Search v3 window re-applies through its own subscription, so this method does not
   * reach across into the window.
   */
  private selectChatWidth(w: ChatWidth): void {
    this.chatWidth = w;
    setChatWidth(w);
  }

  /**
   * Tempdoc 511-followup Track A + 511-followup-2 Track BB —
   * view-tier selector.
   *
   * IMPORTANT: this is a view PREFERENCE, not access control. The
   * local single-user deployment doesn't authenticate or authorize
   * operation invocations; the wire ships every operation's
   * metadata to every client, and `OperationClient.invoke()` will
   * call any operation regardless of the tier set here. Switching
   * tiers only affects what the UI RENDERS. Real authorization
   * (server-side catalog filtering by session identity) is a
   * separate concern that isn't necessary in a single-user local
   * model. The tempdoc's named follow-up "Option A2" describes
   * what such an auth layer would entail.
   *
   * USER (default): hide operator and developer ops; show only
   * user-facing surfaces and operations.
   * OPERATOR: also show operator-only ops (restart-worker,
   * bulk-reindex, etc.) — useful for system administration tasks.
   * DEVELOPER: show everything, including developer-only debug
   * surfaces and operations.
   * AGENT is reserved for the agent runtime, not user-selectable.
   */
  private renderViewerAudience(): TemplateResult {
    const audience: Audience = this.viewerAudience;
    const options: OptionButtonGroupOption[] = [
      { value: 'USER', label: 'User', description: 'Default tier', icon: 'monitor' },
      { value: 'OPERATOR', label: 'Operator', description: 'Shows admin ops', icon: 'shield' },
      { value: 'DEVELOPER', label: 'Developer', description: 'Show everything', icon: 'database' },
    ];
    return html`
      <div class="section">
        <h3>${icon({ name: 'shield', size: 12 })} View tier</h3>
        ${this.renderSettingRow(
          'View tier preference',
          'Controls which operations and surfaces the UI renders for you. This is a view ' +
            'preference — it does not restrict backend access. Leave on "User" for the default ' +
            'experience; switch up for admin or debug workflows.',
          html`
            <jf-option-button-group
              .options=${options}
              .value=${audience}
              .groupLabel=${'View tier'}
              ?enabled=${!this.readOnly}
              @change=${(e: CustomEvent<{ value: string }>) => setViewerAudience(e.detail.value as Audience)}
            ></jf-option-button-group>
          `,
          { below: true },
        )}
      </div>
    `;
  }

  private renderKeyboard(): TemplateResult {
    const action = this.ui.defaultAction ?? 'open';
    return html`
      <div class="section">
        <h3>${icon({ name: 'keyboard', size: 12 })} Keyboard</h3>
        ${this.renderSettingRow(
          'Enter action',
          'Default action when pressing Enter on a result',
          html`
            <!-- Tempdoc 543 §20.7 B1 — schema-driven form via the
                 x-ui-renderer dispatcher. The 'enter-action-select' hint
                 routes to EnterActionPickerRenderer (registered at boot
                 via shell-v0/renderers/registry.ts). -->
            <jf-form
              .schema=${{
                type: 'object',
                properties: {
                  defaultAction: {
                    type: 'string',
                    // 855 P1 (a11y fix riding along) — `title` is the schema-standard field
                    // EnterActionPickerRenderer reads for the control's accessible name (axe
                    // `select-name`, baselined in governance/ui-a11y-baseline.v1.json until this fix).
                    title: 'Enter action',
                    enum: ['open', 'reveal', 'preview'],
                    'x-ui-renderer': 'enter-action-select',
                  },
                },
              }}
              .uischema=${{
                type: 'Control',
                scope: '#/properties/defaultAction',
              }}
              .data=${{ defaultAction: action }}
              ?enabled=${!this.readOnly}
              @form-change=${(e: CustomEvent<FormChangeEventDetail>) => {
                const next = (e.detail.data as { defaultAction?: string }).defaultAction;
                if (next === 'open' || next === 'reveal' || next === 'preview') {
                  void this.patch({ defaultAction: next as UISettings['defaultAction'] });
                }
              }}
            ></jf-form>
          `,
        )}
      </div>
    `;
  }

  /**
   * Tempdoc 778 — the default-on LOCAL feedback-capture control + its visible privacy note. Renders
   * nothing until the flag loads (so it never shows a wrong state). The note is the product's
   * loopback-only story made visible: capture improves ranking and never leaves the machine.
   */
  private renderFeedbackCapture(): TemplateResult | typeof nothing {
    if (this.feedbackCaptureEnabled === null) return nothing;
    return this.renderSettingRow(
      'Improve ranking from your activity (local only)',
      this.feedbackPrivacyNote ||
        'Clicks, opens, and dwell time on results and chat citations are recorded on this ' +
          'machine to improve ranking. Nothing is ever uploaded.',
      html`
        <jf-switch
          .checked=${!!this.feedbackCaptureEnabled}
          label="Improve ranking from your activity (local only)"
          @change=${() => void this.toggleFeedbackCapture()}
        ></jf-switch>
      `,
    );
  }

  private renderDesktop(): TemplateResult | typeof nothing {
    if (!this.host_.platform.capabilities.has('native-notifications') || !this.autostartLoaded || this.autostart === null) {
      return nothing;
    }
    return html`
      <div class="section">
        <h3>${icon({ name: 'power', size: 12 })} Desktop</h3>
        ${this.renderSettingRow(
          'Launch on startup',
          'Start minimized in the system tray',
          html`
            <jf-switch
              .checked=${!!this.autostart}
              label="Launch on startup"
              @change=${() => void this.toggleAutostart()}
            ></jf-switch>
          `,
        )}
      </div>
    `;
  }

  /**
   * Slice 477 H1 — V1.5 Themes section.
   *
   * Lists built-in themes (`themesCatalog.BUILT_IN_THEMES`) with a
   * "Default" option that clears the active theme. Selection writes
   * to `themeState`, which fetches `/themes/<id>.css` and injects
   * the result. Persists across reloads via `themeState`'s
   * localStorage layer.
   */
  /**
   * Tempdoc 855 §15.3 (T3) — declare, don't parse: a built-in's swatch is authored on the manifest
   * entry (`manifest.json`, threaded through `themesCatalog.ThemeCatalogEntry.swatch`). A custom
   * theme has no authored swatch (only seed tokens are user-authorable, §8 #4), so it derives
   * directly from whatever surface/accent tokens its own tree happens to carry — readable without
   * applying (`designTokenTree.ts`). A theme with neither renders a neutral tile (never
   * color-only — the name label beneath every tile is load-bearing regardless).
   */
  private themeSwatchFor(entry: ThemeCatalogEntry): { surface: string; accent: string } | null {
    if (entry.swatch) return entry.swatch;
    const t = entry.tokens?.tokens;
    if (!t) return null;
    const surface = t['surface-primary'] ?? t['surface-0'];
    const accent = t['accent-tint'];
    if (!surface || !accent) return null;
    return { surface, accent };
  }

  /** One swatch tile: a two-tone square (surface ground + accent corner mark), a check badge when
   *  active, or a neutral fallback tile when no swatch data is available. `id=null` is the "Default
   *  tokens" option. The accessible name composes name + description (§15.3 — description demoted
   *  from a visible text wall to the house `title`-attribute idiom, 15 other uses in this file). */
  private renderThemeTile(params: {
    id: string | null;
    displayName: string;
    description: string;
    swatch: { surface: string; accent: string } | null;
    onClick: () => void;
  }): TemplateResult {
    const selected = this.activeThemeId === params.id;
    return html`
      <button
        type="button"
        class="theme-tile ${params.swatch ? '' : 'theme-tile-neutral'} ${selected ? 'selected' : ''}"
        @click=${params.onClick}
        title=${params.description}
        aria-label=${`${params.displayName}: ${params.description}`}
        aria-pressed=${selected ? 'true' : 'false'}
      >
        ${params.swatch
          ? html`
              <span class="theme-swatch" style="background:${params.swatch.surface}">
                <span class="theme-swatch-accent" style="background:${params.swatch.accent}"></span>
              </span>
            `
          : icon({ name: 'circle', size: 18 })}
        ${selected
          ? html`<span class="theme-tile-check" aria-hidden="true"
              >${icon({ name: 'check-circle-2', size: 14 })}</span
            >`
          : nothing}
      </button>
    `;
  }

  /**
   * Slice 477 H1 — V1.5 Themes section.
   *
   * Lists built-in themes (`themesCatalog.BUILT_IN_THEMES`) with a
   * "Default" option that clears the active theme. Selection writes
   * to `themeState`, which fetches `/themes/<id>.css` and injects
   * the result. Persists across reloads via `themeState`'s
   * localStorage layer.
   *
   * Tempdoc 855 §15.3 — a swatch GRID (Discord's Color Themes idiom) replaces the old text-card
   * list; `.theme-composite` keeps the section-composite exception (§15.5).
   */
  private renderThemes(): TemplateResult {
    const themes = listAvailableThemes();
    const renderEntry = (entry: ThemeCatalogEntry): TemplateResult => {
      // Tempdoc 567 — a custom (user-authored) theme carries its token tree; built-ins carry cssPath.
      const isCustom = entry.tokens !== undefined;
      // Tempdoc 567 §8 #3 — custom themes are MANAGED in the host (Settings → Appearance), independent
      // of the editor plugin's lifecycle: rename + delete are SIBLING affordances below the tile (never
      // nested inside its button). While this theme is being renamed (§8 deferred → built), the tile
      // is replaced by an inline input.
      if (isCustom && this.renamingThemeId === entry.id) {
        return html`
          <div class="theme-tile-wrap custom-theme-renaming">
            <input
              class="theme-rename-input"
              .value=${this.renameDraft}
              aria-label=${`Rename custom theme ${entry.displayName}`}
              @input=${(e: Event) => (this.renameDraft = (e.target as HTMLInputElement).value)}
              @keydown=${(e: KeyboardEvent) => {
                if (e.key === 'Enter') this.commitRenameTheme(entry.id);
                else if (e.key === 'Escape') this.cancelRenameTheme();
              }}
            />
            <span class="theme-tile-actions">
              <jf-button
                variant="ghost"
                size="icon"
                label="Save new name"
                title="Save"
                .onActivate=${() => this.commitRenameTheme(entry.id)}
              >
                ${icon({ name: 'check-circle-2', size: 14 })}
              </jf-button>
              <jf-button
                variant="ghost"
                size="icon"
                label="Cancel rename"
                title="Cancel"
                .onActivate=${() => this.cancelRenameTheme()}
              >
                ${icon({ name: 'x', size: 14 })}
              </jf-button>
            </span>
          </div>
        `;
      }
      return html`
        <div class="theme-tile-wrap">
          ${this.renderThemeTile({
            id: entry.id,
            displayName: entry.displayName,
            description: entry.description,
            swatch: this.themeSwatchFor(entry),
            onClick: () => void this.selectTheme(entry.id),
          })}
          <span class="theme-tile-label">${entry.displayName}</span>
          ${isCustom
            ? html`
                <span class="theme-tile-actions">
                  <jf-button
                    class="custom-theme-rename"
                    variant="ghost"
                    size="icon"
                    label=${`Rename custom theme ${entry.displayName}`}
                    title=${`Rename "${entry.displayName}"`}
                    .onActivate=${() => this.beginRenameTheme(entry)}
                  >
                    ${icon({ name: 'pencil', size: 12 })}
                  </jf-button>
                  <jf-button
                    class="custom-theme-del"
                    variant="ghost"
                    size="icon"
                    label=${`Delete custom theme ${entry.displayName}`}
                    title=${`Delete "${entry.displayName}"`}
                    .onActivate=${() => void this.deleteCustomTheme(entry)}
                  >
                    ${icon({ name: 'trash-2', size: 12 })}
                  </jf-button>
                </span>
              `
            : nothing}
        </div>
      `;
    };
    return html`
      <div class="section section-composite theme-composite" data-testid="settings-themes">
        <h3>${icon({ name: 'palette', size: 12 })} Theme</h3>
        <p class="help" style="margin: 0 0 0.75rem 0">
          Pick a full theme palette. Composes with the dark/light variant in
          Appearance above.
        </p>
        <div class="theme-grid" role="group" aria-label="Theme">
          <div class="theme-tile-wrap">
            ${this.renderThemeTile({
              id: null,
              displayName: 'Default',
              description: 'Default tokens — no theme override',
              swatch: null,
              onClick: () => void this.selectTheme(null),
            })}
            <span class="theme-tile-label">Default</span>
          </div>
          ${themes.map(renderEntry)}
        </div>
        ${this.renderThemeImport()}
      </div>
    `;
  }

  /**
   * Tempdoc 567 §8 (deferred → built) — import a custom theme from a pasted JSON declaration. Host-owned
   * (Settings → Appearance), the symmetric counterpart of the editor's export-to-clipboard: a theme
   * shared as JSON can be brought back in without the editor plugin. The host capability validates and
   * holds the tree to the seeds+roles authorable surface, so import is not a backdoor for derived tokens.
   */
  private renderThemeImport(): TemplateResult {
    if (!this.themeImporting) {
      return html`
        <jf-button
          class="theme-import-toggle"
          variant="ghost"
          size="sm"
          label="Import a theme from JSON"
          .onActivate=${() => {
            this.error = null;
            this.themeImporting = true;
          }}
        >
          ${icon({ name: 'upload', size: 14 })} Import theme…
        </jf-button>
      `;
    }
    return html`
      <div class="theme-import">
        <label class="help" for="theme-import-json">Paste a theme's exported JSON</label>
        <textarea
          id="theme-import-json"
          class="theme-import-json"
          rows="5"
          spellcheck="false"
          .value=${this.themeImportDraft}
          @input=${(e: Event) => (this.themeImportDraft = (e.target as HTMLTextAreaElement).value)}
        ></textarea>
        <div class="theme-import-actions">
          <jf-button
            variant="primary"
            size="sm"
            label="Import the pasted theme"
            ?disabled=${this.themeImportDraft.trim() === ''}
            .onActivate=${() => this.importThemeFromDraft()}
          >
            Import
          </jf-button>
          <jf-button
            variant="ghost"
            size="sm"
            label="Cancel import"
            .onActivate=${() => {
              this.themeImporting = false;
              this.themeImportDraft = '';
            }}
          >
            Cancel
          </jf-button>
        </div>
      </div>
    `;
  }

  /** Tempdoc 855 §5 item 2 / §9.6 item 5 — Token Editor's launch affordance, replacing its RAIL
   *  placement. ADR-0035 boundary: settings hosts a LINK to the plugin surface, never the plugin's
   *  UI itself, so this is the same `.onActivate` → `host_.navigation.navigate(surfaceId)` idiom the
   *  old Security & Privacy pointer used (`renderSecurityPrivacyPointer`, superseded by full
   *  absorption in P2) — the surface is DEEPLINK-placement, so the navigation stage-mounts it and
   *  the settings window dismisses automatically (Shell dismisses on realized stage navigation, P0). */
  private renderTokenEditorLink(): TemplateResult {
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Token Editor</h3>
        ${this.renderSettingRow(
          'Custom theme tokens',
          'Author colors, seeds, and roles in the dedicated editor.',
          html`
            <jf-button
              variant="secondary"
              .onActivate=${() =>
                this.host_.navigation.navigate('vendor.token-editor.editor-surface')}
              >Open Token Editor</jf-button
            >
          `,
        )}
      </div>
    `;
  }

  /**
   * Slice 477 H1 — V1.5 Rail customization section.
   *
   * Per-surface visibility checkbox + up/down arrow buttons for
   * reordering. Drag-to-reorder is V1.5.1 polish (richer UX, library
   * dep, accessibility surface); up/down buttons are accessible by
   * default and require no library.
   *
   * The order shown reflects the current `userConfig.surfaceOrder`
   * applied to the catalog (catalog order is the fallback for
   * surfaces not in `surfaceOrder`). Visibility checkbox reflects
   * `userConfig.surfaceVisibility` (absent = visible).
   */
  private renderRail(): TemplateResult {
    const orderedSurfaces = this.applyOrderToSurfaces(this.railSurfaces);
    const visibility = this.userConfig.surfaceVisibility ?? {};
    const hasOverrides =
      this.userConfig.surfaceVisibility !== undefined ||
      this.userConfig.surfaceOrder !== undefined;
    return html`
      <div class="section">
        <h3>${icon({ name: 'menu', size: 12 })} Rail</h3>
        <p class="help" style="margin: 0 0 0.5rem 0">
          Reorder or hide surfaces in the activity rail.
          ${hasOverrides
            ? html`
                <jf-button
                  size="sm"
                  label="Reset"
                  style="margin-left: 0.5rem"
                  title="Reset rail to catalog defaults"
                  .onActivate=${() => this.host_.layout.clearAllLayoutOverrides()}
                >
                  Reset
                </jf-button>
              `
            : nothing}
        </p>
        <ul class="rail-list">
          ${orderedSurfaces.map((s, idx) => {
            const visible = visibility[s.id] !== false;
            const isFirst = idx === 0;
            const isLast = idx === orderedSurfaces.length - 1;
            return html`
              <li class="rail-row">
                <input
                  type="checkbox"
                  ?checked=${visible}
                  @change=${(e: Event) =>
                    this.host_.layout.setSurfaceVisibility(s.id, (e.target as HTMLInputElement).checked)}
                  aria-label=${`Show ${present({ kind: 'surface', id: s.id }).label} in rail`}
                />
                <span class="rail-label" style=${visible ? '' : 'opacity: 0.5'}>
                  ${present({ kind: 'surface', id: s.id }).label}
                </span>
                <jf-button
                  class="rail-arrow"
                  variant="ghost"
                  size="icon"
                  label="Move up"
                  title="Move up"
                  ?disabled=${isFirst}
                  .onActivate=${() => this.moveRailSurface(idx, -1)}
                >
                  ${icon({ name: 'chevron-up', size: 14 })}
                </jf-button>
                <jf-button
                  class="rail-arrow"
                  variant="ghost"
                  size="icon"
                  label="Move down"
                  title="Move down"
                  ?disabled=${isLast}
                  .onActivate=${() => this.moveRailSurface(idx, 1)}
                >
                  ${icon({ name: 'chevron-down', size: 14 })}
                </jf-button>
              </li>
            `;
          })}
        </ul>
      </div>
    `;
  }

  /**
   * Slice 477 H1 — V1.5 Plugins section.
   *
   * Lists plugins from the session-singleton {@link getSessionPluginRegistry}.
   * For each plugin: id + version + provenance (currently uniform
   * TRUSTED_PLUGIN per V1.5 alpha; 478 §4.D refines via Sigstore).
   * Revoke calls `registry.uninstall(id)` + `removePluginSurfaceContributions(id)`.
   *
   * Ships a "Load plugin from URL" input (see renderPlugins) alongside
   * console / dev-examples auto-load. Plugins loaded this way resolve to
   * UNTRUSTED tier. NOTE: per docs/tempdocs/547 F1/F2 the UNTRUSTED
   * sandbox does not fully contain plugins in the default (lockdown-off)
   * configuration — treat loading a plugin as running code with
   * effectively-full app access. Marketplace UI is gated by 470 §7's
   * ≥5-community-plugins demand trigger (deferred per 477 §4.4).
   */
  private renderLayout(): TemplateResult {
    const layouts = listLayouts();
    const activeId = this.userConfig?.activeLayoutId ?? 'core.default';
    // Tempdoc 855 fix-round F2 (S1) — was a hand-rolled `button.card` grid: selection communicated
    // only via a CSS `.active` class, no role/keyboard model at all (a 7th unconverted enum picker
    // this file's other pickers already left behind). Converted to the shared
    // `jf-option-button-group` plain-props path (855 §17 R2) — same values/order, same click →
    // `selectLayout` wiring; the card's border/padding chrome is not missed (no CSS rule ever
    // styled `.card`/`.card-row`/`.card-label`/`.card-desc` in this file — the "card" look was
    // already just an unstyled `<button>` in practice), and the option-btn's built-in description
    // slot carries the explanatory text `.card-desc` used to hold.
    const options: OptionButtonGroupOption[] = layouts.map((layout) => ({
      value: layout.id,
      label: layout.displayName,
      description: layout.description,
    }));
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Layout</h3>
        <p class="help" style="margin: 0 0 0.5rem 0">Choose how the workspace is arranged.</p>
        <jf-option-button-group
          .options=${options}
          .value=${activeId}
          .groupLabel=${'Layout'}
          ?enabled=${!this.readOnly}
          @change=${(e: CustomEvent<{ value: string }>) => this.selectLayout(e.detail.value)}
        ></jf-option-button-group>
      </div>
    `;
  }

  private selectLayout(layoutId: string): void {
    this.host_.layout.setActiveLayoutId(layoutId === 'core.default' ? undefined : layoutId);
  }

  private renderPlugins(): TemplateResult {
    return html`
      <div class="section section-composite">
        <h3>${icon({ name: 'package', size: 12 })} Plugins</h3>
        ${this.plugins.length > 0
          ? html`
              <ul class="plugin-list">
                ${this.plugins.map((p) => this.renderPluginRow(p))}
              </ul>
            `
          : html`<p class="help" style="margin: 0">No plugins installed.</p>`}
        <div class="plugin-loader" style="margin-top: 0.75rem; display: flex; gap: 0.5rem; align-items: center;">
          <input
            type="text"
            class="filter-input"
            placeholder="Plugin URL (e.g., http://localhost:3000/plugin.js)"
            style="flex: 1; padding: 0.4rem 0.625rem; background: var(--surface-secondary); border: 1px solid var(--border-subtle); border-radius: 0.375rem; color: var(--text-primary); font-size: var(--font-size-sm);"
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === 'Enter') void this.loadPluginFromInput();
            }}
          />
          <jf-button .onActivate=${() => void this.loadPluginFromInput()}>
            ${icon({ name: 'folder-plus', size: 12 })} Load
          </jf-button>
        </div>
      </div>
    `;
  }

  private async loadPluginFromInput(): Promise<void> {
    const input = this.shadowRoot?.querySelector('.plugin-loader input') as HTMLInputElement | null;
    const url = input?.value?.trim();
    if (!url) return;
    try {
      this.error = null;
      const manifest = await this.installFromUrl(url);
      this.plugins = getSessionPluginRegistry().list();
      this.recordTrustState(manifest.id, url);
      if (input) input.value = '';
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      const stack = err instanceof Error ? err.stack : undefined;
      this.error = message;
      // Tempdoc 508 §6.2 — dispatch plugin load error to overlay
      const { dispatchPluginError } = await import('../components/PluginErrorOverlay.js');
      dispatchPluginError({ pluginUrl: url, message, stack });
    }
  }

  /**
   * Tempdoc 560 §28 — install a plugin through the real trust path: FirstPartyTrustChannel wrapping
   * RemoteTrustChannel → POST /api/plugins/verify → the persisted operator-allowlist verdict. Shared
   * by the initial load and the post-approval reload so both run the identical channel; an already-
   * fetched `source` is reused (avoids a second fetch and keeps the hashed bytes identical to what was
   * approved). The dev-only first-party marker is the sandboxed stand-in until Sigstore (560 §23); in
   * a production build it is a pure pass-through, so a third-party URL gets the pure backend verdict.
   */
  private async installFromUrl(url: string, source?: string) {
    const { loadPluginFromUrl } = await import('../plugin-api/PluginLoader.js');
    const { RemoteTrustChannel, FirstPartyTrustChannel } = await import(
      '../plugin-api/TrustChannel.js'
    );
    const { resolveApiEndpoint } = await import('../../api/http.js');
    const endpoint = await resolveApiEndpoint();
    const trustChannel = new FirstPartyTrustChannel(new RemoteTrustChannel(endpoint.baseUrl ?? ''));
    // §4.2 — host deps expose the tier-attenuated @kernel/* access path to the plugin.
    const hostDeps = { apiBase: endpoint.baseUrl ?? '', registerSurfacePort: () => {} };
    return loadPluginFromUrl(getSessionPluginRegistry(), url, {
      trustChannel,
      hostDeps,
      ...(source !== undefined ? { sourceFetcher: () => Promise.resolve(source) } : {}),
    });
  }

  /** Tempdoc 560 §28 — record whether a freshly-installed plugin is UNTRUSTED (gates "Approve & trust"). */
  private recordTrustState(id: string, url: string): void {
    const installed = this.plugins.find((p) => p.manifest.id === id);
    const next = new Map(this.untrustedLoads);
    if (installed && installed.trustTier === 'UNTRUSTED_PLUGIN') {
      next.set(id, url);
    } else {
      next.delete(id);
    }
    this.untrustedLoads = next;
  }

  /**
   * Tempdoc 560 §28 — operator approves a URL-loaded UNTRUSTED plugin: fetch its source via the one
   * loader fetch authority, add its artifact SHA-256 to the persisted backend allowlist, then reload
   * it through the same channel so the now-allowlisted verdict returns TRUSTED (its own-element
   * surface becomes admissible under the §4.4 presentation constraint). This is the explicit,
   * auditable trust ceremony — not a client-side tier override.
   */
  private async approveAndTrust(id: string): Promise<void> {
    const url = this.untrustedLoads.get(id);
    if (!url) return;
    try {
      this.error = null;
      const { fetchPluginSource } = await import('../plugin-api/PluginLoader.js');
      const { artifactSha256OfSource } = await import('../plugin-api/TrustChannel.js');
      const source = await fetchPluginSource(url);
      const sha = await artifactSha256OfSource(source);
      const res = await this.doFetch('/api/plugins/allowlist', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ artifactSha256: sha }),
      });
      if (!res.ok) {
        this.error = `Approval failed: HTTP ${res.status}`;
        return;
      }
      // Reload through the same channel: uninstall the UNTRUSTED instance, re-install (now verified).
      const registry = getSessionPluginRegistry();
      if (registry.has(id)) registry.uninstall(id);
      const manifest = await this.installFromUrl(url, source);
      this.plugins = registry.list();
      this.recordTrustState(manifest.id, url);
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private renderPluginRow(p: InstalledPlugin): TemplateResult {
    const errored = p.registerError !== null;
    // §4.1: read the plugin via the unified declaration projection (the FE side of
    // "PluginManifest projected onto the backend Plugin"), not the raw manifest. Pass the stored
    // REGISTRATION tier (CORE for compiled-in, the loader's verdict for URL-loaded) so the badge
    // reflects the real tier; only fall back to signature-presence when no tier was recorded.
    const decl = pluginDeclaration(p.manifest, p.trustTier);
    const trusted = decl.provenance.trustTier !== 'UNTRUSTED_PLUGIN';
    return html`
      <li class="plugin-row">
        <div class="plugin-meta">
          <div class="plugin-id">
            ${p.manifest.id}
            <span class="plugin-version">v${p.manifest.version}</span>
            ${errored
              ? html`<jf-status-badge tone="error" title=${p.registerError?.message ?? ''}
                  >error</jf-status-badge
                >`
              : trusted
                ? html`<jf-status-badge tone="success">trusted</jf-status-badge>`
                : html`<jf-status-badge tone="warning" title="Unsigned / third-party"
                    >untrusted</jf-status-badge
                  >`}
          </div>
          <div class="plugin-display">${decl.presentation.label}</div>
          ${p.manifest.capabilities.surfaces?.length
            ? html`
                <div class="plugin-extras">
                  ${p.manifest.capabilities.surfaces.length} surface(s)
                  ${p.installedTranslationKeys.length > 0
                    ? html` · ${p.installedTranslationKeys.length} i18n key(s)`
                    : nothing}
                </div>
              `
            : nothing}
          ${!trusted && this.untrustedLoads.has(p.manifest.id)
            ? html`<div
                class="plugin-extras"
                style="color: var(--text-warning);"
              >
                Untrusted — its own UI is hidden. Approve to trust this source and load it fully.
              </div>`
            : nothing}
        </div>
        ${!trusted && this.untrustedLoads.has(p.manifest.id)
          ? html`<jf-button
              label="Approve & trust"
              title="Add this plugin's source hash to the operator trust allowlist and reload it as TRUSTED"
              .onActivate=${() => void this.approveAndTrust(p.manifest.id)}
            >
              ${icon({ name: 'shield', size: 14 })} Approve &amp; trust
            </jf-button>`
          : nothing}
        <jf-button
          variant="danger"
          label="Revoke"
          title="Uninstall plugin"
          .onActivate=${() => void this.revokePlugin(p.manifest.id)}
        >
          ${icon({ name: 'trash-2', size: 14 })} Revoke
        </jf-button>
        ${p.manifest.settingsSchema
          ? html`
              <div class="plugin-settings">
                <jf-form
                  .schema=${p.manifest.settingsSchema}
                  .data=${this.host_.settings.getSetting('__all__') ?? {}}
                  @form-change=${(e: CustomEvent<{ data: Record<string, unknown> }>) => {
                    const data = e.detail?.data;
                    if (data) {
                      for (const [k, v] of Object.entries(data)) {
                        this.host_.settings.setSetting(k, v);
                      }
                    }
                  }}
                ></jf-form>
              </div>`
          : nothing}
      </li>
    `;
  }

  private async selectTheme(id: string | null): Promise<void> {
    try {
      this.error = null;
      // selectTheme is a TRUSTED+/CORE write (optional on PluginThemeState since 560 §24); this
      // Settings surface runs at CORE tier so it is present, but guard for the optional type.
      if (id === null) {
        void this.host_.theme.selectTheme?.(null);
      } else {
        await this.host_.theme.selectTheme?.(id);
      }
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Tempdoc 567 §8 #3 — delete a user custom theme from the host (Settings → Appearance), independent
   * of the editor plugin. Deleting the ACTIVE theme reverts to default via the capability's
   * clearActiveTheme (the subscribeActiveTheme listener re-renders); for a non-active theme the catalog
   * changed but no reactive prop did, so request an explicit re-render.
   */
  private async deleteCustomTheme(entry: ThemeCatalogEntry): Promise<void> {
    const confirmed = await this.host_.ui.showConfirmDialog(
      `Delete the custom theme "${entry.displayName}"? This cannot be undone.`,
    );
    if (!confirmed) return;
    this.host_.theme.deleteTheme?.(entry.id);
    this.requestUpdate();
  }

  /**
   * Tempdoc 567 §8 (deferred → built) — begin an inline rename of a custom theme. Opens the row's
   * text input pre-filled with the current label; commit calls the host `renameTheme` capability
   * (displayName-only, id stable). Host-owned management, independent of the editor plugin.
   */
  private beginRenameTheme(entry: ThemeCatalogEntry): void {
    this.error = null;
    this.renamingThemeId = entry.id;
    this.renameDraft = entry.displayName;
  }

  private cancelRenameTheme(): void {
    this.renamingThemeId = null;
    this.renameDraft = '';
  }

  private commitRenameTheme(id: string): void {
    try {
      this.error = null;
      this.host_.theme.renameTheme?.(id, this.renameDraft);
      this.renamingThemeId = null;
      this.renameDraft = '';
      this.requestUpdate();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Tempdoc 567 §8 (deferred → built) — import a custom theme from a pasted JSON declaration (the
   * counterpart to the editor's export-to-clipboard). The host `importTheme` capability validates the
   * tree and holds it to the same seeds+roles authorable surface as a save, so an imported theme can
   * never carry a derived token. On success the new theme joins the catalog's custom layer.
   */
  private importThemeFromDraft(): void {
    try {
      this.error = null;
      this.host_.theme.importTheme?.(this.themeImportDraft);
      this.themeImporting = false;
      this.themeImportDraft = '';
      this.requestUpdate();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Apply current `userConfig.surfaceOrder` to the catalog list,
   * so the user sees the rendered order. Surfaces not in
   * `surfaceOrder` keep catalog order after the ordered set.
   */
  private applyOrderToSurfaces(surfaces: Surface[]): Surface[] {
    const order = this.userConfig.surfaceOrder;
    if (!order || order.length === 0) return surfaces;
    const present = new Map(surfaces.map((s) => [s.id, s]));
    const ordered: Surface[] = [];
    const seen = new Set<string>();
    for (const id of order) {
      const s = present.get(id);
      if (s && !seen.has(id)) {
        ordered.push(s);
        seen.add(id);
      }
    }
    for (const s of surfaces) {
      if (!seen.has(s.id)) ordered.push(s);
    }
    return ordered;
  }

  private moveRailSurface(idx: number, delta: -1 | 1): void {
    const ordered = this.applyOrderToSurfaces(this.railSurfaces);
    const target = idx + delta;
    if (target < 0 || target >= ordered.length) return;
    const next = [...ordered];
    const [moved] = next.splice(idx, 1);
    if (!moved) return;
    next.splice(target, 0, moved);
    this.host_.layout.setSurfaceOrder(next.map((s) => s.id));
  }

  private async revokePlugin(id: string): Promise<void> {
    const ok = await this.host_.ui.showConfirmDialog(
      `This uninstalls plugin "${id}" and removes its surface contributions, ` +
      'overrides, and i18n keys. The plugin must be re-loaded to come back.', {
      confirmLabel: 'Revoke',
    });
    if (!ok) return;
    try {
      this.error = null;
      const registry = getSessionPluginRegistry();
      registry.uninstall(id);
      removePluginSurfaceContributions(id);
      // Refresh local snapshot — `onSurfaceCatalogChange` listener
      // will also fire from `removePluginSurfaceContributions`, but
      // updating now makes the UI feel instantaneous.
      this.plugins = registry.list();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private renderData(): TemplateResult {
    const tauri = this.host_.platform.capabilities.has('native-notifications');
    return html`
      <div class="section">
        <h3>${icon({ name: 'database', size: 12 })} Data</h3>
        <p class="help" style="margin-top: 0">
          If you want to uninstall JustSearch or start fresh, you can delete all local data from
          inside the app.
        </p>
        <div style="margin-top: 0.75rem">
          ${this.deleteState === 'confirming'
            ? this.renderDeleteConfirm()
            : html`<jf-button
                variant="danger"
                label="Delete all local data"
                ?disabled=${!tauri || this.deleting}
                title=${tauri
                  ? 'Closes the app and wipes local data on next start'
                  : 'Available only in the desktop app (Tauri).'}
                .onActivate=${() => this.deleteMachine?.send('REQUEST')}
              >
                ${icon({ name: 'trash-2', size: 14 })}
                ${this.deleting ? 'Closing…' : 'Delete all local data'}
              </jf-button>`}
        </div>
      </div>
    `;
  }

  private renderAppUpdates(): TemplateResult {
    if (!this.host_.platform.capabilities.has('native-notifications')) {
      return html``;
    }
    const status = this.updateStatus;
    const available = status?.state === 'available' && status.availableVersion;
    const repairRequired = status?.state === 'repair_required';
    return html`
      <div class="section" data-testid="settings-app-updates">
        <h3>${icon({ name: 'download', size: 12 })} App updates</h3>
        <p class="help" style="margin-top: 0">
          ${available
            ? `Version ${status.availableVersion} is ready to install.`
            : repairRequired
              ? `The last update needs repair. ${status?.error ?? ''}`
              : status?.state === 'up_to_date'
                ? `JustSearch ${status.currentVersion} is up to date.`
                : `Installed version: ${status?.currentVersion ?? 'unknown'}.`}
        </p>
        <div class="row" style="margin-top: 0.5rem">
          <jf-button
            variant="secondary"
            label="Check for updates"
            ?disabled=${this.updateBusy || repairRequired}
            .onActivate=${() => void this.checkForAppUpdate()}
          >
            ${this.updateBusy && status?.state === 'checking' ? 'Checkingâ€¦' : 'Check for updates'}
          </jf-button>
          ${available
            ? html`<jf-button
                variant="primary"
                label="Install update"
                ?disabled=${this.updateBusy}
                .onActivate=${() => void this.installAppUpdate()}
              >
                Install ${status.availableVersion}
              </jf-button>`
            : nothing}
        </div>
        ${repairRequired
          ? html`<p class="help">
              Re-run the signed installer for
              ${status?.availableVersion ?? 'the target release'}, then restart JustSearch.
            </p>`
          : nothing}
      </div>
    `;
  }

  /**
   * 569 §15 (Move 8) — the declared `confirming` state of the delete ceremony, rendered inline. The
   * typed input feeds the statechart's `typed == true` GUARD: `CONFIRM` only advances to `done`
   * (which runs the bespoke delete) when "DELETE" is typed — otherwise the guard blocks the
   * transition. This is the first BRANCHING, GUARDED statechart driving a real destructive surface
   * flow (the appearance flow was single-state). Replaces the imperative `showConfirmDialog`.
   */
  private renderDeleteConfirm(): TemplateResult {
    const typed = this.confirmText.trim().toUpperCase() === 'DELETE';
    return html`
      <div role="group" aria-label="Confirm delete all local data">
        <p class="help" style="margin-top: 0">
          This closes JustSearch and deletes your local index, settings, and logs on next launch
          (your AI models in AI Home are preserved). Type <strong>DELETE</strong> to confirm.
        </p>
        <div
          style="margin-top:0.5rem; padding:0.6rem; background:var(--accent-warning-16); border-radius:0.25rem"
        >
          <!-- Tempdoc 629 (#protect-on-delete): the AUTHORED stores, if encrypted, are NOT rebuildable,
               so the uniform wipe is permanent. Point to the encrypted export (in Security & Privacy)
               first so the user can restore them later. -->
          If you've encrypted your chat history, memories, or agent history, they
          <strong>can't be rebuilt</strong> — export an encrypted backup first so you can restore them
          later.
          <div style="margin-top:0.5rem">
            <jf-button
              variant="secondary"
              .onActivate=${() => this.host_.navigation.navigate('core.security-surface')}
              >Open Security &amp; Privacy to export</jf-button
            >
          </div>
        </div>
        <input
          type="text"
          aria-label="Type DELETE to confirm"
          placeholder="DELETE"
          style="padding:0.4rem 0.6rem; border-radius:0.375rem; border:1px solid var(--border-subtle); background:var(--surface-1); color:var(--text-primary); font:inherit; min-inline-size:12rem"
          .value=${this.confirmText}
          @input=${(e: Event) => (this.confirmText = (e.target as HTMLInputElement).value)}
        />
        <div style="display:flex; gap:0.5rem; margin-top:0.5rem">
          <jf-button
            variant="danger"
            label="Delete & Close"
            ?disabled=${!typed}
            .onActivate=${() => this.deleteMachine?.send('CONFIRM', { typed })}
          >
            ${icon({ name: 'trash-2', size: 14 })} Delete &amp; Close
          </jf-button>
          <jf-button
            variant="ghost"
            label="Cancel"
            .onActivate=${() => {
              this.confirmText = '';
              this.deleteMachine?.send('CANCEL');
            }}
          >
            Cancel
          </jf-button>
        </div>
      </div>
    `;
  }

  /**
   * Tempdoc 571 §11 / 578 (post-review fix B) — the RAIL surfaces for the rail-customization UI, with
   * host MEMBERS excluded (a member's home is its host, so it never appears on the rail; mirrors
   * Shell.refreshSurfaces). Source-agnostic: excludes members regardless of their declared placement.
   */
  private static railSurfacesForCustomization(): Surface[] {
    const all = listSurfaces();
    const memberIds = new Set(all.flatMap((s) => s.members ?? []));
    return all.filter((s) => s.placement === 'RAIL' && !memberIds.has(s.id));
  }

  override render(): TemplateResult {
    // Tempdoc 855 §9.3 — the settings window is register-driven: `<jf-settings-nav>` projects the
    // ONE declared tree (`settingsRegister.ts`) into groups/categories/sub-anchors, and this
    // render() projects the ACTIVE category into content — the same register both sides read, so
    // nav and content cannot drift.
    const category = findCategory(this.activeCategory) ?? allCategories()[0];
    if (!category) return html``;
    return html`
      <div class="settings-root">
        <div class="header">
          <div>
            <h2>Settings</h2>
            <p class="subtitle">Customize your experience</p>
          </div>
          <div class="row">
            ${this.readOnly
              ? html`<jf-status-badge tone="warning">Session-only</jf-status-badge>`
              : nothing}
            ${this.saving
              ? html`<span style="font-size: var(--font-size-xs); color: var(--text-tint)">Saving…</span>`
              : nothing}
            ${!this.readOnly
              ? html`<span
                  @op-success=${() => this.handleResetSuccess()}
                  @op-error=${(e: CustomEvent<OpErrorEventDetail>) =>
                    this.handleResetError(e)}
                >
                  <jf-operation
                    operation-id="core.reset-settings"
                    context="button"
                    api-base=${this.apiBase}
                  ></jf-operation>
                </span>`
              : nothing}
          </div>
        </div>
        ${this.error
          ? html`<jf-error-alert tone="error" .onDismiss=${() => (this.error = null)}>
              <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
              ${this.error}
            </jf-error-alert>`
          : nothing}
        <div class="settings-shell">
          <jf-settings-nav
            .register=${SETTINGS_REGISTER}
            active-category=${this.activeCategory}
            .activeAnchor=${this.activeAnchor}
            .footerVersion=${this.updateStatus?.currentVersion
              ? `Version ${this.updateStatus.currentVersion}`
              : null}
            @category-select=${(e: CustomEvent<{ id: string }>) => this.selectCategory(e.detail.id)}
            @anchor-jump=${(e: CustomEvent<{ key: string }>) => this.jumpToAnchor(e.detail.key)}
            @search-select=${(e: CustomEvent<{ categoryId: string; sectionKey?: string }>) =>
              this.activateSearchHit(e.detail.categoryId, e.detail.sectionKey)}
          ></jf-settings-nav>
          ${this.renderCategoryContent(category)}
        </div>
      </div>
    `;
  }

  /** Tempdoc 855 §9.3 — the active category's content: a native category renders its declared
   *  section subset (in the centered reading column); a member category mounts its catalog
   *  surface full-bleed (its own SurfaceLayout owns its width, like `SurfaceTabs.renderPanel`). */
  private renderCategoryContent(category: SettingsCategory): TemplateResult {
    if (category.kind === 'member' && category.memberSurfaceId) {
      return html`
        <div class="settings-content-pane member">${this.renderMemberCategory(category.memberSurfaceId)}</div>
      `;
    }
    // A native category rendering drops the member-element cache, so returning to a member
    // category re-mounts it fresh — the same reset `SurfaceTabs.renderPanel` does on its
    // slot branch. Without this, a member with non-idempotent connect/disconnect would
    // silently show stale content (review finding, 855 P1).
    this._activeMemberEl = null;
    this._activeMemberElId = null;
    const sections = (category.sections ?? []).filter((s) => !s.gate || s.gate());
    return html`
      <div class="settings-content-pane">
        <div class="settings-content-inner">
          ${sections.map((s) => this.renderRegisteredSection(s))}
        </div>
      </div>
    `;
  }

  /** One native sub-anchor: `data-settings-anchor` is both the scroll-spy landmark id and the
   *  `<jf-settings-nav>` sub-anchor's jump target — the SAME key the register declares. */
  private renderRegisteredSection(entry: SettingsSectionEntry): TemplateResult {
    const renderer = this.sectionRenderers()[entry.key];
    return html`<div data-settings-anchor=${entry.key}>${renderer ? renderer() : nothing}</div>`;
  }

  /** Tempdoc 855 §9.3 — the SettingsSurface-side projection of the register: each declared native
   *  `key` dispatches to the existing section render method it always called (unchanged bodies —
   *  the redesign re-parents sections, it does not rewrite them). */
  private sectionRenderers(): Record<string, () => TemplateResult | typeof nothing> {
    return {
      interface: () => this.renderInterfaceRegion(),
      theme: () => this.renderThemes(),
      accessibility: () => this.renderAccessibility(),
      'chat-width': () => this.renderChatWidth(),
      'token-editor': () => this.renderTokenEditorLink(),
      layout: () => this.renderLayout(),
      rail: () => this.renderRail(),
      keyboard: () => this.renderKeyboard(),
      'agent-autonomy': () => this.renderAutonomyDial(),
      'context-window': () => this.renderContextWindow(),
      plugins: () => this.renderPlugins(),
      'plugin-permissions': () => this.renderPluginPermissions(),
      'durable-grants': () => this.renderDurableGrants(),
      'delivered-contributions': () => this.renderWitness(),
      desktop: () => this.renderDesktop(),
      'app-updates': () => this.renderAppUpdates(),
      'feedback-capture': () => this.renderFeedbackCaptureSection(),
      'view-tier': () => this.renderViewerAudience(),
      'workspace-profiles': () => this.renderWorkspaceProfilesDeveloper(),
      data: () => this.renderData(),
    };
  }

  /** Tempdoc 855 §4 — Feedback capture moves from nested-inside-Security-pointer to its own
   *  App → Desktop sub-anchor. `renderFeedbackCapture()` itself (the control body) is untouched;
   *  this only gives it the same titled `.section` wrapper its sibling sections have. */
  private renderFeedbackCaptureSection(): TemplateResult | typeof nothing {
    const inner = this.renderFeedbackCapture();
    if (inner === nothing) return nothing;
    return html`
      <div class="section" data-testid="settings-feedback-capture">
        <h3>${icon({ name: 'history', size: 12 })} Feedback capture</h3>
        ${inner}
      </div>
    `;
  }

  /** Tempdoc 855 §9.3 — member-category mount, the same `mountSurface` lazy-load + mount-on-
   *  activation pattern `SurfaceTabs.renderPanel` uses (§11.3), scoped to just this one category
   *  (no cross-category cache: switching category away and back re-mounts, tearing down streams). */
  private renderMemberCategory(surfaceId: string): TemplateResult {
    const surface = getSurface(surfaceId);
    if (!surface) return html`<div class="empty-member">Unknown surface: ${surfaceId}</div>`;
    const tag = surface.mountTag;
    if (isLazySurface(tag) && !customElements.get(tag)) {
      void ensureSurfaceLoaded(tag);
      void customElements.whenDefined(tag).then(() => this.requestUpdate());
      return html`<div class="empty-member">Loading…</div>`;
    }
    if (this._activeMemberElId !== surfaceId || this._activeMemberEl === null) {
      this._activeMemberEl = mountSurface(surface, { apiBase: this.apiBase, host_: this.host_ });
      this._activeMemberElId = surfaceId;
    }
    return html`${this._activeMemberEl ?? html`<div class="empty-member">Cannot mount ${surfaceId}.</div>`}`;
  }

  /** Tempdoc 855 §9.3 — switch the active category: reset scroll to top (a fresh page) and let the
   *  scroll-spy re-measure the new category's sub-anchors. */
  private selectCategory(id: string): void {
    if (id === this.activeCategory) return;
    this.activeCategory = id;
    this.activeAnchor = null;
    void this.updateComplete.then(() => {
      const conv = this.settingsScrollEl();
      if (conv) conv.scrollTop = 0;
      this.setupAnchorObservers();
    });
  }

  /** Tempdoc 855 §6 Phase 4 — a `<jf-settings-nav>` search-result activation: reuses `selectCategory`
   *  and `jumpToAnchor` exactly as `category-select`/`anchor-jump` do, just composed from one event.
   *  A category-only hit (no `sectionKey`) is just `selectCategory`. A section hit in the category
   *  that's ALREADY active jumps immediately (the content is already rendered); a section hit in a
   *  DIFFERENT category must wait for that category's content to render first — `selectCategory`
   *  schedules its own `updateComplete` continuation (scrollTop reset + anchor-observer rewire), so
   *  this chains a second one that runs after it in the same microtask batch (registration order). */
  private activateSearchHit(categoryId: string, sectionKey?: string): void {
    const switchingCategory = categoryId !== this.activeCategory;
    this.selectCategory(categoryId);
    if (!sectionKey) return;
    if (switchingCategory) {
      void this.updateComplete.then(() => this.jumpToAnchor(sectionKey));
    } else {
      this.jumpToAnchor(sectionKey);
    }
  }

  /** Tempdoc 855 §9.5 — click-jump from the nav: scroll the content pane to the sub-anchor,
   *  honoring reduced-motion (mirrors `NavigationController.jumpTo`). */
  private jumpToAnchor(key: string): void {
    const conv = this.settingsScrollEl();
    if (!conv) return;
    const target = conv.querySelector(`[data-settings-anchor="${key}"]`) as HTMLElement | null;
    if (!target) return;
    const reduce =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    target.scrollIntoView({ block: 'start', behavior: reduce ? 'auto' : 'smooth' });
    this.activeAnchor = key;
  }

  private settingsScrollEl(): HTMLElement | null {
    return (this.shadowRoot?.querySelector('.settings-content-pane') as HTMLElement | null) ?? null;
  }

  private readonly onAnchorScroll = (): void => {
    if (this.anchorScrollRaf) return;
    this.anchorScrollRaf = true;
    requestAnimationFrame(() => {
      this.anchorScrollRaf = false;
      this.measureAnchors();
    });
  };

  /** Tempdoc 855 §9.5 — (re)wire the scroll + resize observers onto the current content pane.
   *  Idempotent: a no-op re-measure when the pane element hasn't changed identity (a category
   *  switch between two NATIVE categories reuses the same `.settings-content-pane` node). */
  private setupAnchorObservers(): void {
    const el = this.settingsScrollEl();
    if (!el) {
      this.teardownAnchorObservers();
      return;
    }
    if (this.anchorScrollEl !== el) {
      this.teardownAnchorObservers();
      this.anchorScrollEl = el;
      el.addEventListener('scroll', this.onAnchorScroll, { passive: true });
      if (typeof ResizeObserver !== 'undefined') {
        this.anchorResizeObserver = new ResizeObserver(() => this.measureAnchors());
        this.anchorResizeObserver.observe(el);
      }
    }
    this.measureAnchors();
  }

  private teardownAnchorObservers(): void {
    this.anchorScrollEl?.removeEventListener('scroll', this.onAnchorScroll);
    this.anchorResizeObserver?.disconnect();
    this.anchorResizeObserver = null;
    this.anchorScrollEl = null;
  }

  /** Tempdoc 855 §9.5 — the house derived-focus math (`primitives/navigation.ts`), NOT
   *  IntersectionObserver: measure every `[data-settings-anchor]` child's 0..1 scroll extent, then
   *  derive the topmost one with ≥10% of itself in the viewport window. */
  private measureAnchors(): void {
    const conv = this.anchorScrollEl;
    if (!conv) return;
    const vp = viewportWindow(conv.scrollTop, conv.clientHeight, conv.scrollHeight);
    const convTop = conv.getBoundingClientRect().top;
    const scrollH = conv.scrollHeight || 1;
    const clamp = (f: number): number => Math.min(1, Math.max(0, f));
    const landmarks: Landmark[] = [];
    conv.querySelectorAll('[data-settings-anchor]').forEach((el) => {
      const id = el.getAttribute('data-settings-anchor');
      if (!id) return;
      const rect = (el as HTMLElement).getBoundingClientRect();
      if (rect.height === 0) return;
      const top = rect.top - convTop + conv.scrollTop;
      landmarks.push({
        id,
        extent: { topFrac: clamp(top / scrollH), botFrac: clamp((top + rect.height) / scrollH) },
      });
    });
    const next = deriveFocus(landmarks, vp) ?? landmarks[0]?.id ?? null;
    if (next !== this.activeAnchor) {
      this.activeAnchor = next;
    }
  }

  /**
   * Tempdoc 543 §20.7 B6 — Workspace Profiles substrate developer
   * affordance. Gated to DEVELOPER audience; shows: save current
   * snapshot as a named profile, list known profiles, activate one.
   * Minimal viable consumer for Slice 10's substrate; full UX is a
   * future product slice.
   */
  /**
   * §32 U1 — Agent autonomy dial. Shown to all audiences (a primary
   * agent-safety control). The destructive-op gate is backend-enforced
   * regardless of the setting.
   */
  private renderAutonomyDial(): TemplateResult {
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Agent autonomy</h3>
        <p class="toggle-desc">
          How much the assistant acts on its own. Destructive actions are
          always confirmed regardless of this setting (backend-enforced).
        </p>
        <jf-autonomy-dial></jf-autonomy-dial>
      </div>
    `;
  }

  /**
   * Tempdoc 883 D-A.7 / ADR-0047 — the context window is a DERIVED resource, so this section is a
   * readout first and a control second.
   *
   * The readout's source is the running engine (`/api/inference/status`, via the shared AI store),
   * NOT `llm.contextWindow`: the stored value is `0` (auto) on virtually every installation, so
   * rendering the setting would show "0" where the user wants "32,768". It projects the SAME
   * `core.ai.contextWindow` fact the Brain surface renders rather than re-formatting the numbers
   * here — one Display-value authority (594 §9.2), so the two readouts cannot word it differently.
   *
   * The override exists because a derived resource still needs an escape hatch, and it is labelled
   * as what it is: honoured verbatim or the launch fails loudly. There is no silent fallback — an
   * explicit `justsearch.context.size` is a ONE-RUNG ladder, so a value the card cannot hold aborts
   * the launch instead of quietly stepping down (ADR-0047).
   */
  private renderContextWindow(): TemplateResult {
    const override = this.contextOverrideTokens();
    const fact = this.aiSnapshot ? projectFact('core.ai.contextWindow', this.aiSnapshot) : null;
    // Tri-state, deliberately: `present` is a real launched window; `absent` is a polled engine
    // that reports none (offline / nothing launched); null-or-`unknown` is NOT-YET-POLLED and must
    // not be rendered as "no window" (594 §11.3 #3 — never fabricate the settled case).
    const launched =
      fact && fact.presence === 'present' && fact.value
        ? fact.value
        : fact && fact.presence === 'absent'
          ? null
          : undefined;
    return html`
      <div class="section" data-testid="settings-context-window">
        <h3>${icon({ name: 'layers', size: 12 })} Context window</h3>
        <p class="toggle-desc" data-testid="context-window-readout">
          ${override > 0
            ? html`Override ${formatCount(override)} tokens${launched
                  ? html` &rarr; ${launched}`
                  : nothing}`
            : launched
              ? html`Auto &rarr; ${launched}`
              : launched === null
                ? html`Auto &mdash; derived when the assistant starts`
                : html`Auto &mdash; checking&hellip;`}
        </p>
        <div class="plugin-loader" style="margin-top: 0.5rem; display: flex; gap: 0.5rem; align-items: center;">
          <label for="context-window-override">Override</label>
          <input
            id="context-window-override"
            type="number"
            class="filter-input context-window-override"
            min="512"
            step="512"
            placeholder="Auto"
            aria-describedby="context-window-help"
            ?disabled=${this.readOnly}
            .value=${live(override > 0 ? String(override) : '')}
            @change=${(e: Event) =>
              void this.saveContextOverride((e.target as HTMLInputElement).value)}
          />
        </div>
        <p class="help" id="context-window-help" style="margin: 0.5rem 0 0">
          Leave blank for Auto: the window is derived from your hardware each time the assistant
          starts, and the readout above says which size it got and why. An override is honoured
          <em>verbatim</em> &mdash; if the graphics card cannot hold it the assistant fails to start
          and says so, rather than quietly falling back to a smaller window. Minimum 512 tokens.
        </p>
      </div>
    `;
  }

  /** The stored override in tokens; `0` means auto (the wire's own encoding — `UiSettings`). */
  private contextOverrideTokens(): number {
    const v = this.llm.contextWindow;
    return typeof v === 'number' && v > 0 ? v : 0;
  }

  /**
   * Persist the context-window override. Blank / non-numeric / non-positive all mean AUTO (`0`) —
   * the same collapse `UiSettings.setContextLength` performs, so clearing the field is a real
   * "back to auto" rather than an unrepresentable state. A positive value is floored at 512 here
   * too, so the field cannot post a number the backend will silently rewrite.
   *
   * Written straight through `POST /api/settings/v2` (not the appearance statechart, which owns the
   * `ui` block only). The body carries just the `llm` slice; `SettingsPatch.merge` merges
   * field-by-field, so nothing else is clobbered.
   */
  private async saveContextOverride(raw: string): Promise<void> {
    if (this.readOnly) return;
    const previous = this.llm;
    const parsed = Number.parseInt(raw.trim(), 10);
    const next = Number.isFinite(parsed) && parsed > 0 ? Math.max(512, parsed) : 0;
    this.llm = { ...this.llm, contextWindow: next };
    this.saving = true;
    try {
      await saveAbsoluteSettings((path, init) => this.doFetch(path, init), { llm: { contextWindow: next } });
    } catch (err) {
      this.llm = previous;
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.saving = false;
    }
  }

  /**
   * §28.W3 / §14.3 δ5 — Plugin permissions management UI.
   *
   * Renders the list of recorded consent grants (from
   * substrates/consent/) with revoke + change-decision affordances.
   * Closes the §14.3 β4 "manage plugin permissions" requirement — the
   * per-request prompt already exists via the AuthorizationHost ceremony
   * surface (tempdoc 550 G9, formerly ConsentHost); this is the
   * central management screen for already-granted consents.
   *
   * Visible to OPERATOR + DEVELOPER audiences; USER audience hides
   * the panel (consent management is a power-user affordance).
   */
  // ── Durable grants (tempdoc 560 §28 / 4d) ───────────────────────────────────────────────────────

  private async loadDurableGrants(): Promise<void> {
    try {
      const res = await this.doFetch('/api/authorizations/grants');
      if (!res.ok) return;
      const data = (await res.json()) as {
        grants?: Array<{ kind: string; target: string; sourceTier: string }>;
      };
      this.durableGrants = data.grants ?? [];
    } catch {
      // Best-effort; the panel simply shows no grants if the backend is unreachable.
    }
  }

  private async revokeDurableGrant(
    kind: string,
    target: string,
    sourceTier: string,
  ): Promise<void> {
    try {
      const res = await this.doFetch('/api/authorizations/grants', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ kind, target, sourceTier }),
      });
      if (res.ok) await this.loadDurableGrants();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private async grantFamilyFromInput(): Promise<void> {
    const input = this.shadowRoot?.querySelector(
      '.grant-family-input',
    ) as HTMLInputElement | null;
    const family = input?.value?.trim();
    if (!family) return;
    try {
      const res = await this.doFetch('/api/authorizations/grants', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // The agent loop runs as UNTRUSTED, so a family grant for that tier is what auto-approves it.
        body: JSON.stringify({ kind: 'FAMILY', target: family, sourceTier: 'UNTRUSTED' }),
      });
      if (res.ok) {
        if (input) input.value = '';
        await this.loadDurableGrants();
      }
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private renderDurableGrants(): TemplateResult {
    return html`
      <div class="section" data-testid="settings-durable-grants">
        <h3>Durable grants</h3>
        <p class="help" style="margin: 0 0 0.5rem">
          "Allow always" approvals the trust gate honors without re-prompting — per operation, or for a
          whole capability family (e.g. <code>file-operations</code>). They persist across restarts.
          Destructive (high-risk) actions are never covered: they ask every time. Nor is a file action
          that reaches outside your indexed folders — that one asks, naming the path.
        </p>
        ${this.durableGrants.length === 0
          ? html`<p class="help" style="margin: 0">No durable grants.</p>`
          : html`<ul class="plugin-list">
              ${this.durableGrants.map(
                (g) => html`
                  <li class="plugin-row">
                    <div class="plugin-meta">
                      <div class="plugin-id">
                        ${g.target}
                        <span class="plugin-version"
                          >${g.kind === 'FAMILY' ? 'family' : 'operation'} · ${g.sourceTier}</span
                        >
                      </div>
                    </div>
                    <jf-button
                      variant="danger"
                      label="Revoke"
                      .onActivate=${() =>
                        void this.revokeDurableGrant(g.kind, g.target, g.sourceTier)}
                    >
                      ${icon({ name: 'trash-2', size: 14 })} Revoke
                    </jf-button>
                  </li>
                `,
              )}
            </ul>`}
        <div
          class="plugin-loader"
          style="margin-top: 0.5rem; display: flex; gap: 0.5rem; align-items: center;"
        >
          <input
            type="text"
            class="filter-input grant-family-input"
            placeholder="Capability family (e.g. file-operations)"
            style="flex: 1; padding: 0.4rem 0.625rem; background: var(--surface-secondary); border: 1px solid var(--border-subtle); border-radius: 0.375rem; color: var(--text-primary); font-size: var(--font-size-sm);"
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === 'Enter') void this.grantFamilyFromInput();
            }}
          />
          <jf-button .onActivate=${() => void this.grantFamilyFromInput()}>
            ${icon({ name: 'shield', size: 12 })} Grant family
          </jf-button>
        </div>
      </div>
    `;
  }

  // ── Run-tier witness (tempdoc 560 §28 Phase 3) ──────────────────────────────────────────────────

  private async loadWitness(): Promise<void> {
    try {
      const res = await this.doFetch('/api/registry/witness');
      if (!res.ok) return;
      const data = (await res.json()) as {
        entries?: Array<{
          kind: string;
          id: string;
          owner: string | null;
          buildWitnessed: boolean;
        }>;
      };
      this.witnessEntries = data.entries ?? [];
    } catch {
      // Best-effort observability; the panel shows nothing if the backend is unreachable.
    }
  }

  private renderWitness(): TemplateResult {
    const runtimeOnly = this.witnessEntries.filter((e) => !e.buildWitnessed).length;
    return html`
      <div class="section section-composite" data-testid="settings-delivered-contributions">
        <h3>Delivered contributions</h3>
        <p class="help" style="margin: 0 0 0.5rem">
          The live composed registry: every <em>operation</em> from all sources (core, agent-tools,
          workflows, MCP, plugins) plus all <em>plugin-contributed</em> surfaces, resources, prompts,
          channels and shapes. <strong>${runtimeOnly}</strong> ${runtimeOnly === 1 ? 'is' : 'are'}
          runtime-only (live, but absent from the build-time witness snapshot). Core surfaces/resources
          are served at <code>/api/registry/*</code>, not duplicated here. Read-only observability.
        </p>
        ${this.witnessEntries.length === 0
          ? html`<p class="help" style="margin: 0">No live contributions reported.</p>`
          : html`<ul class="plugin-list">
              ${this.witnessEntries.map(
                (e) => html`
                  <li class="plugin-row">
                    <div class="plugin-meta">
                      <div class="plugin-id">
                        ${e.id}
                        <span class="plugin-version"
                          >${e.kind}${e.owner ? ` · ${e.owner}` : ''}</span
                        >
                        ${e.buildWitnessed
                          ? nothing
                          : html`<jf-status-badge
                              tone="warning"
                              title="Live but absent from the build-time witness snapshot"
                              >runtime-only</jf-status-badge
                            >`}
                      </div>
                    </div>
                  </li>
                `,
              )}
            </ul>`}
      </div>
    `;
  }

  private renderPluginPermissions(): TemplateResult | typeof nothing {
    if (this.viewerAudience === 'USER') return nothing;
    return html`
      <div class="section">
        <h3>${icon({ name: 'shield', size: 12 })} Plugin permissions</h3>
        <p class="toggle-desc">
          Tempdoc 543 §14.3 β4 / §28.W3 — manage capabilities you've granted
          to plugins. Per-request prompts surface in the lower-left when a
          plugin requests permission; this panel shows the persisted decisions.
        </p>
        ${this.consents.length === 0
          ? html`<p class="toggle-desc">No consent decisions recorded yet.</p>`
          : html`
              <table style="width:100%;border-collapse:collapse;font-size: var(--font-size-sm);">
                <thead>
                  <tr style="text-align:left;color:var(--text-secondary);">
                    <th style="padding:0.25rem 0.5rem;">Plugin</th>
                    <th style="padding:0.25rem 0.5rem;">Capability</th>
                    <th style="padding:0.25rem 0.5rem;">Decision</th>
                    <th style="padding:0.25rem 0.5rem;">Granted</th>
                    <th style="padding:0.25rem 0.5rem;"></th>
                  </tr>
                </thead>
                <tbody>
                  ${this.consents.map(
                    (c) => html`
                      <tr data-consent-row="${c.contributorId}:${c.capability}">
                        <td style="padding:0.25rem 0.5rem;font-family:var(--font-mono);">${c.contributorId}</td>
                        <td style="padding:0.25rem 0.5rem;font-family:var(--font-mono);">${c.capability}</td>
                        <td style="padding:0.25rem 0.5rem;">
                          <select
                            data-testid="consent-decision-select"
                            .value=${c.decision}
                            @change=${(e: Event) =>
                              void this.handleConsentDecisionChange(
                                c.contributorId,
                                c.capability,
                                (e.target as HTMLSelectElement).value,
                              )}
                          >
                            <option value="allow-always" ?selected=${c.decision === 'allow-always'}>allow-always</option>
                            <option value="deny" ?selected=${c.decision === 'deny'}>deny</option>
                          </select>
                        </td>
                        <td style="padding:0.25rem 0.5rem;color:var(--text-tertiary);">
                          ${new Date(c.decidedAt).toLocaleString()}
                        </td>
                        <td style="padding:0.25rem 0.5rem;">
                          <button
                            class="option-btn"
                            @click=${() =>
                              void this.handleRevokeConsent(c.contributorId, c.capability)}
                          >
                            Revoke
                          </button>
                        </td>
                      </tr>
                    `,
                  )}
                </tbody>
              </table>
            `}
      </div>
    `;
  }

  private async handleRevokeConsent(
    contributorId: string,
    capability: string,
  ): Promise<void> {
    const { revokeConsent, listAllConsents } = await import(
      '../substrates/consent/index.js'
    );
    revokeConsent(contributorId, capability);
    this.consents = [...listAllConsents()];
    this.requestUpdate();
  }

  private async handleConsentDecisionChange(
    contributorId: string,
    capability: string,
    decision: string,
  ): Promise<void> {
    if (decision !== 'allow-always' && decision !== 'deny') return;
    const { recordConsent, listAllConsents } = await import(
      '../substrates/consent/index.js'
    );
    recordConsent(contributorId, capability, decision);
    this.consents = [...listAllConsents()];
    this.requestUpdate();
  }

  private renderWorkspaceProfilesDeveloper(): TemplateResult | typeof nothing {
    if (this.viewerAudience !== 'DEVELOPER') return nothing;
    return html`
      <div class="section section-composite">
        <h3>${icon({ name: 'layers', size: 12 })} Workspace Profiles (developer)</h3>
        <p class="toggle-desc">
          Tempdoc 543 §13.6 substrate — snapshot the current Scope into
          a named WorkspaceProfile; switch back later to restore. §25.ζ#4
          picker lets you choose a parent profile so the new profile
          inherits manifest set + Scope under set-arithmetic semantics.
        </p>
        <div class="toggle-row">
          <select
            data-testid="workspace-profile-inherits-from"
            @change=${(e: Event) => {
              this.snapshotInheritsFrom = (e.target as HTMLSelectElement).value;
              this.requestUpdate();
            }}
          >
            <option value="">— No parent (flat profile) —</option>
            ${this.workspaceProfiles.map(
              (p) => html`<option value=${p.id} ?selected=${this.snapshotInheritsFrom === p.id}>${p.label}</option>`,
            )}
          </select>
          <button
            class="option-btn"
            @click=${() => void this.handleSnapshotWorkspaceProfile()}
          >
            Snapshot current as Profile
          </button>
          <select
            data-testid="workspace-profile-switcher"
            @change=${(e: Event) => {
              const id = (e.target as HTMLSelectElement).value;
              if (id) void this.handleActivateWorkspaceProfile(id);
            }}
          >
            <option value="">— Activate a profile —</option>
            ${this.workspaceProfiles.map(
              (p) => html`<option value=${p.id}>${p.label}</option>`,
            )}
          </select>
        </div>
      </div>
    `;
  }

  private async handleSnapshotWorkspaceProfile(): Promise<void> {
    const { createProfileFromCurrent, listProfiles: listWorkspaceProfiles } =
      await import('../substrates/profiles/index.js');
    const id = `dev.profile.${Date.now()}`;
    const label = `Snapshot @ ${new Date().toLocaleTimeString()}`;
    // §25.ζ#4 — propagate the picker selection. createProfileFromCurrent's
    // 3rd-arg `overrides` accepts `inheritsFrom`.
    const overrides: { description: string; inheritsFrom?: string } = {
      description: 'Created via SettingsSurface developer affordance',
    };
    if (this.snapshotInheritsFrom) {
      overrides.inheritsFrom = this.snapshotInheritsFrom;
    }
    createProfileFromCurrent(id, label, overrides);
    this.workspaceProfiles = [...listWorkspaceProfiles()];
    this.requestUpdate();
  }

  private async handleActivateWorkspaceProfile(id: string): Promise<void> {
    const { activateProfile } = await import('../substrates/profiles/index.js');
    try {
      await activateProfile(id);
    } catch (err) {
      console.warn(`[SettingsSurface] activateProfile('${id}') failed`, err);
    }
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-settings-surface')) {
  customElements.define('jf-settings-surface', SettingsSurface);
}
