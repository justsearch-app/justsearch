// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { Sv3Main } from '../Sv3Main.js';
import { SV3_RESULTS_IDLE } from '../sv3-search.js';
afterEach(() => document.body.replaceChildren());
it('new result lists start at the top; layout and pane updates preserve the reader position', async () => {
  const main = new Sv3Main(); main.state = 'docked'; main.view = { ...SV3_RESULTS_IDLE, status: 'ready', query: 'harbor', snapshot: { query: 'harbor', results: [{ id: 'one', title: 'harbor', path: '/harbor.pdf' }], matchCount: 1, totalHits: 1, facetsTruncated: false, isSearching: false, processingTimeMs: 10, error: null, searchTrace: null } };
  document.body.append(main); await main.updateComplete;
  const scroller = main.shadowRoot!.querySelector<HTMLElement>('.scroller')!;
  let height = 2000; Object.defineProperty(scroller, 'scrollHeight', { get: () => height });
  main.requestUpdate(); await main.updateComplete;
  expect(scroller.scrollTop).toBe(0);
  scroller.scrollTop = 400; height = 2500; main.requestUpdate(); main.remeasureReadingWindow(); await main.updateComplete;
  expect(scroller.scrollTop).toBe(400);
  // A selection/pane projection keeps the same list, despite a new view object.
  main.view = { ...main.view, selectedIds: new Set(['one']) }; await main.updateComplete;
  expect(scroller.scrollTop).toBe(400);
  // New search, filter and All responses supply a new list, even for the same query.
  for (const query of ['new query', 'new query', 'new query']) {
    main.view = { ...main.view, query, snapshot: { ...main.view.snapshot!, query, results: [...main.view.snapshot!.results] } }; await main.updateComplete;
    expect(scroller.scrollTop).toBe(0); scroller.scrollTop = 400;
  }
});
