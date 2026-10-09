// Entry point for `POST /functions/v1/sync`. See handler.ts for the protocol.
import { createHandler } from "./handler.ts";
import { supabaseDb } from "./db.ts";

Deno.serve(
  createHandler({ db: supabaseDb(), syncToken: Deno.env.get("SYNC_TOKEN") }),
);
