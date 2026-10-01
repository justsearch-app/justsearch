/** Pure Stage E projections. Missing evidence never becomes zero or a passing clause. */
export const finite = n => typeof n === 'number' && Number.isFinite(n) && n >= 0;
export const splitGap = reason => ({ status: 'unmeasurable-on-split', reason,
  disposition: 'Report the native split mechanism alongside branch capability acceptance; retain the paired gap (design §16).' });
export const verdict = checks => checks.includes(false) ? 'fail'
  : checks.length && checks.every(c => c === true) ? 'pass' : 'unmeasurable';

export function gcLogOption(directory) {
  // Unified logging uses ':' as a delimiter, including on Windows drive paths.
  const destination = directory.replaceAll('\\', '/').replace(/^([A-Za-z]):/, '$1\\:');
  return `-Xlog:gc*,safepoint:file="${destination}/jvm-%p-%t.log":time,uptime,pid,level,tags:filecount=20,filesize=32m`;
}
const bytes = (value, unit) => Number(value) * ({ B: 1, K: 1024, M: 1024 ** 2, G: 1024 ** 3 }[unit] ?? NaN);
export function parseGc(text) {
  const points = [], pausesMs = [], capacities = [];
  for (const line of text.split(/\r?\n/)) {
    const stamp = /\[(\d{4}-\d\d-\d\dT[^\]]+)\]/.exec(line);
    const timeMs = stamp && Date.parse(stamp[1]);
    const used = /GC\((\d+)\).*?(\d+(?:\.\d+)?)([BKMG])->(\d+(?:\.\d+)?)([BKMG])\((\d+(?:\.\d+)?)([BKMG])\)/.exec(line);
    if (used && Number.isFinite(timeMs)) {
      points.push({ timeMs, gcId: Number(used[1]), bytes: bytes(used[4], used[5]),
        full: /Pause Full|Full GC/.test(line), kind: /Pause (Full|Young|Mixed)/.exec(line)?.[1] ?? 'other' });
      capacities.push(bytes(used[6], used[7]));
    }
    const pause = /(?:Pause .*?|Total:)\s*([\d.]+)\s*(ms|s|ns)\s*$/.exec(line);
    if (pause) pausesMs.push(Number(pause[1]) * ({ ms: 1, s: 1000, ns: 1e-6 }[pause[2]]));
  }
  return { points, pausesMs, capacities };
}
export function heapTrend(points, startMs, endMs, { warmupMs = 300000, minimumPoints = 4 } = {}) {
  const selected = [...new Map(points.filter(p => p.full && p.timeMs >= startMs + warmupMs
    && p.timeMs <= endMs && finite(p.bytes)).map(p => [`${p.timeMs}/${p.gcId}`, p])).values()]
    .sort((a, b) => a.timeMs - b.timeMs);
  const spanMs = selected.length ? selected.at(-1).timeMs - selected[0].timeMs : 0;
  if (selected.length < minimumPoints || spanMs < (endMs - startMs - warmupMs) / 2) {
    return { status: 'unmeasurable', points: selected, reason: 'Insufficient post-warmup full-GC coverage' };
  }
  const x = selected.map(p => (p.timeMs - selected[0].timeMs) / 60000);
  const meanX = x.reduce((a, b) => a + b, 0) / x.length;
  const meanY = selected.reduce((a, p) => a + p.bytes, 0) / selected.length;
  const divisor = x.reduce((a, n) => a + (n - meanX) ** 2, 0);
  if (divisor === 0) return { status: 'unmeasurable', points: selected, reason: 'No time span' };
  const bytesPerMinute = selected.reduce((a, p, i) => a + (x[i] - meanX) * (p.bytes - meanY), 0) / divisor;
  return { status: bytesPerMinute <= 0 ? 'pass' : 'fail', bytesPerMinute, spanMs, points: selected };
}
export function summedHeapTrend(series, startMs, endMs, toleranceMs = 30000) {
  if (!series.length) return { status: 'unmeasurable', reason: 'No arm JVM series' };
  const anchors = series[0].filter(p => p.full), used = series.map(() => new Set());
  const points = [];
  for (const anchor of anchors) {
    const matches = series.map((items, i) => items.filter(p => p.full && !used[i].has(p)
      && Math.abs(p.timeMs - anchor.timeMs) <= toleranceMs)
      .sort((a, b) => Math.abs(a.timeMs - anchor.timeMs) - Math.abs(b.timeMs - anchor.timeMs))[0]);
    if (matches.some(p => !p)) continue;
    matches.forEach((p, i) => used[i].add(p));
    points.push({ timeMs: Math.max(...matches.map(p => p.timeMs)), gcId: points.length,
      bytes: matches.reduce((sum, p) => sum + p.bytes, 0), full: true,
      constituentTimesMs: matches.map(p => p.timeMs) });
  }
  return { ...heapTrend(points, startMs, endMs), alignmentToleranceMs: toleranceMs };
}
export function roleOf(row, arm) {
  if (/HeadlessApp/.test(row.CommandLine ?? '')) return arm === 'main' ? 'head' : 'engine';
  if (/io\.justsearch\.indexerworker\.IndexerWorker/.test(row.CommandLine ?? '')) return 'worker';
  if (/ExtractionSandboxChild/.test(row.CommandLine ?? '')) return 'extraction-child';
  if (/^llama-server(?:\.exe)?$/i.test(row.Name ?? '')) return 'llama-server';
}
export function ownedProcesses(table, root, arm, retained = []) {
  if (!root || !table.some(p => Number(p.ProcessId) === root.pid
    && p.CreationFileTimeUtc === root.creationFileTimeUtc && p.CommandLine === root.cmdlineFingerprint)) {
    throw new Error('Owned root identity missing or reused');
  }
  const ids = new Set([root.pid]);
  for (const child of retained) if (table.some(row => Number(row.ProcessId) === child.pid
    && row.CreationFileTimeUtc === child.creationFileTimeUtc && row.CommandLine === child.cmdlineFingerprint)) ids.add(child.pid);
  for (let old = -1; old !== ids.size;) {
    old = ids.size;
    for (const row of table) if (ids.has(Number(row.ParentProcessId))) {
      const parent = table.find(p => Number(p.ProcessId) === Number(row.ParentProcessId));
      if (!row.CreationFileTimeUtc || !parent?.CreationFileTimeUtc) throw new Error('Descendant birth evidence missing');
      // A surviving child of a previous owner of the parent's PID is foreign to this run.
      if (BigInt(row.CreationFileTimeUtc) >= BigInt(parent.CreationFileTimeUtc)) ids.add(Number(row.ProcessId));
    }
  }
  return table.filter(row => ids.has(Number(row.ProcessId))).map(row => ({ ...row,
    isJvm: /^java(?:\.exe)?$/i.test(row.Name ?? ''),
    role: roleOf(row, arm) ?? (/^java(?:\.exe)?$/i.test(row.Name ?? '') ? 'other-jvm' : 'other-child') }));
}
export function memorySeries(csv, arm) {
  const [header, ...lines] = csv.trim().split(/\r?\n/), columns = header.split(',');
  const groups = new Map();
  for (const line of lines.filter(Boolean)) {
    const values = line.split(','), row = Object.fromEntries(columns.map((c, i) => [c, values[i]]));
    const timeMs = Date.parse(row.ts), commit = Number(row.privateBytes), working = Number(row.workingSetBytes);
    if (!Number.isFinite(timeMs) || !finite(commit) || !finite(working) || !row.creationFileTimeUtc) {
      throw new Error('Incomplete exact-byte scoped memory sample');
    }
    const group = groups.get(timeMs) ?? { timeMs, commit: 0, working: 0, roles: [], identities: new Set() };
    const key = `${row.pid}/${row.creationFileTimeUtc}`;
    if (group.identities.has(key)) throw new Error('Duplicate memory process identity');
    group.expected ??= row.expectedProcessCount ? Number(row.expectedProcessCount) : undefined;
    if (row.expectedProcessCount && Number(row.expectedProcessCount) !== group.expected) throw new Error("Scope changed within sample");
    group.identities.add(key); group.roles.push(row.role); group.commit += commit; group.working += working;
    groups.set(timeMs, group);
  }
  const samples = [...groups.values()].sort((a, b) => a.timeMs - b.timeMs);
  const required = arm === 'main' ? ['head', 'worker'] : ['engine'];
  const complete = samples.length > 1 && samples.every(s => required.every(role => s.roles.includes(role))
    && (s.expected === undefined || s.expected === s.identities.size));
  return { samples, complete, peakCommitBytes: complete ? Math.max(...samples.map(s => s.commit)) : undefined,
    peakWorkingSetBytes: complete ? Math.max(...samples.map(s => s.working)) : undefined,
    sampledMs: samples.length ? samples.at(-1).timeMs - samples[0].timeMs : 0 };
}
export function launchBudget(flags, role) {
  const get = key => Number(new RegExp(`\\b${key}\\s*:?=\\s*(\\d+)`).exec(flags)?.[1]);
  const maximumHeap = get('MaxHeapSize'), direct = get('MaxDirectMemorySize'), metaspace = get('MetaspaceSize');
  if (![maximumHeap, direct, metaspace].every(finite)) return undefined;
  return maximumHeap > 0 && maximumHeap <= 2 * 1024 ** 3 && direct === 256 * 1024 ** 2
    && metaspace === 128 * 1024 ** 2 && (!['engine', 'head', 'worker'].includes(role) || /UseG1GC\s*:?=\s*true/.test(flags));
}
export function crashObservation(before, after, timeline, arm) {
  const validTimes = finite(timeline.killMs) && finite(timeline.apiMs) && finite(timeline.indexMs)
    && timeline.apiMs >= timeline.killMs && timeline.indexMs >= timeline.killMs;
  const identityChanged = before?.pid !== after?.pid || before?.creationFileTimeUtc !== after?.creationFileTimeUtc;
  const resumed = before?.operation && after?.operation;
  const checkpoint = resumed ? before.operation.state === 'RUNNING'
    && before.operation.units_completed > 0 && Boolean(before.operation.checkpoint_cursor)
    && before.operation.id === after.operation.id && before.operation.operation_key === after.operation.operation_key
    && after.operation.units_completed >= before.operation.units_completed
    && after.operation.state === 'COMPLETE' && after.noDuplicateEffects === true : undefined;
  return { identityChanged, checkpointResume: arm === 'main' ? undefined : checkpoint,
    apiMs: validTimes ? timeline.apiMs - timeline.killMs : undefined,
    indexMs: validTimes ? timeline.indexMs - timeline.killMs : undefined };
}
export function hangVerdict(observation, kind, policy, warmStartBudgetMs) {
  const valid = observation?.injected === true && observation?.preHealthy === true
    && observation?.postUnresponsive === true && observation?.requestObserved === true;
  if (!valid) return undefined;
  if (kind === 'soft' && observation.forced === true) return false;
  if (kind === 'hard' && observation.graceful === true) return false;
  if (kind === 'soft' ? observation.graceful !== true : observation.forced !== true) return undefined;
  if (observation.injectionErrors?.length) return undefined;
  if (kind === 'soft' && finite(observation.deathAtMs)) {
    const earliestDeadline = (observation.requestLowerMs ?? observation.requestAtMs) + policy.gracefulStopDeadlineMs;
    const latestDeadline = observation.requestAtMs + policy.gracefulStopDeadlineMs;
    if (observation.lastAliveAtMs >= latestDeadline) return false;
    if (observation.deathAtMs > earliestDeadline) return undefined;
  }
  if (finite(policy.intervalMs) && finite(policy.missCount) && finite(policy.probeTimeoutMs)) {
    if (!finite(observation.injectionAtMs)) return undefined;
    const detectionMs = observation.requestAtMs - observation.injectionAtMs;
    if (detectionMs > policy.intervalMs * policy.missCount + policy.probeTimeoutMs + 1500) return false;
  }
  const recoveryMs = observation.restoredAtMs - (observation.requestLowerMs ?? observation.requestAtMs);
  if (!finite(recoveryMs) || !finite(policy.gracefulStopDeadlineMs) || !finite(warmStartBudgetMs)) return undefined;
  return recoveryMs <= policy.gracefulStopDeadlineMs + policy.cooldownIncrementMs + warmStartBudgetMs;
}
export function hangPolicy(values, worstPauseMs) {
  const { intervalMs, missCount } = values.hangParameters ?? {};
  if (!finite(worstPauseMs) || !Number.isSafeInteger(intervalMs) || intervalMs < 10000
    || !Number.isSafeInteger(missCount) || missCount < 1 || intervalMs * missCount < 3 * worstPauseMs) {
    throw new Error('Freeze E4-derived hangParameters.intervalMs/missCount before E6');
  }
  return { intervalMs, missCount, splitCompatible: intervalMs === 10000 && missCount === 3 };
}
