// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511-followup-A — Canonical (HealthEvent, activity-row) strategy.
 *
 * Renders a wire-shape HealthEvent as a row in HealthSurface's
 * "Recent events" list. Reads:
 *  - `severity` ('INFO' | 'WARNING' | 'ERROR') → row CSS class
 *    ('info' | 'warning' | 'error', lowercase — matches HealthSurface's
 *    existing .event-row.<class> CSS).
 *  - `i18nKey` → title text via localizeResourceKey, with its `{name}`
 *    placeholders filled from the body (tempdoc 941 F4 — see
 *    `resolveTitle`); falls back to the sibling `.label` key, then to
 *    `id`, if the key isn't in the catalog or unset.
 *  - `body` (HealthEventBodyUnion) → the title template's parameters
 *    (`bodyParams`) AND the message text, variant-aware:
 *      - 'condition' (AssertedCondition): message | reason | status.
 *      - 'lifecycle' (LifecycleEvent): attributes.message |
 *        attributes.disposition; otherwise none — the attributes are
 *        the title template's parameters, not a second message.
 *      - 'threshold' (ThresholdState): message | phase + magnitudes.
 *  - `source.serviceName` → secondary chip (this metadata was
 *    dropped by the pre-followup-A local-shape rendering).
 *  - `timestamp` (ISO string) → relative time via formatRelativeIso.
 *  - `id` → DOM tooltip + DOM key.
 *
 * Before this strategy shipped, HealthSurface.ts:79 declared a local
 * `{title, message, level, timestamp}` interface and cast the SSE
 * payload to it. The wire emits a structurally-different
 * `{severity, source, i18nKey, body}` (per HeadHealthEventsEmitter +
 * LifecycleSnapshotTap). Result: every event row rendered with
 * undefined fields. This strategy reads the wire fields directly;
 * the migration in HealthSurface.ts switches renderEvents to mount
 * <jf-health-event> per event.
 *
 * Generated-type Pass-8 note: wire-types.ts emits every HealthEvent
 * field as optional, so a strategy could compile that reads only
 * `id`. The behavioral Pass-8 mirror in
 * healthEventActivityRow.test.ts is the load-bearing gate: it
 * mutates each of the 6 wire fields and asserts the rendered
 * output diff matches the declared role.
 */

import { html, nothing } from 'lit';
import type {
  HealthEvent,
  AssertedCondition,
  LifecycleEvent,
  ThresholdState,
  Severity,
} from '../../../api/generated/index.js';
import type { AggregateStrategy } from '../aggregateRegistry.js';
import { registerAggregateStrategy } from '../aggregateRegistry.js';
import { assertFieldRoles, type FieldRoles } from '../assertExhaustive.js';
import { present } from '../../display/present.js';
import { interpolateMessage, localizeResourceKey } from '../../../i18n/resourceCatalog.js';
import { formatRelativeIso } from '../../../utils/relativeTime.js';

/**
 * Field-role classification — drives the behavioral Pass-8 test.
 * Every HealthEvent wire field is `visual` (all 6 are consumed by
 * the row's rendered output).
 */
export const HEALTH_EVENT_ACTIVITY_ROW_ROLES: FieldRoles<HealthEvent> = {
  id: 'visual',          // tooltip + DOM key
  timestamp: 'visual',   // relative-time display
  source: 'visual',      // serviceName chip
  severity: 'visual',    // row severity class
  i18nKey: 'visual',     // title text
  body: 'visual',        // message text (variant-aware)
};
assertFieldRoles<HealthEvent>(HEALTH_EVENT_ACTIVITY_ROW_ROLES);

function severityToClass(severity: Severity | undefined): 'error' | 'warning' | 'info' {
  if (severity === 'ERROR') return 'error';
  if (severity === 'WARNING') return 'warning';
  return 'info';
}

/** Health has the termination reason, not the answer text. Do not claim that a
 * step-limited run produced an answer (or that a completed answer is correct). */
const COMPLETION_DETAILS: Readonly<Record<string, string>> = {
  COMPLETED: 'The agent finished its response.',
  MAX_ITERATIONS: 'The agent reached its step limit. Its response may be incomplete.',
  BUDGET_EDGE_FINALIZE: 'The agent reached its response budget and produced a final response.',
  ERRORED: 'The agent stopped because an error occurred.',
  CANCELLED: 'The run was cancelled.',
};

/** A catalog sentence, or null when the catalog has none (`localizeResourceKey` echoes a miss). */
function reasonCopy(key: string): string | null {
  const resolved = localizeResourceKey(key);
  return resolved.length > 0 && resolved !== key ? resolved : null;
}

/**
 * Per-variant message extraction. Returns an empty string when the
 * variant carries no human-readable message; the row degrades to
 * showing just the title in that case.
 */
function bodyToMessage(id: string | undefined, body: HealthEvent['body']): string {
  if (!body) return '';
  switch (body.kind) {
    case 'condition': {
      const c = body as AssertedCondition;
      // Spec A step 7 (F1): a condition with no message of its own is worded by the catalog's
      // per-reason sentence (`health-events.<id>.reason.<Reason>.message`) before its bare reason
      // token, so a failed chat reads "The local AI model file could not be loaded".
      return (
        c.message ??
        (id && c.reason ? reasonCopy(`health-events.${id}.reason.${c.reason}.message`) : null) ??
        c.reason ??
        (c.status ? `status: ${c.status}` : '')
      );
    }
    case 'lifecycle': {
      const l = body as LifecycleEvent;
      const attrs = l.attributes;
      if (attrs && typeof attrs === 'object') {
        const msg = typeof attrs['message'] === 'string' ? (attrs['message'] as string) : null;
        if (msg) return msg;
        const disposition =
          typeof attrs['disposition'] === 'string' ? (attrs['disposition'] as string) : null;
        if (disposition) {
          return Object.hasOwn(COMPLETION_DETAILS, disposition)
            ? COMPLETION_DETAILS[disposition]!
            : 'The run ended, but its outcome could not be identified.';
        }
      }
      // Tempdoc 941 F4 — no last-resort `k=v` flatten of the attributes map. Those attributes are
      // the i18n template's PARAMETERS (`worker.job.failed` sends {path, errorMessage, atMs});
      // `resolveTitle` below renders them inside the authored sentence, so dumping them again put
      // `atMs=1757…, errorMessage=…, path=…` under the title. Where no template consumes them the
      // dump is not user copy either — an epoch-millis field is not a message. The row degrades to
      // its title, exactly as this strategy's header already promised.
      return '';
    }
    case 'threshold': {
      const t = body as ThresholdState;
      if (t.message) return t.message;
      const phase = t.phase ? `phase: ${t.phase}` : '';
      const mags = t.magnitudes
        ? Object.entries(t.magnitudes)
            .map(([k, v]) => `${k}=${v}`)
            .join(', ')
        : '';
      return [phase, mags].filter(Boolean).join(' / ');
    }
    default:
      return '';
  }
}

/**
 * Tempdoc 941 F4 — the parameter map a body variant offers the i18n template. This is the missing
 * half of the catalog contract: the backend authors `{path}`-style placeholders in
 * `health-events.<id>.message` and ships their values on the body, and nothing joined the two.
 *
 * Lifecycle carries them as `attributes` (the emitter's own map), threshold as `magnitudes`
 * (`memory.pressure` = `{usedMb}`/`{maxMb}`). A condition has no map, so its three named scalars
 * are offered — and only when actually present, so a missing one declines the substitution instead
 * of filling the sentence with an empty string.
 */
function bodyParams(body: HealthEvent['body']): Readonly<Record<string, unknown>> {
  if (!body) return {};
  switch (body.kind) {
    case 'lifecycle': {
      const attrs = (body as LifecycleEvent).attributes;
      return attrs && typeof attrs === 'object' ? attrs : {};
    }
    case 'threshold': {
      const mags = (body as ThresholdState).magnitudes;
      return mags && typeof mags === 'object' ? mags : {};
    }
    case 'condition': {
      const c = body as AssertedCondition;
      const params: Record<string, unknown> = {};
      if (c.subject) params['subject'] = c.subject;
      if (c.reason) params['reason'] = c.reason;
      if (c.status) params['status'] = c.status;
      return params;
    }
    default:
      return {};
  }
}

/**
 * Title = the localized `i18nKey`, with its `{name}` placeholders filled from the body.
 *
 * When the template needs a parameter the emitter did not send, `interpolateMessage` returns null
 * rather than leaking a brace, and we fall back to the sibling `.label` key — the catalog's
 * documented short-label form for exactly this id (`health-events.<id>.label`) — then to the id,
 * which is the pre-existing fallback. An unbacked key still renders as the raw key, unchanged from
 * before: that is what the Pass-8 mirror pins, and it is a missing-translation signal, not copy.
 */
function resolveTitle(event: HealthEvent): string {
  if (!event.i18nKey) return event.id ?? '';
  const template = present({ kind: 'resource', key: event.i18nKey }).label || (event.id ?? '');
  const filled = interpolateMessage(template, bodyParams(event.body));
  if (filled !== null) return filled;
  const MESSAGE_SUFFIX = '.message';
  if (event.i18nKey.endsWith(MESSAGE_SUFFIX)) {
    const labelKey = `${event.i18nKey.slice(0, -MESSAGE_SUFFIX.length)}.label`;
    const label = localizeResourceKey(labelKey);
    if (label && label !== labelKey) return label;
  }
  return event.id ?? '';
}

export const healthEventActivityRowStrategy: AggregateStrategy<
  'HealthEvent',
  'activity-row'
> = (event, _ctx, _host) => {
  if (!event.id) return nothing;
  const severityClass = severityToClass(event.severity);
  const title = resolveTitle(event);
  const message = bodyToMessage(event.id, event.body);
  const sourceName = event.source?.serviceName ?? '';
  const timestamp = event.timestamp ?? '';
  const relativeTime = timestamp ? formatRelativeIso(timestamp) : '';
  const bodyKind = event.body?.kind ?? 'unknown';

  return html`
    <div
      class="event-row ${severityClass}"
      data-severity=${event.severity ?? 'INFO'}
      data-body-kind=${bodyKind}
      title=${event.id}
    >
      <div class="event-message">
        <strong>${title}</strong>
        ${message ? html` — ${message}` : nothing}
        ${sourceName
          ? html`<span class="event-source"> · ${sourceName}</span>`
          : nothing}
      </div>
      <div class="event-time">${relativeTime}</div>
    </div>
  `;
};

export function registerHealthEventActivityRowStrategy(): () => void {
  return registerAggregateStrategy({
    aggregate: 'HealthEvent',
    context: 'activity-row',
    rank: 0,
    strategy: healthEventActivityRowStrategy,
    source: 'core',
  });
}

void healthEventActivityRowStrategy;
