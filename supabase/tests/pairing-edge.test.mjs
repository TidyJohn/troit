// Node 24 strips TypeScript types; all upstream Auth and SQL calls are fixtures.
// node supabase/tests/pairing-edge.test.mjs
import assert from 'node:assert/strict';
import { createPairingHandler } from '../functions/device-pairing/handler.ts';
let checks=0;
const check=(ok,message)=>{assert.ok(ok,message);checks++;console.log(`PASS ${message}`);};
const id='00000000-0000-0000-0000-000000000010', user='00000000-0000-0000-0000-000000000001';
const secret='a'.repeat(64), service='fixture-service-secret-never-returned', anon='fixture-public-key';
let status='pending', envelope=null, issues=0, saves=0;
const calls=[];
const session={access_token:'new-independent-tv-access',refresh_token:'new-independent-tv-refresh',expires_in:3600,user:{id:user,email:'account@example.invalid'}};
const handler=createPairingHandler({url:'https://project.invalid',serviceKey:service,publishableKey:anon,fetch:async(url,options)=>{
  const path=new URL(url).pathname;
  const body=options.body?JSON.parse(options.body):null;
  calls.push({path,body,headers:options.headers});
  let response;
  if(path.endsWith('troit_pairing_create')) response={id,status:'pending',expires_at:new Date(Date.now()+600000).toISOString()};
  else if(path.endsWith('troit_pairing_poll')) response=status==='ready'?{status:'ready',encrypted_session:envelope}:status==='approved'?{status:'issue',user_id:user}: {status};
  else if(path.endsWith('troit_pairing_save')) {saves++; envelope=body.p_encrypted_session;status='ready';response={status:'ready'};}
  else if(path.endsWith('troit_pairing_cancel')) {status=body.p_complete?'complete':'cancelled';envelope=null;response=null;}
  else if(path===`/auth/v1/admin/users/${user}`) response={id:user,email:'account@example.invalid',email_confirmed_at:new Date().toISOString()};
  else if(path==='/auth/v1/admin/generate_link') {issues++;response={hashed_token:'fixture-one-time-hash'};}
  else if(path==='/auth/v1/verify') response=session;
  else throw new Error(`Unexpected fixture path ${path}`);
  return new Response(JSON.stringify(response),{status:200});
}});
const request=body=>new Request('https://edge.invalid/device-pairing',{method:'POST',headers:{'content-type':'application/json','x-forwarded-for':'192.0.2.1'},body:JSON.stringify(body)});
const call=async(action,extra={})=>handler(request({action,id,secret,...extra}));
let result=await call('create',{code:'ABCD-EFGH',device_name:'Living room TV'});
check(result.ok,'Anonymous TV creation succeeds through the mediated endpoint');
check(result.headers.get('cache-control').includes('no-store'),'Capability and auth responses cannot be cached');
let body=await result.json();
check(body.code==='ABCD-EFGH' && !JSON.stringify(body).includes(secret),'Response displays the code without exposing the poll secret');
const create=calls[0];
check(create.body.p_secret_hash!==secret && create.body.p_code_hash.length===64,'SQL receives hashes instead of raw possession secret and code');
check(create.body.p_requester_hash.length===64 && !JSON.stringify(create.body).includes('192.0.2.1'),'Requester fingerprint is HMACed rather than storing an IP address');
check((await (await call('poll')).json()).status==='pending' && issues===0,'No auth session is issued before phone approval');
status='approved'; result=await call('poll'); body=await result.json();
check(body.session.refresh_token===session.refresh_token && body.session.access_token===session.access_token,'Approved TV receives its independently issued session');
check(issues===1 && saves===1,'Approval issues and caches one new auth session');
check(!envelope.includes(session.access_token) && !envelope.includes(session.refresh_token),'Database cache contains an AES-GCM envelope, not plaintext credentials');
const auth=calls.find(c=>c.path==='/auth/v1/verify');
check(auth.body.token_hash==='fixture-one-time-hash' && auth.body.type==='magiclink','New session uses the one-time Supabase token-hash verification contract');
check(auth.headers.apikey===anon && !auth.headers.Authorization.includes(service),'Token verification uses public Auth access rather than returning the server key');
body=await (await call('poll')).json();
check(body.session.refresh_token===session.refresh_token && issues===1,'Lost delivery can poll the same session again without minting another');
await call('complete');
check((await (await call('poll')).json()).status==='complete' && envelope===null,'Completion deletes delivery cache and prevents further credential delivery');
check((await call('create',{code:'ABCDEF01',device_name:'TV'})).status===400,'Ambiguous/malformed codes are rejected before database access');
check((await handler(new Request('https://edge.invalid',{method:'GET'}))).status===405,'Endpoint rejects unsupported methods');
check((await handler(request({action:'poll',id,secret:'wrong'}))).status===400,'Malformed possession capability is rejected');
check((await handler(request({action:'poll',id,secret,padding:'x'.repeat(5000)}))).status===413,'Stream body size is bounded even without Content-Length');
check(!JSON.stringify(body).includes(service),'No service credential appears in a public response');
let verified=false;
const denied=createPairingHandler({url:'https://project.invalid',serviceKey:service,publishableKey:anon,fetch:async(url)=>{
  let result;
  if(String(url).includes('troit_pairing_poll')) result={status:'issue',user_id:user};
  else { verified=true;result={id:user,email:'account@example.invalid',email_confirmed_at:new Date().toISOString(),factors:[{status:'verified'}]}; }
  return new Response(JSON.stringify(result));
}});
result=await denied(request({action:'poll',id,secret}));
check(verified && result.status===400 && !(await result.text()).includes('access_token'),'Verified MFA accounts cannot bypass a challenge through TV pairing');
console.log(`\n${checks} pairing Edge checks passed.`);
