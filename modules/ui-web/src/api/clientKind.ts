// SPDX-License-Identifier: Apache-2.0
/**
 * The webview's declared caller kind (ADR-0050).
 *
 * The Engine resolves every request to an `EngineContext`; its `ClientKind` comes from this header.
 * A missing header still resolves to WEBVIEW today, but chat demand will treat a missing header as
 * an agent, so the webview declares itself on every Engine request rather than relying on the
 * default. The value is a declaration, not a credential: it never makes a request a human request
 * on its own (the chat request also has to ask to load).
 */
export const CLIENT_KIND_HEADER = 'X-JustSearch-Client-Kind';
const WEBVIEW_CLIENT_KIND = 'WEBVIEW';

function hasHeader(headers: HeadersInit, name: string): boolean {
  const key = name.toLowerCase();
  if (headers instanceof Headers) return headers.has(name);
  if (Array.isArray(headers)) return headers.some(([k]) => k.toLowerCase() === key);
  return Object.keys(headers).some((k) => k.toLowerCase() === key);
}

/**
 * `init` with the webview client kind declared. A kind the caller set explicitly wins. The shape of
 * the caller's headers is kept (a plain record stays a plain record), and headers carried only by a
 * `Request` input are kept too, because `init.headers` would otherwise replace them.
 */
export function withClientKind(input: RequestInfo | URL, init?: RequestInit): RequestInit {
  const requestHeaders =
    typeof Request !== 'undefined' && input instanceof Request ? input.headers : undefined;
  const headers = init?.headers ?? requestHeaders;
  if (headers && hasHeader(headers, CLIENT_KIND_HEADER)) return init ?? {};
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
