import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mocks = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => false),
  invoke: vi.fn(),
}));

// Mock the tauriRuntime module before importing http
vi.mock('../utils/tauriRuntime', () => ({
  isTauriRuntime: mocks.isTauriRuntime,
}));

vi.mock('@tauri-apps/api/core', () => ({
  invoke: mocks.invoke,
}));

describe('http module', () => {
  let originalFetch: typeof fetch;
  let originalWindow: typeof window | undefined;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    originalWindow = (globalThis as { window?: typeof window }).window;
    mocks.isTauriRuntime.mockReturnValue(false);
    mocks.invoke.mockReset();
    // Reset module state between tests
    vi.resetModules();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    if (originalWindow === undefined) {
      delete (globalThis as { window?: typeof window }).window;
    } else {
      (globalThis as { window?: typeof window }).window = originalWindow;
    }
    vi.clearAllMocks();
    vi.unstubAllEnvs();
  });

  describe('SESSION_TOKEN_HEADER constant', () => {
    it('should be X-JustSearch-Session', async () => {
      const { SESSION_TOKEN_HEADER } = await import('./http');
      expect(SESSION_TOKEN_HEADER).toBe('X-JustSearch-Session');
    });
  });

  describe('getSessionToken', () => {
    it('returns null when token has not been resolved', async () => {
      const { getSessionToken } = await import('./http');
      expect(getSessionToken()).toBeNull();
    });
  });

  describe('request function', () => {
    it('does not include token header on GET requests', async () => {
      let capturedInit: RequestInit | undefined;
      globalThis.fetch = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        capturedInit = init;
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ data: 'test' }),
        });
      });

      const { request } = await import('./http');
      await request('http://localhost:3000', '/api/test', { method: 'GET' });

      expect(capturedInit).toBeDefined();
      // GET request should not have the token header (even if cached)
      const headers = capturedInit?.headers as Record<string, string> | undefined;
      expect(headers?.['X-JustSearch-Session']).toBeUndefined();
    });

    it('includes Content-Type header when body is provided', async () => {
      let capturedInit: RequestInit | undefined;
      globalThis.fetch = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        capturedInit = init;
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ data: 'test' }),
        });
      });

      const { request } = await import('./http');
      await request('http://localhost:3000', '/api/test', {
        method: 'POST',
        body: { key: 'value' },
      });

      expect(capturedInit).toBeDefined();
      const headers = capturedInit?.headers as Record<string, string>;
      expect(headers?.['Content-Type']).toBe('application/json');
    });

    it('serializes body as JSON', async () => {
      let capturedInit: RequestInit | undefined;
      globalThis.fetch = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        capturedInit = init;
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ data: 'test' }),
        });
      });

      const { request } = await import('./http');
      await request('http://localhost:3000', '/api/test', {
        method: 'POST',
        body: { key: 'value' },
      });

      expect(capturedInit?.body).toBe('{"key":"value"}');
    });

    it('passes AbortSignal when provided', async () => {
      let capturedInit: RequestInit | undefined;
      globalThis.fetch = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        capturedInit = init;
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ data: 'test' }),
        });
      });

      const controller = new AbortController();
      const { request } = await import('./http');
      await request('http://localhost:3000', '/api/test', {
        method: 'GET',
        signal: controller.signal,
      });

      expect(capturedInit?.signal).toBe(controller.signal);
    });

    it('retries on server errors', async () => {
      let callCount = 0;
      globalThis.fetch = vi.fn().mockImplementation(() => {
        callCount++;
        if (callCount < 3) {
          return Promise.resolve({
            ok: false,
            status: 500,
            statusText: 'Internal Server Error',
            text: () => Promise.resolve('Server error'),
          });
        }
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ data: 'success' }),
        });
      });

      // Override sleep to speed up test
      vi.doMock('./http', async (importOriginal) => {
        const original = await importOriginal() as typeof import('./http');
        return {
          ...original,
          sleep: () => Promise.resolve(),
        };
      });

      const { request } = await import('./http');
      const result = await request('http://localhost:3000', '/api/test', { retries: 3 });

      expect(callCount).toBe(3);
      expect(result).toEqual({ data: 'success' });
    });

    it('does not retry on 4xx errors', async () => {
      let callCount = 0;
      globalThis.fetch = vi.fn().mockImplementation(() => {
        callCount++;
        return Promise.resolve({
          ok: false,
          status: 400,
          statusText: 'Bad Request',
          text: () => Promise.resolve('{"error":"Bad request"}'),
        });
      });

      const { request } = await import('./http');
      await expect(request('http://localhost:3000', '/api/test', { retries: 3 }))
        .rejects.toThrow();

      expect(callCount).toBe(1); // Should not retry
    });
  });

  describe('generateSessionToken utility (backend)', () => {
    it('normalizeNonBlankString returns null for empty strings', async () => {
      const { normalizeNonBlankString } = await import('./http');
      expect(normalizeNonBlankString('')).toBeNull();
      expect(normalizeNonBlankString('   ')).toBeNull();
      expect(normalizeNonBlankString(null)).toBeNull();
      expect(normalizeNonBlankString(undefined)).toBeNull();
    });

    it('normalizeNonBlankString returns trimmed string for non-empty', async () => {
      const { normalizeNonBlankString } = await import('./http');
      expect(normalizeNonBlankString('  hello  ')).toBe('hello');
      expect(normalizeNonBlankString('token123')).toBe('token123');
    });
  });

  describe('normalizePort', () => {
    it('returns null for invalid ports', async () => {
      const { normalizePort } = await import('./http');
      expect(normalizePort(null)).toBeNull();
      expect(normalizePort(undefined)).toBeNull();
      expect(normalizePort(0)).toBeNull();
      expect(normalizePort(-1)).toBeNull();
      expect(normalizePort('invalid')).toBeNull();
      expect(normalizePort(NaN)).toBeNull();
    });

    it('returns port number for valid ports', async () => {
      const { normalizePort } = await import('./http');
      expect(normalizePort(3000)).toBe(3000);
      expect(normalizePort('8080')).toBe(8080);
      expect(normalizePort(33221)).toBe(33221);
    });
  });

  describe('resolveApiEndpoint', () => {
    it('does not invent a proxy API for a desktop host with no Engine port', async () => {
      mocks.isTauriRuntime.mockReturnValue(true);
      mocks.invoke.mockResolvedValue(null);
      vi.stubEnv('DEV', true);
      vi.stubEnv('VITE_API_PORT', '');
      vi.stubEnv('VITE_JUSTSEARCH_API_PORT', '');
      (globalThis as { window?: Pick<typeof window, 'location'> }).window = {
        location: { search: '', origin: 'http://localhost:5174' } as Location,
      };
      globalThis.fetch = vi.fn();
      const { resolveApiEndpoint } = await import('./http');
      await expect(resolveApiEndpoint()).resolves.toEqual({ port: null, baseUrl: null, source: 'unresolved' });
      expect(globalThis.fetch).not.toHaveBeenCalled();
      mocks.isTauriRuntime.mockReturnValue(false);
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('no manifest'));
      await expect(resolveApiEndpoint()).resolves.toMatchObject({ baseUrl: 'http://localhost:5174', source: 'proxy' });
    });

    it('uses the Tauri api_port command for packaged desktop origins', async () => {
      mocks.isTauriRuntime.mockReturnValue(true);
      mocks.invoke.mockResolvedValue(8080);
      (globalThis as { window?: Pick<typeof window, 'location'> }).window = {
        location: { search: '', origin: 'http://tauri.localhost' } as Location,
      };
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('manifest unavailable'));

      const { resolveApiEndpoint } = await import('./http');

      await expect(resolveApiEndpoint()).resolves.toMatchObject({
        port: 8080,
        baseUrl: 'http://127.0.0.1:8080',
        source: 'tauri',
      });
      expect(mocks.invoke).toHaveBeenCalledWith('api_port');
      expect(globalThis.fetch).toHaveBeenCalledWith('http://127.0.0.1:8080/api/runtime/manifest', {});
    });
  });
});
