# Supabase (context cloud slice)

The phone syncs a filtered slice of the context store (sensitivity ≤ 1 only) to
Supabase. Two Edge Functions own all data access with the service role:

- `sync` — phone → cloud upserts/deletes (bearer `SYNC_TOKEN`).
- `mcp` — MCP server for assistants: search episodes, current state, record notes
  (bearer `MCP_TOKEN`).

Tables (`migrations/`): `episodes`, `daily_state`, `notes`, plus the
`search_episodes(q, from_ts, to_ts, lim)` RPC. RLS is enabled with no policies
and `anon`/`authenticated` have no privileges, so nothing is reachable through
the public API keys. See the comment at the top of the migration.

## Deploy

```sh
supabase login
supabase link --project-ref <project-ref>
supabase db push                       # applies supabase/migrations

supabase secrets set \
  SYNC_TOKEN="$(openssl rand -hex 32)" \
  MCP_TOKEN="$(openssl rand -hex 32)" \
  TIMEZONE="Europe/London"             # IANA zone used for "today"

supabase functions deploy sync mcp --no-verify-jwt
```

`--no-verify-jwt` (also set in `config.toml`) is intentional: the functions
check their own bearer secrets instead of Supabase JWTs. Keep the tokens out of
the repo; put `SYNC_TOKEN` in the phone app and `MCP_TOKEN` in your MCP client.

## Tests

- Schema/RLS: `cd supabase/tests && npm ci && npm test` (in-process Postgres via
  PGlite; `roles.sql` creates the Supabase roles a bare Postgres lacks).
- Edge Functions: `deno test --allow-env --allow-net=127.0.0.1 supabase/functions`.

CI (`.github/workflows/supabase.yml`) runs both, and also applies the
migrations to a real `postgres:16` container.
