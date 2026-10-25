// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest';
import { Sv3Sidebar, SV3_SESSION_NEW } from './Sv3Sidebar.js';
import type { Sv3SessionRow } from './Sv3SessionRow.js';
import type { Sv3SessionGroup } from './sv3-sessions.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';

const group = (ids: string[], id = 'recent'): Sv3SessionGroup => ({ id, label: id, rows: ids.map(id => ({
  id, label: id, meta: '', status: 'resting', active: false, pinned: false, unread: false, live: false, storeBacked: true,
})) });
async function settle(bar: Sv3Sidebar): Promise<void> {
  await bar.updateComplete;
  await Promise.all([...bar.shadowRoot!.querySelectorAll<Sv3SessionRow>('jf-sv3-session-row')].map(r => r.updateComplete));
  await Promise.resolve();
}
async function mount(groups: Sv3SessionGroup[]): Promise<Sv3Sidebar> {
  const bar = document.createElement('jf-sv3-sidebar'); bar.groups = groups; document.body.append(bar); await settle(bar); return bar;
}
function action(bar: Sv3Sidebar, label: string, name = 'button'): HTMLButtonElement {
  const row = [...bar.shadowRoot!.querySelectorAll<Sv3SessionRow>('jf-sv3-session-row')].find(r => r.label === label);
  const button = row?.shadowRoot?.querySelector<HTMLButtonElement>(`[data-testid="sv3-session-row-${name}"]`);
  if (!button) throw Error('Missing row action: '+label+' '+name);
  return button;
}
afterEach(() => document.body.replaceChildren());

for (const count of [1, 30]) {
  for (const focus of ['composer', 'removed', 'survivor'] as const) {
    it(`F1 ${count} removals preserve ${focus} focus ownership`, async () => {
      const ids = ['root', ...Array.from({ length: count - 1 }, (_, i) => 'child-'+i)];
      const bar = await mount([group([...ids, 'keep'])]);
      const field = document.createElement('textarea');field.value = 'A typed draft must survive every conversation being deleted.';document.body.append(field);
      let newSearches = 0;bar.addEventListener(SV3_SESSION_NEW, () => { newSearches++;field.value = ''; });
      action(bar, 'root', 'remove').focus();action(bar, 'root', 'remove').click();
      const target = focus === 'composer' ? field : action(bar, focus === 'survivor' ? 'keep' : ids.at(-1)!, focus === 'removed' ? 'remove' : 'button');
      target.focus();
      const remaining = [...ids, 'keep'];
      for (const id of [...ids].reverse()) {
        remaining.splice(remaining.indexOf(id), 1);bar.groups = [group([...remaining])];await settle(bar);
        const active = deepActiveElement();
        if (focus === 'removed') expect(active?.getAttribute('data-testid')).toBe('sv3-session-row-button');
        else expect(active).toBe(target);
      }
      const active = deepActiveElement() as HTMLElement;
      for (const key of [' ', 'Enter']) {
        active.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, composed: true }));
        if (active instanceof HTMLButtonElement) active.click();
      }
      expect(newSearches).toBe(0);expect(field.value).toBe('A typed draft must survive every conversation being deleted.');
    });
  }
  it(`F1 ${count} removals leave the keyboard on a neutral target when no row survives`, async () => {
    const ids = ['root', ...Array.from({ length: count - 1 }, (_, i) => 'child-'+i)];
    const bar = await mount([group(ids)]);let newSearches = 0;bar.addEventListener(SV3_SESSION_NEW, () => newSearches++);
    action(bar, 'root', 'remove').focus();action(bar, 'root', 'remove').click();
    for (const id of [...ids].reverse()) { bar.groups = ids.indexOf(id) === 0 ? [] : [group(ids.slice(0, ids.indexOf(id)))];await settle(bar); }
    const active = deepActiveElement() as HTMLElement;
    expect(active?.getAttribute('data-testid')).toBe('sv3-sidebar-announcer');
    expect(active?.getAttribute('tabindex')).toBe('-1');
    for (const key of [' ', 'Enter']) { active.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, composed: true }));active.click(); }
    expect(newSearches).toBe(0);
  });
}
it('F1 any row removal preserves focus in a surviving shelf', async () => {
  const bar = await mount([group(['root'], 'active'), group(['keep'], 'recent')]);
  const target = action(bar, 'keep', 'remove');target.focus();
  bar.groups = [group(['keep'], 'recent')];await settle(bar);
  expect(deepActiveElement()).toBe(target);expect(target.isConnected).toBe(true);
});
it('F1 any row removal moves focus only when the removed row held it', async () => {
  const bar = await mount([group(['root', 'keep'])]);action(bar, 'root', 'remove').focus();
  bar.groups = [group(['keep'])];await settle(bar);
  expect(deepActiveElement()).toBe(action(bar, 'keep'));
});
