package llc.applabs.switchboard.data

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import llc.applabs.switchboard.domain.PushRepository
import llc.applabs.switchboard.domain.PushTokens
import llc.applabs.switchboard.domain.model.Device
import llc.applabs.switchboard.domain.model.Items

/** The AppLabs push relay (push-relay/ in the repo). Notices carry no content; details come from the hub. */
const val PUSH_RELAY_URL = "https://switchboard-push-relay.vercel.app"

/**
 * Push registration: the platform's token goes to the relay (which answers with a grant, its signature of
 * the token) and then, with the grant, to the hub (`POST /devices`). Done after sign-in and whenever the
 * platform rotates the token. Best effort: without push the app still updates live while open.
 */
class PushRepositoryImpl(private val client: HubClient, private val tokens: PushTokens?, private val relayUrl: String = PUSH_RELAY_URL) : PushRepository {
    override suspend fun register(token: String?): Boolean {
        val p = tokens ?: return false
        if (client.tokens.accessToken == null) return false
        val t = token ?: p.token() ?: return false
        val grant = runCatching { relayGrant(t, p.platform) }.getOrNull() ?: return false
        client.call<Device>(HttpMethod.Post, "/devices", buildJsonObject {
            put("platform", p.platform); put("push_token", t); put("relay_grant", grant)
        })
        return true
    }

    override suspend fun setPreference(kind: String, on: Boolean): Device? {
        val me = client.call<Items<Device>>(HttpMethod.Get, "/devices").items.firstOrNull { it.thisDevice } ?: return null
        return client.call(HttpMethod.Patch, "/devices/${me.id}", buildJsonObject {
            put("notify_prefs", JsonObject(me.notifyPrefs.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) } +
                (kind to kotlinx.serialization.json.JsonPrimitive(on))))
        })
    }

    private suspend fun relayGrant(token: String, platform: String): String? {
        val text = client.sendTo("$relayUrl/api/register", buildJsonObject { put("token", token); put("platform", platform) }.toString())
        return client.json.parseToJsonElement(text).jsonObject["grant"]?.jsonPrimitive?.content
    }
}
