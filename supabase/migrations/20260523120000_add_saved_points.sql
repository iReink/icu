create table if not exists public.saved_points (
    id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    name text not null,
    latitude double precision not null,
    longitude double precision not null,
    visible boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

create index if not exists saved_points_user_updated_idx
on public.saved_points(user_id, updated_at desc);

alter table public.saved_points enable row level security;

drop policy if exists "Users manage own saved points" on public.saved_points;
create policy "Users manage own saved points"
on public.saved_points
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.saved_points to authenticated;
