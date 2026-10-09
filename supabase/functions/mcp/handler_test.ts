import { assert, assertEquals, assertExists } from "jsr:@std/assert@1";
import { createHandler, LATEST_PROTOCOL_VERSION } from "./handler.ts";
import type { DailyStateRow, Db, EpisodeRow } from "./tools.ts";

const TOKEN = "test-token-123";

interface Calls {
  overlapping: Array<{ from: string; to: string; limit: number }>;
  search: Array<{ q: string; from: string | null; to: string | null; limit: number }>;
  notes: Array<{ text: string; source: string }>;
}

function episode(id: string, startAt: string, endAt: string, extra: Partial<EpisodeRow> = {}): EpisodeRow {
  return {
    id,
    start_ms: Date.parse(startAt),
    end_ms: String(Date.parse(endAt)), // bigint may arrive as a string
    kind: "meeting",
    title: `Episode ${id}`,
    summary: "summary",
    event_ids: ["e1", "e2"],
    sensitivity: 1,
    start_at: startAt,
    end_at: endAt,
    search: "'episode':1",
    synced_at: "2026-03-08T12:00:00Z",
    ...extra,
  };
}

function fakeDb(overrides: Partial<Db> = {}): { db: Db; calls: Calls } {
  const calls: Calls = { overlapping: [], search: [], notes: [] };
  const states: DailyStateRow[] = [
    { date: "2026-03-07", state: { generatedAtMs: 1, maxSensitivity: 1 }, updated_at: "2026-03-07T22:00:00Z" },
    { date: "2026-03-08", state: { generatedAtMs: 2, maxSensitivity: 1, recentNotes: ["hi"] }, updated_at: "2026-03-08T22:00:00Z" },
    { date: "2026-03-09", state: { generatedAtMs: 3, maxSensitivity: 3 }, updated_at: null },
  ];
  const db: Db = {
    latestDailyState: () => Promise.resolve(states[1]),
    dailyState: (date) => Promise.resolve(states.find((s) => s.date === date) ?? null),
    episodesOverlapping: (from, to, limit) => {
      calls.overlapping.push({ from: from.toISOString(), to: to.toISOString(), limit });
      return Promise.resolve([
        episode("a", "2026-03-08T04:30:00Z", "2026-03-08T05:30:00Z", { event_ids: '["x"]' }),
        episode("secret", "2026-03-08T06:00:00Z", "2026-03-08T07:00:00Z", { sensitivity: 2 }),
        episode("unlabelled", "2026-03-08T08:00:00Z", "2026-03-08T09:00:00Z", { sensitivity: null }),
      ]);
    },
    searchEpisodes: (q, from, to, limit) => {
      calls.search.push({ q, from: from?.toISOString() ?? null, to: to?.toISOString() ?? null, limit });
      return Promise.resolve([episode("s1", "2026-03-01T10:00:00Z", "2026-03-01T11:00:00Z")]);
    },
    insertNote: (text, source) => {
      calls.notes.push({ text, source });
      return Promise.resolve({ id: "note-1", created_at: "2026-03-08T12:00:00Z" });
    },
    ...overrides,
  };
  return { db, calls };
}

function post(body: unknown, token: string | null = TOKEN, raw = false): Request {
  const headers: Record<string, string> = { "Content-Type": "application/json", Accept: "application/json, text/event-stream" };
  if (token !== null) headers.Authorization = `Bearer ${token}`;
  return new Request("http://localhost/functions/v1/mcp", {
    method: "POST",
    headers,
    body: raw ? (body as string) : JSON.stringify(body),
  });
}

let nextId = 1;
function rpc(method: string, params?: unknown) {
  return { jsonrpc: "2.0", id: nextId++, method, ...(params === undefined ? {} : { params }) };
}

// deno-lint-ignore no-explicit-any
async function call(handler: (r: Request) => Promise<Response>, msg: unknown): Promise<any> {
  const res = await handler(post(msg));
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("content-type"), "application/json");
  return await res.json();
}

// deno-lint-ignore no-explicit-any
async function tool(handler: (r: Request) => Promise<Response>, name: string, args?: unknown): Promise<any> {
  const body = await call(handler, rpc("tools/call", { name, ...(args === undefined ? {} : { arguments: args }) }));
  assertExists(body.result, JSON.stringify(body));
  return body.result;
}

const handlerWith = (db: Db, timeZone = "America/New_York") => createHandler({ db, mcpToken: TOKEN, timeZone });

// --- auth & transport ------------------------------------------------------

Deno.test("auth: missing or wrong token → 401 with WWW-Authenticate", async () => {
  const h = handlerWith(fakeDb().db);
  for (const token of [null, "wrong", "", TOKEN + "x"]) {
    const res = await h(post(rpc("ping"), token));
    assertEquals(res.status, 401);
    assertEquals(res.headers.get("www-authenticate"), "Bearer");
    await res.body?.cancel();
  }
  const res = await h(new Request("http://localhost/", { method: "POST", headers: { Authorization: `Basic ${TOKEN}` }, body: "{}" }));
  assertEquals(res.status, 401);
  await res.body?.cancel();
});

Deno.test("auth: unset MCP_TOKEN or bad TIMEZONE fails closed with 500", async () => {
  for (const opts of [{ mcpToken: undefined }, { mcpToken: "" }, { mcpToken: TOKEN, timeZone: "Mars/Olympus" }]) {
    const h = createHandler({ db: fakeDb().db, ...opts });
    const res = await h(post(rpc("ping")));
    assertEquals(res.status, 500);
    await res.body?.cancel();
  }
});

Deno.test("GET and DELETE → 405", async () => {
  const h = handlerWith(fakeDb().db);
  for (const method of ["GET", "DELETE"]) {
    const res = await h(new Request("http://localhost/", { method, headers: { Authorization: `Bearer ${TOKEN}` } }));
    assertEquals(res.status, 405);
    assertEquals(res.headers.get("allow"), "POST");
  }
});

Deno.test("parse error → -32700; invalid request → -32600", async () => {
  const h = handlerWith(fakeDb().db);
  const res = await h(post("{not json", TOKEN, true));
  assertEquals(res.status, 400);
  assertEquals((await res.json()).error.code, -32700);

  const bad = await call(h, { jsonrpc: "1.0", id: 7, method: "ping" });
  assertEquals(bad.error.code, -32600);
  assertEquals(bad.id, 7);

  const empty = await h(post([]));
  assertEquals(empty.status, 400);
  assertEquals((await empty.json()).error.code, -32600);
});

Deno.test("notifications and client responses → 202 with no body", async () => {
  const h = handlerWith(fakeDb().db);
  for (const msg of [
    { jsonrpc: "2.0", method: "notifications/initialized" },
    { jsonrpc: "2.0", id: 1, result: {} },
    [{ jsonrpc: "2.0", method: "notifications/initialized" }, { jsonrpc: "2.0", method: "notifications/cancelled", params: { requestId: 1 } }],
  ]) {
    const res = await h(post(msg));
    assertEquals(res.status, 202);
    assertEquals(await res.text(), "");
  }
});

Deno.test("batch: responses for requests only, in order", async () => {
  const h = handlerWith(fakeDb().db);
  const body = await call(h, [
    { jsonrpc: "2.0", id: "a", method: "ping" },
    { jsonrpc: "2.0", method: "notifications/initialized" },
    { jsonrpc: "2.0", id: "b", method: "nope" },
    { jsonrpc: "2.0", id: "c", method: "tools/list" },
  ]);
  assert(Array.isArray(body));
  assertEquals(body.map((r: { id: string }) => r.id), ["a", "b", "c"]);
  assertEquals(body[0].result, {});
  assertEquals(body[1].error.code, -32601);
  assertEquals(body[2].result.tools.length, 4);
});

Deno.test("method routing: unknown → -32601, bad params → -32602", async () => {
  const h = handlerWith(fakeDb().db);
  assertEquals((await call(h, rpc("resources/list"))).error.code, -32601);
  assertEquals((await call(h, rpc("tools/call", ["x"]))).error.code, -32602);
  assertEquals((await call(h, rpc("tools/call", {}))).error.code, -32602);
  assertEquals((await call(h, rpc("tools/call", { name: "drop_tables" }))).error.code, -32602);
  assertEquals((await call(h, rpc("tools/call", { name: "get_day", arguments: "x" }))).error.code, -32602);
});

Deno.test("initialize negotiates protocol version", async () => {
  const h = handlerWith(fakeDb().db);
  for (const [requested, expected] of [
    ["2025-03-26", "2025-03-26"],
    ["2024-11-05", "2024-11-05"],
    ["2099-01-01", LATEST_PROTOCOL_VERSION],
    [undefined, LATEST_PROTOCOL_VERSION],
  ]) {
    const body = await call(h, rpc("initialize", { protocolVersion: requested, capabilities: {}, clientInfo: { name: "t", version: "1" } }));
    assertEquals(body.result.protocolVersion, expected);
    assertEquals(body.result.serverInfo, { name: "personal-context", version: "0.1.0" });
    assertEquals(body.result.capabilities, { tools: { listChanged: false } });
    assert(body.result.instructions.includes("privacy-filtered"));
  }
});

// --- full session ----------------------------------------------------------

Deno.test("session: initialize → initialized → tools/list → tools/call (all four tools)", async () => {
  const { db, calls } = fakeDb();
  const h = handlerWith(db);

  const init = await call(h, rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t", version: "1" } }));
  assertEquals(init.result.protocolVersion, "2025-06-18");
  assertEquals((await h(post({ jsonrpc: "2.0", method: "notifications/initialized" }))).status, 202);

  const list = await call(h, rpc("tools/list"));
  const tools = list.result.tools;
  assertEquals(tools.map((t: { name: string }) => t.name), ["get_state_now", "get_day", "search_episodes", "record_note"]);
  for (const t of tools) {
    assertEquals(t.inputSchema.type, "object");
    assertEquals(t.inputSchema.additionalProperties, false);
    assert(t.description.length > 20);
  }
  const byName = Object.fromEntries(tools.map((t: { name: string }) => [t.name, t]));
  for (const n of ["get_state_now", "get_day", "search_episodes"]) assertEquals(byName[n].annotations.readOnlyHint, true);
  assertEquals(byName.record_note.annotations.destructiveHint, false);
  assertEquals(byName.record_note.annotations.idempotentHint, false);

  // get_state_now
  const now = await tool(h, "get_state_now", {});
  assertEquals(now.isError, false);
  assertEquals(now.structuredContent.date, "2026-03-08");
  assertEquals(now.structuredContent.state.recentNotes, ["hi"]);
  assertEquals(JSON.parse(now.content[0].text), now.structuredContent);
  assertEquals(now.content[0].type, "text");

  // get_day on the New York spring-forward day: bounds are 05:00Z..04:00Z next day
  const day = await tool(h, "get_day", { date: "2026-03-08" });
  assertEquals(day.isError, false);
  assertEquals(calls.overlapping[0], { from: "2026-03-08T05:00:00.000Z", to: "2026-03-09T04:00:00.000Z", limit: 500 });
  const sc = day.structuredContent;
  assertEquals(sc.timeZone, "America/New_York");
  assertEquals(sc.state.generatedAtMs, 2);
  assertEquals(sc.episodes.length, 1, "sensitivity > 1 episodes are dropped");
  assertEquals(sc.episodes[0], {
    id: "a",
    startMs: Date.parse("2026-03-08T04:30:00Z"),
    endMs: Date.parse("2026-03-08T05:30:00Z"),
    kind: "meeting",
    title: "Episode a",
    summary: "summary",
    eventIds: ["x"],
    startAt: "2026-03-08T04:30:00Z",
    endAt: "2026-03-08T05:30:00Z",
  });

  // search_episodes with a range: from = start of from-day, to = end of to-day
  const found = await tool(h, "search_episodes", { query: " dentist ", from: "2026-03-01", to: "2026-03-08", limit: 5 });
  assertEquals(found.isError, false);
  assertEquals(calls.search[0], { q: "dentist", from: "2026-03-01T05:00:00.000Z", to: "2026-03-09T04:00:00.000Z", limit: 5 });
  assertEquals(found.structuredContent.count, 1);
  assertEquals(found.structuredContent.episodes[0].eventIds, ["e1", "e2"]);
  assertEquals("search" in found.structuredContent.episodes[0], false);
  assertEquals("sensitivity" in found.structuredContent.episodes[0], false);

  // search without range uses defaults
  await tool(h, "search_episodes", { query: "run" });
  assertEquals(calls.search[1], { q: "run", from: null, to: null, limit: 20 });

  // record_note
  const note = await tool(h, "record_note", { text: "buy milk" });
  assertEquals(note.isError, false);
  assertEquals(note.structuredContent, { id: "note-1", createdAt: "2026-03-08T12:00:00Z" });
  assertEquals(calls.notes, [{ text: "buy milk", source: "mcp" }]);
});

// --- privacy guards ----------------------------------------------------------

Deno.test("snapshots without maxSensitivity <= 1 are withheld", async () => {
  const { db } = fakeDb({
    latestDailyState: () => Promise.resolve({ date: "2026-03-09", state: { generatedAtMs: 3 }, updated_at: null }),
  });
  const h = handlerWith(db);
  const now = await tool(h, "get_state_now");
  assertEquals(now.structuredContent.state, null);
  assertExists(now.structuredContent.withheld);

  const day = await tool(h, "get_day", { date: "2026-03-09" }); // maxSensitivity 3 in fake data
  assertEquals(day.structuredContent.state, null);

  const empty = await tool(handlerWith(fakeDb({ latestDailyState: () => Promise.resolve(null) }).db), "get_state_now");
  assertEquals(empty.structuredContent, { date: null, updatedAt: null, state: null });
});

// --- validation and errors -----------------------------------------------------

Deno.test("invalid tool arguments → isError result, db untouched", async () => {
  const { db, calls } = fakeDb();
  const h = handlerWith(db);
  const bad: Array<[string, unknown]> = [
    ["get_state_now", { extra: 1 }],
    ["get_day", {}],
    ["get_day", { date: "2026-02-30" }],
    ["get_day", { date: "03/08/2026" }],
    ["search_episodes", {}],
    ["search_episodes", { query: "   " }],
    ["search_episodes", { query: "x", limit: 0 }],
    ["search_episodes", { query: "x", limit: 101 }],
    ["search_episodes", { query: "x", limit: 2.5 }],
    ["search_episodes", { query: "x", from: "2026-03-09", to: "2026-03-01" }],
    ["record_note", {}],
    ["record_note", { text: "" }],
    ["record_note", { text: "x".repeat(20_001) }],
    ["record_note", { text: "x", source: "spoofed" }],
  ];
  for (const [name, args] of bad) {
    const r = await tool(h, name, args);
    assertEquals(r.isError, true, `${name} ${JSON.stringify(args)}`);
    assert(r.content[0].text.startsWith(`Invalid arguments for ${name}`));
    assertEquals(r.structuredContent, undefined);
  }
  assertEquals(calls, { overlapping: [], search: [], notes: [] });
  const ok = await tool(h, "record_note", { text: "x".repeat(20_000) });
  assertEquals(ok.isError, false);
});

Deno.test("db failure → isError result, not a JSON-RPC error; content is not logged", async () => {
  const logged: unknown[][] = [];
  const orig = console.error;
  console.error = (...a: unknown[]) => logged.push(a);
  try {
    const { db } = fakeDb({ insertNote: () => Promise.reject(new Error("connection refused")) });
    const h = handlerWith(db);
    const body = await call(h, rpc("tools/call", { name: "record_note", arguments: { text: "private thought" } }));
    assertEquals(body.error, undefined);
    assertEquals(body.result.isError, true);
    assert(body.result.content[0].text.includes("connection refused"));
  } finally {
    console.error = orig;
  }
  assert(!JSON.stringify(logged).includes("private thought"));
});

Deno.test("MCP-Protocol-Version header is accepted leniently", async () => {
  const h = handlerWith(fakeDb().db);
  for (const v of ["2025-06-18", "1999-01-01"]) {
    const req = post(rpc("ping"));
    req.headers.set("MCP-Protocol-Version", v);
    const res = await h(req);
    assertEquals(res.status, 200);
    assertEquals((await res.json()).result, {});
  }
});
