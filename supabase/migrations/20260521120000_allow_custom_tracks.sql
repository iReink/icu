alter table public.tracks
drop constraint if exists tracks_activity_type_check;

alter table public.tracks
add constraint tracks_activity_type_check
check (activity_type = any (array['walk'::text, 'bike'::text, 'custom'::text]));
