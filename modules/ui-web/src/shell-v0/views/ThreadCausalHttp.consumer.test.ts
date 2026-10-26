// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Ordinary unit invocation is self-contained. Explicit boundary invocation:
 * THREAD_ORDER_HTTP_BRIDGE=1 THREAD_ORDER_HTTP_DIR=/absolute/producer/output npm run test:unit:run -- src/shell-v0/views/ThreadCausalHttp.consumer.test.ts
 * Bridge mode requires actual producer files; missing/unreadable input fails, with no fallback.
 */
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, expect, it, vi } from 'vitest';
import { __resetConversationListForTest } from '../state/conversationListStore.js';
import { fetchUnifiedThread } from './unifiedThreadClient.js';
import { projectUnifiedThread, type ThreadEvent } from './unifiedThreadProjection.js';
import { projectSv3RecordTurns } from './search-v3/sv3-record.js';

const ids = [
  'f0000000-0000-4000-8000-000000000000', 'e0000000-0000-4000-8000-000000000000',
  'd0000000-0000-4000-8000-000000000000', 'c0000000-0000-4000-8000-000000000000',
];
afterEach(() => {
  vi.unstubAllGlobals();
  __resetConversationListForTest();
});

async function decode(raw: string): Promise<ThreadEvent[]> {
  // The shared product HTTP decoder consumes the untouched actual response body.
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(raw, { status: 200, headers: { 'Content-Type': 'application/json' } })));
  const response = await fetchUnifiedThread('http://127.0.0.1:32123', 'owned-consumer');
  expect(response.events, raw).toHaveLength(JSON.parse(raw).events.length);
  return response.events;
}

function assertPairs(events: ThreadEvent[], outcome: string, scoped: boolean): void {
  const original = JSON.stringify(events);
  const turns = projectSv3RecordTurns(events);
  const status = outcome === 'ANSWER' ? 'complete' : outcome === 'NO_CONTENT' && !scoped ? 'no-evidence' : 'failed';
  expect(turns.map(t => [t.recordId, t.assistantRecordId, t.question, t.status])).toEqual([
    [ids[0], ids[1], 'Q1', status], [ids[2], ids[3], 'Q2', status],
  ]);
  expect(turns).toHaveLength(2);
  expect(projectUnifiedThread(events).map(i => i.id)).toEqual(ids);
  if (outcome === 'ANSWER') expect(turns.map(t => t.answer)).toEqual(['E1', 'E2']);
  else {
    expect(events.filter(e => e.kind === 'ERROR').map(e => [e.attributes.errorCode, e.attributes.retrievalScoped]))
      .toEqual([[outcome, scoped], [outcome, scoped]]);
  }
  expect(JSON.stringify(events)).toBe(original);
}

if (process.env.THREAD_ORDER_HTTP_BRIDGE === '1') {
  it('consumes required ACTUAL persisted HTTP JSON across restart/reload, with hashes and no fallback (WU3/WU4/WU7/WU8)', async () => {
    const directory = process.env.THREAD_ORDER_HTTP_DIR;
    if (!directory) throw new Error('THREAD_ORDER_HTTP_DIR is required in HTTP bridge mode');
    const results: Array<Record<string, unknown>> = [];
    async function read(name: string): Promise<ThreadEvent[]> {
      const path = join(directory!, `${name}.json`);
      const raw = readFileSync(path, 'utf8');
      const sha = createHash('sha256').update(raw).digest('hex');
      expect(sha, path).toBe(readFileSync(`${path}.sha256`, 'utf8').trim());
      const storeSha = createHash('sha256').update(readFileSync(join(directory!, `${name}.messages.jsonl`))).digest('hex');
      expect(storeSha, path).toBe(readFileSync(join(directory!, `${name}.messages.sha256`), 'utf8').trim());
      const events = await decode(raw);
      results.push({ path, sha256: sha, storeSha256: storeSha, rows: events.map(e => ({ id: e.id, occurredAt: e.occurredAt, kind: e.kind, attributes: e.attributes })) });
      return events;
    }
    for (const outcome of ['ANSWER', 'NO_CONTENT', 'RETRIEVAL_TIMEOUT', 'RETRIEVAL_FAILED', 'FETCH_FAILED']) {
      for (const scoped of [false, true]) {
        const hashes: string[] = [];
        for (const stage of ['initial', 'initial-reload', 'restart', 'restart-reload']) {
          const name = `${outcome}-${scoped}-${stage}`;
          const events = await read(name);
          assertPairs(events, outcome, scoped);
          hashes.push(readFileSync(join(directory, `${name}.messages.sha256`), 'utf8'));
        }
        expect(new Set(hashes).size).toBe(1);
      }
    }
    const mixed = projectUnifiedThread(await read('mixed'));
    expect(mixed.map(i => [i.id, i.segment?.nodeId])).toEqual([
      [ids[0], undefined], ['c2', 'first'], [ids[1], 'first'], ['d1', 'first'],
      [ids[2], undefined], ['e2', 'second'], [ids[3], 'second'],
    ]);
    // Fixed action slots allow a chat's workflow association to change: Q1 formerly occupied
    // the last chat slot (inside 'second'); restored Q1 is now outside. Actions stay grouped.
    expect(mixed[1]!.kind).toBe('assistant');
    expect(mixed[3]!.attributes.errorCode).toBe('ACTION_ERROR');
    expect(mixed[2]!.attributes.reasoning).toEqual([{ text: 'trailing', durationMs: 10 }]);
    const legacy = projectUnifiedThread(await read('legacy-duplicates'));
    expect(legacy.map(i => i.content)).toEqual(['parent Q', 'parent E', 'child Q', 'child E', 'duplicate Q', 'duplicate E']);
    expect(legacy.map(i => i.id)).toEqual(['idx-0', 'idx-1', 'idx-0', 'idx-1', 'duplicate', 'duplicate']);
    const times = projectUnifiedThread(await read('legacy-times'));
    expect(times.map(i => i.id)).toEqual(['missing', 'malformed', 'numeric', 'early', 'offset', 'late']);
    expect(times.slice(3).map(i => i.subMillisecond)).toEqual([0.494108, 0.494108, 0.494109]);
    expect(times.slice(0, 3).map(i => i.ts)).toEqual([0, 0, 1000]);
    writeFileSync(join(directory, 'consumer-result.json'), JSON.stringify({
      mode: 'actual-http', invocation: process.argv, bridge: process.env.THREAD_ORDER_HTTP_BRIDGE,
      directory, result: 'passed', inputs: results,
    }, null, 2));
  });
} else {
  it('unit counterpart pairs canonical equal-time rows independently of Java artifacts (not HTTP evidence)', async () => {
    const events = ids.map((id, i): ThreadEvent => ({
      id, occurredAt: '2026-10-23T02:04:45.355494108Z', kind: i % 2 === 0 ? 'USER_MESSAGE' : 'ERROR',
      originator: i % 2 === 0 ? 'user' : 'agent', content: `${i % 2 === 0 ? 'Q' : 'E'}${Math.floor(i / 2) + 1}`,
      attributes: i % 2 === 0 ? { shapeId: 'core.rag-ask' } : { errorCode: 'NO_CONTENT', retrievalScoped: false },
    }));
    assertPairs(await decode(JSON.stringify({ conversationId: 'owned-consumer', events, lifecycles: [] })), 'NO_CONTENT', false);
  });
}
