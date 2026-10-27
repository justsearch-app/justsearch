// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { confirmConversationTree } from './conversationDelete.js';
import type { ConfirmDialog } from '../ConfirmDialog.js';
import type { DeleteTreeEntry } from '../../state/conversationListStore.js';

const row = (id: string, depth = 0): DeleteTreeEntry => ({ id, depth, busy: false,
  title: id, titleSource: 'user', createdAt: 1, lastActiveAt: 1, firstUserMessage: '',
  shapeId: 'core.free-chat', storeBacked: true,
});
const memory = (id: string, sourceConversationId?: string | null) => ({ id, sourceConversationId,
  content: id, revision: `"${'a'.repeat(64)}"`, kind: 'fact', actor: 'user', createdAt: '2026-10-25T00:00:00Z',
});
let payload: unknown;
let httpStatus: number;
let delayed: Promise<void> | null;
let release: () => void;
const requests: string[] = [];
beforeEach(() => {
  payload = { locked: false, memories: [] }; httpStatus = 200; delayed = null;
  release = () => {}; requests.length = 0;
  vi.stubGlobal('fetch', vi.fn(async (input: unknown) => {
    requests.push(String(input)); await delayed;
    return new Response(JSON.stringify(payload), { status: httpStatus });
  }));
});
afterEach(async () => {
  release(); await settle();
  for (const host of document.querySelectorAll('jf-confirm-dialog')) host.dispatchEvent(new CustomEvent('cancel'));
  document.body.replaceChildren();
  await new Promise(resolve => setTimeout(resolve, 220));
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
async function settle(): Promise<void> {
  for (let i = 0; i < 5; i++) await new Promise(resolve => setTimeout(resolve, 0));
}
function dialog(): ConfirmDialog { return document.querySelector('jf-confirm-dialog')!; }
async function choose(accept: boolean): Promise<void> {
  const host = dialog(); await host.updateComplete;
  host.dispatchEvent(new CustomEvent(accept ? 'confirm' : 'cancel'));
  await settle();
}

for (const [label, memories, expected] of [
  ['one', [memory('root-memory', 'Root'), memory('ambient', null)], 1],
  ['ambient-only zero', [memory('ambient'), memory('explicitly-unlinked', null)], 0],
  ['exact complete tree', [memory('a', 'Root'), memory('b', 'child'), memory('c', 'leaf')], 3],
  ['repeated exact source', [memory('a', 'child'), memory('b', 'child'), memory('ambient')], 2],
] as const) {
  it(`CD3 disclosure counts ${label} recorded links across the complete named tree`, async () => {
    payload = { locked: false, memories };
    const answer = confirmConversationTree(['child', 'leaf'], [row('Root'), row('child', 1), row('leaf', 2)]);
    await settle();
    expect(dialog().shadowRoot!.textContent).toContain(`${expected} recorded memory link${expected === 1 ? '' : 's'}`);
    expect(dialog().shadowRoot!.textContent).toContain('All memories remain, including memories without a recorded conversation link');
    expect(dialog().shadowRoot!.textContent).toContain('Exports and backups you made remain');
    expect(dialog().shadowRoot!.textContent).toContain('at the time of this read');
    expect(requests).toEqual(['/api/memory']);
    await choose(false); expect(await answer).toBe(false);
  });
}
// CS53 explicitly supersedes CD3's confident zero/tree/casing exclusions. Keep their
// original records intact here: these spellings prove neither equality nor distinction.
// These are client response fixtures, not proof of any filesystem/provider alias runtime.
for (const [label, memories] of [
  ['formerly zero with unrelated source', [memory('ambient'), memory('other', 'elsewhere')]],
  ['formerly tree with unrelated source', [memory('a', 'Root'), memory('b', 'child'), memory('c', 'leaf'), memory('other', 'elsewhere')]],
  ['formerly case-variant provenance', [memory('a', 'Root'), memory('case-alias', 'ROOT'), memory('ambient')]],
  ['empty unlocked envelope', []],
  ['noncase provider alias', [memory('a', 'Root'), memory('alias', 'provider-alias')]],
  ['nonexact descendant spelling', [memory('a', 'Root'), memory('alias', 'CHILD')]],
  ['short-name spelling', [memory('alias', 'ROOT~1')]],
  ['invalid nonexact source', [memory('a', 'Root'), memory('invalid', '../Root')]],
  ['empty-string source', [memory('a', 'Root'), memory('empty', '')]],
] as const) {
  it(`CS53 ${label} cannot confirm a total and still permits consent`, async () => {
    payload = { locked: false, memories };
    const answer = confirmConversationTree(['child', 'leaf'], [row('Root'), row('child', 1), row('leaf', 2)]);
    await settle();
    const text = dialog().shadowRoot!.textContent;
    expect(text?.match(/\d+ recorded memory links?/g) ?? []).toEqual([]);
    expect(text).toContain('Recorded memory link count could not be confirmed');
    expect(text).toContain('All memories remain, including memories without a recorded conversation link');
    expect(text).toContain('Exports and backups you made remain');
    expect(requests).toEqual(['/api/memory']);
    await choose(true); expect(await answer).toBe(true);
  });
}
for (const [label, body, status] of [
  ['locked', { locked: true, memories: [] }, 200],
  ['unavailable', { unavailable: true }, 503],
  ['failed', {}, 500],
  ['missing lock state', { memories: [] }, 200],
  ['malformed provenance', { locked: false, memories: [memory('ok', 'Root'), memory('bad', 7 as never)] }, 200],
  ['malformed list', { locked: false, memories: [null] }, 200],
  ['repeated memory identity', { locked: false, memories: [memory('same', 'Root'), memory('same', 'Root')] }, 200],
] as const) {
  it(`CD3 ${label} memory count is explained as unknown and still permits consent`, async () => {
    payload = body; httpStatus = status;
    const answer = confirmConversationTree([], [row('Root')]); await settle();
    expect(dialog().shadowRoot!.textContent).toContain('Recorded memory link count is unknown');
    expect(dialog().shadowRoot!.textContent).not.toContain('0 recorded memory links');
    expect(dialog().shadowRoot!.textContent).toContain('All memories remain');
    await choose(true); expect(await answer).toBe(true);
  });
}
for (const [label, malformed] of [
  ['id', { ...memory('bad'), id: 7 }],
  ['content', { ...memory('bad'), content: null }],
  ['revision', { ...memory('bad'), revision: 'unversioned' }],
] as const) {
  it(`CS53 validates the entire response including malformed ${label} after a nonexact source`, async () => {
    payload = { locked: false, memories: [memory('exact', 'Root'), memory('nonexact', 'elsewhere'), malformed] };
    const answer = confirmConversationTree([], [row('Root')]); await settle();
    expect(dialog().shadowRoot!.textContent).toContain('Recorded memory link count is unknown because the Memory response could not be read');
    expect(dialog().shadowRoot!.textContent).not.toMatch(/\d+ recorded memory links?/);
    await choose(true); expect(await answer).toBe(true);
  });
}
it('CD3 cancellation during a slow read settles immediately and a late response never changes the next target', async () => {
  delayed = new Promise(resolve => { release = resolve; });
  const first = confirmConversationTree([], [row('Old')]); await settle();
  expect(dialog().shadowRoot!.textContent).toContain('Counting recorded memory links');
  await choose(false); expect(await first).toBe(false);
  // Let the old dialog finish its normal close transition before opening the next one.
  await new Promise(resolve => setTimeout(resolve, 210));
  delayed = null; payload = { locked: false, memories: [memory('new', 'New')] };
  const second = confirmConversationTree([], [row('New')]); await settle();
  const next = dialog(); expect(next.shadowRoot!.textContent).toContain('1 recorded memory link');
  release(); await settle();
  expect(dialog()).toBe(next); expect(next.shadowRoot!.textContent).toContain('New');
  expect(next.shadowRoot!.textContent).not.toContain('Old');
  await choose(false); expect(await second).toBe(false);
});

it('CD3 pending memory disclosure cannot be bypassed by an early confirmation event', async () => {
  delayed = new Promise(resolve => { release = resolve; });
  const answer = confirmConversationTree([], [row('Root')]); await settle();
  let settled = false; void answer.then(() => { settled = true; });
  await choose(true); expect(settled).toBe(false);
  await choose(false); expect(await answer).toBe(false);
});
