alter table public.friend_invites
    alter column expires_at drop not null,
    alter column expires_at drop default;

create table if not exists public.user_profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    first_name text not null default '',
    last_name text not null default '',
    updated_at timestamptz not null default now()
);

create table if not exists public.friend_aliases (
    owner_id uuid not null references auth.users(id) on delete cascade,
    friend_id uuid not null references auth.users(id) on delete cascade,
    first_name text not null default '',
    last_name text not null default '',
    updated_at timestamptz not null default now(),
    primary key (owner_id, friend_id),
    check (owner_id <> friend_id)
);

alter table public.user_profiles enable row level security;
alter table public.friend_aliases enable row level security;

drop policy if exists "Users manage own profile" on public.user_profiles;
create policy "Users manage own profile"
on public.user_profiles
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

drop policy if exists "Users see friend profiles" on public.user_profiles;
create policy "Users see friend profiles"
on public.user_profiles
for select
to authenticated
using (
    (select auth.uid()) = user_id
    or exists (
        select 1
        from public.friendships f
        where (f.user_a = (select auth.uid()) and f.user_b = user_profiles.user_id)
           or (f.user_b = (select auth.uid()) and f.user_a = user_profiles.user_id)
    )
);

drop policy if exists "Users manage own friend aliases" on public.friend_aliases;
create policy "Users manage own friend aliases"
on public.friend_aliases
for all
to authenticated
using ((select auth.uid()) = owner_id)
with check ((select auth.uid()) = owner_id);

grant select, insert, update, delete on public.user_profiles to authenticated;
grant select, insert, update, delete on public.friend_aliases to authenticated;

drop function if exists public.friend_list();
drop function if exists public.my_profile();
drop function if exists public.update_my_profile(text, text);
drop function if exists public.set_friend_alias(uuid, text, text);
drop function if exists public.reset_friend_alias(uuid);

create or replace function public.update_my_profile(first_name_value text, last_name_value text)
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    insert into public.user_profiles(user_id, first_name, last_name, updated_at)
    values (auth.uid(), trim(coalesce(first_name_value, '')), trim(coalesce(last_name_value, '')), now())
    on conflict (user_id) do update set
        first_name = excluded.first_name,
        last_name = excluded.last_name,
        updated_at = now();
end;
$$;

create or replace function public.set_friend_alias(friend uuid, first_name_value text, last_name_value text)
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    if not exists (
        select 1
        from public.friendships f
        where (f.user_a = auth.uid() and f.user_b = friend)
           or (f.user_b = auth.uid() and f.user_a = friend)
    ) then
        raise exception 'Friendship not found';
    end if;

    insert into public.friend_aliases(owner_id, friend_id, first_name, last_name, updated_at)
    values (auth.uid(), friend, trim(coalesce(first_name_value, '')), trim(coalesce(last_name_value, '')), now())
    on conflict (owner_id, friend_id) do update set
        first_name = excluded.first_name,
        last_name = excluded.last_name,
        updated_at = now();
end;
$$;

create or replace function public.reset_friend_alias(friend uuid)
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    delete from public.friend_aliases
    where owner_id = auth.uid() and friend_id = friend;
end;
$$;

create or replace function public.my_profile()
returns table(user_id uuid, email text, first_name text, last_name text)
language sql
security definer
set search_path = public, auth
as $$
    select
        u.id as user_id,
        u.email::text as email,
        coalesce(p.first_name, '') as first_name,
        coalesce(p.last_name, '') as last_name
    from auth.users u
    left join public.user_profiles p on p.user_id = u.id
    where u.id = auth.uid();
$$;

create or replace function public.friend_list()
returns table(
    friendship_id uuid,
    friend_id uuid,
    friend_email text,
    friend_first_name text,
    friend_last_name text,
    alias_first_name text,
    alias_last_name text,
    i_share boolean,
    friend_shares boolean
)
language sql
security definer
set search_path = public, auth
as $$
    select
        f.id as friendship_id,
        case when f.user_a = auth.uid() then f.user_b else f.user_a end as friend_id,
        u.email::text as friend_email,
        coalesce(p.first_name, '') as friend_first_name,
        coalesce(p.last_name, '') as friend_last_name,
        coalesce(a.first_name, '') as alias_first_name,
        coalesce(a.last_name, '') as alias_last_name,
        case when f.user_a = auth.uid() then f.user_a_shares else f.user_b_shares end as i_share,
        case when f.user_a = auth.uid() then f.user_b_shares else f.user_a_shares end as friend_shares
    from public.friendships f
    join auth.users u on u.id = case when f.user_a = auth.uid() then f.user_b else f.user_a end
    left join public.user_profiles p on p.user_id = u.id
    left join public.friend_aliases a on a.owner_id = auth.uid() and a.friend_id = u.id
    where f.user_a = auth.uid() or f.user_b = auth.uid();
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

grant execute on function public.update_my_profile(text, text) to authenticated;
grant execute on function public.set_friend_alias(uuid, text, text) to authenticated;
grant execute on function public.reset_friend_alias(uuid) to authenticated;
grant execute on function public.my_profile() to authenticated;
grant execute on function public.friend_list() to authenticated;
grant execute on function public.accept_friend_invite(text) to authenticated;
