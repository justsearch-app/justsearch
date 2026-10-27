// SPDX-License-Identifier: Apache-2.0
/**
 * lastViewedConversation — Tempdoc 609 Phase 3 (M3, identity-recoverable).
 *
 * The ONE per-tab pointer to the conversation this tab was last viewing. Returning to the chat
 * surface after navigating away should restore that thread automatically rather than collapsing to
 * the "Continue your last conversation?" card — which keys off the GLOBAL most-recent session
 * (`getRecentSessions()[0]`), not what THIS tab had open, and so can offer the wrong thread (609 §J.2).
 *
 * Scope: per-tab `sessionStorage` is deliberate — "what THIS tab was viewing" (it also persists across
 * a same-tab reload, like the agent run's per-tab reattach). Cross-tab restore is NOT wanted here: a
 * second tab should land cold, not adopt another tab's open thread.
 *
 * Single authority: this module is the only reader/writer of the key. A stale pointer (the
 * conversation was deleted) is harmless — `loadConversation` resolves a missing session to an empty
 * thread. That harmlessness is what keeps a browser-local pointer legitimate HERE while run
 * discovery moved to the backend enumeration (`liveRuns.ts`, tempdoc 834 §15.3): a stale pointer to
 * a dead conversation costs an empty thread, whereas a stale pointer to a dead RUN was a claim about
 * what the product is doing right now, which only the backend can hold.
 */
const KEY = 'justsearch.lastViewedConversation.v1';

/** Record that this tab is now viewing `sessionId`. */
export function setLastViewedConversation(sessionId: string): void {
  try {
    if (sessionId) sessionStorage.setItem(KEY, sessionId);
  } catch {
    // sessionStorage unavailable (private mode / SSR / quota) — auto-restore degrades to the
    // resume card; never throw into the chat lifecycle.
  }
}

/** Forget the pointer (explicit New chat / Start fresh). */
export function clearLastViewedConversation(): boolean {
  try {
    sessionStorage.removeItem(KEY);
    return sessionStorage.getItem(KEY) === null;
  } catch {
    return false;
  }
}

/** The conversation this tab should auto-restore on mount, or null for a cold start. */
export function readLastViewedConversation(): string | null {
  try {
    const v = sessionStorage.getItem(KEY);
    return v && v.length > 0 ? v : null;
  } catch {
    return null;
  }
}
