// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// fm-07 (fm-rest-a, S7) — Library's "Pause reading" carries the pause icon (two bars, as fm-12/fm-15 draw
// Pause), not the clock; "Resume reading" keeps its play icon.
import { afterEach, expect, it } from 'vitest';
import './IndexingControls.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import type { StatusResponse } from '../../api/generated/schema-types/index.js';

type Control = HTMLElement & { updateComplete: Promise<unknown> };
async function mount(paused: boolean): Promise<Control> {
  const el = document.createElement('jf-indexing-controls') as Control;
  Object.assign(el, { toolbar: true, compact: true, allowPause: true, progress: selectIndexingProgress({
    worker: { core: { indexState: 'IDLE', indexedDocuments: 1 } },
    operating: { indexing: { state: paused ? 'PAUSED' : 'RUNNING', reasons: paused ? ['user_paused'] : [] } } } as unknown as StatusResponse, true, 0, []) });
  document.body.append(el);
  for (let i = 0; i < 10; i++) { await Promise.resolve(); await el.updateComplete; }
  return el;
}
/** The shape of the first icon on Library's reading toggle. */
function shape(el: Control): string {
  const svg = el.shadowRoot!.querySelector('#pause-toggle svg')!;
  return svg.querySelector('path[d="M9 5v14M15 5v14"]') ? 'pause'
    : svg.querySelector('polyline[points="12 6 12 12 16 14"]') ? 'clock'
      : svg.querySelector('polygon[points="6 3 20 12 6 21 6 3"]') ? 'play' : 'other';
}
afterEach(() => { document.body.replaceChildren(); });

it('S7: "Pause reading" carries the pause icon, not the clock', async () => {
  const el = await mount(false);
  expect(el.shadowRoot!.querySelector('#pause-toggle')!.textContent).toContain('Pause reading');
  expect(shape(el)).toBe('pause');
});

it('S7 (kept): "Resume reading" keeps its play icon', async () => {
  const el = await mount(true);
  expect(el.shadowRoot!.querySelector('#pause-toggle')!.textContent).toContain('Resume reading');
  expect(shape(el)).toBe('play');
});
