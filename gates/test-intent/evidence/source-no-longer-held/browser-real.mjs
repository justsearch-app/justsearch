import { chromium } from '/home/elias/dev/js-uiux-source-gone/modules/ui-web/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const base='/home/elias/.cache/am/mif8de7q/tmp/engine-live';
const docPath=base+'/corpus/cited.txt';
const manifest=JSON.parse(fs.readFileSync(base+'/data/runtime/manifest.json','utf8'));
const api='http://127.0.0.1:'+manifest.head.apiPort;
const uiPort=JSON.parse(fs.readFileSync(base+'/preview-port.json','utf8')).port;
const ui='http://127.0.0.1:'+uiPort;
const report={startedUtc:new Date().toISOString(),engine:JSON.parse(fs.readFileSync(base+'/process.json','utf8')),checks:[],screenshots:[]};
const repo='/home/elias/dev/js-uiux-source-gone';
report.candidateFiles=Object.fromEntries(['modules/ui-web/src/shell-v0/components/chat/CitationsPanel.ts','modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts','modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts','modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts','modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerSearchService.java','modules/ui/src/main/java/io/justsearch/ui/api/PreviewController.java','modules/ui/src/main/java/io/justsearch/ui/api/SchemaController.java','modules/ui/build/install/ui/lib/ui-0.3.0.jar','modules/ui-web/dist/index.html'].map(file=>[file,crypto.createHash('sha256').update(fs.readFileSync(repo+'/'+file)).digest('hex')]));
const tmp=fs.mkdtempSync('/tmp/jsv-');const profile=fs.mkdtempSync('/tmp/jsv-');
const savedFiles=['wp58-ask','wp58-delegate'].flatMap(id=>['meta.json','messages.jsonl'].map(file=>base+'/data/index/conversations/'+id+'/'+file));
const digests=()=>Object.fromEntries(savedFiles.map(file=>[file,crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
report.savedBefore=digests();
const headers={'X-JustSearch-Session':manifest.head.sessionToken,'Content-Type':'application/json'};
const record=(name,facts)=>{report.checks.push({name,status:'passed',facts});console.log('PASS',name);};
async function call(path,method='GET',body){const response=await fetch(api+path,{method,headers,body:body?JSON.stringify(body):undefined});return {status:response.status,body:await response.json()};}
async function membership(expected){
 for(let i=0;i<20;i++){const result=await call('/api/documents/presence','POST',{docIds:[docPath]});if(result.body.states?.[docPath]===expected)return result;await new Promise(resolve=>setTimeout(resolve,300));}
 throw new Error('Membership did not become '+expected);
}
let context;
try{
 context=await chromium.launchPersistentContext(profile,{headless:true,executablePath:'/usr/bin/google-chrome',env:{...process.env,CUDA_VISIBLE_DEVICES:'-1',TMPDIR:tmp},args:['--no-sandbox','--disable-dev-shm-usage'],extraHTTPHeaders:headers,permissions:['clipboard-read','clipboard-write'],viewport:{width:1100,height:820}});
 const cdp=await context.browser().newBrowserCDPSession();report.chromiumProcesses=(await cdp.send('SystemInfo.getProcessInfo')).processInfo;await cdp.detach();
 const pages=[];
 async function open(id){const page=await context.newPage();await page.goto(ui+'/?api_port='+uiPort+'#justsearch://surface/core.search-v3-surface?conversationId='+id);await page.locator('[data-testid="sv3-turn-sources"]').waitFor({timeout:20000});const dismiss=page.getByRole('button',{name:'Dismiss',exact:true});if(await dismiss.isVisible())await dismiss.click();return page;}
 async function show(page){const response=page.waitForResponse(r=>r.url().endsWith('/api/documents/presence')&&r.request().method()==='POST',{timeout:10000});await page.locator('[data-testid="sv3-turn-sources"]').click();const r=await response;assert.equal(r.status(),200);return await r.json();}
 async function closePane(page){const pane=page.locator('jf-document-pane');if(await pane.count())await pane.getByRole('button',{name:'Close',exact:true}).click();}
 async function shot(page,name){const path=base+'/'+name+'.png';await page.screenshot({path,fullPage:true,animations:'disabled'});report.screenshots.push(path);}
 for(const id of ['wp58-ask','wp58-delegate']){
  const page=await open(id);pages.push(page);
  const before=await show(page);assert.equal(before.states[docPath],'present');
  assert.equal(await page.locator('.source-availability').innerText(),'');
  assert.equal(await page.locator('.cite-ref').count(),1);
  await page.locator('jf-citations-panel .source').click();
  await page.locator('jf-document-pane .scroll-region').waitFor();
  assert.match(await page.locator('jf-document-pane .scroll-region').innerText(),/cedar archive/);
  await shot(page,id+'-present');
  record(id+' present saved citation preserves mark and indexed preview',{presence:before.states[docPath]});
 }
 const movedPath=base+'/corpus/moved-cited.txt';
 fs.renameSync(docPath,movedPath);
 try {
  const stillHeld=await membership('present');
  const indexedPreview=await call('/api/preview?docId='+encodeURIComponent(docPath));
  assert.equal(indexedPreview.status,200);assert.match(indexedPreview.body.content,/cedar archive/);
  record('renamed disk file remains present and readable from the index',{presence:stillHeld.body.states[docPath],preview:indexedPreview.status});
 } finally { fs.renameSync(movedPath,docPath); }
 const removed=await call('/api/indexing/roots','DELETE',{path:base+'/corpus'});assert.equal(removed.status,200);await membership('absent');
 for(let i=0;i<pages.length;i++){
  const page=pages[i];const id=['wp58-ask','wp58-delegate'][i];
  await page.locator('[data-testid="sv3-turn-sources"]').click();
  const absent=await show(page);assert.equal(absent.states[docPath],'absent');
  await page.locator('.source-availability').getByText('No longer available',{exact:true}).waitFor();
  assert.equal(await page.locator('.source-availability').getAttribute('role'),'status');
  await page.locator('jf-citations-panel .source').click();
  await page.locator('jf-document-pane .last-known-path').waitFor();
  record(id+' already open pane refreshes after confirmed absence',{});
  await page.reload();await page.locator('[data-testid="sv3-turn-sources"]').waitFor();await show(page);
  await page.locator('.source-availability').getByText('No longer available',{exact:true}).waitFor();
  await page.locator('jf-citations-panel .source').click();
  await page.locator('jf-document-pane .last-known-path').waitFor();
  assert.match(await page.locator('jf-document-pane jf-error-alert').textContent(),/no longer available/);
  assert.equal(await page.locator('.last-known-path').innerText(),docPath);
  assert.equal(await page.getByRole('button',{name:'Reveal path',exact:true}).count(),1);
  await page.getByRole('button',{name:'Copy path',exact:true}).click();
  assert.equal(await page.evaluate(()=>navigator.clipboard.readText()),docPath);
  for(const theme of ['light','dark']){await page.emulateMedia({colorScheme:theme,forcedColors:'none'});await page.evaluate(t=>document.documentElement.setAttribute('data-theme',t),theme);await shot(page,id+'-absent-'+theme);}
  await page.emulateMedia({forcedColors:'active'});await page.setViewportSize({width:360,height:740});await shot(page,id+'-absent-forced-narrow');
  await page.getByRole('button',{name:'Copy path',exact:true}).click();
  assert.equal(await page.evaluate(()=>navigator.clipboard.readText()),docPath);
  const bounds=await page.locator('.last-known-path').boundingBox();assert(bounds&&bounds.width<=360);
  assert.equal(await page.locator('jf-document-pane').getByRole('button',{name:'Copy path',exact:true}).isVisible(),true);
  await closePane(page);
  await page.locator('.source-availability').scrollIntoViewIfNeeded();await shot(page,id+'-source-forced-narrow');
  record(id+' removed folder shows announced absence after redisclosure and reload; pane path copied',{removal:removed.status,themes:['light','dark','forced-colors'],width:360});
  await page.emulateMedia({forcedColors:'none',colorScheme:'light'});await page.setViewportSize({width:1100,height:820});await closePane(page);
 }
 const added=await call('/api/indexing/roots','POST',{path:base+'/corpus'});assert.equal(added.status,200);await membership('present');
 for(const page of pages){await page.reload();await page.locator('[data-testid="sv3-turn-sources"]').waitFor();const returned=await show(page);assert.equal(returned.states[docPath],'present');assert.equal(await page.locator('.source-availability').innerText(),'');}
 record('re-added folder restores sources on next load',{});
 // Keep both saved conversations painted while the private Engine exits.
 fs.writeFileSync(base+'/stop','');
 for(let i=0;i<50&&!fs.existsSync(base+'/exit.json');i++)await new Promise(resolve=>setTimeout(resolve,300));
 assert(fs.existsSync(base+'/exit.json'),'private Engine must stop');
 for(let i=0;i<pages.length;i++){
  const page=pages[i];await page.locator('[data-testid="sv3-turn-sources"]').click();
  const result=page.waitForResponse(r=>r.url().endsWith('/api/documents/presence'),{timeout:10000});
  await page.locator('[data-testid="sv3-turn-sources"]').click();const response=await result;assert(response.status()>=500);
  assert.equal(await page.locator('.source-availability').innerText(),'');
  await page.locator('jf-citations-panel .source').click();await page.locator('jf-document-pane jf-error-alert').waitFor();
  const text=await page.locator('jf-document-pane jf-error-alert').textContent();assert.match(text,/could not be read/);assert.doesNotMatch(text,/HTTP|no longer available/);
  await shot(page,['wp58-ask','wp58-delegate'][i]+'-engine-stopped-unknown');
 }
 record('Engine stopped remains unknown for both saved source panels and opened panes',{});
 report.savedAfter=digests();assert.deepEqual(report.savedAfter,report.savedBefore);record('saved conversation bytes unchanged',{});
 report.status='passed';
}catch(error){report.status='failed';report.error=String(error.stack||error);console.error(report.error);if(context){for(const [i,p]of context.pages().entries()){try{await p.screenshot({path:base+'/failure-'+i+'.png',fullPage:true});}catch{}}}process.exitCode=1;}
finally{report.finishedUtc=new Date().toISOString();fs.writeFileSync(base+'/browser-evidence.json',JSON.stringify(report,null,2)+'\n');if(context)await context.close();fs.rmSync(tmp,{recursive:true,force:true});fs.rmSync(profile,{recursive:true,force:true});}
