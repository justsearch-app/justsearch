// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchWithAdmissionWait } from './admissionFetch.js';
import { CLIENT_KIND_HEADER, withClientKind } from './clientKind.js';
import { authorizedFetch } from '../shell-v0/api/authorizedFetch.js';

function sentKind(init: RequestInit | undefined): string | null {
  return new Headers(init?.headers).get(CLIENT_KIND_HEADER);
}

afterEach(() => vi.unstubAllGlobals());

describe('the webview declares X-JustSearch-Client-Kind: WEBVIEW', () => {
  it('adds the header to a bare GET and keeps every other init field', () => {
    const signal = new AbortController().signal;
    const init = withClientKind('http://127.0.0.1:1/api/status', { method: 'GET', signal });
    expect(init.headers).toEqual({ [CLIENT_KIND_HEADER]: 'WEBVIEW' });
    expect(init.method).toBe('GET');
    expect(init.signal).toBe(signal);
    expect(sentKind(withClientKind('http://127.0.0.1:1/api/status'))).toBe('WEBVIEW');
  });

  it('keeps the caller header shape: record, Headers and tuple list', () => {
    const record = withClientKind('u', { headers: { 'Content-Type': 'application/json' } });
    expect(record.headers).toEqual({ 'Content-Type': 'application/json', [CLIENT_KIND_HEADER]: 'WEBVIEW' });

    const headers = withClientKind('u', { headers: new Headers({ A: '1' }) }).headers as Headers;
    expect(headers).toBeInstanceOf(Headers);
    expect(headers.get('A')).toBe('1');
    expect(headers.get(CLIENT_KIND_HEADER)).toBe('WEBVIEW');

    const list = withClientKind('u', { headers: [['A', '1']] }).headers;
    expect(list).toEqual([['A', '1'], [CLIENT_KIND_HEADER, 'WEBVIEW']]);
  });

  it('never overrides a kind the caller declared, in any casing', () => {
    const init = { headers: { 'x-justsearch-client-kind': 'CLI' } };
    expect(withClientKind('u', init)).toBe(init);
  });

  it('keeps headers a Request input carries when init has none', () => {
    const request = new Request('http://127.0.0.1:1/api/x', { headers: { A: '1' } });
    const headers = new Headers(withClientKind(request).headers);
    expect(headers.get('A')).toBe('1');
    expect(headers.get(CLIENT_KIND_HEADER)).toBe('WEBVIEW');
  });

  it('the admission seam sends it on the first attempt and on every retry', async () => {
    vi.useFakeTimers();
    try {
      const refused = new Response(
        JSON.stringify({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true }),
        { status: 429, headers: { 'Retry-After': '1' } });
      const send = vi.fn().mockResolvedValueOnce(refused).mockResolvedValueOnce(new Response('{}'));
      const result = fetchWithAdmissionWait(send, 'http://127.0.0.1:1/api/chat/runs', {
        method: 'POST', body: '{}', headers: { 'Content-Type': 'application/json' },
      });
      await vi.advanceTimersByTimeAsync(1_000);
      expect((await result).status).toBe(200);
      expect(send).toHaveBeenCalledTimes(2);
      for (const call of send.mock.calls) {
        expect(sentKind(call[1] as RequestInit)).toBe('WEBVIEW');
        expect(new Headers((call[1] as RequestInit).headers).get('Content-Type')).toBe('application/json');
      }
    } finally {
      vi.useRealTimers();
    }
  });

  it('shell-v0 authorizedFetch carries it through the same seam', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}'));
    vi.stubGlobal('fetch', fetchMock);
    await authorizedFetch('http://127.0.0.1:1/api/status');
    expect(sentKind(fetchMock.mock.calls[0]?.[1] as RequestInit)).toBe('WEBVIEW');
  });
});
