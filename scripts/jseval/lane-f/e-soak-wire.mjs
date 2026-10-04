/** Projection of append-only cycle/collector journals, including a killed final cycle. */
import fs from 'node:fs';
import path from 'node:path';
import { boundaryCensored, terminalComplete, wireOutcomes } from './e-agent-metrics.cjs';

function journal(file) {
  if (!file || !fs.existsSync(file)) return { events: [], missing: true };
  const lines = fs.readFileSync(file, 'utf8').split('\n'), events = [];
  let invalid = false;
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].trim()) continue;
    try { events.push(JSON.parse(lines[i])); } catch { invalid = true; }
  }
  return { events, invalid };
}
function requestRows(events) {
  const rows = new Map();
  for (const e of events.filter(e => e.requestId != null)) {
    rows.set(e.requestId, { ...rows.get(e.requestId), ...e });
  }
  return [...rows.values()];
}
const cleanHttp = c => !c.error && c.status >= 200 && c.status < 300;

export function soakWire(record, calls, summaries) {
  const gaps = [], checks = [], search = [];
  const cycles = record.commands.filter(c => /^soak-cycle-/.test(c.label));
  if (!cycles.length) checks.push(undefined);
  for (const cycle of cycles) {
    const option = cycle.args?.indexOf('--search-load-outcomes') ?? -1;
    const file = cycle.outcomesFile ?? (option >= 0 ? cycle.args[option + 1] : undefined);
    const { events, missing, invalid } = journal(file);
    const rows = requestRows(events), ended = events.some(e => e.event === 'load-end');
    let complete = !missing && !invalid && events.some(e => e.event === 'load-start')
      && rows.some(e => e.event === 'request-outcome');
    for (const row of rows) {
      if (row.event !== 'request-outcome') {
        const elapsed = Date.parse(cycle.endedAt) - row.atMs;
        const cut = cycle.cancellationCause === 'fixed-window-end' && !ended
          && Number.isFinite(elapsed) && elapsed >= 0 && elapsed < row.requestTimeoutMs;
        if (cut && !row.error && (row.status == null || row.status >= 200 && row.status < 300)) Object.assign(row, {
          error: 'WINDOW_BOUNDARY_CANCELLED', windowBoundary: true, cancellationCause: 'fixed-window-end',
        });
        else if (row.status == null || row.status >= 200 && row.status < 300) complete = false;
      }
      // An observed HTTP error is a failure even without a final completion event.
      checks.push(row.status >= 300 || row.error && !boundaryCensored(row) ? false
        : row.event === 'request-outcome' ? cleanHttp(row) : boundaryCensored(row) ? true : undefined);
      search.push(row);
    }
    if (events.some(e => e.event === 'load-error')) checks.push(false);
    if (!ended && cycle.cancellationCause !== 'fixed-window-end') complete = false;
    checks.push(complete ? true : undefined);
    if (!complete) gaps.push(`${cycle.label}: missing/incomplete per-request search outcomes (${file ?? 'no journal path'})`);
  }
  const collectorFile = path.join(record.raw, 'collector-wire.jsonl');
  const collectorJournal = journal(collectorFile), collector = requestRows(collectorJournal.events);
  checks.push(!collectorJournal.missing && !collectorJournal.invalid && collector.length ? true : undefined);
  if (collectorJournal.missing || collectorJournal.invalid || !collector.length) gaps.push('Missing collector HTTP request journal');
  for (const c of collector) checks.push(c.status >= 300 || c.error ? false
    : c.event === 'request-outcome' ? cleanHttp(c) : undefined);
  const instrument = path.join(record.raw, 'soak/instruments.json');
  const errors = fs.existsSync(instrument) ? JSON.parse(fs.readFileSync(instrument)).errors ?? [] : [];
  for (const e of errors) if (/HTTP\s+[45]\d\d|timeout/i.test(e.reason ?? '')) {
    checks.push(false); collector.push({ error: e.reason, atMs: e.atMs, source: instrument });
  }
  checks.push(calls.length ? calls.every(c => boundaryCensored(c) || !c.error && terminalComplete(c)
    && (c.status >= 200 && c.status < 300 || c.status === 429)) : undefined);
  const legacyErrors = summaries.reduce((n, s) => n + (s.search_load.outcomes_file ? 0 : s.search_load.errors ?? 0), 0);
  const summaryErrors = summaries.reduce((n, s) => n + (s.search_load.errors ?? 0), 0);
  if (summaries.some(s => s.search_load.errors > 0)) checks.push(false);
  return { check: checks.includes(false) ? false : checks.length && checks.every(c => c === true) ? true : undefined,
    gaps, cycles: cycles.length, search, collector, legacyErrors, summaryErrors,
    outcomes: wireOutcomes([...calls, ...search, ...collector]) };
}
