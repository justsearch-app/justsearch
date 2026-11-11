// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * The shared "is the reader typing?" guard (tempdoc 857 PR-A).
 *
 * Two units, deliberately split: {@link deepActiveElement} answers WHERE FOCUS IS (descending
 * nested shadow roots), {@link isTypingTarget} answers WHETHER THAT IS AN EDITABLE. Splitting them
 * is what lets `commands/KeybindingRegistry.ts` reuse the predicate later against its own subject
 * (`composedPath()[0]`, the event's ORIGIN — a different question) without inheriting the descent.
 *
 * Both are duck-typed, and this suite pins that: the cases below use plain object literals, exactly
 * as `views/UnifiedChatView.test.ts:2482-2499` does. Narrowing either to `instanceof Element`,
 * `nodeType` or `closest()` would break that test, which is the only coverage the descent has.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { deepActiveElement, isImeComposition, isTypingTarget, shouldIgnoreKeyEvent } from './keyboardHandler.js';

afterEach(() => {
  document.body.innerHTML = '';
  delete (document as unknown as Record<string, unknown>).activeElement;
});

const fakeDoc = (active: unknown): Document => ({ activeElement: active }) as unknown as Document;

describe('isTypingTarget — the UNION of the two guards that existed before', () => {
  it('is true for every control the reader types into', () => {
    for (const tagName of ['INPUT', 'TEXTAREA', 'SELECT']) {
      expect(isTypingTarget({ tagName, isContentEditable: false } as unknown as Element)).toBe(true);
    }
    expect(
      isTypingTarget({ tagName: 'DIV', isContentEditable: true } as unknown as Element),
    ).toBe(true);
  });

  it('covers SELECT — the omission that was a live bug, not a symmetry gap', () => {
    // `views/UnifiedChatView.ts:3987` renders a `<select class="workflow-picker">`. The retiree's
    // inline guard checked INPUT/TEXTAREA/contentEditable only, so with the picker focused a `j`
    // press stole the element's native type-ahead instead of typing into it.
    expect(isTypingTarget({ tagName: 'SELECT', isContentEditable: false } as unknown as Element)).toBe(
      true,
    );
  });

  it('is false for a non-editable focused element and for nothing focused at all', () => {
    expect(isTypingTarget({ tagName: 'BUTTON', isContentEditable: false } as unknown as Element)).toBe(
      false,
    );
    // An advisory row: focusable and operable, but not a typing target — which is precisely why the
    // J/K collision with it needs a different guard (`defaultPrevented`), not this one.
    expect(isTypingTarget({ tagName: 'DIV', isContentEditable: false } as unknown as Element)).toBe(
      false,
    );
    expect(isTypingTarget(null)).toBe(false);
  });
});

describe('deepActiveElement — the shadow-root descent', () => {
  it('returns the document’s active element when it hosts no shadow root', () => {
    const plain = { tagName: 'BUTTON', shadowRoot: null };
    expect(deepActiveElement(fakeDoc(plain))).toBe(plain);
  });

  it('descends through NESTED shadow roots to the element that truly has focus', () => {
    const textarea = { tagName: 'TEXTAREA', isContentEditable: false, shadowRoot: null };
    const composer = { tagName: 'JF-SV3-COMPOSER', shadowRoot: { activeElement: textarea } };
    const view = { tagName: 'JF-SV3-MAIN', shadowRoot: { activeElement: composer } };
    expect(deepActiveElement(fakeDoc(view))).toBe(textarea);
    // Composed with the predicate, this is the whole guard — and the case a bare
    // `document.activeElement` check gets wrong, because it stops at the outermost custom element.
    expect(isTypingTarget(deepActiveElement(fakeDoc(view)))).toBe(true);
  });

  it('stops at a shadow host with nothing focused inside it', () => {
    const host = { tagName: 'JF-SV3-MAIN', shadowRoot: { activeElement: null } };
    expect(deepActiveElement(fakeDoc(host))).toBe(host);
    expect(isTypingTarget(deepActiveElement(fakeDoc(host)))).toBe(false);
  });

  it('returns null when nothing is focused', () => {
    expect(deepActiveElement(fakeDoc(null))).toBeNull();
    expect(isTypingTarget(deepActiveElement(fakeDoc(null)))).toBe(false);
  });

  it('defaults to the live document, so callers pass nothing', () => {
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    expect(deepActiveElement()).toBe(input);
    expect(isTypingTarget(deepActiveElement())).toBe(true);
  });
});

/**
 * Tempdoc 864 Layer 2(b) — the third shared unit: event hygiene every global handler owes the
 * reader, independent of where focus is. Three adopters take it verbatim (the registry dispatcher,
 * `Shell.handleGlobalKey`, `Sv3Main.onWindowKeydown`, plus `UnifiedChatView`'s window listener), so
 * a change here changes all of them — which is the point.
 */
describe('shouldIgnoreKeyEvent (864 Layer 2(b))', () => {
  const ev = (init: KeyboardEventInit): KeyboardEvent => new KeyboardEvent('keydown', init);

  it('ignores a keystroke feeding an IME candidate window', () => {
    expect(shouldIgnoreKeyEvent(ev({ key: 'j', isComposing: true }))).toBe(true);
  });

  it('ignores a keydown the IME is processing (keyCode 229) before isComposing is set', () => {
    expect(shouldIgnoreKeyEvent(ev({ key: 'k', ctrlKey: true, keyCode: 229, isComposing: false }))).toBe(true);
    expect(isImeComposition(ev({ key: 'Process', keyCode: 229 }))).toBe(true);
    expect(isImeComposition(ev({ key: 'k', ctrlKey: true, keyCode: 75 }))).toBe(false);
  });

  it('ignores an auto-repeat press — a held key is one intent, not N', () => {
    expect(shouldIgnoreKeyEvent(ev({ key: 'j', repeat: true }))).toBe(true);
  });

  it('leaves an ordinary press alone, modified or not', () => {
    expect(shouldIgnoreKeyEvent(ev({ key: 'j' }))).toBe(false);
    expect(shouldIgnoreKeyEvent(ev({ key: 'd', ctrlKey: true }))).toBe(false);
  });
});
