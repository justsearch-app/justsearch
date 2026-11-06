// @vitest-environment happy-dom

/**
 * ActivitySurface render test — 727 F-4.
 *
 * Pre-fix, the "Operations (structured)" panel's `<jf-resource>` element was mounted with
 * no `api-base` attribute at all, so it (and the `resourceListItemStrategy` it dispatches
 * through) fell back to relative URLs for the `core.operation-history` SSE stream — wrong
 * whenever the FE and backend are served from different origins. The sibling
 * `<jf-action-ledger>` panel was never affected because it always set `api-base` explicitly.
 * This test pins that `<jf-resource>` now receives the same `api-base` ActivitySurface itself
 * was given, mirroring `<jf-action-ledger>`.
 */

import { describe, expect, it, afterEach, beforeAll, vi } from 'vitest';
import { requestActivitySection, takeActivitySection } from '../state/activitySection.js';

// Stub EventSource before ActivitySurface's transitive imports (ActionLedgerView,
// jf-resource-view) try to open real streams against a non-existent backend.
class NoopEventSource extends EventTarget {
  readyState = 0;
  url: string;
  CONNECTING = 0;
  OPEN = 1;
  CLOSED = 2;
  withCredentials = false;
  onopen: ((this: EventSource, ev: Event) => unknown) | null = null;
  onmessage: ((this: EventSource, ev: MessageEvent) => unknown) | null = null;
  onerror: ((this: EventSource, ev: Event) => unknown) | null = null;
  constructor(url: string | URL) {
    super();
    this.url = String(url);
  }
  close(): void {
    this.readyState = this.CLOSED;
  }
}

beforeAll(() => {
  vi.stubGlobal(
    'EventSource',
    function StubES(url: string | URL) {
      return new NoopEventSource(url) as unknown as EventSource;
    },
  );
  // Tempdoc 804 §B9 (F9): <jf-action-ledger> also READS `GET /api/action-ledger` (so an undelivered
  // stream frame can no longer render as a false "No activity yet"). Stub it — this test is about
  // api-base forwarding, not the ledger, and an unstubbed read would hit a non-existent backend.
  vi.stubGlobal(
    'fetch',
    async () => ({ ok: true, status: 200, json: async () => ({ entries: [] }) }) as unknown as Response,
  );
});

import './ActivitySurface.js';
import type { ActivitySurface } from './ActivitySurface.js';

async function mount(apiBase: string): Promise<ActivitySurface> {
  const el = document.createElement('jf-activity-surface') as ActivitySurface;
  el.apiBase = apiBase;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('ActivitySurface (jf-activity-surface) — api-base forwarding (727 F-4)', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('passes its own api-base down to both the action-ledger and resource panels', async () => {
    const el = await mount('http://127.0.0.1:33221');

    const ledger = el.shadowRoot?.querySelector('jf-action-ledger');
    const resource = el.shadowRoot?.querySelector('jf-resource');

    expect(ledger?.getAttribute('api-base')).toBe('http://127.0.0.1:33221');
    // Pre-fix: this attribute was absent entirely (getAttribute returned null),
    // leaving <jf-resource-view> to resolve its stream URL against the wrong origin.
    expect(resource?.getAttribute('api-base')).toBe('http://127.0.0.1:33221');
  });

  it('still declares resource-id="core.operation-history" on the structured-operations panel', async () => {
    const el = await mount('http://127.0.0.1:33221');
    const resource = el.shadowRoot?.querySelector('jf-resource');
    expect(resource?.getAttribute('resource-id')).toBe('core.operation-history');
  });
});

describe('Activity holds what the frame drawers held (DP3)', () => {
  it('shows background runs, this run and the effect journal, with the journal’s undo controls', async () => {
    const el = document.createElement('jf-activity-surface') as ActivitySurface;
    document.body.append(el);
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('jf-run-activity')).not.toBeNull();
    const journal = el.shadowRoot!.querySelector('jf-effect-audit-log') as HTMLElement & { updateComplete: Promise<unknown> };
    await journal.updateComplete;
    expect(journal.shadowRoot!.querySelector('[data-testid="effect-audit-journal"]')).not.toBeNull();
    expect(journal.shadowRoot!.querySelector('dialog')).toBeNull();
    el.remove();
  });

  it.each([
    ['background-runs', 'jf-run-activity', '[data-testid="activity-background-runs"]'],
    ['effect-journal', 'jf-effect-audit-log', '[data-testid="effect-audit-journal"]'],
  ] as const)('a pointer to %s lands on that section, whether Activity is up yet or not', async (section, owner, selector) => {
    requestActivitySection(section);
    const el = document.createElement('jf-activity-surface') as ActivitySurface;
    document.body.append(el);
    await el.updateComplete;
    const child = el.shadowRoot!.querySelector(owner) as HTMLElement & { updateComplete: Promise<unknown> };
    await child.updateComplete;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(child.shadowRoot!.activeElement).toBe(child.shadowRoot!.querySelector(selector));
    expect(takeActivitySection()).toBeNull();
    (child.shadowRoot!.activeElement as HTMLElement).blur();
    requestActivitySection(section);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(child.shadowRoot!.activeElement).toBe(child.shadowRoot!.querySelector(selector));
    el.remove();
  });
});
