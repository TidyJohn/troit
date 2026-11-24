// Isolated PostgreSQL/WASM; never uses real accounts or the configured live project.
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../build/social-db-check/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const db = new PGlite();
const migration = await readFile(new URL('../migrations/20261115010000_troit_device_pairing.sql', import.meta.url), 'utf8');
const uid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const hash = n => String(n).padStart(64, '0');
const alice = uid(1), bob = uid(2), id = uid(10), lease = uid(20), secret = hash(100), code = hash(200);
let checks = 0;
function check(ok, message) { assert.ok(ok, message); checks++; console.log(`PASS ${message}`); }
async function as(role, user, query, args = []) {
  assert.ok(['anon','authenticated','service_role'].includes(role));
  await db.exec(`set role ${role}`);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user ?? '']);
  try { return await db.query(query, args); }
  finally { await db.exec('reset role'); }
}
async function rpc(name, args = [], role = 'service_role', user = null) {
  assert.match(name, /^troit_pairing_[a-z_]+$/);
  return (await as(role, user, `select public.${name}(${args.map((_, i) => `$${i+1}`).join(',')}) as result`, args)).rows[0]?.result;
}
async function rejects(action, message) {
  let failure; try { await action(); } catch (error) { failure = error; }
  check(!!failure, message);
}
async function create(n = 10, requester = hash(300)) {
  return rpc('troit_pairing_create', [uid(n),hash(n+190),hash(n+90),'Living room TV',requester]);
}
const inspect = (h = code, user = alice) => rpc('troit_pairing_inspect',[h],'authenticated',user);
const approve = (h = code, target = id, user = alice) => rpc('troit_pairing_approve',[h,target],'authenticated',user);
const poll = (target = id, capability = secret, nonce = lease) => rpc('troit_pairing_poll',[target,capability,nonce]);
const cool = () => db.exec("update public.troit_pairing_limits set window_start='2000-01-01',count=0");
try {
  await db.exec(`create role anon; create role authenticated; create role service_role; create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to anon,authenticated,service_role;
    insert into auth.users values('${alice}'),('${bob}');`);
  await db.exec(migration); await db.exec(migration);
  check(true,'Pairing migration applies independently and is repeatable');
  check((await db.query("select count(*)::int n from pg_class where relname in ('troit_device_pairings','troit_pairing_limits') and relrowsecurity")).rows[0].n===2,'Pairing private tables enable RLS');
  for (const role of ['anon','authenticated']) {
    for (const table of ['troit_device_pairings','troit_pairing_limits'])
      await rejects(() => as(role,alice,`select * from public.${table}`),`${role} cannot read ${table}`);
    await rejects(() => rpc('troit_pairing_create',[id,code,secret,'TV',hash(300)],role,alice),`${role} cannot create pairing capabilities directly`);
    await rejects(() => rpc('troit_pairing_poll',[id,secret,lease],role,alice),`${role} cannot poll session envelopes directly`);
    await rejects(() => rpc('troit_pairing_save',[id,secret,lease,'x'.repeat(40)],role,alice),`${role} cannot inject session envelopes`);
  }
  await rejects(() => inspect(code,null),'Missing account cannot inspect a code');
  await rejects(() => rpc('troit_pairing_inspect',[code],'anon'),'Anonymous callers cannot inspect a code');
  const created = await create();
  check(created.status==='pending' && created.id===id && !('secret_hash' in created),'Creation returns only request id and expiry');
  check((new Date(created.expires_at)-Date.now())<=600001,'Codes have a ten-minute expiry');
  check(JSON.stringify(await create())===JSON.stringify(created),'Exact create retry is idempotent');
  await rejects(() => rpc('troit_pairing_create',[id,code,hash(999),'Living room TV',hash(300)]),'Existing id cannot be rebound to a new poll secret');
  check(await inspect(hash(201))===null,'Incorrect code reveals no device or account');
  const inspected = await inspect();
  check(inspected.id===id && inspected.device_name==='Living room TV' && !('approved_user_id' in inspected),'Signed-in phone inspects only public TV confirmation details');
  check((await poll()).status==='pending','TV waits until explicit phone approval');
  check((await poll(id,hash(999))).status==='unavailable','Wrong possession secret cannot poll another TV');
  await rejects(() => approve(code,uid(999)),'Approval must match the exact inspected pairing id');
  check((await approve()).status==='approved','Phone can explicitly approve its inspected TV code');
  check((await approve()).status==='approved','Lost approval response can retry without creating another session');
  await rejects(() => approve(code,id,bob),'Another account cannot replace an approved owner');
  check(await inspect()===null,'Approved codes cannot be reused for a new inspection');
  const issued = await poll();
  check(issued.status==='issue' && issued.user_id===alice && issued.lease===lease,'Exactly one poll receives the approved user and issuance lease');
  const duplicate = await poll(id,secret,uid(21));
  check(duplicate.status==='pending' && !('user_id' in duplicate),'Concurrent poll cannot mint a second auth session');
  await rejects(() => rpc('troit_pairing_save',[id,secret,uid(21),'x'.repeat(40)]),'Different issuance lease cannot save credentials');
  await rpc('troit_pairing_save',[id,secret,lease,'encrypted-only-fixture-'.repeat(3)]);
  const ready = await poll();
  check(ready.status==='ready' && ready.encrypted_session==='encrypted-only-fixture-'.repeat(3),'Correct TV capability receives the encrypted envelope');
  check((await poll()).encrypted_session===ready.encrypted_session,'Lost poll response retries the same session rather than creating a new login');
  await rpc('troit_pairing_cancel',[id,secret,true]);
  check((await poll()).status==='complete','TV completion removes delivered session access');
  check((await db.query('select encrypted_session from public.troit_device_pairings where id=$1',[id])).rows[0].encrypted_session===null,'Completion deletes the cached envelope');
  await cool(); await create(11); await rpc('troit_pairing_cancel',[uid(11),hash(101),false]);
  check((await poll(uid(11),hash(101))).status==='cancelled','TV cancel makes the code unusable');
  await rejects(() => approve(hash(201),uid(11)),'Cancelled TV code cannot be approved');
  await create(12); await db.query("update public.troit_device_pairings set expires_at=clock_timestamp()-interval '1 second' where id=$1",[uid(12)]);
  check(await inspect(hash(202))===null,'Expired codes cannot be inspected');
  await rejects(() => approve(hash(202),uid(12)),'Expired codes cannot be approved');
  check((await poll(uid(12),hash(102))).status==='expired','Expired capability polling returns no credentials');
  await create(13); await approve(hash(203),uid(13)); await poll(uid(13),hash(103));
  await db.query("update public.troit_device_pairings set issued_at=clock_timestamp()-interval '46 seconds' where id=$1",[uid(13)]);
  check((await poll(uid(13),hash(103))).status==='failed','Crashed issuance fails closed instead of silently issuing twice');
  await cool(); await create(14); await approve(hash(204),uid(14)); await poll(uid(14),hash(104));
  await rpc('troit_pairing_save',[uid(14),hash(104),lease,'encrypted-envelope-'.repeat(3)]);
  await db.query("update public.troit_device_pairings set ready_until=clock_timestamp()-interval '1 second' where id=$1",[uid(14)]);
  check((await poll(uid(14),hash(104))).status==='expired','Session retry cache expires after its short delivery window');
  check((await db.query('select encrypted_session from public.troit_device_pairings where id=$1',[uid(14)])).rows[0].encrypted_session===null,'Expired delivery cache scrubs encrypted credentials');
  await cool();
  for (let n=0;n<10;n++) await inspect(hash(999));
  await rejects(() => inspect(hash(999)),'Code guesses are bounded per authenticated account');
  await cool();
  for(let n=30;n<35;n++) await create(n,hash(500));
  await rejects(() => create(35,hash(500)),'Anonymous creation is bounded per requester fingerprint');
  await cool();
  for(let n=0;n<40;n++) await poll(uid(34),hash(124));
  await rejects(() => poll(uid(34),hash(124)),'Possession polling is bounded per TV request');
  await rejects(() => rpc('troit_pairing_rate',['anything',99999,1],'authenticated',alice),'Private rate helper cannot be invoked by clients');
  await rejects(() => rpc('troit_pairing_cleanup',[],'authenticated',alice),'Clients cannot trigger privileged cleanup');
  await db.query("update public.troit_device_pairings set status='ready',encrypted_session=$2,ready_until=clock_timestamp()-interval '1 second' where id=$1",[uid(34),'expired-envelope-fixture']);
  await rpc('troit_pairing_cleanup');
  check((await db.query('select status,encrypted_session from public.troit_device_pairings where id=$1',[uid(34)])).rows.every(row=>row.status==='expired' && row.encrypted_session===null),'Scheduled cleanup scrubs expired credential envelopes without a TV poll');
  console.log(`\n${checks} pairing database checks passed.`);
} finally { await db.close(); }
