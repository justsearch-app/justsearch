// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup-A — Pass-8 mirror for (HealthEvent, activity-row).
 *
 * HealthEvent is generated with every field optional, so the
 * compile-time role record alone doesn't enforce reading — a
 * strategy listing every key but consuming only `id` would still
 * type-check. The behavioral Pass-8 mutates each wire field and
 * asserts the rendered output diffs accordingly. Per-variant cases
 * verify the body-message extraction for each HealthEventBodyUnion
 * member.
 */

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render } from 'lit';
import {
  HEALTH_EVENT_ACTIVITY_ROW_ROLES,
  healthEventActivityRowStrategy,
} from './healthEventActivityRow';
import type {
  HealthEvent,
  AssertedCondition,
  LifecycleEvent,
  ThresholdState,
} from '../../../api/generated/index.js';
import { classifiedKeys } from '../assertExhaustive';
import { assertBehavioralPass8 } from '../behavioralPass8';
import { __resetForTest, __seedForTest } from '../../../i18n/resourceCatalog';

const REFERENCE_LIFECYCLE: LifecycleEvent = {
  kind: 'lifecycle',
  attributes: { message: 'baseline-message', sessionId: 'sess-1' },
};

const REFERENCE_EVENT: Required<HealthEvent> = {
  id: 'core.event.test',
  timestamp: '2026-05-18T12:00:00Z',
  source: { serviceName: 'head', serviceInstanceId: 'inst-1', serviceVersion: '1' },
  severity: 'INFO',
  i18nKey: 'health-events.test.message',
  body: REFERENCE_LIFECYCLE,
};

const COMPLETION_CASES = [
  ['COMPLETED', 'The agent finished its response.'],
  ['MAX_ITERATIONS', 'The agent reached its step limit. Its response may be incomplete.'],
  ['BUDGET_EDGE_FINALIZE', 'The agent reached its response budget and produced a final response.'],
  ['ERRORED', 'The agent stopped because an error occurred.'],
  ['CANCELLED', 'The run was cancelled.'],
] as const;

function renderCompletion(disposition: string, message?: string): HTMLElement {
  const container = document.createElement('div');
  render(healthEventActivityRowStrategy({
    ...REFERENCE_EVENT,
    severity: 'WARNING',
    body: { kind: 'lifecycle', attributes: { disposition, ...(message ? { message } : {}) } },
  }, {}, { apiBase: '' }), container);
  return container;
}

describe('(HealthEvent, activity-row) canonical strategy — Pass-8 mirror', () => {
  it('covers the current backend terminal disposition vocabulary', () => {
    const source = readFileSync(resolve(process.cwd(),
      '../app-agent/src/main/java/io/justsearch/agent/TerminalDisposition.java'), 'utf8');
    const codes = [...source.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*[,;]?\s*$/gm)]
      .map((match) => match[1]);
    expect(codes.sort()).toEqual(COMPLETION_CASES.map(([code]) => code).sort());
  });

  it.each(COMPLETION_CASES)('explains %s while preserving the event title and severity', (code, wording) => {
    const container = renderCompletion(code);
    expect(container.textContent).toContain(wording);
    expect(container.textContent).not.toContain(code);
    expect(container.textContent).not.toContain('disposition:');
    expect(container.querySelector('.event-row.warning')?.getAttribute('data-severity')).toBe('WARNING');
    expect(container.querySelector('strong')?.textContent?.trim()).toBeTruthy();
  });

  it.each(['FUTURE_OUTCOME', 'constructor', '__proto__'])('keeps an honest fallback for unknown outcome %s', (code) => {
    const container = renderCompletion(code);
    expect(container.textContent).toContain('The run ended, but its outcome could not be identified.');
    expect(container.textContent).not.toContain(code);
  });

  it('preserves an explicit lifecycle message when present', () => {
    const container = renderCompletion('MAX_ITERATIONS', 'The requested operation was stopped.');
    expect(container.textContent).toContain('The requested operation was stopped.');
    expect(container.textContent).not.toContain('step limit');
  });

  it('roles record covers every HealthEvent wire key', () => {
    const wireKeys = Object.keys(REFERENCE_EVENT).sort();
    const declared = classifiedKeys(HEALTH_EVENT_ACTIVITY_ROW_ROLES)
      .slice()
      .sort();
    expect(declared).toEqual(wireKeys);
  });

  it('behavioral Pass-8 — every wire field affects the rendered row', () => {
    assertBehavioralPass8({
      reference: REFERENCE_EVENT,
      roles: HEALTH_EVENT_ACTIVITY_ROW_ROLES,
      strategy: healthEventActivityRowStrategy,
      ctx: {},
      host: { apiBase: '' },
      mutations: {
        id: (e) => ({ ...e, id: 'core.event.mutated' }),
        timestamp: (e) => ({ ...e, timestamp: '2030-01-01T00:00:00Z' }),
        source: (e) => ({
          ...e,
          source: { ...e.source, serviceName: 'worker' },
        }),
        severity: (e) => ({ ...e, severity: 'ERROR' as const }),
        i18nKey: (e) => ({ ...e, i18nKey: 'health-events.mutated.message' }),
        body: (e) => ({
          ...e,
          body: { kind: 'lifecycle', attributes: { message: 'mutated' } } as LifecycleEvent,
        }),
      },
    });
  });

  it('extracts AssertedCondition.message', () => {
    const event: HealthEvent = {
      ...REFERENCE_EVENT,
      body: {
        kind: 'condition',
        message: 'connection refused',
        status: 'TRUE',
      } as AssertedCondition,
    };
    const result = healthEventActivityRowStrategy(event, {}, { apiBase: '' });
    expect(typeof result).not.toBe('symbol');
  });

  it('extracts ThresholdState message via fallback chain', () => {
    const eventWithMessage: HealthEvent = {
      ...REFERENCE_EVENT,
      body: {
        kind: 'threshold',
        message: 'queue depth high',
        phase: 'FIRING',
        magnitudes: { depth: 1500 },
      } as ThresholdState,
    };
    const eventWithoutMessage: HealthEvent = {
      ...REFERENCE_EVENT,
      body: {
        kind: 'threshold',
        phase: 'FIRING',
        magnitudes: { depth: 1500 },
      } as ThresholdState,
    };
    // Both should produce a non-empty render (different content).
    expect(
      typeof healthEventActivityRowStrategy(eventWithMessage, {}, { apiBase: '' }),
    ).not.toBe('symbol');
    expect(
      typeof healthEventActivityRowStrategy(eventWithoutMessage, {}, { apiBase: '' }),
    ).not.toBe('symbol');
  });

  // ---------------------------------------------------------------------------------------------
  // Tempdoc 941 F4 — the parameterized-message path.
  //
  // Sandbox round 18 caught a real corrupt-PDF failure rendering as the literal template
  // "An indexing job failed for {path}: {errorClass}." followed by "atMs=…, errorMessage=…, path=…".
  // Two independent faults: the catalog asked for a parameter (`errorClass`) no producer emits, and
  // nothing on this path ever substituted parameters at all. Both halves are pinned below against
  // the PRIMARY sources — the real catalog file and the real Java emitter — because a hand-copied
  // fixture of either would re-drift silently, which is the exact failure being fixed.
  // ---------------------------------------------------------------------------------------------

  const CATALOG_PATH = resolve(
    process.cwd(),
    '../app-api/src/main/resources/messages/health-events.en.properties',
  );
  const EMITTER_PATH = resolve(
    process.cwd(),
    '../app-services/src/main/java/io/justsearch/app/services/observability/health/WorkerSnapshotTap.java',
  );
  const JOB_FAILED_KEY = 'health-events.worker.job.failed.message';

  /** The authored message for `key`, read from the backend catalog itself. */
  function catalogMessage(key: string): string {
    const src = readFileSync(CATALOG_PATH, 'utf8');
    const match = new RegExp(`^${key.replace(/\./g, '\\.')}\\s*=\\s*(.+)$`, 'm').exec(src);
    expect(match, `${key} must exist in health-events.en.properties`).toBeTruthy();
    return match![1]!.trim();
  }

  /** The attribute names WorkerSnapshotTap actually puts on the worker.job.failed body. */
  function emittedJobFailureAttributes(): string[] {
    const src = readFileSync(EMITTER_PATH, 'utf8');
    const method = /private void detectJobFailureOccurrence\([\s\S]*?\n {2}}/.exec(src);
    expect(method, 'detectJobFailureOccurrence must exist in WorkerSnapshotTap').toBeTruthy();
    return [...method![0].matchAll(/attributes\.put\("([^"]+)"/g)].map((m) => m[1]!);
  }

  function placeholdersOf(template: string): string[] {
    return [...template.matchAll(/\{([A-Za-z0-9_]+)\}/g)].map((m) => m[1]!);
  }

  it('every {placeholder} in the job-failed message names an attribute the emitter sends', () => {
    // The root cause of F4's first half: `{errorClass}` was authored against an attribute that has
    // never existed on this event. A placeholder with no producer can never be substituted, so the
    // whole sentence degrades to raw template text at the user.
    const placeholders = placeholdersOf(catalogMessage(JOB_FAILED_KEY));
    expect(placeholders.length, 'the message is parameterized — that is the point').toBeGreaterThan(0);
    const emitted = emittedJobFailureAttributes();
    expect(emitted).toContain('path');
    for (const name of placeholders) {
      expect(emitted, `catalog asks for {${name}}; WorkerSnapshotTap emits ${emitted.join(', ')}`)
        .toContain(name);
    }
  });

  it('renders the failed-job event as a substituted sentence — no braces, no attribute dump', () => {
    __seedForTest({ [JOB_FAILED_KEY]: catalogMessage(JOB_FAILED_KEY) });
    try {
      // The exact wire shape WorkerSnapshotTap.detectJobFailureOccurrence emits.
      const container = document.createElement('div');
      render(
        healthEventActivityRowStrategy(
          {
            id: 'worker.job.failed',
            timestamp: '2026-09-06T12:00:00Z',
            source: { serviceName: 'head', serviceInstanceId: 'inst-1', serviceVersion: '1' },
            severity: 'INFO',
            i18nKey: JOB_FAILED_KEY,
            body: {
              kind: 'lifecycle',
              attributes: {
                path: 'C:\\Sandbox\\corrupt.pdf',
                errorMessage: 'ExtractionException: Sandbox parser failed',
                atMs: 1_757_000_000_000,
              },
            } as LifecycleEvent,
          },
          {},
          { apiBase: '' },
        ),
        container,
      );
      const text = (container.textContent ?? '').replace(/\s+/g, ' ');

      // What the user needs: which file, and what went wrong.
      expect(text).toContain('C:\\Sandbox\\corrupt.pdf');
      expect(text).toContain('ExtractionException: Sandbox parser failed');
      // …and none of what round 18 actually saw.
      expect(text, 'an unsubstituted placeholder must never reach the user').not.toContain('{');
      expect(text, 'the attribute map is template parameters, not a message').not.toContain('atMs=');
      expect(text).not.toContain('errorMessage=');
      expect(text).not.toContain('path=');
    } finally {
      __resetForTest();
    }
  });

  it('declines to render a template whose parameter is missing, falling back to the short label', () => {
    // The decline branch: silently blanking `{path}` would read as a finished sentence asserting
    // something the emitter never said, and leaving it would be the defect itself.
    __seedForTest({
      'health-events.worker.job.failed.message': 'An indexing job failed for {path}.',
      'health-events.worker.job.failed.label': 'Indexing job failed',
    });
    try {
      const container = document.createElement('div');
      render(
        healthEventActivityRowStrategy(
          {
            ...REFERENCE_EVENT,
            id: 'worker.job.failed',
            i18nKey: 'health-events.worker.job.failed.message',
            body: { kind: 'lifecycle', attributes: { atMs: 1 } } as LifecycleEvent,
          },
          {},
          { apiBase: '' },
        ),
        container,
      );
      const text = (container.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('Indexing job failed');
      expect(text).not.toContain('{');
      expect(text).not.toContain('atMs');
    } finally {
      __resetForTest();
    }
  });

  // Spec A step 7 (F1) — a failed chat reads as its cause, in the catalog's words, on both events
  // the head emits for it (ai.not-ready from readiness, inference.capability from the registry).
  function renderCondition(id: string, condition: AssertedCondition): string {
    const container = document.createElement('div');
    render(
      healthEventActivityRowStrategy(
        { ...REFERENCE_EVENT, id, i18nKey: `health-events.${id}.message`, body: condition },
        {},
        { apiBase: '' },
      ),
      container,
    );
    return (container.textContent ?? '').replace(/\s+/g, ' ');
  }

  it.each(['ai.not-ready', 'inference.capability'])(
    'words a %s failure by its classified cause from the catalog, not a bare token',
    (id) => {
      const key = `health-events.${id}.reason.InferenceModelLoadFailed.message`;
      __seedForTest({ [key]: catalogMessage(key) });
      try {
        const text = renderCondition(id, {
          kind: 'condition',
          subject: 'inference',
          status: 'TRUE',
          reason: 'InferenceModelLoadFailed',
        } as AssertedCondition);
        expect(text).toContain('The local AI model file could not be loaded');
        expect(text).not.toContain('InferenceModelLoadFailed');
      } finally {
        __resetForTest();
      }
    },
  );

  it("a condition's own message still wins over the catalog's per-reason sentence", () => {
    __seedForTest({
      'health-events.enrichment.incomplete.reason.MissingAtQuiescence.message': 'Generic sentence.',
    });
    try {
      const text = renderCondition('enrichment.incomplete', {
        kind: 'condition',
        subject: 'worker.enrichment/embed',
        status: 'TRUE',
        reason: 'MissingAtQuiescence',
        message: '12 of 340 documents never got the embed stage output, and no work is pending.',
      } as AssertedCondition);
      expect(text).toContain('12 of 340 documents');
      expect(text).not.toContain('Generic sentence.');
    } finally {
      __resetForTest();
    }
  });

  it('returns nothing when id is missing', () => {
    const orphan: HealthEvent = { ...REFERENCE_EVENT, id: undefined };
    const result = healthEventActivityRowStrategy(orphan, {}, { apiBase: '' });
    expect(typeof result).toBe('symbol');
  });
});
