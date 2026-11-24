-- Troit social features. Additive migration; existing music/account tables are untouched.
-- All public operations use authenticated RPCs. No service/secret key belongs in a client.
begin;

create table if not exists public.troit_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  handle text not null unique check (handle ~ '^[a-z0-9_]{3,24}$'),
  display_name text not null check (char_length(btrim(display_name)) between 1 and 40),
  updated_at timestamptz not null default now()
);
create table if not exists public.troit_friendships (
  id uuid primary key default gen_random_uuid(),
  user_low uuid not null references auth.users(id) on delete cascade,
  user_high uuid not null references auth.users(id) on delete cascade,
  requested_by uuid not null references auth.users(id) on delete cascade,
  status text not null check (status in ('pending','accepted','removed')),
  updated_at timestamptz not null default now(),
  unique (user_low,user_high),
  check (user_low < user_high),
  check (requested_by in (user_low,user_high))
);
create index if not exists troit_friendships_low_status on public.troit_friendships(user_low,status);
create index if not exists troit_friendships_high_status on public.troit_friendships(user_high,status);
create table if not exists public.troit_blocks (
  blocker_id uuid not null references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(blocker_id,blocked_id), check(blocker_id <> blocked_id)
);
create index if not exists troit_blocks_target on public.troit_blocks(blocked_id,blocker_id);
create table if not exists public.troit_messages (
  id uuid primary key,
  seq bigint generated always as identity unique,
  friendship_id uuid not null references public.troit_friendships(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  text text,
  sticker_id text,
  created_at timestamptz not null default clock_timestamp(),
  check(sender_id <> recipient_id),
  check (num_nonnulls(text,sticker_id)=1 and (
    (text is not null and char_length(btrim(text)) between 1 and 2000)
    or (sticker_id is not null and sticker_id in ('jomok-waduh','jomok-santuy','jomok-wkwk','jomok-bruh','jawir-gas','jawir-ngopi','jawir-siap','jawir-mantap'))
  )
  )
);
create index if not exists troit_messages_conversation_seq on public.troit_messages(friendship_id,seq desc);
create table if not exists public.troit_user_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  theme_id text not null default 'monochrome' check(theme_id in ('monochrome','midnight','paper','rose','ocean')),
  accent_id text not null default 'neutral' check(accent_id in ('neutral','coral','mint','blue','violet')),
  revision bigint not null default 1 check(revision > 0),
  updated_at timestamptz not null default now()
);
create table if not exists public.troit_social_limits (
  user_id uuid not null references auth.users(id) on delete cascade,
  action text not null,
  window_start timestamptz not null,
  last_at timestamptz not null,
  count integer not null,
  primary key(user_id,action)
);

alter table public.troit_profiles enable row level security;
alter table public.troit_friendships enable row level security;
alter table public.troit_blocks enable row level security;
alter table public.troit_messages enable row level security;
alter table public.troit_user_settings enable row level security;
alter table public.troit_social_limits enable row level security;
-- RPCs below perform participant checks explicitly. No general profile directory, email access,
-- direct message insertion, update, or delete is exposed through the public API.
revoke all on public.troit_profiles, public.troit_friendships, public.troit_blocks,
  public.troit_messages, public.troit_user_settings, public.troit_social_limits from anon, authenticated;
revoke all on sequence public.troit_messages_seq_seq from anon, authenticated;

create or replace function public.troit_social_rate(p_action text,p_max integer,p_seconds integer,p_spacing_ms integer default 0)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); t timestamptz := clock_timestamp(); r public.troit_social_limits%rowtype;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended(me::text || ':' || p_action,0));
  select * into r from public.troit_social_limits where user_id=me and action=p_action for update;
  if found and (t-r.last_at) < (p_spacing_ms * interval '1 millisecond') then
    raise exception 'Please wait a moment before trying again.' using errcode='P0001';
  end if;
  if found and t-r.window_start < (p_seconds * interval '1 second') and r.count >= p_max then
    raise exception 'Too many requests. Please try again shortly.' using errcode='P0001';
  end if;
  insert into public.troit_social_limits(user_id,action,window_start,last_at,count)
    values(me,p_action,t,t,1)
    on conflict(user_id,action) do update set
      count=case when t-public.troit_social_limits.window_start >= p_seconds * interval '1 second' then 1 else public.troit_social_limits.count+1 end,
      window_start=case when t-public.troit_social_limits.window_start >= p_seconds * interval '1 second' then t else public.troit_social_limits.window_start end,
      last_at=t;
end $$;
revoke all on function public.troit_social_rate(text,integer,integer,integer) from public,anon,authenticated;

create or replace function public.troit_profile_save(p_handle text,p_display_name text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); h text := lower(btrim(p_handle)); n text := btrim(p_display_name); r public.troit_profiles%rowtype;
begin
  perform public.troit_social_rate('profile',10,60,500);
  if h is null or h !~ '^[a-z0-9_]{3,24}$' or n is null or char_length(n) not between 1 and 40 then
    raise exception 'Use a 3–24 character handle and a 1–40 character display name.';
  end if;
  insert into public.troit_profiles(user_id,handle,display_name) values(me,h,n)
    on conflict(user_id) do update set handle=excluded.handle,display_name=excluded.display_name,updated_at=now()
    returning * into r;
  return jsonb_build_object('id',r.user_id,'handle',r.handle,'display_name',r.display_name);
exception when unique_violation then raise exception 'That handle is already taken.';
end $$;

create or replace function public.troit_social_snapshot()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); result jsonb;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  select jsonb_build_object(
    'profile',(select jsonb_build_object('id',user_id,'handle',handle,'display_name',display_name) from public.troit_profiles where user_id=me),
    'connections',coalesce((select jsonb_agg(row_data order by sort_name) from (
      select p.handle as sort_name,jsonb_build_object('id',f.id,'peer',jsonb_build_object('id',p.user_id,'handle',p.handle,'display_name',p.display_name),'status',f.status,'requested_by',f.requested_by) as row_data
      from public.troit_friendships f join public.troit_profiles p on p.user_id=case when f.user_low=me then f.user_high else f.user_low end
      where (f.user_low=me or f.user_high=me) and f.status in ('pending','accepted')
      and not exists(select 1 from public.troit_blocks b where (b.blocker_id=me and b.blocked_id=p.user_id) or (b.blocker_id=p.user_id and b.blocked_id=me))
      order by p.handle limit 400
    ) bounded),'[]'::jsonb),
    'blocked',coalesce((select jsonb_agg(jsonb_build_object('id',p.user_id,'handle',p.handle,'display_name',p.display_name) order by p.handle)
      from public.troit_blocks b join public.troit_profiles p on p.user_id=b.blocked_id where b.blocker_id=me),'[]'::jsonb)
  ) into result;
  return result;
end $$;

create or replace function public.troit_friend_request(p_handle text)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); peer uuid; r public.troit_friendships%rowtype;
begin
  perform public.troit_social_rate('friend_request',10,60,500);
  if not exists(select 1 from public.troit_profiles where user_id=me) then raise exception 'Create your chat profile first.'; end if;
  select user_id into peer from public.troit_profiles where handle=lower(btrim(p_handle));
  if peer is null or peer=me then raise exception 'Enter another user''s exact handle.'; end if;
  perform pg_advisory_xact_lock(hashtextextended(least(me,peer)::text || ':' || greatest(me,peer)::text,0));
  if exists(select 1 from public.troit_blocks where (blocker_id=me and blocked_id=peer) or (blocker_id=peer and blocked_id=me)) then raise exception 'This user is unavailable.'; end if;
  select * into r from public.troit_friendships where user_low=least(me,peer) and user_high=greatest(me,peer) for update;
  if found and r.status in ('accepted','pending') then return; end if;
  -- Serialize each user's connection-cap check as well, so simultaneous requests cannot exceed it.
  perform pg_advisory_xact_lock(hashtextextended('social-cap:' || least(me,peer)::text,0));
  perform pg_advisory_xact_lock(hashtextextended('social-cap:' || greatest(me,peer)::text,0));
  if (select count(*) from public.troit_friendships where (user_low=me or user_high=me) and status in ('pending','accepted')) >= 300
    or (select count(*) from public.troit_friendships where (user_low=peer or user_high=peer) and status in ('pending','accepted')) >= 300 then
    raise exception 'The friend list is full. Remove an old request or friend first.';
  end if;
  insert into public.troit_friendships(user_low,user_high,requested_by,status) values(least(me,peer),greatest(me,peer),me,'pending')
    on conflict(user_low,user_high) do update set requested_by=me,status='pending',updated_at=now();
end $$;

create or replace function public.troit_friend_action(p_connection_id uuid,p_action text)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); r public.troit_friendships%rowtype; peer uuid;
begin
  perform public.troit_social_rate('friend_action',30,60,250);
  select * into r from public.troit_friendships where id=p_connection_id and me in (user_low,user_high) for update;
  if not found then raise exception 'This friend request is no longer available.'; end if;
  peer := case when r.user_low=me then r.user_high else r.user_low end;
  if p_action='accept' then
    if r.status <> 'pending' or r.requested_by=me then raise exception 'Only the recipient can accept a pending request.'; end if;
    if exists(select 1 from public.troit_blocks where (blocker_id=me and blocked_id=peer) or (blocker_id=peer and blocked_id=me)) then raise exception 'This user is unavailable.'; end if;
    update public.troit_friendships set status='accepted',updated_at=now() where id=r.id;
  elsif p_action in ('decline','remove') then
    if p_action='decline' and r.status <> 'pending' then raise exception 'This request is no longer pending.'; end if;
    update public.troit_friendships set status='removed',updated_at=now() where id=r.id;
  elsif p_action='block' then
    if (select count(*) from public.troit_blocks where blocker_id=me) >= 200 then raise exception 'Your block list is full.'; end if;
    insert into public.troit_blocks(blocker_id,blocked_id) values(me,peer) on conflict do nothing;
    update public.troit_friendships set status='removed',updated_at=now() where id=r.id;
  else raise exception 'Unknown friend action.';
  end if;
end $$;

create or replace function public.troit_friend_unblock(p_peer_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  perform public.troit_social_rate('friend_action',30,60,250);
  delete from public.troit_blocks where blocker_id=auth.uid() and blocked_id=p_peer_id;
end $$;

create or replace function public.troit_chat_send(p_peer_id uuid,p_message_id uuid,p_text text default null,p_sticker_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); f public.troit_friendships%rowtype; m public.troit_messages%rowtype;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  -- Locking a conversation serializes inserts through commit, so a seq cursor cannot miss
  -- a lower sequence that was still uncommitted when a later message was fetched.
  select * into f from public.troit_friendships where user_low=least(me,p_peer_id) and user_high=greatest(me,p_peer_id) for update;
  if not found or f.status <> 'accepted' or exists(select 1 from public.troit_blocks where (blocker_id=me and blocked_id=p_peer_id) or (blocker_id=p_peer_id and blocked_id=me)) then
    raise exception 'Chat is available only between accepted friends.' using errcode='42501';
  end if;
  select * into m from public.troit_messages where id=p_message_id;
  if found then
    if m.sender_id=me and m.recipient_id=p_peer_id and m.text is not distinct from p_text and m.sticker_id is not distinct from p_sticker_id then return to_jsonb(m)-'friendship_id'; end if;
    raise exception 'This message identifier has already been used.';
  end if;
  if p_message_id is null or num_nonnulls(p_text,p_sticker_id) <> 1 or not coalesce((
    (p_text is not null and char_length(btrim(p_text)) between 1 and 2000 and p_sticker_id is null)
    or (p_text is null and p_sticker_id in ('jomok-waduh','jomok-santuy','jomok-wkwk','jomok-bruh','jawir-gas','jawir-ngopi','jawir-siap','jawir-mantap'))
  ),false) then raise exception 'Send 1–2000 text characters or one supported sticker.'; end if;
  perform public.troit_social_rate('message',60,60,250);
  perform public.troit_social_rate('message_day',1000,86400,0);
  insert into public.troit_messages(id,friendship_id,sender_id,recipient_id,text,sticker_id)
    values(p_message_id,f.id,me,p_peer_id,p_text,p_sticker_id) returning * into m;
  return to_jsonb(m)-'friendship_id';
end $$;

create or replace function public.troit_chat_messages(p_peer_id uuid,p_before bigint default null,p_after bigint default null,p_limit integer default 50)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); f public.troit_friendships%rowtype; result jsonb; page_limit integer := least(50,greatest(1,coalesce(p_limit,50)));
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  if p_before is not null and p_after is not null then raise exception 'Use one message cursor at a time.'; end if;
  select * into f from public.troit_friendships where user_low=least(me,p_peer_id) and user_high=greatest(me,p_peer_id) for share;
  if not found or f.status <> 'accepted' or exists(select 1 from public.troit_blocks where (blocker_id=me and blocked_id=p_peer_id) or (blocker_id=p_peer_id and blocked_id=me)) then
    raise exception 'Chat is available only between accepted friends.' using errcode='42501';
  end if;
  if p_after is not null then
    select coalesce(jsonb_agg(to_jsonb(m)-'friendship_id' order by m.seq),'[]'::jsonb) into result from (
      select * from public.troit_messages where friendship_id=f.id and seq>p_after order by seq limit page_limit
    ) m;
  else
    select coalesce(jsonb_agg(to_jsonb(m)-'friendship_id' order by m.seq),'[]'::jsonb) into result from (
      select * from public.troit_messages where friendship_id=f.id and (p_before is null or seq<p_before) order by seq desc limit page_limit
    ) m;
  end if;
  return result;
end $$;

create or replace function public.troit_get_theme()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  return (select to_jsonb(s)-'user_id' from public.troit_user_settings s where user_id=auth.uid());
end $$;

create or replace function public.troit_set_theme(p_theme_id text,p_accent_id text,p_expected_revision bigint)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); r public.troit_user_settings%rowtype;
begin
  perform public.troit_social_rate('theme',30,60,100);
  perform pg_advisory_xact_lock(hashtextextended('theme:' || me::text,0));
  select * into r from public.troit_user_settings where user_id=me for update;
  if (found and r.revision is distinct from p_expected_revision) or (not found and p_expected_revision is distinct from 0::bigint) then
    raise exception 'Theme changed on another device. Refresh and try again.' using errcode='40001';
  end if;
  insert into public.troit_user_settings(user_id,theme_id,accent_id) values(me,p_theme_id,p_accent_id)
    on conflict(user_id) do update set theme_id=excluded.theme_id,accent_id=excluded.accent_id,revision=public.troit_user_settings.revision+1,updated_at=now()
    returning * into r;
  return to_jsonb(r)-'user_id';
end $$;

revoke all on function public.troit_profile_save(text,text), public.troit_social_snapshot(),
  public.troit_friend_request(text),public.troit_friend_action(uuid,text),public.troit_friend_unblock(uuid),
  public.troit_chat_send(uuid,uuid,text,text),public.troit_chat_messages(uuid,bigint,bigint,integer),
  public.troit_get_theme(),public.troit_set_theme(text,text,bigint) from public,anon;
grant execute on function public.troit_profile_save(text,text), public.troit_social_snapshot(),
  public.troit_friend_request(text),public.troit_friend_action(uuid,text),public.troit_friend_unblock(uuid),
  public.troit_chat_send(uuid,uuid,text,text),public.troit_chat_messages(uuid,bigint,bigint,integer),
  public.troit_get_theme(),public.troit_set_theme(text,text,bigint) to authenticated;
commit;
