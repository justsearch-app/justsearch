// @vitest-environment happy-dom
// Copied from the independent verifier (verify-vdu6/ownLegacyMount.test.ts); only paths adjusted.
import {it,expect,vi,afterEach} from 'vitest';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {loadConversations,__resetConversationListForTest,setConversationApiBase} from './conversationListStore.js';
vi.mock('../api/authorizedFetch.js',()=>({authorizedFetch:(...args:unknown[])=>globalThis.fetch(...args as Parameters<typeof fetch>)}));
const TREE=resolve(import.meta.dirname,'../../../../..');
afterEach(()=>{vi.unstubAllGlobals();localStorage.clear();__resetConversationListForTest();});
it('automatic mount list hydration with retained legacy titles emits no user-action request',async()=>{
 __resetConversationListForTest();localStorage.setItem('jf-conversation-titles',JSON.stringify({'verify-six':'Retained prior title'}));setConversationApiBase('');
 const calls:{url:string,method:string}[]=[];
 vi.stubGlobal('fetch',vi.fn(async(url:unknown,init?:RequestInit)=>{calls.push({url:String(url),method:init?.method??'GET'});return new Response(JSON.stringify(String(url).endsWith('/title')?{ok:true}:{sessions:[{sessionId:'verify-six',createdAtMs:1,lastActiveAtMs:2,messageCount:1,shapeId:'core.rag-ask'}]}),{status:200,headers:{'Content-Type':'application/json'}});}));
 await loadConversations();await new Promise(resolve=>setTimeout(resolve,25));
 const routes=readFileSync(TREE+'/modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java','utf8');
 expect(routes).toMatch(/\/title[^\n]*ActivityRole.USER_ACTION/);
 expect(calls.filter(c=>c.method==='POST'&&c.url.endsWith('/title')),JSON.stringify(calls)).toEqual([]);
});

it('control: fresh automatic list hydration sends only the observation GET',async()=>{
 __resetConversationListForTest();localStorage.clear();setConversationApiBase('');const calls:{url:string,method:string}[]=[];
 vi.stubGlobal('fetch',vi.fn(async(url:unknown,init?:RequestInit)=>{calls.push({url:String(url),method:init?.method??'GET'});return new Response(JSON.stringify({sessions:[{sessionId:'verify-six',createdAtMs:1,lastActiveAtMs:2,messageCount:1,shapeId:'core.rag-ask'}]}),{status:200,headers:{'Content-Type':'application/json'}});}));
 await loadConversations();await new Promise(resolve=>setTimeout(resolve,25));expect(calls).toEqual([{url:'/api/chat/conversations?limit=20',method:'GET'}]);
});
