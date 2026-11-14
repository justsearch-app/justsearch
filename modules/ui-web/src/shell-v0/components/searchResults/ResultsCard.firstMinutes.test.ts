// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ResultsCard, type CardSnapshot } from './ResultsCard.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import { setFirstUseRoots } from '../../state/firstUse.js';
import { getFilters, setFilters } from '../../state/searchFiltersState.js';
import type { ContextMenu } from '../ContextMenu.js';

const literal = { effectiveMode: 'TEXT', stages: [
  { id: 'sparse-retrieval', status: 'executed' },
  { id: 'dense-retrieval', status: 'skipped' },
  { id: 'splade-retrieval', status: 'disabled' },
] };
const hit = { id: 'one', path: '/notes/report.md', title: 'report.md',
  snippet: '# Budget\n**Lease** [renewal](https://example.test) `terms`\n--- OCR page 2 ---\n> - agreed | today',
  modifiedAt: 1735689600000, trace: [{ id: 'sparse-retrieval', rank: 1 }] };
async function mount(extra: Partial<CardSnapshot> = {}) {
  const el = new ResultsCard();
  el.snapshot = { query: 'lease', results: [hit], matchCount: 1, totalHits: 1,
    facetsTruncated: false, isSearching: false, processingTimeMs: 40, error: null,
    searchTrace: literal, ...extra };
  document.body.append(el); await el.updateComplete; return el;
}
beforeEach(() => { setShowDeveloperTools(false); setFirstUseRoots(null, new Map()); });
afterEach(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); document.body.replaceChildren(); setFilters({}); });
it('S5-1 shows files mode and seconds in that order from executed legs', async () => {
  const el = await mount();
  expect(el.shadowRoot!.querySelector('.meta-info')!.textContent!.replace(/\s+/g, ' ').trim())
    .toBe('1 file · by words · 0.04 s');
  el.snapshot = { ...el.snapshot!, searchTrace: { effectiveMode: 'TEXT', stages: [
    ...literal.stages.filter(s => s.id !== 'splade-retrieval'), { id: 'splade-retrieval', status: 'executed' },
  ] } }; await el.updateComplete;
  expect(el.shadowRoot!.querySelector('.meta-info')!.textContent).toContain('by words and meaning');
  el.snapshot = { ...el.snapshot!, searchTrace: { effectiveMode: 'TEXT', stages: [] } }; await el.updateComplete;
  expect(el.shadowRoot!.querySelector('.meta-info')!.textContent).not.toContain('by words');
});
it('S5-1 retains available versus displayed and capped counts as files', async () => {
  const el = await mount({ matchCount: 100, facetsTruncated: true });
  expect(el.shadowRoot!.querySelector('.meta-info')!.textContent).toContain('Showing 1 of 100+ files');
});
it('S5-2 cleans Markdown before display and preserves raw source and code punctuation', async () => {
  const el = await mount();
  expect(el.shadowRoot!.querySelector('.snippet')!.textContent!.replace(/\s+/g, ' ').trim())
    .toBe('Budget Lease renewal terms agreed today');
  expect(el.snapshot!.results[0]).toBe(hit);
  el.snapshot = { ...el.snapshot!, results: [{ ...hit, path: '/notes/main.ts', snippet: '#hash * value | `code` --- OCR page 1 ---' }] };
  await el.updateComplete;
  expect(el.shadowRoot!.querySelector('.snippet')!.textContent!.trim()).toBe('#hash * value | `code`');
});
it('S5-2 keeps copy and explanation out of the default row and exposes a date', async () => {
  const el = await mount();
  expect(el.shadowRoot!.querySelector('[data-testid="copy-actions"]')).toBeNull();
  expect(el.shadowRoot!.querySelector('[data-testid="hit-why"]')).toBeNull();
  expect(el.shadowRoot!.querySelector('time')?.getAttribute('datetime')).toBe(new Date(hit.modifiedAt).toISOString());
});
it('S5-3 uses known library roots to hide a redundant folder trail', async () => {
  setFirstUseRoots([{ pathHash: 'one' }], new Map([['one', '/notes']]));
  const el = await mount(); expect(el.shadowRoot!.querySelector('.path')).toBeNull();
  setFirstUseRoots([{ pathHash: 'one' }, { pathHash: 'two' }], new Map()); await el.updateComplete;
  expect(el.shadowRoot!.querySelector('.path')!.textContent).toContain('notes');
});
it('S5-5 shows All and Type as text and keeps other facets behind More filters', async () => {
  const el = await mount({ facets: { file_kind: { pdf: 2 }, language: { en: 2 } } });
  expect(el.shadowRoot!.querySelector('[data-testid="type-all"]')?.getAttribute('aria-pressed')).toBe('true');
  expect(el.shadowRoot!.querySelector('[data-facet-field="language"]')).toBeNull();
  expect(el.shadowRoot!.querySelector('[data-testid="more-filters"]')).not.toBeNull();
});
it('S5-5 pending responses retain known Type values without old counts', async () => {
  const el = await mount({ facets: { file_kind: { pdf: 2, code: 1 } } });
  el.snapshot = { ...el.snapshot!, results: [], facets: undefined, isSearching: true, countsPending: true };
  await el.updateComplete;
  expect(el.shadowRoot!.querySelector('[data-facet-field="file_kind"]')?.textContent).toContain('pdf');
  expect(el.shadowRoot!.querySelectorAll('.facet-count')).toHaveLength(0);
});
it('S5-5 date presets use local day boundaries and clear the opposite bound', async () => {
  setFilters({ modifiedFromMs: 1234, modifiedToMs: 9876, pathPrefix: '/notes' });
  const el = await mount();
  const trigger = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="date-filter"]')!;
  expect(trigger, 'date menu is on the filter row').not.toBeNull();
  expect(trigger.textContent).toContain('Custom dates');
  trigger.click();
  const menu = document.querySelector<ContextMenu>('jf-context-menu')!; await menu.updateComplete;
  menu.shadowRoot!.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')[1]!.click();
  for (let n = 0; n < 10; n++) await Promise.resolve(); await el.updateComplete;
  const day = new Date(); day.setHours(0, 0, 0, 0); day.setDate(day.getDate() - 7);
  expect(getFilters()).toEqual({ pathPrefix: '/notes', modifiedFromMs: day.getTime() });
  expect(trigger.textContent).toContain('Past week');
  trigger.click();
  const again = document.querySelector<ContextMenu>('jf-context-menu')!; await again.updateComplete;
  again.shadowRoot!.querySelector<HTMLButtonElement>('[role="menuitem"]')!.click();
  for (let n = 0; n < 10; n++) await Promise.resolve();
  expect(getFilters()).toEqual({ pathPrefix: '/notes' });
});
it.each(['md', 'json'] as const)('S5-6 row %s copy keeps raw text chosen files and focus', async format => {
  const el = await mount(); el.selectedIds = new Set(['other']);
  const copied = vi.fn(async (text: string) => text.length > 0); el.copyText = copied;
  const selected = vi.fn(); el.addEventListener('card-selection', selected); await el.updateComplete;
  const actions = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="row-actions"]')!;
  actions.click(); const menu = document.querySelector<ContextMenu>('jf-context-menu')!; await menu.updateComplete;
  const choice = [...menu.shadowRoot!.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')]
    .find(button => button.textContent?.includes(format === 'md' ? 'Copy as Markdown' : 'Copy as JSON'));
  expect(choice, 'copy belongs to the row menu').not.toBeUndefined(); choice!.click();
  for (let n = 0; n < 20; n++) await Promise.resolve(); await el.updateComplete;
  expect(copied).toHaveBeenCalledTimes(1);
  const text = copied.mock.calls[0]![0] as string;
  if (format === 'json') expect(JSON.parse(text)).toEqual([hit]);
  else { expect(text).toContain('OCR page 2'); expect(text).toContain('https://example.test'); }
  expect(selected).not.toHaveBeenCalled(); expect([...el.selectedIds]).toEqual(['other']);
  expect(el.shadowRoot!.activeElement).toBe(actions);
  expect(el.shadowRoot!.querySelector('[role="status"]')!.textContent).toBe('Copied!');
});
it('S5-16 Markdown is cleaned before truncation and raw export inputs stay untouched', async () => {
  const raw = `[label](https://example.test/${'long/'.repeat(100)}) trailing words`;
  const source = { ...hit, snippet: raw };
  const el = await mount({ results: [source] });
  expect(el.shadowRoot!.querySelector('.snippet')!.textContent!.trim()).toBe('label trailing words');
  expect(el.snapshot!.results[0]).toBe(source);
  expect((el.snapshot!.results[0] as typeof source).snippet).toBe(raw);
});
