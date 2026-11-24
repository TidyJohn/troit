-- Resolve the destination's stream while the source is still audible. Old runtimes
-- retain the existing pause/transfer flow. A preload can never activate a device.
begin;

alter table public.troit_ecosystem_devices add column if not exists preload_instance_id uuid;
alter table public.troit_ecosystem_commands drop constraint if exists troit_ecosystem_commands_kind_check;
alter table public.troit_ecosystem_commands add constraint troit_ecosystem_commands_kind_check
  check (kind in ('edit','transfer','preload'));

create or replace function public.troit_ecosystem_heartbeat(
  p_device_id uuid,p_instance_id uuid,p_name text,p_kind text,p_intent_revision bigint,p_supports_preload boolean)
returns jsonb language plpgsql security definer set search_path='' as $$
declare response jsonb;
begin
  response := public.troit_ecosystem_heartbeat(p_device_id,p_instance_id,p_name,p_kind,p_intent_revision);
  update public.troit_ecosystem_devices set preload_instance_id=case when p_supports_preload then p_instance_id end
    where user_id=auth.uid() and device_id=p_device_id and instance_id=p_instance_id
      and preload_instance_id is distinct from (case when p_supports_preload then p_instance_id end);
  return response;
end $$;

create or replace function public.troit_ecosystem_preload(
  p_command_id uuid,p_device_id uuid,p_instance_id uuid,p_expected_revision bigint,p_target_device_id uuid,p_target_instance_id uuid,
  p_observed_at timestamptz,p_expected_target_intent_revision bigint)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); q public.troit_ecosystem_queues%rowtype; active public.troit_connect_playback%rowtype;
  source public.troit_ecosystem_devices%rowtype; target public.troit_ecosystem_devices%rowtype;
begin
  if me is null then raise exception 'Sign in to switch devices.' using errcode='28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended('connect-active:'||me::text,0));
  select * into active from public.troit_connect_playback where user_id=me and device_id=p_device_id and instance_id=p_instance_id;
  select * into q from public.troit_ecosystem_queues where user_id=me and device_id=p_device_id and instance_id=p_instance_id and revision=p_expected_revision;
  select * into source from public.troit_ecosystem_devices where user_id=me and device_id=p_device_id and instance_id=p_instance_id;
  select * into target from public.troit_ecosystem_devices where user_id=me and device_id=p_target_device_id and instance_id=p_target_instance_id
    and preload_instance_id=p_target_instance_id and intent_revision=p_expected_target_intent_revision;
  if active.user_id is null or q.user_id is null or source.user_id is null or target.user_id is null or p_device_id=p_target_device_id
    or q.queue->active.queue_index->>'url' is distinct from active.track_url
    or active.updated_at < clock_timestamp()-interval '45 seconds' or target.updated_at < clock_timestamp()-interval '45 seconds'
    or p_observed_at is null or p_observed_at not between clock_timestamp()-interval '45 seconds' and clock_timestamp()+interval '5 seconds' then
    raise exception 'Refresh playback before switching devices.' using errcode='40001';
  end if;
  perform public.troit_connect_rate('eco-command',60,60,0);
  delete from public.troit_ecosystem_commands where user_id=me and expires_at<clock_timestamp()-interval '5 minutes';
  insert into public.troit_ecosystem_commands(user_id,id,device_id,instance_id,kind,request,payload,source_device_id,source_instance_id,
    source_playback_revision,source_intent_revision,expected_revision,expected_intent_revision)
  values(me,p_command_id,p_target_device_id,p_target_instance_id,'preload','{}'::jsonb,jsonb_build_object('queue',q.queue,
    'index',active.queue_index,'position_ms',active.position_ms,'playing',active.playing,'mode',q.mode,
    'expected_url',active.track_url,'expected_index',active.queue_index),p_device_id,p_instance_id,active.revision,
    source.intent_revision,q.revision,target.intent_revision);
  return jsonb_build_object('id',p_command_id,'status','pending');
end $$;

revoke all on function public.troit_ecosystem_heartbeat(uuid,uuid,text,text,bigint,boolean),
  public.troit_ecosystem_preload(uuid,uuid,uuid,bigint,uuid,uuid,timestamptz,bigint) from public,anon;
grant execute on function public.troit_ecosystem_heartbeat(uuid,uuid,text,text,bigint,boolean),
  public.troit_ecosystem_preload(uuid,uuid,uuid,bigint,uuid,uuid,timestamptz,bigint) to authenticated;
commit;
