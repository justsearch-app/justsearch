// @vitest-environment happy-dom
import {it,expect,vi,afterEach} from 'vitest';
import fixture from './__fixtures__/status-response-live.json';
import {consumeShapeStream} from './streams.ts';
import {subscribeStatus,__resetStatusPollForTest} from '../shell-v0/utils/statusPoll.ts';
afterEach(()=>{__resetStatusPollForTest();vi.useRealTimers();vi.unstubAllGlobals();});
it('OWN-REATTACH-READONLY: a resumed read-only execution causes zero fresh reads',async()=>{
 vi.useFakeTimers();const urls:string[]=[];let reads=0;let delivered=0;
 const completion={event:'tool_exec_completed',payload:{callId:'read',success:true,executionId:'read-execution',output:'Read complete'}};
 const frames=(events:{event:string,payload:unknown}[])=>new Response(events.map(e=>`event: ${e.event}\ndata: ${JSON.stringify(e.payload)}\n\n`).join(''),{headers:{'content-type':'text/event-stream'}});
 vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
  const url=String(input);urls.push(url);
  if(url.includes('/api/status')){if(url.includes('fresh=true'))reads++;return new Response(JSON.stringify(fixture));}
  return frames(url.includes('sinceSeq=20') ? [completion,{event:'done',payload:{}}] : [{event:'tool_exec_started',payload:{callId:'read',toolName:'core_read_document'}},completion,{event:'done',payload:{}}]);
 }));
 const stop=subscribeStatus(()=>{});await vi.advanceTimersByTimeAsync(0);
 try{
  await consumeShapeStream('/api/chat/dispatch',{shapeId:'core.agent-run'},e=>{if(e==='tool_exec_completed')delivered++;});
  await consumeShapeStream('/api/chat/runs/read/stream',{},e=>{if(e==='tool_exec_completed')delivered++;},undefined,{sinceSeq:20});
  expect(delivered).toBe(2);expect(urls.some(u=>u.includes('sinceSeq=20'))).toBe(true);
  expect(reads,'replaying a successful read does not mutate any status state').toBe(0);
 }finally{stop();}
});
