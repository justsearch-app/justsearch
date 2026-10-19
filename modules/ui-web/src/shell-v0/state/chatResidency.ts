// SPDX-License-Identifier: Apache-2.0
/**
 * Spec A step 9 — the chat window's view of a chat model the Engine unloaded for another GPU user.
 *
 * Two signals reach the app:
 *  - the status readiness carries the AI dimension's reason (`inference.unloaded_gpu_pressure` or
 *    `inference.unloaded_game_mode`) while the model is unloaded; chat is then not "offline": a
 *    person's chat request loads it again (reconciliation F8/CH1);
 *  - a chat request the Engine refuses answers with a typed body (`errorCode`, `reason`,
 *    `forceAllowed`, `retryAfterMs`); `forceAllowed` says "Load anyway" applies (D8: UI only).
 *
 * "Load anyway" is `POST /api/chat/load-anyway` with `{allowLoad: true}` from the app's own client.
 */

/** The AI readiness reasons that mean "unloaded for another GPU user; a request reloads it". */
const CHAT_UNLOADED_REASON_CODES: ReadonlySet<string> = new Set([
  'inference.unloaded_gpu_pressure',
  'inference.unloaded_game_mode',
]);

/**
 * The reason chat is unloaded for another GPU user, or null. A chat the user switched off
 * (`chatEnabledSpec === false`) is off, not unloaded.
 */
export function chatUnloadedReason(
  reasonCodes: readonly string[] | undefined,
  chatEnabledSpec: boolean | undefined,
): string | null {
  if (chatEnabledSpec === false) return null;
  return (reasonCodes ?? []).find((code) => CHAT_UNLOADED_REASON_CODES.has(code)) ?? null;
}

/**
 * Spec A step 7 (F1) — the AI readiness reasons that say why chat failed, as the chat controller
 * classified the failure. Each has its own words in readinessNotice's CAUSE_ROWS.
 */
const CHAT_FAILURE_REASON_CODES: ReadonlySet<string> = new Set([
  'inference.out_of_memory',
  'inference.startup_timeout',
  'inference.start_failed',
  'inference.model_load_failed',
  'inference.crashed',
]);

/**
 * The classified reason chat failed, or null. A chat the user switched off
 * (`chatEnabledSpec === false`) is off, not failed.
 */
export function chatFailureReason(
  reasonCodes: readonly string[] | undefined,
  chatEnabledSpec: boolean | undefined,
): string | null {
  if (chatEnabledSpec === false) return null;
  return (reasonCodes ?? []).find((code) => CHAT_FAILURE_REASON_CODES.has(code)) ?? null;
}

/** A chat request the Engine refused (the typed refusal body). */
export interface ChatRefusalDetail {
  errorCode: string;
  /** The refusal reason, e.g. `GPU_BUSY`, `GAME_MODE`, `RELOAD_BACKOFF`. */
  reason: string;
  /** The words the Engine gives a person. */
  message: string;
  /** True when a person may load chat anyway (the app's "Load anyway"). */
  forceAllowed: boolean;
  /** When a retry may succeed, if the Engine knows. */
  retryAfterMs?: number;
}

/** Parses a refusal body; null for any other body. */
export function parseChatRefusal(text: string): ChatRefusalDetail | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object') return null;
  const body = parsed as Record<string, unknown>;
  if (typeof body.reason !== 'string' || typeof body.forceAllowed !== 'boolean') return null;
  if (typeof body.errorCode !== 'string') return null;
  const detail: ChatRefusalDetail = {
    errorCode: body.errorCode,
    reason: body.reason,
    message: typeof body.error === 'string' ? body.error : 'Chat is not available right now.',
    forceAllowed: body.forceAllowed,
  };
  if (typeof body.retryAfterMs === 'number' && body.retryAfterMs > 0) {
    detail.retryAfterMs = body.retryAfterMs;
  }
  return detail;
}

/** The refusal a failed chat stream carried (`consumeShapeStream` attaches it), or null. */
export function chatRefusalOf(err: unknown): ChatRefusalDetail | null {
  if (!(err instanceof Error)) return null;
  const refusal = (err as Error & { chatRefusal?: ChatRefusalDetail }).chatRefusal;
  return refusal ?? null;
}

/** "Try again in about N s." for a refusal that knows when, otherwise empty. */
export function retryWording(refusal: ChatRefusalDetail): string {
  if (!refusal.retryAfterMs) return '';
  return `Try again in about ${Math.max(1, Math.ceil(refusal.retryAfterMs / 1000))} s.`;
}

/** What "Load anyway" answered. */
export type LoadAnywayResult =
  | { ok: true; loading: boolean }
  | { ok: false; refusal: ChatRefusalDetail | null; status: number };

/**
 * Asks the Engine to load chat for a person despite pressure (`POST /api/chat/load-anyway`).
 *
 * @param fetchImpl the app's authorized fetch (the Engine needs the app's own client)
 */
export async function requestLoadAnyway(
  apiBase: string,
  fetchImpl: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>,
): Promise<LoadAnywayResult> {
  const response = await fetchImpl(`${apiBase}/api/chat/load-anyway`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ allowLoad: true }),
  });
  const text = await response.text().catch(() => '');
  if (response.ok) {
    let loading = response.status === 202;
    try {
      const body = JSON.parse(text) as { loading?: unknown };
      if (typeof body.loading === 'boolean') loading = body.loading;
    } catch {
      // The status says enough.
    }
    return { ok: true, loading };
  }
  return { ok: false, refusal: parseChatRefusal(text), status: response.status };
}
