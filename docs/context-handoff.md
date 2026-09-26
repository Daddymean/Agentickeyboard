# Context platform — handoff from Part A (`:core`)

`:core` is merged (PR #105). This brief is for whoever builds the other parts.
It covers only what you need to plug in. Background and rationale are in
[`context-core.md`](context-core.md).

## 1. What exists

`:core` is an Android library: namespace `dev.context.core`, minSdk 34, path
`core/`. Depend on it with `implementation(project(":core"))`. It gives you:

| Piece | Where |
| --- | --- |
| Room DB (`events`, `episodes`), blocking DAOs | `dev.context.core.db.ContextDatabase` / `EventDao` / `EpisodeDao` |
| Models: `Event`, `Episode`, `Snapshot`, `Today`, `NextEvent` | `dev.context.core.model` |
| Constants: `EventTypes.*`, `Sensitivity.*` | `dev.context.core.model` |
| The only JSON config you should use | `dev.context.core.json.ContextJson` |
| AIDL | `dev.context.IContextService` (note: **not** under `.core`) |
| Service-side logic for `logEvents` / `queryRange` | `dev.context.core.service.ContextStore` |
| Client SDK | `dev.context.core.client.ContextClient` |
| Permission, bind action, size limits | `dev.context.core.ContextContract` |

The frozen contract is implemented unchanged.

## 2. Rules every part must follow

1. **Encode and decode with `ContextJson`.** It always writes every key, writes
   nulls explicitly and ignores unknown keys. A separately configured `Json`
   will drift from it.
2. **Event ids are UUID strings and must be stable.** Ingest is
   `INSERT OR IGNORE` on `id`, which is why retries are safe. The flip side:
   events are immutable, and re-sending an id never updates the row. To correct
   an event, log a new one with a new id.
3. **`payload` is a JSON document stored as a string**, per the contract. On the
   wire it is double-encoded: `"payload":"{\"bpm\":62}"`. It must parse as JSON,
   or the event is rejected.
4. **Validation limits.** Events outside these are dropped individually on both
   the client and the service:
   - `id` ≤ 64 chars; `type` ≤ 64 chars; `source` ≤ 128 chars, none blank
   - `sensitivity` in 0..3
   - `startMs` ≥ 0, and `endMs` null or ≥ `startMs`
   - encoded event ≤ 16 K chars
5. **Range semantics.** `queryRange(from, to, types)` uses `[from, to)`:
   - It returns events that *overlap* the range, sorted by `(startMs, id)`.
   - Point events (`endMs` null) match on `startMs`.
   - Events that started up to 48 h before `from` and are still running at `from` are included; anything longer is found only by a range that contains its start.
   - `types` null or empty means all types.
6. **Sensitivity.** Only `Sensitivity.isSyncable(level)` (≤ 1) may leave the
   device. Levels 2 and 3 exist only on the phone.
7. **Signing.** `dev.context.permission.ACCESS` is a signature permission, so
   `:context-app` and every client app **must be signed with the same key for
   the same build type** (debug with debug, release with release).
   `ContextClient` also refuses to bind a service whose signature doesn't match.

## 3. Per part

### `:context-app` — the service and the distiller

Manifest:

```xml
<service android:name=".ContextService" android:exported="true"
    android:permission="dev.context.permission.ACCESS">
  <intent-filter><action android:name="dev.context.action.BIND_CONTEXT_SERVICE" /></intent-filter>
</service>
```

Stub: forward `logEvents` and `queryRange` to `ContextStore`. It enforces the
reply budget that `ContextClient`'s paging relies on, so don't reimplement it.

```kotlin
private val store by lazy { ContextStore(ContextDatabase.get(this)) }
override fun logEvents(eventsJson: String) { store.logEventsJson(eventsJson) }
override fun queryRange(fromMs: Long, toMs: Long, types: Array<out String>?) =
  store.queryRangeJson(fromMs, toMs, types)
override fun getSnapshot(): String = latestSnapshotJson  // yours: see below
```

- **`getSnapshot` must return a precomputed string.** Keep the latest
  `ContextJson.encodeSnapshot(...)` in memory and in a file, rewritten by the
  distiller. Never compute it inside the binder call. Return
  `encodeSnapshot(Snapshot.empty(now))` before the first distill, never null.
- **Distiller.** It shares a process with the database, so use the DAOs
  directly (`EventDao.overlapping*`, `EpisodeDao.upsertAll`, `activeAt`). Don't
  bind to yourself. DAOs block; call them from `Worker.doWork` or
  `withContext(IO)`.
- **Retention.** Call `EventDao.deleteStartedBefore(cutoff)` from a periodic
  worker. Raw heart-rate at one sample per minute is about 1.4 K rows a day.
- Use `ContextDatabase.get(context)`, the process singleton. Don't build a
  second instance.

### Collectors

- **In the `:context-app` process:** write with `EventDao.insertAll`.
- **In another app:** use
  `ContextClient(context).logEvents(events)`. It never blocks and batches for
  up to 10 s. Undeliverable events go to a spool file and are re-sent later.
- **Prefer summary events over raw samples** (e.g. one `health.heart_rate`
  event per period with `{min, max, avg}` rather than one per reading). Both
  battery and binder budget depend on this.
- Use the `EventTypes` constants. Unknown types are accepted, but the
  distiller won't know them.

### Keyboard

- **This repo's `:app` has `minSdk 26`.** It must go to **34** before it can
  depend on `:core`; `tools:overrideLibrary` would crash on older devices.
  Deciding that is part of the keyboard work.
- Create one `ContextClient` in `InputMethodService.onCreate` and `close()` it
  in `onDestroy`.
- **Keystroke path:** read only `client.cachedSnapshot()` or collect
  `client.snapshot`. Both are in-memory and non-blocking; a stale cache
  triggers a background refresh. Never call `getSnapshot()` or `queryRange()`
  per keystroke.
- **Notes:** `client.logNote(text, sensitivity = ...)` sends immediately with
  payload `{"text": ...}`. `sensitivity` has no default on purpose: decide in
  the UI whether a note may sync (≤ 1) or stays on the device.
- Calling the service more often than every 30 s keeps `:context-app` bound
  and resident. The idle unbind only helps if the keyboard goes quiet.

### Sync (phone → Supabase)

- **Episodes:** read with `EpisodeDao.overlappingUpTo(from, to, Sensitivity.SYNC_MAX)`.
  `episodes.eventIds` is a JSON **string**. Either parse it into `jsonb` or
  store it as text, and tell the MCP part which you chose.
- **`daily_state`:** upload `snapshot.redactedForSync()`, never the raw
  snapshot (see open question 1).
- **Notes:** filter `kb.note` events with `Sensitivity.isSyncable` and take
  `text` from the payload.
- Run it from WorkManager with network and battery-not-low constraints.
  Nothing in `:core` schedules work.

### Supabase / MCP

- Row shapes mirror the `Episode` fields and the `Snapshot` JSON exactly as
  `ContextJson` emits them. Every key is present and nulls are explicit.
- `get_state_now()` returns the latest `daily_state.state`. It is already
  redacted by the phone. Don't assume it contains anything above sensitivity 1.
- `record_note` inserts into `notes` only. Nothing in the contract pulls cloud
  notes back to the phone. If you want that, raise it (open question 5).

## 4. Open questions — need a decision before sync and MCP ship

1. **The snapshot has no sensitivity.** `today.places`, `today.topApps` and
   `recentNotes` can't be filtered, and `redactedForSync()` only drops
   `activeEpisode`. *Proposal:* the distiller writes a second, sync-only
   snapshot built from sensitivity ≤ 1 inputs.
2. **Episode has no `updatedMs`.** Sync has no change cursor and must re-scan
   by time. *Proposal:* add `updatedMs: Long`, indexed. This is a Room schema
   bump to v2 in `:core`.
3. **No snapshot push.** The keyboard polls. *Proposal:*
   `oneway void registerListener(ISnapshotListener)`.
4. **`queryRange` has no explicit cursor.** Paging works today by inferring
   truncation from the reply length (`ContextContract.replyMayBeTruncated`).
   *Proposal:* return `{events, nextFromMs}`.
5. **Note direction.** `record_note` writes to the cloud only. Decide whether
   cloud notes should reach the phone (and the keyboard's `recentNotes`).

If any of these is accepted, the change starts in `:core`. Ask for it there
rather than working around it in your part.
