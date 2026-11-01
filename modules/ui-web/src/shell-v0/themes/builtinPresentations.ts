// SPDX-License-Identifier: Apache-2.0
/**
 * 569 — built-in Presentation Declarations (the team-default origin).
 *
 * A built-in declaration is the SAME artifact as a user- or LLM-authored one with a
 * different origin (Move 1). These ship in the binary and are the reference content the
 * apply path renders through the Move-3 engine — the proof that a REAL surface region is a
 * projection of an external declaration, not hand-written Lit.
 *
 * `SETTINGS_DECLARED` drives the Settings surface's Interface + Appearance region: its body
 * is a JSON-Schema + UI-Schema over the renderer vocabulary (the same vocabulary `<jf-form>`
 * dispatches), so the region's content is data, and the engine co-projects the accessible,
 * operable controls + the 558 contrast-safe colours the author never touches.
 */
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import type { SurfaceBodyDeclaration } from '../components/DeclaredSurface.js';
import type { PresentationDeclaration } from './presentationDeclaration.js';
import type { InteractionStatechart } from '../substrates/interaction/index.js';
import type { SwatchSpec } from '../renderers/controls/OptionButtonGroupRenderer.js';

/** The region id the Settings surface exposes for declaration-driving (Phase 1 keystone). */
export const SETTINGS_INTERFACE_REGION = 'core.settings.interface';

/**
 * Tempdoc 855 fix-round F1 — the theme-variant trio's literal (non-token) MODE-concept colors,
 * exported so BOTH the declared `theme.x-enum-swatches` below AND `SettingsSurface`'s hand-authored
 * `renderVariantOptions()` fallback render off the SAME source, not two independently authored
 * copies that could drift. System/Dark/Light depict the MODE concept, not a theme palette, so these
 * intentionally do NOT read from theme tokens (which vary per active theme).
 */
export const THEME_VARIANT_DARK = '#1a1a1e';
export const THEME_VARIANT_LIGHT = '#f4f4f5';

/**
 * Data shape of the Settings Interface + Appearance region (a subset of UISettings). Each control
 * carries an `x-ui-renderer` hint (569 Fix 1) so the engine renders it through the bespoke-quality
 * renderers (option-button grid / switch) — matching the hand-authored look, which is what lets
 * this declaration be the DEFAULT render with no visual downgrade. The `x-enum-labels` /
 * `x-enum-descriptions` carry the human text the bespoke buttons showed (full parity); fix-round F1
 * adds `x-enum-swatches` on `theme` so the declared picker paints the same swatch trio the
 * hand-authored path always had (previously the declared path rendered no swatches at all — the
 * schema branch of `OptionButtonGroupRenderer` hardcoded an empty swatch map).
 *
 * `highContrast` is deliberately NOT declared here (tempdoc 855 §15.4/§17 R1). It used to be, which
 * made this region a SECOND render of the same toggle Appearance hand-authored — and the two of them
 * a second authority over the `high-contrast` root class. The one visible control now lives in the
 * Accessibility section and writes through `SettingsSurface.patch()`, so `APPEARANCE_FLOW`'s
 * `HC_ON`/`HC_OFF` edges below have exactly one producer again. Do not re-add it here.
 */
const THEME_VARIANT_SWATCHES: Record<'system' | 'dark' | 'light', SwatchSpec> = {
  system: { split: [THEME_VARIANT_DARK, THEME_VARIANT_LIGHT] },
  dark: { fill: THEME_VARIANT_DARK },
  light: { fill: THEME_VARIANT_LIGHT },
};
const SETTINGS_INTERFACE_SCHEMA = {
  type: 'object',
  properties: {
    mode: {
      type: 'string',
      enum: ['simple', 'advanced'],
      title: 'Interface mode',
      'x-ui-renderer': 'option-button-group',
      // Tempdoc 738 — "Detailed" is the user-facing label for the `advanced` mode, matching the
      // surfaced topbar Simple/Detailed toggle (the underlying mode value stays `advanced`).
      'x-enum-labels': { simple: 'Simple', advanced: 'Detailed' },
      'x-enum-descriptions': {
        simple: 'Standard view',
        advanced: 'Full controls + diagnostics',
      },
    },
    theme: {
      type: 'string',
      enum: ['system', 'dark', 'light'],
      title: 'Appearance',
      'x-ui-renderer': 'option-button-group',
      'x-enum-labels': { system: 'System', dark: 'Dark', light: 'Light' },
      'x-enum-descriptions': {
        system: 'Follow OS',
        dark: 'Default theme',
        light: 'Bright theme',
      },
      // Fix-round F1 — paints the same swatch trio `SettingsSurface.renderVariantOptions()` paints
      // on the hand fallback path; shared source (`THEME_VARIANT_SWATCHES` above), not a second copy.
      'x-enum-swatches': THEME_VARIANT_SWATCHES,
    },
    vimMode: {
      type: 'boolean',
      title: 'Vim keybindings',
      description: 'Vim-style navigation',
      'x-ui-renderer': 'toggle-switch',
    },
    defaultAction: {
      type: 'string',
      enum: ['open', 'reveal', 'preview'],
      title: 'Default result action',
      'x-ui-renderer': 'option-button-group',
      'x-enum-labels': { open: 'Open', reveal: 'Reveal', preview: 'Preview' },
    },
  },
} as unknown as JsonSchema;

const SETTINGS_INTERFACE_UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
  elements: [
    { type: 'Control', scope: '#/properties/mode' },
    { type: 'Control', scope: '#/properties/theme' },
    { type: 'Control', scope: '#/properties/vimMode' },
    { type: 'Control', scope: '#/properties/defaultAction' },
  ],
} as UISchemaElement;

/** The Settings Interface + Appearance body — the Phase 1 keystone region as a declaration. */
export const SETTINGS_INTERFACE_BODY: SurfaceBodyDeclaration = {
  schema: SETTINGS_INTERFACE_SCHEMA,
  uischema: SETTINGS_INTERFACE_UISCHEMA,
  heading: 'Interface & Appearance',
};

/**
 * 569 Move 8 — a built-in interaction statechart: the destructive-op confirm ceremony, authored
 * as data (states + guards + NAMED effects), not code. `idle → confirming → done`; the CONFIRM
 * edge is guarded (`typed == true`) and fires named effects (invoke-operation + toast) the kernel
 * dispatches through the same trust seam — the behavior analogue of the declared body region.
 */
export const CONFIRM_CEREMONY: InteractionStatechart = {
  id: 'core.confirm-ceremony',
  initial: 'idle',
  states: [
    { id: 'idle', transitions: [{ on: 'REQUEST', target: 'confirming' }] },
    {
      id: 'confirming',
      transitions: [
        {
          on: 'CONFIRM',
          target: 'done',
          guard: 'typed == true',
          // 569 §15 — the destructive op itself is the §7 bespoke residue (e.g. a Tauri command),
          // run by the surface on entering `done`; the chart's declared effect is the safe closed
          // `toast` (no phantom `data.delete-all` op). The ceremony — states + the typed guard — is
          // what is user-authored; the effect BODY stays team-owned (Move 8 / §7).
          effects: [{ kind: 'toast', message: 'Confirmed', severity: 'success' }],
        },
        { on: 'CANCEL', target: 'idle' },
      ],
    },
    { id: 'done', transitions: [] },
  ],
};

/**
 * 569 §14 — the Settings APPEARANCE FLOW as a declared statechart: the FIRST production
 * surface whose real behaviour IS a user-authored statechart run through the gated dispatcher
 * (Move 8 made operative, closing §13.E's "behaviour drives no real surface"). Each appearance
 * choice is a named event firing the closed Effect v3 kinds — `set-appearance` / `set-ui-mode`
 * (optimistic apply) + `save-settings` (persist) — every edge journaled + trust-gated. One
 * `active` state (appearance changes are always accepted, never blocked mid-save), so the chart
 * is honest about the flow's shape rather than fabricating a modal lifecycle. SettingsSurface
 * routes its `patch()` through this instead of calling applyAppearance / setUiMode / fetch
 * imperatively; a user may re-author it via the interaction tier.
 */
export const APPEARANCE_FLOW: InteractionStatechart = {
  id: 'core.appearance-flow',
  initial: 'active',
  states: [
    {
      id: 'active',
      transitions: [
        {
          on: 'THEME_LIGHT',
          target: 'active',
          effects: [
            { kind: 'set-appearance', theme: 'light' },
            { kind: 'save-settings', settings: { ui: { theme: 'light' } } },
          ],
        },
        {
          on: 'THEME_DARK',
          target: 'active',
          effects: [
            { kind: 'set-appearance', theme: 'dark' },
            { kind: 'save-settings', settings: { ui: { theme: 'dark' } } },
          ],
        },
        {
          on: 'THEME_SYSTEM',
          target: 'active',
          effects: [
            { kind: 'set-appearance', theme: 'system' },
            { kind: 'save-settings', settings: { ui: { theme: 'system' } } },
          ],
        },
        {
          on: 'HC_ON',
          target: 'active',
          effects: [
            { kind: 'set-appearance', highContrast: true },
            { kind: 'save-settings', settings: { ui: { highContrast: true } } },
          ],
        },
        {
          on: 'HC_OFF',
          target: 'active',
          effects: [
            { kind: 'set-appearance', highContrast: false },
            { kind: 'save-settings', settings: { ui: { highContrast: false } } },
          ],
        },
        {
          on: 'MODE_SIMPLE',
          target: 'active',
          effects: [
            { kind: 'set-ui-mode', mode: 'simple' },
            { kind: 'save-settings', settings: { ui: { mode: 'simple' } } },
          ],
        },
        {
          on: 'MODE_ADVANCED',
          target: 'active',
          effects: [
            { kind: 'set-ui-mode', mode: 'advanced' },
            { kind: 'save-settings', settings: { ui: { mode: 'advanced' } } },
          ],
        },
        // Tempdoc 806 B.2 (round-12): SETTINGS_INTERFACE_SCHEMA declares `vimMode` and
        // `defaultAction` controls, but this flow had edges only for theme/highContrast/mode — and
        // the statechart's `save-settings` effect is the ONLY thing that persists. So both controls
        // were write-nops: `SettingsSurface.patch()` updated its local `this.ui` copy, the toggle
        // rendered ON from that copy, and `ui.vimMode` stayed false on the backend. Round 12 saw
        // exactly that and correctly cleared the write path via the High-contrast control, which
        // has an edge here. `defaultAction` is the same defect, unreported.
        {
          on: 'VIM_ON',
          target: 'active',
          effects: [{ kind: 'save-settings', settings: { ui: { vimMode: true } } }],
        },
        {
          on: 'VIM_OFF',
          target: 'active',
          effects: [{ kind: 'save-settings', settings: { ui: { vimMode: false } } }],
        },
        {
          on: 'DEFAULT_ACTION_OPEN',
          target: 'active',
          effects: [{ kind: 'save-settings', settings: { ui: { defaultAction: 'open' } } }],
        },
        {
          on: 'DEFAULT_ACTION_REVEAL',
          target: 'active',
          effects: [{ kind: 'save-settings', settings: { ui: { defaultAction: 'reveal' } } }],
        },
        {
          on: 'DEFAULT_ACTION_PREVIEW',
          target: 'active',
          effects: [{ kind: 'save-settings', settings: { ui: { defaultAction: 'preview' } } }],
        },
      ],
    },
  ],
};

/**
 * The built-in declaration that drives the Settings Interface region through the engine.
 * Applying it makes a REAL surface region a projection of this external declaration; not
 * applying it leaves the built-in Lit render (degrade-never-fail). It also carries the
 * confirm-ceremony interaction tier (Move 8), so one artifact spans body + behavior.
 */
export const SETTINGS_DECLARED: PresentationDeclaration = {
  schemaVersion: 1,
  id: 'builtin.settings-declared',
  displayName: 'Settings (declared)',
  description:
    'Renders the Settings Interface + Appearance region from a declaration via the projection engine.',
  body: { [SETTINGS_INTERFACE_REGION]: SETTINGS_INTERFACE_BODY },
  interaction: {
    [CONFIRM_CEREMONY.id]: CONFIRM_CEREMONY,
    [APPEARANCE_FLOW.id]: APPEARANCE_FLOW,
  },
};

// ──────────────────────────────────────────────────────────────────────────────────────────
// 569 §9 — the de-risk spike: ONE declaration driving three MAXIMALLY-DIFFERENT surface KINDS
// (a static settings pane, a results list, a dynamic agent surface) through the same engine + gate.
// The falsification test: if one external declaration renders all three kinds through the gate, the
// generalization holds; if a kind needs team-only escape code, that is the real measured ceiling.
// HONEST FINDING (recorded in the spike test): the CONTENT of all three is declarable to the
// generic-renderer level (each certifies + renders via createChildRenderer) — settings ≈ fully,
// results/agent at the generic level (plainer than bespoke). Bespoke polish is a custom renderer
// (the team-owned content long tail, §7); the INTERACTION (multi-select, streaming choreography) is
// the Move-8 statechart. The spike proves the SPINE generalizes; the residue is the §7 long tail.
// ──────────────────────────────────────────────────────────────────────────────────────────

/** A results-list region as a declaration (the dynamic-list surface kind). */
export const RESULTS_LIST_BODY: SurfaceBodyDeclaration = {
  heading: 'Results',
  schema: {
    type: 'object',
    properties: {
      query: { type: 'string', title: 'Query' },
      hits: {
        type: 'array',
        title: 'Results',
        'x-ui-renderer': 'search-results',
        items: {
          type: 'object',
          properties: {
            title: { type: 'string', title: 'Title' },
            path: { type: 'string', title: 'Path' },
            snippet: { type: 'string', title: 'Snippet' },
            score: { type: 'number', title: 'Score' },
          },
        },
      },
    },
  } as unknown as JsonSchema,
  uischema: {
    type: 'VerticalLayout',
    elements: [
      { type: 'Control', scope: '#/properties/query' },
      { type: 'Control', scope: '#/properties/hits' },
    ],
  } as UISchemaElement,
};

/**
 * An agent surface region as a declaration — the agent surface KIND. Honest scope: this projects
 * the agent's CONTENT (status/answer/sources) statically; it does NOT prove the *streaming/dynamic*
 * behavior (token streaming, tool-call choreography). That dynamic aspect is the §7 long tail — a
 * streaming-aware renderer + a Move-8 interaction statechart — and is NOT claimed by this spike,
 * which tests content-projection across three surface kinds, not their runtime behavior.
 */
export const AGENT_STATUS_BODY: SurfaceBodyDeclaration = {
  heading: 'Assistant',
  schema: {
    type: 'object',
    properties: {
      status: { type: 'string', enum: ['idle', 'streaming', 'done'], title: 'Status' },
      answer: { type: 'string', title: 'Answer' },
      sources: {
        type: 'array',
        title: 'Sources',
        'x-ui-renderer': 'source-chips',
        items: {
          type: 'object',
          properties: {
            title: { type: 'string', title: 'Title' },
            url: { type: 'string', title: 'URL' },
          },
        },
      },
    },
  } as unknown as JsonSchema,
  uischema: {
    type: 'VerticalLayout',
    elements: [
      { type: 'Control', scope: '#/properties/status' },
      { type: 'Control', scope: '#/properties/answer' },
      { type: 'Control', scope: '#/properties/sources' },
    ],
  } as UISchemaElement,
};

/** The §9 spike: one declaration whose body spans all three surface kinds. */
export const THREE_SURFACE_SPIKE: PresentationDeclaration = {
  schemaVersion: 1,
  id: 'builtin.three-surface-spike',
  displayName: 'Three-surface spike',
  description: 'One declaration driving a static settings pane, a results list, and an agent surface.',
  body: {
    [SETTINGS_INTERFACE_REGION]: SETTINGS_INTERFACE_BODY,
    'core.results': RESULTS_LIST_BODY,
    'core.agent': AGENT_STATUS_BODY,
  },
};

/** The region id the Library surface exposes for declaration-driving (the 2nd real surface, §14). */
export const LIBRARY_CARDS_REGION = 'core.library.cards';

/**
 * 569 §14 — the Library indexed-folder cards as a declaration (the 2nd real surface inverted). The
 * `folders` array carries the `folder-card` x-ui-renderer hint; the surface pre-resolves each card's
 * display fields (async path resolution + formatting) into the body data, and the engine projects the
 * cards at bespoke parity. The Remove INTENT is emitted by the renderer and handled by the surface
 * (the gated `core.remove-watched-root` operation + confirm stay team-owned — the §7 boundary).
 */
export const LIBRARY_CARDS_BODY: SurfaceBodyDeclaration = {
  heading: 'Folders',
  placement: 'STAGE',
  schema: {
    type: 'object',
    properties: {
      folders: {
        type: 'array',
        title: 'Folders',
        'x-ui-renderer': 'folder-card',
        items: { type: 'object' },
      },
    },
  } as unknown as JsonSchema,
  uischema: {
    type: 'VerticalLayout',
    elements: [{ type: 'Control', scope: '#/properties/folders' }],
  } as UISchemaElement,
};

/** 569 §15 — the Help reference region (the 3rd real surface): shortcuts table + info lists. */
export const HELP_REFERENCE_REGION = 'core.help.reference';

/**
 * 569 §15 — the Help reference content as a declaration: the keyboard-shortcuts table + the
 * troubleshooting + network lists, rendered through the engine via the `shortcuts-table` / `list-items`
 * renderers. The FAQ (collapsible) + the export-diagnostics operation stay surface-owned (§7).
 */
export const HELP_REFERENCE_BODY: SurfaceBodyDeclaration = {
  heading: 'Reference',
  placement: 'STAGE',
  schema: {
    type: 'object',
    properties: {
      shortcuts: { type: 'array', title: 'Keyboard shortcuts', 'x-ui-renderer': 'shortcuts-table', items: { type: 'object' } },
      troubleshooting: { type: 'array', title: 'Quick troubleshooting', 'x-ui-renderer': 'list-items', items: { type: 'string' } },
      network: { type: 'array', title: 'Network activity', 'x-ui-renderer': 'list-items', items: { type: 'string' } },
    },
  } as unknown as JsonSchema,
  uischema: {
    type: 'VerticalLayout',
    elements: [
      { type: 'Group', label: 'Keyboard shortcuts', elements: [{ type: 'Control', scope: '#/properties/shortcuts' }] },
      { type: 'Group', label: 'Quick troubleshooting', elements: [{ type: 'Control', scope: '#/properties/troubleshooting' }] },
      { type: 'Group', label: 'Network activity', elements: [{ type: 'Control', scope: '#/properties/network' }] },
    ],
  } as UISchemaElement,
};

/** 569 §15 — the Health connection STATUS region (the 4th real surface): the LIVENESS facet. */
export const HEALTH_STATUS_REGION = 'core.health.status';

/**
 * 569 §15 — the Health connection status as a declaration carrying the co-projected `liveness` facet.
 * The engine derives the live tri-state from the ONE observed-state authority (`aiStateStore`); the
 * author declares only the signal ref, so a faked "connected" indicator is unrepresentable (Move 3) —
 * replacing HealthSurface's hand-painted inline-style status colour.
 */
export const HEALTH_STATUS_BODY: SurfaceBodyDeclaration = {
  heading: 'Connection',
  placement: 'STAGE',
  liveness: 'core.retrieval',
  schema: { type: 'object', properties: {} } as unknown as JsonSchema,
  uischema: { type: 'VerticalLayout', elements: [] } as UISchemaElement,
};

/** 569 §15 — the Health index/stats region: the metric-card content + the co-projected OVERFLOW facet. */
export const HEALTH_STATS_REGION = 'core.health.stats';

/**
 * 569 §15 — the Health index stats as a declaration: the metric cards via the `metric-card` renderer,
 * plus a declared adaptive enrichment-capability strip the engine clips via `OverflowController` (the
 * author declares priority; the engine owns the clip — Move 3 OVERFLOW on a real surface).
 */
export const HEALTH_STATS_BODY: SurfaceBodyDeclaration = {
  heading: 'Index',
  placement: 'STAGE',
  // 594 Move 1b — each chip that asserts a runtime/build fact carries a `fact`-ref, not a literal:
  // the engine derives the value from the ONE Display-value authority (projectFact), so a stale or
  // host-wrong literal ("384-d", "GPU cuda12" on a CPU box) is unrepresentable. Absent capabilities
  // omit their chip; unknown ones render muted.
  overflow: [
    { id: 'cap-embed', fact: 'core.embed.dim', priority: 90, pinned: true },
    { id: 'cap-splade', fact: 'core.splade', priority: 70 },
    { id: 'cap-rerank', fact: 'core.reranker', priority: 60 },
    { id: 'cap-ner', fact: 'core.ner', priority: 50 },
    { id: 'cap-gpu', fact: 'core.gpu.accel', priority: 40 },
    { id: 'cap-vec', fact: 'core.vector.precision', priority: 30 },
  ],
  schema: {
    type: 'object',
    properties: {
      metrics: { type: 'array', title: 'Index metrics', 'x-ui-renderer': 'metric-card', items: { type: 'object' } },
    },
  } as unknown as JsonSchema,
  uischema: {
    type: 'VerticalLayout',
    elements: [{ type: 'Control', scope: '#/properties/metrics' }],
  } as UISchemaElement,
};

/**
 * 569 §14/§15 — the ONE built-in default presentation (Move 1, one declaration): the Settings, Library,
 * Help, and Health regions rendered through the engine, plus the behaviour tier (confirm + appearance).
 * Boot-applied so the real surfaces are declaration-default — a single artifact spans every
 * default-declared region + behaviour, not one declaration per surface.
 */
export const CORE_DECLARED: PresentationDeclaration = {
  schemaVersion: 1,
  id: 'builtin.core-declared',
  displayName: 'Core (declared)',
  description:
    'The built-in default: the Settings + Library + Help regions rendered through the projection engine.',
  body: {
    [SETTINGS_INTERFACE_REGION]: SETTINGS_INTERFACE_BODY,
    [LIBRARY_CARDS_REGION]: LIBRARY_CARDS_BODY,
    [HELP_REFERENCE_REGION]: HELP_REFERENCE_BODY,
    [HEALTH_STATUS_REGION]: HEALTH_STATUS_BODY,
    [HEALTH_STATS_REGION]: HEALTH_STATS_BODY,
  },
  interaction: {
    [CONFIRM_CEREMONY.id]: CONFIRM_CEREMONY,
    [APPEARANCE_FLOW.id]: APPEARANCE_FLOW,
  },
};

/**
 * 569 §19 Phase 4 — the built-in "style variations" (skins gallery). Each is `CORE_DECLARED` with a
 * single accent-hue delta on the theme tier: the same surfaces/body/interaction, a different accent
 * colour. `accent-tint` is a colour-ROLE fill whose on-colour is co-projected to a WCAG floor
 * (`deriveRoleForegrounds`), and its `oklch(...)` value is deferred by the gate's literal-contrast check
 * to the runtime oracle — so every variation certifies + renders contrast-safe by construction.
 */
function coreVariation(idSuffix: string, displayName: string, accentTint: string): PresentationDeclaration {
  return {
    ...CORE_DECLARED,
    id: `builtin.core-${idSuffix}`,
    displayName,
    description: `The core layout with a ${displayName.toLowerCase()} accent.`,
    theme: { tokens: { 'accent-tint': accentTint } },
  };
}

const CORE_VARIATIONS: readonly PresentationDeclaration[] = [
  coreVariation('teal', 'Teal', 'oklch(65% 0.12 195)'),
  coreVariation('violet', 'Violet', 'oklch(60% 0.18 290)'),
  coreVariation('amber', 'Amber', 'oklch(75% 0.15 75)'),
  coreVariation('emerald', 'Emerald', 'oklch(68% 0.15 155)'),
  coreVariation('rose', 'Rose', 'oklch(65% 0.18 10)'),
  coreVariation('azure', 'Azure', 'oklch(62% 0.16 250)'),
];

/**
 * All built-in declarations, by id (the team-default origin in the one catalog, Move 1). 569 §19
 * Seam 1 — every built-in is stamped `origin: { kind: 'team' }` here (one place), so provenance is a
 * declared field on every artifact regardless of where the literal is defined.
 */
export const BUILTIN_PRESENTATIONS: readonly PresentationDeclaration[] = [
  CORE_DECLARED,
  ...CORE_VARIATIONS,
  SETTINGS_DECLARED,
  THREE_SURFACE_SPIKE,
].map((d) => (d.origin ? d : { ...d, origin: { kind: 'team' as const } }));

/** Look up a built-in declaration by id. */
export function getBuiltinPresentation(id: string): PresentationDeclaration | undefined {
  return BUILTIN_PRESENTATIONS.find((p) => p.id === id);
}
