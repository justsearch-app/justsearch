// @vitest-environment happy-dom

/**
 * Slice 490 §4.D — AdvisoryToastHost component tests.
 *
 * Covers the Group A2 hasSeenFirstSnapshot + lastFrameKind heuristic:
 *   - initial state → no toast.
 *   - LIFECYCLE snapshot (any size) → seed seenKeys silently.
 *   - UPDATE → toast each new advisory.
 *   - UPDATE before snapshot → defensive baseline seeding.
 *   - Click on toast → store.acknowledge + dismiss.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Tempdoc 655 long-term design pass — mocked before the component import below so the
// OS-notification wiring inside AdvisoryToastHost.onSnapshot can be observed/controlled.
const notifyMocks = vi.hoisted(() => ({
  isWindowFocused: vi.fn(async () => true),
  sendDesktopNotification: vi.fn(async () => {}),
}));
vi.mock('../../../utils/windowFocus.js', () => ({
  isWindowFocused: notifyMocks.isWindowFocused,
}));
vi.mock('../../../utils/notify.js', () => ({
  sendDesktopNotification: notifyMocks.sendDesktopNotification,
}));

import './AdvisoryToastHost.js';
import type { AdvisoryToastHost } from './AdvisoryToastHost.js';
import type {
  AdvisoryListener,
  AdvisoryRecord,
  AdvisorySnapshot,
  AdvisoryStore,
} from './AdvisoryStore.js';
import { __resetForTest, __seedForTest } from '../../../i18n/resourceCatalog.js';
import { __resetUiModeForTest, setUiMode } from '../../state/uiModeState.js';

class StubAdvisoryStore {
  private listeners = new Set<AdvisoryListener>();
  private snap: AdvisorySnapshot = {
    advisories: [],
    unreadCount: 0,
    isConnected: true,
    lastFrameKind: 'initial',
  };
  acknowledge = vi.fn();
  dropEphemeral = vi.fn();

  subscribe(listener: AdvisoryListener): () => void {
    this.listeners.add(listener);
    listener(this.snap);
    return () => this.listeners.delete(listener);
  }
  push(s: Partial<AdvisorySnapshot>): void {
    this.snap = { ...this.snap, ...s };
    for (const l of this.listeners) l(this.snap);
  }
}

function rec(
  operationId: string,
  occurredAt: string,
  acknowledged = false,
  sourceRenderHint: 'EPHEMERAL' | 'PERSISTED' | 'REQUIRES_ACK' = 'PERSISTED',
): AdvisoryRecord {
  return {
    key: `operation.completed:${operationId}:SUCCESS`,
    event: {
      classId: 'operation.completed',
      id: `operation.completed:${operationId}:SUCCESS`,
      occurredAt,
      renderHint: sourceRenderHint,
      diagnosticsLink: null,
      provenance: {
        transport: 'BUTTON',
        executor: 'UI',
        initiator: null,
        occurredAt,
      },
      primaryAction: null,
      bodyI18nKey: 'advisory.operation-completed.success',
      classExtras: { operationId, outcome: 'SUCCESS' },
    },
    acknowledged,
    sourceRenderHint,
    origin: "stream",
  };
}

function make(store: AdvisoryStore | null): AdvisoryToastHost {
  const el = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  el.store = store;
  document.body.appendChild(el);
  return el;
}

describe('AdvisoryToastHost (Group A2 + B4)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('initial state renders nothing', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;
    expect(el.visible.length).toBe(0);
  });

  it('LIFECYCLE snapshot with advisories seeds seenKeys silently — no toast', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [rec('core.historical', '2026-05-12T08:00:00Z')],
      lastFrameKind: 'snapshot',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);
  });

  it('LIFECYCLE empty snapshot then UPDATE — toasts the new event (Group A2 fix)', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // Empty snapshot — the OLD heuristic mis-classified this as "no snapshot
    // seen" and the NEXT UPDATE was treated as snapshot seed.
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);

    // First UPDATE arrives — Group A2 fix asserts it toasts (where the old
    // heuristic would have dropped it).
    store.push({
      advisories: [rec('core.first-real', '2026-05-12T09:00:00Z')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    expect(el.visible[0]?.record.event.classExtras.operationId).toBe('core.first-real');
  });

  it('UPDATE arriving before any snapshot — defensive baseline seeds silently', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // Pathological case: an UPDATE arrives before any LIFECYCLE snapshot.
    // Group A2 fix treats this as the implicit baseline (no toast).
    store.push({
      advisories: [rec('core.early-update', '2026-05-12T09:00:00Z')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);
  });

  it('toasts each new advisory after the first snapshot', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // First snapshot seeds two historical advisories silently.
    store.push({
      advisories: [
        rec('core.old-1', '2026-05-12T08:00:00Z'),
        rec('core.old-2', '2026-05-12T08:01:00Z'),
      ],
      lastFrameKind: 'snapshot',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);

    // Two new updates — each toasts.
    store.push({
      advisories: [
        rec('core.old-1', '2026-05-12T08:00:00Z'),
        rec('core.old-2', '2026-05-12T08:01:00Z'),
        rec('core.new-a', '2026-05-12T09:00:00Z'),
      ],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    expect(el.visible[0]?.record.event.classExtras.operationId).toBe('core.new-a');

    store.push({
      advisories: [
        rec('core.old-1', '2026-05-12T08:00:00Z'),
        rec('core.old-2', '2026-05-12T08:01:00Z'),
        rec('core.new-a', '2026-05-12T09:00:00Z'),
        rec('core.new-b', '2026-05-12T09:05:00Z'),
      ],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(2);
  });

  // Sandbox round 8 — a REQUIRES_ACK toast had NO timeout at all, so two of them were still
  // covering the Library header's control row ~6 minutes and several navigations later (one hid
  // the `Add Folder` button the empty state told the user to press). The toast is now bounded;
  // the RECORD is untouched, keeping its durable home in the inbox drawer + rail badge.
  describe('REQUIRES_ACK toast is time-bounded (round 8 — a persistent advisory was a persistent overlay)', () => {
    it('gets an auto-dismiss timer, unlike the pre-round-8 behaviour', async () => {
      const store = new StubAdvisoryStore();
      const el = make(store as unknown as AdvisoryStore);
      store.push({ advisories: [], lastFrameKind: 'snapshot' });
      store.push({
        advisories: [
          rec('core.req-ack', '2026-05-12T09:00:00Z', false, 'REQUIRES_ACK'),
        ],
        lastFrameKind: 'update',
      });
      await el.updateComplete;
      expect(el.visible.length).toBe(1);
      expect(el.visible[0]?.timeoutId).not.toBeNull();
    });

    it('dwells longer than a plain toast, then hides — without acknowledging or dropping the record', async () => {
      vi.useFakeTimers();
      try {
        const store = new StubAdvisoryStore();
        const el = make(store as unknown as AdvisoryStore);
        store.push({ advisories: [], lastFrameKind: 'snapshot' });
        store.push({
          advisories: [rec('core.req-ack', '2026-05-12T09:00:00Z', false, 'REQUIRES_ACK')],
          lastFrameKind: 'update',
        });
        expect(el.visible.length).toBe(1);
        // Still up at the plain TOAST_DURATION_MS — the ack-required dwell is deliberately longer,
        // so a pass here cannot come from the toast simply reusing the 5s timer.
        vi.advanceTimersByTime(5000);
        expect(el.visible.length).toBe(1);
        // ACK_TOAST_DURATION_MS = 3 x 5000.
        vi.advanceTimersByTime(10_000);
        expect(el.visible.length).toBe(0);
        // The overlay is gone; the RECORD is not. Auto-hide must never acknowledge it (that would
        // clear the rail badge's unread mark) nor drop it (that would empty the inbox drawer).
        expect(store.acknowledge).not.toHaveBeenCalled();
        expect(store.dropEphemeral).not.toHaveBeenCalled();
      } finally {
        vi.useRealTimers();
      }
    });
  });

  it('Substrate-completion — EPHEMERAL + PERSISTED records in same snapshot dispatch independently', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    store.push({
      advisories: [
        rec('core.transient', '2026-05-12T09:00:00Z', false, 'EPHEMERAL'),
        rec('core.persisted', '2026-05-12T09:00:01Z', false, 'PERSISTED'),
        rec('core.urgent', '2026-05-12T09:00:02Z', false, 'REQUIRES_ACK'),
      ],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(3);
    // Every store-backed hint gets an auto-dismiss timer; REQUIRES_ACK's is just longer (round 8).
    const ephemeralToast = el.visible.find(
      (t) => t.record.event.classExtras.operationId === 'core.transient',
    );
    const persistedToast = el.visible.find(
      (t) => t.record.event.classExtras.operationId === 'core.persisted',
    );
    const requiresAckToast = el.visible.find(
      (t) => t.record.event.classExtras.operationId === 'core.urgent',
    );
    expect(ephemeralToast?.timeoutId).not.toBeNull();
    expect(persistedToast?.timeoutId).not.toBeNull();
    expect(requiresAckToast?.timeoutId).not.toBeNull();
  });

  it('clicking a toast acknowledges + dismisses it', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    store.push({
      advisories: [rec('core.click', '2026-05-12T09:00:00Z')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    toast.click();
    expect(store.acknowledge).toHaveBeenCalledWith('operation.completed:core.click:SUCCESS');
    // Happy DOM now exposes getAnimations(), so dismissal waits for the CSS exit to finish.
    toast.dispatchEvent(new Event('animationend'));
    expect(el.visible.length).toBe(0);
  });

  // Sandbox round 7 — the OverlayHost `.top-right` slot is an uncapped, unscrolled fixed flex
  // column, so an unbounded `visible` array stacked a burst of toasts downward over the chat
  // surface's header control row. The stack is now a BOUNDED projection.
  describe('bounded toast stack (round 7 — occlusion of the header control row)', () => {
    /** Drive `n` new stream advisories through the store as consecutive UPDATE frames. */
    async function burst(el: AdvisoryToastHost, store: StubAdvisoryStore, n: number) {
      store.push({ advisories: [], lastFrameKind: 'snapshot' });
      const advisories: AdvisoryRecord[] = [];
      for (let i = 0; i < n; i += 1) {
        advisories.push(rec(`core.burst-${i}`, `2026-05-12T09:0${i}:00Z`, false, 'REQUIRES_ACK'));
        store.push({ advisories: [...advisories], lastFrameKind: 'update' });
      }
      await el.updateComplete;
    }

    it('renders at most 3 toasts for a burst of 8, summarizing the rest as "+N earlier"', async () => {
      const store = new StubAdvisoryStore();
      const el = make(store as unknown as AdvisoryStore);
      await burst(el, store, 8);
      // All 8 stay live (nothing is silently acknowledged or dropped) — only the RENDER is bounded.
      expect(el.visible.length).toBe(8);
      expect(el.shadowRoot?.querySelectorAll('.toast').length).toBe(3);
      const more = el.shadowRoot?.querySelector('[data-testid="toast-more"]');
      expect(more?.textContent?.replace(/\s+/g, ' ').trim()).toBe('+5 earlier notifications');
    });

    it('keeps the NEWEST toasts visible — a burst never buries the just-arrived advisory', async () => {
      const store = new StubAdvisoryStore();
      const el = make(store as unknown as AdvisoryStore);
      await burst(el, store, 5);
      const keys = Array.from(el.shadowRoot?.querySelectorAll('.toast') ?? []).map((t) =>
        t.getAttribute('data-key'),
      );
      expect(keys).toEqual([
        'operation.completed:core.burst-2:SUCCESS',
        'operation.completed:core.burst-3:SUCCESS',
        'operation.completed:core.burst-4:SUCCESS',
      ]);
    });

    it('renders no overflow summary while the stack is within the cap', async () => {
      const store = new StubAdvisoryStore();
      const el = make(store as unknown as AdvisoryStore);
      await burst(el, store, 3);
      expect(el.shadowRoot?.querySelectorAll('.toast').length).toBe(3);
      expect(el.shadowRoot?.querySelector('[data-testid="toast-more"]')).toBeNull();
    });

    it('gives every burst toast — visible or capped-out — its own bounded timer', async () => {
      // The cap bounds how MANY toasts render; the timer bounds how LONG each stays. Round 8 showed
      // the cap alone leaves a REQUIRES_ACK burst on screen indefinitely, so both bounds apply.
      const store = new StubAdvisoryStore();
      const el = make(store as unknown as AdvisoryStore);
      await burst(el, store, 6);
      expect(el.visible.every((t) => t.timeoutId !== null)).toBe(true);
    });
  });

  describe('visible dismiss control (round 7 — dismissal was click-anywhere and undiscoverable)', () => {
    function dismissButton(el: AdvisoryToastHost): HTMLElement | null {
      return el.shadowRoot?.querySelector(
        '.toast jf-button[label="Dismiss notification"]',
      ) as HTMLElement | null;
    }

    it('renders a labelled dismiss button on each toast', async () => {
      const store = new StubAdvisoryStore();
      const el = make(store as unknown as AdvisoryStore);
      store.push({ advisories: [], lastFrameKind: 'snapshot' });
      store.push({
        advisories: [rec('core.dismissable', '2026-05-12T09:00:00Z', false, 'REQUIRES_ACK')],
        lastFrameKind: 'update',
      });
      await el.updateComplete;
      expect(dismissButton(el)).not.toBeNull();
    });

    it('activating it acknowledges + dismisses exactly once (the wrapper stops the toast click)', async () => {
      const store = new StubAdvisoryStore();
      const el = make(store as unknown as AdvisoryStore);
      store.push({ advisories: [], lastFrameKind: 'snapshot' });
      store.push({
        advisories: [rec('core.dismissable', '2026-05-12T09:00:00Z', false, 'REQUIRES_ACK')],
        lastFrameKind: 'update',
      });
      await el.updateComplete;
      const btn = dismissButton(el);
      expect(btn).not.toBeNull();
      // jf-button activates through its nested shadow <button>; drive the same callback the
      // control would, then assert the click it emits does not ALSO reach `.toast`'s handler.
      (btn as unknown as { onActivate: () => void }).onActivate();
      btn!.dispatchEvent(new Event('click', { bubbles: true, composed: true }));
      expect(store.acknowledge).toHaveBeenCalledTimes(1);
      btn!.closest('.toast')!.dispatchEvent(new Event('animationend'));
      expect(el.visible.length).toBe(0);
    });
  });

  // Tempdoc 559 Authority III — local-origin ephemeral records render through the
  // ONE toast host (no second SimpleToast renderer), ungated by frame-kind.
  function localRec(message: string, severity: 'info' | 'success' | 'warning' | 'error'): AdvisoryRecord {
    return {
      key: 'local:1',
      event: {
        classId: 'core.ephemeral',
        id: 'local:1',
        occurredAt: '2026-05-12T09:00:00Z',
        renderHint: 'EPHEMERAL',
        severity,
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        primaryActionKind: null,
        bodyI18nKey: null,
        classExtras: { message },
      },
      acknowledged: false,
      sourceRenderHint: 'EPHEMERAL',
      origin: 'local',
      toast: { message, severity },
    };
  }

  it('559 — renders a local ephemeral record with its message + severity tone, ungated', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // No snapshot frame at all (lastFrameKind stays 'initial') — local records
    // must toast anyway (they are always live).
    store.push({ advisories: [localRec('Bookmarked', 'success')] });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    expect(toast.textContent).toContain('Bookmarked');
    // 559 notice-presentation: the tone is on the shared <jf-system-notice>, not
    // a per-toast CSS class.
    const notice = toast.querySelector('jf-system-notice') as HTMLElement;
    expect(notice).not.toBeNull();
    expect(notice.getAttribute('tone')).toBe('success');
    expect(notice.getAttribute('role')).toBe('status');
  });

  // Tempdoc 613 §14 — a local toast's announcement politeness + dwell are a projection of its declared
  // severity. An ERROR announces assertively (role=alert / aria-live=assertive) AND sticks (no auto-dismiss
  // timer) — an error must not silently auto-vanish (the NN/g "toast is a bad way to show an error" fix).
  it('613 §14 — a local ERROR toast announces assertively and is sticky (no auto-dismiss)', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Operation failed', 'error')] });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const notice = el.shadowRoot?.querySelector('jf-system-notice') as HTMLElement;
    expect(notice.getAttribute('tone')).toBe('error');
    expect(notice.getAttribute('role')).toBe('alert');
    expect(notice.getAttribute('aria-live')).toBe('assertive');
    // Sticky: no auto-dismiss timer (it persists until the user clicks to dismiss).
    expect(el.visible[0]?.timeoutId).toBeNull();
  });

  it('613 §14 — a local WARNING announces assertively but is NOT sticky', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Heads up', 'warning')] });
    await el.updateComplete;
    const notice = el.shadowRoot?.querySelector('jf-system-notice') as HTMLElement;
    expect(notice.getAttribute('role')).toBe('alert');
    expect(el.visible[0]?.timeoutId).not.toBeNull();
  });

  it('613 §14 — a local SUCCESS/INFO toast stays polite + auto-dismisses', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Saved', 'success')] });
    await el.updateComplete;
    const notice = el.shadowRoot?.querySelector('jf-system-notice') as HTMLElement;
    expect(notice.getAttribute('role')).toBe('status');
    expect(notice.getAttribute('aria-live')).toBe('polite');
    // Auto-dismiss timer present (not sticky).
    expect(el.visible[0]?.timeoutId).not.toBeNull();
  });

  it('559 — clicking a local toast drops it (not acknowledge — never persisted)', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Copied', 'info')] });
    await el.updateComplete;
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    toast.click();
    expect(store.dropEphemeral).toHaveBeenCalledWith('local:1');
    expect(store.acknowledge).not.toHaveBeenCalled();
  });

  // Tempdoc 602 R4 — when a superseding emit drops the prior same-classId record
  // from the store, the host must prune its now-orphaned visible toast so the
  // replacement does not briefly stack beside it.
  function navRec(key: string, message: string): AdvisoryRecord {
    return {
      key,
      event: {
        classId: 'core.navigation',
        id: key,
        occurredAt: '2026-05-12T09:00:00Z',
        renderHint: 'EPHEMERAL',
        severity: 'info',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        primaryActionKind: null,
        bodyI18nKey: null,
        classExtras: { message },
      },
      acknowledged: false,
      sourceRenderHint: 'EPHEMERAL',
      origin: 'local',
      toast: { message, classId: 'core.draft-kept', supersede: true },
    };
  }

  it('602 R4 — prunes a superseded local toast that left the snapshot', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [navRec('local:1', 'Navigated to Search')] });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const previousToast = el.shadowRoot!.querySelector('.toast')!;
    // The store superseded local:1: it is gone, local:2 is the replacement.
    store.push({ advisories: [navRec('local:2', 'Navigated to Library')] });
    previousToast.dispatchEvent(new Event('animationend'));
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    expect(toast.textContent).toContain('Navigated to Library');
  });
});

describe('AdvisoryToastHost — tempdoc 655 desktop-notification escalation', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    notifyMocks.isWindowFocused.mockReset().mockResolvedValue(true);
    notifyMocks.sendDesktopNotification.mockReset().mockResolvedValue(undefined);
  });

  it('a new REQUIRES_ACK record while unfocused fires a desktop notification', async () => {
    notifyMocks.isWindowFocused.mockResolvedValue(false);
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    await el.updateComplete;

    store.push({
      advisories: [rec('core.ingest-files', '2026-07-02T09:00:00Z', false, 'REQUIRES_ACK')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;

    await vi.waitFor(() => {
      expect(notifyMocks.sendDesktopNotification).toHaveBeenCalledTimes(1);
    });
  });

  it('a new REQUIRES_ACK record while FOCUSED does not fire a desktop notification', async () => {
    notifyMocks.isWindowFocused.mockResolvedValue(true);
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    await el.updateComplete;

    store.push({
      advisories: [rec('core.ingest-files', '2026-07-02T09:00:00Z', false, 'REQUIRES_ACK')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    // Give the fire-and-forget async path a chance to run before asserting the negative.
    await vi.waitFor(() => {
      expect(notifyMocks.isWindowFocused).toHaveBeenCalled();
    });
    expect(notifyMocks.sendDesktopNotification).not.toHaveBeenCalled();
  });

  it('a new EPHEMERAL/PERSISTED record while unfocused does NOT fire a desktop notification', async () => {
    notifyMocks.isWindowFocused.mockResolvedValue(false);
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    await el.updateComplete;

    store.push({
      advisories: [rec('core.reindex', '2026-07-02T09:00:00Z', false, 'PERSISTED')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;

    // Only REQUIRES_ACK is gated for OS escalation — give any async path a beat, then assert.
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(notifyMocks.isWindowFocused).not.toHaveBeenCalled();
    expect(notifyMocks.sendDesktopNotification).not.toHaveBeenCalled();
  });

  it('a REPLAYED (snapshot) REQUIRES_ACK record never fires a desktop notification', async () => {
    notifyMocks.isWindowFocused.mockResolvedValue(false);
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);

    // A REQUIRES_ACK record arriving as part of the initial LIFECYCLE snapshot (reconnect replay,
    // not a live event) must not re-fire a notification for something already seen.
    store.push({
      advisories: [rec('core.ingest-files', '2026-07-02T09:00:00Z', false, 'REQUIRES_ACK')],
      lastFrameKind: 'snapshot',
    });
    await el.updateComplete;

    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(notifyMocks.isWindowFocused).not.toHaveBeenCalled();
    expect(notifyMocks.sendDesktopNotification).not.toHaveBeenCalled();
  });
});

/**
 * Tempdoc 941 F5 — the toast a first-time user sees for a recoverable health condition.
 *
 * Sandbox round 18 pressed Library → Add Folder during an index rebuild and got a toast whose
 * headline was the class-generic "Recoverable condition", whose only concrete word was the
 * operation id segment `rebuild-index`, and whose timestamp was a raw wall clock
 * ("19:35:18 GMT+0200 (Central European Summer Time)"). Nothing in it said what had happened or
 * what to do.
 *
 * The fixture is the real wire shape: HealthRecoveryProjector.projectCondition puts
 * {conditionId, severity, subject, reason} in classExtras, carries the condition's `recovery` as
 * primaryAction and the event's i18nKey as bodyI18nKey
 * (modules/app-observability/src/main/java/io/justsearch/app/observability/advisory/HealthRecoveryProjector.java:90-103);
 * the index.unavailable ↔ `health-events.index.unavailable.message` ↔ `core.rebuild-index` triple
 * is pinned by modules/ui/src/test/java/io/justsearch/ui/api/HealthEventStreamControllerTest.java:130-134
 * and modules/app-observability/src/test/java/io/justsearch/app/observability/health/ConditionStoreTest.java:181.
 */
describe('AdvisoryToastHost — tempdoc 941 F5 recoverable-condition copy', () => {
  const CONDITION_ID = 'index.unavailable';
  const RECOVERY_OP = 'core.rebuild-index';
  const BODY_KEY = 'health-events.index.unavailable.message';

  /** The authored sentence, read from the backend catalog rather than restated here. */
  function catalogMessage(key: string): string {
    const src = readFileSync(
      resolve(process.cwd(), '../app-api/src/main/resources/messages/health-events.en.properties'),
      'utf8',
    );
    const match = new RegExp(`^${key.replace(/\./g, '\\.')}\\s*=\\s*(.+)$`, 'm').exec(src);
    expect(match, `${key} must exist in health-events.en.properties`).toBeTruthy();
    return match![1]!.trim();
  }

  function recoverableRec(occurredAt: string): AdvisoryRecord {
    return {
      key: `health.recoverable:${CONDITION_ID}`,
      event: {
        classId: 'health.recoverable',
        id: `health.recoverable:${CONDITION_ID}`,
        occurredAt,
        renderHint: 'PERSISTED',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: { target: RECOVERY_OP, defaultArgsJson: '{}' },
        primaryActionKind: null,
        bodyI18nKey: BODY_KEY,
        classExtras: {
          conditionId: CONDITION_ID,
          severity: 'ERROR',
          subject: 'worker',
          reason: 'index.not_healthy',
        },
      },
      acknowledged: false,
      sourceRenderHint: 'PERSISTED',
      origin: 'stream',
    };
  }

  async function showRecoverable(): Promise<AdvisoryToastHost> {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    await el.updateComplete;
    store.push({
      // Three minutes ago: old enough to render a relative unit, recent enough that the assertion
      // does not depend on the day/week buckets.
      advisories: [recoverableRec(new Date(Date.now() - 3 * 60_000).toISOString())],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    return el;
  }

  beforeEach(() => {
    document.body.innerHTML = '';
    __resetUiModeForTest();
    __seedForTest({ [BODY_KEY]: catalogMessage(BODY_KEY) });
  });

  afterEach(() => {
    __resetForTest();
    __resetUiModeForTest();
  });

  it('headlines the condition and states it — no internal id, no class-generic label', async () => {
    const el = await showRecoverable();
    const title = el.shadowRoot?.querySelector('.title') as HTMLElement;
    const titleText = (title.textContent ?? '').trim();

    expect(titleText.length).toBeGreaterThan(0);
    // The three things round 18 actually showed: the class label (true of every advisory in the
    // class, so about nothing) and the two machine ids.
    expect(titleText).not.toContain('Recoverable condition');
    expect(titleText).not.toContain(CONDITION_ID);
    expect(titleText).not.toContain('rebuild-index');

    // The advisory's own authored sentence — the toast rendered no body at all before.
    const body = el.shadowRoot?.querySelector('[data-testid="toast-body"]') as HTMLElement;
    expect(body).not.toBeNull();
    expect((body.textContent ?? '').trim()).toBe(catalogMessage(BODY_KEY));
  });

  it('times the toast in relative words, not a wall clock', async () => {
    const el = await showRecoverable();
    const meta = el.shadowRoot?.querySelector('.meta') as HTMLElement;
    const metaText = (meta.textContent ?? '').trim();

    expect(metaText).toMatch(/(ago|just now)/);
    // `toLocaleTimeString()` produced "19:35:18 GMT+0200 (Central European Summer Time)". Assert
    // against the clock SHAPE as well as the zone token — a happy-dom locale that omits the zone
    // name would still emit the h:mm:ss reading, and that is the part that tells a user nothing.
    expect(metaText).not.toContain('GMT');
    expect(metaText).not.toMatch(/\d{1,2}:\d{2}:\d{2}/);
  });

  it('labels the recovery button from the operation, not the id segment', async () => {
    const el = await showRecoverable();
    const button = el.shadowRoot?.querySelector('.action-btn') as HTMLElement;
    const label = (button.textContent ?? '').trim();

    // `action.target.split('.').pop()` is where the screenshot's `rebuild-index` came from.
    expect(label).not.toBe('rebuild-index');
    expect(label).not.toContain(RECOVERY_OP);
    // present({kind:'operation'}) humanizes when the operation catalog has not booted (the state a
    // unit test is in); with a booted catalog it resolves the authored label instead.
    expect(label).toBe('Rebuild Index');
  });

  // 941 F4 was an unsubstituted `{errorClass}` reaching the Health surface as literal copy. The
  // toast renders the same catalog templates from the same keys, so it gets the same substitution
  // authority: classExtras ARE the parameters (HealthRecoveryProjector.projectLifecycle copies the
  // emitter's attribute map into extras). No advisory carries a parameterized message today — this
  // pins the path shut before one does.
  it('substitutes a parameterized body from classExtras, and declines rather than showing a brace', async () => {
    __seedForTest({ [BODY_KEY]: 'The indexer stopped while reading {path}.' });
    const el = await showRecoverable();
    const body = el.shadowRoot?.querySelector('[data-testid="toast-body"]') as HTMLElement;
    // `path` is not among the condition projection's extras (conditionId/severity/subject/reason),
    // so the sentence is declined outright — the toast shows no body rather than a raw `{path}`.
    expect(body).toBeNull();
    expect(el.shadowRoot?.textContent ?? '').not.toContain('{path}');

    document.body.innerHTML = '';
    __seedForTest({ [BODY_KEY]: 'The indexer stopped while reading {subject}.' });
    const el2 = await showRecoverable();
    const body2 = el2.shadowRoot?.querySelector('[data-testid="toast-body"]') as HTMLElement;
    expect((body2.textContent ?? '').trim()).toBe('The indexer stopped while reading worker.');
  });

  it('keeps the ids — behind Detailed mode, not in the headline', async () => {
    const el = await showRecoverable();
    expect(el.shadowRoot?.querySelector('[data-testid="toast-detail-ids"]')).toBeNull();

    setUiMode('advanced');
    await el.updateComplete;

    const detail = el.shadowRoot?.querySelector('[data-testid="toast-detail-ids"]') as HTMLElement;
    expect(detail, 'Detailed mode must still expose the ids for diagnosis').not.toBeNull();
    const detailText = detail.textContent ?? '';
    expect(detailText).toContain(CONDITION_ID);
    expect(detailText).toContain(RECOVERY_OP);
  });
});
