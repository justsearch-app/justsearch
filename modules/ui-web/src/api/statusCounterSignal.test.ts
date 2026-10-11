// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { bindStatusRefresh, observeStatusSignal, observeStatusResponse, observeStatusEvent,
  statusSnapshotDisplayed, statusSnapshotCanDisplay } from './statusInvalidation.js';
let stop: (() => void) | undefined;
afterEach(() => { stop?.(); stop = undefined; });
const snapshot = (epoch: string, revision: number, current = revision) => new Response('{}', { headers: {
  'X-JustSearch-Status-Epoch': epoch, 'X-JustSearch-Status-Change': String(current),
  'X-JustSearch-Status-Reflected': String(revision),
} });
it('coalesces server publications and suppresses replay across every request method', async () => {
  const refresh = vi.fn(async () => { statusSnapshotDisplayed(snapshot('head', 5)); });
  stop = bindStatusRefresh(refresh); statusSnapshotDisplayed(snapshot('head', 0));
  await Promise.all(Array.from({ length: 50 }, () => observeStatusSignal('head', 5)));
  expect(refresh).toHaveBeenCalledTimes(1);
  await observeStatusEvent({ statusChangeEpoch: 'head', statusChangeCounter: 5 });
  await observeStatusResponse('/api/anything-readonly', snapshot('head', 5));
  expect(refresh).toHaveBeenCalledTimes(1);
});
it('accepts a restarted head at counter zero and rejects old in-flight snapshots', async () => {
  const refresh = vi.fn(async () => { statusSnapshotDisplayed(snapshot('new', 0)); });
  stop = bindStatusRefresh(refresh); statusSnapshotDisplayed(snapshot('old', 12));
  await observeStatusSignal('new', 0);
  expect(refresh).toHaveBeenCalledTimes(1);
  expect(statusSnapshotCanDisplay(snapshot('old', 12))).toBe(false);
  await observeStatusSignal('old', 100);
  expect(refresh).toHaveBeenCalledTimes(1);
});
it('does not acknowledge a current header when the snapshot reflects an older revision', async () => {
  const refresh = vi.fn(async () => { statusSnapshotDisplayed(snapshot('head', 1, 2)); });
  stop = bindStatusRefresh(refresh); statusSnapshotDisplayed(snapshot('head', 0));
  await observeStatusSignal('head', 2);
  expect(refresh).toHaveBeenCalledTimes(1); // a stalled producer cannot cause a retry loop
  await observeStatusSignal('head', 2);
  expect(refresh).toHaveBeenCalledTimes(2); // a later request may retry the pending publication
});
it('makes one trailing read when a new publication arrives during an observation', async () => {
  let release: () => void = () => {};
  const held = new Promise<void>(resolve => { release = resolve; });
  const refresh = vi.fn(async () => {
    if (refresh.mock.calls.length === 1) { await held; statusSnapshotDisplayed(snapshot('head', 1)); }
    else statusSnapshotDisplayed(snapshot('head', 2));
  });
  stop = bindStatusRefresh(refresh); statusSnapshotDisplayed(snapshot('head', 0));
  const first = observeStatusSignal('head', 1); await Promise.resolve();
  const second = observeStatusSignal('head', 2); release(); await Promise.all([first, second]);
  expect(refresh).toHaveBeenCalledTimes(2);
});
it.each([null, -1, 1.5, '1x', '', Number.MAX_SAFE_INTEGER + 1])('ignores malformed counter %s', async value => {
  const refresh = vi.fn(async () => {}); stop = bindStatusRefresh(refresh);
  await observeStatusSignal('head', value); expect(refresh).not.toHaveBeenCalled();
});
it('observes a failed response if another client really changed state, and preserves its body', async () => {
  const refresh = vi.fn(async () => { statusSnapshotDisplayed(snapshot('head', 1)); });
  stop = bindStatusRefresh(refresh); statusSnapshotDisplayed(snapshot('head', 0));
  const response = new Response('{"error":"refused"}', { status: 409, headers: {
    'X-JustSearch-Status-Epoch': 'head', 'X-JustSearch-Status-Change': '1',
  } });
  await observeStatusResponse('/api/read-only', response);
  expect(await response.json()).toEqual({ error: 'refused' }); expect(refresh).toHaveBeenCalledTimes(1);
});
