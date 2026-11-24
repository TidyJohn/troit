-- Account likes and actual-play history. Apply after the existing Connect migration.
begin;
create sequence if not exists public.troit_listening_revision;
create table if not exists public.troit_listening_likes (
  user_id uuid not null references auth.users(id) on delete cascade,
  track_key text not null,
  song jsonb not null,
  liked boolean not null,
  revision bigint not null,
  updated_at timestamptz not null default clock_timestamp(),
  primary key(user_id,track_key)
);
create index if not exists troit_listening_likes_cursor on public.troit_listening_likes(user_id,revision);
create table if not exists public.troit_listening_like_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  request jsonb not null,
  receipt jsonb not null,
  created_at timestamptz not null default clock_timestamp(),
  primary key(user_id,id)
);
create table if not exists public.troit_listening_history (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  seq bigint not null,
  song jsonb not null,
  played_at bigint not null,
  position_ms bigint not null,
  device_name text not null,
  primary key(user_id,id)
);
create index if not exists troit_listening_history_cursor on public.troit_listening_history(user_id,seq);
create table if not exists public.troit_listening_history_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  track_url text not null,
  played_at bigint not null,
  created_at timestamptz not null default clock_timestamp(),
  primary key(user_id,id)
);
alter table public.troit_listening_likes enable row level security;
alter table public.troit_listening_like_receipts enable row level security;
alter table public.troit_listening_history enable row level security;
alter table public.troit_listening_history_receipts enable row level security;
revoke all on public.troit_listening_likes,public.troit_listening_like_receipts,public.troit_listening_history,public.troit_listening_history_receipts from anon,authenticated;
revoke all on sequence public.troit_listening_revision from public,anon,authenticated;

create or replace function public.troit_listening_validate_song(p_song jsonb)
returns void language plpgsql set search_path='' as $$
begin
  if p_song is null or jsonb_typeof(p_song)<>'object' or octet_length(p_song::text)>8192
    or jsonb_typeof(p_song->'url') is distinct from 'string' or char_length(p_song->>'url') not between 1 and 2048
    or p_song->>'url' not like 'https://%'
    or jsonb_typeof(p_song->'title') is distinct from 'string' or char_length(p_song->>'title') not between 1 and 500
    or jsonb_typeof(p_song->'artist') is distinct from 'string' or char_length(p_song->>'artist')>500
    or jsonb_typeof(p_song->'duration') is distinct from 'number' or (p_song->>'duration') !~ '^[0-9]{1,6}$'
    or (p_song->>'duration')::bigint not between 0 and 604800
    or (p_song ? 'artwork' and p_song->'artwork'<>'null'::jsonb and (jsonb_typeof(p_song->'artwork')<>'string'
      or char_length(p_song->>'artwork')>4096 or p_song->>'artwork' not like 'https://%')) then
    raise exception 'Invalid song metadata.';
  end if;
end $$;
revoke all on function public.troit_listening_validate_song(jsonb) from public,anon,authenticated;

create or replace function public.troit_listening_likes(p_after_seq bigint default 0,p_limit integer default 100)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); rows jsonb; cursor bigint;
begin
  if me is null then raise exception 'Sign in to sync liked songs.' using errcode='28000'; end if;
  if p_after_seq is null or p_after_seq<0 or p_limit is null or p_limit not between 1 and 100 then raise exception 'Invalid listening cursor.'; end if;
  select coalesce(jsonb_agg(to_jsonb(r)-'user_id'-'track_key' order by revision),'[]'::jsonb),coalesce(max(revision),p_after_seq)
    into rows,cursor from (select * from public.troit_listening_likes where user_id=me and revision>p_after_seq order by revision limit p_limit) r;
  return jsonb_build_object('items',rows,'cursor',cursor,'has_more',exists(select 1 from public.troit_listening_likes where user_id=me and revision>cursor));
end $$;

create or replace function public.troit_listening_set_like(p_operation_id uuid,p_song jsonb,p_liked boolean,p_expected_revision bigint)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); k text; old public.troit_listening_likes%rowtype; previous public.troit_listening_like_receipts%rowtype;
  requested jsonb; answer jsonb; row_json jsonb;
begin
  if me is null then raise exception 'Sign in to sync liked songs.' using errcode='28000'; end if;
  if p_operation_id is null or p_liked is null or p_expected_revision is null or p_expected_revision<0 then raise exception 'Invalid like request.'; end if;
  perform public.troit_listening_validate_song(p_song);
  k:=encode(sha256(convert_to(p_song->>'url','UTF8')),'hex');
  requested:=jsonb_build_object('song',p_song,'liked',p_liked,'expected_revision',p_expected_revision);
  -- One owner lock gives cursor revisions commit order, not merely sequence allocation order.
  perform pg_advisory_xact_lock(hashtextextended('listening-likes:'||me::text,0));
  select * into previous from public.troit_listening_like_receipts where user_id=me and id=p_operation_id;
  if found then
    if previous.request<>requested then raise exception 'This like request identifier was already used.'; end if;
    return previous.receipt;
  end if;
  perform public.troit_connect_rate('listening-like-minute',120,60,0);
  select * into old from public.troit_listening_likes where user_id=me and track_key=k for update;
  if coalesce(old.revision,0)<>p_expected_revision then
    row_json:=case when old.user_id is null then null else to_jsonb(old)-'user_id'-'track_key' end;
    answer:=jsonb_build_object('id',p_operation_id,'conflict',true,'row',row_json);
  else
    if old.user_id is null and (select count(*) from public.troit_listening_likes where user_id=me)>=5000 then raise exception 'This account has reached its liked-song history limit.'; end if;
    if p_liked and not coalesce(old.liked,false) and (select count(*) from public.troit_listening_likes where user_id=me and liked)>=1000 then raise exception 'Liked songs supports up to 1000 tracks.'; end if;
    if old.user_id is null or old.liked<>p_liked then
      insert into public.troit_listening_likes(user_id,track_key,song,liked,revision)
      values(me,k,p_song,p_liked,nextval('public.troit_listening_revision'))
      on conflict(user_id,track_key) do update set song=excluded.song,liked=excluded.liked,revision=excluded.revision,updated_at=clock_timestamp();
    end if;
    select to_jsonb(l)-'user_id'-'track_key' into row_json from public.troit_listening_likes l where user_id=me and track_key=k;
    answer:=jsonb_build_object('id',p_operation_id,'conflict',false,'row',row_json);
  end if;
  delete from public.troit_listening_like_receipts where user_id=me and (created_at<clock_timestamp()-interval '7 days'
    or id in (select id from public.troit_listening_like_receipts where user_id=me order by created_at desc,id offset 999));
  insert into public.troit_listening_like_receipts(user_id,id,request,receipt) values(me,p_operation_id,requested,answer);
  return answer;
end $$;

create or replace function public.troit_listening_history(p_after_seq bigint default 0,p_limit integer default 100)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); rows jsonb; cursor bigint;
begin
  if me is null then raise exception 'Sign in to sync listening history.' using errcode='28000'; end if;
  if p_after_seq is null or p_after_seq<0 or p_limit is null or p_limit not between 1 and 100 then raise exception 'Invalid listening cursor.'; end if;
  select coalesce(jsonb_agg(to_jsonb(r)-'user_id' order by seq),'[]'::jsonb),coalesce(max(seq),p_after_seq)
    into rows,cursor from (select * from public.troit_listening_history where user_id=me and seq>p_after_seq order by seq limit p_limit) r;
  return jsonb_build_object('items',rows,'cursor',cursor,'has_more',exists(select 1 from public.troit_listening_history where user_id=me and seq>cursor));
end $$;

create or replace function public.troit_listening_record(p_event_id uuid,p_song jsonb,p_played_at bigint,p_position_ms bigint,p_device_name text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); old public.troit_listening_history_receipts%rowtype; t bigint:=(extract(epoch from clock_timestamp())*1000)::bigint;
begin
  if me is null then raise exception 'Sign in to sync listening history.' using errcode='28000'; end if;
  if p_event_id is null then raise exception 'Invalid listening event.'; end if;
  perform public.troit_listening_validate_song(p_song);
  if p_played_at is null or p_played_at not between t-2592000000 and t+300000
    or p_position_ms is null or p_position_ms not between 0 and 604800000
    or p_device_name is null or char_length(btrim(p_device_name)) not between 1 and 64 or p_device_name ~ '[[:cntrl:]]' then raise exception 'Invalid listening event.'; end if;
  perform pg_advisory_xact_lock(hashtextextended('listening-history:'||me::text,0));
  select * into old from public.troit_listening_history_receipts where user_id=me and id=p_event_id;
  if found then
    if old.track_url<>p_song->>'url' or old.played_at<>p_played_at then raise exception 'This listening event identifier was already used.'; end if;
    return jsonb_build_object('id',p_event_id,'duplicate',true);
  end if;
  perform public.troit_connect_rate('listening-history-minute',120,60,0);
  perform public.troit_connect_rate('listening-history-day',1000,86400,0);
  delete from public.troit_listening_history_receipts where user_id=me and created_at<clock_timestamp()-interval '30 days';
  if (select count(*) from public.troit_listening_history_receipts where user_id=me)>=20000 then raise exception 'Listening history is busy. Try again later.'; end if;
  insert into public.troit_listening_history_receipts(user_id,id,track_url,played_at) values(me,p_event_id,p_song->>'url',p_played_at);
  insert into public.troit_listening_history(user_id,id,seq,song,played_at,position_ms,device_name)
    values(me,p_event_id,nextval('public.troit_listening_revision'),p_song,p_played_at,p_position_ms,btrim(p_device_name));
  delete from public.troit_listening_history where user_id=me and id in
    (select id from public.troit_listening_history where user_id=me order by played_at desc,seq desc offset 500);
  return jsonb_build_object('id',p_event_id,'duplicate',false);
end $$;

revoke all on function public.troit_listening_likes(bigint,integer),public.troit_listening_set_like(uuid,jsonb,boolean,bigint),
 public.troit_listening_history(bigint,integer),public.troit_listening_record(uuid,jsonb,bigint,bigint,text) from public,anon;
grant execute on function public.troit_listening_likes(bigint,integer),public.troit_listening_set_like(uuid,jsonb,boolean,bigint),
 public.troit_listening_history(bigint,integer),public.troit_listening_record(uuid,jsonb,bigint,bigint,text) to authenticated;
commit;
