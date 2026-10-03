#!/usr/bin/env node
/**
 * Shared "stage a reference corpus" helper (tempdoc 669).
 *
 * Extracted from the onramp's original inline logic
 * (scripts/dev/test-onramp-first-success.mjs, tempdoc 656 O4) so the onramp
 * script and the demo-corpus staging script share one
 * start-stack → ingest → poll → query → assert implementation instead of
 * drifting copies. Generic across any reference corpus: the caller supplies
 * the corpus path, the canary query, and (optionally) a larger poll budget
 * for a messier/larger corpus.
 */
'use strict';
import { spawn, execFileSync } from 'node:child_process';
import { createOperationKey } from '../../../modules/ui-web/src/api/operationKey.ts';

export function makeLogger(label) {
  return (m) => console.error(`[${label}] ${m}`);
}

export async function getJson(base, route, opts) {
  const res = await fetch(base + route, { signal: AbortSignal.timeout(15000), ...opts });
  const text = await res.text();
  let body;
  try { body = text ? JSON.parse(text) : null; }
  catch { body = text; }
  if (!res.ok) {
    const detail = typeof body === 'string' ? body : JSON.stringify(body);
    const error = new Error(`${route} → HTTP ${res.status}${detail ? `: ${detail}` : ''}`);
    error.status = res.status;
    error.responseBody = body;
    throw error;
  }
  return body;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Spawn `dev-runner start`, resolve with {apiBaseUrl} once the ok:true JSON line is seen. */
// startTimeoutMs is the OUTER waiter; keep it >= dev-runner's own CI readiness timeout (300s,
// dev-runner.cjs:1146/1154) so this waiter never kills dev-runner first and mask its specific error
// behind a generic "stack start timed out" (tempdoc 656 §H timeout-inversion). 330s = 300s + margin.
export function startStack({ repoRoot, devRunner, startTimeoutMs = 330000 }) {
  return new Promise((resolve, reject) => {
    const child = spawn('node', [devRunner, 'start', '--clean', 'hard', '--json'],
      { cwd: repoRoot, stdio: ['ignore', 'pipe', 'inherit'] });
    let buf = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('stack start timed out')); }, startTimeoutMs);
    child.stdout.on('data', (d) => {
      buf += d.toString();
      const line = buf.split('\n').find((l) => l.includes('"apiPort"'));
      if (line) {
        try {
          const r = JSON.parse(line.trim());
          if (r.ok && r.apiPort) { clearTimeout(timer); resolve({ apiBaseUrl: r.apiBaseUrl || `http://127.0.0.1:${r.apiPort}`, child }); }
        } catch { /* partial line */ }
      }
    });
    child.on('exit', (code) => { clearTimeout(timer); reject(new Error(`dev-runner exited early (${code})`)); });
  });
}

export function stopStack({ repoRoot, devRunner }) {
  try { execFileSync('node', [devRunner, 'stop', '--active', '--json'], { cwd: repoRoot, stdio: 'ignore' }); }
  catch { /* best-effort teardown */ }
}

/**
 * Ingest `corpusPath`, poll until indexing settles, run `query`, and assert
 * at least one result with a resolved search mode. Returns `{ results, mode }`.
 *
 * `pollAttempts`/`pollIntervalMs` default to the onramp's original 30×1000ms
 * budget (unchanged behavior for existing callers). A larger/OCR-bearing
 * corpus should pass a bigger budget — OCR's own per-file timeout
 * (`OcrRoutingConfig.perFileTimeoutMs`, default 30000ms) can alone approach
 * the onramp's entire default poll window (see tempdoc 669's
 * confidence-building pass).
 */
export async function stageAndVerify({
  base, corpusPath, query, resultLimit = 5,
  pollAttempts = 30, pollIntervalMs = 1000, failLabel = 'STAGING FAILED',
}) {
  // dev-runner reports "stack up" once the Head answers /api/status (200), but the Worker that serves
  // /api/knowledge/ingest may still be warming up and returns a transient 503 for a beat (tempdoc 656
  // §J — surfaced once the port-wait fix made stack-up fast; the old full-timeout startup masked it by
  // giving the worker ~minutes). Retry transport uncertainty/readiness refusal with one operation
  // key until the operation returns its success receipt, within the same bounded poll budget.
  let ing;
  let lastErr;
  const operationKey = createOperationKey();
  for (let i = 0; i < pollAttempts; i++) {
    try {
      ing = await getJson(base, '/api/knowledge/ingest', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ paths: [corpusPath], idempotencyKey: operationKey }),
      });
      if (ing?.success !== true) {
        throw new Error(`ingest operation failed: ${JSON.stringify(ing)}`);
      }
      const metadata = ing.structuredData;
      if (metadata?.operationKey !== operationKey
          || !Number.isInteger(metadata?.operationRecordId)) {
        throw new Error(`ingest response omitted matching operation metadata: ${JSON.stringify(ing)}`);
      }
      break;
    } catch (e) {
      lastErr = e;
      // A missing HTTP response is an unknown delivery outcome; the stable key makes retry safe.
      // A 503 is the documented Worker-readiness refusal. Other HTTP/application failures retain
      // their complete response and fail immediately rather than being reclassified as warm-up.
      if (Number.isInteger(e?.status) && !isWorkerReadinessRefusal(e)) {
        throw new Error(`${failLabel}: ingest failed without retry: ${e.message}`, { cause: e });
      }
      if (!Number.isInteger(e?.status) && !isTransportFailure(e)) {
        throw new Error(`${failLabel}: ingest failed without retry: ${e.message}`, { cause: e });
      }
    }
    await sleep(pollIntervalMs);
  }
  if (ing?.success !== true) {
    throw new Error(`${failLabel}: ingest operation did not become ready within ${Math.round((pollAttempts * pollIntervalMs) / 1000)}s (last: ${lastErr?.message})`);
  }

  // Fail LOUDLY if indexing never settles — a silent fallthrough here previously surfaced as a
  // misleading "query returned 0 results" failure later, hiding the real cause (settle timeout,
  // not a search defect).
  let settled = false;
  for (let i = 0; i < pollAttempts; i++) {
    const s = await getJson(base, '/api/status');
    const c = s.worker?.core;
    if (c && c.pendingJobs === 0 && c.indexState === 'IDLE' && c.indexedDocuments > 0) { settled = true; break; }
    await sleep(pollIntervalMs);
  }
  if (!settled) {
    throw new Error(`${failLabel}: indexing did not settle within ${Math.round((pollAttempts * pollIntervalMs) / 1000)}s — increase the poll budget or check ingest; NOT a search failure`);
  }

  const r = await getJson(base, '/api/knowledge/search', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, limit: resultLimit }),
  });
  const results = r.results || [];
  const mode = r.searchTrace?.effectiveMode;

  if (results.length < 1) throw new Error(`${failLabel}: query returned 0 results (mode=${mode})`);
  if (!mode) throw new Error(`${failLabel}: no searchTrace.effectiveMode`);

  return { results, mode };
}

function isTransportFailure(error) {
  return error instanceof TypeError
    || error?.name === 'TimeoutError'
    || error?.name === 'AbortError';
}

function isWorkerReadinessRefusal(error) {
  return error?.status === 503
    && typeof error?.responseBody?.unavailable === 'string'
    && error.responseBody.unavailable.length > 0;
}

/** Subprocess-and-parse the doctor's tier (existing precedent — doctor.mjs itself is untouched). */
export function getTier({ repoRoot, doctorPath }) {
  try {
    return JSON.parse(execFileSync('node', [doctorPath, '--json'], { cwd: repoRoot }).toString()).tier;
  } catch { return '(unknown)'; }
}
