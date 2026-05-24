create table if not exists public.track_share_packages (
  id uuid primary key default gen_random_uuid(),
  token text not null unique default encode(gen_random_bytes(16), 'hex'),
  created_by uuid references auth.users(id) on delete set null,
  payload jsonb not null,
  created_at timestamptz not null default now()
);

alter table public.track_share_packages enable row level security;

do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename = 'track_share_packages'
      and policyname = 'Anyone can read track share packages by token'
  ) then
    create policy "Anyone can read track share packages by token"
      on public.track_share_packages
      for select
      to anon, authenticated
      using (true);
  end if;

  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename = 'track_share_packages'
      and policyname = 'Users can create track share packages'
  ) then
    create policy "Users can create track share packages"
      on public.track_share_packages
      for insert
      to authenticated
      with check (auth.uid() = created_by);
  end if;
end $$;
