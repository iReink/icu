create extension if not exists pgcrypto;

create table if not exists public.point_share_packages (
  id uuid primary key default gen_random_uuid(),
  token text not null unique default encode(gen_random_bytes(16), 'hex'),
  created_by uuid references auth.users(id) on delete cascade,
  payload jsonb not null,
  created_at timestamptz not null default now()
);

alter table public.point_share_packages enable row level security;

drop policy if exists "Users can create point share packages" on public.point_share_packages;
create policy "Users can create point share packages"
on public.point_share_packages
for insert
to authenticated
with check (auth.uid() = created_by);

drop policy if exists "Anyone can read point share packages by token" on public.point_share_packages;
create policy "Anyone can read point share packages by token"
on public.point_share_packages
for select
to anon, authenticated
using (true);

create index if not exists point_share_packages_token_idx
on public.point_share_packages (token);
