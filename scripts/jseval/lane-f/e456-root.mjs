/** Receipt-bound E4-E6 process identity. Pure helpers; no process or stack actions. */
import path from 'node:path';

const normalized = value => path.resolve(value).replaceAll('\\', '/').toLowerCase();
const birth = row => typeof row?.CreationFileTimeUtc === 'string' && /^\d+$/.test(row.CreationFileTimeUtc)
  ? BigInt(row.CreationFileTimeUtc) : null;
const epoch = 116444736000000000n;
const java = row => /^java(?:\.exe)?$/i.test(row?.Name ?? '')
  && /^java(?:\.exe)?$/i.test(path.basename(row?.ExecutablePath ?? ''));
const headless = row => java(row) && /\bHeadlessApp\b/.test(row.CommandLine ?? '');
function unavailable(reason) {
  const error = new Error(`Arm root identity unavailable: ${reason}`);
  error.name = 'ArmRootIdentityError'; return error;
}
export function identifyArmRoot({ arm, dataDir, receipt, issuedAt }, table, manifest) {
  const issuedMs = Date.parse(issuedAt), backendPid = Number(receipt?.pids?.backendRootPid);
  if (!Number.isFinite(issuedMs) || !Number.isSafeInteger(backendPid) || backendPid <= 0)
    throw unavailable(`${arm}: start command time/backendRootPid missing`);
  if (!receipt?.dataDir || normalized(receipt.dataDir) !== normalized(dataDir))
    throw unavailable(`${arm}: start receipt data directory mismatch`);
  const afterIssue = row => birth(row) != null && birth(row) >= BigInt(issuedMs) * 10000n + epoch;
  let root;
  if (arm === 'branch') {
    // Owned manifest follows supervised replacements; the initial receipt PID may have exited.
    if (!Number.isSafeInteger(manifest?.pid) || !manifest?.instanceId)
      throw unavailable('branch: owned root manifest PID/instance missing');
    root = table.find(row => Number(row.ProcessId) === manifest.pid);
  } else if (arm === 'main') {
    const launcher = table.find(row => Number(row.ProcessId) === backendPid);
    if (!launcher || !afterIssue(launcher)) throw unavailable(`main: receipt process ${backendPid} absent or predates start command`);
    const descendants = new Set([backendPid]);
    for (let size = -1; size !== descendants.size;) {
      size = descendants.size;
      for (const row of table) if (descendants.has(Number(row.ParentProcessId))) {
        const parent = table.find(p => Number(p.ProcessId) === Number(row.ParentProcessId));
        if (birth(row) != null && birth(parent) != null && birth(row) >= birth(parent)) descendants.add(Number(row.ProcessId));
      }
    }
    const candidates = table.filter(row => descendants.has(Number(row.ProcessId)) && headless(row));
    if (candidates.length !== 1) throw unavailable(`main: receipt tree ${backendPid} has ${candidates.length} HeadlessApp JVMs; require exactly one`);
    root = candidates[0];
  } else throw unavailable(`unknown arm ${arm}`);
  if (!headless(root) || !afterIssue(root)) throw unavailable(`${arm}: root must be a live Java HeadlessApp created after the start command`);
  const match = /(?:^|\s)(?:"-Djustsearch\.data\.dir=([^"]+)"|-Djustsearch\.data\.dir=(?:"([^"]+)"|(\S+)))/.exec(root.CommandLine);
  if (match && normalized(match.slice(1).find(Boolean)) !== normalized(dataDir))
    throw unavailable(`${arm}: JVM data directory disagrees with owned receipt`);
  return root;
}
export function requireCollectorSample(collector, label, role) {
  if (!collector?.root || !Array.isArray(collector.current) || !collector.current.length
    || !Array.isArray(collector.snapshots) || !collector.snapshots.at(-1)?.processes?.length)
    throw new Error(`Collector sample unavailable (${label}): verified root and process snapshot required`);
  if (collector.arm === 'branch' && (collector.manifest?.pid !== collector.root.pid || !collector.manifest?.instanceId))
    throw new Error(`Collector manifest unavailable (${label}): verified branch PID/instance required`);
  if (role) {
    const target = collector.current.find(p => p.role === role);
    if (!target) throw new Error(`Collector target unavailable (${label}): ${role} required`);
    return target;
  }
  return collector.current;
}
