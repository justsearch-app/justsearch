// @vitest-environment happy-dom
//
// Tempdoc 561 (surface tier) — the retrospective panel + the shared-controller/drawer stores.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// These projections do not exercise their incidental backend reads.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import './RetrospectivePanel.js';
import type { RetrospectivePanel } from './RetrospectivePanel.js';
import {
  getAgentSessionController,
  peekAgentSessionController,
  subscribeAgentSession,
  __resetAgentSessionStore,
} from '../state/agentSessionStore.js';
import {
  isRetrospectiveOpen,
  toggleRetrospective,
  setRetrospectiveOpen,
  openRetrospectiveAt,
  __resetRetrospectiveDrawer,
} from '../state/retrospectiveDrawer.js';

afterEach(() => {
  __resetAgentSessionStore();
  __resetRetrospectiveDrawer();
});

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

/** Tab labels with the Background-runs count badge stripped (it is a sibling fact, not the name). */
function tabLabels(el: RetrospectivePanel): string[] {
  return Array.from(el.shadowRoot?.querySelectorAll('[role="tab"]') ?? []).map((t) => {
    const clone = t.cloneNode(true) as Element;
    clone.querySelector('.tab-count')?.remove();
    return (clone.textContent ?? '').replace(/\s+/g, ' ').trim();
  });
}

describe('agentSessionStore — ONE shared controller (561 surface tier)', () => {
  it('returns the same controller instance to every consumer', () => {
    expect(peekAgentSessionController()).toBeNull();
    const a = getAgentSessionController('http://x');
    const b = getAgentSessionController('http://x');
    expect(a).toBe(b);
    expect(peekAgentSessionController()).toBe(a);
  });
});

describe('retrospectiveDrawer store', () => {
  it('toggles + sets the open state', () => {
    expect(isRetrospectiveOpen()).toBe(false);
    toggleRetrospective();
    expect(isRetrospectiveOpen()).toBe(true);
    setRetrospectiveOpen(false);
    expect(isRetrospectiveOpen()).toBe(false);
  });
});

describe('RetrospectivePanel', () => {
  it('renders the retrospective tabs and projects the shared controller sessions', async () => {
    // Seed the shared controller's governed projection directly (no fetch).
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { sessions: unknown[] }).sessions = [
      { sessionId: 's-1', preview: 'find my tax docs', startedAtEpochMs: 0, iterationsUsed: 3 },
    ];

    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true; // render without going through the load path
    document.body.appendChild(el);
    await settle(el);

    // Tempdoc 565 §26.D — the Inbox tab folds the Memory surface's presence/activity half in.
    // Tempdoc 814 (finding 7) — each tab is named by the QUESTION it answers, at its verified scope:
    // Timeline (machine-wide `/api/action-ledger`) → "System activity"; History
    // (`/api/action-ledger` filtered to the ACTIVE agent session id — not `/api/thread`, so not
    // conversation-scoped) → "This run"; Inbox (`/api/presence`) → "Background runs" + a count badge.
    expect(tabLabels(el)).toEqual(['Sessions', 'System activity', 'This run', 'Background runs']);

    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('find my tax docs'); // the human label, not a raw UUID
    el.remove();
  });

  // Tempdoc 577 Move 1 — the Resume affordance projects from the session's declared lifecycle.
  // Tempdoc 585 §D Phase 1 (C1) — finished sessions are now click-through to the replay inspector
  // (a "Replay" button), so they are no longer a dead plain row; Resume still projects from lifecycle.
  it('renders Resume ONLY on resumable sessions; finished ones offer Replay', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { sessions: unknown[] }).sessions = [
      { sessionId: 's-live', preview: 'resumable run', resumable: true },
      { sessionId: 's-done', preview: 'finished run', resumable: false },
      { sessionId: 's-legacy', preview: 'legacy run (no flag)' },
    ];

    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const labels = Array.from(el.shadowRoot?.querySelectorAll('button.resume') ?? []).map((b) =>
      (b.textContent ?? '').trim(),
    );
    // 577 Move 1 — Resume projects from lifecycle (only the one resumable session).
    expect(labels.filter((l) => l === 'Resume').length).toBe(1);
    // 585 §D Phase 1 (C1) — the two finished sessions now offer Replay (read-only inspection).
    expect(labels.filter((l) => l === 'Replay').length).toBe(2);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('finished run');
    expect(text).toContain('Finished'); // the non-resumable rows keep their lifecycle label
    el.remove();
  });

  // Tempdoc 834 §5.2 — the four interruption rows, derived from (state, resumable, interruptedAt).
  it('renders the four interruption presentations, and only where one applies', async () => {
    const at = '2026-08-18T10:00:00Z';
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { sessions: unknown[] }).sessions = [
      { sessionId: 's-fin', preview: 'finished run', status: 'DONE', resumable: false },
      { sessionId: 's-mid', preview: 'mid-step run', status: 'READY_FOR_LLM', resumable: true, interruptedAt: at },
      { sessionId: 's-appr', preview: 'gated run', status: 'WAITING_APPROVAL', resumable: true, interruptedAt: at },
      { sessionId: 's-fork', preview: 'budget-parked run', status: 'WAITING_BUDGET', resumable: false, interruptedAt: at },
    ];

    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const notices = Array.from(
      el.shadowRoot?.querySelectorAll('[data-testid="session-interrupted"]') ?? [],
    ).map((n) => (n.textContent ?? '').replace(/\s+/g, ' ').trim());
    // Three rows say something; the FINISHED one deliberately says nothing.
    expect(notices).toEqual([
      'Interrupted when the app closed. Resume.',
      'Interrupted while waiting for your approval. Resume.',
      'Interrupted while waiting for your decision about tokens/context. Cannot be resumed — start a new run from this transcript',
    ]);
    // The FORK_ONLY row is not resumable, so it keeps Replay/Export — but it must NOT be labelled
    // "Finished", because it was interrupted rather than completed.
    const rows = Array.from(el.shadowRoot?.querySelectorAll('.session-row') ?? []);
    const forkRow = rows.find((r) => (r.textContent ?? '').includes('budget-parked run'));
    expect((forkRow?.textContent ?? '')).not.toContain('Finished');
    expect(forkRow?.querySelector('button.resume')?.textContent?.trim()).toBe('Replay');
    el.remove();
  });

  // Tempdoc 585 §D Phase 1 (C1/D1) — the LIVE open path (setRetrospectiveOpen → loadActive →
  // loadSessions fetch → render) must NOT loop. The browser froze opening this drawer; this drives the
  // exact path the other tests bypass (they set `open=true` + sessions directly), with a notify counter
  // that a render/notify loop would explode (a SYNC loop would instead hang this test = the same signal).
  it('585 §D — opening the drawer via the load path settles without looping', async () => {
    let notifyCount = 0;
    const unsub = subscribeAgentSession(() => {
      notifyCount += 1;
    });
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      const u = String(url);
      if (u.includes('/api/chat/sessions')) {
        return new Response(
          JSON.stringify({
            sessions: [
              { sessionId: 's-done', preview: 'a finished run', resumable: false, startedAtEpochMs: 0, iterationsUsed: 1 },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      getAgentSessionController('http://x');
      const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      // Drive the REAL open path (not `el.open = true`): this fires loadActive → loadSessions.
      setRetrospectiveOpen(true);
      for (let i = 0; i < 30; i += 1) {
        await Promise.resolve();
        await settle(el);
      }
      // A render/notify loop would push this into the thousands; the bound proves the path settles.
      expect(notifyCount).toBeLessThan(50);
      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('a finished run');
    } finally {
      globalThis.fetch = origFetch;
      unsub();
    }
  });

  it('§33 — the Inbox groups by the REAL LifecycleState vocabulary: merged, ranked, friendly', async () => {
    const ctrl = getAgentSessionController('http://x');
    // PresenceRun.state = LifecycleState.name() ∈ {READY_FOR_LLM, WAITING_APPROVAL, AFTER_TOOL_RESULT, DONE, ERROR}
    (ctrl as unknown as { presence: unknown[] }).presence = [
      { sessionId: 'r1', state: 'DONE', actor: 'agent', toolCalls: 1, turns: 1, iterations: 1 },
      { sessionId: 'r2', state: 'ERROR', actor: 'agent', toolCalls: 0, turns: 1, iterations: 1 },
      { sessionId: 'r3', state: 'READY_FOR_LLM', actor: 'agent', toolCalls: 0, turns: 1, iterations: 1 },
      { sessionId: 'r4', state: 'AFTER_TOOL_RESULT', actor: 'agent', toolCalls: 2, turns: 1, iterations: 2 },
    ];

    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    (el as unknown as { activeTab: string }).activeTab = 'inbox';
    document.body.appendChild(el);
    await settle(el);

    const headers = Array.from(el.shadowRoot?.querySelectorAll('.inbox-group-header') ?? []).map((h) =>
      (h.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
    // READY_FOR_LLM + AFTER_TOOL_RESULT merge into ONE friendly "Running" group; rank order Running < Done < Failed.
    expect(headers).toEqual(['Running · 2', 'Done · 1', 'Failed · 1']);
    const statuses = Array.from(el.shadowRoot?.querySelectorAll('.inbox-group') ?? []).map((g) =>
      g.getAttribute('data-status'),
    );
    expect(statuses).toEqual(['running', 'done', 'failed']);
    el.remove();
  });

  // ── Tempdoc 814 (finding 7) — naming, scope and empty states ──

  it('814 — every tab carries a one-line scope descriptor naming the record it answers from', async () => {
    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);

    const scopeOf = async (tab: string): Promise<string> => {
      (el as unknown as { activeTab: string }).activeTab = tab;
      el.requestUpdate();
      await settle(el);
      return (
        el.shadowRoot?.querySelector(`[data-testid="scope-${tab}"]`)?.textContent ?? ''
      ).replace(/\s+/g, ' ').trim();
    };

    expect(await scopeOf('sessions')).toContain('Every agent run');
    // The two former near-synonyms now state the difference the labels imply.
    expect(await scopeOf('timeline')).toContain('this workspace');
    expect(await scopeOf('history')).toContain('run open right now');
    expect(await scopeOf('inbox')).toContain('background');
    el.remove();
  });

  it('814 — each empty state names its FILLING condition, not just its emptiness', async () => {
    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);

    const emptyOf = async (tab: string): Promise<string> => {
      (el as unknown as { activeTab: string }).activeTab = tab;
      el.requestUpdate();
      await settle(el);
      return (el.shadowRoot?.querySelector('.empty-state')?.textContent ?? '')
        .replace(/\s+/g, ' ')
        .trim();
    };

    expect(await emptyOf('inbox')).toContain('Runs you launch in the background appear here');
    expect(await emptyOf('timeline')).toContain('appear here as they happen');
    expect(await emptyOf('history')).toContain('Each tool the agent uses in the current run');
    expect(await emptyOf('sessions')).toContain('Agent runs appear here once you start one');
    el.remove();
  });

  it('814 — the Background-runs tab carries a count badge, so empty reads as a legible zero', async () => {
    const ctrl = getAgentSessionController('http://x');
    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);
    const badge = (): string =>
      (el.shadowRoot?.querySelector('[data-testid="background-runs-count"]')?.textContent ?? '').trim();
    expect(badge()).toBe('0');

    (ctrl as unknown as { presence: unknown[] }).presence = [
      { sessionId: 'r1', state: 'DONE', actor: 'agent', toolCalls: 1, turns: 1, iterations: 1 },
      { sessionId: 'r2', state: 'READY_FOR_LLM', actor: 'agent', toolCalls: 0, turns: 1, iterations: 1 },
    ];
    el.requestUpdate();
    await settle(el);
    expect(badge()).toBe('2');
    el.remove();
  });

  it('814 — openRetrospectiveAt selects the requested tab, opening the drawer if needed', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async () =>
      new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch;
    try {
      const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      expect(isRetrospectiveOpen()).toBe(false);

      openRetrospectiveAt('inbox');
      await settle(el);
      expect(isRetrospectiveOpen()).toBe(true);
      expect((el as unknown as { activeTab: string }).activeTab).toBe('inbox');
      expect(el.shadowRoot?.querySelector('[data-tab="inbox"]')?.getAttribute('aria-selected')).toBe(
        'true',
      );

      // Requested-once: a later plain open keeps whatever tab the user is on.
      setRetrospectiveOpen(false);
      setRetrospectiveOpen(true);
      await settle(el);
      expect((el as unknown as { activeTab: string }).activeTab).toBe('inbox');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});
