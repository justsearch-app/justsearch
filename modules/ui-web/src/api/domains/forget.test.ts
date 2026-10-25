// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

interface Outcome {
  operationKey: string | null; path: string | null; scope: 'FILE' | 'FOLDER' | null;
  status: string; removal: string; keepOut: string; reason: string; explanation: string;
}
interface Attempt { readonly key: string; readonly path: string; readonly scope: 'FILE' | 'FOLDER' }
interface Consent { summary: string; typed: boolean; operationId: string }
interface ForgetApi {
  createForgetAttempt(path: string, scope: 'FILE' | 'FOLDER'): Attempt;
  executeForget(base: string, attempt: Attempt, consent: (prompt: Consent) => Promise<boolean>): Promise<Outcome | null>;
  readForget(base: string, key: string): Promise<Outcome>;
  readUnfinishedForgets(base: string, afterId?: number, signal?: AbortSignal, limit?: number): Promise<unknown>;
  forgetContainsPath(path: string, scope: 'FILE' | 'FOLDER', candidate: string): boolean;
  forgetParentFolder(path: string): string | null;
}
let api: ForgetApi;
type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
let fetchMock: ReturnType<typeof vi.fn<Fetcher>>;
const path = '/docs/report\\[1].txt ';
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });
const outcome = (key: string, overrides: Partial<Outcome> = {}): Outcome => ({ operationKey: key,
  path, scope: 'FILE', status: 'COMPLETE', removal: 'CONFIRMED', keepOut: 'INSTALLED',
  reason: 'NONE', explanation: 'Removal completed; original files and retained answers were not erased.', ...overrides });

beforeEach(async () => {
  // Runtime import wrapped in an assertion: an older checkout without the feature fails a
  // behavior assertion rather than failing test transformation on an absent module.
  await expect(vi.importActual<ForgetApi>('./forget.js').then(module => { api = module; return true; })).resolves.toBe(true);
  fetchMock = vi.fn(async () => json({ error: 'Unexpected fixture route' }, 404));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

function gateway(attempt: Attempt, final: () => Response | Promise<Response>) {
  const invocations: Record<string, unknown>[] = [];
  fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/api/operations/')) {
      const request = JSON.parse(String(init?.body)) as Record<string, unknown>;
      invocations.push(request);
      if (invocations.length === 1) return json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
        pendingId: 'pending-forget', gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH',
        operationKey: attempt.key, preparationNonce: 'frozen-nonce',
        argsSummary: `Forget exact file ${JSON.stringify(path)} (1 trailing whitespace character).`,
        message: 'Confirm the exact target' }, 428);
      return final();
    }
    if (url.endsWith('/api/authorizations/approve')) {
      expect(JSON.parse(String(init?.body))).toEqual({ pendingId: 'pending-forget', allowAlways: false });
      return json({ capsule: 'one-use', operationKey: attempt.key, preparationNonce: 'frozen-nonce' });
    }
    if (url.includes('/api/indexing/forgets/')) return json(outcome(attempt.key));
    return json({});
  });
  return invocations;
}

it('S56.2 consent names the exact frozen target and preserves one key and every path byte', async () => {
  const attempt = api.createForgetAttempt(path, 'FILE');
  expect(Object.isFrozen(attempt)).toBe(true);
  const requests = gateway(attempt, () => json({ success: true, structuredData: { forget: outcome(attempt.key) } }));
  const consent = vi.fn(async (_prompt: Consent) => true);
  expect((await api.executeForget('', attempt, consent))?.status).toBe('COMPLETE');
  expect(consent).toHaveBeenCalledExactlyOnceWith({ summary: `Forget exact file ${JSON.stringify(path)} (1 trailing whitespace character).`,
    typed: true, operationId: 'core.forget-indexed-path' });
  expect(requests).toHaveLength(2);
  expect(requests.map(request => request.idempotencyKey)).toEqual([attempt.key, attempt.key]);
  expect(requests.map(request => request.args)).toEqual([{ path, scope: 'FILE' }, { path, scope: 'FILE' }]);
  expect(requests[1]?.confirmationToken).toBe('one-use');
});

it('N56 cancellation never approves, replays or reads a fictitious successful outcome', async () => {
  const attempt = api.createForgetAttempt(path, 'FILE');
  const requests = gateway(attempt, () => { throw new Error('Cancelled intent cannot execute'); });
  expect(await api.executeForget('', attempt, async () => false)).toBeNull();
  expect(requests).toHaveLength(1);
  expect(fetchMock.mock.calls.some(call => String(call[0]).includes('/authorizations/approve'))).toBe(false);
  expect(fetchMock.mock.calls.some(call => String(call[0]).includes('/indexing/forgets/'))).toBe(false);
});

it('S56 lost post-approval response observes the original key without another destructive POST', async () => {
  const attempt = api.createForgetAttempt(path, 'FILE');
  const requests = gateway(attempt, () => { throw new Error('connection lost after accepted effect'); });
  const result = await api.executeForget('', attempt, async () => true);
  expect(result?.status).toBe('COMPLETE');
  expect(requests).toHaveLength(2);
  expect(fetchMock.mock.calls.filter(call => String(call[0]).includes('/api/indexing/forgets/'))
    .map(call => String(call[0]))).toEqual([`/api/indexing/forgets/${attempt.key}`]);
});

it.each(['INCOMPLETE', 'UNKNOWN'])('S56 keeps typed %s separate from success', async status => {
  const attempt = api.createForgetAttempt(path, 'FILE');
  gateway(attempt, () => json({ success: false, errorClass: 'HANDLER_FAILURE', errorCode: `FORGET_${status}`,
    errorDetails: { forget: outcome(attempt.key, { status, removal: 'UNKNOWN', keepOut: 'INSTALLED', reason: 'OUTCOME_UNCONFIRMED' }) } }));
  const result = await api.executeForget('', attempt, async () => true);
  expect(result?.status).toBe(status); expect(result?.removal).toBe('UNKNOWN'); expect(result?.keepOut).toBe('INSTALLED');
});

it('N56 malformed completion and an observation for another key remain unknown', async () => {
  const attempt = api.createForgetAttempt(path, 'FILE');
  gateway(attempt, () => json({ success: true, structuredData: {
    forget: outcome(attempt.key, { removal: 'UNKNOWN' }) } }));
  const dispatch = fetchMock.getMockImplementation()!;
  fetchMock.mockImplementation((input, init) => String(input).includes('/indexing/forgets/')
    ? Promise.resolve(json(outcome(api.createForgetAttempt('/other', 'FILE').key))) : dispatch(input, init));
  const result = await api.executeForget('', attempt, async () => true);
  expect(result?.status).toBe('UNKNOWN'); expect(result?.operationKey).toBe(attempt.key);
  expect(result?.removal).toBe('UNKNOWN'); expect(result?.keepOut).toBe('UNKNOWN');
});

it('S56 preparation refusal is truthful and does not become a success through an empty read', async () => {
  const attempt = api.createForgetAttempt(path, 'FOLDER');
  fetchMock.mockResolvedValue(json({ success: false, errorClass: 'HANDLER_FAILURE',
    errorCode: 'FORGET_PREPARATION_REFUSED', message: 'A candidate is already building' }));
  const result = await api.executeForget('', attempt, async () => true);
  expect(result?.status).toBe('REFUSED'); expect(result?.keepOut).toBe('NOT_INSTALLED');
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('RF55 Unix backslashes and trailing whitespace are not separators or wildcard prefixes', () => {
  expect(api.forgetParentFolder('/docs/folder\\name.txt ')).toBe('/docs');
  expect(api.forgetParentFolder('/file')).toBeNull();
  expect(api.forgetParentFolder('C:\\folder\\name.txt ')).toBe('C:\\folder');
  expect(api.forgetContainsPath('/docs/folder ', 'FOLDER', '/docs/folder /file')).toBe(true);
  expect(api.forgetContainsPath('/docs/folder ', 'FOLDER', '/docs/folder/file')).toBe(false);
  expect(api.forgetContainsPath('/docs/folder ', 'FILE', '/docs/folder /file')).toBe(false);
  expect(api.forgetContainsPath('/docs/back\\name', 'FILE', '/docs/back/name')).toBe(false);
  expect(api.forgetContainsPath('C:\\DOCS\\Name', 'FOLDER', 'c:/docs/name/file')).toBe(true);
});

it('S56 unfinished pagination is explicit and malformed or failed reads cannot become empty lists', async () => {
  fetchMock.mockResolvedValueOnce(json({ items: [], complete: true, nextId: null }));
  await expect(api.readUnfinishedForgets('', 17, undefined, 1)).resolves.toEqual({ items: [], complete: true, nextId: null });
  expect(String(fetchMock.mock.calls[0]?.[0])).toBe('/api/indexing/forgets?afterId=17&limit=1');
  fetchMock.mockResolvedValueOnce(json({}));
  await expect(api.readUnfinishedForgets('')).rejects.toThrow();
  fetchMock.mockResolvedValueOnce(json({ items: [], complete: false, nextId: null }));
  await expect(api.readUnfinishedForgets('')).rejects.toThrow();
  fetchMock.mockResolvedValueOnce(json({ error: 'unreadable' }, 503));
  await expect(api.readUnfinishedForgets('')).rejects.toThrow();
});
