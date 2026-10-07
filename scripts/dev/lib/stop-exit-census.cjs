/* Identity-bound, read-only exit observer. It never terminates a target process.
 *
 * Spec B §5.1 / Spec F §6.1: the targets and the verdicts come from the exit ledger
 * (`<dataDir>/runtime/exit-ledger`). The OS observation (a handle held across the stop, read by
 * stop-exit-census.ps1) is compared with the ledger record; a code is never classified on its own.
 * The manifest `children` list is a fallback while the Engine does not write `children.v1.jsonl`
 * (Spec B steps 4/5), reported as gap `ledger-unavailable`. The Engine's stderr termination markers
 * stay as the fallback for an EXTRACTION child with no ledger record until the extraction sandbox
 * writes the ledger (Spec B steps 3 and 9); then they are deleted (Spec F §6.1). */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const identity = require('./process-identity.cjs');
const ledgerLib = require('./exit-ledger.cjs');
const TERMINATION_INTENT_PREFIX = 'JUSTSEARCH_MANAGED_CHILD_TERMINATION ';
/** Spec F §0: a console host may exit this long after its console parent and still be accounted. */
const CONHOST_EXIT_GRACE_MS = 2000;
const CONSOLE_HOST_RE = /(^|[\\/])conhost\.exe$/i;

function fileTimeFromInstant(instant) {
  const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(instant ?? '');
  if (!match || !Number.isFinite(Date.parse(instant))) return null;
  const fraction = (match[2] ?? '').padEnd(9, '0');
  if (fraction.slice(7) !== '00') return null; // Windows process starts have 100 ns precision.
  return (BigInt(Date.parse(instant)) * 10000n + 116444736000000000n + BigInt(fraction.slice(3, 7))).toString();
}
const isFileTime = (v) => typeof v === 'string' && /^\d+$/.test(v);
/** The shared birth-identity rule (exit-ledger.cjs `sameBirth`): equal at the coarser clock's precision. */
const { sameBirth, BIRTH_TICKS } = ledgerLib;
/**
 * The tick a target's recorded birth is compared at, by where the birth was read. A ledger birth is
 * GetProcessTimes (the Engine) or CIM (the dev runner), so the microsecond; a B11 startInstant, the
 * millisecond. stop-exit-census.ps1 compares the held handle's exact birth at this tick.
 */
const birthTickOf = (target) => (target.creationTimeSource === 'B11.startInstant'
  ? BIRTH_TICKS.MILLISECOND : BIRTH_TICKS.MICROSECOND);
const sameExe = (a, b) => Boolean(a && b) && path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase();
const birthOf = (p) => p?.creationFileTimeUtc ?? p?.CreationFileTimeUtc ?? null;
const pidOf = (p) => Number(p?.pid ?? p?.ProcessId);
const exeOf = (p) => p?.executable ?? p?.ExecutablePath ?? null;
/** The 2001 rule shared with the ledger (LedgerTimestamps): an exit time before 2001 is unknown. */
/** One exit code as a Windows DWORD: PowerShell reports it signed, libuv and Java unsigned. */
const sameCode = (a, b) => Number.isInteger(a) && Number.isInteger(b) && (a >>> 0) === (b >>> 0);
const knownExitMs = (ms) => (Number.isFinite(ms) && ms >= ledgerLib.EARLIEST_KNOWN_MS ? ms : null);

function engineIdentity(pid, dataDir, table = identity.readProcessTable()) {
  if (!table.ok || !dataDir) return null;
  const row = table.table.find(p => Number(p.ProcessId) === Number(pid));
  const command = row?.CommandLine ?? '';
  if (!row?.CreationFileTimeUtc || !command.includes('HeadlessApp')
    || !command.replaceAll('\\', '/').toLowerCase().includes(dataDir.replaceAll('\\', '/').toLowerCase())) return null;
  const executable = /^"([^"]+)"|^(\S+)/.exec(command)?.slice(1).find(Boolean);
  return executable ? { pid: Number(pid), creationFileTimeUtc: row.CreationFileTimeUtc, executable,
    cmdlineFingerprint: command, role: 'engine', creationTimeSource: 'CIM' } : null;
}
async function engineIdentityAsync(pid, dataDir) {
  return engineIdentity(pid, dataDir, await identity.readProcessTableAsync());
}

/**
 * Both ledger families of a data directory. `present` is false when the family has no file (its
 * writer has never written) or could not be read; an absent family is not evidence of no records.
 */
function readRunLedger(dataDir) {
  const dir = ledgerLib.ledgerDirectory(dataDir);
  const out = {};
  for (const family of ['engine', 'children']) {
    const present = fs.existsSync(ledgerLib.currentPath(dir, family)) || fs.existsSync(ledgerLib.rotatedPath(dir, family));
    try {
      out[family] = { present, records: ledgerLib.readLedger(dir, family).records };
    } catch (error) {
      out[family] = { present: false, records: [], error: error.message };
    }
  }
  return out;
}

const EXIT_TYPES = new Set(['engine-exit', 'engine-owner-died']);
const CHILD_EXIT_TYPES = new Set(['child-exit', 'owner-died']);
/** A ledger exit record as the verdict reads it. */
function exitOf(record) {
  if (!record) return null;
  const line = record.line;
  const ownerDied = record.type === 'engine-owner-died' || record.type === 'owner-died';
  return { type: record.type, exitClass: ownerDied ? (line.exitClass ?? 'OWNER_DIED') : line.exitClass ?? null,
    reason: line.reason ?? null, detail: line.detail ?? null, code: Number.isInteger(line.code) ? line.code : null,
    forced: line.forced === true, osCheck: line.osCheck ?? null, writer: record.writer?.role ?? null,
    exitedAtMs: ledgerLib.knownTimestamp(line.exitedAt),
    observedAtMs: ledgerLib.knownTimestamp(line.observedAt) ?? ledgerLib.knownTimestamp(line.at) };
}

/**
 * The latest moment a closed process can have been alive by its ledger record: its recorded exit
 * time, else (an unknown exit time, S15) the time its owner observed the exit, which bounds it.
 */
const recordedEndMs = (exit) => exit?.exitedAtMs ?? exit?.observedAtMs ?? null;

/**
 * The run's Engine incarnations (`engine-start` with `incarnation.session = runId`) and the children
 * whose `child-spawn` owner is one of them, each with its exit record (or null).
 */
function ledgerView(ledger, runId) {
  const engines = [];
  for (const r of ledger?.engine?.records ?? []) {
    const inc = r.line?.incarnation;
    if (r.type !== 'engine-start' || !runId || inc?.session !== runId || !Number.isInteger(inc?.n)) continue;
    const exit = (ledger.engine.records).filter(e => EXIT_TYPES.has(e.type)
      && e.line?.incarnation?.session === inc.session && e.line?.incarnation?.n === inc.n).pop();
    engines.push({ incarnation: { session: inc.session, n: inc.n }, pid: Number(r.line.pid),
      creationFileTimeUtc: r.line.creationFileTime ?? null, executable: r.line.executable || null, exit: exitOf(exit) });
  }
  const byId = new Map();
  for (const r of ledger?.children?.records ?? []) {
    const id = r.line?.childId;
    if (typeof id !== 'string') continue;
    const c = byId.get(id) ?? { childId: id };
    if (r.type === 'child-spawn') Object.assign(c, { kind: r.line.kind ?? null, owner: r.line.owner ?? null });
    else if (r.type === 'child-start') Object.assign(c, { pid: Number(r.line.pid), creationFileTimeUtc: r.line.creationFileTime ?? null,
      executable: r.line.executable ?? null, contained: r.line.contained ?? null });
    else if (CHILD_EXIT_TYPES.has(r.type)) {
      c.exit = exitOf(r);
      if (r.type === 'owner-died' && !c.owner) c.owner = r.line.previousOwner ?? null;
    }
    byId.set(id, c);
  }
  const ownedBy = (child, engine) => Number(child.owner?.pid) === engine.pid
    && sameBirth(String(child.owner?.creationFileTime ?? ''), engine.creationFileTimeUtc);
  const children = [...byId.values()].filter(c => engines.some(e => ownedBy(c, e)))
    .map(c => ({ ...c, exit: c.exit ?? null }));
  return { engines, children };
}

/**
 * The observed owned processes, one row per process (pid and birth), with the first and last time a
 * snapshot saw it. A scope file is one snapshot (`{atMs, processes}`) or a series (`{snapshots}`).
 */
function observedProcesses(scope) {
  const rows = Array.isArray(scope?.processes)
    ? scope.processes.map(p => withSeen(p, scope.atMs))
    : (scope?.snapshots ?? []).flatMap(s => (s?.processes ?? []).map(p => withSeen(p, s.atMs)));
  return uniqueProcesses(rows);
}
function withSeen(row, atMs) {
  if (!Number.isFinite(atMs)) return row;
  return { ...row, firstSeenAtMs: Math.min(row.firstSeenAtMs ?? atMs, atMs), lastSeenAtMs: Math.max(row.lastSeenAtMs ?? atMs, atMs) };
}
function uniqueProcesses(rows) {
  const byKey = new Map();
  for (const row of rows) {
    const key = `${pidOf(row)}/${birthOf(row)}`;
    const seen = byKey.get(key);
    if (!seen) { byKey.set(key, row); continue; }
    const first = [seen.firstSeenAtMs, row.firstSeenAtMs].filter(Number.isFinite);
    const last = [seen.lastSeenAtMs, row.lastSeenAtMs].filter(Number.isFinite);
    byKey.set(key, { ...seen, ...(first.length ? { firstSeenAtMs: Math.min(...first) } : {}),
      ...(last.length ? { lastSeenAtMs: Math.max(...last) } : {}) });
  }
  return [...byKey.values()];
}

/**
 * Spec B §2.9: the tier C helpers, which do not go through the API and are accounted by job
 * membership plus this executable allowlist (§5.1). DiskEncryptionProbe runs PowerShell.
 */
const TIER_C_EXECUTABLES = new Set(['tesseract.exe', 'nvidia-smi.exe', 'curl.exe', 'powershell.exe', 'taskkill.exe',
  'tesseract', 'nvidia-smi', 'curl']);
const imageNameOf = (row) => path.win32.basename(exeOf(row) ?? row?.name ?? row?.Name ?? '').toLowerCase();

/** A console host row, from the e456 collector (`role`, `name`) or a raw process-table row (`Name`). */
const isConsoleHost = (row) => row.role === 'console-host' || CONSOLE_HOST_RE.test(row.Name ?? row.name ?? '');
/** Spec B step 6: the activation self-test's candidate is a llama child of its own kind. */
const ROLE_OF_KIND = { LLAMA_SERVER: 'llama-server', LLAMA_SELF_TEST: 'llama-self-test', EXTRACTION: 'extraction-child' };
const roleOfKind = (kind) => ROLE_OF_KIND[kind] ?? 'child';

/**
 * The census targets, armed before the stop. Ledger first (Spec F §6.1): the run's open Engine
 * incarnations and their open children. Console hosts observed under a target join as targets of
 * their own (role `console-host`, CH9). Every other observed process (one row per pid and birth) is
 * accounted (`accountedProcesses`) or a gap: a console host of a closed ledger child seen within the
 * child's recorded lifetime, a tier C helper in the Engine's job and its console host (Spec B §5.1).
 */
function exitTargets(run, manifest, observedRows = [], ledger = null) {
  const targets = [], gaps = [], accountedProcesses = [];
  const observed = uniqueProcesses(observedRows);
  const view = ledger ? ledgerView(ledger, run?.runId) : { engines: [], children: [] };
  // Spec F §3.2: an Engine names its incarnation in the manifest; one naming another session was
  // started by another run (a manifest left behind), so nothing in it is this run's to observe.
  const manifestSession = manifest?.incarnation?.session;
  const foreignManifest = typeof manifestSession === 'string' && manifestSession !== run?.runId;
  const openEngines = view.engines.filter(e => !e.exit && e.pid > 0 && isFileTime(e.creationFileTimeUtc)
    && path.isAbsolute(e.executable ?? ''));
  if (view.engines.length) {
    // The run's Engine is in the ledger; an incarnation already closed there has nothing to observe.
    for (const e of view.engines.filter(x => !x.exit && !openEngines.includes(x))) {
      gaps.push({ role: 'engine', pid: e.pid, reason: 'engine-start lacks exact PID/birth/executable identity' });
    }
    for (const e of openEngines) targets.push({ pid: e.pid, creationFileTimeUtc: e.creationFileTimeUtc, executable: e.executable,
      role: 'engine', incarnation: e.incarnation, creationTimeSource: 'ledger', targetSource: 'ledger' });
  } else {
    const engine = run.exitCensusEngine;
    if (foreignManifest) {
      gaps.push({ role: 'engine', pid: run.pids?.backendRootPid, reason: 'manifest from another run', manifestSession });
    } else if (engine && engine.pid === Number(run.pids?.backendRootPid) && manifest?.pid === engine.pid) {
      targets.push({ ...engine, targetSource: 'spawn' });
      gaps.push({ role: 'engine', pid: engine.pid, reason: 'ledger-unavailable', family: 'engine' });
    } else gaps.push({ role: 'engine', pid: run.pids?.backendRootPid, reason: 'missing spawn-bound Engine identity or manifest PID mismatch' });
  }
  const engineTargets = targets.filter(t => t.role === 'engine');
  const knownChildIds = view.children.map(c => c.childId);
  if (ledger?.children?.present) {
    for (const c of view.children) {
      if (c.exit || !(c.pid > 0) || !isFileTime(c.creationFileTimeUtc) || !path.isAbsolute(c.executable ?? '')) continue;
      targets.push({ id: c.childId, childId: c.childId, kind: c.kind, pid: c.pid, creationFileTimeUtc: c.creationFileTimeUtc,
        executable: c.executable, role: roleOfKind(c.kind), creationTimeSource: 'ledger', targetSource: 'ledger' });
    }
  } else {
    // Spec F §6.1: the manifest list, while the Engine does not write children.v1.jsonl.
    gaps.push({ reason: 'ledger-unavailable', family: 'children',
      detail: ledger?.children?.error ?? 'no children.v1.jsonl: historical children cannot be enumerated' });
    if (foreignManifest) gaps.push({ reason: 'manifest from another run', family: 'children', manifestSession });
    else if (!Array.isArray(manifest?.children)) gaps.push({ reason: 'B11 current child registry unavailable' });
    for (const child of foreignManifest ? [] : manifest?.children ?? []) {
      const creationFileTimeUtc = fileTimeFromInstant(child.startedAt);
      if (!Number.isSafeInteger(child.pid) || !creationFileTimeUtc || !path.isAbsolute(child.executable ?? '')
        || !['LLAMA_SERVER', 'EXTRACTION'].includes(child.kind)) {
        gaps.push({ pid: child.pid, id: child.id, reason: 'B11 child lacks exact PID/start/executable identity' }); continue;
      }
      // Java startInstant can have millisecond precision. Bind to the collector's exact
      // owned CIM identity at that same represented millisecond; never add a tolerance.
      const owned = observed.filter(p => pidOf(p) === child.pid
        && sameBirth(birthOf(p), creationFileTimeUtc, BIRTH_TICKS.MILLISECOND) && sameExe(exeOf(p), child.executable));
      const births = [...new Set(owned.map(birthOf))];
      if (births.length > 1) { gaps.push({ pid: child.pid, reason: 'multiple owned births in B11 startInstant precision; cannot attribute child' }); continue; }
      targets.push({ ...child, creationFileTimeUtc: births[0] ?? creationFileTimeUtc,
        creationTimeSource: births.length ? 'CIM' : 'B11.startInstant', targetSource: 'manifest',
        role: child.kind === 'LLAMA_SERVER' ? 'llama-server' : 'extraction-child' });
    }
  }
  // CH9: a console host belongs to its console parent (a target, born no later than the host at the
  // shared birth precision: an exact parent birth and the host's CIM birth can share a microsecond).
  const tick = BIRTH_TICKS.MICROSECOND;
  for (const row of observed) {
    if (!isConsoleHost(row)) continue;
    const birth = birthOf(row);
    const parent = targets.find(t => t.pid === Number(row.ParentProcessId ?? row.parentPid)
      && isFileTime(t.creationFileTimeUtc) && isFileTime(birth) && BigInt(t.creationFileTimeUtc) / tick <= BigInt(birth) / tick);
    if (!parent) continue;
    // A console host's command line starts `\??\C:\...`; the held handle reports the System32 path.
    targets.push({ pid: pidOf(row), creationFileTimeUtc: birth,
      executable: path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'conhost.exe'),
      role: 'console-host', consoleParent: `${parent.pid}/${parent.creationFileTimeUtc}`, creationTimeSource: 'CIM' });
  }
  const unique = [...new Map(targets.map(t => [`${t.pid}/${t.creationFileTimeUtc}`,
    { ...t, birthTick: String(birthTickOf(t)) }])).values()];
  // Processes whose exit is already in the ledger (closed before the stop): a closed child of a run
  // Engine, or a closed incarnation of it. Their console hosts and helpers are judged against the
  // lifetime the ledger records for them.
  const closed = [
    ...view.engines.filter(e => e.exit).map(e => ({ pid: e.pid, creationFileTimeUtc: e.creationFileTimeUtc, exit: e.exit,
      // The manifest read at arming is the open incarnation's; a closed one's containment is unknown.
      role: 'engine', contained: false })),
    ...view.children.filter(c => c.exit).map(c => ({ pid: c.pid, creationFileTimeUtc: c.creationFileTimeUtc, exit: c.exit,
      role: roleOfKind(c.kind), contained: c.contained === true })),
  ];
  const closedOf = (pid, birth) => closed.find(c => c.pid === pid && sameBirth(c.creationFileTimeUtc, birth));
  const isTarget = (pid, birth) => unique.some(t => t.pid === pid && sameBirth(t.creationFileTimeUtc, birth, BigInt(t.birthTick)));
  // A parent the row can belong to: same pid, born no later than the row at the shared birth
  // precision, and (when its exit is recorded) the row last seen within its recorded lifetime.
  const parents = [
    ...unique.filter(t => t.role !== 'console-host').map(t => ({ ...t, open: true,
      contained: t.role === 'engine' ? containedEngine(manifest, run, t.pid)
        : view.children.find(c => c.childId === t.childId)?.contained === true })),
    ...closed.map(c => ({ ...c, open: false })),
  ];
  const parentOf = (row) => {
    const birth = birthOf(row), ppid = Number(row.ParentProcessId ?? row.parentPid);
    if (!isFileTime(birth)) return { parent: null, why: 'no birth' };
    const tick = BIRTH_TICKS.MICROSECOND;
    const candidates = parents.filter(t => t.pid === ppid && isFileTime(t.creationFileTimeUtc)
      && BigInt(t.creationFileTimeUtc) / tick <= BigInt(birth) / tick);
    if (!candidates.length) return { parent: null, why: 'parent is not a run Engine or one of its ledger children' };
    // A reused pid: the latest parent born no later than the row.
    const parent = candidates.sort((a, b) => (BigInt(a.creationFileTimeUtc) < BigInt(b.creationFileTimeUtc) ? -1 : 1)).at(-1);
    if (parent.open) return { parent };
    const endMs = recordedEndMs(parent.exit);
    if (endMs === null) return { parent: null, why: `parent ${parent.pid} exit time unknown in the ledger` };
    if (!Number.isFinite(row.lastSeenAtMs)) return { parent: null, why: 'observed time unknown' };
    if (row.lastSeenAtMs > endMs + CONHOST_EXIT_GRACE_MS) {
      return { parent: null, why: `seen ${row.lastSeenAtMs - endMs} ms after its parent ${parent.pid}'s recorded exit` };
    }
    return { parent };
  };
  const accountedKey = new Set();
  const account = (row, role, parent, basis) => {
    accountedKey.add(`${pidOf(row)}/${birthOf(row)}`);
    accountedProcesses.push({ pid: pidOf(row), creationFileTimeUtc: birthOf(row), role, parent: `${parent.pid}/${parent.creationFileTimeUtc}`,
      basis, ...(Number.isFinite(row.lastSeenAtMs) ? { lastSeenAtMs: row.lastSeenAtMs } : {}) });
  };
  const pending = [];
  for (const p of observed) {
    const pid = pidOf(p), birth = birthOf(p);
    if (isTarget(pid, birth)) continue;
    const ended = closedOf(pid, birth);
    if (!ended) { pending.push(p); continue; }
    // Exited before the stop; its exit is in the ledger, unless it was seen alive after it.
    const endMs = recordedEndMs(ended.exit);
    if (endMs !== null && Number.isFinite(p.lastSeenAtMs) && p.lastSeenAtMs > endMs + CONHOST_EXIT_GRACE_MS) {
      gaps.push({ pid, creationFileTimeUtc: birth, role: ended.role, reason: 'unaccounted_process',
        detail: `seen ${p.lastSeenAtMs - endMs} ms after its recorded exit` });
    }
  }
  // Spec B §5.1: a tier C helper in the Engine's job is accounted as a contained helper.
  const gapOf = new Map();
  for (const p of pending.filter(row => !isConsoleHost(row))) {
    const { parent, why } = parentOf(p);
    const helper = TIER_C_EXECUTABLES.has(imageNameOf(p));
    if (helper && parent?.contained) { account(p, 'tier-c-helper', parent, 'contained helper (Engine job, tier C allowlist)'); continue; }
    gapOf.set(p, { pid: pidOf(p), creationFileTimeUtc: birthOf(p), role: p.role, reason: 'unaccounted_process',
      detail: !helper ? 'observed owned process with no ledger start, absent from the current B11 registry and not a tier C helper'
        : !parent ? `tier C executable, but ${why}` : `tier C executable outside the Engine job (containment ${containmentOf(manifest)})` });
  }
  // CH9: a console host is accounted with its client: a target, a closed ledger process within its
  // recorded lifetime, or an accounted helper. Otherwise it is an orphan host, never "absent from
  // registry".
  parents.push(...accountedProcesses.map(a => ({ pid: a.pid, creationFileTimeUtc: a.creationFileTimeUtc, open: true })));
  for (const p of pending.filter(isConsoleHost)) {
    const { parent, why } = parentOf(p);
    if (parent && !parent.open) { account(p, 'console-host', parent, 'console host of a closed ledger process'); continue; }
    if (parent && accountedKey.has(`${parent.pid}/${parent.creationFileTimeUtc}`)) {
      account(p, 'console-host', parent, 'console host of an accounted helper'); continue;
    }
    gapOf.set(p, { pid: pidOf(p), creationFileTimeUtc: birthOf(p), role: 'console-host', reason: 'orphan_console_host',
      detail: parent ? 'console parent is not accounted' : `console parent is not accounted: ${why}` });
  }
  for (const p of pending) if (gapOf.has(p)) gaps.push(gapOf.get(p)); // one row per process, in observed order
  return { targets: unique, gaps, accountedProcesses, knownChildIds, runId: run?.runId ?? null };
}

/** The manifest's Engine containment status, when the manifest is this run's. */
function containmentOf(manifest) {
  return typeof manifest?.containment?.status === 'string' ? manifest.containment.status : 'unknown';
}
/** Spec F §1.3: CONTAINED means every process the Engine starts is in its kill-on-close job. */
const containedEngine = (manifest, run, pid) => containmentOf(manifest) === 'CONTAINED' && Number(manifest?.pid) === pid
  && (manifest?.incarnation?.session === undefined || manifest.incarnation.session === run?.runId);

function readEngineTerminationIntents(file, offset = 0) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const length = fs.fstatSync(fd).size - offset;
    if (length <= 0 || length > 16 * 1024 * 1024) return []; // Missing/oversize evidence grants no exemption.
    const bytes = Buffer.alloc(length);
    const size = fs.readSync(fd, bytes, 0, length, offset);
    return bytes.subarray(0, size).toString('utf8').split(/\r?\n/).flatMap((line, index, lines) => {
      if (index === lines.length - 1 || !line.startsWith(TERMINATION_INTENT_PREFIX)) return [];
      try { return [{ ...JSON.parse(line.slice(TERMINATION_INTENT_PREFIX.length)), source: file }]; }
      catch { return []; }
    });
  } catch { return []; }
  finally { if (fd !== undefined) fs.closeSync(fd); }
}
function matchingEngineTerminationIntent(input, target, exit, intents = []) {
  const engine = input.targets.find(t => t.role === 'engine');
  if (!engine || target.kind !== 'EXTRACTION' || !Array.isArray(intents) || !Number.isFinite(exit.atMs)) return null;
  return intents.find(intent => {
    if (!intent || typeof intent !== 'object') return false;
    const child = intent.child, ownerBirth = fileTimeFromInstant(intent.engineStartedAt);
    return intent.reason === 'graceful-close-fallback' && intent.enginePid === engine.pid
      && sameBirth(ownerBirth, engine.creationFileTimeUtc, BIRTH_TICKS.MILLISECOND)
      && child && typeof child.id === 'string' && child.id.trim() && child.id === target.id && child.kind === 'EXTRACTION' && child.pid === target.pid
      && fileTimeFromInstant(child.startedAt) && fileTimeFromInstant(child.startedAt) === fileTimeFromInstant(target.startedAt)
      && path.isAbsolute(child.executable ?? '')
      && path.resolve(child.executable).toLowerCase() === path.resolve(target.executable).toLowerCase()
      && Number.isSafeInteger(intent.requestedAtMs)
      && intent.requestedAtMs >= Math.max(Date.parse(child.startedAt), Date.parse(intent.engineStartedAt), input.observedAfterMs ?? 0)
      && intent.requestedAtMs <= exit.atMs;
  }) ?? null;
}

/** The ledger exit record for one target, read at finish (so an exit recorded during the stop counts). */
function ledgerExitFor(target, view) {
  if (target.role === 'engine') {
    const e = view.engines.find(x => (target.incarnation && x.incarnation.n === target.incarnation.n)
      || (x.pid === target.pid && sameBirth(x.creationFileTimeUtc, target.creationFileTimeUtc)));
    return e?.exit ?? null;
  }
  const c = view.children.find(x => (target.childId && x.childId === target.childId)
    || (x.pid === target.pid && sameBirth(x.creationFileTimeUtc, target.creationFileTimeUtc)));
  return c?.exit ?? null;
}

/**
 * The verdict per target (Spec B §5.1): the ledger class compared with the OS observation.
 * `ledger` is re-read at finish; without it every exit is unrecorded.
 */
function exitResult(input, output, source, ledger = null) {
  const gaps = [...input.gaps, ...(output.gaps ?? [])], processExits = [];
  const view = ledger ? ledgerView(ledger, input.runId) : { engines: [], children: [] };
  // The observer echoes the target's birth as the key and reports the held handle's own birth
  // (`osCreationFileTimeUtc`), which must be the target's under the shared rule.
  const osExitOf = (target) => output.processExits?.find(e => e.pid === target.pid && e.creationFileTimeUtc === target.creationFileTimeUtc
    && (e.osCreationFileTimeUtc == null
      || sameBirth(String(e.osCreationFileTimeUtc), target.creationFileTimeUtc, BigInt(target.birthTick ?? birthTickOf(target))))
    && sameExe(e.executable ?? '', target.executable));
  const accounted = new Map();
  for (const target of input.targets.filter(t => t.role !== 'console-host')) {
    const exit = osExitOf(target);
    if (!exit || !Number.isInteger(exit.exitCode)) {
      gaps.push({ pid: target.pid, role: target.role, reason: 'identity-bound OS exit code/time unavailable through teardown' }); continue;
    }
    const atMs = knownExitMs(exit.atMs);
    const base = { ...exit, atMs, exitTimeSource: atMs === null ? 'unknown' : (exit.exitTimeSource ?? 'os'), role: target.role, source };
    const record = ledgerExitFor(target, view);
    const jsCode = target.role === 'engine' ? ledgerLib.JS_ENGINE_STOP : ledgerLib.JS_CHILD_STOP;
    if (record) {
      const entry = { ...base, ledgerClass: record.exitClass, ledgerReason: record.reason, ledgerDetail: record.detail,
        ledgerCode: record.code, ledgerWriter: record.writer };
      if (record.exitClass === 'OWNER_DIED') {
        processExits.push({ ...entry, expectedTermination: false });
        accounted.set(target, entry);
      } else if (record.code === null || !sameCode(record.code, exit.exitCode)) {
        processExits.push({ ...entry, expectedTermination: false });
        gaps.push({ pid: target.pid, role: target.role, reason: 'ledger_os_disagree', ledgerCode: record.code, osCode: exit.exitCode,
          ledgerClass: record.exitClass });
      } else if (record.exitClass === 'REQUESTED' && record.forced
        && ![ledgerLib.JS_CHILD_STOP, ledgerLib.JS_ENGINE_STOP, 1].some(code => sameCode(code, exit.exitCode))) {
        // Spec B §5.1: a forced stop is expected only with the stop code (or 1 for a dev Engine).
        processExits.push({ ...entry, expectedTermination: false });
        gaps.push({ pid: target.pid, role: target.role, reason: 'ledger_os_disagree', ledgerClass: record.exitClass,
          osCode: exit.exitCode, detail: 'forced REQUESTED exit without a stop code' });
      } else if (record.exitClass === 'REQUESTED' || record.exitClass === 'UNEXPECTED') {
        processExits.push({ ...entry, expectedTermination: record.exitClass === 'REQUESTED' });
        accounted.set(target, entry);
      } else {
        processExits.push({ ...entry, expectedTermination: false });
        gaps.push({ pid: target.pid, role: target.role, reason: 'ledger_class_unknown', ledgerClass: record.exitClass });
      }
      continue;
    }
    // No ledger record. Until the extraction sandbox writes the ledger, its stderr marker is the
    // fallback for an EXTRACTION child (Spec B step 3 dual-read); nothing else is read from a code.
    const intent = matchingEngineTerminationIntent(input, target, base, output.engineTerminationIntents);
    if (intent && exit.exitCode === 1) {
      const entry = { ...base, ledgerClass: null, terminationIntent: intent, classifiedBy: 'stderr-marker', expectedTermination: true };
      processExits.push(entry);
      accounted.set(target, entry);
      continue;
    }
    processExits.push({ ...base, ledgerClass: null, expectedTermination: false });
    gaps.push({ pid: target.pid, role: target.role, osCode: exit.exitCode,
      reason: sameCode(exit.exitCode, jsCode) ? 'ledger_record_missing' : 'unrecorded_exit' });
  }
  // CH9: a console host is accounted iff its console parent is, and it exited no later than the
  // parent's exit + CONHOST_EXIT_GRACE_MS.
  for (const host of input.targets.filter(t => t.role === 'console-host')) {
    const exit = osExitOf(host);
    const parent = [...accounted.entries()].find(([t]) => `${t.pid}/${t.creationFileTimeUtc}` === host.consoleParent)?.[1];
    const atMs = knownExitMs(exit?.atMs);
    const late = exit && parent && atMs !== null && parent.atMs !== null && atMs > parent.atMs + CONHOST_EXIT_GRACE_MS;
    if (!exit || !parent || late) {
      gaps.push({ pid: host.pid, role: 'console-host', consoleParent: host.consoleParent, reason: 'orphan_console_host',
        detail: !exit ? 'alive or exit unobserved at terminal stop' : !parent ? 'console parent not accounted'
          : `exited ${atMs - parent.atMs} ms after its console parent` });
      if (exit) processExits.push({ ...exit, atMs, role: 'console-host', consoleParent: host.consoleParent, source, accounted: false });
      continue;
    }
    processExits.push({ ...exit, atMs, exitTimeSource: atMs === null ? 'unknown' : 'os', role: 'console-host',
      consoleParent: host.consoleParent, source, accounted: true, expectedTermination: parent.expectedTermination });
  }
  // Children the ledger names that started after the census was armed: no OS handle exists for
  // them; their code is the ledger's. Not a gap when REQUESTED (Spec F §6.1, Spec B C11).
  const known = new Set(input.knownChildIds ?? []);
  const lateTargets = view.children.filter(c => !known.has(c.childId)
    && !input.targets.some(t => t.childId === c.childId)).map(c => ({ childId: c.childId, pid: c.pid,
    creationFileTimeUtc: c.creationFileTimeUtc, role: roleOfKind(c.kind), ledgerClass: c.exit?.exitClass ?? null,
    code: c.exit?.code ?? null }));
  for (const late of lateTargets) {
    if (late.ledgerClass !== 'REQUESTED') gaps.push({ pid: late.pid, role: late.role, reason: 'late_target_not_requested', ledgerClass: late.ledgerClass });
  }
  return { processExits, ...(lateTargets.length ? { lateTargets } : {}),
    ...(input.accountedProcesses?.length ? { accountedProcesses: input.accountedProcesses } : {}),
    exitAccountingComplete: input.targets.length > 0 && !gaps.length,
    exitAccountingGaps: gaps, exitCensusFile: source, ledgerSummary: ledgerSummaryOf(view, gaps),
    exitAccountingScope: 'exit ledger (run Engine incarnations and their children) compared with identity-bound OS exits; manifest children only as the ledger-unavailable fallback' };
}

/** Spec F §6.3: the run's ledger exits by class, plus the census gaps. */
function ledgerSummaryOf(view, gaps) {
  const summary = { requested: 0, unexpected: 0, ownerDied: 0, unknown: 0, gaps: gaps.length };
  for (const item of [...view.engines, ...view.children]) {
    const cls = item.exit?.exitClass;
    if (!cls) continue;
    if (cls === 'REQUESTED') summary.requested += 1;
    else if (cls === 'UNEXPECTED') summary.unexpected += 1;
    else if (cls === 'OWNER_DIED') summary.ownerDied += 1;
    else summary.unknown += 1;
  }
  return summary;
}

async function startStopExitCensus(run, dataDir, runDir, scopeFile, runtime = {}) {
  const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  const readLedger = runtime.readLedger ?? readRunLedger;
  let manifest, observed = [], scopeError;
  try { manifest = read(path.join(dataDir, 'runtime/manifest.json')); } catch { /* named below */ }
  if (scopeFile) {
    try {
      observed = observedProcesses(read(scopeFile));
    } catch (error) { scopeError = { reason: `owned-process census unavailable: ${error.message}` }; }
  }
  const input = exitTargets(run, manifest, observed, dataDir ? readLedger(dataDir) : null);
  input.observedAfterMs = (runtime.now ?? Date.now)();
  const terminationLog = path.join(runDir, 'logs', 'backend.stderr.log');
  let terminationLogOffset = 0;
  try { terminationLogOffset = fs.statSync(terminationLog).size; } catch { /* no prior log */ }
  if (scopeError) input.gaps.push(scopeError);
  if (!scopeFile) input.gaps.push({ reason: 'no historical owned-process census supplied; B11 is a current registry, not an exit history' });
  const outputFile = path.join(runDir, 'process-exits.json');
  // The verdict reads the ledger last: `beforeVerdict(osExits)` gets the identity-bound OS exits and
  // writes the stop's own records from them (the dev-cli Engine exit), so the report reads them too.
  const verdict = async (output, beforeVerdict) => {
    if (beforeVerdict) await beforeVerdict(exitResult(input, output, outputFile, null).processExits);
    return exitResult(input, output, outputFile, dataDir ? readLedger(dataDir) : null);
  };
  if (process.platform !== 'win32' && !runtime.launch) return { finish: async (killed = [], { beforeVerdict } = {}) => verdict(
    { gaps: [{ reason: 'OS process-handle exit census requires Windows' }] }, beforeVerdict) };
  const inputFile = path.join(runDir, 'exit-targets.json'), readyFile = path.join(runDir, 'exit-census-ready.json');
  const finishFile = path.join(runDir, 'exit-census-finish.json');
  for (const file of [readyFile, finishFile, outputFile]) fs.rmSync(file, { force: true });
  fs.writeFileSync(inputFile, JSON.stringify(input));
  const launch = runtime.launch ?? ((args) => spawn('powershell.exe', args, { windowsHide: true, stdio: 'ignore' }));
  const proc = launch(['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
    path.join(__dirname, 'stop-exit-census.ps1'), '-InputFile', inputFile, '-OutputFile', outputFile,
    '-ReadyFile', readyFile, '-FinishFile', finishFile]);
  let launchError;
  proc.once('error', error => { launchError = error.message; });
  const now = runtime.now ?? Date.now, pause = runtime.pause ?? (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const by = now() + 15000;
  while (!fs.existsSync(readyFile) && !launchError && proc.exitCode == null && now() < by) await pause(50);
  if (!fs.existsSync(readyFile)) {
    proc.kill(); input.gaps.push({ reason: `exit observer failed before shutdown: ${launchError ?? 'ready deadline/early exit'}` });
  }
  // `killed` (the stop's own kills) is informational: a dev kill of the Engine writes its own
  // engine-intent and engine-exit (Spec B §5.1: forcedPids no longer classify anything).
  return { finish: async (killed = [], { beforeVerdict } = {}) => {
    fs.writeFileSync(finishFile, JSON.stringify({ killedPids: killed }));
    const end = now() + 5000;
    while (!fs.existsSync(outputFile) && !launchError && proc.exitCode == null && now() < end) await pause(50);
    let output;
    try { output = read(outputFile); } catch { output = { gaps: [{ reason: 'exit observer did not finish through teardown' }] }; proc.kill(); }
    return verdict({ ...output, engineTerminationIntents: readEngineTerminationIntents(terminationLog, terminationLogOffset) },
      beforeVerdict);
  } };
}
module.exports = { TERMINATION_INTENT_PREFIX, CONHOST_EXIT_GRACE_MS, readEngineTerminationIntents, fileTimeFromInstant,
  engineIdentity, engineIdentityAsync, readRunLedger, ledgerView, observedProcesses, exitTargets, exitResult, startStopExitCensus };
