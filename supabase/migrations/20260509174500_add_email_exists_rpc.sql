create or replace function public.email_exists_for_auth(candidate_email text)
returns boolean
language sql
security definer
set search_path = auth, public
as $$
    select exists (
        select 1
        from auth.users
        where lower(email) = lower(trim(candidate_email))
          and deleted_at is null
    );
$$;

revoke all on function public.email_exists_for_auth(text) from public;
grant execute on function public.email_exists_for_auth(text) to anon, authenticated;
