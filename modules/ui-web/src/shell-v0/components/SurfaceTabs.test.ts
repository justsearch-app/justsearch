// @vitest-environment happy-dom

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import './SurfaceTabs.js';
import type { SurfaceTabs, SurfaceTabItem } from './SurfaceTabs.js';
import { html, type TemplateResult } from 'lit';
import { __seedForTest, __resetForTest } from '../../api/registry/SurfaceCatalogClient.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';

function body(text: string): () => TemplateResult {
  return () => html`<div class="probe">${text}</div>`;
}

const ITEMS: SurfaceTabItem[] = [
  { id: 'folders', label: 'Folders', altitude: 'PRODUCT', content: body('FOLDERS') },
  { id: 'core.logs-surface', label: 'Logs', altitude: 'DIAGNOSTIC', content: body('LOGS') },
  { id: 'core.activity-surface', label: 'Activity', altitude: 'TRUST', content: body('ACTIVITY') },
];

async function mount(items: SurfaceTabItem[], activeId?: string): Promise<SurfaceTabs> {
  const el = document.createElement('jf-surface-tabs') as SurfaceTabs;
  el.items = items;
  if (activeId) el.activeId = activeId;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function tabButtons(el: SurfaceTabs): HTMLButtonElement[] {
  return [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button[role="tab"]')];
}

describe('jf-surface-tabs', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders one tab per item in declared order', async () => {
    const el = await mount(ITEMS);
    expect(
      tabButtons(el).map((b) => b.textContent?.replace(/\s+/g, ' ').trim()),
    ).toEqual(['Folders', 'Logs', 'Activity Audit']);
  });

  it('projects window actions beside the tablist without adding them to tab keyboard navigation', async () => {
    const el = await mount(ITEMS);
    const action = document.createElement('button'); action.slot = 'actions'; action.textContent = 'Add folder';
    el.append(action); await el.updateComplete;
    const slot = el.shadowRoot!.querySelector<HTMLSlotElement>('slot[name="actions"]')!;
    expect(slot.assignedElements()).toEqual([action]);
    expect(slot.closest('[role="tablist"]')).toBeNull();
    tabButtons(el)[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await el.updateComplete;
    expect(el.shadowRoot!.activeElement).toBe(tabButtons(el)[1]);
    expect(action.getAttribute('role')).toBeNull();
  });

  it('shows the first item active by default and renders its content', async () => {
    const el = await mount(ITEMS);
    const first = tabButtons(el)[0]!;
    expect(first.getAttribute('aria-selected')).toBe('true');
    expect(el.shadowRoot!.querySelector('.probe')?.textContent).toBe('FOLDERS');
  });

  it('honors an explicit active-id', async () => {
    const el = await mount(ITEMS, 'core.logs-surface');
    expect(el.shadowRoot!.querySelector('.probe')?.textContent).toBe('LOGS');
  });

  it('switches tab on click and emits tab-change', async () => {
    const el = await mount(ITEMS);
    let changed: string | null = null;
    el.addEventListener('tab-change', (e) => {
      changed = (e as CustomEvent<{ id: string }>).detail.id;
    });
    tabButtons(el)[1]!.click();
    await el.updateComplete;
    expect(changed).toBe('core.logs-surface');
    expect(el.shadowRoot!.querySelector('.probe')?.textContent).toBe('LOGS');
  });

  it('preserves per-member altitude framing — TRUST keeps an Audit marker + data-altitude (578 §9.4.B)', async () => {
    const el = await mount(ITEMS);
    const btns = tabButtons(el);
    expect(btns.map((b) => b.getAttribute('data-altitude'))).toEqual([
      'PRODUCT',
      'DIAGNOSTIC',
      'TRUST',
    ]);
    const trustBtn = btns[2]!;
    expect(trustBtn.querySelector('.alt-marker')?.textContent).toBe('Audit');
    expect(trustBtn.getAttribute('aria-label')).toBe('Activity — audit');
    // non-TRUST tabs carry no audit marker
    expect(btns[0]!.querySelector('.alt-marker')).toBeNull();
    expect(btns[1]!.querySelector('.alt-marker')).toBeNull();
  });

  it('navigates with arrow keys (roving tabindex)', async () => {
    const el = await mount(ITEMS);
    const tablist = el.shadowRoot!.querySelector('[role="tablist"]')!;
    tablist.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await el.updateComplete;
    expect(tabButtons(el)[1]!.getAttribute('aria-selected')).toBe('true');
    // wraps around at the end
    tablist.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    tablist.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await el.updateComplete;
    expect(tabButtons(el)[2]!.getAttribute('aria-selected')).toBe('true');
  });

  it('emits nothing for an empty items list', async () => {
    const el = await mount([]);
    expect(tabButtons(el)).toHaveLength(0);
  });
});

// ── surfaceId member-mount path (the common case: a tab mounts a registered member surface) ──
// The thunk-based tests above cover the host-content branches; this block covers the
// `getSurface` → `mountSurface` branch that members actually use in production.

const MEMBER_TAG = 'jf-surface-tabs-test-member';

class TestMemberElement extends HTMLElement {
  constructor() {
    super();
    this.textContent = 'MEMBER-MOUNTED';
  }
}

function memberSurface(id: string, mountTag: string): Surface {
  return {
    id,
    presentation: {
      labelKey: `registry-surface.${id}.label`,
      descriptionKey: `registry-surface.${id}.description`,
      iconHint: null,
      category: null,
    },
    audience: 'USER',
    placement: 'DEEPLINK',
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    mountTag,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  };
}

function catalogOf(...entries: Surface[]): SurfaceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries,
  };
}

describe('jf-surface-tabs — surfaceId member mount', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
    if (!customElements.get(MEMBER_TAG)) {
      customElements.define(MEMBER_TAG, TestMemberElement);
    }
  });
  afterEach(() => __resetForTest());

  it('mounts the registered member element for a surfaceId item', async () => {
    __seedForTest(catalogOf(memberSurface('core.test-member-surface', MEMBER_TAG)));
    const el = await mount([
      {
        id: 'core.test-member-surface',
        label: 'Member',
        altitude: 'PRODUCT',
        surfaceId: 'core.test-member-surface',
      },
    ]);
    const mounted = el.shadowRoot!.querySelector('.panel ' + MEMBER_TAG);
    expect(mounted).not.toBeNull();
    expect(mounted?.textContent).toBe('MEMBER-MOUNTED');
  });

  it('renders an unknown-surface empty state for an unresolvable surfaceId', async () => {
    __seedForTest(catalogOf()); // empty catalog — the id resolves to nothing
    const el = await mount([
      { id: 'core.ghost', label: 'Ghost', altitude: 'PRODUCT', surfaceId: 'core.ghost' },
    ]);
    const empty = el.shadowRoot!.querySelector('.empty');
    expect(empty?.textContent).toContain('Unknown surface: core.ghost');
  });

  // 578 host-api fix — the host's PluginHostApi must reach the mounted member (the factory sets
  // el.host_ from opts.host_). Without this, members that call this.host_.data.* throw "reading 'data'".
  it('threads host_ to the mounted member element', async () => {
    __seedForTest(catalogOf(memberSurface('core.test-member-surface', MEMBER_TAG)));
    const sentinelHost = { data: { fetch: () => Promise.resolve() } };
    const el = document.createElement('jf-surface-tabs') as SurfaceTabs;
    el.items = [
      { id: 'core.test-member-surface', label: 'Member', surfaceId: 'core.test-member-surface' },
    ];
    // Cast through `unknown` (not `any`) to assign a minimal sentinel host without satisfying the
    // full PluginHostApi shape — the test only threads `host_` to assert it reaches the member.
    (el as unknown as { host_: unknown }).host_ = sentinelHost;
    document.body.appendChild(el);
    await el.updateComplete;
    const mounted = el.shadowRoot!.querySelector(MEMBER_TAG) as HTMLElement & { host_?: unknown };
    expect(mounted).not.toBeNull();
    expect(mounted.host_).toBe(sentinelHost);
  });
});
