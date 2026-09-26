# `:core` — Part A of the context platform

The shared contract of the local-first context platform: Room schema, Snapshot
model, the `IContextService` AIDL, and `ContextClient`, the binder SDK used by
the keyboard and collectors.

## File tree

```
core/
├── build.gradle.kts                  # android-library, minSdk 34, aidl, KSP Room, kotlinx.serialization
├── consumer-rules.pro                # R8 keeps for serializers + AIDL, applied in consuming apps
└── src/
    ├── main/
    │   ├── AndroidManifest.xml       # defines + uses dev.context.permission.ACCESS, <queries> for the bind action
    │   ├── aidl/dev/context/IContextService.aidl
    │   └── java/dev/context/core/
    │       ├── ContextContract.kt    # permission, bind action, IPC size budgets, truncation predicate
    │       ├── model/                # Event (+EventTypes, Sensitivity), Episode, Snapshot/Today/NextEvent, EventValidator
    │       ├── json/ContextJson.kt   # the one Json config for every IPC string
    │       ├── db/                   # ContextDatabase, EventDao, EpisodeDao
    │       ├── service/ContextStore.kt  # service-side logEvents/queryRange over Room, enforces the reply budget
    │       └── client/               # ContextClient, EventBatcher, EventSpool (file + memory), RangeQueryPager, ServiceConnector
    └── test/java/dev/context/core/   # JVM tests (json, batcher, pager, spool) + Robolectric Room tests
```

## Public API at a glance

| Who | Uses |
| --- | --- |
| `:context-app` service | `ContextDatabase.get(ctx)`, `ContextStore(db).logEventsJson / queryRangeJson`, `ContextJson.encodeSnapshot` |
| Distiller | `EventDao` range/type queries, `EpisodeDao.upsertAll`, `Snapshot`, `Snapshot.redactedForSync()` |
| Sync uploader | `EpisodeDao.overlappingUpTo(from, to, Sensitivity.SYNC_MAX)`, `Sensitivity.isSyncable` |
| Keyboard | `ContextClient.cachedSnapshot()` / `snapshot` flow, `logNote(text, sensitivity)` |
| Collectors | `ContextClient.logEvents(events)` (or `EventDao.insertAll` when in-process) |

## Assumptions (not dictated by the spec)

1. **Packages.** AIDL is `dev.context.IContextService`; Kotlin lives under
   `dev.context.core.*`; library namespace `dev.context.core`.
2. **Bind intent.** Action `dev.context.action.BIND_CONTEXT_SERVICE`. The client
   resolves it by action (optionally pinned to `Config.servicePackage`) and only
   binds a service that is exported, declares `dev.context.permission.ACCESS`,
   and is signed with the client's certificate.
3. **Range semantics.** `queryRange` is half-open `[fromMs, toMs)` and returns
   events *overlapping* it: point events by `startMs`, interval events when
   `endMs > fromMs`. The overlap lookback is bounded by
   `MAX_EVENT_SPAN_MS` (48 h) to keep the index scan bounded. Ordered by
   `(startMs, id)`. `null`/empty `types` = all types.
4. **Idempotent ingest.** `events.id` is the primary key; inserts are
   `INSERT OR IGNORE`. This is what makes client retries safe — at-least-once
   delivery, exactly-once storage. Events are immutable once logged.
   Episodes are `@Upsert` (the distiller re-derives them).
5. **IPC budgets.** One event ≤ 16 K chars, one `logEvents` batch ≤ 128 K chars,
   one `queryRange` reply ≤ 128 K chars (~256 KB as UTF-16, a quarter of the
   shared 1 MB binder buffer). The service truncates a reply before the event
   that would overflow; the client detects possible truncation from the reply
   length alone (`ContextContract.replyMayBeTruncated`) and pages on from the
   last `startMs`, de-duplicating by id. No contract change was needed for this.
6. **Invalid events** (blank id/type/source, sensitivity ∉ 0..3,
   `endMs < startMs`, payload not JSON, oversize) are dropped client-side before
   queueing and again server-side, individually — one bad row never fails a batch.
   Unknown `type` strings are accepted for forward compatibility.
7. **Payload** stays a JSON *string* on the wire (double-encoded), exactly as the
   contract types it. `kb.note` payload is `{"text": "..."}`.
8. **JSON shape.** Every key is always emitted (nulls explicit); unknown keys are
   ignored on decode, so either side can add fields without breaking the other.
9. **Batching defaults.** Send when the oldest queued event is 10 s old, 500
   events, or 128 K chars — whichever first; `urgent = true` (used by `logNote`)
   sends immediately. 5 attempts with 1 s → 60 s exponential backoff ±20 %
   jitter. `SecurityException` is not retried; `IllegalArgumentException` from
   the service drops the batch.
10. **Spool.** Undeliverable events go to a JSON-lines file in the client's
    `noBackupFilesDir` (4 MB cap, excluded from cloud backup because it may hold
    sensitivity-3 data). It is only written on failure and drained after the
    next successful send. Events still in memory when the process dies are lost
    (≤ 10 s worth, notes excepted since they flush immediately).
11. **Binding lifetime.** Bind on first call, unbind after 30 s idle.
    `BIND_AUTO_CREATE` only — no `BIND_WAIVE_PRIORITY`, because a de-prioritised
    service process can be put in the cached-app freezer and then fail binder calls.
12. **Snapshot cache.** Served from memory; refreshed in the background when
    older than 60 s, no more than once per 15 s after a failure. `getSnapshot()`
    returns stale data rather than throwing.
13. **Permission** is declared in `:core`'s manifest, so every same-signed app
    defines it. This removes the install-order bug where a client installed
    before the service never gets a signature permission granted.
14. **Room** schema v1, `exportSchema = true`, WAL. Only `:context-app` opens the
    database; there is no multi-process access.

## Integration notes

**`:context-app` (service)**

```xml
<service
    android:name=".ContextService"
    android:exported="true"
    android:permission="dev.context.permission.ACCESS">
  <intent-filter>
    <action android:name="dev.context.action.BIND_CONTEXT_SERVICE" />
  </intent-filter>
</service>
```

```kotlin
class ContextService : Service() {
  private val store by lazy { ContextStore(ContextDatabase.get(this)) }
  private val binder = object : IContextService.Stub() {
    override fun getSnapshot(): String = /* distiller's latest Snapshot JSON, from memory or a file — never computed here */
    override fun logEvents(eventsJson: String) { store.logEventsJson(eventsJson) }
    override fun queryRange(fromMs: Long, toMs: Long, types: Array<out String>?): String =
      store.queryRangeJson(fromMs, toMs, types)
  }
  override fun onBind(intent: Intent): IBinder = binder
}
```

- Must be signed with the same key as every client.
- Encode snapshots with `ContextJson.encodeSnapshot`, not a separate `Json` instance.
- Retention: call `EventDao.deleteStartedBefore` from a periodic worker.

**`:keyboard`**

- `implementation(project(":core"))`. The existing app's `minSdk` is 26; it must
  rise to 34 (or use `tools:overrideLibrary`, not recommended) to depend on `:core`.
- One `ContextClient` per process: create in `InputMethodService.onCreate`,
  `close()` in `onDestroy`.
- Keystroke path: only `cachedSnapshot()` or collect `snapshot`. Never call
  `getSnapshot()`/`queryRange()` per key.
- Notes: `client.logNote(text, sensitivity = …)` — sensitivity is required by design.

**Collectors / distiller** (same process as the DB): use the DAOs directly —
going through the binder to your own process is pure overhead.

**Sync (Supabase)**: filter with `Sensitivity.isSyncable` / `EpisodeDao.overlappingUpTo(…, SYNC_MAX)`
and push snapshots only through `Snapshot.redactedForSync()` (see change request 1).

## Risks

- **Binder buffer.** Handled by the budgets above, but the 1 MB buffer is shared by
  every in-flight transaction of the service process; keep `getSnapshot` small.
- **Process pinning.** A bound IME client elevates `:context-app`; the 30 s idle
  unbind limits that. Keyboard code that polls faster than the idle window keeps
  it pinned indefinitely.
- **Wakeups.** `ContextClient` never schedules alarms or WorkManager jobs; it only
  runs when the host process is already awake. Collectors should batch their own
  sampling (heart-rate at 1/min is 1 440 rows/day) — prefer summarised events.
- **Signature permission** means every client must share the service's signing
  key. Fine for one developer; it rules out third-party clients by design.
- **Spool holds raw events** (possibly sensitivity 3) on disk in the client app;
  it is app-private and excluded from backup, but not separately encrypted.
- **Play policy.** Health Connect and usage-stats collectors carry their own
  declaration requirements; none of that lives in `:core`.

## CONTRACT CHANGE REQUESTS

Implemented against the current contract regardless.

1. **Snapshot has no sensitivity.** `get_state_now()` serves the Snapshot
   off-device, but `today.places`, `today.topApps` and `recentNotes` carry no
   sensitivity, so the rule "only ≤ 1 ever syncs" cannot be enforced on it.
   `redactedForSync()` can only strip `activeEpisode`. *Proposal:* the distiller
   publishes two snapshots (local, and one built only from sensitivity ≤ 1
   inputs), or add `sensitivity` to each list entry.
2. **Episode has no `updatedMs`.** Sync needs a change cursor; without one the
   uploader must re-scan by time range. *Proposal:* add `updatedMs: Long`
   (indexed) to Episode.
3. **No push for snapshot changes.** The client can only poll. *Proposal:* add
   `oneway void registerListener(ISnapshotListener l)` so the keyboard's cache is
   refreshed exactly when the distiller publishes — fewer binds and fresher data.
4. **`queryRange` has no explicit cursor.** Solved here with a length-based
   truncation signal, which works but is implicit. *Proposal:* return
   `{ "events": [...], "nextFromMs": Long? }`, or add a `limit` parameter.
5. **`payload` as a JSON-in-a-string** double-escapes every payload on the wire and
   in Supabase. *Proposal:* type it as a JSON object on the wire (still stored as
   TEXT in Room).
