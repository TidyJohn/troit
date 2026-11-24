// Isolated Postgres/WASM test. Never connects to the configured Supabase project.
// npm install --prefix build/social-db-check @electric-sql/pglite@0.5.16
// node supabase/tests/activity.integration.mjs
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../build/social-db-check/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const db = new PGlite();
const sql = name => readFile(new URL(`../migrations/${name}.sql`, import.meta.url), 'utf8');
const uid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const alice = uid(1), bob = uid(2), carol = uid(3), dave = uid(4);
let checks = 0;
function check(ok, message) { assert.ok(ok, message); checks++; console.log(`PASS ${message}`); }
async function as(user, statement, args = [], role = 'authenticated') {
  await db.exec(`set role ${role}`);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user ?? '']);
  try { return await db.query(statement, args); }
  finally { await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub','',false)"); }
}
async function rpc(user, name, args = []) {
  assert.match(name, /^troit_[a-z_]+$/);
  // Rate-limit spacing is covered by the social and Connect suites; here it would only slow the checks.
  await db.exec("update public.troit_social_limits set last_at='2000-01-01',window_start='2000-01-01',count=0; update public.troit_connect_limits set last_at='2000-01-01',window_start='2000-01-01',count=0");
  return (await as(user, `select public.${name}(${args.map((_, i) => `$${i + 1}`).join(',')}) as result`, args)).rows[0]?.result;
}
async function rejects(action, message, code) {
  let failure;
  try { await action(); } catch (error) { failure = error; }
  assert.ok(failure, `Expected rejection: ${message}`);
  if (code) assert.equal(failure.code, code, `${message}: ${failure.message}`);
  check(true, message);
}
const play = (user, n, playing = true) => rpc(user, 'troit_connect_publish', [uid(100 + n), uid(200 + n), 'Bedroom PC',
  `https://music.youtube.com/watch?v=track${n}aaaa`, `Track ${n}`, 'Artist', null, 180, 0, playing, 1000, true]);
const activity = async user => rpc(user, 'troit_friend_activity');
const listeners = async user => (await activity(user)).items.map(row => row.user_id);

try {
  await db.exec(`create role anon; create role authenticated; create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to anon,authenticated;
    insert into auth.users(id) values('${alice}'),('${bob}'),('${carol}'),('${dave}');`);
  await db.exec(await sql('20261111020000_troit_social'));
  await db.exec(await sql('20261111030000_troit_connect'));
  const migration = await sql('20261116000000_troit_friend_activity');
  await db.exec(migration);
  await db.exec(migration);
  check(true, 'Activity migration applies after social and Connect, and can be reapplied');

  for (const [user, handle] of [[alice, 'alice'], [bob, 'bobby'], [carol, 'carol']]) await rpc(user, 'troit_profile_save', [handle, handle]);
  await rpc(alice, 'troit_friend_request', ['bobby']);
  const request = (await rpc(bob, 'troit_social_snapshot')).connections[0];
  await rpc(bob, 'troit_friend_action', [request.id, 'accept']);
  await rpc(alice, 'troit_friend_request', ['carol']); // stays pending
  await play(bob, 2);
  await play(carol, 3);

  await rejects(() => as(null, 'select public.troit_friend_activity()', [], 'anon'), 'Anonymous activity access denied', '42501');
  await rejects(() => rpc(null, 'troit_friend_activity'), 'Activity without an identity is rejected', '28000');
  await rejects(() => as(alice, 'select share_activity from public.troit_profiles'), 'Profiles stay closed to direct reads', '42501');

  const seen = await activity(alice);
  check(seen.sharing === true, 'Sharing is on by default and reported to its owner');
  check(seen.items.length === 1 && seen.items[0].user_id === bob && seen.items[0].title === 'Track 2', 'An accepted friend\'s live song is visible');
  check(!JSON.stringify(seen).includes('Bedroom PC') && !('position_ms' in seen.items[0]), 'Device name and position are not exposed');
  check((await listeners(carol)).length === 0 && !(await listeners(alice)).includes(carol), 'A pending request shows nothing in either direction');
  check((await listeners(dave)).length === 0, 'An account without friends sees nothing');

  await play(bob, 2, false);
  check((await listeners(alice)).length === 0, 'Paused playback is not shown');
  await play(bob, 2);
  await db.exec("update public.troit_connect_playback set updated_at=clock_timestamp()-interval '2 minutes'");
  check((await listeners(alice)).length === 0, 'A session without a recent heartbeat is not shown');
  await play(bob, 2);

  check(await rpc(bob, 'troit_friend_activity_share', [false]) === false, 'A user can stop sharing');
  check((await listeners(alice)).length === 0 && (await activity(bob)).sharing === false, 'Opting out hides the song from friends');
  await rpc(bob, 'troit_friend_activity_share', [true]);
  check((await listeners(alice)).includes(bob), 'Opting back in shows it again');
  await rejects(() => rpc(dave, 'troit_friend_activity_share', [false]), 'Sharing needs a profile');
  await rejects(() => rpc(bob, 'troit_friend_activity_share', [null]), 'A missing sharing choice is rejected');

  await play(alice, 1);
  check((await listeners(bob)).includes(alice), 'Friendship is mutual');
  await rpc(alice, 'troit_friend_action', [request.id, 'block']);
  check((await listeners(alice)).length === 0 && (await listeners(bob)).length === 0, 'Blocking hides activity both ways');
  console.log(`\n${checks} checks passed`);
} finally { await db.close(); }
