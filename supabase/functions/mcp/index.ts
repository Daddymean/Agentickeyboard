// Supabase Edge Function entry point: POST /functions/v1/mcp
//
// Env: MCP_TOKEN (required), TIMEZONE (IANA, default "UTC"), and the
// platform-provided SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY.

import { createSupabaseDb } from "./db.ts";
import { createHandler } from "./handler.ts";
import type { Db } from "./tools.ts";

function unavailableDb(): Db {
  const fail = () => Promise.reject(new Error("database is not configured"));
  return {
    latestDailyState: fail,
    dailyState: fail,
    episodesOverlapping: fail,
    searchEpisodes: fail,
    insertNote: fail,
  };
}

const url = Deno.env.get("SUPABASE_URL");
const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!url || !key) console.error("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY not set");

const handler = createHandler({
  db: url && key ? createSupabaseDb(url, key) : unavailableDb(),
  mcpToken: Deno.env.get("MCP_TOKEN"),
  timeZone: Deno.env.get("TIMEZONE") || "UTC",
});

Deno.serve(handler);
