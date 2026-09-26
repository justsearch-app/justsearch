// @vitest-environment happy-dom

/**
 * Shell integration tests — slice 492 follow-up.
 *
 * Pre-follow-up: handlers, sources, and the router each had unit tests
 * but the Shell's connectedCallback orchestration had zero coverage.
 * Issues that show up only at the integration layer:
 *
 *   - Source-teardown race: an async source's `start(...)` resolves
 *     after the Shell disconnects. The race fix in Shell tracks a
 *     `disconnected` flag and invokes the teardown immediately.
 *   - activateSurface transport stamping: callers must specify a
 *     transport; the dispatched Intent carries it through the
 *     listener fan-out so audit / observability records the origin.
 *   - State-bearing URL hash → router → NavigationHandler → store
 *     wiring (the headline scenario the slice substrate exists to
 *     enable).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetSurfaceSchemasForTest,
  registerSurfaceStateSchema,
} from '../router/surfaceSchemas.js';
import {
  __resetStoreRegistryForTest,
} from '../router/storeRegistry.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import {
  enqueueUiModePersistence,
  UI_MODE_INTENT_HEADER,
  setUiMode,
  getUiMode,
  __resetUiModeForTest,
} from '../state/uiModeState.js';
import { __activeSurfaceIdForTest, deactivateProjection } from '../router/URLProjector.js';
import { subscribeMemberTab } from '../router/memberTabIntent.js';
import {
  restoreSearch,
  serializeSearch,
} from '../state/searchState.js';
import { getInspectorState, resetInspectorState } from '../state/inspectorState.js';
import type { SettingsWindow } from './SettingsWindow.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';
import type { StateSnapshot } from '../router/types.js';
import type { TransportTag } from '../router/transports.js';
import { __seedForTest as seedErrorCatalog, __resetForTest as resetErrorCatalog } from '../../i18n/errorCatalog.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';

function makeRailSurface(id: string, mountTag: string): Surface {
  return {
    id,
    presentation: {
      labelKey: `${id}.label`,
      descriptionKey: `${id}.description`,
    },
    audience: 'USER',
    placement: 'RAIL',
    consumes: {
      operations: [],
      resources: [],
      prompts: [],
      diagnosticChannels: [],
    },
    mountTag,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
  };
}

const SEARCH = makeRailSurface('core.search-surface', 'jf-search-surface');
const LIBRARY = makeRailSurface('core.library-surface', 'jf-library-surface');
const UNIFIED_SEARCH: Surface = {
  ...makeRailSurface('core.unified-chat-surface', 'jf-unified-chat-view'),
  splitPairing: { secondary: 'core.library-surface' },
};
const LEGACY_ASK: Surface = {
  ...makeRailSurface('core.ask-surface', 'jf-chat-shape-mount'),
  placement: 'DEEPLINK',
};
const LEGACY_FREE_CHAT: Surface = { ...LEGACY_ASK, id: 'core.free-chat-surface' };
const LEGACY_EXTRACT: Surface = { ...LEGACY_ASK, id: 'core.extract-surface' };

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
let settingsAcceptedRevision = 0;
let settingsLastCommittedOperationKey: string | null = null;

function settingsSnapshot(): Response {
  return new Response(JSON.stringify({
    ui: { mode: 'simple' },
    witness: {
      acceptedRevision: settingsAcceptedRevision,
      lastCommittedOperationKey: settingsLastCommittedOperationKey,
    },
  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function completeSettings(init?: RequestInit): Response {
  const request = JSON.parse(String(init?.body ?? '{}')) as {
    operationKey?: string;
    witness?: { acceptedRevision?: number };
  };
  settingsAcceptedRevision = (request.witness?.acceptedRevision ?? 0) + 1;
  settingsLastCommittedOperationKey = request.operationKey ?? null;
  return new Response(JSON.stringify({
    state: 'COMPLETE',
    operationKey: request.operationKey,
    witness: {
      acceptedRevision: settingsAcceptedRevision,
      lastCommittedOperationKey: settingsLastCommittedOperationKey,
    },
  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function seedTwoSurfaces(): void {
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries: [SEARCH, LIBRARY],
  };
  seedSurfaceCatalog(catalog);
}

interface ShellElement extends HTMLElement {
  apiBase: string;
  surfaces: Surface[];
  activeId: string | null;
  updateComplete: Promise<void>;
  activateSurface(id: string, state: StateSnapshot, transport: TransportTag): void;
}

async function renderShell(): Promise<ShellElement> {
  const shell = document.createElement('jf-shell') as ShellElement;
  shell.apiBase = '';
  document.body.appendChild(shell);
  await shell.updateComplete;
  // Let the connectedCallback's async bootstrap (fetchAndRegisterSurfaceSchemas)
  // settle. The schemas are registered ahead via registerSurfaceStateSchema
  // in beforeEach so the fetch resolving "no entries" is fine.
  await new Promise((r) => setTimeout(r, 30));
  await shell.updateComplete;
  return shell;
}

describe('Shell — slice 492 substrate integration', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    settingsAcceptedRevision = 0;
    settingsLastCommittedOperationKey = null;
    deactivateProjection();
    window.location.hash = '';
    seedTwoSurfaces();
    // The Shell's connectedCallback awaits
    // `fetchAndRegisterSurfaceSchemas(apiBase)`, which makes a fetch
    // against `/api/registry/surfaces`. In happy-dom without a server
    // this fetch hangs/rejects unpredictably; stub it so the bootstrap
    // promise resolves promptly and sources start. The test-side
    // schema registration happens BEFORE the stub-fetch returns its
    // empty entries, so the manual schema persists.
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (String(input).endsWith('/api/settings/v2')) {
          return init?.method === 'POST' ? completeSettings(init) : settingsSnapshot();
        }
        return new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }),
    );
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    __resetAiStateForTest();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    vi.unstubAllGlobals();
    __resetUiModeForTest();
    resetErrorCatalog();
  });

  it.each(['invoke', 'undo'] as const)('906: %s failure uses handler facts through the real client and toast channel', async (kind) => {
    seedErrorCatalog({ 'errors.POLICY_ONLINE_AI_DISABLED': 'Online AI is disabled by administrator policy.' });
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/operations/') || url.includes('/api/undo/')) {
        calls.push(url);
        return new Response(JSON.stringify({ success: false, message: 'private-operation-id: internal exception',
          errorClass: 'HANDLER_FAILURE', errorCode: 'POLICY_ONLINE_AI_DISABLED', retryable: false }), { status: 403 });
      }
      return new Response(JSON.stringify({ entries: [] }), { status: 200 });
    }));
    await renderShell();
    const toast = new Promise<EphemeralToastSpec>((resolve) => document.addEventListener(
      EPHEMERAL_TOAST_EVENT, (e) => resolve((e as CustomEvent<EphemeralToastSpec>).detail), { once: true }));
    document.dispatchEvent(new CustomEvent(kind === 'invoke' ? 'jf-invoke-operation' : 'jf-undo-operation', {
      detail: { operationId: 'private-operation-id', executionId: 'execution-1', originator: 'user' },
    }));
    const shown = await toast;
    expect(shown.message).toContain('Online AI is disabled by administrator policy.');
    expect(shown.message).toContain('Resolve the restriction before trying again.');
    expect(shown.message).not.toMatch(/private-operation-id|internal exception|You can try/);
    expect(shown.severity).toBe('error');
    expect(shown.onAction).toBeUndefined();
    expect(calls).toHaveLength(1);
    expect(calls[0]).toContain(kind === 'invoke' ? '/api/operations/' : '/api/undo/');
  });

  it('906: an uncertain mutation response asks users to check its effect, without retrying', async () => {
    let calls = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/operations/')) {
        calls++;
        throw new Error('private transport exception');
      }
      return new Response(JSON.stringify({ entries: [] }), { status: 200 });
    }));
    await renderShell();
    const toast = new Promise<EphemeralToastSpec>((resolve) => document.addEventListener(
      EPHEMERAL_TOAST_EVENT, (e) => resolve((e as CustomEvent<EphemeralToastSpec>).detail), { once: true }));
    document.dispatchEvent(new CustomEvent('jf-invoke-operation', { detail: { operationId: 'private-id' } }));
    const shown = await toast;
    expect(shown.message).toContain('verify whether the action took effect before trying again');
    expect(shown.message).not.toContain('private');
    expect(shown.onAction).toBeUndefined();
    expect(calls).toBe(1);
  });

  it('Tempdoc 738 — the topbar Simple/Detailed toggle reflects the live uiMode and drives it on click', async () => {
    __resetUiModeForTest();
    const shell = await renderShell();
    const opts = () =>
      Array.from(shell.shadowRoot?.querySelectorAll('.ui-mode-opt') ?? []) as HTMLButtonElement[];
    expect(opts().map((b) => b.textContent?.trim())).toEqual(['Simple', 'Detailed']);
    // Default: Simple active.
    expect(opts()[0]!.getAttribute('aria-pressed')).toBe('true');
    expect(opts()[1]!.getAttribute('aria-pressed')).toBe('false');
    // An external mode change (e.g. the async settings seed) is reflected LIVE — the boot-desync fix.
    setUiMode('advanced');
    await shell.updateComplete;
    expect(opts()[1]!.getAttribute('aria-pressed')).toBe('true');
    expect(opts()[0]!.getAttribute('aria-pressed')).toBe('false');
    // Clicking a segment drives the authority back.
    opts()[0]!.click();
    await shell.updateComplete;
    expect(getUiMode()).toBe('simple');
  });

  it('923 review — topbar persistence joins the shared cross-control mode queue', async () => {
    const shell = await renderShell();
    let release!: (response: Response) => void;
    const blocker = enqueueUiModePersistence(
      () => new Promise<Response>((resolve) => {
        release = resolve;
      }),
    );
    await Promise.resolve();

    const detailed = Array.from(
      shell.shadowRoot?.querySelectorAll<HTMLButtonElement>('.ui-mode-opt') ?? [],
    ).find((button) => button.textContent?.trim() === 'Detailed');
    detailed?.click();
    await shell.updateComplete;
    await Promise.resolve();
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    const settingsPosts = () => fetchMock.mock.calls.filter(([input, init]) =>
      String(input).endsWith('/api/settings/v2')
      && (init as RequestInit | undefined)?.method === 'POST');
    expect(settingsPosts()).toHaveLength(0);

    release(new Response('{}', { status: 200 }));
    await blocker;
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(settingsPosts()).toHaveLength(1);
    const request = JSON.parse(String((settingsPosts()[0]?.[1] as RequestInit | undefined)?.body)) as {
      ui: { mode: string };
      witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
      operationKey: string;
    };
    expect(request).toEqual({
      ui: { mode: 'advanced' },
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null },
      operationKey: expect.stringMatching(UUID_V7),
    });
    expect(request.operationKey).toMatch(UUID_V7);
    expect(new Headers((settingsPosts()[0]?.[1] as RequestInit | undefined)?.headers)
      .get(UI_MODE_INTENT_HEADER)).toMatch(/:2$/);
  });

  it('923 F-22 — normalizes a persisted Ask pane to Search or its Library pair', () => {
    seedSurfaceCatalog({
      schemaVersion: '1.0.0',
      catalogVersion: 1,
      namespace: 'core',
      primitive: 'Surface',
      entries: [LIBRARY, UNIFIED_SEARCH, LEGACY_ASK, LEGACY_FREE_CHAT, LEGACY_EXTRACT],
    });
    const shell = document.createElement('jf-shell') as ShellElement & {
      userConfig?: { secondaryActiveSurface?: string };
      surfaces: Surface[];
      resolveSecondarySurface(primaryId: string): Surface | null;
    };
    shell.userConfig = { secondaryActiveSurface: 'core.ask-surface' };
    shell.surfaces = [LIBRARY, UNIFIED_SEARCH];

    expect(shell.resolveSecondarySurface('core.library-surface')?.id).toBe(
      'core.unified-chat-surface',
    );
    expect(shell.resolveSecondarySurface('core.unified-chat-surface')?.id).toBe(
      'core.library-surface',
    );
    for (const legacyId of [
      'core.ask-surface',
      'core.free-chat-surface',
      'core.extract-surface',
    ]) {
      expect(shell.resolveSecondarySurface(legacyId)?.id).toBe('core.library-surface');
    }
  });

  describe('connectedCallback bootstrap', () => {
    it(
      'URL-hash state-bearing intent at boot distributes to stores via the substrate ' +
        '(the headline regression scenario)',
      async () => {
        // Pre-seed the surface stateSchema (the Shell's bootstrap fetch
        // returns entries:[] via the stub, so we register the schema
        // directly). The `registerCoreStores()` call inside
        // connectedCallback registers the production `search` store
        // (which restoreSearch / serializeSearch reach); the adapter
        // for `storeId: 'search'` resolves to that production store.
        registerSurfaceStateSchema('core.search-surface', {
          schema: JSON.stringify({
            type: 'object',
            properties: { query: { type: 'string' } },
          }),
          bindings: [
            { schemaPath: '/query', storeId: 'search', storeKey: 'query' },
          ],
        });
        // Reset the production searchState to a known empty starting
        // point so the assertion below reflects state restored by the
        // boot-read, not pre-existing state.
        restoreSearch({ query: '' });

        // Boot with a state-bearing URL hash.
        window.location.hash = '#justsearch://surface/core.search-surface?query=hello';

        const shell = await renderShell();

        // The URLSource boot-read dispatched the Intent through the router
        // → NavigationHandler → store. The state landed in the production
        // searchState (which is what `restoreSearch` writes to and
        // `serializeSearch` reads from).
        expect(serializeSearch().query).toBe('hello');
        expect(shell.activeId).toBe('core.search-surface');
      },
    );

    it('rail click dispatches with RAIL transport', async () => {
      const shell = await renderShell();
      shell.activateSurface('core.library-surface', {}, 'RAIL');
      await new Promise((r) => setTimeout(r, 10));
      expect(shell.activeId).toBe('core.library-surface');
      expect(window.location.hash).toContain(
        'justsearch://surface/core.library-surface',
      );
    });

    it('activateSurface requires a transport (compile-time check, runtime smoke)', async () => {
      const shell = await renderShell();
      // BUTTON for drop-redirect-style activations.
      shell.activateSurface('core.library-surface', {}, 'BUTTON');
      await new Promise((r) => setTimeout(r, 10));
      expect(shell.activeId).toBe('core.library-surface');
    });
  });

  describe('disconnectedCallback teardown', () => {
    it('cleanly tears down without throwing when sources are mid-bootstrap', async () => {
      // Connect + disconnect quickly. The Tauri source's async start
      // resolves *after* disconnect (in happy-dom it resolves to a no-op
      // teardown because isTauriRuntime() is false; still, the race-aware
      // codepath is the same). The fix asserts: no throw, no leak.
      const shell = await renderShell();
      shell.remove();
      // Let any pending async source.start() promises settle.
      await new Promise((r) => setTimeout(r, 30));
      // If the teardown race caused an unhandled rejection or threw, this
      // test would fail. Reaching here without an error is the assertion.
      expect(document.querySelectorAll('jf-shell').length).toBe(0);
    });

    it('subsequent connect after disconnect re-bootstraps cleanly', async () => {
      const shell = await renderShell();
      document.body.removeChild(shell);
      await new Promise((r) => setTimeout(r, 30));
      document.body.appendChild(shell);
      await shell.updateComplete;
      await new Promise((r) => setTimeout(r, 30));
      // Activate something — verifies the substrate re-wired correctly.
      shell.activateSurface('core.search-surface', {}, 'RAIL');
      await new Promise((r) => setTimeout(r, 10));
      expect(shell.activeId).toBe('core.search-surface');
    });
  });
});

// Search Thread S6 — InspectorPane (`<jf-inspector-pane>`, the Preview/Context/Answer/Ask drawer)
// retired: `<jf-document-pane>` now renders inside UnifiedChatView's own conversation-zone column, and
// Shell.onCitationSelect no longer reaches into a specific pane instance — it pushes the passage line
// range onto the shared inspectorState `selected` (the new optional highlightStartLine/highlightEndLine
// fields) for UnifiedChatView's own inspectorState subscription to project.
describe('Shell — Search Thread S6 citation-select rework', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    seedTwoSurfaces();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    resetInspectorState();
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    __resetAiStateForTest();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    vi.unstubAllGlobals();
    resetInspectorState();
  });

  it('a citation-select event pushes the doc path + highlight range onto inspectorState (no jf-inspector-pane lookup)', async () => {
    const shell = await renderShell();
    shell.dispatchEvent(
      new CustomEvent('citation-select', {
        detail: {
          parentDocId: '/docs/report.md',
          startLine: 4,
          endLine: 9,
          startChar: 0,
          endChar: 120,
          excerpt: 'excerpt text',
        },
        bubbles: true,
        composed: true,
      }),
    );
    const s = getInspectorState();
    expect(s.selected).toMatchObject({
      id: '/docs/report.md',
      path: '/docs/report.md',
      title: 'report.md',
      highlightStartLine: 4,
      highlightEndLine: 9,
    });
    expect(s.isOpen).toBe(true);
  });

  it('never mounts the retired jf-inspector-pane', async () => {
    const shell = await renderShell();
    expect(shell.shadowRoot?.querySelector('jf-inspector-pane')).toBeNull();
  });
});

// Tempdoc 855 §11.1 — the MODAL settings window lives OVER the stage, so the two navigation
// branches must agree about who owns the address. Opening goes through the MODAL branch (no
// setActiveSurface); anything that REALIZES a stage navigation — a real browser Back included,
// since popstate re-enters through the normal branch — must take the window down with it, and
// must not fire the `history.back()` that the window's own close routine would.
describe('Shell — settings window vs. stage navigation (tempdoc 855)', () => {
  const SETTINGS_ID = 'core.settings-surface';

  /** MODAL settings entry with a non-lazy, undefined mountTag: the window renders its empty state. */
  const SETTINGS_MODAL: Surface = {
    ...makeRailSurface(SETTINGS_ID, 'jf-test-settings-mount'),
    placement: 'MODAL',
  };

  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    seedSurfaceCatalog({
      schemaVersion: '1.0.0',
      catalogVersion: 1,
      namespace: 'core',
      primitive: 'Surface',
      entries: [SEARCH, LIBRARY, SETTINGS_MODAL],
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    __resetAiStateForTest();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    vi.unstubAllGlobals();
  });

  it('a realized stage navigation DISMISSES the open window — and fires no history.back()', async () => {
    const shell = await renderShell();
    const win = shell.shadowRoot?.querySelector('jf-settings-window') as SettingsWindow | null;
    expect(win).not.toBeNull();

    // MODAL branch: the window opens and the stage keeps its surface.
    shell.activateSurface(SETTINGS_ID, {}, 'RAIL');
    await new Promise((r) => setTimeout(r, 10));
    await win!.updateComplete;
    expect(win!.open).toBe(true);
    expect(shell.activeId).not.toBe(SETTINGS_ID);

    // Normal branch (the shape a real browser Back takes: popstate → URLSource → this path).
    // history has ALREADY moved, so the dismissal must not add its own back().
    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    shell.activateSurface('core.library-surface', {}, 'RAIL');
    await new Promise((r) => setTimeout(r, 10));
    await win!.updateComplete;

    expect(shell.activeId).toBe('core.library-surface');
    expect(win!.open).toBe(false);
    expect(win!.shadowRoot?.querySelector('dialog')?.open).toBe(false);
    expect(backSpy).not.toHaveBeenCalled();
    backSpy.mockRestore();
  });

  it('a stage navigation while the window is CLOSED touches nothing (no stray dismiss path)', async () => {
    const shell = await renderShell();
    const win = shell.shadowRoot?.querySelector('jf-settings-window') as SettingsWindow | null;
    expect(win!.open).toBe(false);

    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    shell.activateSurface('core.library-surface', {}, 'RAIL');
    await new Promise((r) => setTimeout(r, 10));

    expect(shell.activeId).toBe('core.library-surface');
    expect(win!.open).toBe(false);
    expect(backSpy).not.toHaveBeenCalled();
    backSpy.mockRestore();
  });
});

// Tempdoc 855 §11.1 D3/D4 — the CLOSE contract. The window emits `settings-window-close` and makes
// no history assumption of its own; the Shell unwinds, and which unwind is correct depends on
// whether the OPENING navigation pushed an entry. Getting that wrong is the pair of defects this
// block pins: a duplicate entry (Escape appears to re-open) and a boot entry left with no URL
// projection for the rest of the session.
describe('Shell — settings window close semantics (tempdoc 855 §11.1 D3/D4)', () => {
  const SETTINGS_ID = 'core.settings-surface';
  const MEMBER_ID = 'core.security-surface';
  const STAGE_ID = 'core.search-surface';

  const SETTINGS_MODAL: Surface = {
    ...makeRailSurface(SETTINGS_ID, 'jf-test-settings-mount'),
    placement: 'MODAL',
    members: [MEMBER_ID],
  };
  const MEMBER: Surface = {
    ...makeRailSurface(MEMBER_ID, 'jf-test-member-mount'),
    placement: 'DEEPLINK',
  };

  const settle = (): Promise<void> => new Promise((r) => setTimeout(r, 15));

  function windowOf(shell: ShellElement): SettingsWindow {
    return shell.shadowRoot?.querySelector('jf-settings-window') as SettingsWindow;
  }

  /** Escape on a native <dialog> arrives as a cancelable `cancel` event. */
  function pressEscape(win: SettingsWindow): void {
    win.shadowRoot
      ?.querySelector('dialog')
      ?.dispatchEvent(new Event('cancel', { cancelable: true }));
  }

  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.history.replaceState(null, '', '/');
    seedSurfaceCatalog({
      schemaVersion: '1.0.0',
      catalogVersion: 1,
      namespace: 'core',
      primitive: 'Surface',
      entries: [SEARCH, LIBRARY, SETTINGS_MODAL, MEMBER],
    });
    // The stage surfaces declare a (trivial) state schema so `activateProjection` actually takes
    // ownership — without one the projector is a no-op and clause (d) could not distinguish
    // "projection restored" from "projector never engaged".
    for (const id of [STAGE_ID, 'core.library-surface']) {
      registerSurfaceStateSchema(id, {
        schema: JSON.stringify({ type: 'object', properties: {} }),
        bindings: [],
      });
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    __resetAiStateForTest();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.history.replaceState(null, '', '/');
    vi.unstubAllGlobals();
  });

  it('(a) an in-app open pushes ONE entry, and Escape unwinds it with exactly one history.back()', async () => {
    const shell = await renderShell();
    const win = windowOf(shell);

    shell.activateSurface('core.library-surface', {}, 'RAIL');
    await settle();
    const priorHash = window.location.hash;
    expect(priorHash).toContain('core.library-surface');

    shell.activateSurface(SETTINGS_ID, {}, 'RAIL');
    await settle();
    expect(win.open).toBe(true);
    expect(window.location.hash).toContain(SETTINGS_ID);

    // `back` is spied but NOT stubbed: the real navigation runs, so the assertion below is about
    // the address the user actually lands on, not just the call count.
    const backSpy = vi.spyOn(window.history, 'back');
    pressEscape(win);
    await settle();

    expect(backSpy).toHaveBeenCalledTimes(1);
    expect(win.open).toBe(false);
    expect(window.location.hash).toBe(priorHash);
    backSpy.mockRestore();
  });

  it('(b) a boot entry pushes NOTHING, so Escape navigates forward to the stage surface instead of Back', async () => {
    window.history.replaceState(null, '', `/#justsearch://surface/${SETTINGS_ID}`);
    const shell = await renderShell();
    const win = windowOf(shell);
    await settle();
    expect(win.open).toBe(true);

    // There is no prior entry to return to — `history.back()` here would leave the app.
    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    pressEscape(win);
    await settle();

    expect(backSpy).not.toHaveBeenCalled();
    expect(win.open).toBe(false);
    expect(shell.activeId).toBe(STAGE_ID);
    expect(window.location.hash).toContain(STAGE_ID);
    backSpy.mockRestore();
  });

  it('(d) after a boot-entry close, the stage surface OWNS the URL projection again', async () => {
    window.history.replaceState(null, '', `/#justsearch://surface/${SETTINGS_ID}`);
    const shell = await renderShell();
    const win = windowOf(shell);
    await settle();

    // The defect: the MODAL branch deliberately skips activateProjection, and a boot straight onto
    // the settings address never ran the normal branch for anything — so NO surface owns the URL.
    expect(win.open).toBe(true);
    expect(__activeSurfaceIdForTest()).toBeNull();

    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    pressEscape(win);
    await settle();

    expect(__activeSurfaceIdForTest()).toBe(STAGE_ID);
    backSpy.mockRestore();
  });

  it('(c) a repeat navigation while the window is open stacks no entry — Escape still closes in one step', async () => {
    const shell = await renderShell();
    const win = windowOf(shell);

    shell.activateSurface('core.library-surface', {}, 'RAIL');
    await settle();
    const priorHash = window.location.hash;

    shell.activateSurface(SETTINGS_ID, {}, 'RAIL');
    await settle();
    expect(win.open).toBe(true);

    const pushSpy = vi.spyOn(window.history, 'pushState');
    shell.activateSurface(SETTINGS_ID, {}, 'RAIL');
    await settle();
    expect(pushSpy).not.toHaveBeenCalled();
    expect(win.open).toBe(true);
    pushSpy.mockRestore();

    const backSpy = vi.spyOn(window.history, 'back');
    pressEscape(win);
    await settle();

    expect(backSpy).toHaveBeenCalledTimes(1);
    expect(win.open).toBe(false);
    expect(window.location.hash).toBe(priorHash);
    backSpy.mockRestore();
  });

  // Composition: a member of the MODAL host (Settings ⊇ Security) deep-links to its HOST, which now
  // opens as a window. Both halves must survive the MODAL branch — the window opens AND the
  // member-tab intent reaches the persistently-mounted surface (§11.2).
  it('a member deep-link opens the window AND delivers the member intent (window closed)', async () => {
    const shell = await renderShell();
    const win = windowOf(shell);
    const seen: Array<[string, string]> = [];
    const unsubscribe = subscribeMemberTab((hostId, memberId) => {
      seen.push([hostId, memberId]);
      return true;
    });

    // BUTTON transport: the auto-correct path is transport-independent, and BUTTON keeps the
    // unrelated URL_BAR resolution-toast machinery out of this assertion.
    shell.activateSurface(MEMBER_ID, {}, 'BUTTON');
    await settle();

    expect(win.open).toBe(true);
    expect(shell.activeId).not.toBe(SETTINGS_ID);
    expect(seen).toEqual([[SETTINGS_ID, MEMBER_ID]]);
    unsubscribe();
  });

  it('a member deep-link delivers the member intent while the window is ALREADY open', async () => {
    const shell = await renderShell();
    const win = windowOf(shell);

    shell.activateSurface(SETTINGS_ID, {}, 'RAIL');
    await settle();
    expect(win.open).toBe(true);

    const seen: Array<[string, string]> = [];
    const unsubscribe = subscribeMemberTab((hostId, memberId) => {
      seen.push([hostId, memberId]);
      return true;
    });

    shell.activateSurface(MEMBER_ID, {}, 'BUTTON');
    await settle();

    expect(seen).toEqual([[SETTINGS_ID, MEMBER_ID]]);
    // Re-entering the host it is already showing must not stack an entry (D3) or close the window.
    expect(win.open).toBe(true);
    unsubscribe();
  });
});

// Fix-round F2 (tempdoc 855) — pins the Shell listener seam. `SettingsSurface.highContrast.test.ts`
// covers the whole chain from the Accessibility switch click through to the `high-contrast` root
// class, but binds its OWN two-line adapter onto `applyAppearance` rather than exercising Shell's
// real `document.addEventListener('jf-set-appearance', this.setAppearanceListener)` registration
// (Shell.ts:1091) — that seam had zero coverage. This is the sole remaining HC set-site (the
// Appearance card's duplicate toggle was pruned, §17 R1), so pinning its OWN registration here
// closes the gap: mount the real Shell, dispatch the SAME event a real caller (SettingsSurface via
// `patch()` → the appearance statechart's `set-appearance` effect) would dispatch, and assert the
// class Shell's listener is responsible for applying.
describe('Shell — appearance listener seam (tempdoc 855 fix round F2)', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    seedTwoSurfaces();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    __resetAiStateForTest();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    vi.unstubAllGlobals();
    document.documentElement.classList.remove('high-contrast');
  });

  it('registers the jf-set-appearance listener that applies the high-contrast root class', async () => {
    await renderShell();
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);

    document.dispatchEvent(
      new CustomEvent('jf-set-appearance', { detail: { highContrast: true } }),
    );

    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
  });
});
