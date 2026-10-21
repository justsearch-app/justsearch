// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
const nativeListeners = new Map<string, (event: { payload: unknown }) => void>();
let delayedListener: Promise<void> | null = null;
let pending: { kind: 'focusSearch' } | { kind: 'deepLink'; url: string } | null = null;
const invoke = vi.fn(async (command: string) => {
  if (command === 'take_search_summon') { const value = pending; pending = null; return value; }
  if (command === 'search_shortcut_status') return { enabled: true, active: true, reason: null };
  return undefined;
});
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn(async (name: string, handler: (event: { payload: unknown }) => void) => {
  nativeListeners.set(name, handler);
  if (name === 'justsearch://summon-search' && delayedListener) await delayedListener;
  return () => { if (nativeListeners.get(name) === handler) nativeListeners.delete(name); };
}) }));
vi.mock('@tauri-apps/plugin-deep-link', () => ({ getCurrent: vi.fn(async () => null) }));
vi.mock('../../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
import './Shell.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest, getUserConfig, setSurfaceOverride } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { getJournalState, __resetJournalForTest } from '../state/NavigationJournal.js';
import { ModalityController, __resetModalityForTest } from '../primitives/modality.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import type { Surface } from '../../api/types/surface.js';
import { registerCommand, unregisterCommand } from '../commands/CommandRegistry.js';
import { registerKeybinding, unregisterKeybinding, listKeybindings } from '../commands/KeybindingRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { initLayoutCatalog } from '../layout/LayoutManifest.js';
import { setActiveLayoutId, setSecondaryActiveSurface } from '../state/userConfigState.js';

const DEFAULT = 'core.unified-chat-surface';
const LIBRARY = 'core.library-surface';
const OTHER = 'plugin.other';
class SearchProbe extends HTMLElement {
  readonly field: HTMLTextAreaElement;
  readonly focusComposer = vi.fn(() => { this.field.focus(); });
  constructor() {
    super();
    this.field = document.createElement('textarea');
    this.field.setAttribute('aria-label', 'Search');
    this.attachShadow({ mode: 'open' }).append(this.field);
  }
}
if (!customElements.get('jf-summon-search-probe')) customElements.define('jf-summon-search-probe', SearchProbe);
if (!customElements.get('jf-summon-other-probe')) customElements.define('jf-summon-other-probe', class extends SearchProbe {});
if (!customElements.get('jf-summon-library-probe')) customElements.define('jf-summon-library-probe', class extends HTMLElement { readonly focusComposer = { label: 'unrelated plugin data' }; });
function surface(id: string, mountTag: string): Surface {
  return { id, mountTag, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } };
}
interface ShellElement extends HTMLElement {
  activeId: string;
  updateComplete: Promise<unknown>;
  activateSurface(id: string, state: Record<string, never>, transport: 'BUTTON'): void;
}
async function settle(shell: ShellElement): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 30));
  await shell.updateComplete;
}
async function mount(): Promise<ShellElement> {
  const shell = document.createElement('jf-shell') as ShellElement;
  document.body.append(shell);
  await settle(shell);
  return shell;
}
async function summon(shell: ShellElement): Promise<void> {
  pending = { kind: 'focusSearch' };
  nativeListeners.get('justsearch://summon-search')?.({ payload: null });
  await settle(shell);
}
function probe(shell: ShellElement): SearchProbe | null {
  return shell.shadowRoot?.querySelector('jf-stage')?.shadowRoot?.querySelector('jf-summon-search-probe') ?? null;
}
describe('native search summon shared destination', () => {
  beforeEach(() => {
    resetCatalog(); __resetUserConfigForTest(); __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest(); __resetBootstrapForTest(); deactivateProjection();
    window.location.hash = ''; pending = null; delayedListener = null; nativeListeners.clear();
    __resetJournalForTest();
    initLayoutCatalog();
    seedCatalog({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
      surface(DEFAULT, 'jf-summon-search-probe'), surface(LIBRARY, 'jf-summon-library-probe'), surface(OTHER, 'jf-summon-other-probe'),
    ] });
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  });
  afterEach(async () => {
    document.body.replaceChildren();
    await new Promise((resolve) => setTimeout(resolve, 0));
    __resetAiStateForTest(); __resetModalityForTest(); deactivateProjection();
    __resetBootstrapForTest(); __resetUserConfigForTest(); resetCatalog();
    vi.unstubAllGlobals();
    unregisterCommand('test.overlap');
    unregisterKeybinding('alt+shift+space', 'test.overlap');
  });

  it('SC2 SC3 focuses the current default once without changing draft, URL or journal', async () => {
    const shell = await mount();
    const search = probe(shell);
    expect(search).toBeTruthy();
    search!.field.value = 'keep this draft';
    const journal = getJournalState();
    const url = window.location.href;
    await summon(shell);
    expect(search!.focusComposer).toHaveBeenCalledTimes(1);
    expect(deepActiveElement()).toBe(search!.field);
    await summon(shell);
    expect(search!.field.value).toBe('keep this draft');
    expect(getJournalState()).toEqual(journal);
    expect(window.location.href).toBe(url);
  });

  it('SC4 leaves a modal and an ordinary field in charge, including a modal button', async () => {
    const shell = await mount();
    const search = probe(shell)!;
    const button = document.createElement('button'); document.body.append(button); button.focus();
    const modality = new ModalityController({ addController: () => {} } as never);
    modality.enter();
    try {
      await summon(shell);
      expect(deepActiveElement()).toBe(button);
      expect(search.focusComposer).not.toHaveBeenCalled();
    } finally { modality.exit(); }
    const input = document.createElement('input'); search.shadowRoot!.append(input); input.focus();
    await summon(shell);
    expect(deepActiveElement()).toBe(input);
    expect(search.focusComposer).not.toHaveBeenCalled();
  });

  it('SC4 navigates from a non-modal view once with the prior view one Back away', async () => {
    const shell = await mount();
    shell.activateSurface(LIBRARY, {}, 'BUTTON');
    await settle(shell);
    const before = getJournalState().entries.length;
    await summon(shell);
    expect(shell.activeId).toBe(DEFAULT);
    expect(probe(shell)?.focusComposer).toHaveBeenCalledTimes(1);
    expect(getJournalState().entries.length).toBe(before + 1);
  });

  it('SC5 drains a request already pending before subscription and never replays it on reconnect', async () => {
    pending = { kind: 'focusSearch' };
    const shell = await mount();
    expect(probe(shell)?.focusComposer).toHaveBeenCalledTimes(1);
    shell.remove();
    document.body.append(shell);
    await settle(shell);
    expect(probe(shell)?.focusComposer).toHaveBeenCalledTimes(1);
  });

  it('SC2 preserves a composer override and uses it as the destination', async () => {
    setSurfaceOverride(DEFAULT, OTHER);
    const shell = await mount();
    const override = shell.shadowRoot?.querySelector('jf-stage')?.shadowRoot?.querySelector('jf-summon-other-probe') as SearchProbe;
    await summon(shell);
    expect(override.focusComposer).toHaveBeenCalledTimes(1);
    expect(getUserConfig().surfaceOverride?.[DEFAULT]).toBe(OTHER);
  });

  it('SC2 falls back temporarily for an override without a composer and preserves the saved override', async () => {
    setSurfaceOverride(DEFAULT, LIBRARY);
    const shell = await mount();
    await summon(shell);
    expect(probe(shell)).toBeTruthy();
    expect(probe(shell)!.focusComposer).toHaveBeenCalledTimes(1);
    expect(getUserConfig().surfaceOverride?.[DEFAULT]).toBe(LIBRARY);
  });

  it('SC2 respects a changed override after a temporary core fallback', async () => {
    setSurfaceOverride(DEFAULT, LIBRARY);
    const shell = await mount();
    await summon(shell);
    expect(probe(shell)).toBeTruthy();
    setSurfaceOverride(DEFAULT, OTHER);
    await settle(shell);
    const button = document.createElement('button'); document.body.append(button); button.focus();
    await summon(shell);
    const override = shell.shadowRoot?.querySelector('jf-stage')?.shadowRoot?.querySelector('jf-summon-other-probe') as SearchProbe | null;
    expect(override).toBeTruthy();
    expect(override?.focusComposer).toHaveBeenCalledTimes(1);
    expect(getUserConfig().surfaceOverride?.[DEFAULT]).toBe(OTHER);
  });

  it('SC2 ignores a missing override target without changing the saved override', async () => {
    setSurfaceOverride(DEFAULT, 'plugin.missing');
    const shell = await mount();
    await summon(shell);
    expect(probe(shell)?.focusComposer).toHaveBeenCalledTimes(1);
    expect(getUserConfig().surfaceOverride?.[DEFAULT]).toBe('plugin.missing');
  });

  it('SC2 targets the real Search v3 composer with no chat model and preserves its draft', async () => {
    seedCatalog({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
      surface(DEFAULT, 'jf-sv3-window'), surface(LIBRARY, 'jf-summon-library-probe'),
    ] });
    const shell = await mount();
    let field: HTMLTextAreaElement | null = null;
    await vi.waitFor(() => {
      field = shell.shadowRoot?.querySelector('jf-stage')?.shadowRoot?.querySelector('jf-sv3-window')
        ?.shadowRoot?.querySelector('jf-sv3-composer')?.shadowRoot?.querySelector('textarea') ?? null;
      expect(field).toBeTruthy();
    }, { timeout: 5000 });
    const input = field as unknown as HTMLTextAreaElement;
    input.value = 'keep the actual v3 draft';
    input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    const button = document.createElement('button'); document.body.append(button); button.focus();
    await summon(shell);
    expect(deepActiveElement()).toBe(input);
    expect(input.getAttribute('aria-label')).toBeTruthy();
    expect(input.value).toBe('keep the actual v3 draft');
  });

  it('SC2 reuses a secondary search pane and never focuses the other composer', async () => {
    setActiveLayoutId('core.split');
    setSecondaryActiveSurface(DEFAULT);
    const shell = await mount();
    shell.activateSurface(OTHER, {}, 'BUTTON');
    await settle(shell);
    const stage = shell.shadowRoot!.querySelector('jf-stage')!;
    const other = stage.shadowRoot!.querySelector('jf-summon-other-probe') as SearchProbe;
    const search = probe(shell)!;
    const journal = getJournalState();
    await summon(shell);
    expect(shell.activeId).toBe(OTHER);
    expect(probe(shell)).toBe(search);
    expect(search.focusComposer).toHaveBeenCalledTimes(1);
    expect(other.focusComposer).not.toHaveBeenCalled();
    expect(getJournalState()).toEqual(journal);
  });

  it('SC9 a pending explicit deep link keeps its destination instead of focusing Search', async () => {
    pending = { kind: 'deepLink', url: 'justsearch://surface/core.library-surface' };
    const shell = await mount();
    expect(shell.activeId).toBe(LIBRARY);
    expect(probe(shell)).toBeNull();
  });

  it('SC5 does not wait for the Engine schema request before consuming a ready focus request', async () => {
    let finish!: (response: Response) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { finish = resolve; })));
    pending = { kind: 'focusSearch' };
    const shell = await mount();
    try {
      expect(probe(shell)?.focusComposer).toHaveBeenCalledTimes(1);
    } finally {
      finish(new Response(JSON.stringify({ entries: [] }), { status: 200 }));
      await settle(shell);
    }
  });

  it('SC5 SC6 late subscription cleanup cannot disable the reloaded view shortcut guard', async () => {
    let release!: () => void;
    delayedListener = new Promise<void>((resolve) => { release = resolve; });
    const old = await mount();
    old.remove();
    delayedListener = null;
    const current = await mount();
    const press = () => {
      const event = new KeyboardEvent('keydown', { key: ' ', code: 'Space', altKey: true, shiftKey: true, bubbles: true, cancelable: true });
      document.body.dispatchEvent(event);
      return event;
    };
    try {
      expect(press().defaultPrevented).toBe(true);
      release();
      await settle(current);
      expect(press().defaultPrevented).toBe(true);
    } finally {
      release();
      await settle(current);
    }
  });

  it('SC11 native registration takes precedence without activating previously inert space bindings', async () => {
    const called = vi.fn();
    const shell = await mount();
    registerCommand({ id: 'test.overlap', label: 'overlap', source: 'plugin', provenance: CORE_PROVENANCE, handler: called });
    registerKeybinding({ key: 'alt+shift+space', commandId: 'test.overlap', source: 'user', provenance: CORE_PROVENANCE });
    const saved = listKeybindings().find((entry) => entry.commandId === 'test.overlap');
    const press = () => {
      const event = new KeyboardEvent('keydown', { key: ' ', code: 'Space', altKey: true, shiftKey: true, bubbles: true, cancelable: true });
      document.body.dispatchEvent(event);
      return event;
    };
    expect(press().defaultPrevented).toBe(true);
    expect(called).not.toHaveBeenCalled();
    nativeListeners.get('justsearch://search-shortcut-status')?.({ payload: { enabled: false, active: false, reason: null, revision: 2 } });
    expect(press().defaultPrevented).toBe(false);
    expect(called).not.toHaveBeenCalled();
    expect(listKeybindings().find((entry) => entry.commandId === 'test.overlap')).toEqual(saved);
    shell.remove();
  });
});
