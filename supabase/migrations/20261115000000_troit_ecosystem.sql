-- Named online receivers and confirmed full-queue operations. Apply after Troit Connect.
begin;

create table if not exists public.troit_ecosystem_devices (
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id uuid not null,
  instance_id uuid not null,
  name text not null check(char_length(name) between 1 and 64),
  kind text not null check(kind in ('pc','phone','tv')),
  intent_revision bigint not null default 0 check(intent_revision between 0 and 9000000000000000),
  updated_at timestamptz not null default clock_timestamp(),
  primary key(user_id,device_id)
);
create table if not exists public.troit_ecosystem_queues (
  user_id uuid primary key references auth.users(id) on delete cascade,
  device_id uuid not null,
  instance_id uuid not null,
  revision bigint not null default 1,
  queue jsonb not null,
  mode text not null check(mode in ('STOP','REPEAT_ALL','REPEAT_ONE','RADIO')),
  updated_at timestamptz not null default clock_timestamp()
);
create table if not exists public.troit_ecosystem_commands (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  seq bigint generated always as identity unique,
  device_id uuid not null,
  instance_id uuid not null,
  kind text not null check(kind in ('edit','transfer')),
  request jsonb not null,
  payload jsonb not null,
  source_device_id uuid not null,
  source_instance_id uuid not null,
  source_playback_revision bigint not null,
  source_intent_revision bigint not null,
  expected_revision bigint not null,
  expected_intent_revision bigint not null,
  status text not null default 'pending' check(status in ('pending','claimed','executed','stale','expired','failed')),
  created_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null default clock_timestamp()+interval '30 seconds',
  consumed_at timestamptz,
  activated_at timestamptz,
  primary key(user_id,id)
);
create index if not exists troit_ecosystem_commands_pending on public.troit_ecosystem_commands(user_id,device_id,instance_id,seq) where status='pending';
alter table public.troit_ecosystem_devices enable row level security;
alter table public.troit_ecosystem_queues enable row level security;
alter table public.troit_ecosystem_commands enable row level security;
revoke all on public.troit_ecosystem_devices,public.troit_ecosystem_queues,public.troit_ecosystem_commands from anon,authenticated;
revoke all on sequence public.troit_ecosystem_commands_seq_seq from anon,authenticated;

-- Older widgets still read this RPC; custom names survive generic app heartbeats/publications.
create or replace function public.troit_connect_state()
returns jsonb language plpgsql security definer set search_path='' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to use playback controls.' using errcode='28000'; end if;
  return (select (to_jsonb(p)-'user_id')||jsonb_build_object('device_name',coalesce(d.name,p.device_name),
    'expires_at',p.updated_at+interval '45 seconds','fresh',clock_timestamp()-p.updated_at<=interval '45 seconds')
    from public.troit_connect_playback p left join public.troit_ecosystem_devices d on d.user_id=p.user_id and d.device_id=p.device_id
    where p.user_id=auth.uid());
end $$;

create or replace function public.troit_ecosystem_validate_queue(p_queue jsonb)
returns void language plpgsql set search_path='' as $$
declare song jsonb;
begin
  if p_queue is null or jsonb_typeof(p_queue)<>'array' or jsonb_array_length(p_queue) not between 1 and 1000
    or octet_length(p_queue::text)>800000 then raise exception 'A connected queue must contain 1 to 1000 songs within the shared metadata limit.'; end if;
  for song in select value from jsonb_array_elements(p_queue) loop
    if jsonb_typeof(song)<>'object' or jsonb_typeof(song->'url') is distinct from 'string'
      or char_length(song->>'url') not between 1 and 2048 or song->>'url' not like 'https://%'
      or jsonb_typeof(song->'title') is distinct from 'string' or char_length(song->>'title') not between 1 and 500
      or jsonb_typeof(song->'artist') is distinct from 'string' or char_length(song->>'artist')>500
      or (song ? 'artwork' and song->'artwork'<>'null'::jsonb and (jsonb_typeof(song->'artwork')<>'string'
        or char_length(song->>'artwork')>4096 or song->>'artwork' not like 'https://%'))
      or jsonb_typeof(song->'duration') is distinct from 'number'
      or (song->>'duration') !~ '^[0-9]{1,6}$' then raise exception 'Invalid song in connected queue.'; end if;
    if (song->>'duration')::bigint not between 0 and 604800 then raise exception 'Invalid song duration.'; end if;
  end loop;
end $$;
revoke all on function public.troit_ecosystem_validate_queue(jsonb) from public,anon,authenticated;

create or replace function public.troit_ecosystem_heartbeat(
  p_device_id uuid,p_instance_id uuid,p_name text,p_kind text,p_intent_revision bigint default 0)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); old public.troit_ecosystem_devices%rowtype;
begin
  if me is null then raise exception 'Sign in to connect your devices.' using errcode='28000'; end if;
  if p_device_id is null or p_instance_id is null or p_name is null or char_length(btrim(p_name)) not between 1 and 64
    or p_name ~ '[[:cntrl:]]' or p_kind is null or p_kind not in ('pc','phone','tv')
    or p_intent_revision is null or p_intent_revision not between 0 and 9000000000000000 then raise exception 'Invalid device details.'; end if;
  perform pg_advisory_xact_lock(hashtextextended('ecosystem-devices:'||me::text,0));
  delete from public.troit_ecosystem_devices where user_id=me and updated_at<clock_timestamp()-interval '30 days';
  select * into old from public.troit_ecosystem_devices where user_id=me and device_id=p_device_id for update;
  if old.user_id is null and (select count(*) from public.troit_ecosystem_devices where user_id=me)>=20 then
    raise exception 'This account already has 20 recently connected devices.'; end if;
  perform public.troit_connect_rate('eco-heartbeat:'||p_device_id::text,120,60,0);
  if old.instance_id is not null and old.instance_id<>p_instance_id then
    update public.troit_ecosystem_commands set status='stale',consumed_at=clock_timestamp(),payload='{}'::jsonb
      where user_id=me and device_id=p_device_id and instance_id<>p_instance_id and status in ('pending','claimed');
  end if;
  insert into public.troit_ecosystem_devices(user_id,device_id,instance_id,name,kind,intent_revision)
    values(me,p_device_id,p_instance_id,btrim(p_name),p_kind,p_intent_revision)
  on conflict(user_id,device_id) do update set instance_id=excluded.instance_id,kind=excluded.kind,
    intent_revision=excluded.intent_revision,updated_at=clock_timestamp();
  return (select to_jsonb(d)-'user_id'||jsonb_build_object('online',true) from public.troit_ecosystem_devices d where user_id=me and device_id=p_device_id);
end $$;

create or replace function public.troit_ecosystem_devices()
returns jsonb language plpgsql security definer set search_path='' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to see your devices.' using errcode='28000'; end if;
  return coalesce((select jsonb_agg(to_jsonb(d)-'user_id'||jsonb_build_object('online',clock_timestamp()-d.updated_at<=interval '45 seconds') order by d.name,d.device_id)
    from public.troit_ecosystem_devices d where user_id=auth.uid()),'[]'::jsonb);
end $$;

create or replace function public.troit_ecosystem_rename(p_device_id uuid,p_name text)
returns void language plpgsql security definer set search_path='' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to name your device.' using errcode='28000'; end if;
  if p_name is null or char_length(btrim(p_name)) not between 1 and 64 or p_name ~ '[[:cntrl:]]' then raise exception 'Use a device name between 1 and 64 characters.'; end if;
  perform public.troit_connect_rate('eco-rename',30,60,0);
  update public.troit_ecosystem_devices set name=btrim(p_name) where user_id=auth.uid() and device_id=p_device_id;
  if not found then raise exception 'This device is no longer registered.'; end if;
  update public.troit_connect_playback set device_name=btrim(p_name) where user_id=auth.uid() and device_id=p_device_id;
end $$;

create or replace function public.troit_ecosystem_queue(p_known_revision bigint default 0)
returns jsonb language plpgsql security definer set search_path='' as $$
declare p public.troit_connect_playback%rowtype; q public.troit_ecosystem_queues%rowtype;
begin
  if auth.uid() is null then raise exception 'Sign in to see the shared queue.' using errcode='28000'; end if;
  select * into p from public.troit_connect_playback where user_id=auth.uid();
  select * into q from public.troit_ecosystem_queues where user_id=auth.uid();
  if p.user_id is null or q.user_id is null or p.device_id<>q.device_id or p.instance_id<>q.instance_id
    or q.queue->p.queue_index->>'url' is distinct from p.track_url then return null; end if;
  return (to_jsonb(q)-'user_id')||jsonb_build_object('queue',case when p_known_revision=q.revision then null else q.queue end,
    'unchanged',p_known_revision=q.revision,'index',p.queue_index,'position_ms',p.position_ms,'playing',p.playing,
    'playback_revision',p.revision,'track_url',p.track_url,'updated_at',p.updated_at,
    'fresh',clock_timestamp()-p.updated_at<=interval '45 seconds');
end $$;

create or replace function public.troit_ecosystem_publish_queue(
  p_device_id uuid,p_instance_id uuid,p_queue jsonb,p_index integer,p_position_ms bigint,p_playing boolean,p_mode text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); active public.troit_connect_playback%rowtype;
begin
  if me is null then raise exception 'Sign in to share a queue.' using errcode='28000'; end if;
  perform public.troit_ecosystem_validate_queue(p_queue);
  if p_index is null or p_index<0 or p_index>=jsonb_array_length(p_queue) or p_position_ms is null
    or p_position_ms not between 0 and 604800000 or p_playing is null or p_mode is null
    or p_mode not in ('STOP','REPEAT_ALL','REPEAT_ONE','RADIO') then raise exception 'Invalid queue state.'; end if;
  perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
  update public.troit_ecosystem_commands set status='expired',consumed_at=clock_timestamp(),payload='{}'::jsonb
    where user_id=me and status in ('pending','claimed') and expires_at<=clock_timestamp();
  select * into active from public.troit_connect_playback where user_id=me for share;
  if active.user_id is null or active.device_id is distinct from p_device_id or active.instance_id is distinct from p_instance_id
    or active.queue_index<>p_index or active.track_url is distinct from p_queue->p_index->>'url' then return public.troit_ecosystem_queue(); end if;
  if not exists(select 1 from public.troit_ecosystem_devices where user_id=me and device_id=p_device_id and instance_id=p_instance_id) then
    raise exception 'Register this playback device first.'; end if;
  perform public.troit_connect_rate('eco-publish:'||p_device_id::text,120,60,0);
  insert into public.troit_ecosystem_queues(user_id,device_id,instance_id,queue,mode)
    values(me,p_device_id,p_instance_id,p_queue,p_mode)
  on conflict(user_id) do update set revision=case when public.troit_ecosystem_queues.device_id<>excluded.device_id
    or public.troit_ecosystem_queues.instance_id<>excluded.instance_id or public.troit_ecosystem_queues.queue<>excluded.queue
    or public.troit_ecosystem_queues.mode<>excluded.mode then public.troit_ecosystem_queues.revision+1 else public.troit_ecosystem_queues.revision end,
    device_id=excluded.device_id,instance_id=excluded.instance_id,queue=excluded.queue,mode=excluded.mode,updated_at=clock_timestamp();
  return public.troit_ecosystem_queue();
end $$;

create or replace function public.troit_ecosystem_edit(
  p_command_id uuid,p_device_id uuid,p_instance_id uuid,p_expected_revision bigint,p_operation text,
  p_from integer default -1,p_to integer default -1,p_songs jsonb default '[]'::jsonb,p_observed_at timestamptz default null)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); prior public.troit_ecosystem_commands%rowtype; active public.troit_connect_playback%rowtype;
  q public.troit_ecosystem_queues%rowtype; device public.troit_ecosystem_devices%rowtype; candidate jsonb; requested jsonb; entry jsonb;
begin
  if me is null then raise exception 'Sign in to edit the shared queue.' using errcode='28000'; end if;
  if p_command_id is null or p_operation is null or p_operation not in ('add_next','add_last','remove','move')
    or p_songs is null or p_from is null or p_to is null then raise exception 'Unsupported queue operation.'; end if;
  requested:=jsonb_build_object('device_id',p_device_id,'instance_id',p_instance_id,'revision',p_expected_revision,
    'operation',p_operation,'from',p_from,'to',p_to,'songs_fingerprint',md5(p_songs::text));
  perform pg_advisory_xact_lock(hashtextextended('eco-command:'||me::text||':'||p_command_id::text,0));
  select * into prior from public.troit_ecosystem_commands where user_id=me and id=p_command_id;
  if found then
    if prior.kind<>'edit' or prior.request<>requested then raise exception 'This queue request identifier was already used.'; end if;
    return jsonb_build_object('id',prior.id,'status',case when prior.status='pending' and prior.expires_at<clock_timestamp() then 'expired' else prior.status end);
  end if;
  perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
  select * into active from public.troit_connect_playback where user_id=me for share;
  select * into q from public.troit_ecosystem_queues where user_id=me for share;
  select * into device from public.troit_ecosystem_devices where user_id=me and device_id=p_device_id and instance_id=p_instance_id;
  if active.user_id is null or q.user_id is null or device.user_id is null or active.device_id is distinct from p_device_id
    or active.instance_id is distinct from p_instance_id or q.device_id is distinct from p_device_id or q.instance_id is distinct from p_instance_id
    or q.revision is distinct from p_expected_revision or q.queue->active.queue_index->>'url' is distinct from active.track_url
    or clock_timestamp()-active.updated_at>interval '45 seconds' or clock_timestamp()-device.updated_at>interval '45 seconds'
    or p_observed_at is null or clock_timestamp()-p_observed_at>interval '45 seconds' or p_observed_at>clock_timestamp()+interval '5 seconds' then
    raise exception 'Playback or the shared queue changed. Refresh before editing.' using errcode='40001'; end if;
  if exists(select 1 from public.troit_ecosystem_commands where user_id=me and source_device_id=p_device_id
    and source_instance_id=p_instance_id and status in ('pending','claimed') and expires_at>clock_timestamp()) then
    raise exception 'Wait for the current queue change to finish.' using errcode='40001'; end if;
  candidate:=q.queue;
  if p_operation in ('add_next','add_last') then
    perform public.troit_ecosystem_validate_queue(p_songs);
    if jsonb_array_length(candidate)+jsonb_array_length(p_songs)>1000 then raise exception 'A connected queue supports up to 1000 songs.'; end if;
    if p_operation='add_last' then candidate:=candidate||p_songs;
    else
      select coalesce(jsonb_agg(value order by ordinality),'[]'::jsonb) into candidate from jsonb_array_elements(q.queue) with ordinality where ordinality<=active.queue_index+1;
      candidate:=candidate||p_songs||coalesce((select jsonb_agg(value order by ordinality) from jsonb_array_elements(q.queue) with ordinality where ordinality>active.queue_index+1),'[]'::jsonb);
    end if;
  else
    if p_songs<>'[]'::jsonb or p_from<=active.queue_index or p_from>=jsonb_array_length(candidate) then
      raise exception 'Only upcoming songs can be changed.'; end if;
    if p_operation='remove' then candidate:=candidate-p_from;
    else
      if p_to<=active.queue_index or p_to>=jsonb_array_length(candidate) then raise exception 'Choose an upcoming destination.'; end if;
      entry:=candidate->p_from;
      candidate:=jsonb_insert(candidate-p_from,array[p_to::text],entry);
    end if;
  end if;
  perform public.troit_ecosystem_validate_queue(candidate);
  perform public.troit_connect_rate('eco-command',60,60,0);
  delete from public.troit_ecosystem_commands where user_id=me and expires_at<clock_timestamp()-interval '5 minutes';
  insert into public.troit_ecosystem_commands(user_id,id,device_id,instance_id,kind,request,payload,source_device_id,source_instance_id,
    source_playback_revision,source_intent_revision,expected_revision,expected_intent_revision)
  values(me,p_command_id,p_device_id,p_instance_id,'edit',requested,jsonb_build_object('queue',candidate,'expected_queue',q.queue,
    'index',active.queue_index,'position_ms',active.position_ms,'playing',active.playing,'mode',q.mode,
    'expected_url',active.track_url,'expected_index',active.queue_index),p_device_id,p_instance_id,active.revision,device.intent_revision,q.revision,device.intent_revision);
  return jsonb_build_object('id',p_command_id,'status','pending');
end $$;

create or replace function public.troit_ecosystem_transfer(
  p_command_id uuid,p_device_id uuid,p_instance_id uuid,p_expected_revision bigint,p_target_device_id uuid,p_target_instance_id uuid,
  p_observed_at timestamptz default null,p_expected_target_intent_revision bigint default null)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); prior public.troit_ecosystem_commands%rowtype; active public.troit_connect_playback%rowtype;
  q public.troit_ecosystem_queues%rowtype; target public.troit_ecosystem_devices%rowtype; source public.troit_ecosystem_devices%rowtype; requested jsonb;
begin
  if me is null then raise exception 'Sign in to move playback.' using errcode='28000'; end if;
  if p_command_id is null or p_device_id is null or p_instance_id is null or p_expected_revision is null
    or p_target_device_id is null or p_target_instance_id is null or p_target_device_id=p_device_id
    or p_expected_target_intent_revision is null or p_expected_target_intent_revision not between 0 and 9000000000000000 then raise exception 'Choose another playback device.'; end if;
  requested:=jsonb_build_object('device_id',p_device_id,'instance_id',p_instance_id,'revision',p_expected_revision,
    'target_device_id',p_target_device_id,'target_instance_id',p_target_instance_id,'target_intent_revision',p_expected_target_intent_revision);
  perform pg_advisory_xact_lock(hashtextextended('eco-command:'||me::text||':'||p_command_id::text,0));
  select * into prior from public.troit_ecosystem_commands where user_id=me and id=p_command_id;
  if found then
    if prior.kind<>'transfer' or prior.request<>requested then raise exception 'This playback request identifier was already used.'; end if;
    return jsonb_build_object('id',prior.id,'status',case when prior.status='pending' and prior.expires_at<clock_timestamp() then 'expired' else prior.status end);
  end if;
  perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
  select * into active from public.troit_connect_playback where user_id=me for share;
  select * into q from public.troit_ecosystem_queues where user_id=me for share;
  select * into target from public.troit_ecosystem_devices where user_id=me and device_id=p_target_device_id and instance_id=p_target_instance_id;
  select * into source from public.troit_ecosystem_devices where user_id=me and device_id=p_device_id and instance_id=p_instance_id;
  if active.user_id is null or q.user_id is null or active.device_id is distinct from p_device_id or active.instance_id is distinct from p_instance_id
    or source.user_id is null or q.device_id is distinct from p_device_id or q.instance_id is distinct from p_instance_id or q.revision is distinct from p_expected_revision
    or q.queue->active.queue_index->>'url' is distinct from active.track_url or active.playing
    or clock_timestamp()-active.updated_at>interval '45 seconds' or p_observed_at is null
    or clock_timestamp()-p_observed_at>interval '45 seconds' or p_observed_at>clock_timestamp()+interval '5 seconds' then
    raise exception 'Pause the current player and refresh its full queue before switching.' using errcode='40001'; end if;
  if target.user_id is null or clock_timestamp()-target.updated_at>interval '45 seconds' then raise exception 'The selected device is offline.' using errcode='40001'; end if;
  if target.intent_revision<>p_expected_target_intent_revision then raise exception 'Playback changed on the selected device. Refresh before switching.' using errcode='40001'; end if;
  if exists(select 1 from public.troit_ecosystem_commands where user_id=me and status in ('pending','claimed') and expires_at>clock_timestamp()
    and ((source_device_id=p_device_id and source_instance_id=p_instance_id) or (device_id=p_target_device_id and instance_id=p_target_instance_id))) then
    raise exception 'Wait for the current playback change to finish.' using errcode='40001'; end if;
  perform public.troit_connect_rate('eco-command',60,60,0);
  delete from public.troit_ecosystem_commands where user_id=me and expires_at<clock_timestamp()-interval '5 minutes';
  insert into public.troit_ecosystem_commands(user_id,id,device_id,instance_id,kind,request,payload,source_device_id,source_instance_id,
    source_playback_revision,source_intent_revision,expected_revision,expected_intent_revision)
  values(me,p_command_id,p_target_device_id,p_target_instance_id,'transfer',requested,jsonb_build_object('queue',q.queue,'expected_queue',q.queue,
    'index',active.queue_index,'position_ms',active.position_ms,'playing',true,'mode',q.mode,
    'expected_url',active.track_url,'expected_index',active.queue_index),p_device_id,p_instance_id,active.revision,source.intent_revision,q.revision,target.intent_revision);
  return jsonb_build_object('id',p_command_id,'status','pending');
end $$;

create or replace function public.troit_ecosystem_claim(p_device_id uuid,p_instance_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); command public.troit_ecosystem_commands%rowtype; active public.troit_connect_playback%rowtype;
  q public.troit_ecosystem_queues%rowtype; target public.troit_ecosystem_devices%rowtype;
begin
  if me is null then raise exception 'Sign in to receive playback.' using errcode='28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
  select * into active from public.troit_connect_playback where user_id=me for share;
  select * into q from public.troit_ecosystem_queues where user_id=me for share;
  select * into target from public.troit_ecosystem_devices where user_id=me and device_id=p_device_id and instance_id=p_instance_id;
  update public.troit_ecosystem_commands set status='expired',consumed_at=clock_timestamp(),payload='{}'::jsonb
    where user_id=me and device_id=p_device_id and instance_id=p_instance_id and status='pending' and expires_at<=clock_timestamp();
  for command in select * from public.troit_ecosystem_commands where user_id=me and device_id=p_device_id and instance_id=p_instance_id
    and status='pending' order by seq for update skip locked loop
    if target.user_id is null or clock_timestamp()-target.updated_at>interval '45 seconds'
      or target.intent_revision<>command.expected_intent_revision or active.user_id is null or q.user_id is null
      or active.device_id<>command.source_device_id or active.instance_id<>command.source_instance_id
      or active.revision<>command.source_playback_revision or q.device_id<>command.source_device_id or q.instance_id<>command.source_instance_id
      or q.revision<>command.expected_revision or active.queue_index<>(command.payload->>'expected_index')::integer
      or active.track_url<>command.payload->>'expected_url' or clock_timestamp()-active.updated_at>interval '45 seconds'
      or not exists(select 1 from public.troit_ecosystem_devices where user_id=me and device_id=command.source_device_id
        and instance_id=command.source_instance_id and intent_revision=command.source_intent_revision)
      or (command.kind='transfer' and active.playing) then
      update public.troit_ecosystem_commands set status='stale',consumed_at=clock_timestamp(),payload='{}'::jsonb where user_id=me and id=command.id;
      continue;
    end if;
    update public.troit_ecosystem_commands set status='claimed',consumed_at=clock_timestamp() where user_id=me and id=command.id;
    return command.payload||jsonb_build_object('id',command.id,'kind',command.kind,'expected_revision',command.expected_revision,
      'expected_intent_revision',command.expected_intent_revision,'source_device_id',command.source_device_id,
      'source_instance_id',command.source_instance_id,'source_playback_revision',command.source_playback_revision);
  end loop;
  return null;
end $$;

create or replace function public.troit_ecosystem_ack(p_command_id uuid,p_device_id uuid,p_instance_id uuid,p_result text)
returns void language plpgsql security definer set search_path='' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to confirm playback.' using errcode='28000'; end if;
  if p_result is null or p_result not in ('executed','stale','failed') then raise exception 'Invalid playback result.'; end if;
  update public.troit_ecosystem_commands set status=p_result,payload='{}'::jsonb where user_id=auth.uid() and id=p_command_id
    and device_id=p_device_id and instance_id=p_instance_id and status='claimed';
end $$;

-- Prepare the target silently, then commit ownership only if the paused source is unchanged.
-- Retries identify the original command and never reset an already activated target's position.
create or replace function public.troit_ecosystem_activate(p_command_id uuid,p_device_id uuid,p_instance_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); command public.troit_ecosystem_commands%rowtype; active public.troit_connect_playback%rowtype;
  q public.troit_ecosystem_queues%rowtype; target public.troit_ecosystem_devices%rowtype; song jsonb;
begin
  if me is null then raise exception 'Sign in to activate playback.' using errcode='28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
  select * into active from public.troit_connect_playback where user_id=me for update;
  select * into command from public.troit_ecosystem_commands where user_id=me and id=p_command_id
    and device_id=p_device_id and instance_id=p_instance_id and kind='transfer' for update;
  if not found then return null; end if;
  if command.activated_at is not null then
    if active.device_id=p_device_id and active.instance_id=p_instance_id then return public.troit_connect_state(); end if;
    return null;
  end if;
  if command.status<>'claimed' then return null; end if;
  select * into q from public.troit_ecosystem_queues where user_id=me for share;
  select * into target from public.troit_ecosystem_devices where user_id=me and device_id=p_device_id and instance_id=p_instance_id;
  if command.expires_at<=clock_timestamp() or active.user_id is null or q.user_id is null or target.user_id is null
    or clock_timestamp()-target.updated_at>interval '45 seconds' or target.intent_revision<>command.expected_intent_revision
    or active.device_id<>command.source_device_id or active.instance_id<>command.source_instance_id
    or active.revision<>command.source_playback_revision or active.playing or clock_timestamp()-active.updated_at>interval '45 seconds'
    or not exists(select 1 from public.troit_ecosystem_devices where user_id=me and device_id=command.source_device_id
      and instance_id=command.source_instance_id and intent_revision=command.source_intent_revision)
    or q.device_id<>command.source_device_id or q.instance_id<>command.source_instance_id or q.revision<>command.expected_revision
    or active.queue_index<>(command.payload->>'expected_index')::integer or active.track_url<>command.payload->>'expected_url' then
    update public.troit_ecosystem_commands set status='stale',payload='{}'::jsonb where user_id=me and id=p_command_id;
    return null;
  end if;
  perform public.troit_connect_rate('eco-activate',60,60,0);
  song:=command.payload->'queue'->((command.payload->>'index')::integer);
  update public.troit_connect_playback set device_id=p_device_id,instance_id=p_instance_id,device_name=target.name,
    track_url=song->>'url',title=song->>'title',artist=song->>'artist',artwork=nullif(song->>'artwork',''),
    duration_seconds=(song->>'duration')::bigint,queue_index=(command.payload->>'index')::integer,
    playing=false,position_ms=(command.payload->>'position_ms')::bigint,revision=active.revision+1,updated_at=clock_timestamp()
    where user_id=me;
  update public.troit_ecosystem_commands set activated_at=clock_timestamp() where user_id=me and id=p_command_id;
  return public.troit_connect_state();
end $$;

create or replace function public.troit_ecosystem_status(p_command_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
begin
  if auth.uid() is null then raise exception 'Sign in to inspect playback.' using errcode='28000'; end if;
  return (select jsonb_build_object('id',id,'status',case when status='pending' and expires_at<=clock_timestamp() then 'expired' else status end)
    from public.troit_ecosystem_commands where user_id=auth.uid() and id=p_command_id);
end $$;

revoke all on function public.troit_ecosystem_heartbeat(uuid,uuid,text,text,bigint),public.troit_ecosystem_devices(),
  public.troit_ecosystem_rename(uuid,text),public.troit_ecosystem_queue(bigint),public.troit_ecosystem_publish_queue(uuid,uuid,jsonb,integer,bigint,boolean,text),
  public.troit_ecosystem_edit(uuid,uuid,uuid,bigint,text,integer,integer,jsonb,timestamptz),public.troit_ecosystem_transfer(uuid,uuid,uuid,bigint,uuid,uuid,timestamptz,bigint),
  public.troit_ecosystem_claim(uuid,uuid),public.troit_ecosystem_ack(uuid,uuid,uuid,text),public.troit_ecosystem_status(uuid),
  public.troit_ecosystem_activate(uuid,uuid,uuid) from public,anon;
grant execute on function public.troit_ecosystem_heartbeat(uuid,uuid,text,text,bigint),public.troit_ecosystem_devices(),
  public.troit_ecosystem_rename(uuid,text),public.troit_ecosystem_queue(bigint),public.troit_ecosystem_publish_queue(uuid,uuid,jsonb,integer,bigint,boolean,text),
  public.troit_ecosystem_edit(uuid,uuid,uuid,bigint,text,integer,integer,jsonb,timestamptz),public.troit_ecosystem_transfer(uuid,uuid,uuid,bigint,uuid,uuid,timestamptz,bigint),
  public.troit_ecosystem_claim(uuid,uuid),public.troit_ecosystem_ack(uuid,uuid,uuid,text),public.troit_ecosystem_status(uuid),
  public.troit_ecosystem_activate(uuid,uuid,uuid) to authenticated;
commit;
