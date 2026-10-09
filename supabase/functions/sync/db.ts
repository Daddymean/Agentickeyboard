// supabase-js implementation of the sync `Db` port. Uses the service-role key,
// which the Edge runtime provides as SUPABASE_SERVICE_ROLE_KEY; it bypasses RLS,
// so this function's own bearer check is the only gate.

// deno-lint-ignore no-import-prefix -- inline specifier so it resolves from any cwd
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import type { DailyStateRow, Db, EpisodeRow, NoteRow } from "./handler.ts";

const DELETE_CHUNK = 50;

function check(error: { message: string } | null, op: string): void {
  if (error) throw new Error(`${op}: ${error.message}`);
}

export function supabaseDbFrom(client: SupabaseClient): Db {
  return {
    async deleteEpisodes(ids: string[]): Promise<number> {
      // `.in()` goes into the URL query string; chunk so 500 long ids can't
      // exceed PostgREST/gateway URL limits.
      let deleted = 0;
      for (let i = 0; i < ids.length; i += DELETE_CHUNK) {
        const { data, error } = await client
          .from("episodes")
          .delete()
          .in("id", ids.slice(i, i + DELETE_CHUNK))
          .select("id");
        check(error, "delete episodes");
        deleted += data?.length ?? 0;
      }
      return deleted;
    },

    async upsertEpisodes(rows: EpisodeRow[]): Promise<number> {
      const now = new Date().toISOString();
      // Only base columns: start_at/end_at/search are generated and must not be written.
      const payload = rows.map((r) => ({
        id: r.id,
        start_ms: r.start_ms,
        end_ms: r.end_ms,
        kind: r.kind,
        title: r.title,
        summary: r.summary,
        event_ids: r.event_ids,
        sensitivity: r.sensitivity,
        synced_at: now,
      }));
      const { error } = await client
        .from("episodes")
        .upsert(payload, { onConflict: "id" });
      check(error, "upsert episodes");
      return rows.length;
    },

    async upsertDailyState(row: DailyStateRow): Promise<void> {
      const { error } = await client
        .from("daily_state")
        .upsert(
          {
            date: row.date,
            state: row.state,
            updated_at: new Date().toISOString(),
          },
          { onConflict: "date" },
        );
      check(error, "upsert daily_state");
    },

    async insertNotes(rows: NoteRow[]): Promise<number> {
      const { data, error } = await client
        .from("notes")
        .upsert(rows, { onConflict: "id", ignoreDuplicates: true })
        .select("id");
      check(error, "insert notes");
      return data?.length ?? 0;
    },
  };
}

export function supabaseDb(): Db {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) {
    throw new Error("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set");
  }
  return supabaseDbFrom(
    createClient(url, key, {
      auth: { persistSession: false, autoRefreshToken: false },
    }),
  );
}
