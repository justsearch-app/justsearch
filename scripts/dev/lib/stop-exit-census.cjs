/* Identity-bound, read-only exit observer. It never terminates a target process.
 *
 * Spec B §5.1 / Spec F §6.1: the targets and the verdicts come from the exit ledger
 * (`<dataDir>/runtime/exit-ledger`). The OS observation (a handle held across the stop, read by
 * stop-exit-census.ps1) is compared with the ledger record; a code is never classified on its own.
 * The manifest `children` list is a fallback while the Engine does not write `children.v1.jsonl`
 * (Spec B steps 4/5), reported as gap `ledger-unavailable`. Nothing else stands in for a ledger
 * record (Spec B step 9): no stderr marker, no list of the stop's own kills, no rule that reads a
 * code alone. An exit without its record is a gap. */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const identity = require('./process-identity.cjs');
const ledgerLib = require('./exit-ledger.cjs');
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
const WITNESS_INTENT_BASES = new Set(['owner exit record', 'owner intent', 'owner window', 'console client']);
const isFileTime = (v) => typeof v === 'string' && /^[1-9][0-9]{0,19}$/.test(v);
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
const crashCode = code => Number.isInteger(code) && (code >>> 30) === 3;
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
    const present = ledgerLib.ledgerFiles(dir, family).length > 0;
    try {
      out[family] = { present, records: ledgerLib.readLedger(dir, family).records,
        sourceFiles: ledgerLib.ledgerFiles(dir, family) };
    } catch (error) {
      out[family] = { present: false, records: [], error: error.message };
    }
  }
  // A retained instance manifest reports the Engine job decided at boot. Bind it through a
  // ledger owner reference (PID/birth/instanceId), never through a historical PID alone.
  out.engineContainment = [];
  const refs = [...(out.engine.records ?? []).filter(r => r.type === 'engine-self-intent').map(r => r.line.engine),
    ...(out.children.records ?? []).filter(r => r.type === 'child-spawn').map(r => r.line.owner)];
  for (const ref of refs) {
    if (!ref || !isFileTime(ref.creationFileTime) || !/^[\w-]+$/.test(ref.instanceId ?? '')) continue;
    try {
      const manifest = JSON.parse(fs.readFileSync(path.join(dataDir, 'runtime/instances', ref.instanceId, 'manifest.json'), 'utf8'));
      if (manifest.instanceId !== ref.instanceId || Number(manifest.pid) !== Number(ref.pid)) continue;
      out.engineContainment.push({ ...ref, incarnation: manifest.incarnation, status: containmentOf(manifest) });
    } catch { /* Absent/unreadable history is unknown containment, never evidence of membership. */ }
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
  return { type: record.type, exitClass: crashCode(line.code) ? 'UNEXPECTED' : ownerDied ? (line.exitClass ?? 'OWNER_DIED') : line.exitClass ?? null,
    reason: line.reason ?? null, detail: line.detail ?? null, code: Number.isInteger(line.code) ? line.code : null,
    forced: line.forced === true, osCheck: line.osCheck ?? null, writer: record.writer?.role ?? null,
    terminationMechanism: line.terminationMechanism ?? null, observedBy: line.observedBy ?? null,
    runnerJobClose: line.runnerJobClose ?? null,
    terminateCalledAtMs: ledgerLib.knownTimestamp(line.terminateCalledAt),
    exitedAtMs: ledgerLib.knownTimestamp(line.exitedAt), exitedFileTime: fileTimeFromInstant(line.exitedAt),
    observedAtMs: ledgerLib.knownTimestamp(line.observedAt) ?? ledgerLib.knownTimestamp(line.at) };
}

/**
 * A process's exit time is known only from its exit receipt's actual exit time. A later read,
 * write or reconciliation observation cannot place that exit within (or outside) a job lifetime.
 */
const recordedEndMs = (exit) => exit?.exitedAtMs ?? null;
/** A confirmed death observation can reject later live sightings; it cannot date that death. */
const closedByMs = (exit) => recordedEndMs(exit) ?? exit?.observedAtMs ?? null;
/**
 * Negative containment evidence: once the Engine is observed dead, its kill-on-close job has
 * closed by that observation. This upper bound can reject a later live sighting, but is never
 * the Engine's or a helper's exit time. A helper receipt's observation supplies no job bound.
 */
const jobClosedByMs = (engine) => closedByMs(engine?.exit);

// The creator was identified from the held helper while it was alive. Engine ancestry is
// insufficient: concurrent parsers in the same job must never borrow one another's intent.
const createdBy = (w, owner) => Number(owner?.pid) === w.parentPid
  && isFileTime(owner?.creationFileTime) && isFileTime(w.parentCreationFileTime)
  && sameBirth(owner.creationFileTime, w.parentCreationFileTime, BIRTH_TICKS.EXACT)
  && BigInt(owner.creationFileTime) <= BigInt(w.creationFileTimeUtc);
const sameOwner = (a, b) => a?.pid === b?.pid && a?.instanceId === b?.instanceId
  && sameBirth(a?.creationFileTime, b?.creationFileTime, BIRTH_TICKS.EXACT);
const receiptCreator = (w, c) => createdBy(w, c.owner) || (c.helperId
  && c.creatorPid === c.owner?.pid && isFileTime(c.creatorCreationFileTime)
  && sameBirth(c.creatorCreationFileTime, c.owner.creationFileTime, BIRTH_TICKS.EXACT)
  && BigInt(c.creatorCreationFileTime) <= BigInt(w.creationFileTimeUtc));

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
      creationFileTimeUtc: r.line.creationFileTime ?? null, executable: r.line.executable || null, exit: exitOf(exit),
      contained: (ledger.engineContainment ?? []).some(c => Number(c.pid) === Number(r.line.pid)
        && sameBirth(c.creationFileTime, r.line.creationFileTime)
        && c.incarnation?.session === inc.session && c.incarnation?.n === inc.n && c.status === 'CONTAINED') });
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
  const witnesses = new Map();
  for (const r of ledger?.children?.records ?? []) {
    const line = r.line, inc = line?.incarnation, owner = line?.owner;
    const engine = engines.find(e => e.incarnation.session === inc?.session && e.incarnation.n === inc?.n
      && e.pid === Number(owner?.pid) && sameBirth(e.creationFileTimeUtc, owner?.creationFileTime));
    if (!engine || r.writer?.role !== 'engine' || Number(r.writer.pid) !== engine.pid
      || !/^[\w-]+$/.test(owner?.instanceId ?? '') || !/^[\w-]+$/.test(line?.witnessId ?? '')) continue;
    const writerBirthMs = Number.isFinite(r.writer.startedAt) ? r.writer.startedAt : ledgerLib.knownTimestamp(r.writer.startedAt);
    const engineBirthMs = Number((BigInt(engine.creationFileTimeUtc) - 116444736000000000n) / 10000n);
    if (writerBirthMs != null && writerBirthMs !== engineBirthMs) continue;
    const key = `${inc.session}/${inc.n}/${line.witnessId}`;
    const engineConsole = line.consoleClient === true && CONSOLE_HOST_RE.test(line.executable ?? '')
      && Number(line.parentPid) === engine.pid
      && sameBirth(line.parentCreationFileTime, owner?.creationFileTime, BIRTH_TICKS.EXACT);
    if (r.type === 'job-process-start' && (line.contained === true || engineConsole) && Number.isSafeInteger(line.pid)
      && line.pid > 0 && isFileTime(line.creationFileTime) && BigInt(line.creationFileTime) > 0n
      && BigInt(line.creationFileTime) >= BigInt(engine.creationFileTimeUtc)) {
      if (witnesses.has(key)) { witnesses.get(key).invalid = true; continue; }
      witnesses.set(key, { witnessId: line.witnessId, incarnation: inc, owner, pid: line.pid,
        creationFileTimeUtc: line.creationFileTime, executable: line.executable, contained: line.contained === true,
        awaited: line.awaited === true, expectedCode: line.expectedCode, reason: line.reason,
        engine, consoleClient: engineConsole, parentPid: line.parentPid,
        parentCreationFileTime: line.parentCreationFileTime, exit: null });
    } else if (r.type === 'job-process-exit' && witnesses.has(key)) {
      const w = witnesses.get(key);
      if (w.exit || w.pid !== line.pid || w.owner.instanceId !== owner.instanceId
        || !sameBirth(w.creationFileTimeUtc, line.creationFileTime, BIRTH_TICKS.EXACT)) { w.invalid = true; continue; }
      const exit = exitOf(r);
      if (line.forced !== false || (!Number.isInteger(exit.code) && exit.exitClass !== 'UNKNOWN')
        || !['REQUESTED', 'UNEXPECTED', 'UNKNOWN'].includes(exit.exitClass)
        // A witness line may carry the class of the owner intent it read (intentBasis); the
        // census still derives the class from the owner's own records below, never from it.
        || (exit.exitClass === 'REQUESTED' && !WITNESS_INTENT_BASES.has(line.intentBasis)
          && (!w.awaited || w.expectedCode !== 0 || exit.code !== 0
          || w.reason !== exit.reason || exit.reason !== 'HELPER_COMPLETION'))) { w.invalid = true; continue; }
      w.exit = exit.exitClass === 'UNKNOWN' ? { ...exit, reason: exit.reason ?? 'native exit identity/code unavailable' } : exit;
    }
  }
  const receipts = new Map(), windows = new Map();
  for (const r of ledger?.children?.records ?? []) {
    const line = r.line, inc = line?.incarnation, owner = line?.owner;
    const direct = engines.find(e => e.incarnation.session === inc?.session && e.incarnation.n === inc?.n
      && e.pid === Number(owner?.pid) && sameBirth(e.creationFileTimeUtc, owner?.creationFileTime));
    const parser = children.find(c => c.pid === Number(owner?.pid)
      && sameBirth(c.creationFileTimeUtc, owner?.creationFileTime));
    const engine = direct ?? (parser && engines.find(e => ownedBy(parser, e)
      && e.incarnation.session === inc?.session && e.incarnation.n === inc?.n));
    if (!engine || r.writer?.role !== 'engine' || Number(r.writer.pid) !== Number(owner.pid)) continue;
    const writerBirth = Number.isFinite(r.writer.startedAt) ? r.writer.startedAt : ledgerLib.knownTimestamp(r.writer.startedAt);
    const ownerBirth = isFileTime(owner.creationFileTime)
      ? Number((BigInt(owner.creationFileTime) - 116444736000000000n) / 10000n) : null;
    if (writerBirth != null && writerBirth !== ownerBirth) continue;
    if (r.type === 'helper-start' && isFileTime(line.creationFileTime))
      receipts.set(line.helperId, { ...line, engine, parentPid: line.parentPid, parentCreationFileTime: line.parentCreationFileTime, exit: null });
    if (r.type === 'helper-exit') {
      // A failed start write does not invalidate a self-contained owner exit receipt. Its
      // creator identity is still checked against the live-captured native witness below.
      if (!receipts.has(line.helperId) && /^[\w-]+$/.test(line.helperId ?? '')
        && Number.isSafeInteger(line.pid) && isFileTime(line.creationFileTime))
        receipts.set(line.helperId, { ...line, engine, exit: null });
      const receipt = receipts.get(line.helperId);
      if (receipt && sameOwner(receipt.owner, owner) && receipt.pid === line.pid
        && sameBirth(receipt.creationFileTime, line.creationFileTime, BIRTH_TICKS.EXACT))
        receipt.exit = exitOf(r);
    }
    if (r.type === 'helper-window-start') windows.set(line.intentId, { ...line, engine });
    if (r.type === 'helper-window-end') {
      const intent = windows.get(line.intentId);
      if (intent && sameOwner(intent.owner, owner) && intent.engine === engine
        && intent.image === line.image && intent.reason === line.reason)
        intent.closedAt = line.closedAt;
    }
  }
  // A window's end: its recorded end; when that is absent (an open window, or an unforced end a
  // host crash lost), its owner's recorded exit or crash/owner-died record, or its Engine's exit;
  // with none of those the owner is alive and the window runs to its deadline (task b6d536 b101).
  const msToFileTime = ms => (Number.isFinite(ms) ? (BigInt(Math.trunc(ms)) * 10000n + 116444736000000000n).toString() : null);
  const windowEnd = (i, deadline) => {
    const recorded = fileTimeFromInstant(i.closedAt);
    if (recorded) return recorded;
    const owner = sameBirth(i.engine.creationFileTimeUtc, i.owner?.creationFileTime) && i.engine.pid === Number(i.owner?.pid)
      ? i.engine : children.find(c => c.pid === Number(i.owner?.pid) && sameBirth(c.creationFileTimeUtc, i.owner?.creationFileTime));
    const ends = [owner?.exit, i.engine.exit].filter(Boolean).map(e => msToFileTime(closedByMs(e))).filter(Boolean);
    return ends.length ? ends.reduce((a, b) => (BigInt(a) < BigInt(b) ? a : b)) : deadline;
  };
  const valid = [...witnesses.values()].filter(w => !w.invalid);
  for (const r of ledger?.children?.records ?? []) {
    const line = r.line;
    if (r.type !== 'job-process-exit' || line?.reconciled !== true
      || !['dev-runner', 'dev-cli', 'engine'].includes(r.writer?.role)) continue;
    const w = valid.find(w => w.witnessId === line.witnessId && w.pid === line.pid
      && sameBirth(w.creationFileTimeUtc, line.creationFileTime, BIRTH_TICKS.EXACT)
      && sameOwner(w.owner, line.owner) && w.incarnation.session === line.incarnation?.session
      && w.incarnation.n === line.incarnation?.n);
    const crashReceipt = w?.engine.exit?.exitClass === 'UNEXPECTED' && line.cause === 'ENGINE_CRASH';
    const intendedConsole = w?.consoleClient && w.engine.exit?.exitClass === 'REQUESTED' && line.cause === 'ENGINE_EXIT';
    if (!w || w.exit || (!crashReceipt && !intendedConsole)) continue;
    if (r.writer.role === 'engine' && Number(r.writer.pid) === w.engine.pid) continue;
    if ((line.osCheck === 'GONE' && line.exitClass === (intendedConsole ? 'REQUESTED' : 'OWNER_DIED'))
      || (line.osCheck === 'IDENTITY_UNKNOWN' && line.exitClass === 'UNKNOWN')) w.exit = exitOf(r);
  }
  const ownedHelpers = new Set();
  for (const w of valid) {
    if (!w.exit) continue;
    if (w.exit.code === null && (['OWNER_DIED', 'UNKNOWN'].includes(w.exit.exitClass)
      || (w.consoleClient && w.exit.exitClass === 'REQUESTED'))) continue;
    const ownerExit = [...children, ...receipts.values()].find(c =>
      (c.engine === w.engine || ownedBy(c, w.engine)) && receiptCreator(w, c) && c.pid === w.pid
      && sameBirth(c.creationFileTimeUtc ?? c.creationFileTime, w.creationFileTimeUtc) && c.exit)?.exit;
    if (ownerExit) {
      // An owner record wins only when the independent code receipt confirms it.
      w.exit = sameCode(ownerExit.code, w.exit.code) ? { ...w.exit, ...ownerExit }
        : { ...w.exit, exitClass: 'UNKNOWN', reason: 'owner_witness_disagree' };
      if ([...receipts.values()].some(c => c.engine === w.engine && receiptCreator(w, c)
        && c.pid === w.pid && sameBirth(c.creationFileTime, w.creationFileTimeUtc)
        && c.exit === ownerExit)) ownedHelpers.add(w);
    } else {
      const born = BigInt(w.creationFileTimeUtc);
      const intent = [...windows.values()].find(i => {
        const start = fileTimeFromInstant(i.requestedAt), deadline = fileTimeFromInstant(i.deadlineAt);
        const close = windowEnd(i, deadline);
        return i.engine === w.engine && createdBy(w, i.owner) && typeof i.reason === 'string' && i.reason.length > 0
          && path.win32.basename(w.executable ?? '').toLowerCase() === i.image?.toLowerCase()
          && start && deadline && close && BigInt(close) >= BigInt(start)
          && born >= BigInt(start) && born <= BigInt(deadline) && born <= BigInt(close)
          && w.exit.exitedFileTime != null && BigInt(w.exit.exitedFileTime) >= BigInt(start)
          && BigInt(w.exit.exitedFileTime) <= BigInt(deadline) && BigInt(w.exit.exitedFileTime) <= BigInt(close);
      });
      // An image alone, including a successful code, is never intended.
      w.exit = { ...w.exit, exitClass: intent && !crashCode(w.exit.code) ? 'REQUESTED' : 'UNEXPECTED',
        reason: intent && !crashCode(w.exit.code) ? intent.reason : 'no owner intent' };
      if (intent) ownedHelpers.add(w);
    }
    if (crashCode(w.exit.code)) w.exit = { ...w.exit, exitClass: 'UNEXPECTED', reason: 'helper crash' };
  }
  // A parser's own kill-on-close boundary can end an active helper before that parser
  // writes its exit receipt. Require its explicit boundary declaration, exact parent birth,
  // an open owner window/receipt and native exit timing/code; Engine membership alone is not enough.
  for (const w of valid) {
    if (!w.exit || w.exit.exitClass !== 'UNEXPECTED' || w.exit.code !== 0) continue;
    const parser = children.find(c => c.kind === 'EXTRACTION' && ownedBy(c, w.engine)
      && createdBy(w, { pid: c.pid, creationFileTime: c.creationFileTimeUtc }) && c.exit);
    if (!parser || !['REQUESTED', 'UNEXPECTED'].includes(parser.exit.exitClass)) continue;
    if (parser.exit.exitClass === 'REQUESTED' && (!parser.exit.forced || !parser.exit.reason)) continue;
    const end = recordedEndMs(parser.exit), helperEnd = recordedEndMs(w.exit);
    if (end == null || helperEnd == null || helperEnd < end || helperEnd > end + CONHOST_EXIT_GRACE_MS) continue;
    const declares = [...receipts.values()].some(c => c.engine === w.engine
      && c.ownerContainment === 'KILL_ON_CLOSE' && createdBy(w, c.owner)
      && c.pid === w.pid && sameBirth(c.creationFileTime, w.creationFileTimeUtc, BIRTH_TICKS.EXACT))
      || [...windows.values()].some(i => i.engine === w.engine && i.ownerContainment === 'KILL_ON_CLOSE'
        && createdBy(w, i.owner) && !i.closedAt
        && path.win32.basename(w.executable ?? '').toLowerCase() === i.image?.toLowerCase()
        && fileTimeFromInstant(i.requestedAt) != null && fileTimeFromInstant(i.deadlineAt) != null
        && BigInt(w.creationFileTimeUtc) >= BigInt(fileTimeFromInstant(i.requestedAt))
        && BigInt(w.creationFileTimeUtc) <= BigInt(fileTimeFromInstant(i.deadlineAt)));
    if (!declares) continue;
    w.exit = { ...w.exit, exitClass: parser.exit.exitClass === 'REQUESTED' ? 'REQUESTED' : 'OWNER_DIED',
      reason: parser.exit.exitClass === 'REQUESTED' ? parser.exit.reason : 'parser owner died' };
    ownedHelpers.add(w);
  }
  // A launcher is an owned helper, not another declaring process. Credit only its direct
  // child with a parent PID/birth captured natively while that child was alive. Repeated
  // passes make receipt order irrelevant and support nested wrappers without ancestry credit.
  let extended;
  do {
    extended = false;
    for (const w of valid) {
      if (ownedHelpers.has(w) || CONSOLE_HOST_RE.test(w.executable ?? '')
        || !w.exit || w.exit.exitClass !== 'UNEXPECTED' || crashCode(w.exit.code)) continue;
      const parent = [...ownedHelpers].find(p => p.engine === w.engine
        && createdBy(w, { pid: p.pid, creationFileTime: p.creationFileTimeUtc })
        && p.exit?.exitClass === 'REQUESTED' && p.exit.reason
        && (p.exit.exitedFileTime == null || BigInt(w.creationFileTimeUtc) <= BigInt(p.exit.exitedFileTime)));
      if (!parent) continue;
      w.exit = { ...w.exit, exitClass: 'REQUESTED', reason: parent.exit.reason };
      ownedHelpers.add(w);
      extended = true;
    }
  } while (extended);
  for (const w of valid.filter(w => path.win32.basename(w.executable ?? '').toLowerCase() === 'conhost.exe')) {
    const parent = [...engines, ...children, ...valid].find(p => p.pid === w.parentPid
      && sameBirth(p.creationFileTimeUtc, w.parentCreationFileTime));
    const end = recordedEndMs(parent?.exit), hostEnd = recordedEndMs(w.exit);
    const born = Number((BigInt(w.creationFileTimeUtc) - 116444736000000000n) / 10000n);
    // Unknown times cannot establish a late exit. Preserve the identity-bound client's class
    // unless known native times contradict it; an unknown identity/code receipt stays unknown.
    if (parent?.exit && w.exit && w.exit.exitClass !== 'UNKNOWN' && Number.isInteger(w.exit.code)
        && (end == null || born <= end) && (end == null || hostEnd == null || hostEnd <= end + CONHOST_EXIT_GRACE_MS)
        && !crashCode(w.exit.code)) {
      w.exit = { ...w.exit, exitClass: parent.exit.exitClass, reason: parent.exit.reason, forced: false };
      w.consoleClass = parent.exit.exitClass;
    }
  }
  const reportIdentities = (ledger?.children?.records ?? []).filter(r => r.type === 'job-process-start'
    && r.line.incarnation?.session === runId).map(r => ({ pid: r.line.pid,
    creationFileTimeUtc: r.line.creationFileTime, role: witnessRole(r.line.pid, r.line.creationFileTime, r.line.executable, children) }));
  return { engines, children, witnesses: valid, reportIdentities };

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
 * membership plus this executable allowlist (§5.1). An image and an ancestor's job status
 * do not prove membership of an individual process. DiskEncryptionProbe runs PowerShell.
 */
const TIER_C_EXECUTABLES = new Set(['tesseract.exe', 'nvidia-smi.exe', 'curl.exe', 'powershell.exe', 'taskkill.exe',
  'tesseract', 'nvidia-smi', 'curl']);
const imageNameOf = (row) => path.win32.basename(exeOf(row) ?? row?.name ?? row?.Name ?? '').toLowerCase();

/** A console host row, from the e456 collector (`role`, `name`) or a raw process-table row (`Name`). */
const isConsoleHost = (row) => row.role === 'console-host' || CONSOLE_HOST_RE.test(row.Name ?? row.name ?? '');
/** Spec B step 6: the activation self-test's candidate is a llama child of its own kind. */
const ROLE_OF_KIND = { LLAMA_SERVER: 'llama-server', LLAMA_SELF_TEST: 'llama-self-test', EXTRACTION: 'extraction-child' };
const roleOfKind = (kind) => ROLE_OF_KIND[kind] ?? 'child';
/** A witnessed identity that is a ledger child keeps its own role (llama-server, extraction child). */
function witnessRole(pid, birth, executable, children) {
  if (CONSOLE_HOST_RE.test(executable ?? '')) return 'console-host';
  const child = (children ?? []).find(c => c.pid === Number(pid) && sameBirth(c.creationFileTimeUtc, birth));
  return child ? roleOfKind(child.kind) : 'job-helper';
}

/**
 * The census targets, armed before the stop. Ledger first (Spec F §6.1): the run's open Engine
 * incarnations and their open children. Console hosts observed under a target join as targets of
 * their own (role `console-host`, CH9). Every other observed process (one row per pid and birth) is
 * accounted (`accountedProcesses`) or a gap. Console hosts of closed ledger processes require
 * their parent's owner record and lifetime bound; unledgered helpers require direct membership
 * evidence, which the current scope input does not record (Spec B §5.1).
 */
function exitTargets(run, manifest, observedRows = [], ledger = null) {
  const targets = [], gaps = [], accountedProcesses = [];
  const observed = uniqueProcesses(observedRows);
  const view = ledger ? ledgerView(ledger, run?.runId) : { engines: [], children: [] };
  // Only a child's own membership record can bind it to its owner's job. The owner's exit
  // bounds that job even when the child's exit record is missing or claims a later exit.
  const jobEndOf = (child) => child.contained === true ? jobClosedByMs(view.engines.find(e =>
    e.pid === Number(child.owner?.pid) && sameBirth(e.creationFileTimeUtc, child.owner?.creationFileTime))) : null;
  const jobEnds = new Map(view.children.map(c => [`${c.pid}/${c.creationFileTimeUtc}`, jobEndOf(c)]));
  for (const w of view.witnesses ?? []) jobEnds.set(`${w.pid}/${w.creationFileTimeUtc}`, jobClosedByMs(w.engine));
  const contradictsJob = (row, pid, birth) => {
    const end = jobEnds.get(`${pid}/${birth}`);
    return end != null && Number.isFinite(row.lastSeenAtMs) && row.lastSeenAtMs > end + CONHOST_EXIT_GRACE_MS;
  };
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
  // A known later holder supersedes an earlier parent identity at the child's birth, even if
  // that holder is outside this run. Observation is negative evidence, not an ownership grant.
  const superseded = (parent, birth) => observed.some(p => pidOf(p) === parent.pid && isFileTime(birthOf(p))
    && BigInt(birthOf(p)) / tick > BigInt(parent.creationFileTimeUtc) / tick
    && BigInt(birthOf(p)) / tick <= BigInt(birth) / tick);
  for (const row of observed) {
    if (!isConsoleHost(row)) continue;
    const birth = birthOf(row);
    const parent = targets.find(t => t.pid === Number(row.ParentProcessId ?? row.parentPid)
      && isFileTime(t.creationFileTimeUtc) && isFileTime(birth) && BigInt(t.creationFileTimeUtc) / tick <= BigInt(birth) / tick
      && !superseded(t, birth) && !contradictsJob(row, t.pid, t.creationFileTimeUtc));
    if (!parent) continue;
    // A console host's command line starts `\??\C:\...`; the held handle reports the System32 path.
    targets.push({ pid: pidOf(row), creationFileTimeUtc: birth,
      executable: path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'conhost.exe'),
      role: 'console-host', consoleParent: `${parent.pid}/${parent.creationFileTimeUtc}`, creationTimeSource: 'CIM' });
  }
  const unique = [...new Map(targets.map(t => [`${t.pid}/${t.creationFileTimeUtc}`,
    { ...t, birthTick: String(birthTickOf(t)) }])).values()];
  // The Engine's own console predates its job and may never reach the sampler. Its native
  // client witness is sufficient to arm a read handle; it grants no right to terminate it.
  for (const w of view.witnesses ?? []) {
    if (!w.consoleClient || w.exit || w.engine.exit) continue;
    const parent = engineTargets.find(t => t.pid === w.engine.pid
      && sameBirth(t.creationFileTimeUtc, w.engine.creationFileTimeUtc));
    if (!parent) continue;
    const target = { pid: w.pid, creationFileTimeUtc: w.creationFileTimeUtc, executable: w.executable,
      role: 'console-host', consoleClient: true, witnessId: w.witnessId, incarnation: w.incarnation,
      consoleParent: `${parent.pid}/${parent.creationFileTimeUtc}`, creationTimeSource: 'ledger',
      birthTick: String(BIRTH_TICKS.EXACT) };
    const existing = unique.findIndex(t => t.pid === w.pid && sameBirth(t.creationFileTimeUtc, w.creationFileTimeUtc));
    if (existing < 0) unique.push(target);
    else unique[existing] = target;
  }
  // Processes whose exit is already in the ledger (closed before the stop): a closed child of a run
  // Engine, or a closed incarnation of it. Their console hosts and helpers are judged against the
  // lifetime the ledger records for them.
  const closed = [
    ...view.engines.filter(e => e.exit).map(e => ({ pid: e.pid, creationFileTimeUtc: e.creationFileTimeUtc, exit: e.exit,
      role: 'engine', contained: e.contained })),
    ...view.children.filter(c => c.exit).map(c => ({ pid: c.pid, creationFileTimeUtc: c.creationFileTimeUtc, exit: c.exit,
      role: roleOfKind(c.kind), contained: c.contained === true })),
  ];
  // An exact job witness can account any image, including nested helpers and their hosts.
  // Never use ancestry as a substitute for this identity-bound record.
  const witnessed = [];
  const rejectedWitnesses = new Set();
  for (const p of observed) {
    const w = (view.witnesses ?? []).find(w => w.pid === pidOf(p)
      && sameBirth(w.creationFileTimeUtc, birthOf(p)));
    if (!w || isTargetAlready(p)) continue;
    if (contradictsJob(p, w.pid, w.creationFileTimeUtc)) { rejectedWitnesses.add(p); continue; }
    const jobEnd = jobClosedByMs(w.engine);
    const bornMs = Number((BigInt(w.creationFileTimeUtc) - 116444736000000000n) / 10000n);
    const witnessedEnd = recordedEndMs(w.exit);
    if (jobEnd != null && (bornMs > jobEnd
      || (witnessedEnd != null && witnessedEnd > jobEnd + CONHOST_EXIT_GRACE_MS))) {
      rejectedWitnesses.add(p); continue;
    }
    const role = isConsoleHost(p) ? 'console-host' : witnessRole(w.pid, w.creationFileTimeUtc, w.executable, view.children);
    if (w.exit) {
      const end = recordedEndMs(w.exit);
      if (end != null && Number.isFinite(p.lastSeenAtMs) && p.lastSeenAtMs > end + CONHOST_EXIT_GRACE_MS) {
        rejectedWitnesses.add(p); continue;
      }
      closed.push({ ...w, role });
      witnessed.push({ row: p, witness: w, role });
    } else if (!w.engine.exit) {
      // Live witnessed processes need their OS exit and owner exit compared at finish.
      unique.push({ pid: w.pid, creationFileTimeUtc: w.creationFileTimeUtc, executable: w.executable,
        role, witnessConsole: role === 'console-host', witnessId: w.witnessId, incarnation: w.incarnation,
        creationTimeSource: 'ledger', birthTick: String(BIRTH_TICKS.MICROSECOND) });
    } else rejectedWitnesses.add(p);
  }
  function isTargetAlready(p) {
    return unique.some(t => t.pid === pidOf(p) && sameBirth(t.creationFileTimeUtc, birthOf(p)));
  }
  const closedOf = (pid, birth) => closed.find(c => c.pid === pid && sameBirth(c.creationFileTimeUtc, birth));
  const isTarget = (pid, birth) => unique.some(t => t.pid === pid && sameBirth(t.creationFileTimeUtc, birth, BigInt(t.birthTick)));
  // A parent the row can belong to: same pid, born no later than the row at the shared birth
  // precision, and (when its exit is recorded) the row last seen within its recorded lifetime.
  const parents = [
    ...unique.filter(t => t.role !== 'console-host').map(t => ({ ...t, open: true })),
    ...closed.map(c => ({ ...c, open: false })),
  ];
  for (const parent of parents) {
    // A witnessed host may be the Engine's own console host. Its witness binds the job's
    // closure even when the Engine's exit time is unknown; ancestry alone supplies no bound.
    const ownerWitness = witnessed.find(x => x.witness.engine.pid === parent.pid
      && sameBirth(x.witness.engine.creationFileTimeUtc, parent.creationFileTimeUtc));
    const jobEndMs = jobEnds.get(`${parent.pid}/${parent.creationFileTimeUtc}`)
      ?? (ownerWitness ? jobClosedByMs(ownerWitness.witness.engine) : null);
    if (jobEndMs != null) {
      const end = recordedEndMs(parent.exit);
      parent.closedByMs = end == null ? jobEndMs : Math.min(end, jobEndMs);
      parent.open = false;
    }
  }
  const parentOf = (row) => {
    const birth = birthOf(row), ppid = Number(row.ParentProcessId ?? row.parentPid);
    if (!isFileTime(birth)) return { parent: null, why: 'no birth' };
    const tick = BIRTH_TICKS.MICROSECOND;
    const candidates = parents.filter(t => t.pid === ppid && isFileTime(t.creationFileTimeUtc)
      && BigInt(t.creationFileTimeUtc) / tick <= BigInt(birth) / tick);
    if (!candidates.length) return { parent: null, why: 'parent is not a run Engine or one of its ledger children' };
    // A reused pid: the latest parent born no later than the row.
    const parent = candidates.sort((a, b) => (BigInt(a.creationFileTimeUtc) < BigInt(b.creationFileTimeUtc) ? -1 : 1)).at(-1);
    if (superseded(parent, birth)) return { parent: null, why: `parent ${ppid} PID held by a newer observed identity at child birth` };
    if (parent.open) return { parent };
    // CH9's legacy console-parent attribution uses the confirmed-dead upper bound to reject
    // later sightings. It never compares a helper receipt's observation with the job's end,
    // nor reports that upper bound as the parent's or console host's exit time.
    const endMs = parent.closedByMs ?? closedByMs(parent.exit);
    if (endMs === null) return { parent: null, why: `parent ${parent.pid} exit time unknown in the ledger` };
    if (!Number.isFinite(row.lastSeenAtMs)) return { parent: null, why: 'observed time unknown' };
    const bornMs = Number((BigInt(birth) - 116444736000000000n) / 10000n);
    if (bornMs > endMs) return { parent: null, why: `born after its parent ${parent.pid}'s recorded exit` };
    if (row.lastSeenAtMs > endMs + CONHOST_EXIT_GRACE_MS) {
      return { parent: null, why: `seen ${row.lastSeenAtMs - endMs} ms after its parent ${parent.pid}'s recorded exit` };
    }
    return { parent };
  };
  const account = (row, role, parent, basis) => {
    accountedProcesses.push({ pid: pidOf(row), creationFileTimeUtc: birthOf(row), role, parent: `${parent.pid}/${parent.creationFileTimeUtc}`,
      basis, ledgerClass: parent.exit?.exitClass, ledgerReason: parent.exit?.reason,
      ...(Number.isFinite(row.lastSeenAtMs) ? { lastSeenAtMs: row.lastSeenAtMs } : {}) });
  };
  for (const host of unique.filter(t => t.witnessConsole)) {
    const row = observed.find(p => pidOf(p) === host.pid && sameBirth(birthOf(p), host.creationFileTimeUtc));
    const { parent } = parentOf(row);
    if (parent) host.consoleParent = `${parent.pid}/${parent.creationFileTimeUtc}`;
  }
  const allLosses = [...(ledger?.children?.records ?? []), ...(ledger?.engine?.records ?? [])]
    .filter(r => r.type === 'ledger-gap' && r.line?.reason === 'EVIDENCE_LOST').map(r => r.line);
  const relevantLoss = loss => {
    if (loss.incarnation?.session) return loss.incarnation.session === run?.runId;
    if (loss.scopeComplete === true) return loss.affectedSessions?.includes(run?.runId) === true;
    if (observed.some(p => pidOf(p) === loss.pid && sameBirth(birthOf(p), loss.creationFileTime))) return true;
    // Evicted evidence was written before its eviction, so a row's `at` (the eviction time for
    // summaries and compacted rows, the evidence time for identity rows) bounds a range that is
    // unknown, including rows written before scope fields existed.
    const start = ledgerLib.knownTimestamp(run?.startedAt);
    const through = loss.evidenceRangeComplete === true ? ledgerLib.knownTimestamp(loss.evidenceThrough) : null;
    const bound = through ?? ledgerLib.knownTimestamp(loss.at);
    return !(start != null && bound != null && bound < start);
  };
  const losses = allLosses.filter(relevantLoss);
  const historicalEvidenceLoss = allLosses.filter(loss => !relevantLoss(loss));
  const evidenceLost = [];
  const pending = [];
  for (const p of observed) {
    const pid = pidOf(p), birth = birthOf(p);
    const child = view.children.find(c => c.pid === pid && sameBirth(c.creationFileTimeUtc, birth));
    if (child && contradictsJob(p, child.pid, child.creationFileTimeUtc)) {
      gaps.push({ pid, creationFileTimeUtc: birth, role: roleOfKind(child.kind), reason: 'unaccounted_process',
        detail: 'observed alive after its owner job closed plus grace; contradicts recorded containment' });
      continue;
    }
    if (isTarget(pid, birth)) continue;
    const witness = witnessed.find(x => x.row === p);
    if (witness) {
      // Hosts must also stay within the client's recorded lifetime; membership alone cannot
      // excuse a late host. Missing/reused clients remain unproven.
      if (isConsoleHost(p)) {
        const { parent } = parentOf(p);
        const hostEnd = recordedEndMs(witness.witness.exit), parentEnd = recordedEndMs(parent?.exit);
        if (!parent || parent.open || (hostEnd != null && parentEnd != null
          && hostEnd > parentEnd + CONHOST_EXIT_GRACE_MS)) {
          gaps.push({ pid, creationFileTimeUtc: birth, role: 'console-host', reason: 'orphan_console_host',
            detail: 'witnessed console host exceeds or cannot prove its client lifetime' });
          continue;
        }
      }
      const w = witness.witness;
      const clientReceipt = witness.role === 'console-host' && !crashCode(w.exit.code)
        && !(w.consoleClient && ['OWNER_DIED', 'UNKNOWN'].includes(w.exit.exitClass)) ? parentOf(p).parent?.exit : null;
      accountedProcesses.push({ pid, creationFileTimeUtc: birth, role: witness.role,
        witnessId: w.witnessId, basis: 'identity-bound Engine job witness',
        ledgerClass: clientReceipt?.exitClass ?? w.exit.exitClass,
        ledgerReason: clientReceipt?.reason ?? w.exit.reason, ledgerCode: w.exit.code,
        atMs: w.exit.exitedAtMs, exitTimeSource: w.exit.exitedAtMs == null ? 'unknown' : 'owner-process-handle' });
      continue;
    }
    const ended = closedOf(pid, birth);
    if (!ended) {
      const loss = losses.find(l => (l.pid === pid && sameBirth(l.creationFileTime, birth))
        || (isFileTime(birth) && isFileTime(l.birthFrom) && isFileTime(l.birthThrough)
          && BigInt(birth) >= BigInt(l.birthFrom) && BigInt(birth) <= BigInt(l.birthThrough)));
      if (loss) {
        evidenceLost.push({ pid, creationFileTimeUtc: birth, evidenceClass: 'evidence-lost',
          droppedFile: loss.droppedFile ?? null, membership: loss.pid === pid ? 'previously-recorded' : 'unknown' });
        gaps.push({ pid, creationFileTimeUtc: birth, reason: 'evidence_lost', detail: loss.droppedFile ?? loss.detail });
      } else pending.push(p);
      continue;
    }
    // Exited before the stop; its exit is in the ledger, unless it was seen alive after it.
    const endMs = recordedEndMs(ended.exit);
    if (endMs !== null && Number.isFinite(p.lastSeenAtMs) && p.lastSeenAtMs > endMs + CONHOST_EXIT_GRACE_MS) {
      gaps.push({ pid, creationFileTimeUtc: birth, role: ended.role, reason: 'unaccounted_process',
        detail: `seen ${p.lastSeenAtMs - endMs} ms after its recorded exit` });
    }
  }
  // Spec B §5.1 requires membership evidence for the process itself. Retained manifests prove
  // that an Engine created a job, not that an arbitrary descendant was assigned to it. The
  // existing scope records have no per-helper membership witness; leave those helpers as gaps.
  // Processes with owner-written child-start/exit records were handled above, including the
  // directly queried `contained` flag. Do not promote unproven helpers into console parents.
  for (const loss of losses) {
    if (loss.pid > 0 && isFileTime(loss.creationFileTime) && !observed.some(p => pidOf(p) === loss.pid
      && sameBirth(birthOf(p), loss.creationFileTime))) {
      evidenceLost.push({ pid: loss.pid, creationFileTimeUtc: loss.creationFileTime,
        evidenceClass: 'evidence-lost', membership: 'previously-recorded', droppedFile: loss.droppedFile });
      gaps.push({ pid: loss.pid, creationFileTimeUtc: loss.creationFileTime, reason: 'evidence_lost', detail: loss.droppedFile });
    } else if (!loss.pid) gaps.push({ reason: 'evidence_lost', droppedFile: loss.droppedFile, birthFrom: loss.birthFrom,
      birthThrough: loss.birthThrough, detail: loss.detail, count: loss.count });
  }
  const gapOf = new Map();
  const helpers = pending.filter(row => !isConsoleHost(row));
  for (const p of helpers) {
    const { parent, why } = parentOf(p);
    const helper = TIER_C_EXECUTABLES.has(imageNameOf(p));
    gapOf.set(p, { pid: pidOf(p), creationFileTimeUtc: birthOf(p), role: p.role, reason: 'unaccounted_process',
      detail: !helper ? 'observed owned process with no ledger start, absent from the current B11 registry and not a tier C helper'
        : !parent ? `tier C executable, but ${why}; process job membership unproven`
          : 'tier C executable with Engine ancestry, but no owner record or direct process job membership evidence' });
  }
  // CH9: a console host is accounted with its owner-recorded client within the recorded lifetime.
  // Otherwise it is an orphan host, never "absent from registry".
  for (const p of pending.filter(isConsoleHost)) {
    const { parent, why } = parentOf(p);
    if (parent && !parent.open && !parent.witnessId && !rejectedWitnesses.has(p)) {
      account(p, 'console-host', parent, 'console host of a closed ledger process'); continue;
    }
    gapOf.set(p, { pid: pidOf(p), creationFileTimeUtc: birthOf(p), role: 'console-host', reason: 'orphan_console_host',
      detail: parent ? 'console parent is not accounted' : `console parent is not accounted: ${why}` });
  }
  for (const p of pending) if (gapOf.has(p)) gaps.push(gapOf.get(p)); // one row per process, in observed order
  return { targets: unique, gaps, accountedProcesses, evidenceLost, historicalEvidenceLoss, knownChildIds,
    observedIdentities: observed.map(p => ({ pid: pidOf(p), creationFileTimeUtc: birthOf(p), role: isConsoleHost(p) ? 'console-host' : p.role })),
    runId: run?.runId ?? null };
}

/** The manifest's Engine containment status, when the manifest is this run's. */
function containmentOf(manifest) {
  return typeof manifest?.containment?.status === 'string' ? manifest.containment.status : 'unknown';
}

/** The ledger exit record for one target, read at finish (so an exit recorded during the stop counts). */
function ledgerExitFor(target, view) {
  if (target.witnessId) return (view.witnesses ?? []).find(w => w.witnessId === target.witnessId
    && w.incarnation.session === target.incarnation?.session && w.incarnation.n === target.incarnation?.n
    && w.pid === target.pid && sameBirth(w.creationFileTimeUtc, target.creationFileTimeUtc))?.exit ?? null;
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
    );
  const accounted = new Map();
  for (const target of input.targets.filter(t => t.role !== 'console-host')) {
    const exit = osExitOf(target);
    if (!exit || !Number.isInteger(exit.exitCode)) {
      gaps.push({ pid: target.pid, role: target.role, reason: 'identity-bound OS exit code/time unavailable through teardown' }); continue;
    }
    const atMs = knownExitMs(exit.atMs);
    const base = { ...exit, atMs, exitTimeSource: atMs === null ? 'unknown' : (exit.exitTimeSource ?? 'os'), role: target.role, source };
    const record = ledgerExitFor(target, view);
    const witness = (view.witnesses ?? []).find(w => w.witnessId === target.witnessId
      && w.pid === target.pid && sameBirth(w.creationFileTimeUtc, target.creationFileTimeUtc));
    const jobEnd = jobClosedByMs(witness?.engine);
    if (jobEnd != null && atMs != null && atMs > jobEnd + CONHOST_EXIT_GRACE_MS) {
      gaps.push({ pid: target.pid, role: target.role, reason: 'unaccounted_process',
        detail: 'observed exit after its owner job closed plus grace' });
    }
    const jsCode = target.role === 'engine' ? ledgerLib.JS_ENGINE_STOP : ledgerLib.JS_CHILD_STOP;
    const recordedJobClose = target.role === 'engine' && record?.terminationMechanism === 'runner'
      && record.writer === 'dev-cli' && record.observedBy === 'census' && record.terminateCalledAtMs != null
      && Number.isInteger(record.runnerJobClose?.pid) && record.runnerJobClose.pid > 0
      && isFileTime(record.runnerJobClose.creationFileTimeUtc)
      && record.runnerJobClose.containment === 'LIBUV_JOB' && ['GONE', 'REUSED'].includes(record.runnerJobClose.osCheck)
      && sameCode(exit.exitCode, 0) && (ledger?.engine?.records ?? []).some(r => r.type === 'engine-intent'
        && r.line.incarnation?.session === target.incarnation?.session
        && r.line.incarnation?.n === target.incarnation?.n && r.line.channel === 'TERMINATE' && r.line.mode === 'NOW');
    if (record) {
      const entry = { ...base, ledgerClass: record.exitClass, ledgerReason: record.reason, ledgerDetail: record.detail,
        ledgerCode: record.code, ledgerWriter: record.writer };
      if (record.exitClass === 'OWNER_DIED') {
        processExits.push({ ...entry, expectedTermination: false });
        accounted.set(target, { ...entry, expectedTermination: record.exitClass === 'REQUESTED' });
      } else if (record.code === null || !sameCode(record.code, exit.exitCode)) {
        processExits.push({ ...entry, expectedTermination: false });
        gaps.push({ pid: target.pid, role: target.role, reason: 'ledger_os_disagree', ledgerCode: record.code, osCode: exit.exitCode,
          ledgerClass: record.exitClass });
      } else if (record.exitClass === 'REQUESTED' && record.forced
        && !recordedJobClose
        && ![jsCode, ...(target.role === 'engine' ? [1] : [])].some(code => sameCode(code, exit.exitCode))) {
        // Spec B §5.1: a forced stop is expected only with its owner's stop code (JS_ENGINE_STOP for
        // the Engine, JS_CHILD_STOP for a child), 1 for a dev Engine, or a documented runner job
        // close with the verified runner identity and the Engine's identity-bound native receipt.
        processExits.push({ ...entry, expectedTermination: false });
        gaps.push({ pid: target.pid, role: target.role, reason: 'ledger_os_disagree', ledgerClass: record.exitClass,
          osCode: exit.exitCode, detail: 'forced REQUESTED exit without a stop code' });
      } else if (record.exitClass === 'REQUESTED' || record.exitClass === 'UNEXPECTED') {
        processExits.push({ ...entry, expectedTermination: record.exitClass === 'REQUESTED' });
        accounted.set(target, { ...entry, expectedTermination: record.exitClass === 'REQUESTED' });
      } else {
        processExits.push({ ...entry, expectedTermination: false });
        gaps.push({ pid: target.pid, role: target.role, reason: 'ledger_class_unknown', ledgerClass: record.exitClass });
      }
      continue;
    }
    // No ledger record: a gap, never classified from the code (the gap's name only says whether
    // the code is our own stop code, i.e. whether the record of our own stop was lost).
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
    const record = host.witnessId ? ledgerExitFor(host, view) : null;
    const ownerDied = record?.exitClass === 'OWNER_DIED';
    const unknown = record?.exitClass === 'UNKNOWN';
    // A clean Engine cannot write its own console's final exit after it has quit. CH9 uses
    // the native client witness, held OS exit and the client's intended final receipt instead.
    const intendedClient = host.consoleClient && parent.ledgerClass === 'REQUESTED'
      && (!record || (record.exitClass === 'REQUESTED' && record.code === null));
    if (host.witnessId && !intendedClient && (!record || (!ownerDied && !sameCode(record.code, exit.exitCode)))) {
      gaps.push({ pid: host.pid, role: 'console-host', reason: record ? 'ledger_os_disagree' : 'unrecorded_exit' });
    }
    processExits.push({ ...exit, atMs, exitTimeSource: atMs === null ? 'unknown' : 'os', role: 'console-host',
      consoleParent: host.consoleParent, source, accounted: true,
      ledgerClass: crashCode(exit.exitCode) ? 'UNEXPECTED' : ownerDied ? 'OWNER_DIED' : unknown ? 'UNKNOWN' : parent.ledgerClass,
      ledgerReason: crashCode(exit.exitCode) ? 'console host crash' : ownerDied || unknown ? record.reason : parent.ledgerReason,
      ledgerCode: exit.exitCode,
      expectedTermination: !ownerDied && !unknown && parent.expectedTermination
        && (!host.witnessId || intendedClient || sameCode(record?.code, exit.exitCode))
        && !crashCode(exit.exitCode) });
  }
  // Children the ledger names that started after the census was armed: no OS handle exists for
  // them; their code is the ledger's. Not a gap when REQUESTED (Spec F §6.1, Spec B C11).
  const known = new Set(input.knownChildIds ?? []);
  // Historical helpers ended before arming; their owner retained the OS handle, so its exact
  // witness is also an exit receipt. Expose UNEXPECTED here for the existing crash reader.
  for (const p of input.accountedProcesses ?? []) {
    if (!p.witnessId || !Number.isInteger(p.ledgerCode)) continue;
    if (p.ledgerClass === 'UNKNOWN') gaps.push({ pid: p.pid, creationFileTimeUtc: p.creationFileTimeUtc,
      role: p.role, reason: 'ledger_class_unknown', detail: p.ledgerReason });
    processExits.push({ ...p, exitCode: p.ledgerCode, expectedTermination: p.ledgerClass === 'REQUESTED',
      source: ledger?.children?.sourceFiles?.[0] ?? source,
      ...(ledger?.children?.sourceFiles ? { ledgerSources: ledger.children.sourceFiles } : {}) });
  }
  const lateTargets = view.children.filter(c => !known.has(c.childId)
    && !input.targets.some(t => t.childId === c.childId)).map(c => ({ childId: c.childId, pid: c.pid,
    creationFileTimeUtc: c.creationFileTimeUtc, role: roleOfKind(c.kind), ledgerClass: c.exit?.exitClass ?? null,
    code: c.exit?.code ?? null }));
  for (const w of view.witnesses ?? []) {
    if (view.engines.some(e => e.pid === w.pid && sameBirth(e.creationFileTimeUtc, w.creationFileTimeUtc))
      || view.children.some(c => c.pid === w.pid && sameBirth(c.creationFileTimeUtc, w.creationFileTimeUtc))
      || input.targets.some(t => t.pid === w.pid && sameBirth(t.creationFileTimeUtc, w.creationFileTimeUtc))
      || input.accountedProcesses?.some(p => p.pid === w.pid && sameBirth(p.creationFileTimeUtc, w.creationFileTimeUtc))) continue;
    const end = jobClosedByMs(w.engine);
    const born = Number((BigInt(w.creationFileTimeUtc) - 116444736000000000n) / 10000n);
    const receiptEnd = recordedEndMs(w.exit);
    const outsideLifetime = end != null && (born > end || (receiptEnd != null && receiptEnd > end + CONHOST_EXIT_GRACE_MS));
    lateTargets.push({ witnessId: w.witnessId, pid: w.pid, creationFileTimeUtc: w.creationFileTimeUtc,
      role: 'job-helper', ledgerClass: outsideLifetime ? null : w.exit?.exitClass ?? null, code: w.exit?.code ?? null });
  }
  // The ledger is an inventory, including unsampled rapid processes and crash casualties.
  // Every identity has a row even when no observer could retain its final native receipt.
  for (const item of [...view.engines, ...view.children, ...(view.witnesses ?? [])]) {
    if (!item.pid || !isFileTime(item.creationFileTimeUtc) || processExits.some(p => p.pid === item.pid
      && sameBirth(p.creationFileTimeUtc, item.creationFileTimeUtc))) continue;
    const role = item.witnessId ? witnessRole(item.pid, item.creationFileTimeUtc, item.executable, view.children)
      : item.childId ? roleOfKind(item.kind) : 'engine';
    const outside = lateTargets.some(t => t.pid === item.pid && sameBirth(t.creationFileTimeUtc, item.creationFileTimeUtc)
      && t.ledgerClass == null && item.exit);
    const denied = gaps.find(g => g.pid === item.pid && ['unaccounted_process', 'orphan_console_host'].includes(g.reason));
    const record = outside || denied ? null : item.exit;
    processExits.push({ pid: item.pid, creationFileTimeUtc: item.creationFileTimeUtc, role,
      witnessId: item.witnessId, ledgerClass: record?.exitClass ?? 'UNKNOWN',
      ledgerReason: record?.reason ?? (record ? 'owner exit receipt' : denied?.reason ?? (outside ? 'job lifetime unproven' : 'final owner receipt unavailable')),
      ledgerCode: record?.code ?? null, exitCode: record?.code ?? null,
      atMs: record?.exitedAtMs ?? null, exitTimeSource: record?.exitedAtMs == null ? 'unknown' : 'ledger',
      expectedTermination: record?.exitClass === 'REQUESTED', source });
    if (!record || record.exitClass === 'UNKNOWN') gaps.push({ pid: item.pid,
      creationFileTimeUtc: item.creationFileTimeUtc, role, reason: record?.reason ?? 'final_owner_receipt_unavailable' });
  }
  for (const gap of gaps) {
    if (!gap.pid || !gap.creationFileTimeUtc || processExits.some(p => p.pid === gap.pid
      && sameBirth(p.creationFileTimeUtc, gap.creationFileTimeUtc))) continue;
    processExits.push({ pid: gap.pid, creationFileTimeUtc: gap.creationFileTimeUtc, role: gap.role ?? 'unknown',
      ledgerClass: 'UNKNOWN', ledgerReason: gap.reason, atMs: null, exitTimeSource: 'unknown',
      expectedTermination: false, accounted: false, source });
  }
  for (const p of [...(input.accountedProcesses ?? []), ...(input.observedIdentities ?? []), ...input.targets,
    ...(view.reportIdentities ?? [])]) {
    if (processExits.some(row => row.pid === p.pid && sameBirth(row.creationFileTimeUtc, p.creationFileTimeUtc))) continue;
    let gap = gaps.find(g => g.pid === p.pid && (!g.creationFileTimeUtc || sameBirth(g.creationFileTimeUtc, p.creationFileTimeUtc)));
    if (!gap && !p.ledgerClass) {
      gap = { pid: p.pid, creationFileTimeUtc: p.creationFileTimeUtc, role: p.role,
        reason: 'witness_identity_or_membership_unproven' }; gaps.push(gap);
    }
    processExits.push({ pid: p.pid, creationFileTimeUtc: p.creationFileTimeUtc, role: p.role ?? 'unknown',
      ledgerClass: p.ledgerClass ?? 'UNKNOWN', ledgerReason: p.ledgerReason ?? gap?.reason ?? 'final owner receipt unavailable',
      atMs: null, exitTimeSource: 'unknown', expectedTermination: p.ledgerClass === 'REQUESTED', accounted: !gap, source });
  }
  for (const row of processExits) {
    row.ledgerClass ??= 'UNKNOWN';
    row.ledgerReason ??= row.ledgerClass === 'UNEXPECTED' ? 'no owner intent' : 'owner receipt unavailable';
    if (row.atMs == null) {
      row.exitTimeSource = 'unknown';
      row.exitTimeReason = 'OS exit time unavailable; observation time is not exit time';
    }
  }
  return { processExits, ...(input.evidenceLost?.length ? { evidenceLost: input.evidenceLost } : {}), ...(lateTargets.length ? { lateTargets } : {}),
    ...(input.historicalEvidenceLoss?.length ? { historicalEvidenceLoss: input.historicalEvidenceLoss } : {}),
    ...(input.accountedProcesses?.length ? { accountedProcesses: input.accountedProcesses } : {}),
    exitAccountingComplete: input.targets.length > 0 && !gaps.length,
    exitAccountingGaps: gaps, exitCensusFile: source, ledgerSummary: ledgerSummaryOf({ ...view, witnesses: (view.witnesses ?? []).map(w => {
      const host = [...processExits, ...(input.accountedProcesses ?? [])].find(p => p.role === 'console-host'
        && p.pid === w.pid && sameBirth(p.creationFileTimeUtc, w.creationFileTimeUtc));
      return host ? { ...w, consoleClass: host.expectedTermination === true || host.ledgerClass === 'REQUESTED'
        ? 'REQUESTED' : host.ledgerClass ?? 'UNKNOWN' } : w;
    }) }, gaps),
    exitAccountingScope: 'exit ledger (run Engine incarnations and their children) compared with identity-bound OS exits; manifest children only as the ledger-unavailable fallback' };
}

/**
 * Spec F §6.3 and Spec B §5.2: the run's ledger in one line: how many Engine incarnations and
 * children it records, their exits by class, and the census gaps.
 */
function ledgerSummaryOf(view, gaps) {
  const summary = { incarnations: view.engines.length, children: view.children.length,
    requested: 0, unexpected: 0, ownerDied: 0, unknown: 0, gaps: gaps.length };
  const helpers = (view.witnesses ?? []).filter(w => ![...view.engines, ...view.children].some(c =>
    c.pid === w.pid && sameBirth(c.creationFileTimeUtc, w.creationFileTimeUtc)));
  if (helpers.length) summary.jobProcesses = helpers.length;
  for (const item of [...view.engines, ...view.children, ...helpers]) {
    const console = path.win32.basename(item.executable ?? '').toLowerCase() === 'conhost.exe';
    const cls = item.consoleClass ?? (console ? 'UNKNOWN' : item.exit?.exitClass);
    if (!cls) continue;
    if (cls === 'REQUESTED') summary.requested += 1;
    else if (cls === 'UNEXPECTED') summary.unexpected += 1;
    else if (cls === 'OWNER_DIED') summary.ownerDied += 1;
    else summary.unknown += 1;
  }
  return summary;
}

/** The surviving supervisor records crash casualties and ended Engine consoles; never kills. */
async function reconcileCrashedHelpers(dataDir, runId, lookup, append) {
  const view = ledgerView(readRunLedger(dataDir), runId);
  const open = (view.witnesses ?? []).filter(w => !w.exit && (w.engine.exit?.exitClass === 'UNEXPECTED'
    || (w.consoleClient && w.engine.exit?.exitClass === 'REQUESTED'))
    && w.pid !== w.engine.pid && !view.children.some(c => c.pid === w.pid
      && sameBirth(c.creationFileTimeUtc, w.creationFileTimeUtc)));
  if (!open.length) return [];
  let identities;
  try {
    identities = await lookup(open.map(w => w.pid));
    // Job closure can trail the Engine's own exit receipt. Observe that transition before
    // deciding which original identities the surviving supervisor must reconcile.
    for (let attempt = 0; attempt < 20 && open.some(w => {
      const seen = identities?.get(w.pid);
      return seen?.present && sameBirth(w.creationFileTimeUtc, seen.creationFileTime);
    }); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 50));
      identities = await lookup(open.map(w => w.pid));
    }
  } catch { identities = null; }
  const outcomes = [];
  for (const w of open) {
    const seen = identities?.get(w.pid);
    const gone = seen && (seen.present === false || (isFileTime(seen.creationFileTime)
      && !sameBirth(w.creationFileTimeUtc, seen.creationFileTime)));
    if (seen?.present && sameBirth(w.creationFileTimeUtc, seen.creationFileTime)) continue;
    const intendedConsole = w.consoleClient && w.engine.exit.exitClass === 'REQUESTED';
    const fields = { witnessId: w.witnessId, owner: w.owner, incarnation: w.incarnation,
      pid: w.pid, creationFileTime: w.creationFileTimeUtc, reconciled: true,
      cause: intendedConsole ? 'ENGINE_EXIT' : 'ENGINE_CRASH', osCheck: gone ? 'GONE' : 'IDENTITY_UNKNOWN',
      exitClass: !gone ? 'UNKNOWN' : intendedConsole ? 'REQUESTED' : 'OWNER_DIED',
      reason: gone ? (intendedConsole ? w.engine.exit.reason ?? 'Engine requested exit'
        : w.consoleClient ? 'Engine crash ended its console host; final exit time unknown'
        : 'Engine crash interrupted owner; contained helper gone; final exit time unknown')
        : 'Engine ended; helper identity lookup failed; exit and time unknown',
      code: null, exitedAt: null, observedAt: new Date(), forced: false };
    outcomes.push({ ...fields, durable: append('job-process-exit', fields) });
  }
  return outcomes;
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
  if (scopeError) input.gaps.push(scopeError);
  if (!scopeFile) input.gaps.push({ reason: 'no historical owned-process census supplied; B11 is a current registry, not an exit history' });
  const outputFile = path.join(runDir, 'process-exits.json');
  // The verdict reads the ledger last: `beforeVerdict(osExits)` gets the identity-bound OS exits and
  // writes the stop's own records from them (the dev-cli Engine exit), so the report reads them too.
  const verdict = async (output, beforeVerdict) => {
    if (beforeVerdict) await beforeVerdict(exitResult(input, output, outputFile, null).processExits);
    const finalLedger = dataDir ? readLedger(dataDir) : null;
    const refreshed = exitTargets(run, manifest, observed, finalLedger);
    const membershipGap = g => ['unaccounted_process', 'orphan_console_host', 'evidence_lost'].includes(g.reason);
    // The witness writer runs independently of census arming. Re-evaluate observed membership
    // against the final records, while preserving every OS target we actually armed a handle for.
    const finalInput = { ...input, evidenceLost: refreshed.evidenceLost,
      gaps: [...input.gaps.filter(g => !membershipGap(g)), ...refreshed.gaps.filter(g => membershipGap(g)
        && !input.targets.some(t => t.pid === g.pid && sameBirth(t.creationFileTimeUtc, g.creationFileTimeUtc)))],
      accountedProcesses: refreshed.accountedProcesses.filter(p => !input.targets.some(t =>
        t.pid === p.pid && sameBirth(t.creationFileTimeUtc, p.creationFileTimeUtc))) };
    return exitResult(finalInput, output, outputFile, finalLedger);
  };
  if (process.platform !== 'win32' && !runtime.launch) return { finish: async ({ beforeVerdict } = {}) => verdict(
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
  // The stop's own kills are not an input (Spec B §5.1, step 9): a dev kill of the Engine writes its
  // own engine-intent and engine-exit, and the verdict reads them from the ledger.
  return { finish: async ({ beforeVerdict } = {}) => {
    fs.writeFileSync(finishFile, '{}');
    const end = now() + 5000;
    while (!fs.existsSync(outputFile) && !launchError && proc.exitCode == null && now() < end) await pause(50);
    let output;
    try { output = read(outputFile); } catch { output = { gaps: [{ reason: 'exit observer did not finish through teardown' }] }; proc.kill(); }
    return verdict(output, beforeVerdict);
  } };
}
module.exports = { CONHOST_EXIT_GRACE_MS, fileTimeFromInstant,
  engineIdentity, engineIdentityAsync, readRunLedger, ledgerView, observedProcesses, exitTargets, exitResult,
  reconcileCrashedHelpers, startStopExitCensus };
