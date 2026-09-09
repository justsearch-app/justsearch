// SPDX-License-Identifier: Apache-2.0
import { reportAdmissionWait } from '../shell-v0/state/admissionWaitNotice.js';

const CAPACITY_CODES = new Set(['ADMISSION_CONTEXT_LIMIT', 'ADMISSION_ENGINE_LIMIT']);

/** Retry only explicit pre-execution refusals. Network failures and committed streams are never replayed. */
export async function fetchWithAdmissionWait(
  send: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>,
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const reusable = typeof Request !== 'undefined' && input instanceof Request ? input.clone() : input;
  const signal = init?.signal ?? (typeof Request !== 'undefined' && input instanceof Request ? input.signal : undefined);
  // A streaming upload is single-use. Its caller must supply a fresh body before retrying.
  const replayable = !(typeof ReadableStream !== 'undefined' && init?.body instanceof ReadableStream);
  let attemptInput = input;
  for (;;) {
    signal?.throwIfAborted();
    const response = await send(attemptInput, init);
    if (response.status !== 429 && response.status !== 503) return response;
    let code: unknown;
    let retrySafe = false;
    try {
      const refusal = (await response.clone().json()) as { errorCode?: unknown; retrySafe?: unknown };
      code = refusal.errorCode;
      retrySafe = refusal.retrySafe === true;
    } catch {
      return response;
    }
    const capacity = response.status === 429 && typeof code === 'string' && CAPACITY_CODES.has(code);
    const upgrade = response.status === 503 && code === 'UPGRADE_PREPARING';
    if ((!capacity && !upgrade) || !retrySafe || !replayable) return response;
    const delay = retryDelay(response.headers.get('Retry-After'));
    reportAdmissionWait(upgrade, delay);
    await response.body?.cancel();
    await wait(delay, signal);
    attemptInput = typeof Request !== 'undefined' && reusable instanceof Request ? reusable.clone() : reusable;
  }
}

function retryDelay(header: string | null): number {
  if (header === null) return 1_000;
  const seconds = /^\d+$/.test(header.trim()) ? Number(header) : NaN;
  const delay = Number.isFinite(seconds) ? seconds * 1_000 : Date.parse(header) - Date.now();
  // Browser timers overflow above signed-int milliseconds. A missing or invalid hint uses one second.
  return Number.isFinite(delay) ? Math.min(2_147_483_647, Math.max(100, delay)) : 1_000;
}

function wait(delay: number, signal?: AbortSignal | null): Promise<void> {
  return new Promise((resolve, reject) => {
    const aborted = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', aborted);
      reject(signal?.reason ?? new DOMException('Request cancelled', 'AbortError'));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', aborted);
      resolve();
    }, delay);
    signal?.addEventListener('abort', aborted, { once: true });
    if (signal?.aborted) aborted();
  });
}
