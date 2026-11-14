// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// DP2 — Space previews the focused search result; C8 — the keyboard stays with its owner.
import { afterEach, describe, expect, it, vi } from 'vitest';
import './Sv3Results.js';
import type { Sv3Results } from './Sv3Results.js';
import type { ResultsCard } from '../../components/searchResults/ResultsCard.js';
import type { Sv3ResultsView } from './sv3-search.js';
import { SV3_RESULTS_IDLE } from './sv3-search.js';
import { openLocalFile } from '../../plugin-api/capabilities/platform.js';

vi.mock('../../plugin-api/capabilities/platform.js', { spy: true });
vi.mock('../../primitives/modality.js', { spy: true });

const hit = (id: string, title: string): Record<string, unknown> => ({ id, title, path: `/docs/${title}`, snippet: `snippet of ${title}`, score: 1 });

function view(results: Array<Record<string, unknown>>): Sv3ResultsView {
  return { ...SV3_RESULTS_IDLE, status: 'ready', query: 'heating', snapshot: { query: 'heating', results, matchCount: results.length, totalHits: results.length, facetsTruncated: false, isSearching: false, processingTimeMs: 10, error: null, searchTrace: null } } as unknown as Sv3ResultsView;
}

async function mount(results = [hit('a', 'alpha.md'), hit('b', 'beta.md')]): Promise<Sv3Results> {
  const el = document.createElement('jf-sv3-results');
  el.view = view(results);
  document.body.append(el);
  await settle(el);
  return el;
}

async function settle(el: Sv3Results): Promise<void> {
  await el.updateComplete;
  await el.shadowRoot!.querySelector<ResultsCard>('jf-results-card')?.updateComplete;
  await el.updateComplete;
}

const rows = (el: Sv3Results): HTMLElement[] =>
  [...el.shadowRoot!.querySelector('jf-results-card')!.shadowRoot!.querySelectorAll<HTMLElement>('[data-testid="search-result-row"]')];
const card = (el: Sv3Results): HTMLElement | null => el.shadowRoot!.querySelector('[data-testid="sv3-result-preview"]');

function press(target: Element, key: string, init: KeyboardEventInit = {}): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, composed: true, cancelable: true, ...init });
  target.dispatchEvent(event);
  return event;
}

afterEach(() => {
  document.body.replaceChildren();
  vi.restoreAllMocks();
});

describe('DP2 Space previews the focused result', () => {
  it('every live row takes the keyboard, whether or not forget is available', async () => {
    const el = await mount();
    expect(rows(el).map(row => row.tabIndex)).toEqual([0, 0]);
  });

  it('Space opens the floating card on the focused row, and keeps the keyboard on the row', async () => {
    const el = await mount();
    const [row] = rows(el);
    row!.focus();
    const event = press(row!, ' ');
    await settle(el);
    expect(event.defaultPrevented).toBe(true);
    expect(card(el)?.textContent).toContain('alpha.md');
    expect(card(el)?.textContent).toContain('snippet of alpha.md');
    expect(card(el)?.getAttribute('role')).toBe('status');
    expect(el.previewOpen).toBe(true);
    expect(rows(el)[0]!.getRootNode()).toBe(row!.getRootNode());
  });

  it('Space again closes it', async () => {
    const el = await mount();
    const [row] = rows(el);
    press(row!, ' ');
    await settle(el);
    press(row!, ' ');
    await settle(el);
    expect(card(el)).toBeNull();
  });

  it('Escape closes it, and goes no further', async () => {
    const el = await mount();
    const [row] = rows(el);
    press(row!, ' ');
    await settle(el);
    const beyond: Event[] = [];
    document.body.addEventListener('keydown', event => beyond.push(event), { once: true });
    press(row!, 'Escape');
    await settle(el);
    expect(card(el)).toBeNull();
    expect(beyond).toHaveLength(0);
  });

  it('Space on another row moves the card to it', async () => {
    const el = await mount();
    const [first, secondRow] = rows(el);
    press(first!, ' ');
    await settle(el);
    press(secondRow!, ' ');
    await settle(el);
    expect(card(el)?.textContent).toContain('beta.md');
  });

  it('Enter opens the file and the arrow opens details, each closing the card', async () => {
    vi.mocked(openLocalFile).mockResolvedValue();
    const el = await mount();
    const [row] = rows(el);
    const opened: string[] = [];
    el.addEventListener('card-open', event => opened.push((event as CustomEvent<{ id: string }>).detail.id));
    press(row!, ' ');
    await settle(el);
    press(row!, 'Enter');
    await settle(el);
    expect(openLocalFile).toHaveBeenCalledWith('/docs/alpha.md');
    expect(opened).toEqual([]);
    expect(card(el)).toBeNull();
    press(row!, ' ');
    await settle(el);
    press(row!, 'ArrowRight');
    await settle(el);
    expect(opened).toEqual(['a']);
    expect(card(el)).toBeNull();
  });

  it('Enter on a row with no preview does what a click does', async () => {
    const el = await mount();
    const opened: string[] = [];
    el.addEventListener('card-open', event => opened.push((event as CustomEvent<{ id: string }>).detail.id));
    press(rows(el)[1]!, 'Enter');
    expect(opened).toEqual(['b']);
  });

  it('never outlives its result: new results without the row close it', async () => {
    const el = await mount();
    press(rows(el)[0]!, ' ');
    await settle(el);
    el.view = view([hit('b', 'beta.md')]);
    await settle(el);
    expect(card(el)).toBeNull();
    expect(el.previewOpen).toBe(false);
  });

  it('closes when the keyboard leaves the list', async () => {
    const el = await mount();
    const [row] = rows(el);
    row!.focus();
    press(row!, ' ');
    await settle(el);
    row!.dispatchEvent(new FocusEvent('focusout', { bubbles: true, composed: true }));
    await settle(el);
    expect(card(el)).toBeNull();
  });
});

describe('C8 Space preview keeps to its owner', () => {
  it('never fires from a control inside the row', async () => {
    const el = await mount();
    const button = rows(el)[0]!.querySelector('[data-testid="row-actions"]')!;
    const event = press(button, ' ');
    await settle(el);
    expect(card(el)).toBeNull();
    expect(event.defaultPrevented).toBe(false);
  });

  it('never fires inside a text field, even one reached through the row', async () => {
    const el = await mount();
    const field = document.createElement('input');
    rows(el)[0]!.append(field);
    field.focus();
    const event = press(field, ' ');
    await settle(el);
    expect(card(el)).toBeNull();
    expect(event.defaultPrevented).toBe(false);
  });

  it('never fires during IME composition', async () => {
    const el = await mount();
    press(rows(el)[0]!, ' ', { isComposing: true });
    press(rows(el)[0]!, ' ', { keyCode: 229 } as KeyboardEventInit);
    await settle(el);
    expect(card(el)).toBeNull();
  });

  it('never fires on key repeat, which also may not scroll the list away', async () => {
    const el = await mount();
    const event = press(rows(el)[0]!, ' ', { repeat: true });
    await settle(el);
    expect(card(el)).toBeNull();
    expect(event.defaultPrevented).toBe(true);
  });

  it('never fires with a modifier held', async () => {
    const el = await mount();
    press(rows(el)[0]!, ' ', { ctrlKey: true });
    await settle(el);
    expect(card(el)).toBeNull();
  });

  it('never fires under a modal', async () => {
    const { modalOwnsFocus } = await import('../../primitives/modality.js');
    vi.mocked(modalOwnsFocus).mockReturnValue(true);
    const el = await mount();
    press(rows(el)[0]!, ' ');
    await settle(el);
    expect(card(el)).toBeNull();
  });
});
