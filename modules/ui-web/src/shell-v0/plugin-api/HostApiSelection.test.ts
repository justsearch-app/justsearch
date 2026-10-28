// @vitest-environment happy-dom

/**
 * Tempdoc 508-followup §γ2 — host.selection sub-interface tests.
 *
 * Verifies:
 *   - UNTRUSTED tier exposes only `current` + `subscribe` (no
 *     `setSelection` / `clearSelection`).
 *   - TRUSTED+ / CORE tier exposes all four methods.
 *   - SelectionSnapshot conversion strips internal kind/capability
 *     shape (kind: string, capabilities: string[]).
 *   - setSelection wraps plugin input into the internal SelectionItem
 *     union via the plugin's own pluginId.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { createHostApi } from './HostApiImpl.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest } from '../state/aiStateStore.js';
import { takePendingSelection, } from '../utils/compose.js';
import {
  __resetSelectionForTest,
  getSelection,
  setSingleSelection,
  DEFAULT_CAPABILITIES_BY_KIND,
} from '../state/selectionState.js';

const deps = {
  apiBase: '',
  registerSurfacePort: () => {},
};

beforeEach(() => {
  __resetSelectionForTest();
});

it('C5 plugin compose captures its chosen passage before navigation clears selection', async () => {
  __feedForTest({ inference: { mode: 'online', available: true } as never }); __feedContactForTest();
  const host = createHostApi('cutover-proof', 'CORE', deps);
  const selected = { kind: 'browse-node' as const, path: 'chosen.md', nodeKind: 'file' as const,
    capabilities: DEFAULT_CAPABILITIES_BY_KIND['browse-node'] };
  setSingleSelection(selected, 'core.browse-surface');
  try {
    expect(host.selection.compose!({ operation: 'core.ask', userPrompt: 'Chosen question' })).toBe(true);
    expect(takePendingSelection()).toEqual({ kind: 'item', itemKind: 'browse-node', itemId: 'chosen.md' });
} finally {
    takePendingSelection(); __resetAiStateForTest();
    await new Promise<void>(resolve => setTimeout(resolve, 0));
  }
});

describe('host.selection — UNTRUSTED tier (read-only)', () => {
  it('exposes current + subscribe', () => {
    const host = createHostApi('p1', 'UNTRUSTED_PLUGIN', deps);
    expect(typeof host.selection.current).toBe('function');
    expect(typeof host.selection.subscribe).toBe('function');
  });

  it('omits setSelection + clearSelection', () => {
    const host = createHostApi('p1', 'UNTRUSTED_PLUGIN', deps);
    expect(host.selection.setSelection).toBeUndefined();
    expect(host.selection.clearSelection).toBeUndefined();
  });

  it('current() returns null when no items are selected', () => {
    const host = createHostApi('p1', 'UNTRUSTED_PLUGIN', deps);
    expect(host.selection.current()).toBeNull();
  });
});

describe('host.selection — CORE tier (read+write)', () => {
  it('exposes all four methods', () => {
    const host = createHostApi('p1', 'CORE', deps);
    expect(typeof host.selection.current).toBe('function');
    expect(typeof host.selection.subscribe).toBe('function');
    expect(typeof host.selection.setSelection).toBe('function');
    expect(typeof host.selection.clearSelection).toBe('function');
  });

  it('setSelection wraps plugin items with pluginId', () => {
    const host = createHostApi('my-plugin', 'CORE', deps);
    host.selection.setSelection!({
      items: [
        { kind: 'plugin-item', itemId: 'i1', label: 'Item 1' },
      ],
      primaryIndex: 0,
    });
    const internal = getSelection();
    expect(internal.items).toHaveLength(1);
    const item = internal.items[0]!;
    expect(item.kind).toBe('plugin-item');
    if (item.kind === 'plugin-item') {
      expect(item.pluginId).toBe('my-plugin');
      expect(item.itemId).toBe('i1');
      expect(item.label).toBe('Item 1');
    }
  });

  it('clearSelection drops the current selection', () => {
    const host = createHostApi('my-plugin', 'CORE', deps);
    host.selection.setSelection!({
      items: [{ kind: 'plugin-item', itemId: 'a', label: 'A' }],
    });
    expect(getSelection().items).toHaveLength(1);
    host.selection.clearSelection!();
    expect(getSelection().items).toHaveLength(0);
  });
});

describe('host.selection — snapshot conversion', () => {
  it('strips internal Set into a string[]', () => {
    const host = createHostApi('p1', 'CORE', deps);
    setSingleSelection(
      {
        kind: 'search-hit',
        hitId: 'h1',
        title: 'doc.txt',
        path: '/tmp/doc.txt',
        capabilities: DEFAULT_CAPABILITIES_BY_KIND['search-hit'],
      },
      'core.search-surface',
    );
    const snap = host.selection.current();
    expect(snap).not.toBeNull();
    const item = snap!.items[0]!;
    expect(item.kind).toBe('search-hit');
    expect(item.label).toBe('doc.txt');
    expect(item.id).toBe('h1');
    expect(item.path).toBe('/tmp/doc.txt');
    expect(Array.isArray(item.capabilities)).toBe(true);
    expect(item.capabilities).toContain('open');
  });

  it('subscribe delivers snapshots on change', () => {
    const host = createHostApi('p1', 'CORE', deps);
    const received: Array<unknown> = [];
    const off = host.selection.subscribe((s) => received.push(s));
    // Initial delivery (subscribeSelection fires on subscribe).
    expect(received).toHaveLength(1);
    expect(received[0]).toBeNull();
    host.selection.setSelection!({
      items: [{ kind: 'plugin-item', itemId: 'x', label: 'X' }],
    });
    expect(received).toHaveLength(2);
    expect(received[1]).not.toBeNull();
    off();
  });

  it('UNTRUSTED tier can read but not mutate', () => {
    const host = createHostApi('p1', 'UNTRUSTED_PLUGIN', deps);
    // CORE acts as the writer; UNTRUSTED observes.
    const writer = createHostApi('writer', 'CORE', deps);
    writer.selection.setSelection!({
      items: [{ kind: 'plugin-item', itemId: 'r', label: 'Read' }],
    });
    const snap = host.selection.current();
    expect(snap).not.toBeNull();
    expect(snap!.items[0]!.label).toBe('Read');
  });
});
