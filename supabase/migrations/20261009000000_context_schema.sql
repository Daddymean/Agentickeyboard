-- Context platform cloud slice: episodes, daily_state, notes.
--
-- Security model (single user):
--   * The phone uploads only rows with sensitivity <= 1 (see
--     docs/context-handoff.md); the CHECK constraints below enforce that again
--     server-side so a client bug cannot push private data.
--   * No client ever talks to these tables through PostgREST. Only the `sync`
--     and `mcp` Edge Functions touch data, using the service role, and each
--     function gates requests on its own bearer secret (SYNC_TOKEN / MCP_TOKEN).
--   * Therefore RLS is enabled on every table with NO policies (deny-all for
--     anon/authenticated even if a grant slips back in), table privileges are
--     revoked from anon/authenticated, and search_episodes is not executable by
--     them. service_role bypasses RLS and gets explicit grants.

-- ---------------------------------------------------------------------------
-- episodes
-- ---------------------------------------------------------------------------
create table public.episodes (
  id          text primary key,
  start_ms    bigint not null,
  end_ms      bigint not null,
  kind        text not null,
  title       text not null,
  summary     text not null,
  event_ids   jsonb not null default '[]'::jsonb,
  sensitivity smallint not null check (sensitivity between 0 and 1),
  start_at    timestamptz generated always as (to_timestamp(start_ms / 1000.0)) stored,
  end_at      timestamptz generated always as (to_timestamp(end_ms / 1000.0)) stored,
  search      tsvector generated always as (
                to_tsvector('simple'::regconfig, coalesce(title, '') || ' ' || coalesce(summary, ''))
              ) stored,
  synced_at   timestamptz not null default now(),
  constraint episodes_end_after_start check (end_ms >= start_ms)
);

comment on table public.episodes is
  'Episodes synced from the phone (sensitivity <= 1 only). event_ids is the parsed JSON array of event ids.';

create index episodes_search_idx   on public.episodes using gin (search);
create index episodes_start_at_idx on public.episodes (start_at);
create index episodes_end_at_idx   on public.episodes (end_at);

-- ---------------------------------------------------------------------------
-- daily_state: the sync snapshot (Snapshot.forSync()) per local date
-- ---------------------------------------------------------------------------
create table public.daily_state (
  date       date primary key,
  state      jsonb not null,
  updated_at timestamptz not null default now(),
  constraint daily_state_syncable check (
    (state ? 'generatedAtMs')
    and (state ? 'maxSensitivity')
    and (state ->> 'maxSensitivity')::int <= 1
  )
);

comment on table public.daily_state is
  'Latest sync snapshot per date. Rows must carry maxSensitivity <= 1.';

-- ---------------------------------------------------------------------------
-- notes: written by the MCP record_note tool (cloud only)
-- ---------------------------------------------------------------------------
create table public.notes (
  id         text primary key default gen_random_uuid()::text,
  created_at timestamptz not null default now(),
  text       text not null check (length(text) between 1 and 20000),
  source     text not null default 'mcp'
);

create index notes_created_at_idx on public.notes (created_at desc);

-- ---------------------------------------------------------------------------
-- search_episodes: full-text search with optional time-window overlap
-- ---------------------------------------------------------------------------
create function public.search_episodes(
  q       text,
  from_ts timestamptz default null,
  to_ts   timestamptz default null,
  lim     int default 20
)
returns setof public.episodes
language sql
stable
security invoker
set search_path = ''
as $$
  select e.*
  from public.episodes e
  where e.search @@ pg_catalog.websearch_to_tsquery('simple'::regconfig, q)
    and (from_ts is null or e.end_at > from_ts)
    and (to_ts is null or e.start_at < to_ts)
  order by pg_catalog.ts_rank(e.search, pg_catalog.websearch_to_tsquery('simple'::regconfig, q)) desc,
           e.start_at desc
  limit least(greatest(coalesce(lim, 20), 1), 100)
$$;

-- ---------------------------------------------------------------------------
-- Lock down: RLS on, no policies; only service_role has privileges.
-- ---------------------------------------------------------------------------
alter table public.episodes    enable row level security;
alter table public.daily_state enable row level security;
alter table public.notes       enable row level security;

revoke all on table public.episodes, public.daily_state, public.notes from anon, authenticated;
revoke execute on function public.search_episodes(text, timestamptz, timestamptz, int)
  from public, anon, authenticated;

grant usage on schema public to service_role;
grant select, insert, update, delete on table public.episodes, public.daily_state, public.notes
  to service_role;
grant execute on function public.search_episodes(text, timestamptz, timestamptz, int)
  to service_role;
