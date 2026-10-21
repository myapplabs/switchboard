package llc.applabs.switchboard.hub

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One event on a user's channel: SSE `id:` = [seq], `event:` = [type], `data:` = [json]. */
data class UserEvent(val seq: Long, val type: String, val json: String)

/**
 * The per-user live channel (`GET /v1/me/events`, contract events.md → "User events").
 *
 * Each user has their own sequence (`seq`, +1 per event) and a replay buffer, so a phone that
 * reconnects with `Last-Event-ID` gets what it missed; if the gap is older than the buffer it gets a
 * single `resync` (refetch the screens). M0 keeps the buffer in memory; a hub restart means `resync`.
 */
class UserEvents(private val bufferSize: Int = 1_000) {
    private class Stream {
        var seq = 0L
        val buffer = ArrayDeque<UserEvent>()
        val live = MutableSharedFlow<UserEvent>(extraBufferCapacity = 256)
    }

    private val streams = ConcurrentHashMap<String, Stream>()
    private fun stream(userId: String) = streams.computeIfAbsent(userId) { Stream() }

    /** Send [type] with [fields] to each of [userIds]. */
    fun publish(userIds: Collection<String>, type: String, fields: Map<String, Any?> = emptyMap()) {
        for (userId in userIds.toSet()) {
            val s = stream(userId)
            val event = synchronized(s) {
                s.seq += 1
                val payload = buildJsonObject {
                    put("type", JsonPrimitive(type))
                    put("seq", JsonPrimitive(s.seq))
                    put("at", JsonPrimitive(now()))
                    fields.forEach { (k, v) -> put(k, toJson(v)) }
                }
                UserEvent(s.seq, type, payload.toString()).also {
                    s.buffer.addLast(it)
                    while (s.buffer.size > bufferSize) s.buffer.removeFirst()
                }
            }
            s.live.tryEmit(event)
        }
    }

    /** Events after [lastEventId] (replayed, or one `resync` if they are gone), then live ones. */
    fun subscribe(userId: String, lastEventId: Long?): Flow<UserEvent> {
        val s = stream(userId)
        var sent = lastEventId ?: Long.MAX_VALUE
        return s.live
            .onSubscription {
                val (replay, latest, gap) = synchronized(s) {
                    val oldest = s.buffer.firstOrNull()?.seq ?: (s.seq + 1)
                    val from = lastEventId ?: s.seq
                    Triple(s.buffer.filter { it.seq > from }, s.seq, lastEventId != null && lastEventId + 1 < oldest)
                }
                sent = lastEventId ?: latest
                if (gap) {
                    emit(UserEvent(latest, "resync",
                        buildJsonObject { put("type", JsonPrimitive("resync")); put("seq", JsonPrimitive(latest)); put("at", JsonPrimitive(now())) }.toString()))
                    sent = latest
                } else {
                    replay.forEach { emit(it); sent = it.seq }
                }
            }
            .filter { e -> (e.type == "resync" || e.seq > sent).also { if (it && e.type != "resync") sent = e.seq } }
    }

    private fun toJson(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is JsonObject -> v
        is kotlinx.serialization.json.JsonElement -> v
        else -> JsonPrimitive(v.toString())
    }
}
