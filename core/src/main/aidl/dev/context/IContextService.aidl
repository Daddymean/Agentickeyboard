package dev.context;

// FROZEN CONTRACT. All payloads are JSON strings encoded with
// dev.context.core.json.ContextJson. See dev.context.core.ContextContract for
// the size budgets both sides honour to stay under the binder buffer.
interface IContextService {
    // Latest Snapshot JSON (dev.context.core.model.Snapshot).
    String getSnapshot();

    // JSON array of Event. Idempotent: rows are keyed by Event.id and
    // duplicates are ignored, so callers may retry freely.
    void logEvents(String eventsJson);

    // JSON array of Event overlapping [fromMs, toMs), ordered by (startMs, id).
    // Null or empty types means all types. The reply may be truncated to
    // ContextContract.MAX_REPLY_CHARS; see ContextContract.replyMayBeTruncated.
    String queryRange(long fromMs, long toMs, in String[] types);
}
