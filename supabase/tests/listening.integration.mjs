// Isolated PostgreSQL/WASM: never connects to the configured Supabase project.
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../build/social-db-check/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const db = new PGlite();
const base = await readFile(new URL('../migrations/20261111030000_troit_connect.sql', import.meta.url), 'utf8');
const migration = await readFile(new URL('../migrations/20261115020000_troit_listening.sql', import.meta.url), 'utf8');
const uid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const alice=uid(1), bob=uid(2);
const song = n => ({ title:`Track ${n}`,artist:'Artist',url:`https://music.example/track-${n}`,artwork:null,duration:240 });
let checks=0;
function check(ok,message) { assert.ok(ok,message); checks++; console.log(`PASS ${message}`); }
async function as(user,sql,args=[],role='authenticated') {
  await db.exec(`set role ${role}`);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[user??'']);
  try { return await db.query(sql,args); }
  finally { await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub','',false)"); }
}
async function rpc(user,name,args=[]) {
  assert.match(name,/^troit_listening_[a-z_]+$/);
  return (await as(user,`select public.${name}(${args.map((_,i)=>`$${i+1}`).join(',')}) as result`,args)).rows[0]?.result;
}
async function rejects(work,message,code) {
  let failure; try { await work(); } catch(error) { failure=error; }
  assert.ok(failure,`Expected rejection: ${message}`);
  if(code) assert.equal(failure.code,code,`${message}: ${failure.message}`);
  check(true,message);
}
async function cool() { await db.exec("update public.troit_connect_limits set last_at='2000-01-01',window_start='2000-01-01',count=0"); }
const likes=(after=0,limit=100,user=alice)=>rpc(user,'troit_listening_likes',[after,limit]);
async function like(item,desired,revision=0,id=uid(100),user=alice) {
  await cool(); return rpc(user,'troit_listening_set_like',[id,JSON.stringify(item),desired,revision]);
}
async function record(item= song('A'),id=uid(200),played=Date.now(),user=alice) {
  await cool(); return rpc(user,'troit_listening_record',[id,JSON.stringify(item),played,6000,'Living room TV']);
}
const history=(after=0,limit=100,user=alice)=>rpc(user,'troit_listening_history',[after,limit]);

try {
  await db.exec(`create role anon; create role authenticated; create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to anon,authenticated;
    insert into auth.users(id) values('${alice}'),('${bob}');`);
  await db.exec(base); await db.exec(migration); await db.exec(migration);
  check(true,'Listening migration is additive and can be reapplied');
  const tables=['troit_listening_likes','troit_listening_like_receipts','troit_listening_history','troit_listening_history_receipts'];
  const count=(await db.query("select count(*)::int as n from pg_class where relname=any($1) and relrowsecurity",[tables])).rows[0].n;
  check(count===4,'Every listening table has row-level security');
  for(const table of tables) {
    await rejects(()=>as(alice,`select * from public.${table}`),`Direct reads denied for ${table}`,'42501');
    await rejects(()=>as(alice,`delete from public.${table}`),`Direct deletes denied for ${table}`,'42501');
  }
  await rejects(()=>as(null,'select public.troit_listening_likes()',[],'anon'),'Anonymous likes reads denied','42501');
  await rejects(()=>likes(0,100,null),'Authenticated role without identity rejected','28000');
  await rejects(()=>rpc(alice,'troit_listening_validate_song',[JSON.stringify(song('A'))]),'Private metadata validator cannot be called by clients','42501');
  check((await likes()).items.length===0,'Fresh account starts with no invented likes');
  check((await history()).items.length===0,'Fresh account starts with no invented listening history');
  let first=await like(song('A'),true);
  check(!first.conflict && first.row.liked && first.row.song.url===song('A').url,'One-tap like is stored with canonical song metadata');
  check(first.row.revision>0 && !('user_id' in first.row),'Likes receive a server revision without exposing account identity');
  check((await likes(0,100,bob)).items.length===0,'Other accounts cannot read Alice likes');
  const retry=await like(song('A'),true,0,uid(100));
  check(retry.row.revision===first.row.revision,'Retry of the same UUID returns original like receipt');
  await rejects(()=>like(song('A'),false,0,uid(100)),'Reusing a like UUID with different intent is rejected');
  const stale=await like(song('A'),false,0,uid(101));
  check(stale.conflict && stale.row.liked,'Stale offline unlike cannot overwrite a newer device like');
  const removed=await like(song('A'),false,first.row.revision,uid(102));
  check(!removed.conflict && !removed.row.liked && removed.row.revision>first.row.revision,'Unlike creates a newer tombstone');
  const delta=await likes(first.row.revision);
  check(delta.items.length===1 && !delta.items[0].liked,'Unlike tombstone reaches another device through incremental sync');
  const concurrent=await like(song('A'),true,first.row.revision,uid(103));
  check(concurrent.conflict && !concurrent.row.liked,'Concurrent older like cannot resurrect an unlike');
  const restored=await like(song('A'),true,removed.row.revision,uid(104));
  check(!restored.conflict && restored.row.liked,'A deliberate new like with the current revision succeeds');
  const cursor=await likes(restored.row.revision);
  check(cursor.items.length===0 && cursor.cursor===restored.row.revision,'Unchanged likes poll returns an empty bounded delta');
  await rejects(()=>likes(-1),'Negative like cursor rejected');
  await rejects(()=>likes(0,101),'Unbounded like page rejected');
  await rejects(()=>like({...song('bad'),url:'file:///private/music'},true,0,uid(110)),'Local file URLs never enter account metadata');
  await rejects(()=>like({...song('bad'),title:''},true,0,uid(111)),'Empty song title rejected');
  await rejects(()=>like({...song('bad'),duration:999999},true,0,uid(112)),'Out-of-range duration rejected');
  await rejects(()=>like({...song('bad'),artwork:'http://insecure.example/art'},true,0,uid(113)),'Insecure artwork rejected');
  await rejects(()=>like({...song('bad'),title:'x'.repeat(501)},true,0,uid(114)),'Oversized song metadata rejected');

  const played=Date.now();
  check(!(await record(song('A'),uid(200),played)).duplicate,'A qualified actual play creates one history event');
  check((await record(song('A'),uid(200),played)).duplicate,'Lost history upload response retries the exact original event');
  await rejects(()=>record(song('B'),uid(200),played),'A history UUID cannot be reused for a different recording');
  const h1=await history();
  check(h1.items.length===1 && h1.items[0].device_name==='Living room TV' && h1.items[0].position_ms===6000,'History shares song, source device and qualified position');
  check((await history(0,100,bob)).items.length===0,'Another account cannot read listening history');
  await record(song('A'),uid(201),played+1);
  check((await history()).items.length===2,'A later genuine repeat of the same recording keeps its own occurrence');
  check((await history(h1.cursor)).items.length===1,'History cursor returns only the later play');
  await rejects(()=>history(0,1000),'History pages remain bounded');
  await rejects(()=>record(song('A'),uid(202),Date.now()+600000),'History events cannot be forged far in the future');
  await rejects(()=>record(song('A'),uid(203),Date.now()-2678400000),'Events older than the offline retention window are rejected');
  await cool();
  await db.query("update public.troit_connect_limits set window_start=clock_timestamp(),count=120 where user_id=$1 and action='listening-like-minute'",[alice]);
  await rejects(()=>rpc(alice,'troit_listening_set_like',[uid(120),JSON.stringify(song('rate')),true,0]),'Like writes obey their per-minute budget');
  await cool();

  // Fixtures are inserted as owner to exercise bounds without issuing thousands of rate-limited requests.
  await db.query(`insert into public.troit_listening_likes(user_id,track_key,song,liked,revision)
    select $1,encode(sha256(convert_to('https://fixture/'||n,'UTF8')),'hex'),
      jsonb_build_object('title','Fixture '||n,'artist','Artist','url','https://fixture/'||n,'artwork',null,'duration',180),true,nextval('public.troit_listening_revision')
    from generate_series(1,999) n`,[alice]);
  await rejects(()=>like(song('overflow'),true,0,uid(121)),'Account liked-song bound is enforced');
  const page1=await likes(0,100); const page2=await likes(page1.cursor,100);
  check(page1.items.length===100 && page1.has_more && page2.items.length===100 && page2.items[0].revision>page1.cursor,'Likes use stable cursor pagination');
  const unlike=await like(song('A'),false,restored.row.revision,uid(122));
  check(!unlike.conflict && !unlike.row.liked,'Unlike remains possible when favorites reach capacity');
  check(!(await like(song('overflow'),true,0,uid(123))).conflict,'Unlike frees space for a new favorite');
  await db.query(`insert into public.troit_listening_history(user_id,id,seq,song,played_at,position_ms,device_name)
    select $1,('00000000-0000-0000-0001-'||lpad(n::text,12,'0'))::uuid,nextval('public.troit_listening_revision'),
      jsonb_build_object('title','History '||n,'artist','Artist','url','https://fixture/history/'||n,'artwork',null,'duration',180),$2::bigint+n,6000,'Phone'
    from generate_series(1,500) n`,[alice,played+1000]);
  await record(song('latest'),uid(204),played+3000);
  check((await db.query('select count(*)::int as n from public.troit_listening_history where user_id=$1',[alice])).rows[0].n===500,'History retains at most 500 recent events');
  check((await record(song('A'),uid(200),played)).duplicate,'Pruned old history cannot be reinserted by a retry');
  check((await db.query('select count(*)::int as n from public.troit_listening_history where user_id=$1',[alice])).rows[0].n===500,'Retry does not evict newer listening history');
  await db.exec(migration);
  check((await likes()).items.length>0,'Reapplying the migration preserves favorites and tombstones');
  console.log(`\n${checks} listening database checks passed.`);
} finally { await db.close(); }
