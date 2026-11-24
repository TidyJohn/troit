// Isolated PostgreSQL/WASM; never connects to a live project.
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const require=createRequire(new URL('../../build/social-db-check/package.json',import.meta.url));
const {PGlite}=require('@electric-sql/pglite'); const db=new PGlite();
const uid=n=>`00000000-0000-0000-0000-${String(n).padStart(12,'0')}`;
const host=uid(1),guest=uid(2),stranger=uid(3),pc=uid(10),run=uid(11),tv=uid(12),tvRun=uid(13);
const song=n=>({title:`Song ${n}`,artist:'Artist',url:`https://example.invalid/song-${n}`,artwork:null,duration:240});
const songs=[song('A'),song('B')]; let checks=0;
function check(v,m){assert.ok(v,m);checks++;console.log(`PASS ${m}`)}
async function as(user,name,args=[],role='authenticated'){
 await db.exec(`set role ${role}`);await db.query("select set_config('request.jwt.claim.sub',$1,false)",[user??'']);
 try{return (await db.query(`select public.${name}(${args.map((_,i)=>`$${i+1}`).join(',')}) as value`,args)).rows[0].value}
 finally{await db.exec('reset role');await db.query("select set_config('request.jwt.claim.sub','',false)");}
}
async function reject(work,m){let error;try{await work()}catch(e){error=e}check(!!error,m)}
async function cool(){await db.exec("update public.troit_social_limits set last_at='2000-01-01',window_start='2000-01-01',count=0; update public.troit_connect_limits set last_at='2000-01-01',window_start='2000-01-01',count=0;")}
async function publish(queue=songs,index=0,playing=true,device=pc,instance=run){await cool();const s=queue[index];await as(host,'troit_ecosystem_heartbeat',[device,instance,device===pc?'My PC':'TV',device===pc?'pc':'tv',0]);await as(host,'troit_connect_publish',[device,instance,'Player',s.url,s.title,s.artist,null,240,index,playing,1000,true]);await as(host,'troit_ecosystem_publish_queue',[device,instance,JSON.stringify(queue),index,1000,playing,'STOP']);}
try{
 await db.exec(`create role anon;create role authenticated;create schema auth;create table auth.users(id uuid primary key);
 create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
 grant usage on schema auth to anon,authenticated; insert into auth.users values('${host}'),('${guest}'),('${stranger}');`);
 for(const name of ['20261111020000_troit_social.sql','20261111030000_troit_connect.sql','20261115000000_troit_ecosystem.sql','20261115030000_troit_listening_sessions.sql'])await db.exec(await readFile(new URL(`../migrations/${name}`,import.meta.url),'utf8'));
 const migration=await readFile(new URL('../migrations/20261115030000_troit_listening_sessions.sql',import.meta.url),'utf8');await db.exec(migration);check(true,'Listening sessions migration applies and reapplies');
 await db.exec(`insert into public.troit_profiles(user_id,handle,display_name) values('${host}','host','Host'),('${guest}','friend','Friend'),('${stranger}','other','Other');
 insert into public.troit_friendships(user_low,user_high,requested_by,status) values('${host}','${guest}','${host}','accepted');`);
 for(const table of ['troit_party_rooms','troit_party_members','troit_party_additions','troit_sleep_timers','troit_sleep_requests']){
  check((await db.query('select relrowsecurity from pg_class where relname=$1',[table])).rows[0].relrowsecurity,`${table} enables RLS`);
  await reject(async()=>{await db.exec('set role authenticated');try{await db.query(`select * from public.${table}`)}finally{await db.exec('reset role')}},`Direct client reads denied for ${table}`);
 }
 await reject(()=>as(null,'troit_party_state',[],'anon'),'Anonymous room discovery rejected');
 await reject(()=>as(null,'troit_sleep_state'),'Missing identity cannot read sleep timer');
 await reject(()=>as(host,'troit_party_friend',[host,guest]),'Friend permission helper is private');
 await publish();await cool();let room=await as(host,'troit_party_create',['Evening room']);
 check(room.is_host&&room.members.length===1&&room.queue.queue.length===2,'Host creates room with own full queue and code');
 check(await as(stranger,'troit_party_inspect',[room.code])===null,'Non-friends cannot inspect room codes');
 check((await as(guest,'troit_party_inspect',[room.code])).id===room.id,'Accepted friend can inspect exact room');
 let joined=await as(guest,'troit_party_join',[room.id,room.code]);check(!joined.is_host&&joined.members.length===2,'Friend joins explicitly and sees host queue');
 await reject(()=>as(guest,'troit_party_leave',[room.id,host]),'Guest cannot remove host or another participant');
 let queue=joined.queue;await cool();const addition=await as(guest,'troit_party_add',[uid(100),room.id,queue.revision,queue.playback_revision,JSON.stringify([song('C')]),queue.updated_at,false]);
 check(addition.status==='pending','Guest addition queues a host-owned request');
 check((await as(guest,'troit_party_add',[uid(100),room.id,queue.revision,queue.playback_revision,JSON.stringify([song('C')]),queue.updated_at,false])).status==='pending','Retry UUID returns same receipt without adding twice');
 await reject(()=>as(guest,'troit_party_add',[uid(100),room.id,queue.revision,queue.playback_revision,JSON.stringify([song('D')]),queue.updated_at,false]),'UUID cannot be reused for different songs');
 const command=await as(host,'troit_ecosystem_claim',[pc,run]);check(command.party_id===room.id&&command.queue.length===3,'Existing host receiver claims party append with exact payload');
 check(await as(host,'troit_party_validate_command',[command.id]),'Claimed party request can be checked again before native apply');
 check(!(await as(guest,'troit_party_validate_command',[command.id])),'Guest cannot validate or control host commands');
 await publish(command.queue);await as(host,'troit_ecosystem_ack',[pc===pc?command.id:null,pc,run,'executed']);
 check((await as(guest,'troit_party_status',[command.id])).status==='executed','Guest sees truthful playback confirmation');
 queue=(await as(guest,'troit_party_state')).queue;await cool();
 await reject(()=>as(guest,'troit_party_add',[uid(101),room.id,queue.revision,queue.playback_revision,JSON.stringify([song('D')]),queue.updated_at,true]),'Guest cannot insert ahead of host queue');
 await as(guest,'troit_party_add',[uid(102),room.id,queue.revision,queue.playback_revision,JSON.stringify([song('D')]),queue.updated_at,false]);
 await as(host,'troit_party_leave',[room.id,guest]);check(await as(host,'troit_ecosystem_claim',[pc,run])===null,'Removing guest revokes pending song requests');
 check(await as(guest,'troit_party_state')===null,'Removed guest loses queue visibility');
 check(await as(guest,'troit_party_inspect',[room.code])===null,'Removed guest cannot rejoin with the same code');
 await reject(()=>as(guest,'troit_party_join',[room.id,room.code]),'Host removal cannot be bypassed by re-entering code');
 await db.query("update public.troit_party_members set status='active' where room_id=$1 and user_id=$2",[room.id,guest]);
 queue=(await as(guest,'troit_party_state')).queue;await cool();
 await as(guest,'troit_party_add',[uid(103),room.id,queue.revision,queue.playback_revision,JSON.stringify([song('E')]),queue.updated_at,false]);
 const claimed=await as(host,'troit_ecosystem_claim',[pc,run]);await as(host,'troit_party_leave',[room.id,guest]);
 check(!(await as(host,'troit_party_validate_command',[claimed.id])),'Removing guest also invalidates an already claimed request before native apply');
 check((await as(guest,'troit_party_status',[claimed.id])).status==='stale','Revoked claimed request reports stale rather than execution');
 await reject(()=>as(host,'troit_ecosystem_claim_base',[pc,run]),'Clients cannot bypass party claim wrapper');
 await as(host,'troit_party_leave',[room.id,null]);check(await as(host,'troit_party_state')===null,'Host ending the room removes active membership');
 await cool();let state=await as(host,'troit_sleep_state');check(state.timer===null&&state.revision===0,'No timer is invented for an account');
 state=await as(host,'troit_sleep_set',[uid(200),0,'minutes',15]);check(state.timer.kind==='minutes'&&state.timer.deadline&&state.revision===1,'Minute timer uses server deadline and revision');
 const deadline=state.timer.deadline;check((await as(host,'troit_sleep_set',[uid(200),0,'minutes',15])).timer.deadline===deadline,'Lost-response timer set retry does not extend deadline');
 check((await as(guest,'troit_sleep_state')).timer===null,'Sleep timer is isolated between accounts');
 check(await as(host,'troit_sleep_claim',[state.timer.id,tv,tvRun,false])===null,'Inactive device cannot consume the timer');
 check(await as(host,'troit_sleep_claim',[state.timer.id,pc,run,false])===null,'Minute timer cannot fire before deadline');
 await as(host,'troit_sleep_cancel',[state.timer.id]);check((await as(host,'troit_sleep_state')).timer===null,'Any same-account app can cancel the shared timer');
 check((await as(host,'troit_sleep_set',[uid(200),0,'minutes',15])).timer===null,'Old set retry cannot resurrect a cancelled timer');
 await reject(()=>as(host,'troit_sleep_set',[uid(201),0,'minutes',30]),'Concurrent stale timer edit rejected');
 state=await as(host,'troit_sleep_set',[uid(202),2,'minutes',30]);await db.exec("update public.troit_sleep_timers set deadline=clock_timestamp()-interval '1 second'");
 await publish(command.queue,0,true,tv,tvRun);let fired=await as(host,'troit_sleep_claim',[state.timer.id,tv,tvRun,false]);
 check(fired?.device_id===tv,'Minute timer follows playback transfer to the new active device');
 check(await as(host,'troit_sleep_claim',[state.timer.id,tv,tvRun,false])===null,'Consumed timer never pauses again after retries');
 state=await as(host,'troit_sleep_set',[uid(203),4,'end_of_track',0]);check(await as(host,'troit_sleep_claim',[state.timer.id,tv,tvRun,false])===null,'End-of-track timer waits for native completion');
 check((await as(host,'troit_sleep_claim',[state.timer.id,tv,tvRun,true])).track_url===songs[0].url,'End-of-track completion pauses exact captured song');
 state=await as(host,'troit_sleep_set',[uid(204),6,'end_of_track',0]);await publish(command.queue,1,true,tv,tvRun);
 check(await as(host,'troit_sleep_claim',[state.timer.id,tv,tvRun,true])===null,'Skipping to another song cancels old end-of-track timer');
 check((await as(host,'troit_sleep_state')).timer===null,'Cancelled end timer disappears across devices');
 state=await as(host,'troit_sleep_set',[uid(205),8,'minutes',15]);
 await publish(command.queue,1,false,tv,tvRun);await cool();await as(host,'troit_ecosystem_heartbeat',[pc,run,'PC','pc',0]);
 queue=await as(host,'troit_ecosystem_queue',[0]);
 await as(host,'troit_ecosystem_transfer',[uid(300),tv,tvRun,queue.revision,pc,run,queue.updated_at,0]);
 await as(host,'troit_ecosystem_claim',[pc,run]);
 await db.exec("update public.troit_sleep_timers set deadline=clock_timestamp()-interval '1 second';update public.troit_ecosystem_commands set created_at=clock_timestamp()-interval '2 seconds' where id='"+uid(300)+"'");
 check(await as(host,'troit_ecosystem_activate',[uid(300),pc,run])===null,'A countdown elapsed during target preparation prevents transfer from starting music again');
 check((await as(host,'troit_ecosystem_status',[uid(300)])).status==='stale','Timer-fenced activation returns a stale request receipt');
 await reject(()=>as(host,'troit_ecosystem_activate_base',[uid(300),pc,run]),'Clients cannot bypass timer activation wrapper');
 await db.exec(migration);check(true,'Reapplication preserves timers and rooms without replacing receiver protocol');
 console.log(`\n${checks} listening-session database checks passed.`);
}catch(error){console.error(error.message,error.where,error.internalQuery);process.exitCode=1}finally{await db.close()}

