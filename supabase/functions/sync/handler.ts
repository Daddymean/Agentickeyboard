// Request handling for the `sync` Edge Function: auth, limits, strict
// validation and the ordered writes. Pure apart from the injected `Db`, so the
// tests drive it end to end with an in-memory fake.
//
// Privacy: request bodies carry personal data. Never log them; log only counts
// and error messages.

export const MAX_BODY_BYTES = 1024 * 1024;
export const MAX_ITEMS = 500;
export const MAX_NOTE_CHARS = 20_000;
/** Highest sensitivity allowed to leave the device (Sensitivity.SYNC_MAX). */
export const SYNC_MAX_SENSITIVITY = 1;

/** Row written to `episodes`. Generated columns (start_at, end_at, search) and
 * `synced_at` are deliberately absent; the Db sets `synced_at = now()`. */
export interface EpisodeRow {
  id: string;
  start_ms: number;
  end_ms: number;
  kind: string;
  title: string;
  summary: string;
  event_ids: string[];
  sensitivity: number;
}

/** Row written to `daily_state`; the Db sets `updated_at = now()`. */
export interface DailyStateRow {
  date: string;
  state: Record<string, unknown>;
}

/** Row written to `notes`. */
export interface NoteRow {
  id: string;
  created_at: string;
  text: string;
  source: string;
}

/** Storage port. Each method returns the number of rows it affected. */
export interface Db {
  deleteEpisodes(ids: string[]): Promise<number>;
  upsertEpisodes(rows: EpisodeRow[]): Promise<number>;
  upsertDailyState(row: DailyStateRow): Promise<void>;
  /** Insert, skipping ids that already exist; returns rows actually inserted. */
  insertNotes(rows: NoteRow[]): Promise<number>;
}

export interface SyncResult {
  episodes: number;
  deleted: number;
  dailyState: boolean;
  notes: number;
}

interface ParsedSync {
  episodes: EpisodeRow[];
  deletedEpisodeIds: string[];
  dailyState: DailyStateRow | null;
  notes: NoteRow[];
}

class ValidationError extends Error {}
class TooLargeError extends Error {}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

const encoder = new TextEncoder();

/**
 * Constant-time string equality. Both sides are hashed first so neither the
 * content nor the length of the secret leaks through timing.
 */
export async function timingSafeEqual(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(a)),
    crypto.subtle.digest("SHA-256", encoder.encode(b)),
  ]);
  const va = new Uint8Array(ha);
  const vb = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < va.length; i++) diff |= va[i] ^ vb[i];
  return diff === 0;
}

/** Reads the body, aborting as soon as it exceeds [limit] bytes. */
async function readLimited(req: Request, limit: number): Promise<Uint8Array> {
  const declared = req.headers.get("content-length");
  if (declared !== null && Number(declared) > limit) throw new TooLargeError();
  if (!req.body) return new Uint8Array(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel().catch(() => {});
      throw new TooLargeError();
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.byteLength;
  }
  return out;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function fail(msg: string): never {
  throw new ValidationError(msg);
}

function str(v: unknown, path: string, allowEmpty = true): string {
  if (typeof v !== "string") fail(`${path} must be a string`);
  if (!allowEmpty && v.length === 0) fail(`${path} must not be empty`);
  return v;
}

function int(v: unknown, path: string): number {
  if (typeof v !== "number" || !Number.isSafeInteger(v)) {
    fail(`${path} must be an integer`);
  }
  return v;
}

function optionalArray(v: unknown, path: string): unknown[] {
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v)) fail(`${path} must be an array`);
  if (v.length > MAX_ITEMS) fail(`${path} has more than ${MAX_ITEMS} items`);
  return v;
}

function parseEpisode(v: unknown, path: string): EpisodeRow {
  if (!isObject(v)) fail(`${path} must be an object`);
  const id = str(v.id, `${path}.id`, false);
  const startMs = int(v.startMs, `${path}.startMs`);
  const endMs = int(v.endMs, `${path}.endMs`);
  if (endMs < startMs) fail(`${path}.endMs is before startMs`);
  const sensitivity = int(v.sensitivity, `${path}.sensitivity`);
  if (sensitivity < 0 || sensitivity > SYNC_MAX_SENSITIVITY) {
    fail(`${path}.sensitivity must be 0..${SYNC_MAX_SENSITIVITY}`);
  }
  const rawEventIds = str(v.eventIds, `${path}.eventIds`);
  let eventIds: unknown;
  try {
    eventIds = JSON.parse(rawEventIds);
  } catch {
    fail(`${path}.eventIds is not valid JSON`);
  }
  if (
    !Array.isArray(eventIds) || !eventIds.every((e) => typeof e === "string")
  ) {
    fail(`${path}.eventIds must be a JSON array of strings`);
  }
  if (v.updatedMs !== undefined && v.updatedMs !== null) {
    int(v.updatedMs, `${path}.updatedMs`);
  }
  return {
    id,
    start_ms: startMs,
    end_ms: endMs,
    kind: str(v.kind, `${path}.kind`),
    title: str(v.title, `${path}.title`),
    summary: str(v.summary, `${path}.summary`),
    event_ids: eventIds as string[],
    sensitivity,
  };
}

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

function parseDate(v: unknown, path: string): string {
  const s = str(v, path);
  const m = DATE_RE.exec(s);
  if (!m) fail(`${path} must be YYYY-MM-DD`);
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const dt = new Date(Date.UTC(y, mo - 1, d));
  if (
    dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 ||
    dt.getUTCDate() !== d
  ) {
    fail(`${path} is not a valid calendar date`);
  }
  return s;
}

function parseDailyState(v: unknown): DailyStateRow | null {
  if (v === undefined || v === null) return null;
  if (!isObject(v)) fail("dailyState must be an object or null");
  const date = parseDate(v.date, "dailyState.date");
  const state = v.state;
  if (!isObject(state)) fail("dailyState.state must be an object");
  if (state.generatedAtMs === undefined || state.generatedAtMs === null) {
    fail("dailyState.state.generatedAtMs is missing");
  }
  int(state.generatedAtMs, "dailyState.state.generatedAtMs");
  if (state.maxSensitivity === undefined || state.maxSensitivity === null) {
    fail("dailyState.state.maxSensitivity is missing");
  }
  const max = int(state.maxSensitivity, "dailyState.state.maxSensitivity");
  if (max < 0 || max > SYNC_MAX_SENSITIVITY) {
    fail(`dailyState.state.maxSensitivity must be 0..${SYNC_MAX_SENSITIVITY}`);
  }
  return { date, state };
}

function parseNote(v: unknown, path: string): NoteRow {
  if (!isObject(v)) fail(`${path} must be an object`);
  const id = str(v.id, `${path}.id`, false);
  const createdAt = str(v.createdAt, `${path}.createdAt`, false);
  const t = Date.parse(createdAt);
  if (Number.isNaN(t)) fail(`${path}.createdAt is not a valid timestamp`);
  const text = str(v.text, `${path}.text`, false);
  if (text.length > MAX_NOTE_CHARS) {
    fail(`${path}.text exceeds ${MAX_NOTE_CHARS} characters`);
  }
  return {
    id,
    // Normalized so Postgres never sees a format only JS understands.
    created_at: new Date(t).toISOString(),
    text,
    source: str(v.source, `${path}.source`),
  };
}

/** Keeps the last occurrence of each id (a single upsert can't touch a row twice). */
function dedupeById<T extends { id: string }>(rows: T[]): T[] {
  const byId = new Map<string, T>();
  for (const r of rows) {
    byId.delete(r.id);
    byId.set(r.id, r);
  }
  return [...byId.values()];
}

export function parseSyncBody(body: unknown): ParsedSync {
  if (!isObject(body)) fail("body must be a JSON object");
  const episodes = optionalArray(body.episodes, "episodes")
    .map((e, i) => parseEpisode(e, `episodes[${i}]`));
  const deletedEpisodeIds = optionalArray(
    body.deletedEpisodeIds,
    "deletedEpisodeIds",
  ).map((id, i) => str(id, `deletedEpisodeIds[${i}]`, false));
  const dailyState = parseDailyState(body.dailyState);
  const notes = optionalArray(body.notes, "notes")
    .map((n, i) => parseNote(n, `notes[${i}]`));
  return {
    episodes: dedupeById(episodes),
    deletedEpisodeIds: [...new Set(deletedEpisodeIds)],
    dailyState,
    notes: dedupeById(notes),
  };
}

export function createHandler(
  deps: { db: Db; syncToken: string | undefined },
): (req: Request) => Promise<Response> {
  const { db, syncToken } = deps;
  return async (req: Request): Promise<Response> => {
    if (req.method !== "POST") {
      return new Response(JSON.stringify({ error: "method not allowed" }), {
        status: 405,
        headers: {
          "content-type": "application/json; charset=utf-8",
          allow: "POST",
        },
      });
    }
    if (!syncToken) {
      console.error("sync: SYNC_TOKEN is not configured");
      return json(500, { error: "server not configured" });
    }
    const auth = req.headers.get("authorization") ?? "";
    const match = /^Bearer\s+(.+)$/i.exec(auth);
    if (!match || !(await timingSafeEqual(match[1].trim(), syncToken))) {
      return json(401, { error: "unauthorized" });
    }

    let parsed: ParsedSync;
    try {
      const bytes = await readLimited(req, MAX_BODY_BYTES);
      let body: unknown;
      try {
        body = JSON.parse(
          new TextDecoder("utf-8", { fatal: true }).decode(bytes),
        );
      } catch {
        fail("body is not valid JSON");
      }
      parsed = parseSyncBody(body);
    } catch (e) {
      if (e instanceof TooLargeError) {
        return json(413, { error: `body exceeds ${MAX_BODY_BYTES} bytes` });
      }
      if (e instanceof ValidationError) {
        console.warn(`sync: rejected request: ${e.message}`);
        return json(400, { error: e.message });
      }
      throw e;
    }

    try {
      const result: SyncResult = {
        episodes: 0,
        deleted: 0,
        dailyState: false,
        notes: 0,
      };
      if (parsed.deletedEpisodeIds.length > 0) {
        result.deleted = await db.deleteEpisodes(parsed.deletedEpisodeIds);
      }
      if (parsed.episodes.length > 0) {
        result.episodes = await db.upsertEpisodes(parsed.episodes);
      }
      if (parsed.dailyState) {
        await db.upsertDailyState(parsed.dailyState);
        result.dailyState = true;
      }
      if (parsed.notes.length > 0) {
        result.notes = await db.insertNotes(parsed.notes);
      }
      console.log(
        `sync: ok episodes=${result.episodes} deleted=${result.deleted} ` +
          `dailyState=${result.dailyState} notes=${result.notes}`,
      );
      return json(200, result);
    } catch (e) {
      console.error(
        `sync: database error: ${e instanceof Error ? e.message : String(e)}`,
      );
      return json(500, { error: "database error" });
    }
  };
}
