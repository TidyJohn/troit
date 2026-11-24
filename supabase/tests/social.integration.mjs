// Isolated Postgres/WASM integration: never connects to the configured Supabase project.
// Install test dependency: npm install --prefix build/social-db-check @electric-sql/pglite@0.5.16
// Run from the repository: node supabase/tests/social.integration.mjs
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../build/social-db-check/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const db = new PGlite();
const migration = await readFile(new URL('../migrations/20261111020000_troit_social.sql', import.meta.url), 'utf8');
const inboxMigration = await readFile(new URL('../migrations/20261113000000_troit_chat_inbox.sql', import.meta.url), 'utf8');
const uid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const alice = uid(1), bob = uid(2), outsider = uid(3), fourth = uid(4);
let checks = 0;
function check(condition, message) { assert.ok(condition, message); checks++; console.log(`PASS ${message}`); }
async function as(user, sql, params = [], role = 'authenticated') {
  assert.ok(['authenticated', 'anon'].includes(role));
  await db.exec(`set role ${role};`);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user ?? '']);
  try { return await db.query(sql, params); }
  finally { await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub','',false)"); }
}
async function rpc(user, name, args = []) {
  assert.match(name, /^troit_[a-z_]+$/);
  const placeholders = args.map((_, n) => `$${n + 1}`).join(',');
  return (await as(user, `select public.${name}(${placeholders}) as result`, args)).rows[0]?.result;
}
async function rejects(action, message, code) {
  let failure;
  try { await action(); } catch (error) { failure = error; }
  assert.ok(failure, `Expected rejection: ${message}`);
  if (code) assert.equal(failure.code, code, `${message}: ${failure.message}`);
  check(true, message);
}
async function cool() {
  await db.exec("update public.troit_social_limits set last_at='2000-01-01',window_start='2000-01-01',count=0");
}
async function action(user, name, args) { await cool(); return rpc(user, name, args); }

try {
  await db.exec(`
    create role anon;
    create role authenticated;
    create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to anon,authenticated;
    insert into auth.users(id) values('${alice}'),('${bob}'),('${outsider}'),('${fourth}');
  `);
  await db.exec(migration);
  await db.exec(migration);
  check(true, 'Migration applies and can be reapplied without deleting existing data');
  await db.exec(inboxMigration);
  await db.exec(inboxMigration);
  check(true, 'Additive chat inbox migration can be reapplied');
  const protectedTables = (await db.query("select count(*)::int as n from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in ('troit_profiles','troit_friendships','troit_blocks','troit_messages','troit_user_settings','troit_social_limits') and c.relrowsecurity")).rows[0].n;
  check(protectedTables === 6, 'All six social tables enable row-level security');
  for (const table of ['troit_profiles', 'troit_friendships', 'troit_blocks', 'troit_messages', 'troit_user_settings', 'troit_social_limits']) {
    await rejects(() => as(alice, `select * from public.${table}`), `Authenticated raw reads denied for ${table}`, '42501');
  }
  await rejects(() => as(alice, "insert into public.troit_profiles(user_id,handle,display_name) values($1,'bypass','Bypass')", [alice]), 'Direct client profile writes denied', '42501');
  await rejects(() => as(null, 'select public.troit_social_snapshot()', [], 'anon'), 'Anonymous social RPC denied', '42501');
  await rejects(() => rpc(null, 'troit_social_snapshot'), 'Missing identity rejected by authenticated RPC', '28000');
  await rejects(() => as(null, 'select public.troit_chat_inbox()', [], 'anon'), 'Anonymous inbox access denied', '42501');
  await rejects(() => rpc(null, 'troit_chat_inbox'), 'Inbox rejects a missing authenticated identity', '28000');
  await rejects(() => rpc(alice, 'troit_social_rate', ['bypass', 999999, 1, 0]), 'Private rate-limit helper cannot be called by clients', '42501');

  const profile = await action(alice, 'troit_profile_save', [' Alice_One ', 'Alice']);
  check(profile.handle === 'alice_one' && profile.id === alice, 'Profiles normalize handles and derive owner from the authenticated identity');
  await action(bob, 'troit_profile_save', ['bob_two', 'Bob']);
  await action(outsider, 'troit_profile_save', ['outsider_three', 'Outsider']);
  await action(fourth, 'troit_profile_save', ['fourth_user', 'Fourth']);
  await rejects(() => action(outsider, 'troit_profile_save', ['alice_one', 'Impostor']), 'Duplicate handles rejected');
  await rejects(() => action(alice, 'troit_profile_save', ['x', 'Alice']), 'Too-short handles rejected');
  await rejects(() => action(alice, 'troit_profile_save', ['unicode_é', 'Alice']), 'Non-ASCII handles rejected');
  await rejects(() => action(alice, 'troit_profile_save', ['alice_one', 'x'.repeat(41)]), 'Oversized display names rejected');
  const empty = await rpc(alice, 'troit_social_snapshot');
  check(empty.profile.id === alice && empty.connections.length === 0 && !('email' in empty.profile), 'Snapshot exposes own profile without a public directory or email');
  await rejects(() => action(alice, 'troit_friend_request', ['alice_one']), 'Self-friend requests rejected');
  await rejects(() => action(alice, 'troit_friend_request', ['bob']), 'Friend requests use exact handles rather than broad lookup');
  await rejects(() => action(alice, 'troit_chat_send', [bob, uid(10), 'too soon', null]), 'Sending before friendship acceptance rejected', '42501');
  await rejects(() => rpc(alice, 'troit_chat_messages', [bob, null, null, 50]), 'Reading before friendship acceptance rejected', '42501');
  await action(alice, 'troit_friend_request', ['BOB_TWO']);
  const pending = await rpc(bob, 'troit_social_snapshot');
  const relation = pending.connections[0].id;
  check(pending.connections[0].status === 'pending' && pending.connections[0].requested_by === alice, 'Recipient receives the cross-device pending request');
  await action(alice, 'troit_friend_request', ['bob_two']);
  check((await rpc(alice, 'troit_social_snapshot')).connections.length === 1, 'Repeated friend requests do not create duplicate relationships');
  await rejects(() => action(alice, 'troit_friend_action', [relation, 'accept']), 'Requester cannot accept their own request');
  await rejects(() => action(outsider, 'troit_friend_action', [relation, 'accept']), 'Outsider cannot act on another relationship');
  await action(bob, 'troit_friend_action', [relation, 'accept']);
  check((await rpc(alice, 'troit_social_snapshot')).connections[0].status === 'accepted', 'Recipient acceptance is visible to the sender');

  const sent = await action(alice, 'troit_chat_send', [bob, uid(11), 'Hello Bob', null]);
  const repeated = await rpc(alice, 'troit_chat_send', [bob, uid(11), 'Hello Bob', null]);
  check(sent.id === repeated.id && sent.seq === repeated.seq, 'Retrying a message UUID is idempotent');
  check((await db.query('select count(*)::int as n from public.troit_messages where id=$1', [uid(11)])).rows[0].n === 1, 'Only one message row is stored after a retry');
  await rejects(() => rpc(alice, 'troit_chat_send', [bob, uid(11), 'Changed payload', null]), 'Reusing an id with different content is rejected');
  await rejects(() => action(alice, 'troit_chat_send', [bob, uid(12), null, null]), 'Both-null message payload rejected');
  await rejects(() => action(alice, 'troit_chat_send', [bob, uid(12), 'text', 'jawir-gas']), 'Mixed text-and-sticker payload rejected');
  await rejects(() => action(alice, 'troit_chat_send', [bob, uid(12), '', null]), 'Empty messages rejected');
  await rejects(() => action(alice, 'troit_chat_send', [bob, uid(12), 'x'.repeat(2001), null]), 'Oversized messages rejected');
  await rejects(() => action(alice, 'troit_chat_send', [bob, uid(12), null, 'unknown-sticker']), 'Unknown sticker ids rejected');
  const sticker = await action(bob, 'troit_chat_send', [alice, uid(13), null, 'jawir-gas']);
  check(sticker.sticker_id === 'jawir-gas' && sticker.text === null, 'Compact supported sticker ids round-trip between accounts');
  const delivered = await rpc(bob, 'troit_chat_messages', [alice, null, null, 50]);
  check(delivered.some(m => m.id === sent.id && m.text === 'Hello Bob'), 'Recipient can read the sender message from another session');
  await rejects(() => rpc(outsider, 'troit_chat_messages', [alice, null, null, 50]), 'Outsider cannot read another conversation', '42501');
  await rejects(() => action(outsider, 'troit_chat_send', [alice, uid(14), 'Intrusion', null]), 'Outsider cannot send into another friendship', '42501');

  await db.query(`insert into public.troit_messages(id,friendship_id,sender_id,recipient_id,text)
    select gen_random_uuid(),$1,$2,$3,'Fixture message '||n from generate_series(1,125) n`, [relation, alice, bob]);
  const latest = await rpc(bob, 'troit_chat_messages', [alice, null, null, 99999]);
  check(latest.length === 50 && latest.every((m, n) => n === 0 || latest[n - 1].seq < m.seq), 'Server clamps oversized page requests to 50 ordered messages');
  const older = await rpc(bob, 'troit_chat_messages', [alice, latest[0].seq, null, 50]);
  check(older.length === 50 && older.every(m => m.seq < latest[0].seq), 'Before cursors load the preceding bounded page without overlap');
  const after = await rpc(bob, 'troit_chat_messages', [alice, null, older.at(-1).seq, 50]);
  check(after.length === 50 && after[0].id === latest[0].id, 'After cursors retrieve the next ordered page without timestamp races');
  await rejects(() => rpc(bob, 'troit_chat_messages', [alice, 20, 10, 50]), 'Conflicting message cursors rejected');

  await action(fourth, 'troit_friend_request', ['bob_two']);
  const secondRelation = (await rpc(bob, 'troit_social_snapshot')).connections.find(c => c.peer.id === fourth).id;
  await action(bob, 'troit_friend_action', [secondRelation, 'accept']);
  const fourthMessage = await action(fourth, 'troit_chat_send', [bob, uid(16), 'Another conversation', null]);
  await action(bob, 'troit_chat_send', [fourth, uid(17), 'Outgoing reply', null]);
  const inbox = await rpc(bob, 'troit_chat_inbox');
  check(inbox.length === 2 && new Set(inbox.map(row => row.peer.id)).size === 2,
    'Inbox returns one latest incoming message for each accepted peer');
  check(inbox.find(row => row.peer.id === alice).message.id === latest.at(-1).id &&
    inbox.find(row => row.peer.id === fourth).message.id === fourthMessage.id,
    'Inbox uses per-peer incoming cursors even when the newest conversation message is outgoing');
  check(inbox.every(row => row.message.recipient_id === bob && row.message.sender_id === row.peer.id &&
    !('friendship_id' in row.message) && !('email' in row.peer)),
    'Inbox derives recipients from authenticated identity and exposes no private account fields');
  check((await rpc(alice, 'troit_chat_inbox')).every(row => row.message.id === sticker.id),
    'Inbox excludes messages sent by the requesting account');
  check((await rpc(outsider, 'troit_chat_inbox')).length === 0,
    'An outsider cannot discover other users or messages through the inbox');
  check(inbox.every((row, index) => index === 0 || inbox[index - 1].message.seq < row.message.seq),
    'Inbox messages are consistently ordered by sequence');

  const noTheme = await rpc(alice, 'troit_get_theme');
  check(noTheme === null, 'New accounts have no remote theme until they choose one');
  const theme = await action(alice, 'troit_set_theme', ['ocean', 'blue', 0]);
  check(theme.revision === 1 && theme.theme_id === 'ocean', 'First theme save creates a server-controlled revision');
  check((await rpc(alice, 'troit_get_theme')).accent_id === 'blue' && await rpc(bob, 'troit_get_theme') === null, 'Theme reads are isolated per authenticated account');
  await rejects(() => action(alice, 'troit_set_theme', ['paper', 'neutral', 0]), 'Stale theme revisions cannot overwrite another device', '40001');
  const nextTheme = await action(alice, 'troit_set_theme', ['paper', 'neutral', 1]);
  check(nextTheme.revision === 2, 'Valid theme changes increment revision atomically');
  await rejects(() => action(alice, 'troit_set_theme', ['invalid', 'neutral', 2]), 'Unsupported themes rejected by database constraint', '23514');
  await rejects(() => action(alice, 'troit_set_theme', ['paper', 'arbitrary-css', 2]), 'Unsupported accents rejected by database constraint', '23514');

  await action(bob, 'troit_friend_action', [relation, 'block']);
  check(!(await rpc(bob, 'troit_chat_inbox')).some(row => row.peer.id === alice) &&
    !(await rpc(alice, 'troit_chat_inbox')).some(row => row.peer.id === bob),
    'Blocking hides inbox messages from both participants');
  await db.query("update public.troit_friendships set status='accepted' where id=$1", [relation]);
  check(!(await rpc(bob, 'troit_chat_inbox')).some(row => row.peer.id === alice) &&
    !(await rpc(alice, 'troit_chat_inbox')).some(row => row.peer.id === bob),
    'Inbox independently checks blocks even if a friendship remains accepted');
  await db.query("update public.troit_friendships set status='removed' where id=$1", [relation]);
  check((await rpc(bob, 'troit_social_snapshot')).blocked[0].id === alice, 'Users can inspect their own block list');
  check((await rpc(alice, 'troit_social_snapshot')).connections.length === 0, 'Blocked relationships disappear from the other participant list');
  await rejects(() => rpc(alice, 'troit_chat_messages', [bob, null, null, 50]), 'Blocking revokes subsequent chat reads', '42501');
  await rejects(() => action(alice, 'troit_chat_send', [bob, uid(15), 'Blocked', null]), 'Blocking revokes subsequent sends', '42501');
  await rejects(() => action(alice, 'troit_friend_request', ['bob_two']), 'Blocked users cannot send another friend request');
  await action(bob, 'troit_friend_unblock', [alice]);
  await action(alice, 'troit_friend_request', ['bob_two']);
  await action(bob, 'troit_friend_action', [relation, 'accept']);
  await action(alice, 'troit_friend_action', [relation, 'remove']);
  check(!(await rpc(bob, 'troit_chat_inbox')).some(row => row.peer.id === alice) &&
    !(await rpc(alice, 'troit_chat_inbox')).some(row => row.peer.id === bob),
    'Removed friendships disappear from both inboxes without deleting history');
  await rejects(() => rpc(bob, 'troit_chat_messages', [alice, null, null, 50]), 'Removing friendship revokes reads without deleting history', '42501');
  check((await db.query('select count(*)::int as n from public.troit_messages where friendship_id=$1', [relation])).rows[0].n === 127, 'Removing or blocking friends preserves existing chat rows');

  await action(alice, 'troit_friend_request', ['fourth_user']);
  await rejects(() => rpc(alice, 'troit_friend_request', ['outsider_three']), 'Fast repeated friend requests are throttled');
  await db.query("update public.troit_social_limits set last_at='2000-01-01',window_start=clock_timestamp(),count=10 where user_id=$1 and action='friend_request'", [alice]);
  await rejects(() => rpc(alice, 'troit_friend_request', ['outsider_three']), 'Friend request window quota is enforced');
  await db.exec('begin; grant select on public.troit_messages to authenticated;');
  const rls = await as(outsider, 'select * from public.troit_messages');
  await db.exec('rollback');
  check(rls.rows.length === 0, 'RLS independently denies rows even if a future raw SELECT grant is accidentally added');
  console.log(`\n${checks} isolated PostgreSQL social/security integration checks passed.`);
} finally { await db.close(); }
