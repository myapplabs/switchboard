package llc.applabs.switchboard.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class ApprovalOut(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("requested_by") val requestedBy: String,
    val command: String,
    val description: String?,
    val status: String,
    @SerialName("allowed_decisions") val allowedDecisions: List<String>,
    val decision: String? = null,
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable data class DecisionIn(val decision: String)

/** A stored approval request. [choices] are what the agent offered (once / session / always / deny). */
data class ApprovalRow(
    val id: String, val hubId: String, val runId: String, val sessionId: String, val agentId: String, val requestedBy: String,
    val agentRequestId: String?, val generation: Int?, val command: String, val description: String?, val choices: List<String>,
    val status: String, val decision: String?, val decidedBy: String?, val decidedAt: String?, val expiresAt: String?, val createdAt: String,
)

/**
 * Approvals (spec F12, approval routing): when an agent asks before running something risky, the hub
 * routes the request to the people who may decide (spec: Permission model):
 *
 * - the owner and admins — any request in the hub;
 * - a member — their own requests only; restricted members never decide (an adult does).
 *
 * "Always" changes the agent for everyone, so only the owner may choose it. Decisions are sent to the
 * agent by [Runs.decide]; this class stores, lists and checks.
 */
class Approvals(private val db: Db, private val accounts: Accounts) {

    fun create(hubId: String, runId: String, sessionId: String, agentId: String, requestedBy: String,
               agentRequestId: String?, generation: Int?, command: String, description: String?, choices: List<String>, expiresAt: String?): ApprovalRow {
        val id = newId("apr")
        db.update("""INSERT INTO approval (id, hub_id, run_id, session_id, agent_id, requested_by, agent_request_id, execution_generation,
                     command, description, choices_json, status, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)""",
            id, hubId, runId, sessionId, agentId, requestedBy, agentRequestId, generation, command, description,
            hubJson.encodeToString(ListSerializer(String.serializer()), choices), expiresAt, now())
        return get(id)!!
    }

    fun get(id: String): ApprovalRow? = db.query("SELECT * FROM approval WHERE id = ?", id) { row(it) }.firstOrNull()

    fun pendingFor(runId: String, agentRequestId: String?): ApprovalRow? = db.query(
        "SELECT * FROM approval WHERE run_id = ? AND status = 'pending' AND (? IS NULL OR agent_request_id = ?) ORDER BY created_at DESC LIMIT 1",
        runId, agentRequestId, agentRequestId) { row(it) }.firstOrNull()

    fun canDecide(user: HubUser, a: ApprovalRow): Boolean = when {
        a.hubId != user.hubId -> false
        user.isAdmin -> true
        user.role == Role.member -> a.requestedBy == user.userId
        else -> false
    }

    /** The decisions [user] may choose for [a] (contract ApprovalDecision), in the agent's order. */
    fun allowed(user: HubUser, a: ApprovalRow): List<String> {
        if (!canDecide(user, a) || a.status != "pending") return emptyList()
        return a.choices.mapNotNull { c ->
            when (c) {
                "once" -> "approve_once"
                "session" -> "approve_session"
                "always" -> "approve_always".takeIf { user.isOwner }
                "deny" -> "deny"
                else -> null
            }
        }
    }

    /** Who should be asked: the owner and admins, plus the requester when they are a member. */
    fun deciders(a: ApprovalRow): List<String> {
        val requester = accounts.user(a.requestedBy)
        return (accounts.admins(a.hubId) + listOfNotNull(a.requestedBy.takeIf { requester?.role == Role.member })).distinct()
    }

    /** Pending approvals [user] may decide plus their own requests; or recently decided ones. */
    fun list(user: HubUser, status: String): Items<ApprovalOut> {
        val rows = if (status == "decided")
            db.query("SELECT * FROM approval WHERE hub_id = ? AND status != 'pending' ORDER BY decided_at DESC LIMIT 100", user.hubId) { row(it) }
        else db.query("SELECT * FROM approval WHERE hub_id = ? AND status = 'pending' ORDER BY created_at", user.hubId) { row(it) }
        return Items(rows.filter { canDecide(user, it) || it.requestedBy == user.userId }.map { out(user, it) })
    }

    /** Record a decision (or an outcome on the agent's side); false if it was no longer pending. */
    fun record(id: String, status: String, decision: String?, decidedBy: String?): Boolean =
        db.update("UPDATE approval SET status = ?, decision = ?, decided_by = ?, decided_at = ? WHERE id = ? AND status = 'pending'",
            status, decision, decidedBy, now(), id) == 1

    fun expireRun(runId: String): List<ApprovalRow> {
        val open = db.query("SELECT * FROM approval WHERE run_id = ? AND status = 'pending'", runId) { row(it) }
        open.forEach { record(it.id, "expired", null, null) }
        return open
    }

    fun out(user: HubUser, a: ApprovalRow) = ApprovalOut(a.id, a.runId, a.sessionId, a.agentId, a.requestedBy, a.command, a.description,
        a.status, allowed(user, a), a.decision, a.decidedBy, a.decidedAt, a.expiresAt, a.createdAt)

    private fun row(rs: java.sql.ResultSet) = ApprovalRow(
        rs.getString("id"), rs.getString("hub_id"), rs.getString("run_id"), rs.getString("session_id"), rs.getString("agent_id"),
        rs.getString("requested_by"), rs.str("agent_request_id"), rs.int("execution_generation"), rs.getString("command"), rs.str("description"),
        hubJson.decodeFromString(ListSerializer(String.serializer()), rs.getString("choices_json")), rs.getString("status"), rs.str("decision"),
        rs.str("decided_by"), rs.str("decided_at"), rs.str("expires_at"), rs.getString("created_at"),
    )

    companion object {
        /** Contract decision → the agent's choice. */
        fun choice(decision: String) = when (decision) {
            "approve_once" -> "once"; "approve_session" -> "session"; "approve_always" -> "always"; "deny" -> "deny"
            else -> throw HubException.invalid("decision: approve_once, approve_session, approve_always or deny")
        }
        /** The agent's choice → contract decision. */
        fun decision(choice: String?) = when (choice) { "once" -> "approve_once"; "session" -> "approve_session"; "always" -> "approve_always"; else -> "deny" }
    }
}
