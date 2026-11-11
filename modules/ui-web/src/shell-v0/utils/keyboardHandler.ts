// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 495 F2 — Shared keyboard handler for chat submit.
 *
 * Extracted from the duplicated Enter/Shift+Enter pattern in AgentView,
 * AskView, NavigateView, and SummarizeView.
 */

export function handleSubmitKey(
  e: KeyboardEvent,
  onSubmit: () => void,
  opts?: { requireCtrl?: boolean },
): void {
  if (e.key !== 'Enter') return;
  if (e.shiftKey) return;
  if (opts?.requireCtrl && !e.ctrlKey) return;
  e.preventDefault();
  onSubmit();
}

/**
 * Tempdoc 559 Authority V — map Enter/Space to a control's activation. The shared
 * form of the role="button"+tabindex+keydown triad an element needs when it is a
 * focusable affordance but cannot be a native `<button>`/`<jf-control>` (e.g. a
 * clickable list row whose layout forbids a button wrapper). Pair with
 * `role="button" tabindex="0"`.
 */
export function activateOnKey(e: KeyboardEvent, onActivate: () => void): void {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  e.preventDefault();
  onActivate();
}

/**
 * Tempdoc 857 PR-A — the shadow-piercing half of the "is the reader typing?" guard: descend from the
 * document's active element through nested shadow roots to the element that ACTUALLY has focus
 * (`jf-sv3-main` → `jf-sv3-composer` → `textarea`). A bare `document.activeElement` check stops at the
 * outermost custom element and reports a non-editable host, which is why every modifier-less window
 * shortcut in this app needs the descent rather than the one-liner.
 *
 * Duck-typed on purpose — it reads `.shadowRoot?.activeElement` and nothing else, so a test may
 * monkeypatch `document.activeElement` with a plain object literal (the idiom
 * `views/UnifiedChatView.test.ts:2482-2499` already relies on). Narrowing it to `instanceof Element`,
 * `nodeType` or `closest()` would break that, and with it the only test of the descent.
 */
export function deepActiveElement(doc: Document = document): Element | null {
  let active: Element | null = doc.activeElement;
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active;
}

/**
 * Tempdoc 857 PR-A — is this element one the reader types into? The UNION of the two copies that
 * existed before: `KeybindingRegistry.ts`'s modifier-less guard (which covered `SELECT`) and
 * `UnifiedChatView`'s inline J/K guard (which did not). The omission was a live bug — with the
 * workflow `<select>` focused (`views/UnifiedChatView.ts:3987`) a `j` press stole the element's
 * native type-ahead — so the union is a fix, not a widening for symmetry.
 *
 * Split from {@link deepActiveElement} so a caller that resolves its subject differently can reuse
 * the predicate without inheriting the descent: `KeybindingRegistry` asks where the event ORIGINATED
 * (`composedPath()[0]`), which is a different question from where focus IS, and is deliberately not
 * re-pointed at the descent.
 */
/**
 * Tempdoc 864 Layer 2(b) — the two guards EVERY global key handler owes the reader, in ONE place.
 *
 * - `isComposing` or `keyCode` 229 ({@link isImeComposition}) — a keystroke that is feeding an IME
 *   candidate window belongs to the IME, not to a shortcut. The typing guard alone does not cover
 *   it: composition can be driven from a control the predicate correctly reports as non-editable.
 * - `repeat` — an auto-repeating key is one intent held down, not N intents. A global command fired
 *   per repeat flaps state (`Ctrl+D` bookmark on/off/on) or floods navigation.
 *
 * A SHARED helper rather than three copies (b2, the design's chosen half): `/` and the four `mod+…`
 * chords go through `KeybindingRegistry`, but `Shell.handleGlobalKey` is a raw `document`-capture
 * listener and `Sv3Main.onWindowKeydown`/`UnifiedChatView.onConversationKeydown` are raw `window`
 * listeners — adding the checks to the dispatcher alone would have created a FIFTH fork of the guard
 * set, which is the exact defect §2.2 is about. Routing every global chord through one dispatcher
 * (b1) is the better end state and is deferred, not done: see §3.2(b).
 */
export function shouldIgnoreKeyEvent(e: KeyboardEvent): boolean {
  return isImeComposition(e) || e.repeat === true;
}

/**
 * Shell S6 SP1 — a keystroke the IME is processing. Chromium and WebKit report the first keydown of
 * a composition (and every keydown the IME consumes) with `keyCode` 229 while `isComposing` is still
 * false, so `isComposing` alone lets that keydown reach a shortcut: with Search's composer focused,
 * Ctrl K typed into an IME opened the palette. Both signals mean "this key belongs to the IME".
 */
export function isImeComposition(e: KeyboardEvent): boolean {
  return e.isComposing === true || e.keyCode === 229;
}

export function isTypingTarget(el: Element | null): boolean {
  if (el === null || el === undefined) return false;
  const tag = el.tagName;
  return (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    (el as HTMLElement).isContentEditable === true
  );
}
