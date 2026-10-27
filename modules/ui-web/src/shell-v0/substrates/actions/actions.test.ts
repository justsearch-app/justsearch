// @vitest-environment happy-dom

/**
 * Action substrate unit tests — Tempdoc 543 §3.C.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  registerAction,
  unregisterAction,
  listActions,
  invokeAction,
  invokeAndApply,
  applyEffect,
  dispatchEffectToChrome,
  projectOperationsToActions,
  registerShellActions,
  getAllActions,
  getAction,
  subscribeActions,
  registerCanonicalCoreActions,
  __resetActionsForTest,
  type Action,
} from './index.js';
import { CORE_PROVENANCE, makePluginProvenance } from '../../primitives/provenance.js';
import {
  __resetShellContextForTest,
  updateShellContext,
} from '../../state/shellContextState.js';
import { __resetJournalForTest, listJournal } from '../effects/index.js';
import type { Effect } from '../effect.js';

beforeEach(() => {
  __resetActionsForTest();
  __resetShellContextForTest();
  __resetJournalForTest();
  // Note: __resetActionsForTest() clears the canonical Action that
  // module-load installed. Tests asserting its presence must call
  // registerCanonicalCoreActions() explicitly (see the §20.7 A4
  // test block below).
});

const STUB_ACTION = (
  overrides: Partial<Action> = {},
): Action => ({
  id: 'test.action.demo',
  title: 'Demo Action',
  handler: () => ({ kind: 'noop' as const }),
  provenance: CORE_PROVENANCE,
  ...overrides,
});

describe('Action registration (§3.C)', () => {
  it('register/get round-trip', () => {
    const a = STUB_ACTION();
    registerAction(a);
    expect(getAction('test.action.demo')).toBe(a);
  });

  it('throws on duplicate id', () => {
    registerAction(STUB_ACTION());
    expect(() => registerAction(STUB_ACTION())).toThrow(/already registered/);
  });

  it('unregister removes the entry', () => {
    registerAction(STUB_ACTION());
    unregisterAction('test.action.demo');
    expect(getAction('test.action.demo')).toBeUndefined();
  });

  it('subscribeActions fires on register + unregister', () => {
    const listener = vi.fn();
    subscribeActions(listener);
    registerAction(STUB_ACTION());
    unregisterAction('test.action.demo');
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('getAllActions returns alphabetical by id', () => {
    registerAction(STUB_ACTION({ id: 'b.action' }));
    registerAction(STUB_ACTION({ id: 'a.action' }));
    const ids = getAllActions().map((x) => x.id);
    expect(ids).toEqual(['a.action', 'b.action']);
  });
});

describe('operation projection', () => {
  it('does not project model-terms install operations into zero-argument actions', () => {
    projectOperationsToActions([
      { id: 'core.reindex', presentation: { labelKey: 'ops.core.reindex.label' } },
      { id: 'core.start-ai-install', presentation: { labelKey: 'ops.core.start-ai-install.label' } },
      { id: 'core.repair-ai-install', presentation: { labelKey: 'ops.core.repair-ai-install.label' } },
    ]);

    expect(getAction('core.action.op.core.reindex')).toBeDefined();
    expect(getAction('core.action.op.core.start-ai-install')).toBeUndefined();
    expect(getAction('core.action.op.core.repair-ai-install')).toBeUndefined();
  });
});

describe('shell actions — AI install palette reachability (727 F-1)', () => {
  it('923 F-22 — registers one Search destination and no duplicate Go to Chat alias', async () => {
    const navigate = vi.fn();
    registerShellActions({
      navigate,
      toggleInspector: () => {},
      togglePalette: () => {},
      focusComposer: () => {},
    });

    const search = getAction('core.action.shell.go-to-search');
    expect(search?.title).toBe('Go to Search');
    expect(getAction('core.action.shell.go-to-chat')).toBeUndefined();
    expect(await invokeAction('core.action.shell.go-to-search')).toEqual({
      kind: 'navigate',
      to: 'core.search-v3-surface',
    });
    expect(navigate).toHaveBeenCalledWith('core.search-v3-surface');
  });

  // On pre-fix code, `core.start-ai-install` / `core.repair-ai-install` were excluded from
  // BOTH the zero-arg operation projection (correctly, per the test above — a bare invoke can
  // never supply the required `acceptTerms`) AND any other palette registration, so searching
  // "install" in the palette surfaced only `core.cancel-ai-install`'s zero-arg action. These
  // assertions fail on pre-fix code because `getAction(...)` returns undefined for both ids —
  // registerShellActions never registered them.
  it('registers navigate-only palette entries for Start/Repair AI Install', () => {
    const navigate = vi.fn();
    registerShellActions({
      navigate,
      toggleInspector: () => {},
      togglePalette: () => {},
      focusComposer: () => {},
    });

    const start = getAction('core.action.shell.go-to-brain-install');
    const repair = getAction('core.action.shell.go-to-brain-repair');
    expect(start).toBeDefined();
    expect(repair).toBeDefined();
    // Round-14 F3 — the labels state what the entries DO. They navigate; the consent/terms ceremony
    // is BrainSurface's, and a palette entry must not promise to have started an install it did not
    // start (nor bypass the dialog to make the old label true).
    expect(start!.title).toBe('Go to Brain — Install AI');
    expect(repair!.title).toBe('Go to Brain — Repair AI Install');
    expect(start!.title).not.toBe('Start AI Install');
    // Both are global (no appliesTo) so they surface in the flat palette pool, same as
    // core.action.op.core.cancel-ai-install would.
    expect(start!.appliesTo).toBeUndefined();
    expect(repair!.appliesTo).toBeUndefined();
  });

  it('invoking the Start/Repair entries navigates to the Brain surface (not a bare op-invoke)', async () => {
    const navigate = vi.fn();
    registerShellActions({
      navigate,
      toggleInspector: () => {},
      togglePalette: () => {},
      focusComposer: () => {},
    });

    const startEffect = await invokeAction('core.action.shell.go-to-brain-install');
    expect(startEffect).toEqual({ kind: 'navigate', to: 'core.brain-surface' });
    expect(navigate).toHaveBeenCalledWith('core.brain-surface');

    navigate.mockClear();
    const repairEffect = await invokeAction('core.action.shell.go-to-brain-repair');
    expect(repairEffect).toEqual({ kind: 'navigate', to: 'core.brain-surface' });
    expect(navigate).toHaveBeenCalledWith('core.brain-surface');
  });

  it('round-14 F2 — a plain "Go to Brain" navigation entry exists (the documented palette route)', async () => {
    // Pre-fix, the only palette routes to Brain were the two install-flavoured entries above, so the
    // reach the coverage register documents for the Brain surface (and for Memory, which is a TAB on
    // it) — command palette "Go to Brain" — did not exist.
    const navigate = vi.fn();
    registerShellActions({
      navigate,
      toggleInspector: () => {},
      togglePalette: () => {},
      focusComposer: () => {},
    });

    const brain = getAction('core.action.shell.go-to-brain');
    expect(brain).toBeDefined();
    expect(brain!.title).toBe('Go to Brain');
    expect(brain!.appliesTo).toBeUndefined(); // global — surfaces in the flat palette pool
    expect(await invokeAction('core.action.shell.go-to-brain')).toEqual({
      kind: 'navigate',
      to: 'core.brain-surface',
    });
    expect(navigate).toHaveBeenCalledWith('core.brain-surface');
  });

  it('857 D3 (drafted as 854) — a plain "Go to Activity" navigation entry exists, mirroring go-to-health', async () => {
    // Health had a palette entry (go-to-health); Activity had none. This is the one reachability
    // delta the audience-honesty flip actually needed (857 §6.4) — the surface was already USER-
    // reachable via the System hub's tabs, but only Activity had no named palette route to it.
    const navigate = vi.fn();
    registerShellActions({
      navigate,
      toggleInspector: () => {},
      togglePalette: () => {},
      focusComposer: () => {},
    });

    const activity = getAction('core.action.shell.go-to-activity');
    expect(activity).toBeDefined();
    expect(activity!.title).toBe('Go to Activity');
    expect(activity!.appliesTo).toBeUndefined(); // global — surfaces in the flat palette pool
    expect(await invokeAction('core.action.shell.go-to-activity')).toEqual({
      kind: 'navigate',
      to: 'core.activity-surface', // the TARGET, not merely that registration happened
    });
    expect(navigate).toHaveBeenCalledWith('core.activity-surface');
  });
});

describe('listActions filtering (§3.C)', () => {
  it('global Action (no appliesTo) appears without addressable', () => {
    registerAction(STUB_ACTION({ id: 'global.a' }));
    const out = listActions({ addressable: null });
    expect(out.map((a) => a.id)).toEqual(['global.a']);
  });

  it('addressable-scoped Action requires matching kind', () => {
    registerAction(
      STUB_ACTION({
        id: 'cite.a',
        appliesTo: ['search-result'],
      }),
    );
    // Without addressable → filtered out.
    expect(listActions({}).map((a) => a.id)).toEqual([]);
    // With matching addressable → included.
    expect(
      listActions({
        addressable: { kind: 'search-result', id: 'r1', payload: {} },
      }).map((a) => a.id),
    ).toEqual(['cite.a']);
    // With non-matching addressable → filtered out.
    expect(
      listActions({
        addressable: { kind: 'citation', id: 'c1', payload: {} },
      }).map((a) => a.id),
    ).toEqual([]);
  });

  it('when predicate gates on scope', () => {
    updateShellContext({ audience: 'DEVELOPER' });
    registerAction(
      STUB_ACTION({ id: 'dev.only', when: 'audience == "DEVELOPER"' }),
    );
    expect(listActions({}).map((a) => a.id)).toEqual(['dev.only']);
    updateShellContext({ audience: 'USER' });
    expect(listActions({}).map((a) => a.id)).toEqual([]);
  });

  it('enabled(addressable) predicate filters per-payload', () => {
    registerAction(
      STUB_ACTION({
        id: 'gated',
        appliesTo: ['search-result'],
        enabled: (a) =>
          a !== null &&
          typeof a.payload === 'object' &&
          a.payload !== null &&
          (a.payload as { ok?: boolean }).ok === true,
      }),
    );
    expect(
      listActions({
        addressable: { kind: 'search-result', id: 'r', payload: { ok: true } },
      }).map((a) => a.id),
    ).toEqual(['gated']);
    expect(
      listActions({
        addressable: { kind: 'search-result', id: 'r', payload: { ok: false } },
      }).map((a) => a.id),
    ).toEqual([]);
  });

  it('throwing enabled-fn → action filtered out (defensive)', () => {
    registerAction(
      STUB_ACTION({
        id: 'broken',
        enabled: () => {
          throw new Error('boom');
        },
      }),
    );
    expect(listActions({}).map((a) => a.id)).toEqual([]);
  });

  it('sort: priority desc, title asc within same priority', () => {
    registerAction(STUB_ACTION({ id: 'a', title: 'Beta', priority: 10 }));
    registerAction(STUB_ACTION({ id: 'b', title: 'Alpha', priority: 10 }));
    registerAction(STUB_ACTION({ id: 'c', title: 'Gamma', priority: 5 }));
    expect(listActions({}).map((a) => a.id)).toEqual(['b', 'a', 'c']);
  });
});

describe('invokeAction (§3.C)', () => {
  it('runs the handler and returns its Effect', async () => {
    const eff: Effect = { kind: 'toast', message: 'hi' };
    registerAction(
      STUB_ACTION({ handler: () => eff }),
    );
    expect(await invokeAction('test.action.demo')).toBe(eff);
  });

  it('throws on unknown id', async () => {
    await expect(invokeAction('nope')).rejects.toThrow(/Unknown Action/);
  });

  it('passes args and addressable to handler', async () => {
    const handler = vi.fn(() => ({ kind: 'noop' as const }));
    registerAction(STUB_ACTION({ handler }));
    const addr = { kind: 'search-result' as const, id: 'r', payload: { x: 1 } };
    await invokeAction('test.action.demo', { y: 2 }, addr);
    expect(handler).toHaveBeenCalledWith({ y: 2 }, addr);
  });
});

describe('applyEffect + Effect Journal (§13.2.2 wire)', () => {
  it('writes a journal entry per Effect with provided provenance', () => {
    applyEffect({ kind: 'noop' }, CORE_PROVENANCE);
    const entries = listJournal();
    expect(entries).toHaveLength(1);
    expect(entries[0]!.effect.kind).toBe('noop');
    expect(entries[0]!.invokedBy).toBe(CORE_PROVENANCE);
  });

  it('dispatches toast Effect as jf-advisory-ephemeral', () => {
    const listener = vi.fn();
    document.addEventListener('jf-advisory-ephemeral', listener as EventListener);
    try {
      applyEffect({ kind: 'toast', message: 'hi', severity: 'warning' });
      expect(listener).toHaveBeenCalledTimes(1);
      const ev = listener.mock.calls[0]![0] as CustomEvent;
      // Tempdoc 613 §5.2 — emitEphemeralToast stamps the resolved class + policy: a toast Effect omits
      // classId, so it defaults to `core.ephemeral` (supersede:false from that class's policy); the
      // explicit `warning` severity is kept.
      expect(ev.detail).toEqual({
        message: 'hi',
        severity: 'warning',
        classId: 'core.ephemeral',
        supersede: false,
      });
    } finally {
      document.removeEventListener('jf-advisory-ephemeral', listener as EventListener);
    }
  });

  it('dispatches navigate Effect as window.location.hash assignment', () => {
    applyEffect({ kind: 'navigate', to: '#demo-nav-a' });
    expect(window.location.hash).toBe('#demo-nav-a');
  });

  it('dispatches open-pane / close-pane Effects as events', () => {
    const openL = vi.fn();
    const closeL = vi.fn();
    document.addEventListener('jf-open-pane', openL as EventListener);
    document.addEventListener('jf-close-pane', closeL as EventListener);
    try {
      applyEffect({ kind: 'open-pane', paneId: 'inspector' });
      applyEffect({ kind: 'close-pane', paneId: 'inspector' });
      expect(openL).toHaveBeenCalledTimes(1);
      expect(closeL).toHaveBeenCalledTimes(1);
    } finally {
      document.removeEventListener('jf-open-pane', openL as EventListener);
      document.removeEventListener('jf-close-pane', closeL as EventListener);
    }
  });

  it('569 §14 — dispatches the presentation + search intent Effects as jf-* events', () => {
    const events = [
      'jf-set-appearance',
      'jf-set-ui-mode',
      'jf-apply-presentation',
      'jf-save-settings',
      'jf-set-search-query',
      'jf-set-search-filter',
    ] as const;
    const spies = events.map((name) => {
      const fn = vi.fn();
      document.addEventListener(name, fn as EventListener);
      return { name, fn };
    });
    try {
      applyEffect({ kind: 'set-appearance', theme: 'dark', highContrast: true });
      applyEffect({ kind: 'set-ui-mode', mode: 'advanced' });
      applyEffect({ kind: 'apply-presentation', presentationId: 'builtin.settings' });
      applyEffect({ kind: 'save-settings', settings: { ui: { theme: 'dark' } } });
      applyEffect({ kind: 'set-search-query', query: 'lucene' });
      applyEffect({ kind: 'set-search-filter', fromMs: 1000, toMs: 2000 });
      for (const { name, fn } of spies) {
        expect(fn, name).toHaveBeenCalledTimes(1);
      }
      // Lock the listener-contract detail shapes the authorities depend on.
      expect((spies[0]!.fn.mock.calls[0]![0] as CustomEvent).detail).toEqual({
        theme: 'dark',
        highContrast: true,
      });
      expect((spies[4]!.fn.mock.calls[0]![0] as CustomEvent).detail).toEqual({ query: 'lucene' });
      expect((spies[5]!.fn.mock.calls[0]![0] as CustomEvent).detail).toEqual({
        fromMs: 1000,
        toMs: 2000,
      });
    } finally {
      for (const { name, fn } of spies) {
        document.removeEventListener(name, fn as EventListener);
      }
    }
  });

  it('§32 S2 — invoke-operation carries originator in detail (trust-bridge contract)', () => {
    // The Shell jf-invoke-operation listener reads detail.originator to
    // pick the backend TransportTag (agent → LLM_EMISSION). This test locks
    // the contract the bridge depends on: applyEffect must propagate the
    // originator into the dispatched event detail.
    const listener = vi.fn();
    document.addEventListener('jf-invoke-operation', listener as EventListener);
    try {
      applyEffect(
        { kind: 'invoke-operation', operationId: 'core.bulk-reindex' },
        CORE_PROVENANCE,
        'agent',
      );
      expect(listener).toHaveBeenCalledTimes(1);
      const ev = listener.mock.calls[0]![0] as CustomEvent;
      expect(ev.detail).toMatchObject({
        operationId: 'core.bulk-reindex',
        originator: 'agent',
      });
    } finally {
      document.removeEventListener(
        'jf-invoke-operation',
        listener as EventListener,
      );
    }
  });

  it('§32 U2 — undo-operation Effect dispatches jf-undo-operation', () => {
    const l = vi.fn();
    document.addEventListener('jf-undo-operation', l as EventListener);
    try {
      applyEffect({
        kind: 'undo-operation',
        operationId: 'core.file-operations',
        executionId: 'exec-9',
      });
      expect(l).toHaveBeenCalledTimes(1);
      expect((l.mock.calls[0]![0] as CustomEvent).detail).toMatchObject({
        operationId: 'core.file-operations',
        executionId: 'exec-9',
      });
    } finally {
      document.removeEventListener('jf-undo-operation', l as EventListener);
    }
  });

  it('§32 U2 — invoke-operation detail carries journalEntryId (executionId capture)', () => {
    const l = vi.fn();
    document.addEventListener('jf-invoke-operation', l as EventListener);
    try {
      applyEffect(
        { kind: 'invoke-operation', operationId: 'core.index-gc' },
        CORE_PROVENANCE,
        'agent',
      );
      const detail = (l.mock.calls[0]![0] as CustomEvent).detail;
      expect(typeof detail.journalEntryId).toBe('number');
    } finally {
      document.removeEventListener('jf-invoke-operation', l as EventListener);
    }
  });
});

describe('invokeAndApply (§3.C end-to-end)', () => {
  it('Action → handler → Effect → applyEffect → journal', async () => {
    const provenance = makePluginProvenance('acme', '1.0');
    registerAction({
      id: 'acme.demo',
      title: 'Demo',
      handler: () => ({ kind: 'toast', message: 'from-acme' }),
      provenance,
    });
    await invokeAndApply('acme.demo');
    const entries = listJournal();
    expect(entries).toHaveLength(1);
    expect(entries[0]!.invokedBy).toBe(provenance);
    expect(entries[0]!.effect.kind).toBe('toast');
  });
});

describe('registerCanonicalCoreActions (§20.7 A4 — survive test reset)', () => {
  it('module-load registers core.action.cite-selection (but reset clears)', () => {
    // Direct probe: after __resetActionsForTest() in beforeEach, the
    // canonical Action is cleared. This confirms the reset behavior.
    expect(getAction('core.action.cite-selection')).toBeUndefined();
  });

  it('registerCanonicalCoreActions re-installs the canonical Action', () => {
    registerCanonicalCoreActions();
    const a = getAction('core.action.cite-selection');
    expect(a).toBeDefined();
    expect(a!.title).toBe('Cite Selection');
    expect(a!.appliesTo).toEqual([
      'search-result',
      'citation',
      'document-passage',
    ]);
    expect(a!.provenance.tier).toBe('CORE');
  });

  it('registerCanonicalCoreActions is idempotent (second call no-op)', () => {
    registerCanonicalCoreActions();
    expect(() => registerCanonicalCoreActions()).not.toThrow();
    expect(getAction('core.action.cite-selection')).toBeDefined();
  });

  // 543-fwd #1 (redo) — dispatchEffectToChrome dispatches side-effects but does
  // NOT append to the Effect Journal (applyEffect = recordEffect + this).
  describe('dispatchEffectToChrome (543-fwd #1, journal-suppressed)', () => {
    it('dispatches the chrome event without writing a journal entry', () => {
      const toasts: string[] = [];
      const onToast = ((e: Event) => toasts.push((e as CustomEvent).detail.message)) as EventListener;
      document.addEventListener('jf-advisory-ephemeral', onToast);
      try {
        expect(listJournal()).toHaveLength(0);
        const handled = dispatchEffectToChrome({ kind: 'toast', message: 'hi' });
        expect(handled).toBe(true);
        expect(toasts).toEqual(['hi']); // side-effect happened
        expect(listJournal()).toHaveLength(0); // but no journal append
      } finally {
        document.removeEventListener('jf-advisory-ephemeral', onToast);
      }
    });

    it('contrast: applyEffect DOES append to the journal', () => {
      applyEffect({ kind: 'toast', message: 'recorded' });
      expect(listJournal()).toHaveLength(1);
    });

    it('omits journalEntryId from invoke-operation when not provided', () => {
      let detail: Record<string, unknown> | null = null;
      const onInvoke = ((e: Event) => { detail = (e as CustomEvent).detail; }) as EventListener;
      document.addEventListener('jf-invoke-operation', onInvoke);
      try {
        dispatchEffectToChrome({ kind: 'invoke-operation', operationId: 'core.search-index' });
        expect(detail).not.toBeNull();
        expect(detail!).not.toHaveProperty('journalEntryId');
      } finally {
        document.removeEventListener('jf-invoke-operation', onInvoke);
      }
    });
  });
});
