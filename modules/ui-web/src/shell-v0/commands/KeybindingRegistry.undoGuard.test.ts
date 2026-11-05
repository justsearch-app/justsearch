// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * C8 — keyboard ownership of Ctrl+Z: the shell's Undo (and Redo) never fires inside a text field
 * where the key edits text, during IME composition, on key repeat, or while another modal owns the
 * keyboard. There the key keeps its native meaning (not prevented); elsewhere it runs the shell Undo.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  registerKeybinding,
  attachKeybindingDispatcher,
  detachKeybindingDispatcher,
  __resetForTest,
} from './KeybindingRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { ModalityController, __resetModalityForTest } from '../primitives/modality.js';

describe('C8 Ctrl+Z keyboard ownership', () => {
  let invoked: string[] = [];
  let teardown: (() => void) | null = null;
  const press = (target: EventTarget, init: KeyboardEventInit = {}) => {
    const event = new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true, cancelable: true, ...init });
    target.dispatchEvent(event);
    return event;
  };

  beforeEach(() => {
    invoked = [];
    registerKeybinding({ key: 'mod+z', commandId: 'shell.undo', source: 'default', provenance: CORE_PROVENANCE });
    registerKeybinding({ key: 'mod+shift+z', commandId: 'shell.redo', source: 'default', provenance: CORE_PROVENANCE });
    teardown = attachKeybindingDispatcher((id) => invoked.push(id));
  });
  afterEach(() => {
    teardown?.(); detachKeybindingDispatcher(); __resetForTest(); __resetModalityForTest();
    document.body.replaceChildren();
  });

  it('outside a text field Ctrl+Z runs the shell Undo and Ctrl+Shift+Z its Redo', () => {
    expect(press(document.body).defaultPrevented).toBe(true);
    press(document.body, { key: 'Z', shiftKey: true });
    expect(invoked).toEqual(['shell.undo', 'shell.redo']);
  });

  it.each([
    ['input', () => document.createElement('input')],
    ['textarea', () => document.createElement('textarea')],
    ['contentEditable', () => {
      const el = document.createElement('div');
      Object.defineProperty(el, 'isContentEditable', { configurable: true, value: true });
      return el;
    }],
  ])('inside a %s it edits the text: nothing runs and the key is not taken', (_name, make) => {
    const field = make(); document.body.append(field);
    const event = press(field);
    press(field, { key: 'Z', shiftKey: true });
    expect(invoked).toEqual([]);
    expect(event.defaultPrevented).toBe(false);
  });

  it('a field inside a shadow root is still a text field', () => {
    const host = document.createElement('div'); document.body.append(host);
    const field = document.createElement('input'); host.attachShadow({ mode: 'open' }).append(field);
    expect(press(field).defaultPrevented).toBe(false);
    expect(invoked).toEqual([]);
  });

  it('during IME composition and on key repeat nothing runs', () => {
    press(document.body, { isComposing: true });
    press(document.body, { repeat: true });
    expect(invoked).toEqual([]);
  });

  it('under another modal nothing runs, and it works again once the modal is gone', () => {
    const modality = new ModalityController({
      addController: () => {}, removeController: () => {}, requestUpdate: () => {}, updateComplete: Promise.resolve(true),
    });
    modality.enter();
    try {
      expect(press(document.body).defaultPrevented).toBe(false);
      expect(invoked).toEqual([]);
    } finally {
      modality.exit({ skipFocusRestore: true });
    }
    press(document.body);
    expect(invoked).toEqual(['shell.undo']);
  });

  it('a user rebinding of Undo keeps the same guard', () => {
    registerKeybinding({ key: 'mod+u', commandId: 'shell.undo', source: 'user', provenance: CORE_PROVENANCE });
    const field = document.createElement('input'); document.body.append(field);
    press(field, { key: 'u' });
    expect(invoked).toEqual([]);
    press(document.body, { key: 'u' });
    expect(invoked).toEqual(['shell.undo']);
  });
});
