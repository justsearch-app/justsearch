// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { TopBar } from './TopBar.js';

afterEach(() => document.body.replaceChildren());
it('Menu names its trigger and opens Settings first, then System, Help and About, with focus on Settings', async () => {
  const bar = new TopBar();
  bar.system = { id: 'core.system-surface', label: 'System', icon: 'server', detail: 'Health, Activity, Logs' };
  bar.help = { id: 'core.help-surface', label: 'Help & shortcuts', icon: 'help-circle', shortcut: 'F1' };
  bar.about = { id: 'about', label: 'About JustSearch', icon: 'info' };
  document.body.append(bar); await bar.updateComplete;
  const trigger = bar.shadowRoot!.querySelector<HTMLButtonElement>('.more-trigger')!;
  expect(trigger.getAttribute('aria-label')).toBe('Menu');
  expect(trigger.title).toBe('Menu');
  expect(bar.shadowRoot!.querySelector('.settings-trigger')).toBeNull();
  trigger.click(); await bar.updateComplete;
  const rows = [...bar.shadowRoot!.querySelectorAll('[role="menuitem"]')];
  expect(rows.map(row => row.querySelector('.item-label')!.textContent)).toEqual(['Settings', 'System', 'Help & shortcuts', 'About JustSearch']);
  expect(bar.shadowRoot!.activeElement).toBe(rows[0]);
  expect(rows[0]!.getAttribute('aria-keyshortcuts')).toBe('Control+,');
  expect(rows[0]!.querySelector('kbd')!.textContent).toBe('Ctrl+,');
});
it('uses a 44 px frame and removes the Search band, its glyph, and its layout offset', () => {
  const tokens = readFileSync('src/styles/tokens.css', 'utf8');
  expect(tokens).toContain('--frame-topbar-height: 2.75rem;');
  const view = readFileSync('src/shell-v0/views/search-v3/SearchV3View.ts', 'utf8');
  expect(view).not.toContain('<jf-sv3-topbar');
  expect(view).not.toContain('sv3-topbar-palette');
  const composer = readFileSync('src/shell-v0/views/search-v3/Sv3Composer.ts', 'utf8');
  expect(composer).not.toContain('var(--workspace-topbar-height)');
});
