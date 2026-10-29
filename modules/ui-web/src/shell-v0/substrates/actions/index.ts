// SPDX-License-Identifier: Apache-2.0
/**
 * Action substrate — Tempdoc 543 §3.C.
 *
 * Unified substrate that the design positions as absorbing the three
 * existing action-shaped registries (Command + ContextAction +
 * VirtualOperation + main's SelectionAction):
 *   - Command  = Action with `appliesTo: undefined` (global / palette)
 *   - VirtualOperation = Action exposed to agent (carries no audience tag —
 *     the Operation substrate has its own typed audience array)
 *   - ContextAction = Action with `appliesTo: [AddressableKind...]`
 *   - SelectionAction = Action with selection-kind addressables
 *
Per §21.B (autonomous run 1) the Action substrate absorbed Command
 * + Operation projection; shell commands route through
 * `resolveActionIdFromCommandId` in CommandRegistry.ts. The §13.2.2
 * hybrid wires `applyEffect` to the Effect Journal so every dispatched
 * Action gets logged. SelectionAction / ContextAction / Template /
 * Walkthrough / EmptyState / InspectorTab / StatusBar / Keybinding
 * stay parallel substrates per §22.1 — they project capability rather
 * than just invocability (substrate-discipline rationale documented
 * in §22.1).
 *
 * KCS bridge per §19: future `useActions({addressable, scope})` +
 * `invokeAction(id, args, addressable?)` capability module.
 */

import type { Addressable, AddressableKind } from '../addressable.js';
import type { Effect } from '../effect.js';
import { localizeResourceKey } from '../../../i18n/resourceCatalog.js';
import type { Provenance } from '../../primitives/provenance.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';
// §25.α5 — shared notify primitive.
import { notifyAll } from '../../primitives/notify.js';
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import { emitEphemeralToast } from '../../components/advisory/ephemeralToast.js';
import { requestSurfaceNavigation } from '../../controllers/navigateRequest.js';
// §25.β5 — DataEffect cache write target.
import { setLatestDataResult } from '../evaluationContext/index.js';
// §25.β3 — elicit substrate for mid-invocation user prompts.
import { elicit, type ElicitOptions } from '../elicit/index.js';
// §32 U1 — Autonomy Dial: gate agent invocations (watch → propose).
import { agentInvocationDisposition } from '../autonomy/index.js';
// §25.β3 — proposeEffect (re-exported through KernelCtx) so an Action
// handler can stage an additional Effect for review without forcing
// immediate dispatch.
import {
  proposeEffect,
  type PendingId,
  type ProposeEffectOptions,
} from '../pending-effects/index.js';
import type { EffectOriginator } from '../effects/index.js';

/**
 * §25.β3 — KernelCtx is the kernel-provided context an Action handler
 * receives as its third argument. It exposes substrate primitives the
 * handler may legitimately call mid-invocation:
 *   - elicit: ask the user a clarifying question via a kernel-
 *     rendered form (no plugin chrome).
 *   - proposeEffect: stage a Pending side-effect for review (defers
 *     dispatch; the user accepts/rejects through the chrome queue).
 *
 * KernelCtx is intentionally minimal. Future primitives (capability
 * consent, transcript writes) extend the type with optional fields so
 * existing handlers compile unchanged.
 */
export interface KernelCtx {
  readonly elicit: (opts: ElicitOptions) => Promise<unknown | null>;
  readonly proposeEffect: (
    effect: Effect,
    originator?: EffectOriginator,
    opts?: ProposeEffectOptions,
  ) => PendingId;
}

/**
 * Default KernelCtx — handlers that opt into the third arg via
 * `invokeAndApply` automatically receive this. Plugin sandbox / agent
 * harness can wrap with policy enforcement (e.g., capability-gated
 * elicit) by intercepting at the harness layer.
 */
const _defaultKernelCtx: KernelCtx = Object.freeze({
  elicit,
  proposeEffect: (
    effect: Effect,
    originator?: EffectOriginator,
    opts?: ProposeEffectOptions,
  ): PendingId =>
    proposeEffect(
      effect,
      CORE_PROVENANCE,
      originator,
      opts ?? {},
    ),
});
import { getShellContext } from '../../state/shellContextState.js';
import { evaluateWhen } from '../../commands/whenExpression.js';
import { recordEffect } from '../effects/index.js';

// ============================================================
// ParameterSchema — JSON Schema with x-ui-renderer extension
// ============================================================

/**
 * Per §12.3 #2: JSON Schema with `x-ui-renderer` extension keywords.
 * Same shape used by agent-tools for OpenAI-compatible tool catalogs;
 * UI hosts consult `x-ui-renderer` hints to project a Form (Slice 5)
 * — e.g. `'corpus-picker'` mounts `<jf-corpus-picker>` instead of the
 * default text input.
 */
export interface ParameterSchema {
  readonly type?:
    | 'object'
    | 'string'
    | 'number'
    | 'integer'
    | 'boolean'
    | 'array'
    | 'null';
  readonly properties?: Record<string, ParameterSchema>;
  readonly required?: readonly string[];
  readonly items?: ParameterSchema;
  readonly enum?: readonly unknown[];
  readonly description?: string;
  /** JSON Schema extension — UI hint for which control to render. */
  readonly 'x-ui-renderer'?: string;
  /** Allow additional JSON-Schema keywords without explicit typing. */
  readonly [k: string]: unknown;
}

// ============================================================
// Action interface
// ============================================================

/**
 * An Action — the §3.C unit-of-doing. Registered via `registerAction`;
 * resolved via `listActions({ scope, addressable })`; invoked via
 * `invokeAction(id, args, addressable?)`.
 */
export interface Action {
  /** Stable id (`<contributor>.action.<verb>`). Globally unique. */
  readonly id: string;
  /** Human-readable label rendered in palettes / menus. */
  readonly title: string;
  /** Optional category for grouping (e.g., 'search', 'inspector'). */
  readonly category?: string;
  /**
   * Optional icon name (consumed by chrome that renders the Action —
   * palette row, context-menu item, etc.). Added in §21.B to match
   * Command's icon field so the palette migration in §21.C doesn't
   * lose rendering data.
   */
  readonly icon?: string;
  /**
   * Per §3.C — Addressable kinds this Action applies to. `null` or
   * absent means "global" (no Addressable required; appears in the
   * command palette). Empty array is also treated as global.
   */
  readonly appliesTo?: readonly AddressableKind[] | null;
  /**
   * Per §3.C — Scope predicate. Evaluated against the flat-key
   * ShellContext projection. Absent ⇒ unconditional.
   */
  readonly when?: string;
  // §25.α1 (D5) — `audience` field retired. No consumer ever read it;
  // Operation has its own typed audience array, SelectionAction has
  // its own provenance/trust gating, and ShellContext.audience is the
  // separate USER/AGENT/OPERATOR/DEVELOPER projection. If a future
  // need for Action-level audience filtering surfaces, re-introduce
  // with a consumer that actually filters.
  /**
   * Per §3.C — Args schema. JSON Schema with optional `x-ui-renderer`
   * extension hints. Absent ⇒ no parameters.
   */
  readonly parameters?: ParameterSchema;
  /** Optional keyboard shortcut (e.g., `'Ctrl+K'`). Display hint today. */
  readonly shortcut?: string;
  /** Sort priority within a list; higher = first. Defaults to 0. */
  readonly priority?: number;
  /**
   * Per §3.C — fine-grained gate evaluated against the Addressable
   * payload. Use for predicates that depend on payload-level data
   * (e.g. "only enabled when the search hit has a citation"). Absent
   * ⇒ always enabled.
   */
  readonly enabled?: (addressable: Addressable | null) => boolean;
  /**
   * Per §3.C — invocation handler. Returns the after-effect
   * description; the kernel renders the effect uniformly.
   *
   * §25.β3 — handlers now also receive an optional KernelCtx (3rd
   * arg) exposing elicit() + proposeEffect(). Pre-§25.β3 handlers
   * that ignore the third arg compile unchanged; handlers that need
   * mid-invocation user input declare the parameter and call
   * `await ctx.elicit({ ... })`.
   */
  readonly handler?: (
    args: Record<string, unknown>,
    addressable: Addressable | null,
    ctx?: KernelCtx,
  ) => Promise<Effect> | Effect;
  /**
   * 569 Move 5 — data-only operation reference. An Action may declare WHICH operation
   * it invokes (with optional fixed args) INSTEAD of a JS closure `handler`. When this
   * is present (and `handler` is absent) the kernel synthesizes the `invoke-operation`
   * effect — so a surface body / Presentation Declaration can author actions as DATA,
   * with no closure (the truth/presentation cut, Move 5: actions are op-id references).
   * Exactly one of `handler` / `operationRef` must be present (enforced at registration).
   */
  readonly operationRef?: {
    readonly operationId: string;
    readonly args?: Readonly<Record<string, unknown>>;
  };
  /** Per §3.A — uniform attribution. */
  readonly provenance: Provenance;
}

// ============================================================
// Registry storage
// ============================================================

const _actions = new Map<string, Action>();
const _listeners = new Set<() => void>();

function notify(): void {
  notifyAll(_listeners);
}

// ============================================================
// Public API
// ============================================================

/** Register an Action. Throws on duplicate id. */
export function registerAction(action: Action): void {
  if (_actions.has(action.id)) {
    throw new Error(`Action already registered: ${action.id}`);
  }
  // 569 Move 5 — an Action is EITHER a code action (handler) OR a data action
  // (operationRef), never both and never neither.
  const hasHandler = typeof action.handler === 'function';
  const hasOpRef = action.operationRef !== undefined;
  if (hasHandler === hasOpRef) {
    throw new Error(
      `Action ${action.id} must declare exactly one of handler / operationRef ` +
        `(a data-only action references an operation; a code action uses a handler).`,
    );
  }
  _actions.set(action.id, action);
  notify();
}

/** Remove a previously-registered Action. */
export function unregisterAction(id: string): void {
  if (_actions.delete(id)) notify();
}

/** Subscribe to registry-change notifications. */
export function subscribeActions(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** All registered Actions, no filtering. Sorted by id. */
export function getAllActions(): readonly Action[] {
  return Array.from(_actions.values()).sort((a, b) =>
    a.id.localeCompare(b.id),
  );
}

/** Get a single Action by id. */
export function getAction(id: string): Action | undefined {
  return _actions.get(id);
}

/**
 * Filter shape for `listActions`. `scope` is the flat-key ShellContext
 * (or any object — predicate evaluation uses flat-key lookup).
 * `addressable` narrows by `appliesTo`.
 */
export interface ListActionsFilter {
  readonly scope?: Record<string, unknown>;
  readonly addressable?: Addressable | null;
}

/**
 * The §3.C resolver. Returns Actions whose:
 *   1. `appliesTo` admits `addressable.kind` (or is global / null)
 *   2. `when` evaluates true against `scope` (or absent)
 *   3. `enabled(addressable)` returns true (or absent)
 *
 * Sorted by `priority` desc, then `title` asc for stable display.
 */
export function listActions(
  filter: ListActionsFilter = {},
): readonly Action[] {
  const scope =
    filter.scope ?? (getShellContext() as unknown as Record<string, unknown>);
  const addressable = filter.addressable ?? null;
  const result: Action[] = [];
  for (const a of _actions.values()) {
    // appliesTo gate
    const applies = a.appliesTo;
    if (applies != null && applies.length > 0) {
      if (addressable == null) continue;
      if (!applies.includes(addressable.kind)) continue;
    }
    // when gate
    if (a.when !== undefined) {
      if (!evaluateWhen(a.when, scope)) continue;
    }
    // enabled gate
    if (a.enabled !== undefined) {
      try {
        if (!a.enabled(addressable)) continue;
      } catch {
        // A throwing enabled-fn is treated as "not enabled" — defensive
        // against plugin code; the substrate never trusts plugin
        // handlers.
        continue;
      }
    }
    result.push(a);
  }
  result.sort((x, y) => {
    const px = x.priority ?? 0;
    const py = y.priority ?? 0;
    if (px !== py) return py - px;
    return x.title.localeCompare(y.title);
  });
  return result;
}

/**
 * 569 Move 5 — resolve an Action's Effect: from its `handler` closure, or (data-only)
 * synthesized from its `operationRef`. The single place both invoke paths agree how an
 * action becomes an effect, so a data action and a code action are uniform downstream.
 */
async function resolveActionEffect(
  action: Action,
  args: Record<string, unknown>,
  addressable: Addressable | null,
  ctx?: KernelCtx,
): Promise<Effect> {
  if (action.handler) {
    // Preserve the 2-arg call when no KernelCtx is supplied (invokeAction's contract);
    // pass the 3rd arg only on the ctx-bearing path (invokeAndApply).
    return ctx === undefined
      ? await action.handler(args, addressable)
      : await action.handler(args, addressable, ctx);
  }
  if (action.operationRef) {
    return {
      kind: 'invoke-operation',
      operationId: action.operationRef.operationId,
      args: { ...action.operationRef.args, ...args },
    };
  }
  throw new Error(`Action ${action.id} has neither handler nor operationRef`);
}

/**
 * Invoke an Action by id. Returns the handler's Effect.
 * Throws if the Action is unknown.
 */
export async function invokeAction(
  id: string,
  args: Record<string, unknown> = {},
  addressable: Addressable | null = null,
): Promise<Effect> {
  const action = _actions.get(id);
  if (action === undefined) {
    throw new Error(`Unknown Action: ${id}`);
  }
  return await resolveActionEffect(action, args, addressable);
}

/**
 * Tempdoc 543 §4 — kernel-side Effect renderer. Plugins request,
 * kernel renders. Given an Effect returned by an Action handler,
 * project it into the appropriate chrome surface. Plugins never paint
 * after-effect UI directly; they describe the after-effect and the
 * kernel dispatches.
 *
 * Each kind maps to an existing chrome capability:
 *   - 'noop': nothing to do
 *   - 'toast': emitEphemeralToast → the one message model (AdvisoryStore/toast host)
 *   - 'navigate': set location.hash (router consumes)
 *   - 'open-pane' / 'close-pane': dispatch jf-open-pane /
 *     jf-close-pane (chrome host listens; best-effort dispatch today
 *     — listener wiring depends on which pane is mounted)
 *   - 'invoke-operation': dispatch jf-invoke-operation (OperationClient
 *     listens via the existing operation runner; best-effort
 *     dispatch — full wiring depends on which surface is mounted)
 *
 * Returns true if the kernel knew how to render the effect; false
 * for unknown kinds (caller may add a fallback handler).
 */
/**
 * §32 R-P4 — DOM CustomEvent dispatch helper. The substrate→chrome
 * contract for most Effect kinds is "dispatch a bubbling `jf-*` event on
 * `document`"; this collapses the ~10 repeated guarded-dispatch blocks in
 * `applyEffect` into one call. SSR/headless-safe (no-op when `document`
 * is undefined).
 */
function dispatchDomEvent(
  name: string,
  detail: Record<string, unknown>,
): void {
  if (typeof document !== 'undefined') {
    document.dispatchEvent(new CustomEvent(name, { detail, bubbles: true }));
  }
}

export function applyEffect(
  effect: Effect,
  invokedBy: Provenance = CORE_PROVENANCE,
  originator: EffectOriginator = 'user',
): boolean {
  // Tempdoc 543 §13.2.2 — every effect writes to the kernel-owned
  // Effect Journal before dispatch. The substrate write happens
  // first so a failed dispatch still leaves an audit trail.
  // §28.W13 — originator threads through so agent-emitted effects
  // record under originator='agent' instead of the 'user' default.
  const journalEntry = recordEffect(effect, invokedBy, { originator });
  return dispatchEffectToChrome(effect, { originator, journalEntryId: journalEntry.id });
}

/**
 * 543-fwd #1 (redo) — dispatch an effect to chrome WITHOUT writing to the
 * Effect Journal. This is the journal-suppressed path the cursor-based undo /
 * redo use: moving the undo cursor re-applies side-effects (the original effect
 * on redo, the derived inverse on undo) but must NOT append a new journal entry
 * — the journal is the append-only history the cursor walks, not a redo log.
 * `applyEffect` is `recordEffect` + this. Extracted so neither path duplicates
 * the ~18-arm dispatch switch.
 */
export interface DispatchOptions {
  readonly originator?: EffectOriginator;
  /** Only meaningful for invoke-operation (links the returned executionId). */
  readonly journalEntryId?: number;
}

export function dispatchEffectToChrome(
  effect: Effect,
  opts: DispatchOptions = {},
): boolean {
  const originator: EffectOriginator = opts.originator ?? 'user';
  switch (effect.kind) {
    case 'noop':
      return true;
    case 'toast':
      // 559 Authority III — one message model (was the parallel jf-show-toast).
      emitEphemeralToast({
        message: effect.message,
        severity: effect.severity ?? 'info',
      });
      return true;
    case 'navigate':
      if (typeof window !== 'undefined' && window.location) {
        window.location.hash = effect.to;
      }
      return true;
    case 'open-pane':
      dispatchDomEvent('jf-open-pane', { paneId: effect.paneId });
      return true;
    case 'close-pane':
      dispatchDomEvent('jf-close-pane', { paneId: effect.paneId });
      return true;
    case 'invoke-operation':
      // §32 S2 — thread `originator` so the Shell listener can stamp the
      // backend TransportTag that engages the (SourceTier × RiskTier)
      // trust lattice for agent-originated operations (§32.9.2 bridge).
      // §32 U2 — thread the journal entry id so the Shell listener can
      // associate the returned executionId (undoSupported ops) with this
      // entry via markUndoableOperation.
      dispatchDomEvent('jf-invoke-operation', {
        operationId: effect.operationId,
        args: effect.args ?? {},
        originator,
        ...(opts.journalEntryId !== undefined
          ? { journalEntryId: opts.journalEntryId }
          : {}),
        // §32 #2B + tempdoc 550 C3 — carry the user-consent marker (present only when the
        // user approved a HIGH-risk op via the queue's typed-confirm). The Shell listener
        // uses invokeWithConsent: it recovers the backend's 428 by approving the
        // backend-issued pending by id and re-invoking with the minted capsule. (Replaces the
        // prior pre-minted confirmationToken — consent is a flag; the capsule is minted
        // server-side against the backend-stored op+args.)
        ...(effect.consented ? { consented: true } : {}),
      });
      return true;
    case 'undo-operation':
      // §32 U2 — Shell jf-undo-operation listener calls
      // OperationClient.undoWithConsent → POST /api/undo/{operationId}, taking
      // the same trust-gate ceremony the forward invoke does (tempdoc 875 §C.7).
      dispatchDomEvent('jf-undo-operation', {
        operationId: effect.operationId,
        executionId: effect.executionId,
      });
      return true;
    // ──────────────────────────────────────────────────────────────
    // §21.D Effect union v2 dispatchers.
    // Each chrome event is documented as the substrate→DOM contract;
    // consumers attach their listeners in the corresponding surface.
    // ──────────────────────────────────────────────────────────────
    case 'set-selection':
      dispatchDomEvent('jf-set-selection', {
        surfaceId: effect.surfaceId,
        ids: effect.ids,
      });
      return true;
    case 'clear-selection':
      dispatchDomEvent('jf-clear-selection', { surfaceId: effect.surfaceId });
      return true;
    case 'focus-element': {
      if (typeof document !== 'undefined') {
        const el = document.querySelector(effect.selector);
        if (el instanceof HTMLElement) el.focus();
      }
      return true;
    }
    case 'scroll-to': {
      if (typeof document !== 'undefined') {
        const el = document.querySelector(effect.selector);
        if (el instanceof HTMLElement) {
          el.scrollIntoView({
            behavior: effect.behavior ?? 'smooth',
            block: effect.block ?? 'start',
          });
        }
      }
      return true;
    }
    case 'open-modal':
      dispatchDomEvent('jf-open-modal', {
        modalId: effect.modalId,
        payload: effect.payload ?? {},
      });
      return true;
    case 'close-modal':
      dispatchDomEvent('jf-close-modal', { modalId: effect.modalId });
      return true;
    case 'copy-to-clipboard': {
      // 574 B2 — route the clipboard effect through the one clipboard authority.
      // Tempdoc 613 §6 — a copy confirmation is a RECEIPT (locality: at-control), flashed in the
      // control via ReceiptController by the caller — NOT a window toast. The effect no longer carries
      // a `successMessage` window toast (the lone caller, EffectAuditLog, now flashes a receipt).
      void copyToClipboard(effect.text);
      return true;
    }
    case 'set-form-value':
      dispatchDomEvent('jf-set-form-value', {
        formId: effect.formId,
        path: effect.path,
        value: effect.value,
      });
      return true;
    // ──────────────────────────────────────────────────────────────
    // 569 §14 — Effect v3 presentation + search intent dispatchers.
    // Each emits a `jf-*` DOM event; the owning AUTHORITY listens (the global
    // appearance/ui-mode/presentation listeners live in Shell; save-settings in
    // SettingsSurface; set-search-* in SearchSurface — the 570 seam). The substrate
    // stays free of app-state imports, exactly like open-pane/invoke-operation.
    // ──────────────────────────────────────────────────────────────
    case 'set-appearance':
      dispatchDomEvent('jf-set-appearance', {
        ...(effect.theme !== undefined ? { theme: effect.theme } : {}),
        ...(effect.highContrast !== undefined ? { highContrast: effect.highContrast } : {}),
      });
      return true;
    case 'set-ui-mode':
      dispatchDomEvent('jf-set-ui-mode', { mode: effect.mode });
      return true;
    case 'apply-presentation':
      dispatchDomEvent('jf-apply-presentation', { presentationId: effect.presentationId });
      return true;
    case 'save-settings':
      dispatchDomEvent('jf-save-settings', { settings: effect.settings });
      return true;
    case 'set-search-query':
      dispatchDomEvent('jf-set-search-query', { query: effect.query });
      return true;
    case 'set-search-filter':
      dispatchDomEvent('jf-set-search-filter', {
        ...(effect.fromMs !== undefined ? { fromMs: effect.fromMs } : {}),
        ...(effect.toMs !== undefined ? { toMs: effect.toMs } : {}),
      });
      return true;
    // §25.β5 — DataEffect arm: dispatch as chrome events AND record
    // the latest value in the EvaluationContext data-result cache so
    // when-clauses + projectors can branch on it.
    case 'data-result':
      dispatchDomEvent('jf-data-result', {
        operationId: effect.operationId,
        resultKey: effect.resultKey,
        result: effect.result,
      });
      // Side-write to EvaluationContext data cache so when-clauses +
      // projectors can branch on the latest data return synchronously
      // after the effect dispatches.
      setLatestDataResult(effect.resultKey, {
        operationId: effect.operationId,
        result: effect.result,
        at: Date.now(),
      });
      return true;
    case 'data-error':
      dispatchDomEvent('jf-data-error', {
        operationId: effect.operationId,
        resultKey: effect.resultKey,
        error: effect.error,
      });
      return true;
    default: {
      // Exhaustiveness — Effect is a closed union; missing handler
      // here fails the TS check.
      const _exhaustive: never = effect;
      void _exhaustive;
      return false;
    }
  }
}

/**
 * Convenience: invoke an Action and apply its Effect in one step.
 * The §3.C contract end-to-end: action → handler → effect → kernel
 * renders → journal records.
 */
export async function invokeAndApply(
  id: string,
  args: Record<string, unknown> = {},
  addressable: Addressable | null = null,
  ctx: KernelCtx = _defaultKernelCtx,
  originator: EffectOriginator = 'user',
): Promise<Effect> {
  const action = _actions.get(id);
  if (action === undefined) {
    throw new Error(`Unknown Action: ${id}`);
  }
  // §25.β3 — pass the KernelCtx to the handler. Handlers that ignore
  // the third arg continue to work; handlers that need elicit /
  // proposeEffect call ctx.elicit(...) / ctx.proposeEffect(...).
  const effect = await resolveActionEffect(action, args, addressable, ctx);
  // §32 U1 — Autonomy Dial gates agent invocations. The disposition depends on
  // the level AND the effect kind: 'watch' proposes everything; 'assist'
  // proposes backend operations (invoke-operation) but dispatches pure-FE
  // effects; 'auto' dispatches everything (the backend lattice is the sole
  // gate). A proposed op, on user-accept, re-dispatches as a USER action so the
  // backend auto-runs LOW/MEDIUM and still typed-confirms HIGH/destructive —
  // the dial never lets a destructive op auto-fire (backend-enforced, §32.9).
  if (
    originator === 'agent' &&
    agentInvocationDisposition(effect.kind) === 'propose'
  ) {
    proposeEffect(effect, action.provenance, 'agent', {
      rationale: `Agent: ${action.title}`,
    });
    return effect;
  }
  // §13.2.2: thread the Action's provenance through to applyEffect so the
  // journal entry attributes the effect to the right contributor.
  // §28.W13: thread originator so agent-emitted invocations record under
  // 'agent' instead of the default 'user'.
  applyEffect(effect, action.provenance, originator);
  return effect;
}

/** Test-only: clear all registrations. */
export function __resetActionsForTest(): void {
  _actions.clear();
  _listeners.clear();
}

// ============================================================
// Canonical first-party Actions — proves the contract end-to-end
// ============================================================

/**
 * Register the canonical first-party Actions. Idempotent (skip if
 * already registered). Tempdoc 543 §20.7 A4: extracted from a
 * top-level registerAction call so tests can re-invoke after
 * __resetActionsForTest(); module-load still fires it once on
 * first import for side-effect parity with the prior pattern.
 *
 * `core.action.cite-selection` emits a `toast` Effect describing
 * the cite intent — observable proof that the substrate end-to-end
 * (register → list → invoke → effect → kernel-rendered toast →
 * journal) works.
 */
export function registerCanonicalCoreActions(): void {
  if (!_actions.has('core.action.cite-selection')) {
    registerAction({
      id: 'core.action.cite-selection',
      title: 'Cite Selection',
      category: 'search',
      appliesTo: ['search-result', 'citation', 'document-passage'],
      priority: 100,
      parameters: {
        type: 'object',
        properties: {
          // No user-supplied args today; the Addressable carries the
          // selection payload. Schema documented for future args.
        },
      },
      handler: (_args, addressable) => ({
        kind: 'toast' as const,
        message:
          addressable !== null
            ? `Cited: ${addressable.id}`
            : 'Cite (no selection)',
        severity: 'info' as const,
      }),
      provenance: CORE_PROVENANCE,
    });
  }

  // §21.B retains 'core.action.shell.toggle-palette' here as a fallback
  // entry if shell-actions haven't bound their deps yet. Once Shell.ts
  // calls registerShellActions(deps), that registration replaces this
  // one with a deps-driven handler (a real palette toggle).
  if (!_actions.has('core.action.shell.toggle-palette')) {
    registerAction({
      id: 'core.action.shell.toggle-palette',
      title: 'Toggle Command Palette',
      category: 'view',
      priority: 50,
      handler: () => ({
        kind: 'toast' as const,
        message: 'Palette toggle requested (deps not yet bound)',
        severity: 'info' as const,
      }),
      provenance: CORE_PROVENANCE,
    });
  }
}

// ============================================================
// Shell Actions — Tempdoc 543 §21.B
// ============================================================
//
// Migration target for CommandRegistry.registerShellCommands. Each
// shell-level navigation / view command becomes a first-class Action
// with a real Effect return — kernel-uniform dispatch (the Effect
// Journal records each invocation; PendingEffect can intercept in
// §21.E; undo applies via the journal's inverse derivation).
//
// Deps injection lets Shell.ts hand in `navigate` + `toggleInspector`
// + `togglePalette` at boot. Re-calling replaces the prior bindings
// (chrome remount safe).

/** Dependency surface for shell Actions. Shell.ts owns these. */
export interface ShellActionDeps {
  readonly navigate: (target: string) => void;
  readonly toggleInspector: () => void;
  readonly togglePalette: () => void;
  /** Search Thread S2 — focus the one input bar (the unified window's composer). */
  readonly focusComposer: () => void;
}

/**
 * Register the canonical shell Actions. Idempotent — re-calling
 * replaces existing entries (deps may change between mounts).
 */
export function registerShellActions(deps: ShellActionDeps): void {
  const shellAction = (
    id: string,
    title: string,
    handler: () => Effect,
    extras: { shortcut?: string } = {},
  ): void => {
    // Replace if already present; deps may have changed.
    if (_actions.has(id)) _actions.delete(id);
    registerAction({
      id,
      title,
      category: 'shell',
      priority: 50,
      handler: () => handler(),
      provenance: CORE_PROVENANCE,
      ...(extras.shortcut !== undefined ? { shortcut: extras.shortcut } : {}),
    });
  };

  const navigateAction = (id: string, title: string, target: string): void =>
    shellAction(id, title, () => {
      deps.navigate(target);
      return { kind: 'navigate' as const, to: target };
    });

  // Tempdoc 923 / Search Thread S5b — the standalone Search rail surface is retired; its retrieve
  // tier folded into the one window (core.search-v3-surface). Search is the place; Ask and
  // Delegate are consequences inside it, so there is exactly one palette destination for the place.
  navigateAction('core.action.shell.go-to-search', 'Go to Search', 'core.search-v3-surface');
  navigateAction('core.action.shell.go-to-library', 'Go to Library', 'core.library-surface');
  navigateAction('core.action.shell.go-to-settings', 'Go to Settings', 'core.settings-surface');
  // Rules need both saved values; use the same Settings arrival/focus path as the rule controls.
  shellAction('core.action.shell.go-to-indexing-rules', 'Change indexing rules', () => {
    requestSurfaceNavigation('core.settings-surface', { focusIndexingRule: 'hours' });
    return { kind: 'noop' as const };
  });
  navigateAction('core.action.shell.go-to-health', 'Go to System Health', 'core.health-surface');
  navigateAction('core.action.shell.go-to-activity', 'Go to Activity', 'core.activity-surface');
  navigateAction('core.action.shell.go-to-browse', 'Go to Browse', 'core.browse-surface');
  // Round-14 F2 — the Brain surface had no plain navigation entry: the only palette routes to it
  // were the two install-flavoured ones below, so "open Brain" (and Memory, which is a TAB on Brain
  // — the coverage register names "command palette 'Go to Brain'" as its documented reach) was
  // unreachable by name. This makes the documented route true.
  navigateAction('core.action.shell.go-to-brain', 'Go to Brain', 'core.brain-surface');

  // 727 F-1 — `core.start-ai-install` / `core.repair-ai-install` are deliberately excluded from
  // `canProjectOperationAsZeroArgAction` below: both require `acceptTerms: true`
  // (AiInstallService#startInstall throws TERMS_REQUIRED otherwise), and the zero-arg operation
  // projection never supplies args, so a bare palette invoke of either op could only ever fail.
  // That exclusion is correct (see the "does not project model-terms install operations" test),
  // but it also silently dropped both from being *listed* in the palette at all — leaving only
  // their zero-arg sibling `core.cancel-ai-install` (no terms gate) reachable, which is the F-1
  // symptom. Fix: give them navigate-only palette entries that route to the Brain surface, where
  // BrainSurface's `hostConfirm` collects the actual terms acceptance before invoking.
  //
  // Round-14 F3 — the LABELS now say what invoking them does. "Start AI Install" promised to start
  // the install and only navigated; the terms/consent ceremony is BrainSurface's `hostConfirm`, and
  // routing there is the whole point (a palette entry must never bypass that dialog). Both labels
  // keep "Install AI" / "Repair AI" so the natural query still finds them (the scorer is token-wise
  // since 804 §B9, so `install ai` matches either label).
  navigateAction('core.action.shell.go-to-brain-install', 'Go to Brain — Install AI', 'core.brain-surface');
  navigateAction('core.action.shell.go-to-brain-repair', 'Go to Brain — Repair AI Install', 'core.brain-surface');

  // Tempdoc 840 Phase 5 (U6) — the install was reachable only by knowing the product's word for it.
  // Every existing route to it leads with "Brain", so a user who wants the thing itself ("download
  // the models", "set up search") had no entry point that speaks their words. Same target, a title
  // written from the user's side; the palette scorer is token-wise (804 §B9), so "download models",
  // "set up ai" and "ai models" all land here.
  navigateAction(
    'core.action.shell.download-ai-models',
    'Download AI models — set up search and chat',
    'core.brain-surface',
  );

  shellAction(
    'core.action.shell.toggle-inspector',
    'Toggle Inspector',
    () => {
      deps.toggleInspector();
      return { kind: 'noop' as const };
    },
  );

  // Replace the canonical toggle-palette registration with a deps-bound one
  // that does the actual toggle. Handler returns noop because the toggle is
  // imperative DOM state (§21.D will introduce richer Effect kinds; for now
  // the journal still records the invocation through applyEffect).
  shellAction(
    'core.action.shell.toggle-palette',
    'Toggle Command Palette',
    () => {
      deps.togglePalette();
      return { kind: 'noop' as const };
    },
    { shortcut: 'mod+k' },
  );

  // Search Thread S2 (tempdoc D2/D13) — focus the bar from anywhere: Ctrl+L always,
  // '/' when not typing (the dispatcher's editable-target guard covers plain keys).
  shellAction(
    'core.action.shell.focus-composer',
    'Focus the search bar',
    () => {
      deps.focusComposer();
      return { kind: 'noop' as const };
    },
    { shortcut: 'mod+l' },
  );
}

// ============================================================
// Operation Action projection — Tempdoc 543 §21.B
// ============================================================
//
// Replaces CommandRegistry.projectOperationsToCommands. Each backend
// Operation becomes an Action with `invoke-operation` Effect return.

export interface OperationProjectable {
  readonly id: string;
  readonly presentation?: { readonly labelKey?: string };
}

function canProjectOperationAsZeroArgAction(op: OperationProjectable): boolean {
  return op.id !== 'core.start-ai-install' && op.id !== 'core.repair-ai-install'
    && op.id !== 'core.set-indexing-rules';
}

/**
 * Project a list of backend Operations into the Action registry. Each
 * Operation becomes a global Action whose handler returns an
 * `invoke-operation` Effect — the kernel's applyEffect dispatcher
 * (with the Shell A1+A2 listener) routes through OperationClient.
 */
export function projectOperationsToActions(
  operations: ReadonlyArray<OperationProjectable>,
): void {
  for (const op of operations) {
    if (!canProjectOperationAsZeroArgAction(op)) continue;
    const id = `core.action.op.${op.id}`;
    // §2.A: resolve the label through the one i18n authority — never surface
    // the raw `ops.*.label` key to the user (the raw-identifier-leak defect).
    const title = op.presentation?.labelKey
      ? localizeResourceKey(op.presentation.labelKey)
      : op.id;
    if (_actions.has(id)) _actions.delete(id);
    registerAction({
      id,
      title,
      category: 'Operations',
      priority: 10,
      handler: () => ({
        kind: 'invoke-operation' as const,
        operationId: op.id,
      }),
      provenance: CORE_PROVENANCE,
    });
  }
}

// Module-load side-effect: register canonical Actions once on first
// import (idempotent). After __resetActionsForTest, tests should call
// registerCanonicalCoreActions() to restore the canonical entry.
registerCanonicalCoreActions();
