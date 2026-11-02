// @vitest-environment happy-dom
//
// Tempdoc 914 D4 — Add Folder can name the COLLECTION the new root's documents are tagged with.
//
// Before this, the surface invoked `core.add-watched-root` with `{path}` only, so every folder a
// user added landed in the untagged "default" bucket — even though the operation handler has always
// accepted `collection` and the folder row, the search `collection` filter and the "Other sources"
// section all key on that label. The affordance was missing, not the plumbing.

import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';

interface Invocation {
  readonly id: string;
  readonly args: Record<string, unknown>;
}

/**
 * `picks` are returned one per `pickFolder()` call (the last repeats), so a test can tell "the
 * picker ran again" from "the click did nothing" — with a stub returning one constant path the two
 * are indistinguishable (review nit). `undefined` ⟹ no `folder-picker` capability at all.
 */
function makeHost(recorded: Invocation[], picks?: readonly (string | null)[]): PluginHostApi {
  let call = 0;
  return {
    platform: {
      capabilities: new Set<string>(picks === undefined ? [] : ['folder-picker']),
      pickFolder: async () => {
        const value = picks?.[Math.min(call, picks.length - 1)] ?? null;
        call += 1;
        return value;
      },
    },
    data: {
      // Every fetch this surface makes here (roots list, add-time preview, excludes) is advisory for
      // this test; a non-ok response leaves the Add button ungated ("checking"), which is the state
      // a user typing a path is in.
      fetch: async () => ({ ok: false, status: 503 }) as unknown as Response,
      invokeOperation: async (id: string, args: Record<string, unknown>) => {
        recorded.push({ id, args });
        return { success: true };
      },
    },
  } as unknown as PluginHostApi;
}

async function pump(el: Element): Promise<void> {
  for (let i = 0; i < 20; i += 1) {
    await Promise.resolve();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  }
}

async function mount(
  recorded: Invocation[],
  picks?: readonly (string | null)[],
): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = makeHost(recorded, picks);
  document.body.appendChild(el);
  await pump(el);
  return el;
}

/** Open the inline add form the way a user does — the header's Add Folder button. */
async function openAddForm(el: LibrarySurface): Promise<void> {
  const buttons = Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? []);
  const add = buttons.find((b) => b.getAttribute('label') === 'Add folder') as
    | (Element & { onActivate?: () => void })
    | undefined;
  expect(add, 'the header must offer Add Folder').toBeTruthy();
  add?.onActivate?.();
  await pump(el);
}

function typeInto(el: LibrarySurface, selector: string, value: string): void {
  const input = el.shadowRoot?.querySelector(selector) as HTMLInputElement;
  expect(input, `${selector} must be present in the add form`).toBeTruthy();
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/** Submit the way the form's own Enter handler does (the same `handleAddRoot` the Add button calls). */
async function submit(el: LibrarySurface): Promise<void> {
  const input = el.shadowRoot?.querySelector('.add-row input') as HTMLInputElement;
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await pump(el);
}

describe('LibrarySurface — Add Folder carries the collection (914 D4)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  it('sends `collection` when the user names one', async () => {
    const recorded: Invocation[] = [];
    const el = await mount(recorded);
    try {
      await openAddForm(el);
      typeInto(el, '.add-row input', 'F:\\corpus\\reports');
      typeInto(el, '[data-testid="library-add-collection"]', 'resid3-live');
      await submit(el);

      expect(recorded.length).toBe(1);
      expect(recorded[0]?.id).toBe('core.add-watched-root');
      expect(recorded[0]?.args).toEqual({ path: 'F:\\corpus\\reports', collection: 'resid3-live' });
    } finally {
      el.remove();
    }
  });

  it('OMITS `collection` when the field is blank — the backend default is not re-spelled here', async () => {
    const recorded: Invocation[] = [];
    const el = await mount(recorded);
    try {
      await openAddForm(el);
      typeInto(el, '.add-row input', 'F:\\corpus\\reports');
      await submit(el);

      expect(recorded.length).toBe(1);
      expect(recorded[0]?.args).toEqual({ path: 'F:\\corpus\\reports' });
      // Not `collection: ''` and not `collection: 'default'` — the key is absent entirely.
      expect(Object.keys(recorded[0]?.args ?? {})).toEqual(['path']);
    } finally {
      el.remove();
    }
  });

  it('OMITS `collection` when the field holds only whitespace', async () => {
    const recorded: Invocation[] = [];
    const el = await mount(recorded);
    try {
      await openAddForm(el);
      typeInto(el, '.add-row input', 'F:\\corpus\\reports');
      typeInto(el, '[data-testid="library-add-collection"]', '   ');
      await submit(el);

      expect(recorded[0]?.args).toEqual({ path: 'F:\\corpus\\reports' });
    } finally {
      el.remove();
    }
  });

  it('the native picker fills the SAME form instead of adding immediately, so the field exists there too', async () => {
    const recorded: Invocation[] = [];
    const el = await mount(recorded, ['D:\\picked\\folder']);
    try {
      await openAddForm(el);
      // The picker chose a folder — nothing was added yet; the form is open with the path filled in.
      expect(recorded.length).toBe(0);
      const path = el.shadowRoot?.querySelector('.add-row input') as HTMLInputElement;
      expect(path?.value).toBe('D:\\picked\\folder');
      typeInto(el, '[data-testid="library-add-collection"]', 'picked-corpus');
      await submit(el);
      expect(recorded[0]?.args).toEqual({
        path: 'D:\\picked\\folder',
        collection: 'picked-corpus',
      });
    } finally {
      el.remove();
    }
  });

  it('the header button re-opens the picker rather than submitting an open form', async () => {
    // The wrong-gate this split fixes: with one method for "open the flow" and "submit it", a header
    // click while the form was open submitted — and in picker mode the picker became unreachable.
    const recorded: Invocation[] = [];
    const el = await mount(recorded, ['D:\\first\\pick', 'D:\\second\\pick']);
    try {
      await openAddForm(el);
      const first = el.shadowRoot?.querySelector('.add-row input') as HTMLInputElement;
      expect(first?.value).toBe('D:\\first\\pick');
      typeInto(el, '[data-testid="library-add-collection"]', 'c1');
      await openAddForm(el); // header clicked again while the form is open
      expect(recorded.length, 'the header button must not submit').toBe(0);
      const path = el.shadowRoot?.querySelector('.add-row input') as HTMLInputElement;
      // The stub returns a DIFFERENT path on the second call, so this distinguishes "the picker ran
      // again" from "the click did nothing" — a same-path stub could not (review nit).
      expect(path?.value, 'the header re-ran the picker').toBe('D:\\second\\pick');
    } finally {
      el.remove();
    }
  });

  it('refuses a RESERVED collection instead of creating a root that impersonates an internal corpus', async () => {
    const recorded: Invocation[] = [];
    const el = await mount(recorded);
    try {
      await openAddForm(el);
      typeInto(el, '.add-row input', 'F:\\corpus\\reports');
      // `IngestCollectionPolicy.RESERVED`, case-insensitively. The backend is the authority — since
      // tempdoc 913 D6 the Operation handler routes the value through `normalizeRequested`
      // (`AddWatchedRootHandler.java:72-77`) — and this guard is the early, in-field half of it, so
      // the user is told before the round trip rather than after.
      typeInto(el, '[data-testid="library-add-collection"]', 'Agent-History');
      await submit(el);

      expect(recorded.length).toBe(0);
      const add = Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? []).find(
        (b) => b.getAttribute('label') === 'Add',
      ) as (Element & { availability?: { kind: string; reason?: string } }) | undefined;
      expect(add?.availability?.kind).toBe('unavailable');
      expect(add?.availability?.reason).toContain('reserved');
    } finally {
      el.remove();
    }
  });
});
