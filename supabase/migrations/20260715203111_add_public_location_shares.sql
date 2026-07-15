create table if not exists public.public_location_shares (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    token_hash text not null unique,
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    constraint public_location_shares_token_hash_format
        check (token_hash ~ '^[0-9a-f]{64}$'),
    constraint public_location_shares_expiry_after_creation
        check (expires_at is null or expires_at > created_at)
);

create index if not exists public_location_shares_owner_created_idx
    on public.public_location_shares(owner_id, created_at desc);

create unique index if not exists public_location_shares_one_unrevoked_owner_idx
    on public.public_location_shares(owner_id)
    where revoked_at is null;

alter table public.public_location_shares enable row level security;

drop policy if exists "Users read own public location shares" on public.public_location_shares;
create policy "Users read own public location shares"
on public.public_location_shares
for select
to authenticated
using ((select auth.uid()) = owner_id);

drop policy if exists "Users create own public location shares" on public.public_location_shares;
create policy "Users create own public location shares"
on public.public_location_shares
for insert
to authenticated
with check ((select auth.uid()) = owner_id);

drop policy if exists "Users revoke own public location shares" on public.public_location_shares;
create policy "Users revoke own public location shares"
on public.public_location_shares
for update
to authenticated
using ((select auth.uid()) = owner_id)
with check ((select auth.uid()) = owner_id);

grant select, insert, update on public.public_location_shares to authenticated;
revoke all on public.public_location_shares from anon;

create or replace function public.create_live_location_share(
    share_token_hash text,
    duration_seconds integer
)
returns table(id uuid, created_at timestamptz, expires_at timestamptz)
language plpgsql
security invoker
set search_path = public
as $$
declare
    current_user_id uuid := auth.uid();
begin
    if current_user_id is null then
        raise exception 'Not authenticated';
    end if;

    if share_token_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid token hash';
    end if;

    if duration_seconds is not null
       and duration_seconds not in (900, 3600, 14400, 28800, 86400) then
        raise exception 'Unsupported share duration';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(current_user_id::text, 0));

    update public.public_location_shares
    set revoked_at = now()
    where owner_id = current_user_id
      and revoked_at is null;

    return query
    insert into public.public_location_shares(owner_id, token_hash, expires_at)
    values (
        current_user_id,
        share_token_hash,
        case
            when duration_seconds is null then null
            else now() + make_interval(secs => duration_seconds)
        end
    )
    returning
        public_location_shares.id,
        public_location_shares.created_at,
        public_location_shares.expires_at;
end;
$$;

create or replace function public.revoke_live_location_share(share_id uuid)
returns void
language sql
security invoker
set search_path = public
as $$
    update public.public_location_shares
    set revoked_at = now()
    where id = share_id
      and owner_id = auth.uid()
      and revoked_at is null;
$$;

create or replace function public.current_live_location_share()
returns table(id uuid, created_at timestamptz, expires_at timestamptz)
language sql
security invoker
set search_path = public
stable
as $$
    select share.id, share.created_at, share.expires_at
    from public.public_location_shares share
    where share.owner_id = auth.uid()
      and share.revoked_at is null
      and (share.expires_at is null or share.expires_at > now())
    order by share.created_at desc
    limit 1;
$$;

revoke all on function public.create_live_location_share(text, integer) from public, anon;
revoke all on function public.revoke_live_location_share(uuid) from public, anon;
revoke all on function public.current_live_location_share() from public, anon;

grant execute on function public.create_live_location_share(text, integer) to authenticated;
grant execute on function public.revoke_live_location_share(uuid) to authenticated;
grant execute on function public.current_live_location_share() to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'live-share-web',
    'live-share-web',
    true,
    1048576,
    array['text/html', 'text/css', 'application/javascript']
)
on conflict (id) do update set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;
