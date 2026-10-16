import {afterEach, describe, expect, it, vi} from 'vitest';
import {bindStatusRefresh, observeStatusResponse, statusSnapshotCanDisplay, statusSnapshotDisplayed} from './statusInvalidation';
import {request} from './http';
let release=()=>{};
afterEach(()=>{release();vi.unstubAllGlobals();});
const response=(epoch:string,change:number,reflected?:number)=>new Response('{}',{headers:{'X-JustSearch-Status-Epoch':epoch,'X-JustSearch-Status-Change':String(change),...(reflected===undefined?{}:{'X-JustSearch-Status-Reflected':String(reflected)})}});
describe('independent server publication boundary',()=>{
 it('a refused read-only request still observes a foreign completed publication',async()=>{
  const refresh=vi.fn(async()=>statusSnapshotDisplayed(response('A',2,2)));
  release=bindStatusRefresh(refresh);statusSnapshotDisplayed(response('A',1,1));
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('{"error":"refused","errorClass":"PERMANENT"}',{status:404,headers:{'content-type':'application/json','X-JustSearch-Status-Epoch':'A','X-JustSearch-Status-Change':'2'}})));
  await expect(request('http://localhost','/api/read-only',{retries:1})).rejects.toThrow();
  expect(refresh).toHaveBeenCalledTimes(1);
 });
 it('a newer foreign publication on a read-only response refreshes once and leaves the body readable',async()=>{
  const refresh=vi.fn(async()=>statusSnapshotDisplayed(response('A',2,2)));
  release=bindStatusRefresh(refresh);statusSnapshotDisplayed(response('A',1,1));
  const r=response('A',2);await observeStatusResponse('/api/settings/v2',r);
  expect(refresh).toHaveBeenCalledTimes(1);expect(await r.text()).toBe('{}');
  await observeStatusResponse('/api/settings/v2',response('A',2));expect(refresh).toHaveBeenCalledTimes(1);
 });
 it('a retired head response cannot replace a snapshot after a refused new-head read',async()=>{
  release=bindStatusRefresh(async()=>{});statusSnapshotDisplayed(response('A',3,3));
  await observeStatusResponse('/api/read-only',response('B',1));
  expect(statusSnapshotCanDisplay(response('A',4,4))).toBe(false);
 });
 it('missing metadata cannot certify an older response as current after a restart signal',async()=>{
  release=bindStatusRefresh(async()=>{});statusSnapshotDisplayed(response('A',3,3));
  await observeStatusResponse('/api/read-only',response('B',1));
  expect(statusSnapshotCanDisplay(new Response('{}'))).toBe(false);
 });
 it('an ordinary canonical poll with newer change metadata initiates a bounded fresh read',async()=>{
  const refresh=vi.fn(async()=>statusSnapshotDisplayed(response('A',2,2)));
  release=bindStatusRefresh(refresh);statusSnapshotDisplayed(response('A',1,1));
  const stalePoll=response('A',2,1);
  await observeStatusResponse('/api/status',stalePoll);statusSnapshotDisplayed(stalePoll);
  await Promise.resolve();await Promise.resolve();
  expect(refresh).toHaveBeenCalledTimes(1);
 });
 it('many read-only unchanged responses do not refresh',async()=>{
  const refresh=vi.fn(async()=>{});release=bindStatusRefresh(refresh);statusSnapshotDisplayed(response('A',2,2));
  await Promise.all(Array.from({length:40},()=>observeStatusResponse('/api/read-only',response('A',2))));
  expect(refresh).not.toHaveBeenCalled();
 });
});
