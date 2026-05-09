create table if not exists public.friend_invites (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    token text not null unique default encode(gen_random_bytes(16), 'hex'),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default now() + interval '30 days',
    used_at timestamptz
);

create table if not exists public.friendships (
    id uuid primary key default gen_random_uuid(),
    user_a uuid not null references auth.users(id) on delete cascade,
    user_b uuid not null references auth.users(id) on delete cascade,
    user_a_shares boolean not null default true,
    user_b_shares boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (user_a <> user_b),
    unique (user_a, user_b)
);

create table if not exists public.location_points (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    latitude double precision not null,
    longitude double precision not null,
    altitude double precision,
    accuracy_meters real,
    recorded_at timestamptz not null,
    created_at timestamptz not null default now()
);

create index if not exists friend_invites_owner_created_idx on public.friend_invites(owner_id, created_at desc);
create index if not exists friendships_user_a_idx on public.friendships(user_a);
create index if not exists friendships_user_b_idx on public.friendships(user_b);
create index if not exists location_points_user_recorded_idx on public.location_points(user_id, recorded_at desc);

alter table public.friend_invites enable row level security;
alter table public.friendships enable row level security;
alter table public.location_points enable row level security;

drop policy if exists "Users manage own invites" on public.friend_invites;
create policy "Users manage own invites"
on public.friend_invites
for all
to authenticated
using ((select auth.uid()) = owner_id)
with check ((select auth.uid()) = owner_id);

drop policy if exists "Users see own friendships" on public.friendships;
create policy "Users see own friendships"
on public.friendships
for select
to authenticated
using ((select auth.uid()) = user_a or (select auth.uid()) = user_b);

drop policy if exists "Users update own sharing side" on public.friendships;
create policy "Users update own sharing side"
on public.friendships
for update
to authenticated
using ((select auth.uid()) = user_a or (select auth.uid()) = user_b)
with check ((select auth.uid()) = user_a or (select auth.uid()) = user_b);

drop policy if exists "Users delete own friendships" on public.friendships;
create policy "Users delete own friendships"
on public.friendships
for delete
to authenticated
using ((select auth.uid()) = user_a or (select auth.uid()) = user_b);

drop policy if exists "Users insert own location" on public.location_points;
create policy "Users insert own location"
on public.location_points
for insert
to authenticated
with check ((select auth.uid()) = user_id);

drop policy if exists "Users see own and shared friend locations" on public.location_points;
create policy "Users see own and shared friend locations"
on public.location_points
for select
to authenticated
using (
    (select auth.uid()) = user_id
    or exists (
        select 1
        from public.friendships f
        where (
            f.user_a = (select auth.uid()) and f.user_b = location_points.user_id and f.user_b_shares
        ) or (
            f.user_b = (select auth.uid()) and f.user_a = location_points.user_id and f.user_a_shares
        )
    )
);

grant select, insert, update, delete on public.friend_invites to authenticated;
grant select, update, delete on public.friendships to authenticated;
grant select, insert on public.location_points to authenticated;

create or replace function public.create_friend_invite()
returns text
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    current_user_id uuid;
    invite_token text;
begin
    current_user_id := auth.uid();
    if current_user_id is null then
        raise exception 'Not authenticated';
    end if;

    insert into public.friend_invites(owner_id)
    values (current_user_id)
    returning token into invite_token;

    return invite_token;
end;
$$;

create or replace function public.accept_friend_invite(invite_token text)
returns uuid
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    invite_owner uuid;
    current_user_id uuid;
    first_user uuid;
    second_user uuid;
    friendship_id uuid;
begin
    current_user_id := auth.uid();
    if current_user_id is null then
        raise exception 'Not authenticated';
    end if;

    select owner_id into invite_owner
    from public.friend_invites
    where token = invite_token
      and expires_at > now()
    order by created_at desc
    limit 1;

    if invite_owner is null then
        raise exception 'Invite not found';
    end if;

    if invite_owner = current_user_id then
        raise exception 'Cannot add yourself';
    end if;

    first_user := least(invite_owner, current_user_id);
    second_user := greatest(invite_owner, current_user_id);

    insert into public.friendships(user_a, user_b)
    values (first_user, second_user)
    on conflict (user_a, user_b) do update set updated_at = now()
    returning id into friendship_id;

    update public.friend_invites
    set used_at = now()
    where token = invite_token;

    return friendship_id;
end;
$$;

create or replace function public.friend_list()
returns table(friendship_id uuid, friend_id uuid, friend_email text, i_share boolean, friend_shares boolean)
language sql
security definer
set search_path = public, auth
as $$
    select
        f.id as friendship_id,
        case when f.user_a = auth.uid() then f.user_b else f.user_a end as friend_id,
        u.email as friend_email,
        case when f.user_a = auth.uid() then f.user_a_shares else f.user_b_shares end as i_share,
        case when f.user_a = auth.uid() then f.user_b_shares else f.user_a_shares end as friend_shares
    from public.friendships f
    join auth.users u on u.id = case when f.user_a = auth.uid() then f.user_b else f.user_a end
    where f.user_a = auth.uid() or f.user_b = auth.uid();
$$;

create or replace function public.set_friend_share(friendship uuid, share_enabled boolean)
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
begin
    update public.friendships
    set
        user_a_shares = case when user_a = auth.uid() then share_enabled else user_a_shares end,
        user_b_shares = case when user_b = auth.uid() then share_enabled else user_b_shares end,
        updated_at = now()
    where id = friendship and (user_a = auth.uid() or user_b = auth.uid());
end;
$$;

grant execute on function public.create_friend_invite() to authenticated;
grant execute on function public.accept_friend_invite(text) to authenticated;
grant execute on function public.friend_list() to authenticated;
grant execute on function public.set_friend_share(uuid, boolean) to authenticated;

create or replace function public.prune_old_location_points()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    delete from public.location_points
    where created_at < now() - interval '24 hours';
    return new;
end;
$$;

drop trigger if exists prune_old_location_points_after_insert on public.location_points;
create trigger prune_old_location_points_after_insert
after insert on public.location_points
for each statement
execute function public.prune_old_location_points();
