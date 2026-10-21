package llc.applabs.switchboard.hub

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Push notices through the AppLabs relay (push-relay/; spec: Notifications). A notice is a kind and opaque
 * ids, never content: the phone shows a generic notification and fetches details from this hub.
 *
 * Off until `pushRelayUrl` is set. Each device registers its token with the relay (getting a grant) and
 * then with the hub. People can turn kinds off per device (`notify_prefs`; missing = on). A token the relay
 * reports gone (410) is forgotten.
 */
class Notifier(private val db: Db, private val relayUrl: String?, private val http: HttpClient, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(Notifier::class.java)
    val enabled get() = relayUrl != null

    /** Send [kind] to every device of [userIds] that has push and hasn't turned [kind] off. */
    fun notify(userIds: Collection<String>, kind: String, hubId: String, ids: Map<String, String?> = emptyMap()) {
        val url = relayUrl ?: return
        if (userIds.isEmpty()) return
        data class Target(val deviceId: String, val token: String, val grant: String, val prefs: String)
        val targets = userIds.distinct().flatMap { uid ->
            db.query("SELECT id, push_token, relay_grant, notify_prefs_json FROM device WHERE user_id = ? AND revoked_at IS NULL AND push_token IS NOT NULL AND relay_grant IS NOT NULL",
                uid) { Target(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        }.filter { allowed(it.prefs, kind) }
        for (t in targets) scope.launch {
            val body = buildJsonObject {
                put("token", t.token); put("grant", t.grant); put("kind", kind); put("hub", hubId)
                ids.forEach { (k, v) -> if (v != null) put(k, v) }
            }
            runCatching {
                val r = http.post("${url.trimEnd('/')}/api/send") { contentType(ContentType.Application.Json); setBody(body.toString()) }
                when (r.status.value) {
                    in 200..299 -> Unit
                    410 -> db.update("UPDATE device SET push_token = NULL, relay_grant = NULL WHERE id = ?", t.deviceId)
                    else -> log.info("push relay answered HTTP {} for {}", r.status.value, kind)
                }
            }.onFailure { log.info("push relay unreachable: {}", it.message) }
        }
    }

    companion object {
        /** Kinds the app can show (and the relay accepts). */
        val KINDS = setOf("approval_needed", "question_asked", "run_finished", "job_failed", "member_joined", "task_needs_you", "task_review")

        fun allowed(prefsJson: String, kind: String): Boolean = runCatching {
            Json.parseToJsonElement(prefsJson).jsonObject[kind]?.jsonPrimitive?.booleanOrNull
        }.getOrNull() != false
    }
}
