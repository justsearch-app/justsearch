// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { ResultsCard } from './ResultsCard.js';
import type { ContextMenu } from '../ContextMenu.js';
import { getFilters, setFilters } from '../../state/searchFiltersState.js';
import { projectResultView } from '../../views/searchResultViewModel.js';
afterEach(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); document.body.replaceChildren(); setFilters({}); vi.useRealTimers(); });
async function mount(modifiedAt = new Date(2025, 9, 9).getTime()) {
  const card = new ResultsCard(); card.snapshot = { query: 'harbor', results: [{ id: 'one', title: 'report.docx', path: '/report.docx', modifiedAt }], matchCount: 1, totalHits: 1, facetsTruncated: false, isSearching: false, processingTimeMs: 10, error: null };
  document.body.append(card); await card.updateComplete; return card;
}
it('date is a named text action using the shared keyboard menu and preserves custom bounds until chosen', async () => {
  setFilters({ modifiedFromMs: 1234, modifiedToMs: 9876, pathPrefix: '/docs' });
  const card = await mount();
  expect(card.shadowRoot!.querySelector('select')).toBeNull();
  const trigger = card.shadowRoot!.querySelector<HTMLButtonElement>('button[data-testid="date-filter"]')!;
  expect(trigger).not.toBeNull(); expect(trigger.textContent).toContain('Custom dates');
  expect(trigger.getAttribute('aria-label')).toBe('Modified date: Custom dates');
  expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
  trigger.focus(); trigger.click(); await card.updateComplete;
  const menu = document.querySelector<ContextMenu>('jf-context-menu')!; await menu.updateComplete;
  expect(menu.actions.map(a => a.label)).toEqual(['Any time', 'Past week', 'Past month', 'Past year']);
  expect(getFilters().modifiedToMs).toBe(9876);
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  for (let n = 0; n < 10; n++) await Promise.resolve(); await card.updateComplete;
  const day = new Date(); day.setHours(0, 0, 0, 0); day.setDate(day.getDate() - 7);
  expect(getFilters()).toEqual({ pathPrefix: '/docs', modifiedFromMs: day.getTime() });
  expect(trigger.textContent).toContain('Past week'); expect(card.shadowRoot!.activeElement).toBe(trigger);
  trigger.click(); await card.updateComplete;
  const again = document.querySelector<ContextMenu>('jf-context-menu')!; await again.updateComplete;
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  for (let n = 0; n < 10; n++) await Promise.resolve(); await card.updateComplete;
  expect(getFilters()).toEqual({ pathPrefix: '/docs' }); expect(trigger.textContent).toContain('Any time');
});
it('dates keep the year outside this year and use day then localized short month', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date(2026, 10, 14));
  const card = await mount(); const month = (date: Date) => new Intl.DateTimeFormat(undefined, { month: 'short' }).format(date);
  expect(card.shadowRoot!.querySelector('time')!.textContent).toBe(`9 ${month(new Date(2025, 9, 9))} 2025`);
  card.snapshot = { ...card.snapshot!, results: [{ ...card.snapshot!.results[0]!, modifiedAt: new Date(2026, 10, 12).getTime() }] }; await card.updateComplete;
  expect(card.shadowRoot!.querySelector('time')!.textContent).toBe(`12 ${month(new Date(2026, 10, 12))}`);
});
it.each(['docx', 'pdf', 'eml', 'xlsx', 'html', 'md'])('cleans extracted Markdown in %s only for display', ext => {
  const raw = '# Harbor Report\n**cargo** [arrival](file.pdf)\n--- OCR page 2 ---\n> - delivered | today';
  const hit = { title: 'harbor', path: `/report.${ext}`, snippet: raw };
  expect(projectResultView(hit).snippet).toBe('Harbor Report cargo arrival delivered today'); expect(hit.snippet).toBe(raw);
});
it.each(['ts', 'tsx', 'js', 'jsx', 'py', 'java', 'kt', 'go', 'rs', 'c', 'cc', 'cpp', 'h', 'hpp', 'cs', 'rb', 'php', 'swift', 'scala', 'sql', 'sh', 'ps1', 'yml', 'yaml', 'json', 'xml', 'toml', 'ini', 'gradle', 'properties', 'txt', 'log', 'csv'])('keeps %s punctuation and ordinary user separators while removing exact OCR markers', ext => {
  expect(projectResultView({ title: 'raw', path: `/raw.${ext}`, snippet: '# literal * value | `code`\n--- OCR page 1 ---\n---\n--- user separator ---' }).snippet)
    .toBe('# literal * value | `code` --- --- user separator ---');
});
