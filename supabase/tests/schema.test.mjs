// Schema tests for supabase/migrations, run against an in-process Postgres
// (PGlite). Usage: `npm ci && npm test` in this directory.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { PGlite } from '@electric-sql/pglite';

const here = dirname(fileURLToPath(import.meta.url));
const migrationsDir = join(here, '..', 'migrations');

/** @type {PGlite} */
let db;

const iso = (s) => Date.parse(s);

before(async () => {
  db = new PGlite();
  await db.exec(await readFile(join(here, 'roles.sql'), 'utf8'));
  const files = (await readdir(migrationsDir)).filter((f) => f.endsWith('.sql')).sort();
  assert.ok(files.length > 0, 'no migrations found');
  for (const f of files) {
    await db.exec(await readFile(join(migrationsDir, f), 'utf8'));
  }
});

after(async () => {
  await db?.close();
});

async function insertEpisode(e) {
  await db.query(
    `insert into public.episodes (id, start_ms, end_ms, kind, title, summary, event_ids, sensitivity)
     values ($1, $2, $3, $4, $5, $6, $7::jsonb, $8)`,
    [e.id, e.start, e.end, e.kind ?? 'meeting', e.title, e.summary, JSON.stringify(e.eventIds ?? []), e.sensitivity ?? 0],
  );
}

/** Runs fn as `role`, always resetting afterwards. */
async function asRole(role, fn) {
  await db.exec(`set role ${role}`);
  try {
    return await fn();
  } finally {
    await db.exec('reset role');
  }
}

test('tables and columns exist, generated columns are flagged', async () => {
  const { rows } = await db.query(
    `select table_name, column_name, data_type, is_generated
       from information_schema.columns
      where table_schema = 'public' and table_name in ('episodes', 'daily_state', 'notes')
      order by table_name, ordinal_position`,
  );
  const cols = Object.fromEntries(rows.map((r) => [`${r.table_name}.${r.column_name}`, r]));
  const expect = {
    'episodes.id': 'text',
    'episodes.start_ms': 'bigint',
    'episodes.end_ms': 'bigint',
    'episodes.kind': 'text',
    'episodes.title': 'text',
    'episodes.summary': 'text',
    'episodes.event_ids': 'jsonb',
    'episodes.sensitivity': 'smallint',
    'episodes.start_at': 'timestamp with time zone',
    'episodes.end_at': 'timestamp with time zone',
    'episodes.search': 'tsvector',
    'episodes.synced_at': 'timestamp with time zone',
    'daily_state.date': 'date',
    'daily_state.state': 'jsonb',
    'daily_state.updated_at': 'timestamp with time zone',
    'notes.id': 'text',
    'notes.created_at': 'timestamp with time zone',
    'notes.text': 'text',
    'notes.source': 'text',
  };
  assert.deepEqual(Object.keys(cols).sort(), Object.keys(expect).sort());
  for (const [k, type] of Object.entries(expect)) {
    assert.equal(cols[k].data_type, type, k);
  }
  for (const k of ['episodes.start_at', 'episodes.end_at', 'episodes.search']) {
    assert.equal(cols[k].is_generated, 'ALWAYS', k);
  }
});

test('RLS is enabled with no policies on every table', async () => {
  const { rows } = await db.query(
    `select c.relname, c.relrowsecurity,
            (select count(*)::int from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname) as policies
       from pg_class c join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relname in ('episodes', 'daily_state', 'notes')
      order by c.relname`,
  );
  assert.equal(rows.length, 3);
  for (const r of rows) {
    assert.equal(r.relrowsecurity, true, r.relname);
    assert.equal(r.policies, 0, r.relname);
  }
});

test('episode inserts compute generated columns and enforce checks', async () => {
  await insertEpisode({
    id: 'ep-standup',
    start: iso('2026-10-01T09:00:00Z'),
    end: iso('2026-10-01T09:15:00Z'),
    title: 'Daily standup',
    summary: 'standup with the team about the sprint',
    eventIds: ['a', 'b'],
  });
  await insertEpisode({
    id: 'ep-lunch',
    start: iso('2026-10-02T12:00:00Z'),
    end: iso('2026-10-02T13:00:00Z'),
    kind: 'meal',
    title: 'Lunch',
    summary: 'quick standup recap over lunch',
    sensitivity: 1,
  });
  await insertEpisode({
    id: 'ep-gym',
    start: iso('2026-10-03T18:00:00Z'),
    end: iso('2026-10-03T19:00:00Z'),
    kind: 'exercise',
    title: 'Gym',
    summary: 'leg day',
  });

  const { rows } = await db.query(
    `select start_at, end_at, search::text as search, event_ids, synced_at
       from public.episodes where id = 'ep-standup'`,
  );
  const [r] = rows;
  assert.equal(r.start_at.toISOString(), '2026-10-01T09:00:00.000Z');
  assert.equal(r.end_at.toISOString(), '2026-10-01T09:15:00.000Z');
  assert.match(r.search, /'standup':2,3/);
  assert.match(r.search, /'sprint'/);
  assert.deepEqual(r.event_ids, ['a', 'b']);
  assert.ok(r.synced_at instanceof Date);

  // Millisecond precision survives the ms -> timestamptz conversion.
  await insertEpisode({ id: 'ep-ms', start: 1700000000123, end: 1700000000456, title: 'x', summary: 'y' });
  const ms = await db.query(`select start_at, end_at from public.episodes where id = 'ep-ms'`);
  assert.equal(ms.rows[0].start_at.getTime(), 1700000000123);
  assert.equal(ms.rows[0].end_at.getTime(), 1700000000456);
  await db.query(`delete from public.episodes where id = 'ep-ms'`);

  // event_ids defaults to an empty array.
  await db.query(
    `insert into public.episodes (id, start_ms, end_ms, kind, title, summary, sensitivity)
     values ('ep-default', 0, 0, 'k', 't', 's', 0)`,
  );
  const def = await db.query(`select event_ids from public.episodes where id = 'ep-default'`);
  assert.deepEqual(def.rows[0].event_ids, []);
  await db.query(`delete from public.episodes where id = 'ep-default'`);

  // Sensitivity 2 (private) and negative values are rejected.
  for (const sensitivity of [2, -1]) {
    await assert.rejects(
      insertEpisode({ id: `ep-bad-${sensitivity}`, start: 0, end: 1, title: 't', summary: 's', sensitivity }),
      /check constraint/,
    );
  }
  // end before start is rejected.
  await assert.rejects(
    insertEpisode({ id: 'ep-backwards', start: 10, end: 5, title: 't', summary: 's' }),
    /episodes_end_after_start/,
  );
  // Generated columns cannot be written.
  await assert.rejects(
    db.query(
      `insert into public.episodes (id, start_ms, end_ms, kind, title, summary, sensitivity, start_at)
       values ('ep-gen', 0, 0, 'k', 't', 's', 0, now())`,
    ),
    /non-DEFAULT value into column "start_at"/,
  );
  // Upsert recomputes generated columns.
  await db.query(
    `insert into public.episodes (id, start_ms, end_ms, kind, title, summary, sensitivity)
     values ('ep-gym', $1, $2, 'exercise', 'Gym', 'arm day', 0)
     on conflict (id) do update set start_ms = excluded.start_ms, end_ms = excluded.end_ms,
       summary = excluded.summary, synced_at = now()`,
    [iso('2026-10-03T18:00:00Z'), iso('2026-10-03T19:30:00Z')],
  );
  const up = await db.query(`select end_at, search::text as search from public.episodes where id = 'ep-gym'`);
  assert.equal(up.rows[0].end_at.toISOString(), '2026-10-03T19:30:00.000Z');
  assert.match(up.rows[0].search, /'arm'/);
  assert.doesNotMatch(up.rows[0].search, /'leg'/);
});

test('daily_state requires generatedAtMs and maxSensitivity <= 1', async () => {
  const ok = { generatedAtMs: 1, maxSensitivity: 1, today: null };
  await db.query(`insert into public.daily_state (date, state) values ('2026-10-01', $1::jsonb)`, [JSON.stringify(ok)]);
  await db.query(`insert into public.daily_state (date, state) values ('2026-10-02', $1::jsonb)`, [
    JSON.stringify({ ...ok, maxSensitivity: 0 }),
  ]);
  const { rows } = await db.query(`select state, updated_at from public.daily_state where date = '2026-10-01'`);
  assert.deepEqual(rows[0].state, ok);
  assert.ok(rows[0].updated_at instanceof Date);

  const bad = [
    { generatedAtMs: 1, maxSensitivity: 2 },
    { generatedAtMs: 1 },
    { maxSensitivity: 0 },
    {},
  ];
  for (const state of bad) {
    await assert.rejects(
      db.query(`insert into public.daily_state (date, state) values ('2026-10-09', $1::jsonb)`, [JSON.stringify(state)]),
      /daily_state_syncable/,
      JSON.stringify(state),
    );
  }
  // Upserting an existing date to a private snapshot is rejected too.
  await assert.rejects(
    db.query(`update public.daily_state set state = $1::jsonb where date = '2026-10-01'`, [
      JSON.stringify({ generatedAtMs: 2, maxSensitivity: 2 }),
    ]),
    /daily_state_syncable/,
  );
});

test('notes get default id, created_at and source; text length is bounded', async () => {
  const { rows } = await db.query(
    `insert into public.notes (text) values ('remember the milk') returning id, created_at, source`,
  );
  const [n] = rows;
  assert.match(n.id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assert.ok(n.created_at instanceof Date);
  assert.equal(n.source, 'mcp');

  await assert.rejects(db.query(`insert into public.notes (text) values ('')`), /check constraint/);
  await assert.rejects(db.query(`insert into public.notes (text) values ($1)`, ['x'.repeat(20001)]), /check constraint/);
  await db.query(`insert into public.notes (text, source) values ($1, 'test')`, ['x'.repeat(20000)]);
});

test('search_episodes ranks matches and applies the time window', async () => {
  const ids = async (sql, params = []) => (await db.query(sql, params)).rows.map((r) => r.id);

  // 'standup' appears twice in ep-standup, once in ep-lunch; ep-gym never.
  assert.deepEqual(await ids(`select id from public.search_episodes('standup')`), ['ep-standup', 'ep-lunch']);
  assert.deepEqual(await ids(`select id from public.search_episodes('STANDUP')`), ['ep-standup', 'ep-lunch']);
  assert.deepEqual(await ids(`select id from public.search_episodes('standup -lunch')`), ['ep-standup']);
  assert.deepEqual(await ids(`select id from public.search_episodes('"sprint planning"')`), []);

  // from_ts: episode must end after it (strict).
  assert.deepEqual(
    await ids(`select id from public.search_episodes('standup', from_ts => '2026-10-02T00:00:00Z')`),
    ['ep-lunch'],
  );
  assert.deepEqual(
    await ids(`select id from public.search_episodes('standup', from_ts => '2026-10-01T09:15:00Z')`),
    ['ep-lunch'],
  );
  assert.deepEqual(
    await ids(`select id from public.search_episodes('standup', from_ts => '2026-10-01T09:14:00Z')`),
    ['ep-standup', 'ep-lunch'],
  );
  // to_ts: episode must start before it (strict).
  assert.deepEqual(
    await ids(`select id from public.search_episodes('standup', to_ts => '2026-10-01T09:10:00Z')`),
    ['ep-standup'],
  );
  assert.deepEqual(
    await ids(`select id from public.search_episodes('standup', to_ts => '2026-10-01T09:00:00Z')`),
    [],
  );
  // Both bounds: overlap with a window inside the lunch episode.
  assert.deepEqual(
    await ids(`select id from public.search_episodes('standup', '2026-10-02T12:30:00Z', '2026-10-02T12:31:00Z')`),
    ['ep-lunch'],
  );

  // Equal rank ties break on start_at desc.
  await insertEpisode({ id: 'ep-tie-old', start: iso('2026-09-01T00:00:00Z'), end: iso('2026-09-01T01:00:00Z'), title: 'zebra', summary: 'x' });
  await insertEpisode({ id: 'ep-tie-new', start: iso('2026-09-02T00:00:00Z'), end: iso('2026-09-02T01:00:00Z'), title: 'zebra', summary: 'x' });
  assert.deepEqual(await ids(`select id from public.search_episodes('zebra')`), ['ep-tie-new', 'ep-tie-old']);

  // lim is clamped to [1, 100].
  assert.equal((await ids(`select id from public.search_episodes('zebra', lim => 0)`)).length, 1);
  assert.equal((await ids(`select id from public.search_episodes('zebra', lim => -5)`)).length, 1);
  assert.equal((await ids(`select id from public.search_episodes('zebra', lim => 1)`)).length, 1);
  for (let i = 0; i < 105; i++) {
    await insertEpisode({ id: `ep-bulk-${i}`, start: i * 1000, end: i * 1000 + 1, title: 'bulk', summary: 'x' });
  }
  assert.equal((await ids(`select id from public.search_episodes('bulk', lim => 500)`)).length, 100);
  assert.equal((await ids(`select id from public.search_episodes('bulk')`)).length, 20);
  await db.query(`delete from public.episodes where id like 'ep-bulk-%' or id like 'ep-tie-%'`);
});

for (const role of ['anon', 'authenticated']) {
  test(`${role} cannot read, write or search anything`, async () => {
    await asRole(role, async () => {
      for (const table of ['episodes', 'daily_state', 'notes']) {
        await assert.rejects(db.query(`select * from public.${table}`), /permission denied/, `select ${table}`);
        await assert.rejects(db.query(`delete from public.${table}`), /permission denied/, `delete ${table}`);
        await assert.rejects(db.query(`update public.${table} set ${table === 'daily_state' ? 'updated_at' : 'id'} = ${table === 'daily_state' ? 'now()' : "'x'"}`), /permission denied/, `update ${table}`);
      }
      await assert.rejects(
        db.query(`insert into public.episodes (id, start_ms, end_ms, kind, title, summary, sensitivity) values ('r', 0, 0, 'k', 't', 's', 0)`),
        /permission denied/,
      );
      await assert.rejects(
        db.query(`insert into public.daily_state (date, state) values ('2030-01-01', '{"generatedAtMs":1,"maxSensitivity":0}')`),
        /permission denied/,
      );
      await assert.rejects(db.query(`insert into public.notes (text) values ('hi')`), /permission denied/);
      await assert.rejects(db.query(`select * from public.search_episodes('standup')`), /permission denied/);
    });
  });
}

test('service_role can read, write and search', async () => {
  await asRole('service_role', async () => {
    const eps = await db.query(`select count(*)::int as n from public.episodes`);
    assert.equal(eps.rows[0].n, 3);
    const ds = await db.query(`select count(*)::int as n from public.daily_state`);
    assert.equal(ds.rows[0].n, 2);

    await insertEpisode({ id: 'ep-svc', start: iso('2026-10-04T08:00:00Z'), end: iso('2026-10-04T08:30:00Z'), title: 'standup svc', summary: 'standup standup' });
    const found = await db.query(`select id from public.search_episodes('standup', lim => 10)`);
    assert.ok(found.rows.some((r) => r.id === 'ep-svc'));
    await db.query(`delete from public.episodes where id = 'ep-svc'`);

    await db.query(
      `insert into public.daily_state (date, state) values ('2026-10-04', '{"generatedAtMs":1,"maxSensitivity":1}')
       on conflict (date) do update set state = excluded.state, updated_at = now()`,
    );
    const latest = await db.query(`select date::text as date from public.daily_state order by date desc limit 1`);
    assert.equal(latest.rows[0].date, '2026-10-04');

    const note = await db.query(`insert into public.notes (text) values ('from mcp') returning id`);
    assert.ok(note.rows[0].id);
    const recent = await db.query(`select text from public.notes order by created_at desc limit 50`);
    assert.ok(recent.rows.some((r) => r.text === 'from mcp'));
  });
});
