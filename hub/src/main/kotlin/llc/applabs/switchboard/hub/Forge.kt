package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable data class BridgeIn(val name: String)

@Serializable
data class BridgeOut(
    val id: String, val name: String, val connected: Boolean,
    /** False until FORGE has used the one-time code. */
    val paired: Boolean,
    @SerialName("last_seen_at") val lastSeenAt: String?, @SerialName("created_at") val createdAt: String,
)

/**
 * A one-time code (10 minutes) to type where FORGE runs: `forge switchboard connect --code …` trades it for
 * the bridge's token, which FORGE keeps in its keychain. The token itself never reaches the app.
 */
@Serializable data class BridgeCreated(val bridge: BridgeOut, val code: String, @SerialName("code_expires_at") val codeExpiresAt: String)
@Serializable data class BridgePairIn(val code: String)
@Serializable data class BridgePaired(val token: String, @SerialName("bridge_id") val bridgeId: String, val name: String)

@Serializable data class BridgeCall(val id: String, val tool: String, val arguments: JsonObject)
@Serializable data class BridgeCalls(val calls: List<BridgeCall>)
@Serializable data class BridgeResult(val id: String, val ok: Boolean, val result: JsonElement? = null, val error: String? = null)
@Serializable data class BridgeResults(val results: List<BridgeResult>)

/** A FORGE ticket shown on the board (read from FORGE each time; never stored by the hub). */
@Serializable
data class ForgeCard(
    val id: String,
    val project: String,
    val key: String,
    val title: String,
    /** FORGE's own ticket status: draft, todo, in_progress, in_review, done. */
    val status: String,
    /** Where it sits on the board. */
    val column: String,
    val milestone: String? = null,
    val grade: String? = null,
    @SerialName("run_id") val runId: String? = null,
    /** The gate the ticket's run waits at (plan, final review, research), when it waits for a person. */
    @SerialName("waiting_for") val waitingFor: String? = null,
    val phase: String? = null,
)

@Serializable data class ForgeBoard(val connected: Boolean, val cards: List<ForgeCard> = emptyList(), val problem: String? = null)
@Serializable data class ForgeStartIn(val project: String, val ticket: String, val confirmed: Boolean = false)
@Serializable data class ForgeDecideIn(val decision: String, val note: String? = null)

private class Bridge(val id: String, val hubId: String, val userId: String)

/**
 * FORGE tickets on the board (spec F37). FORGE runs on someone's own machine (or a cloud machine); a bridge
 * next to it polls the hub with its token and runs FORGE's own tools there, so FORGE's machine opens no port
 * and FORGE stays the system of record. Each bridge belongs to one person: their tickets show on their board,
 * and gate decisions go through FORGE's tools as theirs. Only the tools below are relayed.
 */
class ForgeBridges(
    private val db: Db,
    private val accounts: Accounts,
    private val pollHoldMs: Long = 25_000,
    private val callTimeoutMs: Long = 60_000,
    private val boardCacheMs: Long = 15_000,
) {
    private val queues = ConcurrentHashMap<String, Channel<BridgeCall>>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<BridgeResult>>()
    private val lastPoll = ConcurrentHashMap<String, Instant>()
    private val boards = ConcurrentHashMap<String, Pair<Instant, ForgeBoard>>()

    // ── people ──────────────────────────────────────────────────────────────

    fun create(user: HubUser, input: BridgeIn): BridgeCreated {
        if (user.role == Role.restricted) throw HubException.forbidden("Restricted members don't connect FORGE.")
        val name = input.name.trim().takeIf { it.isNotEmpty() && it.length <= 40 } ?: throw HubException.invalid("name: 1–40 characters")
        val code = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        val expires = Instant.now().plusSeconds(CODE_TTL_S).toString()
        val id = newId("fbr")
        db.update("INSERT INTO forge_bridge (id, hub_id, user_id, name, pair_code_hash, pair_expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, user.hubId, user.userId, name, Accounts.hash(code), expires, now())
        accounts.activity(user.hubId, user.userId, null, "forge_bridge_created", """{"bridge_id":"$id"}""")
        return BridgeCreated(list(user).items.first { it.id == id }, code.chunked(4).joinToString("-"), expires)
    }

    /** FORGE trades the one-time code for its token (once; within 10 minutes). */
    fun pair(input: BridgePairIn): BridgePaired {
        val code = input.code.uppercase().filter { it in CODE_ALPHABET }
        val row = db.query("SELECT id, name, pair_expires_at FROM forge_bridge WHERE pair_code_hash = ? AND revoked_at IS NULL", Accounts.hash(code)) {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        }.firstOrNull()
        if (row == null || Instant.parse(row.third).isBefore(Instant.now()))
            throw HubException(HttpStatusCode.Unauthorized, "invalid_code", "Code not valid", "That code is wrong, used or expired. Make a new one in the app.")
        val token = "fbt_" + accounts.randomToken()
        val n = db.update("UPDATE forge_bridge SET token_hash = ?, pair_code_hash = NULL, pair_expires_at = NULL WHERE id = ? AND pair_code_hash IS NOT NULL",
            Accounts.hash(token), row.first)
        if (n == 0) throw HubException(HttpStatusCode.Unauthorized, "invalid_code", "Code not valid", "That code was just used.")
        return BridgePaired(token, row.first, row.second)
    }

    fun list(user: HubUser): Items<BridgeOut> = Items(db.query(
        "SELECT * FROM forge_bridge WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at", user.userId) { rs ->
        val id = rs.getString("id")
        BridgeOut(id, rs.getString("name"), connected(id), rs.str("token_hash") != null, rs.str("last_seen_at"), rs.getString("created_at"))
    })

    fun revoke(user: HubUser, id: String) {
        val n = db.update("UPDATE forge_bridge SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL", now(), id, user.userId)
        if (n == 0) throw HubException.notFound("FORGE connection")
        lastPoll.remove(id)
        queues.remove(id)?.close()
        boards.remove(user.userId)
        accounts.activity(user.hubId, user.userId, null, "forge_bridge_revoked", """{"bridge_id":"$id"}""")
    }

    // ── the bridge ──────────────────────────────────────────────────────────

    private fun bridge(rawToken: String?): Bridge {
        val token = rawToken?.takeIf { it.startsWith("fbt_") } ?: throw HubException.unauthorized()
        return db.query("SELECT id, hub_id, user_id FROM forge_bridge WHERE token_hash = ? AND revoked_at IS NULL", Accounts.hash(token)) {
            Bridge(it.getString(1), it.getString(2), it.getString(3))
        }.firstOrNull() ?: throw HubException.unauthorized()
    }

    /** The bridge asks for work: tool calls waiting for it, or none after [pollHoldMs]. */
    suspend fun poll(rawToken: String?): BridgeCalls {
        val b = bridge(rawToken)
        val first = lastPoll[b.id] == null || !connected(b.id)
        lastPoll[b.id] = Instant.now()
        db.update("UPDATE forge_bridge SET last_seen_at = ? WHERE id = ?", now(), b.id)
        if (first) boards.remove(b.userId)
        val q = queues.computeIfAbsent(b.id) { Channel(Channel.UNLIMITED) }
        val one = withTimeoutOrNull(pollHoldMs) { q.receive() } ?: return BridgeCalls(emptyList())
        val calls = mutableListOf(one)
        while (true) calls += q.tryReceive().getOrNull() ?: break
        lastPoll[b.id] = Instant.now()
        return BridgeCalls(calls)
    }

    fun results(rawToken: String?, input: BridgeResults) {
        bridge(rawToken)
        input.results.forEach { r -> pending.remove(r.id)?.complete(r) }
    }

    private fun connected(bridgeId: String) = lastPoll[bridgeId]?.let { Duration.between(it, Instant.now()).toMillis() < pollHoldMs + 20_000 } == true

    // ── FORGE through the bridge ────────────────────────────────────────────

    private fun bridgeFor(user: HubUser): String? =
        db.query("SELECT id FROM forge_bridge WHERE user_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC", user.userId) { it.getString(1) }
            .firstOrNull { connected(it) }

    /** One FORGE tool call on the person's own FORGE. */
    suspend fun call(user: HubUser, tool: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        if (tool !in RELAYED) throw HubException.forbidden("Not a FORGE tool the hub relays.")
        val bridgeId = bridgeFor(user) ?: throw notConnected()
        val call = BridgeCall(newId("fc"), tool, args)
        val done = CompletableDeferred<BridgeResult>().also { pending[call.id] = it }
        queues.computeIfAbsent(bridgeId) { Channel(Channel.UNLIMITED) }.send(call)
        val r = withTimeoutOrNull(callTimeoutMs) { done.await() } ?: run {
            pending.remove(call.id)
            throw HubException(HttpStatusCode.GatewayTimeout, "forge_timeout", "FORGE didn't answer", "FORGE didn't answer in time. Is the machine it runs on awake?")
        }
        if (!r.ok) throw HubException(HttpStatusCode.UnprocessableEntity, "forge_error", "FORGE refused", r.error?.take(500) ?: "FORGE refused.")
        return r.result ?: JsonNull
    }

    /** The person's FORGE tickets as cards (cached briefly; FORGE is asked again after that). */
    suspend fun board(user: HubUser, fresh: Boolean = false): ForgeBoard {
        if (bridgeFor(user) == null) return ForgeBoard(false)
        if (!fresh) boards[user.userId]?.takeIf { Duration.between(it.first, Instant.now()).toMillis() < boardCacheMs }?.let { return it.second }
        val board = try {
            val projects = (call(user, "forge_projects") as? JsonObject)?.get("items") as? JsonArray ?: JsonArray(emptyList())
            val waiting = ((call(user, "forge_attention") as? JsonObject)?.get("waiting_for_owner") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }.associateBy { it.s("run_id") }
            val cards = projects.mapNotNull { (it as? JsonObject)?.s("name") }.flatMap { project ->
                val backlog = call(user, "forge_backlog", buildJsonObject { put("project", project) }) as? JsonObject
                ((backlog?.get("tickets") as? JsonArray).orEmpty()).mapNotNull { it as? JsonObject }.map { t -> card(project, t, waiting) }
            }
            ForgeBoard(true, cards)
        } catch (e: HubException) {
            ForgeBoard(e.code != "forge_not_connected", problem = e.detail)
        }
        boards[user.userId] = Instant.now() to board
        return board
    }

    suspend fun start(user: HubUser, input: ForgeStartIn): JsonElement {
        if (!TICKET.matches(input.ticket)) throw HubException.invalid("ticket: T-NNN")
        val out = call(user, "forge_start_ticket", buildJsonObject {
            put("project", input.project); put("ticket", input.ticket); put("confirmed", input.confirmed)
        })
        if (input.confirmed) { boards.remove(user.userId); accounts.activity(user.hubId, user.userId, null, "forge_ticket_started", """{"ticket":"${input.ticket}"}""") }
        return out
    }

    suspend fun run(user: HubUser, runId: String): JsonElement = call(user, "forge_run", buildJsonObject { put("run_id", runId) })

    /** A gate decision on the person's FORGE, recorded there as theirs, made in this app. */
    suspend fun decide(user: HubUser, runId: String, input: ForgeDecideIn): JsonElement {
        if (input.decision !in setOf("approve", "reject")) throw HubException.invalid("decision: approve or reject")
        val note = input.note?.trim()?.takeIf { it.isNotEmpty() }
        if (input.decision == "reject" && note == null) throw HubException.invalid("A rejection needs a note: what should change.")
        val said = "${user.displayName} chose ${input.decision} in the Switchboard app" + (note?.let { ": $it" } ?: ".")
        val out = call(user, "forge_decide", buildJsonObject {
            put("run_id", runId); put("decision", input.decision); put("owner_said", said.take(500)); note?.let { put("note", it) }
        })
        boards.remove(user.userId)
        accounts.activity(user.hubId, user.userId, null, "forge_decided", """{"run_id":"$runId","decision":"${input.decision}"}""")
        return out
    }

    private fun card(project: String, t: JsonObject, waiting: Map<String?, JsonObject>): ForgeCard {
        val key = t.s("key") ?: "?"
        val status = t.s("status") ?: "draft"
        val runIds = (t["runs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val waitingRun = runIds.lastOrNull { it in waiting }?.let { waiting[it] }
        val column = when {
            waitingRun != null -> Tasks.NEEDS_YOU
            status == "in_progress" -> Tasks.WORKING
            status == "in_review" -> Tasks.REVIEW
            status == "done" -> Tasks.DONE
            else -> Tasks.BACKLOG
        }
        return ForgeCard("forge:$project:$key", project, key, t.s("title") ?: key, status, column, t.s("milestone"), t.s("grade"),
            waitingRun?.s("run_id") ?: runIds.lastOrNull(), waitingRun?.s("waiting_for"), waitingRun?.s("phase"))
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun notConnected() = HubException(HttpStatusCode.ServiceUnavailable, "forge_not_connected", "FORGE not connected",
        "No FORGE is connected for you. Run `forge switchboard connect` where FORGE runs.")

    companion object {
        /** The FORGE tools the hub relays (the bridge has the same list). */
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"   // no 0/O, 1/I/L
        private const val CODE_TTL_S = 600L
        private val random = java.security.SecureRandom()
        val RELAYED = setOf("forge_projects", "forge_backlog", "forge_attention", "forge_run", "forge_start_ticket", "forge_decide")
        private val TICKET = Regex("T-[0-9]{3,}")
    }
}
