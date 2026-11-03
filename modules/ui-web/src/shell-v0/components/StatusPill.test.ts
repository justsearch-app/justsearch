// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetTasksForTest } from '../substrates/tasks/index.js';
import { registerStatusBarItem, unregisterStatusBarItem, __resetForTest as resetRegistry } from '../commands/StatusBarRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { ModalityController, __resetModalityForTest } from '../primitives/modality.js';
import { deriveFrameStatus } from '../state/frameStatus.js';
let pill: StatusPill;
const button = () => pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
async function flush() { await Promise.resolve(); await pill.updateComplete; await panel().updateComplete; await Promise.resolve(); }
beforeEach(async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetTasksForTest(); resetRegistry(); __resetModalityForTest();
  pill = new StatusPill(); document.body.appendChild(pill); await flush();
});
afterEach(() => { pill.remove(); __resetAiStateForTest(); __resetTasksForTest(); resetRegistry(); __resetModalityForTest(); vi.unstubAllGlobals(); });
describe('status pill and panel', () => {
  it('ST3 opens, closes on pill, Escape and outside pointer, and returns focus', async () => {
    button().click(); await flush();
    expect(pill.open).toBe(true);
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(panel().shadowRoot!.activeElement?.getAttribute('role')).toBe('dialog');
    button().click(); await flush(); expect(pill.open).toBe(false);
    button().click(); await flush();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flush(); expect(pill.open).toBe(false); expect(pill.shadowRoot!.activeElement).toBe(button());
    button().click(); await flush();
    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
    await flush(); expect(pill.open).toBe(false); expect(pill.shadowRoot!.activeElement).toBe(button());
    button().click(); await flush();
    panel().shadowRoot!.querySelector<HTMLButtonElement>('button[aria-label="Close status panel"]')!.click();
    await flush(); expect(pill.open).toBe(false); expect(pill.shadowRoot!.activeElement).toBe(button());
  });
  it('C10 teardown and remount keep exactly one activity subscription', async () => {
    const stops: ReturnType<typeof vi.fn>[] = [];
    const multiplex = { subscribe: vi.fn(() => { const stop = vi.fn(); stops.push(stop); return stop; }) };
    pill.multiplex = multiplex as unknown as StatusPill['multiplex']; await flush();
    expect(multiplex.subscribe).toHaveBeenCalledTimes(1);
    pill.remove(); expect(stops[0]).toHaveBeenCalledTimes(1);
    document.body.appendChild(pill); await flush();
    expect(multiplex.subscribe).toHaveBeenCalledTimes(2);
    expect(stops.filter(stop => stop.mock.calls.length === 0)).toHaveLength(1);
  });
  it('ST3 C8 shortcut yields to typing, IME, repeat and modal ownership', async () => {
    const press = (extra = {}) => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'S', ctrlKey: true, shiftKey: true, bubbles: true, ...extra }));
    press(); await flush(); expect(pill.open).toBe(true); pill.close(); await flush();
    const input = document.createElement('input'); document.body.appendChild(input); input.focus();
    press(); await flush(); expect(pill.open).toBe(false); input.remove();
    press({ isComposing: true }); await flush(); expect(pill.open).toBe(false);
    press({ repeat: true }); await flush(); expect(pill.open).toBe(false);
    const modal = new ModalityController(pill); modal.enter(); press(); await flush(); expect(pill.open).toBe(false); modal.exit();
  });
  it('ST15 updates preserve the scrolling element, expanded row and keyed plugin node', async () => {
    pill.open = true; await flush();
    const earlier = { id: 'history1', section: 'earlier' as const, title: 'Retained advisory', unread: true };
    panel().model = deriveFrameStatus({ ai: null, now: Date.now(), contactLostAt: null, conditions: [earlier] });
    await panel().updateComplete;
    const scroll = panel().shadowRoot!.querySelector<HTMLElement>('.panel')!; scroll.scrollTop = 91;
    panel().shadowRoot!.querySelector<HTMLButtonElement>('article button')!.click(); await panel().updateComplete;
    const expanded = panel().shadowRoot!.querySelector('[data-expanded]');
    panel().model = deriveFrameStatus({ ai: null, now: Date.now(), contactLostAt: null,
      conditions: [{ ...earlier, unread: false }, { id: 'new1', section: 'needs', title: 'New arrival' }] });
    await panel().updateComplete;
    expect(panel().shadowRoot!.querySelector('.panel')).toBe(scroll); expect(scroll.scrollTop).toBe(91);
    expect(panel().shadowRoot!.querySelector('[data-expanded]')).toBe(expanded);
  });
  it('ST9 renders original plugin content and provenance, removes on uninstall, and layout only hides plugins', async () => {
    const content = document.createElement('button'); content.textContent = 'Focus timer';
    const render = vi.fn(() => content);
    registerStatusBarItem({ id: 'plugin.timer', source: 'plugin', provenance: CORE_PROVENANCE, priority: 1, position: 'right', render });
    await flush();
    expect(panel().shadowRoot!.querySelector('[data-plugin-item]')?.contains(content)).toBe(true);
    expect(panel().shadowRoot!.querySelector('jf-provenance-chip')).not.toBeNull();
    pill.status.refresh(); await flush(); expect(render).toHaveBeenCalledTimes(1);
    pill.pluginsVisible = false; await flush(); expect(panel().shadowRoot!.querySelector('[data-plugin-item]')).toBeNull();
    expect(button()).not.toBeNull(); expect(panel().model).not.toBeNull();
    pill.pluginsVisible = true; await flush(); unregisterStatusBarItem('plugin.timer'); await flush();
    expect(panel().shadowRoot!.querySelector('[data-plugin-item]')).toBeNull();
  });
  it('ST15 window switch closes the panel; teardown removes keyboard and blur listeners', async () => {
    button().click(); await flush(); window.dispatchEvent(new Event('blur')); await flush(); expect(pill.open).toBe(false);
    pill.remove(); document.dispatchEvent(new KeyboardEvent('keydown', { key: 'S', ctrlKey: true, shiftKey: true })); expect(pill.open).toBe(false);
    document.body.appendChild(pill); await flush();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'S', ctrlKey: true, shiftKey: true })); await flush(); expect(pill.open).toBe(true);
  });
});
