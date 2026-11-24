-- Short-code TV sign-in. Anonymous capabilities are mediated by the Edge Function;
-- only inspect/approve are exposed to authenticated phones. No phone tokens are copied.
begin;
create table if not exists public.troit_device_pairings (
  id uuid primary key,
  code_hash text not null unique check(code_hash ~ '^[0-9a-f]{64}$'),
  secret_hash text not null check(secret_hash ~ '^[0-9a-f]{64}$'),
  device_name text not null check(char_length(device_name) between 1 and 64),
  approved_user_id uuid references auth.users(id) on delete cascade,
  status text not null default 'pending' check(status in ('pending','approved','issuing','ready','complete','cancelled','failed','expired')),
  lease uuid,
  issued_at timestamptz,
  encrypted_session text,
  ready_until timestamptz,
  created_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null default clock_timestamp()+interval '10 minutes'
);
create table if not exists public.troit_pairing_limits (
  key text primary key,
  window_start timestamptz not null,
  count integer not null
);
alter table public.troit_device_pairings enable row level security;
alter table public.troit_pairing_limits enable row level security;
revoke all on public.troit_device_pairings,public.troit_pairing_limits from anon,authenticated;

create or replace function public.troit_pairing_rate(p_key text,p_limit integer,p_seconds integer)
returns void language plpgsql security definer set search_path='' as $$
declare t timestamptz := clock_timestamp(); r public.troit_pairing_limits%rowtype;
begin
  perform pg_advisory_xact_lock(hashtextextended('pair-rate:'||p_key,0));
  select * into r from public.troit_pairing_limits where key=p_key for update;
  if found and t-r.window_start < p_seconds*interval '1 second' and r.count>=p_limit then
    raise exception 'Too many pairing attempts. Please wait before trying again.' using errcode='P0001';
  end if;
  insert into public.troit_pairing_limits values(p_key,t,1)
  on conflict(key) do update set count=case when t-public.troit_pairing_limits.window_start>=p_seconds*interval '1 second' then 1 else public.troit_pairing_limits.count+1 end,
    window_start=case when t-public.troit_pairing_limits.window_start>=p_seconds*interval '1 second' then t else public.troit_pairing_limits.window_start end;
end $$;

create or replace function public.troit_pairing_cleanup()
returns void language plpgsql security definer set search_path='' as $$
begin
  update public.troit_device_pairings set encrypted_session=null,status='expired'
    where (status='ready' and ready_until<=clock_timestamp()) or (expires_at<=clock_timestamp() and status not in ('complete','cancelled','failed','expired'));
  delete from public.troit_device_pairings where expires_at<clock_timestamp()-interval '1 hour';
  delete from public.troit_pairing_limits where window_start<clock_timestamp()-interval '2 hours';
end $$;

create or replace function public.troit_pairing_create(p_id uuid,p_code_hash text,p_secret_hash text,p_device_name text,p_requester_hash text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_device_pairings%rowtype;
begin
  if p_id is null or p_code_hash is null or p_code_hash !~ '^[0-9a-f]{64}$' or p_secret_hash is null or p_secret_hash !~ '^[0-9a-f]{64}$'
    or p_requester_hash is null or p_requester_hash !~ '^[0-9a-f]{64}$' or p_device_name is null or char_length(trim(p_device_name)) not between 1 and 64 then
    raise exception 'Invalid pairing request.';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('pair-create:'||p_id::text,0));
  select * into r from public.troit_device_pairings where id=p_id for update;
  if found then
    if r.code_hash=p_code_hash and r.secret_hash=p_secret_hash and r.device_name=p_device_name and r.status='pending' and r.expires_at>clock_timestamp() then
      return jsonb_build_object('id',r.id,'expires_at',r.expires_at,'status',r.status);
    end if;
    raise exception 'Pairing request was already used.';
  end if;
  perform public.troit_pairing_rate('create:'||p_requester_hash,5,600);
  perform public.troit_pairing_rate('create-global',1000,60);
  perform public.troit_pairing_cleanup();
  if (select count(*) from public.troit_device_pairings where expires_at>clock_timestamp())>=10000 then raise exception 'Pairing is busy. Try later.'; end if;
  insert into public.troit_device_pairings(id,code_hash,secret_hash,device_name) values(p_id,p_code_hash,p_secret_hash,p_device_name) returning * into r;
  return jsonb_build_object('id',r.id,'expires_at',r.expires_at,'status',r.status);
end $$;

create or replace function public.troit_pairing_inspect(p_code_hash text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); r public.troit_device_pairings%rowtype;
begin
  if me is null then raise exception 'Sign in on this device first.' using errcode='28000'; end if;
  perform public.troit_pairing_rate('inspect:'||me::text,10,60);
  select * into r from public.troit_device_pairings where code_hash=p_code_hash and status='pending' and expires_at>clock_timestamp();
  if not found then return null; end if;
  return jsonb_build_object('id',r.id,'device_name',r.device_name,'expires_at',r.expires_at);
end $$;

create or replace function public.troit_pairing_approve(p_code_hash text,p_pairing_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); r public.troit_device_pairings%rowtype;
begin
  if me is null then raise exception 'Sign in on this device first.' using errcode='28000'; end if;
  select * into r from public.troit_device_pairings where id=p_pairing_id and code_hash=p_code_hash for update;
  if not found or r.expires_at<=clock_timestamp() then raise exception 'This TV code is unavailable or expired.'; end if;
  if r.approved_user_id=me and r.status in ('approved','issuing','ready','complete') then return jsonb_build_object('status','approved'); end if;
  if r.status<>'pending' then raise exception 'This TV code was already used.'; end if;
  perform public.troit_pairing_rate('approve:'||me::text,5,60);
  update public.troit_device_pairings set status='approved',approved_user_id=me where id=r.id;
  return jsonb_build_object('status','approved');
end $$;

create or replace function public.troit_pairing_poll(p_id uuid,p_secret_hash text,p_lease uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare r public.troit_device_pairings%rowtype;
begin
  select * into r from public.troit_device_pairings where id=p_id and secret_hash=p_secret_hash for update;
  if not found then return jsonb_build_object('status','unavailable'); end if;
  perform public.troit_pairing_rate('poll:'||r.id::text,40,60);
  if r.expires_at<=clock_timestamp() or (r.status='ready' and r.ready_until<=clock_timestamp()) then
    update public.troit_device_pairings set status='expired',encrypted_session=null where id=r.id;
    return jsonb_build_object('status','expired');
  end if;
  if r.status='approved' and p_lease is not null then
    update public.troit_device_pairings set status='issuing',lease=p_lease,issued_at=clock_timestamp() where id=r.id;
    return jsonb_build_object('status','issue','user_id',r.approved_user_id,'lease',p_lease);
  end if;
  if r.status='issuing' and clock_timestamp()-r.issued_at>interval '45 seconds' then
    update public.troit_device_pairings set status='failed' where id=r.id;
    return jsonb_build_object('status','failed');
  end if;
  if r.status='ready' then return jsonb_build_object('status','ready','encrypted_session',r.encrypted_session); end if;
  return jsonb_build_object('status',case when r.status='issuing' then 'pending' else r.status end);
end $$;

create or replace function public.troit_pairing_save(p_id uuid,p_secret_hash text,p_lease uuid,p_encrypted_session text)
returns jsonb language plpgsql security definer set search_path='' as $$
begin
  if p_encrypted_session is null or char_length(p_encrypted_session) not between 32 and 32000 then raise exception 'Invalid session envelope.'; end if;
  update public.troit_device_pairings set status='ready',encrypted_session=p_encrypted_session,ready_until=least(expires_at,clock_timestamp()+interval '90 seconds')
    where id=p_id and secret_hash=p_secret_hash and lease=p_lease and status='issuing' and expires_at>clock_timestamp()
    and issued_at>clock_timestamp()-interval '45 seconds';
  if not found then raise exception 'Pairing was cancelled or expired.'; end if;
  return jsonb_build_object('status','ready');
end $$;

create or replace function public.troit_pairing_cancel(p_id uuid,p_secret_hash text,p_complete boolean default false)
returns void language plpgsql security definer set search_path='' as $$
begin
  update public.troit_device_pairings set status=case when p_complete then 'complete' else 'cancelled' end,encrypted_session=null
    where id=p_id and secret_hash=p_secret_hash;
end $$;

revoke all on function public.troit_pairing_cleanup(),public.troit_pairing_rate(text,integer,integer),public.troit_pairing_create(uuid,text,text,text,text),
 public.troit_pairing_poll(uuid,text,uuid),public.troit_pairing_save(uuid,text,uuid,text),public.troit_pairing_cancel(uuid,text,boolean) from public,anon,authenticated;
grant execute on function public.troit_pairing_create(uuid,text,text,text,text),public.troit_pairing_poll(uuid,text,uuid),
 public.troit_pairing_save(uuid,text,uuid,text),public.troit_pairing_cancel(uuid,text,boolean),public.troit_pairing_cleanup() to service_role;
revoke all on function public.troit_pairing_inspect(text),public.troit_pairing_approve(text,uuid) from public,anon;
grant execute on function public.troit_pairing_inspect(text),public.troit_pairing_approve(text,uuid) to authenticated;
commit;
