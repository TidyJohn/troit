-- Collaborative playlists are separate from personal, last-write-wins library rows.
-- Apply after tunes_sync and troit_social. All public access is through authenticated RPCs.
begin;

create table if not exists public.troit_collab_playlists (
  id uuid primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(btrim(title)) between 1 and 100),
  songs jsonb not null default '[]'::jsonb check (jsonb_typeof(songs)='array' and jsonb_array_length(songs)<=1000),
  revision bigint not null default 1 check (revision>0),
  source_id uuid,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  unique(owner_id,source_id)
);
create table if not exists public.troit_collab_members (
  playlist_id uuid not null references public.troit_collab_playlists(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  status text not null check (status in ('pending','accepted')),
  primary key(playlist_id,user_id)
);
create index if not exists troit_collab_members_user on public.troit_collab_members(user_id,status,playlist_id);
create index if not exists troit_collab_owner_active on public.troit_collab_playlists(owner_id,deleted);
alter table public.troit_collab_playlists enable row level security;
alter table public.troit_collab_members enable row level security;
revoke all on public.troit_collab_playlists,public.troit_collab_members from public,anon,authenticated;

create or replace function public.troit_collab_friends(p_owner uuid,p_peer uuid)
returns boolean language sql stable security definer set search_path='' as $$
  select p_owner=p_peer or (
    exists(select 1 from public.troit_friendships f where f.user_low=least(p_owner,p_peer)
      and f.user_high=greatest(p_owner,p_peer) and f.status='accepted')
    and not exists(select 1 from public.troit_blocks b where
      (b.blocker_id=p_owner and b.blocked_id=p_peer) or (b.blocker_id=p_peer and b.blocked_id=p_owner))
  )
$$;

create or replace function public.troit_collab_access(p_playlist_id uuid,p_owner_only boolean default false,p_pending boolean default false)
returns public.troit_collab_playlists language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); r public.troit_collab_playlists%rowtype;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  select * into r from public.troit_collab_playlists where id=p_playlist_id and not deleted;
  if not found then raise exception 'This collaborative playlist is unavailable.' using errcode='42501'; end if;
  if r.owner_id=me then return r; end if;
  if p_owner_only or not public.troit_collab_friends(r.owner_id,me) or exists(
    select 1 from public.troit_collab_members other join public.troit_blocks b on
      ((b.blocker_id=me and b.blocked_id=other.user_id) or (b.blocker_id=other.user_id and b.blocked_id=me))
    where other.playlist_id=r.id and other.status='accepted' and other.user_id<>me
  ) or not exists(
    select 1 from public.troit_collab_members m where m.playlist_id=r.id and m.user_id=me
      and (m.status='accepted' or p_pending)) then
    raise exception 'You no longer have access to this collaborative playlist.' using errcode='42501';
  end if;
  return r;
end $$;

create or replace function public.troit_collab_validate_songs(p_songs jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare item jsonb; result jsonb; duration numeric;
begin
  if p_songs is null or jsonb_typeof(p_songs)<>'array' or jsonb_array_length(p_songs)>1000
    or octet_length(p_songs::text)>2000000 then raise exception 'Use at most 1,000 songs in a collaborative playlist.'; end if;
  for item in select value from jsonb_array_elements(p_songs) loop
    if jsonb_typeof(item)<>'object' or jsonb_typeof(item->'title') is distinct from 'string'
      or char_length(btrim(item->>'title')) not between 1 and 500
      or jsonb_typeof(item->'artist') is distinct from 'string' or char_length(item->>'artist')>500
      or jsonb_typeof(item->'url') is distinct from 'string' or char_length(item->>'url')>2048
      or (item->>'url') !~ '^https://(music\.)?(www\.)?youtube\.com/watch\?v=[A-Za-z0-9_-]{11}([&].*)?$'
      or (item ? 'artwork' and jsonb_typeof(item->'artwork') not in ('null','string'))
      or (jsonb_typeof(item->'artwork')='string' and ((item->>'artwork') !~ '^https://' or char_length(item->>'artwork')>4096))
      or jsonb_typeof(item->'duration') is distinct from 'number' then
      raise exception 'A song contains invalid metadata or an unsupported music URL.';
    end if;
    duration := (item->>'duration')::numeric;
    if duration<0 or duration>604800 or trunc(duration)<>duration then raise exception 'Invalid song duration.'; end if;
  end loop;
  -- Keep the first occurrence and only recognized fields, preventing accidental metadata blobs.
  select coalesce(jsonb_agg(jsonb_build_object('title',value->>'title','artist',value->>'artist',
    'url',value->>'url','artwork',coalesce(value->'artwork','null'::jsonb),'duration',(value->>'duration')::bigint) order by ordinal),'[]'::jsonb)
    into result from (
      select distinct on (value->>'url') value,ordinal from jsonb_array_elements(p_songs) with ordinality x(value,ordinal)
      order by value->>'url',ordinal
    ) firsts;
  return result;
end $$;

create or replace function public.troit_collab_summary(p_playlist_id uuid,p_viewer uuid)
returns jsonb language sql stable security definer set search_path='' as $$
  select jsonb_build_object('id',p.id,'title',p.title,'owner_id',p.owner_id,'revision',p.revision,
    'song_count',jsonb_array_length(p.songs),'artwork',p.songs->0->'artwork','source_id',p.source_id,
    'member_count',(select count(*) from public.troit_collab_members m where m.playlist_id=p.id and m.status='accepted'
      and public.troit_collab_friends(p.owner_id,m.user_id)),
    'role',case when p.owner_id=p_viewer then 'owner' when exists(select 1 from public.troit_collab_members m
      where m.playlist_id=p.id and m.user_id=p_viewer and m.status='accepted') then 'editor' else 'invited' end)
  from public.troit_collab_playlists p where p.id=p_playlist_id
$$;

create or replace function public.troit_collab_get(p_playlist_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_collab_playlists%rowtype; result jsonb;
begin
  r:=public.troit_collab_access(p_playlist_id);
  select public.troit_collab_summary(r.id,auth.uid()) || jsonb_build_object('songs',r.songs,'members',
    coalesce((select jsonb_agg(jsonb_build_object('id',u.user_id,'handle',u.handle,'display_name',u.display_name,
      'status',m.status,'role',case when u.user_id=r.owner_id then 'owner' else 'editor' end)
      order by (u.user_id=r.owner_id) desc,u.handle)
      from public.troit_collab_members m join public.troit_profiles u on u.user_id=m.user_id where m.playlist_id=r.id
      and (auth.uid()=r.owner_id or public.troit_collab_friends(r.owner_id,m.user_id))),'[]'::jsonb)) into result;
  return result;
end $$;

create or replace function public.troit_collab_list()
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); result jsonb;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  select jsonb_build_object(
    'playlists',coalesce((select jsonb_agg(public.troit_collab_summary(p.id,me) order by p.updated_at desc,p.id)
      from public.troit_collab_playlists p join public.troit_collab_members m on m.playlist_id=p.id
      where not p.deleted and m.user_id=me and m.status='accepted' and public.troit_collab_friends(p.owner_id,me)
        and (p.owner_id=me or not exists(select 1 from public.troit_collab_members other join public.troit_blocks b on
          ((b.blocker_id=me and b.blocked_id=other.user_id) or (b.blocker_id=other.user_id and b.blocked_id=me))
          where other.playlist_id=p.id and other.status='accepted' and other.user_id<>me))),'[]'::jsonb),
    'invites',coalesce((select jsonb_agg(public.troit_collab_summary(p.id,me) order by p.updated_at desc,p.id)
      from public.troit_collab_playlists p join public.troit_collab_members m on m.playlist_id=p.id
      where not p.deleted and m.user_id=me and m.status='pending' and public.troit_collab_friends(p.owner_id,me)
        and not exists(select 1 from public.troit_collab_members other join public.troit_blocks b on
          ((b.blocker_id=me and b.blocked_id=other.user_id) or (b.blocker_id=other.user_id and b.blocked_id=me))
          where other.playlist_id=p.id and other.status='accepted' and other.user_id<>me)),'[]'::jsonb)
  ) into result;
  return result;
end $$;

create or replace function public.troit_collab_invite_locked(p_playlist_id uuid,p_peer_ids uuid[])
returns void language plpgsql security definer set search_path='' as $$
declare r public.troit_collab_playlists%rowtype; peer uuid; added integer:=0;
begin
  select * into strict r from public.troit_collab_playlists where id=p_playlist_id;
  if p_peer_ids is null or cardinality(p_peer_ids)>19 then raise exception 'Choose up to 19 friends.'; end if;
  -- Lock every affected account in a stable order, including caps across different playlists.
  for peer in select distinct x from unnest(p_peer_ids) x order by x loop
    if peer is null or peer=r.owner_id or not public.troit_collab_friends(r.owner_id,peer)
      or not exists(select 1 from public.troit_profiles where user_id=peer) then
      raise exception 'Invite only accepted friends who are available.' using errcode='42501';
    end if;
    if exists(select 1 from public.troit_blocks b where
      (b.blocker_id=peer and (b.blocked_id=any(p_peer_ids) or exists(select 1 from public.troit_collab_members m
        where m.playlist_id=r.id and m.user_id=b.blocked_id))) or
      (b.blocked_id=peer and (b.blocker_id=any(p_peer_ids) or exists(select 1 from public.troit_collab_members m
        where m.playlist_id=r.id and m.user_id=b.blocker_id)))) then
      raise exception 'An invited friend is unavailable for this group.' using errcode='42501';
    end if;
    perform pg_advisory_xact_lock(hashtextextended('collab-cap:'||peer::text,0));
    if not exists(select 1 from public.troit_collab_members where playlist_id=r.id and user_id=peer) then
      if (select count(*) from public.troit_collab_members m join public.troit_collab_playlists p on p.id=m.playlist_id
        where m.user_id=peer and not p.deleted)>=50 then raise exception 'This friend has reached the collaborative playlist limit.'; end if;
      added:=added+1;
    end if;
  end loop;
  if (select count(*) from public.troit_collab_members where playlist_id=r.id)+added>20 then
    raise exception 'A collaborative playlist can have at most 20 members, including invitations.';
  end if;
  insert into public.troit_collab_members(playlist_id,user_id,status)
    select r.id,x,'pending' from unnest(p_peer_ids) x group by x on conflict do nothing;
end $$;

create or replace function public.troit_collab_create(p_playlist_id uuid,p_title text,p_songs jsonb,p_peer_ids uuid[],p_source_id uuid default null)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); r public.troit_collab_playlists%rowtype; clean jsonb; title text:=btrim(p_title); stamp bigint;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  if p_playlist_id is null or title is null or char_length(title) not between 1 and 100 then raise exception 'Use a playlist name between 1 and 100 characters.'; end if;
  if not exists(select 1 from public.troit_profiles where user_id=me) then raise exception 'Create your Friends profile before collaborating.'; end if;
  perform pg_advisory_xact_lock(hashtextextended('collab-create:'||p_playlist_id::text,0));
  if p_source_id is not null then perform pg_advisory_xact_lock(hashtextextended('collab-source:'||me::text||':'||p_source_id::text,0)); end if;
  select * into r from public.troit_collab_playlists where id=p_playlist_id or
    (p_source_id is not null and owner_id=me and source_id=p_source_id) order by (id=p_playlist_id) desc limit 1 for update;
  if found then
    if r.owner_id<>me or r.deleted or r.source_id is distinct from p_source_id then
      raise exception 'This playlist identifier is unavailable.' using errcode='42501';
    end if;
    return public.troit_collab_get(r.id); -- Retrying never overwrites another member's edits.
  end if;
  clean:=public.troit_collab_validate_songs(p_songs);
  perform public.troit_social_rate('collab_create',20,60,0);
  perform pg_advisory_xact_lock(hashtextextended('collab-cap:'||me::text,0));
  if (select count(*) from public.troit_collab_members m join public.troit_collab_playlists p on p.id=m.playlist_id
    where m.user_id=me and not p.deleted)>=50 then raise exception 'You have reached the 50 collaborative playlist limit.'; end if;
  insert into public.troit_collab_playlists(id,owner_id,title,songs,source_id) values(p_playlist_id,me,title,clean,p_source_id);
  insert into public.troit_collab_members(playlist_id,user_id,status) values(p_playlist_id,me,'accepted');
  perform public.troit_collab_invite_locked(p_playlist_id,p_peer_ids);
  if p_source_id is not null then
    stamp:=floor(extract(epoch from clock_timestamp())*1000)::bigint;
    insert into public.tunes_playlists(user_id,id,title,songs,updated_at,deleted) values(me,p_source_id,title,'[]'::jsonb,stamp,true)
      on conflict(user_id,id) do update set songs='[]'::jsonb,deleted=true,
        updated_at=greatest(excluded.updated_at,public.tunes_playlists.updated_at+1);
  end if;
  return public.troit_collab_get(p_playlist_id);
end $$;

create or replace function public.troit_collab_add_songs(p_playlist_id uuid,p_songs jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_collab_playlists%rowtype; clean jsonb; merged jsonb;
begin
  perform 1 from public.troit_collab_playlists where id=p_playlist_id for update;
  r:=public.troit_collab_access(p_playlist_id);
  clean:=public.troit_collab_validate_songs(p_songs);
  -- Row lock + granular append prevents two editors from replacing each other's songs.
  select r.songs || coalesce(jsonb_agg(value order by ordinal),'[]'::jsonb) into merged
    from jsonb_array_elements(clean) with ordinality x(value,ordinal)
    where not exists(select 1 from jsonb_array_elements(r.songs) old(value) where old.value->>'url'=x.value->>'url');
  if jsonb_array_length(merged)>1000 then raise exception 'A collaborative playlist can contain at most 1,000 songs.'; end if;
  if merged<>r.songs then
    perform public.troit_social_rate('collab_write',120,60,0);
    update public.troit_collab_playlists set songs=merged,revision=revision+1,updated_at=clock_timestamp() where id=r.id;
  end if;
  return public.troit_collab_get(r.id);
end $$;

create or replace function public.troit_collab_remove_song(p_playlist_id uuid,p_song_url text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_collab_playlists%rowtype; changed jsonb;
begin
  perform 1 from public.troit_collab_playlists where id=p_playlist_id for update;
  r:=public.troit_collab_access(p_playlist_id);
  select coalesce(jsonb_agg(value order by ordinal),'[]'::jsonb) into changed
    from jsonb_array_elements(r.songs) with ordinality x(value,ordinal) where value->>'url' is distinct from p_song_url;
  if changed<>r.songs then
    perform public.troit_social_rate('collab_write',120,60,0);
    update public.troit_collab_playlists set songs=changed,revision=revision+1,updated_at=clock_timestamp() where id=r.id;
  end if;
  return public.troit_collab_get(r.id);
end $$;

create or replace function public.troit_collab_rename(p_playlist_id uuid,p_title text,p_expected_revision bigint)
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_collab_playlists%rowtype; normalized_title text:=btrim(p_title);
begin
  perform 1 from public.troit_collab_playlists where id=p_playlist_id for update;
  r:=public.troit_collab_access(p_playlist_id,true);
  if normalized_title is null or char_length(normalized_title) not between 1 and 100 then raise exception 'Use a playlist name between 1 and 100 characters.'; end if;
  if r.revision is distinct from p_expected_revision then raise exception 'This playlist changed. Refresh before renaming it.' using errcode='40001'; end if;
  if r.title<>normalized_title then
    perform public.troit_social_rate('collab_write',120,60,0);
    update public.troit_collab_playlists set title=normalized_title,revision=revision+1,updated_at=clock_timestamp() where id=r.id;
  end if;
  return public.troit_collab_get(r.id);
end $$;

create or replace function public.troit_collab_invite(p_playlist_id uuid,p_peer_ids uuid[])
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_collab_playlists%rowtype; previous_count integer;
begin
  perform 1 from public.troit_collab_playlists where id=p_playlist_id for update;
  r:=public.troit_collab_access(p_playlist_id,true);
  perform public.troit_social_rate('collab_write',120,60,0);
  select count(*) into previous_count from public.troit_collab_members where playlist_id=r.id;
  perform public.troit_collab_invite_locked(r.id,p_peer_ids);
  if (select count(*) from public.troit_collab_members where playlist_id=r.id)<>previous_count then
    update public.troit_collab_playlists set revision=revision+1,updated_at=clock_timestamp() where id=r.id;
  end if;
  return public.troit_collab_get(r.id);
end $$;

create or replace function public.troit_collab_respond(p_playlist_id uuid,p_accept boolean)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); r public.troit_collab_playlists%rowtype; membership text;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  perform 1 from public.troit_collab_playlists where id=p_playlist_id for update;
  r:=public.troit_collab_access(p_playlist_id,false,true);
  if r.owner_id=me then raise exception 'The owner is already a member.'; end if;
  select status into membership from public.troit_collab_members where playlist_id=r.id and user_id=me;
  if membership='accepted' and p_accept then return public.troit_collab_get(r.id); end if;
  if membership<>'pending' or p_accept is null then raise exception 'This invitation is no longer pending.'; end if;
  perform public.troit_social_rate('collab_write',120,60,0);
  if p_accept then update public.troit_collab_members set status='accepted' where playlist_id=r.id and user_id=me;
  else delete from public.troit_collab_members where playlist_id=r.id and user_id=me; end if;
  update public.troit_collab_playlists set revision=revision+1,updated_at=clock_timestamp() where id=r.id;
  if p_accept then return public.troit_collab_get(r.id); end if;
  return null;
end $$;

create or replace function public.troit_collab_member_action(p_playlist_id uuid,p_peer_id uuid,p_action text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); r public.troit_collab_playlists%rowtype; removed integer;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  select * into r from public.troit_collab_playlists where id=p_playlist_id and not deleted for update;
  if not found then raise exception 'This collaborative playlist is unavailable.' using errcode='42501'; end if;
  if p_peer_id is null or p_peer_id=r.owner_id or not (
    (p_action='remove' and me=r.owner_id) or (p_action='leave' and p_peer_id=me)) then
    raise exception 'Only the owner can remove members; editors can leave themselves.' using errcode='42501';
  end if;
  if p_action='leave' and not exists(select 1 from public.troit_collab_members where playlist_id=r.id and user_id=me) then
    raise exception 'You are not a member of this playlist.' using errcode='42501';
  end if;
  perform public.troit_social_rate('collab_write',120,60,0);
  delete from public.troit_collab_members where playlist_id=r.id and user_id=p_peer_id;
  get diagnostics removed=row_count;
  if removed>0 then update public.troit_collab_playlists set revision=revision+1,updated_at=clock_timestamp() where id=r.id; end if;
  if p_action='leave' then return null; end if;
  return public.troit_collab_get(r.id);
end $$;

create or replace function public.troit_collab_delete(p_playlist_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_collab_playlists%rowtype;
begin
  perform 1 from public.troit_collab_playlists where id=p_playlist_id for update;
  r:=public.troit_collab_access(p_playlist_id,true);
  perform public.troit_social_rate('collab_write',120,60,0);
  update public.troit_collab_playlists set deleted=true,songs='[]'::jsonb,revision=revision+1,updated_at=clock_timestamp() where id=r.id;
  return null;
end $$;

revoke all on function public.troit_collab_friends(uuid,uuid),public.troit_collab_access(uuid,boolean,boolean),
  public.troit_collab_validate_songs(jsonb),public.troit_collab_summary(uuid,uuid),public.troit_collab_invite_locked(uuid,uuid[]) from public,anon,authenticated;
revoke all on function public.troit_collab_list(),public.troit_collab_get(uuid),public.troit_collab_create(uuid,text,jsonb,uuid[],uuid),
  public.troit_collab_add_songs(uuid,jsonb),public.troit_collab_remove_song(uuid,text),public.troit_collab_rename(uuid,text,bigint),
  public.troit_collab_invite(uuid,uuid[]),public.troit_collab_respond(uuid,boolean),public.troit_collab_member_action(uuid,uuid,text),public.troit_collab_delete(uuid) from public,anon;
grant execute on function public.troit_collab_list(),public.troit_collab_get(uuid),public.troit_collab_create(uuid,text,jsonb,uuid[],uuid),
  public.troit_collab_add_songs(uuid,jsonb),public.troit_collab_remove_song(uuid,text),public.troit_collab_rename(uuid,text,bigint),
  public.troit_collab_invite(uuid,uuid[]),public.troit_collab_respond(uuid,boolean),public.troit_collab_member_action(uuid,uuid,text),public.troit_collab_delete(uuid) to authenticated;
commit;
