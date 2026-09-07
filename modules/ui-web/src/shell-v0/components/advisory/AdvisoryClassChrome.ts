// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 494 §10.1 + §10.2: class-chrome lookup table for advisory classes.
 * Maps each registered classId to its FE presentation. New advisory classes
 * add one entry; no protocol changes needed.
 *
 * Unknown classIds return the default chrome descriptor (§10.2) — the strict
 * BE-side registry is paired with graceful FE fallback so deployment-skew
 * and future plugin-contributed classes render safely.
 */
import { present } from '../../display/present.js';

export interface AdvisoryClassChromeEntry {
  readonly label: string;
  readonly icon: string;
  readonly toneClass: string;
}

/**
 * The advisory class a health condition with a recovery affordance projects into.
 *
 * Module-private: its one reader is {@link healthAdvisoryReasonBody} below. Exporting it would be
 * a speculative surface — the two renderers ask this module the QUESTION ("what body copy does
 * this advisory have?") rather than the class id, which is what keeps the resolution in one place.
 */
const HEALTH_RECOVERABLE_CLASS_ID = 'health.recoverable';

const CHROME_MAP: Record<string, AdvisoryClassChromeEntry> = {
  'operation.completed': {
    label: 'Operation completed',
    icon: '⚡',
    toneClass: 'tone-operation',
  },
  'health.recoverable': {
    label: 'Recoverable condition',
    icon: '🔧',
    toneClass: 'tone-health',
  },
  // Tempdoc 655 long-term design pass — a pending MCP/UI approval waiting for a decision.
  'authorization.pending': {
    label: 'Approval requested',
    icon: '🔐',
    toneClass: 'tone-authorization',
  },
};

const DEFAULT_CHROME: AdvisoryClassChromeEntry = {
  label: 'Advisory',
  icon: 'ℹ',
  toneClass: 'tone-default',
};

const loggedUnknown = new Set<string>();

export function advisoryClassChrome(classId: string): AdvisoryClassChromeEntry {
  const entry = CHROME_MAP[classId];
  if (entry) return entry;
  if (!loggedUnknown.has(classId)) {
    loggedUnknown.add(classId);
    console.warn(`[advisory] Unknown advisory classId: ${classId} — using default chrome`);
  }
  return DEFAULT_CHROME;
}

/**
 * Tempdoc 941 — the authored BODY sentence for a `health.recoverable` advisory, preferring the
 * per-reason override over the generic per-condition message.
 *
 * The wire's `bodyI18nKey` is always `health-events.<id>.message` (every emitter builds it that
 * way — `HeadHealthEventsEmitter`, `LifecycleSnapshotTap`, `RuleEmitter`, …), so the generic
 * sentence is the only one the two advisory renderers could ever show. The condition's REASON
 * travels separately, in `classExtras.reason` (`HealthRecoveryProjector.projectCondition`), and
 * that is the half that selects the more specific authored copy. Resolving it lives HERE, once,
 * because both renderers (the toast body and the inbox drawer's expanded detail) need the same
 * answer and a second copy of this rule is how the two would come to say different things about
 * the same condition.
 *
 * Returns `null` for a non-health advisory, for extras carrying no `conditionId`, and when the
 * catalog has no per-reason entry — the caller then falls back to its `bodyI18nKey` path, which
 * is the pre-941 behaviour exactly.
 */
export function healthAdvisoryReasonBody(
  classId: string,
  extras: Record<string, unknown>,
): string | null {
  if (classId !== HEALTH_RECOVERABLE_CLASS_ID) return null;
  const conditionId = typeof extras['conditionId'] === 'string' ? extras['conditionId'] : '';
  if (!conditionId) return null;
  const reason = typeof extras['reason'] === 'string' ? extras['reason'] : '';
  if (!reason) return null;
  // Ask only for the REASON-specific sentence: `present` falls back to the generic `.message`,
  // which is the same string `bodyI18nKey` already resolves. Returning it here would look like a
  // win while changing nothing, and would mask a missing per-reason key.
  const withReason = present({ kind: 'condition', id: conditionId, reason }).description;
  const generic = present({ kind: 'condition', id: conditionId }).description;
  return withReason !== undefined && withReason !== generic ? withReason : null;
}
