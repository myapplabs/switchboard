package llc.applabs.switchboard.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuickCommandOut(val id: String, val label: String, val prompt: String, @SerialName("agent_id") val agentId: String?, val scope: String)

@Serializable
data class QuickCommandIn(val label: String, val prompt: String, @SerialName("agent_id") val agentId: String? = null, val scope: String = "personal")

/** Quick commands (prompt templates): personal ones, and shared ones that owner and admins keep for everyone. */
class QuickCommands(private val db: Db, private val agents: Agents) {

    fun list(user: HubUser): Items<QuickCommandOut> = Items(db.query(
        """SELECT * FROM quick_command WHERE hub_id = ? AND (user_id IS NULL OR user_id = ?) ORDER BY user_id IS NOT NULL, position, created_at""",
        user.hubId, user.userId) { row(it) })

    fun create(user: HubUser, input: QuickCommandIn): QuickCommandOut {
        validate(user, input)
        val id = newId("qc")
        db.update("INSERT INTO quick_command (id, hub_id, user_id, label, prompt, agent_id, position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, user.hubId, if (input.scope == EVERYONE) null else user.userId, input.label.trim(), input.prompt, input.agentId,
            list(user).items.size, now())
        return get(user, id)
    }

    fun update(user: HubUser, id: String, input: QuickCommandIn): QuickCommandOut {
        val existing = editable(user, id)
        validate(user, input)
        db.update("UPDATE quick_command SET label = ?, prompt = ?, agent_id = ?, user_id = ? WHERE id = ?",
            input.label.trim(), input.prompt, input.agentId, if (input.scope == EVERYONE) null else user.userId, existing.id)
        return get(user, id)
    }

    fun delete(user: HubUser, id: String) { editable(user, id); db.update("DELETE FROM quick_command WHERE id = ?", id) }

    private fun get(user: HubUser, id: String) = list(user).items.firstOrNull { it.id == id } ?: throw HubException.notFound("quick command")

    private fun editable(user: HubUser, id: String): QuickCommandOut {
        val c = get(user, id)
        if (c.scope == EVERYONE && !user.isAdmin) throw HubException.forbidden("Commands for everyone are kept by the owner and admins.")
        return c
    }

    private fun validate(user: HubUser, i: QuickCommandIn) {
        if (i.label.isBlank() || i.label.length > 40) throw HubException.invalid("label: 1–40 characters")
        if (i.prompt.isBlank() || i.prompt.length > 5000) throw HubException.invalid("prompt: 1–5000 characters")
        if (i.scope !in setOf("personal", EVERYONE)) throw HubException.invalid("scope: personal or everyone")
        if (i.scope == EVERYONE && !user.isAdmin) throw HubException.forbidden("Only the owner and admins add commands for everyone.")
        i.agentId?.let { id -> if (agents.record(id)?.hubId != user.hubId) throw HubException.invalid("unknown agent") }
    }

    private fun row(rs: java.sql.ResultSet) = QuickCommandOut(rs.getString("id"), rs.getString("label"), rs.getString("prompt"),
        rs.str("agent_id"), if (rs.str("user_id") == null) EVERYONE else "personal")
}

/** Quick commands for every member (contract 0.3.0; was "household"). */
private const val EVERYONE = "everyone"
