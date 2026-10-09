// MCP tool definitions and execution against an abstract Db.

import { dayBounds, parseDate } from "./timezone.ts";

/** Highest sensitivity allowed off the device (`Sensitivity.SYNC_MAX` in :core). */
export const SYNC_MAX_SENSITIVITY = 1;

/** Row of `episodes` as returned by PostgREST (snake_case). */
export interface EpisodeRow {
  id: string;
  start_ms: number | string;
  end_ms: number | string;
  kind: string;
  title: string;
  summary: string;
  event_ids: unknown;
  sensitivity?: number | null;
  start_at: string;
  end_at: string;
  [extra: string]: unknown;
}

/** Row of `daily_state`. */
export interface DailyStateRow {
  date: string;
  state: unknown;
  updated_at?: string | null;
}

/** Data access used by the tools. `db.ts` implements it with supabase-js. */
export interface Db {
  /** Latest `daily_state` row by `date` desc, or null when the table is empty. */
  latestDailyState(): Promise<DailyStateRow | null>;
  /** `daily_state` row for `date`, or null. */
  dailyState(date: string): Promise<DailyStateRow | null>;
  /**
   * Episodes overlapping `[from, to)` (start_at < to and end_at > from, or
   * zero-length starting in range), with sensitivity <= 1,
   * ordered by start_at, at most `limit`.
   */
  episodesOverlapping(from: Date, to: Date, limit: number): Promise<EpisodeRow[]>;
  /** Postgres RPC `search_episodes(q, from_ts, to_ts, lim)`. */
  searchEpisodes(
    q: string,
    from: Date | null,
    to: Date | null,
    limit: number,
  ): Promise<EpisodeRow[]>;
  /** Inserts into `notes(text, source)`; returns the new row's id/created_at. */
  insertNote(
    text: string,
    source: string,
  ): Promise<{ id: string; created_at: string }>;
}

export interface ToolContext {
  db: Db;
  timeZone: string;
}

/** Episode as returned to MCP clients. */
export interface EpisodeOut {
  id: string;
  startMs: number;
  endMs: number;
  kind: string;
  title: string;
  summary: string;
  eventIds: string[];
  startAt: string;
  endAt: string;
}

/** Thrown for bad tool arguments; reported to the client as an `isError` result. */
export class ToolInputError extends Error {}

const MAX_DAY_EPISODES = 500;
const NOTE_MAX_CHARS = 20_000;
const SEARCH_DEFAULT_LIMIT = 20;
const SEARCH_MAX_LIMIT = 100;

const DATE_SCHEMA = {
  type: "string",
  pattern: "^\\d{4}-\\d{2}-\\d{2}$",
};

export const TOOLS = [
  {
    name: "get_state_now",
    title: "Current personal state",
    description:
      "Returns the user's most recent daily snapshot: today's sleep, steps, top apps, places, " +
      "the active episode, the next calendar event and recent notes, as last synced from their " +
      "phone. Use this first for questions about what the user is doing or how their day is " +
      "going. Returns {date, updatedAt, state}; state is null if nothing has been synced.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
    annotations: {
      title: "Current personal state",
      readOnlyHint: true,
      openWorldHint: false,
    },
  },
  {
    name: "get_day",
    title: "One day of personal context",
    description:
      "Returns everything known about one local calendar day: that day's snapshot (or null) and " +
      "all episodes (distilled spans of activity, e.g. a meeting, a commute, a workout) that " +
      "overlap it, oldest first. The day is interpreted in the user's time zone (returned as " +
      "timeZone). Returns {date, timeZone, state, episodes}.",
    inputSchema: {
      type: "object",
      properties: {
        date: {
          ...DATE_SCHEMA,
          description: "Local calendar date, YYYY-MM-DD (e.g. \"2026-03-29\").",
        },
      },
      required: ["date"],
      additionalProperties: false,
    },
    annotations: {
      title: "One day of personal context",
      readOnlyHint: true,
      openWorldHint: false,
    },
  },
  {
    name: "search_episodes",
    title: "Search episodes",
    description:
      "Full-text search over the user's episodes (titles and summaries), optionally limited to a " +
      "local date range (inclusive on both ends). Use for questions like \"when did I last go " +
      "running\" or \"meetings about the budget in March\". Returns {query, from, to, count, " +
      "episodes} ordered by relevance.",
    inputSchema: {
      type: "object",
      properties: {
        query: {
          type: "string",
          minLength: 1,
          maxLength: 500,
          description: "Search words, e.g. \"dentist\" or \"standup budget\".",
        },
        from: {
          ...DATE_SCHEMA,
          description: "Optional first local date to include, YYYY-MM-DD.",
        },
        to: {
          ...DATE_SCHEMA,
          description: "Optional last local date to include, YYYY-MM-DD.",
        },
        limit: {
          type: "integer",
          minimum: 1,
          maximum: SEARCH_MAX_LIMIT,
          default: SEARCH_DEFAULT_LIMIT,
          description: `Maximum results, 1..${SEARCH_MAX_LIMIT} (default ${SEARCH_DEFAULT_LIMIT}).`,
        },
      },
      required: ["query"],
      additionalProperties: false,
    },
    annotations: {
      title: "Search episodes",
      readOnlyHint: true,
      openWorldHint: false,
    },
  },
  {
    name: "record_note",
    title: "Record a note",
    description:
      "Saves a short note for the user (e.g. a reminder or something they asked you to " +
      "remember). Notes are stored in the cloud only; they are not sent back to the phone. " +
      "Each call creates a new note. Returns {id, createdAt}.",
    inputSchema: {
      type: "object",
      properties: {
        text: {
          type: "string",
          minLength: 1,
          maxLength: NOTE_MAX_CHARS,
          description: `Note text, 1..${NOTE_MAX_CHARS} characters.`,
        },
      },
      required: ["text"],
      additionalProperties: false,
    },
    annotations: {
      title: "Record a note",
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: false,
      openWorldHint: false,
    },
  },
] as const;

export type ToolName = typeof TOOLS[number]["name"];

export function isToolName(name: string): name is ToolName {
  return TOOLS.some((t) => t.name === name);
}

// ---------------------------------------------------------------------------
// Argument validation

type Args = Record<string, unknown>;

function checkKeys(args: Args, allowed: string[]): void {
  for (const k of Object.keys(args)) {
    if (!allowed.includes(k)) {
      throw new ToolInputError(
        `Unknown argument "${k}". Allowed: ${allowed.join(", ") || "(none)"}.`,
      );
    }
  }
}

function requireDate(args: Args, key: string, required: boolean): string | undefined {
  const v = args[key];
  if (v === undefined || v === null) {
    if (required) throw new ToolInputError(`"${key}" is required (YYYY-MM-DD).`);
    return undefined;
  }
  if (typeof v !== "string" || parseDate(v) === null) {
    throw new ToolInputError(
      `"${key}" must be a valid calendar date in YYYY-MM-DD form, e.g. "2026-03-29".`,
    );
  }
  return v;
}

// ---------------------------------------------------------------------------
// Row mapping and privacy guards

function toNumber(v: unknown): number {
  return typeof v === "number" ? v : Number(v);
}

function toEventIds(v: unknown): string[] {
  let x = v;
  if (typeof x === "string") {
    try {
      x = JSON.parse(x);
    } catch {
      return [];
    }
  }
  return Array.isArray(x) ? x.map(String) : [];
}

/**
 * Defense in depth: the sync job should never upload rows above SYNC_MAX.
 * Fails closed: a row without a numeric sensitivity is dropped.
 */
function isSyncableRow(row: EpisodeRow): boolean {
  const s = Number(row.sensitivity);
  return row.sensitivity !== null && row.sensitivity !== undefined &&
    Number.isFinite(s) && s <= SYNC_MAX_SENSITIVITY;
}

export function mapEpisode(row: EpisodeRow): EpisodeOut {
  return {
    id: row.id,
    startMs: toNumber(row.start_ms),
    endMs: toNumber(row.end_ms),
    kind: row.kind,
    title: row.title,
    summary: row.summary,
    eventIds: toEventIds(row.event_ids),
    startAt: row.start_at,
    endAt: row.end_at,
  };
}

function mapEpisodes(rows: EpisodeRow[]): EpisodeOut[] {
  return rows.filter(isSyncableRow).map(mapEpisode);
}

/**
 * Returns the snapshot if it declares `maxSensitivity` ≤ SYNC_MAX (see
 * docs/context-handoff.md, "Supabase / MCP"), otherwise null.
 */
export function syncableState(state: unknown): Record<string, unknown> | null {
  if (state === null || typeof state !== "object" || Array.isArray(state)) return null;
  const s = state as Record<string, unknown>;
  const max = s.maxSensitivity;
  if (typeof max !== "number" || max > SYNC_MAX_SENSITIVITY) return null;
  return s;
}

// ---------------------------------------------------------------------------
// Execution

/** Runs a tool. Throws ToolInputError for bad arguments, other errors for failures. */
export async function executeTool(
  name: ToolName,
  args: Args,
  ctx: ToolContext,
): Promise<Record<string, unknown>> {
  switch (name) {
    case "get_state_now": {
      checkKeys(args, []);
      const row = await ctx.db.latestDailyState();
      if (!row) return { date: null, updatedAt: null, state: null };
      const state = syncableState(row.state);
      return {
        date: row.date,
        updatedAt: row.updated_at ?? null,
        state,
        ...(state === null ? { withheld: "snapshot is not marked syncable" } : {}),
      };
    }

    case "get_day": {
      checkKeys(args, ["date"]);
      const date = requireDate(args, "date", true)!;
      const { start, end } = dayBounds(date, ctx.timeZone);
      const [row, episodes] = await Promise.all([
        ctx.db.dailyState(date),
        ctx.db.episodesOverlapping(start, end, MAX_DAY_EPISODES),
      ]);
      return {
        date,
        timeZone: ctx.timeZone,
        state: row ? syncableState(row.state) : null,
        episodes: mapEpisodes(episodes),
      };
    }

    case "search_episodes": {
      checkKeys(args, ["query", "from", "to", "limit"]);
      const query = args.query;
      if (typeof query !== "string" || query.trim().length === 0) {
        throw new ToolInputError(`"query" must be a non-empty string.`);
      }
      if (query.length > 500) {
        throw new ToolInputError(`"query" must be at most 500 characters.`);
      }
      const from = requireDate(args, "from", false);
      const to = requireDate(args, "to", false);
      if (from && to && from > to) {
        throw new ToolInputError(`"from" (${from}) must not be after "to" (${to}).`);
      }
      let limit = SEARCH_DEFAULT_LIMIT;
      if (args.limit !== undefined && args.limit !== null) {
        const l = args.limit;
        if (
          typeof l !== "number" || !Number.isInteger(l) || l < 1 || l > SEARCH_MAX_LIMIT
        ) {
          throw new ToolInputError(
            `"limit" must be an integer from 1 to ${SEARCH_MAX_LIMIT}.`,
          );
        }
        limit = l;
      }
      const fromTs = from ? dayBounds(from, ctx.timeZone).start : null;
      const toTs = to ? dayBounds(to, ctx.timeZone).end : null;
      const rows = await ctx.db.searchEpisodes(query.trim(), fromTs, toTs, limit);
      const episodes = mapEpisodes(rows);
      return {
        query: query.trim(),
        from: from ?? null,
        to: to ?? null,
        count: episodes.length,
        episodes,
      };
    }

    case "record_note": {
      checkKeys(args, ["text"]);
      const text = args.text;
      if (typeof text !== "string" || text.trim().length === 0) {
        throw new ToolInputError(`"text" must be a non-empty string.`);
      }
      if (text.length > NOTE_MAX_CHARS) {
        throw new ToolInputError(
          `"text" must be at most ${NOTE_MAX_CHARS} characters (got ${text.length}).`,
        );
      }
      const row = await ctx.db.insertNote(text, "mcp");
      return { id: row.id, createdAt: row.created_at };
    }
  }
}
