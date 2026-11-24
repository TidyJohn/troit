-- What accepted friends are playing right now. Additive; apply after the social and Connect migrations.
-- Reads the playback row each device already publishes, so nothing new is stored except the opt-out.
begin;

alter table public.troit_profiles add column if not exists share_activity boolean not null default true;

create or replace function public.troit_friend_activity_share(p_enabled boolean)
returns boolean language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid();
begin
  perform public.troit_social_rate('activity_share',20,60,250);
  if p_enabled is null then raise exception 'Choose whether to share your listening activity.'; end if;
  update public.troit_profiles set share_activity=p_enabled where user_id=me;
  if not found then raise exception 'Create your profile first.'; end if;
  return p_enabled;
end $$;

-- Only accepted, unblocked friends who share, and only while their device reports live playback.
-- Device names, queue positions and paused or stale sessions are never returned.
create or replace function public.troit_friend_activity()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  return jsonb_build_object(
    'sharing',coalesce((select share_activity from public.troit_profiles where user_id=me),true),
    'items',coalesce((select jsonb_agg(row_data) from (
      select jsonb_build_object('user_id',p.user_id,'track_url',c.track_url,'title',c.title,'artist',c.artist,
        'artwork',c.artwork,'duration_seconds',c.duration_seconds) as row_data
      from public.troit_friendships f
      join public.troit_profiles p on p.user_id=case when f.user_low=me then f.user_high else f.user_low end
      join public.troit_connect_playback c on c.user_id=p.user_id
      where (f.user_low=me or f.user_high=me) and f.status='accepted' and p.share_activity
        and c.playing and clock_timestamp()-c.updated_at <= interval '45 seconds'
        and not exists(select 1 from public.troit_blocks b
          where (b.blocker_id=me and b.blocked_id=p.user_id) or (b.blocker_id=p.user_id and b.blocked_id=me))
      limit 400
    ) bounded),'[]'::jsonb));
end $$;

revoke all on function public.troit_friend_activity_share(boolean), public.troit_friend_activity() from public, anon;
grant execute on function public.troit_friend_activity_share(boolean), public.troit_friend_activity() to authenticated;

commit;
