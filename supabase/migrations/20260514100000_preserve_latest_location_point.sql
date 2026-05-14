create or replace function public.prune_old_location_points()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    delete from public.location_points lp
    where lp.created_at < now() - interval '24 hours'
      and exists (
          select 1
          from public.location_points newer
          where newer.user_id = lp.user_id
            and newer.recorded_at > lp.recorded_at
      );
    return new;
end;
$$;
