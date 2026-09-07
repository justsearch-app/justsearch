/**
 * Slice 448 phase 5 — diagnosticChannelStrategy tests.
 */

import { describe, expect, it } from 'vitest';
import type { SseEnvelope } from '../streaming/envelope-types';
import type {
  DiagnosticEvent,
  DiagnosticEventEnvelope,
} from '../../api/types/diagnostic';
import { KIND_LOG_EVENT } from '../../api/types/diagnostic';
import { diagnosticChannelStrategy } from './diagnosticChannelStrategy';

function event(
  level: string,
  message: string,
  subCategory: DiagnosticEvent['subCategory'] = 'CORE_DIAGNOSTIC',
  loggerName: string = 'io.justsearch.example.X',
): DiagnosticEvent {
  return {
    level,
    message,
    loggerName,
    threadName: 'main',
    threadId: 1,
    timestamp: '2026-05-07T10:00:00Z',
    mdc: {},
    dataClasses: [],
    subCategory,
  };
}

function update(payload: DiagnosticEventEnvelope, seq: number): SseEnvelope {
  return {
    streamId: 'system:diagnostic-core-engine-log',
    frameKind: 'UPDATE',
    seq,
    ts: '2026-05-07T10:00:00Z',
    payload,
    resumeToken: `tok-${seq}`,
  };
}

function lifecycle(kind: string, seq: number): SseEnvelope {
  return {
    streamId: 'system:diagnostic-core-engine-log',
    frameKind: 'LIFECYCLE',
    seq,
    ts: '2026-05-07T10:00:00Z',
    payload: { kind },
    resumeToken: `tok-${seq}`,
  };
}

describe('diagnosticChannelStrategy', () => {
  it('appends log-event payloads into the events array', () => {
    const strat = diagnosticChannelStrategy();
    let s = strat.initialState;
    s = strat.reducer(
      s,
      update({ kind: KIND_LOG_EVENT, event: event('INFO', 'a') }, 1),
    );
    s = strat.reducer(
      s,
      update({ kind: KIND_LOG_EVENT, event: event('WARN', 'b') }, 2),
    );
    expect(s.data.events).toHaveLength(2);
    expect(s.data.events[0]?.message).toBe('a');
    expect(s.data.events[1]?.level).toBe('WARN');
    expect(s.catalogVersion).toBe(2);
  });

  it('respects the ring-buffer cap by evicting oldest', () => {
    const strat = diagnosticChannelStrategy({ cap: 2 });
    let s = strat.initialState;
    s = strat.reducer(
      s,
      update({ kind: KIND_LOG_EVENT, event: event('INFO', 'a') }, 1),
    );
    s = strat.reducer(
      s,
      update({ kind: KIND_LOG_EVENT, event: event('INFO', 'b') }, 2),
    );
    s = strat.reducer(
      s,
      update({ kind: KIND_LOG_EVENT, event: event('INFO', 'c') }, 3),
    );
    expect(s.data.events.map((e) => e.message)).toEqual(['b', 'c']);
  });

  it('client-side filter drops events whose subCategory is not in the filter', () => {
    const strat = diagnosticChannelStrategy({
      subCategoryFilter: ['CORE_DIAGNOSTIC'],
    });
    let s = strat.initialState;
    s = strat.reducer(
      s,
      update(
        { kind: KIND_LOG_EVENT, event: event('INFO', 'core', 'CORE_DIAGNOSTIC') },
        1,
      ),
    );
    s = strat.reducer(
      s,
      update(
        {
          kind: KIND_LOG_EVENT,
          event: event('INFO', 'lib', 'LIBRARY_TRACE'),
        },
        2,
      ),
    );
    expect(s.data.events.map((e) => e.message)).toEqual(['core']);
    // Seq advances even on filtered events (cursor must keep moving).
    expect(s.catalogVersion).toBe(2);
  });

  it('empty filter accepts all subCategories', () => {
    const strat = diagnosticChannelStrategy();
    let s = strat.initialState;
    s = strat.reducer(
      s,
      update(
        { kind: KIND_LOG_EVENT, event: event('INFO', 'a', 'LIBRARY_TRACE') },
        1,
      ),
    );
    s = strat.reducer(
      s,
      update(
        { kind: KIND_LOG_EVENT, event: event('INFO', 'b', 'BOOT_TRACE') },
        2,
      ),
    );
    expect(s.data.events.map((e) => e.message)).toEqual(['a', 'b']);
  });

  it('LIFECYCLE.reset clears the events array', () => {
    const strat = diagnosticChannelStrategy();
    let s = strat.initialState;
    s = strat.reducer(
      s,
      update({ kind: KIND_LOG_EVENT, event: event('INFO', 'a') }, 1),
    );
    s = strat.reducer(s, lifecycle('reset', 2));
    expect(s.data.events).toEqual([]);
    expect(s.catalogVersion).toBe(2);
  });

  it('LIFECYCLE.connected / heartbeat advance seq without state change', () => {
    const strat = diagnosticChannelStrategy();
    let s = strat.initialState;
    s = strat.reducer(s, lifecycle('connected', 1));
    s = strat.reducer(s, lifecycle('heartbeat', 2));
    expect(s.data.events).toEqual([]);
    expect(s.catalogVersion).toBe(2);
  });

  it('UPDATE with non-log-event kind is ignored', () => {
    const strat = diagnosticChannelStrategy();
    let s = strat.initialState;
    s = strat.reducer(s, update({ kind: 'unknown-future-kind', event: event('INFO', 'x') }, 1));
    expect(s.data.events).toEqual([]);
  });
});
