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
const isFileTime = (v) => typeof v === 'string' && /^[1-9][0-9]{0,19}$/.test(v);
/** The shared birth-identity rule (exit-ledger.cjs `sameBirth`): equal at the coarser clock's precision. */
const { sameBirth, BIRTH_TICKS } = ledgerLib;
/**
 * The tick a target's recorded birth was measured at, by where it was read: a B11 startInstant, the
 * millisecond; a CIM read (Win32_Process CreationDate), the microsecond; a ledger birth, what its
 * record measured (`birthTick` from ledgerView: GetProcessTimes, exact, for the Engine's own records;
 * the microsecond for an Engine start a dev runner read through CIM). The held handle's birth is
 * GetProcessTimes, exact, so stop-exit-census.ps1 compares at this tick: exactly when both are native.
 */
const birthTickOf = (target) => {
  if (target.creationTimeSource === 'B11.startInstant') return BIRTH_TICKS.MILLISECOND;
  if (target.creationTimeSource === 'ledger' && /^[1-9][0-9]*$/.test(String(target.birthTick ?? ''))) return BigInt(target.birthTick);
  return BIRTH_TICKS.MICROSECOND;
};
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
 * The files of a family read with lines that could not be read: corrupt, of an unknown schema or
 * type, or a torn last line (one a writer had not finished when it was read). Each names its file
 * and how many lines it lost; the records on them are not in the ledger read.
 */
function damageOf(files) {
  return (files ?? []).filter(f => f.corruptLines || f.unknownSchemaLines || f.unknownTypeLines || f.tornTail)
    .map(f => ({ file: f.file, lines: f.corruptLines + f.unknownSchemaLines + f.unknownTypeLines + (f.tornTail ? 1 : 0),
      corruptLines: f.corruptLines, unknownSchemaLines: f.unknownSchemaLines, unknownTypeLines: f.unknownTypeLines,
      tornTail: f.tornTail }));
}

/**
 * Both ledger families of a data directory. `present` is false when the family has no file (its
 * writer has never written) or could not be read; an absent family is not evidence of no records.
 * `damage` lists the family's files with lines that could not be read (damageOf).
 * `reader` (exit-ledger createIncrementalReader) parses each file once across the reads of a stop.
 */
function readRunLedger(dataDir, reader = null) {
  const dir = ledgerLib.ledgerDirectory(dataDir);
  const out = {};
  for (const family of ['engine', 'children']) {
    const present = ledgerLib.ledgerFiles(dir, family).length > 0;
    try {
      const read = reader ? reader.read(dir, family) : ledgerLib.readLedger(dir, family);
      out[family] = { present, records: read.records, sourceFiles: ledgerLib.ledgerFiles(dir, family),
        damage: damageOf(read.files) };
    } catch (error) {
      out[family] = { present: false, records: [], error: error.message };
    }
  }
  // A retained instance manifest reports the Engine job decided at boot. Bind it through a
  // ledger owner reference (PID/birth/instanceId), never through a historical PID alone.
  out.engineContainment = [];
  const refs = [...(out.engine.records ?? []).filter(r => r.type === 'engine-self-intent').map(r => r.line.engine),
    ...(out.children.records ?? []).filter(r => r.type === 'child-spawn').map(r => r.line.owner)];
  const refKeys = new Set();
  for (const ref of refs) {
    if (!ref || !isFileTime(ref.creationFileTime) || !/^[\w-]+$/.test(ref.instanceId ?? '')) continue;
    // Each owner reference is looked up once: every child-spawn of an Engine names the same owner.
    const refKey = `${ref.pid}/${ref.creationFileTime}/${ref.instanceId}`;
    if (refKeys.has(refKey)) continue;
    refKeys.add(refKey);
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
 * Whether one ledger record proves that the identity it is read for has ended (the proof table in
 * the b102 changeset). Only explicit facts prove: an exit its writer saw (engine-exit, child-exit),
 * or a reconciliation that looked for the process and found it gone (engine-owner-died or owner-died
 * with osCheck GONE). A reconciliation that found it alive (ALIVE_MATCH), could not tell
 * (IDENTITY_UNKNOWN), found its PID reused (REUSED) or names no check proves nothing; nor does an
 * exit class, reason or flag on any record. A record that names a PID or a birth must name the
 * identity's. `ambiguous` (two different starts for one Engine incarnation or child) admits only a
 * record naming both the PID and the birth. Job witnesses: witnessProofOf.
 */
function proofOfRecord(record, identity, ambiguous = false) {
  const line = record?.line ?? {};
  if (line.pid != null && Number(line.pid) !== identity.pid) return null;
  if (line.creationFileTime != null && !(isFileTime(line.creationFileTime) && isFileTime(identity.creationFileTimeUtc)
    && sameBirth(line.creationFileTime, identity.creationFileTimeUtc, BIRTH_TICKS.EXACT))) return null;
  if (ambiguous && (line.pid == null || line.creationFileTime == null)) return null;
  if (record.type === 'engine-exit' || record.type === 'child-exit') return line.reconciled === true ? null : 'direct';
  if ((record.type === 'engine-owner-died' || record.type === 'owner-died') && line.osCheck === 'GONE') return 'gone';
  return null;
}
/**
 * The exit an identity's records prove, from all of them at once (so their order never matters, and
 * no later record can take a proof back): the last exit its writer saw, else the last reconciliation
 * that found it gone. `exit` is that record (the class read from it), or null when nothing proves the
 * identity ended; `unproven` is then the last record that did not.
 */
const unprovenDetail = (exit) => `${exit.type}${exit.osCheck ? ` ${exit.osCheck}` : ''} does not prove the exit`;
function provenExitOf(records, identity, ambiguous) {
  const judged = records.map(r => ({ r, proof: proofOfRecord(r, identity, ambiguous) }));
  const chosen = judged.filter(j => j.proof === 'direct').at(-1) ?? judged.filter(j => j.proof === 'gone').at(-1);
  if (!chosen) return { exit: null, proof: null, unproven: exitOf(records.at(-1)) };
  return { exit: exitOf(chosen.r), proof: { type: chosen.r.type, osCheck: chosen.r.line.osCheck ?? null, by: chosen.proof },
    unproven: null };
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

/** A wire instant as a BigInt FILETIME, or null (fileTimeFromInstant's rule). */
const fileTimeOf = (instant) => {
  const ft = fileTimeFromInstant(instant);
  return ft === null ? null : BigInt(ft);
};

/**
 * The run's Engine incarnations (`engine-start` with `incarnation.session = runId`) and the children
 * whose `child-spawn` owner is one of them, each with its exit record (or null).
 */
function ledgerView(ledger, runId) {
  const engines = [];
  const engineStarts = (ledger?.engine?.records ?? []).filter(r => r.type === 'engine-start' && runId
    && r.line?.incarnation?.session === runId && Number.isInteger(r.line?.incarnation?.n));
  for (const r of engineStarts) {
    const inc = r.line.incarnation;
    const identity = { pid: Number(r.line.pid), creationFileTimeUtc: r.line.creationFileTime ?? null };
    // Two starts naming one incarnation with different identities: a record naming only the
    // incarnation cannot say which of them it is about.
    const ambiguous = engineStarts.some(s => s !== r && s.line.incarnation.n === inc.n
      && (Number(s.line.pid) !== identity.pid || s.line.creationFileTime !== identity.creationFileTimeUtc));
    const exits = (ledger.engine.records).filter(e => EXIT_TYPES.has(e.type)
      && e.line?.incarnation?.session === inc.session && e.line?.incarnation?.n === inc.n);
    const { exit, proof, unproven } = provenExitOf(exits, identity, ambiguous);
    // A dev runner or dev CLI reads an Engine's birth through CIM (the microsecond); any other writer exactly.
    const birthTick = ['dev-runner', 'dev-cli'].includes(r.writer?.role) ? BIRTH_TICKS.MICROSECOND : BIRTH_TICKS.EXACT;
    engines.push({ incarnation: { session: inc.session, n: inc.n }, ...identity, executable: r.line.executable || null,
      exit, proof, unproven, birthTick: String(birthTick),
      contained: (ledger.engineContainment ?? []).some(c => Number(c.pid) === Number(r.line.pid)
        && sameBirth(c.creationFileTime, r.line.creationFileTime)
        && c.incarnation?.session === inc.session && c.incarnation?.n === inc.n && c.status === 'CONTAINED') });
  }
  const byId = new Map();
  for (const r of ledger?.children?.records ?? []) {
    const id = r.line?.childId;
    if (typeof id !== 'string') continue;
    const c = byId.get(id) ?? { childId: id, starts: [], exits: [] };
    if (r.type === 'child-spawn') Object.assign(c, { kind: r.line.kind ?? null, owner: r.line.owner ?? null });
    else if (r.type === 'child-start') {
      const start = { pid: Number(r.line.pid), creationFileTimeUtc: r.line.creationFileTime ?? null,
        executable: r.line.executable ?? null, contained: r.line.contained ?? null };
      // The same process recorded twice is one start; its latest record describes it.
      const same = c.starts.findIndex(s => s.pid === start.pid && s.creationFileTimeUtc === start.creationFileTimeUtc);
      if (same >= 0) c.starts[same] = start; else c.starts.push(start);
    } else if (CHILD_EXIT_TYPES.has(r.type)) {
      c.exits.push(r);
      if (r.type === 'owner-died' && !c.owner) c.owner = r.line.previousOwner ?? null;
    }
    byId.set(id, c);
  }
  const ownedBy = (child, engine) => Number(child.owner?.pid) === engine.pid
    && sameBirth(String(child.owner?.creationFileTime ?? ''), engine.creationFileTimeUtc);
  // Each child-start is its own identity (PID and birth), proven on its own: a childId is never the
  // unit of proof. Under a childId with two or more different starts, a record naming only the
  // childId cannot say which process ended, so only a record naming a start's PID and birth proves
  // that start. A child-spawn without a start has no identity, and nothing proves it ended.
  const children = [];
  for (const { starts, exits, ...c } of byId.values()) {
    if (!engines.some(e => ownedBy(c, e))) continue;
    if (!starts.length) { children.push({ ...c, exit: null, proof: null, unproven: exitOf(exits.at(-1)) }); continue; }
    const ambiguous = starts.length > 1;
    // The Engine reads a child's birth natively (GetProcessTimes): exact.
    for (const start of starts) children.push({ ...c, ...start, ...provenExitOf(exits, start, ambiguous),
      ...(ambiguous ? { sharedChildId: true } : {}), birthTick: String(BIRTH_TICKS.EXACT) });
  }
  const witnesses = new Map();
  // Job witness proofs, by witness and identity: what the Engine's witness saw exit through the
  // handle it held (its own job-process-exit, never one marked reconciled), and what a later owner's
  // reconciliation found gone (osCheck GONE). Each names the PID and exact birth of a start the
  // same witness recorded. Kept apart from the classification below, which can still reject a
  // receipt or reclassify it: neither takes the proof back.
  const witnessStarts = new Map(), witnessProofs = new Map();
  const witnessKey = (inc, witnessId, pid, birth) => `${inc?.session}/${inc?.n}/${witnessId}/${pid}/${birth}`;
  // Candidates, judged once every start is known (so record order never matters): each must name a
  // start of the same witness with the same owner (PID, birth and instance).
  const witnessProofCandidates = [];
  const proveWitness = (line, by, type, writer) => {
    if (!Number.isSafeInteger(line.pid) || !isFileTime(line.creationFileTime)) return;
    witnessProofCandidates.push({ key: witnessKey(line.incarnation, line.witnessId, line.pid, line.creationFileTime),
      owner: line.owner, writer, proof: { type, osCheck: line.osCheck ?? null, by } });
  };
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
      witnessStarts.set(witnessKey(inc, line.witnessId, line.pid, line.creationFileTime),
        { pid: line.pid, creationFileTimeUtc: line.creationFileTime, owner, executable: line.executable });
      if (witnesses.has(key)) { witnesses.get(key).invalid = true; continue; }
      witnesses.set(key, { witnessId: line.witnessId, incarnation: inc, owner, pid: line.pid,
        creationFileTimeUtc: line.creationFileTime, executable: line.executable, contained: line.contained === true,
        awaited: line.awaited === true, expectedCode: line.expectedCode, reason: line.reason,
        engine, consoleClient: engineConsole, parentPid: line.parentPid,
        parentCreationFileTime: line.parentCreationFileTime, exit: null });
    } else if (r.type === 'job-process-exit' && line.reconciled !== true) {
      // The witness's own receipt: it waited on the handle it held, so the process has ended.
      proveWitness(line, 'direct', r.type, r.writer);
      if (!witnesses.has(key)) continue;
      const w = witnesses.get(key);
      if (w.exit || w.pid !== line.pid || w.owner.instanceId !== owner.instanceId
        || !sameBirth(w.creationFileTimeUtc, line.creationFileTime, BIRTH_TICKS.EXACT)) { w.invalid = true; continue; }
      const exit = exitOf(r);
      if (line.forced !== false || (!Number.isInteger(exit.code) && exit.exitClass !== 'UNKNOWN')
        || !['REQUESTED', 'UNEXPECTED', 'UNKNOWN'].includes(exit.exitClass)
        || (exit.exitClass === 'REQUESTED' && (!w.awaited || w.expectedCode !== 0 || exit.code !== 0
          || w.reason !== exit.reason || exit.reason !== 'HELPER_COMPLETION'))) { w.invalid = true; continue; }
      w.exit = exit.exitClass === 'UNKNOWN' ? { ...exit, reason: exit.reason ?? 'native exit identity/code unavailable' } : exit;
    }
  }
  const receipts = new Map(), windows = new Map();
  // The run's children by PID, in ledger order, for the owner a record names.
  const childrenByPid = new Map();
  for (const c of children) {
    if (!childrenByPid.has(c.pid)) childrenByPid.set(c.pid, []);
    childrenByPid.get(c.pid).push(c);
  }
  for (const r of ledger?.children?.records ?? []) {
    const line = r.line, inc = line?.incarnation, owner = line?.owner;
    const direct = engines.find(e => e.incarnation.session === inc?.session && e.incarnation.n === inc?.n
      && e.pid === Number(owner?.pid) && sameBirth(e.creationFileTimeUtc, owner?.creationFileTime));
    const parser = (childrenByPid.get(Number(owner?.pid)) ?? [])
      .find(c => sameBirth(c.creationFileTimeUtc, owner?.creationFileTime));
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
    // A window's instants are read once here, not once per helper compared with it.
    if (r.type === 'helper-window-start') windows.set(line.intentId, { ...line, engine,
      startFt: fileTimeOf(line.requestedAt), deadlineFt: fileTimeOf(line.deadlineAt), closeFt: fileTimeOf(line.closedAt) });
    if (r.type === 'helper-window-end') {
      const intent = windows.get(line.intentId);
      if (intent && sameOwner(intent.owner, owner) && intent.engine === engine
        && intent.image === line.image && intent.reason === line.reason) {
        intent.closedAt = line.closedAt;
        intent.closeFt = fileTimeOf(line.closedAt);
      }
    }
  }
  // The windows by image (the lower-cased name a helper's executable must have), in ledger order.
  const windowsByImage = new Map();
  for (const i of windows.values()) {
    if (typeof i.image !== 'string') continue;
    const key = i.image.toLowerCase();
    if (!windowsByImage.has(key)) windowsByImage.set(key, []);
    windowsByImage.get(key).push(i);
  }
  const windowsFor = (w) => windowsByImage.get(path.win32.basename(w.executable ?? '').toLowerCase()) ?? [];
  const valid = [...witnesses.values()].filter(w => !w.invalid);
  for (const r of ledger?.children?.records ?? []) {
    const line = r.line;
    if (r.type !== 'job-process-exit' || line?.reconciled !== true
      || !['dev-runner', 'dev-cli', 'engine'].includes(r.writer?.role)) continue;
    // A later owner found the witnessed identity gone. The witness's own Engine cannot reconcile it.
    if (line.osCheck === 'GONE') proveWitness(line, 'gone', r.type, r.writer);
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
  for (const { key, owner, writer, proof } of witnessProofCandidates) {
    const started = witnessStarts.get(key);
    if (!started || !sameOwner(started.owner, owner)) continue;
    // A later owner found it gone; the witness's own Engine cannot reconcile what it held.
    if (proof.by === 'gone' && writer?.role === 'engine' && Number(writer.pid) === Number(started.owner?.pid)) continue;
    if (witnessProofs.get(key)?.by !== 'direct') witnessProofs.set(key, proof);
  }
  for (const w of valid) w.proof = witnessProofs.get(witnessKey(w.incarnation, w.witnessId, w.pid, w.creationFileTimeUtc)) ?? null;
  // Every witnessed identity whose end is proven, including one whose receipts the classification rejected.
  const provenWitnesses = [...witnessStarts.entries()].filter(([key]) => witnessProofs.has(key))
    .map(([key, s]) => ({ ...s, proof: witnessProofs.get(key) }));
  // Owner records by PID, in the order they are looked for: ledger children, then helper receipts.
  const receiptsByPid = new Map(), ownerRecordsByPid = new Map();
  const index = (map, c) => { if (!map.has(c.pid)) map.set(c.pid, []); map.get(c.pid).push(c); };
  for (const c of children) index(ownerRecordsByPid, c);
  for (const c of receipts.values()) { index(ownerRecordsByPid, c); index(receiptsByPid, c); }
  const ownedHelpers = new Set();
  for (const w of valid) {
    if (!w.exit) continue;
    if (w.exit.code === null && (['OWNER_DIED', 'UNKNOWN'].includes(w.exit.exitClass)
      || (w.consoleClient && w.exit.exitClass === 'REQUESTED'))) continue;
    const ownerExit = (ownerRecordsByPid.get(w.pid) ?? []).find(c =>
      (c.engine === w.engine || ownedBy(c, w.engine)) && receiptCreator(w, c) && c.pid === w.pid
      && sameBirth(c.creationFileTimeUtc ?? c.creationFileTime, w.creationFileTimeUtc) && c.exit)?.exit;
    if (ownerExit) {
      // An owner record wins only when the independent code receipt confirms it.
      w.exit = sameCode(ownerExit.code, w.exit.code) ? { ...w.exit, ...ownerExit }
        : { ...w.exit, exitClass: 'UNKNOWN', reason: 'owner_witness_disagree' };
      if ((receiptsByPid.get(w.pid) ?? []).some(c => c.engine === w.engine && receiptCreator(w, c)
        && c.pid === w.pid && sameBirth(c.creationFileTime, w.creationFileTimeUtc)
        && c.exit === ownerExit)) ownedHelpers.add(w);
    } else {
      const born = BigInt(w.creationFileTimeUtc);
      const exited = w.exit.exitedFileTime != null ? BigInt(w.exit.exitedFileTime) : null;
      const intent = windowsFor(w).find(i => {
        const start = i.startFt, deadline = i.deadlineFt, close = i.closeFt;
        return i.engine === w.engine && createdBy(w, i.owner) && typeof i.reason === 'string' && i.reason.length > 0
          && start != null && deadline != null && close != null && close >= start
          && born >= start && born <= deadline && born <= close
          && exited != null && exited >= start && exited <= deadline && exited <= close;
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
    const declares = (receiptsByPid.get(w.pid) ?? []).some(c => c.engine === w.engine
      && c.ownerContainment === 'KILL_ON_CLOSE' && createdBy(w, c.owner)
      && c.pid === w.pid && sameBirth(c.creationFileTime, w.creationFileTimeUtc, BIRTH_TICKS.EXACT))
      || windowsFor(w).some(i => i.engine === w.engine && i.ownerContainment === 'KILL_ON_CLOSE'
        && createdBy(w, i.owner) && !i.closedAt
        && i.startFt != null && i.deadlineFt != null
        && BigInt(w.creationFileTimeUtc) >= i.startFt
        && BigInt(w.creationFileTimeUtc) <= i.deadlineFt);
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
  // Console parents by PID, in the order they are looked for: Engines, children, then witnesses.
  const byPid = new Map();
  for (const p of [...engines, ...children, ...valid]) {
    if (!byPid.has(p.pid)) byPid.set(p.pid, []);
    byPid.get(p.pid).push(p);
  }
  for (const w of valid.filter(w => path.win32.basename(w.executable ?? '').toLowerCase() === 'conhost.exe')) {
    const parent = (byPid.get(w.parentPid) ?? []).find(p => sameBirth(p.creationFileTimeUtc, w.parentCreationFileTime));
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
    creationFileTimeUtc: r.line.creationFileTime, role: CONSOLE_HOST_RE.test(r.line.executable ?? '') ? 'console-host' : 'job-helper' }));
  return { engines, children, witnesses: valid, provenWitnesses, reportIdentities };

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

/**
 * The census targets, armed before the stop. Ledger first (Spec F §6.1): the run's open Engine
 * incarnations and their open children. Console hosts observed under a target join as targets of
 * their own (role `console-host`, CH9). Every other observed process (one row per pid and birth) is
 * accounted (`accountedProcesses`) or a gap. Console hosts of closed ledger processes require
 * their parent's owner record and lifetime bound; unledgered helpers require direct membership
 * evidence, which the current scope input does not record (Spec B §5.1).
 */
function exitTargets(run, manifest, observedRows = [], ledger = null, view = null) {
  const targets = [], gaps = [], accountedProcesses = [];
  const observed = uniqueProcesses(observedRows);
  view = view ?? (ledger ? ledgerView(ledger, run?.runId) : { engines: [], children: [] });
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
      role: 'engine', incarnation: e.incarnation, creationTimeSource: 'ledger', targetSource: 'ledger', birthTick: e.birthTick });
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
  const knownChildIds = [...new Set(view.children.map(c => c.childId))];
  const knownChildren = view.children.map(c => `${c.childId}/${c.pid}/${c.creationFileTimeUtc}`);
  if (ledger?.children?.present) {
    for (const c of view.children) {
      if (c.exit || !(c.pid > 0) || !isFileTime(c.creationFileTimeUtc) || !path.isAbsolute(c.executable ?? '')) continue;
      targets.push({ id: c.childId, childId: c.childId, kind: c.kind, pid: c.pid, creationFileTimeUtc: c.creationFileTimeUtc,
        executable: c.executable, role: roleOfKind(c.kind), creationTimeSource: 'ledger', targetSource: 'ledger',
        birthTick: c.birthTick });
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
  const rejectedWitnesses = new Set(), unprovenRows = new Set();
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
    const role = isConsoleHost(p) ? 'console-host' : 'job-helper';
    if (w.exit && !w.proof) {
      // A receipt that does not prove the exit (a reconciliation that could not tell) accounts nothing.
      gaps.push({ pid: w.pid, creationFileTimeUtc: w.creationFileTimeUtc, role, reason: 'exit_unproven',
        detail: unprovenDetail(w.exit) });
      unprovenRows.add(p); continue;
    }
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
        creationTimeSource: 'ledger', birthTick: String(BIRTH_TICKS.EXACT) });
    } else rejectedWitnesses.add(p);
  }
  function isTargetAlready(p) {
    return unique.some(t => t.pid === pidOf(p) && sameBirth(t.creationFileTimeUtc, birthOf(p)));
  }
  const closedOf = (pid, birth) => closed.find(c => c.pid === pid && sameBirth(c.creationFileTimeUtc, birth));
  const isTarget = (pid, birth) => unique.some(t => t.pid === pid && sameBirth(t.creationFileTimeUtc, birth,
    BigInt(t.birthTick) > BIRTH_TICKS.MICROSECOND ? BigInt(t.birthTick) : BIRTH_TICKS.MICROSECOND));
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
    if (isTarget(pid, birth) || unprovenRows.has(p)) continue;
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
  return { targets: unique, gaps, accountedProcesses, evidenceLost, historicalEvidenceLoss, knownChildIds, knownChildren,
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
  const c = view.children.find(x => x.pid === target.pid && (target.childId
    ? x.childId === target.childId && x.creationFileTimeUtc === target.creationFileTimeUtc
    : sameBirth(x.creationFileTimeUtc, target.creationFileTimeUtc)));
  return c?.exit ?? null;
}

/**
 * The verdict per target (Spec B §5.1): the ledger class compared with the OS observation.
 * `ledger` is re-read at finish; without it every exit is unrecorded. `view` is ledgerView of that
 * ledger when the caller already has it.
 */
function exitResult(input, output, source, ledger = null, view = null) {
  const gaps = [...input.gaps, ...(output.gaps ?? [])], processExits = [];
  // Accounting is never complete over records the census could not read.
  for (const family of ['engine', 'children']) {
    for (const d of ledger?.[family]?.damage ?? []) {
      gaps.push({ reason: 'ledger-damaged', family, ...d, detail: `${d.lines} line(s) of ${path.basename(d.file)} could not be read`
        + `${d.tornTail ? ' (its last line is torn)' : ''}; any process recorded there is not accounted` });
    }
    // A family that could not be read (its files kept moving, or an I/O error) accounts nothing.
    if (ledger?.[family]?.error) gaps.push({ reason: 'ledger-unreadable', family, detail: ledger[family].error });
  }
  view = view ?? (ledger ? ledgerView(ledger, input.runId) : { engines: [], children: [] });
  // The rows by PID, kept current as rows are added (rows are only ever appended).
  const rowsByPid = new Map();
  let indexed = 0;
  const rowsOf = (pid) => {
    for (; indexed < processExits.length; indexed++) {
      const row = processExits[indexed];
      if (!rowsByPid.has(row.pid)) rowsByPid.set(row.pid, []);
      rowsByPid.get(row.pid).push(row);
    }
    return rowsByPid.get(pid) ?? [];
  };
  const hasRow = (pid, birth) => rowsOf(pid).some(p => sameBirth(p.creationFileTimeUtc, birth));
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
  const knownChildren = input.knownChildren ? new Set(input.knownChildren) : null;
  const lateTargets = view.children.filter(c => (knownChildren ? !knownChildren.has(`${c.childId}/${c.pid}/${c.creationFileTimeUtc}`)
    : !known.has(c.childId)) && !input.targets.some(t => t.childId === c.childId && t.pid === c.pid
    && t.creationFileTimeUtc === c.creationFileTimeUtc)).map(c => ({ childId: c.childId, pid: c.pid,
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
  const lateByPid = new Map();
  for (const t of lateTargets) {
    if (!lateByPid.has(t.pid)) lateByPid.set(t.pid, []);
    lateByPid.get(t.pid).push(t);
  }
  for (const item of [...view.engines, ...view.children, ...(view.witnesses ?? [])]) {
    if (!item.pid || !isFileTime(item.creationFileTimeUtc) || hasRow(item.pid, item.creationFileTimeUtc)) continue;
    const role = item.witnessId ? (CONSOLE_HOST_RE.test(item.executable ?? '') ? 'console-host' : 'job-helper')
      : item.childId ? roleOfKind(item.kind) : 'engine';
    const outside = (lateByPid.get(item.pid) ?? []).some(t => sameBirth(t.creationFileTimeUtc, item.creationFileTimeUtc)
      && t.ledgerClass == null && item.exit);
    const denied = gaps.find(g => g.pid === item.pid && ['unaccounted_process', 'orphan_console_host'].includes(g.reason));
    // Only a proven exit accounts an identity (proofOfRecord); a record that proves nothing names why.
    const unproven = item.exit && !item.proof ? item.exit : !item.exit ? item.unproven ?? null : null;
    const record = outside || denied || unproven ? null : item.exit;
    processExits.push({ pid: item.pid, creationFileTimeUtc: item.creationFileTimeUtc, role,
      witnessId: item.witnessId, ledgerClass: record?.exitClass ?? 'UNKNOWN',
      ledgerReason: record?.reason ?? (record ? 'owner exit receipt' : denied?.reason ?? (outside ? 'job lifetime unproven'
        : unproven ? unprovenDetail(unproven) : 'final owner receipt unavailable')),
      ledgerCode: record?.code ?? null, exitCode: record?.code ?? null,
      atMs: record?.exitedAtMs ?? null, exitTimeSource: record?.exitedAtMs == null ? 'unknown' : 'ledger',
      expectedTermination: record?.exitClass === 'REQUESTED', source });
    if (unproven && !denied && !outside) gaps.push({ pid: item.pid, creationFileTimeUtc: item.creationFileTimeUtc, role,
      reason: 'exit_unproven', detail: unprovenDetail(unproven) });
    else if (!record || record.exitClass === 'UNKNOWN') gaps.push({ pid: item.pid,
      creationFileTimeUtc: item.creationFileTimeUtc, role, reason: record?.reason ?? 'final_owner_receipt_unavailable' });
  }
  for (const gap of gaps) {
    if (!gap.pid || !gap.creationFileTimeUtc || hasRow(gap.pid, gap.creationFileTimeUtc)) continue;
    processExits.push({ pid: gap.pid, creationFileTimeUtc: gap.creationFileTimeUtc, role: gap.role ?? 'unknown',
      ledgerClass: 'UNKNOWN', ledgerReason: gap.reason, atMs: null, exitTimeSource: 'unknown',
      expectedTermination: false, accounted: false, source });
  }
  for (const p of [...(input.accountedProcesses ?? []), ...(input.observedIdentities ?? []), ...input.targets,
    ...(view.reportIdentities ?? [])]) {
    if (hasRow(p.pid, p.creationFileTimeUtc)) continue;
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
  const consoleRows = new Map();
  for (const p of [...processExits, ...(input.accountedProcesses ?? [])]) {
    if (p.role !== 'console-host') continue;
    if (!consoleRows.has(p.pid)) consoleRows.set(p.pid, []);
    consoleRows.get(p.pid).push(p);
  }
  const consoleRowsOf = (pid) => consoleRows.get(pid) ?? [];
  return { processExits, ...(input.evidenceLost?.length ? { evidenceLost: input.evidenceLost } : {}), ...(lateTargets.length ? { lateTargets } : {}),
    ...(input.historicalEvidenceLoss?.length ? { historicalEvidenceLoss: input.historicalEvidenceLoss } : {}),
    ...(input.accountedProcesses?.length ? { accountedProcesses: input.accountedProcesses } : {}),
    // Complete with no gap and something to account: an armed target, or (a stop after the Engine
    // had already gone, which arms none) the run's Engine incarnations in the ledger, every one of
    // whose recorded identities then has its exit (an identity without one is a gap above).
    exitAccountingComplete: (input.targets.length > 0 || view.engines.length > 0) && !gaps.length,
    exitAccountingGaps: gaps, exitCensusFile: source, ledgerSummary: ledgerSummaryOf({ ...view, witnesses: (view.witnesses ?? []).map(w => {
      const host = consoleRowsOf(w.pid).find(p => sameBirth(p.creationFileTimeUtc, w.creationFileTimeUtc));
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

/**
 * The identities the ledger proves have ended (proofOfRecord, witnessProofs in ledgerView): each
 * Engine incarnation, ledger child and job-witnessed process of the run whose records prove its end.
 * Nothing else is one: a process alive under such a PID whose birth cannot be read is never decided
 * here. `birthTick` is the precision its birth was recorded at (the microsecond for an Engine start
 * a dev runner or dev CLI read through CIM, else exact); a recorded identity matches it at the
 * coarser of that and its own, never coarser.
 */
function endedIdentitiesOf(view) {
  const ended = [];
  const add = (item, role) => {
    if (!item.proof || !Number.isSafeInteger(item.pid) || item.pid <= 0 || !isFileTime(item.creationFileTimeUtc)) return;
    if (ended.some(e => e.pid === item.pid && e.creationFileTimeUtc === item.creationFileTimeUtc)) return;
    ended.push({ pid: item.pid, creationFileTimeUtc: item.creationFileTimeUtc, role,
      birthTick: String(item.birthTick ?? BIRTH_TICKS.EXACT), ledgerType: item.proof.type,
      proof: item.proof.by, exitedAtMs: item.exit?.exitedAtMs ?? null });
  };
  for (const e of view.engines ?? []) add(e, 'engine');
  for (const c of view.children ?? []) add(c, roleOfKind(c.kind));
  for (const w of view.provenWitnesses ?? view.witnesses ?? []) {
    add(w, CONSOLE_HOST_RE.test(w.executable ?? '') ? 'console-host' : 'job-helper');
  }
  return ended;
}

/** How long the verdict waits before reading a torn ledger line again. */
const TORN_TAIL_SETTLE_MS = 250;
const EARLIER_TARGETS_RE = /^exit-targets\.(\d+)\.json$/;
const EARLIER_EXITS_RE = /^process-exits\.(\d+)\.json$/;
const sameIdentity = (a, b) => Number(a?.pid) === Number(b?.pid) && a?.creationFileTimeUtc === b?.creationFileTimeUtc;

/** How long keeping an earlier census retries a rename another process's open handle refuses. */
const CENSUS_ARCHIVE_ATTEMPTS = 40;
const CENSUS_ARCHIVE_PAUSE_MS = 50;

/**
 * A retried stop never overwrites an earlier census of its run: the earlier targets and OS exits
 * (exit-targets.json, process-exits.json) are kept under the next free number, as they were, and
 * every census kept so far is read back so this one adds to it. A rename another process's handle
 * refuses for a moment (Windows EBUSY, EPERM, EACCES) is tried again for about two seconds; a file
 * that still cannot be renamed stays as it is, untouched, is read where it is, and this census is
 * written under the next free number instead (`inputFile`, `outputFile`; `notArchived` names what
 * stayed and why). Archived or not, every earlier file is read and listed (`files`, the names;
 * `fileStates`, each with `readable` and `archived`); one that cannot be read is kept as it is, adds
 * nothing, and is named in `unreadable` (the census makes it a gap).
 */
async function keepEarlierCensus(runDir, read, pause = ms => new Promise(resolve => setTimeout(resolve, ms))) {
  const inputFile = path.join(runDir, 'exit-targets.json'), outputFile = path.join(runDir, 'process-exits.json');
  const list = () => { try { return fs.readdirSync(runDir); } catch { return null; } };
  let names = list();
  if (!names) return { files: [], fileStates: [], unreadable: [], targets: [], osExits: [], inputFile, outputFile, notArchived: [] };
  const nextNumber = () => Math.max(0, ...names.map(n => EARLIER_TARGETS_RE.exec(n)?.[1] ?? EARLIER_EXITS_RE.exec(n)?.[1])
    .filter(Boolean).map(Number)) + 1;
  const next = nextNumber();
  const notArchived = [];
  for (const [file, name] of [[inputFile, `exit-targets.${next}.json`], [outputFile, `process-exits.${next}.json`]]) {
    for (let attempt = 0; fs.existsSync(file); attempt++) {
      try {
        fs.renameSync(file, path.join(runDir, name));
        break;
      } catch (err) {
        if (err?.code === 'ENOENT') break;
        if (!['EBUSY', 'EPERM', 'EACCES'].includes(err?.code) || attempt + 1 >= CENSUS_ARCHIVE_ATTEMPTS) {
          notArchived.push({ file: path.basename(file), error: `${err?.code ?? 'error'}: ${err?.message ?? err}` });
          break;
        }
        await pause(CENSUS_ARCHIVE_PAUSE_MS);
      }
    }
  }
  names = list() ?? [];
  const byNumber = (re) => names.filter(n => re.test(n)).sort((a, b) => Number(re.exec(a)[1]) - Number(re.exec(b)[1]));
  const held = new Set(notArchived.map(n => n.file).filter(n => names.includes(n)));
  const files = [], fileStates = [], targets = [], osExits = [], unreadable = [];
  for (const [re, canonical, field, into] of [[EARLIER_TARGETS_RE, 'exit-targets.json', 'targets', targets],
    [EARLIER_EXITS_RE, 'process-exits.json', 'processExits', osExits]]) {
    // The numbered (archived) files, then the one that stayed where it was (the latest earlier census).
    const kept = [...byNumber(re).map(name => ({ name, archived: true })), ...(held.has(canonical) ? [{ name: canonical, archived: false }] : [])];
    for (const { name, archived } of kept) {
      let readable = true;
      try {
        for (const item of read(path.join(runDir, name))[field] ?? []) if (!into.some(x => sameIdentity(x, item))) into.push(item);
      } catch (err) {
        // Kept as it is; it adds nothing, and is named.
        readable = false;
        unreadable.push({ name, archived, error: `${err?.code ?? err?.name ?? 'error'}: ${err?.message ?? err}` });
      }
      files.push(name);
      fileStates.push({ name, readable, archived });
    }
  }
  if (!notArchived.length) return { files, fileStates, unreadable, targets, osExits, inputFile, outputFile, notArchived };
  // An earlier census file stays where it was: this census goes under the next free number.
  const own = nextNumber();
  return { files, fileStates, unreadable, targets, osExits, notArchived,
    inputFile: path.join(runDir, `exit-targets.${own}.json`), outputFile: path.join(runDir, `process-exits.${own}.json`) };
}

async function startStopExitCensus(run, dataDir, runDir, scopeFile, runtime = {}) {
  const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  // One reader for the whole stop (the stop's own when it passes one): each ledger file is parsed
  // once, and later reads add what was appended.
  const reader = runtime.ledgerReader ?? ledgerLib.createIncrementalReader();
  const readLedger = runtime.readLedger ?? (dir => readRunLedger(dir, reader));
  const settle = runtime.pause ?? (ms => new Promise(resolve => setTimeout(resolve, ms)));
  let manifest, observed = [], scopeError;
  try { manifest = read(path.join(dataDir, 'runtime/manifest.json')); } catch { /* named below */ }
  if (scopeFile) {
    try {
      observed = observedProcesses(read(scopeFile));
    } catch (error) { scopeError = { reason: `owned-process census unavailable: ${error.message}` }; }
  }
  const input = exitTargets(run, manifest, observed, dataDir ? readLedger(dataDir) : null);
  // The gaps of the arming read of the ledger; those added below are not the ledger's.
  const armingReadGaps = input.gaps.length;
  input.observedAfterMs = (runtime.now ?? Date.now)();
  if (scopeError) input.gaps.push(scopeError);
  if (!scopeFile) input.gaps.push({ reason: 'no historical owned-process census supplied; B11 is a current registry, not an exit history' });
  const earlier = await keepEarlierCensus(runDir, read, runtime.pause);
  const { inputFile, outputFile } = earlier;
  // An earlier census that cannot be read may hold an exit (or a disagreement) this one cannot see.
  for (const u of earlier.unreadable) input.gaps.push({ reason: 'earlier-census-unreadable', file: u.name, archived: u.archived,
    detail: `${u.name} could not be read (${u.error}); what that census observed is not accounted` });
  // The verdict reads the ledger last: `beforeVerdict(osExits)` gets the identity-bound OS exits and
  // writes the stop's own records from them (the dev-cli Engine exit), so the report reads them too.
  const verdict = async (observerOutput, beforeVerdict) => {
    // An earlier census of this run adds its OS exits, and the targets it observed them for.
    const output = earlier.files.length ? { ...observerOutput, processExits: [...(observerOutput.processExits ?? []),
      ...earlier.osExits.filter(e => !(observerOutput.processExits ?? []).some(x => sameIdentity(x, e)))] } : observerOutput;
    const armed = { ...input, targets: [...input.targets, ...earlier.targets.filter(t => !input.targets.some(x => sameIdentity(x, t))
      && (output.processExits ?? []).some(e => sameIdentity(e, t)))] };
    if (beforeVerdict) await beforeVerdict(exitResult(armed, output, outputFile, null).processExits);
    let finalLedger = dataDir ? readLedger(dataDir) : null;
    // A line its writer is still writing is read again once; torn then, it is a gap like any other.
    const torn = l => ['engine', 'children'].some(f => (l?.[f]?.damage ?? []).some(d => d.tornTail));
    if (torn(finalLedger)) {
      await settle(TORN_TAIL_SETTLE_MS);
      finalLedger = readLedger(dataDir);
    }
    const finalView = finalLedger ? ledgerView(finalLedger, run?.runId) : null;
    const refreshed = exitTargets(run, manifest, observed, finalLedger, finalView);
    const membershipGap = g => ['unaccounted_process', 'orphan_console_host', 'evidence_lost'].includes(g.reason);
    // The ledger's gaps are the final read's: a gap of the arming read that a later read in this
    // stop resolves (a torn Engine start completed, a record written since) is dropped. The witness
    // writer runs independently of census arming, so observed membership is judged on the final
    // records too, while every OS target a handle was armed for is kept.
    const finalInput = { ...armed, evidenceLost: refreshed.evidenceLost,
      gaps: [...armed.gaps.slice(armingReadGaps), ...refreshed.gaps.filter(g => !membershipGap(g)
        || !armed.targets.some(t => t.pid === g.pid && sameBirth(t.creationFileTimeUtc, g.creationFileTimeUtc)))],
      accountedProcesses: refreshed.accountedProcesses.filter(p => !armed.targets.some(t =>
        t.pid === p.pid && sameBirth(t.creationFileTimeUtc, p.creationFileTimeUtc))) };
    const result = exitResult(finalInput, output, outputFile, finalLedger, finalView);
    // An earlier census file that could not be renamed stays as it was; this census names where it went.
    const written = earlier.notArchived.length ? { earlierCensusNotArchived: earlier.notArchived,
      censusWrittenAs: [path.basename(inputFile), path.basename(outputFile)] } : {};
    if (!earlier.files.length) return { ...result, ...written };
    // The census file holds every OS exit observed for the run, the earlier ones included.
    try {
      fs.writeFileSync(outputFile, JSON.stringify({ ...output, earlierCensus: earlier.files, earlierCensusFiles: earlier.fileStates }));
    } catch { /* the kept files remain */ }
    return { ...result, ...written, earlierCensus: earlier.files, earlierCensusFiles: earlier.fileStates };
  };
  if (process.platform !== 'win32' && !runtime.launch) return { finish: async ({ beforeVerdict } = {}) => verdict(
    { gaps: [{ reason: 'OS process-handle exit census requires Windows' }] }, beforeVerdict) };
  const readyFile = path.join(runDir, 'exit-census-ready.json');
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
  endedIdentitiesOf, reconcileCrashedHelpers, startStopExitCensus };
