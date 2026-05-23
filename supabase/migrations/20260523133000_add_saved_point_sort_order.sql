alter table public.saved_points
add column if not exists sort_order bigint;

update public.saved_points
set sort_order = -floor(extract(epoch from created_at) * 1000)::bigint
where sort_order is null;

alter table public.saved_points
alter column sort_order set default 0,
alter column sort_order set not null;

create index if not exists saved_points_user_sort_idx
on public.saved_points(user_id, sort_order asc);
