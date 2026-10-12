import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const census = require(path.resolve(process.env.CENSUS_TEST_TREE ?? process.cwd(), 'scripts/dev/lib/stop-exit-census.cjs'));
const ledger = require(path.resolve(process.env.CENSUS_TEST_TREE ?? process.cwd(), 'scripts/dev/lib/exit-ledger.cjs'));
const at = n => new Date(Date.UTC(2026, 9, 8, 10, 0, n)).toISOString();
const birth = n => census.fileTimeFromInstant(at(n));
const incarnation = { session: 'shim-console', n: 1 };
const owner = { pid: 100, creationFileTime: birth(0), instanceId: 'owner' };
const rec = (type, line) => ({ type, line, writer: { role: 'engine', pid: 100 } });
const start = (pid, born, parent, parentBorn, image = 'wrapper.exe') => rec('job-process-start', {
  witnessId: `j-${pid}`, owner, incarnation, pid, creationFileTime: birth(born),
  parentPid: parent, parentCreationFileTime: birth(parentBorn), executable: `C:\\test\\${image}`, contained: true });
const exit = (pid, born, end, code = 0) => rec('job-process-exit', { witnessId: `j-${pid}`, owner,
  incarnation, pid, creationFileTime: birth(born), exitedAt: at(end), observedAt: at(end),
  exitClass: 'UNEXPECTED', code, forced: false });
function fixture() {
  return { engine: { present: true, records: [rec('engine-start', { incarnation, pid: 100,
    creationFileTime: birth(0), executable: 'C:\\test\\java.exe' })] }, children: { present: true, records: [
    // Reverse child/parent order is deliberate.
    start(102, 2, 101, 1, 'child.exe'), exit(102, 2, 3), start(101, 1, 100, 0), exit(101, 1, 4),
    rec('helper-start', { helperId: 'h-1', owner, incarnation, pid: 101, creationFileTime: birth(1),
      creatorPid: 100, creatorCreationFileTime: birth(0) }),
    rec('helper-exit', { helperId: 'h-1', owner, incarnation, pid: 101, creationFileTime: birth(1),
      creatorPid: 100, creatorCreationFileTime: birth(0), code: 0, exitClass: 'REQUESTED',
      reason: 'owned launcher: awaited completion', exitedAt: at(4) })] } };
}
const result = data => census.ledgerView(data, incarnation.session).witnesses.find(w => w.pid === 102).exit;
test('a direct child inherits its exact owned launcher receipt, independent of record order', () => {
  assert.equal(result(fixture()).exitClass, 'REQUESTED');
  assert.equal(result(fixture()).reason, 'owned launcher: awaited completion');
});
test('launcher inheritance rejects siblings, reused creators, impossible births and orphan ancestry', () => {
  for (const mutate of [
    d => { d.children.records[0].line.parentPid = 100; },
    d => { d.children.records[0].line.parentCreationFileTime = birth(0); },
    d => { d.children.records[0].line.creationFileTime = birth(0); d.children.records[1].line.creationFileTime = birth(0); },
    d => { d.children.records[2].line.parentPid = 999; d.children.records[4].line.creatorPid = 999; },
    d => { d.children.records[3].line.exitedAt = at(1); d.children.records[5].line.exitedAt = at(1); },
    d => { d.children.records.splice(4); },
  ]) { const data = fixture(); mutate(data); assert.equal(result(data).exitClass, 'UNEXPECTED'); }
});
test('a parser window cannot credit an orphan grandchild without an owned helper between', () => {
  const data = fixture(); data.children.records.splice(4);
  data.children.records.push(rec('helper-window-start', { owner, incarnation, intentId: 'window',
    image: 'child.exe', reason: 'availability', requestedAt: at(0), deadlineAt: at(5) }),
  rec('helper-window-end', { owner, incarnation, intentId: 'window', image: 'child.exe',
    reason: 'availability', closedAt: at(5) }));
  assert.equal(result(data).exitClass, 'UNEXPECTED');
});
test('an actual child crash during launcher intent remains unexpected', () => {
  const data = fixture(); data.children.records[1].line.code = -1073741819;
  assert.equal(result(data).exitClass, 'UNEXPECTED');
});
test('a self-contained owner receipt survives a missing helper-start but no identity can be borrowed', () => {
  const data = fixture(); data.children.records.splice(4, 1);
  assert.equal(census.ledgerView(data, incarnation.session).witnesses.find(w => w.pid === 101).exit.exitClass, 'REQUESTED');
  data.children.records.at(-1).line.creatorCreationFileTime = birth(5);
  data.children.records[2].line.parentCreationFileTime = birth(5);
  assert.equal(census.ledgerView(data, incarnation.session).witnesses.find(w => w.pid === 101).exit.exitClass, 'UNEXPECTED');
});
test('an Engine console outside the job needs its exact live-captured client identity', () => {
  const data = fixture(); const host = start(103, 1, 100, 0, 'conhost.exe');
  Object.assign(host.line, { contained: false, consoleClient: true });
  data.children.records.push(host);
  assert.equal(census.ledgerView(data, incarnation.session).witnesses.filter(w => w.pid === 103).length, 1);
  host.line.parentCreationFileTime = birth(2);
  assert.equal(census.ledgerView(data, incarnation.session).witnesses.filter(w => w.pid === 103).length, 0);
});
test('failed Engine-console identity reconciliation stays unknown in sampled and unsampled census', () => {
  const data = fixture(), host = start(103, 1, 100, 0, 'conhost.exe');
  Object.assign(host.line, { contained: false, consoleClient: true });
  data.children.records.push(host, {type:'job-process-exit',writer:{role:'dev-runner',pid:200},line:{...host.line,
    reconciled:true,cause:'ENGINE_CRASH',osCheck:'IDENTITY_UNKNOWN',exitClass:'UNKNOWN',code:null,
    exitedAt:null,observedAt:at(20),reason:'Engine crash; identity lookup failed',forced:false}});
  data.engine.records.push(rec('engine-exit',{incarnation,exitClass:'UNEXPECTED',code:1,observedAt:at(10)}));
  for (const observed of [[], [{pid:103,parentPid:100,creationFileTimeUtc:birth(1),name:'conhost.exe',lastSeenAtMs:Date.parse(at(5))}]]) {
    const input=census.exitTargets({runId:incarnation.session,pids:{backendRootPid:100}},null,observed,data);
    const result=census.exitResult(input,{processExits:[]},'unknown identity',data);
    assert.equal(result.processExits.find(r=>r.pid===103).ledgerClass,'UNKNOWN');
  }
});
test('the surviving dev owner writes an Engine-console crash consequence after an exact identity query', async () => {
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'console-receipt-'));
  try {
    const cases = [[{present:false},'OWNER_DIED'],[{present:true,creationFileTime:'0'},'UNKNOWN'],[null,'UNKNOWN'],
      [{present:false},'REQUESTED','REQUESTED'],[null,'UNKNOWN','REQUESTED']];
    for (let i=0;i<cases.length;i++) {
      const [seen,expected,engineClass='UNEXPECTED'] = cases[i], fixtureDir=path.join(data,String(i));
      const ledgerDir = ledger.ledgerDirectory(fixtureDir), writer = {role:'engine',pid:100,startedAt:Date.parse(at(0))};
      const engines = ledger.createLedgerWriter({ledgerDir,family:'engine',writer:{...writer,role:'dev-runner'}});
      engines.append('engine-start',{incarnation,pid:100,creationFileTime:birth(0),executable:'C:\\test\\java.exe'});
      engines.append('engine-exit',{incarnation,exitClass:engineClass,reason:'QUIT',code:engineClass==='REQUESTED'?0:1,observedAt:at(10)});
      const children = ledger.createLedgerWriter({ledgerDir,family:'children',writer});
      const host = start(103,1,100,0,'conhost.exe');
      children.append(host.type,{...host.line,contained:false,consoleClient:true,observedAt:at(1)});
      const survivor = ledger.createLedgerWriter({ledgerDir,family:'children',writer:{...writer,role:'dev-runner',pid:200}});
      const receipts = await census.reconcileCrashedHelpers(fixtureDir,incarnation.session,
        async pids => { assert.deepEqual(pids,[103]); if(seen===null) throw new Error('identity lookup failed'); return new Map([[103,seen]]); },
        (type,fields) => survivor.append(type,fields));
      assert.equal(receipts.length,1,'pre-job Engine console must enter surviving-owner reconciliation');
      assert.equal(receipts[0].cause,engineClass==='REQUESTED'?'ENGINE_EXIT':'ENGINE_CRASH');
      assert.equal(receipts[0].exitClass,expected);
      assert.equal(receipts[0].exitedAt,null);
      assert.equal(census.ledgerView(census.readRunLedger(fixtureDir),incarnation.session).witnesses[0].exit.exitClass,expected);
    }
  } finally {
    assert.equal(path.dirname(path.resolve(data)),path.resolve(os.tmpdir()));
    assert.ok(path.basename(data).startsWith('console-receipt-'));
    fs.rmSync(data,{recursive:true,force:true});
  }
});

test('an unsampled Engine console is armed and its clean quit inherits the client receipt', () => {
  for (const sampled of [false, true]) {
    const data = fixture(), host = start(103, 1, 100, 0, 'conhost.exe');
    Object.assign(host.line, { contained: false, consoleClient: true });
    data.children.records.push(host);
    const seen = sampled ? [{pid:103,parentPid:100,creationFileTimeUtc:birth(1),name:'conhost.exe'}] : [];
    const input = census.exitTargets({runId:incarnation.session,pids:{backendRootPid:100}}, null, seen, data);
    const armed = input.targets.find(t => t.pid === 103);
    assert.ok(armed, 'native console witness arms a handle without sampler coverage');
    assert.equal(armed.witnessId, host.line.witnessId);
    data.engine.records.push(rec('engine-exit', {incarnation,exitClass:'REQUESTED',code:0,reason:'QUIT',exitedAt:at(10)}));
    const output = {processExits:input.targets.map(t => ({pid:t.pid,creationFileTimeUtc:t.creationFileTimeUtc,
      osCreationFileTimeUtc:t.creationFileTimeUtc,exitCode:0,atMs:Date.parse(at(t.pid===103 ? 11 : 10))}))};
    const result = census.exitResult(input,output,'clean console',data);
    assert.equal(result.processExits.find(r => r.pid === 103).ledgerClass,'REQUESTED');
    assert.equal(result.processExits.find(r => r.pid === 103).expectedTermination,true);
    assert.deepEqual(result.exitAccountingGaps,[]);
    assert.equal(result.processExits.filter(r => r.ledgerClass === 'UNEXPECTED').length,0);
    output.processExits.find(r => r.pid === 103).exitCode = -1073741819;
    const crashed = census.exitResult(input,output,'crashed console during quit',data);
    assert.equal(crashed.processExits.find(r => r.pid === 103).ledgerClass,'UNEXPECTED');
    assert.equal(crashed.processExits.find(r => r.pid === 103).expectedTermination,false);
  }
});
