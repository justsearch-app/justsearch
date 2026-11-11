// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Shell S6 SP1 — one chord, one palette: the keybinding registry's binding for the toggle action is
 * the only owner of Ctrl K (Cmd K on macOS), from every area; the toggle reads modality when it runs.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './Shell.js';
import '../views/search-v3/SearchV3View.js';
import type { Shell } from './Shell.js';
import type { SettingsWindow } from './SettingsWindow.js';
import type { CommandPalette } from '../commands/CommandPalette.js';
import type { SearchV3View } from '../views/search-v3/SearchV3View.js';
import { invokeCommandWithResult, onCommandInvoked } from '../commands/CommandRegistry.js';
import type { ReactiveControllerHost } from 'lit';
import { ModalityController, modalOwnsFocus, __resetModalityForTest } from '../primitives/modality.js';
import { confirmAsync } from '../components/ConfirmDialog.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { __resetJournalForTest } from '../state/NavigationJournal.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetConversationListForTest } from '../state/conversationListStore.js';
import { resetSearchState } from '../state/searchState.js';
import type { Surface } from '../../api/types/surface.js';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';
const CONTRIBUTED = 'acme.timeline-surface';

function surface(id: string, mountTag: string, extra: Partial<Surface> = {}): Surface {
  return { id, mountTag, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra };
}
/** A window with one control, so a chord can be raised from inside it. */
class AreaProbe extends HTMLElement {
  connectedCallback(): void { if (!this.firstChild) this.append(Object.assign(document.createElement('button'), { textContent: this.localName })); }
}
for (const tag of ['jf-palette-library-probe', 'jf-palette-brain-probe', 'jf-palette-contributed-probe', 'jf-palette-settings-probe']) {
  if (!customElements.get(tag)) customElements.define(tag, class extends AreaProbe {});
}

async function drain(): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(r => setTimeout(r, 0));
}
async function mount(): Promise<Shell> {
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell);
  await shell.updateComplete; await drain(); await shell.updateComplete; await drain();
  return shell;
}
async function settle(shell: Shell): Promise<void> { await drain(); await shell.updateComplete; await drain(); }
const palette = (shell: Shell): CommandPalette => shell.shadowRoot!.querySelector('jf-command-palette') as CommandPalette;
const stage = (shell: Shell): ShadowRoot => shell.shadowRoot!.querySelector('jf-stage')!.shadowRoot!;
const search = (shell: Shell): SearchV3View => stage(shell).querySelector('jf-sv3-window') as SearchV3View;
async function composerField(shell: Shell): Promise<HTMLTextAreaElement> {
  const composer = search(shell).shadowRoot!.querySelector('jf-sv3-composer') as HTMLElement & { updateComplete: Promise<unknown> };
  await composer.updateComplete;
  return composer.shadowRoot!.querySelector('textarea')!;
}
/** Every palette-like dialog open anywhere: the app's one and anything inside Search. */
function openPalettes(shell: Shell): number {
  const inSearch = [...(search(shell)?.shadowRoot?.querySelectorAll<HTMLElement & { open?: boolean }>('jf-sv3-palette, [role="dialog"][aria-modal="true"]') ?? [])]
    .filter(dialog => dialog.open !== false).length;
  return (palette(shell).open ? 1 : 0) + inSearch;
}
function chord(target: EventTarget, init: KeyboardEventInit & { altGraph?: boolean } = {}): KeyboardEvent {
  const { altGraph, ...rest } = init;
  const event = new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true, composed: true, cancelable: true, ...rest });
  if (altGraph) Object.defineProperty(event, 'getModifierState', { value: (name: string) => name === 'AltGraph' });
  target.dispatchEvent(event);
  return event;
}
async function go(shell: Shell, id: string): Promise<void> { shell.activateSurface(id, {}, 'RAIL'); await settle(shell); }

beforeEach(() => {
  vi.spyOn(window.performance, 'getEntriesByType').mockImplementation(type => type === 'navigation'
    ? [{ type: 'navigate' } as unknown as PerformanceEntry] : []);
  localStorage.clear(); sessionStorage.clear();
  window.history.pushState(null, '', '/');
  resetCatalog(); __resetBootstrapForTest(); __resetSurfaceSchemasForTest(); __resetStoreRegistryForTest();
  __resetUserConfigForTest(); __resetJournalForTest(); __resetAiStateForTest(); deactivateProjection();
  __resetConversationListForTest(); resetSearchState(); __resetModalityForTest();
  seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(SEARCH, 'jf-sv3-window'), surface(LIBRARY, 'jf-palette-library-probe'), surface(BRAIN, 'jf-palette-brain-probe'),
    surface(CONTRIBUTED, 'jf-palette-contributed-probe', { provenance: { tier: 'TRUSTED_PLUGIN', contributorId: 'acme', version: '1.0.0' } }),
    surface(SETTINGS, 'jf-palette-settings-probe', { placement: 'MODAL' }),
  ] });
  vi.stubGlobal('fetch', vi.fn(async (input: unknown) => {
    const url = String(input);
    if (url.includes('/api/chat/conversations?')) return Response.json({ sessions: [] });
    if (url.includes('/api/chat/runs/live')) return Response.json({ runs: [] });
    return Response.json({ entries: [] });
  }));
});
afterEach(() => {
  document.querySelectorAll('jf-shell').forEach(el => el.remove());
  deactivateProjection(); __resetModalityForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

describe('SP1 one chord, one palette', () => {
  it('SP1 one chord press toggles exactly one palette from Search\'s composer, Library, AI Brain and a contributed window', async () => {
    const shell = await mount();
    const invoked: string[] = [];
    const stop = onCommandInvoked(id => invoked.push(id));
    try {
      for (const area of [SEARCH, LIBRARY, BRAIN, CONTRIBUTED]) {
        await go(shell, area);
        const invoker = area === SEARCH ? await composerField(shell) : stage(shell).querySelector('button')!;
        invoker.focus();
        invoked.length = 0;
        chord(invoker); await settle(shell);
        expect(invoked, area).toEqual(['shell.toggle-palette']);
        expect(palette(shell).open, area).toBe(true);
        expect(openPalettes(shell), area).toBe(1);
        // The next press closes it, and focus goes back to where the chord was pressed.
        chord(palette(shell).shadowRoot!.querySelector('input')!); await settle(shell);
        expect(invoked, area).toEqual(['shell.toggle-palette', 'shell.toggle-palette']);
        expect(openPalettes(shell), area).toBe(0);
        expect(invoker.getRootNode() instanceof ShadowRoot ? (invoker.getRootNode() as ShadowRoot).activeElement : document.activeElement, area).toBe(invoker);
      }
    } finally { stop(); }
  });

  it('SP1 the Search topbar button opens the same palette through the toggle action', async () => {
    const shell = await mount();
    const topbar = search(shell).shadowRoot!.querySelector('jf-sv3-topbar') as HTMLElement & { updateComplete: Promise<unknown> };
    await topbar.updateComplete;
    const button = topbar.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-topbar-palette"]')!;
    button.focus(); button.click(); await settle(shell);
    expect(palette(shell).open).toBe(true);
    expect(openPalettes(shell)).toBe(1);
    palette(shell).shadowRoot!.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }));
    await settle(shell);
    expect(palette(shell).open).toBe(false);
    expect(topbar.shadowRoot!.activeElement).toBe(button);
  });

  it('SP1 under Settings, a confirmation or any other modal, the chord and the toggle action leave that dialog in control', async () => {
    const shell = await mount();
    const field = await composerField(shell); field.focus();
    // Settings, opened by its own chord.
    chord(field, { key: ',' }); await settle(shell);
    const settings = shell.shadowRoot!.querySelector<SettingsWindow>('jf-settings-window')!;
    expect(settings.open).toBe(true);
    chord(document.body); await settle(shell);
    expect(palette(shell).open).toBe(false);
    settings.dismiss(); await settle(shell);
    expect(settings.open).toBe(false);
    // A confirmation dialog owns the keyboard.
    const cancel = new AbortController();
    const confirmation = confirmAsync({ title: 'Remove folder?', message: 'Remove it from the library?', signal: cancel.signal });
    await settle(shell);
    expect(modalOwnsFocus()).toBe(true);
    chord(field); await settle(shell);
    expect(palette(shell).open).toBe(false);
    cancel.abort(); await confirmation; await settle(shell);
    expect(modalOwnsFocus()).toBe(false);
    // The check is the toggle action's own, made when it runs, whoever asks (not only the key path).
    const other = new ModalityController({ addController: () => {} } as unknown as ReactiveControllerHost);
    other.enter();
    expect((await invokeCommandWithResult('shell.toggle-palette')).ok).toBe(true);
    await settle(shell);
    expect(palette(shell).open).toBe(false);
    other.exit({ skipFocusRestore: true });
    chord(field); await settle(shell);
    expect(palette(shell).open).toBe(true);
  });

  it('SP1 IME composition, key repeat, extra modifiers and AltGraph never toggle; Cmd K on macOS and Ctrl K elsewhere', async () => {
    const shell = await mount();
    const field = await composerField(shell); field.focus();
    for (const extra of [{ isComposing: true }, { keyCode: 229 }, { repeat: true }, { shiftKey: true }, { altKey: true }, { altGraph: true },
      { ctrlKey: false, metaKey: true }, { metaKey: true }]) {
      chord(field, extra); await settle(shell);
      expect(openPalettes(shell), JSON.stringify(extra)).toBe(0);
    }
    vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel');
    chord(field); await settle(shell);
    expect(openPalettes(shell), 'Ctrl K on macOS').toBe(0);
    chord(field, { ctrlKey: false, metaKey: true }); await settle(shell);
    expect(palette(shell).open, 'Cmd K on macOS').toBe(true);
    expect(openPalettes(shell)).toBe(1);
  });

  it('SP1 a keydown the IME is processing (keyCode 229, isComposing still false) runs no binding: neither Ctrl K nor Settings Ctrl comma', async () => {
    const shell = await mount();
    const invoked: string[] = [];
    const stop = onCommandInvoked(id => invoked.push(id));
    try {
      const field = await composerField(shell); field.focus();
      const settings = (): SettingsWindow => shell.shadowRoot!.querySelector<SettingsWindow>('jf-settings-window')!;
      for (const key of ['k', ',']) {
        const event = chord(field, { key, keyCode: 229, isComposing: false }); await settle(shell);
        expect(event.defaultPrevented, key).toBe(false);
      }
      expect(invoked).toEqual([]);
      expect(openPalettes(shell)).toBe(0);
      expect(settings().open).toBe(false);
      // The same chords without the IME code do run, so the guard is what held them.
      chord(field, { key: ',' }); await settle(shell);
      expect(settings().open).toBe(true);
      settings().dismiss(); await settle(shell);
      field.focus();
      chord(field); await settle(shell);
      expect(palette(shell).open).toBe(true);
      expect(invoked).toEqual(['shell.go-to-settings', 'shell.toggle-palette']);
    } finally { stop(); }
  });
});
