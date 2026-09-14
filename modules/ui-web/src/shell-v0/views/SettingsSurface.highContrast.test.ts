// @vitest-environment happy-dom

/**
 * Tempdoc 855 §15.4/§17 R1 — the end-to-end High-contrast path. NOTHING covered this before: the two
 * competing HC set-sites both shipped with zero e2e coverage, which is why a two-authority defect
 * survived in a shipped surface. This pins the surviving one end to end.
 *
 * The chain under test: the Accessibility switch → `SettingsSurface.patch()` → the APPEARANCE_FLOW
 * statechart's `HC_ON`/`HC_OFF` edge → its two effects → (a) `set-appearance`, which reaches
 * `themeState.applyAppearance` and writes the `high-contrast` root class, and (b) `save-settings`,
 * the narrow `{ui:{highContrast}}` POST to `/api/settings/v2`.
 *
 * HONEST SEAM: `jf-set-appearance` is a global chrome listener (`Shell.ts` — `setAppearanceListener`),
 * and mounting the whole chrome around a settings unit test is not feasible; the test binds the same
 * two-line adapter onto the REAL `applyAppearance` authority. So the assertion covers everything from
 * the click to the class EXCEPT Shell's own event registration, which `Shell` owns.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SettingsSurface.js';
// Fix-round F1 — the declared-path swatch tests below drill through the REAL nested renderer chain
// (`jf-declared-surface` → `jf-vertical-layout` → `jf-x-ui-renderer-control` → the leaf control), so
// the full default renderer set must be registered (production registers it via this barrel at boot
// — SettingsSurface.ts itself imports only the specific renderers it references by name, not the
// layout renderers `<jf-declared-surface>`'s generic engine dispatches to).
import '../renderers/registry.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { applyAppearance, __resetThemeStateForTest } from '../state/themeState.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { SETTINGS_INTERFACE_BODY } from '../themes/builtinPresentations.js';
import { restoreActivePresentationOnBoot } from '../state/presentationState.js';
import { __resetPresentationForTest } from '../state/presentationRuntime.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';

interface PostedCall {
  readonly path: string;
  readonly body: unknown;
  readonly headers: Headers;
}

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function completeSettings(init?: { body?: string | object }): Response {
  const request = JSON.parse(String(init?.body ?? '{}')) as {
    operationKey?: string;
    witness?: { acceptedRevision?: number };
  };
  return new Response(JSON.stringify({
    state: 'COMPLETE',
    operationKey: request.operationKey,
    witness: {
      acceptedRevision: (request.witness?.acceptedRevision ?? 0) + 1,
      lastCommittedOperationKey: request.operationKey,
    },
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

function expectSettingsAttempt(
  body: unknown,
  expectedPatch: Record<string, unknown>,
  expectedRevision: number,
): void {
  const attempt = body as Record<string, unknown>;
  const witness = attempt.witness as { acceptedRevision?: number; lastCommittedOperationKey?: unknown };
  expect(attempt).toEqual(expect.objectContaining(expectedPatch));
  expect(Object.keys(attempt).sort()).toEqual([
    ...Object.keys(expectedPatch), 'operationKey', 'witness',
  ].sort());
  expect(attempt.operationKey).toEqual(expect.stringMatching(UUID_V7));
  expect(witness.acceptedRevision).toBe(expectedRevision);
  if (expectedRevision === 0) expect(witness.lastCommittedOperationKey).toBeNull();
  else expect(witness.lastCommittedOperationKey).toEqual(expect.stringMatching(UUID_V7));
}

async function settlePersistence(el: HTMLElement & { updateComplete: Promise<unknown> }): Promise<void> {
  await el.updateComplete;
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

let posted: PostedCall[] = [];
let appearanceListener: ((e: Event) => void) | null = null;

/** Stand-in for `Shell.setAppearanceListener` (Shell.ts) — the same adapter onto the same authority. */
function bindAppearanceListener(): void {
  appearanceListener = (e: Event) => {
    const d = (e as CustomEvent<{ theme?: string; highContrast?: boolean }>).detail ?? {};
    void applyAppearance({
      ...(d.theme !== undefined ? { theme: d.theme } : {}),
      ...(d.highContrast !== undefined ? { highContrast: d.highContrast } : {}),
    });
  };
  document.addEventListener('jf-set-appearance', appearanceListener);
}

async function mountSettings(): Promise<HTMLElement> {
  const el = document.createElement('jf-settings-surface') as HTMLElement & {
    updateComplete: Promise<unknown>;
  };
  let acceptedRevision = 0;
  let lastCommittedOperationKey: string | null = null;
  (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
    data: {
      fetch: (path: string, init?: { method?: string; body?: string | object; headers?: HeadersInit }) => {
        if (init?.method === 'POST') {
          const request = JSON.parse(String(init.body)) as {
            operationKey?: string;
            witness?: { acceptedRevision?: number };
          };
          acceptedRevision = (request.witness?.acceptedRevision ?? 0) + 1;
          lastCommittedOperationKey = request.operationKey ?? null;
          posted.push({ path, body: request, headers: new Headers(init.headers) });
          return Promise.resolve(completeSettings(init));
        }
        return Promise.resolve(new Response(JSON.stringify({
          ui: {}, witness: { acceptedRevision, lastCommittedOperationKey },
        }), { status: 200 }));
      },
    },
  });
  document.body.appendChild(el);
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  return el;
}

/** Activate the switch the way a user does — the inner `role="switch"` element, one shadow root in. */
async function toggleHighContrast(el: HTMLElement): Promise<void> {
  const row = el.shadowRoot!.querySelector('[data-testid="settings-high-contrast"]');
  expect(row, 'the Accessibility section must render the High contrast row').toBeTruthy();
  const sw = row!.querySelector('jf-switch') as HTMLElement & { updateComplete: Promise<unknown> };
  expect(sw, 'the control is the shared jf-switch atom').toBeTruthy();
  await sw.updateComplete;
  (sw.shadowRoot!.querySelector('[role="switch"]') as HTMLElement).click();
  await settlePersistence(el as HTMLElement & { updateComplete: Promise<unknown> });
}

beforeEach(() => {
  posted = [];
  document.documentElement.className = '';
  __resetUserConfigForTest();
  __resetThemeStateForTest();
  __resetSessionRegistryForTest();
  __resetPresentationForTest();
  __resetForTest();
  __seedForTest({
    'settings.related.label': 'Related settings',
    'settings.section.accessibility': 'Accessibility',
  });
  bindAppearanceListener();
  // Tempdoc 855 fix round 2 — apply the REAL default presentation (production boot's
  // `restoreActivePresentationOnBoot()` applies `CORE_DECLARED` when nothing is persisted, and
  // `__resetUserConfigForTest` above clears any persisted `activePresentationId`). Without this,
  // `activeBodyFor(SETTINGS_INTERFACE_REGION)` resolves undefined and every test below silently
  // exercises the FALLBACK render path instead of the one production actually ships.
  restoreActivePresentationOnBoot();
});

afterEach(() => {
  if (appearanceListener) document.removeEventListener('jf-set-appearance', appearanceListener);
  appearanceListener = null;
  document.body.innerHTML = '';
  document.documentElement.className = '';
  __resetPresentationForTest();
  __resetForTest();
  vi.restoreAllMocks();
});

describe('SettingsSurface — High contrast, end to end', () => {
  it('activating the switch applies the root class AND persists the narrow POST', async () => {
    const el = await mountSettings();
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);

    await toggleHighContrast(el);

    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
    expect(posted).toHaveLength(1);
    expect(posted[0]!.path).toBe('/api/settings/v2');
    expectSettingsAttempt(posted[0]!.body, { ui: { highContrast: true } }, 0);
  });

  it('toggling off reverses both the class and the persisted value', async () => {
    const el = await mountSettings();
    await toggleHighContrast(el);
    await toggleHighContrast(el);

    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);
    expect(posted).toHaveLength(2);
    expect(posted[0]!.path).toBe('/api/settings/v2');
    expect(posted[1]!.path).toBe('/api/settings/v2');
    expectSettingsAttempt(posted[0]!.body, { ui: { highContrast: true } }, 0);
    expectSettingsAttempt(posted[1]!.body, { ui: { highContrast: false } }, 1);
  });

  it('renders exactly ONE high-contrast control in the whole surface (§17 R1)', async () => {
    const el = await mountSettings();
    const root = el.shadowRoot!;
    const switches = Array.from(root.querySelectorAll('jf-switch')).filter(
      (s) => (s as HTMLElement & { label?: string }).label === 'High contrast',
    );
    expect(switches.length).toBe(1);
  });

  it('the declared Interface region no longer declares a second high-contrast control', () => {
    // The other half of "exactly one": the surface test above already runs the DECLARED path
    // (`beforeEach` now applies `CORE_DECLARED`, the same default production boot applies), so its
    // `jf-switch` count already covers this region's rendered output. This test pins the SOURCE —
    // the region's schema is what the projection engine renders when a presentation body is applied —
    // so a future body cannot reintroduce the second toggle even before it is ever mounted.
    const properties = (
      SETTINGS_INTERFACE_BODY.schema as unknown as { properties: Record<string, unknown> }
    ).properties;
    expect(Object.keys(properties)).not.toContain('highContrast');
  });

  it('renders the declared Interface region (default path), not the built-in fallback', async () => {
    const el = await mountSettings();
    const root = el.shadowRoot!;
    const declaredSurface = root.querySelector('jf-declared-surface');
    expect(
      declaredSurface,
      'production boot applies CORE_DECLARED, so the default path is the declared region',
    ).toBeTruthy();
  });
});

describe('SettingsSurface — the Appearance → Accessibility cross-link (§17 R1)', () => {
  it('replaces the pruned toggle with a keyboard-operable link to the target section', async () => {
    const el = await mountSettings();
    const link = el.shadowRoot!.querySelector('.link-row') as HTMLButtonElement | null;
    expect(link, 'Appearance must point at where the control now lives').toBeTruthy();
    expect(link!.tagName).toBe('BUTTON'); // native button = focus + Enter/Space for free
    expect(link!.textContent?.trim()).toContain('Accessibility'); // the target's own catalog label
  });

  it('renders exactly once, as a sibling of the declared surface — not projected inside it (F1)', async () => {
    // Fix-round F1 — `renderInterfaceRegion()` renders EITHER `<jf-declared-surface>` OR the
    // built-in fallback, never both, so the row has to sit at the branch join to survive on the
    // default declared path. Before the fix it lived inside `renderAppearance()`, which the
    // declared branch never calls — this is the assertion that would have failed against that
    // placement (the declared path renders CORE_DECLARED by default via `beforeEach` above).
    const el = await mountSettings();
    const root = el.shadowRoot!;
    const declaredSurface = root.querySelector('jf-declared-surface');
    expect(declaredSurface, 'the declared region must render on the default path').toBeTruthy();
    // jf-declared-surface renders through its OWN shadow root, so a light-DOM query against the
    // host element can only see slotted/projected children — there are none here, which is the
    // point: the row is a document sibling, not something projected into the declared surface.
    expect(declaredSurface!.querySelector('.link-row')).toBeNull();
    const linkRows = root.querySelectorAll('.link-row');
    expect(linkRows.length).toBe(1);
  });

  it('activating it scrolls the Accessibility sub-anchor into view', async () => {
    const el = await mountSettings();
    const target = el.shadowRoot!.querySelector('[data-settings-anchor="accessibility"]');
    expect(target, 'the anchor the link addresses must exist in the register-driven render').toBeTruthy();
    // The observable outcome is the scroll, not `activeAnchor`: the scroll-spy re-derives that from
    // measured rects, and every rect is zero-height under happy-dom, so it settles back to null here.
    const scrolled = vi.fn();
    (target as unknown as { scrollIntoView: () => void }).scrollIntoView = scrolled;
    (el.shadowRoot!.querySelector('.link-row') as HTMLButtonElement).click();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(scrolled).toHaveBeenCalledTimes(1);
  });
});

/**
 * Fix-round F1 — the theme-variant swatch trio was DEAD CODE on the default declared path: the
 * declared `theme` field (`SETTINGS_INTERFACE_SCHEMA` in `builtinPresentations.ts`) supersedes the
 * hand-authored `SettingsSurface.renderAppearance()` in every real boot (production applies
 * `CORE_DECLARED` — see `beforeEach` above), and `OptionButtonGroupRenderer`'s JsonForms schema
 * branch hardcoded `swatches = {}`, so the declared picker rendered plain unswatched buttons no
 * matter what `renderVariantOptions()` (the never-reached hand path) authored. This test walks the
 * REAL nested-shadow-root chain the declared path renders through (`jf-declared-surface` →
 * `jf-vertical-layout` → `jf-x-ui-renderer-control` → `jf-option-button-group`), the same chain
 * `SettingsSurface.highContrast.test.ts`'s other declared-path assertions above walk.
 */
describe('SettingsSurface — declared theme picker swatches (fix-round F1)', () => {
  async function findThemeOptionGroup(el: HTMLElement): Promise<HTMLElement> {
    // Each nested custom element (jf-declared-surface → jf-vertical-layout →
    // jf-x-ui-renderer-control → jf-option-button-group) renders on its OWN microtask, so its
    // shadow root content isn't guaranteed populated just because an ancestor's `updateComplete`
    // resolved — await at every level, same pattern `toggleHighContrast` above uses.
    const declared = el.shadowRoot!.querySelector(
      'jf-declared-surface',
    ) as (HTMLElement & { updateComplete: Promise<unknown> }) | null;
    expect(declared, 'the declared Interface region must render (default CORE_DECLARED boot)').toBeTruthy();
    await declared!.updateComplete;
    const layout = declared!.shadowRoot!.querySelector('jf-vertical-layout') as
      | (HTMLElement & { updateComplete: Promise<unknown> })
      | null;
    expect(layout, 'the Interface region body is a VerticalLayout').toBeTruthy();
    await layout!.updateComplete;
    const dispatchers = Array.from(
      layout!.shadowRoot!.querySelectorAll('jf-x-ui-renderer-control'),
    ) as Array<HTMLElement & { path?: string; updateComplete: Promise<unknown> }>;
    const themeDispatcher = dispatchers.find((d) => d.path === 'theme');
    expect(themeDispatcher, 'a theme x-ui-renderer-control must exist in the declared body').toBeTruthy();
    await themeDispatcher!.updateComplete;
    const group = themeDispatcher!.shadowRoot!.querySelector('jf-option-button-group') as
      | (HTMLElement & { updateComplete: Promise<unknown> })
      | null;
    expect(group, 'the theme control dispatches to jf-option-button-group').toBeTruthy();
    await group!.updateComplete;
    return group as HTMLElement;
  }

  it('renders the swatch tile trio, not plain text buttons', async () => {
    const el = await mountSettings();
    const group = await findThemeOptionGroup(el);
    expect(
      group.shadowRoot!.querySelector('.option-group.swatch-group'),
      'the schema x-enum-swatches must reach the renderer, marking the group swatch-group',
    ).toBeTruthy();
    const tiles = group.shadowRoot!.querySelectorAll('.option-swatch-tile');
    expect(tiles.length).toBe(3);
  });

  it('paints the same fill colors the hand-authored fallback uses (one shared vocabulary)', async () => {
    const el = await mountSettings();
    const group = await findThemeOptionGroup(el);
    const tiles = Array.from(group.shadowRoot!.querySelectorAll('.option-swatch-tile'));
    const fills = tiles.map((t) => t.firstElementChild?.getAttribute('style') ?? '');
    expect(fills[0]).toContain('linear-gradient(135deg, #1a1a1e 50%, #f4f4f5 50%)'); // system
    expect(fills[1]).toContain('background:#1a1a1e'); // dark
    expect(fills[2]).toContain('background:#f4f4f5'); // light
  });

  it('renders a check badge on the swatch tile matching the current theme value', async () => {
    const el = await mountSettings();
    // The settings-v2 fetch mock returns `{ui:{}}` (no persisted theme), so drive an explicit
    // value the way `SettingsSurface.patch()` would — through the declared-surface `data` path,
    // not the unrelated `?? 'system'` default that only the hand-authored fallback applies.
    (el as unknown as { ui: { theme?: string } }).ui = { theme: 'light' };
    (el as unknown as { requestUpdate: () => void }).requestUpdate();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const group = await findThemeOptionGroup(el);
    const selectedBtn = group.shadowRoot!.querySelector('button.option-btn.selected');
    expect(selectedBtn?.querySelector('.option-swatch-check')).toBeTruthy();
    // It's specifically the "light" tile (index 2, the third swatch) that carries it.
    const tiles = Array.from(group.shadowRoot!.querySelectorAll('.option-btn'));
    expect(tiles[2]).toBe(selectedBtn);
  });
});
