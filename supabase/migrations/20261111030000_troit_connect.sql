-- Widget / cross-device playback controls. Additive: legacy playback and social tables are untouched.
begin;

create table if not exists public.troit_connect_playback (
  user_id uuid primary key references auth.users(id) on delete cascade,
  device_id uuid not null,
  instance_id uuid not null,
  device_name text not null check(char_length(device_name) between 1 and 64),
  track_url text not null check(char_length(track_url) between 1 and 2048 and track_url like 'https://%'),
  title text not null check(char_length(title) between 1 and 500),
  artist text not null check(char_length(artist) <= 500),
  artwork text check(artwork is null or (char_length(artwork) <= 4096 and artwork like 'https://%')),
  duration_seconds bigint not null check(duration_seconds between 0 and 604800),
  queue_index integer not null check(queue_index >= 0),
  playing boolean not null,
  position_ms bigint not null check(position_ms between 0 and 604800000),
  revision bigint not null default 1 check(revision > 0),
  updated_at timestamptz not null default clock_timestamp()
);

create table if not exists public.troit_connect_commands (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  seq bigint generated always as identity unique,
  device_id uuid not null,
  instance_id uuid not null,
  expected_revision bigint not null,
  expected_url text not null,
  queue_index integer not null,
  action text not null check(action in ('previous','next','play','pause')),
  status text not null default 'pending' check(status in ('pending','claimed','executed','stale','expired','failed')),
  created_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null default clock_timestamp() + interval '30 seconds',
  consumed_at timestamptz,
  primary key(user_id,id)
);
create index if not exists troit_connect_commands_pending on public.troit_connect_commands(user_id,device_id,instance_id,seq) where status='pending';
create table if not exists public.troit_connect_limits (
  user_id uuid not null references auth.users(id) on delete cascade,
  action text not null,
  window_start timestamptz not null,
  last_at timestamptz not null,
  count integer not null,
  primary key(user_id,action)
);
alter table public.troit_connect_playback enable row level security;
alter table public.troit_connect_commands enable row level security;
alter table public.troit_connect_limits enable row level security;
revoke all on public.troit_connect_playback,public.troit_connect_commands,public.troit_connect_limits from anon,authenticated;
revoke all on sequence public.troit_connect_commands_seq_seq from anon,authenticated;

create or replace function public.troit_connect_rate(p_action text,p_max integer,p_seconds integer,p_spacing_ms integer)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); t timestamptz := clock_timestamp(); r public.troit_connect_limits%rowtype;
begin
  if me is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended('connect:' || me::text || ':' || p_action,0));
  select * into r from public.troit_connect_limits where user_id=me and action=p_action for update;
  if found and t-r.last_at < p_spacing_ms * interval '1 millisecond' then raise exception 'Please wait a moment before trying again.'; end if;
  if found and t-r.window_start < p_seconds * interval '1 second' and r.count >= p_max then raise exception 'Too many playback requests. Please try again shortly.'; end if;
  insert into public.troit_connect_limits(user_id,action,window_start,last_at,count) values(me,p_action,t,t,1)
  on conflict(user_id,action) do update set
    count=case when t-public.troit_connect_limits.window_start >= p_seconds*interval '1 second' then 1 else public.troit_connect_limits.count+1 end,
    window_start=case when t-public.troit_connect_limits.window_start >= p_seconds*interval '1 second' then t else public.troit_connect_limits.window_start end,
    last_at=t;
end $$;
revoke all on function public.troit_connect_rate(text,integer,integer,integer) from public,anon,authenticated;

create or replace function public.troit_connect_state()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  return (select (to_jsonb(p)-'user_id') || jsonb_build_object(
    'expires_at',p.updated_at+interval '45 seconds','fresh',clock_timestamp()-p.updated_at <= interval '45 seconds')
    from public.troit_connect_playback p where user_id=auth.uid());
end $$;

create or replace function public.troit_connect_publish(
  p_device_id uuid,p_instance_id uuid,p_device_name text,p_track_url text,p_title text,p_artist text,
  p_artwork text,p_duration_seconds bigint,p_queue_index integer,p_playing boolean,p_position_ms bigint,p_takeover boolean default false)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); current public.troit_connect_playback%rowtype;
begin
  if me is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  if p_device_id is null or p_instance_id is null or p_playing is null or p_track_url is null or p_title is null
    or char_length(p_device_name) not between 1 and 64 or char_length(p_title) not between 1 and 500
    or p_device_name is null or char_length(coalesce(p_artist,'')) > 500
    or char_length(p_track_url) > 2048 or p_track_url not like 'https://%'
    or (p_artwork is not null and (char_length(p_artwork)>4096 or p_artwork not like 'https://%'))
    or p_duration_seconds is null or p_duration_seconds not between 0 and 604800
    or p_position_ms is null or p_position_ms not between 0 and 604800000
    or p_queue_index is null or p_queue_index < 0 then raise exception 'Invalid playback details.'; end if;
  -- Every publisher uses the same account lock before inspecting or replacing the active pointer.
  perform pg_advisory_xact_lock(hashtextextended('connect-active:' || me::text,0));
  select * into current from public.troit_connect_playback where user_id=me for update;
  -- An idle heartbeat never promotes a different instance, including a stale restored queue.
  if found and (current.instance_id <> p_instance_id or current.device_id <> p_device_id) and not coalesce(p_takeover,false) then
    return public.troit_connect_state();
  end if;
  if not found and not coalesce(p_takeover,false) then return null; end if;
  perform public.troit_connect_rate('publish',120,60,100);
  insert into public.troit_connect_playback(user_id,device_id,instance_id,device_name,track_url,title,artist,artwork,duration_seconds,queue_index,playing,position_ms)
    values(me,p_device_id,p_instance_id,p_device_name,p_track_url,p_title,coalesce(p_artist,''),p_artwork,p_duration_seconds,p_queue_index,p_playing,p_position_ms)
  on conflict(user_id) do update set
    revision=case when public.troit_connect_playback.device_id<>excluded.device_id
      or public.troit_connect_playback.instance_id<>excluded.instance_id
      or public.troit_connect_playback.track_url<>excluded.track_url
      or public.troit_connect_playback.queue_index<>excluded.queue_index then public.troit_connect_playback.revision+1 else public.troit_connect_playback.revision end,
    device_id=excluded.device_id,instance_id=excluded.instance_id,device_name=excluded.device_name,
    track_url=excluded.track_url,title=excluded.title,artist=excluded.artist,artwork=excluded.artwork,
    duration_seconds=excluded.duration_seconds,queue_index=excluded.queue_index,playing=excluded.playing,
    position_ms=excluded.position_ms,updated_at=clock_timestamp();
  return public.troit_connect_state();
end $$;

create or replace function public.troit_connect_command(
  p_command_id uuid,p_device_id uuid,p_instance_id uuid,p_expected_revision bigint,p_expected_url text,p_observed_at timestamptz,p_action text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); target public.troit_connect_playback%rowtype; existing public.troit_connect_commands%rowtype;
begin
  if me is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  if p_command_id is null or p_action is null or p_action not in ('previous','next','play','pause') then raise exception 'Unsupported playback control.'; end if;
  -- Serialize reuse of one UUID before inspecting its receipt, including competing network retries.
  perform pg_advisory_xact_lock(hashtextextended('connect-command:' || me::text || ':' || p_command_id::text,0));
  select * into existing from public.troit_connect_commands where user_id=me and id=p_command_id;
  if found then
    if existing.device_id=p_device_id and existing.instance_id=p_instance_id and existing.expected_revision=p_expected_revision
      and existing.expected_url=p_expected_url and existing.action=p_action then
      return jsonb_build_object('id',existing.id,'status',case when existing.status='pending' and existing.expires_at<clock_timestamp() then 'expired' else existing.status end);
    end if;
    raise exception 'This playback request identifier was already used.';
  end if;
  select * into target from public.troit_connect_playback where user_id=me for share;
  if not found or target.device_id is distinct from p_device_id or target.instance_id is distinct from p_instance_id
    or target.revision is distinct from p_expected_revision or target.track_url is distinct from p_expected_url
    or clock_timestamp()-target.updated_at > interval '45 seconds' or p_observed_at is null
    or clock_timestamp()-p_observed_at > interval '45 seconds' or p_observed_at > clock_timestamp()+interval '5 seconds' then
    raise exception 'Playback changed or the device is offline. Refresh the widget and try again.' using errcode='40001';
  end if;
  perform public.troit_connect_rate('command',60,60,150);
  -- All retries carry the original observed snapshot, which expires after 45s. Keep more than that window
  -- of command IDs while bounding each account's stored history; no message/audio queues are copied.
  delete from public.troit_connect_commands where user_id=me and expires_at < clock_timestamp()-interval '5 minutes';
  if (select count(*) from public.troit_connect_commands where user_id=me and status='pending' and expires_at>clock_timestamp()) >= 20 then
    raise exception 'Playback controls are busy. Please wait a moment.';
  end if;
  insert into public.troit_connect_commands(user_id,id,device_id,instance_id,expected_revision,expected_url,queue_index,action)
    values(me,p_command_id,p_device_id,p_instance_id,p_expected_revision,p_expected_url,target.queue_index,p_action)
    on conflict(user_id,id) do nothing;
  return jsonb_build_object('id',p_command_id,'status','pending');
end $$;

create or replace function public.troit_connect_claim(p_device_id uuid,p_instance_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); target public.troit_connect_playback%rowtype; command public.troit_connect_commands%rowtype;
begin
  if me is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  -- Match publish/enqueue lock ordering: active pointer first, pending commands second.
  select * into target from public.troit_connect_playback where user_id=me for share;
  update public.troit_connect_commands set status='expired',consumed_at=clock_timestamp()
    where user_id=me and device_id=p_device_id and instance_id=p_instance_id and status='pending' and expires_at<=clock_timestamp();
  if target.user_id is null or target.device_id is distinct from p_device_id or target.instance_id is distinct from p_instance_id
    or clock_timestamp()-target.updated_at > interval '45 seconds' then
    update public.troit_connect_commands set status='stale',consumed_at=clock_timestamp()
      where user_id=me and device_id=p_device_id and instance_id=p_instance_id and status='pending';
    return null;
  end if;
  update public.troit_connect_commands set status='stale',consumed_at=clock_timestamp()
    where user_id=me and device_id=p_device_id and instance_id=p_instance_id and status='pending'
      and (expected_revision<>target.revision or expected_url<>target.track_url or queue_index<>target.queue_index);
  select * into command from public.troit_connect_commands where user_id=me and device_id=p_device_id
    and instance_id=p_instance_id and status='pending' order by seq for update skip locked limit 1;
  if not found then return null; end if;
  -- Consume before returning. A crash can lose a control, but cannot replay a skip on restart.
  update public.troit_connect_commands set status='claimed',consumed_at=clock_timestamp() where user_id=me and id=command.id;
  return jsonb_build_object('id',command.id,'action',command.action,'track_url',command.expected_url,'revision',command.expected_revision,'queue_index',command.queue_index);
end $$;

create or replace function public.troit_connect_ack(p_command_id uuid,p_device_id uuid,p_instance_id uuid,p_result text)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  if p_result is null or p_result not in ('executed','stale','failed') then raise exception 'Invalid playback result.'; end if;
  update public.troit_connect_commands set status=p_result where user_id=auth.uid() and id=p_command_id
    and device_id=p_device_id and instance_id=p_instance_id and status='claimed';
end $$;

create or replace function public.troit_connect_command_status(p_command_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  return (select jsonb_build_object('id',c.id,'status',case when c.status='pending' and c.expires_at<=clock_timestamp() then 'expired' else c.status end)
    from public.troit_connect_commands c where c.user_id=auth.uid() and c.id=p_command_id);
end $$;

revoke all on function public.troit_connect_state(),public.troit_connect_publish(uuid,uuid,text,text,text,text,text,bigint,integer,boolean,bigint,boolean),
  public.troit_connect_command(uuid,uuid,uuid,bigint,text,timestamptz,text),public.troit_connect_claim(uuid,uuid),
  public.troit_connect_ack(uuid,uuid,uuid,text),public.troit_connect_command_status(uuid) from public,anon;
grant execute on function public.troit_connect_state(),public.troit_connect_publish(uuid,uuid,text,text,text,text,text,bigint,integer,boolean,bigint,boolean),
  public.troit_connect_command(uuid,uuid,uuid,bigint,text,timestamptz,text),public.troit_connect_claim(uuid,uuid),
  public.troit_connect_ack(uuid,uuid,uuid,text),public.troit_connect_command_status(uuid) to authenticated;
commit;
