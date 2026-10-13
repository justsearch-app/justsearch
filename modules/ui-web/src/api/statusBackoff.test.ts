// SPDX-License-Identifier: Apache-2.0
// b76: back-off ends when the server answers status reads again, and a fresh read answered before
// its lane reflects is followed up so the change shows at the next render once reflected.
// The OWN7 cases are the round-7 verifier's, copied with their assertions kept (only the result
// log line is local). Real statusInvalidation, http and statusPoll modules; only fetch (and the
// clock) is stubbed.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import body from './__fixtures__/status-response-live.json';

type Meta = { epoch?: string; change?: number; reflected?: number; retryAfter?: string };
const hdr = (m: Meta) => {
  const h: Record<string, string> = { 'content-type': 'application/json' };
  if (m.epoch) h['X-JustSearch-Status-Epoch'] = m.epoch;
  if (m.change !== undefined) h['X-JustSearch-Status-Change'] = String(m.change);
  if (m.reflected !== undefined) h['X-JustSearch-Status-Reflected'] = String(m.reflected);
  if (m.retryAfter !== undefined) h['Retry-After'] = m.retryAfter;
  return h;
};
let server: { status: Meta; other: Meta; statusCode: number; freshCode?: number; state: string; otherCode: number };
let calls: string[];
async function load() {
  vi.resetModules();
  const poll = await import('../shell-v0/utils/statusPoll.js');
  const http = await import('./http.js');
  return { poll, http };
}
const flush = async () => { for (let i = 0; i < 30; i++) await new Promise(r => setTimeout(r, 0)); };
let now = 1_000_000;
beforeEach(() => {
  calls = [];
  now = 1_000_000;
  server = { status: { epoch: 'A', change: 3, reflected: 3 }, other: { epoch: 'A', change: 3 }, statusCode: 200, state: 'unlocked', otherCode: 200 };
  (globalThis as unknown as { window: unknown }).window = globalThis;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input);
    calls.push(url);
    if (url.includes('/api/status')) {
      const code = url.includes('fresh=true') && server.freshCode ? server.freshCode : server.statusCode;
      if (code !== 200) {
        return new Response('{"error":"refused","errorCode":"UPGRADE_PREPARING","retrySafe":false}', { status: code, headers: hdr(server.status) });
      }
      const b = structuredClone(body) as Record<string, unknown>;
      b.conversationProtection = { state: server.state };
      return new Response(JSON.stringify(b), { status: 200, headers: hdr(server.status) });
    }
    if (server.otherCode !== 200) return new Response('{"error":"bad","errorCode":"VALIDATION"}', { status: server.otherCode, headers: hdr(server.other) });
    return new Response('{"ok":true}', { status: 200, headers: hdr(server.other) });
  }));
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const fresh = () => calls.filter(c => c.includes('fresh=true')).length;
const ordinary = () => calls.filter(c => c.endsWith('/api/status')).length;
type Seen = { conversationProtection?: { state?: string } } | null;
const state = (s: Seen) => s?.conversationProtection?.state ?? null;
type Http = { request: (base: string, path: string, options: { retries: number }) => Promise<unknown> };
const ro = async (http: Http) => { try { await http.request('http://x', '/api/read-only', { retries: 1 }); } catch { /* failed action */ } await flush(); };
const log = (line: string) => console.log(line);

describe('round 7 independent cases (copied)', { timeout: 120_000 }, () => {
  beforeEach(() => { vi.spyOn(Date, 'now').mockImplementation(() => now); });

  it('OWN7-BACKOFF-THEN-ACTION: after refused fresh reads and recovery, a later user action is shown at the next render', async () => {
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await flush();
    // Restart-like period: fresh reads refused with Retry-After 30 while the counter advances.
    server.freshCode = 503; server.status = { epoch: 'A', change: 3, reflected: 3, retryAfter: '30' };
    for (let c = 4; c <= 9; c++) { server.other = { epoch: 'A', change: c }; await ro(http); now += 2_000; }
    // Recovery: canonical reads succeed again; one ordinary read displays current state.
    server.freshCode = undefined; server.status = { epoch: 'A', change: 9, reflected: 9 };
    await poll.refreshStatusNow(); await flush();
    const freshBefore = fresh();
    // A user action changes state: its response carries change 10; canonical now reports locked.
    server.state = 'locked'; server.status = { epoch: 'A', change: 10, reflected: 10 }; server.other = { epoch: 'A', change: 10 };
    await ro(http);
    log(`OWN7-BACKOFF-THEN-ACTION freshAfterAction=${fresh() - freshBefore} rendered=${JSON.stringify(seen.slice(-3))}`);
    un();
    expect(seen.at(-1), 'post-action state must be shown at the next render after recovery').toBe('locked');
  });

  it('OWN7-BACKOFF-REALISTIC: restart with Retry-After 1 while the counter advances for 40 s, then recovery; an action 3 s later is shown at the next render', async () => {
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await flush();
    server.freshCode = 503; server.status = { epoch: 'A', change: 3, reflected: 3, retryAfter: '1' };
    let c = 3;
    for (let t = 0; t < 40; t++) { c++; server.other = { epoch: 'A', change: c }; await ro(http); now += 1_000; }
    const refused = fresh();
    server.freshCode = undefined; server.status = { epoch: 'A', change: c, reflected: c };
    await poll.refreshStatusNow(); await flush();
    now += 3_000;
    const freshBefore = fresh();
    c++; server.state = 'locked'; server.status = { epoch: 'A', change: c, reflected: c }; server.other = { epoch: 'A', change: c };
    await ro(http);
    log(`OWN7-BACKOFF-REALISTIC refusedFreshReadsIn40s=${refused} freshAfterAction=${fresh() - freshBefore} rendered=${JSON.stringify(seen.slice(-3))}`);
    un();
    expect(seen.at(-1), 'post-action state must be shown at the next render after recovery').toBe('locked');
  });

  it('OWN7-LAGGING-FRESH (assertion added by b76): an action fresh read answered before its lane reflects; once the lane reflects, the next request shows the change without another fresh read', async () => {
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await flush();
    server.other = { epoch: 'A', change: 6 }; server.status = { epoch: 'A', change: 6, reflected: 3 }; // lane not yet reflected
    await ro(http);
    const afterAction = seen.at(-1);
    server.state = 'locked'; server.status = { epoch: 'A', change: 6, reflected: 6 }; // lane reflects shortly after
    const f0 = fresh();
    await ro(http); // next read-only request of any kind
    log(`OWN7-LAGGING-FRESH afterAction=${afterAction} afterNextRequest=${seen.at(-1)} freshOnNextRequest=${fresh() - f0}`);
    un();
    expect(afterAction).toBe('unlocked');
    expect(seen.at(-1), 'the reflected change must show at the next render').toBe('locked');
    expect(fresh() - f0, 'one fresh read per counter value: the follow-up is an ordinary read').toBe(0);
  });
});

describe('b76 back-off ends at recovery; follow-ups stay bounded', { timeout: 120_000 }, () => {
  it('fresh reads refused for 40 s while another client toggles; after an ordinary poll succeeds, a change 2 s later shows at the next render', async () => {
    vi.useFakeTimers();
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await vi.advanceTimersByTimeAsync(0);
    // Fresh reads only are refused (a proxy answering 503 with Retry-After 300); ordinary polls succeed.
    server.freshCode = 503;
    server.status = { epoch: 'A', change: 3, reflected: 3, retryAfter: '300' };
    let c = 3;
    const startFresh = fresh();
    for (let t = 0; t < 40; t++) {
      if (t % 4 === 0) { c++; server.state = server.state === 'locked' ? 'unlocked' : 'locked'; server.status = { ...server.status, change: c, reflected: c }; }
      server.other = { epoch: 'A', change: c };
      await http.request('http://x', '/api/read-only', { retries: 1 });
      await vi.advanceTimersByTimeAsync(1_000);
    }
    const refused = fresh() - startFresh;
    // Recovery: fresh reads are answered again; an ordinary poll succeeds.
    server.freshCode = undefined;
    server.state = 'unlocked'; c++; server.status = { epoch: 'A', change: c, reflected: c };
    await vi.advanceTimersByTimeAsync(10_000);
    expect(seen.at(-1), 'the ordinary poll shows the recovered state').toBe('unlocked');
    await vi.advanceTimersByTimeAsync(2_000);
    // Another client locks; the UI sends one read-only request that carries the new counter.
    c++; server.state = 'locked'; server.status = { epoch: 'A', change: c, reflected: c }; server.other = { epoch: 'A', change: c };
    const before = fresh();
    await http.request('http://x', '/api/read-only', { retries: 1 });
    await vi.advanceTimersByTimeAsync(0);
    console.log(`B76-RECOVERY-BY-POLL refusedFreshReadsIn40s=${refused} freshAfterChange=${fresh() - before} rendered=${JSON.stringify(seen.slice(-3))}`);
    un();
    expect(seen.at(-1), 'next render after recovery shows the lock').toBe('locked');
    expect(fresh() - before).toBe(1);
    expect(refused, 'refused fresh reads stay bounded while ordinary polls succeed').toBeLessThanOrEqual(8);
  });

  it('the UI\'s own successful action after recovery by an ordinary poll gets its fresh read at once', async () => {
    vi.useFakeTimers();
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await vi.advanceTimersByTimeAsync(0);
    server.statusCode = 503; server.status = { epoch: 'A', change: 3, reflected: 3, retryAfter: '30' };
    server.other = { epoch: 'A', change: 4 };
    await http.request('http://x', '/api/read-only', { retries: 1 });
    await vi.advanceTimersByTimeAsync(0);
    expect(fresh(), 'the refused read').toBe(1);
    server.statusCode = 200; server.status = { epoch: 'A', change: 4, reflected: 4 };
    await vi.advanceTimersByTimeAsync(10_000); // ordinary poll succeeds, 20 s of Retry-After remain
    await vi.advanceTimersByTimeAsync(2_000);
    server.state = 'locked'; server.status = { epoch: 'A', change: 5, reflected: 5 }; server.other = { epoch: 'A', change: 5 };
    await http.request('http://x', '/api/conversation/lock', { retries: 1 }); // own successful action
    await vi.advanceTimersByTimeAsync(0);
    un();
    expect(fresh()).toBe(2);
    expect(seen.at(-1)).toBe('locked');
  });

  it('while status reads keep failing, refused fresh reads stay bounded and Retry-After holds', async () => {
    vi.useFakeTimers();
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await vi.advanceTimersByTimeAsync(0);
    server.statusCode = 503; server.status = { epoch: 'A', change: 3, reflected: 3, retryAfter: '25' };
    for (let c = 4; c < 34; c++) {
      server.other = { epoch: 'A', change: c };
      await http.request('http://x', '/api/read-only', { retries: 1 });
      await vi.advanceTimersByTimeAsync(1_000);
    }
    const polls = ordinary();
    un();
    expect(fresh(), 'Retry-After 25 s: one refused read in the first 25 s and at most one more by 30 s').toBeLessThanOrEqual(2);
    expect(polls, 'ordinary polling continues throughout').toBeGreaterThanOrEqual(3);
  });

  it('a lane held after the fresh read costs one fresh read and a bounded number of ordinary follow-ups per value', async () => {
    const { poll, http } = await load();
    const seen: (string | null)[] = [];
    const un = poll.subscribeStatus((s: Seen) => seen.push(state(s)));
    await flush();
    server.status = { epoch: 'A', change: 5, reflected: 3 }; server.other = { epoch: 'A', change: 5 };
    const f0 = fresh(); const o0 = ordinary();
    for (let i = 0; i < 20; i++) await ro(http);
    const freshReads = fresh() - f0; const followUps = ordinary() - o0;
    // The lane releases: the next ordinary poll shows it.
    server.state = 'locked'; server.status = { epoch: 'A', change: 5, reflected: 5 };
    await poll.refreshStatusNow(); await flush();
    console.log(`B76-HELD-LANE fresh=${freshReads} ordinaryFollowUps=${followUps}`);
    un();
    expect(freshReads).toBe(1);
    expect(followUps).toBeLessThanOrEqual(3);
    expect(seen.at(-1)).toBe('locked');
  });

  it('OWN7-REFUSED-ADVANCING on one clock: refused fresh reads while every response advances stay bounded', async () => {
    // The round-7 case advances Date.now by 18 ms per response but leaves the 10 s interval poll on
    // the real clock, so its 3.6 s span covers about nine real ordinary polls. Here both share one
    // clock: 200 responses over 3.6 s, fresh reads refused, ordinary polls answered.
    vi.useFakeTimers();
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await vi.advanceTimersByTimeAsync(0);
    server.freshCode = 503; server.status = { epoch: 'A', change: 3, reflected: 3 };
    const before = fresh();
    for (let c = 4; c < 204; c++) {
      server.other = { epoch: 'A', change: c };
      await http.request('http://x', '/api/read-only', { retries: 1 });
      await vi.advanceTimersByTimeAsync(18);
    }
    const n = fresh() - before;
    console.log(`B76-REFUSED-ADVANCING-ONE-CLOCK fresh=${n}`);
    un();
    expect(n).toBeLessThanOrEqual(6);
  });

  it('fresh reads refused for 90 s while every response advances: at most one fresh read per answered ordinary poll beyond the back-off schedule', async () => {
    vi.useFakeTimers();
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await vi.advanceTimersByTimeAsync(0);
    server.freshCode = 503; server.status = { epoch: 'A', change: 3, reflected: 3 };
    const start = calls.length;
    for (let c = 4; c < 4 + 900; c++) {
      server.other = { epoch: 'A', change: c };
      await http.request('http://x', '/api/read-only', { retries: 1 });
      await vi.advanceTimersByTimeAsync(100);
    }
    const order = calls.slice(start).filter(u => u.includes('/api/status')).map(u => (u.includes('fresh=true') ? 'F' : 'o')).join('');
    const freshReads = [...order].filter(x => x === 'F').length;
    const polls = [...order].filter(x => x === 'o').length;
    // Between two answered ordinary polls (10 s apart) the exponential schedule (1, 2, 4, 8 s ...)
    // allows its own reads only until the back-off exceeds the poll interval.
    const perGap = order.split('o').slice(1, -1).map(g => g.length);
    console.log(`B76-REFUSED-90S fresh=${freshReads} polls=${polls} order=${order}`);
    un();
    expect(polls).toBeGreaterThanOrEqual(9);
    expect(Math.max(...perGap), 'one probe per answered poll once the back-off exceeds the interval').toBeLessThanOrEqual(1);
    expect(freshReads).toBeLessThanOrEqual(polls + 4);
  });

  it('status responses do not trigger follow-ups of their own', async () => {
    vi.useFakeTimers();
    const { poll, http } = await load();
    const un = poll.subscribeStatus(() => {});
    await vi.advanceTimersByTimeAsync(0);
    server.status = { epoch: 'A', change: 5, reflected: 3 }; server.other = { epoch: 'A', change: 5 };
    await http.request('http://x', '/api/read-only', { retries: 1 });
    await vi.advanceTimersByTimeAsync(0);
    const o0 = ordinary();
    await vi.advanceTimersByTimeAsync(60_000); // six interval polls, each lagging
    un();
    expect(ordinary() - o0, 'only the interval polls').toBe(6);
    expect(fresh()).toBe(1);
  });
});
