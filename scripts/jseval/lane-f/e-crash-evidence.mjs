/** Scoped crash receipts retained after teardown; projection never queries a live process. */
import fs from 'node:fs';
import path from 'node:path';
const files = dir => fs.existsSync(dir) ? fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory()
  ? files(path.join(dir, e.name)) : [path.join(dir, e.name)]) : [];
export function crashTiming(atMs, window) {
  if (Number.isFinite(atMs) && Number.isFinite(window?.teardownStartMs) && atMs >= window.teardownStartMs) return 'teardown';
  return !Number.isFinite(atMs) || !Number.isFinite(window?.endMs) ? 'unknown'
    : atMs > window.endMs ? 'teardown' : atMs >= window.startMs ? 'inside-window' : 'before-window';
}
export function crashEvidence(dataDirs, window, stop = {}, exits = [], census) {
  const events = [], sources = [];
  for (const dir of dataDirs) for (const file of files(dir).filter(f => /[\\/]crashes[\\/]|hs_err_pid\d+.*\.log$/.test(f))) {
    const stat = fs.statSync(file);
    let body; try { body = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { body = {}; }
    const pid = body.pid ?? Number(path.basename(file).match(/pid(\d+)/)?.[1]);
    const atMs = Number.isFinite(Date.parse(body.timestamp)) ? Date.parse(body.timestamp) : stat.mtimeMs;
    const prior = events.find(e => e.pid === pid && Math.abs(e.atMs - atMs) < 2000);
    if (prior) { prior.sources.push(file); if (path.basename(file).startsWith('hs_err')) prior.type = 'native-jvm-crash'; }
    else events.push({ pid, role: body.process ?? 'unknown-owned-process', atMs,
      timestampSource: body.timestamp ? 'crash-report.timestamp' : 'file.mtime',
      timing: crashTiming(atMs, window), sources: [file], type: path.basename(file).startsWith('hs_err') ? 'native-jvm-crash' : 'crash-report' });
    sources.push(file);
  }
  for (const exit of exits.filter(e => Number.isFinite(e.exitCode) && e.exitCode !== 0 && e.expectedTermination !== true)) events.push({
    ...exit, timing: crashTiming(exit.atMs, window), type: 'abnormal-owned-exit', sources: [exit.source] });
  const seen = new Map();
  for (const sample of census?.snapshots ?? []) {
    for (const role of ['engine', 'head', 'worker']) {
      const process = sample.processes?.find(p => p.role === role), previous = seen.get(role);
      if (previous && (!process || process.pid !== previous.process.pid
        || process.creationFileTimeUtc !== previous.process.creationFileTimeUtc)) {
        const prior = events.find(e => e.pid === previous.process.pid);
        if (prior) { if (!prior.sources.includes(census.source)) prior.sources.push(census.source); }
        else events.push({ pid: previous.process.pid, role, atMs: sample.atMs,
          observedBetweenMs: [previous.atMs, sample.atMs], timestampSource: 'census observation interval',
          timing: crashTiming(sample.atMs, window), type: process ? 'replacement-observed' : 'disappearance-observed',
          sources: [census.source] });
      }
      if (process) seen.set(role, { process, atMs: sample.atMs });
      else seen.delete(role);
    }
  }
  const identities = new Map((census?.snapshots ?? []).flatMap(s => s.processes ?? [])
    .map(p => [`${p.pid}/${p.creationFileTimeUtc}`, p]));
  const exitCoverage = identities.size > 0 && [...identities.values()].every(p => exits.some(e =>
    e.pid === p.pid && e.creationFileTimeUtc === p.creationFileTimeUtc && Number.isInteger(e.exitCode) && e.source));
  return { events, sources, window, exitCoverage, exitAccountingGaps: stop.exitAccountingGaps ?? [], throughTeardown: stop.portsClosed === true,
    // Exit receipts cover native children too; a stopped port alone says nothing about their exit outcome.
    complete: stop.portsClosed === true && stop.exitAccountingComplete === true && exitCoverage };
}
export function projectCrashes(record) {
  const evidence = record.metrics.crashEvidence;
  record.clauses['zero-crashes'] = evidence?.events?.length || record.clauses['zero-crashes'] === false ? false
    : evidence?.complete === true && record.clauses['zero-crashes'] === true ? true : undefined;
  record.gaps['zero-crashes'] = evidence?.events?.length
    ? JSON.stringify(evidence.events) : record.clauses['zero-crashes'] === false ? 'Crash observed in retained live census; exact exit/timing receipt unavailable' : `No crash observed; complete exit accounting through teardown for every JVM/native child is required; ${JSON.stringify(evidence?.exitAccountingGaps ?? [])}`;
}
