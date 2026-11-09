// SPDX-License-Identifier: Apache-2.0
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type BrowserServer, type Browser } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';
let browserServer: BrowserServer;
let server: ViteDevServer, browser: Browser, output: string, url: string;
const HARNESS = `<!doctype html><html><head><link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
import '/src/shell-v0/components/FailedFilesList.ts';
import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
import { __seedForTest as operations } from '/src/api/registry/OperationCatalogClient.ts';
import { __seedForTest as resources } from '/src/api/registry/ResourceCatalogClient.ts';
operations({schemaVersion:'1',catalogVersion:1,namespace:'core',primitive:'Operation',entries:
 ['retry','cancel'].map(verb=>({id:'core.'+verb+'-indexing-job',policy:{risk:'LOW',confirm:{kind:'NONE'}},
 presentation:{labelKey:verb==='retry'?'Retry':'Cancel',descriptionKey:''},lineage:{affects:[],supersedes:[]}}))});
resources({schemaVersion:'1',catalogVersion:1,namespace:'core',primitive:'Resource',entries:[{id:'core.failed-indexing-jobs',
 primaryKey:'pathHash',privacy:{pathPolicy:'NO_PATHS'},itemOperations:['core.retry-indexing-job','core.cancel-indexing-job']}]});
const list=document.createElement('jf-failed-files-list');
list.host_=createMockHostApi({data:{fetch:async()=>new Response(JSON.stringify({count:1,jobs:[{pathHash:'file',state:'FAILED',
 attempts:1,lastUpdatedMs:1700000000000,errorMessage:'Could not parse file',retryAfterMs:0,collection:'default',scanId:''}]}))}});
list.folderPathHash='root';document.body.append(list);
// An ActionButton outside the failed-file list is a preservation witness.
const other=document.createElement('jf-action-button');other.label='Other action';document.body.append(other);
document.body.dataset.ready='true';
</script></body></html>`;
beforeAll(async () => {
  const root=path.resolve('../..','tmp'); await mkdir(root,{recursive:true}); output=await mkdtemp(path.join(root,'failed-buttons-'));
  server=await createServer({configFile:false,root:process.cwd(),cacheDir:output,publicDir:false,logLevel:'silent',
    server:{host:'127.0.0.1',port:0},plugins:[{name:'failed-buttons',configureServer(vite){vite.middlewares.use('/failed-buttons',(_req,res)=>{
      res.setHeader('Content-Type','text/html');res.end(HARNESS);
    });}}]});
  await server.listen(); const address=server.httpServer!.address();
  if(!address||typeof address==='string')throw new Error('No private Vite port');
  url=`http://127.0.0.1:${address.port}/failed-buttons`;
  const env={...process.env,TMPDIR:output,CUDA_VISIBLE_DEVICES:'-1'};
  for(const name of ['DISPLAY','WAYLAND_DISPLAY'])Reflect.deleteProperty(env,name);
  browserServer=await chromium.launchServer({headless:true,args:['--disable-gpu'],env});
  browser=await chromium.connect(browserServer.wsEndpoint());
},60_000);
afterAll(async()=>{await browser?.close(); await browserServer?.close();await server?.close();if(output)await rm(output,{recursive:true,force:true});});
it.each(['light','dark','forced'] as const)('L12 failed-file Cancel/Retry match nearby compact buttons (%s), including narrow/wide-font keyboard focus',async theme=>{
  const page=await browser.newPage({viewport:{width:420,height:600},colorScheme:theme==='dark'?'dark':'light',forcedColors:theme==='forced'?'active':'none'});
  page.setDefaultTimeout(5000);
  try {
    const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
    page.on('console',message=>{if(message.type()==='error')errors.push(message.text());});
    await page.goto(url,{timeout:20_000});
    await page.locator('body[data-ready="true"]').waitFor({state:'attached',timeout:20_000}).catch(error=>{throw new Error(`${String(error)}\nPage errors: ${errors.join('\n')}`);});
    await page.locator('html').evaluate((el,theme)=>{el.dataset.theme=theme==='dark'?'dark':'light';},theme);
    const nearby=page.locator('jf-button[label="Try again"] button'); await nearby.waitFor();
    const compact=await nearby.evaluate(el=>({font:getComputedStyle(el).fontSize,height:el.getBoundingClientRect().height}));
    const actionFor=(verb:string)=>page.locator(`jf-action-button[operation-id="core.${verb}-indexing-job"] button.invoke`);
    for(const verb of ['retry','cancel']) {
      const action=actionFor(verb);
      const size=await action.evaluate(el=>({font:getComputedStyle(el).fontSize,height:el.getBoundingClientRect().height}));
      console.info(theme,verb,{compact,action:size});
      expect(size.font).toBe(compact.font); expect(Math.abs(size.height-compact.height)).toBeLessThanOrEqual(1);
      for(let i=0;i<12&&!await action.evaluate(el=>el.matches(':focus'));i++)await page.keyboard.press('Tab');
      expect(await action.evaluate(el=>el.matches(':focus-visible')&&getComputedStyle(el).outlineStyle!=='none')).toBe(true);
    }
    await page.locator('jf-failed-files-list').evaluate(el=>{(el as HTMLElement).style.fontFamily='"DejaVu Sans", sans-serif';(el as HTMLElement).style.letterSpacing='2px';});
    for(const verb of ['retry','cancel']) {
      const action=actionFor(verb);
      expect(await action.evaluate(el=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&el.scrollWidth<=el.clientWidth;})).toBe(true);
    }
    expect(await page.getByRole('button',{name:'Other action'}).evaluate(el=>({font:getComputedStyle(el).fontSize,padding:getComputedStyle(el).padding})))
      .toEqual({font:'16px',padding:'8px 16px'});
  } finally {await page.close();}
},30_000);
