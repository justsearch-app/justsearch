import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));

for (const missingBirth of [false, true]) {
  test(`Linux original-child cleanup survives unavailable ${missingBirth ? 'birth' : 'helper'}`, {
    skip: process.platform !== 'linux' && 'Linux fixture backend; Windows retains its native held-handle cleanup',
  }, () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-cleanup-'));
    try {
      for (const file of ['scripts/dev/lib/test-fixture-processes.cjs', 'scripts/dev/lib/process-identity.cjs',
        'scripts/lib/native-targets.cjs', 'contracts/native-targets/spec.v1.json']) {
        const target = path.join(dir, file);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(path.join(root, file), target);
      }
      // Deliberately no staged helper: this is the bootstrap/failed-stop condition, even on a
      // developer checkout that already has a distribution. Backstop cleanup uses only the two
      // original ChildProcess objects, so the regression itself cannot leave its children alive.
      const probe = `
const cp=require('node:child_process');
const originalKill=cp.ChildProcess.prototype.kill;
const identity=require(${JSON.stringify(path.join(dir, 'scripts/dev/lib/process-identity.cjs'))});
require(${JSON.stringify(path.join(dir, 'scripts/dev/lib/test-fixture-processes.cjs'))});
${missingBirth ? 'identity.readLinuxProcess=()=>null;' : ''}
const children=[];
function spawn(){const child=cp.spawn(process.execPath,['-e',"process.on('SIGTERM',()=>{});process.send('ready');setInterval(()=>{},1000)"],{stdio:['ignore','ignore','ignore','ipc']});children.push(child);return child;}
const ready=child=>new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('fixture never ready')),3000);child.once('message',()=>{clearTimeout(timer);resolve();});});
const ended=child=>child.exitCode!==null||child.signalCode!==null;
(async()=>{let result;try{
 const victim=spawn(),stranger=spawn();await Promise.all([ready(victim),ready(stranger)]);
 let attemptedPid;const signal=identity.signalLinuxProcess;identity.signalLinuxProcess=(saved,...args)=>{attemptedPid=saved.pid;return signal(saved,...args);};
 victim.recordedIdentity=stranger.recordedIdentity;
 let failure;try{victim.kill('SIGTERM');}catch(error){failure=error.message;}
 const deadline=Date.now()+2000;while(!ended(victim)&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,10));
 result={failure,victimEnded:ended(victim),strangerEnded:ended(stranger),attemptedPid,victimPid:victim.pid};
 const fake={pid:stranger.pid,exitCode:null,signalCode:null,recordedIdentity:stranger.recordedIdentity};
 try{cp.ChildProcess.prototype.kill.call(fake,'SIGKILL');result.fakeRefused=false;}catch{result.fakeRefused=true;}
 result.strangerStillAlive=!ended(stranger);
}finally{await Promise.all(children.map(child=>new Promise(resolve=>{if(ended(child)){resolve();return;}child.once('exit',resolve);originalKill.call(child,'SIGKILL');})));}
console.log(JSON.stringify(result));})().catch(error=>{console.error(error);process.exitCode=1;});`;
      const result = spawnSync(process.execPath, ['-e', probe], { encoding: 'utf8', timeout: 10000 });
      assert.equal(result.status, 1, 'the fixture program must fail even when its caller catches the stop error');
      assert.match(result.stderr, missingBirth ? /spawn-recorded identity/ : /ENOENT/);
      const observed = JSON.parse(result.stdout);
      assert.match(observed.failure, missingBirth ? /spawn-recorded identity/ : /ENOENT/,
        'the failed recorded stop must still name its cause');
      assert.equal(observed.victimEnded, true, 'the original child must be reclaimed despite the failed recorded stop');
      if (!missingBirth) assert.equal(observed.attemptedPid, observed.victimPid,
        'a replaced public identity must not authorize signalling a stranger');
      assert.equal(observed.strangerEnded, false, 'a different child must be untouched');
      assert.equal(observed.fakeRefused, true, 'a PID and copied identity confer no original-spawn authority');
      assert.equal(observed.strangerStillAlive, true);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
}
