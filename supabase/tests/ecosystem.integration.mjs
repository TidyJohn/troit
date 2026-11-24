// Isolated PostgreSQL/WASM checks; never connects to the live Supabase project.
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../build/social-db-check/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const db = new PGlite();
const base = await readFile(new URL('../migrations/20261111030000_troit_connect.sql', import.meta.url), 'utf8');
const migration = await readFile(new URL('../migrations/20261115000000_troit_ecosystem.sql', import.meta.url), 'utf8');
const preloadMigration = await readFile(new URL('../migrations/20261124000000_troit_handoff_preload.sql', import.meta.url), 'utf8');
const uid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const alice=uid(1),bob=uid(2),pc=uid(10),phone=uid(11),tv=uid(12),pcRun=uid(20),phoneRun=uid(21),tvRun=uid(22);
const song = n => ({title:`Track ${n}`,artist:'Artist',url:`https://www.youtube.com/watch?v=track${n}`,artwork:null,duration:240});
const songs = [song('A'),song('B'),song('A'),song('D')]; // Deliberate repeated occurrence.
let checks=0, commandNumber=100;
function check(ok, message) { assert.ok(ok,message); checks++; console.log(`PASS ${message}`); }
async function as(user,sql,args=[],role='authenticated') {
  assert.ok(['authenticated','anon'].includes(role));
  await db.exec(`set role ${role}`);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[user??'']);
  try { return await db.query(sql,args); }
  finally { await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub','',false)"); }
}
async function rpc(user,name,args=[]) {
  assert.match(name,/^troit_(ecosystem|connect)_[a-z_]+$/);
  return (await as(user,`select public.${name}(${args.map((_,i)=>`$${i+1}`).join(',')}) as result`,args)).rows[0]?.result;
}
async function rejects(work,message,code) {
  let failure; try { await work(); } catch(error) { failure=error; }
  assert.ok(failure,`Expected rejection: ${message}`);
  if(code) assert.equal(failure.code,code,`${message}: ${failure.message}`);
  check(true,message);
}
async function cool() { await db.exec("update public.troit_connect_limits set last_at='2000-01-01',window_start='2000-01-01',count=0"); }
async function heartbeat(device=pc,instance=pcRun,name='PC',kind='pc',intent=0,user=alice) {
  await cool(); return rpc(user,'troit_ecosystem_heartbeat',[device,instance,name,kind,intent]);
}
async function play(queue=songs,index=0,playing=true,device=pc,instance=pcRun,position=12000) {
  await cool(); const s=queue[index];
  return rpc(alice,'troit_connect_publish',[device,instance,'Generic app name',s.url,s.title,s.artist,s.artwork,s.duration,index,playing,position,true]);
}
async function publish(queue=songs,index=0,playing=true,device=pc,instance=pcRun,mode='STOP',position=12000) {
  await cool();
  return rpc(alice,'troit_ecosystem_publish_queue',[device,instance,JSON.stringify(queue),index,position,playing,mode]);
}
const getQueue = (revision=0,user=alice) => rpc(user,'troit_ecosystem_queue',[revision]);
async function edit(queue,operation,from=-1,to=-1,added=[],id=uid(commandNumber++),user=alice,observed=queue.updated_at) {
  await cool(); return rpc(user,'troit_ecosystem_edit',[id,queue.device_id,queue.instance_id,queue.revision,operation,from,to,JSON.stringify(added),observed]);
}
async function transfer(queue,device=tv,instance=tvRun,id=uid(commandNumber++),user=alice,observed=queue.updated_at,intent=null) {
  await cool();
  const target=(await rpc(user,'troit_ecosystem_devices')).find(d=>d.device_id===device);
  return rpc(user,'troit_ecosystem_transfer',[id,queue.device_id,queue.instance_id,queue.revision,device,instance,observed,intent??target?.intent_revision??0]);
}
const claim=(device=pc,instance=pcRun,user=alice)=>rpc(user,'troit_ecosystem_claim',[device,instance]);
const status=(id,user=alice)=>rpc(user,'troit_ecosystem_status',[id]);
const ack=(id,result='executed',device=pc,instance=pcRun,user=alice)=>rpc(user,'troit_ecosystem_ack',[id,device,instance,result]);
const activate=(id,device=tv,instance=tvRun,user=alice)=>rpc(user,'troit_ecosystem_activate',[id,device,instance]);
async function applyEdit(queue,operation,from=-1,to=-1,added=[]) {
  const receipt=await edit(queue,operation,from,to,added);
  const command=await claim();
  assert.equal(command.id,receipt.id);
  await publish(command.queue,command.index,command.playing,pc,pcRun,command.mode);
  await ack(command.id);
  return getQueue();
}

try {
  await db.exec(`create role anon; create role authenticated; create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to anon,authenticated;
    insert into auth.users(id) values('${alice}'),('${bob}');`);
  await db.exec(base); await db.exec(migration); await db.exec(migration);
  await db.exec(preloadMigration); await db.exec(preloadMigration);
  check(true,'Ecosystem migration applies additively and can be reapplied');
  for(const table of ['troit_ecosystem_devices','troit_ecosystem_queues','troit_ecosystem_commands']) {
    check((await db.query('select relrowsecurity from pg_class where relname=$1',[table])).rows[0].relrowsecurity,`${table} enables row-level security`);
    await rejects(()=>as(alice,`select * from public.${table}`),`Direct reads denied for ${table}`,'42501');
    await rejects(()=>as(alice,`delete from public.${table}`),`Direct deletes denied for ${table}`,'42501');
  }
  await rejects(()=>as(null,'select public.troit_ecosystem_devices()',[],'anon'),'Anonymous device discovery denied','42501');
  await rejects(()=>rpc(null,'troit_ecosystem_devices'),'Authenticated role without identity denied','28000');
  await rejects(()=>rpc(alice,'troit_ecosystem_validate_queue',[JSON.stringify(songs)]),'Private queue validator is not client-callable','42501');
  check((await rpc(alice,'troit_ecosystem_devices')).length===0 && await getQueue()===null,'New account has no invented receiver or queue');
  await heartbeat(); await heartbeat(phone,phoneRun,'Phone','phone'); await heartbeat(tv,tvRun,'Android TV','tv');
  let devices=await rpc(alice,'troit_ecosystem_devices');
  check(devices.length===3 && devices.every(d=>d.online&&!('user_id' in d)),'Idle PC, phone and TV appear online without exposing account identity');
  check(await rpc(alice,'troit_connect_state')===null,'Idle receiver registration never takes playback ownership');
  check((await rpc(bob,'troit_ecosystem_devices')).length===0,'Device registry is isolated between accounts');
  await rejects(()=>heartbeat(uid(13),uid(23),'Invalid','speaker'),'Unknown device kinds rejected');
  await rejects(()=>heartbeat(pc,pcRun,'\nHidden device','pc'),'Control characters rejected in device names');
  await rejects(()=>heartbeat(pc,pcRun,'PC','pc',-1),'Negative local intent revisions rejected');
  await rpc(alice,'troit_ecosystem_rename',[pc,'Bedroom PC']);
  await heartbeat(pc,pcRun,'PC','pc');
  check((await rpc(alice,'troit_ecosystem_devices')).some(d=>d.device_id===pc&&d.name==='Bedroom PC'),'Custom names survive generic heartbeat names');
  await rejects(()=>rpc(bob,'troit_ecosystem_rename',[pc,'Stolen']),'Other accounts cannot rename a receiver');
  await db.query("update public.troit_ecosystem_devices set updated_at=clock_timestamp()-interval '46 seconds' where device_id=$1",[tv]);
  check((await rpc(alice,'troit_ecosystem_devices')).some(d=>d.device_id===tv&&!d.online),'Receiver presence expires after 45 seconds');
  await heartbeat(tv,tvRun,'Android TV','tv');
  check(await publish()===null,'An idle receiver cannot publish a queue without actual playback ownership');
  await play();
  check((await rpc(alice,'troit_connect_state')).device_name==='Bedroom PC','Old widget snapshot also uses the registered custom device name');
  let queue=await publish();
  check(queue.queue.length===4 && queue.queue[0].url===queue.queue[2].url && queue.index===0,'Complete ordered queue retains duplicate occurrences');
  check(queue.mode==='STOP' && queue.playing && queue.position_ms===12000 && queue.fresh,'Queue exposes actual mode, position and playing state');
  check(await getQueue(0,bob)===null,'Another account cannot read the active full queue');
  let conditional=await getQueue(queue.revision);
  check(conditional.unchanged && conditional.queue===null && conditional.device_id===pc && conditional.index===0,'Unchanged queue polls return metadata without downloading all songs');
  const originalRevision=queue.revision;
  await play(songs,2,true);
  queue=await getQueue(originalRevision);
  check(queue.unchanged && queue.queue===null && queue.index===2 && queue.track_url===songs[2].url,'Natural progression updates metadata while reusing cached full queue');
  check(queue.playback_revision>1 && queue.revision===originalRevision,'Repeated URL at a new index gets playback identity independent of queue content revision');
  queue=await publish(songs,2,true,pc,pcRun,'REPEAT_ALL');
  check(queue.revision>originalRevision && queue.mode==='REPEAT_ALL','Changing playback mode advances the queue content revision');
  await play(songs,0,true); queue=await getQueue();
  const pending=await edit(queue,'add_next',-1,-1,[song('X')]);
  check((await getQueue()).queue.length===4,'Enqueueing an edit does not optimistically mutate the confirmed queue');
  await rejects(()=>edit(queue,'add_last',-1,-1,[song('Y')]),'Concurrent queue edits at one revision are serialized','40001');
  check(await claim(phone,phoneRun)===null && await status(pending.id,bob)===null && await claim(pc,pcRun,bob)===null,'Queue command receipts and receivers are account and device isolated');
  check(await claim(pc,uid(99))===null,'Another runtime on the same device cannot inherit queued edits');
  let command=await claim();
  check(command.kind==='edit' && command.queue[1].url===song('X').url && command.expected_queue.length===4 && command.index===0,'Queue edit claim contains the complete candidate and original queue guards');
  check(await claim()===null && (await status(pending.id)).status==='claimed','Claim consumes an edit before returning it; lost receipts cannot replay it');
  await ack(pending.id,'executed',pc,uid(99));
  check((await status(pending.id)).status==='claimed','A different runtime cannot acknowledge an edit');
  queue=await publish(command.queue,0,true,pc,pcRun,command.mode);
  await ack(pending.id);
  check((await status(pending.id)).status==='executed' && queue.queue.length===5,'Receiver publishes the applied queue before a truthful execution receipt');
  check((await db.query('select payload,octet_length(request::text) as size from public.troit_ecosystem_commands where id=$1',[pending.id])).rows.every(r=>Object.keys(r.payload).length===0&&r.size<1000),'Consumed commands release full queue payloads while retaining compact deduplication receipts');
  const oldQueue={...queue,revision:command.expected_revision};
  check((await edit(oldQueue,'add_next',-1,-1,[song('X')],pending.id,alice,'2000-01-01')).status==='executed','An accepted UUID retry returns its receipt even after the observed snapshot ages');
  await rejects(()=>edit(oldQueue,'add_next',-1,-1,[song('Y')],pending.id),'Reusing an edit UUID with different content is rejected');
  queue=await applyEdit(queue,'add_last',-1,-1,[song('Y')]);
  check(queue.queue.at(-1).url===song('Y').url,'Add to queue appends without replacing the current occurrence');
  queue=await applyEdit(queue,'move',1,queue.queue.length-1);
  check(queue.queue[0].url===songs[0].url && queue.queue.at(-1).url===song('X').url,'Move reorders an upcoming duplicate-safe occurrence without moving the current song');
  const beforeRemove=queue.queue.length;
  queue=await applyEdit(queue,'remove',1);
  check(queue.queue.length===beforeRemove-1 && queue.index===0,'Remove changes only an upcoming occurrence');
  await rejects(()=>edit(queue,'remove',0),'Removing the currently playing occurrence is rejected');
  await rejects(()=>edit(queue,'move',1,0),'Moving an upcoming song before the current occurrence is rejected');
  await rejects(()=>edit(queue,'remove',999),'Out-of-bounds queue mutation rejected');
  await rejects(()=>edit(queue,'add_next',-1,-1,[song('Z')],uid(300),alice,'2000-01-01'),'Old observed snapshots cannot enqueue queue edits','40001');
  await rejects(()=>edit(queue,'add_next',-1,-1,[song('Z')],uid(301),alice,'2099-01-01'),'Future observed snapshots cannot enqueue queue edits','40001');
  const noopId=uid(commandNumber++),noopBefore=queue;
  await edit(queue,'move',1,1,[],noopId);
  command=await claim(); await publish(command.queue,command.index,command.playing,pc,pcRun,command.mode); await ack(noopId);
  await db.query('delete from public.troit_ecosystem_commands where id=$1',[noopId]);
  await rejects(()=>edit(noopBefore,'move',1,1,[],noopId,alice,'2000-01-01'),'Aged retries remain rejected after receipt pruning even when queue content revision is unchanged','40001');
  await rejects(()=>publish([{...song('BAD'),url:'file:///private/music'}]),'Local/private URLs rejected from shared queue');
  await rejects(()=>publish([{...song('BAD'),duration:1.5}]),'Fractional duration rejected before integer casts');
  await rejects(()=>publish([{...song('BAD'),duration:999999999999}]),'Oversized duration rejected without unsafe numeric casting');
  await rejects(()=>publish([{...song('BAD'),title:'x'.repeat(501)}]),'Oversized song metadata rejected');
  await rejects(()=>publish(Array.from({length:1001},(_,n)=>song(n))),'Queues larger than 1000 songs are rejected');
  const changedTrack=await edit(queue,'add_last',-1,-1,[song('S')]);
  await play(queue.queue,1,true);
  check(await claim()===null && (await status(changedTrack.id)).status==='stale','A track/index change invalidates a delayed queue edit');
  await play(queue.queue,0,true); queue=await getQueue();
  const changedQueue=await edit(queue,'add_last',-1,-1,[song('S')]);
  queue=await publish([...queue.queue,song('Local')],0,true);
  check(await claim()===null && (await status(changedQueue.id)).status==='stale','A newer local queue revision invalidates a delayed remote edit');
  await rejects(()=>transfer(queue),'Switching a playing source before confirmed pause is rejected','40001');
  const preloadId=uid(commandNumber++);
  const preloadArgs=[preloadId,pc,pcRun,queue.revision,tv,tvRun,queue.updated_at,0];
  await rejects(()=>rpc(alice,'troit_ecosystem_preload',preloadArgs),'Legacy runtimes do not receive preload requests','40001');
  await cool(); await rpc(alice,'troit_ecosystem_heartbeat',[tv,tvRun,'TV','tv',0,true]);
  await rpc(alice,'troit_ecosystem_preload',preloadArgs);
  const preloadCommand=await claim(tv,tvRun);
  check(preloadCommand.kind==='preload' && preloadCommand.queue[preloadCommand.index].url===queue.queue[queue.index].url,
    'Selected runtime can preload the playing song before the source pauses');
  check(await activate(preloadId)===null && (await rpc(alice,'troit_connect_state')).playing,
    'Preload never changes ownership or interrupts the source');
  await ack(preloadId,'executed',tv,tvRun);
  await play(queue.queue,0,false,pc,pcRun,23456); queue=await getQueue();
  await db.query("update public.troit_ecosystem_devices set updated_at=clock_timestamp()-interval '46 seconds' where device_id=$1",[tv]);
  await rejects(()=>transfer(queue),'Offline target devices cannot receive playback','40001');
  await heartbeat(tv,tvRun,'TV','tv',7);
  await rejects(()=>transfer(queue,tv,tvRun,uid(302),alice,queue.updated_at,6),'Target local intent changed during the pause handshake prevents enqueue','40001');
  const moved=await transfer(queue);
  check((await getQueue()).device_id===pc && (await rpc(alice,'troit_connect_state')).playing===false,'Transfer freezes the paused source without optimistic ownership changes');
  check(await claim(pc,pcRun)===null && await claim(phone,phoneRun)===null,'Only the chosen idle target can receive a transfer');
  command=await claim(tv,tvRun);
  check(command.kind==='transfer' && command.queue.length===queue.queue.length && command.index===queue.index && command.position_ms===23456
    && command.expected_intent_revision===7 && command.playing && command.mode===queue.mode,'Idle TV receives the full queue, mode and exact paused position with a local-intent guard');
  check(await claim(tv,tvRun)===null,'A transfer is consumed before playback, preventing duplicate restart');
  check(await activate(moved.id,tv,uid(999))===null && await activate(moved.id,tv,tvRun,bob)===null,'Only the exact account and claimed receiver can activate prepared playback');
  const activated=await activate(moved.id);
  check(activated.device_id===tv && !activated.playing && activated.position_ms===23456,'Activation atomically moves ownership to the silent prepared target');
  const activatedAgain=await activate(moved.id);
  check(activatedAgain.revision===activated.revision && !activatedAgain.playing && activatedAgain.position_ms===23456,'Lost activation response retries do not reset or duplicate playback ownership');
  await play(command.queue,command.index,true,tv,tvRun,command.position_ms);
  check((await activate(moved.id)).playing,'Activation retry after target playback starts never pauses the playing target');
  const tvQueue=await publish(command.queue,command.index,true,tv,tvRun,command.mode,command.position_ms);
  await ack(moved.id,'executed',tv,tvRun);
  check(tvQueue.device_id===tv && tvQueue.revision>queue.revision && (await status(moved.id)).status==='executed','New device ownership keeps queue revisions monotonic and publishes before acknowledgment');
  await rejects(()=>edit(queue,'add_last',-1,-1,[song('Stale')]),'Old source queue cannot mutate the newly active TV','40001');
  await play(tvQueue.queue,0,false,tv,tvRun); let source=await getQueue();
  let duringPreparation=await transfer(source,phone,phoneRun);
  await claim(phone,phoneRun);
  await play(tvQueue.queue,0,true,tv,tvRun);
  check(await activate(duringPreparation.id,phone,phoneRun)===null && (await status(duringPreparation.id)).status==='stale'
    && (await rpc(alice,'troit_connect_state')).device_id===tv,'Source resume while target prepares cancels activation instead of stealing new playback');
  await play(tvQueue.queue,0,false,tv,tvRun); source=await getQueue();
  duringPreparation=await transfer(source,phone,phoneRun);
  await claim(phone,phoneRun);
  await heartbeat(tv,tvRun,'TV','tv',8);
  check(await activate(duringPreparation.id,phone,phoneRun)===null,'A newer source intent still cancels activation after the source is paused again');
  source=await getQueue();
  duringPreparation=await transfer(source,phone,phoneRun);
  await claim(phone,phoneRun);
  await publish([...source.queue,song('New source queue')],0,false,tv,tvRun,source.mode);
  check(await activate(duringPreparation.id,phone,phoneRun)===null,'Source queue changes while preparing prevent activation');
  source=await getQueue();
  let uncertain=await transfer(source,phone,phoneRun);
  await heartbeat(phone,phoneRun,'Phone','phone',1);
  check(await claim(phone,phoneRun)===null && (await status(uncertain.id)).status==='stale','Target local playback intent after enqueue cancels a delayed transfer');
  uncertain=await transfer(source,phone,phoneRun);
  await play(tvQueue.queue,1,false,tv,tvRun);
  check(await claim(phone,phoneRun)===null && (await status(uncertain.id)).status==='stale','Source track changes after pause cancel a delayed transfer');
  await play(tvQueue.queue,0,false,tv,tvRun); source=await getQueue();
  uncertain=await transfer(source,phone,phoneRun);
  await heartbeat(phone,uid(400),'Phone','phone',0);
  check((await status(uncertain.id)).status==='stale' && await claim(phone,uid(400))===null,'Restarted receivers cannot inherit the previous runtime transfer');
  uncertain=await transfer(source,phone,uid(400));
  await db.query("update public.troit_ecosystem_commands set expires_at=clock_timestamp()-interval '1 second' where id=$1",[uncertain.id]);
  check((await status(uncertain.id)).status==='expired' && await claim(phone,uid(400))===null,'Expired transfer requests never start audio');
  const metadataHeavy=Array.from({length:500},(_,n)=>({...song(n),title:'T'.repeat(500),artist:'A'.repeat(500)}));
  await play(metadataHeavy,0,true); queue=await publish(metadataHeavy);
  await rejects(()=>edit(queue,'add_last',-1,-1,metadataHeavy.slice(0,300)),'Candidate queue metadata stays below bounded command-response size');
  await rejects(()=>publish([...metadataHeavy,...metadataHeavy]),'Full queue metadata limit is enforced independently of song count');
  const large=Array.from({length:1000},(_,n)=>song(n));
  await play(large,0,true); queue=await publish(large);
  check(await activate(moved.id)===null && (await rpc(alice,'troit_connect_state')).device_id===pc,'An old successful activation cannot retake a later playback owner');
  check(queue.queue.length===1000,'A full 1000-song queue round-trips without truncation');
  await rejects(()=>edit(queue,'add_last',-1,-1,[song('overflow')]),'Queue edits enforce the same 1000-song bound');
  await db.exec("update public.troit_connect_limits set count=60,window_start=clock_timestamp(),last_at=clock_timestamp() where action='eco-command'");
  await rejects(()=>rpc(alice,'troit_ecosystem_edit',[uid(900),queue.device_id,queue.instance_id,queue.revision,'move',1,1,'[]',queue.updated_at]),'Queue operations enforce their per-minute authenticated write budget');
  await db.query("update public.troit_ecosystem_devices set updated_at=clock_timestamp()-interval '31 days' where device_id=$1",[phone]);
  await heartbeat();
  check(!(await rpc(alice,'troit_ecosystem_devices')).some(d=>d.device_id===phone),'Devices not seen for 30 days are pruned from the registry');
  for(let n=0;n<18;n++) await heartbeat(uid(500+n),uid(600+n),`Room ${n}`,'tv');
  check((await rpc(alice,'troit_ecosystem_devices')).length===20,'Registry supports at most twenty recent devices');
  await rejects(()=>heartbeat(uid(700),uid(701),'Overflow','tv'),'A twenty-first receiver is rejected instead of growing the registry indefinitely');
  await db.exec(migration);
  check((await getQueue()).queue.length===1000,'Reapplying the migration preserves confirmed queue state');
  console.log(`\n${checks} ecosystem database checks passed.`);
} finally { await db.close(); }
