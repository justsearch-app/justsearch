// SPDX-License-Identifier: Apache-2.0
/**
 * The webview's declared caller kind (ADR-0050).
 *
 * The Engine resolves every request to an `EngineContext`; its `ClientKind` comes from this header,
 * and a missing header resolves to WEBVIEW. The value is a declaration, not a credential: it never
 * makes a request a human request on its own (the chat request also has to ask to load).
 *
 * The packaged webview calls the Engine cross-origin, and a custom header makes a request
 * non-simple. Declaring it everywhere would add a CORS preflight to every GET, so the webview
 * declares it only on requests that are already preflighted: a non-GET with a JSON body
 * (`Content-Type: application/json`). GETs, multipart uploads and SSE streams go without it and
 * keep resolving to WEBVIEW by default.
 */
export const CLIENT_KIND_HEADER = 'X-JustSearch-Client-Kind';
const WEBVIEW_CLIENT_KIND = 'WEBVIEW';

function headerValue(headers: HeadersInit, name: string): string | null {
  const key = name.toLowerCase();
  if (headers instanceof Headers) return headers.get(name);
  if (Array.isArray(headers)) {
    const found = headers.find(([k]) => k.toLowerCase() === key);
    return found ? found[1] : null;
  }
  const record = headers as Record<string, string>;
  const found = Object.keys(record).find((k) => k.toLowerCase() === key);
  return found === undefined ? null : record[found] ?? null;
}

function requestOf(input: RequestInfo | URL): Request | undefined {
  return typeof Request !== 'undefined' && input instanceof Request ? input : undefined;
}

/** Whether the request is already preflighted: a non-GET, non-HEAD with a JSON content type. */
function alreadyPreflighted(
  input: RequestInfo | URL,
  headers: HeadersInit | undefined,
  init: RequestInit | undefined,
): boolean {
  const method = (init?.method ?? requestOf(input)?.method ?? 'GET').toUpperCase();
  if (method === 'GET' || method === 'HEAD') return false;
  const contentType = headers ? headerValue(headers, 'Content-Type') : null;
  return contentType?.split(';')[0]?.trim().toLowerCase() === 'application/json';
}

/**
 * `init` with the webview client kind declared when the request is already preflighted (see the
 * module note); otherwise `init` unchanged. A kind the caller set explicitly wins. The shape of the
 * caller's headers is kept (a plain record stays a plain record), and headers carried only by a
 * `Request` input are kept too, because `init.headers` would otherwise replace them.
 */
export function withClientKind(input: RequestInfo | URL, init?: RequestInit): RequestInit {
  const headers = init?.headers ?? requestOf(input)?.headers;
  if (!alreadyPreflighted(input, headers, init)) return init ?? {};
  if (headers && headerValue(headers, CLIENT_KIND_HEADER) !== null) return init ?? {};
  let declared: HeadersInit;
  if (headers instanceof Headers) {
    const merged = new Headers(headers);
    merged.set(CLIENT_KIND_HEADER, WEBVIEW_CLIENT_KIND);
    declared = merged;
  } else if (Array.isArray(headers)) {
    declared = [...headers, [CLIENT_KIND_HEADER, WEBVIEW_CLIENT_KIND]];
  } else {
    declared = { ...(headers ?? {}), [CLIENT_KIND_HEADER]: WEBVIEW_CLIENT_KIND };
  }
  return { ...(init ?? {}), headers: declared };
}
