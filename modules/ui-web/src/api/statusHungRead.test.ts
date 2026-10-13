// SPDX-License-Identifier: Apache-2.0
// b82: a status read that never settles cannot stop the poll schedule or leave the display stuck.
// It is abandoned (aborted) after one poll interval; at most one status read is in flight, and an
// abandoned read is never left running beside its successor. Real statusPoll, statusInvalidation
// and http modules; only fetch and the timers are stubbed.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import body from './__fixtures__/status-response-live.json';

const POLL_INTERVAL_MS = 10_000;

type Read = { url: string; signal: AbortSignal | null | undefined; settled: boolean };
let reads: Read[];
let hang: boolean;
let state: string;
let change: number;
let maxOpen: number;

/** A read is open until it answered or its signal aborted; a hung read ignores its signal. */
const open = () => reads.filter(r => !r.settled && !r.signal?.aborted).length;
const statusReads = () => reads.filter(r => r.url.includes('/api/status')).length;
const freshReads = () => reads.filter(r => r.url.includes('fresh=true')).length;

async function load() {
  vi.resetModules();
  return import('../shell-v0/utils/statusPoll.js');
}

type Seen = { conversationProtection?: { state?: string } } | null;
const shown = (s: Seen) => s?.conversationProtection?.state ?? null;

beforeEach(() => {
  reads = []; hang = false; state = 'unlocked'; change = 3; maxOpen = 0;
  (globalThis as unknown as { window: unknown }).window = globalThis;
  vi.useFakeTimers();
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    const read: Read = { url, signal: init?.signal, settled: false };
    reads.push(read);
    maxOpen = Math.max(maxOpen, open());
    // Never settles and ignores abort, like a frozen request thread behind a proxy.
    if (hang) return new Promise<Response>(() => {});
    const b = structuredClone(body) as Record<string, unknown>;
    b.conversationProtection = { state };
    read.settled = true;
    return Promise.resolve(new Response(JSON.stringify(b), {
      status: 200,
      headers: {
        'content-type': 'application/json', 'X-JustSearch-Status-Epoch': 'A',
        'X-JustSearch-Status-Change': String(change), 'X-JustSearch-Status-Reflected': String(change),
      },
    }));
  }));
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('b82 a status read that never settles', { timeout: 60_000 }, () => {
  it('does not stop polling: the hung read is aborted after one interval and the next poll shows current state', async () => {
    const poll = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(shown(s)));
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.at(-1)).toBe('unlocked');

    // The next read never returns; meanwhile another client locks.
    hang = true;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    const hung = reads.at(-1)!;
    expect(statusReads()).toBe(2);
    state = 'locked'; change = 4;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS - 1);
    expect(statusReads(), 'no second read while the first is within its interval').toBe(2);

    // Every read keeps hanging for a minute: each is abandoned before its successor starts.
    await vi.advanceTimersByTimeAsync(60_000);
    const duringHang = statusReads();
    expect(duringHang, 'polling continues while reads hang').toBeGreaterThanOrEqual(7);
    expect(hung.signal?.aborted, 'the abandoned read is aborted, not left running').toBe(true);
    expect(duringHang, 'no read storm: at most one read per poll interval').toBeLessThanOrEqual(9);
    expect(maxOpen, 'at most one status read in flight').toBe(1);

    // The server answers again: the next poll displays the current state.
    hang = false;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    console.log(`B82-HUNG-READ readsDuringHang=${duringHang} maxOpen=${maxOpen} rendered=${JSON.stringify(seen.slice(-3))}`);
    un();
    expect(seen.at(-1), 'the display follows the server once reads answer again').toBe('locked');
    expect(maxOpen).toBe(1);
  });

  it('a fresh read requested behind a hung read is not lost and is never stacked beside it', async () => {
    const poll = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(shown(s)));
    await vi.advanceTimersByTimeAsync(0);
    hang = true;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    state = 'locked'; change = 4;
    let settled = false;
    const refreshed = poll.refreshStatusNow().then(() => { settled = true; });
    hang = false;
    await vi.advanceTimersByTimeAsync(1_000);
    expect(freshReads(), 'the fresh read waits for the read in flight').toBe(0);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    void refreshed;
    console.log(`B82-HUNG-THEN-FRESH fresh=${freshReads()} maxOpen=${maxOpen} rendered=${JSON.stringify(seen.slice(-3))}`);
    un();
    expect(settled, 'the requested fresh read completes').toBe(true);
    expect(freshReads()).toBe(1);
    expect(seen.at(-1)).toBe('locked');
    expect(maxOpen, 'never more than one status read in flight').toBe(1);
  });
});
