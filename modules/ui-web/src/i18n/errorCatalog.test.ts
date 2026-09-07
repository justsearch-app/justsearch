// @vitest-environment happy-dom
/**
 * Tempdoc 941 — the error catalog's boot must stay re-attemptable until the backend answers.
 *
 * The pre-941 guard was `bootAttempted && the catalog is non-empty`. `bootErrorCatalog` seeds the
 * in-memory catalog from the localStorage body BEFORE it fetches, so a boot that raced an
 * unanswering Head left the guard set AND the catalog non-empty: permanently closed for the life
 * of the document, serving whatever the previous session — or the previous VERSION of the app —
 * had cached. Success is now recorded only where the backend actually answered (200 or 304).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const BASE = 'http://localhost:33221';

describe('errorCatalog', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.resetModules();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.clearAllMocks();
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem('justsearch.errorCatalog.en.body');
      localStorage.removeItem('justsearch.errorCatalog.en.etag');
    }
  });

  function okResponse(messages: Record<string, string>): unknown {
    return {
      ok: true,
      status: 200,
      json: () => Promise.resolve({ messages }),
      headers: { get: () => null },
    };
  }

  it('re-fetches after a failed boot, and stops once the backend answers', async () => {
    const mod = await import('./errorCatalog');
    mod.__resetForTest();
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new Error('backend not up yet'))
      .mockResolvedValue(okResponse({ 'errors.INDEX_UNAVAILABLE': 'The index is unavailable.' }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await mod.bootErrorCatalog(BASE);
    // Nothing landed: the wire message is all there is.
    expect(mod.getErrorMessage({ i18nKey: 'errors.INDEX_UNAVAILABLE', message: 'raw wire' }))
      .toBe('raw wire');

    // The backend came up. The SAME entry point re-attempts — this is what the shell's
    // backend-ready edge (`i18n.ts` watchForBackendReady) calls.
    await mod.bootErrorCatalog(BASE);
    expect(fetchMock.mock.calls.length).toBe(2);
    expect(mod.localizeError({ i18nKey: 'errors.INDEX_UNAVAILABLE', message: 'raw wire' }))
      .toEqual({ message: 'The index is unavailable.', localized: true });

    // A third call is a no-op now that the fetch has been answered.
    await mod.bootErrorCatalog(BASE);
    expect(fetchMock.mock.calls.length).toBe(2);
  });

  it('a stale localStorage body no longer closes the boot (the one-shot trap)', async () => {
    const mod = await import('./errorCatalog');
    mod.__resetForTest();
    localStorage.setItem(
      'justsearch.errorCatalog.en.body',
      JSON.stringify({ 'errors.OLD': 'From the last session' }),
    );
    localStorage.setItem('justsearch.errorCatalog.en.etag', '"stale-etag"');
    const fetchMock = vi.fn().mockRejectedValue(new Error('backend not up yet'));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await mod.bootErrorCatalog(BASE);
    // The seeded body IS used (that part is deliberate) …
    expect(mod.getErrorMessage({ i18nKey: 'errors.OLD' })).toBe('From the last session');
    // … but it must not be mistaken for a successful boot.
    await mod.bootErrorCatalog(BASE);
    expect(fetchMock.mock.calls.length).toBe(2);
  });

  it('an empty baseUrl leaves the boot open for a later attempt', async () => {
    const mod = await import('./errorCatalog');
    mod.__resetForTest();
    const fetchMock = vi
      .fn()
      .mockResolvedValue(okResponse({ 'errors.LATE': 'Resolved late.' }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await mod.bootErrorCatalog('');
    expect(fetchMock).not.toHaveBeenCalled();

    await mod.bootErrorCatalog(BASE);
    expect(mod.getErrorMessage({ i18nKey: 'errors.LATE' })).toBe('Resolved late.');
  });

  it('a 304 closes the boot — the backend answered, the seeded body is current', async () => {
    const mod = await import('./errorCatalog');
    mod.__resetForTest();
    localStorage.setItem(
      'justsearch.errorCatalog.en.body',
      JSON.stringify({ 'errors.CACHED': 'Cached and current.' }),
    );
    localStorage.setItem('justsearch.errorCatalog.en.etag', '"etag"');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 304,
      json: () => Promise.resolve({}),
      headers: { get: () => null },
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await mod.bootErrorCatalog(BASE);
    await mod.bootErrorCatalog(BASE);
    expect(fetchMock.mock.calls.length).toBe(1);
    expect(mod.getErrorMessage({ i18nKey: 'errors.CACHED' })).toBe('Cached and current.');
  });

  it('concurrent boots share one request', async () => {
    const mod = await import('./errorCatalog');
    mod.__resetForTest();
    const fetchMock = vi.fn().mockResolvedValue(okResponse({ 'errors.A': 'A' }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    await Promise.all([
      mod.bootErrorCatalog(BASE),
      mod.bootErrorCatalog(BASE),
      mod.bootErrorCatalog(BASE),
    ]);
    expect(fetchMock.mock.calls.length).toBe(1);
  });
});
