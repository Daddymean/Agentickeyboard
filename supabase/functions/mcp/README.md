# `mcp` Edge Function

Serves the privacy-filtered slice of personal context in Supabase (sensitivity
≤ 1 only; see `docs/context-handoff.md`) to AI agents over MCP **Streamable
HTTP**, stateless, JSON responses only (no SSE, no sessions).

| Tool | What it returns |
| --- | --- |
| `get_state_now()` | Latest `daily_state` row: `{date, updatedAt, state}` |
| `get_day(date)` | `{date, timeZone, state, episodes}` for one local day in `TIMEZONE` |
| `search_episodes(query, from?, to?, limit?)` | Full-text hits via RPC `search_episodes` |
| `record_note(text)` | Inserts into `notes` with `source = 'mcp'`; `{id, createdAt}` |

Defense in depth: snapshots whose `maxSensitivity` is missing or > 1 are
returned as `state: null`, and episode rows with `sensitivity` > 1 are dropped.

## Configuration

| Env | Required | Notes |
| --- | --- | --- |
| `MCP_TOKEN` | yes | Bearer token clients send. Unset → every request is 500. Use a long random value (`openssl rand -hex 32`). |
| `TIMEZONE` | no | IANA zone for local dates, default `UTC` (e.g. `Europe/London`). Invalid → 500. |
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | provided | Injected by the platform. |

```sh
supabase secrets set MCP_TOKEN=$(openssl rand -hex 32) TIMEZONE=Europe/London
supabase functions deploy mcp --no-verify-jwt
```

`--no-verify-jwt` (or `[functions.mcp] verify_jwt = false` in
`supabase/config.toml`) is required: the gateway would otherwise reject the
`MCP_TOKEN` bearer as an invalid Supabase JWT. The function does its own auth.

## Connecting a client

- URL: `https://<project-ref>.supabase.co/functions/v1/mcp`
- Header: `Authorization: Bearer <MCP_TOKEN>`

Claude Code:

```sh
claude mcp add --transport http personal-context \
  https://<project-ref>.supabase.co/functions/v1/mcp \
  --header "Authorization: Bearer <MCP_TOKEN>"
```

Generic MCP client config (`.mcp.json`, Claude Desktop via a remote-capable
client, Cursor, etc.):

```json
{
  "mcpServers": {
    "personal-context": {
      "type": "http",
      "url": "https://<project-ref>.supabase.co/functions/v1/mcp",
      "headers": { "Authorization": "Bearer <MCP_TOKEN>" }
    }
  }
}
```

Smoke test:

```sh
curl -s https://<project-ref>.supabase.co/functions/v1/mcp \
  -H "Authorization: Bearer $MCP_TOKEN" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Protocol notes

- `POST` only; `GET`/`DELETE` → 405. Single messages and batches are accepted;
  a body of only notifications/responses → `202` with no body.
- `initialize` echoes the client's protocol version if it is one of
  `2025-06-18`, `2025-03-26`, `2024-11-05`, else replies `2025-06-18`. The
  `MCP-Protocol-Version` header is accepted leniently.
- Malformed `tools/call` params or an unknown tool → JSON-RPC `-32602`. Bad
  tool *arguments* and database failures come back as a tool result with
  `isError: true`, so the model can read the message and correct itself.
- `Origin` is not validated: the server is remote and every request needs the
  bearer token, so DNS-rebinding (the reason the spec asks for it) does not
  apply.
- Note text and tool results are never logged.
- `search_episodes` passes `from_ts` = start of `from` and `to_ts` = start of
  the day after `to` (both in `TIMEZONE`); the RPC should treat `to_ts` as an
  exclusive upper bound.

## Tests

```sh
deno test --allow-env --allow-net=127.0.0.1 supabase/functions
```

The tests drive full JSON-RPC sessions through `createHandler` with a fake
`Db`; they need no network or env.
