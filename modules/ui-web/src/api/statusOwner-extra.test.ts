import {it,expect,vi} from 'vitest';
import {bindStatusRefresh,observeStatusResponse,observeStatusEvent,statusSnapshotDisplayed} from './statusInvalidation.ts';
import {OperationClient} from '../shell-v0/operations/OperationClient.ts';
const json=(x:unknown,revision=0)=>new Response(JSON.stringify(x),{headers:{'content-type':'application/json','X-JustSearch-Status-Epoch':'test','X-JustSearch-Status-Change':String(revision),'X-JustSearch-Status-Reflected':String(revision)}});
const createStatusExecutionObserver=()=> (_event:string,payload:unknown)=>observeStatusEvent(payload);
const statusWriteCompleted=(url:string,_init:RequestInit,response:Response)=>observeStatusResponse(url,response);
it('OWN-READONLY-APPROVAL: known read-only pending execution must not refresh',async()=>{
 const refresh=vi.fn(async()=>{});const unbind=bindStatusRefresh(refresh);
 try{
  const client=new OperationClient({apiBase:'',fetchImpl:async(input)=>String(input).includes('/pending/') ? json({pendingId:'pending-readonly',operationId:'core.search-index',argsSummary:'Search only',sourceTier:'UNTRUSTED',riskTier:'LOW',gateBehavior:'INLINE_CONFIRM',rationale:'read-only'}) : json({executed:true,executeSuccess:true,executeMessage:'Search completed'})});
  expect((await client.peekPending('pending-readonly'))?.operationId).toBe('core.search-index');
  await client.approveAndExecutePending('pending-readonly');
  expect(refresh).toHaveBeenCalledTimes(0);
 }finally{unbind();}
});
it('OWN-REPLAY-READONLY: duplicate read-only completion must remain read-only',async()=>{
 const refresh=vi.fn(async()=>{});const unbind=bindStatusRefresh(refresh);
 try{
  const observe=createStatusExecutionObserver();
  await observe('tool_exec_started',{callId:'read',toolName:'core_get_status'});
  await observe('tool_exec_completed',{callId:'read',success:true,executionId:'same-read'});
  await observe('tool_exec_completed',{callId:'read',success:true,executionId:'same-read'});
  expect(refresh).toHaveBeenCalledTimes(0);
 }finally{unbind();}
});
it('OWN-REPLAY-MUTATION: one execution delivered twice causes one read',async()=>{
 const refresh=vi.fn(async()=>{statusSnapshotDisplayed(json({},1));});const unbind=bindStatusRefresh(refresh);
 try{
  const observe=createStatusExecutionObserver();
  await observe('tool_exec_started',{callId:'write',toolName:'core_set_chat_enabled'});
  await observe('tool_exec_completed',{callId:'write',success:true,executionId:'same-write',statusChangeEpoch:'test',statusChangeCounter:1});
  await observe('tool_exec_completed',{callId:'write',success:true,executionId:'same-write',statusChangeEpoch:'test',statusChangeCounter:1});
  expect(refresh).toHaveBeenCalledTimes(1);
 }finally{unbind();}
});
it('OWN-RECEIPT-BODY: observing a raw plugin receipt preserves caller body',async()=>{
 const refresh=vi.fn(async()=>{});const unbind=bindStatusRefresh(refresh);
 try{
  const response=json({success:true,data:{x:1}},1);
  await statusWriteCompleted('/api/operations/core.set-chat-enabled/invoke',{method:'POST'},response);
  expect(await response.json()).toEqual({success:true,data:{x:1}});expect(refresh).toHaveBeenCalledTimes(1);
 }finally{unbind();}
});
it('OWN-FAILED-READ: failed and consent-only approvals cause zero reads',async()=>{
 const refresh=vi.fn(async()=>{});const unbind=bindStatusRefresh(refresh);
 try{
  for(const value of [{executed:true,executeSuccess:false},{executed:false,executeSuccess:true},{success:true,consentCapsule:'cap'}]){
   await statusWriteCompleted('/api/authorizations/approve',{method:'POST'},json(value));
  }
  expect(refresh).toHaveBeenCalledTimes(0);
 }finally{unbind();}
});
