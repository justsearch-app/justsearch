import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { driveCase, watchSupervisor, engineRecords, ledgerMismatch, FIRST_RUNNING_TIMEOUT_MS } from './adapters/dev-runner.mjs';
import { binaryPath, runBinary } from './adapters/tauri.mjs';
import { enginePlanFor } from './contract.mjs';
export async function runLifecycle({ name, testCase, policy, io }) {
  const alive = io.isAlive;
  const workDir=io.makeWorkDir(`${name}-${testCase.id}`);
  const runs=[];let previousRunId=null;
  const suspendFile=path.join(workDir,'suspend.json');
  if(['suspend','resume-hang'].includes(testCase.probe)) fs.writeFileSync(suspendFile,JSON.stringify([{fromMs:800,toMs:3300}]));
  io={...io,harnessOverrides:{...io.harnessOverrides,...(testCase.probe==='cooldown'?{JUSTSEARCH_SUPERVISOR_COOLDOWN_INCREMENT_MS:'800',JUSTSEARCH_SUPERVISOR_MAX_COOLDOWN_MS:'800'}:{}),
    ...(testCase.probe==='locked'?{JUSTSEARCH_SUPERVISOR_DATA_DIR_LOCKED_WAIT_MS:'5000'}:{}),
    ...(['suspend','resume-hang'].includes(testCase.probe)?{JUSTSEARCH_SUPERVISOR_HARNESS_SUSPEND_FILE:suspendFile,JUSTSEARCH_SUPERVISOR_RESUME_GRACE_MS:'1200'}:{})}};
  async function start() {
    if(testCase.probe==='stale') {
      const runtime=path.join(workDir,'data/runtime');fs.mkdirSync(runtime,{recursive:true});
      fs.writeFileSync(path.join(runtime,'shutdown-request.v1.json'),JSON.stringify({schemaVersion:1,reason:'quit',deadlineEpochMs:Date.now()+60000,incarnation:'predecessor/1',requestNonce:'old-request'}));
    }
    if(name==='dev-runner') {const run=await driveCase({testCase,policy,io,workDir,keepWorkDir:true});runs.push(run);return run;}
    const dataDir=path.join(workDir,'data');fs.mkdirSync(path.join(dataDir,'runtime'),{recursive:true});
    const planPath=path.join(workDir,'fake-engine.plan.json');if(!fs.existsSync(planPath))io.writeEnginePlan(workDir,enginePlanFor(testCase));
    const run=runBinary({binary:binaryPath(io.repoRoot),workDir,dataDir,planPath,io,
      env:{...process.env,...io.harnessOverrides,JUSTSEARCH_SUPERVISOR_START_DEADLINE_MS:String(testCase.startTimeoutMs??4000)},stopAfterIncarnation:0});
    Object.assign(run,{dataDir,statePath:path.join(dataDir,'runtime/supervisor.v1.json'),seen:new Map()});runs.push(run);return run;
  }
  function send(run,reason='quit',action=null) {
    if(name==='tauri') fs.writeFileSync(path.join(run.dataDir,'shell-control.json'),JSON.stringify({action,reason}));
    else {
      const state=io.readJsonIfPresent(run.statePath);const dir=path.join(run.stateRoot,'runs',state.runId,'control');fs.mkdirSync(dir,{recursive:true});
      fs.writeFileSync(path.join(dir,'stop-request.json'),JSON.stringify({requestId:crypto.randomUUID(),reason,actor:'supervisor-conformance'}));
    }
  }
  // Only the initial observations include fixture setup; later lifecycle phases keep their budget.
  const setupTimeoutMs=25000+(name==='dev-runner'?FIRST_RUNNING_TIMEOUT_MS:0);
  const waitState=(run,predicate,timeoutMs=25000)=>watchSupervisor({statePath:run.statePath,predicate,io,seen:run.seen,timeoutMs,faultGatePath:run.faultGatePath});
  try {
    if(testCase.expect?.killSupervisor) {
      const first=await start();const state=await waitState(first,s=>s.state==='running',setupTimeoutMs);assert(state,'first Engine reaches running');
      let opened = false;
      try { opened = await io.waitFor(()=>engineRecords(io,first.dataDir).some(r=>r.type==='engine-start'),{what:'durable open Engine start'}); } catch {}
      assert(opened,'the supervisor records the open Engine start before it can die');
      previousRunId=engineRecords(io,first.dataDir).filter(r=>r.type==='engine-start').at(-1).incarnation.session;
      first.child.kill();await first.exited;await io.waitFor(()=>!alive(state.pid),{what:'Engine ended with killed supervisor'});
    }
    const run=await start();const probe=testCase.probe;
    if(probe) {
      const state=await waitState(run,s=>s.state===(probe==='locked'?'exhausted':probe==='cooldown'?'restarting':probe==='start'?'starting':'running'),setupTimeoutMs);
      assert(state,`reached trigger phase for ${probe}`);
      if(probe==='locked') {
        const records=engineRecords(io,run.dataDir);const exits=records.filter(r=>r.type==='engine-exit');
        assert.equal(exits.length,3,'one uncounted lock retry, then exhaustion');assert.equal(state.restartCount,1);
        assert.equal(exits[1].contract.counted,false);assert.equal(exits[2].contract.action,'exhausted');return {problems:[]};
      }
      if(probe==='suspend') {
        await io.sleep(3500);assert.equal(io.readJsonIfPresent(run.statePath).incarnation,1);
        assert.equal(engineRecords(io,run.dataDir).filter(r=>r.type==='engine-intent'&&r.reason==='TIMEOUT').length,0,'sleep is not a hang');
      }
      if(probe==='resume-hang') {
        const restarted=await waitState(run,s=>s.state==='running'&&s.incarnation===2);assert(restarted,'hang detection returns after grace');
        assert(engineRecords(io,run.dataDir).some(r=>r.type==='engine-intent'&&r.reason==='TIMEOUT'));
        send(run);await waitState(run,s=>s.state==='stopped');return {problems:[]};
      }
      if(probe==='session' && process.platform==='linux') {
        // The Windows case below exercises the native window messages. On Linux
        // drive the same two coordinator edges through the binary's fixture controls.
        send(run,'quit','session-query');
        await io.waitFor(()=>engineRecords(io,run.dataDir).some(r=>r.type==='engine-intent'
          &&r.reason==='QUIT'&&r.detail==='session_end'),{what:'session query begins quit',timeoutMs:4000,intervalMs:20});
        send(run,'quit','session-end');
        assert(await waitState(run,s=>s.state==='stopped'),'session end publishes stopped');
        const exits=engineRecords(io,run.dataDir).filter(r=>r.type==='engine-exit');
        assert.equal(exits.length,1);
        assert.equal(exits[0].exitClass,'REQUESTED');
        assert.equal(exits[0].reason,'QUIT');
        assert.equal(exits[0].detail,'session_end');
        assert(!alive(state.pid),'session end leaves no Engine');
        return {problems:[]};
      }
      if(probe==='session') {
        const child=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(io.repoRoot,'scripts/dev/test-session-end.ps1'),'-SupervisorPid',String(run.child.pid),'-DataDir',run.dataDir],{windowsHide:true});
        let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);
        const code=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('exit',resolve);});
        assert.equal(code,0,output);await waitState(run,s=>s.state==='stopped');return {problems:[]};
      }
      let stream=null;let helper=null;
      if(probe==='stream') {
        stream=await fetch(`http://127.0.0.1:${state.apiPort}/v1/chat/completions`,{method:'POST'});
        const first=await stream.body.getReader().read();assert(first.value?.length>0,'chat is actively streaming');
        helper=await io.waitFor(()=>io.readJsonIfPresent(path.join(workDir,'grandchild-1.json')),{what:'active extraction helper'});
        assert(alive(helper.pid),'extraction runs concurrently');
      }
      if(probe==='stale') {
        await io.sleep(700);
        assert.equal(io.readJsonIfPresent(run.statePath)?.state,"running");
        assert.equal(engineRecords(io,run.dataDir).some(r=>r.type==='engine-intent'),false,'stale file must not stop the successor');
        send(run);await waitState(run,s=>s.state==='stopped');return {problems:[]};
      }
      const started=Date.now();send(run,'quit',probe==='triggers'?'triggers':null);
      if(probe==='triggers' && name==='dev-runner') {await io.sleep(5);send(run);}
      const stopped=await waitState(run,s=>s.state==='stopped');assert(stopped,'coordinated quit publishes stopped');
      const records=engineRecords(io,run.dataDir);const exits=records.filter(r=>r.type==='engine-exit');
      if(probe==='cooldown') {assert.equal(records.filter(r=>r.type==='engine-start').length,1);}
      else {
        assert.equal(exits.length,1,'one Engine exit record');
        const intents=records.filter(r=>r.type==='engine-intent'&&r.reason==='QUIT'&&r.channel!=='TERMINATE');assert.equal(intents.length,1,'one cooperative QUIT intent');
        const linuxForced = process.platform==='linux' && ['forced','deadline'].includes(probe);
        assert.equal(exits[0].exitClass,probe==='crash'?'UNEXPECTED':linuxForced&&name==='dev-runner'?'UNKNOWN':'REQUESTED');
        if(linuxForced) { assert.equal(exits[0].code,null); assert.equal(exits[0].codeDistinctive,false); if(name==='tauri') { assert.equal(exits[0].signal,9); assert.equal(exits[0].ownerIntentBeforeSignal,true); } }
        if(probe==='forced'||probe==='deadline') {
          assert.equal(exits[0].forced,true);
          assert(exits[0].code===1246953474 || ((name==='dev-runner'||linuxForced)&&exits[0].codeDistinctive===false));
          assert(Date.now()-started<=policy.quitDeadlineMs+2000,'quit finishes within the contract bound');
        }
        if(probe==='deadline') {
          const elapsed=Date.parse(exits[0].terminateCalledAt)-Date.parse(intents[0].requestedAt);
          assert(Math.abs(elapsed-(policy.quitDeadlineMs-policy.quitForceMarginMs))<250,`force issued at the contract deadline: ${elapsed}`);
        }
      }
      if(probe==='stream') { assert(Date.now()-started<=policy.quitDeadlineMs+policy.quitForceMarginMs,'streaming quit deadline');assert(!alive(helper.pid),'extraction ended with Engine'); }
      for(const record of records.filter(r=>r.type==='engine-start')) assert(!alive(record.pid),'no Engine survives quit');
      return {problems:[]};
    }
    const reach=testCase.expect?.reach??{state:'running',incarnation:1};
    const reached=await waitState(run,s=>s.state===reach.state&&s.incarnation===reach.incarnation&&(!previousRunId||engineRecords(io,run.dataDir).some(r=>r.type==='engine-start'&&r.incarnation.session!==previousRunId)),setupTimeoutMs);
    assert(reached,`reached ${reach.state}/${reach.incarnation}`);
    if(reach.restartCount!==undefined) assert.equal(reached.restartCount,reach.restartCount);
    if(testCase.expect?.seenPhase) assert([...run.seen.values()].some(s=>s.incarnation===testCase.expect.seenPhase.incarnation&&s.phase===testCase.expect.seenPhase.phase),'discovery phase is published');
    if(testCase.expect?.ledger) {
      const runId=engineRecords(io,run.dataDir).filter(r=>r.type==='engine-start').at(-1)?.incarnation.session;
      const problems=ledgerMismatch(engineRecords(io,run.dataDir),testCase.expect.ledger,{runId,previousRunId,adapter:name});
      assert.deepEqual(problems,[],"ledger satisfies the contract");
    }
    return {problems:[]};
  } finally {
    for(const run of runs) {
      if(name==='dev-runner') await run.cleanup();
      else if(run.child.exitCode===null&&run.child.signalCode===null) {
        try {send(run);} catch {}
        await Promise.race([run.exited,io.sleep(11000)]);
        if(run.child.exitCode===null&&run.child.signalCode===null) { run.child.kill(); await run.exited; }
      }
    }
    if(process.env.SUPERVISOR_CONFORMANCE_KEEP!=='1')fs.rmSync(workDir,{recursive:true,force:true,maxRetries:5});
  }
}
