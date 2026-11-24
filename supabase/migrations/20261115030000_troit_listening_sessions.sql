-- Friend listening rooms and account-owned sleep timers. Apply after social + ecosystem.
begin;
create table if not exists public.troit_party_rooms (
 id uuid primary key default gen_random_uuid(), host_id uuid not null references auth.users(id) on delete cascade,
 code text not null unique, name text not null check(char_length(name) between 1 and 64),
 active boolean not null default true, created_at timestamptz not null default clock_timestamp(),
 expires_at timestamptz not null default clock_timestamp()+interval '24 hours'
);
create unique index if not exists troit_party_one_host on public.troit_party_rooms(host_id) where active;
create table if not exists public.troit_party_members (
 room_id uuid not null references public.troit_party_rooms(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 status text not null check(status in ('active','left','removed')), joined_at timestamptz not null default clock_timestamp(),
 primary key(room_id,user_id)
);
create table if not exists public.troit_party_additions (
 guest_id uuid not null references auth.users(id) on delete cascade, id uuid not null,
 room_id uuid not null references public.troit_party_rooms(id) on delete cascade,
 host_id uuid not null references auth.users(id) on delete cascade, fingerprint text not null,
 created_at timestamptz not null default clock_timestamp(), primary key(guest_id,id)
);
create table if not exists public.troit_sleep_timers (
 user_id uuid primary key references auth.users(id) on delete cascade, id uuid not null,
 revision bigint not null default 1, kind text not null check(kind in ('minutes','end_of_track')),
 deadline timestamptz, track_url text not null, queue_index integer not null,
 status text not null check(status in ('active','cancelled','fired')), updated_at timestamptz not null default clock_timestamp()
);
create table if not exists public.troit_sleep_requests (
 user_id uuid not null references auth.users(id) on delete cascade,id uuid not null,
 fingerprint text not null, created_at timestamptz not null default clock_timestamp(),primary key(user_id,id)
);
alter table public.troit_party_rooms enable row level security;
alter table public.troit_party_members enable row level security;
alter table public.troit_party_additions enable row level security;
alter table public.troit_sleep_timers enable row level security;
alter table public.troit_sleep_requests enable row level security;
revoke all on public.troit_party_rooms,public.troit_party_members,public.troit_party_additions,public.troit_sleep_timers,public.troit_sleep_requests from anon,authenticated;

create or replace function public.troit_party_friend(p_host uuid,p_guest uuid)
returns boolean language sql stable security definer set search_path='' as $$
 select p_host=p_guest or (exists(select 1 from public.troit_friendships where user_low=least(p_host,p_guest)
 and user_high=greatest(p_host,p_guest) and status='accepted') and not exists(select 1 from public.troit_blocks
 where (blocker_id=p_host and blocked_id=p_guest) or (blocker_id=p_guest and blocked_id=p_host)))
$$;
create or replace function public.troit_party_member_allowed(p_room uuid,p_user uuid)
returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from public.troit_party_rooms r join public.troit_party_members m on m.room_id=r.id
 where r.id=p_room and r.active and r.expires_at>clock_timestamp() and m.user_id=p_user and m.status='active'
 and public.troit_party_friend(r.host_id,p_user))
$$;
revoke all on function public.troit_party_friend(uuid,uuid),public.troit_party_member_allowed(uuid,uuid) from public,anon,authenticated;

create or replace function public.troit_party_state(p_known_revision bigint default 0)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();r public.troit_party_rooms%rowtype;p public.troit_connect_playback%rowtype;q public.troit_ecosystem_queues%rowtype;queue jsonb;
begin
 if me is null then raise exception 'Sign in to use a listening room.' using errcode='28000'; end if;
 select x.* into r from public.troit_party_rooms x join public.troit_party_members m on m.room_id=x.id
 where m.user_id=me and public.troit_party_member_allowed(x.id,me) order by m.joined_at desc limit 1;
 if not found then return null; end if;
 select * into p from public.troit_connect_playback where user_id=r.host_id;
 select * into q from public.troit_ecosystem_queues where user_id=r.host_id;
 if p.device_id=q.device_id and p.instance_id=q.instance_id and q.queue->p.queue_index->>'url'=p.track_url then
  queue:=(to_jsonb(q)-'user_id')||jsonb_build_object('queue',case when p_known_revision=q.revision then null else q.queue end,
   'unchanged',p_known_revision=q.revision,'index',p.queue_index,'position_ms',p.position_ms,'playing',p.playing,
   'playback_revision',p.revision,'updated_at',p.updated_at,'fresh',clock_timestamp()-p.updated_at<=interval '45 seconds',
   'device_name',coalesce((select name from public.troit_ecosystem_devices where user_id=r.host_id and device_id=p.device_id),p.device_name));
 end if;
 return jsonb_build_object('id',r.id,'code',r.code,'name',r.name,'host_id',r.host_id,
  'host_name',coalesce((select display_name from public.troit_profiles where user_id=r.host_id),'Troit host'),
  'is_host',r.host_id=me,'expires_at',r.expires_at,'queue',queue,
  'members',coalesce((select jsonb_agg(jsonb_build_object('id',m.user_id,'name',coalesce(profile.display_name,'Troit friend'),'host',m.user_id=r.host_id)
    order by m.joined_at) from public.troit_party_members m left join public.troit_profiles profile on profile.user_id=m.user_id
    where m.room_id=r.id and public.troit_party_member_allowed(r.id,m.user_id)),'[]'::jsonb));
end $$;

create or replace function public.troit_party_create(p_name text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();r public.troit_party_rooms%rowtype;
begin
 if me is null then raise exception 'Sign in to host a listening room.' using errcode='28000'; end if;
 if p_name is null or char_length(btrim(p_name)) not between 1 and 64 or p_name ~ '[[:cntrl:]]' then raise exception 'Choose a room name between 1 and 64 characters.'; end if;
 perform public.troit_social_rate('party-create',10,3600,0);
 perform pg_advisory_xact_lock(hashtextextended('party-user:'||me::text,0));
 update public.troit_party_rooms set active=false where host_id=me and expires_at<=clock_timestamp();
 if exists(select 1 from public.troit_party_rooms existing join public.troit_party_members m on m.room_id=existing.id
   where m.user_id=me and public.troit_party_member_allowed(existing.id,me)) then raise exception 'Leave your current room before creating another.'; end if;
 delete from public.troit_party_rooms where expires_at<clock_timestamp()-interval '7 days';
 insert into public.troit_party_rooms(host_id,code,name) values(me,upper(substr(md5(gen_random_uuid()::text),1,8)),btrim(p_name)) returning * into r;
 insert into public.troit_party_members(room_id,user_id,status) values(r.id,me,'active');
 return public.troit_party_state();
end $$;

create or replace function public.troit_party_inspect(p_code text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();r public.troit_party_rooms%rowtype;
begin
 if me is null then raise exception 'Sign in to join friends.' using errcode='28000'; end if;
 perform public.troit_social_rate('party-inspect',10,60,0);
 if p_code is null or upper(p_code) !~ '^[0-9A-F]{8}$' then return null; end if;
 select * into r from public.troit_party_rooms where code=upper(p_code) and active and expires_at>clock_timestamp();
 if not found or not public.troit_party_friend(r.host_id,me) or exists(select 1 from public.troit_party_members where room_id=r.id and user_id=me and status='removed') then return null; end if;
 return jsonb_build_object('id',r.id,'name',r.name,'code',r.code,'host_name',coalesce((select display_name from public.troit_profiles where user_id=r.host_id),'Troit host'));
end $$;
create or replace function public.troit_party_join(p_room_id uuid,p_code text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();r public.troit_party_rooms%rowtype;
begin
 if me is null then raise exception 'Sign in to join friends.' using errcode='28000'; end if;
 perform public.troit_social_rate('party-join',10,60,0);
 perform pg_advisory_xact_lock(hashtextextended('party-user:'||me::text,0));
 select * into r from public.troit_party_rooms where id=p_room_id for update;
 if not found or not r.active or r.expires_at<=clock_timestamp() or r.code is distinct from upper(p_code) or not public.troit_party_friend(r.host_id,me)
  or exists(select 1 from public.troit_party_members where room_id=r.id and user_id=me and status='removed') then raise exception 'This room is unavailable.'; end if;
 if exists(select 1 from public.troit_party_rooms x join public.troit_party_members m on m.room_id=x.id where x.id<>r.id and m.user_id=me and public.troit_party_member_allowed(x.id,me)) then raise exception 'Leave your current room first.'; end if;
 if not exists(select 1 from public.troit_party_members where room_id=r.id and user_id=me and status='active') and
  (select count(*) from public.troit_party_members where room_id=r.id and status='active')>=20 then raise exception 'This room is full.'; end if;
 insert into public.troit_party_members(room_id,user_id,status) values(r.id,me,'active') on conflict(room_id,user_id) do update set status='active',joined_at=clock_timestamp();
 return public.troit_party_state();
end $$;

create or replace function public.troit_party_leave(p_room_id uuid,p_remove_user uuid default null)
returns void language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();r public.troit_party_rooms%rowtype;peer uuid;
begin
 if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
 perform public.troit_social_rate('party-leave',30,60,0);
 select * into r from public.troit_party_rooms where id=p_room_id for update;
 if not found then return; end if;
 if p_remove_user is not null and r.host_id<>me then raise exception 'Only the host can remove a participant.' using errcode='42501'; end if;
 if r.host_id<>me and not exists(select 1 from public.troit_party_members where room_id=r.id and user_id=me and status='active') then return; end if;
 peer:=coalesce(p_remove_user,me);
 if r.host_id=me and p_remove_user is null then update public.troit_party_rooms set active=false where id=r.id;
 else update public.troit_party_members set status=case when p_remove_user is null then 'left' else 'removed' end where room_id=r.id and user_id=peer and user_id<>r.host_id; end if;
 perform pg_advisory_xact_lock(hashtextextended('connect-active:'||r.host_id::text,0));
 update public.troit_ecosystem_commands c set status='stale',payload='{}'::jsonb,consumed_at=clock_timestamp()
 where c.user_id=r.host_id and c.status in ('pending','claimed') and exists(select 1 from public.troit_party_additions a
  where a.host_id=c.user_id and a.id=c.id and a.room_id=r.id and (not r.active or (r.host_id=me and p_remove_user is null) or a.guest_id=peer));
end $$;

create or replace function public.troit_party_add(p_command_id uuid,p_room_id uuid,p_expected_revision bigint,p_expected_playback_revision bigint,
 p_songs jsonb,p_observed_at timestamptz,p_play_next boolean default false)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();r public.troit_party_rooms%rowtype;p public.troit_connect_playback%rowtype;q public.troit_ecosystem_queues%rowtype;
 d public.troit_ecosystem_devices%rowtype;a public.troit_party_additions%rowtype;c public.troit_ecosystem_commands%rowtype;candidate jsonb;fingerprint text;
begin
 if me is null then raise exception 'Sign in to add songs.' using errcode='28000'; end if;
 if p_command_id is null or p_play_next is null then raise exception 'Invalid song request.'; end if;
 fingerprint:=md5(jsonb_build_array(p_room_id,p_expected_revision,p_expected_playback_revision,p_songs,p_play_next)::text);
 perform pg_advisory_xact_lock(hashtextextended('party-add:'||me::text||':'||p_command_id::text,0));
 select * into a from public.troit_party_additions where guest_id=me and id=p_command_id;
 if found then
  if a.fingerprint<>fingerprint then raise exception 'This song request identifier was already used.'; end if;
  select * into c from public.troit_ecosystem_commands where user_id=a.host_id and id=p_command_id;
  return jsonb_build_object('id',p_command_id,'status',case when c.id is null or c.expires_at<clock_timestamp() and c.status='pending' then 'expired' else c.status end);
 end if;
 select * into r from public.troit_party_rooms where id=p_room_id for share;
 if not found or not public.troit_party_member_allowed(r.id,me) then raise exception 'This listening room is unavailable.' using errcode='42501'; end if;
 if p_play_next and r.host_id<>me then raise exception 'Guests can add songs at the end of the queue.' using errcode='42501'; end if;
 perform public.troit_social_rate('party-add',20,60,0);
 perform pg_advisory_xact_lock(hashtextextended('connect-active:'||r.host_id::text,0));
 select * into p from public.troit_connect_playback where user_id=r.host_id for share;
 select * into q from public.troit_ecosystem_queues where user_id=r.host_id for share;
 select * into d from public.troit_ecosystem_devices where user_id=r.host_id and device_id=p.device_id and instance_id=p.instance_id;
 if p.user_id is null or q.user_id is null or d.user_id is null or q.device_id<>p.device_id or q.instance_id<>p.instance_id
  or q.revision is distinct from p_expected_revision or p.revision is distinct from p_expected_playback_revision
  or q.queue->p.queue_index->>'url' is distinct from p.track_url or clock_timestamp()-p.updated_at>interval '45 seconds'
  or clock_timestamp()-d.updated_at>interval '45 seconds' or p_observed_at is null or clock_timestamp()-p_observed_at>interval '45 seconds'
  or p_observed_at>clock_timestamp()+interval '5 seconds' then raise exception 'The host playback changed or is offline. Refresh the room.' using errcode='40001'; end if;
 if exists(select 1 from public.troit_ecosystem_commands where user_id=r.host_id and status in ('pending','claimed') and expires_at>clock_timestamp()) then raise exception 'Wait for the previous queue change to finish.' using errcode='40001'; end if;
 if (select count(*) from public.troit_party_additions where room_id=r.id and created_at>clock_timestamp()-interval '1 minute')>=60 then raise exception 'The room has too many song requests. Wait a moment.'; end if;
 perform public.troit_ecosystem_validate_queue(p_songs);
 if jsonb_array_length(p_songs)>20 then raise exception 'Add at most twenty songs at a time.'; end if;
 if p_play_next then
  select coalesce(jsonb_agg(value order by ordinality),'[]'::jsonb) into candidate from jsonb_array_elements(q.queue) with ordinality where ordinality<=p.queue_index+1;
  candidate:=candidate||p_songs||coalesce((select jsonb_agg(value order by ordinality) from jsonb_array_elements(q.queue) with ordinality where ordinality>p.queue_index+1),'[]'::jsonb);
 else candidate:=q.queue||p_songs; end if;
 perform public.troit_ecosystem_validate_queue(candidate);
 if exists(select 1 from public.troit_ecosystem_commands where user_id=r.host_id and id=p_command_id) then raise exception 'This song request identifier was already used.'; end if;
 delete from public.troit_party_additions where created_at<clock_timestamp()-interval '1 day';
 insert into public.troit_ecosystem_commands(user_id,id,device_id,instance_id,kind,request,payload,source_device_id,source_instance_id,
 source_playback_revision,source_intent_revision,expected_revision,expected_intent_revision)
 values(r.host_id,p_command_id,p.device_id,p.instance_id,'edit',jsonb_build_object('party',r.id,'guest',me,'fingerprint',fingerprint),
 jsonb_build_object('queue',candidate,'expected_queue',q.queue,'index',p.queue_index,'position_ms',p.position_ms,'playing',p.playing,'mode',q.mode,
 'expected_url',p.track_url,'expected_index',p.queue_index,'party_id',r.id),p.device_id,p.instance_id,p.revision,d.intent_revision,q.revision,d.intent_revision);
 insert into public.troit_party_additions(guest_id,id,room_id,host_id,fingerprint) values(me,p_command_id,r.id,r.host_id,fingerprint);
 return jsonb_build_object('id',p_command_id,'status','pending');
end $$;
create or replace function public.troit_party_status(p_command_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare a public.troit_party_additions%rowtype;c public.troit_ecosystem_commands%rowtype;
begin
 if auth.uid() is null then raise exception 'Sign in first.' using errcode='28000'; end if;
 select * into a from public.troit_party_additions where guest_id=auth.uid() and id=p_command_id;
 if not found then return null; end if;
 select * into c from public.troit_ecosystem_commands where user_id=a.host_id and id=a.id;
 return jsonb_build_object('id',a.id,'status',case when c.id is null or c.expires_at<clock_timestamp() and c.status='pending' then 'expired' else c.status end);
end $$;
create or replace function public.troit_party_validate_command(p_command_id uuid)
returns boolean language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is null then return false; end if;
 return exists(select 1 from public.troit_party_additions a join public.troit_ecosystem_commands c on c.user_id=a.host_id and c.id=a.id
  where c.user_id=auth.uid() and c.id=p_command_id and c.status='claimed' and c.expires_at>clock_timestamp()
  and public.troit_party_member_allowed(a.room_id,a.guest_id));
end $$;

-- Preserve the existing receiver protocol while revoking queued guest requests before claim.
do $$ begin
 if to_regprocedure('public.troit_ecosystem_claim_base(uuid,uuid)') is null then
  alter function public.troit_ecosystem_claim(uuid,uuid) rename to troit_ecosystem_claim_base;
 end if;
end $$;
revoke all on function public.troit_ecosystem_claim_base(uuid,uuid) from public,anon,authenticated;
create or replace function public.troit_ecosystem_claim(p_device_id uuid,p_instance_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();
begin
 if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
 perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
 update public.troit_ecosystem_commands c set status='stale',payload='{}'::jsonb,consumed_at=clock_timestamp()
 where c.user_id=me and c.status='pending' and exists(select 1 from public.troit_party_additions a
  where a.host_id=c.user_id and a.id=c.id and not public.troit_party_member_allowed(a.room_id,a.guest_id));
 return public.troit_ecosystem_claim_base(p_device_id,p_instance_id);
end $$;

create or replace function public.troit_sleep_state()
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();t public.troit_sleep_timers%rowtype;p public.troit_connect_playback%rowtype;
begin
 if me is null then raise exception 'Sign in to share a sleep timer.' using errcode='28000'; end if;
 select * into t from public.troit_sleep_timers where user_id=me;
 select * into p from public.troit_connect_playback where user_id=me;
 return jsonb_build_object('revision',coalesce(t.revision,0),'server_time',clock_timestamp(),
  'timer',case when t.status='active' then (to_jsonb(t)-'user_id')||jsonb_build_object('device_id',p.device_id,'instance_id',p.instance_id,'device_name',coalesce(
   (select name from public.troit_ecosystem_devices where user_id=me and device_id=p.device_id),p.device_name,'Current player')) else null end);
end $$;
create or replace function public.troit_sleep_set(p_request_id uuid,p_expected_revision bigint,p_kind text,p_minutes integer default 0)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();t public.troit_sleep_timers%rowtype;p public.troit_connect_playback%rowtype;prior public.troit_sleep_requests%rowtype;fingerprint text;
begin
 if me is null then raise exception 'Sign in to share a sleep timer.' using errcode='28000'; end if;
 if p_request_id is null or p_kind is null or p_kind not in ('minutes','end_of_track') or p_minutes is null
  or (p_kind='minutes' and p_minutes not in (15,30,60)) or (p_kind='end_of_track' and p_minutes<>0) then raise exception 'Choose 15, 30, 60 minutes or end of song.'; end if;
 fingerprint:=md5(jsonb_build_array('set',p_expected_revision,p_kind,p_minutes)::text);
 perform pg_advisory_xact_lock(hashtextextended('sleep:'||me::text,0));
 select * into prior from public.troit_sleep_requests where user_id=me and id=p_request_id;
 if found then
  if prior.fingerprint<>fingerprint then raise exception 'This timer request identifier was already used.'; end if;
  return public.troit_sleep_state();
 end if;
 select * into t from public.troit_sleep_timers where user_id=me for update;
 if coalesce(t.revision,0) is distinct from p_expected_revision then raise exception 'The sleep timer changed. Refresh before changing it.' using errcode='40001'; end if;
 select * into p from public.troit_connect_playback where user_id=me;
 if not found or clock_timestamp()-p.updated_at>interval '45 seconds' then raise exception 'Start playback on an online device first.'; end if;
 perform public.troit_connect_rate('sleep-set',20,60,0);
 insert into public.troit_sleep_timers(user_id,id,revision,kind,deadline,track_url,queue_index,status)
 values(me,p_request_id,coalesce(t.revision,0)+1,p_kind,case when p_kind='minutes' then clock_timestamp()+p_minutes*interval '1 minute' else null end,p.track_url,p.queue_index,'active')
 on conflict(user_id) do update set id=excluded.id,revision=excluded.revision,kind=excluded.kind,deadline=excluded.deadline,
 track_url=excluded.track_url,queue_index=excluded.queue_index,status='active',updated_at=clock_timestamp();
 insert into public.troit_sleep_requests values(me,p_request_id,fingerprint,clock_timestamp());
 delete from public.troit_sleep_requests where user_id=me and created_at<clock_timestamp()-interval '1 day';
 return public.troit_sleep_state();
end $$;
create or replace function public.troit_sleep_cancel(p_timer_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is null then raise exception 'Sign in first.' using errcode='28000'; end if;
 perform pg_advisory_xact_lock(hashtextextended('sleep:'||auth.uid()::text,0));
 update public.troit_sleep_timers set status='cancelled',revision=revision+1,updated_at=clock_timestamp()
 where user_id=auth.uid() and id=p_timer_id and status='active';
 return public.troit_sleep_state();
end $$;
create or replace function public.troit_sleep_claim(p_timer_id uuid,p_device_id uuid,p_instance_id uuid,p_completed boolean default false)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();t public.troit_sleep_timers%rowtype;p public.troit_connect_playback%rowtype;
begin
 if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
 perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
 perform pg_advisory_xact_lock(hashtextextended('sleep:'||me::text,0));
 select * into t from public.troit_sleep_timers where user_id=me and id=p_timer_id and status='active' for update;
 if not found then return null; end if;
 select * into p from public.troit_connect_playback where user_id=me for share;
 if not found or p.device_id is distinct from p_device_id or p.instance_id is distinct from p_instance_id
  or clock_timestamp()-p.updated_at>interval '45 seconds' then return null; end if;
 if t.kind='end_of_track' and (t.track_url<>p.track_url or t.queue_index<>p.queue_index) then
  update public.troit_sleep_timers set status='cancelled',revision=revision+1,updated_at=clock_timestamp() where user_id=me; return null;
 end if;
 if (t.kind='minutes' and clock_timestamp()<t.deadline) or (t.kind='end_of_track' and not coalesce(p_completed,false)) then return null; end if;
 update public.troit_sleep_timers set status='fired',revision=revision+1,updated_at=clock_timestamp() where user_id=me;
 return jsonb_build_object('id',t.id,'track_url',p.track_url,'queue_index',p.queue_index,'device_id',p.device_id,'instance_id',p.instance_id);
end $$;

-- A timer that elapsed while a destination was loading must not be bypassed by activation.
do $$ begin
 if to_regprocedure('public.troit_ecosystem_activate_base(uuid,uuid,uuid)') is null then
  alter function public.troit_ecosystem_activate(uuid,uuid,uuid) rename to troit_ecosystem_activate_base;
 end if;
end $$;
revoke all on function public.troit_ecosystem_activate_base(uuid,uuid,uuid) from public,anon,authenticated;
create or replace function public.troit_ecosystem_activate(p_command_id uuid,p_device_id uuid,p_instance_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();command public.troit_ecosystem_commands%rowtype;t public.troit_sleep_timers%rowtype;
begin
 if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
 perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
 select * into command from public.troit_ecosystem_commands where user_id=me and id=p_command_id and device_id=p_device_id and instance_id=p_instance_id for update;
 if not found then return null; end if;
 select * into t from public.troit_sleep_timers where user_id=me;
 if command.activated_at is null and ((t.status='active' and t.kind='minutes' and t.deadline>command.created_at and t.deadline<=clock_timestamp())
   or (t.status='fired' and t.updated_at>=command.created_at)) then
  update public.troit_ecosystem_commands set status='stale',payload='{}'::jsonb,consumed_at=clock_timestamp() where user_id=me and id=p_command_id;
  return null;
 end if;
 return public.troit_ecosystem_activate_base(p_command_id,p_device_id,p_instance_id);
end $$;

revoke all on function public.troit_party_state(bigint),public.troit_party_create(text),public.troit_party_inspect(text),public.troit_party_join(uuid,text),
 public.troit_party_leave(uuid,uuid),public.troit_party_add(uuid,uuid,bigint,bigint,jsonb,timestamptz,boolean),public.troit_party_status(uuid),
 public.troit_party_validate_command(uuid),public.troit_ecosystem_claim(uuid,uuid),public.troit_sleep_state(),
 public.troit_sleep_set(uuid,bigint,text,integer),public.troit_sleep_cancel(uuid),public.troit_sleep_claim(uuid,uuid,uuid,boolean),
 public.troit_ecosystem_activate(uuid,uuid,uuid) from public,anon;
grant execute on function public.troit_party_state(bigint),public.troit_party_create(text),public.troit_party_inspect(text),public.troit_party_join(uuid,text),
 public.troit_party_leave(uuid,uuid),public.troit_party_add(uuid,uuid,bigint,bigint,jsonb,timestamptz,boolean),public.troit_party_status(uuid),
 public.troit_party_validate_command(uuid),public.troit_ecosystem_claim(uuid,uuid),public.troit_sleep_state(),
 public.troit_sleep_set(uuid,bigint,text,integer),public.troit_sleep_cancel(uuid),public.troit_sleep_claim(uuid,uuid,uuid,boolean),
 public.troit_ecosystem_activate(uuid,uuid,uuid) to authenticated;
commit;

