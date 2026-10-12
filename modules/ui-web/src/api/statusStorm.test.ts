// SPDX-License-Identifier: Apache-2.0
// b73: fresh canonical reads are bounded and bodies without metadata are ordered by what the
// client knows. The OWN6 cases are the round-6 verifier's, copied with their assertions kept.
// Real statusInvalidation, http and statusPoll modules; only fetch is stubbed.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import body from './__fixtures__/status-response-live.json';

type Meta = { epoch?: string; change?: number; reflected?: number };
const hdr = (m: Meta, extra: Record<string, string> = {}) => {
  const h: Record<string, string> = { 'content-type': 'application/json', ...extra };
  if (m.epoch) h['X-JustSearch-Status-Epoch'] = m.epoch;
  if (m.change !== undefined) h['X-JustSearch-Status-Change'] = String(m.change);
  if (m.reflected !== undefined) h['X-JustSearch-Status-Reflected'] = String(m.reflected);
  return h;
};
let server: { status: Meta; other: Meta; statusCode: number; state: string; retryAfter?: string };
let calls: string[];
async function load() {
  vi.resetModules();
  const poll = await import('../shell-v0/utils/statusPoll.js');
  const http = await import('./http.js');
  return { poll, http };
}
const flush = async () => { for (let i = 0; i < 30; i++) await new Promise(r => setTimeout(r, 0)); };
beforeEach(() => {
  calls = [];
  server = { status: { epoch: 'A', change: 3, reflected: 3 }, other: { epoch: 'A', change: 3 }, statusCode: 200, state: 'unlocked' };
  (globalThis as unknown as { window: unknown }).window = globalThis;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input);
    calls.push(url);
    if (url.includes('/api/status')) {
      if (server.statusCode !== 200) {
        return new Response('{"error":"refused","errorCode":"UPGRADE_PREPARING","retrySafe":false}', {
          status: server.statusCode,
          headers: hdr(server.status, server.retryAfter ? { 'Retry-After': server.retryAfter } : {}),
        });
      }
      const b = structuredClone(body) as Record<string, unknown>;
      b.conversationProtection = { state: server.state };
      return new Response(JSON.stringify(b), { status: 200, headers: hdr(server.status) });
    }
    return new Response('{"ok":true}', { status: 200, headers: hdr(server.other) });
  }));
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
const fresh = () => calls.filter(c => c.includes('fresh=true')).length;
type Seen = { conversationProtection?: { state?: string } } | null;
const state = (s: Seen) => s?.conversationProtection?.state ?? null;

describe('round 6 independent cases', { timeout: 120_000 }, () => {
  it('OWN6-HUNG-STORM: a held producer (change > reflected, no new publication) does not turn ordinary requests into fresh reads', async () => {
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await flush();
    // One publication happens while one lane is held: change advances, reflected stays behind.
    server.status = { epoch: 'A', change: 5, reflected: 3 }; server.other = { epoch: 'A', change: 5 };
    await http.request('http://x', '/api/read-only', { retries: 1 }); await flush();
    const afterSignal = fresh();
    for (let i = 0; i < 20; i++) { await http.request('http://x', '/api/read-only', { retries: 1 }); await flush(); }
    const extra = fresh() - afterSignal;
    console.log(`OWN6-HUNG-STORM freshAfterSignal=${afterSignal} extraFreshOver20ReadOnlyRequests=${extra} totalCalls=${calls.length}`);
    un();
    expect(afterSignal).toBeGreaterThanOrEqual(1);
    expect(extra, 'no new server publication: further read-only requests must not each trigger a fresh status read').toBeLessThanOrEqual(1);
  });
  it('OWN6-REFUSED-STORM: while canonical reads are refused, read-only requests do not each trigger a fresh read', async () => {
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await flush();
    server.statusCode = 503; server.other = { epoch: 'A', change: 4 };
    await http.request('http://x', '/api/read-only', { retries: 1 }); await flush();
    const afterSignal = fresh();
    for (let i = 0; i < 20; i++) { await http.request('http://x', '/api/read-only', { retries: 1 }); await flush(); }
    const extra = fresh() - afterSignal;
    console.log(`OWN6-REFUSED-STORM freshAfterSignal=${afterSignal} extraFreshOver20ReadOnlyRequests=${extra}`);
    un();
    expect(extra, 'refused canonical reads must not become one fresh read per request').toBeLessThanOrEqual(1);
  });
  it('OWN6-METADATA-LOSS: when the server stops supplying metadata the UI is not stuck on the old state', async () => {
    const { poll } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await flush();
    expect(seen.at(-1)).toBe('unlocked');
    server.status = {}; server.other = {}; server.state = 'locked';
    for (let i = 0; i < 5; i++) { await poll.refreshStatusNow(); await flush(); }
    console.log(`OWN6-METADATA-LOSS rendered=${JSON.stringify(seen)}`);
    un();
    expect(seen.at(-1), 'server reports locked without metadata; UI must not keep showing the old unlocked state').not.toBe('unlocked');
  });
  it('OWN6-OUT-OF-ORDER: an older reflected body arriving after a newer one is not displayed (control)', async () => {
    const { poll } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await flush();
    server.status = { epoch: 'A', change: 6, reflected: 6 }; server.state = 'locked';
    await poll.refreshStatusNow(); await flush();
    server.status = { epoch: 'A', change: 6, reflected: 4 }; server.state = 'unlocked';
    await poll.refreshStatusNow(); await flush();
    console.log(`OWN6-OUT-OF-ORDER rendered=${JSON.stringify(seen)}`);
    un();
    expect(seen.at(-1)).toBe('locked');
  });
  it('OWN6-EPOCH-INVALIDATE: a new epoch on a read-only response revokes the displayed body while canonical reads are refused (control)', async () => {
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await flush();
    server.statusCode = 503; server.other = { epoch: 'B', change: 1 }; server.status = { epoch: 'B', change: 1, reflected: 1 };
    await http.request('http://x', '/api/read-only', { retries: 1 }); await flush();
    console.log(`OWN6-EPOCH-INVALIDATE rendered=${JSON.stringify(seen)}`);
    un();
    expect(seen.at(-1)).toBe(null);
  });
  it('OWN6-IDLE: unchanged metadata on polls and read-only requests causes zero fresh reads (control)', async () => {
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await flush();
    const before = fresh();
    for (let i = 0; i < 10; i++) { await http.request('http://x', '/api/read-only', { retries: 1 }); await flush(); }
    un();
    expect(fresh() - before).toBe(0);
  });
});

describe('b73 bounded fresh reads and metadata ordering', { timeout: 120_000 }, () => {
  it('converges within the poll interval when the server supplies no metadata at all', async () => {
    vi.useFakeTimers();
    const { poll } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.at(-1)).toBe('unlocked');
    server.status = {}; server.other = {}; server.state = 'locked';
    await vi.advanceTimersByTimeAsync(10_000);
    un();
    expect(seen.at(-1), 'the next ordinary poll must show the canonical state').toBe('locked');
    expect(fresh()).toBe(0);
  });

  it('a body without metadata answering a read issued before the displayed body never replaces it', async () => {
    vi.resetModules();
    const signal = await import('./statusInvalidation.js');
    expect(typeof (signal as Record<string, unknown>).statusReadIssued, 'a canonical read must record when it was issued').toBe('function');
    const stop = signal.bindStatusRefresh(async () => ({ ok: true }));
    const meta = (m: Meta) => new Response('{}', { headers: hdr(m) });
    expect(signal.statusSnapshotCanDisplay(meta({ epoch: 'A', change: 1, reflected: 1 }))).toBe(true);
    signal.statusSnapshotDisplayed(meta({ epoch: 'A', change: 1, reflected: 1 }));
    const early = signal.statusReadIssued();
    const late = signal.statusReadIssued();
    signal.statusSnapshotDisplayed(meta({ epoch: 'A', change: 2, reflected: 2 }), late);
    expect(signal.statusSnapshotCanDisplay(new Response('{}'), early), 'issued before the displayed body').toBe(false);
    expect(signal.statusSnapshotCanDisplay(new Response('{}'), signal.statusReadIssued())).toBe(true);
    await signal.observeStatusSignal('A', 7);
    expect(signal.statusSnapshotCanDisplay(meta({ epoch: 'A', change: 7, reflected: 1 })), 'lower reflected revision').toBe(false);
    stop();
  });

  it('starts one fresh read per observed value, backs off on refusal honouring Retry-After, and catches up', async () => {
    vi.useFakeTimers();
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await vi.advanceTimersByTimeAsync(0);
    server.statusCode = 503; server.retryAfter = '20';
    server.other = { epoch: 'A', change: 4 }; server.status = { epoch: 'A', change: 4, reflected: 4 };
    await http.request('http://x', '/api/read-only', { retries: 1 });
    await vi.advanceTimersByTimeAsync(0);
    expect(fresh()).toBe(1);
    server.other = { epoch: 'A', change: 5 }; server.status = { epoch: 'A', change: 5, reflected: 5 };
    for (let i = 0; i < 10; i++) { await http.request('http://x', '/api/read-only', { retries: 1 }); await vi.advanceTimersByTimeAsync(1_000); }
    expect(fresh(), 'a newer value inside the Retry-After window must wait').toBe(1);
    const polls = calls.filter(c => c.endsWith('/api/status')).length;
    expect(polls, 'ordinary polling continues while fresh reads back off').toBeGreaterThanOrEqual(2);
    server.statusCode = 200; server.state = 'locked';
    await vi.advanceTimersByTimeAsync(10_000);
    expect(seen.at(-1), 'the display catches up at the next successful read').toBe('locked');
    await http.request('http://x', '/api/read-only', { retries: 1 });
    await vi.advanceTimersByTimeAsync(0);
    un();
    expect(fresh(), 'a value already displayed needs no fresh read').toBeLessThanOrEqual(2);
  });

  it('a refused fresh read is not retried by later requests for the same value, even after the back-off', async () => {
    vi.useFakeTimers();
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await vi.advanceTimersByTimeAsync(0);
    server.statusCode = 429; server.other = { epoch: 'A', change: 4 };
    for (let i = 0; i < 30; i++) { await http.request('http://x', '/api/read-only', { retries: 1 }); await vi.advanceTimersByTimeAsync(2_000); }
    un();
    expect(fresh()).toBe(1);
  });
});
