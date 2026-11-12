// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FC6a — the status panel's "All activity" is an arrival at Activity, as Ctrl+K's journal is: the
 * Activity the navigation shows has its earlier filter reset and the keyboard in its feed, not on
 * the status pill the panel closed back to. The navigation is applied as the Shell applies it: it
 * addresses the pending arrival to the window it lands on (here, the page).
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import '../views/ActivitySurface.js';
import { addressActivitySection } from '../state/activitySection.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';
import { DEFAULT_FEED_FILTER } from '../operations/activityFeed.js';

class NoopEventSource extends EventTarget {
  readyState = 0;
  constructor(public url: string) { super(); }
  close(): void { this.readyState = 2; }
}

type Updating = HTMLElement & { updateComplete: Promise<unknown> };

beforeAll(() => {
  vi.stubGlobal('EventSource', function StubES(url: string | URL) { return new NoopEventSource(String(url)) as unknown as EventSource; });
  // One record in the ledger, so the feed has an entry to take the keyboard.
  const entries = [{ id: 'operation:k1', kind: 'operation', originator: 'user', operationId: 'core.reindex', outcome: 'SUCCESS',
    occurredAt: '2026-11-01T10:00:00Z' }];
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => ({ ok: true, status: 200,
    json: async () => (String(input).includes('/api/action-ledger') ? { entries } : { entries: [] }), body: null }) as unknown as Response);
});

/** The Shell's part: the navigation to Activity is applied, landing on this page. */
const navigated: string[] = [];
const land = (event: Event): void => {
  const surfaceId = (event as CustomEvent<{ surfaceId: string }>).detail.surfaceId;
  navigated.push(surfaceId);
  if (surfaceId === 'core.activity-surface') addressActivitySection(() => document.body);
};
beforeEach(() => { navigated.length = 0; document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, land); });
afterEach(() => { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, land); document.body.innerHTML = ''; });

/** The element with the keyboard, across shadow roots. */
function deepActive(): Element | null {
  let active: Element | null = document.activeElement;
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active;
}

async function ticks(...elements: Updating[]): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    for (const element of elements) await element.updateComplete;
  }
}

describe('FC6a the status panel\'s "All activity"', () => {
  it('arrives like Ctrl+K: Activity\'s earlier filter is reset and the keyboard is in its feed, not on the pill', async () => {
    const activity = document.createElement('jf-activity-surface') as Updating;
    const pill = new StatusPill();
    document.body.append(pill, activity);
    await activity.updateComplete;
    const feed = activity.shadowRoot!.querySelector('jf-action-ledger') as Updating & { filter: Record<string, unknown> };
    await feed.updateComplete;
    feed.filter = { ...feed.filter, quick: 'problems', originators: ['system'], date: 'today' };
    await ticks(feed);

    pill.toggle();
    await ticks(pill);
    const panel = pill.shadowRoot!.querySelector('jf-status-panel') as StatusPanel;
    await panel.updateComplete;
    const link = [...panel.shadowRoot!.querySelectorAll<HTMLButtonElement>('.footer button')].find((b) => b.textContent?.trim() === 'All activity');
    expect(link, 'the panel offers All activity').toBeDefined();
    link!.click();
    await ticks(pill, activity, feed);

    expect(navigated).toEqual(['core.activity-surface']);
    expect(feed.filter, 'the earlier filter is reset: every record shows').toEqual({ ...DEFAULT_FEED_FILTER });
    const keyboard = deepActive();
    expect(keyboard, 'the keyboard is not left on the pill').not.toBe(pill.shadowRoot!.querySelector('button.trigger'));
    expect(feed.shadowRoot!.contains(keyboard), `the keyboard is in Activity's feed: ${keyboard?.outerHTML.slice(0, 80)}`).toBe(true);
    expect(keyboard?.classList.contains('entry'), 'on its newest record').toBe(true);
  });
});
