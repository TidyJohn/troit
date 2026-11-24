// Isolated Postgres/WASM test. Never connects to the configured Supabase project.
// npm install --prefix build/social-db-check @electric-sql/pglite@0.5.16
// node supabase/tests/connect.integration.mjs
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../build/social-db-check/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const db = new PGlite();
const migration = await readFile(new URL('../migrations/20261111030000_troit_connect.sql', import.meta.url), 'utf8');
const uid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const alice = uid(1), bob = uid(2), pc = uid(10), phone = uid(11), tv = uid(12), pcRun = uid(20), phoneRun = uid(21), tvRun = uid(24);
let checks = 0;
function check(ok, message) { assert.ok(ok, message); checks++; console.log(`PASS ${message}`); }
async function as(user, sql, args = [], role = 'authenticated') {
  assert.ok(['authenticated', 'anon'].includes(role));
  await db.exec(`set role ${role}`);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user ?? '']);
  try { return await db.query(sql, args); }
  finally { await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub','',false)"); }
}
async function rpc(user, name, args = []) {
  assert.match(name, /^troit_connect_[a-z_]+$/);
  return (await as(user, `select public.${name}(${args.map((_, i) => `$${i + 1}`).join(',')}) as result`, args)).rows[0]?.result;
}
async function rejects(action, message, code) {
  let failure;
  try { await action(); } catch (error) { failure = error; }
  assert.ok(failure, `Expected rejection: ${message}`);
  if (code) assert.equal(failure.code, code, `${message}: ${failure.message}`);
  check(true, message);
}
async function cool(action) {
  await db.query("update public.troit_connect_limits set last_at='2000-01-01',window_start='2000-01-01',count=0 where action=$1", [action]);
}
async function publish(user = alice, device = pc, instance = pcRun, options = {}) {
  await cool('publish');
  return rpc(user, 'troit_connect_publish', [device, instance, options.name ?? 'My PC', options.url ?? 'https://www.youtube.com/watch?v=trackA',
    options.title ?? 'Track A', 'Artist', null, 180, options.index ?? 0, options.playing ?? true, options.position ?? 1000, options.takeover ?? true]);
}
async function command(snapshot, id, action = 'next', user = alice, observed = snapshot.updated_at) {
  await cool('command');
  return rpc(user, 'troit_connect_command', [id, snapshot.device_id, snapshot.instance_id, snapshot.revision, snapshot.track_url, observed, action]);
}
const claim = (device = pc, instance = pcRun, user = alice) => rpc(user, 'troit_connect_claim', [device, instance]);
const status = (id, user = alice) => rpc(user, 'troit_connect_command_status', [id]);

try {
  await db.exec(`create role anon; create role authenticated; create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to anon,authenticated;
    insert into auth.users(id) values('${alice}'),('${bob}');`);
  await db.exec(migration);
  await db.exec(migration);
  check(true, 'Connect migration applies independently and can be reapplied');
  const rls = (await db.query("select count(*)::int as n from pg_class where relname in ('troit_connect_playback','troit_connect_commands','troit_connect_limits') and relrowsecurity")).rows[0].n;
  check(rls === 3, 'All Connect tables enable row-level security');
  for (const table of ['troit_connect_playback', 'troit_connect_commands', 'troit_connect_limits']) {
    await rejects(() => as(alice, `select * from public.${table}`), `Direct client reads denied for ${table}`, '42501');
    await rejects(() => as(alice, `delete from public.${table}`), `Direct client deletes denied for ${table}`, '42501');
  }
  await rejects(() => as(null, 'select public.troit_connect_state()', [], 'anon'), 'Anonymous state access denied', '42501');
  await rejects(() => rpc(null, 'troit_connect_state'), 'Authenticated role without an identity is rejected', '28000');
  await rejects(() => rpc(alice, 'troit_connect_rate', ['command', 999999, 1, 0]), 'Private rate helper is not client-callable', '42501');
  check(await rpc(alice, 'troit_connect_state') === null, 'Empty account has no pretend playing state');
  check(await publish(alice, pc, pcRun, { takeover: false }) === null, 'Restored paused/idle session cannot create the active pointer');

  let snapshot = await publish();
  check(snapshot.device_id === pc && snapshot.instance_id === pcRun && snapshot.playing && snapshot.fresh && !('user_id' in snapshot), 'Published widget state identifies the owner process without exposing account identity');
  check(await rpc(bob, 'troit_connect_state') === null, 'Another account cannot read the current track');
  await rejects(() => command(snapshot, uid(100), 'next', bob), 'Another account cannot enqueue a control for this playback', '40001');
  await rejects(() => publish(alice, pc, pcRun, { url: 'file:///private/music' }), 'Non-HTTPS track metadata is rejected');
  await rejects(() => publish(alice, pc, pcRun, { title: 'x'.repeat(501) }), 'Oversized metadata is rejected');

  const revision = snapshot.revision;
  snapshot = await publish(alice, pc, pcRun, { takeover: false, position: 5000, playing: false });
  check(snapshot.revision === revision && !snapshot.playing && snapshot.position_ms === 5000, 'Position and pause heartbeat preserve the track revision');
  const phoneSnapshot = await publish(alice, phone, phoneRun, { name: 'My phone', url: 'https://www.youtube.com/watch?v=trackB', index: 1 });
  const unchanged = await publish(alice, pc, pcRun, { takeover: false });
  check(unchanged.instance_id === phoneRun && unchanged.revision === phoneSnapshot.revision, 'Old PC heartbeat cannot steal a newly active phone session');
  await db.query("update public.troit_connect_playback set updated_at=clock_timestamp()-interval '2 minutes' where user_id=$1", [alice]);
  check(!(await rpc(alice, 'troit_connect_state')).fresh, 'Offline state is marked stale instead of claiming to be playing live');
  check((await publish(alice, pc, pcRun, { takeover: false })).instance_id === phoneRun, 'Old idle heartbeat cannot steal even a stale different-device pointer');
  snapshot = await publish(alice, pc, pcRun);
  check(snapshot.instance_id === pcRun && snapshot.revision > phoneSnapshot.revision, 'Explicit playback can take over the active device');

  // The same RPCs serve the television, phone and desktop. Controllers carry the active device's
  // snapshot, while only that exact playback runtime may claim the resulting audio command.
  const previousPc = snapshot;
  await command(previousPc, uid(150), 'next');
  let television = await publish(alice, tv, tvRun, {
    name: 'Living room TV', url: 'https://www.youtube.com/watch?v=trackTV', title: 'TV song', index: 4, position: 42000,
  });
  const observedOnPhone = await rpc(alice, 'troit_connect_state');
  const observedOnPc = await rpc(alice, 'troit_connect_state');
  check(observedOnPhone.device_id === tv && observedOnPc.device_id === tv &&
    observedOnPhone.device_name === 'Living room TV' && observedOnPhone.title === 'TV song' &&
    observedOnPc.position_ms === 42000 && observedOnPc.queue_index === 4 && observedOnPc.playing,
  'Phone and PC observe the TV current song, progress, play state and device name');
  check(await claim(pc, pcRun) === null && (await status(uid(150))).status === 'stale',
    'A superseded PC cannot claim its pending skip after TV takes playback');
  await rejects(() => command(previousPc, uid(151), 'pause'),
    'An outdated PC snapshot cannot pause the newly active TV', '40001');
  for (const [offset, action] of ['pause', 'play', 'next', 'previous'].entries()) {
    const id = uid(152 + offset);
    const accepted = await command(television, id, action);
    check(accepted.status === 'pending' && await claim(phone, phoneRun) === null && await claim(pc, pcRun) === null,
      `Remote ${action} targets only the active TV instead of the controller device`);
    const tvCommand = await claim(tv, tvRun);
    check(tvCommand?.id === id && tvCommand.action === action && tvCommand.queue_index === television.queue_index &&
      tvCommand.track_url === television.track_url, `TV claims the exact remote ${action} control`);
    // Model the player reporting its actual result before it acknowledges the command.
    const changes = action === 'next' ? { index: 5, url: 'https://www.youtube.com/watch?v=tvNext', title: 'Next TV song', position: 0 }
      : action === 'previous' ? { index: 4, url: 'https://www.youtube.com/watch?v=trackTV', title: 'TV song', position: 0 }
        : { index: television.queue_index, url: television.track_url, title: television.title, position: television.position_ms };
    television = await publish(alice, tv, tvRun, { name: 'Living room TV', takeover: false,
      ...changes, playing: action === 'pause' ? false : action === 'play' ? true : television.playing });
    await rpc(alice, 'troit_connect_ack', [id, tv, tvRun, 'executed']);
    const reflected = await rpc(alice, 'troit_connect_state');
    check((await status(id)).status === 'executed' && reflected.device_id === tv &&
      reflected.playing === television.playing && reflected.track_url === television.track_url && await claim(tv, tvRun) === null,
    `TV ${action} result is shared with controllers and cannot be replayed`);
  }
  const fromTv = await publish(alice, phone, phoneRun, { name: 'My phone', position: 24000 });
  check((await publish(alice, tv, tvRun, { takeover: false })).device_id === phone &&
    (await rpc(alice, 'troit_connect_state')).revision === fromTv.revision,
  'The TV heartbeat cannot steal playback after the user continues on their phone');
  snapshot = await publish();

  const receipt = await command(snapshot, uid(101));
  const retry = await command(snapshot, uid(101));
  check(receipt.id === retry.id && retry.status === 'pending', 'A stable UUID returns one receipt for network retries');
  await rejects(() => command(snapshot, uid(101), 'previous'), 'Reusing a UUID for a different action is rejected');
  check(await status(uid(101), bob) === null && await claim(pc, pcRun, bob) === null, 'Other accounts cannot inspect or claim a command');
  check(await claim(pc, uid(22)) === null, 'A second app process on the same PC cannot claim the owner process control');
  const claimed = await claim();
  check(claimed.id === uid(101) && claimed.track_url === snapshot.track_url && claimed.queue_index === snapshot.queue_index, 'The exact owner claims a command bound to its track and queue index');
  check(await claim() === null && (await status(uid(101))).status === 'claimed', 'Claim consumes before execution and never replays without an acknowledgement');
  await rpc(alice, 'troit_connect_ack', [uid(101), pc, uid(22), 'executed']);
  check((await status(uid(101))).status === 'claimed', 'A different runtime cannot acknowledge another process command');
  await rpc(alice, 'troit_connect_ack', [uid(101), pc, pcRun, 'executed']);
  check((await status(uid(101))).status === 'executed', 'Execution acknowledgement is visible to the widget');
  check((await command(snapshot, uid(101))).status === 'executed', 'Retry after acknowledgement returns the executed receipt without another skip');
  await rejects(() => rpc(alice, 'troit_connect_ack', [uid(101), pc, pcRun, 'pending']), 'Acknowledgement cannot reset a consumed command to pending');

  await command(snapshot, uid(102));
  await db.query("update public.troit_connect_commands set expires_at=clock_timestamp()-interval '1 second' where id=$1", [uid(102)]);
  check((await status(uid(102))).status === 'expired' && await claim() === null, 'Expired controls are never executed');
  await command(snapshot, uid(103));
  snapshot = await publish(alice, pc, pcRun, { url: 'https://www.youtube.com/watch?v=trackC', index: 2 });
  check(await claim() === null && (await status(uid(103))).status === 'stale', 'A delayed next command cannot advance a newer track');
  const repeatSong = await publish(alice, pc, pcRun, { url: snapshot.track_url, index: 3 });
  check(repeatSong.revision > snapshot.revision, 'Repeated songs at a different queue index receive a new revision');
  await rejects(() => command(snapshot, uid(104)), 'Old queue index revision is rejected even for the same song URL', '40001');
  snapshot = repeatSong;
  await rejects(() => command(snapshot, uid(105), 'next', alice, '2000-01-01T00:00:00Z'), 'Persisted stale widget work cannot create a new command', '40001');
  await rejects(() => command(snapshot, uid(106), 'next', alice, '2999-01-01T00:00:00Z'), 'Future snapshot timestamps are rejected', '40001');
  await rejects(() => command(snapshot, uid(107), 'delete'), 'Unknown command actions are rejected');

  await command(snapshot, uid(108), 'pause');
  await publish(alice, phone, phoneRun, { url: snapshot.track_url });
  check(await claim() === null && (await status(uid(108))).status === 'stale', 'A device handoff invalidates pending controls for the previous device');
  snapshot = await publish();
  await command(snapshot, uid(140));
  await publish(alice, pc, uid(23));
  check(await claim() === null && await claim(pc, uid(23)) === null && (await status(uid(140))).status === 'stale', 'Restarting the same physical PC cannot inherit a previous runtime control');
  snapshot = await publish();
  await command(snapshot, uid(109));
  await rejects(() => rpc(alice, 'troit_connect_command', [uid(110), pc, pcRun, snapshot.revision, snapshot.track_url, snapshot.updated_at, 'next']), 'Rapid requests are rate limited');
  await db.query("update public.troit_connect_limits set count=60,last_at=clock_timestamp()-interval '1 second' where user_id=$1 and action='command'", [alice]);
  await rejects(() => rpc(alice, 'troit_connect_command', [uid(111), pc, pcRun, snapshot.revision, snapshot.track_url, snapshot.updated_at, 'next']), 'Per-minute command budget is enforced');
  // Synthetic expiry advances time without sleeping; this is local test data only.
  await db.query("update public.troit_connect_commands set expires_at=clock_timestamp()-interval '6 minutes' where user_id=$1", [alice]);
  await command(snapshot, uid(112));
  check((await db.query('select count(*)::int as n from public.troit_connect_commands where user_id=$1', [alice])).rows[0].n === 1, 'New requests prune expired command history instead of growing indefinitely');
  for (let i = 113; i < 132; i++) await command(snapshot, uid(i));
  await rejects(() => command(snapshot, uid(132)), 'Pending command backlog is capped at twenty');
  await db.exec(migration);
  check((await rpc(alice, 'troit_connect_state')).device_id === pc, 'Reapplying the migration preserves playback metadata');
  console.log(`\n${checks} Connect database checks passed.`);
} finally { await db.close(); }
