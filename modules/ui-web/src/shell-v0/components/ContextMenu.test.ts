// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import './ContextMenu.js';
import {
  type ContextMenu,
  type ContextMenuAction,
  openContextMenu,
} from './ContextMenu.js';

function basicActions(): ContextMenuAction[] {
  return [
    { id: 'open', label: 'Open', category: 'file', enabled: true },
    { id: 'reveal', label: 'Reveal', category: 'file', enabled: true },
    { id: 'summarize', label: 'Summarize', category: 'ai', enabled: true },
    { id: 'reindex', label: 'Reindex', category: 'index', enabled: false },
  ];
}

function make(actions: ContextMenuAction[] = basicActions()): ContextMenu {
  const el = document.createElement('jf-context-menu') as ContextMenu;
  el.actions = actions;
  el.anchor = { x: 100, y: 100 };
  document.body.appendChild(el);
  return el;
}

describe('ContextMenu (slice 458)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders nothing when anchor is null', async () => {
    const el = document.createElement('jf-context-menu') as ContextMenu;
    el.actions = basicActions();
    document.body.appendChild(el);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.menu')).toBeNull();
  });

  it('groups actions with separators between categories', async () => {
    const el = make();
    await el.updateComplete;
    const items = el.shadowRoot?.querySelectorAll('button.item, .sep');
    // 4 actions + 2 separators (file/ai + ai/index)
    expect(items?.length).toBe(6);
  });

  it('disabled action button has disabled attr', async () => {
    const el = make();
    await el.updateComplete;
    const btns = Array.from(el.shadowRoot?.querySelectorAll<HTMLButtonElement>('button.item') ?? []);
    const reindex = btns.find((b) => b.textContent?.includes('Reindex'));
    expect(reindex?.disabled).toBe(true);
  });

  it('emits context-action on click', async () => {
    const el = make();
    await el.updateComplete;
    let actionId: string | null = null;
    el.addEventListener('context-action', (e) => {
      actionId = (e as CustomEvent<{ id: string }>).detail.id;
    });
    el.shadowRoot?.querySelector<HTMLButtonElement>('button.item')?.click();
    await new Promise((r) => setTimeout(r, 10));
    expect(actionId).toBe('open');
  });

  it('emits cancel on Escape key', async () => {
    const el = make();
    await el.updateComplete;
    let cancelled = false;
    el.addEventListener('cancel', () => (cancelled = true));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(cancelled).toBe(true);
  });

  it('ArrowDown moves focus across enabled actions only', async () => {
    const el = make();
    await el.updateComplete;
    const buttons = [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button.item')];
    expect(el.shadowRoot!.activeElement).toBe(buttons[0]);
    const menu = el.shadowRoot!.querySelector('[role="menu"]')!;
    for (const expected of [buttons[1], buttons[2], buttons[0]]) {
      menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
      expect(el.shadowRoot!.activeElement).toBe(expected);
    }
  });

  it('openContextMenu resolves with the invoked action id', async () => {
    const promise = openContextMenu({
      actions: basicActions(),
      anchor: { x: 100, y: 100 },
    });
    await new Promise((r) => setTimeout(r, 10));
    const menu = document.querySelector('jf-context-menu') as ContextMenu;
    menu.shadowRoot?.querySelector<HTMLButtonElement>('button.item')?.click();
    expect(await promise).toBe('open');
  });

  it('openContextMenu resolves null on Escape', async () => {
    const promise = openContextMenu({
      actions: basicActions(),
      anchor: { x: 100, y: 100 },
    });
    await new Promise((r) => setTimeout(r, 10));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(await promise).toBeNull();
  });
});
