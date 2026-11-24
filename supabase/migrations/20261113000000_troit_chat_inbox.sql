-- A bounded recipient inbox avoids polling every conversation separately.
begin;
create index if not exists troit_messages_recipient_sender_seq
  on public.troit_messages(recipient_id,sender_id,seq desc);

create or replace function public.troit_chat_inbox()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); result jsonb;
begin
  if me is null then raise exception 'Sign in first.' using errcode='28000'; end if;
  -- Latest incoming message per peer, rather than a global seq cursor: independent
  -- conversations can commit in a different order without losing notifications.
  select coalesce(jsonb_agg(jsonb_build_object(
    'peer',jsonb_build_object('id',p.user_id,'handle',p.handle,'display_name',p.display_name),
    'message',to_jsonb(m)-'friendship_id') order by m.seq),'[]'::jsonb)
  into result
  from (
    select f.id,case when f.user_low=me then f.user_high else f.user_low end as peer_id
    from public.troit_friendships f
    where me in (f.user_low,f.user_high) and f.status='accepted'
    order by f.id limit 300
  ) f
  join public.troit_profiles p on p.user_id=f.peer_id
  join lateral (
    select msg.* from public.troit_messages msg
    where msg.recipient_id=me and msg.sender_id=f.peer_id and msg.friendship_id=f.id
    order by msg.seq desc limit 1
  ) m on true
  where not exists(select 1 from public.troit_blocks b
    where (b.blocker_id=me and b.blocked_id=f.peer_id) or (b.blocker_id=f.peer_id and b.blocked_id=me));
  return result;
end $$;
revoke all on function public.troit_chat_inbox() from public,anon;
grant execute on function public.troit_chat_inbox() to authenticated;
commit;
