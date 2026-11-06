// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// DP3 — a folder's failed files, inline under the folder (One item's problem → at the item).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import '../renderers/controls/FolderCardRenderer.js';
import '../renderers/layouts/VerticalLayout.js';
import '../renderers/controls/XUiRendererControl.js';
import type { LibrarySurface } from './LibrarySurface.js';
import { revealFailedFiles, type FailedFilesList } from '../components/FailedFilesList.js';
import { applyPresentationBodies, clearPresentation } from '../state/presentationRuntime.js';
import { CORE_DECLARED } from '../themes/builtinPresentations.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';

const job = (pathHash: string, errorMessage: string): Record<string, unknown> => ({ pathHash, state: 'FAILED', attempts: 3,
  lastUpdatedMs: 1_700_000_000_000, errorMessage, retryAfterMs: 0, collection: 'default', scanId: '' });

let replies: Record<string, () => Promise<Response>>;

const host = {
  platform: { capabilities: new Set<string>() },
  data: { fetch: async (path: string) => {
    const folder = new URL(path, 'http://test').searchParams.get('pathHash');
    return folder !== null && replies[folder] ? replies[folder]() : ({ ok: false, status: 503 }) as unknown as Response;
  } },
  utilities: { formatRelativeTime: () => 'just now' },
} as unknown as PluginHostApi;

const root = (pathHash: string, failedCount: number): IndexedRootView =>
  ({ pathHash, collection: 'default', fileCount: 200, lastIndexedIsoTime: '2026-09-02T00:00:00Z', status: 'indexed', walkError: '',
    inFlightCount: 0, failedCount, walkCompleted: true }) as IndexedRootView;

async function settle(el: LibrarySurface): Promise<void> {
  for (let i = 0; i < 12; i += 1) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
  for (const list of lists(el)) await list.updateComplete;
}

async function mount(rows: IndexedRootView[]): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host;
  document.body.append(el);
  await el.updateComplete;
  Object.assign(el, { roots: rows, loading: false });
  await settle(el);
  return el;
}

function deepFind(from: Element, tag: string): Element | null {
  for (const node of from.shadowRoot?.querySelectorAll('*') ?? []) {
    if (node.localName === tag) return node;
    const inner = deepFind(node, tag);
    if (inner !== null) return inner;
  }
  return null;
}

const lists = (el: LibrarySurface): FailedFilesList[] => [...el.shadowRoot!.querySelectorAll<FailedFilesList>('jf-failed-files-list')];
const rowOf = (el: LibrarySurface, hash: string): HTMLElement => el.shadowRoot!.querySelector<HTMLElement>(`.card[data-focus-row="${hash}"]`)!;

beforeEach(() => {
  __resetAiStateForTest();
  replies = {
    a: async () => Response.json({ jobs: [job('a-file', 'A reason')], count: 1 }),
    b: async () => Response.json({ jobs: [job('b-file', 'B reason')], count: 1 }),
  };
});

afterEach(() => {
  document.body.replaceChildren();
  vi.restoreAllMocks();
});

describe('failed files sit under their folder', () => {
  it('the list is in the folder’s own row, and each folder lists its own files', async () => {
    const el = await mount([root('a', 1), root('b', 1)]);
    const inA = rowOf(el, 'a').querySelector<FailedFilesList>('jf-failed-files-list')!;
    const inB = rowOf(el, 'b').querySelector<FailedFilesList>('jf-failed-files-list')!;
    expect(inA.shadowRoot!.textContent).toContain('A reason');
    expect(inA.shadowRoot!.textContent).not.toContain('B reason');
    expect(inB.shadowRoot!.textContent).toContain('B reason');
    expect(inB.shadowRoot!.textContent).not.toContain('A reason');
  });

  it('a folder with no failures has no list', async () => {
    const el = await mount([root('a', 0)]);
    expect(lists(el)).toHaveLength(0);
  });

  it('there is no second list and no way to a frame drawer', async () => {
    const el = await mount([root('a', 1)]);
    expect(lists(el)).toHaveLength(1);
    expect(el.shadowRoot!.querySelector('.attention')!.textContent).not.toContain('Open failed-files list');
  });

  it('the failed chip takes the reader to the list under its folder', async () => {
    const el = await mount([root('a', 1), root('b', 1)]);
    const chip = rowOf(el, 'b').querySelector('jf-button.failed-chip') as HTMLElement & { onActivate(): void };
    chip.onActivate();
    await settle(el);
    const list = rowOf(el, 'b').querySelector<FailedFilesList>('jf-failed-files-list')!;
    expect(list.shadowRoot!.activeElement?.classList.contains('list')).toBe(true);
  });

  it('the folder’s problem takes the reader there too', async () => {
    const el = await mount([root('a', 1)]);
    const show = [...el.shadowRoot!.querySelectorAll('.attention jf-button')].find(button => button.textContent?.includes('Show files')) as HTMLElement & { onActivate(): void };
    show.onActivate();
    await settle(el);
    expect(lists(el)[0]!.shadowRoot!.activeElement?.classList.contains('list')).toBe(true);
  });

  it('a list that cannot be read says so under its folder, and the other folder is unaffected', async () => {
    replies['a'] = async () => new Response('nope', { status: 503 });
    const el = await mount([root('a', 1), root('b', 1)]);
    expect(rowOf(el, 'a').querySelector('jf-failed-files-list')!.shadowRoot!.textContent).toContain("Couldn't load failed files");
    expect(rowOf(el, 'b').querySelector('jf-failed-files-list')!.shadowRoot!.textContent).toContain('B reason');
  });

  it('themed rows carry the list too, and the chip still reaches it', async () => {
    expect(applyPresentationBodies(CORE_DECLARED).ok).toBe(true);
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ jobs: [job('a-file', 'A reason')], count: 1 })));
    try {
      const el = await mount([root('a', 1)]);
      await vi.waitFor(() => expect(deepFind(el, 'jf-failed-files-list'), 'the themed row').not.toBeNull());
      const list = deepFind(el, 'jf-failed-files-list') as FailedFilesList;
      await settle(el);
      await list.updateComplete;
      expect(list.folderPathHash).toBe('a');
      revealFailedFiles('a');
      await settle(el);
      expect(list.shadowRoot!.activeElement?.classList.contains('list')).toBe(true);
    } finally {
      clearPresentation();
      vi.unstubAllGlobals();
    }
  });

  it('C4 a delayed reply for one folder never fills the other', async () => {
    let answerA!: (response: Response) => void;
    replies['a'] = () => new Promise<Response>(resolve => { answerA = resolve; });
    const el = await mount([root('a', 1), root('b', 1)]);
    answerA(Response.json({ jobs: [job('a-file', 'late A')], count: 1 }));
    await settle(el);
    expect(rowOf(el, 'b').querySelector('jf-failed-files-list')!.shadowRoot!.textContent).not.toContain('late A');
    expect(rowOf(el, 'a').querySelector('jf-failed-files-list')!.shadowRoot!.textContent).toContain('late A');
  });
});
