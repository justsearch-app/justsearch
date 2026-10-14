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
/**
 * Every identity comparison goes through the one rule (ledger-identity.cjs compareIdentity), with the
 * whole value it compares: a ledger start, a record, a target, an OS row. Nothing here compares a
 * PID or a birth itself (a structural check in test-dev-runner-stop-census.mjs holds it to that).
 */
const { TICK, isFileTime, identityOf, startIdentity, noteConflict, compareIdentity, identityKey, namedBy, hasPid,
  writerIsOwner, birthMs, birthFileTime, birthOrder, bornNotAfter, bornAfter, bornAt } = require('./ledger-identity.cjs');
/** Records by PID: an index only, each candidate in a bucket is then matched by compareIdentity. */
const byPid = (list, of = x => x) => {
  const index = new Map();
  for (const x of list) {
    const pid = identityOf(of(x))?.pid ?? null;
    if (!index.has(pid)) index.set(pid, []);
    index.get(pid).push(x);
  }
  return index;
};
/**
 * The tick a target's recorded birth was measured at, by where it was read: a B11 startInstant, the
 * millisecond; a CIM read (Win32_Process CreationDate), the microsecond; a ledger birth, what its
 * record measured (`birthTick` from ledgerView: GetProcessTimes, exact, for the Engine's own records;
 * the microsecond for an Engine start a dev runner read through CIM). The held handle's birth is
 * GetProcessTimes, exact, so stop-exit-census.ps1 compares at this tick: exactly when both are native.
 *
 * The tick is always worked out from where the birth was read, never taken from a `birthTick` the
 * target carries: a ledger child or job witness was read natively (exact); a ledger Engine start at
 * the tick ledgerView gives that start in `view` (exact unless a dev runner or dev CLI read it
 * through CIM), and exact when the view has no such start (an unknown precision is never coarser).
 */
const birthTickOf = (target, view = null) => {
  if (target.creationTimeSource === 'B11.startInstant') return TICK.MILLISECOND;
  if (target.creationTimeSource === 'CIM') return TICK.MICROSECOND;
  if (target.creationTimeSource === 'ledger') {
    if (target.role !== 'engine' || target.childId || target.witnessId) return TICK.EXACT;
    const start = (view?.engines ?? []).find(e => compareIdentity(e, { pid: target.pid,
      creationFileTimeUtc: target.creationFileTimeUtc, incarnation: target.incarnation ?? null }, 'exact'));
    return start ? BigInt(start.birthTick) : TICK.EXACT;
  }
  return TICK.MICROSECOND;
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
  const row = table.table.find(p => hasPid(p, pid));
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
    const refKey = `${identityKey(ref)}/${ref.instanceId}`;
    if (refKeys.has(refKey)) continue;
    refKeys.add(refKey);
    try {
      const manifest = JSON.parse(fs.readFileSync(path.join(dataDir, 'runtime/instances', ref.instanceId, 'manifest.json'), 'utf8'));
      if (manifest.instanceId !== ref.instanceId || !hasPid(ref, manifest.pid)) continue;
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
 * Whether one ledger record proves that the start it is read for has ended (the proof table in the
 * b102 changeset). Only explicit facts prove: an exit its writer saw (engine-exit, child-exit), or a
 * reconciliation that looked for the process and found it gone (engine-owner-died or owner-died with
 * osCheck GONE). A reconciliation that found it alive (ALIVE_MATCH), could not tell (IDENTITY_UNKNOWN),
 * found its PID reused (REUSED) or names no check proves nothing; nor does an exit class, reason or
 * flag on any record. Whether the record is about the start at all is the one identity rule
 * (compareIdentity 'proves'): a key-only record (a childId, an Engine incarnation) proves only the one
 * start of its key, unconflicted and uncontested, and a child's only through the owner it names or its
 * writer; any other record must carry and agree on every field the start carries.
 */
function proofOfRecord(record, identity, ctx) {
  const line = record?.line ?? {};
  if (!compareIdentity(line, identity, 'proves', { ...ctx, writer: record?.writer ?? null })) return null;
  if (record.type === 'engine-exit' || record.type === 'child-exit') return line.reconciled === true ? null : 'direct';
  if ((record.type === 'engine-owner-died' || record.type === 'owner-died') && line.osCheck === 'GONE') return 'gone';
  return null;
}

/**
 * The exit a start's records prove, from all of them at once (so their order never matters, and no
 * later record can take a proof back): the last exit its writer saw, else the last reconciliation that
 * found it gone. `exit` is that record (the class read from it), or null when nothing proves the start
 * ended; `unproven` is then the last record that did not. `keyStarts` is how many starts share the
 * key the records were read by (the childId, the incarnation); a record of the key that contradicts the
 * start (another PID, birth, owner or incarnation) leaves the key contested, and then no key-only record
 * proves it. `conflictingCodes` lists the different exit codes two or more of its proving exits name:
 * a ledger that holds two codes for one identity disagrees with itself, and exitResult makes that a
 * gap no later record clears.
 */
const unprovenDetail = (exit) => `${exit.type}${exit.osCheck ? ` ${exit.osCheck}` : ''} does not prove the exit`;
function provenExitOf(records, identity, keyStarts) {
  const contested = records.some(r => compareIdentity(r.line ?? {}, identity, 'contradicts', { tick: TICK.EXACT }));
  const judged = records.map(r => ({ r, proof: proofOfRecord(r, identity, { keyStarts, contested }) }));
  const chosen = judged.filter(j => j.proof === 'direct').at(-1) ?? judged.filter(j => j.proof === 'gone').at(-1);
  if (!chosen) return { exit: null, proof: null, unproven: exitOf(records.at(-1)) };
  const codes = [...new Set(judged.filter(j => j.proof === 'direct' && Number.isInteger(j.r.line.code))
    .map(j => j.r.line.code >>> 0))];
  return { exit: exitOf(chosen.r), proof: { type: chosen.r.type, osCheck: chosen.r.line.osCheck ?? null, by: chosen.proof },
    unproven: null, ...(codes.length > 1 ? { conflictingCodes: codes } : {}) };
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
// The parent a witness recorded for its process, as an identity (read natively: exact).
const recordedParent = (w) => identityOf({ pid: w.parentPid, creationFileTime: w.parentCreationFileTime ?? null, birthTick: '1' });
const createdBy = (w, owner) => Boolean(owner) && compareIdentity(recordedParent(w), owner, 'same', { tick: TICK.EXACT })
  && bornNotAfter(owner, w, TICK.EXACT);
const sameOwner = (a, b) => Boolean(a && b) && compareIdentity(a, b, 'exact', { tick: TICK.EXACT });
const receiptCreator = (w, c) => createdBy(w, c.owner) || Boolean(c.helperId && c.owner
  && compareIdentity({ pid: c.creatorPid, creationFileTime: c.creatorCreationFileTime ?? null, birthTick: '1' }, c.owner, 'same',
    { tick: TICK.EXACT })
  && bornNotAfter(bornAt(c.creatorCreationFileTime), w, TICK.EXACT));

/** A wire instant as a BigInt FILETIME, or null (fileTimeFromInstant's rule). */
const fileTimeOf = (instant) => {
  const ft = fileTimeFromInstant(instant);
  return ft === null ? null : BigInt(ft);
};

/**
 * The run's Engine incarnations (`engine-start` with `incarnation.session = runId`) and the children
 * whose owner is one of them, each with its exit record (or null).
 *
 * Each start's identity (ledger-identity.cjs) is built once, from its own record, when it is read: an
 * Engine start's PID, birth (at the tick its writer measured it), and incarnation; a child-start's PID,
 * birth, childId, and its owner and incarnation (its own, else the latest child-spawn before it for its
 * childId). Nothing read later rewrites it. A child-spawn, or a second child-start of the same process,
 * that disagrees with it is a conflict on it (`identityConflicts`, which the census reports as finding
 * `identity_metadata_conflict`), and a start with a conflict is proven by no record.
 */
function ledgerView(ledger, runId) {
  const engines = [];
  const engineStarts = (ledger?.engine?.records ?? []).filter(r => r.type === 'engine-start' && runId
    && r.line?.incarnation?.session === runId && Number.isInteger(r.line?.incarnation?.n));
  // A dev runner or dev CLI reads an Engine's birth through CIM (the microsecond); any other writer exactly.
  const engineIds = [];
  for (const r of engineStarts) {
    const cim = ['dev-runner', 'dev-cli'].includes(r.writer?.role);
    const id = startIdentity({ pid: r.line.pid, creationFileTime: r.line.creationFileTime ?? null, kind: 'engine',
      incarnation: r.line.incarnation, tick: cim ? TICK.MICROSECOND : TICK.EXACT, source: cim ? 'CIM' : 'native' });
    // The same start recorded twice is one start.
    if (engineIds.some(e => compareIdentity(e.id, id, 'exact'))) continue;
    engineIds.push({ r, id });
  }
  for (const { r, id } of engineIds) {
    const inc = r.line.incarnation;
    // Two starts naming one incarnation: a record naming only the incarnation cannot say which it is about.
    const keyStarts = engineIds.filter(e => e.id.incarnation.n === inc.n).length;
    const exits = (ledger.engine.records).filter(e => EXIT_TYPES.has(e.type)
      && e.line?.incarnation?.session === inc.session && e.line?.incarnation?.n === inc.n);
    const { exit, proof, unproven, conflictingCodes } = provenExitOf(exits, id, keyStarts);
    engines.push({ incarnation: { session: inc.session, n: inc.n }, pid: Number(r.line.pid),
      creationFileTimeUtc: r.line.creationFileTime ?? null, executable: r.line.executable || null,
      exit, proof, unproven, ...(conflictingCodes ? { conflictingCodes } : {}), birthTick: id.tick,
      contained: (ledger.engineContainment ?? []).some(c => c.status === 'CONTAINED' && compareIdentity(c, id, 'same')),
      identity: id });
  }
  const byId = new Map();
  for (const r of ledger?.children?.records ?? []) {
    const childId = r.line?.childId;
    if (typeof childId !== 'string') continue;
    const c = byId.get(childId) ?? { childId, kind: null, spawn: null, starts: [], exits: [] };
    if (r.type === 'child-spawn') {
      const spawn = { kind: r.line.kind ?? null, owner: r.line.owner ?? null, incarnation: r.line.incarnation ?? null };
      // A later spawn of the childId never rewrites a start already read; one that disagrees with it is a conflict on it.
      const meta = identityOf({ owner: spawn.owner, incarnation: spawn.incarnation, childId });
      for (const s of c.starts) {
        if (compareIdentity(meta, s.identity, 'contradicts')) noteConflict(s.identity, { field: 'later-spawn',
          detail: 'a later child-spawn of its childId names another owner or incarnation', value: JSON.stringify(spawn) });
      }
      c.spawn = spawn;
      c.kind = spawn.kind ?? c.kind;
    } else if (r.type === 'child-start') {
      const spawn = c.spawn;
      const own = { owner: r.line.owner ?? null, incarnation: r.line.incarnation ?? null };
      const identity = startIdentity({ pid: r.line.pid, creationFileTime: r.line.creationFileTime ?? null, childId,
        kind: 'child', owner: own.owner ?? spawn?.owner ?? null, incarnation: own.incarnation ?? spawn?.incarnation ?? null });
      // Its own owner and incarnation, where it records them, must be its spawn's; the start keeps its own.
      if (spawn && compareIdentity(identityOf({ owner: spawn.owner, incarnation: spawn.incarnation, childId }), identity,
        'contradicts')) noteConflict(identity, { field: 'spawn', value: JSON.stringify(spawn),
        detail: 'its child-spawn names another owner or incarnation than the child-start' });
      const start = { pid: Number(r.line.pid), creationFileTimeUtc: r.line.creationFileTime ?? null,
        executable: r.line.executable ?? null, contained: r.line.contained ?? null, kind: spawn?.kind ?? c.kind,
        owner: own.owner ?? spawn?.owner ?? null, incarnation: identity.incarnation, identity };
      // The same process recorded twice is one start; its latest record describes it. The same process
      // recorded with other metadata keeps its first identity, and the disagreement is a conflict on it.
      const same = c.starts.findIndex(s => compareIdentity(s.identity, identity, 'native', { tick: TICK.EXACT }));
      if (same < 0) c.starts.push(start);
      else if (compareIdentity(c.starts[same].identity, identity, 'exact')) {
        c.starts[same] = { ...c.starts[same], executable: start.executable, contained: start.contained };
      } else {
        noteConflict(c.starts[same].identity, { field: 'restart', value: JSON.stringify(own),
          detail: 'a second child-start of the same process names another owner or incarnation' });
        for (const conflict of identity.conflicts) noteConflict(c.starts[same].identity, conflict);
      }
    } else if (CHILD_EXIT_TYPES.has(r.type)) c.exits.push(r);
    byId.set(childId, c);
  }
  // A child is the run's when the owner its own identity names is a run Engine.
  const ownedBy = (child, engine) => {
    const owner = identityOf(child)?.owner;
    return Boolean(owner) && compareIdentity(owner, engine, 'same');
  };
  // Each child-start is its own identity, proven on its own: a childId is never the unit of proof.
  // Under a childId with two or more starts, a record naming only the childId cannot say which process
  // ended (compareIdentity 'proves', keyStarts). A child-spawn without a start has no identity, and
  // nothing proves it ended.
  const children = [];
  const identityConflicts = [];
  for (const { starts, exits, spawn, ...c } of byId.values()) {
    if (!starts.length) {
      const owner = spawn?.owner ? identityOf({ owner: spawn.owner }) : null;
      if (owner && engines.some(e => ownedBy(owner, e))) {
        children.push({ ...c, owner: spawn.owner, incarnation: spawn.incarnation, exit: null, proof: null,
          unproven: exitOf(exits.at(-1)) });
      }
      continue;
    }
    for (const start of starts) {
      if (!engines.some(e => ownedBy(start, e))) continue;
      const item = { ...c, ...start, ...provenExitOf(exits, start.identity, starts.length),
        ...(starts.length > 1 ? { sharedChildId: true } : {}), birthTick: String(TICK.EXACT) };
      children.push(item);
      if (start.identity.conflicts.length) identityConflicts.push(item);
    }
  }
  const witnesses = new Map();
  // Job witness proofs: what the Engine's witness saw exit through the handle it held (its own
  // job-process-exit, never one marked reconciled), and what a later owner's reconciliation found gone
  // (osCheck GONE). Each must carry and agree on every field of a start the same witness recorded (its
  // PID, exact birth, witnessId, owner and incarnation: compareIdentity 'proves'). Kept apart from the
  // classification below, which can still reject a receipt or reclassify it: neither takes the proof back.
  const witnessStartsById = new Map();
  // Candidates, judged once every start is known (so record order never matters).
  const witnessProofCandidates = [];
  const proveWitness = (line, by, type, writer) => {
    if (!isFileTime(line.creationFileTime) || !identityOf(line, { defaultTick: TICK.EXACT }).pid) return;
    witnessProofCandidates.push({ line, writer, proof: { type, osCheck: line.osCheck ?? null, by } });
  };
  for (const r of ledger?.children?.records ?? []) {
    const line = r.line, inc = line?.incarnation, owner = line?.owner;
    const engine = inc && owner && engines.find(e => compareIdentity({ ...owner, incarnation: inc }, e, 'same'));
    if (!engine || !writerIsOwner(r.writer, engine, { unknownBirth: true })
      || !/^[\w-]+$/.test(owner?.instanceId ?? '') || !/^[\w-]+$/.test(line?.witnessId ?? '')) continue;
    const key = `${inc.session}/${inc.n}/${line.witnessId}`;
    const engineConsole = line.consoleClient === true && CONSOLE_HOST_RE.test(line.executable ?? '')
      && compareIdentity(recordedParent(line), owner, 'same', { tick: TICK.EXACT });
    const start = r.type === 'job-process-start' ? startIdentity({ pid: line.pid, creationFileTime: line.creationFileTime ?? null,
      kind: 'witness', witnessId: line.witnessId, owner, incarnation: inc }) : null;
    if (start && (line.contained === true || engineConsole) && start.pid !== null && start.birth !== null
      && bornNotAfter(engine, start, TICK.EXACT)) {
      if (!witnessStartsById.has(line.witnessId)) witnessStartsById.set(line.witnessId, []);
      witnessStartsById.get(line.witnessId).push({ pid: start.pid, creationFileTimeUtc: line.creationFileTime, owner,
        executable: line.executable, witnessId: line.witnessId, incarnation: inc, identity: start });
      if (witnesses.has(key)) { witnesses.get(key).invalid = true; continue; }
      witnesses.set(key, { witnessId: line.witnessId, incarnation: inc, owner, pid: start.pid,
        creationFileTimeUtc: line.creationFileTime, executable: line.executable, contained: line.contained === true,
        awaited: line.awaited === true, expectedCode: line.expectedCode, reason: line.reason,
        engine, consoleClient: engineConsole, parentPid: line.parentPid,
        parentCreationFileTime: line.parentCreationFileTime, exit: null, identity: start });
    } else if (r.type === 'job-process-exit' && line.reconciled !== true) {
      // The witness's own receipt: it waited on the handle it held, so the process has ended.
      proveWitness(line, 'direct', r.type, r.writer);
      if (!witnesses.has(key)) continue;
      const w = witnesses.get(key);
      if (w.exit || !compareIdentity(line, w.identity, 'proves', { family: 'witness' })) { w.invalid = true; continue; }
      const exit = exitOf(r);
      if (line.forced !== false || (!Number.isInteger(exit.code) && exit.exitClass !== 'UNKNOWN')
        || !['REQUESTED', 'UNEXPECTED', 'UNKNOWN'].includes(exit.exitClass)
        || (exit.exitClass === 'REQUESTED' && (!w.awaited || w.expectedCode !== 0 || exit.code !== 0
          || w.reason !== exit.reason || exit.reason !== 'HELPER_COMPLETION'))) { w.invalid = true; continue; }
      w.exit = exit.exitClass === 'UNKNOWN' ? { ...exit, reason: exit.reason ?? 'native exit identity/code unavailable' } : exit;
    }
  }
  const receipts = new Map(), windows = new Map();
  // The run's children by PID (an index only: each is matched by compareIdentity), for the owner a record names.
  const childrenByPid = new Map();
  for (const c of children) {
    if (!childrenByPid.has(c.pid)) childrenByPid.set(c.pid, []);
    childrenByPid.get(c.pid).push(c);
  }
  for (const r of ledger?.children?.records ?? []) {
    const line = r.line, inc = line?.incarnation, owner = line?.owner;
    if (!inc || !owner) continue;
    const direct = engines.find(e => compareIdentity({ ...owner, incarnation: inc }, e, 'same'));
    const parser = (childrenByPid.get(identityOf(owner).pid) ?? []).find(c => c.identity && compareIdentity(owner, c, 'native'));
    const engine = direct ?? (parser && engines.find(e => ownedBy(parser, e)
      && e.incarnation.session === inc.session && e.incarnation.n === inc.n));
    if (!engine || !writerIsOwner(r.writer, owner, { unknownBirth: true })) continue;
    if (r.type === 'helper-start' && isFileTime(line.creationFileTime))
      receipts.set(line.helperId, { ...line, engine, parentPid: line.parentPid, parentCreationFileTime: line.parentCreationFileTime, exit: null });
    if (r.type === 'helper-exit') {
      // A failed start write does not invalidate a self-contained owner exit receipt. Its
      // creator identity is still checked against the live-captured native witness below.
      if (!receipts.has(line.helperId) && /^[\w-]+$/.test(line.helperId ?? '')
        && Number.isSafeInteger(line.pid) && isFileTime(line.creationFileTime))
        receipts.set(line.helperId, { ...line, engine, exit: null });
      const receipt = receipts.get(line.helperId);
      if (receipt && sameOwner(receipt.owner, owner) && compareIdentity(receipt, line, 'same', { tick: TICK.EXACT }))
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
    const w = valid.find(w => compareIdentity(line, w.identity, 'proves', { family: 'witness' }));
    const crashReceipt = w?.engine.exit?.exitClass === 'UNEXPECTED' && line.cause === 'ENGINE_CRASH';
    const intendedConsole = w?.consoleClient && w.engine.exit?.exitClass === 'REQUESTED' && line.cause === 'ENGINE_EXIT';
    if (!w || w.exit || (!crashReceipt && !intendedConsole)) continue;
    if (writerIsOwner(r.writer, w.engine, { unknownBirth: true })) continue;
    if ((line.osCheck === 'GONE' && line.exitClass === (intendedConsole ? 'REQUESTED' : 'OWNER_DIED'))
      || (line.osCheck === 'IDENTITY_UNKNOWN' && line.exitClass === 'UNKNOWN')) w.exit = exitOf(r);
  }
  // Each witnessed start, once (the same start recorded twice is one), with what proves its end.
  const witnessStarts = [];
  for (const starts of witnessStartsById.values()) {
    for (const s of starts) if (!witnessStarts.some(x => compareIdentity(x.identity, s.identity, 'exact'))) witnessStarts.push(s);
  }
  for (const { line, writer, proof } of witnessProofCandidates) {
    for (const started of witnessStartsById.get(line.witnessId) ?? []) {
      const s = witnessStarts.find(x => compareIdentity(x.identity, started.identity, 'exact'));
      if (!compareIdentity(line, s.identity, 'proves', { family: 'witness' })) continue;
      // A later owner found it gone; the witness's own Engine cannot reconcile what it held.
      if (proof.by === 'gone' && writerIsOwner(writer, s.owner, { unknownBirth: true })) continue;
      if (s.proof?.by !== 'direct') s.proof = proof;
    }
  }
  for (const w of valid) w.proof = witnessStarts.find(s => compareIdentity(s.identity, w.identity, 'exact'))?.proof ?? null;
  // Every witnessed identity whose end is proven, including one whose receipts the classification rejected.
  const provenWitnesses = witnessStarts.filter(s => s.proof);
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
    // The owner's record of the same process (a ledger child, a helper receipt): its own fields name
    // its creator, not the witness's Engine, so they are paired by the process alone.
    const ownerExit = (ownerRecordsByPid.get(w.pid) ?? []).find(c =>
      (c.engine === w.engine || ownedBy(c, w.engine)) && receiptCreator(w, c)
      && compareIdentity(c, w, 'native') && c.exit)?.exit;
    if (ownerExit) {
      // An owner record wins only when the independent code receipt confirms it.
      w.exit = sameCode(ownerExit.code, w.exit.code) ? { ...w.exit, ...ownerExit }
        : { ...w.exit, exitClass: 'UNKNOWN', reason: 'owner_witness_disagree' };
      if ((receiptsByPid.get(w.pid) ?? []).some(c => c.engine === w.engine && receiptCreator(w, c)
        && compareIdentity(c, w, 'native') && c.exit === ownerExit)) ownedHelpers.add(w);
    } else {
      const born = birthFileTime(w);
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
    const parser = children.find(c => c.kind === 'EXTRACTION' && ownedBy(c, w.engine) && createdBy(w, c) && c.exit);
    if (!parser || !['REQUESTED', 'UNEXPECTED'].includes(parser.exit.exitClass)) continue;
    if (parser.exit.exitClass === 'REQUESTED' && (!parser.exit.forced || !parser.exit.reason)) continue;
    const end = recordedEndMs(parser.exit), helperEnd = recordedEndMs(w.exit);
    if (end == null || helperEnd == null || helperEnd < end || helperEnd > end + CONHOST_EXIT_GRACE_MS) continue;
    const declares = (receiptsByPid.get(w.pid) ?? []).some(c => c.engine === w.engine
      && c.ownerContainment === 'KILL_ON_CLOSE' && createdBy(w, c.owner)
      && compareIdentity(c, w, 'native', { tick: TICK.EXACT }))
      || windowsFor(w).some(i => i.engine === w.engine && i.ownerContainment === 'KILL_ON_CLOSE'
        && createdBy(w, i.owner) && !i.closedAt
        && i.startFt != null && i.deadlineFt != null
        && birthFileTime(w) >= i.startFt
        && birthFileTime(w) <= i.deadlineFt);
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
      const parent = [...ownedHelpers].find(p => p.engine === w.engine && createdBy(w, p)
        && p.exit?.exitClass === 'REQUESTED' && p.exit.reason
        && (p.exit.exitedFileTime == null || birthFileTime(w) <= BigInt(p.exit.exitedFileTime)));
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
    const parent = (byPid.get(w.parentPid) ?? []).find(p => compareIdentity(identityOf({ pid: w.parentPid,
      creationFileTime: w.parentCreationFileTime ?? null }), p, 'same'));
    const end = recordedEndMs(parent?.exit), hostEnd = recordedEndMs(w.exit);
    const born = birthMs(w);
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
  return { engines, children, witnesses: valid, provenWitnesses, witnessStarts, reportIdentities, identityConflicts };

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
    const key = identityKey(row);
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
  const jobEndOf = (child) => {
    const owner = identityOf(child)?.owner;
    return child.contained === true && owner ? jobClosedByMs(view.engines.find(e => compareIdentity(owner, e, 'same'))) : null;
  };
  // The job end bounding each recorded process: its witness's Engine, else its own owner's when the
  // child records itself contained. PIDs only index; each match is compareIdentity's.
  const jobBound = byPid([...(view.witnesses ?? []).map(w => ({ item: w, end: jobClosedByMs(w.engine) })),
    ...view.children.map(c => ({ item: c, end: jobEndOf(c) }))], x => x.item);
  const jobEndFor = (x) => (jobBound.get(identityOf(x)?.pid) ?? []).find(b => compareIdentity(b.item, x, 'same'))?.end ?? null;
  const contradictsJob = (row, recorded) => {
    const end = jobEndFor(recorded);
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
    } else if (engine && hasPid(engine, run.pids?.backendRootPid) && hasPid(engine, manifest?.pid)) {
      targets.push({ ...engine, targetSource: 'spawn' });
      gaps.push({ role: 'engine', pid: engine.pid, reason: 'ledger-unavailable', family: 'engine' });
    } else gaps.push({ role: 'engine', pid: run.pids?.backendRootPid, reason: 'missing spawn-bound Engine identity or manifest PID mismatch' });
  }
  const engineTargets = targets.filter(t => t.role === 'engine');
  const knownChildIds = [...new Set(view.children.map(c => c.childId))];
  const knownChildren = view.children.map(c => `${c.childId}/${identityKey(c)}`);
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
      const owned = observed.filter(p => compareIdentity(p, { pid: child.pid, creationFileTimeUtc,
        creationTimeSource: 'B11.startInstant' }, 'same') && sameExe(exeOf(p), child.executable));
      const births = [...new Set(owned.map(birthOf))];
      if (births.length > 1) { gaps.push({ pid: child.pid, reason: 'multiple owned births in B11 startInstant precision; cannot attribute child' }); continue; }
      targets.push({ ...child, creationFileTimeUtc: births[0] ?? creationFileTimeUtc,
        creationTimeSource: births.length ? 'CIM' : 'B11.startInstant', targetSource: 'manifest',
        role: child.kind === 'LLAMA_SERVER' ? 'llama-server' : 'extraction-child' });
    }
  }
  // CH9: a console host belongs to its console parent (a target, born no later than the host at the
  // shared birth precision: an exact parent birth and the host's CIM birth can share a microsecond).
  const tick = TICK.MICROSECOND;
  // A known later holder supersedes an earlier parent identity at the child's birth, even if
  // that holder is outside this run. Observation is negative evidence, not an ownership grant.
  const observedByPid = byPid(observed);
  const superseded = (parent, row) => (observedByPid.get(identityOf(parent).pid) ?? [])
    .some(p => bornAfter(p, parent, tick) && bornNotAfter(p, row, tick));
  // A parent link names a PID only; the parent is the identity of that PID born no later than the row.
  const parentPidOf = (row) => row.ParentProcessId ?? row.parentPid;
  for (const row of observed) {
    if (!isConsoleHost(row)) continue;
    const birth = birthOf(row);
    const parent = targets.find(t => hasPid(t, parentPidOf(row)) && bornNotAfter(t, row, tick)
      && !superseded(t, row) && !contradictsJob(row, t));
    if (!parent) continue;
    // A console host's command line starts `\??\C:\...`; the held handle reports the System32 path.
    targets.push({ pid: pidOf(row), creationFileTimeUtc: birth,
      executable: path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'conhost.exe'),
      role: 'console-host', consoleParent: identityKey(parent), creationTimeSource: 'CIM' });
  }
  const unique = [...new Map(targets.map(t => [identityKey(t), { ...t, birthTick: String(birthTickOf(t, view)) }])).values()];
  // The Engine's own console predates its job and may never reach the sampler. Its native
  // client witness is sufficient to arm a read handle; it grants no right to terminate it.
  for (const w of view.witnesses ?? []) {
    if (!w.consoleClient || w.exit || w.engine.exit) continue;
    const parent = engineTargets.find(t => compareIdentity(t, w.engine, 'same'));
    if (!parent) continue;
    const target = { pid: w.pid, creationFileTimeUtc: w.creationFileTimeUtc, executable: w.executable,
      role: 'console-host', consoleClient: true, witnessId: w.witnessId, incarnation: w.incarnation,
      consoleParent: identityKey(parent), creationTimeSource: 'ledger', birthTick: String(TICK.EXACT) };
    const existing = unique.findIndex(t => compareIdentity(t, w, 'same'));
    if (existing < 0) unique.push(target);
    else unique[existing] = target;
  }
  // Processes whose exit is already in the ledger (closed before the stop): a closed child of a run
  // Engine, or a closed incarnation of it. Their console hosts and helpers are judged against the
  // lifetime the ledger records for them.
  const closed = [
    ...view.engines.filter(e => e.exit).map(e => ({ pid: e.pid, creationFileTimeUtc: e.creationFileTimeUtc, exit: e.exit,
      role: 'engine', contained: e.contained, identity: e.identity })),
    ...view.children.filter(c => c.exit).map(c => ({ pid: c.pid, creationFileTimeUtc: c.creationFileTimeUtc, exit: c.exit,
      role: roleOfKind(c.kind), contained: c.contained === true, identity: c.identity })),
  ];
  // An exact job witness can account any image, including nested helpers and their hosts.
  // Never use ancestry as a substitute for this identity-bound record.
  const witnessed = [];
  const rejectedWitnesses = new Set(), unprovenRows = new Set();
  for (const p of observed) {
    const w = (view.witnesses ?? []).find(w => compareIdentity(w, p, 'same'));
    if (!w || isTargetAlready(p)) continue;
    if (contradictsJob(p, w)) { rejectedWitnesses.add(p); continue; }
    const jobEnd = jobClosedByMs(w.engine);
    const bornMs = birthMs(w);
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
        creationTimeSource: 'ledger', birthTick: String(TICK.EXACT) });
    } else rejectedWitnesses.add(p);
  }
  function isTargetAlready(p) {
    return unique.some(t => compareIdentity(t, p, 'same'));
  }
  const closedOf = (row) => closed.find(c => compareIdentity(c, row, 'same'));
  // An observed row is a target at the coarser of the target's measured tick and its own (CIM's).
  const isTarget = (row) => unique.some(t => compareIdentity(t, row, 'same'));
  // A parent the row can belong to: same pid, born no later than the row at the shared birth
  // precision, and (when its exit is recorded) the row last seen within its recorded lifetime.
  const parents = [
    ...unique.filter(t => t.role !== 'console-host').map(t => ({ ...t, open: true })),
    ...closed.map(c => ({ ...c, open: false })),
  ];
  for (const parent of parents) {
    // A witnessed host may be the Engine's own console host. Its witness binds the job's
    // closure even when the Engine's exit time is unknown; ancestry alone supplies no bound.
    const ownerWitness = witnessed.find(x => compareIdentity(x.witness.engine, parent, 'same'));
    const jobEndMs = jobEndFor(parent) ?? (ownerWitness ? jobClosedByMs(ownerWitness.witness.engine) : null);
    if (jobEndMs != null) {
      const end = recordedEndMs(parent.exit);
      parent.closedByMs = end == null ? jobEndMs : Math.min(end, jobEndMs);
      parent.open = false;
    }
  }
  const parentOf = (row) => {
    const birth = birthOf(row), ppid = Number(parentPidOf(row));
    if (!isFileTime(birth)) return { parent: null, why: 'no birth' };
    const candidates = parents.filter(t => hasPid(t, ppid) && bornNotAfter(t, row, tick));
    if (!candidates.length) return { parent: null, why: 'parent is not a run Engine or one of its ledger children' };
    // A reused pid: the latest parent born no later than the row.
    const parent = candidates.sort((a, b) => (birthOrder(a, b, TICK.EXACT) < 0 ? -1 : 1)).at(-1);
    if (superseded(parent, row)) return { parent: null, why: `parent ${ppid} PID held by a newer observed identity at child birth` };
    if (parent.open) return { parent };
    // CH9's legacy console-parent attribution uses the confirmed-dead upper bound to reject
    // later sightings. It never compares a helper receipt's observation with the job's end,
    // nor reports that upper bound as the parent's or console host's exit time.
    const endMs = parent.closedByMs ?? closedByMs(parent.exit);
    if (endMs === null) return { parent: null, why: `parent ${parent.pid} exit time unknown in the ledger` };
    if (!Number.isFinite(row.lastSeenAtMs)) return { parent: null, why: 'observed time unknown' };
    const bornMs = birthMs(row);
    if (bornMs > endMs) return { parent: null, why: `born after its parent ${parent.pid}'s recorded exit` };
    if (row.lastSeenAtMs > endMs + CONHOST_EXIT_GRACE_MS) {
      return { parent: null, why: `seen ${row.lastSeenAtMs - endMs} ms after its parent ${parent.pid}'s recorded exit` };
    }
    return { parent };
  };
  const account = (row, role, parent, basis) => {
    accountedProcesses.push({ pid: pidOf(row), creationFileTimeUtc: birthOf(row), role, parent: identityKey(parent),
      basis, ledgerClass: parent.exit?.exitClass, ledgerReason: parent.exit?.reason,
      ...(Number.isFinite(row.lastSeenAtMs) ? { lastSeenAtMs: row.lastSeenAtMs } : {}) });
  };
  for (const host of unique.filter(t => t.witnessConsole)) {
    const row = observed.find(p => compareIdentity(p, host, 'same'));
    const { parent } = parentOf(row);
    if (parent) host.consoleParent = identityKey(parent);
  }
  const allLosses = [...(ledger?.children?.records ?? []), ...(ledger?.engine?.records ?? [])]
    .filter(r => r.type === 'ledger-gap' && r.line?.reason === 'EVIDENCE_LOST').map(r => r.line);
  const relevantLoss = loss => {
    if (loss.incarnation?.session) return loss.incarnation.session === run?.runId;
    if (loss.scopeComplete === true) return loss.affectedSessions?.includes(run?.runId) === true;
    if (observed.some(p => compareIdentity(p, loss, 'same'))) return true;
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
    const child = view.children.find(c => compareIdentity(c, p, 'same'));
    if (child && contradictsJob(p, child)) {
      gaps.push({ pid, creationFileTimeUtc: birth, role: roleOfKind(child.kind), reason: 'unaccounted_process',
        detail: 'observed alive after its owner job closed plus grace; contradicts recorded containment' });
      continue;
    }
    if (isTarget(p) || unprovenRows.has(p)) continue;
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
    const ended = closedOf(p);
    if (!ended) {
      // A loss names the identity it lost, or the range of births whose evidence it lost.
      const named = (l) => compareIdentity(l, p, 'same');
      const loss = losses.find(l => named(l) || (isFileTime(l.birthFrom) && isFileTime(l.birthThrough)
        && bornNotAfter(bornAt(l.birthFrom), p, TICK.EXACT) && bornNotAfter(p, bornAt(l.birthThrough), TICK.EXACT)));
      if (loss) {
        evidenceLost.push({ pid, creationFileTimeUtc: birth, evidenceClass: 'evidence-lost',
          droppedFile: loss.droppedFile ?? null, membership: hasPid(loss, pid) ? 'previously-recorded' : 'unknown' });
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
    if (loss.pid > 0 && isFileTime(loss.creationFileTime) && !observed.some(p => compareIdentity(p, loss, 'same'))) {
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
  // The target at the tick its birth was measured at (birthTickOf), never one it declares.
  const measured = { ...target, birthTick: String(birthTickOf(target, view)) };
  if (target.witnessId) return (view.witnesses ?? []).find(w => target.incarnation
    && compareIdentity(w, measured, 'same'))?.exit ?? null;
  if (target.role === 'engine') {
    // The start the target is: its PID and birth (at the coarser of the start's and the target's
    // measured precision), and its incarnation when the target names one. An incarnation alone never
    // picks a start: two starts can share one, and each has only its own exit. The start recorded with
    // the target's own birth is the one; failing that, only a single start that matches at the coarser
    // precision is (two that do are ambiguous, and an ambiguous match proves nothing).
    const matches = view.engines.filter(x => compareIdentity(x, measured, 'same'));
    const e = matches.find(x => namedBy(x, identityKey(target))) ?? (matches.length === 1 ? matches[0] : null);
    return e?.exit ?? null;
  }
  // A ledger child target names its childId; a manifest one (the ledger-unavailable fallback) does not.
  const c = view.children.find(x => compareIdentity(x, measured, 'same'));
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
  // A ledger that holds two different exit codes for one identity disagrees with itself: which of
  // them is the exit is not known, and no later record decides it.
  for (const item of [...view.engines, ...view.children]) {
    if (!item.conflictingCodes) continue;
    gaps.push({ pid: item.pid, creationFileTimeUtc: item.creationFileTimeUtc, role: item.childId ? roleOfKind(item.kind) : 'engine',
      reason: 'ledger_codes_disagree', codes: item.conflictingCodes,
      detail: `the ledger records exit codes ${item.conflictingCodes.join(' and ')} for one identity` });
  }
  // A start whose own record and later metadata (a child-spawn, a second child-start) disagree on its
  // owner or incarnation: which process the ledger's records are about is in doubt, and nothing proves it.
  for (const item of view.identityConflicts ?? []) {
    gaps.push({ pid: item.pid, creationFileTimeUtc: item.creationFileTimeUtc, role: roleOfKind(item.kind), childId: item.childId,
      reason: 'identity_metadata_conflict', conflicts: item.identity.conflicts.map(c => ({ field: c.field, detail: c.detail })),
      detail: `the ledger names child ${item.childId} with conflicting metadata: ${item.identity.conflicts.map(c => c.detail).join('; ')}` });
  }
  // The rows by PID (an index only), kept current as rows are added (rows are only ever appended).
  const rowsByPid = new Map();
  let indexed = 0;
  const rowsOf = (pid) => {
    for (; indexed < processExits.length; indexed++) {
      const row = processExits[indexed];
      const key = identityOf(row)?.pid ?? null;
      if (!rowsByPid.has(key)) rowsByPid.set(key, []);
      rowsByPid.get(key).push(row);
    }
    return rowsByPid.get(pid) ?? [];
  };
  const hasRow = (x) => rowsOf(identityOf(x)?.pid ?? null).some(p => compareIdentity(p, x, 'same'));
  // A gap names an identity (its PID and birth), or only a PID when it records no birth.
  const gapNames = (g, x) => (g.creationFileTimeUtc ? compareIdentity(g, x, 'same') : hasPid(g, identityOf(x)?.pid));
  // The observer echoes the target's birth as the key and reports the held handle's own birth
  // (`osCreationFileTimeUtc`), which must be the target's at the precision the target's birth was
  // measured at (birthTickOf: worked out from its source, never a tick the target declares).
  // The OS exits by the key they echo (an index only, in their order; each is then matched as before).
  let exitsByKey = null;
  const exitsNamed = (key) => {
    if (!exitsByKey) {
      exitsByKey = new Map();
      for (const e of output.processExits ?? []) {
        const k = identityKey(e);
        if (!exitsByKey.has(k)) exitsByKey.set(k, []);
        exitsByKey.get(k).push(e);
      }
    }
    return exitsByKey.get(key) ?? [];
  };
  const osExitOf = (target) => {
    if (!output.processExits) return undefined;
    const measured = { ...target, birthTick: String(birthTickOf(target, view)) };
    const key = identityKey(target);
    return exitsNamed(key).find(e => namedBy(e, key) && (e.osCreationFileTimeUtc == null
      || compareIdentity({ pid: e.pid, creationFileTimeUtc: String(e.osCreationFileTimeUtc), birthTick: String(TICK.EXACT) },
        measured, 'native')));
  };
  const accounted = new Map();
  for (const target of input.targets.filter(t => t.role !== 'console-host')) {
    const exit = osExitOf(target);
    if (!exit || !Number.isInteger(exit.exitCode)) {
      gaps.push({ pid: target.pid, role: target.role, reason: 'identity-bound OS exit code/time unavailable through teardown' }); continue;
    }
    const atMs = knownExitMs(exit.atMs);
    const base = { ...exit, atMs, exitTimeSource: atMs === null ? 'unknown' : (exit.exitTimeSource ?? 'os'), role: target.role, source };
    const record = ledgerExitFor(target, view);
    const witness = target.witnessId ? (view.witnesses ?? []).find(w => compareIdentity(w, target, 'same')) : null;
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
    const parent = [...accounted.entries()].find(([t]) => namedBy(t, host.consoleParent))?.[1];
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
  const lateTargets = view.children.filter(c => (knownChildren ? !knownChildren.has(`${c.childId}/${identityKey(c)}`)
    : !known.has(c.childId)) && !input.targets.some(t => t.childId != null && compareIdentity(t, c, 'same')))
    .map(c => ({ childId: c.childId, pid: c.pid,
    creationFileTimeUtc: c.creationFileTimeUtc, role: roleOfKind(c.kind), ledgerClass: c.exit?.exitClass ?? null,
    code: c.exit?.code ?? null }));
  for (const w of view.witnesses ?? []) {
    // A witnessed process the ledger also records as an Engine or child, or that is a target or
    // accounted already: the same process, whatever relation each record names.
    if (view.engines.some(e => compareIdentity(e, w, 'native'))
      || view.children.some(c => c.identity && compareIdentity(c, w, 'native'))
      || input.targets.some(t => compareIdentity(t, w, 'native'))
      || input.accountedProcesses?.some(p => compareIdentity(p, w, 'native'))) continue;
    const end = jobClosedByMs(w.engine);
    const born = birthMs(w);
    const receiptEnd = recordedEndMs(w.exit);
    const outsideLifetime = end != null && (born > end || (receiptEnd != null && receiptEnd > end + CONHOST_EXIT_GRACE_MS));
    lateTargets.push({ witnessId: w.witnessId, pid: w.pid, creationFileTimeUtc: w.creationFileTimeUtc,
      role: 'job-helper', ledgerClass: outsideLifetime ? null : w.exit?.exitClass ?? null, code: w.exit?.code ?? null });
  }
  // The ledger is an inventory, including unsampled rapid processes and crash casualties.
  // Every identity has a row even when no observer could retain its final native receipt.
  const lateByPid = byPid(lateTargets);
  // The gaps that deny an identity its record, in their order, kept current as gaps are added (gaps
  // are only ever appended): each item asks only these, never every gap.
  const DENYING = new Set(['unaccounted_process', 'orphan_console_host']);
  const denying = [];
  let scanned = 0;
  const denyingGaps = () => {
    for (; scanned < gaps.length; scanned++) if (DENYING.has(gaps[scanned].reason)) denying.push(gaps[scanned]);
    return denying;
  };
  for (const item of [...view.engines, ...view.children, ...(view.witnesses ?? [])]) {
    if (!item.pid || !isFileTime(item.creationFileTimeUtc) || hasRow(item)) continue;
    const role = item.witnessId ? (CONSOLE_HOST_RE.test(item.executable ?? '') ? 'console-host' : 'job-helper')
      : item.childId ? roleOfKind(item.kind) : 'engine';
    const outside = (lateByPid.get(identityOf(item).pid) ?? []).some(t => compareIdentity(t, item, 'same')
      && t.ledgerClass == null && item.exit);
    const denied = denyingGaps().find(g => gapNames(g, item));
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
    if (!gap.pid || !gap.creationFileTimeUtc || hasRow(gap)) continue;
    processExits.push({ pid: gap.pid, creationFileTimeUtc: gap.creationFileTimeUtc, role: gap.role ?? 'unknown',
      ledgerClass: 'UNKNOWN', ledgerReason: gap.reason, atMs: null, exitTimeSource: 'unknown',
      expectedTermination: false, accounted: false, source });
  }
  for (const p of [...(input.accountedProcesses ?? []), ...(input.observedIdentities ?? []), ...input.targets,
    ...(view.reportIdentities ?? [])]) {
    if (hasRow(p)) continue;
    let gap = gaps.find(g => gapNames(g, p));
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
      const host = consoleRowsOf(w.pid).find(p => compareIdentity(p, w, 'same'));
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
    c.identity && compareIdentity(c, w, 'native')));
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
    && !hasPid(w, w.engine.pid) && !view.children.some(c => c.identity && compareIdentity(c, w, 'native')));
  if (!open.length) return [];
  let identities;
  // The OS row a lookup returned for a witnessed PID, as an identity (the PID is the one looked up).
  const osRow = (w) => {
    const seen = identities?.get(w.pid);
    return seen ? { present: seen.present, pid: w.pid, creationFileTime: seen.creationFileTime ?? null } : null;
  };
  const stillThere = (w) => { const row = osRow(w); return Boolean(row?.present) && compareIdentity(row, w, 'native'); };
  try {
    identities = await lookup(open.map(w => w.pid));
    // Job closure can trail the Engine's own exit receipt. Observe that transition before
    // deciding which original identities the surviving supervisor must reconcile.
    for (let attempt = 0; attempt < 20 && open.some(stillThere); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 50));
      identities = await lookup(open.map(w => w.pid));
    }
  } catch { identities = null; }
  const outcomes = [];
  for (const w of open) {
    const seen = osRow(w);
    const gone = seen && (seen.present === false || (isFileTime(seen.creationFileTime) && !compareIdentity(seen, w, 'native')));
    if (stillThere(w)) continue;
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
 * Every start of the run the ledger records, each with its identity value and what proves its end
 * (`proof`, or null): its Engine incarnations, its children's starts, and the processes its job
 * witnesses recorded.
 */
function ledgerStartsOf(view) {
  return [...(view.engines ?? []).map(e => ({ item: e, role: 'engine' })),
    ...(view.children ?? []).filter(c => c.identity).map(c => ({ item: c, role: roleOfKind(c.kind) })),
    ...(view.witnessStarts ?? view.provenWitnesses ?? []).map(w => ({ item: w,
      role: CONSOLE_HOST_RE.test(w.executable ?? '') ? 'console-host' : 'job-helper' }))]
    .filter(({ item }) => item.identity || identityOf(item));
}

/**
 * The identities the ledger proves have ended (proofOfRecord, the witness proofs in ledgerView): each
 * Engine incarnation, ledger child and job-witnessed process of the run whose records prove its end,
 * as its whole identity value (PID, birth and the tick it was measured at, childId, witnessId, owner,
 * incarnation). A process another start of the run names and nothing proves ended (the same PID and
 * birth under another childId, say) is not one: the PID and birth alone cannot say which of them
 * ended. Nothing else is one either: a process alive under such a PID whose birth cannot be read is
 * never decided here.
 */
function endedIdentitiesOf(view, starts = ledgerStartsOf(view)) {
  const ended = [];
  const startsByPid = byPid(starts, s => s.item), endedByPid = new Map();
  for (const { item, role } of starts) {
    const id = identityOf(item);
    if (!item.proof || id.pid === null || id.birth === null) continue;
    if ((startsByPid.get(id.pid) ?? []).some(o => !o.item.proof && compareIdentity(o.item, item, 'native'))) continue;
    if ((endedByPid.get(id.pid) ?? []).some(e => compareIdentity(e.identity, id, 'exact'))) continue;
    if (!endedByPid.has(id.pid)) endedByPid.set(id.pid, []);
    endedByPid.get(id.pid).push({ identity: id });
    ended.push({ pid: id.pid, creationFileTimeUtc: id.birth, role, birthTick: id.tick, ledgerType: item.proof.type,
      proof: item.proof.by, exitedAtMs: item.exit?.exitedAtMs ?? null,
      ...(id.childId ? { childId: id.childId } : {}), ...(id.witnessId ? { witnessId: id.witnessId } : {}),
      ...(item.owner ? { owner: item.owner } : {}), ...(id.incarnation ? { incarnation: id.incarnation } : {}),
      identity: id });
  }
  return ended;
}

/**
 * What the ledger says of the identities a stop tracks (the runner's lookup suppression and job stop).
 * `ended` is endedIdentitiesOf. `isEnded(tracked)` is true only when at least one start of the run is
 * the tracked identity (compareIdentity 'same': its PID and birth at the coarser of their ticks, and
 * every other field both carry, the childId, owner and incarnation included) and every such start is
 * proven ended; a tracked record no start agrees with, or one an unended start also agrees with, is
 * looked up.
 */
function ledgerResolution(view) {
  const starts = ledgerStartsOf(view);
  const index = byPid(starts, s => s.item);
  return {
    ended: endedIdentitiesOf(view, starts),
    isEnded(tracked) {
      const id = identityOf(tracked);
      if (!id || id.pid === null || id.birth === null) return false;
      const agreeing = (index.get(id.pid) ?? []).filter(s => compareIdentity(s.item, tracked, 'same'));
      return agreeing.length > 0 && agreeing.every(s => s.item.proof);
    },
  };
}

/** How long the verdict waits before reading a torn ledger line again. */
const TORN_TAIL_SETTLE_MS = 250;
const EARLIER_TARGETS_RE = /^exit-targets\.(\d+)\.json$/;
const EARLIER_EXITS_RE = /^process-exits\.(\d+)\.json$/;
/** One census record (a target, an OS exit) of an earlier census is this one's when it names the same key. */
const sameIdentity = (a, b) => Boolean(a && b) && namedBy(a, identityKey(b));
/** The keys of a list's records, for asking sameIdentity of each record against all of them at once. */
const keysOf = (list) => new Set((list ?? []).filter(Boolean).map(identityKey));
/** `x` names the same key as one of `keys` (keysOf): sameIdentity against every record of the list. */
const namedIn = (keys, x) => Boolean(x) && keys.has(identityKey(x));

/**
 * The findings of a stop that no later stop or record clears (the round-8 decision: carry by default).
 * Every gap a stop reports is a finding, carried into every later stop of the run, and accounting
 * stays incomplete until a person forgets the run, unless its kind only says an exit was not yet seen
 * (NOT_SEEN_YET): the retry looks again, and a later record or observation can account it. A torn last
 * line is not lost evidence either: its writer completes it, and a later read reads it whole. Any other
 * kind, a disagreement, lost or unreadable evidence, a contradicted containment, a conflict in the
 * ledger's metadata, and any kind added later, is carried forever.
 */
const NOT_SEEN_YET = new Set(['unrecorded_exit']);
function isFinding(gap) {
  if (!gap || typeof gap !== 'object') return false;
  if (gap.reason === 'ledger-damaged') return !gap.tornTail || (gap.lines ?? 0) > 1;
  return !NOT_SEEN_YET.has(gap.reason);
}
/** A gap without where it was carried from, with its keys sorted: two equal findings have one key. */
const findingKey = (gap) => JSON.stringify(Object.keys(gap).filter(k => k !== 'carriedFrom').sort().map(k => [k, gap[k]]));

/**
 * `result` (exitResult's) with the findings of earlier stops of the run carried forward
 * (`earlier`: `{ gap, from }`, `from` the run-directory file that held it). Each finding not already
 * among the result's gaps is added, marked `carriedFrom` (the file it was first read from), and
 * accounting is then not complete. Used for both the earlier census files and the earlier stop reports.
 */
function carryFindings(result, earlier) {
  const gaps = Array.isArray(result?.exitAccountingGaps) ? result.exitAccountingGaps : [];
  const have = new Set(gaps.map(findingKey));
  const carried = [];
  for (const { gap, from } of earlier ?? []) {
    if (!isFinding(gap)) continue;
    const key = findingKey(gap);
    if (have.has(key)) continue;
    have.add(key);
    carried.push({ ...gap, carriedFrom: gap.carriedFrom ?? from });
  }
  if (!carried.length) return result;
  return { ...result, exitAccountingGaps: [...gaps, ...carried], exitAccountingComplete: false };
}

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
  if (!names) return { files: [], fileStates: [], unreadable: [], targets: [], osExits: [], findings: [], inputFile, outputFile,
    notArchived: [] };
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
  const files = [], fileStates = [], targets = [], osExits = [], unreadable = [], findings = [];
  for (const [re, canonical, field, into] of [[EARLIER_TARGETS_RE, 'exit-targets.json', 'targets', targets],
    [EARLIER_EXITS_RE, 'process-exits.json', 'processExits', osExits]]) {
    // The numbered (archived) files, then the one that stayed where it was (the latest earlier census).
    const kept = [...byNumber(re).map(name => ({ name, archived: true })), ...(held.has(canonical) ? [{ name: canonical, archived: false }] : [])];
    const seen = keysOf(into);
    for (const { name, archived } of kept) {
      let readable = true;
      try {
        const kept = read(path.join(runDir, name));
        // A file that parses to anything but a census record (null, a list, a number) is not readable either.
        if (!kept || typeof kept !== 'object' || Array.isArray(kept)) {
          throw Object.assign(new Error('not a census file'), { code: 'NOT_A_CENSUS' });
        }
        for (const item of kept[field] ?? []) {
          if (namedIn(seen, item)) continue;
          into.push(item);
          if (item) seen.add(identityKey(item));
        }
        // What that census found that no later record clears (its verdict wrote them; carryFindings).
        for (const gap of Array.isArray(kept.findings) ? kept.findings : []) findings.push({ gap, from: name });
      } catch (err) {
        // Kept as it is; it adds nothing, and is named.
        readable = false;
        unreadable.push({ name, archived, error: `${err?.code ?? err?.name ?? 'error'}: ${err?.message ?? err}` });
      }
      files.push(name);
      fileStates.push({ name, readable, archived });
    }
  }
  if (!notArchived.length) return { files, fileStates, unreadable, targets, osExits, findings, inputFile, outputFile, notArchived };
  // An earlier census file stays where it was: this census goes under the next free number.
  const own = nextNumber();
  return { files, fileStates, unreadable, targets, osExits, findings, notArchived,
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
  const armingLedger = dataDir ? readLedger(dataDir) : null;
  // The arming view also gives each target's measured birth tick to the OS exits handed to
  // `beforeVerdict` (birthTickOf: a dev runner's Engine start is microsecond, not exact).
  const armingView = armingLedger ? ledgerView(armingLedger, run?.runId) : null;
  const input = exitTargets(run, manifest, observed, armingLedger, armingView);
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
    const observerKeys = keysOf(observerOutput.processExits);
    const output = earlier.files.length ? { ...observerOutput, processExits: [...(observerOutput.processExits ?? []),
      ...earlier.osExits.filter(e => !namedIn(observerKeys, e))] } : observerOutput;
    const targetKeys = keysOf(input.targets), exitKeys = keysOf(output.processExits);
    const armed = { ...input, targets: [...input.targets, ...earlier.targets.filter(t => !namedIn(targetKeys, t)
      && namedIn(exitKeys, t))] };
    if (beforeVerdict) await beforeVerdict(exitResult(armed, output, outputFile, null, armingView).processExits);
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
        || !armed.targets.some(t => compareIdentity(t, g, 'same')))],
      accountedProcesses: refreshed.accountedProcesses.filter(p => !armed.targets.some(t => compareIdentity(t, p, 'same'))) };
    // What an earlier census of the run found is carried forward: no later record clears it.
    const result = carryFindings(exitResult(finalInput, output, outputFile, finalLedger, finalView), earlier.findings);
    // An earlier census file that could not be renamed stays as it was; this census names where it went.
    const written = earlier.notArchived.length ? { earlierCensusNotArchived: earlier.notArchived,
      censusWrittenAs: [path.basename(inputFile), path.basename(outputFile)] } : {};
    // The census file keeps this verdict's findings beside its OS exits, so a later census carries
    // them even when the ledger has changed since (a later receipt never clears a disagreement).
    const findings = result.exitAccountingGaps.filter(isFinding);
    if (!earlier.files.length) {
      if (findings.length && fs.existsSync(outputFile)) {
        try { fs.writeFileSync(outputFile, JSON.stringify({ ...observerOutput, findings })); } catch { /* the report holds them too */ }
      }
      return { ...result, ...written };
    }
    // The census file holds every OS exit observed for the run, the earlier ones included.
    try {
      fs.writeFileSync(outputFile, JSON.stringify({ ...output, ...(findings.length ? { findings } : {}),
        earlierCensus: earlier.files, earlierCensusFiles: earlier.fileStates }));
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
module.exports = { CONHOST_EXIT_GRACE_MS, NOT_SEEN_YET, fileTimeFromInstant, isFinding, carryFindings,
  engineIdentity, engineIdentityAsync, readRunLedger, ledgerView, observedProcesses, exitTargets, exitResult,
  endedIdentitiesOf, ledgerResolution, reconcileCrashedHelpers, startStopExitCensus };
