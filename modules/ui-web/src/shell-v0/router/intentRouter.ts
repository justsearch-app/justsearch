// SPDX-License-Identifier: Apache-2.0
/**
 * IntentRouter — slice 489 §7 / §9 single consumer of normalized intents.
 *
 * Per tempdoc 499: the router implements the Resolve → Dispatch lifecycle.
 * Resolution is mandatory — every intent is resolved against the catalog
 * before dispatch. Unresolved intents never reach handlers.
 */

import type { Intent } from './types.js';
import type { NavigationHandler } from './navigationHandler.js';
import type { InvocationHandler } from './invocationHandler.js';
import type { ResolutionResult, Suggestion } from './resolution.js';
import { interactivePolicy, type RecoveryPolicy } from './recoveryPolicy.js';
import { setPendingForceShape, setPendingAutoRun, setPendingSelection } from '../utils/compose.js';

export interface RouterConfig {
  navigationHandler: NavigationHandler;
  invocationHandler: InvocationHandler;
  isKnownSurface: (surfaceId: string) => boolean;
  resolveSurface: (rawId: string) => ResolutionResult;
  resolveOperation: (rawId: string) => ResolutionResult;
  /** Recovery policy for resolution failures (default: interactivePolicy). */
  recoveryPolicy?: RecoveryPolicy;
  /**
   * 548 S4-A: the surface a `query` intent is lowered to (the search surface). Search Thread S5b —
   * the standalone Search rail surface is retired; the retrieve tier folded into the one window.
   * Defaults to `core.search-v3-surface`.
   */
  querySurfaceId?: string;
  /**
   * 548 S4-A: the state key the query surface restores the query text from.
   * Defaults to `query` (matches the search surface's searchState adapter).
   */
  queryStateKey?: string;
  /**
   * 548 §4.5: the surface an `answer` intent is lowered to (the shape-hosting chat surface).
   * Defaults to `core.search-v3-surface`.
   */
  answerSurfaceId?: string;
  /**
   * 548 §4.5: the state key the chat surface restores the answer prompt from.
   * Defaults to `query` (matches the unified-chat adapter).
   */
  answerStateKey?: string;
  /**
   * Tempdoc 571 §11 / 578 — fired when a navigation is auto-corrected/redirected from `originalId` to
   * `targetId` (the actual navigation that ran). Generic hook — the composition concern lives in the
   * caller (Shell wires it to fire `requestMemberTab` when the redirect is a member→host one), keeping
   * this router free of surface-composition knowledge.
   */
  onRedirect?: (originalId: string, targetId: string) => void;
}

/** Default search-surface id a `query` intent activates. Search Thread S5b — the standalone
 *  Search rail surface is retired; the retrieve tier folded into the one window. */
const DEFAULT_QUERY_SURFACE_ID = 'core.search-v3-surface';
/** Default state key the search surface restores the query text from. */
const DEFAULT_QUERY_STATE_KEY = 'query';
/** Default chat-surface id an `answer` intent activates. */
const DEFAULT_ANSWER_SURFACE_ID = 'core.search-v3-surface';
/** Default state key the chat surface restores the answer prompt from. */
const DEFAULT_ANSWER_STATE_KEY = 'query';

export interface DispatchOptions {
  /** Internal history/journal provenance, never a public URL field or authority. */
  taskOrigin?: 'fresh' | 'restore';
  /** When false, the handler skips pushAddress. Default true. */
  pushHistory?: boolean;
  /** Per-dispatch policy override (tempdoc 499 F4). Shadows the router-level policy for this one dispatch. */
  recoveryPolicyOverride?: RecoveryPolicy;
}

/**
 * Structured outcome passed to subscribers (tempdoc 499 §4.5).
 *
 *   - `dispatched` — exact match; handler ran.
 *   - `redirected` — alias hit; handler ran with canonical ID.
 *   - `unresolved` — no match; carries diagnosis and ranked alternatives.
 */
export type DispatchOutcome =
  | { readonly status: 'dispatched' }
  | { readonly status: 'redirected'; readonly canonicalId: string; readonly originalId: string; readonly reason: string }
  | { readonly status: 'auto-corrected'; readonly canonicalId: string; readonly originalId: string }
  | { readonly status: 'unresolved'; readonly attemptedId: string; readonly diagnosis: { mode: string; detail: string }; readonly alternatives: Suggestion[] };

export type IntentListener = (intent: Intent, outcome: DispatchOutcome) => void;

export interface IntentRouter {
  dispatch(intent: Intent, options?: DispatchOptions): Promise<unknown>;
  subscribe(listener: IntentListener): () => void;
}

export function createIntentRouter(config: RouterConfig): IntentRouter {
  const listeners = new Set<IntentListener>();

  const fireListeners = (intent: Intent, outcome: DispatchOutcome) => {
    for (const l of listeners) {
      try {
        l(intent, outcome);
      } catch {
        // Listener errors must not block dispatch.
      }
    }
  };

  const defaultPolicy = config.recoveryPolicy ?? interactivePolicy;

  return {
    async dispatch(intent: Intent, options?: DispatchOptions): Promise<unknown> {
      const policy = options?.recoveryPolicyOverride ?? defaultPolicy;
      switch (intent.address.kind) {
        case 'navigate': {
          const resolution = config.resolveSurface(intent.address.target);
          const action = policy(resolution);
          switch (action.kind) {
            case 'proceed':
              fireListeners(intent, { status: 'dispatched' });
              await config.navigationHandler.handle(intent.address, {
                push: options?.pushHistory,
                ...(options?.taskOrigin ? { taskOrigin: options.taskOrigin } : {}),
              });
              break;
            case 'auto-correct':
              fireListeners(intent, {
                status: 'auto-corrected',
                canonicalId: action.id,
                originalId: action.originalId,
              });
              config.onRedirect?.(action.originalId, action.id);
              await config.navigationHandler.handle(
                { ...intent.address, target: action.id },
                { push: options?.pushHistory, ...(options?.taskOrigin ? { taskOrigin: options.taskOrigin } : {}) },
              );
              break;
            case 'suggest':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'typo', detail: `Unknown surface '${action.attemptedId}'` },
                alternatives: action.alternatives,
              });
              break;
            case 'abort':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'unknown', detail: action.reason },
                alternatives: [],
              });
              break;
          }
          return undefined;
        }
        case 'invoke': {
          const resolution = config.resolveOperation(intent.address.target);
          const action = policy(resolution);
          switch (action.kind) {
            case 'proceed':
              fireListeners(intent, { status: 'dispatched' });
              return await config.invocationHandler.handle(
                intent.address,
                intent.transport,
              );
            case 'auto-correct':
              fireListeners(intent, {
                status: 'auto-corrected',
                canonicalId: action.id,
                originalId: action.originalId,
              });
              return await config.invocationHandler.handle(
                { ...intent.address, target: action.id },
                intent.transport,
              );
            case 'suggest':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'typo', detail: `Unknown operation '${action.attemptedId}'` },
                alternatives: action.alternatives,
              });
              return undefined;
            case 'abort':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'unknown', detail: action.reason },
                alternatives: [],
              });
              return undefined;
          }
        }
        case 'query': {
          // 548 S4-A: lower the search verb to a search-surface activation. Reuses the
          // navigationHandler (the proven S4-B effect) — the query travels through the
          // intent pipeline as a navigation carrying the query text in state.
          const target = config.querySurfaceId ?? DEFAULT_QUERY_SURFACE_ID;
          const stateKey = config.queryStateKey ?? DEFAULT_QUERY_STATE_KEY;
          const resolution = config.resolveSurface(target);
          const searchTarget = resolution.status !== 'unresolved' && resolution.id === 'core.search-v3-surface';
          const navState = searchTarget
            ? { [stateKey]: intent.address.query }
            : { ...intent.address.state, [stateKey]: intent.address.query };
          const action = policy(resolution);
          switch (action.kind) {
            case 'proceed':
              fireListeners(intent, { status: 'dispatched' });
              await config.navigationHandler.handle(
                { kind: 'navigate', target, state: navState },
                { push: options?.pushHistory, ...(options?.taskOrigin ? { taskOrigin: options.taskOrigin } : {}) },
              );
              break;
            case 'auto-correct':
              fireListeners(intent, {
                status: 'auto-corrected',
                canonicalId: action.id,
                originalId: action.originalId,
              });
              config.onRedirect?.(action.originalId, action.id);
              await config.navigationHandler.handle(
                { kind: 'navigate', target: action.id, state: navState },
                { push: options?.pushHistory, ...(options?.taskOrigin ? { taskOrigin: options.taskOrigin } : {}) },
              );
              break;
            case 'suggest':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'typo', detail: `Unknown surface '${action.attemptedId}'` },
                alternatives: action.alternatives,
              });
              break;
            case 'abort':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'unknown', detail: action.reason },
                alternatives: [],
              });
              break;
          }
          return undefined;
        }
        case 'answer': {
          // 548 §4.5: lower the answer verb to an activation of the shape-hosting chat surface,
          // carrying the prompt in state. The resolution target is conceptually the AI-shape
          // catalog (default core.rag-ask); V1 lowers to the chat surface that hosts the shape.
          const target = config.answerSurfaceId ?? DEFAULT_ANSWER_SURFACE_ID;
          const stateKey = config.answerStateKey ?? DEFAULT_ANSWER_STATE_KEY;
          const resolution = config.resolveSurface(target);
          const searchTarget = resolution.status !== 'unresolved' && resolution.id === 'core.search-v3-surface';
          const navState = searchTarget
            ? { ...intent.address.state, [stateKey]: intent.address.prompt, forceShapeId: intent.address.shape }
            : { ...intent.address.state, [stateKey]: intent.address.prompt };
          const action = policy(resolution);
          // Search answer links prepare the named mode and wait for Send. Legacy explicitly
          // configured hosts retain their old behavior until their implementation is retired.
          const parkAnswerRun = (): void => {
            if (searchTarget) setPendingSelection(null);
            setPendingForceShape(intent.address.kind === 'answer' ? intent.address.shape : null);
            setPendingAutoRun(!searchTarget);
          };
          switch (action.kind) {
            case 'proceed':
              parkAnswerRun();
              fireListeners(intent, { status: 'dispatched' });
              await config.navigationHandler.handle(
                { kind: 'navigate', target, state: navState },
                { push: options?.pushHistory, ...(options?.taskOrigin ? { taskOrigin: options.taskOrigin } : {}) },
              );
              break;
            case 'auto-correct':
              parkAnswerRun();
              fireListeners(intent, {
                status: 'auto-corrected',
                canonicalId: action.id,
                originalId: action.originalId,
              });
              await config.navigationHandler.handle(
                { kind: 'navigate', target: action.id, state: navState },
                { push: options?.pushHistory, ...(options?.taskOrigin ? { taskOrigin: options.taskOrigin } : {}) },
              );
              break;
            case 'suggest':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'typo', detail: `Unknown surface '${action.attemptedId}'` },
                alternatives: action.alternatives,
              });
              break;
            case 'abort':
              fireListeners(intent, {
                status: 'unresolved',
                attemptedId: action.attemptedId,
                diagnosis: { mode: 'unknown', detail: action.reason },
                alternatives: [],
              });
              break;
          }
          return undefined;
        }
      }
    },
    subscribe(listener: IntentListener): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
