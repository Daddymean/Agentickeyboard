-- Stand-ins for the roles a hosted Supabase project already has, so the
-- migrations can be applied to a bare Postgres (PGlite in schema.test.mjs, the
-- postgres:16 service container in CI). Like on Supabase, service_role
-- bypasses RLS. Idempotent.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin noinherit bypassrls;
  end if;
end
$$;

-- Supabase grants schema usage and default table privileges to the API roles;
-- mirror that so the migration's revokes are actually exercised.
grant usage on schema public to anon, authenticated, service_role;
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
