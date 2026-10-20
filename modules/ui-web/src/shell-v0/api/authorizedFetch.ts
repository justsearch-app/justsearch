// SPDX-License-Identifier: Apache-2.0
/** Authenticated Engine requests, with one token refresh after a pre-execution rejection. */

import {
  SESSION_TOKEN_HEADER,
  getSessionToken,
  invalidateSessionToken,
  resolveSessionTokenFromTauri,
} from '../../api/http.js';
import { fetchWithAdmissionWait } from '../../api/admissionFetch.js';
import { observeStatusResponse } from '../../api/statusInvalidation.js';

/** The backend's error code for "this request needs a session token it did not carry". */
const TOKEN_REQUIRED_CODE = 'UI_TOKEN_REQUIRED';

/**
 * The one sanctioned reference to the raw global — this seam's exit. Resolved per
 * call (never bound at module load) so runtime fetch mocks still take effect.
 */
function globalFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  // eslint-disable-next-line no-restricted-syntax
  return globalThis.fetch(input, init);
}

function headerAlreadySet(headers: HeadersInit | undefined): boolean {
  if (!headers) return false;
  const key = SESSION_TOKEN_HEADER.toLowerCase();
  if (headers instanceof Headers) return headers.has(SESSION_TOKEN_HEADER);
  if (Array.isArray(headers)) return headers.some(([k]) => k.toLowerCase() === key);
  return Object.keys(headers).some((k) => k.toLowerCase() === key);
}

function withToken(headers: HeadersInit | undefined, token: string): HeadersInit {
  if (headers instanceof Headers) {
    const merged = new Headers(headers);
    merged.set(SESSION_TOKEN_HEADER, token);
    return merged;
  }
  if (Array.isArray(headers)) {
    return [...headers, [SESSION_TOKEN_HEADER, token]];
  }
  // Plain-record shape is preserved (call sites and their tests read it back as one).
  return { ...(headers ?? {}), [SESSION_TOKEN_HEADER]: token } as Record<string, string>;
}

/** Adds the desktop token on every method; an explicit caller header wins. */
export async function authorizedFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const response = await fetchWithAdmissionWait(sendAuthorized, input, init);
  await observeStatusResponse(input, response);
  return response;
}

async function sendAuthorized(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  // A Request body is single-use, so the retry needs its own copy taken BEFORE the first send.
  const retryInput =
    typeof Request !== 'undefined' && input instanceof Request ? input.clone() : input;

  const response = await sendWithToken(input, init);
  if (!(await isStaleTokenRejection(response))) {
    return response;
  }

  // Tempdoc 805 G.1: the backend says this token is not the live one — the binding died under us
  // (a restart the shell's event has not delivered yet). Drop it, re-resolve, retry EXACTLY once;
  // a second rejection is the answer, not a loop.
  invalidateSessionToken();
  return sendWithToken(retryInput, init);
}

async function sendWithToken(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  // Ordering matters: resolve BEFORE building headers, so a request issued
  // during cold start blocks on the token instead of racing past it.
  await resolveSessionTokenFromTauri();
  const token = getSessionToken();

  // Headers carried by a Request input are only visible when `init` supplies
  // none — that mirrors fetch's own precedence (init.headers replaces them).
  const callerHeaders =
    init?.headers ??
    (typeof Request !== 'undefined' && input instanceof Request ? input.headers : undefined);

  if (!token || headerAlreadySet(callerHeaders)) {
    return globalFetch(input, init);
  }

  return globalFetch(input, { ...init, headers: withToken(callerHeaders, token) });
}

/**
 * True only for a 401 whose body names {@link TOKEN_REQUIRED_CODE}. Every other 401 (and every
 * other status) is the caller's answer — re-resolving would not change it.
 *
 * Reads a CLONE so the caller still gets an unconsumed body.
 */
async function isStaleTokenRejection(response: Response): Promise<boolean> {
  if (response.status !== 401) return false;
  try {
    const body = (await response.clone().json()) as { errorCode?: unknown };
    return body?.errorCode === TOKEN_REQUIRED_CODE;
  } catch {
    return false; // unparseable body — not a claim we can act on
  }
}
