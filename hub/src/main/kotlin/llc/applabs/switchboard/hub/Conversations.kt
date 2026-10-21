package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import llc.applabs.switchboard.hub.adapters.AgentRuns
import org.slf4j.LoggerFactory

/** A stored conversation (contract: ChatSession). */
data class ConversationRecord(
    val id: String, val hubId: String, val agentId: String, val ownerUserId: String, val title: String,
    val titleIsDefault: Boolean, val modelId: String?, val agentSessionId: String, val lastMessagePreview: String?,
    val inputTokens: Long, val outputTokens: Long, val estimatedCostUsd: Double?, val createdAt: String, val updatedAt: String,
    val sharedEveryone: Boolean = false,
)

/**
 * Conversations (contract 0.2.0 `/sessions`), stored by the hub (hybrid; see the contract's `info`).
 *
 * Access (M2): a conversation is private to the person who started it unless they share it with chosen
 * members or everyone (spec F11). Participants see it live and may send; only its owner
 * renames, switches the model, shares or deletes it. Starting one needs chat access to the agent. Each conversation gets its own session on the agent
 * (`agent_session_id`), continued run after run; deleting the conversation deletes that too, retried
 * while the agent is offline.
 */
class Conversations(
    private val db: Db,
    private val agents: Agents,
    private val events: UserEvents,
    private val accounts: Accounts,
) {
    private val log = LoggerFactory.getLogger(Conversations::class.java)
    lateinit var permissions: Permissions

    fun create(user: HubUser, input: SessionCreate): ChatSessionOut {
        val agent = agents.record(input.agentId)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("agent")
        val access = permissions.access(user, agent)
        if (!access.canChat) throw HubException.forbidden("You don't have access to ${agent.name}. Ask the owner to grant it.")
        if (!agent.capabilities.chat) throw HubException(HttpStatusCode.UnprocessableEntity, "not_supported", "Chat not supported",
            "This agent does not offer chat.")
        input.modelId?.let { checkModel(agent, it); checkGranted(access, it) }
        val title = input.title?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null && title.length > 120) throw HubException.invalid("title: at most 120 characters")
        val id = newId("ses")
        val t = now()
        db.update("""INSERT INTO chat_session (id, hub_id, agent_id, owner_user_id, title, title_is_default, model_id, agent_session_id, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, user.hubId, agent.id, user.userId, title ?: DEFAULT_TITLE, title == null, input.modelId, "sb_$id", t, t)
        return out(get(user, id))
    }

    fun list(user: HubUser, agentId: String?, q: String?, cursor: String?, limit: Int): Page<ChatSessionOut> {
        val offset = cursor?.let { runCatching { String(Base64.getUrlDecoder().decode(it)).toInt() }.getOrNull() } ?: 0
        val where = StringBuilder("""s.hub_id = ? AND (s.owner_user_id = ? OR s.shared_everyone = 1
            OR EXISTS (SELECT 1 FROM session_participant p WHERE p.session_id = s.id AND p.user_id = ?))""")
        val args = mutableListOf<Any?>(user.hubId, user.userId, user.userId)
        agentId?.let { where.append(" AND s.agent_id = ?"); args += it }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (s.title LIKE ? OR EXISTS (SELECT 1 FROM message m WHERE m.session_id = s.id AND m.text LIKE ?))")
            args += "%$it%"; args += "%$it%"
        }
        val n = limit.coerceIn(1, 100)
        val rows = db.query("SELECT s.* FROM chat_session s WHERE $where ORDER BY s.updated_at DESC, s.id LIMIT ? OFFSET ?",
            *(args + listOf(n + 1, offset)).toTypedArray()) { row(it) }
            .filter { canSee(user, it) }   // shared with everyone: only people who may use the agent
        val next = if (rows.size > n) Base64.getUrlEncoder().withoutPadding().encodeToString((offset + n).toString().toByteArray()) else null
        return Page(rows.take(n).map(::out), next)
    }

    /** The conversation, if [user] may see it (else 404 — never reveal others' conversations). */
    fun get(user: HubUser, id: String): ConversationRecord =
        record(id)?.takeIf { canSee(user, it) } ?: throw HubException.notFound("session")

    /** The conversation, if [user] owns it (renaming, models, sharing, deleting). */
    fun owned(user: HubUser, id: String): ConversationRecord {
        val c = get(user, id)
        if (c.ownerUserId != user.userId) throw HubException.forbidden("Only the person who started this conversation can change it.")
        return c
    }

    fun canSee(user: HubUser, c: ConversationRecord): Boolean {
        if (c.hubId != user.hubId) return false
        if (c.ownerUserId == user.userId) return true
        val agent = agents.record(c.agentId) ?: return false
        if (!permissions.access(user, agent).canChat) return false
        return c.sharedEveryone || participantIds(c.id).contains(user.userId)
    }

    fun participantIds(sessionId: String): List<String> =
        db.query("SELECT user_id FROM session_participant WHERE session_id = ?", sessionId) { it.getString(1) }

    /** Everyone in the conversation: its owner, chosen participants, or everyone (who may use the agent). */
    fun audience(c: ConversationRecord): List<HubUser> {
        val agent = agents.record(c.agentId)
        val ids = if (c.sharedEveryone && agent != null) agents.viewers(agent) + c.ownerUserId else participantIds(c.id) + c.ownerUserId
        return ids.distinct().mapNotNull(accounts::user)
    }

    val ConversationRecord.isShared get() = sharedEveryone || participantIds(id).isNotEmpty()

    fun record(id: String): ConversationRecord? = db.query("SELECT * FROM chat_session WHERE id = ?", id) { row(it) }.firstOrNull()

    fun update(user: HubUser, id: String, patch: SessionPatch): ChatSessionOut {
        val c = owned(user, id)
        if (patch.modelId != null && activeRunId(id) != null) throw HubException(HttpStatusCode.Conflict, "run_active", "A run is active",
            "Wait for the current answer, or stop it, before switching models.")
        patch.title?.let { if (it.isBlank() || it.length > 120) throw HubException.invalid("title: 1–120 characters") }
        val agent = agents.record(c.agentId) ?: throw HubException.notFound("agent")
        patch.modelId?.let { m -> checkModel(agent, m); checkGranted(permissions.access(user, agent), m) }
        patch.sharedWith?.let { share -> setSharing(user, c, agent, share) }
        db.update("UPDATE chat_session SET title = ?, title_is_default = ?, model_id = ?, updated_at = ? WHERE id = ?",
            patch.title?.trim() ?: c.title, if (patch.title != null) false else c.titleIsDefault, patch.modelId ?: c.modelId, now(), id)
        changed(id)
        return out(get(user, id))
    }

    /** Delete everywhere: the hub's copy now; the agent's session now or, if it is offline, later. */
    suspend fun delete(user: HubUser, id: String) {
        val c = owned(user, id)
        deleteEverywhere(user, c)
    }

    /** A removed member's own conversations go with them (and the agents' copies). */
    suspend fun deleteAllOwnedBy(removed: HubUser) {
        db.query("SELECT * FROM chat_session WHERE owner_user_id = ?", removed.userId) { row(it) }.forEach { c ->
            db.update("UPDATE run SET status = 'cancelled', finished_at = ? WHERE session_id = ? AND finished_at IS NULL", now(), c.id)
            deleteEverywhere(removed, c)
        }
    }

    private suspend fun deleteEverywhere(user: HubUser, c: ConversationRecord) {
        val id = c.id
        val audience = audience(c).map { it.userId }
        if (activeRunId(id) != null) throw HubException(HttpStatusCode.Conflict, "run_active", "A run is active",
            "Stop the current answer before deleting the conversation.")
        db.update("DELETE FROM chat_session WHERE id = ?", id)
        events.publish(audience, "session.removed", mapOf("session_id" to id))
        accounts.activity(c.hubId, user.userId, c.agentId, "session_deleted")
        val memory = memoryScope(c)
        if (!deleteOnAgent(c.agentId, c.agentSessionId, memory)) {
            db.update("INSERT OR REPLACE INTO agent_cleanup (agent_id, agent_session_id, user_scope, attempts, next_at) VALUES (?, ?, ?, 0, ?)",
                c.agentId, c.agentSessionId, memory, Instant.now().plus(1, ChronoUnit.MINUTES).toString())
            log.info("agent offline: session {} will be deleted on the agent later", c.agentSessionId)
        }
    }

    fun messages(user: HubUser, id: String, cursor: String?, limit: Int): Page<MessageOut> {
        get(user, id)
        val offset = cursor?.let { runCatching { String(Base64.getUrlDecoder().decode(it)).toInt() }.getOrNull() } ?: 0
        val n = limit.coerceIn(1, 100)
        val rows = db.query("SELECT * FROM message WHERE session_id = ? ORDER BY created_at, rowid LIMIT ? OFFSET ?", id, n + 1, offset) { rs ->
            MessageRow(rs.getString("id"), rs.getString("role"), rs.getString("kind"), rs.str("author_user_id"), rs.getString("text"),
                rs.str("run_id"), rs.str("status"), rs.str("model_label"), rs.getString("created_at"))
        }
        val next = if (rows.size > n) Base64.getUrlEncoder().withoutPadding().encodeToString((offset + n).toString().toByteArray()) else null
        return Page(rows.take(n).map { m ->
            MessageOut(m.id, m.role, m.kind, m.author, m.text, m.runId, m.status,
                if (m.role == "assistant") toolCalls(m.id) else emptyList(),
                if (m.role == "assistant") clarifications(m.id) else emptyList(), m.modelLabel, m.createdAt)
        }, next)
    }

    fun out(c: ConversationRecord) = ChatSessionOut(
        id = c.id, agentId = c.agentId, title = c.title, ownerUserId = c.ownerUserId, modelId = c.modelId,
        modelLabel = c.modelId?.let(::modelLabel), lastMessagePreview = c.lastMessagePreview, activeRunId = activeRunId(c.id),
        sharedWith = ShareOut(c.sharedEveryone, participantIds(c.id)),
        usage = if (c.inputTokens + c.outputTokens > 0) SessionUsage(c.inputTokens, c.outputTokens, c.estimatedCostUsd) else null,
        createdAt = c.createdAt, updatedAt = c.updatedAt,
    )

    fun activeRunId(sessionId: String): String? = db.query(
        "SELECT id FROM run WHERE session_id = ? AND status IN ('queued', 'running', 'waiting_for_approval', 'waiting_for_input', 'stopping') LIMIT 1",
        sessionId) { it.getString(1) }.firstOrNull()

    /** Tell the owner's devices the conversation changed (title, preview, active run). */
    fun changed(id: String) {
        val c = record(id) ?: return
        events.publish(audience(c).map { it.userId }, "session.updated", mapOf("session_id" to c.id, "agent_id" to c.agentId, "title" to c.title,
            "last_message_preview" to c.lastMessagePreview, "active_run_id" to activeRunId(c.id)))
    }

    // ── agent-side cleanup ──────────────────────────────────────────────────

    private suspend fun deleteOnAgent(agentId: String, agentSessionId: String, scope: String): Boolean {
        val agent = agents.record(agentId) ?: return true   // agent removed: nothing left to delete
        val runs = agents.adapterFor(agent) as? AgentRuns ?: return true
        val target = agents.primaryTarget(agent) ?: return true
        return runs.deleteSession(target, agentSessionId, scope)
    }

    /** Retry agent-side deletes every minute (backing off to an hour). */
    fun startCleanup(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            runCatching { cleanupOnce() }.onFailure { log.warn("agent cleanup failed: {}", it.message) }
            runCatching { db.update("DELETE FROM activity WHERE at < ?", Instant.now().minus(90, ChronoUnit.DAYS).toString()) }   // F21: 90 days
            delay(60_000)
        }
    }

    suspend fun cleanupOnce() {
        val due = db.query("SELECT agent_id, agent_session_id, user_scope, attempts FROM agent_cleanup WHERE next_at <= ?", now()) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getInt(4).toString())
        }
        for ((agentId, sid, scope, attempts) in due) {
            if (deleteOnAgent(agentId, sid, scope)) db.update("DELETE FROM agent_cleanup WHERE agent_id = ? AND agent_session_id = ?", agentId, sid)
            else db.update("UPDATE agent_cleanup SET attempts = attempts + 1, next_at = ? WHERE agent_id = ? AND agent_session_id = ?",
                Instant.now().plus(minOf(60L, 1L shl minOf(attempts.toInt() + 1, 6)), ChronoUnit.MINUTES).toString(), agentId, sid)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private data class MessageRow(val id: String, val role: String, val kind: String, val author: String?, val text: String,
                                  val runId: String?, val status: String?, val modelLabel: String?, val createdAt: String)

    private fun toolCalls(messageId: String) = db.query(
        "SELECT * FROM tool_call WHERE message_id = ? ORDER BY position", messageId) { rs ->
        ToolCallOut(rs.getString("id"), rs.getString("tool"), rs.str("preview"), rs.getString("state"),
            rs.getDouble("duration_s").takeUnless { rs.wasNull() }, rs.str("output_excerpt"))
    }

    private fun clarifications(messageId: String) = db.query(
        "SELECT * FROM clarification WHERE message_id = ? ORDER BY created_at", messageId) { rs ->
        ClarificationOut(rs.getString("id"), rs.getString("question"),
            (hubJson.parseToJsonElement(rs.getString("choices_json")) as JsonArray).map { it.jsonPrimitive.content },
            rs.getInt("multi_select") == 1, rs.getString("status"),
            rs.str("answer_json")?.let { hubJson.parseToJsonElement(it) }, rs.str("answered_by"), rs.str("expires_at"))
    }

    private fun row(rs: java.sql.ResultSet) = ConversationRecord(
        rs.getString("id"), rs.getString("hub_id"), rs.getString("agent_id"), rs.getString("owner_user_id"), rs.getString("title"),
        rs.getInt("title_is_default") == 1, rs.str("model_id"), rs.getString("agent_session_id"), rs.str("last_message_preview"),
        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getDouble("estimated_cost_usd").takeUnless { rs.wasNull() },
        rs.getString("created_at"), rs.getString("updated_at"), rs.getInt("shared_everyone") == 1,
    )

    private fun setSharing(user: HubUser, c: ConversationRecord, agent: AgentRecord, share: ShareIn) {
        val ids = (share.userIds ?: emptyList()).distinct().filter { it != c.ownerUserId }
        val people = ids.map { id -> accounts.user(id)?.takeIf { it.hubId == user.hubId } ?: throw HubException.invalid("unknown member $id") }
        val lacking = people.filter { !permissions.access(it, agent).canChat }
        if (lacking.isNotEmpty()) throw HubException(HttpStatusCode.UnprocessableEntity, "forbidden", "Can't share with everyone",
            "${lacking.joinToString { it.displayName }} can't use ${agent.name}. The owner can grant access first.")
        db.tx { conn ->
            conn.prepareStatement("DELETE FROM session_participant WHERE session_id = ?").use { Db.bind(it, arrayOf<Any?>(c.id)); it.executeUpdate() }
            people.forEach { p -> conn.prepareStatement("INSERT INTO session_participant (session_id, user_id) VALUES (?, ?)").use {
                Db.bind(it, arrayOf<Any?>(c.id, p.userId)); it.executeUpdate() } }
            conn.prepareStatement("UPDATE chat_session SET shared_everyone = ? WHERE id = ?").use {
                Db.bind(it, arrayOf<Any?>(share.everyone == true, c.id)); it.executeUpdate() }
        }
    }

    /** The memory scope sent to the agent: the person's own, or the hub's shared one for a shared conversation. */
    fun memoryScope(c: ConversationRecord): String =
        if (c.sharedEveryone || participantIds(c.id).isNotEmpty()) "switchboard:hub:${c.hubId}" else scope(c.ownerUserId)

    private fun checkGranted(access: Access, modelId: String) {
        if (access.models != null && modelId !in access.models) throw HubException.forbidden("That model isn't granted to you on this agent.")
    }

    private fun checkModel(agent: AgentRecord, modelId: String) {
        if (modelId.isBlank() || modelId.length > 200) throw HubException.invalid("model_id: 1–200 characters")
        if (!agent.capabilities.modelSwitching) throw HubException(HttpStatusCode.UnprocessableEntity, "not_supported",
            "Model switching not supported", "This agent runs one model; choose a different agent for another model.")
    }

    companion object {
        const val DEFAULT_TITLE = "New conversation"

        /** The per-user memory scope sent to agents (Hermes `X-Hermes-Session-Key`). */
        fun scope(userId: String) = "switchboard:$userId"

        /** `openrouter::anthropic/claude-x` → `claude-x`. */
        fun modelLabel(modelId: String) = modelId.substringAfter("::").substringAfterLast('/')

    }
}
