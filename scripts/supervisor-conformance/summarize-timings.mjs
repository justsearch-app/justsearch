#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export function summarize(records) {
  const phases = new Map();
  const add = (record, phase, ms) => {
    if (!Number.isFinite(ms) || ms < 0) return;
    const key = `${record.adapter}/${record.platform}/${phase}`;
    if (!phases.has(key)) phases.set(key, []);
    phases.get(key).push(ms);
  };
  const pairs = [
    ['spawnToEntry', 'spawnRequested', 'engineEntry'],
    ['entryToPort', 'engineEntry', 'portEmitted'],
    ['portToReady', 'portEmitted', 'ready'],
    ['quitToForce', 'quitRequested', 'forceIssued'],
    ['forceToExit', 'forceIssued', 'exitObserved'],
    ['quitToExit', 'quitRequested', 'exitObserved'],
    ['exitToStopped', 'exitObserved', 'stoppedPublished'],
  ];
  for (const record of records) {
    for (const [phase, from, to] of pairs) {
      for (const end of record.events.filter(row => row.phase === to && row.incarnation)) {
        const start = record.events.find(row => row.phase === from && row.incarnation === end.incarnation);
        if (start) add(record, phase, end.atMs - start.atMs);
      }
    }
    for (const row of record.measurements) add(record, row.phase, row.endedAtMs - row.startedAtMs);
  }
  return [...phases].sort(([a], [b]) => a.localeCompare(b)).map(([phase, values]) => {
    values.sort((a, b) => a - b);
    const percentile = p => values[Math.max(0, Math.ceil(values.length * p) - 1)];
    return { phase, n: values.length, p50: percentile(0.5), p95: percentile(0.95), max: values.at(-1) };
  });
}

function readRecords(target) {
  if (fs.statSync(target).isDirectory()) return fs.readdirSync(target).flatMap(name => readRecords(path.join(target, name)));
  return target.endsWith('.timing.json') ? [JSON.parse(fs.readFileSync(target, 'utf8'))] : [];
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const records = (process.argv.slice(2).length ? process.argv.slice(2) : ['tmp/supervisor-conformance/records']).flatMap(readRecords);
    if (!records.length) throw new Error('no conformance timing records found');
    console.log(`${records.length} records (${records.filter(row => row.problems.length).length} failed); milliseconds; nearest-rank percentiles; missing phases excluded`);
    console.log('adapter/platform/phase\tn\tp50\tp95\tmax');
    for (const row of summarize(records)) console.log(`${row.phase}\t${row.n}\t${row.p50}\t${row.p95}\t${row.max}`);
  } catch (err) {
    console.error(err.message);
    process.exitCode = 1;
  }
}
