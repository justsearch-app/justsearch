// SPDX-License-Identifier: Apache-2.0
/**
 * Ephemeral-toast emit API — tempdoc 559 Authority III (one messaging model).
 *
 * THE single client-originated message channel. Before 559 a SECOND toast
 * system (`SimpleToast` + the `jf-show-toast` event) ran parallel to the
 * advisory model; this collapses it. Any code that wants to surface a transient
 * message calls {@link emitEphemeralToast}; the one {@link AdvisoryStore}
 * listens for {@link EPHEMERAL_TOAST_EVENT} and folds the message into its
 * snapshot as a local-origin EPHEMERAL record, so the ONE renderer
 * (`AdvisoryToastHost`) shows it — never the inbox/badge (EPHEMERAL is
 * toast-only). `severity` is the orthogonal tone axis (RenderHint stays the
 * lifecycle/channel axis).
 *
 * This module holds only the contract (event name + spec + dispatch helper) so
 * lower-level callers (the actions substrate) need not import the chrome store.
 */
import {
  DEFAULT_MESSAGE_CLASS,
  policyFor,
  type LocalMessageClass,
} from '../../state/messageClasses.js';
// Tempdoc 613 §6 — the routing derivation: this WINDOW-toast channel only accepts `window`-locality
// classes; an `at-control` (receipt) class is routed elsewhere (ReceiptController), enforced below.
import { routePushSurface } from '../../state/messageRouting.js';

/** Orthogonal tone axis of a system message (independent of RenderHint). */
export type MessageSeverity = 'info' | 'success' | 'warning' | 'error';

/** A client-originated transient message. Content is pre-humanized by the caller. */
export interface EphemeralToastSpec {
  /** Display text — already projected through `present()` where it names entities. */
  readonly message: string;
  readonly severity?: MessageSeverity;
  /** Plain consequence shown with the record in the status panel's Earlier section. */
  readonly detail?: string;
  readonly durationMs?: number;
  readonly actionLabel?: string;
  readonly onAction?: () => void;
  /**
   * The action is the real reversal of the person's own action (`state/ownReversals.ts`): the pill
   * shows the Ctrl+Z hint and the shell's Undo command runs it while the notice shows.
   */
  readonly reversal?: boolean;
  /** False once the action can no longer run (done, or its reversal window closed): it is no longer offered. */
  readonly actionAvailable?: () => boolean;
  /** A removed folder this notice's action adds back; other records of the same removal defer to it. */
  readonly restoresFolder?: string;
  /** Recovery choices for an unresolved destination, rendered in Earlier. */
  readonly destinations?: readonly { readonly id: string; readonly label: string }[];
  /**
   * Tempdoc 613 §5.2 — the message's CLASS, from the closed {@link LocalMessageClass} vocabulary
   * (defaults to `core.ephemeral`). The class declares the routing-relevant policy (supersede +
   * default tone), so a free-form / drifting classId is unrepresentable here (the union type).
   */
  readonly classId?: LocalMessageClass;
  /**
   * Tempdoc 602 R4 — opt-in single-occupancy. When true, emitting this toast
   * supersedes any live toast of the SAME `classId` (the prior one is dropped,
   * not stacked). Tempdoc 613 §5.2 — when omitted, the resolved value comes from
   * the class policy ({@link policyFor}), so the CLASS, not the call site, is the
   * single source for whether a class supersedes.
   */
  readonly supersede?: boolean;
}

/** Document event the single AdvisoryStore consumes. */
export const EPHEMERAL_TOAST_EVENT = 'jf-advisory-ephemeral';

/**
 * Surface a transient message through the one message model. Decoupled by a
 * document event (mirrors the retired `showToast`, but feeds the single store).
 *
 * Tempdoc 613 §5.2 — the call's `severity`/`supersede` default from the message
 * CLASS policy ({@link policyFor}) when omitted, so those routing-relevant axes
 * are declared once per class rather than re-chosen at each call site.
 */
export function emitEphemeralToast(spec: EphemeralToastSpec): void {
  if (typeof document === 'undefined') return;
  const classId: LocalMessageClass = spec.classId ?? DEFAULT_MESSAGE_CLASS;
  const policy = policyFor(classId);
  // Tempdoc 613 §6 — structural invariant: a RECEIPT (`locality:'at-control'`) must not become a
  // window toast. `f` (routePushSurface) routes such a class to the in-control ReceiptController, so
  // sending it here is a misuse — refuse it rather than render the wrong surface.
  if (routePushSurface(policy.locality) !== 'toast') {
    console.error(
      `[messageRouting] classId '${classId}' is locality:'${policy.locality}' (a receipt) — it must ` +
        `use ReceiptController (in-control flash), not the window-toast channel. Toast suppressed.`,
    );
    return;
  }
  const resolved: EphemeralToastSpec = {
    ...spec,
    classId,
    severity: spec.severity ?? policy.defaultSeverity,
    supersede: spec.supersede ?? policy.supersede,
  };
  document.dispatchEvent(
    new CustomEvent<EphemeralToastSpec>(EPHEMERAL_TOAST_EVENT, {
      detail: resolved,
      bubbles: true,
    }),
  );
}
