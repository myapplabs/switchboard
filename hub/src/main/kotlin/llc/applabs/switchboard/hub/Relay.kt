package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import llc.applabs.switchboard.hub.adapters.AgentJobs
import llc.applabs.switchboard.hub.adapters.JobFields
import org.slf4j.LoggerFactory

@Serializable
data class RelayStatus(
    /** off: not set up · waiting: set up, the agent hasn't connected yet · connected · disconnected */
    val status: String,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class RelaySetup(val code: String, @SerialName("expires_at") val expiresAt: String, val path: String)

/**
 * Scheduled-job results in Switchboard, through Hermes's relay (contract v1, EXPERIMENTAL in Hermes:
 * `website/docs/developer-guide/relay-connector-contract.md`). Hermes never delivers cron results to its API
 * server, but a gateway with `GATEWAY_RELAY_URL` dials a connector's `/relay` WebSocket and delivers to
 * `relay:<chat_id>` like to a Telegram chat. The hub is that connector, for its own agents only:
 *
 * - **Setup:** the owner gets a one-time code (10 minutes); `curl …/relay/setup/<code> | sh` on the agent's
 *   computer writes `GATEWAY_RELAY_URL/ID/SECRET` into Hermes's `.env` and restarts the gateway. The secret
 *   is per agent, kept sealed, and verified on every connection (Hermes's HMAC upgrade token).
 * - **Delivery:** "send results here" on a job sets its Hermes `deliver` to include `relay:<delivery id>`; a
 *   `send` to that id posts a `job_result` message into the job's conversation (created for the person who
 *   turned it on) and sends them `job_finished`. A send to any other id is refused: an agent can only post
 *   into its own jobs' conversations.
 * - Nothing else of the relay is offered (`supported_ops`: send, typing): no inbound, no edits, no media.
 */
class Relay(
    private val db: Db,
    private val secrets: SecretBox,
    private val agents: Agents,
    private val conversations: Conversations,
    private val accounts: Accounts,
) {
    var notifier: Notifier? = null
    private val log = LoggerFactory.getLogger(Relay::class.java)
    private val random = SecureRandom()
    private val live = java.util.concurrent.ConcurrentHashMap<String, Int>()   // agent id → open connections

    // ── setup (owner) ───────────────────────────────────────────────────────

    fun status(user: HubUser, agentId: String): RelayStatus {
        agent(user, agentId)
        return statusOf(agentId)
    }

    fun statusOf(agentId: String): RelayStatus {
        val row = db.query("SELECT connected_at, last_seen_at FROM relay_link WHERE agent_id = ?", agentId) {
            it.getString(1) to it.getString(2) }.firstOrNull()
        val pending = db.query("SELECT 1 FROM relay_setup_code WHERE agent_id = ? AND expires_at > ?", agentId, now()) { 1 }.isNotEmpty()
        return when {
            (live[agentId] ?: 0) > 0 -> RelayStatus("connected", row?.first, row?.second)
            row?.first != null -> RelayStatus("disconnected", row.first, row.second)
            row != null || pending -> RelayStatus("waiting", row?.first, row?.second)
            else -> RelayStatus("off")
        }
    }

    /** A new one-time setup code for [agentId]. Earlier codes stay valid; the link changes only when one is used. */
    fun createSetup(user: HubUser, agentId: String): RelaySetup {
        if (!user.isOwner) throw HubException.forbidden("Only the owner connects job results.")
        val agent = agent(user, agentId)
        if (!agent.capabilities.jobs) throw HubException(HttpStatusCode.UnprocessableEntity, "not_supported", "No jobs", "${agent.name} doesn't run scheduled jobs.")
        val code = (1..10).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        val expires = Instant.now().plusSeconds(CODE_TTL_S).toString()
        db.update("DELETE FROM relay_setup_code WHERE expires_at <= ?", now())
        db.update("INSERT INTO relay_setup_code (code_hash, agent_id, hub_id, created_by, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            Accounts.hash(code), agent.id, agent.hubId, user.userId, expires, now())
        accounts.activity(user.hubId, user.userId, agent.id, "relay_setup", "{}")
        return RelaySetup(code, expires, "/relay/setup/$code")
    }

    fun remove(user: HubUser, agentId: String) {
        if (!user.isOwner) throw HubException.forbidden("Only the owner disconnects job results.")
        val agent = agent(user, agentId)
        db.update("DELETE FROM relay_link WHERE agent_id = ?", agent.id)
        db.update("DELETE FROM relay_setup_code WHERE agent_id = ?", agent.id)
        accounts.activity(user.hubId, user.userId, agent.id, "relay_removed", "{}")
    }

    /**
     * The setup script for a one-time [code], or null (unknown, used or expired). Fetching it uses the code
     * and mints the agent's secret, which is only ever in this response. [hubUrl] is where the agent reaches
     * the hub (the URL the script was fetched from).
     */
    fun setupScript(code: String, hubUrl: String): String? {
        val hash = Accounts.hash(code.trim().uppercase())
        data class C(val agentId: String, val hubId: String, val by: String, val expires: String)
        val c = db.query("SELECT agent_id, hub_id, created_by, expires_at FROM relay_setup_code WHERE code_hash = ?", hash) {
            C(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }.firstOrNull()
        if (c == null) { log.info("relay: unknown or already used setup code"); return null }
        if (!Instant.parse(c.expires).isAfter(Instant.now())) { log.info("relay: expired setup code for agent {}", c.agentId); return null }
        // One use: whoever deletes the code first gets the secret.
        if (db.update("DELETE FROM relay_setup_code WHERE code_hash = ?", hash) == 0) return null
        val gatewayId = "sb-" + newId("gw").removePrefix("gw_").take(12)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        db.update("""INSERT INTO relay_link (agent_id, hub_id, gateway_id, secret_sealed, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT (agent_id) DO UPDATE SET gateway_id = excluded.gateway_id, secret_sealed = excluded.secret_sealed,
                     created_by = excluded.created_by, created_at = excluded.created_at, connected_at = NULL, last_seen_at = NULL""",
            c.agentId, c.hubId, gatewayId, secrets.seal(secret), c.by, now())
        val agentId = c.agentId
        log.info("relay: setup fetched for agent {}", agentId)
        return script(hubUrl, gatewayId, secret)
    }

    // ── the agent's connection ──────────────────────────────────────────────

    /** The agent whose gateway presents [authorization] (Hermes's `base64url("id:exp:hmac")`), or null. */
    fun authenticate(authorization: String?, nowS: Long = Instant.now().epochSecond): String? {
        val token = authorization?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() && it.length < 512 } ?: return null
        val raw = runCatching { String(Base64.getUrlDecoder().decode(token.padEnd((token.length + 3) / 4 * 4, '='))) }.getOrNull() ?: return null
        val parts = raw.split(":")
        if (parts.size != 3) return null
        val (gatewayId, expS, sig) = parts
        val exp = expS.toLongOrNull() ?: return null
        // Hermes mints 5-minute tokens; "0" (never expires) is refused.
        if (exp <= nowS || exp > nowS + MAX_TOKEN_TTL_S) return null
        val (agentId, sealed) = db.query("SELECT agent_id, secret_sealed FROM relay_link WHERE gateway_id = ? AND secret_sealed IS NOT NULL", gatewayId) {
            it.getString(1) to it.getString(2) }.firstOrNull() ?: return null
        val expected = hmacHex(secrets.open(sealed), "$gatewayId:$expS")
        return agentId.takeIf { MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray()) }
    }

    /** Serve one gateway connection (already authenticated as [agentId]) until it closes. */
    suspend fun serve(agentId: String, session: DefaultWebSocketSession) {
        live.merge(agentId, 1, Int::plus)
        db.update("UPDATE relay_link SET connected_at = ?, last_seen_at = ? WHERE agent_id = ?", now(), now(), agentId)
        log.info("relay: agent {} connected", agentId)
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                for (line in frame.readText().split('\n')) {
                    if (line.isBlank()) continue
                    val reply = runCatching { handle(agentId, line) }.getOrElse {
                        log.info("relay: bad frame from {}: {}", agentId, it.message); null
                    } ?: continue
                    session.send(Frame.Text(reply.toString() + "\n"))
                }
                db.update("UPDATE relay_link SET last_seen_at = ? WHERE agent_id = ?", now(), agentId)
                // Setup was redone or removed while connected: this secret is no longer valid.
                if (db.query("SELECT 1 FROM relay_link WHERE agent_id = ? AND secret_sealed IS NOT NULL", agentId) { 1 }.isEmpty()) {
                    session.close(CloseReason(UNAUTHORIZED, "unauthorized")); break
                }
            }
        } finally {
            live.computeIfPresent(agentId) { _, n -> (n - 1).takeIf { it > 0 } }
            log.info("relay: agent {} disconnected", agentId)
        }
    }

    /** One frame from the gateway; returns the frame to answer with, if any. */
    internal fun handle(agentId: String, line: String): JsonObject? {
        val f = hubJson.parseToJsonElement(line).jsonObject
        return when (f.s("type")) {
            "hello" -> buildJsonObject {
                put("type", "descriptor")
                putJsonObject("descriptor") {
                    put("contract_version", 1); put("platform", f.s("platform") ?: "relay"); put("label", "Switchboard")
                    put("max_message_length", MAX_MESSAGE); put("supports_draft_streaming", false); put("supports_edit", false)
                    put("supports_threads", false); put("markdown_dialect", "markdown"); put("len_unit", "chars")
                    put("emoji", "📨")
                    put("platform_hint", "Messages here appear in the Switchboard app. Markdown is rendered.")
                    putJsonArray("supported_ops") { add("send"); add("typing") }
                }
            }
            "outbound" -> buildJsonObject {
                put("type", "outbound_result"); put("requestId", f.s("requestId") ?: "")
                put("result", outbound(agentId, f["action"]?.jsonObject ?: JsonObject(emptyMap())))
            }
            "going_idle" -> buildJsonObject { put("type", "going_idle_ack") }
            else -> null   // inbound_ack, interrupt: nothing to do
        }
    }

    private fun outbound(agentId: String, action: JsonObject): JsonObject = when (action.s("op")) {
        "send" -> {
            val chatId = action.s("chat_id") ?: ""
            val text = (action.s("content") ?: "").take(MAX_MESSAGE)
            val messageId = deliver(agentId, chatId, text)
            if (messageId != null) buildJsonObject { put("success", true); put("message_id", messageId) }
            else buildJsonObject { put("success", false); put("error", "unknown chat") }
        }
        "typing" -> buildJsonObject { put("success", true) }
        else -> buildJsonObject { put("success", false); put("error", "not supported by Switchboard") }
    }

    /** Post [text] from [agentId]'s job delivery [deliveryId] into its conversation; null if it isn't one of the agent's. */
    internal fun deliver(agentId: String, deliveryId: String, text: String): String? {
        data class D(val hubId: String, val jobId: String, val jobName: String, val userId: String, val sessionId: String?)
        val d = db.query("SELECT hub_id, job_id, job_name, user_id, session_id FROM job_delivery WHERE id = ? AND agent_id = ?", deliveryId, agentId) {
            D(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }.firstOrNull() ?: return null
        if (text.isBlank()) return null
        val user = accounts.user(d.userId) ?: return null
        val agent = agents.record(agentId) ?: return null
        // The person who turned it on must still be allowed to use the agent.
        if (!agent.let { a -> conversationsAllowed(user, a) }) return null
        val sessionId = d.sessionId?.takeIf { conversations.record(it) != null }
            ?: conversations.create(user, SessionCreate(agentId, title = d.jobName.take(120))).id.also {
                db.update("UPDATE job_delivery SET session_id = ? WHERE id = ?", it, deliveryId)
            }
        val id = newId("msg")
        val t = now()
        db.update("INSERT INTO message (id, hub_id, session_id, role, kind, text, status, created_at) VALUES (?, ?, ?, 'assistant', 'job_result', ?, 'complete', ?)",
            id, d.hubId, sessionId, text, t)
        db.update("UPDATE chat_session SET last_message_preview = ?, updated_at = ? WHERE id = ?", text.take(200), t, sessionId)
        db.update("UPDATE job_delivery SET last_delivered_at = ? WHERE id = ?", t, deliveryId)
        conversations.changed(sessionId)
        notifier?.notify(listOf(d.userId), "job_finished", d.hubId, mapOf("session_id" to sessionId, "agent_id" to agentId, "job_id" to d.jobId))
        return id
    }

    private fun conversationsAllowed(user: HubUser, agent: AgentRecord) = agents.permissions.access(user, agent).canChat

    // ── per job ─────────────────────────────────────────────────────────────

    /** Where [jobId]'s results go in Switchboard, if anywhere: (delivery id, session id, user id). */
    fun delivery(agentId: String, jobId: String): Triple<String, String?, String>? =
        db.query("SELECT id, session_id, user_id FROM job_delivery WHERE agent_id = ? AND job_id = ?", agentId, jobId) {
            Triple(it.getString(1), it.getString(2), it.getString(3)) }.firstOrNull()

    /**
     * Turn "send results here" on or off for a job: keeps the job's other delivery targets (Telegram …) and
     * adds or removes `relay:<delivery id>`. Results go to a conversation of [user].
     */
    suspend fun setDelivery(user: HubUser, agent: AgentRecord, api: AgentJobs, target: llc.applabs.switchboard.hub.adapters.ProfileTarget,
                            jobId: String, jobName: String, currentDeliver: String?, on: Boolean): String {
        val existing = delivery(agent.id, jobId)
        val others = (currentDeliver ?: "local").split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it != "local" && !it.startsWith("relay:") }
        val deliveryId = if (on) existing?.first ?: newId("jd").also {
            db.update("INSERT INTO job_delivery (id, hub_id, agent_id, job_id, job_name, user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                it, agent.hubId, agent.id, jobId, jobName, user.userId, now())
        } else null
        if (on && existing != null && existing.third != user.userId)
            db.update("UPDATE job_delivery SET user_id = ?, session_id = NULL WHERE id = ?", user.userId, existing.first)
        if (on) db.update("UPDATE job_delivery SET job_name = ? WHERE id = ?", jobName, deliveryId)
        val deliver = (others + listOfNotNull(deliveryId?.let { "relay:$it" })).joinToString(",").ifEmpty { "local" }
        api.updateJob(target, jobId, JobFields(deliver = deliver))
        if (!on) db.update("DELETE FROM job_delivery WHERE agent_id = ? AND job_id = ?", agent.id, jobId)
        accounts.activity(user.hubId, user.userId, agent.id, "job_changed", """{"job_id":"$jobId","change":"${if (on) "results_here" else "results_not_here"}"}""")
        return deliver
    }

    fun forget(agentId: String, jobId: String) = db.update("DELETE FROM job_delivery WHERE agent_id = ? AND job_id = ?", agentId, jobId)

    private fun agent(user: HubUser, agentId: String): AgentRecord =
        agents.record(agentId)?.takeIf { it.hubId == user.hubId && (user.isOwner || user.role == Role.admin) } ?: throw HubException.notFound("agent")

    private fun JsonObject.s(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    companion object {
        const val CODE_TTL_S = 600L
        const val MAX_TOKEN_TTL_S = 900L
        const val MAX_MESSAGE = 60_000
        /** Hermes treats this close code as "secret revoked". */
        const val UNAUTHORIZED: Short = 4401
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun hmacHex(secret: String, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** Only characters a URL host and port can have, so nothing else ends up in the script. */
        fun safeHubUrl(scheme: String, host: String): String? =
            host.takeIf { it.matches(Regex("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) }?.let { "$scheme://$it" }

        internal fun script(hubUrl: String, gatewayId: String, secret: String) = """
            |#!/bin/sh
            |# Switchboard: send this Hermes agent's scheduled-job results to your hub (Hermes relay).
            |# Run it as the account Hermes runs as. For a named profile: | HERMES_HOME=~/.hermes/profiles/NAME sh
            |set -eu
            |umask 077
            |home="${'$'}{HERMES_HOME:-${'$'}HOME/.hermes}"
            |env="${'$'}home/.env"
            |[ -d "${'$'}home" ] || { echo "No Hermes folder at ${'$'}home. Run this as the account Hermes runs as, or set HERMES_HOME."; exit 1; }
            |touch "${'$'}env"
            |set_var() {
            |  tmp="${'$'}env.switchboard.${'$'}${'$'}"
            |  grep -v "^${'$'}1=" "${'$'}env" > "${'$'}tmp" || true
            |  printf '%s=%s\n' "${'$'}1" "${'$'}2" >> "${'$'}tmp"
            |  mv "${'$'}tmp" "${'$'}env"
            |}
            |set_var GATEWAY_RELAY_URL '$hubUrl'
            |set_var GATEWAY_RELAY_ID '$gatewayId'
            |set_var GATEWAY_RELAY_SECRET '$secret'
            |# Without this, Hermes turns off its other chat platforms (Telegram, …) once a relay is set.
            |set_var GATEWAY_RELAY_ALLOW_DIRECT_PLATFORMS true
            |chmod 600 "${'$'}env"
            |echo "✓ Saved in ${'$'}env"
            |# Over ssh, ~/.local/bin (where Hermes installs) is often not on PATH.
            |hermes="${'$'}(command -v hermes || true)"
            |[ -n "${'$'}hermes" ] || [ ! -x "${'$'}HOME/.local/bin/hermes" ] || hermes="${'$'}HOME/.local/bin/hermes"
            |if [ -n "${'$'}hermes" ] && "${'$'}hermes" gateway restart >/dev/null 2>&1; then
            |  echo "✓ Hermes gateway restarted"
            |else
            |  echo "→ Restart the Hermes gateway to finish: hermes gateway restart"
            |fi
            |echo "Done. In the Switchboard app, open Jobs and turn on \"Send results here\" for a job."
            |""".trimMargin()
    }
}
