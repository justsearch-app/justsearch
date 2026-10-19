// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchWithAdmissionWait } from './admissionFetch.js';
import { CLIENT_KIND_HEADER, withClientKind } from './clientKind.js';
import { authorizedFetch } from '../shell-v0/api/authorizedFetch.js';

function sentKind(init: RequestInit | undefined): string | null {
  return new Headers(init?.headers).get(CLIENT_KIND_HEADER);
}

const JSON_TYPE = { 'Content-Type': 'application/json' };

afterEach(() => vi.unstubAllGlobals());

describe('the webview declares X-JustSearch-Client-Kind: WEBVIEW only on already-preflighted requests', () => {
  it('never adds it to a GET or HEAD, so a simple read gains no CORS preflight', () => {
    const signal = new AbortController().signal;
    const get = { method: 'GET', signal };
    expect(withClientKind('http://127.0.0.1:1/api/status', get)).toBe(get);
    expect(sentKind(withClientKind('http://127.0.0.1:1/api/status'))).toBeNull();
    expect(sentKind(withClientKind('u', { headers: JSON_TYPE }))).toBeNull();
    expect(sentKind(withClientKind('u', { method: 'get', headers: JSON_TYPE }))).toBeNull();
    expect(sentKind(withClientKind('u', { method: 'HEAD', headers: JSON_TYPE }))).toBeNull();
    expect(sentKind(withClientKind(new Request('http://127.0.0.1:1/api/status')))).toBeNull();
  });

  it('never adds it to a non-JSON write: multipart, text body or no body', () => {
    const form = new FormData();
    form.append('file', new Blob(['x']), 'a.txt');
    expect(sentKind(withClientKind('u', { method: 'POST', body: form }))).toBeNull();
    expect(sentKind(withClientKind('u', { method: 'POST', body: 'x' }))).toBeNull();
    expect(
      sentKind(withClientKind('u', { method: 'POST', body: 'x', headers: { 'Content-Type': 'text/plain' } })),
    ).toBeNull();
    expect(sentKind(withClientKind('u', { method: 'DELETE' }))).toBeNull();
  });

  it('adds it to a non-GET with a JSON body and keeps every other init field', () => {
    const signal = new AbortController().signal;
    for (const method of ['POST', 'PUT', 'DELETE', 'patch']) {
      const init = withClientKind('u', { method, headers: JSON_TYPE, body: '{}', signal });
      expect(sentKind(init)).toBe('WEBVIEW');
      expect(init.method).toBe(method);
      expect(init.body).toBe('{}');
      expect(init.signal).toBe(signal);
    }
    const charset = { method: 'POST', headers: { 'content-type': 'Application/JSON; charset=utf-8' } };
    expect(sentKind(withClientKind('u', charset))).toBe('WEBVIEW');
  });

  it('keeps the caller header shape: record, Headers and tuple list', () => {
    const record = withClientKind('u', { method: 'POST', headers: { ...JSON_TYPE } });
    expect(record.headers).toEqual({ ...JSON_TYPE, [CLIENT_KIND_HEADER]: 'WEBVIEW' });

    const headers = withClientKind('u', { method: 'POST', headers: new Headers(JSON_TYPE) }).headers as Headers;
    expect(headers).toBeInstanceOf(Headers);
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(headers.get(CLIENT_KIND_HEADER)).toBe('WEBVIEW');

    const list = withClientKind('u', { method: 'POST', headers: [['Content-Type', 'application/json']] }).headers;
    expect(list).toEqual([['Content-Type', 'application/json'], [CLIENT_KIND_HEADER, 'WEBVIEW']]);
  });

  it('never overrides a kind the caller declared, in any casing', () => {
    const init = { method: 'POST', headers: { ...JSON_TYPE, 'x-justsearch-client-kind': 'CLI' } };
    expect(withClientKind('u', init)).toBe(init);
  });

  it('reads method and headers from a Request input and keeps its headers', () => {
    const request = new Request('http://127.0.0.1:1/api/x', {
      method: 'POST',
      headers: { ...JSON_TYPE, A: '1' },
      body: '{}',
    });
    const headers = new Headers(withClientKind(request).headers);
    expect(headers.get('A')).toBe('1');
    expect(headers.get(CLIENT_KIND_HEADER)).toBe('WEBVIEW');
  });

  it('the admission seam sends it on the first attempt and on every retry of a JSON write', async () => {
    vi.useFakeTimers();
    try {
      const refused = new Response(
        JSON.stringify({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true }),
        { status: 429, headers: { 'Retry-After': '1' } });
      const send = vi.fn().mockResolvedValueOnce(refused).mockResolvedValueOnce(new Response('{}'));
      const result = fetchWithAdmissionWait(send, 'http://127.0.0.1:1/api/chat/runs', {
        method: 'POST', body: '{}', headers: { ...JSON_TYPE },
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

  it('shell-v0 authorizedFetch: a GET goes without it, a JSON write carries it', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}'));
    vi.stubGlobal('fetch', fetchMock);
    await authorizedFetch('http://127.0.0.1:1/api/status');
    expect(sentKind(fetchMock.mock.calls[0]?.[1] as RequestInit)).toBeNull();

    await authorizedFetch('http://127.0.0.1:1/api/knowledge/disposition', {
      method: 'POST', headers: { ...JSON_TYPE }, body: '{}',
    });
    expect(sentKind(fetchMock.mock.calls.at(-1)?.[1] as RequestInit)).toBe('WEBVIEW');
  });
});
