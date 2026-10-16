import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import census from './lib/stop-exit-census.cjs';

// Task b6d536 b101: a helper receipt names a witnessed process only on native identity. The
// member's native creator wins over the creator a receipt declares (a conflict is no match), a
// receipt's birth and the witness's, both native, match exactly, and the Engine's end cuts off a
// witnessed member at native 100 ns precision.
const at = n => `2026-10-14T10:00:${String(n).padStart(2, '0')}.000Z`;
const birth = n => census.fileTimeFromInstant(at(n));
const incarnation = { session: 'census-identity', n: 1 };
const engine = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
const parser = { pid: 202, creationFileTime: birth(2), instanceId: 'parser' };
const rec = (type, line, pid = 100) => ({ type, line, writer: { role: 'engine', pid } });
// Synthetic records use the host's absolute-path spelling and retain each image's identity.
const fixtureExecutable = image => path.resolve('census-identity-fixture', image);

/** The extraction child's receipt for Tesseract 303 and the Engine job witness's record of it. */
function receiptLedger({ creator = parser, helperBirth = birth(4), receiptBirth = birth(4), withCreatorFields = true } = {}) {
  const receipt = { helperId: 'helper', owner: parser, incarnation, pid: 303, creationFileTime: receiptBirth };
  if (withCreatorFields) Object.assign(receipt, { creatorPid: parser.pid, creatorCreationFileTime: parser.creationFileTime });
  return {
    engine: { present: true, records: [rec('engine-start', { incarnation, pid: 100, creationFileTime: birth(0), executable: fixtureExecutable('java.exe') })] },
    children: { present: true, records: [
      rec('child-spawn', { childId: 'parser', owner: engine, kind: 'EXTRACTION' }),
      rec('child-start', { childId: 'parser', pid: 202, creationFileTime: birth(2), executable: fixtureExecutable('java.exe') }),
      rec('helper-start', receipt, 202),
      rec('helper-exit', { ...receipt, code: 0, exitClass: 'REQUESTED', reason: 'receipt reason', forced: false,
        exitedAt: at(5), observedAt: at(5) }, 202),
      rec('job-process-start', { witnessId: 'member', owner: engine, incarnation, pid: 303, creationFileTime: helperBirth,
        executable: fixtureExecutable('tesseract.exe'), contained: true, parentPid: creator.pid,
        parentCreationFileTime: creator.creationFileTime, awaited: false }),
      rec('job-process-exit', { witnessId: 'member', owner: engine, incarnation, pid: 303, creationFileTime: helperBirth,
        code: 0, exitClass: 'UNEXPECTED', reason: 'no recorded intent', forced: false, exitedAt: at(5), observedAt: at(5) }),
    ] },
  };
}
const member = data => census.ledgerView(data, incarnation.session).witnesses.find(w => w.pid === 303)?.exit;

test('preservation: a receipt with the member\'s exact birth and native creator names it', () => {
  assert.equal(member(receiptLedger())?.exitClass, 'REQUESTED');
});

test('a receipt\'s declared creator does not override a conflicting native creator', () => {
  const exit = member(receiptLedger({ creator: engine }));
  assert.equal(exit?.exitClass, 'UNEXPECTED', JSON.stringify(exit));
});

test('a receipt birth 100 ns from the native birth does not name the member', () => {
  const exit = member(receiptLedger({ receiptBirth: String(BigInt(birth(4)) + 1n) }));
  assert.equal(exit?.exitClass, 'UNEXPECTED', JSON.stringify(exit));
});

test('preservation: a conflicting native creator with no declared creator is refused', () => {
  assert.equal(member(receiptLedger({ creator: engine, withCreatorFields: false }))?.exitClass, 'UNEXPECTED');
});

// The Engine exits at 05.1234567; a witnessed helper counts only if born by then and exited
// within the 2 s grace after it, compared at native 100 ns precision.
const start = '2026-10-14T10:00:00.0000000Z';
const engineEnd = '2026-10-14T10:00:05.1234567Z';
const owner = { pid: 100, creationFileTime: census.fileTimeFromInstant(start), instanceId: 'engine' };
function cutoffLedger(born, exited) {
  const ft = t => census.fileTimeFromInstant(t);
  // The Engine writes as itself, naming its start instant: an exit proves its start only from a
  // writer bound to it (b102), so the Engine's end is the cutoff.
  const r = (type, line) => ({ type, line, writer: { role: 'engine', pid: 100, startedAt: start } });
  const helper = { helperId: 'h', owner, incarnation, pid: 303, creationFileTime: ft(born), creatorPid: 100,
    creatorCreationFileTime: ft(start) };
  return {
    engine: { present: true, records: [
      r('engine-start', { incarnation, pid: 100, creationFileTime: ft(start), executable: fixtureExecutable('java.exe') }),
      r('engine-exit', { incarnation, code: 0, exitClass: 'REQUESTED', reason: 'QUIT', exitedAt: engineEnd, observedAt: engineEnd }),
    ] },
    children: { present: true, records: [
      r('helper-start', helper),
      r('helper-exit', { ...helper, code: 0, exitClass: 'REQUESTED', reason: 'awaited completion', exitedAt: exited,
        observedAt: exited, forced: false }),
      r('job-process-start', { witnessId: 'j', owner, incarnation, pid: 303, creationFileTime: ft(born),
        executable: fixtureExecutable('tesseract.exe'), contained: true, parentPid: 100, parentCreationFileTime: ft(start), awaited: false }),
      r('job-process-exit', { witnessId: 'j', owner, incarnation, pid: 303, creationFileTime: ft(born), code: 0,
        exitClass: 'UNEXPECTED', reason: 'no recorded intent', exitedAt: exited, observedAt: exited, forced: false }),
    ] },
  };
}
function accounted(born, exited) {
  const ft = census.fileTimeFromInstant(born);
  const result = census.exitTargets({ runId: incarnation.session, startedAt: start, pids: { backendRootPid: 100 } }, null,
    [{ pid: 303, creationFileTimeUtc: ft, executable: fixtureExecutable('tesseract.exe'), parentPid: 100, lastSeenAtMs: Date.parse(born) }],
    cutoffLedger(born, exited));
  return result.accountedProcesses.some(p => p.pid === 303 && p.ledgerClass === 'REQUESTED');
}

test('preservation: a member born 100 ns before the Engine\'s end is accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:05.1234566Z', '2026-10-14T10:00:05.1234567Z'), true);
});

test('a member born 100 ns after the Engine\'s end is not accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:05.1234568Z', '2026-10-14T10:00:05.1234569Z'), false);
});

test('preservation: a member exiting 100 ns before the Engine\'s grace cutoff is accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:04.0000000Z', '2026-10-14T10:00:07.1234566Z'), true);
});

test('a member exiting 100 ns after the Engine\'s grace cutoff is not accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:04.0000000Z', '2026-10-14T10:00:07.1234568Z'), false);
});

// The same cutoff for a member no sampler saw: the verdict's inventory of late targets (exitResult)
// judges it at native 100 ns precision too (task b6d536, the merge with b102).
function lateAccepted(born, exited) {
  const ledger = cutoffLedger(born, exited);
  const run = { runId: incarnation.session, startedAt: start, pids: { backendRootPid: 100 } };
  const result = census.exitResult(census.exitTargets(run, null, [], ledger), { processExits: [], gaps: [] }, 'test', ledger);
  return result.processExits.find(p => p.pid === 303)?.ledgerClass === 'REQUESTED';
}

test('preservation: an unsampled member born 100 ns before the Engine\'s end keeps its receipt', () => {
  assert.equal(lateAccepted('2026-10-14T10:00:05.1234566Z', '2026-10-14T10:00:05.1234567Z'), true);
});

test('an unsampled member born 100 ns after the Engine\'s end does not keep its receipt', () => {
  assert.equal(lateAccepted('2026-10-14T10:00:05.1234568Z', '2026-10-14T10:00:05.1234569Z'), false);
});

test('preservation: an unsampled member exiting 100 ns before the Engine\'s grace cutoff keeps its receipt', () => {
  assert.equal(lateAccepted('2026-10-14T10:00:04.0000000Z', '2026-10-14T10:00:07.1234566Z'), true);
});

test('an unsampled member exiting 100 ns after the Engine\'s grace cutoff does not keep its receipt', () => {
  assert.equal(lateAccepted('2026-10-14T10:00:04.0000000Z', '2026-10-14T10:00:07.1234568Z'), false);
});

// A console host takes its parent's class only when the parent it read natively is that process to
// the tick: the parser's exact birth (task b6d536, the merge with b102).
function consoleHost(parentOffset) {
  const data = {
    engine: { present: true, records: [rec('engine-start', { incarnation, pid: 100, creationFileTime: birth(0), executable: fixtureExecutable('java.exe') })] },
    children: { present: true, records: [
      rec('child-spawn', { childId: 'parser', owner: engine, kind: 'EXTRACTION' }),
      rec('child-start', { childId: 'parser', pid: 202, creationFileTime: birth(2), executable: fixtureExecutable('java.exe'), contained: true }),
      rec('child-exit', { childId: 'parser', owner: engine, pid: 202, creationFileTime: birth(2), code: 1246953473,
        exitClass: 'REQUESTED', reason: 'QUIT', forced: true, exitedAt: at(6), observedAt: at(6) }),
      rec('job-process-start', { witnessId: 'console', owner: engine, incarnation, pid: 303, creationFileTime: birth(3),
        executable: fixtureExecutable('conhost.exe'), contained: true, parentPid: 202,
        parentCreationFileTime: String(BigInt(birth(2)) + BigInt(parentOffset)), awaited: false }),
      rec('job-process-exit', { witnessId: 'console', owner: engine, incarnation, pid: 303, creationFileTime: birth(3),
        code: 0, exitClass: 'UNEXPECTED', reason: 'no recorded intent', forced: false, exitedAt: at(7), observedAt: at(7) }),
    ] },
  };
  return member(data)?.exitClass;
}

test('preservation: a console host whose native parent is the parser exactly takes its class', () => {
  assert.equal(consoleHost(0), 'REQUESTED');
});

test('a console host whose native parent birth is 100 ns from the parser\'s keeps its own class', () => {
  assert.equal(consoleHost(1), 'UNEXPECTED');
});

test('preservation: a console host whose native parent birth is 1 us from the parser\'s keeps its own class', () => {
  assert.equal(consoleHost(10), 'UNEXPECTED');
});

// Task b6d536 round 12 (option A, by exact identity): a real Engine's job witness records every
// member of its job, the Engine itself included. A member record naming the same PID and birth as
// the bound, proven Engine start that owns it is that start's process and is proven by that start's
// proof (compareIdentity 'same': to the tick where both births are native, else at the tick the
// start was measured at); both records are kept and reported. A member with another birth, or none,
// shares nothing: it is its own start, and only the stop's lookup taken after it acted decides it.
const selfInc = { session: 'census-self-member', n: 1 };
const selfAt = n => `2026-10-15T10:00:${String(n).padStart(2, '0')}.000Z`;
// The Engine's native birth, with digits below the microsecond; a dev runner reads it through CIM.
const engineNative = String(BigInt(census.fileTimeFromInstant(selfAt(0))) + 1234567n);
const cimOf = ft => String(BigInt(ft) / 10n * 10n);
const selfOwner = { pid: 100, creationFileTime: engineNative, instanceId: 'engine' };
const SELF_EXE = fixtureExecutable('java.exe');
const DEV_RUNNER = { role: 'dev-runner', pid: 4000, startedAt: '2026-10-15T09:59:00.000Z' };
const SHELL = { role: 'shell', pid: 4000, startedAt: '2026-10-15T09:59:00.000Z' };
// The Engine's own writer names its start to the millisecond.
const ENGINE_WRITER = { role: 'engine', pid: 100, startedAt: '2026-10-15T10:00:00.123Z' };
const selfRec = (type, line, writer) => ({ type, line, writer });

/** A clean quit of the Engine (start and exit by one supervisor writer); its job witness recorded `members`. */
function selfLedger({ supervisor = DEV_RUNNER, members = [{ witnessId: 'self', birth: engineNative }] } = {}) {
  const startBirth = supervisor.role === 'dev-runner' ? cimOf(engineNative) : engineNative;
  return {
    engine: { present: true, records: [
      selfRec('engine-start', { incarnation: selfInc, pid: 100, creationFileTime: startBirth, executable: SELF_EXE }, supervisor),
      selfRec('engine-exit', { incarnation: selfInc, exitClass: 'REQUESTED', reason: 'QUIT', code: 0, exitedAt: selfAt(20) }, supervisor)] },
    children: { present: true, records: members.map(m => selfRec('job-process-start', { witnessId: m.witnessId, owner: selfOwner,
      incarnation: selfInc, pid: 100, ...(m.birth ? { creationFileTime: m.birth } : {}), executable: SELF_EXE, contained: true,
      parentPid: 4000, parentCreationFileTime: census.fileTimeFromInstant('2026-10-15T09:59:00.000Z'), observedAt: selfAt(1),
      awaited: false, expectedCode: null, reason: null }, ENGINE_WRITER)) },
  };
}
/** The stop's census of that ledger: the Engine's OS exit observed, and what its lookups observed. */
function selfCensus(ledger, { lookupEnded = [] } = {}) {
  const run = { runId: selfInc.session, pids: { backendRootPid: 100 },
    identities: { backend: { pid: 100, creationFileTimeUtc: cimOf(engineNative) } } };
  const arming = { engine: { present: true, records: ledger.engine.records.slice(0, 1) }, children: ledger.children };
  const input = census.exitTargets(run, null, [{ pid: 100, parentPid: 4000, creationFileTimeUtc: engineNative, name: 'java.exe',
    executable: SELF_EXE, lastSeenAtMs: Date.parse(selfAt(5)) }], arming);
  const processExits = input.targets.map(t => ({ pid: t.pid, creationFileTimeUtc: t.creationFileTimeUtc,
    executable: t.executable, exitCode: 0, atMs: Date.parse(selfAt(20)) }));
  return census.exitResult(input, { processExits }, 'census-identity', ledger, null, { lookupEnded });
}
const resolutionOf = ledger => census.ledgerResolution(census.ledgerView(ledger, selfInc.session));
/** The lookup of the Engine's recorded identity at `n` seconds: its PID absent, or naming `birth`. */
const engineLookup = (n, birth = null) => census.lookupObservation({ pid: 100, creationFileTimeUtc: cimOf(engineNative), role: 'backend' },
  birth ? { present: true, creationFileTime: birth, birthTick: '1' } : { present: false }, Date.parse(selfAt(n)));

test('a clean quit whose job witness recorded the Engine itself is complete: the member shares the Engine start\'s proof, both records kept', () => {
  const ledger = selfLedger();
  const result = selfCensus(ledger);
  assert.equal(result.exitAccountingComplete, true, JSON.stringify(result.exitAccountingGaps));
  const member = result.processExits.find(p => p.witnessId === 'self');
  assert.equal(member?.ledgerClass, 'REQUESTED', JSON.stringify(member));
  const res = resolutionOf(ledger);
  assert.deepEqual(res.unproven, [], 'nothing of the run is left to look up');
  assert.deepEqual(res.ended.map(e => [e.pid, e.role, e.witnessId ?? null]).sort(),
    [[100, 'engine', null], [100, 'job-helper', 'self']], 'the Engine start and its member record, each reported, not merged');
  assert.equal(res.isEnded({ pid: 100, creationFileTimeUtc: cimOf(engineNative) }), true, 'the tracked Engine is ended');
});

test('preservation: a member naming the Engine\'s PID with another birth is not proven by the Engine\'s start', () => {
  for (const [label, supervisor, birth] of [
    ['3 ms later, under a dev runner start read through CIM', DEV_RUNNER, String(BigInt(engineNative) + 30000n)],
    ['100 ns off, under a shell start read natively', SHELL, String(BigInt(engineNative) + 1n)]]) {
    const ledger = selfLedger({ supervisor, members: [{ witnessId: 'other', birth }] });
    const result = selfCensus(ledger);
    assert.equal(result.exitAccountingComplete, false, `${label}: ${JSON.stringify(result.exitAccountingGaps)}`);
    assert.ok(result.exitAccountingGaps.some(g => g.pid === 100 && g.creationFileTimeUtc === birth), `${label}: the member's own gap`);
    const res = resolutionOf(ledger);
    assert.deepEqual(res.unproven.map(u => [u.pid, u.witnessId, u.creationFileTimeUtc]), [[100, 'other', birth]], `${label}: looked up`);
    assert.ok(!res.ended.some(e => e.witnessId === 'other'), `${label}: not among the proven`);
  }
});

test('a member recorded without a birth shares nothing with the Engine\'s start: the stop looks its PID up, and only a lookup finding the PID gone, or naming a process born after the record saw it, accounts it', () => {
  const ledger = selfLedger({ members: [{ witnessId: 'unborn', birth: null }] });
  const member = r => r.processExits.find(p => p.pid === 100 && !p.creationFileTimeUtc);
  const res = resolutionOf(ledger);
  assert.ok(!res.ended.some(e => e.witnessId === 'unborn'), 'not proven by the Engine\'s start');
  const asked = res.unproven.find(u => u.pid === 100 && u.witnessId === 'unborn');
  assert.ok(asked && !asked.creationFileTimeUtc && asked.bornNotAfterMs === Date.parse(selfAt(1)),
    `the stop looks up its PID, seen alive at its record: ${JSON.stringify(res.unproven)}`);
  // The stop's lookup of that PID (lookupObservation), at `n` seconds.
  const lookup = (n, row) => [census.lookupObservation(asked, row, Date.parse(selfAt(n)))].filter(Boolean);
  for (const [label, lookupEnded] of [['no lookup', []],
    ['the PID naming the Engine, born before the record saw its process', lookup(2, { present: true, creationFileTime: engineNative, birthTick: '1' })],
    ['the PID absent before the record saw its process', lookup(0, { present: false })]]) {
    const result = selfCensus(ledger, { lookupEnded });
    assert.equal(result.exitAccountingComplete, false, `${label}: ${JSON.stringify(result.exitAccountingGaps)}`);
    assert.notEqual(member(result)?.accounted, true, label);
  }
  for (const [label, lookupEnded] of [['the PID absent', lookup(30, { present: false })],
    ['the PID naming a process born after the record saw its process', lookup(3, { present: true,
      creationFileTime: census.fileTimeFromInstant(selfAt(2)), birthTick: '1' })]]) {
    const result = selfCensus(ledger, { lookupEnded });
    assert.equal(result.exitAccountingComplete, true, `${label}: ${JSON.stringify(result.exitAccountingGaps)}`);
    assert.equal(member(result)?.accounted, true, `${label}: ${JSON.stringify(member(result))}`);
    assert.equal(member(result)?.ledgerReason, census.LOOKUP_ENDED_REASON, `${label}: accounted by the lookup, not by the Engine's proof`);
  }
});

test('preservation: a live member naming the Engine\'s PID with another birth leaves the stop incomplete', () => {
  const live = String(BigInt(engineNative) + 5000000n);
  const ledger = selfLedger({ members: [{ witnessId: 'live', birth: live }] });
  // After the stop acted, the PID names the member's own birth: the Engine ended, the member runs.
  const result = selfCensus(ledger, { lookupEnded: [engineLookup(90, live)] });
  assert.equal(result.exitAccountingComplete, false, JSON.stringify(result.exitAccountingGaps));
  assert.ok(result.exitAccountingGaps.some(g => g.pid === 100 && g.creationFileTimeUtc === live), JSON.stringify(result.exitAccountingGaps));
  assert.ok(!resolutionOf(ledger).ended.some(e => e.witnessId === 'live'), 'not proven by the Engine\'s start');
});
