// @vitest-environment happy-dom

/**
 * The Shell's global chords and the ONE typing guard (tempdoc 864 Layer 2(a)).
 *
 * `handleGlobalKey` is a raw `document`-capture listener guarding the app's most powerful chords
 * (`Alt+←/→` history, `Ctrl+D`, `Ctrl+Shift+A`). Until 864 it carried a PRIVATE copy of the shadow
 * descent that omitted `SELECT` — precisely the omission tempdoc 857 PR-A was written to close on the
 * other copies — so a chord pressed while a `<select>` had focus fired over the reader's native
 * type-ahead. It now asks `isTypingTarget(deepActiveElement())`, the app's one definition.
 *
 * The observable is `defaultPrevented`: every branch of the handler calls `preventDefault()` before
 * it acts, so a claimed chord and a guarded one are distinguishable without reaching into the Shell.
 * The `<select>` case is asserted BESIDE a `<button>` case that must still fire — a guard that simply
 * swallowed everything would pass the first assertion and fail the second.
 *
 * Both subjects live inside a shadow root, because that is the only way this handler ever sees them:
 * a capture-phase `event.target` is retargeted to the shadow host, which is why the guard resolves
 * focus by descent rather than from the event.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import { __resetSurfaceSchemasForTest } from '../router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../router/storeRegistry.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { ModalityController, __resetModalityForTest } from '../primitives/modality.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';

interface ShellElement extends HTMLElement {
  apiBase: string;
  updateComplete: Promise<void>;
}

function makeRailSurface(id: string, mountTag: string): Surface {
  return {
    id,
    presentation: { labelKey: `${id}.label`, descriptionKey: `${id}.description` },
    audience: 'USER',
    placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    mountTag,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
  };
}

function seedSurfaces(): void {
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries: [makeRailSurface('core.search-surface', 'jf-search-surface')],
  };
  seedSurfaceCatalog(catalog);
}

/** A focusable control of the given tag, inside a shadow root the chord's target retargets to. */
function focusInShadow(tag: 'select' | 'button'): HTMLElement {
  const host = document.createElement('div');
  host.className = 'probe-host';
  document.body.appendChild(host);
  const control = document.createElement(tag);
  if (tag === 'select') {
    const option = document.createElement('option');
    option.value = 'a';
    option.textContent = 'a';
    control.appendChild(option);
  }
  host.attachShadow({ mode: 'open' }).appendChild(control);
  control.focus();
  return control;
}

/** Press a chord the way the shipped app delivers it: on `document`, in the capture phase. */
function press(init: KeyboardEventInit): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  document.dispatchEvent(event);
  return event;
}

describe('Shell global chords — the shared typing guard (tempdoc 864 Layer 2(a))', () => {
  let shell: ShellElement;

  beforeEach(async () => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    seedSurfaces();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ entries: [] }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    );
    shell = document.createElement('jf-shell') as ShellElement;
    shell.apiBase = '';
    document.body.appendChild(shell);
    await shell.updateComplete;
    await new Promise((r) => setTimeout(r, 30));
    await shell.updateComplete;
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    document.querySelectorAll('.probe-host').forEach((el) => el.remove());
    __resetAiStateForTest();
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    __resetModalityForTest();
  });

  it('leaves a chord alone while a <select> has focus — the omission 857 closed elsewhere', () => {
    focusInShadow('select');
    expect(press({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(false);
    expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(false);
  });

  it('still claims the same chord when focus is on a control the reader does NOT type into', () => {
    focusInShadow('button');
    expect(press({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(true);
    expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(true);
  });

  /**
   * Tempdoc 864 Layer 2(b) — this raw `document` listener is one of the three global-key sites that
   * take the SHARED `shouldIgnoreKeyEvent`. A held `Ctrl+D` used to toggle the bookmark on and off
   * for as long as the key was down; an IME-composing press was a shortcut. Both are asserted beside
   * the same chord still being claimed, so a guard that swallowed everything would fail.
   */
  it('864: ignores an IME-composing chord and an auto-repeat chord', () => {
    focusInShadow('button');
    expect(press({ key: 'd', ctrlKey: true, isComposing: true }).defaultPrevented).toBe(false);
    expect(press({ key: 'd', ctrlKey: true, repeat: true }).defaultPrevented).toBe(false);
    expect(press({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(true);
  });

  /**
   * Tempdoc 864 Layer 2(d) — a modal owns the keyboard. `showModal()` makes the background inert to
   * the POINTER, but this is a `document` listener and a keystroke made INSIDE the dialog still
   * arrives here, so `Ctrl+Shift+A` would swap the surface out from under an open confirmation.
   */
  it('864: stands down while a modal owns the keyboard', () => {
    focusInShadow('button');
    const modality = new ModalityController({
      addController: () => {},
      removeController: () => {},
      requestUpdate: () => {},
      updateComplete: Promise.resolve(true),
    });
    modality.enter();
    try {
      expect(press({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(false);
      expect(press({ key: 'A', ctrlKey: true, shiftKey: true }).defaultPrevented).toBe(false);
    } finally {
      modality.exit({ skipFocusRestore: true });
    }
    expect(press({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(true);
  });

  it('leaves a chord alone while a shadow-nested <input> has focus', () => {
    const host = document.createElement('div');
    host.className = 'probe-host';
    document.body.appendChild(host);
    const input = document.createElement('input');
    host.attachShadow({ mode: 'open' }).appendChild(input);
    input.focus();
    expect(press({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(false);
  });
});
