// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// fm-04's filter row: kinds in a person's words, none for one kind, and the chosen filter marked.
import { afterEach, expect, it } from 'vitest';
import { ResultsCard, type CardSnapshot } from './ResultsCard.js';
import { setFilters } from '../../state/searchFiltersState.js';

const hit = { id: 'one', path: '/notes/report.md', title: 'report.md', snippet: 'inspection', modifiedAt: 1735689600000 };
async function mount(extra: Partial<CardSnapshot> = {}, facetSelections: Record<string, string[]> = {}) {
  const el = new ResultsCard();
  el.snapshot = { query: 'inspection', results: [hit], matchCount: 3, totalHits: 3, facetsTruncated: false,
    isSearching: false, processingTimeMs: 40, error: null, ...extra };
  el.facetSelections = facetSelections;
  document.body.append(el); await el.updateComplete; return el;
}
const row = (el: ResultsCard) => [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.result-filters button')];
const words = (el: ResultsCard) => row(el).map(b => b.textContent!.replace(/\s+/g, ' ').trim());
afterEach(() => { document.body.replaceChildren(); setFilters({}); });
// The picture's three files as the Engine describes them: its kind for mail is the catch-all "binary".
const EML = 'message/rfc822';
const PICTURE = { file_kind: { binary: 1, pdf: 1, office: 1 },
  mime_base: { [EML]: 1, 'application/pdf': 1, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 1 } };

it('PF1 kind filters read as a person would say them, plural with the count, as drawn', async () => {
  const el = await mount({ facets: PICTURE });
  expect(words(el)).toEqual(['All', 'Documents 2', 'Mail 1', 'Any time', 'More filters']);
  const documents = row(el)[1]!;
  expect(documents.getAttribute('aria-label')).toBe('Documents, 2 files');
});

it('PF1 the packaged app\'s "text 36" reads "Text files 36" beside another kind', async () => {
  const el = await mount({ facets: { file_kind: { text: 36, code: 2 } } });
  expect(words(el).slice(0, 3)).toEqual(['All', 'Text files 36', 'Code 2']);
  expect(words(el).join(' ')).not.toMatch(/\btext\b|\bcode\b|\bpdf\b/);
});

it('PF1 a press on a filter that stands for several kinds chooses them all at once', async () => {
  const el = await mount({ facets: PICTURE });
  const events: unknown[] = [];
  el.addEventListener('card-facet-toggle', e => events.push((e as CustomEvent).detail));
  row(el)[1]!.click(); row(el)[2]!.click();
  expect(events).toEqual([{ field: 'file_kind', value: ['pdf', 'office'] }, { field: 'mime_base', value: EML }]);
});

it('PF1\' Mail is the mail the Engine calls "binary"; other binary files are Other files, never Mail', async () => {
  const el = await mount({ facets: { file_kind: { binary: 3, pdf: 1 }, mime_base: { [EML]: 1, 'application/octet-stream': 2, 'application/pdf': 1 } } });
  expect(words(el)).toEqual(['All', 'Other files 2', 'Documents 1', 'Mail 1', 'Any time', 'More filters']);
  const one = await mount({ facets: { file_kind: { binary: 1, pdf: 1 }, mime_base: { 'application/x-sqlite3': 1, 'application/pdf': 1 } } });
  expect(words(one)).toEqual(['All', 'Other files 1', 'Documents 1', 'Any time', 'More filters']);
});

it('PF1\' with Mail chosen, All lets go of it and of any kind, in one press', async () => {
  const el = await mount({ facets: { file_kind: { binary: 1 }, mime_base: { [EML]: 1 } } }, { mime_base: [EML] });
  expect(words(el)).toEqual(['All', 'Mail 1', 'Any time', 'More filters']);
  expect(el.shadowRoot!.querySelector('[data-testid="more-filters"]')!.getAttribute('aria-label'), 'Mail is not a More filters choice').toBeNull();
  const events: unknown[] = [];
  el.addEventListener('card-facet-toggle', e => events.push((e as CustomEvent).detail));
  row(el)[0]!.click();
  expect(events).toEqual([[{ field: 'file_kind', value: null }, { field: 'mime_base', value: EML }]]);
});

it('PF2 one kind among the results shows no kind filters and no All; dates and More filters stay', async () => {
  const el = await mount({ facets: { file_kind: { text: 36 } } });
  expect(words(el)).toEqual(['Any time', 'More filters']);
  expect(el.shadowRoot!.querySelector('[data-testid="type-all"]')).toBeNull();
});

it('PF2 a chosen kind stays on the row so it can be undone, even when it is the only kind', async () => {
  const el = await mount({ facets: { file_kind: { pdf: 2 } } }, { file_kind: ['pdf'] });
  expect(words(el)).toEqual(['All', 'Documents 2', 'Any time', 'More filters']);
});

it('PF3 the chosen filter is marked for assistive tech; Any time and More filters only when they hold a choice', async () => {
  const el = await mount({ facets: PICTURE });
  const pressed = () => row(el).filter(b => b.getAttribute('aria-pressed') === 'true').map(b => b.textContent!.trim());
  const strong = () => row(el).filter(b => b.matches('[aria-pressed="true"], .on')).map(b => b.textContent!.replace(/\s+/g, ' ').trim());
  expect(pressed()).toEqual(['All']);
  expect(strong()).toEqual(['All']);
  el.facetSelections = { mime_base: [EML] }; await el.updateComplete;
  expect(pressed()).toEqual(['Mail 1']);
  el.facetSelections = { mime_base: [EML], language: ['en'] };
  setFilters({ modifiedFromMs: 1234 }); await el.updateComplete;
  expect(strong()).toEqual(['Mail 1', 'Custom dates', 'More filters']);
  expect(el.shadowRoot!.querySelector('[data-testid="more-filters"]')!.getAttribute('aria-label')).toBe('More filters, 1 set');
  el.facetSelections = {}; setFilters({}); await el.updateComplete;
  el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="more-filters"]')!.click(); await el.updateComplete;
  expect(strong(), 'an open panel is not a choice').toEqual(['All']);
});
