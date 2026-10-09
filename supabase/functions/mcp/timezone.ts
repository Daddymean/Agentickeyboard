// Day-boundary math for an IANA time zone, using only Intl (no Temporal, which
// the Supabase Edge Runtime does not guarantee).

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAY_MS = 86_400_000;

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let f = formatters.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    formatters.set(timeZone, f);
  }
  return f;
}

/** True when `timeZone` is an IANA zone name this runtime knows. */
export function isValidTimeZone(timeZone: string): boolean {
  try {
    formatter(timeZone);
    return true;
  } catch {
    return false;
  }
}

interface LocalParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function localParts(epochMs: number, timeZone: string): LocalParts {
  const out: Record<string, number> = {};
  for (const p of formatter(timeZone).formatToParts(new Date(epochMs))) {
    if (p.type !== "literal") out[p.type] = Number(p.value);
  }
  return {
    year: out.year,
    month: out.month,
    day: out.day,
    hour: out.hour,
    minute: out.minute,
    second: out.second,
  };
}

/** Offset of local wall time from UTC at `epochMs`, in ms (+02:00 → 7_200_000). */
function offsetAt(epochMs: number, timeZone: string): number {
  const t = Math.floor(epochMs / 1000) * 1000;
  const p = localParts(t, timeZone);
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - t;
}

/**
 * Parses a strict "YYYY-MM-DD" calendar date. Returns it as UTC-midnight epoch
 * ms (a pure calendar value), or null when malformed or nonexistent.
 */
export function parseDate(date: string): number | null {
  const m = DATE_RE.exec(date);
  if (!m) return null;
  const y = Number(m[1]), mo = Number(m[2]), d = Number(m[3]);
  const t = Date.UTC(y, mo - 1, d);
  const back = new Date(t);
  if (
    back.getUTCFullYear() !== y || back.getUTCMonth() !== mo - 1 ||
    back.getUTCDate() !== d
  ) return null;
  return t;
}

/** Adds `days` to a "YYYY-MM-DD" date. */
export function addDays(date: string, days: number): string {
  const t = parseDate(date);
  if (t === null) throw new RangeError(`invalid date: ${date}`);
  return new Date(t + days * DAY_MS).toISOString().slice(0, 10);
}

/** The local "YYYY-MM-DD" at `epochMs` in `timeZone`. */
export function localDate(epochMs: number, timeZone: string): string {
  const p = localParts(epochMs, timeZone);
  const pad = (n: number, w: number) => String(n).padStart(w, "0");
  return `${pad(p.year, 4)}-${pad(p.month, 2)}-${pad(p.day, 2)}`;
}

/**
 * The first instant (epoch ms) of local calendar day `date` in `timeZone`.
 *
 * Handles DST: if local midnight is skipped (a gap at 00:00), the day starts
 * at the transition; if midnight occurs twice, the earlier instant wins.
 * Each candidate is `wall - offset` for an offset in effect near the day; the
 * earliest candidate whose local date is `date` is the day's first instant.
 */
export function startOfDay(date: string, timeZone: string): number {
  const wall = parseDate(date);
  if (wall === null) throw new RangeError(`invalid date: ${date}`);
  const offsets = new Set([
    offsetAt(wall - DAY_MS, timeZone),
    offsetAt(wall, timeZone),
    offsetAt(wall + DAY_MS, timeZone),
  ]);
  let best: number | null = null;
  for (const o of offsets) {
    const t = wall - o;
    if (localDate(t, timeZone) === date && (best === null || t < best)) {
      best = t;
    }
  }
  if (best !== null) return best;
  // Not reachable for real zones; a minute scan keeps it total anyway.
  for (let t = wall - 15 * 3_600_000; t < wall + 15 * 3_600_000; t += 60_000) {
    if (localDate(t, timeZone) === date) return t;
  }
  throw new RangeError(`cannot resolve start of ${date} in ${timeZone}`);
}

/** UTC bounds `[start, end)` of local calendar day `date` in `timeZone`. */
export function dayBounds(
  date: string,
  timeZone: string,
): { start: Date; end: Date } {
  return {
    start: new Date(startOfDay(date, timeZone)),
    end: new Date(startOfDay(addDays(date, 1), timeZone)),
  };
}
