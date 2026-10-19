import fs from 'node:fs';
import path from 'node:path';
import {spawn, spawnSync, execFileSync} from 'node:child_process';

const root = process.cwd();
const out = path.resolve(process.env.VERIFY_OUT || 'verification-output');
fs.mkdirSync(out, {recursive:true});
const testFile = path.join(root, 'scripts/lib/engine-discovery.test.mjs');
const prodFile = path.join(root, 'scripts/prod/justsearch-mcp/server.mjs');
const sdk = path.join(root, 'node_modules/@modelcontextprotocol/sdk');
const original = fs.readFileSync(testFile, 'utf8');
const prod = fs.readFileSync(prodFile, 'utf8');
const base = execFileSync('git', ['show', '485fe86b82b0bc8dc42ca0c1ebfa9d2fb71a136d:scripts/lib/engine-discovery.test.mjs'], {encoding:'utf8'});
const runtime=path.resolve(process.env.RUNNER_TEMP || out, 'verify-788-runtime');
fs.mkdirSync(runtime,{recursive:true});
process.env.TMPDIR=runtime;process.env.TMP=runtime;process.env.TEMP=runtime;
const results = [];
let bad = false;
const health = 'D9: the port is taken while /api/health';
function replace(s,a,b) { if(!s.includes(a)) throw new Error('mutation anchor absent: '+a); return s.replace(a,b); }
async function run(label, pattern, expected, reason, source=original, prodSource=prod, limit=45000) {
  fs.writeFileSync(testFile, source); fs.writeFileSync(prodFile, prodSource);
  const started = Date.now();
  const child = spawn(process.execPath, ['--test', '--test-reporter=tap', '--test-name-pattern='+pattern, testFile], {cwd:root, env:process.env, detached:process.platform!=='win32', stdio:['ignore','pipe','pipe']});
  let output='', timedOut=false;
  child.stdout.on('data',d=>output+=d); child.stderr.on('data',d=>output+=d);
  const timer=setTimeout(()=>{
    timedOut=true;
    if(process.platform==='win32') spawnSync('taskkill',['/pid',String(child.pid),'/T','/F']);
    else process.kill(-child.pid,'SIGKILL');
  },limit);
  const code=await new Promise(r=>child.on('close',r)); clearTimeout(timer);
  fs.writeFileSync(path.join(out,label+'.log'),output);
  let ok=expected==='broken' ? (code!==0||timedOut) : expected==='pass' ? code===0&&!timedOut : expected==='hang' ? timedOut : code!==0&&!timedOut&&(!reason||reason.test(output));
  const resources=[...output.matchAll(/VERIFIER_RESOURCE (\{[^\n]+\})/g)].map(m=>JSON.parse(m[1]));
  for(const resource of resources) {
    let alive;try {process.kill(resource.pid,0);alive=true;}catch {alive=false;}
    const remains=fs.existsSync(resource.root);
    console.log('RESOURCE',label,JSON.stringify({...resource,alive,remains}));
    if(alive) {try {process.kill(resource.pid,'SIGKILL');}catch{}}
    if(expected!=='hang' && (alive||remains)) ok=false;
  }
  const result={label,code,timedOut,seconds:(Date.now()-started)/1000,expected,ok};
  console.log(JSON.stringify(result));results.push(result);if(!ok)bad=true;
  fs.writeFileSync(testFile,original);fs.writeFileSync(prodFile,prod);
}
try {
  // Real baseline/candidate executions also establish that the SDK is actually installed.
  await run('before-suite','.*',process.platform==='win32'?'broken':'pass',null,base,prod,process.platform==='win32'?25000:70000);
  await run('after-suite','.*','pass',null,original,prod,70000);
  for(const [version,source] of [['before',base],['after',original]]) {
    // Discover has completed the takeover and refused bootstrap, but never reports its result.
    const exitSource=replace(source,"'.then((r) => { process.stdout.write(JSON.stringify(r)); })'","'.then((r) => { process.exit(23); })'");
    await run(version+'-discover-early-exit',health,version==='before'?'pass':'fail',/23|Expected values to be strictly equal/,exitSource);
    let timeoutSource=replace(source,"'.then((r) => { process.stdout.write(JSON.stringify(r)); })'","'.then((r) => { setInterval(() => {}, 1000); })'");
    timeoutSource=replace(timeoutSource,'timeoutMs = 20000','timeoutMs = 1200');
    await run(version+'-discover-timeout',health,version==='before'?'pass':'fail',/Expected values to be strictly equal/,timeoutSource);
  }
  // Bypass the port-owner proof only in production's authenticated calls; discovery stays real.
  const unsafe=prod.replaceAll('      engineTarget,','      { ...engineTarget, engine: null },');
  if(unsafe===prod)throw new Error('unsafe mutation absent');
  await run('after-unsafe-token','D9: production MCP (stops|re-proves|status)','fail',/ENGINE_GONE|continuation|AssertionError/,original,unsafe);
  // A self-terminating fixture actually survives into cleanup. It must be diagnosed and reaped.
  const leaked=`\ntest('verifier: surviving child', async t => { const root=tempRoot(t); const m=machine(root); const c=spawnNode(['-e','setInterval(()=>{},1000)'],{cwd:m.cwd,stdio:'ignore'},'self'); await once(c,'spawn'); console.log('VERIFIER_RESOURCE '+JSON.stringify({pid:c.pid,root})); });\n`;
  await run('after-surviving-child','verifier: surviving child','fail',/still running at cleanup; killed/,original+leaked);
  const assertion=`\ntest('verifier: failing assertion', async t => { const root=tempRoot(t); const m=machine(root); const c=spawnNode(['-e',"process.stdin.resume(); process.stdin.on('end',()=>process.exit(0));"],{cwd:m.cwd,stdio:['pipe','ignore','ignore']},'stdin'); await once(c,'spawn'); console.log('VERIFIER_RESOURCE '+JSON.stringify({pid:c.pid,root})); assert.fail('verifier assertion fired'); });\n`;
  await run('after-assertion-cleanup','verifier: failing assertion','fail',/verifier assertion fired/,original+assertion);
  // Force each newly bounded kind of wait never to arrive. Expiry must fail and terminate.
  await run('after-arrival-deadline',health,'fail',/held request arriving.*nothing within 10000 ms/,replace(original,'await within(service.arrived,','await within(new Promise(() => {}),'));
  await run('after-handshake-deadline','D9: bridge reconnect','fail',/handshake: not done within 10000 ms/,replace(original,'while (!done()) {','while (true) {'));
  const requestHang=replace(original,'await within(engine.arrived, 10000,','await within(new Promise(() => {}), 10000,');
  await run('after-request-deadline','D9: production MCP re-proves','fail',/retrieve-context arriving.*nothing within 10000 ms/,requestHang);
  // Physically remove SDK resolution, including from real production imports.
  fs.renameSync(sdk,sdk+'-verifier-hidden');
  try {
    await run('before-sdk-absent','D9: production MCP','pass',null,base);
    await run('after-sdk-absent','D9: production MCP','fail',/production MCP needs the MCP SDK/);
    await run('after-sdk-free-bridge','D1/D2/D3: the extracted MCPB','pass');
  } finally {fs.renameSync(sdk+'-verifier-hidden',sdk);}
} finally {
  fs.writeFileSync(testFile,original);fs.writeFileSync(prodFile,prod);
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2));
}
process.exitCode=bad?1:0;
