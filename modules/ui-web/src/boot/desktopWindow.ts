// SPDX-License-Identifier: Apache-2.0
/**
 * Where focus goes after the desktop window layer's controls (F-desktop B2 FW1). index.html's window
 * layer owns the controls and lets go of focus when one sends the window away; this gives it back by
 * the one focus-return rule. Installed by main.jsx at window capture before any application code, so it
 * runs ahead of the start-up hand-off's input guard, on every page the application module reaches.
 */
import { isTauriRuntime } from '../utils/tauriRuntime.js';
import { composedContains, focusedElement, returnFocus } from '../utils/focusReturn.js';

/**
 * A window control that sends the window away (Minimize, Close to the tray) returns focus as it is
 * used, so the window comes back with focus where it was before the control, or on the main content.
 * Maximize keeps its focus: the window stays, and the person may toggle it again.
 */
export function installWindowControlFocus(): void {
  if (!isTauriRuntime()) return;
  const layer = (): HTMLElement | null => document.getElementById('window-controls');
  let before: HTMLElement | null = null;
  document.addEventListener('focusin', () => {
    const now = focusedElement();
    const controls = layer();
    if (now && !(controls && composedContains(controls, now))) before = now;
  }, true);
  window.addEventListener('click', (event) => {
    const controls = layer();
    const target = event.composedPath()[0];
    if (!controls || !(target instanceof Element) || !composedContains(controls, target)) return;
    const command = target.closest('[data-window-command]')?.getAttribute('data-window-command');
    if (command === 'minimize' || command === 'close') returnFocus(before);
  }, true);
}
