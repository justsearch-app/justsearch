import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import actuator from './lib/engine-actuator.cjs';
import census from './lib/stop-exit-census.cjs';
import ledgerLib from './lib/exit-ledger.cjs';

// No processes are spawned. Temporary fixtures contain only files we create, never links.
function temporary(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ix-lifecycle-'));
  t.after(() => {
    for (const entry of fs.readdirSync(dir, { recursive: true })) {
      assert.equal(fs.lstatSync(path.join(dir, entry)).isSymbolicLink(), false);
    }
    fs.rmSync(dir, { recursive: true, force: true });
  });
  return dir;
}
for (const endedBy of ['runner', 'terminate', 'taskkill', 'cooperative']) {
  test(`fallback ${endedBy} without OS receipt keeps exit unknown`, t => {
    const dataDir = temporary(t);
    const recorder = actuator.createCliEngineRecorder({ dataDir, incarnation: { session: 'test', n: 2 } });
    recorder.intent({ mode: 'NOW', channel: 'TERMINATE' });
    const outcome = recorder.exit({ endedBy });
    assert.equal(outcome.code, null, 'an attempted stop does not observe the exit code');
    assert.equal(outcome.exitClass, 'UNKNOWN');
    const record = ledgerLib.readLedger(ledgerLib.ledgerDirectory(dataDir), 'engine').records.at(-1).line;
    assert.equal(record.code, null);
  });
}
test('identity-bound concurrent crash stays unexpected during fallback', t => {
  const recorder = actuator.createCliEngineRecorder({ dataDir: temporary(t), incarnation: { session: 'test', n: 2 } });
  const out = recorder.exit({ endedBy: 'terminate', observed: { exitCode: -1073741819, atMs: Date.now() } });
  assert.equal(out.exitClass, 'UNEXPECTED');
  assert.equal(out.code, -1073741819);
});
const at = n => `2026-10-08T10:00:${String(n).padStart(2,'0')}.000Z`;
const birth = n => census.fileTimeFromInstant(at(n));
const incarnation = { session: 'parser-stop', n: 2 };
const owner = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
const parser = { pid: 101, creationFileTime: birth(1), instanceId: 'parser' };
// Each record's writer as the Engine writes it: its PID and its start, to the millisecond (the owner's
// birth for the Engine, pid 100; the parser's for pid 101).
const rec = (type, line, pid=100) => ({ type, line, writer: { role: 'engine', pid, startedAt: at(pid === 100 ? 0 : 1) } });
function parserHistory(reason, code=0, contained=true) {
  return { engine: { present: true, records: [rec('engine-start', { incarnation, pid:100,
    creationFileTime: birth(0), executable:'C:\\fixture\\java.exe' })] }, children: { present: true, records:[
    rec('child-spawn', { childId:'parser', owner, kind:'EXTRACTION' }),
    rec('child-start', { childId:'parser', pid:101, creationFileTime:birth(1), executable:'C:\\fixture\\java.exe', contained:true }),
    rec('helper-window-start', { intentId:'ocr', owner:parser, incarnation, ownerContainment: contained ? 'KILL_ON_CLOSE' : null,
      image:'tesseract.exe', reason:'OCR', requestedAt:at(2), deadlineAt:at(30) }, 101),
    rec('job-process-start', { witnessId:'ocr', owner, incarnation, pid:102, creationFileTime:birth(3),
      executable:'C:\\fixture\\tesseract.exe', parentPid:101, parentCreationFileTime:birth(1), contained:true }),
    rec('child-exit', { childId:'parser', code:reason==='CRASH'?17:1246953473,
      exitClass:reason==='CRASH'?'UNEXPECTED':'REQUESTED', reason:reason==='CRASH'?null:reason,
      forced:reason!=='CRASH', exitedAt:at(4), observedAt:at(4) }),
    rec('job-process-exit', { witnessId:'ocr', owner, incarnation, pid:102, creationFileTime:birth(3),
      code, exitClass:'UNEXPECTED', reason:null, forced:false, exitedAt:at(5), observedAt:at(5) })] } };
}
for (const reason of ['TIMEOUT','QUIT','CRASH']) test(`parser ${reason} retains contained helper cause`, () => {
  const ended = census.ledgerView(parserHistory(reason), incarnation.session).witnesses[0].exit;
  assert.equal(ended.exitClass, reason==='CRASH'?'OWNER_DIED':'REQUESTED');
  assert.equal(ended.reason, reason==='CRASH'?'parser owner died':reason);
});
test('parser inheritance refuses missing containment, wrong birth, early exit and real helper crashes', () => {
  for (const history of [parserHistory('QUIT',0,false), parserHistory('QUIT',-1073741819),
    parserHistory('QUIT',17)]) assert.equal(census.ledgerView(history, incarnation.session).witnesses[0].exit.exitClass,'UNEXPECTED');
  const reused = parserHistory('QUIT'); reused.children.records[3].line.parentCreationFileTime=birth(2);
  assert.equal(census.ledgerView(reused,incarnation.session).witnesses[0].exit.exitClass,'UNEXPECTED');
  const early = parserHistory('QUIT'); early.children.records.at(-1).line.exitedAt=at(3);
  // An exit before the parser's does not inherit its QUIT. The parser's window, with no end record,
  // covers until the parser's recorded exit, so the exit is the window's (task b6d536 b101).
  const earlyExit = census.ledgerView(early,incarnation.session).witnesses[0].exit;
  assert.deepEqual([earlyExit.exitClass, earlyExit.reason], ['REQUESTED', 'OCR']);
});
test('running record preserves the first-ready retry incarnation', async t => {
  // Execute the production record construction with IO seams; no second copy of its fields.
  const source=fs.readFileSync(new URL('./dev-runner.cjs',import.meta.url),'utf8');
  const begin=source.indexOf('  runJson = {');
  assert.ok(begin>0);
  const end=source.indexOf('\n  };',begin)+5;
  const spawn={cwd:'.',command:'fixture',args:[],shell:false};
  const context={fs,path,process:{pid:10},runJson:null,runId:'retry',startedAt:at(0),
    backend:{pid:102},frontend:{pid:103},startingRun:{incarnation:2,identities:{backend:{pid:102}}},
    firstReady:{n:2,incarnation:2},readyIncarnation:2,engineIdentityAsync:async()=>({pid:102}),
    runnerIdentity:{pid:10},frontendIdentity:{pid:103},apiPortRequested:0,apiPortActual:4999,uiPort:5999,
    apiBaseUrl:'',uiUrl:'',dataDir:'.',repoRoot:'.',toPosix:v=>v,effectiveChatProfile:'',devHotReload:{},
    spawnBackend:spawn,spawnF:spawn,backendStdoutPath:'.',backendStderrPath:'.',frontendStdoutPath:'.',frontendStderrPath:'.',
    manifestInstanceId:'engine-2',holderBlockFor:()=>({}),resolveOwnerConfidence:()=>null,callerIdentity:{},confirmedIbp:null,
    expectedIbp:{path:'.',evidence:null},stopSignalPath:null,
    actuator:{current:()=>null},initialCensusState:null,initialCensusIdentity:null};
  await vm.runInNewContext(`(async()=>{${source.slice(begin,end)};return runJson;})()`,context);
  assert.equal(context.runJson.incarnation,2,'publishing running must not replace retry 2 with missing/1');
  const dataDir=temporary(t);
  actuator.createCliEngineRecorder({dataDir,incarnation:{session:'retry',n:1}})
    .exit({endedBy:'cooperative',observed:{exitCode:17,atMs:Date.now()}});
  const fallback=actuator.createCliEngineRecorder({dataDir,incarnation:{session:'retry',n:context.runJson.incarnation}});
  assert.equal(fallback.intent({mode:'NOW',channel:'TERMINATE'}).written,true);
  assert.equal(fallback.exit({endedBy:'terminate',observed:{exitCode:ledgerLib.JS_ENGINE_STOP,atMs:Date.now()}}).written,true);
  const records=ledgerLib.readLedger(ledgerLib.ledgerDirectory(dataDir),'engine').records.filter(r=>r.line.incarnation?.n===2);
  assert.deepEqual(records.map(r=>r.type),['engine-intent','engine-exit']);
});
