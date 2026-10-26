// SPDX-License-Identifier: Apache-2.0
import { request } from '../http.js';
import { forgetOutcomeSchema, type ForgetOutcome } from '../generated/schema-types/forget-outcome.js';
import { forgetPageSchema, type ForgetPage } from '../generated/schema-types/forget-page.js';
import { createOperationKey } from '../operationKey.js';
import { OperationClient, OperationError } from '../../shell-v0/operations/OperationClient.js';

export type ForgetScope = NonNullable<ForgetOutcome['scope']>;

/** A node-owned notification for the Stage that already retains the originating surface. */
export const FORGET_COMPLETED_EVENT = 'jf-indexed-forget-completed';
export interface IndexedForgetCompletion {
  readonly apiBase: string;
  readonly owner: object | undefined;
  readonly outcome: ForgetOutcome;
}

export function completedForget(value: unknown): ForgetOutcome | null {
  const parsed = forgetOutcomeSchema.safeParse(value);
  return parsed.success && parsed.data.status === 'COMPLETE' && parsed.data.removal === 'CONFIRMED'
    && parsed.data.operationKey !== null && parsed.data.path !== null && parsed.data.scope !== null
    ? parsed.data : null;
}

export function publishForgetCompletion(node: HTMLElement, apiBase: string, outcome: ForgetOutcome,
  owner: object | undefined): void {
  const confirmed = completedForget(outcome);
  if (confirmed !== null) node.dispatchEvent(new CustomEvent<IndexedForgetCompletion>(FORGET_COMPLETED_EVENT,
    { detail: { apiBase, owner, outcome: confirmed } }));
}

function outcomeFor(value: unknown, key: string, target?: Pick<ForgetAttempt, 'path' | 'scope'>): ForgetOutcome | null {
  const parsed = forgetOutcomeSchema.safeParse(value);
  if (!parsed.success || parsed.data.operationKey !== key
      || parsed.data.status === 'COMPLETE' && (parsed.data.removal !== 'CONFIRMED'
        || parsed.data.path === null || parsed.data.scope === null)
      || target !== undefined && (parsed.data.path !== null && parsed.data.path !== target.path
        || parsed.data.scope !== null && parsed.data.scope !== target.scope)) return null;
  return parsed.data;
}

/** Unlike an ordinary display projection, malformed removal evidence cannot establish success. */
export async function readForget(base: string, key: string, signal?: AbortSignal): Promise<ForgetOutcome> {
  const outcome = outcomeFor(await request(base, `/api/indexing/forgets/${encodeURIComponent(key)}`, { signal, retries: 1 }), key);
  if (outcome === null) throw new Error('The forget observation does not establish this intent');
  return outcome;
}

export async function readUnfinishedForgets(base: string, afterId = 0, signal?: AbortSignal, limit = 100): Promise<ForgetPage> {
  return parseForgetPage(await request(base, `/api/indexing/forgets?afterId=${afterId}&limit=${limit}`, { signal, retries: 1 }), afterId);
}

/** The bounded DTO's cursor invariants also apply at its JavaScript trust boundary. */
export function parseForgetPage(value: unknown, afterId = 0): ForgetPage {
  const page = forgetPageSchema.parse(value);
  if (page.items.length > 100 || page.complete !== (page.nextId === null)
      || page.nextId !== null && (page.items.length === 0 || page.nextId <= afterId)) {
    throw new Error('The unfinished forget page does not establish a complete observation');
  }
  return page;
}

export interface ForgetConsent {
  summary: string;
  typed: boolean;
  operationId: string;
}

export interface ForgetAttempt {
  readonly key: string;
  readonly path: string;
  readonly scope: ForgetScope;
}

export function createForgetAttempt(path: string, scope: ForgetScope): ForgetAttempt {
  return Object.freeze({ key: createOperationKey(), path, scope });
}

/** Same key throughout consent, execution and lost-response observation; never a replacement add. */
export async function executeForget(base: string, attempt: ForgetAttempt,
  consent: (prompt: ForgetConsent) => Promise<boolean>): Promise<ForgetOutcome | null> {
  return invoke(base, 'core.forget-indexed-path', { path: attempt.path, scope: attempt.scope },
    attempt.key, attempt.key, consent, attempt);
}

export async function decideForget(base: string, forgetKey: string, decision: 'retry' | 'withdraw',
  consent: (prompt: ForgetConsent) => Promise<boolean>, target?: Pick<ForgetAttempt, 'path' | 'scope'>): Promise<ForgetOutcome | null> {
  return invoke(base, `core.${decision}-forget`, { forgetKey }, createOperationKey(), forgetKey, consent, target);
}

async function invoke(base: string, operationId: string, args: Record<string, unknown>,
  invocationKey: string, originalKey: string,
  consent: (prompt: ForgetConsent) => Promise<boolean>, target?: Pick<ForgetAttempt, 'path' | 'scope'>): Promise<ForgetOutcome | null> {
  let cancelled = false;
  try {
    const result = await new OperationClient({ apiBase: base }).invokeWithConsent(operationId,
      { args, idempotencyKey: invocationKey, transport: 'BUTTON' }, {
        requestConsent: async prompt => {
          // This is the server's frozen exact-target preview. It already distinguishes the
          // outside-root FILE exception and visibly escapes path whitespace/control characters.
          const approved = await consent({ summary: prompt.argsSummary ?? prompt.purpose ?? '',
            typed: prompt.gateBehavior === 'TYPED_CONFIRM', operationId });
          cancelled = !approved;
          return { approved, allowAlways: false };
        },
      });
    const parsed = outcomeFor(result.structuredData?.forget, originalKey, target);
    if (parsed !== null) return parsed;
  } catch (error: unknown) {
    if (cancelled) return null;
    const parsed = outcomeFor(error instanceof OperationError ? error.errorDetails?.forget : null, originalKey, target);
    if (parsed !== null) return parsed;
    if (operationId === 'core.forget-indexed-path' && error instanceof OperationError
        && error.errorCode === 'FORGET_PREPARATION_REFUSED') {
      return { operationKey: originalKey, path: typeof args.path === 'string' ? args.path : null,
        scope: args.scope === 'FILE' || args.scope === 'FOLDER' ? args.scope : null,
        status: 'REFUSED', removal: 'UNCONFIRMED', keepOut: args.scope === 'FOLDER' ? 'NOT_INSTALLED' : 'UNKNOWN',
        reason: 'AUTHORITY_CHANGED', explanation: `${error.message} This request was refused before operational admission; no removal or keep-out was performed.` };
    }
  }
  // Includes lost/late transport and malformed receipts. A read cannot authorize another effect.
  try {
    const observed = outcomeFor(await readForget(base, originalKey), originalKey, target);
    if (observed === null) throw new Error('The observation differs from the frozen forget target');
    return observed;
  }
  catch {
    return { operationKey: originalKey, path: typeof args.path === 'string' ? args.path : null,
      scope: args.scope === 'FILE' || args.scope === 'FOLDER' ? args.scope : null,
      status: 'UNKNOWN', removal: 'UNKNOWN', keepOut: 'UNKNOWN', reason: 'OUTCOME_UNCONFIRMED',
      explanation: 'The forget outcome could not be confirmed. No completed removal is being claimed. Review unfinished forgets in Library before trying again.' };
  }
}

export function forgetObservationText(outcome: ForgetOutcome): string {
  const removal = outcome.removal === 'CONFIRMED' ? 'Removal confirmed.'
    : outcome.removal === 'UNKNOWN' ? 'Removal outcome unknown.' : 'Removal not confirmed.';
  const keepOut = { INSTALLED: 'Exact keep-out installed.', NOT_REQUIRED: 'No permanent keep-out applies.',
    NOT_INSTALLED: 'Keep-out not installed.', RELEASED: 'This keep-out was released.', UNKNOWN: 'Keep-out outcome unknown.' }[outcome.keepOut];
  return `${outcome.explanation} ${removal} ${keepOut}`;
}

/** Index-key rules: Unix backslashes remain filename bytes; drive/UNC paths use Windows rules. */
export function forgetContainsPath(target: string, scope: ForgetScope, candidate: string): boolean {
  const windows = /^(?:[a-z]:[\\/]|\\\\|\/\/)/iu.test(target);
  const key = (path: string): string => windows ? path.replace(/\\/gu, '/').toLowerCase() : path;
  const expected = key(target); const observed = key(candidate);
  return observed === expected || scope === 'FOLDER' && observed.startsWith(`${expected}/`);
}

export function forgetParentFolder(path: string): string | null {
  const windows = /^(?:[a-z]:[\\/]|\\\\|\/\/)/iu.test(path);
  const at = windows ? Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) : path.lastIndexOf('/');
  if (at < 1 || windows && at === 2 && /^[a-z]:/iu.test(path)) return null;
  return path.slice(0, at);
}
