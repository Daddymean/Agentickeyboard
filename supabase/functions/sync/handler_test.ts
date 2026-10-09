// End-to-end tests for the sync handler: full Request -> Response round trips
// through createHandler with an in-memory Db. No network or env needed.
// deno-lint-ignore no-import-prefix -- inline specifier so it resolves from any cwd
import {
  assert,
  assertEquals,
  assertFalse,
  assertMatch,
} from "jsr:@std/assert@1";
import {
  createHandler,
  type DailyStateRow,
  type Db,
  type EpisodeRow,
  MAX_BODY_BYTES,
  type NoteRow,
  timingSafeEqual,
} from "./handler.ts";

const TOKEN = "s3cret-token";
const URL_ = "http://localhost/functions/v1/sync";

class FakeDb implements Db {
  episodes = new Map<string, EpisodeRow>();
  daily = new Map<string, DailyStateRow>();
  notes = new Map<string, NoteRow>();
  calls: string[] = [];
  episodeRowsSeen: EpisodeRow[] = [];
  fail = false;

  deleteEpisodes(ids: string[]): Promise<number> {
    this.calls.push("deleteEpisodes");
    if (this.fail) return Promise.reject(new Error("boom"));
    let n = 0;
    for (const id of ids) if (this.episodes.delete(id)) n++;
    return Promise.resolve(n);
  }
  upsertEpisodes(rows: EpisodeRow[]): Promise<number> {
    this.calls.push("upsertEpisodes");
    if (this.fail) return Promise.reject(new Error("boom"));
    this.episodeRowsSeen.push(...rows);
    for (const r of rows) this.episodes.set(r.id, r);
    return Promise.resolve(rows.length);
  }
  upsertDailyState(row: DailyStateRow): Promise<void> {
    this.calls.push("upsertDailyState");
    this.daily.set(row.date, row);
    return Promise.resolve();
  }
  insertNotes(rows: NoteRow[]): Promise<number> {
    this.calls.push("insertNotes");
    let n = 0;
    for (const r of rows) {
      if (!this.notes.has(r.id)) {
        this.notes.set(r.id, r);
        n++;
      }
    }
    return Promise.resolve(n);
  }
}

function episode(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: "ep1",
    startMs: 1000,
    endMs: 2000,
    kind: "work",
    title: "Focus block",
    summary: "Deep work",
    eventIds: '["e1","e2"]',
    sensitivity: 1,
    updatedMs: 2500,
    ...over,
  };
}

function dailyState(
  over: Record<string, unknown> = {},
  stateOver: Record<string, unknown> = {},
) {
  return {
    date: "2026-10-09",
    state: {
      generatedAtMs: 1760000000000,
      maxSensitivity: 1,
      today: { sleepHours: null, steps: 1234, topApps: [], places: [] },
      activeEpisode: null,
      nextEvent: null,
      recentNotes: [],
      ...stateOver,
    },
    ...over,
  };
}

function note(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: "n1",
    createdAt: "2026-10-09T10:00:00+02:00",
    text: "remember milk",
    source: "keyboard",
    ...over,
  };
}

function post(
  body: unknown,
  opts: { token?: string | null; raw?: string } = {},
): Request {
  const headers = new Headers({ "content-type": "application/json" });
  const token = opts.token === undefined ? TOKEN : opts.token;
  if (token !== null) headers.set("authorization", `Bearer ${token}`);
  return new Request(URL_, {
    method: "POST",
    headers,
    body: opts.raw ?? JSON.stringify(body),
  });
}

function setup(...args: [syncToken?: string | undefined]) {
  // An explicit `undefined` means "unset", unlike an omitted argument.
  const syncToken = args.length === 0 ? TOKEN : args[0];
  const db = new FakeDb();
  return { db, handle: createHandler({ db, syncToken }) };
}

async function expect400(
  body: unknown,
  pattern?: RegExp,
  raw?: string,
): Promise<FakeDb> {
  const { db, handle } = setup();
  const res = await handle(post(body, { raw }));
  assertEquals(res.status, 400);
  const json = await res.json();
  assertEquals(typeof json.error, "string");
  if (pattern) assertMatch(json.error, pattern);
  assertEquals(db.calls, [], "nothing may be written on a rejected request");
  return db;
}

// ---- method / auth / config ----

Deno.test("405 for non-POST", async () => {
  const { handle, db } = setup();
  for (const method of ["GET", "PUT", "DELETE"]) {
    const res = await handle(new Request(URL_, { method }));
    assertEquals(res.status, 405);
    await res.body?.cancel();
  }
  assertEquals(db.calls, []);
});

Deno.test("500 when SYNC_TOKEN unset (fail closed)", async () => {
  for (const tok of [undefined, ""]) {
    const { handle, db } = setup(tok);
    const res = await handle(post({}, { token: "" }));
    assertEquals(res.status, 500);
    await res.body?.cancel();
    assertEquals(db.calls, []);
  }
});

Deno.test("401 for missing, malformed or wrong token", async () => {
  const { handle, db } = setup();
  for (const token of [null, "wrong", TOKEN + "x", TOKEN.slice(0, -1)]) {
    const res = await handle(post({ episodes: [episode()] }, { token }));
    assertEquals(res.status, 401);
    await res.body?.cancel();
  }
  const basic = new Request(URL_, {
    method: "POST",
    headers: { authorization: `Basic ${TOKEN}` },
    body: "{}",
  });
  assertEquals((await handle(basic)).status, 401);
  assertEquals(db.calls, []);
});

Deno.test("timingSafeEqual", async () => {
  assert(await timingSafeEqual("abc", "abc"));
  assert(await timingSafeEqual("", ""));
  assertFalse(await timingSafeEqual("abc", "abd"));
  assertFalse(await timingSafeEqual("abc", "abcd"));
  assertFalse(await timingSafeEqual("", "a"));
});

// ---- size limits ----

Deno.test("413 when body exceeds 1 MB", async () => {
  const { handle, db } = setup();
  const big = JSON.stringify({
    notes: [note({ text: "x".repeat(MAX_BODY_BYTES) })],
  });
  const res = await handle(post(null, { raw: big }));
  assertEquals(res.status, 413);
  await res.body?.cancel();
  assertEquals(db.calls, []);
});

Deno.test("413 when streamed body without content-length exceeds 1 MB", async () => {
  const { handle } = setup();
  const chunk = new Uint8Array(256 * 1024).fill(0x20);
  let sent = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(c) {
      if (sent >= 5) return c.close();
      sent++;
      c.enqueue(chunk);
    },
  });
  const req = new Request(URL_, {
    method: "POST",
    headers: { authorization: `Bearer ${TOKEN}` },
    body: stream,
  });
  assertEquals(req.headers.get("content-length"), null);
  const res = await handle(req);
  assertEquals(res.status, 413);
  await res.body?.cancel();
});

Deno.test("400 over item limits", async () => {
  const many = (f: (i: number) => unknown) =>
    Array.from({ length: 501 }, (_, i) => f(i));
  await expect400(
    { episodes: many((i) => episode({ id: `e${i}` })) },
    /episodes/,
  );
  await expect400(
    { deletedEpisodeIds: many((i) => `d${i}`) },
    /deletedEpisodeIds/,
  );
  await expect400({ notes: many((i) => note({ id: `n${i}` })) }, /notes/);
});

Deno.test("exactly 500 items is accepted", async () => {
  const { handle } = setup();
  const res = await handle(post({
    episodes: Array.from({ length: 500 }, (_, i) => episode({ id: `e${i}` })),
  }));
  assertEquals(res.status, 200);
  assertEquals((await res.json()).episodes, 500);
});

// ---- validation ----

Deno.test("400 for malformed JSON or non-object body", async () => {
  await expect400(null, /JSON/, "{not json");
  await expect400(null, /object/, "[]");
  await expect400(null, /object/, "null");
  await expect400(null, /JSON/, "");
});

Deno.test("400 for wrong container types", async () => {
  await expect400({ episodes: {} }, /episodes must be an array/);
  await expect400({ notes: "x" }, /notes must be an array/);
  await expect400({ deletedEpisodeIds: [1] }, /deletedEpisodeIds\[0\]/);
  await expect400({ deletedEpisodeIds: [""] }, /deletedEpisodeIds\[0\]/);
  await expect400({ dailyState: "today" }, /dailyState/);
});

Deno.test("400 for episode sensitivity outside 0..1 or non-integer", async () => {
  for (const s of [2, 3, -1, 0.5, "1", null, undefined]) {
    await expect400({
      episodes: [episode(), episode({ id: "ep2", sensitivity: s })],
    }, /episodes\[1\]\.sensitivity/);
  }
});

Deno.test("400 when endMs < startMs", async () => {
  await expect400({ episodes: [episode({ startMs: 5, endMs: 4 })] }, /endMs/);
});

Deno.test("400 for non-integer timestamps / missing string fields", async () => {
  await expect400({ episodes: [episode({ startMs: "1" })] }, /startMs/);
  await expect400({ episodes: [episode({ endMs: 1.5 })] }, /endMs/);
  await expect400({ episodes: [episode({ id: "" })] }, /id/);
  await expect400({ episodes: [episode({ kind: undefined })] }, /kind/);
  await expect400({ episodes: [episode({ title: 3 })] }, /title/);
  await expect400({ episodes: [episode({ summary: null })] }, /summary/);
  await expect400({ episodes: [episode({ updatedMs: "x" })] }, /updatedMs/);
  await expect400({ episodes: ["nope"] }, /episodes\[0\]/);
});

Deno.test("400 when eventIds is not a JSON array string of strings", async () => {
  for (
    const ev of [
      '{"a":1}',
      "not json",
      "[1,2]",
      '["a",null]',
      ["e1"],
      5,
      '"e1"',
    ]
  ) {
    await expect400({ episodes: [episode({ eventIds: ev })] }, /eventIds/);
  }
});

Deno.test("400 for invalid dailyState.date", async () => {
  for (
    const date of [
      "2026-1-09",
      "20261009",
      "2026-10-09T00:00:00Z",
      "2026-02-30",
      "2026-13-01",
      20261009,
      undefined,
    ]
  ) {
    await expect400({ dailyState: dailyState({ date }) }, /dailyState\.date/);
  }
});

Deno.test("400 for invalid dailyState.state", async () => {
  await expect400(
    { dailyState: dailyState({ state: null }) },
    /state must be an object/,
  );
  await expect400(
    { dailyState: dailyState({ state: [] }) },
    /state must be an object/,
  );
  await expect400(
    { dailyState: dailyState({}, { maxSensitivity: undefined }) },
    /maxSensitivity is missing/,
  );
  await expect400(
    { dailyState: dailyState({}, { maxSensitivity: null }) },
    /maxSensitivity is missing/,
  );
  for (const m of [2, 3, -1, 0.5, "1"]) {
    await expect400(
      { dailyState: dailyState({}, { maxSensitivity: m }) },
      /maxSensitivity/,
    );
  }
  await expect400(
    { dailyState: dailyState({}, { generatedAtMs: undefined }) },
    /generatedAtMs is missing/,
  );
  await expect400(
    { dailyState: dailyState({}, { generatedAtMs: "now" }) },
    /generatedAtMs/,
  );
});

Deno.test("400 for invalid notes", async () => {
  await expect400({ notes: [note({ text: "" })] }, /text must not be empty/);
  await expect400(
    { notes: [note({ text: "x".repeat(20_001) })] },
    /text exceeds/,
  );
  await expect400({ notes: [note({ text: 5 })] }, /text/);
  for (const createdAt of ["yesterday", "", 123, undefined]) {
    await expect400({ notes: [note({ createdAt })] }, /createdAt/);
  }
  await expect400({ notes: [note({ id: "" })] }, /notes\[0\]\.id/);
  await expect400({ notes: [note({ source: undefined })] }, /source/);
});

Deno.test("one bad item rejects the whole request", async () => {
  const db = await expect400({
    deletedEpisodeIds: ["old"],
    episodes: [episode()],
    dailyState: dailyState(),
    notes: [note(), note({ id: "n2", text: "" })],
  });
  assertEquals(db.episodes.size, 0);
});

// ---- happy paths ----

Deno.test("empty object is a no-op 200", async () => {
  const { handle, db } = setup();
  const res = await handle(post({}));
  assertEquals(res.status, 200);
  assertEquals(await res.json(), {
    episodes: 0,
    deleted: 0,
    dailyState: false,
    notes: 0,
  });
  assertEquals(db.calls, []);
});

Deno.test("explicit nulls/empties are accepted; unknown keys ignored", async () => {
  const { handle } = setup();
  const res = await handle(post({
    episodes: null,
    deletedEpisodeIds: [],
    dailyState: null,
    notes: [],
    somethingNew: { x: 1 },
  }));
  assertEquals(res.status, 200);
  assertEquals(await res.json(), {
    episodes: 0,
    deleted: 0,
    dailyState: false,
    notes: 0,
  });
});

Deno.test("full sync writes everything in order and returns counts", async () => {
  const { handle, db } = setup();
  db.episodes.set("gone", { ...(await rowOf(episode({ id: "gone" }))) });
  db.notes.set("n-existing", {
    id: "n-existing",
    created_at: "2026-01-01T00:00:00.000Z",
    text: "old",
    source: "x",
  });
  const res = await handle(post({
    episodes: [
      episode({ extraKey: "ignored" }),
      episode({ id: "ep2", sensitivity: 0, eventIds: "[]" }),
    ],
    deletedEpisodeIds: ["gone", "never-existed"],
    dailyState: dailyState(),
    notes: [note(), note({ id: "n-existing", text: "new text" })],
  }));
  assertEquals(res.status, 200);
  assertEquals(await res.json(), {
    episodes: 2,
    deleted: 1,
    dailyState: true,
    notes: 1,
  });
  assertEquals(db.calls, [
    "deleteEpisodes",
    "upsertEpisodes",
    "upsertDailyState",
    "insertNotes",
  ]);

  assertEquals(db.episodes.get("ep1"), {
    id: "ep1",
    start_ms: 1000,
    end_ms: 2000,
    kind: "work",
    title: "Focus block",
    summary: "Deep work",
    event_ids: ["e1", "e2"],
    sensitivity: 1,
  });
  assertEquals(db.episodes.get("ep2")?.event_ids, []);
  assertFalse(db.episodes.has("gone"));

  const ds = db.daily.get("2026-10-09");
  assertEquals(ds?.state.maxSensitivity, 1);
  assertEquals((ds?.state.today as Record<string, unknown>).steps, 1234);

  assertEquals(db.notes.get("n1")?.created_at, "2026-10-09T08:00:00.000Z");
  assertEquals(
    db.notes.get("n-existing")?.text,
    "old",
    "existing notes are not overwritten",
  );
});

Deno.test("generated columns, synced_at and client-only fields are never sent", async () => {
  const { handle, db } = setup();
  await (await handle(post({
    episodes: [
      episode({ start_at: "x", end_at: "y", search: "z", synced_at: "w" }),
    ],
  }))).body?.cancel();
  const keys = Object.keys(db.episodeRowsSeen[0]).sort();
  assertEquals(keys, [
    "end_ms",
    "event_ids",
    "id",
    "kind",
    "sensitivity",
    "start_ms",
    "summary",
    "title",
  ]);
});

Deno.test("deletions are applied before upserts (same id in both ends up present)", async () => {
  const { handle, db } = setup();
  const res = await handle(post({
    episodes: [episode({ id: "x", title: "revived" })],
    deletedEpisodeIds: ["x"],
  }));
  assertEquals(res.status, 200);
  assertEquals(db.calls, ["deleteEpisodes", "upsertEpisodes"]);
  assertEquals(db.episodes.get("x")?.title, "revived");
});

Deno.test("duplicate ids within one request are collapsed (last wins)", async () => {
  const { handle, db } = setup();
  const res = await handle(post({
    episodes: [episode({ title: "first" }), episode({ title: "second" })],
    deletedEpisodeIds: ["a", "a"],
    notes: [note(), note()],
  }));
  assertEquals(await res.json(), {
    episodes: 1,
    deleted: 0,
    dailyState: false,
    notes: 1,
  });
  assertEquals(db.episodes.get("ep1")?.title, "second");
});

Deno.test("dailyState with maxSensitivity 0 is accepted", async () => {
  const { handle, db } = setup();
  const res = await handle(
    post({ dailyState: dailyState({}, { maxSensitivity: 0 }) }),
  );
  assertEquals(res.status, 200);
  assertEquals(db.calls, ["upsertDailyState"]);
});

Deno.test("note of exactly 20000 chars is accepted", async () => {
  const { handle } = setup();
  const res = await handle(
    post({ notes: [note({ text: "y".repeat(20_000) })] }),
  );
  assertEquals(res.status, 200);
  assertEquals((await res.json()).notes, 1);
});

Deno.test("database failure yields 500 without leaking details", async () => {
  const { handle, db } = setup();
  db.fail = true;
  const res = await handle(post({ episodes: [episode()] }));
  assertEquals(res.status, 500);
  assertEquals(await res.json(), { error: "database error" });
});

// Builds the stored row the handler would produce, via a throwaway handler.
async function rowOf(ep: Record<string, unknown>): Promise<EpisodeRow> {
  const { handle, db } = setup();
  await (await handle(post({ episodes: [ep] }))).body?.cancel();
  return db.episodes.get(ep.id as string)!;
}
