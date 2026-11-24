// Isolated Postgres/WASM tests. This script never connects to the live Supabase project.
// node supabase/tests/collab.integration.mjs
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../build/social-db-check/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const db = new PGlite();
const uid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const alice=uid(1), bob=uid(2), carol=uid(3), outsider=uid(4), noProfile=uid(70);
const playlist=uid(100), source=uid(101), fullPlaylist=uid(102), group=uid(103);
let checks=0;
const check=(condition,message)=>{ assert.ok(condition,message); checks++; console.log(`PASS ${message}`); };
const song=n=>({title:`Song ${n}`,artist:'Artist',url:`https://music.youtube.com/watch?v=${String(n).padStart(11,'0')}`,artwork:null,duration:180});
async function as(user,sql,args=[],role='authenticated') {
  await db.exec(`set role ${role}`);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[user??'']);
  try { return await db.query(sql,args); }
  finally { await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub','',false)"); }
}
async function rpc(user,name,args=[]) {
  assert.match(name,/^troit_collab_[a-z_]+$/);
  return (await as(user,`select public.${name}(${args.map((_,i)=>`$${i+1}`).join(',')}) as result`,args)).rows[0]?.result;
}
async function cool() { await db.exec("update public.troit_social_limits set count=0,last_at='2000-01-01',window_start='2000-01-01'"); }
async function action(user,name,args=[]) { await cool(); return rpc(user,name,args); }
async function rejects(operation,message,code) {
  let failure;
  try { await operation(); } catch(error) { failure=error; }
  assert.ok(failure,`Expected rejection: ${message}`);
  if(code) assert.equal(failure.code,code,`${message}: ${failure.message}`);
  check(true,message);
}
const json=JSON.stringify;
async function create(id,tracks=[],peers=[],owner=alice,sourceId=null) {
  return action(owner,'troit_collab_create',[id,'Shared listening',json(tracks),peers,sourceId]);
}

try {
  await db.exec(`
    create role anon; create role authenticated; create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to anon,authenticated;
  `);
  for(let n=1;n<=80;n++) await db.query('insert into auth.users(id) values($1) on conflict do nothing',[uid(n)]);
  for(const file of ['20261111000000_tunes_sync.sql','20261111020000_troit_social.sql'])
    await db.exec(await readFile(new URL(`../migrations/${file}`,import.meta.url),'utf8'));
  const migration=await readFile(new URL('../migrations/20261113010000_troit_collaborative_playlists.sql',import.meta.url),'utf8');
  await db.exec(migration); await db.exec(migration);
  check(true,'Collaborative migration applies repeatedly without deleting existing music tables');
  for(let n=1;n<=65;n++) await db.query('insert into public.troit_profiles(user_id,handle,display_name) values($1,$2,$3)',[uid(n),`friend_${n}`,`Friend ${n}`]);
  for(let n=2;n<=65;n++) await db.query("insert into public.troit_friendships(user_low,user_high,requested_by,status) values($1,$2,$1,'accepted')",[alice,uid(n)]);
  for(const table of ['troit_collab_playlists','troit_collab_members']) {
    await rejects(()=>as(alice,`select * from public.${table}`),`Direct authenticated reads denied for ${table}`,'42501');
    check((await db.query('select relrowsecurity from pg_class where oid=$1::regclass',[`public.${table}`])).rows[0].relrowsecurity,'RLS enabled for '+table);
  }
  await rejects(()=>as(alice,"insert into public.troit_collab_members(playlist_id,user_id,status) values($1,$2,'accepted')",[playlist,alice]),'Direct membership writes cannot bypass invitations','42501');
  await rejects(()=>as(null,'select public.troit_collab_list()',[],'anon'),'Anonymous collaborative RPC denied','42501');
  await rejects(()=>rpc(null,'troit_collab_list'),'Authenticated RPC still requires an identity','28000');
  await rejects(()=>rpc(alice,'troit_collab_access',[playlist,false,false]),'Private access helper denied to clients','42501');
  await rejects(()=>rpc(alice,'troit_collab_validate_songs',[json([])]),'Private metadata helper denied to clients','42501');
  await rejects(()=>create(uid(150),[],[],noProfile),'A Friends profile is required before creating collaboration');
  await rejects(()=>create(uid(151),[],[noProfile]),'Only accepted friends can be invited','42501');
  await rejects(()=>create(uid(152),[],[alice]),'Owners cannot invite themselves as an editor','42501');
  await rejects(()=>create(uid(153),[],Array.from({length:20},(_,i)=>uid(i+2))),'Oversized invitation arrays are rejected');
  await rejects(()=>action(alice,'troit_collab_create',[uid(154),' ',json([]),[],null]),'Blank playlist titles are rejected');

  await db.query('insert into public.tunes_playlists(user_id,id,title,songs,updated_at) values($1,$2,$3,$4,9000000000000)',[alice,source,'Private original',json([song(1),song(2)])]);
  await db.query('insert into public.tunes_playlists(user_id,id,title,songs,updated_at) values($1,$2,$3,$4,100)',[bob,source,'Bob private row',json([song(9)])]);
  let detail=await create(playlist,[song(1),song(2),song(1)],[bob,carol],alice,source);
  check(detail.role==='owner' && detail.owner_id===alice && detail.songs.length===2 && detail.revision===1,'Promotion preserves songs, deduplicates URLs, and assigns the authenticated owner');
  check(detail.members.length===3 && detail.members.filter(m=>m.status==='pending').length===2,'Friends are invited rather than silently added as editors');
  const tombstone=(await db.query('select * from public.tunes_playlists where user_id=$1 and id=$2',[alice,source])).rows[0];
  check(tombstone.deleted && tombstone.songs.length===0 && Number(tombstone.updated_at)===9000000000001,'Promotion atomically tombstones the original even when its timestamp is in the future');
  check(!(await db.query('select deleted from public.tunes_playlists where user_id=$1 and id=$2',[bob,source])).rows[0].deleted,'Promotion never changes another account’s personal row with the same playlist ID');
  check((await rpc(alice,'troit_collab_list')).playlists[0].source_id===source,'Owner summary links the promoted source ID');
  const invite=(await rpc(bob,'troit_collab_list')).invites[0];
  check(invite.role==='invited' && !('songs' in invite) && !('members' in invite),'Invitations contain bounded summaries without songs or a member directory');
  await rejects(()=>rpc(bob,'troit_collab_get',[playlist]),'Pending invitees cannot read songs before accepting','42501');
  await rejects(()=>rpc(outsider,'troit_collab_get',[playlist]),'An uninvited friend cannot read another group','42501');
  check((await rpc(outsider,'troit_collab_list')).playlists.length===0,'A general friend sees no private collaborative playlists');
  await action(carol,'troit_collab_respond',[playlist,false]);
  check((await rpc(carol,'troit_collab_list')).invites.length===0,'Declining removes the invitation');
  await action(alice,'troit_collab_invite',[playlist,[carol]]);
  detail=await action(bob,'troit_collab_respond',[playlist,true]);
  check(detail.role==='editor' && detail.members.find(m=>m.id===bob).status==='accepted','Accepting grants editor access');
  await action(carol,'troit_collab_respond',[playlist,true]);
  const accepted=await rpc(bob,'troit_collab_get',[playlist]);
  check((await action(bob,'troit_collab_respond',[playlist,true])).revision===accepted.revision,'A repeated acceptance does not duplicate membership or advance revisions');

  let first=await action(bob,'troit_collab_add_songs',[playlist,json([song(3),song(1)])]);
  let second=await action(carol,'troit_collab_add_songs',[playlist,json([song(4)])]);
  check(second.songs.map(s=>s.title).join(',')==='Song 1,Song 2,Song 3,Song 4' && second.revision===first.revision+1,'Independent granular editor appends preserve both changes in order');
  check((await action(bob,'troit_collab_add_songs',[playlist,json([song(3)])])).revision===second.revision,'Retrying an append is idempotent');
  await rejects(()=>action(bob,'troit_collab_rename',[playlist,'No permission',second.revision]),'Editors cannot rename owner-managed playlists','42501');
  await rejects(()=>action(bob,'troit_collab_invite',[playlist,[outsider]]),'Editors cannot appoint other members','42501');
  await rejects(()=>action(bob,'troit_collab_delete',[playlist]),'Editors cannot delete the playlist','42501');
  await rejects(()=>action(alice,'troit_collab_rename',[playlist,'Stale title',accepted.revision]),'Stale title revisions cannot overwrite newer member edits','40001');
  detail=await action(alice,'troit_collab_rename',[playlist,' Renamed together ',second.revision]);
  check(detail.title==='Renamed together' && detail.revision===second.revision+1,'Owner rename trims names and advances the server revision');
  detail=await action(bob,'troit_collab_remove_song',[playlist,song(2).url]);
  check(detail.songs.every(s=>s.url!==song(2).url),'Editors can remove songs without replacing the whole playlist');
  const revision=detail.revision;
  check((await action(bob,'troit_collab_remove_song',[playlist,song(2).url])).revision===revision,'Retrying a removal is idempotent');
  const retried=await create(playlist,[song(99)],[],alice,source);
  check(retried.title===detail.title && retried.revision===revision && retried.songs.length===3,'Creation retries never overwrite later collaborative edits');
  check((await create(uid(155),[],[],alice,source)).id===playlist,'Retrying promotion with a new UUID returns the existing shared source');
  await rejects(()=>create(playlist,[],[],bob),'Another owner cannot reuse a known playlist UUID','42501');

  for(const [bad,label] of [
    [{...song(1),url:'file:///private'},'Non-music URLs'],[{...song(1),artist:{}},'Object metadata'],
    [{...song(1),duration:-1},'Negative duration'],[{...song(1),duration:0.5},'Fractional duration'],
    [{...song(1),artwork:'http://insecure.example/a'},'Non-HTTPS artwork'],[{...song(1),title:'x'.repeat(501)},'Oversized titles'],
  ]) await rejects(()=>action(bob,'troit_collab_add_songs',[playlist,json([bad])]),label+' are rejected before storage');
  const sanitized=await action(bob,'troit_collab_add_songs',[playlist,json([{...song(5),artist:'',extra_blob:'not stored'}])]);
  check(sanitized.songs.find(s=>s.url===song(5).url).artist==='' && !('extra_blob' in sanitized.songs.at(-1)),'Empty artists remain playable while unknown metadata fields are discarded');
  await rejects(()=>create(uid(156),Array.from({length:1001},(_,i)=>song(i+1000))),'The 1,000-song creation limit is enforced on the server');
  await create(fullPlaylist,Array.from({length:1000},(_,i)=>song(i+1000)));
  await rejects(()=>action(alice,'troit_collab_add_songs',[fullPlaylist,json([song(99999)])]),'Appending beyond 1,000 songs fails without overwriting the full playlist');
  check((await rpc(alice,'troit_collab_get',[fullPlaylist])).songs.length===1000,'The full 1,000-song payload round-trips intact');

  await create(group,[],Array.from({length:19},(_,i)=>uid(i+2)));
  await rejects(()=>action(alice,'troit_collab_invite',[group,[uid(21)]]),'Membership is capped at 20 including owner and pending invitations');
  await action(alice,'troit_collab_member_action',[group,uid(20),'remove']);
  check((await action(alice,'troit_collab_invite',[group,[uid(21)]])).members.length===20,'The owner can replace an invited member within the cap');
  await action(alice,'troit_collab_member_action',[playlist,carol,'remove']);
  await rejects(()=>rpc(carol,'troit_collab_get',[playlist]),'Removed editors immediately lose access','42501');
  await action(alice,'troit_collab_invite',[playlist,[carol]]);
  await action(carol,'troit_collab_respond',[playlist,true]);
  await action(carol,'troit_collab_member_action',[playlist,carol,'leave']);
  await rejects(()=>rpc(carol,'troit_collab_get',[playlist]),'Leaving removes member access','42501');
  await rejects(()=>action(alice,'troit_collab_member_action',[playlist,alice,'leave']),'Owners cannot leave and orphan the shared playlist','42501');
  await rejects(()=>action(bob,'troit_collab_member_action',[playlist,alice,'remove']),'Editors cannot remove the owner','42501');

  await db.query("update public.troit_friendships set status='removed' where user_low=$1 and user_high=$2",[alice,bob]);
  await rejects(()=>rpc(bob,'troit_collab_get',[playlist]),'Friend removal revokes an existing editor’s reads','42501');
  await rejects(()=>action(bob,'troit_collab_add_songs',[playlist,json([song(6)])]),'Friend removal also revokes existing editor writes','42501');
  check((await rpc(bob,'troit_collab_list')).playlists.length===0,'Friend removal hides former collaborative groups');
  await db.query("update public.troit_friendships set status='accepted' where user_low=$1 and user_high=$2",[alice,bob]);
  for(const [blocker,blocked] of [[alice,bob],[bob,alice]]) {
    await db.query('insert into public.troit_blocks(blocker_id,blocked_id) values($1,$2)',[blocker,blocked]);
    await rejects(()=>rpc(bob,'troit_collab_get',[playlist]),'Blocking in either direction revokes editor access','42501');
    await rejects(()=>action(alice,'troit_collab_invite',[playlist,[bob]]),'Blocking in either direction prevents invitations','42501');
    await db.query('delete from public.troit_blocks where blocker_id=$1 and blocked_id=$2',[blocker,blocked]);
  }
  await action(alice,'troit_collab_invite',[playlist,[carol]]);
  await action(carol,'troit_collab_respond',[playlist,true]);
  await db.query('insert into public.troit_blocks(blocker_id,blocked_id) values($1,$2)',[bob,carol]);
  await rejects(()=>rpc(bob,'troit_collab_get',[playlist]),'A block between editors hides the shared group from the blocker','42501');
  await rejects(()=>rpc(carol,'troit_collab_get',[playlist]),'A block between editors hides the shared group from the blocked account','42501');
  check((await rpc(bob,'troit_collab_list')).playlists.length===0,'Pairwise editor blocks also hide group summaries');
  check((await rpc(alice,'troit_collab_get',[playlist])).role==='owner','Owner retains management access to resolve a blocked group');
  await action(alice,'troit_collab_member_action',[playlist,carol,'remove']);
  await rejects(()=>action(alice,'troit_collab_invite',[playlist,[carol]]),'An owner cannot re-invite a peer blocked by an existing member','42501');
  check((await rpc(bob,'troit_collab_get',[playlist])).role==='editor','Removing the incompatible member restores access for the remaining editor');

  await action(alice,'troit_collab_delete',[playlist]);
  await rejects(()=>rpc(bob,'troit_collab_get',[playlist]),'Deleting the shared playlist immediately removes member reads','42501');
  await rejects(()=>create(playlist,[],[],alice,source),'A late create retry cannot resurrect a deleted shared playlist','42501');
  check((await rpc(alice,'troit_collab_list')).playlists.every(p=>p.id!==playlist),'Deleted playlists disappear from owner summaries');
  check((await db.query('select deleted from public.tunes_playlists where user_id=$1 and id=$2',[alice,source])).rows[0].deleted,'Deleting collaboration never silently restores a private playlist');

  // Independent cap fixture avoids relying on client-side counts or outstanding invitation status.
  for(let n=0;n<50;n++) {
    const id=uid(1000+n);
    await db.query("insert into public.troit_collab_playlists(id,owner_id,title) values($1,$2,'Cap fixture')",[id,outsider]);
    await db.query("insert into public.troit_collab_members(playlist_id,user_id,status) values($1,$2,'accepted')",[id,outsider]);
  }
  await rejects(()=>create(uid(160),[],[],outsider),'The 50-playlist account limit is enforced for owners');
  await rejects(()=>action(alice,'troit_collab_invite',[fullPlaylist,[outsider]]),'The account limit also bounds outstanding invited memberships');
  console.log(`\n${checks} collaborative playlist checks passed.`);
} finally { await db.close(); }
