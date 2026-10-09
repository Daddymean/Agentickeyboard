// Db implementation backed by supabase-js with the service-role key (the
// function is the only reader; RLS keeps anon/authenticated clients out).

import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import type { DailyStateRow, Db, EpisodeRow } from "./tools.ts";

const EPISODE_COLUMNS =
  "id,start_ms,end_ms,kind,title,summary,event_ids,sensitivity,start_at,end_at";

function check<T>(res: { data: T; error: { message: string } | null }, what: string): T {
  if (res.error) throw new Error(`${what}: ${res.error.message}`);
  return res.data;
}

export function createSupabaseDb(url: string, serviceRoleKey: string): Db {
  const client: SupabaseClient = createClient(url, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  return {
    async latestDailyState() {
      const res = await client
        .from("daily_state")
        .select("date,state,updated_at")
        .order("date", { ascending: false })
        .limit(1)
        .maybeSingle();
      return check(res, "daily_state query failed") as DailyStateRow | null;
    },

    async dailyState(date) {
      const res = await client
        .from("daily_state")
        .select("date,state,updated_at")
        .eq("date", date)
        .maybeSingle();
      return check(res, "daily_state query failed") as DailyStateRow | null;
    },

    async episodesOverlapping(from, to, limit) {
      const res = await client
        .from("episodes")
        .select(EPISODE_COLUMNS)
        .lt("start_at", to.toISOString())
        // [from, to) overlap; zero-length episodes count if they start in range.
        .or(`end_at.gt.${from.toISOString()},start_at.gte.${from.toISOString()}`)
        .lte("sensitivity", 1)
        .order("start_at", { ascending: true })
        .limit(limit);
      return (check(res, "episodes query failed") ?? []) as unknown as EpisodeRow[];
    },

    async searchEpisodes(q, from, to, limit) {
      const res = await client.rpc("search_episodes", {
        q,
        from_ts: from ? from.toISOString() : null,
        to_ts: to ? to.toISOString() : null,
        lim: limit,
      });
      return (check(res, "search_episodes failed") ?? []) as unknown as EpisodeRow[];
    },

    async insertNote(text, source) {
      const res = await client
        .from("notes")
        .insert({ text, source })
        .select("id,created_at")
        .single();
      return check(res, "notes insert failed") as { id: string; created_at: string };
    },
  };
}
