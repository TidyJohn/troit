-- Cross-device sync for Tunes. Every row belongs to one account, and row level security
-- is what stops one account reading another's: the apps only ever hold the publishable key.

create table if not exists public.tunes_playlists (
  user_id    uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  id         uuid    not null,
  title      text    not null check (char_length(title) <= 500),
  songs      jsonb   not null default '[]'::jsonb,
  updated_at bigint  not null,
  deleted    boolean not null default false,
  primary key (user_id, id)
);

create table if not exists public.tunes_playback (
  user_id     uuid   primary key default auth.uid() references auth.users (id) on delete cascade,
  queue       jsonb  not null default '[]'::jsonb,
  idx         int    not null default 0,
  position_ms bigint not null default 0,
  device      text   not null,
  device_name text   not null,
  updated_at  bigint not null
);

alter table public.tunes_playlists enable row level security;
alter table public.tunes_playback  enable row level security;

drop policy if exists "own playlists" on public.tunes_playlists;
create policy "own playlists" on public.tunes_playlists
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "own playback" on public.tunes_playback;
create policy "own playback" on public.tunes_playback
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

revoke all on public.tunes_playlists, public.tunes_playback from anon;
grant select, insert, update, delete on public.tunes_playlists, public.tunes_playback to authenticated;
