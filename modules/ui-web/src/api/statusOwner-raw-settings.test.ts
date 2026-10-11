// @vitest-environment happy-dom
import {it,expect,vi} from 'vitest';
import {bindStatusRefresh} from './statusInvalidation.ts';
import {createDataApi} from '../shell-v0/plugin-api/capabilities/data.ts';
it('OWN-RAW-SETTINGS: a successful trusted raw settings completion reaches canonical status',async()=>{
 const key='01993ba0-0000-7000-8000-000000000001';
 const receipt={state:'COMPLETE',operationKey:key,witness:{acceptedRevision:1,lastCommittedOperationKey:key}};
 const refresh=vi.fn(async()=>{}),unbind=bindStatusRefresh(refresh);
 vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify(receipt),{headers:{'content-type':'application/json','X-JustSearch-Status-Epoch':'test','X-JustSearch-Status-Change':'1'}})));
 try{
  const api=createDataApi('TRUSTED_PLUGIN','');
  const response=await api.fetch('/api/settings/v2',{method:'POST',body:JSON.stringify({witness:{acceptedRevision:0,lastCommittedOperationKey:null},operationKey:key,ui:{chatEnabled:false}}),headers:{'Content-Type':'application/json'}});
  expect(await response.json()).toEqual(receipt);
  expect(refresh,'raw trusted plugin writes share the canonical completion owner').toHaveBeenCalledTimes(1);
 }finally{unbind();vi.unstubAllGlobals();}
});
