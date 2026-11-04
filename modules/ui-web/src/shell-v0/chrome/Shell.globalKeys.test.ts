// @vitest-environment happy-dom

/**
 * The Shell's global chords and their guards (tempdoc 864 Layer 2).
 *
 * `handleGlobalKey` is a raw `document`-capture listener guarding the app's most powerful chords
 * (`Alt+←/→` history, `Ctrl+Shift+A`; shell slice 2 adds `Ctrl+1/2/3`, `F6` and `F1` and retired
 * `Ctrl+D` with the bookmarks button). Until 864 it carried a PRIVATE copy of the shadow
 * descent that omitted `SELECT`, so a chord pressed while a `<select>` had focus fired over the
 * reader's native type-ahead; it then asked `isTypingTarget(deepActiveElement())`. Shell slice 2
 * left no chord here that edits text: every one of them now works from a text field (C8, FR3), and
 * only IME composition, key repeat and a modal stand them down.
 *
 * The observable is `defaultPrevented`: every branch of the handler calls `preventDefault()` before
 * it acts, so a claimed chord and a guarded one are distinguishable without reaching into the Shell.
 * Each guard case is asserted BESIDE the same chord still being claimed — a guard that simply
 * swallowed everything would pass the first assertion and fail the second.
 *
 * The focused controls live inside a shadow root, because that is the only way this handler ever
 * sees them: a capture-phase `event.target` is retargeted to the shadow host.
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
function focusInShadow(tag: 'select' | 'button' | 'textarea'): HTMLElement {
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

describe('Shell global chords and their guards (tempdoc 864 Layer 2)', () => {
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

  /**
   * FR3 — Alt+Left/Right are Back and Forward wherever focus is: they insert or remove no text, and
   * Back often lands in a text field (the Search composer takes focus on arrival), where a typing
   * guard swallowed the Forward that followed. The chord is the app's alone, so the focused control
   * (a <select>'s option keys, a tab row's or option group's arrows) never also acts on it.
   */
  it('FR3: Alt+Left/Right are claimed in a <select>, a shadow <input> and a <textarea>, and reach nothing under them', () => {
    for (const tag of ['select', 'textarea'] as const) {
      const control = focusInShadow(tag);
      const reached = vi.fn();
      control.addEventListener('keydown', reached);
      expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented, `${tag} Alt+Left`).toBe(true);
      expect(press({ key: 'ArrowRight', altKey: true }).defaultPrevented, `${tag} Alt+Right`).toBe(true);
      expect(reached, tag).not.toHaveBeenCalled();
    }
    const host = document.createElement('div');
    host.className = 'probe-host';
    document.body.appendChild(host);
    const input = document.createElement('input');
    host.attachShadow({ mode: 'open' }).appendChild(input);
    input.focus();
    expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(true);
    // Shift+Alt and Ctrl+Alt arrows stay the platform's (text selection, layout switching).
    expect(press({ key: 'ArrowLeft', altKey: true, shiftKey: true }).defaultPrevented).toBe(false);
    expect(press({ key: 'ArrowRight', altKey: true, ctrlKey: true }).defaultPrevented).toBe(false);
  });

  /**
   * C8 on macOS — Option+Left/Right move the caret by word in a text field, so there the chord is
   * left to the text; outside a text field it is still Back and Forward.
   */
  it('C8 macOS: Option+Left/Right stay word moves in a text field and navigate elsewhere', () => {
    vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel');
    const input = focusInShadow('textarea');
    expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(false);
    expect(press({ key: 'ArrowRight', altKey: true }).defaultPrevented).toBe(false);
    input.blur();
    const button = document.createElement('button');
    button.className = 'probe-host';
    document.body.appendChild(button);
    button.focus();
    expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(true);
  });

  it('still claims the same chord when focus is on a control the reader does NOT type into', () => {
    focusInShadow('button');
    expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(true);
    expect(press({ key: 'ArrowRight', altKey: true }).defaultPrevented).toBe(true);
  });

  /**
   * C8 — Ctrl+1/2/3 and F6 neither insert nor remove text, so they work from a text field (the
   * Search composer keeps focus on arrival); AltGr (reported as Ctrl+Alt) types characters on many
   * layouts and is never taken. Ctrl+D (retired with the bookmarks button, FR4) is no chord now.
   */
  it('C8: area keys and F6 work from a text field; an AltGr combination is left to typing', () => {
    focusInShadow('textarea');
    expect(press({ key: '2', code: 'Digit2', ctrlKey: true }).defaultPrevented).toBe(true);
    expect(press({ key: 'F6' }).defaultPrevented).toBe(true);
    expect(press({ key: '²', code: 'Digit2', ctrlKey: true, altKey: true }).defaultPrevented).toBe(false);
    expect(press({ key: '@', code: 'Digit2', ctrlKey: true, shiftKey: true }).defaultPrevented).toBe(false);
    expect(press({ key: 'd', ctrlKey: true }).defaultPrevented).toBe(false);
  });

  /**
   * Tempdoc 864 Layer 2(b) — this raw `document` listener is one of the three global-key sites that
   * take the SHARED `shouldIgnoreKeyEvent`. A held `Ctrl+D` used to toggle the bookmark on and off
   * for as long as the key was down; an IME-composing press was a shortcut. Both are asserted beside
   * the same chord still being claimed, so a guard that swallowed everything would fail.
   */
  it('864: ignores an IME-composing chord and an auto-repeat chord', () => {
    focusInShadow('button');
    for (const chord of [{ key: '2', code: 'Digit2', ctrlKey: true }, { key: 'F6' }, { key: 'F1' }, { key: 'ArrowLeft', altKey: true }]) {
      expect(press({ ...chord, isComposing: true }).defaultPrevented, `${chord.key} composing`).toBe(false);
      expect(press({ ...chord, repeat: true }).defaultPrevented, `${chord.key} repeat`).toBe(false);
      expect(press(chord).defaultPrevented, `${chord.key} plain`).toBe(true);
    }
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
      expect(press({ key: '2', code: 'Digit2', ctrlKey: true }).defaultPrevented).toBe(false);
      expect(press({ key: 'F6' }).defaultPrevented).toBe(false);
      expect(press({ key: 'F1' }).defaultPrevented).toBe(false);
      expect(press({ key: 'A', ctrlKey: true, shiftKey: true }).defaultPrevented).toBe(false);
      expect(press({ key: 'ArrowLeft', altKey: true }).defaultPrevented).toBe(false);
    } finally {
      modality.exit({ skipFocusRestore: true });
    }
    expect(press({ key: '2', code: 'Digit2', ctrlKey: true }).defaultPrevented).toBe(true);
  });

});
